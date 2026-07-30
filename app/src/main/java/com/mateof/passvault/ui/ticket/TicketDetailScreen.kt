package com.mateof.passvault.ui.ticket

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.mateof.passvault.R
import com.mateof.passvault.ui.theme.LocalSpacing

/**
 * One ticket, ready to be scanned.
 *
 * The barcode is the whole screen. Everything else is small and underneath it, because this is
 * looked at with somebody waiting behind you and a hand held out — the payload has to be the first
 * and largest thing, not one card among several.
 */
@Immutable
data class TicketDetail(
    val id: String,
    val eventName: String,
    val label: String?,
    val seat: String?,
    val barcodeFormat: String?,
    val barcodeValue: String?,
    val holderLabel: String?,
    val isProvisional: Boolean,
)

@Composable
fun TicketDetailScreen(
    detail: TicketDetail,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current

    Column(
        modifier = modifier
            .fillMaxSize()
            // Scrollable rather than fitted: a dense PDF417 plus a long venue name overflows a small
            // screen, and a barcode cropped off the bottom is worse than one you have to scroll to.
            .verticalScroll(rememberScrollState())
            .padding(spacing.large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        if (detail.barcodeFormat != null && detail.barcodeValue != null) {
            BarcodeImage(format = detail.barcodeFormat, payload = detail.barcodeValue)
            // The payload in text too. When a scanner will not read — a scratched screen, bad
            // light, an old reader — somebody on the door types it in, and that is the difference
            // between getting in and not.
            Text(
                text = detail.barcodeValue,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Text(
            text = detail.eventName,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )

        listOfNotNull(detail.label, detail.seat, detail.holderLabel)
            .takeIf { it.isNotEmpty() }
            ?.let { parts ->
                Text(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

        if (detail.isProvisional) {
            // Said on the screen the user would take to the gate, not only in the list. A claim made
            // offline is not settled, and finding that out at the door is the failure this whole
            // design exists to prevent.
            Text(
                text = stringResource(R.string.claim_provisional_explain),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
