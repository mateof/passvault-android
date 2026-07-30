package com.mateof.passvault.ui.share

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mateof.passvault.data.DeviceKeys
import com.mateof.passvault.share.DiscoveredPeer
import com.mateof.passvault.share.PairedPeer
import com.mateof.passvault.share.PeerDiscovery
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
) : ViewModel() {

    private val discovery = PeerDiscovery(context)
    private var server: TransferServer? = null
    private var discovering: Job? = null

    /** Completed by the user tapping "the digits match", or cancelled by tapping the other button. */
    private var awaitingComparison: CompletableDeferred<Boolean>? = null

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

        _state.value = _state.value.copy(stage = ShareStage.Looking, ownName = displayName)
        discovering = viewModelScope.launch {
            discovery.discover(displayName).collect { peers ->
                _state.value = _state.value.copy(peers = peers)
            }
        }
    }

    fun stop() {
        discovering?.cancel()
        discovering = null
        discovery.stopAdvertising()
        server?.stop()
        server = null
        awaitingComparison?.complete(false)
        awaitingComparison = null
        _state.value = ShareUiState()
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

    private fun converse(socket: Socket, isInitiator: Boolean) {
        val identity = keys.identity()
        val peer = Transfer.greet(
            input = socket.getInputStream(),
            output = socket.getOutputStream(),
            deviceId = identity.deviceId,
            signingPublicKey = identity.signingPublicKey,
            displayName = _state.value.ownName ?: "PassVault",
            isInitiator = isInitiator,
        )

        // Everything stops here until a human says the two screens match. A timeout would be worse
        // than useless: it would train people to tap through the one step that does the work.
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

        Transfer.confirm(peer)
        _state.value = _state.value.copy(stage = ShareStage.Transferring)
        exchange(peer, isInitiator)
    }

    /**
     * The exchange itself: the same request and response the server speaks.
     *
     * The initiator asks and the responder answers, so the two do not both start talking. Each side
     * offers what it holds and takes what it lacks, so one round leaves both with the same log.
     */
    private fun exchange(peer: PairedPeer, isInitiator: Boolean) = kotlinx.coroutines.runBlocking {
        val mine = log.allApplied()
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
    val ownName: String? = null,
    val peers: List<DiscoveredPeer> = emptyList(),
    val peerName: String? = null,
    val digits: String? = null,
    val sentCount: Int = 0,
    val receivedCount: Int = 0,
    val failure: TransferError? = null,
)
