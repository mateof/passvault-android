package com.mateof.passvault.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Smartphone
import com.mateof.passvault.R
import com.mateof.passvault.ui.theme.LocalSpacing

/**
 * Who you are on the server, in one place.
 *
 * The username, the open sessions and the way out lived at the bottom of the server screen,
 * under connection steps and second factors — which is why nobody found them. A profile is a
 * different question from a connection: "who am I here" versus "where is here".
 *
 * Everything on it needs a signed-in server, and the screen says so plainly when there is none
 * rather than showing a column of disabled fields.
 */
@Composable
fun ProfileScreen(
    state: ServerUiState,
    onHandleChanged: (String) -> Unit,
    onSaveHandle: () -> Unit,
    onRevokeSession: (String) -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    var inspecting by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<com.mateof.passvault.server.OpenSession?>(null)
    }
    var confirmingDeletion by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }

    if (state.stage != ServerStage.Ready) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.profile_need_server),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(spacing.large),
            )
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        state.failure?.let { failure ->
            Text(
                text = failure,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // ── The name people find you by ─────────────────────────────────────────
        Text(stringResource(R.string.handle_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.handle_explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = state.currentHandle?.let { stringResource(R.string.handle_current, it) }
                ?: stringResource(R.string.handle_none),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = state.handle,
            onValueChange = onHandleChanged,
            label = { Text(stringResource(R.string.handle_field)) },
            singleLine = true,
            isError = state.handleTaken == true,
            supportingText = {
                when {
                    state.handleSaved -> Text(stringResource(R.string.handle_free))
                    state.handleTaken == true -> Text(stringResource(R.string.handle_taken))
                    state.handleTaken == false -> Text(stringResource(R.string.handle_free))
                    else -> {}
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = onSaveHandle,
            enabled = state.handle.trim().length >= 3 && state.handleTaken != true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.handle_save))
        }

        HorizontalDivider()

        // ── Where this account is open ──────────────────────────────────────────
        Text(stringResource(R.string.sessions_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.sessions_explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (session in state.sessions) {
            // A card that opens rather than a paragraph that lists: the row carries what a
            // person scans for — which device, when — and everything else waits in the dialog.
            androidx.compose.material3.Card(
                onClick = { inspecting = session },
                modifier = Modifier.fillMaxWidth(),
            ) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(spacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = if (session.current) {
                            Icons.Filled.Smartphone
                        } else {
                            Icons.Filled.Devices
                        },
                        contentDescription = null,
                        tint = if (session.current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = spacing.medium),
                    ) {
                        Text(
                            text = session.clientName.ifBlank {
                                stringResource(R.string.sessions_unknown)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = listOfNotNull(
                                session.lastSeenAt?.take(16)?.replace('T', ' '),
                                if (session.current) {
                                    stringResource(R.string.sessions_current)
                                } else {
                                    null
                                },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Icon(
                        imageVector =
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        HorizontalDivider()

        // ── The way out ─────────────────────────────────────────────────────────
        // Signing out, distinct from forgetting the server: the session ends here and there,
        // the sealed secrets go, and the address stays so coming back is a password, not a
        // reconfiguration. Drawn with weight — a quiet text link is how it went unfound.
        androidx.compose.material3.Button(
            onClick = onSignOut,
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.AutoMirrored.Filled.Logout,
                contentDescription = null,
            )
            Text(
                text = stringResource(R.string.profile_sign_out),
                modifier = Modifier.padding(start = spacing.small),
            )
        }

        // The way out with no way back, visually last and visually quiet: it must be findable
        // by somebody looking for it and unremarkable to everybody else.
        TextButton(
            onClick = { confirmingDeletion = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.delete_account_start),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (confirmingDeletion) {
        DeleteAccountDialog(
            busy = state.busy,
            failure = state.failure,
            onDismiss = { confirmingDeletion = false },
            onConfirm = onDeleteAccount,
        )
    }

    inspecting?.let { session ->
        SessionDetailDialog(
            session = session,
            onDismiss = { inspecting = null },
            onRevoke = {
                inspecting = null
                onRevokeSession(session.id)
            },
        )
    }
}

/**
 * Everything a session row knows, and the button that ends it.
 *
 * The detail lives here rather than on the row because most of it is only wanted while deciding
 * whether to end one: the full client string, the address, the dates. The row keeps what a
 * person scans for.
 */
@Composable
private fun SessionDetailDialog(
    session: com.mateof.passvault.server.OpenSession,
    onDismiss: () -> Unit,
    onRevoke: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(session.clientName.ifBlank { stringResource(R.string.sessions_unknown) })
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DetailRow(stringResource(R.string.sessions_detail_client), session.userAgent)
                DetailRow(stringResource(R.string.sessions_detail_ip), session.ipAddress)
                DetailRow(stringResource(R.string.sessions_detail_created), instant(session.createdAt))
                DetailRow(stringResource(R.string.sessions_detail_last), instant(session.lastSeenAt))
                DetailRow(stringResource(R.string.sessions_detail_expires), instant(session.expiresAt))
            }
        },
        confirmButton = {
            TextButton(onClick = onRevoke) {
                Text(
                    text = stringResource(R.string.sessions_revoke),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** An instant as a person reads one: local day and clock, no zone arithmetic on their side. */
private fun instant(value: String?): String? {
    val parsed = value?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() } ?: return value
    return java.time.LocalDateTime.ofInstant(parsed, java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}

/**
 * How the app behaves. One setting today — the language — and a screen so the next one has a home.
 */
@Composable
fun SettingsScreen(
    currentLocale: String?,
    onLocaleChosen: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.settings_language_explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // A radio list rather than a dropdown: four options fit on screen, and a list shows the
        // current choice without being opened — which a dropdown never does.
        val options = listOf(
            null to stringResource(R.string.settings_language_system),
            "gl" to "Galego",
            "es" to "Español",
            "en" to "English",
        )
        for ((tag, label) in options) {
            val selected = currentLocale == tag
            OutlinedButton(
                onClick = { if (!selected) onLocaleChosen(tag) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !selected,
            ) {
                Text(if (selected) "✓  $label" else label)
            }
        }
    }
}

/**
 * The confirmation that cannot be a reflex.
 *
 * The secret is typed here — the password, or the address for an account that has none —
 * because a password prompt is the strongest "are you sure" an interface can ask, and this is
 * the one act in the whole application with nothing on the other side of it.
 */
@Composable
private fun DeleteAccountDialog(
    busy: Boolean,
    failure: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var secret by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_account_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.delete_account_warning))
                failure?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text(stringResource(R.string.delete_account_field)) },
                    singleLine = true,
                    visualTransformation =
                        androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(secret) },
                enabled = secret.isNotBlank() && !busy,
            ) {
                Text(
                    text = stringResource(R.string.delete_account_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
