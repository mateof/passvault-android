package com.mateof.passvault.ui.wallet

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mateof.passvault.R
import com.mateof.passvault.ui.theme.LocalEventHues
import com.mateof.passvault.ui.theme.LocalSpacing

/**
 * Choosing the mark an event is recognised by.
 *
 * Swatches, not two dropdowns. The whole value of a mark is that it is recognisable without being
 * read, and a list of colour names is precisely the thing that cannot be judged at a glance —
 * every option is shown as what it will actually look like.
 *
 * The icons are drawn in the colour currently chosen and the colours in the icon currently chosen,
 * so the two grids preview the same decision from both sides rather than making the user assemble
 * it in their head.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarkPickerDialog(
    eventId: String,
    icon: String?,
    colour: String?,
    onDismiss: () -> Unit,
    onChosen: (icon: String, colour: String) -> Unit,
) {
    val spacing = LocalSpacing.current
    val hues = LocalEventHues.current

    var chosenIcon by rememberSaveable { mutableStateOf(icon ?: defaultIconFor(eventId)) }
    var chosenColour by rememberSaveable {
        mutableStateOf(colour ?: hues.all[defaultColourFor(eventId, hues.all.size)].first)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.event_mark_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
                Text(
                    text = stringResource(R.string.event_mark_icon),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.small),
                    verticalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    EVENT_ICONS.forEach { option ->
                        Swatch(chosen = option == chosenIcon, onClick = { chosenIcon = option }) {
                            EventMark(eventId, option, chosenColour, size = 44.dp)
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.event_mark_colour),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.small),
                    verticalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    hues.all.forEach { (name, _) ->
                        Swatch(chosen = name == chosenColour, onClick = { chosenColour = name }) {
                            EventMark(eventId, chosenIcon, name, size = 36.dp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onChosen(chosenIcon, chosenColour) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun Swatch(chosen: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .border(
                width = 2.dp,
                color = if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(3.dp),
    ) {
        content()
    }
}
