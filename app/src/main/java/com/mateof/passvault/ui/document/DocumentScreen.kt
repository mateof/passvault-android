package com.mateof.passvault.ui.document

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.mateof.passvault.R
import com.mateof.passvault.ui.theme.LocalSpacing

/**
 * The document the tickets were split out of.
 *
 * Rendered inside the app rather than handed to a PDF viewer. Opening it with another application
 * means writing the decrypted file somewhere that application can read, and a plaintext ticket
 * document sitting in a shared cache is the thing the rest of this app takes trouble to avoid — it
 * would undo the encryption for the convenience of not drawing pages.
 *
 * Every page, including the ones ingestion excluded. Those are the whole point: a page with no
 * barcode is a page ingestion leaves out, and it is also where the instructions, the venue map and
 * the terms live.
 */
@Immutable
data class DocumentPage(val number: Int, val image: ImageBitmap)

@Immutable
data class DocumentViewState(
    val pages: List<DocumentPage> = emptyList(),
    val isLoading: Boolean = true,
    val failed: Boolean = false,
)

@Composable
fun DocumentScreen(state: DocumentViewState, modifier: Modifier = Modifier) {
    val spacing = LocalSpacing.current

    when {
        state.isLoading -> Message(stringResource(R.string.document_loading), modifier)
        state.failed || state.pages.isEmpty() ->
            Message(stringResource(R.string.document_unreadable), modifier)
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(spacing.medium),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            items(state.pages, key = { it.number }) { page ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        bitmap = page.image,
                        contentDescription = stringResource(R.string.document_page, page.number),
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            // White behind the page: a PDF page is drawn on white and a
                            // transparent background flattens to the theme's surface, which in
                            // dark mode turns black text into black on black.
                            .background(Color.White, RoundedCornerShape(8.dp))
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    Text(
                        text = stringResource(R.string.document_page, page.number),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.tight),
                    )
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, modifier: Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(LocalSpacing.current.large),
        )
    }
}
