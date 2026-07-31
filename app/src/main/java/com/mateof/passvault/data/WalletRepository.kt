package com.mateof.passvault.data

import com.mateof.passvault.crypto.Primitives
import com.mateof.passvault.sync.OperationType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.mateof.passvault.tkpak.OpenedTkpak
import com.mateof.passvault.ui.wallet.TicketRow
import com.mateof.passvault.ui.wallet.TicketState
import java.text.NumberFormat
import java.time.Instant
import java.util.Currency
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The wallet, decrypted.
 *
 * Everything of value is encrypted before it reaches the database and decrypted here, so no
 * component above this layer ever holds a key or has to remember to encrypt anything. The
 * associated data names the exact column and row, so a ciphertext cannot be moved: a label pasted
 * into a barcode column, or one ticket's row into another's, fails authentication rather than
 * decrypting into the wrong place.
 */
class WalletRepository(
    private val dao: WalletDao,
    private val keys: DeviceKeys,
    private val log: com.mateof.passvault.sync.OperationLog,
    private val documents: DocumentDao,
    private val documentStore: DocumentStore,
) {
    private fun aad(table: String, column: String, rowId: String) =
        "passvault/v1/field:$table.$column:$rowId"

    private fun encrypt(value: String, table: String, column: String, rowId: String): ByteArray {
        val nonce = Primitives.randomNonce()
        return nonce + Primitives.seal(keys.vaultKey(), nonce, value.toByteArray(), aad(table, column, rowId))
    }

    private fun decrypt(stored: ByteArray?, table: String, column: String, rowId: String): String? {
        if (stored == null) return null
        val nonce = stored.copyOfRange(0, Primitives.NONCE_BYTES)
        val ciphertext = stored.copyOfRange(Primitives.NONCE_BYTES, stored.size)
        return Primitives.open(keys.vaultKey(), nonce, ciphertext, aad(table, column, rowId))
            .toString(Charsets.UTF_8)
    }

    /**
     * The list the wallet screen renders.
     *
     * Decryption and formatting happen here rather than in a composable, so no frame ever spends
     * time on AES or on `NumberFormat`. A screen that decrypts during composition drops frames on
     * exactly the scroll where the user notices.
     */
    fun wallet(locale: Locale = Locale.getDefault()): Flow<List<TicketRow>> =
        dao.wallet().map { rows ->
            rows.map { row ->
                TicketRow(
                    id = row.id,
                    eventName = decrypt(row.eventNameCipher, "events", "name_cipher", row.eventId)
                        ?: "",
                    label = decrypt(row.labelCipher, "tickets", "label_cipher", row.id) ?: "",
                    seat = decrypt(row.seatCipher, "tickets", "seat_cipher", row.id),
                    state = stateOf(row.assignmentState),
                    paymentLabel = paymentLabel(row, locale),
                )
            }
        }

    /**
     * The events the wallet lists.
     *
     * Decrypted here rather than in a composable, like the ticket list: a screen that decrypts
     * during composition drops frames on exactly the scroll where the user notices.
     */
    fun events(): Flow<List<com.mateof.passvault.ui.wallet.EventRow>> =
        dao.events().map { rows ->
            rows.map { row ->
                com.mateof.passvault.ui.wallet.EventRow(
                    id = row.id,
                    name = decrypt(row.nameCipher, "events", "name_cipher", row.id) ?: "",
                    venue = decrypt(row.venueCipher, "events", "venue_cipher", row.id),
                    startsAt = row.startsAt?.take(10),
                    ticketCount = row.ticketCount,
                    provisionalCount = row.provisionalCount,
                    icon = row.icon,
                    colour = row.colour,
                )
            }
        }

    /**
     * Changes the mark an event is recognised by.
     *
     * A single UPDATE of two plaintext columns rather than a re-save of the row: everything else
     * on an event is ciphertext, and rewriting it to change a colour would mean decrypting and
     * re-encrypting a name for no reason at all.
     */
    suspend fun setEventMark(eventId: String, icon: String, colour: String) {
        dao.setEventMark(eventId, icon, colour)
    }

    /**
     * The documents an event's tickets were split out of, watched.
     *
     * Nothing here is encrypted: a media type, a page count and a size. The bytes are ciphertext
     * on disk and are only decrypted when somebody opens one, which is the point at which they
     * asked to see it.
     */
    fun documentRowsOf(eventId: String): Flow<List<com.mateof.passvault.ui.wallet.DocumentRow>> =
        documents.forEventFlow(eventId).map { rows ->
            rows.map { row ->
                com.mateof.passvault.ui.wallet.DocumentRow(
                    id = row.id,
                    mediaType = row.mediaType,
                    pageCount = row.pageCount,
                    byteCount = row.byteCount,
                )
            }
        }

    /**
     * Writes a `.tkpak` for a scope.
     *
     * The other half of a promise the app had been making without keeping: it could read these
     * files since the first version and never write one, so "share tickets as a single encrypted
     * file that travels over WhatsApp" was true of the format and not of this application.
     *
     * The password is the only key slot. Sealing to a recipient needs their agreement key, which
     * is what pairing is for — a file is the route for when there is no pairing to be had, which
     * is precisely when nobody has exchanged keys.
     *
     * Documents are left out deliberately. They are megabytes, they are the pages that are *not*
     * tickets, and a file sent through a messaging app is usually up against a size limit; the
     * barcodes are what gets somebody through a turnstile.
     */
    suspend fun exportTkpak(
        eventId: String,
        ticketIds: Set<String>?,
        password: String,
    ): ByteArray {
        val event = dao.event(eventId) ?: throw IllegalArgumentException("no such event")
        val rows = dao.ticketsForExport(eventId)
            .filter { ticketIds == null || it.id in ticketIds }

        val bundle = com.mateof.passvault.tkpak.TkpakBundle(
            fileId = Ids.newId(),
            exportedAt = Ids.toInstant(),
            event = com.mateof.passvault.tkpak.TkpakEvent(
                id = event.id,
                name = decrypt(event.nameCipher, "events", "name_cipher", event.id) ?: "",
                venue = decrypt(event.venueCipher, "events", "venue_cipher", event.id),
                startsAt = event.startsAt,
                defaultAssignmentMode = event.defaultAssignmentMode,
                passwordProtected = event.passwordProtected == 1,
            ),
            tickets = rows.map { row ->
                com.mateof.passvault.tkpak.TkpakTicket(
                    id = row.id,
                    label = decrypt(row.labelCipher, "tickets", "label_cipher", row.id),
                    seat = decrypt(row.seatCipher, "tickets", "seat_cipher", row.id),
                    barcode = row.barcodeFormat?.let { format ->
                        decrypt(row.barcodeCipher, "tickets", "barcode_cipher", row.id)?.let {
                            com.mateof.passvault.tkpak.TkpakBarcode(format, it)
                        }
                    },
                    assignmentMode = row.assignmentMode,
                    assignment = com.mateof.passvault.tkpak.TkpakAssignment(
                        state = row.assignmentState,
                        holderLabel = decrypt(
                            row.holderLabelCipher,
                            "tickets",
                            "holder_label_cipher",
                            row.id,
                        ),
                    ),
                    payment = row.paymentState?.let { state ->
                        com.mateof.passvault.tkpak.TkpakPayment(
                            state = state,
                            amountCents = row.amountCents,
                            currency = row.currency,
                            visibility = "ALL",
                        )
                    },
                )
            },
        )

        val identity = keys.identity()
        return com.mateof.passvault.tkpak.TkpakWriter.write(
            com.mateof.passvault.tkpak.TkpakWriter.Input(
                issuer = com.mateof.passvault.tkpak.TkpakWriter.Issuer(
                    deviceId = identity.deviceId,
                    privateKey = identity.signingPrivateKey,
                ),
                bundle = bundle,
                password = password,
            ),
        )
    }

    /** The tickets of one event, decrypted the same way as the wallet list. */
    fun ticketsOf(eventId: String, locale: Locale = Locale.getDefault()): Flow<List<TicketRow>> =
        dao.ticketsOf(eventId).map { rows ->
            rows.map { row ->
                TicketRow(
                    id = row.id,
                    eventName = decrypt(row.eventNameCipher, "events", "name_cipher", row.eventId)
                        ?: "",
                    label = decrypt(row.labelCipher, "tickets", "label_cipher", row.id) ?: "",
                    seat = decrypt(row.seatCipher, "tickets", "seat_cipher", row.id),
                    state = stateOf(row.assignmentState),
                    paymentLabel = paymentLabel(row, locale),
                )
            }
        }

    private fun stateOf(stored: String): TicketState = when (stored) {
        "PROVISIONAL" -> TicketState.Provisional
        "CLAIMED", "ASSIGNED" -> TicketState.Held
        "TRANSFERRED" -> TicketState.Transferred
        else -> TicketState.Free
    }

    private fun paymentLabel(row: TicketWithEvent, locale: Locale): String? {
        val amount = row.amountCents ?: return null
        val currency = row.currency ?: return null
        return runCatching {
            NumberFormat.getCurrencyInstance(locale).apply {
                this.currency = Currency.getInstance(currency)
            }.format(amount / 100.0)
        }.getOrNull()
    }

    suspend fun ticketCount(): Int = dao.ticketCount()

    /**
     * Stores what an imported `.tkpak` contained.
     *
     * Under this device's key, not the sender's: the file was encrypted for transport, and once it
     * has arrived it belongs to this wallet and is protected the way everything else here is.
     */
    suspend fun import(opened: OpenedTkpak, now: String = Instant.now().toString()) {
        val event = opened.bundle.event
        dao.upsertEvent(
            EventEntity(
                id = event.id,
                nameCipher = encrypt(event.name, "events", "name_cipher", event.id),
                venueCipher = event.venue?.let { encrypt(it, "events", "venue_cipher", event.id) },
                startsAt = event.startsAt,
                defaultAssignmentMode = event.defaultAssignmentMode,
                passwordProtected = if (event.passwordProtected) 1 else 0,
                createdAt = now,
            ),
        )
        dao.upsertTickets(
            opened.bundle.tickets.map { ticket ->
                TicketEntity(
                    id = ticket.id,
                    eventId = event.id,
                    labelCipher = ticket.label?.let { encrypt(it, "tickets", "label_cipher", ticket.id) },
                    seatCipher = ticket.seat?.let { encrypt(it, "tickets", "seat_cipher", ticket.id) },
                    barcodeFormat = ticket.barcode?.format,
                    barcodeCipher = ticket.barcode?.let {
                        encrypt(it.value, "tickets", "barcode_cipher", ticket.id)
                    },
                    assignmentMode = ticket.assignmentMode,
                    assignmentState = ticket.assignment.state,
                    holderLabelCipher = ticket.assignment.holderLabel?.let {
                        encrypt(it, "tickets", "holder_label_cipher", ticket.id)
                    },
                    paymentState = ticket.payment?.state,
                    amountCents = ticket.payment?.amountCents,
                    currency = ticket.payment?.currency,
                    exportedAt = null,
                    createdAt = now,
                )
            },
        )
    }

    /**
     * Saves the tickets a user confirmed from an ingestion proposal.
     *
     * Written to the log first and projected from it, not written to the tables directly. This
     * device made the event, so it is its creator, and the operations it signs here are what let the
     * same tickets travel to another phone later. Doing it the other way round — tables now, log
     * some day — is how a wallet ends up holding tickets it cannot explain the provenance of.
     *
     * The event is created here because a document carries no event of its own: the file is a stack
     * of tickets, and which event they belong to is something only the person importing them knows.
     */
    suspend fun saveProposed(
        eventName: String,
        tickets: List<com.mateof.passvault.ingest.ProposedTicket>,
        source: SourceDocument? = null,
        now: String = Ids.toInstant(),
    ): Int {
        if (tickets.isEmpty()) return 0
        val eventId = Ids.newId()

        // The document the tickets were split out of, kept whole. The pages ingestion leaves out
        // are the ones with no barcode, and those are exactly the pages that carry the
        // instructions, the map and the terms — so the rule that makes the split right is the
        // rule that would lose the rest.
        if (source != null) {
            val documentId = Ids.newId()
            documentStore.write(documentId, source.bytes)
            documents.upsert(
                DocumentEntity(
                    id = documentId,
                    eventId = eventId,
                    mediaType = source.mediaType,
                    pageCount = source.pageCount,
                    byteCount = source.bytes.size,
                    createdAt = now,
                ),
            )
        }

        log.registerSelf(eventId, null)
        log.append(
            eventId,
            OperationType.EVENT_CREATE,
            buildJsonObject { put("name", eventName) },
        )
        for (proposed in tickets) {
            log.append(
                eventId,
                OperationType.TICKET_ADD,
                buildJsonObject {
                    put("ticketId", Ids.newId())
                    put("label", proposed.suggestedLabel)
                    proposed.barcode?.let {
                        put("barcodeFormat", it.format)
                        put("barcodeValue", it.value)
                    }
                },
            )
        }

        project(log.replay(eventId), now)
        return tickets.size
    }

    /**
     * Writes what the log says the wallet looks like.
     *
     * The projection is the only path from the log to the screen, and it upserts rather than
     * replacing: replay recomputes an event from scratch, so what it produces is the whole truth
     * about that event and can overwrite whatever was there.
     *
     * This is what makes a transfer visible. Operations arriving from another phone are verified and
     * stored by the log, and until they are projected they are entirely invisible — the user sees an
     * unchanged wallet and concludes the transfer failed.
     */
    suspend fun project(replayed: com.mateof.passvault.sync.ReplayResult, now: String = Ids.toInstant()) {
        for (event in replayed.events) {
            dao.upsertEvent(
                EventEntity(
                    id = event.eventId,
                    nameCipher = encrypt(event.name, "events", "name_cipher", event.eventId),
                    venueCipher = event.venue?.let {
                        encrypt(it, "events", "venue_cipher", event.eventId)
                    },
                    startsAt = event.startsAt,
                    defaultAssignmentMode = "OPEN",
                    passwordProtected = 0,
                    createdAt = now,
                ),
            )
        }
        dao.upsertTickets(
            replayed.tickets.map { ticket ->
                TicketEntity(
                    id = ticket.ticketId,
                    eventId = ticket.eventId,
                    labelCipher = ticket.label?.let {
                        encrypt(it, "tickets", "label_cipher", ticket.ticketId)
                    },
                    seatCipher = ticket.seat?.let {
                        encrypt(it, "tickets", "seat_cipher", ticket.ticketId)
                    },
                    barcodeFormat = ticket.barcodeFormat,
                    barcodeCipher = ticket.barcodeValue?.let {
                        encrypt(it, "tickets", "barcode_cipher", ticket.ticketId)
                    },
                    assignmentMode = "OPEN",
                    assignmentState = ticket.state.name,
                    holderLabelCipher = ticket.holder?.let {
                        encrypt(it, "tickets", "holder_label_cipher", ticket.ticketId)
                    },
                    paymentState = ticket.paymentState,
                    amountCents = ticket.amountCents,
                    currency = ticket.currency,
                    exportedAt = null,
                    createdAt = now,
                )
            },
        )
        for (ticketId in replayed.withdrawn) {
            dao.deleteTicket(ticketId)
        }
    }

    /** The documents kept for an event, newest last. */
    suspend fun documentsOf(eventId: String): List<StoredDocument> =
        documents.forEvent(eventId).map {
            StoredDocument(it.id, it.mediaType, it.pageCount, it.byteCount)
        }

    /** The decrypted bytes of one document, for rendering. Null if the file is gone. */
    suspend fun documentBytes(documentId: String): ByteArray? = documentStore.read(documentId)

    /** Projects every event the log knows about. What a device does after a transfer. */
    suspend fun projectAll() {
        for (eventId in log.eventIds()) {
            project(log.replay(eventId))
        }
    }

    /**
     * One ticket, decrypted, including its barcode.
     *
     * The barcode is decrypted here and nowhere else, because this is the only screen that shows
     * one — the list would otherwise decrypt forty payloads to draw rows that never display them.
     */
    suspend fun detail(ticketId: String): com.mateof.passvault.ui.ticket.TicketDetail? {
        val row = dao.ticket(ticketId) ?: return null
        return com.mateof.passvault.ui.ticket.TicketDetail(
            id = row.id,
            eventId = row.eventId,
            eventName = decrypt(row.eventNameCipher, "events", "name_cipher", row.eventId) ?: "",
            label = decrypt(row.labelCipher, "tickets", "label_cipher", row.id),
            seat = decrypt(row.seatCipher, "tickets", "seat_cipher", row.id),
            barcodeFormat = row.barcodeFormat,
            barcodeValue = decrypt(row.barcodeCipher, "tickets", "barcode_cipher", row.id),
            holderLabel = decrypt(row.holderLabelCipher, "tickets", "holder_label_cipher", row.id),
            isProvisional = row.assignmentState == "PROVISIONAL",
            hasDocument = documents.forEvent(row.eventId).isNotEmpty(),
        )
    }

    /**
     * The barcode, decrypted only when it is about to be shown.
     *
     * Separate from the list on purpose: the wallet renders forty rows and none of them needs a
     * barcode, so decrypting them all to draw a list would be work spent on data nobody is looking
     * at — and would put every barcode in memory at once.
     */
    suspend fun barcodeOf(ticketId: String, stored: ByteArray?): String? =
        decrypt(stored, "tickets", "barcode_cipher", ticketId)
}

/** A document about to be stored, with what is known about it before it is encrypted. */
data class SourceDocument(val bytes: ByteArray, val mediaType: String, val pageCount: Int) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

data class StoredDocument(
    val id: String,
    val mediaType: String,
    val pageCount: Int,
    val byteCount: Int,
)
