package com.mateof.passvault.ui.share

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mateof.passvault.data.DeviceKeys
import com.mateof.passvault.share.DiscoveredPeer
import com.mateof.passvault.share.PairedPeer
import com.mateof.passvault.share.PeerDiscovery
import com.mateof.passvault.share.LocalPairing
import com.mateof.passvault.share.NfcHandover
import com.mateof.passvault.share.NfcHandoverSource
import com.mateof.passvault.share.PairingKeys
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
 * Passing tickets to a phone in the same room.
 *
 * The sequence is fixed by the threat model and not by convenience: discover, greet, **stop and let
 * two people compare six digits**, and only then move anything. The pause in the middle is the
 * feature. Everything either side sends before it is an ephemeral public key, which is worth nothing
 * to whoever is listening.
 *
 * Both roles live here because a phone is both: it advertises so it can be found, and it browses so
 * it can find. Whoever taps first becomes the initiator, and that is the only difference between
 * them — it fixes the order the two keys are hashed in, so both derive the same digits.
 */
@HiltViewModel
class ShareViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: OperationLog,
    private val keys: DeviceKeys,
    private val wallet: com.mateof.passvault.data.WalletRepository,
) : ViewModel() {

    private val discovery = PeerDiscovery(context)
    private var server: TransferServer? = null
    private var discovering: Job? = null

    /** Completed by the user tapping "the digits match", or cancelled by tapping the other button. */
    private var awaitingComparison: CompletableDeferred<Boolean>? = null

    /**
     * What this phone is offering.
     *
     * Everything, until a screen says otherwise. That is what this did before there was a choice,
     * and it is still right for the case it was built for: two phones belonging to the same person.
     */
    private var scope: ShareScope = ShareScope.Everything

    fun offer(chosen: ShareScope) {
        scope = chosen
        _state.value = _state.value.copy(scope = chosen)
    }

    /**
     * The keys and token this phone published on its tag, while it is advertising.
     *
     * Held so the greeting can use the very pair whose public half was tapped: greeting with a
     * fresh one would make the receiver's check fail and an honest tap look like an attack.
     */
    private var tapKeys: PairingKeys? = null
    private var tapToken: ByteArray? = null

    /**
     * This phone's own address, for the tag.
     *
     * Whatever interface is carrying the local network. A tag naming the loopback address is a tag
     * the other phone cannot dial, and it is what `InetAddress.getLocalHost` returns on Android.
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
     * Starts being findable and looking for others.
     *
     * The display name is the phone's own, which is what the other person will look for in a list.
     * It is a label, not a credential: anybody can advertise any name, which is precisely why the
     * digits exist.
     */
    fun start(displayName: String) {
        if (server != null) return
        val listening = TransferServer { socket -> serve(socket) }
        val port = listening.start()
        server = listening
        discovery.advertise(port, displayName)
        // The port the system handed out, logged so a transfer can be driven from a workstation
        // with `adb forward`. mDNS does not cross the emulator's NAT, and a feature that can only be
        // exercised with two physical phones in the room is one that does not get exercised.
        android.util.Log.i(TAG, "listening on port $port as \"$displayName\"")

        // The tag carries the key this phone will greet with, a token authorising one session,
        // and where to connect. Published only while this screen is up: a tag pointing at a socket
        // that is no longer listening is worse than no tag.
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

        _state.value = _state.value.copy(
            stage = ShareStage.Looking,
            ownName = displayName,
            tapReady = true,
        )
        discovering = viewModelScope.launch {
            discovery.discover(displayName).collect { peers ->
                _state.value = _state.value.copy(peers = peers)
            }
        }
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
     * Dials the phone that was just touched.
     *
     * The handover carries the address, so no list is browsed and no name is guessed — and the key
     * it carries is checked against the one the socket presents, which is what replaces the two
     * humans comparing six digits.
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
                        converse(socket, isInitiator = true, tapped = handover)
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
    fun reportTapFailure(cause: com.mateof.passvault.share.TransferException) {
        if (_state.value.stage == ShareStage.Looking || _state.value.stage == ShareStage.Idle) {
            _state.value = _state.value.copy(stage = ShareStage.Failed, failure = cause.code)
        }
    }

    /** Dials a peer the user picked. This side becomes the initiator. */
    fun connect(peer: DiscoveredPeer) {
        viewModelScope.launch {
            _state.value = _state.value.copy(stage = ShareStage.Greeting, peerName = peer.name)
            withContext(Dispatchers.IO) {
                runCatching {
                    TransferClient.connect(peer).use { socket -> converse(socket, isInitiator = true) }
                }.onFailure { report(it) }
            }
        }
    }

    /** The other half: a peer dialled this phone. Runs on the server thread, not the main one. */
    private fun serve(socket: Socket) {
        runCatching { converse(socket, isInitiator = false) }.onFailure { report(it) }
    }

    private fun converse(socket: Socket, isInitiator: Boolean, tapped: NfcHandover? = null) {
        val identity = keys.identity()
        val peer = Transfer.greet(
            input = socket.getInputStream(),
            output = socket.getOutputStream(),
            deviceId = identity.deviceId,
            signingPublicKey = identity.signingPublicKey,
            displayName = _state.value.ownName ?: "PassVault",
            isInitiator = isInitiator,
            // The advertising side greets with the pair it published; the tapping side checks the
            // socket against the key it read. Both are null for a transfer nobody tapped, which
            // falls back to the six digits exactly as before.
            ephemeralKeys = if (tapped == null) tapKeys else null,
            expectedPeerKey = tapped?.ephemeralPublicKey,
        )

        // A tap already did what the digits do, and better: the key on the socket was checked
        // against the one read off the other phone, and the token proves physical contact. Asking
        // two people to compare six digits *as well* would teach them the step is ceremonial,
        // which is the habit that makes it useless when it is the only check there is.
        val bySight = tapped == null && tapToken == null
        if (bySight) {
            // Everything stops here until a human says the two screens match. A timeout would be
            // worse than useless: it would train people to tap through the one step that works.
            val comparison = CompletableDeferred<Boolean>()
            awaitingComparison = comparison
            _state.value = _state.value.copy(
                stage = ShareStage.Comparing,
                digits = peer.shortAuthenticationString,
                peerName = peer.displayName,
            )

            val agreed = kotlinx.coroutines.runBlocking { comparison.await() }
            awaitingComparison = null
            if (!agreed) {
                _state.value = _state.value.copy(stage = ShareStage.Cancelled, digits = null)
                return
            }
        } else {
            _state.value = _state.value.copy(
                stage = ShareStage.Greeting,
                peerName = peer.displayName,
                pairedByTap = true,
            )
        }

        Transfer.confirm(
            peer = peer,
            isInitiator = isInitiator,
            // The tapping side returns what it read; the advertising side demands it. Both null
            // for a transfer nobody tapped, which leaves the digits doing the work as before.
            token = tapped?.token,
            expected = if (tapped == null) tapToken else null,
        )
        _state.value = _state.value.copy(stage = ShareStage.Transferring)
        exchange(peer, isInitiator)
    }

    /**
     * The operations this transfer is offering.
     *
     * Receiving is never narrowed — a phone takes whatever it is given, because the other side
     * decided what to give and refusing part of it would only lose tickets. The scope is about
     * what leaves, which is the direction with a privacy question attached.
     */
    private suspend fun offered(): List<com.mateof.passvault.sync.Operation> = when (val chosen = scope) {
        ShareScope.Everything -> log.allApplied()
        is ShareScope.Event -> log.appliedFor(chosen.eventId)
        is ShareScope.Tickets -> log.appliedFor(chosen.eventId, chosen.ticketIds.toSet())
    }

    /**
     * The exchange itself: the same request and response the server speaks.
     *
     * The initiator asks and the responder answers, so the two do not both start talking. Each side
     * offers what it holds and takes what it lacks, so one round leaves both with the same log.
     */
    private fun exchange(peer: PairedPeer, isInitiator: Boolean) = kotlinx.coroutines.runBlocking {
        val mine = offered()
        val received: Int

        // Exactly one message each way, and the roles decide who speaks first so the two do not both
        // start talking into a socket nobody is reading.
        if (isInitiator) {
            val answer = Transfer.requestSync(peer, mine)
            received = log.accept(answer.operations).count { it.state == AcceptState.APPLIED }
        } else {
            val request = Transfer.readRequest(peer)
            received = log.accept(request.operations).count { it.state == AcceptState.APPLIED }
            Transfer.respond(peer, mine)
        }

        // The log holding an operation is not the same as the user seeing a ticket. Projecting here
        // is what turns a successful exchange into something visible in the wallet.
        wallet.projectAll()

        _state.value = _state.value.copy(
            stage = ShareStage.Done,
            sentCount = mine.size,
            receivedCount = received,
            digits = null,
        )
    }

    fun digitsMatch() {
        awaitingComparison?.complete(true)
    }

    fun digitsDiffer() {
        awaitingComparison?.complete(false)
        _state.value = _state.value.copy(stage = ShareStage.Attacked, digits = null)
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
    val stage: ShareStage = ShareStage.Idle,
    /** True once this phone is publishing a tag, so the screen can say "hold them together". */
    val tapReady: Boolean = false,
    /** True when a tap authenticated this transfer and no digits were shown. */
    val pairedByTap: Boolean = false,
    /** What is about to leave this phone, so the screen can say so before it does. */
    val scope: ShareScope = ShareScope.Everything,
    val ownName: String? = null,
    val peers: List<DiscoveredPeer> = emptyList(),
    val peerName: String? = null,
    val digits: String? = null,
    val sentCount: Int = 0,
    val receivedCount: Int = 0,
    val failure: TransferError? = null,
)
