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
 * Pairing by touching two phones together, which is what replaces the six digits.
 *
 * The digits exist because being on the same Wi-Fi authenticates nobody, and they work — but they
 * are the step people get wrong, because a glance and a "yes" looks exactly like a check. Physical
 * contact cannot be faked by anybody on the network, so the tap carries what the digits protected
 * and the comparison stops being needed.
 *
 * Two properties make that true, and both are tested here because losing either one silently turns
 * a stronger check into no check at all:
 *
 *   * the tapping side refuses a socket that presents a key it did not read off the tag;
 *   * the advertising side refuses a peer that cannot return the token from the tag.
 *
 * Between them the tap authenticates both directions. Without the second, the tap would prove to
 * the receiver who it is talking to while the sender was still talking to anybody at all.
 */
class NfcPairingTest {

    private val pool = Executors.newCachedThreadPool()

    @After
    fun tearDown() = pool.shutdownNow().let { }

    private class Wire {
        val initiatorIn = PipedInputStream(1 shl 16)
        val responderIn = PipedInputStream(1 shl 16)
        val initiatorOut = PipedOutputStream(responderIn)
        val responderOut = PipedOutputStream(initiatorIn)
    }

    private fun <T> async(block: () -> T): Future<T> = pool.submit(block)

    private val ana = DeviceIdentity.generate()
    private val brais = DeviceIdentity.generate()

    /**
     * A whole tap-and-connect, as the two phones perform it.
     *
     * `advertised` is the pair whose public half went on the tag; `readKey` is what the tapping
     * side believes it read. They are the same in the honest case and deliberately different when
     * an attacker is being simulated.
     */
    private fun pair(
        advertised: PairingKeys,
        readKey: ByteArray,
        token: ByteArray,
        returnedToken: ByteArray,
    ): Pair<Future<Unit>, Future<Unit>> {
        val wire = Wire()

        val tapper = async {
            val peer = Transfer.greet(
                wire.initiatorIn, wire.initiatorOut,
                ana.deviceId, ana.signingPublicKey, "Ana", isInitiator = true,
                expectedPeerKey = readKey,
            )
            Transfer.confirmAsSender(peer, token = returnedToken)
        }
        val advertiser = async {
            val peer = Transfer.greet(
                wire.responderIn, wire.responderOut,
                brais.deviceId, brais.signingPublicKey, "Brais", isInitiator = false,
                ephemeralKeys = advertised,
            )
            expectTapToken(peer, token)
            Transfer.acknowledge(peer)
        }
        return tapper to advertiser
    }

    @Test
    fun `a genuine tap pairs with nothing to compare`() {
        val advertised = LocalPairing.generateKeys()
        val token = Primitives.randomBytes(32)

        val (tapper, advertiser) = pair(advertised, advertised.publicKey, token, token)

        tapper.get(10, TimeUnit.SECONDS)
        advertiser.get(10, TimeUnit.SECONDS)
    }

    @Test
    fun `a socket presenting a key nobody touched is refused`() {
        // What an interposed device looks like: it agreed a session with the tapping phone, but the
        // key it presented is not the one printed on the tag that phone actually read.
        val advertised = LocalPairing.generateKeys()
        val somebodyElse = LocalPairing.generateKeys()
        val token = Primitives.randomBytes(32)

        val (tapper, _) = pair(advertised, somebodyElse.publicKey, token, token)

        val failure = runCatching { tapper.get(10, TimeUnit.SECONDS) }.exceptionOrNull()
        assertThat(causeOf(failure)?.code).isEqualTo(TransferError.DIGITS_MISMATCH)
    }

    @Test
    fun `a peer that never touched the phone cannot return its token`() {
        val advertised = LocalPairing.generateKeys()
        val token = Primitives.randomBytes(32)

        // The socket is honest about its key; it simply never read the tag, so it guesses.
        val (_, advertiser) = pair(advertised, advertised.publicKey, token, Primitives.randomBytes(32))

        val failure = runCatching { advertiser.get(10, TimeUnit.SECONDS) }.exceptionOrNull()
        assertThat(causeOf(failure)?.code).isEqualTo(TransferError.DIGITS_MISMATCH)
    }

    @Test
    fun `a sender without a token falls to the digits, and the token is never revealed`() {
        // The ordering is the security property: the receiver hears the sender's confirmation
        // first, and its own acknowledgement carries no token. A sender that never tapped is
        // not refused outright — it is held for the human comparison, which is the check that
        // applies to it — but nothing it receives helps it pretend it tapped.
        val advertised = LocalPairing.generateKeys()
        val wire = Wire()

        val listPicker = async {
            val peer = Transfer.greet(
                wire.initiatorIn, wire.initiatorOut,
                ana.deviceId, ana.signingPublicKey, "Ana", isInitiator = true,
            )
            // Confirms without a token, the best a device that never touched anything can do.
            Transfer.confirmAsSender(peer)
        }
        val advertiser = async {
            val peer = Transfer.greet(
                wire.responderIn, wire.responderOut,
                brais.deviceId, brais.signingPublicKey, "Brais", isInitiator = false,
                ephemeralKeys = advertised,
            )
            val presented = Transfer.awaitConfirmation(peer)
            Transfer.acknowledge(peer)
            presented
        }

        // Null is the fall-to-digits signal; anything else would let a dialler skip the humans.
        assertThat(advertiser.get(10, TimeUnit.SECONDS)).isNull()
        listPicker.get(10, TimeUnit.SECONDS)
    }

    @Test
    fun `a handover survives the round trip through a tag`() {
        val handover = NfcHandover(
            version = NfcHandover.VERSION,
            ephemeralPublicKey = LocalPairing.generateKeys().publicKey,
            token = Primitives.randomBytes(32),
            host = "192.168.0.22",
            port = 45123,
            displayName = "Pixel 8",
        )

        val read = NfcHandover.decode(handover.encode())

        assertThat(read.host).isEqualTo("192.168.0.22")
        assertThat(read.port).isEqualTo(45123)
        assertThat(read.ephemeralPublicKey).isEqualTo(handover.ephemeralPublicKey)
        assertThat(read.token).isEqualTo(handover.token)
    }

    @Test
    fun `a tag this version cannot read is refused rather than half-trusted`() {
        val garbage = "not a handover at all".toByteArray()

        val failure = runCatching { NfcHandover.decode(garbage) }.exceptionOrNull()

        assertThat((failure as? TransferException)?.code).isEqualTo(TransferError.PROTOCOL)
    }

    /** What the receiving screen does with the sender's confirmation, distilled. */
    private fun expectTapToken(peer: PairedPeer, expected: ByteArray) {
        val presented = Transfer.awaitConfirmation(peer)
        if (presented == null || !presented.contentEquals(expected)) {
            throw TransferException(
                TransferError.DIGITS_MISMATCH,
                "the sender presented a tap token this phone did not publish",
            )
        }
    }

    private fun causeOf(failure: Throwable?): TransferException? =
        generateSequence(failure) { it.cause }.filterIsInstance<TransferException>().firstOrNull()
}
