package com.mateof.passvault.ui.notices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mateof.passvault.server.Invitation
import com.mateof.passvault.server.Notice
import com.mateof.passvault.server.ServerApi
import com.mateof.passvault.server.ServerException
import com.mateof.passvault.sync.SyncEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The answers a wallet owes, and the things it was told.
 *
 * Accepting is followed by a synchronisation rather than by a refresh of this screen alone: what
 * somebody just agreed to hold is an event with tickets in it, and the point of saying yes is
 * having them. Doing it here means the wallet is right by the time they navigate back to it.
 */
@HiltViewModel
class NoticesViewModel @Inject constructor(
    private val api: ServerApi,
    private val engine: SyncEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(NoticesUiState())
    val state: StateFlow<NoticesUiState> = _state.asStateFlow()

    fun load() {
        if (!api.isSignedIn) {
            _state.value = _state.value.copy(signedIn = false, loading = false)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, failure = null, signedIn = true)
            val loaded = withContext(Dispatchers.IO) {
                runCatching { api.notices() to api.invitations() }
            }
            _state.value = loaded.fold(
                onSuccess = { (notices, invitations) ->
                    _state.value.copy(
                        loading = false,
                        notices = notices.first,
                        unread = notices.second,
                        invitations = invitations.filter { it.state == "PENDING" },
                    )
                },
                onFailure = { _state.value.copy(loading = false, failure = describe(it)) },
            )
        }
    }

    fun accept(invitation: Invitation, password: String?) {
        viewModelScope.launch {
            val done = withContext(Dispatchers.IO) {
                runCatching { api.acceptInvitation(invitation.id, password) }
            }
            done.fold(
                onSuccess = {
                    // The tickets are the reason for saying yes, so they are fetched now rather
                    // than whenever the wallet next happens to synchronise.
                    withContext(Dispatchers.IO) { engine.sync() }
                    load()
                },
                onFailure = { _state.value = _state.value.copy(failure = describe(it)) },
            )
        }
    }

    fun decline(invitation: Invitation) {
        viewModelScope.launch {
            val done = withContext(Dispatchers.IO) {
                runCatching { api.declineInvitation(invitation.id) }
            }
            done.fold(
                onSuccess = { load() },
                onFailure = { _state.value = _state.value.copy(failure = describe(it)) },
            )
        }
    }

    fun markRead() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { api.markNoticesRead() } }
            load()
        }
    }

    private fun describe(cause: Throwable): String =
        (cause as? ServerException)?.message ?: cause.message ?: "error"
}

data class NoticesUiState(
    val notices: List<Notice> = emptyList(),
    val invitations: List<Invitation> = emptyList(),
    val unread: Int = 0,
    val loading: Boolean = true,
    val signedIn: Boolean = true,
    val failure: String? = null,
) {
    /**
     * The event's name, which lives in the notice rather than in the invitation.
     *
     * An invitation names an event whose name is encrypted under a key the recipient does not
     * have until they accept — which is the whole point of accepting. The notice carries the name
     * because it was written by somebody who did have the key.
     */
    fun nameFor(invitation: Invitation): String =
        notices.firstOrNull { it.kind == "event.invited" && it.eventId == invitation.eventId }
            ?.eventName
            .orEmpty()
}
