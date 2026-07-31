package com.mateof.passvault.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.mateof.passvault.R
import com.mateof.passvault.update.ApkVerifier
import com.mateof.passvault.ui.theme.LocalSpacing

/**
 * Updating the app from its own releases.
 *
 * Three steps kept apart on screen — look, fetch, install — because they cost different things.
 * Seeing that 0.5.0 exists and reading what changed is a few kilobytes; the APK is several
 * megabytes and should not arrive on somebody's mobile data because they were curious.
 *
 * The verification step is shown rather than hidden. When a download is refused the screen says
 * which check failed, because "the signature is not the one this installation trusts" and "the
 * file arrived truncated" are entirely different events and only one of them is worth worrying
 * about.
 */
@Composable
fun UpdateScreen(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onGrantPermission: () -> Unit,
    mayInstall: Boolean,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        Text(
            text = stringResource(R.string.update_installed, state.installed),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.update_explain),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            state.installedNow -> Text(
                text = stringResource(R.string.update_installed_now),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            state.notUpdatable -> Text(
                text = stringResource(R.string.update_not_updatable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.available != null -> {
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.update_available, state.available.version),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.available.notes.isNotBlank()) {
                    Text(
                        text = state.available.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.available.apkBytes > 0) {
                    Text(
                        text = stringResource(
                            R.string.update_size,
                            state.available.apkBytes / (1024 * 1024),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.checked -> Text(
                text = stringResource(R.string.update_up_to_date),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (state.busy && state.progress > 0f) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        state.refused?.let { verdict ->
            Text(
                text = when (verdict) {
                    ApkVerifier.Verdict.DigestMismatch -> stringResource(R.string.update_refused_digest)
                    is ApkVerifier.Verdict.WrongPackage -> stringResource(R.string.update_refused_package)
                    ApkVerifier.Verdict.WrongSigner -> stringResource(R.string.update_refused_signer)
                    ApkVerifier.Verdict.Unreadable -> stringResource(R.string.update_refused_unreadable)
                    ApkVerifier.Verdict.Ok -> ""
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        state.failure?.let { failure ->
            Text(
                text = failure,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        when {
            state.ready && !mayInstall -> {
                // The permission is granted per application in Settings and is revocable, so
                // this is a state the screen can be in even after a successful update before.
                Text(
                    text = stringResource(R.string.update_needs_permission),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onGrantPermission, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.update_grant_permission))
                }
            }

            state.ready -> Button(
                onClick = onInstall,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.update_install))
            }

            state.available != null -> Button(
                onClick = onDownload,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.update_download))
            }
        }

        OutlinedButton(
            onClick = onCheck,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.update_check))
        }
    }
}
