package com.mateof.passvault.share

import com.mateof.passvault.crypto.Primitives
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * What travels over the wire between two phones.
 *
 * Two layers, kept apart on purpose. Framing is length-prefixed bytes and knows nothing about
 * meaning; the session applies AES-GCM under the key the pairing derived. Everything after the
 * handshake goes through the session, so nothing readable crosses the network — including on the
 * café Wi-Fi where the discovery that found the peer authenticated nobody.
 *
 * The nonce is a counter rather than random, and the two directions use separate keys. Reusing a
 * key and nonce pair in GCM loses confidentiality and forges messages, and with two peers writing
 * independently, random nonces on a shared key is exactly how that happens.
 */
object TransferProtocol {

    const val SERVICE_TYPE = "_passvault._tcp"

    /** Bumped when the wire format changes in a way an older build cannot read. */
    const val VERSION = 1

    /** A frame no honest peer sends, so a hostile one cannot make this allocate a gigabyte. */
    const val MAX_FRAME_BYTES = 8 * 1024 * 1024

    private const val INITIATOR_INFO = "passvault/v1/transfer/initiator"
    private const val RESPONDER_INFO = "passvault/v1/transfer/responder"

    fun writeFrame(out: OutputStream, payload: ByteArray) {
        require(payload.size <= MAX_FRAME_BYTES) { "frame is larger than the limit" }
        DataOutputStream(out).apply {
            writeInt(payload.size)
            write(payload)
            flush()
        }
    }

    fun readFrame(input: InputStream): ByteArray {
        val stream = DataInputStream(input)
        val length = try {
            stream.readInt()
        } catch (_: EOFException) {
            throw TransferException(TransferError.CONNECTION_LOST, "the peer closed the connection")
        }
        if (length < 0 || length > MAX_FRAME_BYTES) {
            // Checked before allocating, not after. A length field is the first thing a hostile peer
            // reaches for, and `ByteArray(length)` on an attacker's number is the classic way to be
            // told to allocate more memory than the phone has.
            throw TransferException(TransferError.PROTOCOL, "frame length $length is out of range")
        }
        val payload = ByteArray(length)
        stream.readFully(payload)
        return payload
    }

    /**
     * The two directional keys, derived from the one the pairing produced.
     *
     * Separate keys per direction so the counters cannot collide: both sides start at zero, and with
     * a single shared key that would be immediate nonce reuse.
     */
    fun directionalKeys(sessionKey: ByteArray, isInitiator: Boolean): DirectionalKeys {
        val initiatorKey = Primitives.hkdf(sessionKey, ByteArray(0), INITIATOR_INFO)
        val responderKey = Primitives.hkdf(sessionKey, ByteArray(0), RESPONDER_INFO)
        return if (isInitiator) {
            DirectionalKeys(send = initiatorKey, receive = responderKey)
        } else {
            DirectionalKeys(send = responderKey, receive = initiatorKey)
        }
    }

    /** A 96-bit nonce built from a counter: eight zero bytes then the big-endian count. */
    fun nonceOf(counter: Long): ByteArray {
        val nonce = ByteArray(Primitives.NONCE_BYTES)
        for (index in 0 until 8) {
            nonce[Primitives.NONCE_BYTES - 1 - index] = (counter shr (8 * index) and 0xFF).toByte()
        }
        return nonce
    }
}

data class DirectionalKeys(val send: ByteArray, val receive: ByteArray) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/**
 * An open, authenticated conversation with the other phone.
 *
 * Created only after both users have compared the six digits. Before that the keys exist but nothing
 * is sent through them, which is the entire point of the short authentication string: a device in
 * the middle has a working session with each side and cannot make the two screens agree.
 */
class TransferSession(
    private val input: InputStream,
    private val output: OutputStream,
    sessionKey: ByteArray,
    isInitiator: Boolean,
) {
    private val keys = TransferProtocol.directionalKeys(sessionKey, isInitiator)
    private var sent = 0L
    private var received = 0L

    fun send(payload: ByteArray) {
        val nonce = TransferProtocol.nonceOf(sent)
        // The counter is authenticated, so a frame cannot be replayed at a different position in
        // the conversation or reordered without the tag failing.
        val sealed = Primitives.seal(keys.send, nonce, payload, aad(sent))
        sent += 1
        TransferProtocol.writeFrame(output, sealed)
    }

    fun receive(): ByteArray {
        val frame = TransferProtocol.readFrame(input)
        val nonce = TransferProtocol.nonceOf(received)
        val opened = try {
            Primitives.open(keys.receive, nonce, frame, aad(received))
        } catch (cause: Exception) {
            throw TransferException(
                TransferError.TAMPERED,
                "a frame did not authenticate at position $received",
                cause,
            )
        }
        received += 1
        return opened
    }

    private fun aad(counter: Long) = "passvault/v1/transfer:$counter"
}

enum class TransferError {
    /** The digits did not match. Presented as a detected attack, never as a glitch. */
    DIGITS_MISMATCH,
    TAMPERED,
    PROTOCOL,
    CONNECTION_LOST,
    UNSUPPORTED_VERSION,
    CANCELLED,
}

class TransferException(
    val code: TransferError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
