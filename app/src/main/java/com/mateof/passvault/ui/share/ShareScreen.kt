package com.mateof.passvault.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mateof.passvault.R
import com.mateof.passvault.share.DiscoveredPeer
import com.mateof.passvault.share.ShareScope
import com.mateof.passvault.share.TransferError
import com.mateof.passvault.ui.theme.LocalSpacing

/**
 * Passing tickets to a phone in the same room — the sending side.
 *
 * The comparison step is the screen. Everything else here is plumbing around it, and the layout
 * says so: the digits are the largest thing the application ever draws, monospaced and spaced out
 * so two people can read them to each other across a table without misreading a 6 for an 8.
 */
@Composable
fun ShareSendScreen(
    state: ShareUiState,
    onConnect: (DiscoveredPeer) -> Unit,
    onConnectManual: (String) -> Unit,
    onDigitsMatch: () -> Unit,
    onDigitsDiffer: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        // What is about to leave, said before anything leaves. A transfer that hands over a
        // whole wallet when somebody meant one seat is not recoverable: the other phone has
        // the tickets.
        ScopeBanner(state.scope)

        if (state.stage == ShareStage.Looking || state.stage == ShareStage.Idle) {
            Text(
                text = stringResource(R.string.share_send_explain),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.share_tap_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.pairedByTap) {
            Text(
                text = stringResource(R.string.share_tap_paired),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        when (state.stage) {
            ShareStage.Idle, ShareStage.Looking -> Looking(state, onConnect, onConnectManual)
            ShareStage.Greeting -> Busy(stringResource(R.string.share_greeting, state.peerName ?: ""))
            ShareStage.Comparing -> Comparing(state, onDigitsMatch, onDigitsDiffer)
            ShareStage.Transferring -> Busy(stringResource(R.string.share_transferring))
            ShareStage.Done -> Outcome(
                title = pluralStringResource(
                    R.plurals.share_sent_done,
                    state.sentCount,
                    state.sentCount,
                ),
                detail = stringResource(R.string.share_sent_detail, state.peerName ?: ""),
                onDone = onDone,
            )
            ShareStage.Cancelled -> Outcome(
                title = stringResource(R.string.share_cancelled),
                detail = null,
                onDone = onDone,
            )
            ShareStage.Attacked -> Outcome(
                title = stringResource(R.string.share_attacked),
                detail = stringResource(R.string.share_attacked_detail),
                onDone = onDone,
            )
            ShareStage.Failed -> Outcome(
                title = stringResource(R.string.share_failed),
                detail = state.failure?.let { stringResource(reasonOf(it)) },
                onDone = onDone,
            )
        }
    }
}

/**
 * The receiving side: this phone's name and address, large, and then patience.
 *
 * The name and the address are the whole interface, because they are what the person opposite
 * needs — to recognise this phone in their list, or to type where discovery is blocked. Nothing
 * is chosen here: the receiver takes what it is given, and the sender decided what that is.
 */
@Composable
fun ShareReceiveScreen(
    state: ShareUiState,
    onDigitsMatch: () -> Unit,
    onDigitsDiffer: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        when (state.stage) {
            ShareStage.Idle, ShareStage.Looking -> {
                Text(
                    text = stringResource(R.string.share_receive_explain),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(
                        modifier = Modifier.padding(spacing.medium),
                        verticalArrangement = Arrangement.spacedBy(spacing.small),
                    ) {
                        Text(
                            text = state.ownName.orEmpty(),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = stringResource(R.string.share_receive_own_name_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.ownAddress?.let { address ->
                            Text(
                                text = stringResource(R.string.share_manual_own, address),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = spacing.medium),
                    horizontalArrangement = Arrangement.spacedBy(spacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.share_receive_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            ShareStage.Greeting -> Busy(stringResource(R.string.share_greeting, state.peerName ?: ""))
            ShareStage.Comparing -> Comparing(state, onDigitsMatch, onDigitsDiffer)
            ShareStage.Transferring -> Busy(stringResource(R.string.share_transferring))
            ShareStage.Done -> Outcome(
                title = pluralStringResource(
                    R.plurals.share_done,
                    state.receivedCount,
                    state.receivedCount,
                ),
                detail = null,
                onDone = onDone,
            )
            ShareStage.Cancelled -> Outcome(
                title = stringResource(R.string.share_cancelled),
                detail = null,
                onDone = onDone,
            )
            ShareStage.Attacked -> Outcome(
                title = stringResource(R.string.share_attacked),
                detail = stringResource(R.string.share_attacked_detail),
                onDone = onDone,
            )
            ShareStage.Failed -> Outcome(
                title = stringResource(R.string.share_failed),
                detail = state.failure?.let { stringResource(reasonOf(it)) },
                onDone = onDone,
            )
        }
        if (state.pairedByTap) {
            Text(
                text = stringResource(R.string.share_tap_paired),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** A line naming exactly what this transfer offers, above everything else on the screen. */
@Composable
private fun ScopeBanner(scope: ShareScope) {
    Text(
        text = when (scope) {
            ShareScope.Everything -> stringResource(R.string.share_scope_everything)
            is ShareScope.Event -> stringResource(R.string.share_scope_event, scope.eventName)
            is ShareScope.Tickets -> pluralStringResource(
                R.plurals.share_scope_tickets,
                scope.ticketIds.size,
                scope.ticketIds.size,
                scope.eventName,
            )
        },
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun Looking(
    state: ShareUiState,
    onConnect: (DiscoveredPeer) -> Unit,
    onConnectManual: (String) -> Unit,
) {
    val spacing = LocalSpacing.current

    // Said before anything is found, not after. Somebody about to tap a name in a list is the
    // person who needs to know that the name in the list proves nothing.
    Text(
        text = stringResource(R.string.share_names_prove_nothing),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (state.peers.isEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = spacing.medium),
            horizontalArrangement = Arrangement.spacedBy(spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.share_no_peers), style = MaterialTheme.typography.bodyMedium)
        }
    }

    // The way in when the list stays empty. A lot of routers and every guest network eat the
    // discovery broadcasts, and NFC needs hardware both phones may lack — this path needs
    // nothing but eyes: the receiving screen says its address, and it is typed here.
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier.padding(spacing.medium),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            Text(
                text = stringResource(R.string.share_manual_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.share_manual_send_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
            var typed by remember { mutableStateOf("") }
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(R.string.share_manual_field)) },
                    placeholder = { Text("192.168.0.34:40213") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onConnectManual(typed) },
                    enabled = ':' in typed,
                ) {
                    Text(stringResource(R.string.share_manual_connect))
                }
            }
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        items(state.peers, key = { it.name }) { peer ->
            Card(
                onClick = { onConnect(peer) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(spacing.medium)) {
                    Text(peer.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = peer.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Comparing(state: ShareUiState, onMatch: () -> Unit, onDiffer: () -> Unit) {
    val spacing = LocalSpacing.current

    Text(
        text = stringResource(R.string.share_compare_title, state.peerName ?: ""),
        style = MaterialTheme.typography.titleLarge,
    )
    Text(
        text = stringResource(R.string.share_compare_explain),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text(
        text = state.digits.orEmpty().toCharArray().joinToString(" "),
        // The biggest thing the app draws, monospaced so every digit takes the same width and
        // the grouping cannot shift as the value changes.
        style = MaterialTheme.typography.displayLarge.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 44.sp,
        ),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = spacing.large),
    )

    Button(onClick = onMatch, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.share_digits_match))
    }
    OutlinedButton(onClick = onDiffer, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.share_digits_differ))
    }
}

@Composable
private fun Busy(message: String) {
    val spacing = LocalSpacing.current

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = spacing.medium),
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Outcome(title: String, detail: String?, onDone: () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    detail?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_done))
    }
}

private fun reasonOf(error: TransferError): Int = when (error) {
    TransferError.DIGITS_MISMATCH -> R.string.share_attacked_detail
    TransferError.TAMPERED -> R.string.share_error_tampered
    TransferError.CONNECTION_LOST -> R.string.share_error_connection
    TransferError.UNSUPPORTED_VERSION -> R.string.share_error_version
    TransferError.CANCELLED -> R.string.share_cancelled
    TransferError.PROTOCOL -> R.string.share_error_protocol
}
