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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current

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
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = session.userAgent ?: stringResource(R.string.sessions_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = listOfNotNull(
                        session.ipAddress,
                        session.lastSeenAt?.take(16)?.replace('T', ' '),
                        if (session.current) stringResource(R.string.sessions_current) else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { onRevokeSession(session.id) }) {
                    Text(stringResource(R.string.sessions_revoke))
                }
            }
        }

        HorizontalDivider()

        // ── The way out ─────────────────────────────────────────────────────────
        // Signing out, distinct from forgetting the server: the session ends here and there,
        // the sealed secrets go, and the address stays so coming back is a password, not a
        // reconfiguration.
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.profile_sign_out))
        }
    }
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
