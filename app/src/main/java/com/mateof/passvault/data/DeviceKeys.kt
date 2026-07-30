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

    override fun vaultKey(): ByteArray = cached

    private fun loadOrCreate(): ByteArray {
        val stored = preferences.getString(WRAPPED_KEY, null)
        if (stored != null) {
            return unwrap(Base64Url.decode(stored))
        }
        val vaultKey = Primitives.randomKey()
        preferences.edit().putString(WRAPPED_KEY, Base64Url.encode(wrap(vaultKey))).apply()
        return vaultKey
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
    }
}

/** For tests. Holds the key in memory and never touches the platform. */
class InMemoryDeviceKeys(private val key: ByteArray = Primitives.randomKey()) : DeviceKeys {
    override fun vaultKey(): ByteArray = key
}
