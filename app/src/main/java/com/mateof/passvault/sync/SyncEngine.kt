package com.mateof.passvault.sync

import com.mateof.passvault.crypto.Base64Url
import com.mateof.passvault.data.DeviceKeys
import com.mateof.passvault.data.WalletRepository
import com.mateof.passvault.server.ServerApi
import com.mateof.passvault.server.ServerException
import com.mateof.passvault.server.ServerSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One synchronisation, wherever it was asked for.
 *
 * It used to live inside the server screen's view model, which meant it could only happen while
 * somebody was looking at that screen and pressing the button. Moving it here is what lets the
 * same code run from a background worker, from the wallet opening, and from the button — one
 * mechanism, so a scheduled run and a manual one cannot drift apart in what they do.
 *
 * Guarded by a mutex, because the three callers can overlap: the app comes to the foreground just
 * as the periodic worker fires, and two synchronisations pushing the same operations at once is
 * the sort of thing that produces duplicate uploads and confusing counters. The second caller
 * waits rather than being refused — being told "already running" is not an answer somebody who
 * pressed a button wants.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val api: ServerApi,
    private val settings: ServerSettings,
    private val log: OperationLog,
    private val wallet: WalletRepository,
    private val keys: DeviceKeys,
) {
    private val running = Mutex()

    /** What this device is called on the server's list: the name its owner gave it. */
    private val deviceName: String get() = settings.deviceName()

    val isPossible: Boolean get() = settings.isConfigured() && api.isSignedIn

    suspend fun sync(): SyncOutcome = running.withLock {
        if (!isPossible) {
            // Not a failure. A wallet with no server is the ordinary case and the whole design.
            return@withLock SyncOutcome.NotConfigured
        }
        withContext(Dispatchers.IO) {
            runCatching { exchange() }.fold(
                onSuccess = { SyncOutcome.Done(it) },
                onFailure = { cause ->
                    // Three different situations that used to collapse into one screen. A dead
                    // session needs a person; a locked vault needs a passphrase this phone may
                    // already hold; a server that is down needs waiting.
                    when {
                        (cause as? ServerException)?.status == 401 -> SyncOutcome.SignedOut
                        (cause as? ServerException)?.status == 423 &&
                            (cause as? ServerException)?.code == "vault.passphraseRequired" ->
                            SyncOutcome.VaultLocked
                        else -> SyncOutcome.Failed(describe(cause))
                    }
                },
            )
        }
    }

    private suspend fun exchange(): SyncSummary {
        var sent = 0
        var applied = 0
        var published = 0
        var documentsUp = 0
        var documentsDown = 0

        // Before anything is pushed. The server verifies every operation against a registered
        // signing key and holds back what it cannot verify, so skipping this uploads a wallet
        // that lands entirely in quarantine — and reports success while doing it.
        val identity = keys.identity()
        api.registerDevice(
            deviceId = identity.deviceId,
            name = deviceName,
            signingPublicKey = Base64Url.encode(identity.signingPublicKey),
            agreementPublicKey = Base64Url.encode(identity.agreementPublicKey),
        )

        val remote = api.events().toSet()
        val local = log.eventIds().toSet()

        // The union, not the local list. Iterating only what this phone already holds makes an
        // event that exists solely on the server unreachable: it is never asked for, so it never
        // arrives. Joining a server is mostly about the events already there.
        for (eventId in remote + local) {
            val mine = if (eventId in local) {
                log.since(eventId, cursor = "", limit = 500).operations
            } else {
                emptyList()
            }
            // The password the event is to be published under, if one was chosen. It can only be
            // set as the server creates the event, which for a wallet built offline is now.
            val chosen = settings.eventPassword(eventId)
            // The codes this phone holds for the event, uploaded alongside the log rather than in
            // it. The server seals them and serves them only on download; it takes them only from
            // the creator, so a member offering theirs is harmlessly ignored. An assignee holds no
            // code and sends none — which is the whole point.
            val codes = if (eventId in local) wallet.localBarcodes(eventId) else emptyList()
            val result = api.sync(eventId, mine, cursor = null, eventPassword = chosen, barcodes = codes)
            if (chosen != null && result.created) settings.setEventPassword(eventId, null)
            sent += mine.size
            if (result.created) published += 1
            applied += log.accept(result.received).count { it.state == AcceptState.APPLIED }

            // After the operations, because the event has to exist on both sides before a file
            // can be attached to it.
            val exchanged = exchangeDocuments(eventId)
            documentsUp += exchanged.first
            documentsDown += exchanged.second
        }

        wallet.projectAll()
        return SyncSummary(
            sent = sent,
            received = applied,
            published = published,
            documentsSent = documentsUp,
            documentsReceived = documentsDown,
        )
    }

    /**
     * The original files, in both directions.
     *
     * The log carries operations, and a PDF is not one: it is not something that happened to an
     * event, it is what the event was made from. So it travels beside the log, by identifier —
     * each side asks what the other has and sends only what is missing.
     */
    private suspend fun exchangeDocuments(eventId: String): Pair<Int, Int> {
        val here = wallet.documentsOf(eventId)
        val there = try {
            api.documents(eventId)
        } catch (failure: ServerException) {
            // An event this server does not hold, or will not open for this account. Neither is a
            // failed synchronisation. Anything else is left to fail loudly, because a document
            // that silently never arrives is the very thing this exists to stop.
            if (failure.status == 404 || failure.status == 403) return 0 to 0
            throw failure
        }
        val theirIds = there.map { it.id }.toSet()
        var up = 0
        var down = 0

        for (document in here.filter { it.id !in theirIds }) {
            // Held back on purpose, if its owner said so. The tickets still sync; only the original
            // file stays off the server, which is the whole point of the choice.
            if (settings.serverDocumentBlocked(document.id)) continue
            val bytes = wallet.documentBytes(document.id) ?: continue
            val stored = try {
                api.uploadDocument(
                    eventId = eventId,
                    documentId = document.id,
                    mediaType = document.mediaType,
                    pageCount = document.pageCount,
                    bytes = bytes,
                )
            } catch (failure: ServerException) {
                // Only whoever created the event says what its original file is. A phone that was
                // given tickets holds a copy and is not entitled to publish it.
                if (failure.status == 403) continue
                throw failure
            }
            if (stored) up += 1
        }

        val hereIds = here.map { it.id }.toSet()
        for (document in there.filter { it.id !in hereIds }) {
            val bytes = api.downloadDocument(eventId, document.id) ?: continue
            wallet.keepDocument(
                id = document.id,
                eventId = eventId,
                mediaType = document.mediaType,
                pageCount = document.pageCount,
                bytes = bytes,
            )
            down += 1
        }

        return up to down
    }

    private fun describe(cause: Throwable): String =
        (cause as? ServerException)?.message ?: cause.message ?: "error"
}

/** What a synchronisation did, or why it did nothing. */
sealed interface SyncOutcome {
    data class Done(val summary: SyncSummary) : SyncOutcome

    /** No server, or not signed in. The ordinary state of a wallet that never joined one. */
    data object NotConfigured : SyncOutcome

    /** The session ended. Somebody has to sign in again; retrying will not help. */
    data object SignedOut : SyncOutcome

    /** The vault needs its passphrase and this phone does not hold it. A person, not a retry. */
    data object VaultLocked : SyncOutcome

    data class Failed(val message: String) : SyncOutcome
}

data class SyncSummary(
    val sent: Int,
    val received: Int,
    /** Events this synchronisation created on the server, having existed only on this phone. */
    val published: Int,
    /** Original files uploaded, which the operation log has no way of carrying. */
    val documentsSent: Int = 0,
    /** And ones fetched, for an event imported somewhere else. */
    val documentsReceived: Int = 0,
)
