package com.mateof.passvault.ui.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mateof.passvault.data.WalletRepository
import com.mateof.passvault.tkpak.Tkpak
import com.mateof.passvault.tkpak.TkpakError
import com.mateof.passvault.tkpak.TkpakException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class WalletViewModel @Inject constructor(
    private val repository: WalletRepository,
) : ViewModel() {

    private val transient = MutableStateFlow(TransientState())

    /**
     * The screen state.
     *
     * `stateIn` with a five-second stop timeout, so rotating the device or a brief trip to another
     * app does not tear down the database query and rebuild it — the usual cause of a list that
     * flashes empty when you come back to it.
     */
    val state: StateFlow<WalletUiState> = combine(
        repository.wallet(),
        transient,
    ) { tickets, extra ->
        WalletUiState(
            tickets = tickets,
            isLoading = extra.isLoading,
            isLocked = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = WalletUiState(isLoading = true),
    )

    private val _events = MutableStateFlow<ImportOutcome?>(null)
    val importOutcome: StateFlow<ImportOutcome?> = _events.asStateFlow()

    /**
     * Imports a file somebody shared.
     *
     * Argon2id is deliberately expensive, so this runs off the main thread. Doing it inline would
     * freeze the interface for the best part of a second on the operation the user is watching.
     */
    fun import(archive: ByteArray, password: String) {
        viewModelScope.launch {
            transient.value = TransientState(isLoading = true)
            val outcome = withContext(Dispatchers.Default) {
                try {
                    val opened = Tkpak.openWithPassword(archive, password)
                    repository.import(opened)
                    ImportOutcome.Imported(
                        ticketCount = opened.bundle.tickets.size,
                        eventName = opened.bundle.event.name,
                        // Reported rather than hidden. An unknown issuer is the ordinary case for a
                        // file from somebody you have never paired with, and the user is the one who
                        // can judge whether they trust it.
                        senderVerified = opened.signatureValid,
                    )
                } catch (failure: TkpakException) {
                    ImportOutcome.Failed(failure.code)
                }
            }
            transient.value = TransientState(isLoading = false)
            _events.value = outcome
        }
    }

    fun consumeOutcome() {
        _events.value = null
    }

    private data class TransientState(val isLoading: Boolean = false)
}

sealed interface ImportOutcome {
    data class Imported(
        val ticketCount: Int,
        val eventName: String,
        val senderVerified: Boolean,
    ) : ImportOutcome

    data class Failed(val code: TkpakError) : ImportOutcome
}
