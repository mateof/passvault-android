package com.mateof.passvault.sync

import com.mateof.passvault.crypto.Base64Url
import com.mateof.passvault.crypto.Primitives
import com.mateof.passvault.data.DeviceIdentity
import com.mateof.passvault.data.Ids
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One entry of the signed operation log, as specified in `docs/spec/sync-protocol.md`.
 *
 * The log, not the state, is what devices exchange. Two phones that both moved a ticket from FREE to
 * CLAIMED hold identical state and different histories, and since deciding who was first is this
 * product's hardest case, the history is the thing worth keeping.
 *
 * This mirrors `apps/server/src/operations.ts`. Every field name and every rule below has a
 * counterpart there, and `OperationSigningTest` pins the two together — a divergence in how the
 * canonical form is built is a signature the other side rejects, which looks like tampering.
 */
data class Operation(
    val operationId: String,
    val deviceId: String,
    val actorUserId: String?,
    val lamport: Long,
    val wallClock: String,
    val eventId: String,
    val type: String,
    val body: JsonObject,
    /** Ed25519 over [signingInput], base64url. Absent until the operation is signed. */
    val signature: String? = null,
) {
    /**
     * The operation as JSON, without its signature.
     *
     * The scope is nested here rather than stored nested, because a device only ever scopes an
     * operation to an event and a flat column is what the query needs.
     */
    fun unsignedJson(): JsonObject = buildJsonObject {
        put("operationId", operationId)
        put("deviceId", deviceId)
        // Present and null rather than absent, matching the server: a device with no account still
        // says so, so both sides serialise the same key set.
        if (actorUserId == null) put("actorUserId", JsonNull) else put("actorUserId", actorUserId)
        put("lamport", lamport)
        put("wallClock", wallClock)
        put(
            "scope",
            buildJsonObject {
                put("kind", "event")
                put("id", eventId)
            },
        )
        put("type", type)
        put("body", body)
    }

    fun signedJson(): JsonObject = JsonObject(
        unsignedJson() + ("signature" to JsonPrimitive(requireNotNull(signature) { "unsigned" })),
    )

    fun signedWith(identity: DeviceIdentity): Operation =
        copy(signature = Base64Url.encode(identity.sign(signingInput())))

    fun signingInput(): ByteArray = Operations.signingInput(unsignedJson())

    fun verifiedBy(signingPublicKey: ByteArray): Boolean {
        val signature = this.signature ?: return false
        val bytes = runCatching { Base64Url.decode(signature) }.getOrNull() ?: return false
        return Primitives.verifyEd25519(signingPublicKey, signingInput(), bytes)
    }
}

object Operations {

    const val DOMAIN = "passvault/v1/operation"

    /**
     * The canonical bytes an operation is signed over: keys sorted, no whitespace, no signature.
     *
     * This is the one canonicalisation rule the project accepts, and `.tkpak` deliberately refuses
     * one. A manifest is stored bytes, so "sign exactly what is on disk" works — the bytes exist. An
     * operation is re-serialised at every hop: a phone reads it from a file, holds it in Room, and
     * later posts it as JSON. There are no original bytes to preserve, so the rule has to be one
     * that two implementations can each reproduce from scratch.
     */
    fun canonicalBytes(value: JsonElement): ByteArray =
        canonicalJson(value).toByteArray(Charsets.UTF_8)

    fun signingInput(unsigned: JsonElement): ByteArray =
        Primitives.domainSeparated(DOMAIN, Primitives.sha256(canonicalBytes(unsigned)))

    private fun canonicalJson(value: JsonElement): String = when (value) {
        is JsonNull -> "null"
        is JsonPrimitive -> if (value.isString) quote(value.content) else value.content
        is JsonArray -> value.joinToString(",", "[", "]") { canonicalJson(it) }
        is JsonObject -> value.entries
            .sortedBy { it.key }
            .joinToString(",", "{", "}") { "${quote(it.key)}:${canonicalJson(it.value)}" }
    }

    /**
     * A JSON string, escaped the way both implementations escape it.
     *
     * Written out rather than delegated, because the two runtimes disagree by default and the
     * signature covers these bytes. `JSON.stringify` escapes exactly the control characters below
     * and leaves every other code point — including non-ASCII — literal; a serialiser that escaped
     * accents as `\uXXXX` would produce different bytes for the same Galician venue name.
     */
    private fun quote(text: String): String = buildString(text.length + 2) {
        append('"')
        for (character in text) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

    /**
     * The order every participant computes independently.
     *
     * `(lamport, sha256(deviceId))` ascending. The device hash breaks ties deterministically so two
     * devices replaying the same operations reach the same outcome without trusting any clock. Wall
     * clocks are never used to order: a phone whose date is a week out must not win or lose a race
     * because of it.
     */
    val LOGICAL_ORDER: Comparator<Operation> = compareBy<Operation> { it.lamport }
        .thenBy { deviceHash(it.deviceId) }
        .thenBy { it.operationId }

    /**
     * Rebuilds an operation from the JSON a peer or the server sent.
     *
     * The signature travels with it and is checked later, on acceptance — parsing is not the place
     * to decide whether something is genuine.
     */
    fun fromSignedJson(source: JsonObject): Operation {
        val actor = source["actorUserId"]?.jsonPrimitive
        return Operation(
            operationId = source.text("operationId").orEmpty(),
            deviceId = source.text("deviceId").orEmpty(),
            actorUserId = actor?.let { if (it.isString) it.content else null },
            lamport = source["lamport"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            wallClock = source.text("wallClock").orEmpty(),
            eventId = source["scope"]?.jsonObject?.text("id").orEmpty(),
            type = source.text("type").orEmpty(),
            body = source["body"]?.jsonObject ?: JsonObject(emptyMap()),
            signature = source.text("signature"),
        )
    }

    private fun JsonObject.text(key: String): String? =
        this[key]?.jsonPrimitive?.let { if (it.isString) it.content else null }

    fun deviceHash(deviceId: String): String =
        Base64Url.encode(Primitives.sha256(deviceId.toByteArray(Charsets.UTF_8)))

    /**
     * Builds an operation this device is about to issue.
     *
     * `lamport` is passed in rather than read here: the counter belongs to the log, and an operation
     * built with a stale value loses races it should have won.
     */
    fun create(
        identity: DeviceIdentity,
        eventId: String,
        type: String,
        body: JsonObject,
        lamport: Long,
        actorUserId: String? = null,
    ): Operation = Operation(
        operationId = Ids.newId(),
        deviceId = identity.deviceId,
        actorUserId = actorUserId,
        lamport = lamport,
        wallClock = Ids.toInstant(),
        eventId = eventId,
        type = type,
        body = body,
    ).signedWith(identity)
}

/**
 * The operation types this version knows.
 *
 * Anything else is retained rather than dropped. The log is append-only and a reader that learns a
 * type in a later release can still replay what it kept, so an unknown type goes to quarantine.
 */
object OperationType {
    const val EVENT_CREATE = "event.create"
    const val EVENT_UPDATE = "event.update"
    const val TICKET_ADD = "ticket.add"
    const val TICKET_REMOVE = "ticket.remove"
    const val TICKET_ASSIGN = "ticket.assign"
    const val TICKET_UNASSIGN = "ticket.unassign"
    const val TICKET_TRANSFER = "ticket.transfer"
    const val CLAIM_COUPON_ISSUE = "claim.coupon.issue"
    const val CLAIM_REQUEST = "claim.request"
    const val CLAIM_CONFIRM = "claim.confirm"
    const val CLAIM_REJECT = "claim.reject"
    const val PAYMENT_SET = "payment.set"
    const val DEVICE_REGISTER = "device.register"

    val APPLIED: Set<String> = setOf(
        EVENT_CREATE,
        EVENT_UPDATE,
        TICKET_ADD,
        TICKET_REMOVE,
        TICKET_ASSIGN,
        TICKET_UNASSIGN,
        TICKET_TRANSFER,
        CLAIM_COUPON_ISSUE,
        CLAIM_REQUEST,
        CLAIM_CONFIRM,
        CLAIM_REJECT,
        PAYMENT_SET,
        DEVICE_REGISTER,
    )

    /**
     * Types only the event's creator may issue.
     *
     * Enforced when replaying, on every device, rather than trusted from whoever sent it — which is
     * what stops a compromised server injecting an assignment as well as a dishonest peer.
     */
    val CREATOR_ONLY: Set<String> = setOf(
        EVENT_UPDATE,
        TICKET_ADD,
        TICKET_REMOVE,
        TICKET_ASSIGN,
        TICKET_UNASSIGN,
        CLAIM_COUPON_ISSUE,
        PAYMENT_SET,
    )

    /** Types only the event's authority may issue: the server, or the creator's device if local. */
    val AUTHORITY_ONLY: Set<String> = setOf(CLAIM_CONFIRM, CLAIM_REJECT)
}
