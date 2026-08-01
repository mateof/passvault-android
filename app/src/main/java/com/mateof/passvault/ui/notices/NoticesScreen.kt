package com.mateof.passvault.ui.notices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.mateof.passvault.R
import com.mateof.passvault.server.Invitation
import com.mateof.passvault.server.Notice
import com.mateof.passvault.ui.theme.LocalSpacing

/**
 * What needs an answer, and what merely happened.
 *
 * Sharing offers an event now rather than putting it in somebody's wallet unasked — an event
 * carries a friend's name, their seat and sometimes what they paid, and holding one is a
 * decision. This is where that decision is made, which makes it the screen without which
 * sharing does not work at all.
 *
 * The password comes up here, when there is one, because accepting is the first moment the
 * person who has to type it is present. Whoever shared the event tells them by some other
 * route, which is what an event password is for.
 */
@Composable
fun NoticesScreen(
    state: NoticesUiState,
    onAccept: (Invitation, String?) -> Unit,
    onDecline: (Invitation) -> Unit,
    onMarkRead: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    var answering by remember { mutableStateOf<Invitation?>(null) }

    if (!state.signedIn) {
        Message(stringResource(R.string.notices_need_server), modifier)
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        state.failure?.let { failure ->
            item {
                Text(
                    text = failure,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (state.loading) {
            item { CircularProgressIndicator() }
        }

        if (state.invitations.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.notices_invitations),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        items(state.invitations, key = { it.id }) { invitation ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(spacing.medium)) {
                    Text(
                        text = state.nameFor(invitation).ifBlank {
                            stringResource(R.string.notices_an_event)
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (invitation.passwordProtected) {
                        Text(
                            text = stringResource(R.string.notices_needs_password),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                        TextButton(onClick = {
                            // A protected event asks; an open one is one press.
                            if (invitation.passwordProtected) answering = invitation
                            else onAccept(invitation, null)
                        }) {
                            Text(stringResource(R.string.notices_accept))
                        }
                        TextButton(onClick = { onDecline(invitation) }) {
                            Text(stringResource(R.string.notices_decline))
                        }
                    }
                }
            }
        }

        if (state.notices.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.notices_recent),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (state.unread > 0) {
                        TextButton(onClick = onMarkRead) {
                            Text(stringResource(R.string.notices_mark_read))
                        }
                    }
                }
            }
        }

        items(state.notices, key = { it.id }) { notice ->
            Text(
                text = sentence(notice),
                style = MaterialTheme.typography.bodyMedium,
                color = if (notice.read) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }

        if (!state.loading && state.notices.isEmpty() && state.invitations.isEmpty()) {
            item { Message(stringResource(R.string.notices_none), Modifier) }
        }
    }

    answering?.let { invitation ->
        PasswordDialog(
            eventName = state.nameFor(invitation),
            onDismiss = { answering = null },
            onAccept = { password ->
                answering = null
                onAccept(invitation, password)
            },
        )
    }
}

/** A notice is a kind and a payload; the sentence is made here, in the reader's language. */
@Composable
private fun sentence(notice: Notice): String = when (notice.kind) {
    "event.invited" -> stringResource(
        R.string.notice_invited,
        notice.invitedBy.ifBlank { "?" },
        notice.eventName,
    )
    "event.accepted" -> stringResource(R.string.notice_accepted)
    "event.declined" -> stringResource(R.string.notice_declined)
    "ticket.assigned" -> stringResource(R.string.notice_assigned)
    else -> notice.kind
}

@Composable
private fun PasswordDialog(
    eventName: String,
    onDismiss: () -> Unit,
    onAccept: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(eventName.ifBlank { stringResource(R.string.notices_an_event) }) },
        text = {
            Column {
                Text(stringResource(R.string.notices_password_explain))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.event_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAccept(password) }, enabled = password.isNotBlank()) {
                Text(stringResource(R.string.notices_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
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
