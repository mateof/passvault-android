package com.mateof.passvault.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mateof.passvault.server.AccessEntry
import com.mateof.passvault.server.Group
import com.mateof.passvault.server.GroupMember
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
 * The people you share with more than once.
 *
 * A group turns "type four addresses again for this concert" into "the family". That is the whole
 * of it, and it is why this holds a list of names with people under each one rather than any
 * general notion of permissions.
 *
 * Everything here needs a server. A wallet works entirely offline — that is the point of the app —
 * but a group is a statement about other people's accounts, and there is nowhere to keep one
 * without a server to hold the accounts.
 */
@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val api: ServerApi,
) : ViewModel() {

    private val _state = MutableStateFlow(GroupsUiState())
    val state: StateFlow<GroupsUiState> = _state.asStateFlow()

    fun load() {
        if (!api.isSignedIn) {
            _state.value = _state.value.copy(signedIn = false, loading = false)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, failure = null, signedIn = true)
            val loaded = withContext(Dispatchers.IO) { runCatching { api.groups() } }
            _state.value = loaded.fold(
                onSuccess = { _state.value.copy(loading = false, groups = it) },
                onFailure = { _state.value.copy(loading = false, failure = describe(it)) },
            )
        }
    }

    /** Opens one, loading who is in it. Closing it again is what a second tap on the same row means. */
    fun open(groupId: String?) {
        if (groupId == null || groupId == _state.value.openGroupId) {
            _state.value = _state.value.copy(openGroupId = null, members = emptyList())
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(openGroupId = groupId, members = emptyList())
            val loaded = withContext(Dispatchers.IO) { runCatching { api.members(groupId) } }
            loaded.onSuccess { members ->
                // Guarded, because a slow response for a group the user has already closed would
                // otherwise repopulate a panel that is no longer on screen.
                if (_state.value.openGroupId == groupId) {
                    _state.value = _state.value.copy(members = members)
                }
            }
            loaded.onFailure { _state.value = _state.value.copy(failure = describe(it)) }
        }
    }

    fun create(name: String) = act { api.createGroup(name) }

    fun rename(groupId: String, name: String) = act { api.renameGroup(groupId, name) }

    fun delete(groupId: String) = act(closeOpen = true) { api.deleteGroup(groupId) }

    fun addMember(groupId: String, email: String) = act(reopen = groupId) {
        api.addMember(groupId, email)
    }

    fun removeMember(groupId: String, userId: String) = act(reopen = groupId) {
        api.removeMember(groupId, userId)
    }

    /**
     * Whether an address belongs to an account here, asked while it is typed.
     *
     * A courtesy rather than the control — the server refuses an unknown address on its own — but
     * it is the difference between seeing a typo beside the field and discovering it when somebody
     * never receives their ticket.
     */
    fun check(email: String) {
        val trimmed = email.trim()
        if (!trimmed.contains('@') || trimmed.length < 5) {
            _state.value = _state.value.copy(addressKnown = null)
            return
        }
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { api.lookup(trimmed) } }
            // Only if the field still holds what was asked about. Answers arrive out of order
            // when somebody types quickly, and a stale yes is worse than no answer.
            if (_state.value.pendingEmail.trim() == trimmed) {
                _state.value = _state.value.copy(addressKnown = found.getOrNull())
            }
        }
    }

    fun setPendingEmail(value: String) {
        _state.value = _state.value.copy(pendingEmail = value, addressKnown = null)
        check(value)
    }

    private fun act(
        closeOpen: Boolean = false,
        reopen: String? = null,
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, failure = null)
            val done = withContext(Dispatchers.IO) { runCatching { block() } }
            _state.value = _state.value.copy(busy = false, pendingEmail = "", addressKnown = null)
            done.fold(
                onSuccess = {
                    if (closeOpen) _state.value = _state.value.copy(openGroupId = null)
                    load()
                    if (reopen != null) {
                        // Cleared first, because `open` treats the same identifier twice as a
                        // request to close — and closing the panel is the opposite of what
                        // adding somebody to it should do.
                        _state.value = _state.value.copy(openGroupId = null)
                        open(reopen)
                    }
                },
                onFailure = { _state.value = _state.value.copy(failure = describe(it)) },
            )
        }
    }

    private fun describe(cause: Throwable): String =
        (cause as? ServerException)?.message ?: cause.message ?: "error"
}

data class GroupsUiState(
    val groups: List<Group> = emptyList(),
    val openGroupId: String? = null,
    val members: List<GroupMember> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val signedIn: Boolean = true,
    val failure: String? = null,
    /** The address being typed, and whether anybody here uses it. Null means "not asked yet". */
    val pendingEmail: String = "",
    val addressKnown: Boolean? = null,
)

/** Who an event is shared with, kept beside the groups because it is the same conversation. */
data class SharingUiState(
    /** Chosen before the event exists there, and used by the synchronisation that creates it. */
    val eventPassword: String = "",
    /** The password the server holds now, readable by the creator alone. Null for none. */
    val serverPassword: String? = null,
    val access: List<AccessEntry> = emptyList(),
    val groups: List<Group> = emptyList(),
    val loading: Boolean = true,
    val failure: String? = null,
    val pendingEmail: String = "",
    val addressKnown: Boolean? = null,
    /** Set once a free ticket has been taken, so the screen can say so. */
    val claimed: Boolean = false,
    /** Which of the event's originals are being kept off the server. Sharing is the default, so
     *  this holds only the ones deliberately blocked. */
    val blockedDocuments: Set<String> = emptySet(),
)
