package com.mateof.passvault.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mateof.passvault.ingest.ProposedTicket
import com.mateof.passvault.tkpak.Tkpak
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Writing a `.tkpak`, and opening it again.
 *
 * `TkpakWriter` has existed since the first version and nothing in the app ever called it, so the
 * README's "share tickets as a single encrypted file that travels over WhatsApp" was true of the
 * format and false of this application. What matters is not that bytes come out but that they go
 * back in: the file is read by the other phone's importer, and a file only this side can produce
 * is not a transfer.
 */
@RunWith(RobolectricTestRunner::class)
class ExportTkpakTest {

    private lateinit var database: PassVaultDatabase
    private lateinit var repository: WalletRepository

    private val password = "un contrasinal"

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PassVaultDatabase::class.java,
        ).allowMainThreadQueries().build()
        val keys = InMemoryDeviceKeys()
        repository = WalletRepository(
            database.walletDao(),
            keys,
            com.mateof.passvault.sync.OperationLog(database.operationDao(), keys),
            database.documentDao(),
            DocumentStore(ApplicationProvider.getApplicationContext(), keys),
        )
    }

    @After
    fun tearDown() = database.close()

    /** Three tickets in one event, through the same path ingestion uses. */
    private suspend fun seed(): String {
        repository.saveProposed(
            "Festival do Norte",
            (1..3).map { seat ->
                ProposedTicket(
                    index = seat - 1,
                    suggestedLabel = "Entrada $seat",
                    barcode = com.mateof.passvault.ingest.DecodedBarcode("QR_CODE", "8412-SEAT-$seat"),
                    pageNumber = seat,
                    include = true,
                    warnings = emptyList(),
                )
            },
        )
        return repository.events().first().first().id
    }

    @Test
    fun `a whole event comes out and goes back in`() = runTest {
        val eventId = seed()

        val bytes = repository.exportTkpak(eventId, ticketIds = null, password = password)
        val opened = Tkpak.openWithPassword(bytes, password)

        assertThat(opened.bundle.tickets).hasSize(3)
    }

    @Test
    fun `the event keeps its name across the file`() = runTest {
        val eventId = seed()

        val opened = Tkpak.openWithPassword(
            repository.exportTkpak(eventId, null, password),
            password,
        )

        assertThat(opened.bundle.event.name).isEqualTo("Festival do Norte")
    }

    @Test
    fun `the barcodes travel, since they are what the file is for`() = runTest {
        val eventId = seed()

        val opened = Tkpak.openWithPassword(
            repository.exportTkpak(eventId, null, password),
            password,
        )

        assertThat(opened.bundle.tickets.mapNotNull { it.barcode?.value })
            .containsExactly("8412-SEAT-1", "8412-SEAT-2", "8412-SEAT-3")
    }

    @Test
    fun `chosen tickets travel and the others do not`() = runTest {
        val eventId = seed()
        val all = repository.ticketsOf(eventId).first()
        val one = all.first().id

        val opened = Tkpak.openWithPassword(
            repository.exportTkpak(eventId, setOf(one), password),
            password,
        )

        assertThat(opened.bundle.tickets).hasSize(1)
        assertThat(opened.bundle.tickets.single().id).isEqualTo(one)
    }

    @Test
    fun `the wrong password does not open it`() = runTest {
        val eventId = seed()
        val bytes = repository.exportTkpak(eventId, null, password)

        val failure = runCatching { Tkpak.openWithPassword(bytes, "outra cousa") }.exceptionOrNull()

        assertThat(failure).isNotNull()
    }
}
