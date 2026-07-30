package com.mateof.passvault.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import com.mateof.passvault.R
import com.mateof.passvault.ui.theme.LocalSpacing

/**
 * Joining a server.
 *
 * The screen says what a server is for before asking for anything, because the honest answer is
 * "nothing you need". Every part of this application works without one, and somebody who arrives
 * here should be able to decide to leave.
 *
 * Three steps rather than one form. The vault passphrase is asked for separately from the password
 * on purpose: they are different secrets with different jobs, and putting them in one form is how a
 * user concludes they are two names for the same thing and types the same value into both.
 */
@Composable
fun ServerScreen(
    state: ServerUiState,
    onAddressChange: (String) -> Unit,
    onConnect: () -> Unit,
    onSignIn: (String, String) -> Unit,
    onSecondFactor: (String, String) -> Unit,
    onUnlock: (String) -> Unit,
    onSync: () -> Unit,
    onForget: () -> Unit,
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
        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        state.failure?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        when (state.stage) {
            ServerStage.Address -> AddressStep(state, onAddressChange, onConnect)
            ServerStage.SignIn -> SignInStep(state, onSignIn, onForget)
            ServerStage.SecondFactor -> SecondFactorStep(state, onSecondFactor)
            ServerStage.Vault -> VaultStep(onUnlock)
            ServerStage.Ready -> ReadyStep(state, onSync, onForget)
        }
    }
}

@Composable
private fun AddressStep(
    state: ServerUiState,
    onAddressChange: (String) -> Unit,
    onConnect: () -> Unit,
) {
    Text(stringResource(R.string.server_title), style = MaterialTheme.typography.titleLarge)
    // Said before the field, not after. Somebody who does not need a server should be able to work
    // that out without typing anything.
    Text(
        text = stringResource(R.string.server_optional),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = state.address,
        onValueChange = onAddressChange,
        label = { Text(stringResource(R.string.server_address)) },
        placeholder = { Text("passvault.example.com") },
        supportingText = { Text(stringResource(R.string.server_address_help)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = onConnect,
        enabled = !state.busy && state.address.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.server_connect))
    }
}

@Composable
private fun SignInStep(state: ServerUiState, onSignIn: (String, String) -> Unit, onForget: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Text(state.address, style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text(stringResource(R.string.server_email)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(stringResource(R.string.server_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { onSignIn(email.trim(), password) },
        enabled = !state.busy && email.isNotBlank() && password.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.server_sign_in))
    }
    TextButton(onClick = onForget) { Text(stringResource(R.string.server_forget)) }
}

@Composable
private fun SecondFactorStep(state: ServerUiState, onSubmit: (String, String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val method = state.secondFactorMethods.firstOrNull() ?: "email"

    Text(stringResource(R.string.server_second_factor), style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(
            if (method == "totp") R.string.server_second_factor_totp
            else R.string.server_second_factor_email,
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        label = { Text(stringResource(R.string.server_code)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { onSubmit(code.trim(), method) },
        enabled = !state.busy && code.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.server_sign_in))
    }
}

@Composable
private fun VaultStep(onUnlock: (String) -> Unit) {
    var passphrase by rememberSaveable { mutableStateOf("") }

    Text(stringResource(R.string.vault_open_title), style = MaterialTheme.typography.titleMedium)
    // The one explanation this design cannot do without. Two secrets is the part users find
    // baffling, and the moment the second is asked for is the moment it is worth reading.
    Text(
        text = stringResource(R.string.vault_two_secrets),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = passphrase,
        onValueChange = { passphrase = it },
        label = { Text(stringResource(R.string.vault_passphrase_label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { onUnlock(passphrase) },
        enabled = passphrase.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.vault_open))
    }
}

@Composable
private fun ReadyStep(state: ServerUiState, onSync: () -> Unit, onForget: () -> Unit) {
    Text(state.address, style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(R.string.server_ready),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Button(onClick = onSync, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.server_sync))
    }

    state.lastSync?.let { summary ->
        Text(
            text = pluralStringResource(R.plurals.server_sync_done, summary.received, summary.received),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (summary.localOnly > 0) {
            // Said rather than hidden. An event this phone made has no counterpart on the server
            // yet, and a sync that silently skipped it would look like a sync that worked.
            Text(
                text = pluralStringResource(
                    R.plurals.server_sync_local_only,
                    summary.localOnly,
                    summary.localOnly,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }

    TextButton(onClick = onForget) { Text(stringResource(R.string.server_forget)) }
}
