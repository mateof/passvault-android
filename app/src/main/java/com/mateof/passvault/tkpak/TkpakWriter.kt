package com.mateof.passvault.tkpak

import com.mateof.passvault.crypto.Base64Url
import com.mateof.passvault.crypto.Primitives
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Writing a `.tkpak`.
 *
 * The phone is the primary writer: the whole point of the format is that two people can hand each
 * other tickets with no server involved, so this path has to work with no network and no account.
 *
 * Symmetry with the reader is not assumed. `TkpakWriterTest` writes a file and reads it back, and
 * the file this produces is also opened by the server's TypeScript reader — a round trip inside one
 * implementation would prove only that it is self-consistent.
 */
object TkpakWriter {
    private val json = Json { encodeDefaults = false; explicitNulls = false }

    /** Production defaults: OWASP 2024 guidance, and comfortable on a mid-range phone. */
    const val DEFAULT_MEMORY_KIB = 65_536
    const val DEFAULT_ITERATIONS = 3
    const val DEFAULT_PARALLELISM = 1

    data class Issuer(
        val deviceId: String,
        /** Ed25519 private key. The public half is derived, so a device stores only this. */
        val privateKey: ByteArray,
        val displayName: String? = null,
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    enum class PreviewMode { FULL, MINIMAL, NONE }

    data class Input(
        val issuer: Issuer,
        val bundle: TkpakBundle,
        val documents: List<TkpakDocument> = emptyList(),
        /** Produces an argon2id slot. Required unless a recipient key is given. */
        val password: String? = null,
        /** X25519 public key of the recipient. Produces an x25519-sealed slot. */
        val recipientPublicKey: ByteArray? = null,
        val preview: PreviewMode = PreviewMode.FULL,
        val memoryKiB: Int = DEFAULT_MEMORY_KIB,
        val iterations: Int = DEFAULT_ITERATIONS,
        val parallelism: Int = DEFAULT_PARALLELISM,
    )

    fun write(input: Input): ByteArray {
        if (input.password == null && input.recipientPublicKey == null) {
            throw TkpakException(
                TkpakError.NO_USABLE_KEY_SLOT,
                "a file needs a password, a recipient key, or both; writing one nobody can open is never intended",
            )
        }

        val fileId = input.bundle.fileId
        // One file key encrypts everything. Each slot wraps that same key under a different
        // key-encryption key, which is what lets one file be opened by a password *and* by a named
        // recipient without storing the ciphertext twice.
        val fileKey = Primitives.randomKey()

        val payloadNonce = Primitives.randomNonce()
        val payloadCiphertext = Primitives.seal(
            fileKey,
            payloadNonce,
            json.encodeToString(input.bundle).toByteArray(Charsets.UTF_8),
            "tkpak/v1/payload:$fileId",
        )

        val blobs = LinkedHashMap<String, ByteArray>()
        val blobEntries = input.documents.map { document ->
            val nonce = Primitives.randomNonce()
            val ciphertext = Primitives.seal(
                fileKey,
                nonce,
                document.bytes,
                "tkpak/v1/blob:$fileId:${document.id}",
            )
            blobs[document.id] = ciphertext
            TkpakBlobEntry(
                id = document.id,
                nonce = Base64Url.encode(nonce),
                sha256 = Base64Url.encode(Primitives.sha256(ciphertext)),
                byteLength = ciphertext.size,
                mediaType = document.mediaType,
            )
        }

        val slots = buildList {
            input.password?.let {
                add(passwordSlot(fileId, fileKey, it, input.memoryKiB, input.iterations, input.parallelism))
            }
            input.recipientPublicKey?.let { add(sealedSlot(fileId, fileKey, it)) }
        }

        val manifest = TkpakManifest(
            format = "tkpak",
            version = 1,
            fileId = fileId,
            createdAt = input.bundle.exportedAt,
            issuer = TkpakIssuer(
                deviceId = input.issuer.deviceId,
                publicKey = Base64Url.encode(publicKeyOf(input.issuer.privateKey)),
                displayName = input.issuer.displayName,
            ),
            keySlots = slots,
            payload = TkpakPart(
                nonce = Base64Url.encode(payloadNonce),
                sha256 = Base64Url.encode(Primitives.sha256(payloadCiphertext)),
                byteLength = payloadCiphertext.size,
            ),
            blobs = blobEntries,
            preview = previewOf(input),
        )

        // Serialise once and reuse. The bytes written are the bytes signed, so no reformatting can
        // slip in between signing and storing.
        val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
        val signature = sign(
            input.issuer.privateKey,
            Primitives.domainSeparated("tkpak/v1/manifest", Primitives.sha256(manifestBytes)),
        )

        return pack(manifestBytes, payloadCiphertext, blobs, signature)
    }

    private fun passwordSlot(
        fileId: String,
        fileKey: ByteArray,
        password: String,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int,
    ): TkpakKeySlot {
        val salt = Primitives.randomBytes(16)
        val kek = Primitives.deriveKey(password, salt, memoryKiB, iterations, parallelism)
        val nonce = Primitives.randomNonce()
        return TkpakKeySlot(
            kind = "argon2id",
            salt = Base64Url.encode(salt),
            memoryKiB = memoryKiB,
            iterations = iterations,
            parallelism = parallelism,
            wrapNonce = Base64Url.encode(nonce),
            wrappedFileKey = Base64Url.encode(
                Primitives.seal(kek, nonce, fileKey, "tkpak/v1/filekey:$fileId"),
            ),
        )
    }

    private fun sealedSlot(
        fileId: String,
        fileKey: ByteArray,
        recipientPublicKey: ByteArray,
    ): TkpakKeySlot {
        // A fresh ephemeral pair per file, and the private half is never retained: the sender
        // cannot reopen this slot afterwards, only the recipient can.
        val ephemeralPrivate = X25519PrivateKeyParameters(Primitives.randomBytes(32), 0)
        val ephemeralPublic = ephemeralPrivate.generatePublicKey().encoded
        val kek = Primitives.sealedSlotKey(
            Primitives.agree(ephemeralPrivate.encoded, recipientPublicKey),
            ephemeralPublic,
            recipientPublicKey,
        )
        val nonce = Primitives.randomNonce()
        return TkpakKeySlot(
            kind = "x25519-sealed",
            recipientPublicKey = Base64Url.encode(recipientPublicKey),
            ephemeralPublicKey = Base64Url.encode(ephemeralPublic),
            wrapNonce = Base64Url.encode(nonce),
            wrappedFileKey = Base64Url.encode(
                Primitives.seal(kek, nonce, fileKey, "tkpak/v1/filekey:$fileId"),
            ),
        )
    }

    /**
     * How much of the event goes in the clear.
     *
     * FULL by default: a recipient who cannot tell which of three forwarded files is the right one
     * is a real usability failure. MINIMAL is for anyone who would rather their messaging provider
     * not learn which concerts they attend. Never a barcode, a holder name or an amount.
     */
    private fun previewOf(input: Input): TkpakPreview? = when (input.preview) {
        PreviewMode.NONE -> null
        PreviewMode.MINIMAL -> TkpakPreview(ticketCount = input.bundle.tickets.size)
        PreviewMode.FULL -> TkpakPreview(
            ticketCount = input.bundle.tickets.size,
            eventName = input.bundle.event.name,
            eventStartsAt = input.bundle.event.startsAt,
            venue = input.bundle.event.venue,
        )
    }

    private fun publicKeyOf(privateKey: ByteArray): ByteArray =
        Ed25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded

    private fun sign(privateKey: ByteArray, message: ByteArray): ByteArray =
        Ed25519Signer().apply {
            init(true, Ed25519PrivateKeyParameters(privateKey, 0))
            update(message, 0, message.size)
        }.generateSignature()

    private fun pack(
        manifestBytes: ByteArray,
        payload: ByteArray,
        blobs: Map<String, ByteArray>,
        signature: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifestBytes)
            zip.closeEntry()

            // Ciphertext is incompressible, so deflating it costs time and saves nothing. The
            // manifest is the one part worth compressing.
            zip.setLevel(Deflater.NO_COMPRESSION)
            zip.putNextEntry(ZipEntry("payload.bin"))
            zip.write(payload)
            zip.closeEntry()

            for ((blobId, bytes) in blobs) {
                zip.putNextEntry(ZipEntry("blobs/$blobId.bin"))
                zip.write(bytes)
                zip.closeEntry()
            }

            zip.putNextEntry(ZipEntry("signature.bin"))
            zip.write(signature)
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /** A device's signing identity. Generated once and kept in the Android KeyStore-backed store. */
    fun generateSigningKey(): ByteArray = Primitives.randomBytes(32)

    fun signingPublicKey(privateKey: ByteArray): ByteArray = publicKeyOf(privateKey)
}
