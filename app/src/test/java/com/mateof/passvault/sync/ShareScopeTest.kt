package com.mateof.passvault.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mateof.passvault.data.InMemoryDeviceKeys
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
 * What a transfer offers, once it stopped offering everything.
 *
 * Pairing two phones used to exchange both wallets entirely, which is right for two devices
 * belonging to one person and wrong for handing a friend one seat. The rule being tested is the
 * one that decides which operations leave, and it has a failure mode in each direction: send too
 * much and somebody's whole wallet goes across, send too little and the tickets arrive belonging
 * to an event the receiver has never heard of.
 */
@RunWith(RobolectricTestRunner::class)
class ShareScopeTest {

    private lateinit var database: PassVaultDatabase
    private lateinit var log: OperationLog

    private val concert = "event-concert"
    private val flight = "event-flight"

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

    /** Two events, one with three tickets and one with a single unrelated ticket. */
    private suspend fun seed() {
        log.append(concert, OperationType.EVENT_CREATE, buildJsonObject { put("name", "Festival") })
        for (seat in 1..3) {
            log.append(
                concert,
                OperationType.TICKET_ADD,
                buildJsonObject { put("ticketId", "ticket-$seat") },
            )
        }
        log.append(flight, OperationType.EVENT_CREATE, buildJsonObject { put("name", "Voo") })
        log.append(
            flight,
            OperationType.TICKET_ADD,
            buildJsonObject { put("ticketId", "ticket-flight") },
        )
    }

    @Test
    fun `everything is everything, which is what it did before there was a choice`() = runTest {
        seed()

        assertThat(log.allApplied()).hasSize(6)
    }

    @Test
    fun `one event carries its own operations and nothing from the other`() = runTest {
        seed()

        val offered = log.appliedFor(concert)

        assertThat(offered).hasSize(4)
        assertThat(offered.map { it.eventId }.toSet()).containsExactly(concert)
    }

    @Test
    fun `a chosen ticket travels`() = runTest {
        seed()

        val offered = log.appliedFor(concert, setOf("ticket-2"))

        assertThat(offered.mapNotNull { it.body["ticketId"]?.toString()?.trim('"') })
            .containsExactly("ticket-2")
    }

    @Test
    fun `the ones not chosen stay behind, which is the whole point`() = runTest {
        seed()

        val offered = log.appliedFor(concert, setOf("ticket-2"))

        assertThat(offered.mapNotNull { it.body["ticketId"]?.toString()?.trim('"') })
            .doesNotContain("ticket-1")
    }

    @Test
    fun `the event itself always travels, or the tickets arrive belonging to nothing`() = runTest {
        seed()

        val offered = log.appliedFor(concert, setOf("ticket-2"))

        assertThat(offered.map { it.type }).contains(OperationType.EVENT_CREATE)
    }

    @Test
    fun `choosing tickets never leaks the other event`() = runTest {
        seed()

        val offered = log.appliedFor(concert, setOf("ticket-1", "ticket-2", "ticket-3"))

        assertThat(offered.map { it.eventId }.toSet()).containsExactly(concert)
    }

    @Test
    fun `choosing none still carries the event, and no tickets`() = runTest {
        seed()

        val offered = log.appliedFor(concert, emptySet())

        assertThat(offered.map { it.type }).containsExactly(OperationType.EVENT_CREATE)
    }
}
