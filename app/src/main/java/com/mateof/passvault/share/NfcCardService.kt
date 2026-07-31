package com.mateof.passvault.share

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * The side of the tap that is touched.
 *
 * Android Beam is the API everybody remembers for this and it is gone: deprecated in Android 10 and
 * removed in 14, replaced by Quick Share, which is Bluetooth and Wi-Fi rather than NFC and has no
 * interface for an app to use. What is left for phone-to-phone NFC is card emulation — one phone
 * pretends to be a contactless card and the other reads it — which is what this is.
 *
 * It answers exactly two commands: SELECT, naming the application, and READ, which returns the
 * handover. Nothing else is answered, and nothing is read from the caller: the phone doing the
 * reading is untrusted, and the only thing it needs is a public key, a token that authorises one
 * session, and an address.
 *
 * What is served changes only when a share screen is open. A phone with nothing to offer answers
 * that it has nothing, rather than leaving a stale key on a tag that no longer has a socket behind
 * it.
 */
class NfcCardService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val command = commandApdu ?: return UNKNOWN
        return when {
            isSelect(command) -> OK
            isRead(command) -> NfcHandoverSource.current()?.let { it + OK } ?: NOT_FOUND
            else -> UNKNOWN
        }
    }

    /** The field went away: the phones were separated. Nothing to undo — nothing was committed. */
    override fun onDeactivated(reason: Int) = Unit

    private fun isSelect(command: ByteArray): Boolean =
        command.size >= 4 && command[0] == 0x00.toByte() && command[1] == 0xA4.toByte()

    private fun isRead(command: ByteArray): Boolean =
        command.size >= 2 && command[0] == 0x80.toByte() && command[1] == READ_INSTRUCTION

    private companion object {
        const val READ_INSTRUCTION = 0xB0.toByte()

        /** Status words, as an ISO 7816 reader expects them. */
        val OK = byteArrayOf(0x90.toByte(), 0x00)
        val NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        val UNKNOWN = byteArrayOf(0x6D.toByte(), 0x00)
    }
}

/**
 * What the card is currently offering.
 *
 * Process-wide, because the service is constructed by the system when a field appears and has no
 * connection to whichever screen is open. It holds one handover at a time: a phone advertises one
 * transfer, and offering two would mean a reader could not tell which socket it was about to be
 * pointed at.
 */
object NfcHandoverSource {

    @Volatile
    private var offered: ByteArray? = null

    fun offer(handover: NfcHandover) {
        offered = handover.encode()
    }

    /** Called when the share screen goes away. A tag with nothing behind it must answer nothing. */
    fun withdraw() {
        offered = null
    }

    fun current(): ByteArray? = offered
}
