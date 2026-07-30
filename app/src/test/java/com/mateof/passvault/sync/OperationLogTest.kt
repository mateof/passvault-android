package com.mateof.passvault.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mateof.passvault.crypto.Base64Url
import com.mateof.passvault.data.DeviceIdentity
import com.mateof.passvault.data.InMemoryDeviceKeys
import com.mateof.passvault.data.OperationState
import com.mateof.passvault.data.PassVaultDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Letting something into the log, which is a different question from what the log means.
 *
 * Two devices here, because every interesting rule is about a message from somebody else: a
 * signature that does not check, a device whose key has not arrived yet, a batch replayed twice.
 * The database is real Room, so the idempotency is the primary key doing its job rather than a
 * fake agreeing with the test.
 */
@RunWith(RobolectricTestRunner::class)
class OperationLogTest {

    private lateinit var database: PassVaultDatabase
    private lateinit var log: OperationLog

    /** The other phone. Its operations are built here and handed over as if they arrived. */
    private val peer = DeviceIdentity.generate()
    private val eventId = "event-1"

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PassVaultDatabase::class.java,
        ).allowMainThreadQueries().build()
        log = OperationLog(database.operationDao(), InMemoryDeviceKeys())
    }

    @After
    fun tearDown() = database.close()

    private fun peerOperation(
        type: String = OperationType.TICKET_ADD,
        lamport: Long = 1,
        body: kotlinx.serialization.json.JsonObject = buildJsonObject { put("ticketId", "ticket-1") },
    ) = Operations.create(peer, eventId, type, body, lamport)

    private fun peerRegistration(lamport: Long = 1) = Operations.create(
        peer,
        eventId,
        OperationType.DEVICE_REGISTER,
        buildJsonObject {
            put("deviceId", peer.deviceId)
            put("signingPublicKey", Base64Url.encode(peer.signingPublicKey))
            put("agreementPublicKey", Base64Url.encode(peer.agreementPublicKey))
        },
        lamport,
    )

    @Test
    fun `an operation this device issues is stored applied`() = runTest {
        val operation = log.append(eventId, OperationType.EVENT_CREATE, buildJsonObject { put("name", "Festival") })

        assertThat(database.operationDao().byId(operation.operationId)?.state)
            .isEqualTo(OperationState.APPLIED)
    }

    @Test
    fun `the logical clock advances past everything seen`() = runTest {
        log.append(eventId, OperationType.EVENT_CREATE, buildJsonObject { put("name", "Festival") })
        log.accept(listOf(peerRegistration(lamport = 40)))

        assertThat(log.nextLamport(eventId)).isEqualTo(41)
    }

    @Test
    fun `a registration is accepted against the key it announces`() = runTest {
        val outcome = log.accept(listOf(peerRegistration())).single()

        assertThat(outcome.state).isEqualTo(AcceptState.APPLIED)
    }

    @Test
    fun `and the peer becomes a device this one can check`() = runTest {
        log.accept(listOf(peerRegistration()))

        assertThat(database.operationDao().device(peer.deviceId)).isNotNull()
    }

    @Test
    fun `an operation from a device with no known key waits in quarantine`() = runTest {
        val outcome = log.accept(listOf(peerOperation())).single()

        assertThat(outcome.state).isEqualTo(AcceptState.QUARANTINED)
    }

    @Test
    fun `and says the reason is the unknown device, not a bad signature`() = runTest {
        val outcome = log.accept(listOf(peerOperation())).single()

        assertThat(outcome.reason).isEqualTo("unknown_device")
    }

    @Test
    fun `quarantined operations are applied once the device becomes known`() = runTest {
        // The ordinary case, and the reason quarantine exists rather than dropping: the key arrives
        // after the operations it signed.
        val early = peerOperation(lamport = 2)
        log.accept(listOf(early))

        log.accept(listOf(peerRegistration(lamport = 1)))

        assertThat(database.operationDao().byId(early.operationId)?.state)
            .isEqualTo(OperationState.APPLIED)
    }

    @Test
    fun `a registration arriving in the same batch as what it signs is enough`() = runTest {
        val added = peerOperation(lamport = 2)

        log.accept(listOf(added, peerRegistration(lamport = 1)))

        assertThat(database.operationDao().byId(added.operationId)?.state)
            .isEqualTo(OperationState.APPLIED)
    }

    @Test
    fun `an operation altered after signing is rejected`() = runTest {
        log.accept(listOf(peerRegistration()))
        val tampered = peerOperation(lamport = 5).copy(lamport = 6)

        val outcome = log.accept(listOf(tampered)).single()

        assertThat(outcome.state).isEqualTo(AcceptState.REJECTED)
    }

    @Test
    fun `a repeated operation is counted, not applied twice`() = runTest {
        log.accept(listOf(peerRegistration()))
        val added = peerOperation(lamport = 2)
        log.accept(listOf(added))

        val outcome = log.accept(listOf(added)).single()

        assertThat(outcome.state).isEqualTo(AcceptState.DUPLICATE)
    }

    @Test
    fun `so replaying an interrupted transfer leaves one copy`() = runTest {
        log.accept(listOf(peerRegistration()))
        val added = peerOperation(lamport = 2)

        log.accept(listOf(added))
        log.accept(listOf(added))

        assertThat(database.operationDao().countFor(eventId)).isEqualTo(2)
    }

    @Test
    fun `a type this version does not know is kept rather than lost`() = runTest {
        log.accept(listOf(peerRegistration()))
        val future = peerOperation(type = "ticket.teleport", lamport = 2)

        val outcome = log.accept(listOf(future)).single()

        assertThat(outcome.state).isEqualTo(AcceptState.QUARANTINED)
    }

    @Test
    fun `the cursor pages through arrival order`() = runTest {
        log.append(eventId, OperationType.EVENT_CREATE, buildJsonObject { put("name", "Festival") })
        log.append(eventId, OperationType.TICKET_ADD, buildJsonObject { put("ticketId", "t-1") })

        val first = log.since(eventId, cursor = "", limit = 1)

        assertThat(first.hasMore).isTrue()
    }

    @Test
    fun `and continuing from the cursor returns what the first page did not`() = runTest {
        log.append(eventId, OperationType.EVENT_CREATE, buildJsonObject { put("name", "Festival") })
        val second = log.append(eventId, OperationType.TICKET_ADD, buildJsonObject { put("ticketId", "t-1") })

        val page = log.since(eventId, cursor = log.since(eventId, "", 1).cursor, limit = 10)

        assertThat(page.operations.map { it.operationId }).containsExactly(second.operationId)
    }

    @Test
    fun `a stored operation comes back with its body intact`() = runTest {
        // The body is encrypted at rest, so this is checking the round trip rather than the store.
        log.append(
            eventId,
            OperationType.TICKET_ADD,
            buildJsonObject {
                put("ticketId", "t-1")
                put("barcodeValue", "8412-LOG-0001")
            },
        )

        val stored = log.since(eventId, "", 10).operations.single()

        assertThat(stored.body["barcodeValue"].toString()).contains("8412-LOG-0001")
    }

    @Test
    fun `a stored operation still verifies after the round trip`() = runTest {
        // If storage altered anything the signature covers, this is where it shows.
        log.rememberSelf()
        val issued = log.append(eventId, OperationType.TICKET_ADD, buildJsonObject { put("ticketId", "t-1") })

        val stored = log.since(eventId, "", 10).operations.single { it.operationId == issued.operationId }

        assertThat(stored.signature).isEqualTo(issued.signature)
    }
}
