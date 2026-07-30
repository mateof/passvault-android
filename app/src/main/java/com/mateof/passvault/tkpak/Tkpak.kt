package com.mateof.passvault.tkpak

import com.mateof.passvault.crypto.AeadFailure
import com.mateof.passvault.crypto.Base64Url
import com.mateof.passvault.crypto.Primitives
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The `.tkpak` interchange format, version 1.
 *
 * A second, independent implementation of the format specified in the server repository at
 * `docs/spec/tkpak-v1.md`. Two implementations of one format drift; the reference vectors in
 * `app/src/test/resources/vectors` are what stops that being discovered by a user whose friend
 * sent them a file the app cannot open.
 */
enum class TkpakError {
    NOT_A_TKPAK,
    UNSUPPORTED_VERSION,
    MALFORMED_MANIFEST,
    LIMIT_EXCEEDED,
    BAD_SIGNATURE,
    UNKNOWN_ISSUER,
    DIGEST_MISMATCH,
    WRONG_PASSWORD,
    NO_USABLE_KEY_SLOT,
    DECRYPTION_FAILED,
    FILE_ID_MISMATCH,
}

class TkpakException(val code: TkpakError, message: String, cause: Throwable? = null) :
    Exception(message, cause)

private const val MANIFEST_ENTRY = "manifest.json"
private const val PAYLOAD_ENTRY = "payload.bin"
private const val SIGNATURE_ENTRY = "signature.bin"
private const val BLOB_PREFIX = "blobs/"
private const val MANIFEST_DOMAIN = "tkpak/v1/manifest"

/** Mirrors the limits in the specification. A ZIP is attacker-controlled input. */
private object Limits {
    const val MANIFEST_BYTES = 1024 * 1024
    const val PAYLOAD_BYTES = 8 * 1024 * 1024
    const val BLOB_BYTES = 32 * 1024 * 1024
    const val BLOB_COUNT = 512
    const val TOTAL_BYTES = 512L * 1024 * 1024
    const val MAX_MEMORY_KIB = 1_048_576
    const val MAX_ITERATIONS = 16
    const val MAX_PARALLELISM = 16
}

@Serializable
data class TkpakIssuer(
    val deviceId: String,
    val publicKey: String,
    val displayName: String? = null,
)

@Serializable
data class TkpakKeySlot(
    val kind: String,
    val salt: String? = null,
    val memoryKiB: Int? = null,
    val iterations: Int? = null,
    val parallelism: Int? = null,
    val recipientPublicKey: String? = null,
    val ephemeralPublicKey: String? = null,
    val wrapNonce: String,
    val wrappedFileKey: String,
)

@Serializable
data class TkpakPart(val nonce: String, val sha256: String, val byteLength: Int)

@Serializable
data class TkpakBlobEntry(
    val id: String,
    val nonce: String,
    val sha256: String,
    val byteLength: Int,
    val mediaType: String,
)

@Serializable
data class TkpakPreview(
    val ticketCount: Int,
    val eventName: String? = null,
    val eventStartsAt: String? = null,
    val venue: String? = null,
)

@Serializable
data class TkpakManifest(
    val format: String,
    val version: Int,
    val fileId: String,
    val createdAt: String,
    val issuer: TkpakIssuer,
    val keySlots: List<TkpakKeySlot>,
    val payload: TkpakPart,
    val blobs: List<TkpakBlobEntry> = emptyList(),
    val preview: TkpakPreview? = null,
)

@Serializable
data class TkpakBarcode(val format: String, val value: String)

@Serializable
data class TkpakAssignment(
    val state: String,
    val holderLabel: String? = null,
    val holderUserId: String? = null,
    val assignedAt: String? = null,
)

@Serializable
data class TkpakPayment(
    val state: String,
    val amountCents: Int? = null,
    val currency: String? = null,
    val visibility: String,
    val settledAt: String? = null,
)

@Serializable
data class TkpakTicket(
    val id: String,
    val label: String? = null,
    val section: String? = null,
    val row: String? = null,
    val seat: String? = null,
    val barcode: TkpakBarcode? = null,
    val documentBlobId: String? = null,
    val documentPage: Int? = null,
    val assignmentMode: String,
    val assignment: TkpakAssignment,
    val payment: TkpakPayment? = null,
)

@Serializable
data class TkpakEvent(
    val id: String,
    val name: String,
    val venue: String? = null,
    val startsAt: String? = null,
    val timeZone: String? = null,
    val notes: String? = null,
    val defaultAssignmentMode: String,
    val passwordProtected: Boolean,
)

@Serializable
data class TkpakBundle(
    val fileId: String,
    val exportedAt: String,
    val exportedFor: String? = null,
    val event: TkpakEvent,
    val tickets: List<TkpakTicket>,
    @SerialName("operations") val operations: List<JsonObject> = emptyList(),
)

data class TkpakDocument(val id: String, val mediaType: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is TkpakDocument && id == other.id && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * id.hashCode() + bytes.contentHashCode()
}

data class TkpakInspection(
    val manifest: TkpakManifest,
    val signatureValid: Boolean,
    val issuerPublicKey: ByteArray,
    val canOpenWithPassword: Boolean,
    val sealedFor: List<String>,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

data class OpenedTkpak(
    val manifest: TkpakManifest,
    val bundle: TkpakBundle,
    val documents: Map<String, TkpakDocument>,
    val issuerPublicKey: ByteArray,
    val signatureValid: Boolean,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

private class Parts(
    val manifestBytes: ByteArray,
    val payload: ByteArray,
    val signature: ByteArray,
    val blobs: Map<String, ByteArray>,
)

object Tkpak {
    private val json = Json { ignoreUnknownKeys = true }

    private fun aadFileKey(fileId: String) = "tkpak/v1/filekey:$fileId"
    private fun aadPayload(fileId: String) = "tkpak/v1/payload:$fileId"
    private fun aadBlob(fileId: String, blobId: String) = "tkpak/v1/blob:$fileId:$blobId"

    /**
     * Reads what a file claims without needing any key, so the app can say "four tickets for
     * Festival do Norte, needs a password" before asking for one.
     */
    fun inspect(archive: ByteArray): TkpakInspection {
        val parts = unpack(archive)
        val manifest = parseManifest(parts.manifestBytes)
        val issuerPublicKey = Base64Url.decodeExact(manifest.issuer.publicKey, 32)
        return TkpakInspection(
            manifest = manifest,
            signatureValid = verifySignature(parts, issuerPublicKey),
            issuerPublicKey = issuerPublicKey,
            canOpenWithPassword = manifest.keySlots.any { it.kind == "argon2id" },
            sealedFor = manifest.keySlots.filter { it.kind == "x25519-sealed" }
                .mapNotNull { it.recipientPublicKey },
        )
    }

    fun openWithPassword(
        archive: ByteArray,
        password: String,
        requireValidSignature: Boolean = true,
    ): OpenedTkpak {
        val verified = verify(archive, requireValidSignature)
        val slots = verified.manifest.keySlots.filter { it.kind == "argon2id" }
        if (slots.isEmpty()) {
            throw TkpakException(TkpakError.NO_USABLE_KEY_SLOT, "file has no password slot")
        }
        for (slot in slots) {
            val memory = slot.memoryKiB ?: 0
            val iterations = slot.iterations ?: 0
            val parallelism = slot.parallelism ?: 0
            // Enforced before deriving, not after: the point is to refuse to allocate the memory a
            // hostile file asked for.
            if (memory > Limits.MAX_MEMORY_KIB ||
                iterations > Limits.MAX_ITERATIONS ||
                parallelism > Limits.MAX_PARALLELISM
            ) {
                throw TkpakException(TkpakError.LIMIT_EXCEEDED, "Argon2 parameters exceed the limit")
            }
            if (memory < 8 || iterations < 1 || parallelism < 1) {
                throw TkpakException(TkpakError.MALFORMED_MANIFEST, "Argon2 parameters are too low")
            }
            val salt = slot.salt?.let(Base64Url::decode)
                ?: throw TkpakException(TkpakError.MALFORMED_MANIFEST, "slot has no salt")
            val kek = Primitives.deriveKey(password, salt, memory, iterations, parallelism)
            val fileKey = tryUnwrap(kek, slot, verified.manifest.fileId)
            if (fileKey != null) {
                return decrypt(verified, fileKey)
            }
        }
        throw TkpakException(TkpakError.WRONG_PASSWORD, "no password slot opened")
    }

    fun openWithRecipientKey(
        archive: ByteArray,
        recipientPrivateKey: ByteArray,
        recipientPublicKey: ByteArray,
        requireValidSignature: Boolean = true,
    ): OpenedTkpak {
        val verified = verify(archive, requireValidSignature)
        val encoded = Base64Url.encode(recipientPublicKey)
        val slot = verified.manifest.keySlots.firstOrNull {
            it.kind == "x25519-sealed" && it.recipientPublicKey == encoded
        } ?: throw TkpakException(
            TkpakError.NO_USABLE_KEY_SLOT,
            "file is not sealed to this recipient key",
        )
        val ephemeral = Base64Url.decodeExact(
            slot.ephemeralPublicKey ?: throw TkpakException(
                TkpakError.MALFORMED_MANIFEST,
                "sealed slot has no ephemeral key",
            ),
            32,
        )
        val kek = Primitives.sealedSlotKey(
            Primitives.agree(recipientPrivateKey, ephemeral),
            ephemeral,
            recipientPublicKey,
        )
        val fileKey = tryUnwrap(kek, slot, verified.manifest.fileId)
            // The slot named this key, so failing to unwrap is not a wrong-key case: it was altered.
            ?: throw TkpakException(
                TkpakError.DECRYPTION_FAILED,
                "the slot addressed to this key did not unwrap",
            )
        return decrypt(verified, fileKey)
    }

    private class Verified(
        val parts: Parts,
        val manifest: TkpakManifest,
        val issuerPublicKey: ByteArray,
        val signatureValid: Boolean,
    )

    private fun verify(archive: ByteArray, requireValidSignature: Boolean): Verified {
        val parts = unpack(archive)
        val manifest = parseManifest(parts.manifestBytes)
        val issuerPublicKey = Base64Url.decodeExact(manifest.issuer.publicKey, 32)
        val signatureValid = verifySignature(parts, issuerPublicKey)
        if (!signatureValid && requireValidSignature) {
            throw TkpakException(TkpakError.BAD_SIGNATURE, "signature does not verify")
        }

        assertDigest(parts.payload, manifest.payload.sha256, manifest.payload.byteLength, PAYLOAD_ENTRY)
        for (blob in manifest.blobs) {
            val bytes = parts.blobs[blob.id] ?: throw TkpakException(
                TkpakError.MALFORMED_MANIFEST,
                "manifest lists blob ${blob.id} but the archive does not contain it",
            )
            assertDigest(bytes, blob.sha256, blob.byteLength, "blobs/${blob.id}.bin")
        }
        return Verified(parts, manifest, issuerPublicKey, signatureValid)
    }

    private fun verifySignature(parts: Parts, issuerPublicKey: ByteArray): Boolean =
        Primitives.verifyEd25519(
            issuerPublicKey,
            // The manifest is verified as the exact bytes stored in the archive, never as a
            // re-serialisation of the parsed object — that would make verification depend on this
            // implementation's JSON formatting matching the writer's.
            Primitives.domainSeparated(MANIFEST_DOMAIN, Primitives.sha256(parts.manifestBytes)),
            parts.signature,
        )

    private fun assertDigest(bytes: ByteArray, expected: String, expectedLength: Int, what: String) {
        if (bytes.size != expectedLength) {
            throw TkpakException(
                TkpakError.DIGEST_MISMATCH,
                "$what is ${bytes.size} bytes, manifest says $expectedLength",
            )
        }
        if (!Primitives.sha256(bytes).contentEquals(Base64Url.decode(expected))) {
            throw TkpakException(TkpakError.DIGEST_MISMATCH, "$what does not match the manifest")
        }
    }

    private fun tryUnwrap(kek: ByteArray, slot: TkpakKeySlot, fileId: String): ByteArray? = try {
        Primitives.open(
            kek,
            Base64Url.decodeExact(slot.wrapNonce, Primitives.NONCE_BYTES),
            Base64Url.decode(slot.wrappedFileKey),
            aadFileKey(fileId),
        ).also {
            if (it.size != Primitives.KEY_BYTES) {
                throw TkpakException(TkpakError.MALFORMED_MANIFEST, "unwrapped key has wrong length")
            }
        }
    } catch (_: AeadFailure) {
        null
    }

    private fun decrypt(verified: Verified, fileKey: ByteArray): OpenedTkpak {
        val manifest = verified.manifest
        // Past this point the key is known to be right, so a tag failure means the file was
        // modified rather than that the password was wrong — which is what lets the app say "this
        // file has been altered" instead of "try again".
        val payloadBytes = try {
            Primitives.open(
                fileKey,
                Base64Url.decodeExact(manifest.payload.nonce, Primitives.NONCE_BYTES),
                verified.parts.payload,
                aadPayload(manifest.fileId),
            )
        } catch (cause: AeadFailure) {
            throw TkpakException(TkpakError.DECRYPTION_FAILED, "payload did not decrypt", cause)
        }

        val bundle = try {
            json.decodeFromString<TkpakBundle>(payloadBytes.toString(Charsets.UTF_8))
        } catch (cause: Exception) {
            throw TkpakException(TkpakError.MALFORMED_MANIFEST, "payload is not valid JSON", cause)
        }
        if (bundle.fileId != manifest.fileId) {
            throw TkpakException(
                TkpakError.FILE_ID_MISMATCH,
                "payload names ${bundle.fileId}, manifest names ${manifest.fileId}",
            )
        }

        val documents = manifest.blobs.associate { blob ->
            val ciphertext = verified.parts.blobs.getValue(blob.id)
            val bytes = try {
                Primitives.open(
                    fileKey,
                    Base64Url.decodeExact(blob.nonce, Primitives.NONCE_BYTES),
                    ciphertext,
                    aadBlob(manifest.fileId, blob.id),
                )
            } catch (cause: AeadFailure) {
                throw TkpakException(TkpakError.DECRYPTION_FAILED, "blob did not decrypt", cause)
            }
            blob.id to TkpakDocument(blob.id, blob.mediaType, bytes)
        }

        return OpenedTkpak(manifest, bundle, documents, verified.issuerPublicKey, verified.signatureValid)
    }

    private fun parseManifest(manifestBytes: ByteArray): TkpakManifest {
        val parsed = try {
            json.decodeFromString<TkpakManifest>(manifestBytes.toString(Charsets.UTF_8))
        } catch (cause: Exception) {
            throw TkpakException(TkpakError.MALFORMED_MANIFEST, "manifest is not valid JSON", cause)
        }
        // The order the specification mandates: what the file claims to be, then whether its
        // numbers are sane, and only afterwards anything cryptographic. Verifying a signature first
        // would answer "corrupt file" to a file from a newer release.
        if (parsed.format != "tkpak") {
            throw TkpakException(TkpakError.NOT_A_TKPAK, "format is '${parsed.format}'")
        }
        if (parsed.version < 1) {
            throw TkpakException(TkpakError.MALFORMED_MANIFEST, "version must be positive")
        }
        if (parsed.version > 1) {
            throw TkpakException(
                TkpakError.UNSUPPORTED_VERSION,
                "file is version ${parsed.version}, this reader supports 1",
            )
        }
        if (parsed.keySlots.isEmpty()) {
            throw TkpakException(TkpakError.MALFORMED_MANIFEST, "keySlots must not be empty")
        }
        if (parsed.blobs.size > Limits.BLOB_COUNT) {
            throw TkpakException(TkpakError.LIMIT_EXCEEDED, "too many blobs")
        }
        return parsed
    }

    private fun unpack(archive: ByteArray): Parts {
        var manifestBytes: ByteArray? = null
        var payload: ByteArray? = null
        var signature: ByteArray? = null
        val blobs = mutableMapOf<String, ByteArray>()
        var total = 0L

        try {
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val bytes = zip.readBytes()
                    total += bytes.size
                    if (total > Limits.TOTAL_BYTES) {
                        throw TkpakException(TkpakError.LIMIT_EXCEEDED, "archive is too large")
                    }
                    when {
                        entry.name == MANIFEST_ENTRY -> {
                            if (bytes.size > Limits.MANIFEST_BYTES) {
                                throw TkpakException(TkpakError.LIMIT_EXCEEDED, "manifest too large")
                            }
                            manifestBytes = bytes
                        }
                        entry.name == PAYLOAD_ENTRY -> {
                            if (bytes.size > Limits.PAYLOAD_BYTES) {
                                throw TkpakException(TkpakError.LIMIT_EXCEEDED, "payload too large")
                            }
                            payload = bytes
                        }
                        entry.name == SIGNATURE_ENTRY -> signature = bytes
                        entry.name.startsWith(BLOB_PREFIX) && entry.name.endsWith(".bin") -> {
                            if (bytes.size > Limits.BLOB_BYTES) {
                                throw TkpakException(TkpakError.LIMIT_EXCEEDED, "blob too large")
                            }
                            blobs[entry.name.removePrefix(BLOB_PREFIX).removeSuffix(".bin")] = bytes
                        }
                        // Unknown entries are ignored rather than rejected, so a future version can
                        // add parts without breaking a version 1 reader.
                    }
                }
            }
        } catch (cause: TkpakException) {
            throw cause
        } catch (cause: Exception) {
            throw TkpakException(TkpakError.NOT_A_TKPAK, "file is not a readable ZIP", cause)
        }

        return Parts(
            manifestBytes = manifestBytes
                ?: throw TkpakException(TkpakError.NOT_A_TKPAK, "archive has no $MANIFEST_ENTRY"),
            payload = payload
                ?: throw TkpakException(TkpakError.NOT_A_TKPAK, "archive has no $PAYLOAD_ENTRY"),
            signature = signature
                ?: throw TkpakException(TkpakError.NOT_A_TKPAK, "archive has no $SIGNATURE_ENTRY"),
            blobs = blobs,
        )
    }
}
