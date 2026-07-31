package com.mateof.passvault.ui.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.mateof.passvault.R
import com.mateof.passvault.ui.theme.LocalSpacing

/**
 * Turning tickets into a file somebody can be sent.
 *
 * The password is not optional and is not defaulted. A `.tkpak` is a bearer object travelling
 * through a messaging app, which is to say through at least one company's servers; the password is
 * the only thing standing between the file and whoever ends up holding it.
 *
 * The warning above it is the one from the threat model, said before the file exists rather than
 * after it has been sent: once somebody imports this, they have the barcode, and no amount of
 * withdrawing it here takes it off their phone.
 */
@Composable
fun ExportDialog(
    ticketCount: Int,
    onDismiss: () -> Unit,
    onExport: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
                Text(
                    text = stringResource(R.string.export_no_revocation),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.export_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                Text(
                    text = stringResource(R.string.export_password_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onExport(password) },
                // A file nobody can open is never what somebody meant, so the button waits rather
                // than producing one and reporting success.
                enabled = password.length >= MINIMUM_FILE_PASSWORD,
            ) {
                Text(pluralStringResource(R.plurals.export_confirm, ticketCount, ticketCount))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Short enough to say out loud over a phone, long enough that Argon2id is doing real work. */
const val MINIMUM_FILE_PASSWORD = 6
