package com.mateof.passvault.share

import com.mateof.passvault.crypto.Base64Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * What one phone hands the other by touching it, and why that removes the six digits.
 *
 * The six-digit comparison exists because being on the same Wi-Fi authenticates nobody: on a café
 * network any device can advertise itself as "Ana's PassVault", so the two sides derive digits from
 * the transcript and two humans check they match. It works, and it is the step people get wrong —
 * they glance, they say "yes", and the check they performed was a ritual.
 *
 * Holding two phones together is a channel an attacker on the network cannot reach into. So the tap
 * carries what the digits were protecting, and the comparison stops being necessary:
 *
 *   * **the advertiser's ephemeral public key.** The receiver checks that the key arriving over the
 *     network is the one it read over NFC. A device in the middle can substitute a key on the
 *     socket; it cannot substitute one it never touched.
 *   * **a single-use token.** Sent back through the *encrypted* session, it proves to the advertiser
 *     that the peer on the socket is the phone that physically touched it — otherwise the tap would
 *     authenticate only one direction, and the advertiser would still be talking to anybody.
 *   * **where to connect**, so the receiver does not have to find the right name in a list of two
 *     identical ones.
 *
 * The token is worth nothing to somebody who reads it later: it authorises one session and the
 * session key is derived from keys that exist for one attempt. A tap that is not followed by a
 * connection leaves nothing behind.
 *
 * The payload is small on purpose — a key, a token, an address, a name — because NFC moves bytes
 * slowly and this is a handshake, not a transfer. The tickets go over Wi-Fi at full speed.
 */
data class NfcHandover(
    val version: Int,
    /** The advertiser's ephemeral X25519 public key, the one the socket must also present. */
    val ephemeralPublicKey: ByteArray,
    /** Proves, when it comes back through the session, that this peer is the one that tapped. */
    val token: ByteArray,
    val host: String,
    val port: Int,
    val displayName: String,
) {
    override fun equals(other: Any?) = this === other

    override fun hashCode() = System.identityHashCode(this)

    fun encode(): ByteArray = buildJsonObject {
        put("v", version)
        put("k", Base64Url.encode(ephemeralPublicKey))
        put("t", Base64Url.encode(token))
        put("h", host)
        put("p", port)
        put("n", displayName)
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        const val VERSION = 1

        /** The bytes a reader got. Anything it cannot read is refused rather than half-trusted. */
        fun decode(bytes: ByteArray): NfcHandover {
            val json: JsonObject = runCatching {
                Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            }.getOrElse {
                throw TransferException(TransferError.PROTOCOL, "the tap carried no handover", it)
            }

            val version = json["v"]?.jsonPrimitive?.content?.toIntOrNull()
            if (version != VERSION) {
                throw TransferException(
                    TransferError.UNSUPPORTED_VERSION,
                    "the other phone taps version $version, this one taps $VERSION",
                )
            }

            return NfcHandover(
                version = version,
                ephemeralPublicKey = key(json, "k"),
                token = key(json, "t"),
                host = text(json, "h") ?: throw malformed("h"),
                port = json["p"]?.jsonPrimitive?.content?.toIntOrNull() ?: throw malformed("p"),
                displayName = text(json, "n").orEmpty(),
            )
        }

        private fun key(json: JsonObject, name: String): ByteArray =
            runCatching { Base64Url.decodeExact(text(json, name)!!, 32) }
                .getOrElse { throw malformed(name) }

        private fun text(json: JsonObject, name: String): String? =
            json[name]?.jsonPrimitive?.let { if (it.isString) it.content else null }

        private fun malformed(field: String) =
            TransferException(TransferError.PROTOCOL, "the tap carried no usable $field")
    }
}
