package com.mateof.passvault.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mateof.passvault.R
import com.mateof.passvault.server.Group
import com.mateof.passvault.ui.theme.LocalSpacing

/**
 * Groups: the people you share an event with more than once.
 *
 * A group exists so "the family" is a thing you name once instead of four addresses typed again
 * for every concert. Sharing an event with one gives everybody in it access at the moment the
 * event is shared — adding somebody later does not retroactively hand them a ticket, which is the
 * behaviour the access rules already have and the wording here has to match.
 */
@Composable
fun GroupsSection(
    groups: List<Group>,
    onCreateGroup: (String) -> Unit,
    onAddMember: (String, String) -> Unit,
    onShareEvent: (String) -> Unit,
    sharingEventName: String?,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    var newGroup by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var email by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        Text(stringResource(R.string.groups_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.groups_explain),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        for (group in groups) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(
                    modifier = Modifier.padding(spacing.medium),
                    verticalArrangement = Arrangement.spacedBy(spacing.tight),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(group.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = pluralStringResource(
                                R.plurals.groups_members,
                                group.memberCount,
                                group.memberCount,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                        TextButton(
                            onClick = { expanded = if (expanded == group.id) null else group.id },
                        ) {
                            Text(stringResource(R.string.groups_add_member))
                        }
                        if (sharingEventName != null) {
                            TextButton(onClick = { onShareEvent(group.id) }) {
                                Text(stringResource(R.string.groups_share_here))
                            }
                        }
                    }

                    if (expanded == group.id) {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text(stringResource(R.string.groups_member_email)) },
                            // The address has to belong to an account on this server. Said here
                            // rather than only in the refusal, because "invite by email" is what
                            // people expect and this is not that.
                            supportingText = { Text(stringResource(R.string.groups_member_help)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(
                            onClick = {
                                onAddMember(group.id, email.trim())
                                email = ""
                                expanded = null
                            },
                            enabled = email.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.action_confirm_import))
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = newGroup,
            onValueChange = { newGroup = it },
            label = { Text(stringResource(R.string.groups_new)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = {
                onCreateGroup(newGroup.trim())
                newGroup = ""
            },
            enabled = newGroup.isNotBlank(),
        ) {
            Text(stringResource(R.string.groups_create))
        }
    }
}
