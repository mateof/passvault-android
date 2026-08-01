package com.mateof.passvault.ui.server

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mateof.passvault.crypto.Base64Url
import com.mateof.passvault.data.DeviceKeys
import com.mateof.passvault.server.ServerApi
import com.mateof.passvault.server.ServerException
import com.mateof.passvault.server.ServerSettings
import com.mateof.passvault.server.SignInOutcome
import com.mateof.passvault.sync.SyncOutcome
import com.mateof.passvault.sync.SyncSummary
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
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val settings: ServerSettings,
    private val api: ServerApi,
    private val engine: com.mateof.passvault.sync.SyncEngine,
    private val keys: DeviceKeys,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ServerUiState(address = settings.baseUrl(), stage = stageFor(settings, api.isSignedIn)),
    )
    val state: StateFlow<ServerUiState> = _state.asStateFlow()

    private var challenge: String? = null

    fun setAddress(value: String) {
        _state.value = _state.value.copy(address = value)
    }

    /**
     * Synchronises when the screen opens, if there is anything to synchronise with.
     *
     * Together with the periodic worker this is what makes the button optional: by the time
     * somebody looks at the wallet, what a friend shared this morning is already in it. The
     * button stays because a schedule is not something to trust while standing at a turnstile.
     */
    fun syncIfPossible() {
        if (!engine.isPossible) {
            // Nothing to schedule either. A wallet that never joined a server should not have a
            // periodic job waking the device up to be told so.
            com.mateof.passvault.sync.SyncScheduler.cancel(context)
            return
        }
        // Asked for here rather than at startup: this is the first point at which the app knows
        // there is a server and a session. The request is unique by name, so repeating it
        // replaces the previous schedule instead of stacking up.
        com.mateof.passvault.sync.SyncScheduler.schedule(context)
        sync()
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
                    // Kept sealed under the KeyStore, like the token: the server forgets its
                    // unwrapped keys on every restart by design, and without this every update
                    // of the server asked this phone to retype the passphrase.
                    settings.setVaultPassphrase(passphrase)
                    loadGroups()
                    // Who this account is — including whether it runs the installation. Without
                    // this the admin door only appeared after leaving the screen and returning,
                    // because arrival had asked while nobody was signed in yet.
                    loadSessions()
                    _state.value.copy(busy = false, stage = ServerStage.Ready)
                },
                onFailure = { _state.value.copy(busy = false, failure = describe(it)) },
            )
        }
    }

    fun adminUrl(): String = api.adminUrl()

    fun deviceName(): String = settings.deviceName()

    fun setDeviceName(name: String) = settings.setDeviceName(name)

    fun uiLocale(): String? = settings.uiLocale()

    fun setUiLocale(tag: String?) = settings.setUiLocale(tag)

    /**
     * Deletes the account on the server, then forgets everything local about it.
     *
     * The wallet on this phone survives — it existed before the server and owes it nothing —
     * but the connection, the sealed secrets and the schedule all go, because the account they
     * belonged to no longer exists.
     */
    fun deleteAccount(secret: String, onDeleted: () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, failure = null)
            val done = withContext(Dispatchers.IO) { runCatching { api.deleteMyAccount(secret) } }
            done.fold(
                onSuccess = {
                    forget()
                    onDeleted()
                },
                onFailure = {
                    _state.value = _state.value.copy(busy = false, failure = describe(it))
                },
            )
        }
    }

    /**
     * Signs out of the server and stays pointed at it.
     *
     * Different from `forget`, which erases the address too. Signing out is "not me, not now":
     * the session ends here and on the server, the sealed secrets go, and the address stays so
     * the next sign-in is an email and a password rather than reconfiguration.
     */
    fun signOut() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { api.signOut() } }
            settings.setVaultPassphrase(null)
            com.mateof.passvault.sync.SyncScheduler.cancel(context)
            challenge = null
            _state.value = ServerUiState(address = settings.baseUrl(), stage = ServerStage.SignIn)
        }
    }

    /**
     * Exchanges the log with the server, one event at a time.
     *
     * The work itself lives in `SyncEngine`, which is what lets a background run and this button
     * be the same thing. Pressing it while the scheduled run is in flight waits rather than being
     * refused: "already running" is not an answer somebody who pressed a button wants.
     */
    fun sync() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, failure = null, lastSync = null)
            _state.value = when (val outcome = engine.sync()) {
                is SyncOutcome.Done -> _state.value.copy(busy = false, lastSync = outcome.summary)
                SyncOutcome.NotConfigured -> _state.value.copy(busy = false)
                SyncOutcome.SignedOut ->
                    // The session ended somewhere else — revoked from another device, or simply
                    // expired. Back to the sign-in step rather than a failure message about a
                    // synchronisation, which is not the thing that needs attention.
                    _state.value.copy(busy = false, stage = ServerStage.SignIn)
                SyncOutcome.VaultLocked ->
                    // The session is fine; the server merely lacks the passphrase after a
                    // restart and this phone does not hold it either. Ask for exactly that,
                    // not for a whole sign-in.
                    _state.value.copy(busy = false, stage = ServerStage.Vault)
                is SyncOutcome.Failed -> _state.value.copy(busy = false, failure = outcome.message)
            }
        }
    }

    /**
     * Begins enrolling a second factor.
     *
     * Kept on this screen rather than behind a settings menu: it is the screen where somebody has
     * just proved they can sign in, which is the one moment they are thinking about how they sign
     * in.
     */
    fun enrolTotp() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, failure = null)
            val result = withContext(Dispatchers.IO) { runCatching { api.totpEnrol() } }
            _state.value = result.fold(
                onSuccess = { _state.value.copy(busy = false, totp = it) },
                onFailure = { _state.value.copy(busy = false, failure = describe(it)) },
            )
        }
    }

    /** Arms it. Nothing is in force until a code the authenticator produced comes back correct. */
    fun confirmTotp(code: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, failure = null)
            val done = withContext(Dispatchers.IO) { runCatching { api.totpConfirm(code) } }
            _state.value = done.fold(
                onSuccess = { _state.value.copy(busy = false, totp = null, totpConfirmed = true) },
                onFailure = { _state.value.copy(busy = false, failure = describe(it)) },
            )
        }
    }

    /**
     * The sessions open on this account, and ending one.
     *
     * A phone left in a taxi is what this is for, and until now the only answer was to wait for
     * the session to expire. Loaded when the ready screen appears rather than watched: sessions
     * change when somebody signs in, which is not often enough to hold a request open for.
     */
    fun loadSessions() {
        if (!api.isSignedIn) return
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { api.sessions() to api.me() }
            }
            loaded.onSuccess { (sessions, account) ->
                _state.value = _state.value.copy(
                    sessions = sessions,
                    currentHandle = account.handle,
                    isAdmin = account.isAdmin,
                    // Prefilled so "change my name" starts from the name, not from an empty
                    // field and a doubt about whether one was ever chosen.
                    handle = _state.value.handle.ifBlank { account.handle.orEmpty() },
                )
            }
        }
    }

    fun revokeSession(sessionId: String) {
        viewModelScope.launch {
            val done = withContext(Dispatchers.IO) { runCatching { api.revokeSession(sessionId) } }
            done.fold(
                onSuccess = {
                    // Ending the one you are using is allowed and is simply signing out, so the
                    // screen has to notice rather than showing a list it can no longer refresh.
                    if (_state.value.sessions.firstOrNull { it.id == sessionId }?.current == true) {
                        forget()
                    } else {
                        loadSessions()
                    }
                },
                onFailure = { _state.value = _state.value.copy(failure = describe(it)) },
            )
        }
    }

    /**
     * Claims a public name to be found by.
     *
     * Checked while it is typed, so a name somebody else has is refused beside the field rather
     * than after pressing save. The check is a courtesy; the unique index on the server is what
     * actually decides, and a lost race comes back as the same sentence.
     */
    fun checkHandle(rawValue: String) {
        // Sanitised as they type, so a Spanish or Galician name — a capital, a space, an accent,
        // all three forbidden by the server — becomes a handle it accepts instead of a late
        // error. "Mateo Fernández" turns into mateo-fernandez in front of them.
        val value = slugifyHandle(rawValue)
        _state.value = _state.value.copy(handle = value, handleTaken = null, handleSaved = false)
        val trimmed = value.trim()
        if (trimmed.length < 3) return
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { api.handleTaken(trimmed) } }
            if (_state.value.handle.trim() == trimmed) {
                _state.value = _state.value.copy(handleTaken = found.getOrNull())
            }
        }
    }

    fun saveHandle() {
        val wanted = _state.value.handle.trim()
        if (wanted.length < 3) return
        viewModelScope.launch {
            val done = withContext(Dispatchers.IO) { runCatching { api.setHandle(wanted) } }
            _state.value = done.fold(
                onSuccess = { _state.value.copy(handle = it, handleSaved = true, failure = null) },
                onFailure = { _state.value.copy(failure = describe(it)) },
            )
        }
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
        // The one thing that ends a kept session, and with it the background synchronisation:
        // "until I decide to leave it" is what the stored token promises.
        com.mateof.passvault.sync.SyncScheduler.cancel(context)
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
        /**
         * Where the server screen opens.
         *
         * A kept session goes straight to the ready state rather than to a sign-in form. The
         * token survives a restart now, so asking for a password on every launch would be asking
         * for something the app already has — and the vault passphrase is still asked for
         * separately, because that one genuinely is not stored anywhere.
         */
        fun stageFor(settings: ServerSettings, signedIn: Boolean = false) = when {
            !settings.isConfigured() -> ServerStage.Address
            signedIn -> ServerStage.Ready
            else -> ServerStage.SignIn
        }
    }
}


/**
 * Turns whatever someone types into a handle the server will accept.
 *
 * Its rule is narrow on purpose — lower case, digits, dot, dash, underscore — and a Spanish or
 * Galician name walks straight into it: "Mateo Fernández" has a capital, a space and an accent,
 * all three forbidden. Decomposing to base letters drops the accents; everything outside the set
 * collapses to a single dash. The result is what the field shows and what gets saved.
 */
private fun slugifyHandle(value: String): String =
    java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .replace(Regex("^[._-]+"), "")
        .take(32)

enum class ServerStage { Address, SignIn, SecondFactor, Vault, Ready }

data class ServerUiState(
    /** Where this account is open. Empty until the ready screen asks. */
    val sessions: List<com.mateof.passvault.server.OpenSession> = emptyList(),
    /** Whether this account runs the installation, and so should be offered the admin door. */
    val isAdmin: Boolean = false,
    /** The name this account already has, from /me. Null until one is chosen. */
    val currentHandle: String? = null,
    /** The public name being typed, and whether anybody already has it. */
    val handle: String = "",
    val handleTaken: Boolean? = null,
    val handleSaved: Boolean = false,
    val address: String = "",
    val stage: ServerStage = ServerStage.Address,
    val busy: Boolean = false,
    val failure: String? = null,
    val secondFactorMethods: List<String> = emptyList(),
    val lastSync: SyncSummary? = null,
    val groups: List<com.mateof.passvault.server.Group> = emptyList(),
    val sharedWithGroup: Boolean = false,
    val passkeyAdded: Boolean = false,
    /** Present while an enrolment is waiting for its first code. */
    val totp: com.mateof.passvault.server.TotpEnrolment? = null,
    val totpConfirmed: Boolean = false,
)
