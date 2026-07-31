package com.mateof.passvault.ui.document

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mateof.passvault.R
import com.mateof.passvault.ui.theme.LocalSpacing
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
 * Tapping a page opens it full screen, where it can be pinched, dragged and swiped through. Zooming
 * inside the list was the first design and was wrong: a drag means two things at once there — pan
 * the page or scroll to the next — and whichever the gesture detector picks, half the time it is
 * not the one the reader meant.
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
    var opened by remember { mutableStateOf<Int?>(null) }

    // Closing the opened page is what back means while one is open, rather than leaving the
    // document altogether — which would throw away the reader's place for the sake of one tap.
    BackHandler(enabled = opened != null) { opened = null }

    opened?.let { index ->
        PagedPages(pages = state.pages, initialPage = index, modifier = modifier)
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
            itemsIndexed(state.pages, key = { _, page -> page.number }) { index, page ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        bitmap = page.image,
                        contentDescription = stringResource(R.string.document_page, page.number),
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { opened = index }
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
 * The pages, full screen, one swipe apart.
 *
 * Reading a document meant opening a page, pinching, going back and picking the next one from the
 * list — four gestures to turn one page, on the screen where somebody is looking for the gate
 * instructions. A document is a sequence, so it is read as one.
 *
 * Paging is switched off while a page is magnified, which is the whole difficulty here: a drag on a
 * zoomed page has to mean pan, and on an unzoomed one it has to mean turn. Deciding by zoom level
 * rather than by direction or velocity is what keeps that unambiguous — the reader is never
 * guessing which of the two they are about to get.
 */
@Composable
private fun PagedPages(pages: List<DocumentPage>, initialPage: Int, modifier: Modifier = Modifier) {
    val pager = rememberPagerState(
        initialPage = initialPage.coerceIn(0, maxOf(pages.size - 1, 0)),
        pageCount = { maxOf(pages.size, 1) },
    )
    val zoom = remember { ZoomState() }

    // A new page starts unmagnified. Carrying a scale across would land the reader in the middle
    // of a page they have not seen, with no way of knowing which part of it they are looking at.
    LaunchedEffect(pager.currentPage) { zoom.reset() }

    Box(modifier = modifier.fillMaxSize().background(Color.White)) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoom.isMagnified,
            key = { page -> pages.getOrNull(page)?.number ?: page },
        ) { index ->
            pages.getOrNull(index)?.let { page ->
                ZoomablePage(page = page, zoom = zoom, isCurrent = index == pager.currentPage)
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(LocalSpacing.current.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!zoom.isMagnified) {
                Text(
                    text = stringResource(R.string.document_zoom_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.Black.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                )
            }
            // Which page of how many, always. A document read one screenful at a time is a
            // document somebody loses their place in, and the page number is the place.
            Text(
                text = stringResource(
                    R.string.document_page_position,
                    pages.getOrNull(pager.currentPage)?.number ?: 1,
                    pages.size,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = Color.Black.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * Scale and offset for the page being read.
 *
 * Held by the pager rather than by each page, because the pager has to know whether the page is
 * magnified: that is what decides whether a horizontal drag pans or turns.
 */
private class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)
    var frame by mutableStateOf(IntSize.Zero)

    /** A hair above one, so a pinch that lands imperceptibly off 1x does not lock paging. */
    val isMagnified: Boolean get() = scale > 1.01f

    fun reset() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    /** Keeps the page inside its own frame, whatever the pinch did. */
    fun clamp() {
        val limitX = max(0f, (frame.width * scale - frame.width) / 2f)
        val limitY = max(0f, (frame.height * scale - frame.height) / 2f)
        offsetX = offsetX.coerceIn(-limitX, limitX)
        offsetY = offsetY.coerceIn(-limitY, limitY)
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
private fun ZoomablePage(page: DocumentPage, zoom: ZoomState, isCurrent: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .onSizeChanged { if (isCurrent) zoom.frame = it }
            .pointerInput(page.number, isCurrent) {
                // Only the page in front responds. The pager keeps its neighbours composed, and a
                // page off screen that still handled gestures would zoom while a finger moved
                // somewhere else entirely.
                if (!isCurrent) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        // Whose gesture this is. Two fingers are always a pinch, and one finger on
                        // a magnified page is a pan; one finger on a whole page belongs to the
                        // pager, which is turning to the next.
                        //
                        // This is why the ready-made transform detector is not used here: it
                        // consumes every drag it sees, so at 1x it swallowed the swipe and no page
                        // ever turned. Consuming only what belongs to the page is the whole point.
                        val mine = event.changes.count { it.pressed } > 1 || zoom.isMagnified
                        if (mine) {
                            zoom.scale = (zoom.scale * event.calculateZoom())
                                .coerceIn(MINIMUM_SCALE, MAXIMUM_SCALE)
                            // Panning only means something once there is more page than frame.
                            if (zoom.isMagnified) {
                                val pan = event.calculatePan()
                                zoom.offsetX += pan.x
                                zoom.offsetY += pan.y
                            } else {
                                zoom.offsetX = 0f
                                zoom.offsetY = 0f
                            }
                            zoom.clamp()
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(page.number, isCurrent) {
                if (!isCurrent) return@pointerInput
                detectTapGestures(
                    onDoubleTap = { tap ->
                        // Straight to a useful magnification and back, because pinching to read one
                        // barcode and pinching back out again is four gestures for one glance.
                        if (zoom.isMagnified) {
                            zoom.reset()
                        } else {
                            zoom.scale = DOUBLE_TAP_SCALE
                            // Towards what was tapped, so the thing being looked at ends up in the
                            // middle rather than the centre of the page doing so.
                            zoom.offsetX = (zoom.frame.width / 2f - tap.x) * (DOUBLE_TAP_SCALE - 1f)
                            zoom.offsetY = (zoom.frame.height / 2f - tap.y) * (DOUBLE_TAP_SCALE - 1f)
                            zoom.clamp()
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
                    // Only the page in front is magnified. A neighbour drawn at the reader's zoom
                    // would slide in already halfway across itself.
                    val applied = if (isCurrent) zoom.scale else 1f
                    scaleX = applied
                    scaleY = applied
                    translationX = if (isCurrent) zoom.offsetX else 0f
                    translationY = if (isCurrent) zoom.offsetY else 0f
                },
        )
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
