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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
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
import com.mateof.passvault.ui.share.ShareScreen
import com.mateof.passvault.ui.share.ShareViewModel
import com.mateof.passvault.ui.theme.Motion
import com.mateof.passvault.ui.theme.PassVaultTheme
import com.mateof.passvault.ui.ticket.TicketDetail
import com.mateof.passvault.ui.ticket.TicketDetailScreen
import com.mateof.passvault.ui.wallet.ImportOutcome
import com.mateof.passvault.ui.wallet.WalletScreen
import com.mateof.passvault.ui.wallet.WalletUiState
import com.mateof.passvault.ui.wallet.WalletViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
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
    data class Ticket(val detail: TicketDetail) : Screen
    data class Share(val scope: ShareScope) : Screen
    data class Document(val eventId: String) : Screen
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
    val proposal by viewModel.pendingProposal.collectAsStateWithLifecycle()
    val pendingArchive by viewModel.pendingArchive.collectAsStateWithLifecycle()
    val documentState by viewModel.document.collectAsStateWithLifecycle()
    val eventsState by viewModel.events.collectAsStateWithLifecycle()
    val eventTickets by viewModel.eventTickets.collectAsStateWithLifecycle()
    val eventDocuments by viewModel.eventDocuments.collectAsStateWithLifecycle()
    var excluded by remember { mutableStateOf(emptySet<Int>()) }
    val snackbars = remember { SnackbarHostState() }
    val context = LocalContext.current

    // The system back gesture returns to the wallet rather than leaving the app, which is what a
    // user expects of a detail screen and what they do not get for free.
    // Back from a ticket returns to its event, not to the wallet: that is where the user came
    // from, and jumping two levels loses their place in a list of forty.
    BackHandler(enabled = screen is Screen.Ticket || screen is Screen.Document) {
        val event = openEvent
        screen = if (event != null) Screen.Event(event.first, event.second) else Screen.Wallet
    }
    BackHandler(
        enabled = screen is Screen.Event ||
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
        is Screen.Share -> Destination.Share
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
            Destination.Share -> Screen.Share(ShareScope.Everything)
            Destination.Server -> Screen.Server
            Destination.Updates -> Screen.Updates
        }
    }

    // The gesture is enabled only on the wallet: a drawer that opens from the left edge of a
    // detail screen fights the system back gesture, and back is what the user means there.
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = screen is Screen.Wallet,
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
                    state = eventsState,
                    onEventClick = { id, name ->
                        viewModel.openEvent(id)
                        screen = Screen.Event(id, name)
                    },
                    onShare = { screen = Screen.Share(ShareScope.Everything) },
                    onImport = { pickFile.launch(IMPORTABLE_TYPES) },
                    onCapture = { startCapture() },
                    onMenu = openDrawer,
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
                        onTicketClick = { ticketId ->
                            viewModel.openTicket(ticketId) { detail -> screen = Screen.Ticket(detail) }
                        },
                        onOpenDocument = { documentId ->
                            viewModel.openDocument(documentId)
                            screen = Screen.Document(current.id)
                        },
                        onMarkChosen = { chosenIcon, chosenColour ->
                            viewModel.setEventMark(current.id, chosenIcon, chosenColour)
                        },
                        onShare = { chosen ->
                            screen = Screen.Share(
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
                    )
                }
                is Screen.Ticket -> TicketPane(
                    detail = current.detail,
                    onBack = { screen = Screen.Wallet },
                    onOpenDocument = {
                        eventDocuments.firstOrNull()?.let { document ->
                            viewModel.openDocument(document.id)
                            screen = Screen.Document(current.detail.eventId)
                        }
                    },
                )
                Screen.Server -> ServerPane(onBack = { screen = Screen.Wallet })
                Screen.Updates -> UpdatePane(onBack = { screen = Screen.Wallet })
                is Screen.Document -> DocumentPane(
                    state = documentState,
                    // Back to the event, not to the wallet. The annex is reached from inside an
                    // event now, and dropping somebody two levels loses their place in a list.
                    onBack = {
                        val event = openEvent
                        screen = if (event != null) Screen.Event(event.first, event.second)
                        else Screen.Wallet
                    },
                )
                is Screen.Share -> SharePane(
                    scope = current.scope,
                    onBack = { screen = Screen.Wallet },
                )
            }
        }
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
    onBack: () -> Unit,
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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
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
    onEventClick: (String, String) -> Unit,
    onShare: () -> Unit,
    onImport: () -> Unit,
    onCapture: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Collapses as the list scrolls, giving the content the whole screen once the user is reading
    // rather than navigating.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
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
        },
    ) { padding ->
        com.mateof.passvault.ui.wallet.EventsScreen(
            state = state,
            onEventClick = { id ->
                onEventClick(id, state.events.firstOrNull { it.id == id }?.name.orEmpty())
            },
            modifier = Modifier.padding(padding),
        )
    }
}

/** One event, with its tickets. */
@OptIn(ExperimentalMaterial3Api::class)
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
    /** Null shares the whole event; a set shares exactly those tickets. */
    onShare: (Set<String>?) -> Unit,
    /** The same scope, written to a file instead of handed to a phone in the room. */
    onExport: (Set<String>?, String) -> Unit,
) {
    var exporting by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    // Null while browsing. Entering selection is its own act rather than a long press, because a
    // long press on a ticket is undiscoverable and this is the screen where somebody arrives
    // meaning to hand two seats to a friend.
    var selection by remember { mutableStateOf<Set<String>?>(null) }

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
                            Text(title)
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
                        IconButton(onClick = { picking = true }) {
                            Icon(
                                Icons.Filled.Palette,
                                contentDescription = stringResource(R.string.event_mark_title),
                            )
                        }
                        IconButton(onClick = { selection = emptySet() }) {
                            Icon(
                                Icons.Filled.Checklist,
                                contentDescription = stringResource(R.string.action_select),
                            )
                        }
                        IconButton(onClick = { onShare(null) }) {
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = stringResource(R.string.action_share),
                            )
                        }
                        IconButton(onClick = { exporting = true }) {
                            Icon(
                                Icons.Filled.Save,
                                contentDescription = stringResource(R.string.action_export),
                            )
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
        WalletScreen(
            state = WalletUiState(tickets = tickets),
            onTicketClick = onTicketClick,
            modifier = Modifier.padding(padding),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerPane(
    onBack: () -> Unit,
    sharingEventId: String? = null,
    sharingEventName: String? = null,
    viewModel: com.mateof.passvault.ui.server.ServerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Built from the activity context, because the credential sheet is a system dialog that
    // has to attach to a window rather than to the application.
    val passkeys = remember(context) { com.mateof.passvault.server.Passkeys(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.server_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
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
            onCreateGroup = viewModel::createGroup,
            onAddMember = viewModel::addMember,
            onShareEvent = { groupId ->
                sharingEventId?.let { viewModel.shareEventWithGroup(it, groupId) }
            },
            onPasskeySignIn = { viewModel.signInWithPasskey(passkeys) },
            onAddPasskey = { viewModel.addPasskey(passkeys, android.os.Build.MODEL ?: "Android") },
            onEnrolTotp = viewModel::enrolTotp,
            onConfirmTotp = viewModel::confirmTotp,
            onOpenUri = { uri ->
                // Whatever registered for `otpauth:` — Google Authenticator, Microsoft
                // Authenticator, Aegis, a password manager. If nothing did, the key is on
                // screen to be typed rather than the tap doing nothing.
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TicketPane(detail: TicketDetail, onBack: () -> Unit, onOpenDocument: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail.eventName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        TicketDetailScreen(detail, onOpenDocument, Modifier.padding(padding))
    }
}

/**
 * Passing tickets to a phone in the same room.
 *
 * The permission is asked for here, at the moment the user chose to share, rather than at startup.
 * Android 13 replaced the location permission this needs with `NEARBY_WIFI_DEVICES`; on anything
 * older the manifest's scoped location entry covers it, so there is nothing to request.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharePane(
    scope: ShareScope,
    onBack: () -> Unit,
    viewModel: ShareViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val deviceName = remember { android.os.Build.MODEL ?: "PassVault" }

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

    // Before anything is advertised, so a phone never offers more than the screen said it would.
    LaunchedEffect(scope) { viewModel.offer(scope) }

    LaunchedEffect(granted) {
        if (granted) {
            viewModel.start(deviceName)
        } else {
            request.launch(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    /**
     * Listening for the other phone to be held against this one.
     *
     * Both phones do this and both also publish a tag, so neither user has to be told which of
     * them is "the reader" — whichever pair of hands moves first wins the race, and the other
     * side's reader finds nothing because the transfer has already started.
     *
     * Only while this screen is up. Reader mode takes NFC away from the rest of the system, and an
     * app that kept it after the user moved on would break every contactless payment on the phone.
     */
    val activity = context as? android.app.Activity
    DisposableEffect(activity) {
        val reader = activity?.let { com.mateof.passvault.share.NfcReader(it) }
        reader?.start(
            onRead = { handover -> viewModel.connectTapped(handover) },
            onFailure = { viewModel.reportTapFailure(it) },
        )
        onDispose { reader?.stop() }
    }

    // Leaving the screen tears down the advertisement and the listening socket. A phone that keeps
    // announcing itself after the user has moved on is a phone anybody in the café can dial.
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_share)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        ShareScreen(
            state = state,
            onConnect = viewModel::connect,
            onDigitsMatch = viewModel::digitsMatch,
            onDigitsDiffer = viewModel::digitsDiffer,
            onDone = onBack,
            modifier = Modifier.padding(padding),
        )
    }
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
