package com.mateof.passvault.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import com.mateof.passvault.R
import com.mateof.passvault.server.AccessEntry
import com.mateof.passvault.server.Group
import com.mateof.passvault.ui.theme.LocalSpacing

/**
 * Sharing an event through the server, with a group or with one person.
 *
 * A different thing from the Wi-Fi transfer on the same screen, and the difference is worth being
 * clear about: that one hands a copy to a phone in the room, this one grants access to an account
 * that can be taken away again. Both are on the event because both are answers to "let my friends
 * have these", and which one somebody wants depends on whether their friends are in the room.
 *
 * Who it is already shared with is listed first. Sharing used to be write-only — grant and hope —
 * so "did I remember the family?" could only be answered by doing it again.
 */
@Composable
fun ShareWithDialog(
    access: List<AccessEntry>,
    groups: List<Group>,
    eventPassword: String,
    onEventPasswordChanged: (String) -> Unit,
    /** The password the server holds, for the creator to see, copy and change. */
    serverPassword: String?,
    onChangeServerPassword: (String?) -> Unit,
    pendingEmail: String,
    addressKnown: Boolean?,
    failure: String?,
    claimed: Boolean,
    onClaim: () -> Unit,
    onEmailChanged: (String) -> Unit,
    onShareWithGroup: (String) -> Unit,
    onShareWithPerson: () -> Unit,
    onRevoke: (AccessEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sharing_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                failure?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (access.isEmpty()) {
                    Text(
                        text = stringResource(R.string.sharing_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    for (entry in access) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = spacing.tight),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (entry.subjectKind == "GROUP") {
                                    Icons.Filled.Group
                                } else {
                                    Icons.Filled.Person
                                },
                                contentDescription = null,
                            )
                            Text(
                                text = entry.label.ifBlank { entry.subjectId.take(8) },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = spacing.small),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = { onRevoke(entry) }) {
                                Text(stringResource(R.string.sharing_revoke))
                            }
                        }
                    }
                    // Said where it matters: taking access away stops what happens next and
                    // recovers nothing already delivered.
                    Text(
                        text = stringResource(R.string.sharing_revoke_explain),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Divider(modifier = Modifier.padding(vertical = spacing.small))

                // A password is a decision about who can decrypt at all, not a second lock in
                // front of something the server can already read — so it belongs beside the list
                // of people this is being handed to.
                if (serverPassword != null) {
                    // The copy the creator keeps: its job is social as well as cryptographic,
                    // because whoever set it has to tell it to their friends weeks later, and
                    // "I chose it in March" is not a password.
                    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                    Text(
                        text = stringResource(R.string.event_password_current),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = serverPassword,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(serverPassword))
                        }) {
                            Text(stringResource(R.string.action_copy))
                        }
                    }
                }
                OutlinedTextField(
                    value = eventPassword,
                    onValueChange = onEventPasswordChanged,
                    label = {
                        Text(
                            stringResource(
                                if (serverPassword == null) R.string.event_password
                                else R.string.event_password_new,
                            ),
                        )
                    },
                    singleLine = true,
                    supportingText = { Text(stringResource(R.string.event_password_help)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (serverPassword != null && eventPassword.isNotBlank()) {
                    TextButton(onClick = { onChangeServerPassword(eventPassword) }) {
                        Text(stringResource(R.string.event_password_change))
                    }
                }

                Divider(modifier = Modifier.padding(vertical = spacing.small))

                if (groups.isEmpty()) {
                    Text(
                        text = stringResource(R.string.groups_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.sharing_with_group),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    for (group in groups) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onShareWithGroup(group.id) }
                                .padding(vertical = spacing.small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Group, contentDescription = null)
                            Text(
                                text = group.name.ifBlank {
                                    stringResource(R.string.groups_unnamed)
                                },
                                modifier = Modifier.padding(start = spacing.small),
                            )
                        }
                    }
                }

                Divider(modifier = Modifier.padding(vertical = spacing.small))

                OutlinedTextField(
                    value = pendingEmail,
                    onValueChange = onEmailChanged,
                    // One field for both, because somebody sharing is thinking "give it to ana",
                    // not "is this an address or a username".
                    label = { Text(stringResource(R.string.sharing_person_or_handle)) },
                    singleLine = true,
                    isError = addressKnown == false,
                    supportingText = {
                        when (addressKnown) {
                            true -> Text(stringResource(R.string.groups_email_known))
                            false -> Text(stringResource(R.string.groups_email_unknown))
                            null -> {}
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                // Out of reach until the address belongs to somebody. A share with a typo in it
                // is a share that silently goes nowhere.
                TextButton(onClick = onShareWithPerson, enabled = addressKnown == true) {
                    Text(stringResource(R.string.sharing_with_person))
                }

                Divider(modifier = Modifier.padding(vertical = spacing.small))

                // Offered without working out whether this event hands its tickets out that way:
                // a phone that has just received an event does not reliably know, and the server
                // answers with a sentence rather than a shrug when the answer is no.
                if (claimed) {
                    Text(
                        text = stringResource(R.string.claim_taken),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    TextButton(onClick = onClaim) {
                        Text(stringResource(R.string.claim_take))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}
