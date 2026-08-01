package com.mateof.passvault.ui.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Schedule
import com.mateof.passvault.R
import com.mateof.passvault.server.Tag
import com.mateof.passvault.ui.tags.TagChip
import com.mateof.passvault.ui.theme.LocalSpacing

/**
 * When an event is, and what it is to you.
 *
 * Two things that live together because they are the same act — telling the wallet enough about
 * an event to find it again later. The date is what sorts the list and what decides when an event
 * sinks to the bottom as past; the labels are what filter it.
 *
 * They are stored in different places, and that difference is worth knowing. The date is written
 * to this device's operation log and travels with the event to every device and every person it
 * is shared with — it is a fact about the event. A label is stored on the server against one
 * account and travels nowhere: it is what the event is *to you*, and the person you shared it
 * with has their own words for it.
 *
 * Typed rather than picked from a calendar. The platform picker is two dialogs deep for a value
 * most events never get, and a text field that accepts `2026-08-14` is faster for somebody
 * copying a date off a ticket — which is where this date usually comes from.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventDetailsDialog(
    startsAt: String?,
    venue: String?,
    tags: List<Tag>,
    chosenTagIds: List<String>,
    onDismiss: () -> Unit,
    onSave: (startsAt: String?, venue: String?, tagIds: List<String>) -> Unit,
    /** Makes a label here rather than three screens away, which is where the wish arises. */
    onCreateTag: (name: String, colour: String) -> Unit,
) {
    val spacing = LocalSpacing.current
    val existing = remember(startsAt) { splitInstant(startsAt) }
    var date by remember { mutableStateOf(existing.first) }
    var time by remember { mutableStateOf(existing.second) }
    var where by remember { mutableStateOf(venue.orEmpty()) }
    var chosen by remember(chosenTagIds) { mutableStateOf(chosenTagIds.toSet()) }
    var newTagName by remember { mutableStateOf("") }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.event_when)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text(stringResource(R.string.event_when_date)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    trailingIcon = {
                        androidx.compose.material3.IconButton(onClick = { pickingDate = true }) {
                            androidx.compose.material3.Icon(
                                androidx.compose.material.icons.Icons.Filled.CalendarToday,
                                contentDescription = stringResource(R.string.event_when_pick_date),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = time,
                    onValueChange = { time = it },
                    label = { Text(stringResource(R.string.event_when_time)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    trailingIcon = {
                        androidx.compose.material3.IconButton(onClick = { pickingTime = true }) {
                            androidx.compose.material3.Icon(
                                androidx.compose.material.icons.Icons.Filled.Schedule,
                                contentDescription = stringResource(R.string.event_when_pick_time),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = where,
                    onValueChange = { where = it },
                    label = { Text(stringResource(R.string.event_venue)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                HorizontalDivider()
                Text(
                    text = stringResource(R.string.tags_of_event),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.tight)) {
                        for (tag in tags) {
                            TagChip(
                                name = tag.name,
                                colour = tag.colour,
                                selected = tag.id in chosen,
                                onClick = {
                                    chosen = if (tag.id in chosen) chosen - tag.id else chosen + tag.id
                                },
                            )
                        }
                    }
                }
                // A new label, made without leaving. The colour is picked for them — recolouring
                // afterwards on the labels screen is one tap, and a dialog inside a dialog is not.
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.tight),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newTagName,
                        onValueChange = { newTagName = it },
                        label = { Text(stringResource(R.string.tags_name)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            onCreateTag(
                                newTagName.trim(),
                                // Spread over the palette by count, so five quick labels do not
                                // come out as five identical violet dots.
                                listOf("violet", "blue", "teal", "green", "amber", "orange", "red", "pink")[tags.size % 8],
                            )
                            newTagName = ""
                        },
                        enabled = newTagName.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.tags_create_short))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(instantOf(date, time), where.trim(), chosen.toList()) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )

    if (pickingDate) {
        DatePickerSheet(
            initial = date,
            onDismiss = { pickingDate = false },
            onPicked = { date = it },
        )
    }
    if (pickingTime) {
        TimePickerSheet(
            initial = time,
            onDismiss = { pickingTime = false },
            onPicked = { time = it },
        )
    }
}

/**
 * The platform's own calendar, feeding the same text field the keyboard does.
 *
 * The pickers write into the typed fields rather than into their own state: whichever way a
 * value arrived, there is one source of truth and the save button reads it.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerSheet(
    initial: String,
    onDismiss: () -> Unit,
    onPicked: (String) -> Unit,
) {
    val initialMillis = runCatching {
        java.time.LocalDate.parse(initial)
            .atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    }.getOrNull()
    val pickerState = androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = initialMillis,
    )

    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let { millis ->
                    onPicked(
                        java.time.Instant.ofEpochMilli(millis)
                            .atZone(java.time.ZoneOffset.UTC)
                            .toLocalDate()
                            .toString(),
                    )
                }
                onDismiss()
            }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    ) {
        androidx.compose.material3.DatePicker(state = pickerState)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerSheet(
    initial: String,
    onDismiss: () -> Unit,
    onPicked: (String) -> Unit,
) {
    val parsed = runCatching { java.time.LocalTime.parse(initial) }.getOrNull()
    val pickerState = androidx.compose.material3.rememberTimePickerState(
        initialHour = parsed?.hour ?: 20,
        initialMinute = parsed?.minute ?: 0,
        is24Hour = true,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.event_when_time)) },
        text = { androidx.compose.material3.TimePicker(state = pickerState) },
        confirmButton = {
            TextButton(onClick = {
                onPicked("%02d:%02d".format(pickerState.hour, pickerState.minute))
                onDismiss()
            }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** The stored instant as a local day and clock, which is how somebody reads and edits it. */
private fun splitInstant(value: String?): Pair<String, String> {
    val instant = value?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
        ?: return "" to ""
    val local = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
    val time = if (local.hour == 0 && local.minute == 0) {
        // Midnight is how a date with no time is written down, so it is shown as no time. The
        // alternative is every dateless event claiming to start at exactly midnight.
        ""
    } else {
        "%02d:%02d".format(local.hour, local.minute)
    }
    return local.toLocalDate().toString() to time
}

/**
 * A local day and clock as the instant to store.
 *
 * Null when the date is empty or not a date: an empty field means "remove it", and a half-typed
 * one must not become a wrong date silently. The time is optional and defaults to midnight, which
 * is the convention the rest of this reads back as "no time".
 */
private fun instantOf(date: String, time: String): String? {
    val day = runCatching { java.time.LocalDate.parse(date.trim()) }.getOrNull() ?: return null
    val clock = runCatching {
        if (time.isBlank()) java.time.LocalTime.MIDNIGHT else java.time.LocalTime.parse(time.trim())
    }.getOrNull() ?: java.time.LocalTime.MIDNIGHT
    return day.atTime(clock)
        .atZone(java.time.ZoneId.systemDefault())
        .toInstant()
        .toString()
}
