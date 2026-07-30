package com.mateof.passvault

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mateof.passvault.ui.ingest.IngestReviewScreen
import com.mateof.passvault.ui.ingest.IngestReviewState
import com.mateof.passvault.ui.ingest.ReviewRow
import com.mateof.passvault.ui.theme.Motion
import com.mateof.passvault.ui.theme.PassVaultTheme
import com.mateof.passvault.ui.ticket.TicketDetail
import com.mateof.passvault.ui.ticket.TicketDetailScreen
import com.mateof.passvault.ui.wallet.ImportOutcome
import com.mateof.passvault.ui.wallet.WalletScreen
import com.mateof.passvault.ui.wallet.WalletUiState
import com.mateof.passvault.ui.wallet.WalletViewModel
import dagger.hilt.android.AndroidEntryPoint

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

private sealed interface Screen {
    data object Wallet : Screen
    data class Ticket(val detail: TicketDetail) : Screen
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
    val proposal by viewModel.pendingProposal.collectAsStateWithLifecycle()
    val pendingArchive by viewModel.pendingArchive.collectAsStateWithLifecycle()
    var excluded by remember { mutableStateOf(emptySet<Int>()) }
    val snackbars = remember { SnackbarHostState() }
    val context = LocalContext.current

    // The system back gesture returns to the wallet rather than leaving the app, which is what a
    // user expects of a detail screen and what they do not get for free.
    BackHandler(enabled = screen is Screen.Ticket) { screen = Screen.Wallet }
    BackHandler(enabled = proposal != null) { viewModel.discardProposal() }

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
                        onConfirm = {
                            val chosen = pending.tickets
                                .filter { it.include != excluded.contains(it.index) }
                                .map { it.index }
                            excluded = emptySet()
                            // A placeholder name until the review screen asks for one. It was a
                            // bare Kotlin literal, which put an untranslated word on screen in
                            // every language.
                            viewModel.saveProposal(context.getString(R.string.ingest_event_default), chosen)
                        },
                    )
                } ?: WalletPane(
                    state = state,
                    onTicketClick = { ticketId ->
                        viewModel.openTicket(ticketId) { detail -> screen = Screen.Ticket(detail) }
                    },
                )
                is Screen.Ticket -> TicketPane(
                    detail = current.detail,
                    onBack = { screen = Screen.Wallet },
                )
            }
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletPane(
    state: WalletUiState,
    onTicketClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Collapses as the list scrolls, giving the content the whole screen once the user is reading
    // rather than navigating.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumTopAppBar(
                title = { Text(stringResource(R.string.wallet_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        WalletScreen(state = state, onTicketClick = onTicketClick, modifier = Modifier.padding(padding))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TicketPane(detail: TicketDetail, onBack: () -> Unit) {
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
        TicketDetailScreen(detail, Modifier.padding(padding))
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
