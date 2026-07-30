package com.mateof.passvault.share

import com.mateof.passvault.crypto.Primitives
import java.math.BigInteger
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

/**
 * Pairing two phones on a local network.
 *
 * Being on the same Wi-Fi authenticates nobody. On a café or hotel network any device can advertise
 * itself as `_passvault._tcp` under the name "Ana's PassVault", and discovery cannot tell the
 * difference. So the two sides agree a key with X25519 and then each derive six digits from the
 * transcript. The users compare the digits by looking at each other's screens, and nothing moves
 * until they confirm.
 *
 * An attacker who interposes themselves ends up with two different shared secrets — one with each
 * side — and therefore cannot make both screens show the same digits. A mismatch is a detected
 * attack, not a glitch, and the interface says so.
 *
 * This is the short-authentication-string construction from ZRTP, and it mirrors
 * `packages/crypto/src/pairing.ts` on the server exactly. SPAKE2 would let the sender dictate a PIN
 * instead of both parties comparing one, which is slightly nicer, but it would mean hand-writing an
 * unusual protocol twice; this uses only primitives both platforms already ship.
 */
const val SAS_DIGITS = 6

private const val SAS_BYTES = 8

data class PairingKeys(val privateKey: ByteArray, val publicKey: ByteArray) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

data class PairingResult(
    /** Digits both users must compare before any ticket moves. */
    val shortAuthenticationString: String,
    /** Key for the transfer itself, usable only after the users confirm. */
    val sessionKey: ByteArray,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

object LocalPairing {
    /** A fresh pair per pairing attempt. Nothing about a session survives it. */
    fun generateKeys(): PairingKeys {
        val privateKey = X25519PrivateKeyParameters(Primitives.randomBytes(32), 0)
        return PairingKeys(privateKey.encoded, privateKey.generatePublicKey().encoded)
    }

    /**
     * Completes the agreement and derives what both sides need.
     *
     * `isInitiator` decides the order the two public keys are hashed in. Both sides must order them
     * identically or they derive different digits, and every honest pairing would look like an
     * attack.
     */
    fun complete(
        own: PairingKeys,
        peerPublicKey: ByteArray,
        isInitiator: Boolean,
    ): PairingResult {
        val shared = Primitives.agree(own.privateKey, peerPublicKey)
        val initiatorKey = if (isInitiator) own.publicKey else peerPublicKey
        val responderKey = if (isInitiator) peerPublicKey else own.publicKey
        val salt = initiatorKey + responderKey

        return PairingResult(
            shortAuthenticationString = digitsFrom(
                Primitives.hkdf(shared, salt, "passvault/v1/sas", SAS_BYTES),
            ),
            sessionKey = Primitives.hkdf(shared, salt, "passvault/v1/session"),
        )
    }

    /**
     * Maps eight bytes onto six decimal digits.
     *
     * Reducing 64 bits modulo a million leaves a bias far below the one-in-a-million an attacker
     * already faces. Doing the same with four bytes would be visible.
     */
    private fun digitsFrom(bytes: ByteArray): String {
        val value = BigInteger(1, bytes)
        val modulus = BigInteger.TEN.pow(SAS_DIGITS)
        return value.mod(modulus).toString().padStart(SAS_DIGITS, '0')
    }
}
