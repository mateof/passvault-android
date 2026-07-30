package com.mateof.passvault.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * Deriving the wallet from the log.
 *
 * These tests are the offline design, stated as behaviour. The interesting ones are the last few:
 * two friends with no signal both claim the last spare ticket, and what matters is not that the
 * conflict is avoided — it cannot be — but that both devices independently reach the same answer
 * and the loser is told.
 *
 * Signatures are not involved here. Replay decides outcomes; deciding whether an operation is
 * genuine happens before it, in `OperationLog`, and is tested there.
 */
class ReplayTest {

    private val creator = "device-creator"
    private val ana = "device-ana"
    private val brais = "device-brais"
    private val eventId = "event-1"
    private val ticketId = "ticket-1"

    private var counter = 0

    private fun operation(
        deviceId: String,
        type: String,
        lamport: Long,
        body: JsonObject = buildJsonObject { },
        actorUserId: String? = null,
    ) = Operation(
        operationId = "op-${counter++}",
        deviceId = deviceId,
        actorUserId = actorUserId,
        lamport = lamport,
        wallClock = "2026-07-30T10:00:00.000Z",
        eventId = eventId,
        type = type,
        body = body,
    )

    private fun created(lamport: Long = 1) = operation(
        creator,
        OperationType.EVENT_CREATE,
        lamport,
        buildJsonObject { put("name", "Festival do Norte 2026") },
    )

    private fun ticketAdded(lamport: Long = 2) = operation(
        creator,
        OperationType.TICKET_ADD,
        lamport,
        buildJsonObject {
            put("ticketId", ticketId)
            put("barcodeValue", "8412-REPLAY-0001")
        },
    )

    private fun couponIssued(lamport: Long = 3, coupon: String = "COUPON-A") = operation(
        creator,
        OperationType.CLAIM_COUPON_ISSUE,
        lamport,
        buildJsonObject {
            put("ticketId", ticketId)
            put("coupon", coupon)
        },
    )

    private fun claimRequest(deviceId: String, lamport: Long, coupon: String = "COUPON-A") = operation(
        deviceId,
        OperationType.CLAIM_REQUEST,
        lamport,
        buildJsonObject {
            put("ticketId", ticketId)
            put("coupon", coupon)
        },
    )

    @Test
    fun `an event creation establishes the event`() {
        val result = Replay.of(listOf(created()))

        assertThat(result.events.single().name).isEqualTo("Festival do Norte 2026")
    }

    @Test
    fun `an update by the creator changes the field it names`() {
        val update = operation(
            creator,
            OperationType.EVENT_UPDATE,
            5,
            buildJsonObject { put("venue", "Recinto Ferial") },
        )

        val result = Replay.of(listOf(created(), update))

        assertThat(result.events.single().venue).isEqualTo("Recinto Ferial")
    }

    @Test
    fun `an update leaves fields it does not name alone`() {
        // Field-level last-writer-wins: two organisers editing different fields keep both edits.
        val update = operation(
            creator,
            OperationType.EVENT_UPDATE,
            5,
            buildJsonObject { put("venue", "Recinto Ferial") },
        )

        val result = Replay.of(listOf(created(), update))

        assertThat(result.events.single().name).isEqualTo("Festival do Norte 2026")
    }

    @Test
    fun `a ticket added by the creator appears`() {
        val result = Replay.of(listOf(created(), ticketAdded()))

        assertThat(result.tickets.single().barcodeValue).isEqualTo("8412-REPLAY-0001")
    }

    @Test
    fun `a ticket added by somebody who is not the creator is refused`() {
        val intruder = operation(
            ana,
            OperationType.TICKET_ADD,
            2,
            buildJsonObject { put("ticketId", "ticket-intruder") },
        )

        val result = Replay.of(listOf(created(), intruder))

        assertThat(result.tickets).isEmpty()
    }

    @Test
    fun `and says why it was refused rather than dropping it silently`() {
        val intruder = operation(
            ana,
            OperationType.TICKET_ADD,
            2,
            buildJsonObject { put("ticketId", "ticket-intruder") },
        )

        val result = Replay.of(listOf(created(), intruder))

        assertThat(result.refused.single().reason).isEqualTo(OperationRefusal.NOT_PERMITTED)
    }

    @Test
    fun `a removal is a tombstone that later edits do not undo`() {
        val removed = operation(
            creator,
            OperationType.TICKET_REMOVE,
            4,
            buildJsonObject { put("ticketId", ticketId) },
        )
        val revived = operation(
            creator,
            OperationType.TICKET_ADD,
            5,
            buildJsonObject { put("ticketId", ticketId) },
        )

        val result = Replay.of(listOf(created(), ticketAdded(), removed, revived))

        assertThat(result.tickets).isEmpty()
    }

    @Test
    fun `a claim without a coupon the creator issued is refused`() {
        // What bounds a dishonest client: it cannot claim a ticket that was never offered, whatever
        // lamport value it invents.
        val result = Replay.of(listOf(created(), ticketAdded(), claimRequest(ana, 4, "MADE-UP")))

        assertThat(result.refused.map { it.reason }).contains(OperationRefusal.UNKNOWN_COUPON)
    }

    @Test
    fun `a claim with a valid coupon is provisional, never settled`() {
        val log = listOf(created(), ticketAdded(), couponIssued(), claimRequest(ana, 4))

        val result = Replay.of(log)

        assertThat(result.tickets.single().state).isEqualTo(TicketAssignment.PROVISIONAL)
    }

    @Test
    fun `two offline claims both arrive, and the earlier one shows as provisional`() {
        val log = listOf(
            created(),
            ticketAdded(),
            couponIssued(),
            claimRequest(brais, 9),
            claimRequest(ana, 4),
        )

        val result = Replay.of(log)

        assertThat(result.tickets.single().provisionalClaimBy).isEqualTo(ana)
    }

    @Test
    fun `the authority confirming a claim settles it`() {
        val request = claimRequest(ana, 4)
        val confirm = operation(
            creator,
            OperationType.CLAIM_CONFIRM,
            10,
            buildJsonObject {
                put("ticketId", ticketId)
                put("operationId", request.operationId)
            },
        )

        val result = Replay.of(listOf(created(), ticketAdded(), couponIssued(), request, confirm))

        assertThat(result.tickets.single().state).isEqualTo(TicketAssignment.CLAIMED)
    }

    @Test
    fun `a confirmation from anybody but the authority is refused`() {
        // For a local event the authority is the creator's device. A member confirming their own
        // claim is exactly the attack the authority rule exists to stop.
        val request = claimRequest(ana, 4)
        val forged = operation(
            ana,
            OperationType.CLAIM_CONFIRM,
            5,
            buildJsonObject {
                put("ticketId", ticketId)
                put("operationId", request.operationId)
            },
        )

        val result = Replay.of(listOf(created(), ticketAdded(), couponIssued(), request, forged))

        assertThat(result.tickets.single().state).isEqualTo(TicketAssignment.PROVISIONAL)
    }

    @Test
    fun `a rejection returns the ticket to free and carries a reason`() {
        val request = claimRequest(ana, 4)
        val reject = operation(
            creator,
            OperationType.CLAIM_REJECT,
            10,
            buildJsonObject {
                put("ticketId", ticketId)
                put("operationId", request.operationId)
                put("reason", "lost_the_race")
            },
        )

        val result = Replay.of(listOf(created(), ticketAdded(), couponIssued(), request, reject))

        assertThat(result.tickets.single().rejectionReason).isEqualTo("lost_the_race")
    }

    @Test
    fun `a rejection for a claim that is not the one showing leaves the ticket alone`() {
        // A late reject for a request that already lost must not free a ticket somebody now holds.
        val loser = claimRequest(brais, 9)
        val winner = claimRequest(ana, 4)
        val confirm = operation(
            creator,
            OperationType.CLAIM_CONFIRM,
            10,
            buildJsonObject {
                put("ticketId", ticketId)
                put("operationId", winner.operationId)
            },
        )
        val lateReject = operation(
            creator,
            OperationType.CLAIM_REJECT,
            11,
            buildJsonObject {
                put("ticketId", ticketId)
                put("operationId", loser.operationId)
            },
        )

        val result = Replay.of(
            listOf(created(), ticketAdded(), couponIssued(), loser, winner, confirm, lateReject),
        )

        assertThat(result.tickets.single().state).isEqualTo(TicketAssignment.CLAIMED)
    }

    @Test
    fun `replay does not depend on the order operations arrived in`() {
        // The property the whole design rests on: a device that received a confirmation before the
        // request it confirms must reach the same wallet as one that received them in order.
        val request = claimRequest(ana, 4)
        val confirm = operation(
            creator,
            OperationType.CLAIM_CONFIRM,
            10,
            buildJsonObject {
                put("ticketId", ticketId)
                put("operationId", request.operationId)
            },
        )
        val log = listOf(created(), ticketAdded(), couponIssued(), request, confirm)

        val inOrder = Replay.of(log)
        val reversed = Replay.of(log.reversed())

        assertThat(reversed.tickets).isEqualTo(inOrder.tickets)
    }

    @Test
    fun `every shuffling of the log produces the same wallet`() {
        val request = claimRequest(ana, 4)
        val confirm = operation(
            creator,
            OperationType.CLAIM_CONFIRM,
            10,
            buildJsonObject {
                put("ticketId", ticketId)
                put("operationId", request.operationId)
            },
        )
        val log = listOf(created(), ticketAdded(), couponIssued(), request, confirm)
        val expected = Replay.of(log).tickets

        // Deterministic seed, so a failure is reproducible rather than appearing once in a hundred
        // builds and never again.
        val random = java.util.Random(20260730)
        repeat(50) {
            assertThat(Replay.of(log.shuffled(random)).tickets).isEqualTo(expected)
        }
    }

    @Test
    fun `an unknown operation type is refused rather than applied`() {
        val future = operation(creator, "ticket.teleport", 5)

        val result = Replay.of(listOf(created(), future))

        assertThat(result.refused.single().reason).isEqualTo(OperationRefusal.UNKNOWN_TYPE)
    }

    @Test
    fun `a second device cannot claim to have created the same event`() {
        val takeover = operation(
            ana,
            OperationType.EVENT_CREATE,
            9,
            buildJsonObject { put("name", "Not your event") },
        )

        val result = Replay.of(listOf(created(), takeover))

        assertThat(result.events.single().creatorDeviceId).isEqualTo(creator)
    }

    @Test
    fun `payment details replay onto the ticket`() {
        val payment = operation(
            creator,
            OperationType.PAYMENT_SET,
            5,
            buildJsonObject {
                put("ticketId", ticketId)
                put("state", "PAID")
                put("amountCents", 2500)
                put("currency", "EUR")
            },
        )

        val result = Replay.of(listOf(created(), ticketAdded(), payment))

        assertThat(result.tickets.single().amountCents).isEqualTo(2500)
    }
}
