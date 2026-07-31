package com.mateof.passvault.ui.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mateof.passvault.R
import com.mateof.passvault.ui.theme.LocalSpacing

/**
 * The file the tickets were split out of, inside the event that holds them.
 *
 * It used to be reachable only from inside a ticket, which put it in the wrong place twice over.
 * The document does not belong to one ticket — it produced all of them — and the pages worth
 * keeping are the ones that are not tickets at all: the venue map, the terms, the instructions,
 * which is exactly what splitting drops, because splitting drops every page with no barcode on it.
 *
 * So it sits in the event, above its tickets, as what it is: an annex. Somebody looking for "the
 * PDF they sent me" is looking at the event, not at the third pass inside it.
 */
@Immutable
data class DocumentRow(
    val id: String,
    val mediaType: String,
    val pageCount: Int,
    val byteCount: Int,
)

@Composable
fun DocumentAnnexCard(document: DocumentRow, onOpen: (String) -> Unit) {
    val spacing = LocalSpacing.current

    Card(
        onClick = { onOpen(document.id) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            // Held apart from the ticket cards on purpose: it is a different kind of thing, and a
            // list where the attachment looks like a pass is a list where somebody taps it
            // expecting a barcode.
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(spacing.medium),
            horizontalArrangement = Arrangement.spacedBy(spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = iconFor(document.mediaType),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.document_annex_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = listOfNotNull(
                        pluralStringResource(
                            R.plurals.document_page_count,
                            document.pageCount,
                            document.pageCount,
                        ).takeIf { document.pageCount > 0 },
                        readableSize(document.byteCount),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
            )
        }
    }
}

private fun iconFor(mediaType: String): ImageVector = when {
    mediaType.contains("pdf") -> Icons.Filled.PictureAsPdf
    mediaType.startsWith("image/") -> Icons.Filled.Image
    else -> Icons.Filled.Description
}

private fun readableSize(bytes: Int): String =
    if (bytes >= 1024 * 1024) {
        "%.1f MB".format(bytes / 1024f / 1024f)
    } else {
        "${(bytes + 1023) / 1024} kB"
    }
