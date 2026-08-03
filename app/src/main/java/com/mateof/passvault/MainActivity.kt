package com.mateof.passvault

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mateof.passvault.share.ShareScope
import com.mateof.passvault.ui.Destination
import com.mateof.passvault.ui.MainDrawerSheet
import com.mateof.passvault.ui.ingest.IngestReviewScreen
import com.mateof.passvault.ui.ingest.IngestReviewState
import com.mateof.passvault.ui.ingest.ReviewRow
import com.mateof.passvault.ui.share.ShareViewModel
import com.mateof.passvault.ui.theme.Motion
import com.mateof.passvault.ui.theme.PassVaultTheme
import com.mateof.passvault.ui.ticket.TicketPager
import com.mateof.passvault.ui.wallet.ImportOutcome
import com.mateof.passvault.ui.wallet.WalletScreen
import com.mateof.passvault.ui.wallet.WalletUiState
import com.mateof.passvault.ui.wallet.WalletViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Applies the chosen language before any resource is read.
     *
     * The device's language is the default and stays so until somebody chooses otherwise — the
     * setting exists for the phone set to English whose owner reads Galician. Changing it
     * recreates the activity, which is the platform's own way of reloading every string.
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        val chosen = newBase
            .getSharedPreferences("passvault.server", android.content.Context.MODE_PRIVATE)
            .getString("ui_locale", null)
        if (chosen.isNullOrBlank()) {
            super.attachBaseContext(newBase)
            return
        }
        val configuration = android.content.res.Configuration(newBase.resources.configuration)
        configuration.setLocale(java.util.Locale(chosen))
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge to edge before setContent, so the first frame is already laid out for it rather than
        // reflowing once the insets arrive.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val shared = sharedFile(intent)
        setContent {
            PassVaultTheme {
                PassVaultApp(sharedFile = shared)
            }
        }
    }

    /**
     * The file somebody sent.
     *
     * Both SEND and VIEW, because a messaging app picks whichever it likes and there is no
     * predicting which. Most send an unregistered extension as `application/octet-stream`, so the
     * manifest matches that too and the format is identified from the bytes rather than the name.
     */
    @Suppress("DEPRECATION")
    private fun sharedFile(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
        Intent.ACTION_VIEW -> intent.data
        else -> null
    }
}

/**
 * What the picker offers.
 *
 * `application/octet-stream` is in the list because a `.tkpak` has no registered type on most
 * devices and a picker that hides it makes the file unopenable — the same reason the manifest
 * matches it for a share.
 */
private val IMPORTABLE_TYPES = arrayOf(
    "application/pdf",
    "image/*",
    "application/vnd.apple.pkpass",
    "application/vnd.passvault.tkpak",
    "application/octet-stream",
)

private sealed interface Screen {
    data object Wallet : Screen
    data class Event(val id: String, val name: String) : Screen
    /**
     * Which ticket to open on, rather than the ticket itself.
     *
     * Carrying the decrypted detail meant the screen could only ever show the one it was opened
     * with. Moving to the next needs the event's whole list and the ability to load any of it,
     * which is the pager's job — so the route carries only where to start.
     */
    data class Ticket(val ticketId: String) : Screen
    /** Handing tickets to a phone in the room, this side doing the giving. */
    data class ShareSend(val scope: ShareScope) : Screen
    /** The other side: named, findable, waiting. */
    data object ShareReceive : Screen
    /** The question that comes first: which side of the table is this phone. */
    data object ShareChooser : Screen
    data class Document(val eventId: String) : Screen
    data object Notices : Screen
    data object Groups : Screen
    data object Tags : Screen
    /** Choosing what to hand over, before the radio does anything. */
    data object SharePicker : Screen
    data object Profile : Screen
    data object Settings : Screen
    data object Server : Screen
    data object Updates : Screen
}

@Composable
private fun PassVaultApp(
    sharedFile: Uri?,
    viewModel: WalletViewModel = hiltViewModel(),
) {
    // collectAsStateWithLifecycle, not collectAsState: the database query should stop while the app
    // is in the background rather than keep the process awake for a screen nobody is looking at.
    val state by viewModel.state.collectAsStateWithLifecycle()
    val outcome by viewModel.importOutcome.collectAsStateWithLifecycle()

    var screen by remember { mutableStateOf<Screen>(Screen.Wallet) }
    // Remembered so a ticket knows which event to go back to.
    var openEvent by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Which screen asked for the document, so closing it returns there rather than somewhere
    // plausible. Cleared as it is used: a stale origin would send the next reader to the wrong
    // place entirely.
    var documentOrigin by remember { mutableStateOf<Screen?>(null) }
    val proposal by viewModel.pendingProposal.collectAsStateWithLifecycle()
    val pendingArchive by viewModel.pendingArchive.collectAsStateWithLifecycle()
    val documentState by viewModel.document.collectAsStateWithLifecycle()
    val eventsState by viewModel.events.collectAsStateWithLifecycle()
    val walletRefreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshTags() }
    val eventTickets by viewModel.eventTickets.collectAsStateWithLifecycle()
    val eventDocuments by viewModel.eventDocuments.collectAsStateWithLifecycle()
    var excluded by remember { mutableStateOf(emptySet<Int>()) }
    val snackbars = remember { SnackbarHostState() }
    val context = LocalContext.current

    // The system back gesture returns to the wallet rather than leaving the app, which is what a
    // user expects of a detail screen and what they do not get for free.
    // Back from a ticket returns to its event, not to the wallet: that is where the user came
    // from, and jumping two levels loses their place in a list of forty.
    /**
     * Where a detail screen goes back to.
     *
     * The event it belongs to, falling back to the wallet only when there is none — which happens
     * for a ticket opened straight from a share intent. Used by the system gesture *and* by the
     * arrow in the title bar: they were separate before, the gesture was right and the arrow
     * dropped two levels, so which one somebody used decided where they ended up.
     */
    val backToEvent: () -> Unit = {
        val event = openEvent
        screen = if (event != null) Screen.Event(event.first, event.second) else Screen.Wallet
    }

    /**
     * Where the document screen goes back to.
     *
     * Wherever it was opened from, which is now two places: the annex inside an event, and a
     * ticket. Returning a reader to the event when they came from a ticket loses their place in a
     * list of forty just as surely as returning them to the wallet did.
     */
    val backFromDocument: () -> Unit = {
        val origin = documentOrigin
        documentOrigin = null
        if (origin != null) screen = origin else backToEvent()
    }

    BackHandler(enabled = screen is Screen.Ticket) { backToEvent() }
    BackHandler(enabled = screen is Screen.Document) { backFromDocument() }
    BackHandler(
        enabled = screen is Screen.Event ||
            screen is Screen.Tags ||
            screen is Screen.ShareSend ||
            screen is Screen.ShareReceive ||
            screen is Screen.ShareChooser ||
            screen is Screen.SharePicker ||
            screen is Screen.Profile ||
            screen is Screen.Settings ||
            screen is Screen.Notices ||
            screen is Screen.Groups ||
            screen is Screen.Server ||
            screen is Screen.Updates,
    ) {
        viewModel.openEvent(null)
        screen = Screen.Wallet
    }
    BackHandler(enabled = proposal != null) { viewModel.discardProposal() }

    /**
     * Opening a document the user chose.
     *
     * `OpenDocument`, not `GetContent`: it returns a URI that survives the picker closing and
     * can be re-read, and it shows the system file browser rather than a gallery. Both matter
     * for a PDF sitting in Downloads, which is where a ticket from a vendor lands.
     */
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.receive { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
        }
    }

    // Where the camera will write. Held across the result, because the contract hands back only
    // whether a picture was taken, not where it went.
    var captureTarget by remember { mutableStateOf<Uri?>(null) }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { taken ->
        val uri = captureTarget
        captureTarget = null
        if (taken && uri != null) {
            viewModel.receive {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            // Deleted as soon as it has been read. A photograph of a barcode left in the cache
            // is a plaintext ticket outside the encrypted store, which is what the rest of this
            // app goes to some trouble to avoid.
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    fun launchCapture() {
        val directory = java.io.File(context.cacheDir, "captures").apply { mkdirs() }
        val file = java.io.File(directory, "capture-${System.currentTimeMillis()}.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            file,
        )
        captureTarget = uri
        takePicture.launch(uri)
    }

    // The camera permission is only demanded because the manifest declares it; an app that does
    // not declare it can use the camera app without asking. It is declared for the scanner that
    // reads a barcode live, so it has to be granted here too.
    val requestCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) launchCapture() }

    fun startCapture() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) launchCapture() else requestCamera.launch(android.Manifest.permission.CAMERA)
    }

    // A shared file is read once, and what it turns out to be decides which way it goes: a .tkpak
    // asks for a password, anything else goes through review first.
    LaunchedEffect(sharedFile) {
        val uri = sharedFile ?: return@LaunchedEffect
        viewModel.receive { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
    }

    LaunchedEffect(outcome) {
        val result = outcome ?: return@LaunchedEffect
        val message = when (result) {
            is ImportOutcome.Imported -> buildString {
                append(
                    context.resources.getQuantityString(
                        R.plurals.import_done,
                        result.ticketCount,
                        result.ticketCount,
                        result.eventName,
                    ),
                )
                if (!result.senderVerified) {
                    // Said, not hidden. An unverified sender is the ordinary case for a file from
                    // somebody never paired with, and the user is who can judge it.
                    append(' ')
                    append(context.getString(R.string.import_unverified_sender))
                }
            }
            is ImportOutcome.Saved -> context.resources.getQuantityString(
                R.plurals.ingest_saved,
                result.ticketCount,
                result.ticketCount,
            )
            is ImportOutcome.Failed -> result.code.name
            ImportOutcome.Unreadable -> context.getString(R.string.import_unreadable)
        }
        snackbars.showSnackbar(message)
        viewModel.consumeOutcome()
    }

    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val destination = when (screen) {
        Screen.Notices -> Destination.Notices
        Screen.Groups -> Destination.Groups
        Screen.Tags -> Destination.Tags
        Screen.Profile -> Destination.Profile
        Screen.Settings -> Destination.Settings
        is Screen.ShareSend -> Destination.Share
        Screen.ShareReceive -> Destination.Share
        Screen.ShareChooser -> Destination.Share
        Screen.SharePicker -> Destination.Share
        Screen.Server -> Destination.Server
        Screen.Updates -> Destination.Updates
        else -> Destination.Wallet
    }
    val openDrawer: () -> Unit = { scope.launch { drawer.open() } }
    val goTo: (Destination) -> Unit = { chosen ->
        scope.launch { drawer.close() }
        viewModel.openEvent(null)
        screen = when (chosen) {
            Destination.Wallet -> Screen.Wallet
            Destination.Notices -> Screen.Notices
            Destination.Groups -> Screen.Groups
            Destination.Tags -> Screen.Tags
            Destination.Profile -> Screen.Profile
            Destination.Settings -> Screen.Settings
            // Through the chooser: one phone gives and the other takes, and which this
            // one is doing is the first decision, not something inferred from who taps first.
            Destination.Share -> Screen.ShareChooser
            Destination.Server -> Screen.Server
            Destination.Updates -> Screen.Updates
        }
    }

    // The gesture is enabled on every screen the drawer itself leads to: they are siblings,
    // not details, and each wears the menu button in its title bar for the same reason. Detail
    // screens — an event, a ticket — keep the edge for the system back gesture.
    val topLevel = screen is Screen.Wallet ||
        screen is Screen.Notices ||
        screen is Screen.Groups ||
        screen is Screen.Tags ||
        screen is Screen.Profile ||
        screen is Screen.Settings ||
        screen is Screen.Server ||
        screen is Screen.Updates ||
        screen is Screen.ShareChooser
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = topLevel,
        drawerContent = { MainDrawerSheet(current = destination, onSelect = goTo) },
    ) {
    Scaffold(snackbarHost = { SnackbarHost(snackbars) }) { padding ->
        AnimatedContent(
            targetState = screen,
            transitionSpec = { Motion.itemEnter togetherWith Motion.itemExit },
            label = "screen",
            modifier = Modifier.padding(padding),
        ) { current ->
            when (current) {
                Screen.Wallet -> proposal?.let { pending ->
                    IngestReviewScreen(
                        state = IngestReviewState(
                            pageCount = pending.pageCount,
                            rows = pending.tickets.map { ticket ->
                                ReviewRow(
                                    index = ticket.index,
                                    label = ticket.suggestedLabel,
                                    barcodeValue = ticket.barcode?.value,
                                    pageNumber = ticket.pageNumber,
                                    include = ticket.include != excluded.contains(ticket.index),
                                    warning = ticket.warnings.firstOrNull(),
                                )
                            },
                        ),
                        onToggle = { index ->
                            excluded = if (excluded.contains(index)) excluded - index else excluded + index
                        },
                        onConfirm = { typedName ->
                            val chosen = pending.tickets
                                .filter { it.include != excluded.contains(it.index) }
                                .map { it.index }
                            excluded = emptySet()
                            viewModel.saveProposal(
                                typedName.ifBlank { context.getString(R.string.ingest_event_default) },
                                chosen,
                            )
                        },
                    )
                } ?: WalletPane(
                    refreshing = walletRefreshing,
                    onRefresh = viewModel::refreshFromServer,
                    state = eventsState,
                    onEventClick = { id, name ->
                        viewModel.openEvent(id)
                        screen = Screen.Event(id, name)
                    },
                    onShare = { screen = Screen.ShareChooser },
                    onImport = { pickFile.launch(IMPORTABLE_TYPES) },
                    onCapture = { startCapture() },
                    onMenu = openDrawer,
                    onDeleteEvents = { chosen -> viewModel.deleteEvents(chosen) },
                )
                is Screen.Event -> {
                    openEvent = current.id to current.name
                    val row = eventsState.events.firstOrNull { it.id == current.id }
                    EventPane(
                        eventId = current.id,
                        title = current.name,
                        icon = row?.icon,
                        colour = row?.colour,
                        tickets = eventTickets,
                        documents = eventDocuments,
                        onBack = { viewModel.openEvent(null); screen = Screen.Wallet },
                        // Straight to the screen. It used to decrypt the ticket first and pass
                        // the result in, so a tap did nothing visible until the decrypt finished.
                        onTicketClick = { ticketId -> screen = Screen.Ticket(ticketId) },
                        onOpenDocument = { documentId ->
                            viewModel.openDocument(documentId)
                            documentOrigin = current
                            screen = Screen.Document(current.id)
                        },
                        onMarkChosen = { chosenIcon, chosenColour ->
                            viewModel.setEventMark(current.id, chosenIcon, chosenColour)
                        },
                        startsAt = row?.startsAt,
                        venue = row?.venue,
                        tags = eventsState.tags,
                        tagIds = row?.tagIds.orEmpty(),
                        onCreateTag = viewModel::createTag,
                        onDetailsSaved = { startsAt, venue, tagIds ->
                            viewModel.setEventFacts(current.id, startsAt, venue)
                            // Labels go to the server and the facts to the log: one is what this
                            // event is to this account, the others are facts about the event.
                            viewModel.setEventTags(current.id, tagIds)
                        },
                        onShare = { chosen ->
                            screen = Screen.ShareSend(
                                if (chosen == null) {
                                    ShareScope.Event(current.id, current.name)
                                } else {
                                    ShareScope.Tickets(current.id, current.name, chosen.toList())
                                },
                            )
                        },
                        onExport = { chosen, password ->
                            viewModel.export(current.id, chosen, password)
                        },
                        onDeleteEvent = {
                            viewModel.deleteEvents(listOf(current.id))
                            viewModel.openEvent(null)
                            screen = Screen.Wallet
                        },
                        onDeleteTickets = { chosen -> viewModel.deleteTickets(current.id, chosen) },
                    )
                }
                is Screen.Ticket -> TicketPager(
                    ticketId = current.ticketId,
                    tickets = eventTickets,
                    load = viewModel::loadTicket,
                    onBack = backToEvent,
                    onOpenDocument = { shown ->
                        eventDocuments.firstOrNull()?.let { document ->
                            viewModel.openDocument(document.id)
                            // The ticket on screen, not the one this was opened on: they differ
                            // once somebody has swiped, and coming back to the wrong one undoes
                            // the swiping they just did.
                            documentOrigin = Screen.Ticket(shown)
                            screen = Screen.Document(openEvent?.first.orEmpty())
                        }
                    },
                    onReturn = { shown ->
                        // Back to the event once given up: the seat is no longer theirs to look at.
                        viewModel.returnTicket(shown, onDone = backToEvent)
                    },
                    onControl = { shown, control ->
                        viewModel.ticketControl { api ->
                            when (control) {
                                com.mateof.passvault.ui.ticket.TicketControl.Block ->
                                    api.blockTicket(shown)
                                com.mateof.passvault.ui.ticket.TicketControl.Unblock ->
                                    api.unblockTicket(shown)
                                com.mateof.passvault.ui.ticket.TicketControl.ToggleShareOn ->
                                    api.setSharePermission(shown, true)
                                com.mateof.passvault.ui.ticket.TicketControl.ToggleShareOff ->
                                    api.setSharePermission(shown, false)
                                com.mateof.passvault.ui.ticket.TicketControl.VisibleDayBefore ->
                                    api.setTicketVisibility(shown, visibleFrom = null, hoursBeforeEvent = 24)
                                com.mateof.passvault.ui.ticket.TicketControl.ClearVisibility ->
                                    api.setTicketVisibility(shown, visibleFrom = null, hoursBeforeEvent = null)
                            }
                        }
                    },
                )
                Screen.Notices -> NoticesPane(onMenu = openDrawer)
                Screen.Groups -> GroupsPane(onMenu = openDrawer)
                Screen.Tags -> TagsPane(onMenu = openDrawer)
                Screen.ShareChooser -> ShareChooserPane(
                    onMenu = openDrawer,
                    onSend = { screen = Screen.SharePicker },
                    onReceive = { screen = Screen.ShareReceive },
                )
                Screen.SharePicker -> SharePickerPane(
                    events = eventsState.events,
                    tickets = eventTickets,
                    documents = eventDocuments,
                    onLoadEvent = { id -> viewModel.openEvent(id) },
                    loadPolicy = { id -> viewModel.sharePolicy(id) },
                    onBack = { screen = Screen.ShareChooser },
                    onChosen = { chosen -> screen = Screen.ShareSend(chosen) },
                )
                Screen.Profile -> ProfilePane(
                    onMenu = openDrawer,
                    onDeleted = { screen = Screen.Wallet },
                )
                Screen.Settings -> SettingsPane(onMenu = openDrawer)
                Screen.Server -> ServerPane(onMenu = openDrawer)
                Screen.Updates -> UpdatePane(onMenu = openDrawer)
                is Screen.Document -> DocumentPane(
                    state = documentState,
                    onBack = backFromDocument,
                )
                is Screen.ShareSend -> ShareSendPane(
                    scope = current.scope,
                    onBack = { screen = Screen.Wallet },
                )
                Screen.ShareReceive -> ShareReceivePane(
                    onBack = { screen = Screen.Wallet },
                )
            }
        }
    }
    }

    val deletionNotice by viewModel.notice.collectAsStateWithLifecycle()
    LaunchedEffect(deletionNotice) {
        deletionNotice?.let {
            snackbars.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    val exported by viewModel.exported.collectAsStateWithLifecycle()
    LaunchedEffect(exported) {
        val file = exported ?: return@LaunchedEffect
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".files",
            file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.passvault.tkpak"
            putExtra(Intent.EXTRA_STREAM, uri)
            // Without this the receiving app is handed a URI it has no permission to open, which
            // surfaces as a share that appears to work and delivers an empty file.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null))
        viewModel.consumeExport()
    }

    pendingArchive?.let { bytes ->
        ImportDialog(
            onDismiss = { viewModel.consumeArchive() },
            onConfirm = { password ->
                viewModel.consumeArchive()
                viewModel.import(bytes, password)
            },
        )
    }
}

/**
 * Updating the app from its own releases.
 *
 * The check runs when the screen opens, because somebody who navigated here came to ask that
 * question and making them press a button to ask it again is asking twice. The download does not:
 * it is several megabytes and belongs to a decision the user makes after reading what changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdatePane(
    onMenu: () -> Unit,
    viewModel: com.mateof.passvault.ui.update.UpdateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.collectInstallResult()
        viewModel.check()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.update_title)) },
                navigationIcon = {
                    IconButton(onClick = onMenu) {
                        Icon(
                            Icons.Filled.Menu,
                            contentDescription = stringResource(R.string.action_menu),
                        )
                    }
                },
            )
        },
    ) { padding ->
        com.mateof.passvault.ui.update.UpdateScreen(
            state = state,
            onCheck = viewModel::check,
            onDownload = viewModel::download,
            onInstall = viewModel::install,
            onGrantPermission = {
                context.startActivity(
                    viewModel.permissionSettings().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            // Read on each recomposition rather than held: the user may be coming back from
            // Settings having just granted it, and a remembered `false` would keep the screen
            // asking for something they have already done.
            mayInstall = viewModel.mayInstall(),
            modifier = Modifier.padding(padding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletPane(
    state: com.mateof.passvault.ui.wallet.EventsUiState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onEventClick: (String, String) -> Unit,
    onShare: () -> Unit,
    onImport: () -> Unit,
    onCapture: () -> Unit,
    onMenu: () -> Unit,
    onDeleteEvents: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Collapses as the list scrolls, giving the content the whole screen once the user is reading
    // rather than navigating.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // Null while browsing; the chosen ids while a long press has the list in selection mode.
    var selection by remember { mutableStateOf<Set<String>?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    BackHandler(enabled = selection != null) { selection = null }

    if (confirmingDelete) {
        val chosen = selection.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = {
                Text(pluralStringResource(R.plurals.events_delete_confirm, chosen.size, chosen.size))
            },
            text = { Text(stringResource(R.string.events_delete_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    selection = null
                    onDeleteEvents(chosen.toList())
                }) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            val chosen = selection
            if (chosen != null) {
                // The contextual bar: what is chosen and the one thing to do about it. The
                // cross leaves selection without doing anything, which must always be offered.
                TopAppBar(
                    title = {
                        Text(pluralStringResource(R.plurals.events_selected, chosen.size, chosen.size))
                    },
                    navigationIcon = {
                        IconButton(onClick = { selection = null }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_cancel),
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { confirmingDelete = true },
                            enabled = chosen.isNotEmpty(),
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                            )
                        }
                    },
                )
            } else {
                MediumTopAppBar(
                    title = { Text(stringResource(R.string.events_title)) },
                    actions = {
                        IconButton(onClick = onImport) {
                            Icon(
                                Icons.Filled.FolderOpen,
                                contentDescription = stringResource(R.string.action_import),
                            )
                        }
                        IconButton(onClick = onCapture) {
                            Icon(
                                Icons.Filled.PhotoCamera,
                                contentDescription = stringResource(R.string.action_capture),
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onMenu) {
                            Icon(
                                Icons.Filled.Menu,
                                contentDescription = stringResource(R.string.action_menu),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { padding ->
        // The gesture a thumb already knows. It runs a real synchronisation, so what it promises
        // — fresh data — is what it delivers, and the spinner holds until that is true.
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.padding(padding),
        ) {
            com.mateof.passvault.ui.wallet.EventsScreen(
                state = state,
                onEventClick = { id ->
                    onEventClick(id, state.events.firstOrNull { it.id == id }?.name.orEmpty())
                },
                selection = selection,
                onToggleSelection = { id ->
                    selection = selection?.let { if (id in it) it - id else it + id }
                },
                onStartSelection = { id -> selection = setOf(id) },
            )
        }
    }
}

/** One event, with its tickets. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EventPane(
    eventId: String,
    title: String,
    icon: String?,
    colour: String?,
    tickets: List<com.mateof.passvault.ui.wallet.TicketRow>,
    documents: List<com.mateof.passvault.ui.wallet.DocumentRow>,
    onBack: () -> Unit,
    onTicketClick: (String) -> Unit,
    onOpenDocument: (String) -> Unit,
    onMarkChosen: (String, String) -> Unit,
    /** When it is, as stored. Null for an event nobody has dated. */
    startsAt: String?,
    venue: String?,
    tags: List<com.mateof.passvault.server.Tag>,
    tagIds: List<String>,
    onCreateTag: (String, String) -> Unit,
    onDetailsSaved: (String?, String?, List<String>) -> Unit,
    /** Null shares the whole event; a set shares exactly those tickets. */
    onShare: (Set<String>?) -> Unit,
    /** The same scope, written to a file instead of handed to a phone in the room. */
    onExport: (Set<String>?, String) -> Unit,
    onDeleteEvent: () -> Unit,
    onDeleteTickets: (Set<String>) -> Unit,
) {
    var confirmingEventDelete by remember { mutableStateOf(false) }
    var confirmingTicketDelete by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var editingDetails by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var sharingWith by remember { mutableStateOf(false) }
    val sharing: com.mateof.passvault.ui.groups.SharingViewModel = hiltViewModel()
    val sharingState by sharing.state.collectAsStateWithLifecycle()
    // Null while browsing. Entering selection is its own act rather than a long press, because a
    // long press on a ticket is undiscoverable and this is the screen where somebody arrives
    // meaning to hand two seats to a friend.
    var selection by remember { mutableStateOf<Set<String>?>(null) }

    if (confirmingEventDelete) {
        AlertDialog(
            onDismissRequest = { confirmingEventDelete = false },
            title = { Text(stringResource(R.string.event_delete_action)) },
            text = { Text(stringResource(R.string.event_delete_confirm_text, title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingEventDelete = false
                    onDeleteEvent()
                }) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingEventDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (confirmingTicketDelete) {
        val chosen = selection.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmingTicketDelete = false },
            title = {
                Text(pluralStringResource(R.plurals.tickets_delete_confirm, chosen.size, chosen.size))
            },
            text = { Text(stringResource(R.string.tickets_delete_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingTicketDelete = false
                    selection = null
                    onDeleteTickets(chosen)
                }) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingTicketDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (exporting) {
        com.mateof.passvault.ui.wallet.ExportDialog(
            ticketCount = selection?.size ?: tickets.size,
            onDismiss = { exporting = false },
            onExport = { password ->
                onExport(selection, password)
                exporting = false
            },
        )
    }

    if (sharingWith) {
        com.mateof.passvault.ui.groups.ShareWithDialog(
            access = sharingState.access,
            groups = sharingState.groups,
            eventPassword = sharingState.eventPassword,
            onEventPasswordChanged = { sharing.setEventPassword(eventId, it) },
            serverPassword = sharingState.serverPassword,
            onChangeServerPassword = { sharing.changeServerPassword(eventId, it) },
            pendingEmail = sharingState.pendingEmail,
            addressKnown = sharingState.addressKnown,
            failure = sharingState.failure,
            claimed = sharingState.claimed,
            onClaim = { sharing.claim(eventId) },
            onEmailChanged = { sharing.setPendingEmail(eventId, it) },
            onShareWithGroup = { sharing.shareWithGroup(eventId, it) },
            onShareWithPerson = { sharing.shareWithPerson(eventId) },
            onRevoke = { sharing.revoke(eventId, it) },
            documents = documents,
            blockedDocuments = sharingState.blockedDocuments,
            onDocumentShared = { id, shared -> sharing.setDocumentShared(id, shared) },
            onDismiss = { sharingWith = false },
        )
    }

    if (editingDetails) {
        com.mateof.passvault.ui.wallet.EventDetailsDialog(
            startsAt = startsAt,
            venue = venue,
            tags = tags,
            chosenTagIds = tagIds,
            onDismiss = { editingDetails = false },
            onCreateTag = onCreateTag,
            onSave = { when_, where, chosen ->
                editingDetails = false
                onDetailsSaved(when_, where, chosen)
            },
        )
    }

    if (picking) {
        com.mateof.passvault.ui.wallet.MarkPickerDialog(
            eventId = eventId,
            icon = icon,
            colour = colour,
            onDismiss = { picking = false },
            onChosen = { chosenIcon, chosenColour ->
                onMarkChosen(chosenIcon, chosenColour)
                picking = false
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // The mark sits in the title bar, which is also where it is changed from: it is
                // the one place on this screen where the event is being named rather than its
                // tickets listed.
                title = {
                    val chosen = selection
                    if (chosen == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            com.mateof.passvault.ui.wallet.EventMark(
                                eventId = eventId,
                                icon = icon,
                                colour = colour,
                                size = 28.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                            // One line, cut with an ellipsis. Six icons once squeezed this into
                            // a vertical ribbon of single letters.
                            Text(
                                text = title,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text(pluralStringResource(R.plurals.tickets_selected, chosen.size, chosen.size))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (selection == null) {
                        IconButton(onClick = {
                            sharing.load(eventId)
                            // Snapshot which originals are blocked, so the panel's switches start
                            // from the truth rather than all-on.
                            sharing.refreshDocumentSharing(documents.map { it.id })
                            sharingWith = true
                        }) {
                            Icon(
                                Icons.Filled.Group,
                                contentDescription = stringResource(R.string.sharing_title),
                            )
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.action_more),
                                )
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.event_when)) },
                                    leadingIcon = { Icon(Icons.Filled.CalendarToday, null) },
                                    onClick = { menuOpen = false; editingDetails = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.event_mark_title)) },
                                    leadingIcon = { Icon(Icons.Filled.Palette, null) },
                                    onClick = { menuOpen = false; picking = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_select)) },
                                    leadingIcon = { Icon(Icons.Filled.Checklist, null) },
                                    onClick = { menuOpen = false; selection = emptySet() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_share)) },
                                    leadingIcon = { Icon(Icons.Filled.Share, null) },
                                    onClick = { menuOpen = false; onShare(null) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_export)) },
                                    leadingIcon = { Icon(Icons.Filled.Save, null) },
                                    onClick = { menuOpen = false; exporting = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.event_delete_action)) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                    onClick = { menuOpen = false; confirmingEventDelete = true },
                                )
                            }
                        }
                    } else {
                        // Sharing nothing is not a transfer, so the action waits until something
                        // is picked rather than starting one that would hand over an event and no
                        // tickets.
                        IconButton(
                            onClick = { selection?.let(onShare) },
                            enabled = selection?.isNotEmpty() == true,
                        ) {
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = stringResource(R.string.action_share),
                            )
                        }
                        IconButton(
                            onClick = { exporting = true },
                            enabled = selection?.isNotEmpty() == true,
                        ) {
                            Icon(
                                Icons.Filled.Save,
                                contentDescription = stringResource(R.string.action_export),
                            )
                        }
                        IconButton(
                            onClick = { confirmingTicketDelete = true },
                            enabled = selection?.isNotEmpty() == true,
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                            )
                        }
                        IconButton(onClick = { selection = null }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_cancel),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.Column(modifier = Modifier.padding(padding)) {
            // The event wears its labels where its owner put them, above the tickets. Tapping
            // any of them opens the same editor the calendar icon does — a label seen is a
            // label somebody may want to change.
            if (tagIds.isNotEmpty()) {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement =
                        androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    for (tagId in tagIds) {
                        tags.firstOrNull { it.id == tagId }?.let { tag ->
                            com.mateof.passvault.ui.tags.TagChip(
                                name = tag.name,
                                colour = tag.colour,
                                onClick = { editingDetails = true },
                            )
                        }
                    }
                }
            }
        WalletScreen(
            state = WalletUiState(tickets = tickets),
            onTicketClick = onTicketClick,
            modifier = Modifier,
            // Hidden while choosing: the annex is not a ticket and cannot be handed over as one.
            documents = if (selection == null) documents else emptyList(),
            onOpenDocument = onOpenDocument,
            selected = selection,
            onToggleSelection = { id ->
                selection = selection?.let { if (id in it) it - id else it + id }
            },
        )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerPane(
    onMenu: () -> Unit,
    sharingEventId: String? = null,
    sharingEventName: String? = null,
    viewModel: com.mateof.passvault.ui.server.ServerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Built from the activity context, because the credential sheet is a system dialog that
    // has to attach to a window rather than to the application.
    val passkeys = remember(context) { com.mateof.passvault.server.Passkeys(context) }

    // On arrival, not only on the button. A kept session means this usually finds the wallet
    // already up to date, which is the point: the button is for the moment somebody is standing
    // at a turnstile and will not wait for a schedule.
    LaunchedEffect(Unit) {
        viewModel.syncIfPossible()
        // The list of open sessions is fetched when the screen appears rather than watched:
        // sessions change when somebody signs in, which is not often enough to hold a request
        // open for.
        viewModel.loadSessions()
        // And the authenticators, so a returning user sees "you already have two-factor" rather
        // than an offer to turn on what is already on.
        viewModel.loadTotp()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.server_title)) },
                navigationIcon = {
                    IconButton(onClick = onMenu) {
                        Icon(
                            Icons.Filled.Menu,
                            contentDescription = stringResource(R.string.action_menu),
                        )
                    }
                },
            )
        },
    ) { padding ->
        com.mateof.passvault.ui.server.ServerScreen(
            state = state,
            onAddressChange = viewModel::setAddress,
            onConnect = viewModel::connect,
            onSignIn = viewModel::signIn,
            onSecondFactor = viewModel::submitSecondFactor,
            onUnlock = viewModel::unlockVault,
            onSync = viewModel::sync,
            onForget = viewModel::forget,
            onPasskeySignIn = { viewModel.signInWithPasskey(passkeys) },
            onAddPasskey = { viewModel.addPasskey(passkeys, android.os.Build.MODEL ?: "Android") },
            onEnrolTotp = viewModel::enrolTotp,
            onConfirmTotp = { code, label -> viewModel.confirmTotp(code, label) },
            onRemoveTotp = viewModel::removeTotp,
            onSignOut = viewModel::signOut,
            // Fired, not asked about first. `resolveActivity` needs a `<queries>` declaration
            // since Android 11 and returns null without one even when three browsers are
            // installed — the button that "did nothing" was this check refusing to believe in
            // them. Starting the intent and catching the miss asks the only party that knows.
            onOpenAdmin = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(viewModel.adminUrl())))
                }
            },
            onOpenUri = { uri ->
                // Whatever registered for `otpauth:` — Google Authenticator, Microsoft
                // Authenticator, Aegis, a password manager. If nothing did, the key is on
                // screen to be typed rather than the tap doing nothing.
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
            },
            sharingEventName = sharingEventName,
            modifier = Modifier.padding(padding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentPane(
    state: com.mateof.passvault.ui.document.DocumentViewState,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.document_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        com.mateof.passvault.ui.document.DocumentScreen(state, Modifier.padding(padding))
    }
}

/**
 * Who you are on the server: your name, your open sessions, and the way out.
 *
 * Split off the server screen, which had grown into connection, synchronisation, second factors,
 * a username and a session list in one column. The server screen keeps the connection; this
 * keeps the identity. Both read the same view model because they describe the same account.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfilePane(
    onMenu: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: com.mateof.passvault.ui.server.ServerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadSessions() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_title)) },
                navigationIcon = {
                    IconButton(onClick = onMenu) {
                        Icon(
                            Icons.Filled.Menu,
                            contentDescription = stringResource(R.string.action_menu),
                        )
                    }
                },
            )
        },
    ) { padding ->
        com.mateof.passvault.ui.server.ProfileScreen(
            state = state,
            onHandleChanged = viewModel::checkHandle,
            onSaveHandle = viewModel::saveHandle,
            onRevokeSession = viewModel::revokeSession,
            onSignOut = viewModel::signOut,
            onDeleteAccount = { secret -> viewModel.deleteAccount(secret, onDeleted = onDeleted) },
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * How the app behaves: for now, which language it speaks.
 *
 * Its own screen rather than a corner of another, because settings accumulate — and the first
 * one, language, exists for the phone set to English whose owner reads Galician.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPane(
    onMenu: () -> Unit,
    viewModel: com.mateof.passvault.ui.server.ServerViewModel = hiltViewModel(),
) {
    val activity = LocalContext.current as? android.app.Activity

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onMenu) {
                        Icon(
                            Icons.Filled.Menu,
                            contentDescription = stringResource(R.string.action_menu),
                        )
                    }
                },
            )
        },
    ) { padding ->
        com.mateof.passvault.ui.server.SettingsScreen(
            currentLocale = viewModel.uiLocale(),
            onLocaleChosen = { tag ->
                viewModel.setUiLocale(tag)
                // The platform's own way of reloading every string. Anything subtler leaves
                // half the screens in the old language until they happen to recompose.
                activity?.recreate()
            },
            deviceName = viewModel.deviceName(),
            onDeviceNameSaved = viewModel::setDeviceName,
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * Labels, which are the reader's own words for their own events.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagsPane(
    onMenu: () -> Unit,
    viewModel: com.mateof.passvault.ui.tags.TagsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tags_title)) },
                navigationIcon = {
                    IconButton(onClick = onMenu) {
                        Icon(
                            Icons.Filled.Menu,
                            contentDescription = stringResource(R.string.action_menu),
                        )
                    }
                },
            )
        },
    ) { padding ->
        com.mateof.passvault.ui.tags.TagsScreen(
            state = state,
            onCreate = viewModel::create,
            onUpdate = viewModel::update,
            onDelete = viewModel::delete,
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * What needs an answer.
 *
 * Sharing offers an event rather than putting it in a wallet unasked, so without this screen an
 * Android user cannot accept anything anybody shares with them. Synchronising happens on the way
 * out of it: the tickets are the reason for saying yes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoticesPane(
    onMenu: () -> Unit,
    viewModel: com.mateof.passvault.ui.notices.NoticesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notices_title)) },
                navigationIcon = {
                    IconButton(onClick = onMenu) {
                        Icon(
                            Icons.Filled.Menu,
                            contentDescription = stringResource(R.string.action_menu),
                        )
                    }
                },
            )
        },
    ) { padding ->
        com.mateof.passvault.ui.notices.NoticesScreen(
            state = state,
            onAccept = viewModel::accept,
            onDecline = viewModel::decline,
            onMarkRead = viewModel::markRead,
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * Groups, which are the people you share with more than once.
 *
 * On the main menu rather than buried in the server screen, where the first version put them: a
 * group is something you keep and edit, not a step in connecting to a server.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupsPane(
    onMenu: () -> Unit,
    viewModel: com.mateof.passvault.ui.groups.GroupsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.groups_title)) },
                navigationIcon = {
                    IconButton(onClick = onMenu) {
                        Icon(
                            Icons.Filled.Menu,
                            contentDescription = stringResource(R.string.action_menu),
                        )
                    }
                },
            )
        },
    ) { padding ->
        com.mateof.passvault.ui.groups.GroupsScreen(
            state = state,
            onOpen = viewModel::open,
            onCreate = viewModel::create,
            onRename = viewModel::rename,
            onDelete = viewModel::delete,
            onAddMember = viewModel::addMember,
            onRemoveMember = viewModel::removeMember,
            onEmailChanged = viewModel::setPendingEmail,
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * Choosing what to hand over, before any radio comes on.
 *
 * The drawer's share entry used to jump straight to offering the whole wallet, which read as
 * "this button gives everything away" — technically true and rightly alarming. Now the scope is
 * a decision made in daylight: everything, one event, or a handful of that event's tickets.
 * The event screen keeps its own share buttons for people already standing in one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharePickerPane(
    events: List<com.mateof.passvault.ui.wallet.EventRow>,
    tickets: List<com.mateof.passvault.ui.wallet.TicketRow>,
    documents: List<com.mateof.passvault.ui.wallet.DocumentRow>,
    onLoadEvent: (String?) -> Unit,
    loadPolicy: suspend (String) -> com.mateof.passvault.ui.wallet.SharePolicy,
    onBack: () -> Unit,
    onChosen: (ShareScope) -> Unit,
) {
    // Null while picking an event; an event while picking its tickets.
    var narrowing by remember { mutableStateOf<com.mateof.passvault.ui.wallet.EventRow?>(null) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    // What the creator lets this phone hand on: everything for its own events, only the lent seats
    // for one shared to it. Null while it is still being worked out, which keeps the buttons off.
    var policy by remember {
        mutableStateOf<com.mateof.passvault.ui.wallet.SharePolicy?>(null)
    }
    LaunchedEffect(narrowing?.id) {
        policy = narrowing?.let { loadPolicy(it.id) }
    }
    // The original files ticked to travel with the event. None by default: a file is an extra a
    // person opts into, not something a share drags along unasked.
    var pickedDocs by remember { mutableStateOf(setOf<String>()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.share_picker_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (narrowing != null) {
                            narrowing = null
                            picked = emptySet()
                            pickedDocs = emptySet()
                            onLoadEvent(null)
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            val chosenEvent = narrowing
            if (chosenEvent == null) {
                item {
                    Text(
                        text = stringResource(R.string.share_picker_explain),
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    androidx.compose.material3.Card(
                        onClick = { onChosen(ShareScope.Everything) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.share_picker_everything),
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(items = events, key = { row -> row.id }) { event ->
                    androidx.compose.material3.Card(
                        onClick = {
                            narrowing = event
                            picked = emptySet()
                            pickedDocs = emptySet()
                            onLoadEvent(event.id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            com.mateof.passvault.ui.wallet.EventMark(
                                eventId = event.id,
                                icon = event.icon,
                                colour = event.colour,
                                size = 36.dp,
                            )
                            Text(
                                text = event.name,
                                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            } else {
                val currentPolicy = policy
                // Only an event's creator hands the whole thing on. A member sees this card gone
                // and can pick, at most, the individual seats they were lent.
                if (currentPolicy?.canShareWholeEvent == true) {
                    // Inside one event: hand over all of it, or tick the seats that travel.
                    item {
                        androidx.compose.material3.Card(
                            onClick = {
                                onChosen(
                                    ShareScope.Event(
                                        chosenEvent.id,
                                        chosenEvent.name,
                                        pickedDocs.toList(),
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.share_picker_whole_event,
                                    chosenEvent.name,
                                ),
                                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                } else if (currentPolicy != null && currentPolicy.permittedTicketIds?.isEmpty() == true) {
                    // Nothing here is theirs to share, and saying so beats a screen of disabled rows.
                    item {
                        Text(
                            text = stringResource(R.string.share_picker_not_allowed),
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }

                // The original files, ticked to travel or left behind. Only when this phone may
                // hand on the whole event — the files belong to it, not to a single lent seat.
                if (documents.isNotEmpty() && currentPolicy?.canShareWholeEvent == true) {
                    item {
                        Text(
                            text = stringResource(R.string.share_picker_documents),
                            style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(items = documents, key = { row -> "doc:${row.id}" }) { document ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = document.id in pickedDocs,
                                onCheckedChange = {
                                    pickedDocs =
                                        if (document.id in pickedDocs) pickedDocs - document.id
                                        else pickedDocs + document.id
                                },
                            )
                            Text(
                                text = stringResource(
                                    R.string.share_picker_document,
                                    document.pageCount,
                                    android.text.format.Formatter.formatShortFileSize(
                                        androidx.compose.ui.platform.LocalContext.current,
                                        document.byteCount.toLong(),
                                    ),
                                ),
                            )
                        }
                    }
                }

                // Only the seats this phone may hand on: all of them for its own event, the lent
                // ones for a shared event, and none until the policy is known.
                val shareableTickets = when {
                    currentPolicy == null -> emptyList()
                    currentPolicy.canShareWholeEvent -> tickets
                    else -> tickets.filter { currentPolicy.permittedTicketIds?.contains(it.id) == true }
                }
                items(items = shareableTickets, key = { row -> row.id }) { ticket ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = ticket.id in picked,
                            onCheckedChange = {
                                picked =
                                    if (ticket.id in picked) picked - ticket.id else picked + ticket.id
                            },
                        )
                        Text(
                            text = listOfNotNull(
                                ticket.label.ifBlank { null },
                                ticket.seat,
                            ).joinToString(" · ").ifBlank { ticket.eventName },
                        )
                    }
                }
                item {
                    androidx.compose.material3.Button(
                        onClick = {
                            onChosen(
                                ShareScope.Tickets(
                                    chosenEvent.id,
                                    chosenEvent.name,
                                    picked.toList(),
                                    pickedDocs.toList(),
                                ),
                            )
                        },
                        // Sharing nothing is not a transfer: the button waits for a choice.
                        enabled = picked.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            pluralStringResource(
                                R.plurals.share_picker_chosen,
                                picked.size,
                                picked.size,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The fork: give or take.
 *
 * The first design had no fork — both phones did everything at once, and two people each waited
 * for the other's phone to make the first move. Saying the roles out loud costs one tap and
 * removes the mutual waiting, which was most of what "sharing does not work" turned out to be.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareChooserPane(
    onMenu: () -> Unit,
    onSend: () -> Unit,
    onReceive: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_share)) },
                navigationIcon = {
                    IconButton(onClick = onMenu) {
                        Icon(
                            Icons.Filled.Menu,
                            contentDescription = stringResource(R.string.action_menu),
                        )
                    }
                },
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.material3.Card(
                onClick = onSend,
                modifier = Modifier.fillMaxWidth(),
            ) {
                androidx.compose.foundation.layout.Column(Modifier.padding(20.dp)) {
                    Text(
                        text = stringResource(R.string.share_send_card),
                        style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.share_send_card_hint),
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            androidx.compose.material3.Card(
                onClick = onReceive,
                modifier = Modifier.fillMaxWidth(),
            ) {
                androidx.compose.foundation.layout.Column(Modifier.padding(20.dp)) {
                    Text(
                        text = stringResource(R.string.share_receive_card),
                        style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.share_receive_card_hint),
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The giving side.
 *
 * The permission is asked for here, at the moment the user chose to share, rather than at
 * startup. Android 13 replaced the location permission this needs with `NEARBY_WIFI_DEVICES`;
 * on anything older the manifest's scoped location entry covers it, so there is nothing to
 * request. The NFC reader is armed on this side only: the receiver wears the tag, the sender
 * touches it — two readers and two tags was a race nobody could win.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSendPane(
    scope: ShareScope,
    onBack: () -> Unit,
    viewModel: ShareViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var granted by remember {
        mutableStateOf(
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.NEARBY_WIFI_DEVICES,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    val request = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { allowed -> granted = allowed }

    LaunchedEffect(granted) {
        if (granted) {
            viewModel.startSending(scope)
        } else {
            request.launch(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    // Only while this screen is up. Reader mode takes NFC away from the rest of the system, and
    // an app that kept it after the user moved on would break every contactless payment.
    val activity = context as? android.app.Activity
    DisposableEffect(activity) {
        val reader = activity?.let { com.mateof.passvault.share.NfcReader(it) }
        reader?.start(
            onRead = { handover -> viewModel.connectTapped(handover) },
            onFailure = { viewModel.reportTapFailure(it) },
        )
        onDispose { reader?.stop() }
    }

    DisposableEffect(Unit) { onDispose { viewModel.stop() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.share_send_card)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        com.mateof.passvault.ui.share.ShareSendScreen(
            state = state,
            onConnect = viewModel::connect,
            onConnectManual = viewModel::connectManual,
            onDigitsMatch = viewModel::digitsMatch,
            onDigitsDiffer = viewModel::digitsDiffer,
            onDone = onBack,
            nfcDisabled = nfcIsOff(context),
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * The taking side: named, findable, waiting.
 *
 * No reader here — this phone wears the tag, through the card-emulation service, and the
 * listening socket is torn down with the screen: a phone that keeps announcing itself after
 * the user has moved on is a phone anybody in the café can dial.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareReceivePane(
    onBack: () -> Unit,
    viewModel: ShareViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var granted by remember {
        mutableStateOf(
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.NEARBY_WIFI_DEVICES,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    val request = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { allowed -> granted = allowed }

    LaunchedEffect(granted) {
        if (granted) {
            viewModel.startReceiving()
        } else {
            request.launch(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    DisposableEffect(Unit) { onDispose { viewModel.stop() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.share_receive_card)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        com.mateof.passvault.ui.share.ShareReceiveScreen(
            state = state,
            onDigitsMatch = viewModel::digitsMatch,
            onDigitsDiffer = viewModel::digitsDiffer,
            onDone = onBack,
            nfcDisabled = nfcIsOff(context),
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * Whether this phone has NFC hardware but it is switched off.
 *
 * The one state worth calling out on a share screen: a tap simply cannot fire, and the person
 * would otherwise keep touching two phones together wondering why nothing happens. A phone with no
 * NFC at all is not warned — the list and the typed address are the whole story there.
 */
private fun nfcIsOff(context: android.content.Context): Boolean {
    val adapter = android.nfc.NfcAdapter.getDefaultAdapter(context)
    return adapter != null && !adapter.isEnabled
}

/**
 * Asks for the password a shared file needs.
 *
 * Typed here, used once, never stored. Whoever forwards the file on has to pass the password
 * separately, which is the entire point: it is what keeps the barcode unreadable to every service
 * the file travels through on the way.
 */
@Composable
private fun ImportDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_title)) },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.import_password_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = password.isNotEmpty()) {
                Text(stringResource(R.string.action_confirm_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
