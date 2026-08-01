package com.mateof.passvault.ui.tags

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NewLabel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mateof.passvault.R
import com.mateof.passvault.server.Tag
import com.mateof.passvault.ui.theme.LocalEventHues
import com.mateof.passvault.ui.theme.LocalSpacing

/**
 * Managing labels: making them, renaming them, recolouring them, deleting them.
 *
 * The colour is chosen from swatches rather than a list of names, for the same reason the event
 * mark is: the value of a colour is that it is recognised without being read, and a dropdown of
 * the word "amber" is exactly what cannot be.
 */
@Composable
fun TagsScreen(
    state: TagsUiState,
    onCreate: (String, String) -> Unit,
    onUpdate: (String, String, String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Tag?>(null) }

    if (!state.signedIn) {
        Message(stringResource(R.string.tags_need_server), modifier)
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        item {
            Text(
                text = stringResource(R.string.tags_explain),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.failure?.let { failure ->
            item {
                Text(
                    text = failure,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        item {
            TextButton(onClick = { creating = true }) {
                Icon(Icons.Filled.NewLabel, contentDescription = null)
                Text(
                    text = stringResource(R.string.tags_create),
                    modifier = Modifier.padding(start = spacing.tight),
                )
            }
        }

        if (state.loading && state.tags.isEmpty()) {
            item { CircularProgressIndicator() }
        }

        if (!state.loading && state.tags.isEmpty()) {
            item { Message(stringResource(R.string.tags_empty), Modifier) }
        }

        items(state.tags, key = { it.id }) { tag ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(spacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        TagChip(
                            name = tag.name.ifBlank { stringResource(R.string.tags_unnamed) },
                            colour = tag.colour,
                        )
                        Text(
                            text = pluralStringResource(
                                R.plurals.tags_event_count,
                                tag.eventCount,
                                tag.eventCount,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = spacing.tight),
                        )
                    }
                    IconButton(onClick = { editing = tag }) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.tags_edit))
                    }
                    IconButton(onClick = { onDelete(tag.id) }) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.tags_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        TagDialog(
            title = stringResource(R.string.tags_create),
            initialName = "",
            initialColour = TAG_COLOURS.first(),
            onDismiss = { creating = false },
            onConfirm = { name, colour ->
                creating = false
                onCreate(name, colour)
            },
        )
    }

    editing?.let { tag ->
        TagDialog(
            title = stringResource(R.string.tags_edit),
            initialName = tag.name,
            initialColour = tag.colour,
            onDismiss = { editing = null },
            onConfirm = { name, colour ->
                editing = null
                onUpdate(tag.id, name, colour)
            },
        )
    }
}

@Composable
private fun TagDialog(
    title: String,
    initialName: String,
    initialColour: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var colour by remember { mutableStateOf(initialColour) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.medium)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.tags_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Swatches(chosen = colour, onChosen = { colour = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), colour) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun Swatches(chosen: String, onChosen: (String) -> Unit) {
    val hues = LocalEventHues.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (name in TAG_COLOURS) {
            val colour = hues.named(name)
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(colour, CircleShape)
                    .border(
                        width = if (name == chosen) 3.dp else 0.dp,
                        color = if (name == chosen) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            Color.Transparent
                        },
                        shape = CircleShape,
                    )
                    .clickable { onChosen(name) },
            )
        }
    }
}

@Composable
private fun Message(text: String, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(LocalSpacing.current.large),
        )
    }
}
