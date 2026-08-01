package com.mateof.passvault.ui.wallet

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
 * Each row leads with its mark, because that is what the eye finds before it reads anything. Then
 * the name, then where and when with an icon each, then how many tickets are inside and — when it
 * applies — how many are still waiting to be confirmed. That last number is the one worth
 * surfacing at the top level: a provisional claim is not settled, and finding that out at the gate
 * is the failure the whole offline design exists to prevent.
 */
@Immutable
data class EventRow(
    val id: String,
    val name: String,
    val venue: String?,
    /** The full instant as stored, so the list can sort by it and say whether it has passed. */
    val startsAt: String?,
    val ticketCount: Int,
    val provisionalCount: Int,
    /** Null until somebody chooses one; the mark is then derived from the identifier. */
    val icon: String? = null,
    val colour: String? = null,
    /** The reader's own labels, which live on the server and are empty without one. */
    val tagIds: List<String> = emptyList(),
) {
    /**
     * Whether it is over.
     *
     * A day's grace, because a concert at nine last night is still the thing in your pocket on
     * the way home and should not have sunk to the bottom of the wallet before you get there.
     * An event with no date is never past: "we do not know when" is not "it already happened".
     */
    fun isPast(nowMillis: Long): Boolean {
        val at = startsAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
        return at != null && at < nowMillis - 86_400_000L
    }
}

/** How the wallet is sorted. Date first, because that is what a wallet is usually asked. */
enum class EventOrder { Date, Name, Added }

@Immutable
data class EventsUiState(
    val events: List<EventRow> = emptyList(),
    val isLoading: Boolean = false,
    /** Labels this account has, for filtering. Empty without a server, which is the usual case. */
    val tags: List<com.mateof.passvault.server.Tag> = emptyList(),
)

@Composable
fun EventsScreen(
    state: EventsUiState,
    onEventClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    var search by rememberSaveable { mutableStateOf("") }
    var order by rememberSaveable { mutableStateOf(EventOrder.Date) }
    var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var showPast by rememberSaveable { mutableStateOf(true) }

    // Read once per composition rather than per row: forty rows each asking the clock what time
    // it is would each get a slightly different answer.
    val now = remember(state.events) { System.currentTimeMillis() }

    val shown = remember(state.events, search, order, tagFilter, showPast, now) {
        state.events
            .filter { event ->
                val needle = search.trim().lowercase()
                val haystack = "${event.name} ${event.venue.orEmpty()}".lowercase()
                (needle.isEmpty() || haystack.contains(needle)) &&
                    (tagFilter == null || tagFilter in event.tagIds) &&
                    (showPast || !event.isPast(now))
            }
            .sortedWith(
                // Past events sink whatever the order: they are still here and no longer the
                // answer to "what am I going to".
                compareBy<EventRow> { it.isPast(now) }.thenComparator { left, right ->
                    when (order) {
                        EventOrder.Name -> left.name.compareTo(right.name, ignoreCase = true)
                        // Undated events after dated ones: a blank sorts arbitrarily wherever it
                        // is put, and putting it first hides everything that has a date.
                        EventOrder.Date -> compareValues(
                            left.startsAt ?: "\uffff",
                            right.startsAt ?: "\uffff",
                        )
                        EventOrder.Added -> 0
                    }
                },
            )
    }

    when {
        state.events.isEmpty() && !state.isLoading ->
            Message(stringResource(R.string.wallet_empty), modifier)
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(spacing.medium),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            item(key = "toolbar", contentType = "toolbar") {
                Toolbar(
                    search = search,
                    onSearch = { search = it },
                    order = order,
                    onOrder = { order = it },
                    showPast = showPast,
                    onShowPast = { showPast = it },
                    tags = state.tags,
                    tagFilter = tagFilter,
                    onTagFilter = { tagFilter = if (tagFilter == it) null else it },
                )
            }

            items(shown, key = { it.id }, contentType = { "event" }) { event ->
                EventCard(
                    event = event,
                    tags = state.tags,
                    past = event.isPast(now),
                    onClick = onEventClick,
                )
            }

            if (shown.isEmpty()) {
                item(key = "nothing") {
                    Message(stringResource(R.string.events_no_match), Modifier)
                }
            }
        }
    }
}

/**
 * Searching, ordering and filtering, in one row of controls.
 *
 * One decision about one list, so one place: three separate cards stacked above the wallet is
 * what turns a screen into a column of panels with the content pushed off the bottom.
 */
@Composable
private fun Toolbar(
    search: String,
    onSearch: (String) -> Unit,
    order: EventOrder,
    onOrder: (EventOrder) -> Unit,
    showPast: Boolean,
    onShowPast: (Boolean) -> Unit,
    tags: List<com.mateof.passvault.server.Tag>,
    tagFilter: String?,
    onTagFilter: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    var menuOpen by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        OutlinedTextField(
            value = search,
            onValueChange = onSearch,
            label = { Text(stringResource(R.string.events_search)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                TextButton(onClick = { menuOpen = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null)
                    Text(
                        text = stringResource(
                            when (order) {
                                EventOrder.Date -> R.string.events_order_date
                                EventOrder.Name -> R.string.events_order_name
                                EventOrder.Added -> R.string.events_order_added
                            },
                        ),
                        modifier = Modifier.padding(start = spacing.tight),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    for (option in EventOrder.entries) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        when (option) {
                                            EventOrder.Date -> R.string.events_order_date
                                            EventOrder.Name -> R.string.events_order_name
                                            EventOrder.Added -> R.string.events_order_added
                                        },
                                    ),
                                )
                            },
                            onClick = {
                                onOrder(option)
                                menuOpen = false
                            },
                        )
                    }
                }
            }

            FilterChip(
                selected = showPast,
                onClick = { onShowPast(!showPast) },
                label = { Text(stringResource(R.string.events_show_past)) },
            )
        }

        if (tags.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                for (tag in tags) {
                    com.mateof.passvault.ui.tags.TagChip(
                        name = tag.name,
                        colour = tag.colour,
                        selected = tagFilter == null || tagFilter == tag.id,
                        onClick = { onTagFilter(tag.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EventCard(
    event: EventRow,
    tags: List<com.mateof.passvault.server.Tag>,
    past: Boolean,
    onClick: (String) -> Unit,
) {
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
            // Faded rather than hidden. It has not stopped existing — it is a receipt now, and
            // somebody looking for last month's concert should still find it.
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (past) 0.55f else 1f },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        // Raised, and further when pressed. A flat card on a tinted background reads as a panel;
        // one that lifts under a finger reads as an object, which is what a ticket stands for.
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp, pressedElevation = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(spacing.medium),
            horizontalArrangement = Arrangement.spacedBy(spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EventMark(eventId = event.id, icon = event.icon, colour = event.colour)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.tight),
            ) {
                Text(
                    text = event.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                event.startsAt?.let { MetaRow(Icons.Filled.CalendarToday, whenText(it)) }
                event.venue?.let { MetaRow(Icons.Filled.Place, it) }

                if (event.tagIds.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.tight)) {
                        for (tagId in event.tagIds) {
                            tags.firstOrNull { it.id == tagId }?.let { tag ->
                                com.mateof.passvault.ui.tags.TagChip(
                                    name = tag.name,
                                    colour = tag.colour,
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.padding(top = spacing.hairline),
                    horizontalArrangement = Arrangement.spacedBy(spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MetaRow(
                        icon = Icons.Filled.ConfirmationNumber,
                        text = pluralStringResource(
                            R.plurals.event_ticket_count,
                            event.ticketCount,
                            event.ticketCount,
                        ),
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

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A line of secondary information, with the icon that says which kind it is. */
@Composable
private fun MetaRow(icon: ImageVector, text: String) {
    val spacing = LocalSpacing.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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

/**
 * When it starts, as somebody would say it.
 *
 * A date with no time is stored as midnight, which is how "the 14th of August" is written down —
 * so a midnight instant is shown as a day and anything else gets its clock. Printing "00:00"
 * beside every dateless event would be inventing a detail nobody entered.
 */
private fun whenText(value: String): String {
    val instant = runCatching { java.time.Instant.parse(value) }.getOrNull() ?: return value.take(10)
    val local = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
    val date = local.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
    return if (local.hour == 0 && local.minute == 0) {
        date
    } else {
        date + " " + local.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    }
}
