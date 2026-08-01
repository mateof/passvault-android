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
import kotlinx.serialization.json.add
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
        // The app says what it is and what it runs on. The default "okhttp/4.x" names a
        // library, and the session list is read by a person looking for their own phone.
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header(
                        "User-Agent",
                        "PassVault Android (${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL})",
                    )
                    .build(),
            )
        }
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /**
     * The bearer token.
     *
     * Restored from storage on first use rather than held only in memory: a wallet that asks for
     * a password every time the process restarts is a wallet nobody keeps signed in, and this is
     * the app somebody opens in a queue.
     */
    @Volatile
    private var token: String? = settings.sessionToken()

    val isSignedIn: Boolean get() = token != null

    /** Forgets it here and on disk. What "sign out" means and the only thing that ends it. */
    fun signOutLocally() {
        token = null
        settings.setSessionToken(null)
    }

    private fun url(path: String): String {
        val base = settings.baseUrl().trimEnd('/')
        return "$base$path"
    }

    private fun call(
        path: String,
        body: JsonObject? = null,
        method: String = if (body == null) "GET" else "POST",
    ): JsonObject = callOnce(path, body, method, retryOnLockedVault = true)

    /**
     * One request, with one self-service retry for a vault the server forgot.
     *
     * The server drops its unwrapped keys on every restart, by design, and answers 423 until the
     * passphrase is given again. This phone keeps that passphrase sealed under its KeyStore, so
     * the honest response to a 423 is to unlock and try once more — not to surface an error to
     * a screen that can do nothing about it. The retry happened only inside the synchroniser at
     * first, which left every other feature quietly broken after a server update.
     */
    private fun callOnce(
        path: String,
        body: JsonObject? = null,
        method: String = if (body == null) "GET" else "POST",
        retryOnLockedVault: Boolean = false,
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
                    // With a body when there is one: revoking access names its subject in the
                    // body, and a DELETE that dropped it would revoke nothing.
                    "DELETE" ->
                        if (body == null) delete() else delete(body.toString().toRequestBody(jsonType))
                    "PATCH" -> patch((body ?: JsonObject(emptyMap())).toString().toRequestBody(jsonType))
                    else -> post((body ?: JsonObject(emptyMap())).toString().toRequestBody(jsonType))
                }
            }
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val parsed = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!response.isSuccessful) {
                val code = parsed?.text("code") ?: parsed?.text("error")
                if (
                    retryOnLockedVault &&
                    response.code == 423 &&
                    code == "vault.passphraseRequired"
                ) {
                    val passphrase = settings.vaultPassphrase()
                    if (passphrase != null) {
                        val unlocked = runCatching {
                            callOnce(
                                "/api/v1/vault/unlock",
                                buildJsonObject { put("passphrase", passphrase) },
                            )
                        }.isSuccess
                        if (unlocked) {
                            // Once. A second 423 after a successful unlock is a different
                            // problem, and looping on it would hide whatever it is.
                            return callOnce(path, body, method, retryOnLockedVault = false)
                        }
                    }
                }
                throw ServerException(
                    status = response.code,
                    // The server's own message when there is one. A code translated here would be
                    // a second place the wording lives, and the two would drift.
                    message = parsed?.text("message") ?: parsed?.text("error")
                        ?: "HTTP ${response.code}",
                    // The machine-readable key rides in `error`; a caller deciding what a 423
                    // means needs the key, not the sentence.
                    code = code,
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
            settings.setSessionToken(token)
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
        settings.setSessionToken(null)
    }

    /** Where the web administration lives, for a phone that wants to open it. */
    fun adminUrl(): String = settings.baseUrl().trimEnd('/') + "/admin"

    fun me(): Account {
        val result = call("/api/v1/me")
        return Account(
            userId = result.text("userId").orEmpty(),
            isAdmin = result["isAdmin"]?.jsonPrimitive?.content == "true",
            vaultUnlocked = result["vaultUnlocked"]?.jsonPrimitive?.content == "true",
            // Null until one is chosen. A client that cannot see it cannot tell "you have no
            // name" from "we never asked", which is how somebody sets the same handle twice.
            handle = result.text("handle"),
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

    /**
     * The creator's readable copy of an event password. Null for none, and null for anybody who
     * is not the creator — asked with a catch rather than a permission check, because whether
     * this phone created the event is exactly what the server knows and the phone may not.
     */
    fun eventPassword(eventId: String): String? = try {
        call("/api/v1/events/$eventId/password").text("password")
    } catch (refused: ServerException) {
        if (refused.status == 403 || refused.status == 404) null else throw refused
    }

    /** Sets, changes or (with null) removes the event password. Creator only; the server enforces it. */
    fun setEventPasswordOnServer(eventId: String, password: String?) {
        call(
            "/api/v1/events/$eventId/password",
            buildJsonObject {
                if (password == null) put("password", kotlinx.serialization.json.JsonNull)
                else put("password", password)
            },
            method = "PUT",
        )
    }

    // --- Labels ---------------------------------------------------------------------------

    /**
     * The reader's own labels.
     *
     * Labels live on the server because they belong to an account rather than to a device: the
     * same person's wallet on a second phone should be organised the same way. That means the app
     * can only show them while it is signed in, which is the same rule that already applies to
     * groups and to notices.
     */
    fun tags(): List<Tag> =
        (call("/api/v1/tags")["tags"] as? JsonArray).orEmpty().map { it.jsonObject }.map {
            Tag(
                id = it.text("id").orEmpty(),
                name = it.text("name").orEmpty(),
                colour = it.text("colour").orEmpty().ifBlank { "violet" },
                eventCount = it["eventCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            )
        }

    fun createTag(name: String, colour: String): String =
        call(
            "/api/v1/tags",
            buildJsonObject {
                put("name", name)
                put("colour", colour)
            },
        ).text("tagId").orEmpty()

    fun updateTag(tagId: String, name: String, colour: String) {
        call(
            "/api/v1/tags/$tagId",
            buildJsonObject {
                put("name", name)
                put("colour", colour)
            },
            method = "PATCH",
        )
    }

    fun deleteTag(tagId: String) {
        call("/api/v1/tags/$tagId", method = "DELETE")
    }

    /** Which labels an event carries. Replaces the set rather than adding to it. */
    fun setEventTags(eventId: String, tagIds: List<String>) {
        call(
            "/api/v1/events/$eventId/tags",
            buildJsonObject {
                putJsonArray("tagIds") { tagIds.forEach { add(it) } }
            },
            method = "PUT",
        )
    }

    /**
     * Which labels each event carries, for the whole wallet at once.
     *
     * One request rather than one per event: a wallet of twelve events would otherwise be twelve
     * round trips to draw twelve coloured dots.
     */
    fun eventTags(): Map<String, List<String>> =
        (call("/api/v1/events")["events"] as? JsonArray).orEmpty()
            .map { it.jsonObject }
            .mapNotNull { event ->
                val id = event.text("id") ?: return@mapNotNull null
                val tags = (event["tagIds"] as? JsonArray).orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNull() }
                id to tags
            }
            .toMap()

    // --- Notices, invitations and sessions -----------------------------------------------

    /**
     * What happened while nobody was looking, and what needs an answer.
     *
     * Sharing offers an event now rather than imposing it, so a wallet that cannot read this list
     * is a wallet where everything anybody shares stays invisible.
     */
    fun notices(): Pair<List<Notice>, Int> {
        val result = call("/api/v1/notifications")
        val list = (result["notifications"] as? JsonArray).orEmpty().map { it.jsonObject }.map {
            Notice(
                id = it.text("id").orEmpty(),
                kind = it.text("kind").orEmpty(),
                eventId = (it["payload"] as? JsonObject)?.text("eventId").orEmpty(),
                eventName = (it["payload"] as? JsonObject)?.text("eventName").orEmpty(),
                invitedBy = (it["payload"] as? JsonObject)?.text("invitedBy").orEmpty(),
                createdAt = it.text("createdAt").orEmpty(),
                read = it["read"]?.jsonPrimitive?.content == "true",
            )
        }
        val unread = result["unread"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        return list to unread
    }

    fun markNoticesRead() {
        call("/api/v1/notifications/read", buildJsonObject { })
    }

    fun invitations(): List<Invitation> =
        (call("/api/v1/invitations")["invitations"] as? JsonArray).orEmpty()
            .map { it.jsonObject }
            .map {
                Invitation(
                    id = it.text("id").orEmpty(),
                    eventId = it.text("eventId").orEmpty(),
                    state = it.text("state").orEmpty(),
                    passwordProtected =
                        it["passwordProtected"]?.jsonPrimitive?.content == "true",
                )
            }

    /** Says yes. The password, when the event has one, is typed here — the first moment it can be. */
    fun acceptInvitation(id: String, password: String?) {
        call(
            "/api/v1/invitations/$id/accept",
            buildJsonObject { if (!password.isNullOrBlank()) put("password", password) },
        )
    }

    fun declineInvitation(id: String) {
        call("/api/v1/invitations/$id/decline", buildJsonObject { })
    }

    val baseUrl: String get() = settings.baseUrl()

    /** Where this account is open. A phone left in a taxi is what this is for. */
    fun sessions(): List<OpenSession> =
        (call("/api/v1/sessions")["sessions"] as? JsonArray).orEmpty()
            .map { it.jsonObject }
            .map {
                OpenSession(
                    id = it.text("id").orEmpty(),
                    current = it["current"]?.jsonPrimitive?.content == "true",
                    userAgent = it.text("userAgent"),
                    ipAddress = it.text("ipAddress"),
                    createdAt = it.text("createdAt"),
                    lastSeenAt = it.text("lastSeenAt"),
                    expiresAt = it.text("expiresAt"),
                )
            }

    fun revokeSession(id: String) {
        call("/api/v1/sessions/$id", method = "DELETE")
    }

    /** A public name to be found by, so somebody can share without knowing an address. */
    fun setHandle(handle: String): String =
        call("/api/v1/me/handle", buildJsonObject { put("handle", handle) }, method = "PUT")
            .text("handle")
            .orEmpty()

    /**
     * Whether a handle is taken by somebody else.
     *
     * "Taken by you" reports false: a form has to treat your own name as available to you, or
     * typing exactly the handle you already hold gets refused — which is what happened.
     */
    fun handleTaken(handle: String): Boolean {
        val result =
            call("/api/v1/directory/handle?handle=" + java.net.URLEncoder.encode(handle, "UTF-8"))
        val taken = result["taken"]?.jsonPrimitive?.content == "true"
        val mine = result["mine"]?.jsonPrimitive?.content == "true"
        return taken && !mine
    }

    /** Shares with somebody named the way people name each other, rather than by identifier. */
    fun shareEventWithHandle(eventId: String, handle: String) {
        call(
            "/api/v1/events/$eventId/access",
            buildJsonObject {
                put("subjectKind", "USER")
                put("handle", handle)
            },
        )
    }

    // --- Groups and sharing -------------------------------------------------------------

    fun groups(): List<Group> =
        (call("/api/v1/groups")["groups"] as? JsonArray).orEmpty().map { it.jsonObject }.map {
            Group(
                id = it.text("id").orEmpty(),
                name = it.text("name").orEmpty(),
                role = it.text("role").orEmpty(),
                memberCount = it["memberCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                isOwner = it["isOwner"]?.jsonPrimitive?.content == "true",
            )
        }

    fun createGroup(name: String): String =
        call("/api/v1/groups", buildJsonObject { put("name", name) }).text("groupId").orEmpty()

    fun renameGroup(groupId: String, name: String) {
        call("/api/v1/groups/$groupId", buildJsonObject { put("name", name) }, method = "PATCH")
    }

    fun deleteGroup(groupId: String) {
        call("/api/v1/groups/$groupId", method = "DELETE")
    }

    /** Who is in it, by address: a list of identifiers is not a list of people. */
    fun members(groupId: String): List<GroupMember> =
        (call("/api/v1/groups/$groupId/members")["members"] as? JsonArray).orEmpty()
            .map { it.jsonObject }
            .map {
                GroupMember(
                    userId = it.text("userId").orEmpty(),
                    email = it.text("email").orEmpty(),
                    role = it.text("role").orEmpty(),
                    isSelf = it["isSelf"]?.jsonPrimitive?.content == "true",
                )
            }

    fun addMember(groupId: String, email: String) {
        call("/api/v1/groups/$groupId/members", buildJsonObject { put("email", email) })
    }

    fun removeMember(groupId: String, userId: String) {
        call("/api/v1/groups/$groupId/members/$userId", method = "DELETE")
    }

    /**
     * Whether an address belongs to an account on this server.
     *
     * Asked before sharing rather than after: a typo in an address is otherwise discovered when a
     * friend never sees the ticket, which is far too late to be useful.
     */
    fun lookup(email: String): Boolean =
        call("/api/v1/directory/lookup?email=" + java.net.URLEncoder.encode(email, "UTF-8"))["exists"]
            ?.jsonPrimitive?.content == "true"

    /** Who an event is shared with, so sharing is something the organiser can look at. */
    fun eventAccess(eventId: String): List<AccessEntry> =
        (call("/api/v1/events/$eventId/access")["access"] as? JsonArray).orEmpty()
            .map { it.jsonObject }
            .map {
                AccessEntry(
                    subjectKind = it.text("subjectKind").orEmpty(),
                    subjectId = it.text("subjectId").orEmpty(),
                    label = it.text("label").orEmpty(),
                )
            }

    fun shareEventWithPerson(eventId: String, email: String) {
        call(
            "/api/v1/events/$eventId/access",
            buildJsonObject {
                put("subjectKind", "USER")
                put("email", email)
            },
        )
    }

    fun revokeAccess(eventId: String, subjectKind: String, subjectId: String) {
        call(
            "/api/v1/events/$eventId/access",
            buildJsonObject {
                put("subjectKind", subjectKind)
                put("subjectId", subjectId)
            },
            method = "DELETE",
        )
    }

    /** Takes a free ticket in a self-claim event. No coupon: the caller is looking at the event. */
    fun claimFree(eventId: String): String =
        call("/api/v1/events/$eventId/claim", buildJsonObject { }).text("ticketId").orEmpty()

    /** Gives one to somebody with an account, which is what makes it theirs and nobody else's. */
    fun assignTicket(ticketId: String, holderUserId: String) {
        call(
            "/api/v1/tickets/$ticketId/assign",
            buildJsonObject { put("holderUserId", holderUserId) },
        )
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

data class Group(
    val id: String,
    val name: String,
    val role: String,
    val memberCount: Int,
    /** Whether this account may rename or delete it, which the screen has to know before drawing. */
    val isOwner: Boolean = false,
)

data class GroupMember(
    val userId: String,
    val email: String,
    val role: String,
    val isSelf: Boolean,
) {
    val isOwner: Boolean get() = role == "OWNER"
}

/** A group or a person an event is shared with, named the way its owner thinks of them. */
data class AccessEntry(val subjectKind: String, val subjectId: String, val label: String)

/** What an authenticator needs, in the two forms one might be given it. */
data class TotpEnrolment(val secret: String, val uri: String)

/** A word somebody chose for their own events, and the colour it is drawn in. */
data class Tag(val id: String, val name: String, val colour: String, val eventCount: Int)

/** Something that happened, which the reader may not have been present for. */
data class Notice(
    val id: String,
    val kind: String,
    val eventId: String,
    val eventName: String,
    val invitedBy: String,
    val createdAt: String,
    val read: Boolean,
)

/** An event somebody offered. Nothing happens until it is answered. */
data class Invitation(
    val id: String,
    val eventId: String,
    val state: String,
    val passwordProtected: Boolean,
)

/** One place this account is open, as the request that opened it described itself. */
data class OpenSession(
    val id: String,
    val current: Boolean,
    val userAgent: String?,
    val ipAddress: String?,
    val createdAt: String? = null,
    val lastSeenAt: String? = null,
    val expiresAt: String? = null,
) {
    /**
     * The client as a person would name it.
     *
     * A browser announces itself in a hundred characters of lineage; the app announces the phone
     * model. A row has space for a name, and the whole string is in the detail dialog for
     * whoever wants it.
     */
    val clientName: String
        get() {
            val agent = userAgent ?: return ""
            return when {
                agent.startsWith("PassVault Android") ->
                    agent.substringAfter('(').substringBefore(')').ifBlank { agent }
                "Firefox" in agent -> "Firefox"
                "Edg" in agent -> "Edge"
                "OPR" in agent || "Opera" in agent -> "Opera"
                "Chrome" in agent -> "Chrome"
                "Safari" in agent -> "Safari"
                else -> agent.take(40)
            }
        }
}

/** A document the server holds, as its listing describes it. The bytes are fetched separately. */
data class RemoteDocument(val id: String, val mediaType: String, val pageCount: Int)

data class Account(
    val userId: String,
    val isAdmin: Boolean,
    val vaultUnlocked: Boolean,
    val handle: String? = null,
)

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
