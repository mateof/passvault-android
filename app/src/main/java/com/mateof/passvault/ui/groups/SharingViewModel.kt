package com.mateof.passvault.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mateof.passvault.server.AccessEntry
import com.mateof.passvault.server.ServerApi
import com.mateof.passvault.server.ServerException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Who an event is shared with, and changing it.
 *
 * Separate from the groups screen even though it speaks to the same endpoints, because it answers
 * a different question: that one is "who do I share with", this one is "who has this". A single
 * view model holding both would reload the group list every time somebody opened one event.
 */
@HiltViewModel
class SharingViewModel @Inject constructor(
    private val api: ServerApi,
    private val settings: com.mateof.passvault.server.ServerSettings,
) : ViewModel() {

    private val _state = MutableStateFlow(SharingUiState())
    val state: StateFlow<SharingUiState> = _state.asStateFlow()

    fun load(eventId: String) {
        if (!api.isSignedIn) {
            _state.value = SharingUiState(loading = false, failure = null)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, failure = null)
            loadEventPassword(eventId)
            val loaded = withContext(Dispatchers.IO) {
                runCatching { api.eventAccess(eventId) to api.groups() }
            }
            _state.value = loaded.fold(
                onSuccess = { (access, groups) ->
                    _state.value.copy(loading = false, access = access, groups = groups)
                },
                onFailure = { _state.value.copy(loading = false, failure = describe(it)) },
            )
        }
    }

    /**
     * The password this event will be published under.
     *
     * Kept until the synchronisation that creates the event on the server, because that is the
     * only moment a password can be set: it decides which key wraps the event, and an event that
     * already exists cannot be rewrapped without every member's agreement.
     *
     * Only for an event that is not there yet. Offering it for one already published would be
     * offering something that cannot happen.
     */
    fun setEventPassword(eventId: String, password: String) {
        settings.setEventPassword(eventId, password)
        _state.value = _state.value.copy(eventPassword = password)
    }

    fun loadEventPassword(eventId: String) {
        _state.value = _state.value.copy(eventPassword = settings.eventPassword(eventId).orEmpty())
    }

    fun setPendingEmail(eventId: String, value: String) {
        _state.value = _state.value.copy(pendingEmail = value, addressKnown = null)
        val trimmed = value.trim()
        if (!trimmed.contains('@') || trimmed.length < 5) return
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { api.lookup(trimmed) } }
            if (_state.value.pendingEmail.trim() == trimmed) {
                _state.value = _state.value.copy(addressKnown = found.getOrNull())
            }
        }
    }

    fun shareWithGroup(eventId: String, groupId: String) = act(eventId) {
        api.shareEvent(eventId, "GROUP", groupId)
    }

    fun shareWithPerson(eventId: String) = act(eventId) {
        api.shareEventWithPerson(eventId, _state.value.pendingEmail.trim())
    }

    /**
     * Takes a free ticket in a self-claim event.
     *
     * Offered to everybody rather than worked out from the event's mode, because a phone that has
     * only just received the event does not reliably know how its tickets are handed out — the
     * server does, and it answers with a sentence saying why not when the answer is no.
     */
    fun claim(eventId: String) {
        viewModelScope.launch {
            val done = withContext(Dispatchers.IO) { runCatching { api.claimFree(eventId) } }
            _state.value = done.fold(
                onSuccess = { _state.value.copy(claimed = true, failure = null) },
                onFailure = { _state.value.copy(claimed = false, failure = describe(it)) },
            )
        }
    }

    fun revoke(eventId: String, entry: AccessEntry) = act(eventId) {
        api.revokeAccess(eventId, entry.subjectKind, entry.subjectId)
    }

    private fun act(eventId: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            val done = withContext(Dispatchers.IO) { runCatching { block() } }
            _state.value = _state.value.copy(pendingEmail = "", addressKnown = null)
            done.fold(
                onSuccess = { load(eventId) },
                onFailure = { _state.value = _state.value.copy(failure = describe(it)) },
            )
        }
    }

    private fun describe(cause: Throwable): String =
        (cause as? ServerException)?.message ?: cause.message ?: "error"
}
