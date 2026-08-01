package com.mateof.passvault.ui.wallet

import androidx.compose.ui.graphics.asImageBitmap
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@kotlin.OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class WalletViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val repository: WalletRepository,
    private val rasterizer: com.mateof.passvault.ingest.PageRasterizer,
    private val api: com.mateof.passvault.server.ServerApi,
    private val engine: com.mateof.passvault.sync.SyncEngine,
) : ViewModel() {

    private val _pendingProposal =
        MutableStateFlow<com.mateof.passvault.ingest.IngestProposal?>(null)
    val pendingProposal: StateFlow<com.mateof.passvault.ingest.IngestProposal?> =
        _pendingProposal.asStateFlow()

    /**
     * Decides what arrived and routes it.
     *
     * A `.tkpak` needs a password and goes straight into the wallet; a PDF, an image or a pass goes
     * through review first. The decision is made from the bytes, because the extension a messaging
     * app attaches is not evidence of anything.
     */
    fun receive(read: () -> ByteArray?) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching(read).getOrNull() }
            if (bytes == null) {
                _events.value = ImportOutcome.Unreadable
                return@launch
            }
            // A .tkpak is a ZIP holding a manifest.json; detectMediaKind rejects it as an unknown
            // archive, which is exactly how the two paths are told apart.
            val kind = runCatching { com.mateof.passvault.ingest.detectMediaKind(bytes) }.getOrNull()
            if (kind == null) {
                _pendingArchive.value = bytes
                return@launch
            }
            transient.value = TransientState(isLoading = true)
            val proposal = withContext(Dispatchers.Default) {
                runCatching { com.mateof.passvault.ingest.propose(bytes, rasterizer) }.getOrNull()
            }
            transient.value = TransientState(isLoading = false)
            if (proposal == null) {
                _events.value = ImportOutcome.Unreadable
            } else {
                // Held alongside the proposal, because saving happens later and by then the
                // document would otherwise be gone: the user reviews first, and the pages the
                // review leaves out are the ones worth keeping.
                pendingSource = com.mateof.passvault.data.SourceDocument(
                    bytes = bytes,
                    mediaType = when (kind) {
                        com.mateof.passvault.ingest.MediaKind.PDF -> "application/pdf"
                        com.mateof.passvault.ingest.MediaKind.PNG -> "image/png"
                        com.mateof.passvault.ingest.MediaKind.JPEG -> "image/jpeg"
                        com.mateof.passvault.ingest.MediaKind.PKPASS -> "application/vnd.apple.pkpass"
                    },
                    pageCount = proposal.pageCount,
                )
                _pendingProposal.value = proposal
            }
        }
    }

    private var pendingSource: com.mateof.passvault.data.SourceDocument? = null

    private val _pendingArchive = MutableStateFlow<ByteArray?>(null)
    val pendingArchive: StateFlow<ByteArray?> = _pendingArchive.asStateFlow()

    fun consumeArchive() {
        _pendingArchive.value = null
    }

    /** Saves what the user ticked, and nothing they did not. */
    fun saveProposal(eventName: String, included: List<Int>) {
        val proposal = _pendingProposal.value ?: return
        viewModelScope.launch {
            val chosen = proposal.tickets.filter { included.contains(it.index) }
            val source = pendingSource
            val saved = withContext(Dispatchers.Default) {
                repository.saveProposed(eventName, chosen, source)
            }
            pendingSource = null
            _pendingProposal.value = null
            _events.value = ImportOutcome.Saved(saved)
            // The event this phone just built should reach the server now, not at the next
            // quarter-hour tick. Nothing happens if no server is configured.
            com.mateof.passvault.sync.SyncScheduler.syncNow(context)
        }
    }

    fun discardProposal() {
        pendingSource = null
        _pendingProposal.value = null
    }

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

    /**
     * Labels, and which events carry them.
     *
     * Fetched from the server rather than kept on the device, because a label belongs to an
     * account: the same person's wallet on a second phone should be organised the same way. A
     * wallet with no server simply has none, which is why this starts empty and stays empty
     * until something asks.
     */
    private val _tags = MutableStateFlow<List<com.mateof.passvault.server.Tag>>(emptyList())
    private val _eventTags = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    /** The events the wallet lists, with whatever labels the server knows about them. */
    val events: StateFlow<com.mateof.passvault.ui.wallet.EventsUiState> =
        combine(repository.events(), _tags, _eventTags) { rows, tags, byEvent ->
            com.mateof.passvault.ui.wallet.EventsUiState(
                events = rows.map { row -> row.copy(tagIds = byEvent[row.id].orEmpty()) },
                isLoading = false,
                tags = tags,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = com.mateof.passvault.ui.wallet.EventsUiState(isLoading = true),
        )

    /**
     * Refreshes the labels.
     *
     * Called when the wallet appears rather than watched: labels change when somebody edits them,
     * which is rare, and holding a request open for that would be a poor trade. Failures are
     * swallowed on purpose — a wallet works with no server, so "could not reach it" must not turn
     * the list of events into an error.
     */
    fun refreshTags() {
        if (!api.isSignedIn) {
            _tags.value = emptyList()
            _eventTags.value = emptyMap()
            return
        }
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { api.tags() to api.eventTags() }
            }
            loaded.onSuccess { (tags, byEvent) ->
                _tags.value = tags
                _eventTags.value = byEvent
            }
        }
    }

    /** Sets which labels an event carries, then refreshes so the wallet shows it. */
    fun setEventTags(eventId: String, tagIds: List<String>) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { api.setEventTags(eventId, tagIds) } }
            refreshTags()
        }
    }

    private val _openEvent = MutableStateFlow<String?>(null)

    /**
     * The tickets of whichever event is open.
     *
     * Driven by a flow of the chosen id rather than by loading a list when the screen opens, so a
     * claim confirmed while the screen is up updates it without anything asking.
     */
    val eventTickets: StateFlow<List<TicketRow>> = _openEvent
        .flatMapLatest { id -> if (id == null) kotlinx.coroutines.flow.flowOf(emptyList()) else repository.ticketsOf(id) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * The files the open event's tickets came out of.
     *
     * Driven by the same flow as the tickets, so an import while the event is on screen shows up
     * in its annex without anything asking.
     */
    val eventDocuments: StateFlow<List<DocumentRow>> = _openEvent
        .flatMapLatest { id ->
            if (id == null) kotlinx.coroutines.flow.flowOf(emptyList())
            else repository.documentRowsOf(id)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun openEvent(eventId: String?) {
        _openEvent.value = eventId
    }

    private val _exported = MutableStateFlow<java.io.File?>(null)

    /** Where the last file was written, for the screen to hand to the system share sheet. */
    val exported: StateFlow<java.io.File?> = _exported.asStateFlow()

    /**
     * Writes a `.tkpak` into this app's own cache.
     *
     * The cache rather than Downloads, and shared out through a FileProvider: the file is a bearer
     * object holding barcodes, and leaving a copy in a folder every app can read would undo the
     * password it was just given.
     */
    fun export(eventId: String, ticketIds: Set<String>?, password: String) {
        viewModelScope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = repository.exportTkpak(eventId, ticketIds, password)
                    val directory = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
                    // One at a time: the previous file has already been sent or abandoned, and
                    // keeping them would leave a pile of encrypted tickets in the cache.
                    directory.listFiles()?.forEach { it.delete() }
                    java.io.File(directory, "passvault-${eventId.take(8)}.tkpak")
                        .apply { writeBytes(bytes) }
                }
            }
            written.fold(
                onSuccess = { _exported.value = it },
                onFailure = { _events.value = ImportOutcome.Unreadable },
            )
        }
    }

    fun consumeExport() {
        _exported.value = null
    }

    /** Records the icon and colour somebody chose for an event. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /**
     * The pull gesture: one synchronisation, felt.
     *
     * Distinct from syncNow, which is fire-and-forget for edits — a pull holds the screen and
     * has to be *seen* finishing, or the gesture reads as broken.
     */
    fun refreshFromServer() {
        viewModelScope.launch {
            _refreshing.value = true
            withContext(Dispatchers.IO) { runCatching { engine.sync() } }
            refreshTags()
            _refreshing.value = false
        }
    }

    fun setEventMark(eventId: String, icon: String, colour: String) {
        viewModelScope.launch { repository.setEventMark(eventId, icon, colour) }
    }

    fun setEventFacts(eventId: String, startsAt: String?, venue: String?) {
        viewModelScope.launch {
            repository.setEventFacts(
                eventId = eventId,
                startsAt = startsAt,
                venue = venue,
                startsAtTouched = true,
                venueTouched = true,
            )
            com.mateof.passvault.sync.SyncScheduler.syncNow(context)
        }
    }

    /**
     * Makes a label without leaving whatever screen wanted it.
     *
     * The moment somebody wants "Vigo" on an event is the moment the label does not exist yet,
     * and a round trip through the labels screen loses them.
     */
    fun createTag(name: String, colour: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { api.createTag(name, colour) } }
            refreshTags()
        }
    }

    private val _events = MutableStateFlow<ImportOutcome?>(null)
    val importOutcome: StateFlow<ImportOutcome?> = _events.asStateFlow()

    /** A sentence about the last deletion, for the snackbar. Deletions deserve a receipt. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun consumeNotice() {
        _notice.value = null
    }

    /**
     * Deletes events: on the server first when one is connected, then on this phone.
     *
     * The order is the policy the user was promised — the server's copy is the one that
     * counts. When the server cannot be reached, only this phone forgets, and the notice says
     * so in as many words: what a server keeps comes back at the next synchronisation.
     */
    fun deleteEvents(eventIds: List<String>) {
        viewModelScope.launch {
            val configured = engine.isPossible
            var keptByServer = 0
            withContext(Dispatchers.IO) {
                for (eventId in eventIds) {
                    if (configured) {
                        // 404 is as good as deleted: an event the server never had, or already
                        // let go of, is exactly the outcome asked for.
                        val outcome = runCatching { api.deleteEvent(eventId) }
                        val status = (outcome.exceptionOrNull() as? com.mateof.passvault.server.ServerException)?.status
                        if (outcome.isFailure && status != 404) keptByServer += 1
                    }
                    repository.purgeEventLocally(eventId)
                }
            }
            _notice.value = context.getString(
                when {
                    !configured -> com.mateof.passvault.R.string.notice_deleted_device
                    keptByServer == 0 -> com.mateof.passvault.R.string.notice_deleted_everywhere
                    else -> com.mateof.passvault.R.string.notice_deleted_device_only_server_keeps
                },
            )
        }
    }

    /**
     * Deletes tickets, by whichever mechanism this phone is entitled to.
     *
     * An event created on this device gets tombstones in the log — the removal travels to the
     * server and to every other phone by the same road every other edit takes. An event created
     * elsewhere gets the server asked (it holds the authority) and this phone forgets its copy;
     * without a reachable server that forgetting is local, and the notice says what that means.
     */
    fun deleteTickets(eventId: String, ticketIds: Set<String>) {
        if (ticketIds.isEmpty()) return
        viewModelScope.launch {
            val propagates = withContext(Dispatchers.IO) {
                if (repository.isCreatedHere(eventId)) {
                    repository.removeTicketsByOperation(eventId, ticketIds)
                    true
                } else {
                    var reached = api.isSignedIn
                    if (reached) {
                        for (ticketId in ticketIds) {
                            val outcome = runCatching { api.withdrawTicket(ticketId) }
                            val status = (outcome.exceptionOrNull() as? com.mateof.passvault.server.ServerException)?.status
                            if (outcome.isFailure && status != 404) reached = false
                        }
                    }
                    repository.purgeTicketsLocally(eventId, ticketIds)
                    reached
                }
            }
            _notice.value = context.getString(
                if (propagates) com.mateof.passvault.R.string.notice_tickets_deleted
                else com.mateof.passvault.R.string.notice_tickets_deleted_device,
            )
            com.mateof.passvault.sync.SyncScheduler.syncNow(context)
        }
    }

    /**
     * Imports a file somebody shared.
     *
     * Argon2id is deliberately expensive, so this runs off the main thread. Doing it inline would
     * freeze the interface for the best part of a second on the operation the user is watching.
     */
    /**
     * Reads the shared file and imports it.
     *
     * Reading is passed in as a lambda rather than done by the caller, so a file that cannot be
     * opened reports through the same channel as a wrong password instead of vanishing.
     */
    fun importFrom(password: String, read: () -> ByteArray?) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching(read).getOrNull() }
            if (bytes == null) {
                _events.value = ImportOutcome.Unreadable
                return@launch
            }
            import(bytes, password)
        }
    }

    fun import(archive: ByteArray, password: String) {
        viewModelScope.launch {
            transient.value = TransientState(isLoading = true)
            val outcome = withContext(Dispatchers.Default) {
                try {
                    val opened = Tkpak.openWithPassword(archive, password)
                    repository.import(opened)
                    com.mateof.passvault.sync.SyncScheduler.syncNow(context)
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

    /**
     * Loads one ticket.
     *
     * Off the main thread, because this is where the barcode is decrypted — the one field the list
     * deliberately never touches.
     *
     * Suspending rather than taking a callback, which is what the ticket pager needs: each page
     * loads its own ticket as it is composed, and Compose composes the pages either side of the
     * current one, so the next ticket is already decrypted before a finger reaches it.
     */
    suspend fun loadTicket(ticketId: String): com.mateof.passvault.ui.ticket.TicketDetail? =
        withContext(Dispatchers.Default) { repository.detail(ticketId) }

    private val _document = MutableStateFlow(com.mateof.passvault.ui.document.DocumentViewState())
    val document: StateFlow<com.mateof.passvault.ui.document.DocumentViewState> =
        _document.asStateFlow()

    /**
     * Renders a stored document for viewing.
     *
     * Decrypted into memory and rasterised page by page; nothing is written anywhere a viewer
     * application could read. Off the main thread because a long document is a lot of bitmaps.
     */
    fun openDocument(documentId: String) {
        viewModelScope.launch {
            _document.value = com.mateof.passvault.ui.document.DocumentViewState(isLoading = true)
            val pages = withContext(Dispatchers.Default) {
                runCatching {
                    val bytes = repository.documentBytes(documentId) ?: return@runCatching emptyList()
                    val count = rasterizer.pageCount(bytes)
                    (1..count).map { number ->
                        val page = rasterizer.render(bytes, number, RENDER_WIDTH)
                        com.mateof.passvault.ui.document.DocumentPage(
                            number = number,
                            image = android.graphics.Bitmap.createBitmap(
                                page.pixels,
                                page.width,
                                page.height,
                                android.graphics.Bitmap.Config.ARGB_8888,
                            ).asImageBitmap(),
                        )
                    }
                }.getOrDefault(emptyList())
            }
            _document.value = com.mateof.passvault.ui.document.DocumentViewState(
                pages = pages,
                isLoading = false,
                failed = pages.isEmpty(),
            )
        }
    }

    suspend fun hasDocument(eventId: String): Boolean = repository.documentsOf(eventId).isNotEmpty()

    fun consumeOutcome() {
        _events.value = null
    }

    private data class TransientState(val isLoading: Boolean = false)

    private companion object {
        // Narrower than ingestion's 1600: this is for reading, not for decoding a dense barcode,
        // and a long document at full width is a lot of bitmaps held at once.
        const val RENDER_WIDTH = 1080
    }
}

sealed interface ImportOutcome {
    data class Imported(
        val ticketCount: Int,
        val eventName: String,
        val senderVerified: Boolean,
    ) : ImportOutcome

    data class Failed(val code: TkpakError) : ImportOutcome

    data class Saved(val ticketCount: Int) : ImportOutcome

    /** The file itself could not be read — a revoked grant, a deleted file, a broken share. */
    data object Unreadable : ImportOutcome
}
