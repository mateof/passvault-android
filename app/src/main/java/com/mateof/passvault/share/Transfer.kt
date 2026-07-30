package com.mateof.passvault.share

import com.mateof.passvault.crypto.Base64Url
import com.mateof.passvault.sync.Operation
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The conversation two phones have.
 *
 * ```
 *   HELLO        ──►   plaintext: version, device, ephemeral key
 *        ◄──  HELLO
 *   (both derive the same six digits; the users compare them out loud)
 *   CONFIRM      ──►   through the session
 *        ◄──  CONFIRM
 *   SYNC_REQUEST ──►   operations I hold, and the cursor I last saw
 *        ◄──  SYNC_RESPONSE
 * ```
 *
 * The confirmation is sent *through* the encrypted session rather than in the open, and that is what
 * turns the six digits from a ritual into a check. A device sitting in the middle holds a working
 * session with each side but two different keys, so its relayed confirmation fails to authenticate
 * and the receiving side learns it is being attacked — even if a careless user waved the digits
 * through.
 *
 * The keys agreed here are ephemeral, one pair per attempt. The device's long-term agreement key is
 * announced but not used for the exchange, so a phone that is later compromised does not hand over
 * transfers somebody recorded last year.
 */
object Transfer {

    private const val HELLO = "hello"
    private const val CONFIRM = "confirm"

    /**
     * Exchanges greetings and works out the digits.
     *
     * Nothing has been authenticated when this returns. The caller must show
     * [PairedPeer.shortAuthenticationString] to the user and only then call [confirm].
     */
    fun greet(
        input: InputStream,
        output: OutputStream,
        deviceId: String,
        signingPublicKey: ByteArray,
        displayName: String,
        isInitiator: Boolean,
    ): PairedPeer {
        val ephemeral = LocalPairing.generateKeys()

        val greeting = buildJsonObject {
            put("kind", HELLO)
            put("version", TransferProtocol.VERSION)
            put("deviceId", deviceId)
            put("name", displayName)
            put("ephemeralPublicKey", Base64Url.encode(ephemeral.publicKey))
            put("signingPublicKey", Base64Url.encode(signingPublicKey))
        }

        // Both sides write before either reads. Reading first on both ends is a deadlock, and
        // ordering it by role would make the initiator wait a round trip for no reason.
        TransferProtocol.writeFrame(output, greeting.toString().toByteArray(Charsets.UTF_8))
        val peerGreeting = parse(TransferProtocol.readFrame(input))

        if (peerGreeting.string("kind") != HELLO) {
            throw TransferException(TransferError.PROTOCOL, "expected a greeting")
        }
        val version = peerGreeting["version"]?.jsonPrimitive?.content?.toIntOrNull()
        if (version != TransferProtocol.VERSION) {
            throw TransferException(
                TransferError.UNSUPPORTED_VERSION,
                "the other phone speaks version $version, this one speaks ${TransferProtocol.VERSION}",
            )
        }

        val peerEphemeral = peerGreeting.key("ephemeralPublicKey")
        val result = LocalPairing.complete(ephemeral, peerEphemeral, isInitiator)

        return PairedPeer(
            deviceId = peerGreeting.string("deviceId")
                ?: throw TransferException(TransferError.PROTOCOL, "the greeting named no device"),
            displayName = peerGreeting.string("name") ?: "",
            signingPublicKey = peerGreeting.key("signingPublicKey"),
            shortAuthenticationString = result.shortAuthenticationString,
            session = TransferSession(input, output, result.sessionKey, isInitiator),
        )
    }

    /**
     * Says the digits matched, and checks the other side says so too.
     *
     * A frame that fails to authenticate here is the interposed device, not a network glitch, and is
     * reported as [TransferError.DIGITS_MISMATCH] rather than as a generic failure — the user needs
     * to be told they were attacked, not that something went wrong.
     */
    fun confirm(peer: PairedPeer) {
        peer.session.send(buildJsonObject { put("kind", CONFIRM) }.toString().toByteArray(Charsets.UTF_8))
        val reply = try {
            parse(peer.session.receive())
        } catch (cause: TransferException) {
            if (cause.code == TransferError.TAMPERED) {
                throw TransferException(
                    TransferError.DIGITS_MISMATCH,
                    "the other phone derived different digits, which means something is relaying this",
                    cause,
                )
            }
            throw cause
        }
        if (reply.string("kind") != CONFIRM) {
            throw TransferException(TransferError.PROTOCOL, "expected a confirmation")
        }
    }

    /**
     * Offers everything this device holds and takes everything it lacks, in one round.
     *
     * One message each way rather than a page-by-page conversation per event. The first design
     * iterated the events each side knew about, which deadlocked the moment one of them knew about
     * none: its loop body never ran, so it never answered, and the other waited forever. Sending the
     * whole log is also what the `.tkpak` transport does, and an event has hundreds of operations,
     * not millions.
     */
    fun requestSync(peer: PairedPeer, operations: List<Operation>): SyncExchange {
        val request = buildJsonObject {
            put("kind", "sync.request")
            putJsonArray("operations") { operations.forEach { add(it.signedJson()) } }
        }
        peer.session.send(request.toString().toByteArray(Charsets.UTF_8))
        return readExchange(parse(peer.session.receive()))
    }

    /** Reads a peer's request. The answering side of [requestSync]. */
    fun readRequest(peer: PairedPeer): SyncExchange = readExchange(parse(peer.session.receive()))

    fun respond(peer: PairedPeer, operations: List<Operation>) {
        val response = buildJsonObject {
            put("kind", "sync.response")
            putJsonArray("operations") { operations.forEach { add(it.signedJson()) } }
        }
        peer.session.send(response.toString().toByteArray(Charsets.UTF_8))
    }

    private fun readExchange(message: JsonObject): SyncExchange {
        val operations = message["operations"]?.jsonArray.orEmpty()
        return SyncExchange(
            kind = message.string("kind") ?: "",
            eventId = message.string("eventId"),
            cursor = message.string("cursor") ?: "",
            hasMore = message["hasMore"]?.jsonPrimitive?.content == "true",
            operations = operations.map { operationFrom(it.jsonObject) },
        )
    }

    fun operationFrom(source: JsonObject): Operation {
        val actor = source["actorUserId"]?.jsonPrimitive
        return Operation(
            operationId = source.string("operationId").orEmpty(),
            deviceId = source.string("deviceId").orEmpty(),
            actorUserId = actor?.let { if (it.isString) it.content else null },
            lamport = source["lamport"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            wallClock = source.string("wallClock").orEmpty(),
            eventId = source["scope"]?.jsonObject?.string("id").orEmpty(),
            type = source.string("type").orEmpty(),
            body = source["body"]?.jsonObject ?: JsonObject(emptyMap()),
            signature = source.string("signature"),
        )
    }

    private fun parse(bytes: ByteArray): JsonObject =
        runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }
            .getOrElse { throw TransferException(TransferError.PROTOCOL, "a frame was not an object", it) }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.let { if (it.isString) it.content else null }

    private fun JsonObject.key(name: String): ByteArray =
        runCatching { Base64Url.decodeExact(string(name)!!, 32) }
            .getOrElse { throw TransferException(TransferError.PROTOCOL, "$name is not a 32-byte key", it) }
}

/** The other phone, once greeted. Not yet trusted: the digits decide that. */
class PairedPeer(
    val deviceId: String,
    val displayName: String,
    val signingPublicKey: ByteArray,
    val shortAuthenticationString: String,
    val session: TransferSession,
)

data class SyncExchange(
    val kind: String,
    val eventId: String?,
    val cursor: String,
    val hasMore: Boolean,
    val operations: List<Operation>,
)
