package com.mateof.passvault.server

import com.mateof.passvault.sync.Operation
import com.mateof.passvault.sync.Operations
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Talking to a PassVault server.
 *
 * Written against the server's actual routes rather than a guess, because a client built from an
 * assumption is a client that compiles and fails on contact — the web front end learned that four
 * times over in one sitting.
 *
 * Hand-rolled over OkHttp instead of Retrofit. The surface is a dozen endpoints whose bodies are
 * already `JsonObject` on this side — operations are stored and signed as JSON, not as data classes
 * — so an interface of typed models would mean converting to types and back again for no gain.
 *
 * The token lives in memory. Written to disk it would be a bearer credential sitting in the app's
 * storage for as long as it is valid; held here, a restart asks again, which is the right trade for
 * something that opens a wallet.
 */
class ServerApi(private val settings: ServerSettings) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    @Volatile
    private var token: String? = null

    val isSignedIn: Boolean get() = token != null

    fun signOutLocally() {
        token = null
    }

    private fun url(path: String): String {
        val base = settings.baseUrl().trimEnd('/')
        return "$base$path"
    }

    private fun call(
        path: String,
        body: JsonObject? = null,
        method: String = if (body == null) "GET" else "POST",
    ): JsonObject {
        val request = Request.Builder()
            .url(url(path))
            .apply {
                token?.let { header("Authorization", "Bearer $it") }
                // Asked for in the user's language, so a refusal comes back already translated by
                // the side that owns the wording rather than being reworded here from a code.
                header("Accept-Language", settings.locale())
                when (method) {
                    "GET" -> get()
                    "DELETE" -> delete()
                    else -> post((body ?: JsonObject(emptyMap())).toString().toRequestBody(jsonType))
                }
            }
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val parsed = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!response.isSuccessful) {
                throw ServerException(
                    status = response.code,
                    // The server's own message when there is one. A code translated here would be
                    // a second place the wording lives, and the two would drift.
                    message = parsed?.text("message") ?: parsed?.text("error")
                        ?: "HTTP ${response.code}",
                    code = parsed?.text("code"),
                )
            }
            return parsed ?: JsonObject(emptyMap())
        }
    }

    /** Whether the address points at something that answers like a PassVault server. */
    fun probe(): String = call("/api/v1/health").text("status") ?: "unknown"

    /**
     * Signs in with an email and a password.
     *
     * Returns what the server said rather than a boolean: a second factor is not a failure, it is
     * the other half of the same conversation, and the caller needs the challenge to continue it.
     */
    fun signIn(email: String, password: String): SignInOutcome {
        val result = call(
            "/api/v1/auth/login",
            buildJsonObject {
                put("email", email)
                put("password", password)
            },
        )
        return outcomeOf(result)
    }

    fun completeSecondFactor(challenge: String, code: String, method: String): SignInOutcome =
        outcomeOf(
            call(
                "/api/v1/auth/second-factor",
                buildJsonObject {
                    put("challenge", challenge)
                    put("code", code)
                    put("method", method)
                },
            ),
        )

    private fun outcomeOf(result: JsonObject): SignInOutcome {
        if (result.text("status") == "complete") {
            token = result.text("token")
            return SignInOutcome.SignedIn(result.text("userId").orEmpty())
        }
        return SignInOutcome.SecondFactorNeeded(
            challenge = result.text("challenge").orEmpty(),
            methods = result["methods"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull() }
                ?: listOf("email"),
        )
    }

    fun signOut() {
        runCatching { call("/api/v1/auth/logout", buildJsonObject { }) }
        token = null
    }

    fun me(): Account {
        val result = call("/api/v1/me")
        return Account(
            userId = result.text("userId").orEmpty(),
            isAdmin = result["isAdmin"]?.jsonPrimitive?.content == "true",
            vaultUnlocked = result["vaultUnlocked"]?.jsonPrimitive?.content == "true",
        )
    }

    /** Opens the vault for this session. The passphrase is never stored, here or there. */
    fun unlockVault(passphrase: String) {
        call("/api/v1/vault/unlock", buildJsonObject { put("passphrase", passphrase) })
    }

    fun events(): List<String> =
        call("/api/v1/events")["events"]?.jsonArray
            ?.mapNotNull { it.jsonObject.text("id") }
            ?: emptyList()

    /**
     * Tells the server which device this is, and what its signatures look like.
     *
     * Has to happen before anything is pushed. The server verifies every operation against a
     * registered signing key, and holds back what it cannot verify — so an unregistered device
     * uploads a wallet that lands entirely in quarantine and reports success while doing it.
     *
     * Idempotent, and keyed on the signing key rather than on the identifier: registering twice
     * returns the same device, and the identifier this device already signs with is kept, because
     * changing it would orphan every operation it has produced.
     */
    fun registerDevice(
        deviceId: String,
        name: String,
        signingPublicKey: String,
        agreementPublicKey: String,
    ): String =
        call(
            "/api/v1/devices",
            buildJsonObject {
                put("deviceId", deviceId)
                put("name", name)
                put("signingPublicKey", signingPublicKey)
                put("agreementPublicKey", agreementPublicKey)
            },
        ).text("deviceId").orEmpty()

    /**
     * One round trip in both directions, which is what the server offers and what a phone wants.
     *
     * What is sent is applied before what comes back is computed, so a device never receives a view
     * that predates its own contribution.
     */
    fun sync(
        eventId: String,
        operations: List<Operation>,
        cursor: String?,
        eventPassword: String?,
    ): SyncResult {
        val result = call(
            "/api/v1/sync/$eventId",
            buildJsonObject {
                putJsonArray("operations") { operations.forEach { add(it.signedJson()) } }
                if (cursor != null) put("cursor", cursor)
                if (eventPassword != null) put("eventPassword", eventPassword)
            },
        )
        return SyncResult(
            received = (result["operations"] as? JsonArray).orEmpty()
                .map { Operations.fromSignedJson(it.jsonObject) },
            cursor = result.text("cursor"),
            hasMore = result["hasMore"]?.jsonPrimitive?.content == "true",
            nextLamport = result["nextLamport"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            accepted = (result["accepted"] as? JsonArray).orEmpty().size,
            // True when this call is what brought the event into existence there, which is a
            // different thing to tell the user than "we exchanged some operations".
            created = result["created"]?.jsonPrimitive?.content == "true",
        )
    }

    // --- Documents ----------------------------------------------------------------------

    /**
     * The original files the server holds for an event.
     *
     * The signed log carries what happened to an event; the PDF it all came out of is not one of
     * those things, so it needs a channel of its own. Without it a wallet built on a phone
     * synchronised its tickets and left the file behind — and the pages ingestion drops on
     * purpose, the map and the terms and the gate instructions, existed on one device only.
     */
    fun documents(eventId: String): List<RemoteDocument> =
        (call("/api/v1/events/$eventId/documents")["documents"] as? JsonArray).orEmpty()
            .map { it.jsonObject }
            .mapNotNull { document ->
                val id = document.text("id") ?: return@mapNotNull null
                RemoteDocument(
                    id = id,
                    mediaType = document.text("mediaType") ?: "application/octet-stream",
                    pageCount = document["pageCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                )
            }

    /**
     * Sends one, under the identifier this phone already uses for it.
     *
     * A PUT because the client names the resource: saying it twice says the same thing, so a
     * synchronisation that runs every day uploads the file once rather than accumulating a copy
     * per run. The server answers 200 rather than 201 when it already had it.
     */
    fun uploadDocument(
        eventId: String,
        documentId: String,
        mediaType: String,
        pageCount: Int,
        bytes: ByteArray,
    ): Boolean {
        val request = Request.Builder()
            .url(url("/api/v1/events/$eventId/documents/$documentId?pages=$pageCount"))
            .apply {
                token?.let { header("Authorization", "Bearer $it") }
                header("Accept-Language", settings.locale())
                put(bytes.toRequestBody(mediaType.toMediaType()))
            }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val parsed = runCatching {
                    Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                }.getOrNull()
                throw ServerException(
                    status = response.code,
                    message = parsed?.text("message") ?: "HTTP ${response.code}",
                    code = parsed?.text("code"),
                )
            }
            // 201 means it was stored now; 200 means it was already there. Only the first is
            // worth counting, or a summary reports uploads that never happened.
            return response.code == 201
        }
    }

    /** Fetches one whole. Null when the server no longer has it, which is not a broken wallet. */
    fun downloadDocument(eventId: String, documentId: String): ByteArray? {
        val request = Request.Builder()
            .url(url("/api/v1/events/$eventId/documents/$documentId"))
            .apply {
                token?.let { header("Authorization", "Bearer $it") }
                header("Accept-Language", settings.locale())
                get()
            }
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) {
                throw ServerException(response.code, "HTTP ${response.code}", null)
            }
            return response.body?.bytes()
        }
    }

    // --- Groups and sharing -------------------------------------------------------------

    fun groups(): List<Group> =
        (call("/api/v1/groups")["groups"] as? JsonArray).orEmpty().map { it.jsonObject }.map {
            Group(
                id = it.text("id").orEmpty(),
                name = it.text("name").orEmpty(),
                role = it.text("role").orEmpty(),
                memberCount = it["memberCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            )
        }

    fun createGroup(name: String): String =
        call("/api/v1/groups", buildJsonObject { put("name", name) }).text("groupId").orEmpty()

    fun addMember(groupId: String, email: String) {
        call("/api/v1/groups/$groupId/members", buildJsonObject { put("email", email) })
    }

    /**
     * Gives a group or a person access to an event.
     *
     * The assignment mode travels with the event rather than with the grant: it is a property of
     * how the tickets are handed out, not of who can see them.
     */
    fun shareEvent(eventId: String, subjectKind: String, subjectId: String, role: String = "MEMBER") {
        call(
            "/api/v1/events/$eventId/access",
            buildJsonObject {
                put("subjectKind", subjectKind)
                put("subjectId", subjectId)
                put("role", role)
            },
        )
    }

    // --- Passkeys -----------------------------------------------------------------------
    //
    // The options and the response are passed through as JSON. WebAuthn's shapes are defined by
    // the specification and understood by both the platform and the server; a third model here
    // would be a third opinion about a format that already has two agreeing ones.

    fun passkeyLoginOptions(): String = call("/api/v1/passkeys/login/options", JsonObject(emptyMap())).toString()

    fun passkeyLogin(responseJson: String): SignInOutcome {
        val response = Json.parseToJsonElement(responseJson)
        val result = call(
            "/api/v1/passkeys/login",
            buildJsonObject { put("response", response) },
        )
        return outcomeOf(result)
    }

    /**
     * Starts enrolling a second factor.
     *
     * Returns the secret and the `otpauth:` URI that every authenticator understands. There is
     * nothing Google-specific or Microsoft-specific about either: TOTP is RFC 6238, the URI is the
     * key format Google published and everybody adopted, and the server's parameters — HMAC-SHA1,
     * six digits, thirty seconds — are the ones both of those apps require of a third-party
     * account.
     *
     * The secret is stored unconfirmed. An unconfirmed secret never satisfies a factor, so an
     * enrolment abandoned halfway cannot lock somebody out with a code they never scanned.
     */
    fun totpEnrol(): TotpEnrolment {
        val result = call("/api/v1/totp/enrol", JsonObject(emptyMap()))
        return TotpEnrolment(
            secret = result.text("secret").orEmpty(),
            uri = result.text("uri").orEmpty(),
        )
    }

    fun totpConfirm(code: String) {
        call("/api/v1/totp/confirm", buildJsonObject { put("code", code) })
    }

    fun passkeyRegisterOptions(): String =
        call("/api/v1/passkeys/register/options", JsonObject(emptyMap())).toString()

    fun passkeyRegister(responseJson: String, name: String) {
        val response = Json.parseToJsonElement(responseJson)
        call(
            "/api/v1/passkeys/register",
            buildJsonObject {
                put("response", response)
                put("name", name)
            },
        )
    }

    private fun JsonObject.text(key: String): String? =
        this[key]?.jsonPrimitive?.let { if (it.isString) it.content else null }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        if (isString) content else null
}

data class Group(val id: String, val name: String, val role: String, val memberCount: Int)

/** What an authenticator needs, in the two forms one might be given it. */
data class TotpEnrolment(val secret: String, val uri: String)

/** A document the server holds, as its listing describes it. The bytes are fetched separately. */
data class RemoteDocument(val id: String, val mediaType: String, val pageCount: Int)

data class Account(val userId: String, val isAdmin: Boolean, val vaultUnlocked: Boolean)

sealed interface SignInOutcome {
    data class SignedIn(val userId: String) : SignInOutcome

    /** Not a failure: the other half of the same conversation. */
    data class SecondFactorNeeded(val challenge: String, val methods: List<String>) : SignInOutcome
}

data class SyncResult(
    val received: List<Operation>,
    val cursor: String?,
    val hasMore: Boolean,
    /** What the server says this device's clock should be at least. */
    val nextLamport: Long,
    val accepted: Int,
    /** True when this synchronisation is what created the event on the server. */
    val created: Boolean = false,
)

class ServerException(
    val status: Int,
    message: String,
    val code: String? = null,
) : IOException(message)
