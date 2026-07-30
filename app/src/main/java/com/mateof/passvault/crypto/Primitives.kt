package com.mateof.passvault.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * The primitives the interchange format needs, mirroring `packages/crypto` on the server.
 *
 * AES-GCM comes from the platform, which has hardware acceleration for it. Argon2id, Ed25519 and
 * X25519 come from Bouncy Castle's lightweight API: none of the three is available through
 * `javax.crypto` at minSdk 26, and the lightweight API needs no provider registration and no JNI,
 * so this same code runs in a JVM unit test and on a phone.
 *
 * Everything here has a counterpart in the TypeScript implementation. The reference vectors in
 * `TkpakVectorTest` are what prove the two agree; these functions are only useful if they do.
 */
object Primitives {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BITS = 128

    private val random = SecureRandom()

    fun randomBytes(length: Int): ByteArray = ByteArray(length).also(random::nextBytes)

    fun randomKey(): ByteArray = randomBytes(KEY_BYTES)

    fun randomNonce(): ByteArray = randomBytes(NONCE_BYTES)

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** AES-256-GCM. Returns ciphertext with the tag appended, which is how every part is stored. */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: String): ByteArray {
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes" }
        require(nonce.size == NONCE_BYTES) { "nonce must be $NONCE_BYTES bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(plaintext)
    }

    /**
     * Opens a sealed part.
     *
     * Throws [AeadFailure] rather than the platform's own exception, because the caller has to
     * distinguish a wrong password from a tampered file and the platform reports both identically.
     */
    fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: String): ByteArray {
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes" }
        require(nonce.size == NONCE_BYTES) { "nonce must be $NONCE_BYTES bytes" }
        if (ciphertext.size < TAG_BITS / 8) {
            throw AeadFailure("ciphertext is shorter than the authentication tag")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return try {
            cipher.doFinal(ciphertext)
        } catch (cause: Exception) {
            throw AeadFailure("authentication tag did not verify", cause)
        }
    }

    /**
     * Argon2id.
     *
     * Parameters are read from the file rather than fixed here, so a manifest written with stronger
     * settings still opens. Normalising the secret to NFC is not cosmetic: the same accented
     * password typed on an Android keyboard and on a desktop otherwise produces different bytes for
     * what the user sees as identical text, and the file would not be portable.
     */
    fun deriveKey(
        secret: String,
        salt: ByteArray,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int,
    ): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(memoryKiB)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .build()
        val generator = Argon2BytesGenerator().apply { init(parameters) }
        val out = ByteArray(KEY_BYTES)
        generator.generateBytes(normalise(secret).toByteArray(Charsets.UTF_8), out)
        return out
    }

    fun normalise(secret: String): String = Normalizer.normalize(secret, Normalizer.Form.NFC)

    fun verifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) {
            return false
        }
        return try {
            val signer = Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(publicKey, 0))
                update(message, 0, message.size)
            }
            signer.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Prefixes a message with a domain string before signing or verifying.
     *
     * Without it a signature made for one purpose could be replayed as valid for another. The
     * separator is a zero byte so no domain string can be a prefix of another.
     */
    fun domainSeparated(domain: String, digest: ByteArray): ByteArray =
        domain.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + digest

    fun agree(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKey, 0))
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPublicKey, 0), shared, 0)
        return shared
    }

    fun hkdf(inputKey: ByteArray, salt: ByteArray, info: String, length: Int = KEY_BYTES): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(inputKey, salt, info.toByteArray(Charsets.UTF_8)))
        return ByteArray(length).also { generator.generateBytes(it, 0, it.size) }
    }

    /** The key-encryption key of a `.tkpak` `x25519-sealed` slot. */
    fun sealedSlotKey(
        sharedSecret: ByteArray,
        ephemeralPublicKey: ByteArray,
        recipientPublicKey: ByteArray,
    ): ByteArray = hkdf(sharedSecret, ephemeralPublicKey + recipientPublicKey, "tkpak/v1/seal")
}

class AeadFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Base64url without padding, the only binary form the interchange format uses.
 *
 * Standard base64 and padded input are rejected rather than quietly accepted: the signature covers
 * the exact manifest bytes, so a reader that tolerated several encodings of the same value would
 * let a file round-trip into different bytes than the ones that were signed.
 */
object Base64Url {
    private val ALPHABET = Regex("^[A-Za-z0-9_-]*$")
    private val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
    private val decoder = java.util.Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    fun decode(text: String): ByteArray {
        require(ALPHABET.matches(text)) { "value is not unpadded base64url" }
        return decoder.decode(text)
    }

    fun decodeExact(text: String, expectedBytes: Int): ByteArray {
        val bytes = decode(text)
        require(bytes.size == expectedBytes) { "expected $expectedBytes bytes, got ${bytes.size}" }
        return bytes
    }
}
