package com.mateof.passvault.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PersonAdd
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.mateof.passvault.R
import com.mateof.passvault.server.Group
import com.mateof.passvault.server.GroupMember
import com.mateof.passvault.ui.theme.LocalSpacing

/**
 * Groups, as a list of names with the people under each one.
 *
 * The address field is the part worth attention. Adding somebody by email is the only handle
 * anybody has on anybody else, and an address with a typo in it used to be discovered when a
 * friend never received their ticket — so the field says, while it is being typed, whether an
 * account here uses it, and the button stays out of reach until it does.
 */
@Composable
fun GroupsScreen(
    state: GroupsUiState,
    onOpen: (String?) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onAddMember: (String, String) -> Unit,
    onRemoveMember: (String, String) -> Unit,
    onEmailChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Group?>(null) }
    var deleting by remember { mutableStateOf<Group?>(null) }

    if (!state.signedIn) {
        Message(stringResource(R.string.groups_need_server), modifier)
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        item {
            Text(
                text = stringResource(R.string.groups_explain),
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
                Icon(Icons.Filled.Group, contentDescription = null)
                Text(
                    text = stringResource(R.string.groups_create),
                    modifier = Modifier.padding(start = spacing.tight),
                )
            }
        }

        if (state.loading && state.groups.isEmpty()) {
            item { CircularProgressIndicator() }
        }

        if (!state.loading && state.groups.isEmpty()) {
            item { Message(stringResource(R.string.groups_empty), Modifier) }
        }

        items(state.groups, key = { it.id }) { group ->
            GroupCard(
                group = group,
                isOpen = state.openGroupId == group.id,
                members = if (state.openGroupId == group.id) state.members else emptyList(),
                pendingEmail = state.pendingEmail,
                addressKnown = state.addressKnown,
                onToggle = { onOpen(group.id) },
                onRename = { renaming = group },
                onDelete = { deleting = group },
                onEmailChanged = onEmailChanged,
                onAddMember = { onAddMember(group.id, state.pendingEmail.trim()) },
                onRemoveMember = { userId -> onRemoveMember(group.id, userId) },
            )
        }
    }

    if (creating) {
        NameDialog(
            title = stringResource(R.string.groups_create),
            initial = "",
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                onCreate(name)
            },
        )
    }

    renaming?.let { group ->
        NameDialog(
            title = stringResource(R.string.groups_rename),
            initial = group.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                onRename(group.id, name)
            },
        )
    }

    deleting?.let { group ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(group.name.ifBlank { stringResource(R.string.groups_unnamed) }) },
            // Said before it happens. "Delete" does not obviously mean "and close every event
            // this group was opening", and that is exactly what it means.
            text = { Text(stringResource(R.string.groups_delete_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(group.id)
                }) {
                    Text(stringResource(R.string.groups_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun GroupCard(
    group: Group,
    isOpen: Boolean,
    members: List<GroupMember>,
    pendingEmail: String,
    addressKnown: Boolean?,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onEmailChanged: (String) -> Unit,
    onAddMember: () -> Unit,
    onRemoveMember: (String) -> Unit,
) {
    val spacing = LocalSpacing.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.medium)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onToggle),
                ) {
                    Text(
                        text = group.name.ifBlank { stringResource(R.string.groups_unnamed) },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.groups_member_count,
                            group.memberCount,
                            group.memberCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (group.isOwner) {
                    IconButton(onClick = onRename) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.groups_rename))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.groups_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (isOpen) {
                for (member in members) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = spacing.tight),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(member.email, style = MaterialTheme.typography.bodyMedium)
                            if (member.isOwner) {
                                Text(
                                    text = stringResource(R.string.groups_owner),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        // The owner is never removable: a group with nobody to administer it is
                        // a group nobody can fix.
                        if (group.isOwner && !member.isOwner) {
                            TextButton(onClick = { onRemoveMember(member.userId) }) {
                                Text(stringResource(R.string.groups_remove))
                            }
                        }
                    }
                }

                if (group.isOwner) {
                    OutlinedTextField(
                        value = pendingEmail,
                        onValueChange = onEmailChanged,
                        label = { Text(stringResource(R.string.groups_email)) },
                        singleLine = true,
                        isError = addressKnown == false,
                        supportingText = {
                            when (addressKnown) {
                                true -> Text(stringResource(R.string.groups_email_known))
                                false -> Text(stringResource(R.string.groups_email_unknown))
                                null -> {}
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = spacing.small),
                    )
                    TextButton(onClick = onAddMember, enabled = addressKnown == true) {
                        Icon(Icons.Filled.PersonAdd, contentDescription = null)
                        Text(
                            text = stringResource(R.string.groups_add),
                            modifier = Modifier.padding(start = spacing.tight),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.groups_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun Message(text: String, modifier: Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(LocalSpacing.current.large),
        )
    }
}
