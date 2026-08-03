package com.mateof.passvault.ui.ticket

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val eventId: String,
    val eventName: String,
    val label: String?,
    val seat: String?,
    val barcodeFormat: String?,
    val barcodeValue: String?,
    val holderLabel: String?,
    val isProvisional: Boolean,
    /** Whether the document this was split out of was kept. */
    val hasDocument: Boolean = false,
    /** The creator has not opened this code yet, so it is withheld even though the phone holds it. */
    val locked: Boolean = false,
    /** Why: 'blocked', 'unpaid', or 'notYet'. Null when it is not locked. */
    val lockReason: String? = null,
    /** The moment it opens, for a countdown measured against the server's clock. */
    val visibleFrom: String? = null,
    /** Whether the holder may still hand it back — only while the code is still locked to them. */
    val canReturn: Boolean = false,
    /** Whether this device created the event, and so may work the controls below. */
    val isCreator: Boolean = false,
    /** Creator's view: whether the code is held back, may still be, and may be passed on. */
    val blocked: Boolean = false,
    val revealed: Boolean = false,
    val sharePermitted: Boolean = false,
    /** Whether it is paid, and who may see that, when the creator lets this viewer see it at all. */
    val paymentState: String? = null,
    val paymentVisibility: String? = null,
    val amountCents: Int? = null,
    val currency: String? = null,
)

@Composable
fun TicketDetailScreen(
    detail: TicketDetail,
    onOpenDocument: () -> Unit,
    onReturn: () -> Unit = {},
    onBlock: () -> Unit = {},
    onUnblock: () -> Unit = {},
    onToggleShare: (Boolean) -> Unit = {},
    onVisibleDayBefore: () -> Unit = {},
    onClearVisibility: () -> Unit = {},
    onSetPayment: (String, String) -> Unit = { _, _ -> },
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
        if (detail.locked) {
            // The code the phone holds but must not show yet, in the words the holder can act on.
            // A time lock with a moment still ahead counts down; everything else is a plain reason.
            val timeLock = detail.lockReason != "unpaid" &&
                detail.lockReason != "blocked" &&
                detail.visibleFrom != null
            if (timeLock) {
                LockCountdown(target = detail.visibleFrom!!)
            } else {
                Text(
                    text = stringResource(
                        when (detail.lockReason) {
                            "unpaid" -> R.string.ticket_locked_unpaid
                            "blocked" -> R.string.ticket_locked_blocked
                            else -> R.string.ticket_locked_until
                        },
                        detail.visibleFrom?.let { whenText(it) } ?: "",
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center,
                )
            }
            if (detail.canReturn) {
                // The way out while there is still nothing to keep: a locked ticket can be handed
                // back, and once the code has been seen it cannot.
                TextButton(onClick = onReturn) {
                    Text(stringResource(R.string.ticket_return))
                }
            }
        }
        if (detail.barcodeValue != null) {
            // The image needs a symbology; the payload does not. A ticket that arrived without a
            // declared format used to render nothing at all — no image and no text — so a perfectly
            // good ticket looked empty, and the holder would have found out at the gate. The
            // symbology is not guessed: drawing a QR for what was actually a PDF417 produces
            // something that looks scannable and is not, which is worse than no image.
            if (detail.barcodeFormat != null) {
                BarcodeImage(format = detail.barcodeFormat, payload = detail.barcodeValue)
            }
            // The payload in text either way. When a scanner will not read — a scratched screen, bad
            // light, an old reader — somebody on the door types it in, and that is the difference
            // between getting in and not.
            Text(
                text = detail.barcodeValue,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (detail.barcodeFormat == null) {
                Text(
                    text = stringResource(R.string.ticket_no_format),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center,
                )
            }
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

        // Whether it is paid, when the creator lets this viewer see it. Its presence is the
        // permission — the server only sends a payment to somebody allowed to see it.
        detail.paymentState?.let { state ->
            val amount = detail.amountCents?.takeIf { it > 0 }?.let { cents ->
                " · " + java.text.NumberFormat.getCurrencyInstance().apply {
                    runCatching { currency = java.util.Currency.getInstance(detail.currency ?: "EUR") }
                }.format(cents / 100.0)
            } ?: ""
            Text(
                text = stringResource(paymentStateLabel(state)) + amount,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        if (detail.hasDocument) {
            // Reached from the ticket rather than from a menu, because "where is the rest of the
            // PDF" is a question somebody asks while looking at a ticket and finding no gate
            // instructions on it.
            TextButton(onClick = onOpenDocument) {
                Text(stringResource(R.string.action_open_document))
            }
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

        if (detail.isCreator) {
            CreatorControls(
                detail = detail,
                onBlock = onBlock,
                onUnblock = onUnblock,
                onToggleShare = onToggleShare,
                onVisibleDayBefore = onVisibleDayBefore,
                onClearVisibility = onClearVisibility,
                onSetPayment = onSetPayment,
            )
        }
    }
}

private fun paymentStateLabel(state: String): Int = when (state) {
    "PAID" -> R.string.payment_paid
    "PARTIAL" -> R.string.payment_partial
    "WAIVED" -> R.string.payment_waived
    else -> R.string.payment_unpaid
}

/**
 * How long until a time-locked code opens, ticking once a second.
 *
 * The barcode is already on this phone — the lock is the app keeping its word — so the count is for
 * the holder's benefit, not a guard, and the device clock is honest enough for that. When it reaches
 * zero the next reload finds the code served and this disappears.
 */
@Composable
private fun LockCountdown(target: String) {
    val targetMs = remember(target) { runCatching { java.time.Instant.parse(target).toEpochMilli() }.getOrNull() }
    if (targetMs == null) {
        // A target we cannot read is better shown as its raw text than as a broken clock.
        Text(
            text = stringResource(R.string.ticket_locked_until, whenText(target)),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.Center,
        )
        return
    }
    var now by remember(target) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(target) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val remaining = (targetMs - now).coerceAtLeast(0)
    val total = remaining / 1000
    val days = total / 86400
    val hours = (total % 86400) / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    val text = if (days > 0) "${days}d ${hours}h ${minutes}m" else "${hours}h ${minutes}m ${seconds}s"
    Text(
        text = stringResource(R.string.ticket_locked_countdown, text),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.secondary,
        textAlign = TextAlign.Center,
    )
}

/**
 * The creator's grip on this one barcode, under it.
 *
 * The block button turns itself off the moment the code has been served, because from there the
 * holder may have a photograph and a block would be a lie the interface must not tell. The rest —
 * open the day before, clear the gate, lend the ticket on — is small on purpose: the fine control
 * lives on the web, and this is what somebody adjusts with the phone already in their hand.
 */
@Composable
private fun CreatorControls(
    detail: TicketDetail,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    onToggleShare: (Boolean) -> Unit,
    onVisibleDayBefore: () -> Unit,
    onClearVisibility: () -> Unit,
    onSetPayment: (String, String) -> Unit,
) {
    val spacing = LocalSpacing.current
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(vertical = spacing.small),
    )

    // Who has paid, and who may see it. The state gates the barcode too — an unpaid seat stays
    // locked — so this is where "mark it paid" lives beside "who can know".
    Text(
        text = stringResource(R.string.payment_title),
        style = MaterialTheme.typography.titleSmall,
    )
    val visibility = detail.paymentVisibility ?: "ALL"
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        val paid = detail.paymentState == "PAID"
        androidx.compose.material3.OutlinedButton(
            onClick = { onSetPayment(if (paid) "UNPAID" else "PAID", visibility) },
            modifier = Modifier.weight(1f),
        ) {
            Text(
                stringResource(
                    if (paid) R.string.payment_mark_unpaid else R.string.payment_mark_paid,
                ),
            )
        }
    }
    // Who may see the payment: everybody, only the debtor, or nobody but you.
    androidx.compose.foundation.layout.Column {
        Text(
            text = stringResource(R.string.payment_visibility_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val options = listOf(
            "ALL" to R.string.payment_visibility_all,
            "HOLDER_ONLY" to R.string.payment_visibility_holder,
            "CREATOR_ONLY" to R.string.payment_visibility_creator,
        )
        for ((value, label) in options) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.RadioButton(
                    selected = visibility == value,
                    onClick = { onSetPayment(detail.paymentState ?: "UNPAID", value) },
                )
                Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(vertical = spacing.small),
    )
    Text(
        text = stringResource(R.string.ticket_controls_title),
        style = MaterialTheme.typography.titleSmall,
    )
    detail.visibleFrom?.let {
        Text(
            text = stringResource(R.string.ticket_visible_from, whenText(it)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (detail.blocked) {
        androidx.compose.material3.OutlinedButton(
            onClick = onUnblock,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.ticket_unblock))
        }
    } else {
        androidx.compose.material3.OutlinedButton(
            onClick = onBlock,
            enabled = !detail.revealed,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (detail.revealed) R.string.ticket_block_revealed else R.string.ticket_block,
                ),
            )
        }
    }

    androidx.compose.material3.OutlinedButton(
        onClick = onVisibleDayBefore,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.ticket_visible_day_before))
    }
    androidx.compose.material3.OutlinedButton(
        onClick = onClearVisibility,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.ticket_visibility_clear))
    }

    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Switch(
            checked = detail.sharePermitted,
            onCheckedChange = onToggleShare,
        )
        Text(
            text = stringResource(R.string.ticket_allow_share),
            modifier = Modifier.padding(start = spacing.small),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** A server instant as a local day and time, so "visible from" reads as a moment. */
private fun whenText(value: String): String {
    val instant = runCatching { java.time.Instant.parse(value) }.getOrNull() ?: return value
    return java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}
