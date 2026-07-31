package com.mateof.passvault.ui.document

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mateof.passvault.R
import com.mateof.passvault.ui.theme.LocalSpacing
import kotlin.math.abs
import kotlin.math.max

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
 *
 * Tapping a page opens it on its own, where it can be pinched and dragged. Zooming inside the list
 * was the first design and was wrong: a drag means two things at once there — pan the page or
 * scroll to the next — and whichever the gesture detector picks, half the time it is not the one
 * the reader meant. On its own there is nothing to scroll, so a drag can only mean one thing.
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
    var opened by remember { mutableStateOf<DocumentPage?>(null) }

    // Closing the zoomed page is what back means while one is open, rather than leaving the
    // document altogether — which would throw away the reader's place for the sake of one tap.
    BackHandler(enabled = opened != null) { opened = null }

    opened?.let { page ->
        ZoomablePage(page = page, modifier = modifier)
        return
    }

    when {
        state.isLoading -> Message(stringResource(R.string.document_loading), modifier)
        state.failed || state.pages.isEmpty() ->
            Message(stringResource(R.string.document_unreadable), modifier)
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(spacing.medium),
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
                            .clickable { opened = page }
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

/**
 * One page, pinchable and draggable.
 *
 * Scale and offset are applied through `graphicsLayer`, so the gesture runs on the render thread
 * and neither layout nor measurement happens while a finger is moving — the difference between a
 * pinch that tracks the hand and one that lags behind it.
 *
 * The offset is clamped to the page. Letting somebody fling the page off screen and then wonder
 * where it went is the failure mode of every hand-rolled zoom, and the fix is arithmetic rather
 * than a gesture: the further in you are, the further you may travel, and never past the edge.
 */
@Composable
private fun ZoomablePage(page: DocumentPage, modifier: Modifier = Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var frame by remember { mutableStateOf(IntSize.Zero) }

    /** Keeps the page inside its own frame, whatever the pinch did. */
    fun clamp() {
        val limitX = max(0f, (frame.width * scale - frame.width) / 2f)
        val limitY = max(0f, (frame.height * scale - frame.height) / 2f)
        offsetX = offsetX.coerceIn(-limitX, limitX)
        offsetY = offsetY.coerceIn(-limitY, limitY)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
            .onSizeChanged { frame = it }
            .pointerInput(page.number) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(MINIMUM_SCALE, MAXIMUM_SCALE)
                    // Panning only means something once there is more page than frame. At 1x the
                    // drag is ignored rather than moving a page that has nowhere to go.
                    if (scale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                    clamp()
                }
            }
            .pointerInput(page.number) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        // Straight to a useful magnification and back, because pinching to read one
                        // barcode and pinching back out again is four gestures for one glance.
                        if (scale > 1f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = DOUBLE_TAP_SCALE
                            // Towards what was tapped, so the thing being looked at ends up in the
                            // middle rather than the centre of the page doing so.
                            offsetX = (frame.width / 2f - tap.x) * (DOUBLE_TAP_SCALE - 1f)
                            offsetY = (frame.height / 2f - tap.y) * (DOUBLE_TAP_SCALE - 1f)
                            clamp()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = page.image,
            contentDescription = stringResource(R.string.document_page, page.number),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                },
        )

        if (abs(scale - 1f) < 0.01f) {
            Text(
                text = stringResource(R.string.document_zoom_hint),
                style = MaterialTheme.typography.labelMedium,
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(LocalSpacing.current.medium),
            )
        }
    }
}

/** Below one the page would float inside its frame; above five a rendered page is only pixels. */
private const val MINIMUM_SCALE = 1f
private const val MAXIMUM_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f

@Composable
private fun Message(text: String, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(LocalSpacing.current.large),
        )
    }
}
