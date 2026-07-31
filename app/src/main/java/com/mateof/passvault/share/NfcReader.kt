package com.mateof.passvault.share

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep

/**
 * The side of the tap that touches.
 *
 * Reader mode rather than the foreground dispatch: it takes over NFC for as long as this screen is
 * up, so the system does not open whatever app claims the tag, and it lets the platform sounds be
 * turned off — a beep announcing a tag the app is about to handle itself is noise.
 *
 * The exchange is two commands. SELECT names the application, so the phone being touched answers
 * as PassVault rather than as a payment card; READ asks for the handover. `IsoDep` is what carries
 * them, and it is the only technology asked for: a bus pass held near the phone is not a mistake
 * worth reporting, it is simply not this.
 */
class NfcReader(private val activity: Activity) {

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    val available: Boolean get() = adapter != null

    val enabled: Boolean get() = adapter?.isEnabled == true

    /**
     * Starts listening for a phone to be held against this one.
     *
     * `onRead` runs on the NFC thread, not the main one — the caller has to move to wherever it
     * intends to do the work, and doing a socket connection here is exactly right because doing it
     * on the main thread would not be.
     */
    fun start(onRead: (NfcHandover) -> Unit, onFailure: (TransferException) -> Unit) {
        val nfc = adapter ?: return
        nfc.enableReaderMode(
            activity,
            { tag -> read(tag, onRead, onFailure) },
            // Type A only, which is what an Android phone emulating a card presents. Platform
            // sounds off: this screen says what happened, and a beep for a tag it then rejects is
            // worse than silence.
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            null,
        )
    }

    fun stop() {
        adapter?.disableReaderMode(activity)
    }

    private fun read(
        tag: Tag,
        onRead: (NfcHandover) -> Unit,
        onFailure: (TransferException) -> Unit,
    ) {
        val isoDep = IsoDep.get(tag) ?: return
        try {
            isoDep.connect()
            // Generous, because the phones are being held together by a human hand and a strict
            // timeout turns a slight wobble into a failure the user cannot interpret.
            isoDep.timeout = 5_000

            val selected = isoDep.transceive(SELECT)
            if (!endsWithOk(selected)) {
                throw TransferException(
                    TransferError.PROTOCOL,
                    "the other phone did not answer as PassVault",
                )
            }

            val answer = isoDep.transceive(READ)
            if (!endsWithOk(answer) || answer.size <= 2) {
                throw TransferException(
                    TransferError.PROTOCOL,
                    "the other phone has nothing to hand over; open its share screen first",
                )
            }
            onRead(NfcHandover.decode(answer.copyOfRange(0, answer.size - 2)))
        } catch (cause: TransferException) {
            onFailure(cause)
        } catch (cause: Exception) {
            // Almost always the phones being separated mid-exchange, which is a thing to say
            // plainly rather than a stack trace to swallow.
            onFailure(TransferException(TransferError.PROTOCOL, "the tap did not complete", cause))
        } finally {
            runCatching { isoDep.close() }
        }
    }

    private fun endsWithOk(response: ByteArray?): Boolean =
        response != null &&
            response.size >= 2 &&
            response[response.size - 2] == 0x90.toByte() &&
            response[response.size - 1] == 0x00.toByte()

    private companion object {
        /** SELECT by name, carrying the application identifier declared in `apduservice.xml`. */
        val SELECT = byteArrayOf(
            0x00, 0xA4.toByte(), 0x04, 0x00, 0x07,
            0xF0.toByte(), 0x50, 0x41, 0x53, 0x53, 0x56, 0x54,
            0x00,
        )

        /** A vendor command: give me the handover. Expected length 0 means "as much as you have". */
        val READ = byteArrayOf(0x80.toByte(), 0xB0.toByte(), 0x00, 0x00, 0x00)
    }
}
