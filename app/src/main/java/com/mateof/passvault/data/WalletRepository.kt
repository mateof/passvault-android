package com.mateof.passvault.data

import com.mateof.passvault.crypto.Primitives
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
     * One ticket, decrypted, including its barcode.
     *
     * The barcode is decrypted here and nowhere else, because this is the only screen that shows
     * one — the list would otherwise decrypt forty payloads to draw rows that never display them.
     */
    suspend fun detail(ticketId: String): com.mateof.passvault.ui.ticket.TicketDetail? {
        val row = dao.ticket(ticketId) ?: return null
        return com.mateof.passvault.ui.ticket.TicketDetail(
            id = row.id,
            eventName = decrypt(row.eventNameCipher, "events", "name_cipher", row.eventId) ?: "",
            label = decrypt(row.labelCipher, "tickets", "label_cipher", row.id),
            seat = decrypt(row.seatCipher, "tickets", "seat_cipher", row.id),
            barcodeFormat = row.barcodeFormat,
            barcodeValue = decrypt(row.barcodeCipher, "tickets", "barcode_cipher", row.id),
            holderLabel = decrypt(row.holderLabelCipher, "tickets", "holder_label_cipher", row.id),
            isProvisional = row.assignmentState == "PROVISIONAL",
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
