package com.mateof.passvault.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mateof.passvault.tkpak.Tkpak
import com.mateof.passvault.tkpak.TkpakAssignment
import com.mateof.passvault.tkpak.TkpakBarcode
import com.mateof.passvault.tkpak.TkpakBundle
import com.mateof.passvault.tkpak.TkpakEvent
import com.mateof.passvault.tkpak.TkpakPayment
import com.mateof.passvault.tkpak.TkpakTicket
import com.mateof.passvault.tkpak.TkpakWriter
import com.mateof.passvault.ui.wallet.TicketState
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Importing a file into the wallet, and reading it back out.
 *
 * The database is real — Room in memory, not a fake DAO — so the query, the column names and the
 * ordering are exercised rather than assumed. The key is the in-memory one, because the Android
 * KeyStore does not exist in a JVM test; what is being checked here is the encryption around it,
 * which is the part with logic in it.
 */
@RunWith(RobolectricTestRunner::class)
class WalletRepositoryTest {
    private lateinit var database: PassVaultDatabase
    private lateinit var repository: WalletRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PassVaultDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = WalletRepository(database.walletDao(), InMemoryDeviceKeys())
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * Ticket ids are derived from the event name, so two bundles in one test are two tickets.
     *
     * Reusing an id made an ordering test look broken when what it actually demonstrated was
     * REPLACE doing its job: the second import overwrote the first ticket rather than adding one.
     */
    private fun bundle(
        eventName: String = "Festival do Norte 2026",
        startsAt: String? = "2026-08-14T19:00:00.000Z",
        tickets: List<TkpakTicket> = listOf(
            TkpakTicket(
                id = "ticket-${eventName.hashCode()}",
                label = "Grada A",
                seat = "14-B",
                barcode = TkpakBarcode("QR_CODE", "8412-SECRET-0001"),
                assignmentMode = "ASSIGNED",
                assignment = TkpakAssignment(state = "ASSIGNED", holderLabel = "Ana"),
                payment = TkpakPayment(state = "PAID", amountCents = 4500, currency = "EUR", visibility = "ALL"),
            ),
        ),
    ) = TkpakBundle(
        fileId = "file-1",
        exportedAt = "2026-07-30T10:15:00.000Z",
        event = TkpakEvent(
            id = "event-${eventName.hashCode()}",
            name = eventName,
            venue = "Recinto Ferial",
            startsAt = startsAt,
            defaultAssignmentMode = "ASSIGNED",
            passwordProtected = true,
        ),
        tickets = tickets,
    )

    private fun opened(bundle: TkpakBundle = bundle()) = Tkpak.openWithPassword(
        TkpakWriter.write(
            TkpakWriter.Input(
                issuer = TkpakWriter.Issuer("device-1", TkpakWriter.generateSigningKey()),
                bundle = bundle,
                password = "sempre en Galiza",
                memoryKiB = 8192,
                iterations = 1,
                parallelism = 1,
            ),
        ),
        "sempre en Galiza",
    )

    @Test
    fun `an imported file becomes a ticket in the wallet`() = runTest {
        repository.import(opened())

        assertThat(repository.wallet().first()).hasSize(1)
    }

    @Test
    fun `the event name survives the round trip through storage`() = runTest {
        repository.import(opened())

        assertThat(repository.wallet().first().first().eventName).isEqualTo("Festival do Norte 2026")
    }

    @Test
    fun `the seat survives the round trip through storage`() = runTest {
        repository.import(opened())

        assertThat(repository.wallet().first().first().seat).isEqualTo("14-B")
    }

    @Test
    fun `nothing readable is written to the database`() = runTest {
        repository.import(opened())

        val cursor = database.query("SELECT name_cipher FROM events", emptyArray())
        cursor.moveToFirst()
        val stored = String(cursor.getBlob(0), Charsets.ISO_8859_1)
        cursor.close()

        assertThat(stored).doesNotContain("Festival")
    }

    @Test
    fun `the barcode is never written in the clear either`() = runTest {
        repository.import(opened())

        val cursor = database.query("SELECT barcode_cipher FROM tickets", emptyArray())
        cursor.moveToFirst()
        val stored = String(cursor.getBlob(0), Charsets.ISO_8859_1)
        cursor.close()

        assertThat(stored).doesNotContain("8412")
    }

    @Test
    fun `the barcode format stays readable, so a client knows how to draw it`() = runTest {
        repository.import(opened())

        val cursor = database.query("SELECT barcode_format FROM tickets", emptyArray())
        cursor.moveToFirst()
        val format = cursor.getString(0)
        cursor.close()

        assertThat(format).isEqualTo("QR_CODE")
    }

    @Test
    fun `an assigned ticket reads as held`() = runTest {
        repository.import(opened())

        assertThat(repository.wallet().first().first().state).isEqualTo(TicketState.Held)
    }

    @Test
    fun `a provisional claim reads as provisional, not as held`() = runTest {
        // The distinction the whole offline design rests on: a claim made without connectivity is
        // not settled, and the wallet has to say so.
        val provisional = bundle(
            tickets = listOf(
                TkpakTicket(
                    id = "ticket-2",
                    assignmentMode = "SELF_CLAIM",
                    assignment = TkpakAssignment(state = "PROVISIONAL"),
                ),
            ),
        )

        repository.import(opened(provisional))

        assertThat(repository.wallet().first().first().state).isEqualTo(TicketState.Provisional)
    }

    @Test
    fun `an amount is formatted for the reader locale`() = runTest {
        repository.import(opened())

        val label = repository.wallet(Locale.forLanguageTag("gl-ES")).first().first().paymentLabel

        assertThat(label).contains("45")
    }

    @Test
    fun `a ticket with no recorded payment has no payment label`() = runTest {
        val unpaid = bundle(
            tickets = listOf(
                TkpakTicket(
                    id = "ticket-3",
                    assignmentMode = "OPEN",
                    assignment = TkpakAssignment(state = "FREE"),
                ),
            ),
        )

        repository.import(opened(unpaid))

        assertThat(repository.wallet().first().first().paymentLabel).isNull()
    }

    @Test
    fun `tickets are ordered by when the event starts`() = runTest {
        repository.import(opened(bundle(eventName = "Later", startsAt = "2026-12-01T20:00:00.000Z")))
        repository.import(opened(bundle(eventName = "Sooner", startsAt = "2026-08-01T20:00:00.000Z")))

        assertThat(repository.wallet().first().map { it.eventName })
            .containsExactly("Sooner", "Later")
            .inOrder()
    }

    @Test
    fun `an event with no date sorts last rather than first`() = runTest {
        // SQLite puts NULL first, which would push every undated ticket to the top of the wallet.
        repository.import(opened(bundle(eventName = "Undated", startsAt = null)))
        repository.import(opened(bundle(eventName = "Dated", startsAt = "2026-08-01T20:00:00.000Z")))

        assertThat(repository.wallet().first().map { it.eventName })
            .containsExactly("Dated", "Undated")
            .inOrder()
    }

    @Test
    fun `importing the same file twice does not duplicate the ticket`() = runTest {
        repository.import(opened())
        repository.import(opened())

        assertThat(repository.ticketCount()).isEqualTo(1)
    }

    @Test
    fun `a ciphertext moved to another row does not decrypt`() = runTest {
        // The associated data binds a value to its column and row, so a stolen blob cannot be
        // planted somewhere it would read as something else.
        repository.import(opened())
        val cursor = database.query("SELECT name_cipher FROM events", emptyArray())
        cursor.moveToFirst()
        val eventName = cursor.getBlob(0)
        cursor.close()

        val thrown = runCatching {
            repository.barcodeOf("ticket-${"Festival do Norte 2026".hashCode()}", eventName)
        }.exceptionOrNull()

        assertThat(thrown).isNotNull()
    }
}
