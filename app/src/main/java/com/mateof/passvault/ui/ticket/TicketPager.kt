package com.mateof.passvault.ui.ticket

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mateof.passvault.R
import com.mateof.passvault.ui.wallet.TicketRow
import kotlinx.coroutines.launch

/**
 * A ticket, with the rest of the event's tickets either side of it.
 *
 * One barcode fills the screen, which is right at the gate and awkward everywhere else: four people
 * arrive with four tickets to one event, and reading the next one meant going back to the list and
 * picking again — with somebody waiting behind you. They are a stack of cards, so they behave like
 * one, and the neighbours are a swipe away.
 *
 * Neither end wraps. A stack has a first and a last, and sliding off the end back round to the
 * beginning is how somebody loses track of which ones they have already shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketPager(
    ticketId: String,
    tickets: List<TicketRow>,
    load: suspend (String) -> TicketDetail?,
    onBack: () -> Unit,
    onOpenDocument: (String) -> Unit,
    onReturn: (String) -> Unit = {},
    /** A creator control on the ticket. Suspends until the server answers, so the page reloads
     *  onto the new state rather than showing a stale one. */
    onControl: suspend (String, TicketControl) -> Unit = { _, _ -> },
    /** Records who has paid and who may see it: (ticketId, state, visibility). */
    onSetPayment: suspend (String, String, String) -> Unit = { _, _, _ -> },
    /** Sets when the code opens: (ticketId, absolute instant, hours-before) — one or the other. */
    onSetVisibleFrom: suspend (String, String?, Int?) -> Unit = { _, _, _ -> },
    /** Gives the seat to an account by address: (ticketId, email). */
    onAssign: suspend (String, String) -> Unit = { _, _ -> },
    /** Takes an assignment back: (ticketId). */
    onUnassign: suspend (String) -> Unit = {},
    /** Downloads the held code from the server, which is what marks it seen. */
    onDownloadBarcode: suspend (String) -> com.mateof.passvault.server.ServerBarcode? = { null },
    modifier: Modifier = Modifier,
) {
    val pager = rememberPagerState(
        // The one that was tapped, or the first if it is not in the list — which is the case for a
        // ticket reached from a share rather than from its event.
        initialPage = tickets.indexOfFirst { it.id == ticketId }.coerceAtLeast(0),
        // Never zero: a pager with no pages cannot show the one ticket we do have.
        pageCount = { maxOf(tickets.size, 1) },
    )

    // A safety net for the case where the event's tickets are still being queried as this opens,
    // which leaves `initialPage` computed against an empty list. Once only, and only while the
    // reader has not moved, so it cannot fight a swipe already under way.
    var aligned by remember(ticketId) { mutableStateOf(false) }
    LaunchedEffect(ticketId, tickets) {
        if (!aligned) {
            val target = tickets.indexOfFirst { it.id == ticketId }
            if (target >= 0) {
                pager.scrollToPage(target)
                aligned = true
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(tickets.getOrNull(pager.currentPage)?.eventName.orEmpty(), maxLines = 1)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (tickets.size > 1) {
                        // Where you are in the stack. Without it a swipe changes the barcode and
                        // little else, which reads as the screen having flickered.
                        //
                        // At the end of the bar rather than under the title: a two-line title is
                        // taller than a small top bar, so the second line was being clipped away.
                        Text(
                            text = stringResource(
                                R.string.ticket_position,
                                pager.currentPage + 1,
                                tickets.size,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        HorizontalPager(
            state = pager,
            modifier = Modifier.padding(padding),
            // Keyed by ticket, so a page keeps what it loaded when the list around it changes — a
            // sync arriving mid-swipe would otherwise re-decrypt whatever is on screen.
            key = { page -> tickets.getOrNull(page)?.id ?: ticketId },
        ) { page ->
            val shown = tickets.getOrNull(page)?.id ?: ticketId
            TicketPage(
                ticketId = shown,
                load = load,
                onOpenDocument = { onOpenDocument(shown) },
                onReturn = { onReturn(shown) },
                onControl = { control -> onControl(shown, control) },
                onSetPayment = { state, visibility -> onSetPayment(shown, state, visibility) },
                onSetVisibleFrom = { from, hrs -> onSetVisibleFrom(shown, from, hrs) },
                onAssign = { email -> onAssign(shown, email) },
                onUnassign = { onUnassign(shown) },
                onDownloadBarcode = { onDownloadBarcode(shown) },
            )
        }
    }
}

/** The creator's controls, as one closed set the pager can hand to the server. */
enum class TicketControl { Block, Unblock, ToggleShareOn, ToggleShareOff, VisibleDayBefore, ClearVisibility }

/**
 * One ticket inside the pager, which loads itself.
 *
 * Per page rather than all at once: an event can hold forty, and decrypting forty barcodes to show
 * one is work nobody asked for. The pager keeps the pages either side of the current one composed,
 * so a neighbour is loaded before it is wanted and the blank below is seen only on the first.
 */
@Composable
private fun TicketPage(
    ticketId: String,
    load: suspend (String) -> TicketDetail?,
    onOpenDocument: () -> Unit,
    onReturn: () -> Unit,
    onControl: suspend (TicketControl) -> Unit,
    onSetPayment: suspend (String, String) -> Unit,
    onSetVisibleFrom: suspend (String?, Int?) -> Unit,
    onAssign: suspend (String) -> Unit,
    onUnassign: suspend () -> Unit,
    onDownloadBarcode: suspend () -> com.mateof.passvault.server.ServerBarcode?,
) {
    var detail by remember(ticketId) { mutableStateOf<TicketDetail?>(null) }
    // Bumped after a control acts, so the page reloads onto the state the server now reports.
    var reload by remember(ticketId) { mutableStateOf(0) }
    LaunchedEffect(ticketId, reload) { detail = load(ticketId) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val act: (TicketControl) -> Unit = { control ->
        scope.launch {
            onControl(control)
            reload += 1
        }
    }

    detail?.let {
        TicketDetailScreen(
            detail = it,
            onOpenDocument = onOpenDocument,
            onReturn = onReturn,
            onBlock = { act(TicketControl.Block) },
            onUnblock = { act(TicketControl.Unblock) },
            onToggleShare = { on ->
                act(if (on) TicketControl.ToggleShareOn else TicketControl.ToggleShareOff)
            },
            onVisibleDayBefore = { act(TicketControl.VisibleDayBefore) },
            onClearVisibility = { act(TicketControl.ClearVisibility) },
            onSetPayment = { state, visibility ->
                scope.launch {
                    onSetPayment(state, visibility)
                    reload += 1
                }
            },
            onSetVisibleFrom = { from, hrs ->
                scope.launch {
                    onSetVisibleFrom(from, hrs)
                    reload += 1
                }
            },
            onAssign = { email ->
                scope.launch {
                    onAssign(email)
                    reload += 1
                }
            },
            onUnassign = {
                scope.launch {
                    onUnassign()
                    reload += 1
                }
            },
            // No reload: the screen holds the downloaded code itself, and reloading would drop it.
            onDownloadBarcode = { onDownloadBarcode() },
        )
    }
}
