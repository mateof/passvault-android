package com.mateof.passvault.tkpak

import com.google.common.truth.Truth.assertThat
import com.mateof.passvault.crypto.Base64Url
import com.mateof.passvault.crypto.Primitives
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * The contract with the server's TypeScript implementation.
 *
 * These files were produced by `npm run vectors:generate` in the passvault repository and copied
 * into `app/src/test/resources/vectors`. Both readers run the same set: if this suite passes and
 * the server's does too, the two implementations agree. If one drifts, its own tests fail rather
 * than a user discovering that a file exported from the phone will not open on the server.
 *
 * This is the whole reason the format was specified in prose before either side was written.
 */
@Serializable
private data class VectorIndex(
    val format: String,
    val version: Int,
    val issuerPublicKey: String,
    val vectors: List<Vector>,
)

@Serializable
private data class Vector(
    val name: String,
    val description: String,
    val archive: String,
    val open: OpenSpec,
    val options: Options? = null,
    val expect: Expectation,
)

@Serializable
private data class OpenSpec(
    val kind: String,
    val password: String? = null,
    val privateKey: String? = null,
    val publicKey: String? = null,
)

@Serializable private data class Options(val requireValidSignature: Boolean? = null)

@Serializable
private data class Expectation(
    val outcome: String,
    val code: String? = null,
    val fileId: String? = null,
    val eventName: String? = null,
    val ticketCount: Int? = null,
    val barcodes: List<ExpectedBarcode> = emptyList(),
    val documents: List<ExpectedDocument> = emptyList(),
    val signatureValid: Boolean? = null,
)

@Serializable private data class ExpectedBarcode(val format: String, val value: String)

@Serializable
private data class ExpectedDocument(val id: String, val mediaType: String, val sha256: String)

class TkpakVectorTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("vectors/$name")) {
            "vectors/$name is missing. Copy spec/vectors from the passvault repository."
        }.use { it.readBytes() }

    private fun index(): VectorIndex =
        json.decodeFromString(resource("index.json").toString(Charsets.UTF_8))

    private fun open(vector: Vector): OpenedTkpak {
        val archive = resource(vector.archive)
        val requireSignature = vector.options?.requireValidSignature ?: true
        return if (vector.open.kind == "password") {
            Tkpak.openWithPassword(archive, requireNotNull(vector.open.password), requireSignature)
        } else {
            Tkpak.openWithRecipientKey(
                archive,
                Base64Url.decode(requireNotNull(vector.open.privateKey)),
                Base64Url.decode(requireNotNull(vector.open.publicKey)),
                requireSignature,
            )
        }
    }

    @Test
    fun `the vector set is present, so a missing checkout does not pass silently`() {
        assertThat(index().vectors).isNotEmpty()
    }

    @Test
    fun `the vector set is version 1`() {
        assertThat(index().version).isEqualTo(1)
    }

    @Test
    fun `every file the server said should open, opens`() {
        val failures = index().vectors
            .filter { it.expect.outcome == "success" }
            .mapNotNull { vector ->
                runCatching { open(vector) }.exceptionOrNull()?.let { "${vector.name}: $it" }
            }

        assertThat(failures).isEmpty()
    }

    @Test
    fun `every opened file has the file id the server recorded`() {
        for (vector in index().vectors.filter { it.expect.outcome == "success" }) {
            assertThat(open(vector).bundle.fileId).isEqualTo(vector.expect.fileId)
        }
    }

    @Test
    fun `every opened file has the ticket count the server recorded`() {
        for (vector in index().vectors.filter { it.expect.outcome == "success" }) {
            assertThat(open(vector).bundle.tickets).hasSize(vector.expect.ticketCount!!)
        }
    }

    @Test
    fun `every barcode decodes to exactly what the server wrote`() {
        for (vector in index().vectors.filter { it.expect.outcome == "success" }) {
            val actual = open(vector).bundle.tickets.mapNotNull { ticket ->
                ticket.barcode?.let { ExpectedBarcode(it.format, it.value) }
            }
            assertThat(actual).isEqualTo(vector.expect.barcodes)
        }
    }

    @Test
    fun `every document comes back byte for byte`() {
        for (vector in index().vectors.filter { it.expect.outcome == "success" }) {
            val actual = open(vector).documents.values.map { document ->
                ExpectedDocument(
                    document.id,
                    document.mediaType,
                    Base64Url.encode(Primitives.sha256(document.bytes)),
                )
            }
            assertThat(actual).containsExactlyElementsIn(vector.expect.documents)
        }
    }

    @Test
    fun `both implementations agree on whether a signature is valid`() {
        for (vector in index().vectors.filter { it.expect.outcome == "success" }) {
            assertThat(open(vector).signatureValid).isEqualTo(vector.expect.signatureValid)
        }
    }

    @Test
    fun `the event name in the preview matches`() {
        for (vector in index().vectors.filter { it.expect.outcome == "success" }) {
            assertThat(open(vector).manifest.preview?.eventName).isEqualTo(vector.expect.eventName)
        }
    }

    @Test
    fun `every file the server said should be rejected, is rejected with the same code`() {
        val failures = index().vectors
            .filter { it.expect.outcome == "error" }
            .mapNotNull { vector ->
                val thrown = runCatching { open(vector) }.exceptionOrNull()
                when {
                    thrown == null -> "${vector.name}: opened, but should have been rejected"
                    thrown !is TkpakException -> "${vector.name}: threw ${thrown::class.simpleName}"
                    thrown.code.name != vector.expect.code ->
                        "${vector.name}: reported ${thrown.code}, expected ${vector.expect.code}"
                    else -> null
                }
            }

        assertThat(failures).isEmpty()
    }

    @Test
    fun `a file can be inspected without any key`() {
        val vector = index().vectors.first { it.expect.outcome == "success" && it.expect.ticketCount!! > 0 }

        val inspection = Tkpak.inspect(resource(vector.archive))

        assertThat(inspection.manifest.preview?.ticketCount).isEqualTo(vector.expect.ticketCount)
    }

    @Test
    fun `inspection agrees with the issuer key the server generated`() {
        val expected = index().issuerPublicKey

        val inspection = Tkpak.inspect(resource(index().vectors.first().archive))

        assertThat(inspection.manifest.issuer.publicKey).isEqualTo(expected)
    }

    @Test
    fun `a wrong password is reported as a wrong password, not as tampering`() {
        val vector = index().vectors.first { it.open.kind == "password" && it.expect.outcome == "success" }

        val thrown = runCatching {
            Tkpak.openWithPassword(resource(vector.archive), "nunca mais")
        }.exceptionOrNull()

        assertThat((thrown as TkpakException).code).isEqualTo(TkpakError.WRONG_PASSWORD)
    }

    @Test
    fun `a password typed in a different normalisation form still opens the file`() {
        // Sealed with an NFC password. Typed here decomposed, as an Android keyboard may produce it.
        val vector = index().vectors.first { it.name == "09-unicode-password" }
        val decomposed = java.text.Normalizer.normalize(
            requireNotNull(vector.open.password),
            java.text.Normalizer.Form.NFD,
        )

        val opened = Tkpak.openWithPassword(resource(vector.archive), decomposed)

        assertThat(opened.bundle.fileId).isEqualTo(vector.expect.fileId)
    }
}
