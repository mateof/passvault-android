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
                    // The full instant. This used to be cut to ten characters for display,
                    // which left the edit dialog unable to parse it: it showed empty fields,
                    // and saving the emptiness erased a date the user had just set.
                    startsAt = row.startsAt,
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
     * Says when an event is and where.
     *
     * Through the log rather than straight into the row, unlike the mark. A colour is a local
     * preference; a date and a venue are facts about the event, so they travel to the other
     * phones and to whoever it is shared with — which is what the log is for.
     *
     * A null clears its field, written as an empty string: `event.update` treats a missing field
     * as "unchanged", so removing something has to be said out loud rather than by omission.
     * A field the caller did not touch is simply not written.
     */
    suspend fun setEventFacts(
        eventId: String,
        startsAt: String? = null,
        venue: String? = null,
        startsAtTouched: Boolean = true,
        venueTouched: Boolean = false,
    ) {
        log.append(
            eventId,
            OperationType.EVENT_UPDATE,
            buildJsonObject {
                if (startsAtTouched) put("startsAt", startsAt ?: "")
                if (venueTouched) put("venue", venue ?: "")
            },
        )
        project(log.replay(eventId))
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
     * The original document goes with a whole event and not with a selection of seats. It is
     * megabytes and a file sent through a messaging app is usually up against a size limit — but
     * it is also the pages ingestion drops on purpose, which is what somebody arriving at an
     * unfamiliar venue actually needs, so a whole event carries it and two seats do not.
     */
    suspend fun exportTkpak(
        eventId: String,
        ticketIds: Set<String>?,
        password: String,
    ): ByteArray {
        val event = dao.event(eventId) ?: throw IllegalArgumentException("no such event")
        val rows = dao.ticketsForExport(eventId)
            .filter { ticketIds == null || it.id in ticketIds }

        // The file the tickets were split out of, whole. Sending only the tickets sends only what
        // ingestion kept, and the pages it drops on purpose — the map, the terms, how to get in —
        // are exactly what somebody receiving a ticket for an unfamiliar venue needs.
        //
        // Only for a whole event. Somebody being handed one seat is not being handed everybody's
        // document, and a thirty-megabyte PDF beside a single ticket is a poor trade anyway.
        val originals = if (ticketIds == null) {
            documents.forEvent(eventId).mapNotNull { row ->
                documentStore.read(row.id)?.let { bytes ->
                    com.mateof.passvault.tkpak.TkpakDocument(row.id, row.mediaType, bytes)
                }
            }
        } else {
            emptyList()
        }

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
                documentIds = originals.map { it.id },
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
                documents = originals,
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

        // The original the tickets came out of, kept as this wallet's own. Encrypted on the way in
        // under this device's key, like everything else that arrives: the file was sealed for
        // transport, and transport is over.
        for (documentId in event.documentIds) {
            val document = opened.documents[documentId] ?: continue
            documentStore.write(document.id, document.bytes)
            documents.upsert(
                DocumentEntity(
                    id = document.id,
                    eventId = event.id,
                    mediaType = document.mediaType,
                    // Unknown until it is opened and drawn, and a wrong number is worse than none.
                    // The annex counts what it renders; this is only what the row says beforehand.
                    pageCount = 0,
                    byteCount = document.bytes.size,
                    createdAt = now,
                ),
            )
        }
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
        // The ticket id is minted here rather than inside the body so the barcode can be stored
        // against it after projection. The barcode is deliberately NOT in the operation body: the
        // log is pulled whole by every member of an event, so a code there would reach a device the
        // creator means to withhold it from. It is kept locally and uploaded to the server by its
        // own side-channel on the next sync.
        val newBarcodes = mutableListOf<Triple<String, String, String>>()
        for (proposed in tickets) {
            val ticketId = Ids.newId()
            log.append(
                eventId,
                OperationType.TICKET_ADD,
                buildJsonObject {
                    put("ticketId", ticketId)
                    put("label", proposed.suggestedLabel)
                },
            )
            proposed.barcode?.let { newBarcodes.add(Triple(ticketId, it.format, it.value)) }
        }

        project(log.replay(eventId), now)
        // After projection, because it creates the rows; a targeted write so the next projection,
        // which recomputes from a log without the code, does not wipe it.
        for ((ticketId, format, value) in newBarcodes) {
            storeBarcode(ticketId, format, value)
        }
        return tickets.size
    }

    /**
     * Puts a barcode onto a ticket that already exists, from a side-channel rather than the log.
     *
     * The single point where a code enters the wallet now that it does not travel in the operation
     * that adds the ticket: a local import, a phone-to-phone carrier, a `.tkpak`. Encrypted at rest
     * like every payload, and written on its own so a later projection leaves it be.
     */
    suspend fun storeBarcode(ticketId: String, format: String, value: String) {
        dao.updateBarcode(ticketId, format, encrypt(value, "tickets", "barcode_cipher", ticketId))
    }

    /**
     * The codes this device holds for an event, as plaintext, for the sync side-channel.
     *
     * The creator's device uploads these alongside its operations so the server can seal them and
     * serve them on download. A device that holds no code for a seat — an assignee — contributes
     * nothing, and the server ignores a code from anyone but the event's creator, so offering them
     * here is safe.
     */
    suspend fun localBarcodes(eventId: String): List<Triple<String, String, String>> =
        dao.ticketsForExport(eventId).mapNotNull { ticket ->
            val format = ticket.barcodeFormat ?: return@mapNotNull null
            val cipher = ticket.barcodeCipher ?: return@mapNotNull null
            val value = decrypt(cipher, "tickets", "barcode_cipher", ticket.id)
                ?: return@mapNotNull null
            Triple(ticket.id, format, value)
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
        // The barcode is no longer in the log, so replay does not carry it. A projection must not
        // then wipe a code that arrived by its own side-channel — the creator's import, a
        // phone-to-phone carrier, a `.tkpak` — so the existing code is read first and kept whenever
        // replay has none of its own. (Tickets from before this change still carry theirs in an old
        // operation, and those win, which is how a re-projection leaves them exactly as they were.)
        val existingBarcodes = replayed.tickets
            .map { it.eventId }
            .distinct()
            .flatMap { dao.ticketsForExport(it) }
            .associate { it.id to Pair(it.barcodeFormat, it.barcodeCipher) }
        dao.upsertTickets(
            replayed.tickets.map { ticket ->
                val kept = existingBarcodes[ticket.ticketId]
                TicketEntity(
                    id = ticket.ticketId,
                    eventId = ticket.eventId,
                    labelCipher = ticket.label?.let {
                        encrypt(it, "tickets", "label_cipher", ticket.ticketId)
                    },
                    seatCipher = ticket.seat?.let {
                        encrypt(it, "tickets", "seat_cipher", ticket.ticketId)
                    },
                    barcodeFormat = ticket.barcodeFormat ?: kept?.first,
                    barcodeCipher = ticket.barcodeValue?.let {
                        encrypt(it, "tickets", "barcode_cipher", ticket.ticketId)
                    } ?: kept?.second,
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

    /**
     * The originals to hand to another phone, decrypted and ready to send.
     *
     * Only the ones the sender chose, and only those whose bytes are still on disk — a row whose
     * file went missing is skipped rather than sent as an empty file. Decrypted here because they
     * are about to leave under the transfer's own key, not this device's.
     */
    suspend fun outgoingDocuments(
        documentIds: Collection<String>,
    ): List<com.mateof.passvault.share.OutgoingDocument> =
        documentIds.distinct().mapNotNull { id ->
            val row = documents.byId(id) ?: return@mapNotNull null
            val bytes = documentStore.read(id) ?: return@mapNotNull null
            com.mateof.passvault.share.OutgoingDocument(
                id = row.id,
                eventId = row.eventId,
                mediaType = row.mediaType,
                pageCount = row.pageCount,
                bytes = bytes,
            )
        }

    /** Every original this wallet holds, for the "whole wallet" share to your own other phone. */
    suspend fun allOutgoingDocuments(): List<com.mateof.passvault.share.OutgoingDocument> =
        outgoingDocuments(documents.all().map { it.id })

    /** The decrypted bytes of one document, for rendering. Null if the file is gone. */
    suspend fun documentBytes(documentId: String): ByteArray? = documentStore.read(documentId)

    /**
     * Keeps a document that arrived from somewhere else.
     *
     * Under the identifier it already has, so the two sides agree on which file this is and a
     * second synchronisation does not produce a second copy of the same PDF. Encrypted on the way
     * in like every other document: a file from a server is no more readable at rest than one this
     * phone split itself.
     */
    suspend fun keepDocument(
        id: String,
        eventId: String,
        mediaType: String,
        pageCount: Int,
        bytes: ByteArray,
        now: String = Ids.toInstant(),
    ) {
        documentStore.write(id, bytes)
        documents.upsert(
            DocumentEntity(
                id = id,
                eventId = eventId,
                mediaType = mediaType,
                pageCount = pageCount,
                byteCount = bytes.size,
                createdAt = now,
            ),
        )
    }

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

    /** Whether this very device created the event, which is what entitles it to sign removals. */
    suspend fun isCreatedHere(eventId: String): Boolean =
        log.replay(eventId).events.firstOrNull()?.creatorDeviceId == keys.identity().deviceId

    /**
     * Removes tickets from an event this device created: a tombstone each, then reprojection.
     *
     * Through the log, like every other fact about an event, so the removal travels — to the
     * server at the next synchronisation and from there to every other phone. Reviving a
     * removed ticket is a new ticket; the tombstone is permanent by design.
     */
    suspend fun removeTicketsByOperation(eventId: String, ticketIds: Set<String>) {
        for (ticketId in ticketIds) {
            log.append(
                eventId,
                OperationType.TICKET_REMOVE,
                buildJsonObject { put("ticketId", ticketId) },
            )
        }
        project(log.replay(eventId))
    }

    /**
     * Hides tickets this device has no authority over: their operations are forgotten locally.
     *
     * For an event somebody else created, where a signed removal would rightly be refused. If a
     * server still holds these tickets they return at the next synchronisation — the caller is
     * the one who knows whether that was also dealt with, and says so to the user.
     */
    suspend fun purgeTicketsLocally(eventId: String, ticketIds: Set<String>) {
        log.purgeTickets(eventId, ticketIds)
        for (ticketId in ticketIds) {
            dao.deleteTicket(ticketId)
        }
        project(log.replay(eventId))
    }

    /**
     * Forgets an event on this phone: rows, log, documents, files.
     *
     * Deliberately silent about the server — deleting there is a different act with different
     * authority, and the caller does it (or cannot) and reports accordingly.
     */
    suspend fun purgeEventLocally(eventId: String) {
        log.purgeEvent(eventId)
        for (row in documents.forEvent(eventId)) {
            documentStore.delete(row.id)
            documents.delete(row.id)
        }
        dao.deleteTicketsOf(eventId)
        dao.deleteEvent(eventId)
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
