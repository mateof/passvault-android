package com.mateof.passvault.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.mateof.passvault.crypto.Base64Url
import com.mateof.passvault.crypto.Primitives
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the key that encrypts the wallet comes from.
 *
 * Behind an interface for the same reason as the PDF rasterizer: the Android KeyStore does not
 * exist in a JVM unit test, and encryption logic that can only be exercised on a device is
 * encryption logic that does not get exercised. The device implementation is a thin adapter; the
 * interesting part is tested against the in-memory one.
 */
interface DeviceKeys {
    /** The key wallet fields are encrypted with. Stable across launches. */
    fun vaultKey(): ByteArray

    /** Who this device is when it signs an operation or pairs with another phone. */
    fun identity(): DeviceIdentity

    /**
     * Seals a short secret so it can be written to ordinary storage.
     *
     * For things that are neither wallet data nor identity — a server session token — which need
     * to survive a restart and must not be readable by anything that gets at the app's files.
     * The same KeyStore key that wraps the vault key does it, so the sealed value is useless on
     * another device and useless here to anything that cannot ask the KeyStore.
     */
    fun seal(secret: String): String

    /** Null when it was written by a different KeyStore key, which is what a reinstall leaves. */
    fun open(sealed: String): String?
}

/**
 * This device's identity in the operation log.
 *
 * Two keys with different jobs, kept apart for the same reason the vault passphrase is not the login
 * password: one proves who wrote something and the other establishes a shared secret, and a key that
 * does both is a key whose compromise costs twice.
 *
 * The signing key is what every operation this device produces is signed with, and what every other
 * participant verifies against. The agreement key is what local pairing and sealed exports use.
 * `deviceId` is the name both are registered under, and it never changes: rotating it would orphan
 * every operation this device has already signed.
 */
class DeviceIdentity(
    val deviceId: String,
    val signingPrivateKey: ByteArray,
    val agreementPrivateKey: ByteArray,
) {
    val signingPublicKey: ByteArray by lazy { Primitives.ed25519PublicKey(signingPrivateKey) }
    val agreementPublicKey: ByteArray by lazy { Primitives.x25519PublicKey(agreementPrivateKey) }

    /** Signs the already domain-separated input an operation is signed over. */
    fun sign(signingInput: ByteArray): ByteArray =
        Primitives.signEd25519(signingPrivateKey, signingInput)

    companion object {
        fun generate(deviceId: String = Ids.newId()): DeviceIdentity = DeviceIdentity(
            deviceId = deviceId,
            signingPrivateKey = Primitives.randomBytes(32),
            agreementPrivateKey = Primitives.randomBytes(32),
        )
    }
}

/**
 * Two tiers, mirroring the server.
 *
 * A random vault key encrypts the data. That key is itself wrapped by an AES key held in the
 * Android KeyStore, which is hardware-backed where the device offers it and cannot be exported at
 * all — so the wrapped key sitting in preferences is useless on any other device, and useless on
 * this one to anything that cannot ask the KeyStore.
 *
 * The indirection is what makes rotation possible: re-wrapping the vault key re-keys the app
 * without touching a single stored row.
 */
class KeyStoreDeviceKeys(context: Context) : DeviceKeys {
    private val preferences = context.getSharedPreferences("passvault.keys", Context.MODE_PRIVATE)

    private val cached: ByteArray by lazy { loadOrCreate() }

    private val cachedIdentity: DeviceIdentity by lazy { loadOrCreateIdentity() }

    override fun vaultKey(): ByteArray = cached

    override fun identity(): DeviceIdentity = cachedIdentity

    override fun seal(secret: String): String =
        Base64Url.encode(wrap(secret.toByteArray(Charsets.UTF_8)))

    override fun open(sealed: String): String? = runCatching {
        unwrap(Base64Url.decode(sealed)).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun loadOrCreate(): ByteArray {
        val stored = preferences.getString(WRAPPED_KEY, null)
        if (stored != null) {
            return unwrap(Base64Url.decode(stored))
        }
        val vaultKey = Primitives.randomKey()
        preferences.edit().putString(WRAPPED_KEY, Base64Url.encode(wrap(vaultKey))).apply()
        return vaultKey
    }

    /**
     * The device identity, created once and then never regenerated.
     *
     * Both private keys are wrapped by the same KeyStore key that protects the vault key, so they
     * are useless on another device and useless here to anything that cannot ask the KeyStore. The
     * two are stored as one blob rather than two entries: a device that had a signing key and no
     * agreement key, because the second write failed, would be a device that can sign operations
     * nobody can pair with.
     */
    private fun loadOrCreateIdentity(): DeviceIdentity {
        val storedId = preferences.getString(DEVICE_ID, null)
        val storedKeys = preferences.getString(WRAPPED_IDENTITY, null)
        if (storedId != null && storedKeys != null) {
            val keys = unwrap(Base64Url.decode(storedKeys))
            return DeviceIdentity(
                deviceId = storedId,
                signingPrivateKey = keys.copyOfRange(0, 32),
                agreementPrivateKey = keys.copyOfRange(32, 64),
            )
        }
        val identity = DeviceIdentity.generate()
        preferences.edit()
            .putString(DEVICE_ID, identity.deviceId)
            .putString(
                WRAPPED_IDENTITY,
                Base64Url.encode(wrap(identity.signingPrivateKey + identity.agreementPrivateKey)),
            )
            .apply()
        return identity
    }

    private fun keyStoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Deliberately not requiring user authentication for this key. The wallet has to be
                // readable to answer a share intent or show a reminder, and the app lock is a
                // separate control that gates the interface rather than the storage.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun wrap(vaultKey: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyStoreKey())
        // The KeyStore chooses the IV for GCM, so it has to be stored alongside the ciphertext.
        return cipher.iv + cipher.doFinal(vaultKey)
    }

    private fun unwrap(stored: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyStoreKey(), GCMParameterSpec(128, stored, 0, IV_BYTES))
        return cipher.doFinal(stored, IV_BYTES, stored.size - IV_BYTES)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "passvault.vault"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val WRAPPED_KEY = "wrapped_vault_key"
        const val WRAPPED_IDENTITY = "wrapped_device_identity"
        const val DEVICE_ID = "device_id"
    }
}

/** For tests. Holds the keys in memory and never touches the platform. */
class InMemoryDeviceKeys(
    private val key: ByteArray = Primitives.randomKey(),
    private val identity: DeviceIdentity = DeviceIdentity.generate(),
) : DeviceKeys {
    override fun vaultKey(): ByteArray = key

    override fun identity(): DeviceIdentity = identity

    // Not encryption, and not pretending to be: a test double for storage that a test can read
    // back. The real one goes through the KeyStore.
    override fun seal(secret: String): String = Base64Url.encode(secret.toByteArray(Charsets.UTF_8))

    override fun open(sealed: String): String? =
        runCatching { Base64Url.decode(sealed).toString(Charsets.UTF_8) }.getOrNull()
}
