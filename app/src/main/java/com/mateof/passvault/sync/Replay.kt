package com.mateof.passvault.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Deriving the wallet from the log.
 *
 * Recomputed from scratch every time rather than patched forward, which the specification asks for
 * and which is the whole reason this is affordable: operations arrive in any order, including a
 * `claim.confirm` before the `claim.request` it confirms. Incremental application is faster and is
 * exactly where merge bugs live; an event has tens or hundreds of operations, not millions.
 *
 * A pure function over a list, deliberately. Nothing here touches Room, so the rules that decide who
 * owns a ticket are testable without a database, and the same code decides them whether the
 * operations came from a file, a peer or the server.
 */
object Replay {

    fun of(operations: List<Operation>, knownDevices: Map<String, ReplayDevice> = emptyMap()): ReplayResult {
        val ordered = operations.sortedWith(Operations.LOGICAL_ORDER)
        val events = LinkedHashMap<String, ReplayedEvent>()
        val tickets = LinkedHashMap<String, ReplayedTicket>()
        val coupons = HashMap<String, MutableSet<String>>()
        val requests = HashMap<String, MutableList<Operation>>()
        val refused = mutableListOf<RefusedOperation>()

        for (operation in ordered) {
            val event = events[operation.eventId]

            // Authorisation is enforced here, on every device that replays, rather than trusted from
            // whoever relayed it. That is what stops a compromised server injecting an assignment
            // just as surely as it stops a dishonest peer.
            val issue = authorise(operation, event, knownDevices)
            if (issue != null) {
                refused += RefusedOperation(operation, issue)
                continue
            }

            when (operation.type) {
                OperationType.EVENT_CREATE -> events[operation.eventId] = ReplayedEvent(
                    eventId = operation.eventId,
                    creatorDeviceId = operation.deviceId,
                    creatorUserId = operation.actorUserId,
                    name = operation.body.text("name") ?: "",
                    venue = operation.body.text("venue"),
                    startsAt = operation.body.text("startsAt"),
                )

                OperationType.EVENT_UPDATE -> events[operation.eventId] = (
                    event ?: ReplayedEvent(operation.eventId, null, null, "", null, null)
                    ).let { current ->
                    // Field-level last-writer-wins under the logical order: a key that is absent was
                    // not edited, and keeps whatever the previous writer set.
                    current.copy(
                        name = operation.body.text("name") ?: current.name,
                        venue = operation.body.text("venue") ?: current.venue,
                        startsAt = operation.body.text("startsAt") ?: current.startsAt,
                    )
                }

                OperationType.TICKET_ADD -> {
                    val ticketId = operation.body.text("ticketId") ?: continue
                    // A tombstone is never undone by a later add of the same id. Reviving a removed
                    // ticket is a new ticket with a new id, so the history stays honest.
                    if (tickets[ticketId]?.removed == true) continue
                    tickets[ticketId] = ReplayedTicket(
                        ticketId = ticketId,
                        eventId = operation.eventId,
                        label = operation.body.text("label"),
                        seat = operation.body.text("seat"),
                        barcodeFormat = operation.body.text("barcodeFormat"),
                        barcodeValue = operation.body.text("barcodeValue"),
                    )
                }

                OperationType.TICKET_REMOVE -> {
                    val ticketId = operation.body.text("ticketId") ?: continue
                    tickets[ticketId] = (tickets[ticketId] ?: ReplayedTicket(ticketId, operation.eventId))
                        .copy(removed = true, state = TicketAssignment.WITHDRAWN)
                }

                OperationType.TICKET_ASSIGN -> {
                    val ticketId = operation.body.text("ticketId") ?: continue
                    tickets[ticketId] = (tickets[ticketId] ?: ReplayedTicket(ticketId, operation.eventId))
                        .copy(
                            holder = operation.body.text("holderLabel")
                                ?: operation.body.text("holderUserId"),
                            state = TicketAssignment.ASSIGNED,
                        )
                }

                OperationType.TICKET_UNASSIGN -> {
                    val ticketId = operation.body.text("ticketId") ?: continue
                    tickets[ticketId] = (tickets[ticketId] ?: ReplayedTicket(ticketId, operation.eventId))
                        .copy(holder = null, state = TicketAssignment.FREE)
                }

                OperationType.CLAIM_COUPON_ISSUE -> {
                    val ticketId = operation.body.text("ticketId") ?: continue
                    val coupon = operation.body.text("coupon") ?: continue
                    coupons.getOrPut(ticketId) { mutableSetOf() } += coupon
                }

                OperationType.CLAIM_REQUEST -> {
                    val ticketId = operation.body.text("ticketId") ?: continue
                    val coupon = operation.body.text("coupon")
                    // A request without a coupon the creator issued is not a claim. This is what
                    // bounds a dishonest client: it cannot claim a ticket that was never offered,
                    // whatever lamport value it invents.
                    if (coupon == null || coupon !in (coupons[ticketId] ?: emptySet<String>())) {
                        refused += RefusedOperation(operation, OperationRefusal.UNKNOWN_COUPON)
                        continue
                    }
                    requests.getOrPut(ticketId) { mutableListOf() } += operation
                    val current = tickets[ticketId] ?: ReplayedTicket(ticketId, operation.eventId)
                    // Provisional, never settled. The interface must not present it as final: this
                    // is the single most important user-facing consequence of the offline design.
                    if (current.state == TicketAssignment.FREE) {
                        tickets[ticketId] = current.copy(
                            state = TicketAssignment.PROVISIONAL,
                            provisionalClaimBy = operation.deviceId,
                            provisionalClaimOperationId = operation.operationId,
                        )
                    }
                }

                OperationType.CLAIM_CONFIRM -> {
                    val ticketId = operation.body.text("ticketId") ?: continue
                    val winning = operation.body.text("operationId")
                    val winner = requests[ticketId]?.firstOrNull { it.operationId == winning }
                    tickets[ticketId] = (tickets[ticketId] ?: ReplayedTicket(ticketId, operation.eventId))
                        .copy(
                            state = TicketAssignment.CLAIMED,
                            holder = winner?.actorUserId ?: winner?.deviceId,
                            provisionalClaimBy = null,
                            provisionalClaimOperationId = null,
                        )
                }

                OperationType.CLAIM_REJECT -> {
                    val ticketId = operation.body.text("ticketId") ?: continue
                    val losing = operation.body.text("operationId")
                    val current = tickets[ticketId] ?: continue
                    // Only clears the claim if the rejected request is the one currently showing.
                    // A late reject for a request that already lost must not free a settled ticket.
                    if (current.provisionalClaimOperationId == losing) {
                        tickets[ticketId] = current.copy(
                            state = TicketAssignment.FREE,
                            provisionalClaimBy = null,
                            provisionalClaimOperationId = null,
                            rejectionReason = operation.body.text("reason"),
                        )
                    }
                }

                OperationType.PAYMENT_SET -> {
                    val ticketId = operation.body.text("ticketId") ?: continue
                    tickets[ticketId] = (tickets[ticketId] ?: ReplayedTicket(ticketId, operation.eventId))
                        .copy(
                            paymentState = operation.body.text("state"),
                            amountCents = operation.body["amountCents"]?.jsonPrimitive?.intOrNull,
                            currency = operation.body.text("currency"),
                            paymentVisibility = operation.body.text("visibility"),
                        )
                }

                OperationType.TICKET_TRANSFER -> {
                    val ticketId = operation.body.text("ticketId") ?: continue
                    tickets[ticketId] = (tickets[ticketId] ?: ReplayedTicket(ticketId, operation.eventId))
                        .copy(transferred = true)
                }

                // Registration carries no wallet state; it is what makes later signatures checkable
                // and is consumed before replay, when devices are learned.
                OperationType.DEVICE_REGISTER -> Unit
            }
        }

        return ReplayResult(
            events = events.values.toList(),
            tickets = tickets.values.filter { !it.removed },
            withdrawn = tickets.values.filter { it.removed }.map { it.ticketId },
            refused = refused,
        )
    }

    /**
     * Who is allowed to issue what.
     *
     * An event whose `event.create` has not arrived yet has no known creator, so creator-only
     * operations cannot be checked. They are refused rather than assumed valid — the operation stays
     * in the log and a later replay, once the creation has arrived, will accept it.
     */
    private fun authorise(
        operation: Operation,
        event: ReplayedEvent?,
        knownDevices: Map<String, ReplayDevice>,
    ): OperationRefusal? {
        if (operation.type !in OperationType.APPLIED) return OperationRefusal.UNKNOWN_TYPE

        if (operation.type == OperationType.EVENT_CREATE) {
            // Two devices claiming to have created the same event: the first in logical order wins,
            // and the second is a device trying to take over an event it did not make.
            return if (event == null) null else OperationRefusal.NOT_PERMITTED
        }

        if (operation.type in OperationType.CREATOR_ONLY) {
            val creator = event?.creatorDeviceId ?: return OperationRefusal.UNKNOWN_EVENT
            if (creator != operation.deviceId) return OperationRefusal.NOT_PERMITTED
        }

        if (operation.type in OperationType.AUTHORITY_ONLY) {
            // For a purely local event the authority is the creator's device. An event synchronised
            // to a server has the server as its authority, which this device learns when it joins
            // one; until then, a confirmation from anybody else is not one.
            val authority = event?.creatorDeviceId ?: return OperationRefusal.UNKNOWN_EVENT
            val serverAuthority = knownDevices[operation.deviceId]?.isAuthority == true
            if (authority != operation.deviceId && !serverAuthority) {
                return OperationRefusal.NOT_PERMITTED
            }
        }

        return null
    }

    private fun JsonObject.text(key: String): String? =
        this[key]?.jsonPrimitive?.let { if (it.isString) it.content else null }
}

data class ReplayDevice(val deviceId: String, val isAuthority: Boolean = false)

enum class TicketAssignment { FREE, PROVISIONAL, CLAIMED, ASSIGNED, WITHDRAWN }

enum class OperationRefusal { NOT_PERMITTED, UNKNOWN_EVENT, UNKNOWN_TYPE, UNKNOWN_COUPON }

data class RefusedOperation(val operation: Operation, val reason: OperationRefusal)

data class ReplayedEvent(
    val eventId: String,
    val creatorDeviceId: String?,
    val creatorUserId: String?,
    val name: String,
    val venue: String?,
    val startsAt: String?,
)

data class ReplayedTicket(
    val ticketId: String,
    val eventId: String,
    val label: String? = null,
    val seat: String? = null,
    val barcodeFormat: String? = null,
    val barcodeValue: String? = null,
    val state: TicketAssignment = TicketAssignment.FREE,
    val holder: String? = null,
    val provisionalClaimBy: String? = null,
    val provisionalClaimOperationId: String? = null,
    val rejectionReason: String? = null,
    val paymentState: String? = null,
    val amountCents: Int? = null,
    val currency: String? = null,
    val paymentVisibility: String? = null,
    val removed: Boolean = false,
    val transferred: Boolean = false,
)

data class ReplayResult(
    val events: List<ReplayedEvent>,
    val tickets: List<ReplayedTicket>,
    val withdrawn: List<String>,
    /** Operations the rules refused, kept so the interface can say why rather than losing them. */
    val refused: List<RefusedOperation>,
)
