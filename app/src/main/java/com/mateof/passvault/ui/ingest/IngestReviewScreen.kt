package com.mateof.passvault.ui.ingest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mateof.passvault.R
import com.mateof.passvault.ingest.ProposalWarning
import com.mateof.passvault.ui.theme.LocalSpacing

/**
 * Confirming what a document contained.
 *
 * This screen is the whole reason ingestion produces a proposal rather than saving. Splitting a PDF
 * one ticket per page is right most of the time and wrong often enough to matter, and the person
 * looking at the document is the only one who can tell which case this is. Everything here exists
 * to make that judgement quick: what was found, what looks off, and one tap per row to disagree.
 */
@Immutable
data class ReviewRow(
    val index: Int,
    val label: String,
    val barcodeValue: String?,
    val pageNumber: Int?,
    val include: Boolean,
    val warning: ProposalWarning?,
)

@Immutable
data class IngestReviewState(
    val rows: List<ReviewRow> = emptyList(),
    val pageCount: Int = 0,
)

@Composable
fun IngestReviewScreen(
    state: IngestReviewState,
    onToggle: (Int) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val chosen = state.rows.count { it.include }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.ingest_review_explain),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(spacing.medium),
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = spacing.medium),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            items(
                items = state.rows,
                // Keyed by the proposal index, so toggling one row animates that row rather than
                // rebuilding a list the user is halfway down.
                key = { row -> row.index },
                contentType = { "proposed" },
            ) { row ->
                ReviewCard(row = row, onToggle = { onToggle(row.index) })
            }
        }

        Button(
            onClick = onConfirm,
            enabled = chosen > 0,
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.medium),
        ) {
            Text(pluralStringResource(R.plurals.ingest_confirm, chosen, chosen))
        }
    }
}

@Composable
private fun ReviewCard(row: ReviewRow, onToggle: () -> Unit) {
    val spacing = LocalSpacing.current

    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            // The whole card toggles, so the checkbox is an indicator rather than the only target —
            // a checkbox is a small hit area for something the user does once per row.
            Checkbox(checked = row.include, onCheckedChange = { onToggle() })

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.hairline),
            ) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                row.barcodeValue?.let { value ->
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                row.warning?.let { warning ->
                    // Said in words, not only by leaving the row unticked. "Why is this one off?" is
                    // the first thing the user asks, and the answer decides whether they override it.
                    Text(
                        text = stringResource(warning.messageId()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}

private fun ProposalWarning.messageId(): Int = when (this) {
    ProposalWarning.NO_BARCODE -> R.string.ingest_warning_no_barcode
    ProposalWarning.MULTIPLE_BARCODES -> R.string.ingest_warning_multiple
    ProposalWarning.DUPLICATE_BARCODE -> R.string.ingest_warning_duplicate
    ProposalWarning.PKPASS_NO_BARCODE -> R.string.ingest_warning_no_barcode
}
