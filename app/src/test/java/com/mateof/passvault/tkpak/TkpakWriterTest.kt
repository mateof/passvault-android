package com.mateof.passvault.tkpak

import com.google.common.truth.Truth.assertThat
import com.mateof.passvault.crypto.Base64Url
import com.mateof.passvault.crypto.Primitives
import java.io.File
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Test

/**
 * Writing a `.tkpak` on the phone.
 *
 * A round trip inside this implementation proves only that it is self-consistent, so the last test
 * here writes a file to `build/interop/` for the server's TypeScript reader to open. That check is
 * what makes the format bidirectional rather than merely readable in one direction.
 */
class TkpakWriterTest {
    private val password = "sempre en Galiza"

    private fun issuer() = TkpakWriter.Issuer(
        deviceId = "0192f5c0-2222-7000-8000-ddddeeeeffff",
        privateKey = TkpakWriter.generateSigningKey(),
        displayName = "Mateo",
    )

    private fun bundle(ticketCount: Int = 2, fileId: String = "0192f5c1-8a3e-7c44-9b21-6d5e4f3a2b10") =
        TkpakBundle(
            fileId = fileId,
            exportedAt = "2026-07-30T10:15:00.000Z",
            event = TkpakEvent(
                id = "0192f5b0-3333-7000-8000-111122223333",
                name = "Festival do Norte 2026",
                venue = "Recinto Ferial, Vilagarcía",
                startsAt = "2026-08-14T19:00:00.000Z",
                timeZone = "Europe/Madrid",
                defaultAssignmentMode = "ASSIGNED",
                passwordProtected = true,
            ),
            tickets = (1..ticketCount).map { index ->
                TkpakTicket(
                    id = "0192f5b1-4444-7000-8000-44445555666$index",
                    label = "Grada A 14-${'A' + index}",
                    barcode = TkpakBarcode("QR_CODE", "8412-KOTLIN-000$index"),
                    assignmentMode = "ASSIGNED",
                    assignment = TkpakAssignment(state = "ASSIGNED", holderLabel = "Holder $index"),
                )
            },
        )

    private fun document(id: String = "0192f5c2-2222-7000-8000-ddddeeeeffff") = TkpakDocument(
        id = id,
        mediaType = "application/pdf",
        bytes = ("%PDF-1.7\n" + " ".repeat(256) + "\n%%EOF\n").toByteArray(Charsets.UTF_8),
    )

    /** Weak on purpose: the production parameters would add a second to every test. */
    private fun write(input: TkpakWriter.Input) = TkpakWriter.write(
        input.copy(memoryKiB = 8192, iterations = 1, parallelism = 1),
    )

    @Test
    fun `a file written with a password opens with that password`() {
        val archive = write(TkpakWriter.Input(issuer(), bundle(), password = password))

        val opened = Tkpak.openWithPassword(archive, password)

        assertThat(opened.bundle.tickets).hasSize(2)
    }

    @Test
    fun `the barcodes survive the round trip exactly`() {
        val source = bundle()
        val archive = write(TkpakWriter.Input(issuer(), source, password = password))

        val opened = Tkpak.openWithPassword(archive, password)

        assertThat(opened.bundle.tickets.map { it.barcode?.value })
            .isEqualTo(source.tickets.map { it.barcode?.value })
    }

    @Test
    fun `a wrong password is refused`() {
        val archive = write(TkpakWriter.Input(issuer(), bundle(), password = password))

        val thrown = runCatching { Tkpak.openWithPassword(archive, "nunca mais") }.exceptionOrNull()

        assertThat((thrown as TkpakException).code).isEqualTo(TkpakError.WRONG_PASSWORD)
    }

    @Test
    fun `the file is signed by the issuing device`() {
        val archive = write(TkpakWriter.Input(issuer(), bundle(), password = password))

        assertThat(Tkpak.inspect(archive).signatureValid).isTrue()
    }

    @Test
    fun `the issuer key in the manifest is the one derived from the private key`() {
        val issuer = issuer()
        val archive = write(TkpakWriter.Input(issuer, bundle(), password = password))

        assertThat(Tkpak.inspect(archive).manifest.issuer.publicKey)
            .isEqualTo(Base64Url.encode(TkpakWriter.signingPublicKey(issuer.privateKey)))
    }

    @Test
    fun `documents come back byte for byte`() {
        val document = document()
        val archive = write(
            TkpakWriter.Input(issuer(), bundle(), documents = listOf(document), password = password),
        )

        val opened = Tkpak.openWithPassword(archive, password)

        assertThat(opened.documents.getValue(document.id).bytes).isEqualTo(document.bytes)
    }

    @Test
    fun `a file sealed to a recipient opens with their key`() {
        val recipientPrivate = X25519PrivateKeyParameters(Primitives.randomBytes(32), 0)
        val recipientPublic = recipientPrivate.generatePublicKey().encoded
        val archive = write(
            TkpakWriter.Input(issuer(), bundle(), recipientPublicKey = recipientPublic),
        )

        val opened = Tkpak.openWithRecipientKey(archive, recipientPrivate.encoded, recipientPublic)

        assertThat(opened.bundle.tickets).hasSize(2)
    }

    @Test
    fun `a sealed file cannot be opened by anybody else`() {
        val recipientPublic = X25519PrivateKeyParameters(Primitives.randomBytes(32), 0)
            .generatePublicKey().encoded
        val stranger = X25519PrivateKeyParameters(Primitives.randomBytes(32), 0)
        val archive = write(
            TkpakWriter.Input(issuer(), bundle(), recipientPublicKey = recipientPublic),
        )

        val thrown = runCatching {
            Tkpak.openWithRecipientKey(archive, stranger.encoded, stranger.generatePublicKey().encoded)
        }.exceptionOrNull()

        assertThat((thrown as TkpakException).code).isEqualTo(TkpakError.NO_USABLE_KEY_SLOT)
    }

    @Test
    fun `both slots wrap the same file key, so the ciphertext is stored once`() {
        val recipientPrivate = X25519PrivateKeyParameters(Primitives.randomBytes(32), 0)
        val recipientPublic = recipientPrivate.generatePublicKey().encoded
        val archive = write(
            TkpakWriter.Input(
                issuer(),
                bundle(),
                password = password,
                recipientPublicKey = recipientPublic,
            ),
        )

        val viaPassword = Tkpak.openWithPassword(archive, password)
        val viaKey = Tkpak.openWithRecipientKey(archive, recipientPrivate.encoded, recipientPublic)

        assertThat(viaPassword.bundle).isEqualTo(viaKey.bundle)
    }

    @Test
    fun `writing a file nobody could open is refused rather than produced`() {
        val thrown = runCatching {
            write(TkpakWriter.Input(issuer(), bundle()))
        }.exceptionOrNull()

        assertThat((thrown as TkpakException).code).isEqualTo(TkpakError.NO_USABLE_KEY_SLOT)
    }

    @Test
    fun `the event is named in the preview by default, so forwarded files can be told apart`() {
        val archive = write(TkpakWriter.Input(issuer(), bundle(), password = password))

        assertThat(Tkpak.inspect(archive).manifest.preview?.eventName)
            .isEqualTo("Festival do Norte 2026")
    }

    @Test
    fun `minimal metadata reveals only the ticket count`() {
        val archive = write(
            TkpakWriter.Input(
                issuer(),
                bundle(),
                password = password,
                preview = TkpakWriter.PreviewMode.MINIMAL,
            ),
        )

        val preview = Tkpak.inspect(archive).manifest.preview

        assertThat(preview?.ticketCount).isEqualTo(2)
        assertThat(preview?.eventName).isNull()
    }

    @Test
    fun `no barcode ever reaches the cleartext preview`() {
        val archive = write(TkpakWriter.Input(issuer(), bundle(), password = password))

        val preview = Tkpak.inspect(archive).manifest.preview

        assertThat(preview.toString()).doesNotContain("8412")
    }

    @Test
    fun `a tampered file is refused`() {
        val archive = write(TkpakWriter.Input(issuer(), bundle(), password = password))
        // Flip a byte well past the ZIP header so the archive still parses.
        val tampered = archive.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }

        val thrown = runCatching { Tkpak.openWithPassword(tampered, password) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(TkpakException::class.java)
    }

    /**
     * Writes a file for the server's reader to open.
     *
     * Not an assertion — it produces the artefact that `npm run interop:check` in the passvault
     * repository consumes. A round trip within one implementation cannot show the two agree; only
     * the other side opening this file can.
     */
    @Test
    fun `writes an interoperability sample for the server to verify`() {
        val archive = write(
            TkpakWriter.Input(
                issuer(),
                bundle(ticketCount = 3),
                documents = listOf(document()),
                password = password,
            ),
        )

        val directory = File("build/interop").apply { mkdirs() }
        File(directory, "written-by-android.tkpak").writeBytes(archive)
        File(directory, "written-by-android.json").writeText(
            """{"archive":"written-by-android.tkpak","password":"$password","ticketCount":3}""",
        )

        assertThat(File(directory, "written-by-android.tkpak").length()).isGreaterThan(0L)
    }
}
