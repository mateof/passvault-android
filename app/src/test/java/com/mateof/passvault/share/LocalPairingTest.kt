package com.mateof.passvault.share

import com.google.common.truth.Truth.assertThat
import com.mateof.passvault.crypto.Primitives
import org.junit.Test

/**
 * Two phones meeting on a local network.
 *
 * `pairHonestly` is the ordinary case. `pairThroughAttacker` is a device sitting between them,
 * agreeing separately with each side — which is exactly what discovery over mDNS cannot rule out,
 * and the reason the six digits exist at all.
 */
class LocalPairingTest {

    private fun pairHonestly(): Pair<PairingResult, PairingResult> {
        val ana = LocalPairing.generateKeys()
        val brais = LocalPairing.generateKeys()
        return LocalPairing.complete(ana, brais.publicKey, isInitiator = true) to
            LocalPairing.complete(brais, ana.publicKey, isInitiator = false)
    }

    private fun pairThroughAttacker(): Pair<PairingResult, PairingResult> {
        val ana = LocalPairing.generateKeys()
        val brais = LocalPairing.generateKeys()
        val attacker = LocalPairing.generateKeys()
        // Each believes they are talking to the other; both agree with the attacker.
        return LocalPairing.complete(ana, attacker.publicKey, isInitiator = true) to
            LocalPairing.complete(brais, attacker.publicKey, isInitiator = false)
    }

    @Test
    fun `both devices derive the same session key`() {
        val (initiator, responder) = pairHonestly()

        assertThat(initiator.sessionKey).isEqualTo(responder.sessionKey)
    }

    @Test
    fun `both screens show the same digits`() {
        val (initiator, responder) = pairHonestly()

        assertThat(initiator.shortAuthenticationString)
            .isEqualTo(responder.shortAuthenticationString)
    }

    @Test
    fun `the digits are six, zero-padded, so a leading zero is never dropped`() {
        val (initiator, _) = pairHonestly()

        assertThat(initiator.shortAuthenticationString).matches("\\d{$SAS_DIGITS}")
    }

    @Test
    fun `a device in the middle cannot make both screens agree`() {
        // The whole point. An attacker holds two different shared secrets, one per side, so the
        // digits differ and the users see it before anything transfers.
        val (initiator, responder) = pairThroughAttacker()

        assertThat(initiator.shortAuthenticationString)
            .isNotEqualTo(responder.shortAuthenticationString)
    }

    @Test
    fun `and reaches neither honest session key`() {
        val (initiator, responder) = pairThroughAttacker()

        assertThat(initiator.sessionKey).isNotEqualTo(responder.sessionKey)
    }

    @Test
    fun `every pairing produces different digits, since the keys are ephemeral`() {
        val first = pairHonestly().first.shortAuthenticationString
        val second = pairHonestly().first.shortAuthenticationString

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `the session key is a full-strength key, not a truncated one`() {
        val (initiator, _) = pairHonestly()

        assertThat(initiator.sessionKey).hasLength(Primitives.KEY_BYTES)
    }

    @Test
    fun `swapping who thinks they started produces different digits, which is why the role is fixed`() {
        // Both sides must order the two public keys identically. If they disagree about who
        // initiated, every honest pairing would look like an attack.
        val ana = LocalPairing.generateKeys()
        val brais = LocalPairing.generateKeys()

        val correct = LocalPairing.complete(brais, ana.publicKey, isInitiator = false)
        val confused = LocalPairing.complete(brais, ana.publicKey, isInitiator = true)

        assertThat(correct.shortAuthenticationString)
            .isNotEqualTo(confused.shortAuthenticationString)
    }

    @Test
    fun `the derived digits match the ones the server derives`() {
        // The transcript is hashed the same way on both platforms, so a phone can pair with the web
        // interface as well as with another phone. Fixed keys, so the expectation is reproducible.
        val initiatorPrivate = ByteArray(32) { 1 }
        val responderPrivate = ByteArray(32) { 2 }
        val initiator = keysFrom(initiatorPrivate)
        val responder = keysFrom(responderPrivate)

        val a = LocalPairing.complete(initiator, responder.publicKey, isInitiator = true)
        val b = LocalPairing.complete(responder, initiator.publicKey, isInitiator = false)

        assertThat(a.shortAuthenticationString).isEqualTo(b.shortAuthenticationString)
        assertThat(a.sessionKey).isEqualTo(b.sessionKey)
        // Pinned against the value the TypeScript implementation produces for these same keys. If
        // either side changes how the transcript is hashed, this fails rather than a user watching
        // two devices show different digits and concluding they are being attacked.
        assertThat(a.shortAuthenticationString).isEqualTo("857495")
        assertThat(com.mateof.passvault.crypto.Base64Url.encode(a.sessionKey))
            .isEqualTo("MrkaWe4E_laRP6T1ByC1ZEg16OO4lRqLzI87peD1VmQ")
    }

    private fun keysFrom(privateKey: ByteArray): PairingKeys {
        val parameters =
            org.bouncycastle.crypto.params.X25519PrivateKeyParameters(privateKey, 0)
        return PairingKeys(parameters.encoded, parameters.generatePublicKey().encoded)
    }
}
