package com.mateof.passvault.ui.share

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mateof.passvault.data.DeviceKeys
import com.mateof.passvault.server.ServerSettings
import com.mateof.passvault.share.DiscoveredPeer
import com.mateof.passvault.share.LocalPairing
import com.mateof.passvault.share.NfcHandover
import com.mateof.passvault.share.NfcHandoverSource
import com.mateof.passvault.share.PairedPeer
import com.mateof.passvault.share.PairingKeys
import com.mateof.passvault.share.PeerDiscovery
import com.mateof.passvault.share.ShareScope
import com.mateof.passvault.share.Transfer
import com.mateof.passvault.share.TransferClient
import com.mateof.passvault.share.TransferError
import com.mateof.passvault.share.TransferException
import com.mateof.passvault.share.TransferServer
import com.mateof.passvault.sync.AcceptState
import com.mateof.passvault.sync.OperationLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Socket
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Passing tickets to a phone in the same room, with the roles said out loud.
 *
 * One phone **sends** and the other **receives**. The first design had every phone do both at
 * once — advertise, browse, listen, read tags — which produced two users each waiting for the
 * other's phone to appear, and a receiver that demanded an NFC token from a sender who had never
 * read one, so every non-NFC transfer authenticated itself to death. The roles fix both: the
 * receiver is the one that advertises, listens and wears the tag; the sender is the one that
 * browses, types an address or taps.
 *
 * The sequence within a connection is unchanged and fixed by the threat model: greet, **stop and
 * let two people compare six digits** (unless a tap already proved contact), and only then move
 * anything. The pause in the middle is the feature.
 */
@HiltViewModel
class ShareViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: OperationLog,
    private val keys: DeviceKeys,
    private val wallet: com.mateof.passvault.data.WalletRepository,
    private val settings: ServerSettings,
) : ViewModel() {

    private val discovery = PeerDiscovery(context)
    private var server: TransferServer? = null
    private var discovering: Job? = null

    /** Completed by the user tapping "the digits match", or cancelled by tapping the other button. */
    private var awaitingComparison: CompletableDeferred<Boolean>? = null

    /** What this phone is offering, when it is the sender. */
    private var scope: ShareScope = ShareScope.Everything

    /**
     * The keys and token this phone published on its tag, while receiving.
     *
     * Held so the greeting can use the very pair whose public half was tapped: greeting with a
     * fresh one would make the sender's check fail and an honest tap look like an attack.
     */
    private var tapKeys: PairingKeys? = null
    private var tapToken: ByteArray? = null

    /**
     * This phone's own address, for the receiving screen.
     *
     * Whatever interface is carrying the local network. A screen naming the loopback address is
     * one the other phone cannot dial, and loopback is what `InetAddress.getLocalHost` returns
     * on Android.
     */
    private fun localAddress(): String =
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull()
            ?.hostAddress
            ?: "127.0.0.1"

    private val _state = MutableStateFlow(ShareUiState())
    val state: StateFlow<ShareUiState> = _state.asStateFlow()

    /**
     * Becomes the sender: browses for receivers and offers nothing over the air until one is
     * chosen and checked. No socket listens and no tag is worn — a sender is not a doorway.
     */
    fun startSending(chosen: ShareScope) {
        if (discovering != null) return
        scope = chosen
        _state.value = ShareUiState(
            role = ShareRole.Sending,
            stage = ShareStage.Looking,
            scope = chosen,
            ownName = settings.deviceName(),
            tapReady = true,
        )
        discovering = viewModelScope.launch {
            runCatching {
                discovery.discover(null).collect { peers ->
                    _state.value = _state.value.copy(peers = peers)
                }
            }
        }
    }

    /**
     * Becomes the receiver: listens, advertises under the device's name, and wears the NFC tag.
     *
     * The name and the address are on screen because they are how the person opposite finds
     * this phone — by reading the list on theirs, or by typing what this screen says.
     */
    fun startReceiving() {
        if (server != null) return
        val displayName = settings.deviceName()
        val listening = TransferServer { socket -> serve(socket) }
        val port = listening.start()
        server = listening
        discovery.advertise(port, displayName)
        android.util.Log.i(TAG, "receiving on port $port as \"$displayName\"")

        // The tag carries the key this phone will greet with, a token authorising one session,
        // and where to connect. Published only while this screen is up: a tag pointing at a
        // socket that is no longer listening is worse than no tag.
        val keysForTap = LocalPairing.generateKeys()
        val token = com.mateof.passvault.crypto.Primitives.randomBytes(32)
        tapKeys = keysForTap
        tapToken = token
        NfcHandoverSource.offer(
            NfcHandover(
                version = NfcHandover.VERSION,
                ephemeralPublicKey = keysForTap.publicKey,
                token = token,
                host = localAddress(),
                port = port,
                displayName = displayName,
            ),
        )

        _state.value = ShareUiState(
            role = ShareRole.Receiving,
            stage = ShareStage.Looking,
            ownName = displayName,
            ownAddress = "${localAddress()}:$port",
        )
    }

    fun stop() {
        discovering?.cancel()
        discovering = null
        NfcHandoverSource.withdraw()
        tapKeys = null
        tapToken = null
        discovery.stopAdvertising()
        server?.stop()
        server = null
        awaitingComparison?.complete(false)
        awaitingComparison = null
        _state.value = ShareUiState()
    }

    /**
     * Dials the phone that was just touched. Sender side.
     *
     * The handover carries the address, so no list is browsed and no name is guessed — and the
     * key it carries is checked against the one the socket presents, which is what replaces the
     * two humans comparing six digits.
     */
    fun connectTapped(handover: NfcHandover) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                stage = ShareStage.Greeting,
                peerName = handover.displayName,
            )
            withContext(Dispatchers.IO) {
                runCatching {
                    TransferClient.connect(handover.host, handover.port).use { socket ->
                        converse(socket, isSender = true, tapped = handover)
                    }
                }.onFailure { report(it) }
            }
        }
    }

    /**
     * A tap that did not complete.
     *
     * Almost always the phones being separated too early, which is worth saying rather than
     * swallowing: the user is holding two objects together and needs to know whether to try again.
     */
    fun reportTapFailure(cause: TransferException) {
        if (_state.value.stage == ShareStage.Looking || _state.value.stage == ShareStage.Idle) {
            _state.value = _state.value.copy(stage = ShareStage.Failed, failure = cause.code)
        }
    }

    /**
     * Dials an address somebody read off the receiving phone's screen.
     *
     * The fallback for a network where discovery is blocked. Exactly as safe as tapping a name
     * in the list: neither proves anything, which is why both end in the same six digits.
     */
    fun connectManual(typed: String) {
        val cleaned = typed.trim()
        val host = cleaned.substringBeforeLast(':')
        val port = cleaned.substringAfterLast(':').toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            _state.value = _state.value.copy(failure = TransferError.PROTOCOL, stage = ShareStage.Failed)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(stage = ShareStage.Greeting, peerName = cleaned)
            withContext(Dispatchers.IO) {
                runCatching {
                    TransferClient.connect(host, port).use { socket ->
                        converse(socket, isSender = true)
                    }
                }.onFailure { report(it) }
            }
        }
    }

    /** Dials a receiver the user picked from the list. */
    fun connect(peer: DiscoveredPeer) {
        viewModelScope.launch {
            _state.value = _state.value.copy(stage = ShareStage.Greeting, peerName = peer.name)
            withContext(Dispatchers.IO) {
                runCatching {
                    TransferClient.connect(peer).use { socket -> converse(socket, isSender = true) }
                }.onFailure { report(it) }
            }
        }
    }

    /** The receiving half: a sender dialled this phone. Runs on the server thread, not the main one. */
    private fun serve(socket: Socket) {
        runCatching { converse(socket, isSender = false) }.onFailure { report(it) }
    }

    private fun converse(socket: Socket, isSender: Boolean, tapped: NfcHandover? = null) {
        val identity = keys.identity()
        val peer = Transfer.greet(
            input = socket.getInputStream(),
            output = socket.getOutputStream(),
            deviceId = identity.deviceId,
            signingPublicKey = identity.signingPublicKey,
            displayName = settings.deviceName(),
            isInitiator = isSender,
            // The receiver greets with the pair it published on its tag; the tapping sender
            // checks the socket against the key it read. Both are null for a transfer nobody
            // tapped, which falls back to the six digits.
            ephemeralKeys = if (isSender) null else tapKeys,
            expectedPeerKey = tapped?.ephemeralPublicKey,
        )

        if (isSender) {
            if (tapped != null) {
                // The tap already did what the digits do, and better: the key on the socket was
                // checked against the one read off the other phone. Asking two people to compare
                // six digits *as well* would teach them the step is ceremonial.
                _state.value = _state.value.copy(
                    stage = ShareStage.Greeting,
                    peerName = peer.displayName,
                    pairedByTap = true,
                )
                Transfer.confirmAsSender(peer, token = tapped.token)
            } else {
                if (!askHuman(peer)) return
                Transfer.confirmAsSender(peer, token = null)
            }
        } else {
            // The receiver cannot yet know how this sender will authenticate — a tap presents
            // the token, a list-pick presents nothing — so the digits go on screen at once and
            // the sender's confirmation decides whether a human is still needed.
            val comparison = CompletableDeferred<Boolean>()
            awaitingComparison = comparison
            _state.value = _state.value.copy(
                stage = ShareStage.Comparing,
                digits = peer.shortAuthenticationString,
                peerName = peer.displayName,
            )
            val presented = Transfer.awaitConfirmation(peer)
            if (presented != null) {
                awaitingComparison = null
                val expected = tapToken
                if (expected == null || !presented.contentEquals(expected)) {
                    // A token this phone never published is a device that never touched it.
                    throw TransferException(
                        TransferError.DIGITS_MISMATCH,
                        "the sender presented a tap token this phone did not publish",
                    )
                }
                _state.value = _state.value.copy(
                    stage = ShareStage.Greeting,
                    pairedByTap = true,
                    digits = null,
                )
            } else {
                // Everything stops here until a human says the two screens match. A timeout
                // would be worse than useless: it would train people to tap through the one
                // step that works.
                val agreed = kotlinx.coroutines.runBlocking { comparison.await() }
                awaitingComparison = null
                if (!agreed) {
                    if (_state.value.stage != ShareStage.Attacked) {
                        _state.value = _state.value.copy(stage = ShareStage.Cancelled, digits = null)
                    }
                    return
                }
            }
            Transfer.acknowledge(peer)
        }

        _state.value = _state.value.copy(stage = ShareStage.Transferring)
        exchange(peer, isSender)
    }

    /** Shows the digits and waits for the person. Sender side. */
    private fun askHuman(peer: PairedPeer): Boolean {
        val comparison = CompletableDeferred<Boolean>()
        awaitingComparison = comparison
        _state.value = _state.value.copy(
            stage = ShareStage.Comparing,
            digits = peer.shortAuthenticationString,
            peerName = peer.displayName,
        )
        val agreed = kotlinx.coroutines.runBlocking { comparison.await() }
        awaitingComparison = null
        if (!agreed && _state.value.stage != ShareStage.Attacked) {
            _state.value = _state.value.copy(stage = ShareStage.Cancelled, digits = null)
        }
        return agreed
    }

    /** The operations this transfer is offering, when sending. */
    private suspend fun offered(): List<com.mateof.passvault.sync.Operation> = when (val chosen = scope) {
        ShareScope.Everything -> log.allApplied()
        is ShareScope.Event -> log.appliedFor(chosen.eventId)
        is ShareScope.Tickets -> log.appliedFor(chosen.eventId, chosen.ticketIds.toSet())
    }

    /** The original files this transfer is offering, when sending: whichever the sender chose. */
    private suspend fun offeredDocuments(): List<com.mateof.passvault.share.OutgoingDocument> =
        when (val chosen = scope) {
            // The whole-wallet share carries every file to your other phone; a single-event share
            // carries only the files ticked in the picker, and none by default.
            ShareScope.Everything -> wallet.allOutgoingDocuments()
            is ShareScope.Event -> wallet.outgoingDocuments(chosen.documentIds)
            is ShareScope.Tickets -> wallet.outgoingDocuments(chosen.documentIds)
        }

    /**
     * The exchange, one direction only.
     *
     * The sender offers its scope; the receiver takes it and answers with nothing. The first
     * design synchronised both ways, which meant "share one ticket" quietly also pulled the
     * other wallet — technically symmetric, humanly wrong. Whoever wants the other direction
     * turns the phones around and runs it again.
     */
    private fun exchange(peer: PairedPeer, isSender: Boolean) = kotlinx.coroutines.runBlocking {
        if (isSender) {
            val mine = offered()
            Transfer.requestSync(peer, mine)
            // The files, after the log, and only to a phone that speaks the document phase — an
            // older build reads as not speaking it, and the tickets still go without them.
            var sentDocuments = 0
            if (peer.supportsDocuments) {
                val documents = offeredDocuments()
                Transfer.sendDocuments(peer, documents)
                sentDocuments = documents.size
            }
            _state.value = _state.value.copy(
                stage = ShareStage.Done,
                sentCount = mine.size,
                sentDocuments = sentDocuments,
                receivedCount = 0,
                digits = null,
            )
        } else {
            val request = Transfer.readRequest(peer)
            val received = log.accept(request.operations).count { it.state == AcceptState.APPLIED }
            Transfer.respond(peer, emptyList())
            // The files the sender chose to include, kept under this phone's own key. Only when the
            // sender speaks the phase, or there is no manifest coming and the read would block.
            var receivedDocuments = 0
            if (peer.supportsDocuments) {
                val incoming = Transfer.receiveDocuments(peer)
                for (document in incoming) {
                    wallet.keepDocument(
                        id = document.id,
                        eventId = document.eventId,
                        mediaType = document.mediaType,
                        pageCount = document.pageCount,
                        bytes = document.bytes,
                    )
                }
                receivedDocuments = incoming.size
            }
            // The log holding an operation is not the same as the user seeing a ticket.
            // Projecting here is what turns a successful exchange into something visible.
            wallet.projectAll()
            _state.value = _state.value.copy(
                stage = ShareStage.Done,
                sentCount = 0,
                receivedCount = received,
                receivedDocuments = receivedDocuments,
                digits = null,
            )
        }
    }

    fun digitsMatch() {
        awaitingComparison?.complete(true)
    }

    fun digitsDiffer() {
        _state.value = _state.value.copy(stage = ShareStage.Attacked, digits = null)
        awaitingComparison?.complete(false)
    }

    private fun report(cause: Throwable) {
        val code = (cause as? TransferException)?.code ?: TransferError.PROTOCOL
        _state.value = _state.value.copy(
            stage = if (code == TransferError.DIGITS_MISMATCH) ShareStage.Attacked else ShareStage.Failed,
            failure = code,
            digits = null,
        )
    }

    override fun onCleared() {
        stop()
    }

    private companion object {
        const val TAG = "PassVaultShare"
    }
}

/** Which side of the table this phone is. */
enum class ShareRole { Sending, Receiving }

enum class ShareStage {
    Idle,
    Looking,
    Greeting,
    Comparing,
    Transferring,
    Done,
    Cancelled,

    /** A mismatch is a detected attack, not an error, and the interface has to say so. */
    Attacked,
    Failed,
}

data class ShareUiState(
    val role: ShareRole = ShareRole.Sending,
    val stage: ShareStage = ShareStage.Idle,
    /** True while the sender's NFC reader is armed, so the screen can say "hold them together". */
    val tapReady: Boolean = false,
    /** True when a tap authenticated this transfer and no digits were shown. */
    val pairedByTap: Boolean = false,
    /** What is about to leave this phone, so the screen can say so before it does. */
    val scope: ShareScope = ShareScope.Everything,
    val ownName: String? = null,
    /** host:port, readable off the receiving screen so the sender can dial it by hand. */
    val ownAddress: String? = null,
    val peers: List<DiscoveredPeer> = emptyList(),
    val peerName: String? = null,
    val digits: String? = null,
    val sentCount: Int = 0,
    val receivedCount: Int = 0,
    /** Original files handed over or taken in, alongside the tickets. */
    val sentDocuments: Int = 0,
    val receivedDocuments: Int = 0,
    val failure: TransferError? = null,
)
