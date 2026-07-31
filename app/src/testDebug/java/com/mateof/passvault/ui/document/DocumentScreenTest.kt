package com.mateof.passvault.ui.document

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import com.mateof.passvault.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reading a kept document page by page.
 *
 * Turning a page used to mean going back to the list and picking the next one, so the assertions
 * here are about the gesture: swipe, and the page after it is the one on screen.
 *
 * The last case is the one that pays for the rest. Paging and panning are the same gesture with
 * two meanings, and the rule that separates them — a magnified page pans, an unmagnified one
 * turns — is the sort of thing that works when written and quietly stops working later.
 *
 * In `testDebug` rather than `test`: hosting a composable needs the activity that ui-test-manifest
 * declares, and that manifest is merged into the debug build alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class DocumentScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * Built inside a test rather than in a field: a bitmap needs the Android environment, and a
     * field initialiser runs while the test class is being constructed, before there is one.
     */
    private fun state() = DocumentViewState(
        pages = (1..3).map {
            DocumentPage(
                number = it,
                // Wide and short, so all three thumbnails fit the test screen at once. A page
                // drawn at its real aspect ratio pushes the third one below the fold, where it is
                // not composed and cannot be tapped.
                image = Bitmap.createBitmap(200, 40, Bitmap.Config.ARGB_8888).asImageBitmap(),
            )
        },
        isLoading = false,
    )

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun page(number: Int) = context().getString(R.string.document_page, number)

    private fun position(number: Int, total: Int) =
        context().getString(R.string.document_page_position, number, total)

    /** Opens the reader on a page, the way a reader does: by tapping it in the list. */
    private fun openFromList(number: Int) {
        val state = state()
        compose.setContent { DocumentScreen(state) }
        compose.onNodeWithContentDescription(page(number)).performClick()
        compose.waitForIdle()
    }

    @Test
    fun `a page opens where it was tapped`() {
        openFromList(2)

        compose.onNodeWithText(position(2, 3)).assertIsDisplayed()
    }

    @Test
    fun `swiping left turns to the next page`() {
        openFromList(1)

        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()

        compose.onNodeWithText(position(2, 3)).assertIsDisplayed()
    }

    @Test
    fun `swiping right turns back`() {
        openFromList(3)

        compose.onRoot().performTouchInput { swipeRight() }
        compose.waitForIdle()

        compose.onNodeWithText(position(2, 3)).assertIsDisplayed()
    }

    @Test
    fun `the document does not wrap round at the end`() {
        openFromList(3)

        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()

        compose.onNodeWithText(position(3, 3)).assertIsDisplayed()
    }

    @Test
    fun `a magnified page pans instead of turning`() {
        openFromList(1)

        // Double tap magnifies, and from then on the drag belongs to the page. A swipe that turned
        // the page here would make a zoomed page impossible to read: every attempt to look at the
        // right-hand side of it would land on the next page instead.
        compose.onRoot().performTouchInput { doubleClick() }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()

        compose.onNodeWithText(position(1, 3)).assertIsDisplayed()
    }

    @Test
    fun `zooming back out turns the page again`() {
        openFromList(1)

        compose.onRoot().performTouchInput { doubleClick() }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { doubleClick() }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()

        compose.onNodeWithText(position(2, 3)).assertIsDisplayed()
    }
}
