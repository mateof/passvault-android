package com.mateof.passvault.share

import com.google.common.truth.Truth.assertThat
import com.mateof.passvault.crypto.Primitives
import com.mateof.passvault.data.DeviceIdentity
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Test

/**
 * Two phones talking, over a pipe instead of a socket.
 *
 * A socket adds nothing this needs to check: the protocol is streams in and streams out, and piping
 * them makes the whole conversation — greeting, digits, confirmation, exchange — a fast unit test
 * rather than something that only runs with two devices in the room.
 *
 * The test that matters is the last one. Everything else is the honest case; that one is the reason
 * the six digits exist.
 */
class TransferTest {

    private val pool = Executors.newCachedThreadPool()

    @After
    fun tearDown() = pool.shutdownNow().let { }

    /** A bidirectional pipe: what one side writes, the other reads. */
    private class Wire {
        val initiatorIn = PipedInputStream(1 shl 16)
        val responderIn = PipedInputStream(1 shl 16)
        val initiatorOut = PipedOutputStream(responderIn)
        val responderOut = PipedOutputStream(initiatorIn)
    }

    private fun <T> async(block: () -> T): Future<T> = pool.submit(block)

    private fun greetBothSides(wire: Wire): Pair<PairedPeer, PairedPeer> {
        val ana = DeviceIdentity.generate()
        val brais = DeviceIdentity.generate()
        // Both sides at once. Each writes its greeting before reading the other's, so running them
        // sequentially would block on a pipe that nobody is draining.
        val initiator = async {
            Transfer.greet(
                wire.initiatorIn, wire.initiatorOut,
                ana.deviceId, ana.signingPublicKey, "Ana", isInitiator = true,
            )
        }
        val responder = async {
            Transfer.greet(
                wire.responderIn, wire.responderOut,
                brais.deviceId, brais.signingPublicKey, "Brais", isInitiator = false,
            )
        }
        return initiator.get(10, TimeUnit.SECONDS) to responder.get(10, TimeUnit.SECONDS)
    }

    @Test
    fun `both phones derive the same six digits`() {
        val (initiator, responder) = greetBothSides(Wire())

        assertThat(initiator.shortAuthenticationString)
            .isEqualTo(responder.shortAuthenticationString)
    }

    @Test
    fun `each phone learns the other's name`() {
        val (initiator, _) = greetBothSides(Wire())

        assertThat(initiator.displayName).isEqualTo("Brais")
    }

    @Test
    fun `the digits are six of them`() {
        val (initiator, _) = greetBothSides(Wire())

        assertThat(initiator.shortAuthenticationString).matches("\\d{6}")
    }

    @Test
    fun `a confirmation from each side opens the session`() {
        val wire = Wire()
        val (initiator, responder) = greetBothSides(wire)

        val left = async { Transfer.confirm(initiator, isInitiator = true) }
        val right = async { Transfer.confirm(responder, isInitiator = false) }
        left.get(10, TimeUnit.SECONDS)
        right.get(10, TimeUnit.SECONDS)

        assertThat(initiator.shortAuthenticationString).isNotEmpty()
    }

    @Test
    fun `what one side sends the other reads back`() {
        val wire = Wire()
        val (initiator, responder) = greetBothSides(wire)
        async { Transfer.confirm(initiator, isInitiator = true) }
        async { Transfer.confirm(responder, isInitiator = false) }.get(10, TimeUnit.SECONDS)

        val sent = async { initiator.session.send("entradas".toByteArray()) }
        val received = async { responder.session.receive() }
        sent.get(10, TimeUnit.SECONDS)

        assertThat(String(received.get(10, TimeUnit.SECONDS))).isEqualTo("entradas")
    }

    @Test
    fun `the two directions use different keys`() {
        // Both sides start their counter at zero. On one shared key that is immediate nonce reuse,
        // which in GCM is not a weakness but a break.
        val sessionKey = Primitives.randomKey()

        val initiator = TransferProtocol.directionalKeys(sessionKey, isInitiator = true)

        assertThat(initiator.send).isNotEqualTo(initiator.receive)
    }

    @Test
    fun `and each side sends with the key the other receives with`() {
        val sessionKey = Primitives.randomKey()

        val initiator = TransferProtocol.directionalKeys(sessionKey, isInitiator = true)
        val responder = TransferProtocol.directionalKeys(sessionKey, isInitiator = false)

        assertThat(initiator.send).isEqualTo(responder.receive)
    }

    @Test
    fun `a frame length no honest peer would send is refused before anything is allocated`() {
        val hostile = java.io.ByteArrayInputStream(
            byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
        )

        val thrown = runCatching { TransferProtocol.readFrame(hostile) }.exceptionOrNull()

        assertThat((thrown as TransferException).code).isEqualTo(TransferError.PROTOCOL)
    }

    @Test
    fun `a peer that hangs up mid-conversation is reported as a lost connection`() {
        val empty = java.io.ByteArrayInputStream(ByteArray(0))

        val thrown = runCatching { TransferProtocol.readFrame(empty) }.exceptionOrNull()

        assertThat((thrown as TransferException).code).isEqualTo(TransferError.CONNECTION_LOST)
    }

    @Test
    fun `an altered frame does not authenticate`() {
        val wire = Wire()
        val (initiator, responder) = greetBothSides(wire)
        async { Transfer.confirm(initiator, isInitiator = true) }
        async { Transfer.confirm(responder, isInitiator = false) }.get(10, TimeUnit.SECONDS)
        // A session built on a different key is what a relayed frame looks like from the inside.
        val impostor = TransferSession(wire.responderIn, wire.responderOut, Primitives.randomKey(), false)

        async { initiator.session.send("entradas".toByteArray()) }
        val thrown = runCatching { impostor.receive() }.exceptionOrNull()

        assertThat((thrown as TransferException).code).isEqualTo(TransferError.TAMPERED)
    }

    @Test
    fun `a device in the middle cannot make both phones agree on the digits`() {
        // The whole reason for the short authentication string. The attacker pairs with each side
        // separately and holds two working sessions; what it cannot do is make the two screens show
        // the same six digits.
        val toAna = Wire()
        val toBrais = Wire()
        val ana = DeviceIdentity.generate()
        val brais = DeviceIdentity.generate()
        val attacker = DeviceIdentity.generate()

        val anaSide = async {
            Transfer.greet(
                toAna.initiatorIn, toAna.initiatorOut,
                ana.deviceId, ana.signingPublicKey, "Ana", isInitiator = true,
            )
        }
        val attackerToAna = async {
            Transfer.greet(
                toAna.responderIn, toAna.responderOut,
                attacker.deviceId, attacker.signingPublicKey, "Brais", isInitiator = false,
            )
        }
        val attackerToBrais = async {
            Transfer.greet(
                toBrais.initiatorIn, toBrais.initiatorOut,
                attacker.deviceId, attacker.signingPublicKey, "Ana", isInitiator = true,
            )
        }
        val braisSide = async {
            Transfer.greet(
                toBrais.responderIn, toBrais.responderOut,
                brais.deviceId, brais.signingPublicKey, "Brais", isInitiator = false,
            )
        }
        attackerToAna.get(10, TimeUnit.SECONDS)
        attackerToBrais.get(10, TimeUnit.SECONDS)

        assertThat(anaSide.get(10, TimeUnit.SECONDS).shortAuthenticationString)
            .isNotEqualTo(braisSide.get(10, TimeUnit.SECONDS).shortAuthenticationString)
    }
}
