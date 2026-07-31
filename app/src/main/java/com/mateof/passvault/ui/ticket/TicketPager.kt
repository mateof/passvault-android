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
            )
        }
    }
}

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
) {
    var detail by remember(ticketId) { mutableStateOf<TicketDetail?>(null) }
    LaunchedEffect(ticketId) { detail = load(ticketId) }

    detail?.let { TicketDetailScreen(it, onOpenDocument) }
}
