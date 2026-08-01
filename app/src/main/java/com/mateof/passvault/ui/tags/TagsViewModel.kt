package com.mateof.passvault.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mateof.passvault.server.ServerApi
import com.mateof.passvault.server.ServerException
import com.mateof.passvault.server.Tag
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Labels, which are the reader's own vocabulary for their own wallet.
 *
 * Distinct from the icon and colour an event already carries, and worth keeping distinct: those
 * say what kind of thing it is, from a closed set, so a concert looks like a concert in
 * everybody's wallet. A label says what it is *to you* — "Vigo", "traballo", "aniversario de
 * Ana" — which cannot come from a list somebody else wrote.
 *
 * They live on the server because they belong to an account rather than to a device: the same
 * person's wallet on a second phone should be organised the same way. The consequence is stated
 * plainly on screen — no server, no labels — which is the same rule groups and notices follow.
 */
@HiltViewModel
class TagsViewModel @Inject constructor(
    private val api: ServerApi,
) : ViewModel() {

    private val _state = MutableStateFlow(TagsUiState())
    val state: StateFlow<TagsUiState> = _state.asStateFlow()

    fun load() {
        if (!api.isSignedIn) {
            _state.value = _state.value.copy(signedIn = false, loading = false)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, failure = null, signedIn = true)
            val loaded = withContext(Dispatchers.IO) { runCatching { api.tags() } }
            _state.value = loaded.fold(
                onSuccess = { _state.value.copy(loading = false, tags = it) },
                onFailure = { _state.value.copy(loading = false, failure = describe(it)) },
            )
        }
    }

    fun create(name: String, colour: String) = act { api.createTag(name, colour) }

    fun update(tagId: String, name: String, colour: String) = act { api.updateTag(tagId, name, colour) }

    fun delete(tagId: String) = act { api.deleteTag(tagId) }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            val done = withContext(Dispatchers.IO) { runCatching { block() } }
            done.fold(
                onSuccess = { load() },
                onFailure = { _state.value = _state.value.copy(failure = describe(it)) },
            )
        }
    }

    private fun describe(cause: Throwable): String =
        (cause as? ServerException)?.message ?: cause.message ?: "error"
}

data class TagsUiState(
    val tags: List<Tag> = emptyList(),
    val loading: Boolean = true,
    val signedIn: Boolean = true,
    val failure: String? = null,
)

/** The eight the server accepts. Anything else is stored as the first, so the list is the truth. */
val TAG_COLOURS = listOf("violet", "blue", "teal", "green", "amber", "orange", "red", "pink")
