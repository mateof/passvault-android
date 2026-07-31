package com.mateof.passvault.ui.wallet

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mateof.passvault.R
import com.mateof.passvault.ui.theme.LocalSpacing
import com.mateof.passvault.ui.theme.LocalStatusColours
import com.mateof.passvault.ui.theme.Motion

/**
 * The wallet, which lists events rather than loose tickets.
 *
 * Tickets belong to an event and are only meaningful inside one: forty rows called "Grada A 14-A"
 * with no grouping is a list nobody can read, and it makes the thing you actually share — an event,
 * with everybody's tickets in it — impossible to name on screen.
 *
 * Each row says how many tickets are inside and, when it applies, how many are still waiting to be
 * confirmed. That second number is the one worth surfacing at the top level: a provisional claim is
 * not settled, and finding that out at the gate is the failure the whole offline design exists to
 * prevent.
 */
@Immutable
data class EventRow(
    val id: String,
    val name: String,
    val venue: String?,
    val startsAt: String?,
    val ticketCount: Int,
    val provisionalCount: Int,
)

@Immutable
data class EventsUiState(
    val events: List<EventRow> = emptyList(),
    val isLoading: Boolean = false,
)

@Composable
fun EventsScreen(
    state: EventsUiState,
    onEventClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current

    when {
        state.events.isEmpty() && !state.isLoading ->
            Message(stringResource(R.string.wallet_empty), modifier)
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(spacing.medium),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            items(state.events, key = { it.id }, contentType = { "event" }) { event ->
                EventCard(event = event, onClick = onEventClick)
            }
        }
    }
}

@Composable
private fun EventCard(event: EventRow, onClick: (String) -> Unit) {
    val spacing = LocalSpacing.current
    val status = LocalStatusColours.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    // Through graphicsLayer so the press runs on the render thread and skips layout entirely —
    // the difference between a gesture that keeps up with a finger and one that stutters.
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = Motion.quick(),
        label = "press",
    )

    Card(
        onClick = { onClick(event.id) },
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(spacing.large),
            verticalArrangement = Arrangement.spacedBy(spacing.tight),
        ) {
            Text(
                text = event.name,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            listOfNotNull(event.startsAt, event.venue)
                .takeIf { it.isNotEmpty() }
                ?.let { parts ->
                    Text(
                        text = parts.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

            Row(
                modifier = Modifier.padding(top = spacing.small),
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = pluralStringResource(
                        R.plurals.event_ticket_count,
                        event.ticketCount,
                        event.ticketCount,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (event.provisionalCount > 0) {
                    // Surfaced at the top level, not only inside. A claim made offline is not
                    // settled, and the whole point of the design is that the app says so before
                    // somebody is standing at a turnstile.
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(status.provisional, CircleShape),
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.event_provisional_count,
                            event.provisionalCount,
                            event.provisionalCount,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = status.provisional,
                    )
                }
            }
        }
    }
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
