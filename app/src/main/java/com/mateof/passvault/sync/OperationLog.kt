package com.mateof.passvault.sync

import com.mateof.passvault.crypto.Base64Url
import com.mateof.passvault.crypto.Primitives
import com.mateof.passvault.data.DeviceEntity
import com.mateof.passvault.data.DeviceKeys
import com.mateof.passvault.data.Ids
import com.mateof.passvault.data.OperationDao
import com.mateof.passvault.data.OperationEntity
import com.mateof.passvault.data.OperationReason
import com.mateof.passvault.data.OperationState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The log this device keeps, and the rules for letting something into it.
 *
 * Acceptance and replay are deliberately separate jobs. Acceptance asks "is this genuine and is it
 * for me" — identity, signature, scope — and is where an operation from an unknown device is
 * retained rather than dropped. Replay asks "what does the wallet look like given everything I
 * hold", and is where authorisation and the claim rules live, because those depend on the log as a
 * whole rather than on one message.
 *
 * Mirrors `apps/server/src/operations.ts`, with one deliberate difference recorded here: the server
 * additionally encrypts an operation body with the event key, so that a compromised server cannot
 * read what it stores. On the device the body is encrypted with the vault key at rest and with the
 * session key in flight, and there is no third party to hide it from — the event-key layer is what
 * this needs when the app starts syncing to a server, and it is not here yet.
 */
class OperationLog(
    private val dao: OperationDao,
    private val keys: DeviceKeys,
) {
    private val json = Json { encodeDefaults = true }

    private fun JsonObject.text(key: String): String? =
        this[key]?.jsonPrimitive?.let { if (it.isString) it.content else null }

    private fun aad(operationId: String) = "passvault/v1/field:operations.body_cipher:$operationId"

    private fun encryptBody(body: JsonObject, operationId: String): ByteArray {
        val nonce = Primitives.randomNonce()
        return nonce + Primitives.seal(
            keys.vaultKey(),
            nonce,
            json.encodeToString(JsonObject.serializer(), body).toByteArray(Charsets.UTF_8),
            aad(operationId),
        )
    }

    private fun decryptBody(stored: ByteArray, operationId: String): JsonObject {
        val nonce = stored.copyOfRange(0, Primitives.NONCE_BYTES)
        val ciphertext = stored.copyOfRange(Primitives.NONCE_BYTES, stored.size)
        val plain = Primitives.open(keys.vaultKey(), nonce, ciphertext, aad(operationId))
        return Json.parseToJsonElement(plain.toString(Charsets.UTF_8)).jsonObject
    }

    private fun OperationEntity.toOperation() = Operation(
        operationId = operationId,
        deviceId = deviceId,
        actorUserId = actorUserId,
        lamport = lamport,
        wallClock = wallClock,
        eventId = eventId,
        type = type,
        body = decryptBody(bodyCipher, operationId),
        signature = signature,
    )

    private fun Operation.toEntity(state: String, reason: String?, receivedAt: String) = OperationEntity(
        operationId = operationId,
        eventId = eventId,
        deviceId = deviceId,
        actorUserId = actorUserId,
        lamport = lamport,
        wallClock = wallClock,
        type = type,
        bodyCipher = encryptBody(body, operationId),
        signature = requireNotNull(signature) { "an unsigned operation is never stored" },
        state = state,
        reason = reason,
        receivedAt = receivedAt,
    )

    /**
     * The next logical clock value for an event.
     *
     * One more than everything this device has seen for it, which is the Lamport rule. A device that
     * has been offline does not guess: it raises its counter to at least what arrived.
     */
    suspend fun nextLamport(eventId: String): Long = (dao.maxLamport(eventId) ?: 0L) + 1

    /** Produces, signs and stores an operation this device is issuing. */
    suspend fun append(
        eventId: String,
        type: String,
        body: JsonObject,
        actorUserId: String? = null,
    ): Operation {
        val operation = Operations.create(
            identity = keys.identity(),
            eventId = eventId,
            type = type,
            body = body,
            lamport = nextLamport(eventId),
            actorUserId = actorUserId,
        )
        // Its own device row, so that replaying its own operations verifies like anybody else's
        // rather than through a special case that would never be exercised.
        rememberSelf()
        dao.insert(operation.toEntity(OperationState.APPLIED, null, Ids.toInstant()))
        return operation
    }

    suspend fun rememberSelf() {
        val identity = keys.identity()
        if (dao.device(identity.deviceId) == null) {
            dao.upsertDevice(
                DeviceEntity(
                    id = identity.deviceId,
                    signingPublicKey = Base64Url.encode(identity.signingPublicKey),
                    agreementPublicKey = Base64Url.encode(identity.agreementPublicKey),
                    userId = null,
                    name = null,
                    status = "ACTIVE",
                ),
            )
        }
    }

    /**
     * Takes in a batch from a peer.
     *
     * Idempotent by `operationId`: retrying an interrupted transfer is safe, and a duplicate is
     * counted rather than applied. That is what lets a device push without tracking what got
     * through.
     */
    suspend fun accept(operations: List<Operation>): List<AcceptOutcome> {
        val outcomes = mutableListOf<AcceptOutcome>()
        for (operation in operations) {
            outcomes += acceptOne(operation)
        }
        // A batch can contain the registration that makes an earlier message checkable, so quarantine
        // is swept once at the end rather than per message.
        for (deviceId in operations.map { it.deviceId }.toSet()) {
            reconsiderQuarantine(deviceId)
        }
        return outcomes
    }

    private suspend fun acceptOne(operation: Operation): AcceptOutcome {
        if (dao.byId(operation.operationId) != null) {
            return AcceptOutcome(operation.operationId, AcceptState.DUPLICATE, null)
        }
        val receivedAt = Ids.toInstant()

        // A device registration carries the key that checks it, so it is self-describing: verify it
        // against the key it announces, then remember that key.
        if (operation.type == OperationType.DEVICE_REGISTER) {
            return acceptRegistration(operation, receivedAt)
        }

        val device = dao.device(operation.deviceId)
        if (device == null) {
            // Retained, not dropped. An unknown device is usually a peer whose key has not been
            // exchanged yet, so it waits where the user can see it and is reconsidered later.
            dao.insert(
                operation.toEntity(
                    OperationState.QUARANTINED,
                    OperationReason.UNKNOWN_DEVICE,
                    receivedAt,
                ),
            )
            return AcceptOutcome(
                operation.operationId,
                AcceptState.QUARANTINED,
                OperationReason.UNKNOWN_DEVICE,
            )
        }

        if (!operation.verifiedBy(Base64Url.decode(device.signingPublicKey))) {
            dao.insert(
                operation.toEntity(OperationState.REJECTED, OperationReason.BAD_SIGNATURE, receivedAt),
            )
            return AcceptOutcome(
                operation.operationId,
                AcceptState.REJECTED,
                OperationReason.BAD_SIGNATURE,
            )
        }

        if (operation.type !in OperationType.APPLIED) {
            // Kept for a later version rather than discarded. The log is append-only, and a reader
            // that learns the type in a future release can still replay what it kept.
            dao.insert(
                operation.toEntity(OperationState.QUARANTINED, OperationReason.UNKNOWN_TYPE, receivedAt),
            )
            return AcceptOutcome(
                operation.operationId,
                AcceptState.QUARANTINED,
                OperationReason.UNKNOWN_TYPE,
            )
        }

        dao.insert(operation.toEntity(OperationState.APPLIED, null, receivedAt))
        return AcceptOutcome(operation.operationId, AcceptState.APPLIED, null)
    }

    private suspend fun acceptRegistration(operation: Operation, receivedAt: String): AcceptOutcome {
        val announced = operation.body.text("signingPublicKey")?.let {
            runCatching { Base64Url.decodeExact(it, 32) }.getOrNull()
        }
        if (announced == null || !operation.verifiedBy(announced)) {
            dao.insert(
                operation.toEntity(OperationState.REJECTED, OperationReason.BAD_SIGNATURE, receivedAt),
            )
            return AcceptOutcome(
                operation.operationId,
                AcceptState.REJECTED,
                OperationReason.BAD_SIGNATURE,
            )
        }
        dao.upsertDevice(
            DeviceEntity(
                id = operation.deviceId,
                signingPublicKey = Base64Url.encode(announced),
                agreementPublicKey = operation.body.text("agreementPublicKey"),
                userId = operation.actorUserId,
                name = operation.body.text("name"),
                status = "ACTIVE",
            ),
        )
        dao.insert(operation.toEntity(OperationState.APPLIED, null, receivedAt))
        return AcceptOutcome(operation.operationId, AcceptState.APPLIED, null)
    }

    /**
     * Re-examines what was held back once a device becomes known.
     *
     * This is the payoff for quarantining rather than dropping: the ordinary case is a peer whose
     * key arrived in the same batch as, or after, the operations it signed.
     */
    suspend fun reconsiderQuarantine(deviceId: String) {
        val device = dao.device(deviceId) ?: return
        val publicKey = Base64Url.decode(device.signingPublicKey)
        for (entity in dao.quarantinedFrom(deviceId)) {
            if (entity.reason != OperationReason.UNKNOWN_DEVICE) continue
            val operation = entity.toOperation()
            if (!operation.verifiedBy(publicKey)) {
                dao.setState(entity.operationId, OperationState.REJECTED, OperationReason.BAD_SIGNATURE)
            } else if (operation.type !in OperationType.APPLIED) {
                dao.setState(entity.operationId, OperationState.QUARANTINED, OperationReason.UNKNOWN_TYPE)
            } else {
                dao.setState(entity.operationId, OperationState.APPLIED, null)
            }
        }
    }

    /** Registers this device into its own log, so a peer receiving the log can check its signatures. */
    suspend fun registerSelf(eventId: String, name: String?): Operation {
        val identity = keys.identity()
        val body = buildJsonObject {
            put("deviceId", identity.deviceId)
            put("signingPublicKey", Base64Url.encode(identity.signingPublicKey))
            put("agreementPublicKey", Base64Url.encode(identity.agreementPublicKey))
            if (name != null) put("name", name)
        }
        return append(eventId, OperationType.DEVICE_REGISTER, body)
    }

    /** A page of operations a peer has not been given, in arrival order. */
    suspend fun since(eventId: String, cursor: String, limit: Int = 200): SyncPage {
        val rows = dao.since(eventId, cursor, limit + 1)
        val hasMore = rows.size > limit
        val page = if (hasMore) rows.take(limit) else rows
        return SyncPage(
            operations = page.map { it.toOperation() },
            cursor = page.lastOrNull()?.receivedAt ?: cursor,
            hasMore = hasMore,
        )
    }

    suspend fun eventIds(): List<String> = dao.eventIds()

    /** Everything this device holds and would offer a peer. */
    suspend fun allApplied(): List<Operation> = dao.applied().map { it.toOperation() }

    /** Everything this device holds about one event. */
    suspend fun appliedFor(eventId: String): List<Operation> =
        dao.appliedFor(eventId).map { it.toOperation() }

    /**
     * One event, narrowed to some of its tickets.
     *
     * An operation is kept when it names no ticket — the event's own creation and renames, which
     * the receiver needs or the tickets arrive belonging to nothing — or when the ticket it names
     * is one of the chosen. Everything else is left behind.
     *
     * What the receiver gets is deliberately partial, and replay is built for that: an operation
     * about a ticket nobody sent is a gap rather than an error, and a device that later learns the
     * rest applies it then. It is also why the screen says how many of how many are going — two
     * tickets out of twelve looks like a failed transfer to anybody who was not told.
     */
    suspend fun appliedFor(eventId: String, ticketIds: Set<String>): List<Operation> =
        appliedFor(eventId).filter { operation ->
            val named = (operation.body["ticketId"] as? JsonPrimitive)
                ?.let { if (it.isString) it.content else null }
            named == null || named in ticketIds
        }

    suspend fun replay(eventId: String): ReplayResult {
        val devices = dao.devices().associate { it.id to ReplayDevice(it.id) }
        return Replay.of(dao.appliedFor(eventId).map { it.toOperation() }, devices)
    }

    suspend fun quarantined(eventId: String): List<Operation> =
        dao.quarantinedFor(eventId).map { it.toOperation() }
}

enum class AcceptState { APPLIED, DUPLICATE, QUARANTINED, REJECTED }

data class AcceptOutcome(val operationId: String, val state: AcceptState, val reason: String?)

data class SyncPage(
    val operations: List<Operation>,
    /** Opaque to the peer. Handed back to continue where this left off. */
    val cursor: String,
    val hasMore: Boolean,
)
