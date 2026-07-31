package com.mateof.passvault.ui.server

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mateof.passvault.crypto.Base64Url
import com.mateof.passvault.data.DeviceKeys
import com.mateof.passvault.data.WalletRepository
import com.mateof.passvault.server.ServerApi
import com.mateof.passvault.server.ServerException
import com.mateof.passvault.server.ServerSettings
import com.mateof.passvault.server.SignInOutcome
import com.mateof.passvault.sync.AcceptState
import com.mateof.passvault.sync.OperationLog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Joining a server, which the app never requires.
 *
 * Everything works with no server: the wallet, ingestion, `.tkpak` files and the transfer between
 * two phones on the same Wi-Fi. A server adds a place for the log to meet other devices that are
 * not in the room, and an authority that can settle a contested claim. Nothing about how the app
 * behaves offline changes when one is configured — which is why this is a screen you can leave
 * without touching.
 *
 * Three gates, in order and for different reasons: an address, because there is no default; a
 * session, which proves who you are; and the vault passphrase, which decrypts. The third is not a
 * repeat of the second — the server holds no copy of it, and every reconnection asks again because
 * the key lives only in that process's memory.
 */
@HiltViewModel
class ServerViewModel @Inject constructor(
    private val settings: ServerSettings,
    private val api: ServerApi,
    private val log: OperationLog,
    private val wallet: WalletRepository,
    private val keys: DeviceKeys,
) : ViewModel() {

    /**
     * What this device is called on the server's device list.
     *
     * The model rather than anything the user typed: it is shown next to a signing key in a list
     * of devices, and "Pixel 8" tells its owner which one to revoke where "Android" does not.
     */
    private val deviceName: String = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { "Android" }
        .take(120)

    private val _state = MutableStateFlow(
        ServerUiState(address = settings.baseUrl(), stage = stageFor(settings)),
    )
    val state: StateFlow<ServerUiState> = _state.asStateFlow()

    private var challenge: String? = null

    fun setAddress(value: String) {
        _state.value = _state.value.copy(address = value)
    }

    /** Saves the address and asks the server whether it is one. */
    fun connect() {
        val typed = _state.value.address
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, failure = null)
            settings.setBaseUrl(typed)
            val reached = withContext(Dispatchers.IO) { runCatching { api.probe() } }
            _state.value = reached.fold(
                onSuccess = {
                    _state.value.copy(
                        busy = false,
                        address = settings.baseUrl(),
                        stage = ServerStage.SignIn,
                    )
                },
                onFailure = { cause ->
                    // The address is kept rather than discarded: a typo is easier to fix than to
                    // retype, and a server that is merely asleep is not a wrong address.
                    _state.value.copy(busy = false, failure = describe(cause))
                },
            )
        }
    }

    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, failure = null)
            val outcome = withContext(Dispatchers.IO) { runCatching { api.signIn(email, password) } }
            _state.value = outcome.fold(
                onSuccess = { result ->
                    when (result) {
                        is SignInOutcome.SignedIn -> _state.value.copy(
                            busy = false,
                            stage = ServerStage.Vault,
                        )
                        is SignInOutcome.SecondFactorNeeded -> {
                            challenge = result.challenge
                            _state.value.copy(
                                busy = false,
                                stage = ServerStage.SecondFactor,
                                secondFactorMethods = result.methods,
                            )
                        }
                    }
                },
                onFailure = { _state.value.copy(busy = false, failure = describe(it)) },
            )
        }
    }

    fun submitSecondFactor(code: String, method: String) {
        val pending = challenge ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, failure = null)
            val outcome = withContext(Dispatchers.IO) {
                runCatching { api.completeSecondFactor(pending, code, method) }
            }
            _state.value = outcome.fold(
                onSuccess = { result ->
                    if (result is SignInOutcome.SignedIn) {
                        challenge = null
                        _state.value.copy(busy = false, stage = ServerStage.Vault)
                    } else {
                        _state.value.copy(busy = false, failure = "auth.error.invalidOtp")
                    }
                },
                onFailure = { _state.value.copy(busy = false, failure = describe(it)) },
            )
        }
    }

    fun unlockVault(passphrase: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, failure = null)
            val outcome = withContext(Dispatchers.IO) { runCatching { api.unlockVault(passphrase) } }
            _state.value = outcome.fold(
                onSuccess = {
                    loadGroups()
                    _state.value.copy(busy = false, stage = ServerStage.Ready)
                },
                onFailure = { _state.value.copy(busy = false, failure = describe(it)) },
            )
        }
    }

    /**
     * Exchanges the log with the server, one event at a time.
     *
     * The same operations the two-phone transfer sends, through a different transport — one
     * mechanism, three transports, which is what the specification promises and what makes this a
     * few lines rather than a second implementation.
     *
     * Events made on this phone are uploaded too. The server creates one from the `event.create`
     * the log already carries, keeping the identifier this device signs against — so a wallet built
     * entirely offline, which is how the app is meant to be used, ends up there rather than being
     * counted as "local only" and skipped while the screen reports a successful synchronisation.
     */
    fun sync() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, failure = null, lastSync = null)
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    var sent = 0
                    var applied = 0
                    var published = 0

                    // Before anything is pushed. The server verifies every operation against a
                    // registered signing key and holds back what it cannot verify, so skipping
                    // this uploads a wallet that lands entirely in quarantine — and reports
                    // success while doing it.
                    announceThisDevice()

                    val remote = api.events().toSet()
                    val local = log.eventIds().toSet()

                    // The union, not the local list. Iterating only what this phone already holds
                    // makes an event that exists solely on the server unreachable: it is never
                    // asked for, so it never arrives, and the screen says "received 0" as though
                    // that were the truth. Joining a server is mostly about the events already
                    // there.
                    for (eventId in remote + local) {
                        val mine = if (eventId in local) {
                            log.since(eventId, cursor = "", limit = 500).operations
                        } else {
                            emptyList()
                        }
                        val result = api.sync(eventId, mine, cursor = null, eventPassword = null)
                        sent += mine.size
                        if (result.created) published += 1
                        applied += log.accept(result.received)
                            .count { it.state == AcceptState.APPLIED }
                    }
                    wallet.projectAll()
                    SyncSummary(sent = sent, received = applied, published = published)
                }
            }
            _state.value = outcome.fold(
                onSuccess = { _state.value.copy(busy = false, lastSync = it) },
                onFailure = { _state.value.copy(busy = false, failure = describe(it)) },
            )
        }
    }

    /**
     * Registers this device's signing key with the server.
     *
     * Idempotent and cheap, so it runs on every synchronisation rather than once at sign-in: the
     * alternative is remembering whether it was done, per server, and being wrong about it after a
     * reinstall or a restore — where the cost of being wrong is a wallet that uploads into
     * quarantine.
     *
     * The identifier is the one this device already signs with, never a new one. Rotating it would
     * orphan every operation it has ever produced.
     */
    private fun announceThisDevice() {
        val identity = keys.identity()
        api.registerDevice(
            deviceId = identity.deviceId,
            name = deviceName,
            signingPublicKey = Base64Url.encode(identity.signingPublicKey),
            agreementPublicKey = Base64Url.encode(identity.agreementPublicKey),
        )
    }

    /** Loads the groups this account belongs to. Only meaningful once the vault is open. */
    fun loadGroups() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { api.groups() } }
            loaded.onSuccess { _state.value = _state.value.copy(groups = it) }
        }
    }

    fun createGroup(name: String) {
        viewModelScope.launch {
            val done = withContext(Dispatchers.IO) { runCatching { api.createGroup(name) } }
            done.fold(
                onSuccess = { loadGroups() },
                onFailure = { _state.value = _state.value.copy(failure = describe(it)) },
            )
        }
    }

    fun addMember(groupId: String, email: String) {
        viewModelScope.launch {
            val done = withContext(Dispatchers.IO) { runCatching { api.addMember(groupId, email) } }
            done.fold(
                onSuccess = { loadGroups() },
                onFailure = { _state.value = _state.value.copy(failure = describe(it)) },
            )
        }
    }

    /** Gives a group access to an event, which is what a group is for. */
    fun shareEventWithGroup(eventId: String, groupId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, failure = null)
            val done = withContext(Dispatchers.IO) {
                runCatching { api.shareEvent(eventId, "GROUP", groupId) }
            }
            _state.value = done.fold(
                onSuccess = { _state.value.copy(busy = false, sharedWithGroup = true) },
                onFailure = { _state.value.copy(busy = false, failure = describe(it)) },
            )
        }
    }

    /**
     * Signs in with a passkey.
     *
     * The platform's sheet does the whole ceremony, so there is no password to type and nothing
     * for this code to hold. A dismissal is not an error: choosing not to use a passkey is a
     * decision, and putting a red message on screen for it would be wrong.
     */
    fun signInWithPasskey(passkeys: com.mateof.passvault.server.Passkeys) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, failure = null)
            val outcome = runCatching {
                val options = withContext(Dispatchers.IO) { api.passkeyLoginOptions() }
                val response = passkeys.authenticate(options) ?: return@runCatching null
                withContext(Dispatchers.IO) { api.passkeyLogin(response) }
            }
            _state.value = outcome.fold(
                onSuccess = { result ->
                    when (result) {
                        null -> _state.value.copy(busy = false)
                        is SignInOutcome.SignedIn -> _state.value.copy(
                            busy = false,
                            stage = ServerStage.Vault,
                        )
                        is SignInOutcome.SecondFactorNeeded -> {
                            challenge = result.challenge
                            _state.value.copy(
                                busy = false,
                                stage = ServerStage.SecondFactor,
                                secondFactorMethods = result.methods,
                            )
                        }
                    }
                },
                onFailure = { _state.value.copy(busy = false, failure = describe(it)) },
            )
        }
    }

    /** Adds a passkey to the signed-in account, so the next sign-in needs no password. */
    fun addPasskey(passkeys: com.mateof.passvault.server.Passkeys, name: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, failure = null)
            val outcome = runCatching {
                val options = withContext(Dispatchers.IO) { api.passkeyRegisterOptions() }
                val response = passkeys.register(options) ?: return@runCatching false
                withContext(Dispatchers.IO) { api.passkeyRegister(response, name) }
                true
            }
            _state.value = outcome.fold(
                onSuccess = { added -> _state.value.copy(busy = false, passkeyAdded = added) },
                onFailure = { _state.value.copy(busy = false, failure = describe(it)) },
            )
        }
    }

    fun forget() {
        api.signOutLocally()
        settings.clear()
        challenge = null
        _state.value = ServerUiState(address = "", stage = ServerStage.Address)
    }

    /**
     * What went wrong, in the server's words where it said any.
     *
     * A `ServerException` already carries a translated message: the request asked for one. Anything
     * else is the network, and saying so is more useful than a status code.
     */
    private fun describe(cause: Throwable): String = when (cause) {
        is ServerException -> cause.message ?: "HTTP ${cause.status}"
        else -> cause.message ?: "network"
    }

    private companion object {
        fun stageFor(settings: ServerSettings) =
            if (settings.isConfigured()) ServerStage.SignIn else ServerStage.Address
    }
}

enum class ServerStage { Address, SignIn, SecondFactor, Vault, Ready }

data class SyncSummary(
    val sent: Int,
    val received: Int,
    /** Events this synchronisation created on the server, having existed only on this phone. */
    val published: Int,
)

data class ServerUiState(
    val address: String = "",
    val stage: ServerStage = ServerStage.Address,
    val busy: Boolean = false,
    val failure: String? = null,
    val secondFactorMethods: List<String> = emptyList(),
    val lastSync: SyncSummary? = null,
    val groups: List<com.mateof.passvault.server.Group> = emptyList(),
    val sharedWithGroup: Boolean = false,
    val passkeyAdded: Boolean = false,
)
