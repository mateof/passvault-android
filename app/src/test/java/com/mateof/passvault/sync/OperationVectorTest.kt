package com.mateof.passvault.sync

import com.google.common.truth.Truth.assertThat
import com.mateof.passvault.crypto.Base64Url
import com.mateof.passvault.crypto.Primitives
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * The contract with the server over the shape of a signed operation.
 *
 * The `.tkpak` vectors pin the file format; these pin the log. They exist because the canonical
 * form is the one place both implementations have to independently rebuild the same bytes, and a
 * disagreement there does not look like a bug — it looks like tampering, because the symptom is one
 * side rejecting the other's signature.
 *
 * Produced by `npm run vectors:generate:operations` in the passvault repository and copied into
 * `app/src/test/resources/vectors`. The cases are chosen for where serialisers differ: unsorted
 * keys, accented text an over-eager escaper would turn into `\uXXXX`, control characters, an
 * explicit null and an empty body.
 */
class OperationVectorTest {

    private val document: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResourceAsStream("vectors/operations.json")) {
            "vectors/operations.json is missing"
        }.reader().readText(),
    ).jsonObject

    private val vectors = document["vectors"]!!.jsonArray.map { it.jsonObject }

    private val signingPrivateKey =
        Base64Url.decode(document["signingPrivateKey"]!!.jsonPrimitive.content)

    private val signingPublicKey =
        Base64Url.decode(document["signingPublicKey"]!!.jsonPrimitive.content)

    /** The vector's operation, as this implementation models it. */
    private fun operationOf(vector: JsonObject): Operation {
        val source = vector["operation"]!!.jsonObject
        val actor = source["actorUserId"]!!
        return Operation(
            operationId = source["operationId"]!!.jsonPrimitive.content,
            deviceId = source["deviceId"]!!.jsonPrimitive.content,
            actorUserId = actor.jsonPrimitive.let { if (it.isString) it.content else null },
            lamport = source["lamport"]!!.jsonPrimitive.content.toLong(),
            wallClock = source["wallClock"]!!.jsonPrimitive.content,
            eventId = source["scope"]!!.jsonObject["id"]!!.jsonPrimitive.content,
            type = source["type"]!!.jsonPrimitive.content,
            body = source["body"]!!.jsonObject,
        )
    }

    @Test
    fun `the public key is the one derived from the vector private key`() {
        assertThat(Base64Url.encode(Primitives.ed25519PublicKey(signingPrivateKey)))
            .isEqualTo(Base64Url.encode(signingPublicKey))
    }

    @Test
    fun `every vector canonicalises to exactly the bytes the server produced`() {
        // The bytes themselves, not a digest of them, so a failure shows which character differs
        // rather than only that something does.
        for (vector in vectors) {
            val canonical = Operations.canonicalBytes(operationOf(vector).unsignedJson())

            assertThat(String(canonical, Charsets.UTF_8))
                .isEqualTo(vector["canonical"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `every vector produces the same signing input digest`() {
        for (vector in vectors) {
            val digest = Primitives.sha256(operationOf(vector).signingInput())

            assertThat(Base64Url.encode(digest))
                .isEqualTo(vector["signingInputSha256"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `every vector signature is reproduced byte for byte`() {
        // The end-to-end pin. Reaching the same signature means the canonical bytes, the digest, the
        // domain separation and the Ed25519 implementation all agree.
        for (vector in vectors) {
            val signature = Primitives.signEd25519(
                signingPrivateKey,
                operationOf(vector).signingInput(),
            )

            assertThat(Base64Url.encode(signature))
                .isEqualTo(vector["signature"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `every vector signature verifies against the vector public key`() {
        for (vector in vectors) {
            val operation = operationOf(vector)
                .copy(signature = vector["signature"]!!.jsonPrimitive.content)

            assertThat(operation.verifiedBy(signingPublicKey)).isTrue()
        }
    }

    @Test
    fun `a body edited after signing no longer verifies`() {
        // What the signature is for. The vectors prove agreement; this proves the agreement is
        // worth something.
        val vector = vectors.first { it["name"]!!.jsonPrimitive.content == "claim-request" }
        val tampered = operationOf(vector).copy(
            signature = vector["signature"]!!.jsonPrimitive.content,
            lamport = 9_999,
        )

        assertThat(tampered.verifiedBy(signingPublicKey)).isFalse()
    }
}
