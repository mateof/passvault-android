package com.mateof.passvault.ui.wallet

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mateof.passvault.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import com.mateof.passvault.ui.theme.LocalSpacing
import com.mateof.passvault.ui.theme.LocalStatusColours
import com.mateof.passvault.ui.theme.Motion

/**
 * The wallet.
 *
 * Three decisions here are about performance rather than looks, and they are the ones that decide
 * whether a list of forty tickets scrolls at all:
 *
 *   * every model is `@Immutable`, so Compose can skip a card whose data has not changed;
 *   * `items` is keyed by ticket id, so confirming a claim animates one row instead of rebuilding
 *     the list, and scroll position survives a refresh;
 *   * the state is one already-derived object, so no formatting or filtering happens during
 *     composition — that work belongs to the view model, off the frame.
 */
@Immutable
data class TicketRow(
    val id: String,
    val label: String,
    val eventName: String,
    val seat: String?,
    val state: TicketState,
    val paymentLabel: String?,
)

/**
 * The states a ticket can be in, as the interface presents them.
 *
 * `Provisional` is not decoration. A claim made offline is not settled, and the interface has to
 * say so rather than showing the ticket as the user's and taking it back later — the single most
 * important user-facing consequence of the offline design.
 */
enum class TicketState { Provisional, Held, Free, Transferred }

@Immutable
data class WalletUiState(
    val tickets: List<TicketRow> = emptyList(),
    val isLoading: Boolean = false,
    val isLocked: Boolean = false,
)

@Composable
fun WalletScreen(
    state: WalletUiState,
    onTicketClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current

    when {
        state.isLocked -> Message(stringResource(R.string.vault_locked), modifier)
        state.tickets.isEmpty() && !state.isLoading ->
            Message(stringResource(R.string.wallet_empty), modifier)
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(spacing.medium),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            items(
                items = state.tickets,
                // A stable key is what lets a single row animate when its state changes, instead of
                // the list being rebuilt and the scroll position lost.
                key = { ticket -> ticket.id },
                // One content type, so the item pool is reused rather than reallocated per row.
                contentType = { "ticket" },
            ) { ticket ->
                TicketCard(ticket = ticket, onClick = onTicketClick)
            }
        }
    }
}

@Composable
private fun TicketCard(
    ticket: TicketRow,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    // A small scale on press. Through graphicsLayer rather than by changing the size, so it runs on
    // the render thread and skips layout and measurement entirely — the difference between a
    // gesture that keeps up with a finger and one that stutters on a long list.
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = Motion.quick(),
        label = "press",
    )

    Card(
        onClick = { onClick(ticket.id) },
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale },
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, pressedElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            StateDot(ticket.state)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.tight),
            ) {
                Text(
                    text = ticket.eventName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(ticket.label, ticket.seat).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (ticket.state == TicketState.Provisional) {
                    Text(
                        text = stringResource(R.string.claim_provisional),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            ticket.paymentLabel?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The state indicator.
 *
 * Two things this got wrong until the app was run on a device, neither of which compiling would
 * have shown:
 *
 *   * the colours came from the scheme, so dynamic colour flattened all four into the same grey;
 *   * `Free` used `surfaceVariant`, which under that scheme was the card background — an indicator
 *     that was simply invisible.
 *
 * Now the colours are fixed (see StatusColours) and `Free` is an outline rather than a fill, so it
 * reads against any surface. Shape carries the meaning alongside colour, for a colour-blind user
 * and for sunlight; the written label is what actually states it.
 */
@Composable
private fun StateDot(state: TicketState) {
    val status = LocalStatusColours.current
    val target = when (state) {
        TicketState.Held -> status.held
        TicketState.Provisional -> status.provisional
        TicketState.Free -> status.free
        TicketState.Transferred -> status.transferred
    }
    val colour by animateColorAsState(target, Motion.quick(), label = "state")

    val base = Modifier.size(12.dp)
    Box(
        modifier = when (state) {
            // An outline, so it is visible on any surface rather than only on a darker one.
            TicketState.Free -> base.border(BorderStroke(2.dp, colour), CircleShape)
            else -> base.background(colour, CircleShape)
        },
    )
}

@Composable
private fun Message(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(LocalSpacing.current.large),
        )
    }
}
