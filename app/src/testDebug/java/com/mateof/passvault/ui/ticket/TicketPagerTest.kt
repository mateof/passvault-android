package com.mateof.passvault.ui.ticket

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.mateof.passvault.R
import com.mateof.passvault.ui.wallet.TicketRow
import com.mateof.passvault.ui.wallet.TicketState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Moving between the tickets of one event without going back to the list.
 *
 * Driven through the gesture rather than the pager's state, because what was asked for is a swipe:
 * a test that called `scrollToPage` would pass with the pager's own drag handling switched off.
 *
 * The barcode payload is what each assertion looks for. It is the one field that differs per ticket
 * and the one somebody at a gate is actually reading, so if the right payload is on screen the
 * right ticket is.
 *
 * Under `testDebug` rather than `test`, so it runs for the debug variant only: hosting a composable
 * needs an activity that comes from `ui-test-manifest`, and that manifest is merged into the debug
 * build alone. A test activity has no business being declared in a release APK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class TicketPagerTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The position as this device would word it.
     *
     * Read from resources rather than written out here, so the test asserts that the right numbers
     * reach the label rather than which of the three translations the test happened to run under.
     */
    private fun position(page: Int, total: Int): String =
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.ticket_position, page, total)

    private val tickets = (1..3).map { number ->
        TicketRow(
            id = "ticket-$number",
            label = "Entrada $number",
            eventName = "Festival",
            seat = null,
            state = TicketState.Held,
            paymentLabel = null,
        )
    }

    private fun detailOf(ticketId: String) = TicketDetail(
        id = ticketId,
        eventId = "event",
        eventName = "Festival",
        label = null,
        seat = null,
        // No symbology, so nothing is rasterised: this is about which ticket is on screen, and the
        // payload is drawn as text either way.
        barcodeFormat = null,
        barcodeValue = "payload-$ticketId",
        holderLabel = null,
        isProvisional = false,
    )

    private fun open(ticketId: String) {
        compose.setContent {
            TicketPager(
                ticketId = ticketId,
                tickets = tickets,
                load = { detailOf(it) },
                onBack = {},
                onOpenDocument = {},
            )
        }
    }

    @Test
    fun `opens on the ticket that was chosen`() {
        open("ticket-2")

        compose.onNodeWithText("payload-ticket-2").assertIsDisplayed()
    }

    @Test
    fun `swiping left moves to the next ticket`() {
        open("ticket-1")

        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()

        compose.onNodeWithText("payload-ticket-2").assertIsDisplayed()
    }

    @Test
    fun `swiping right moves to the previous ticket`() {
        open("ticket-2")

        compose.onRoot().performTouchInput { swipeRight() }
        compose.waitForIdle()

        compose.onNodeWithText("payload-ticket-1").assertIsDisplayed()
    }

    @Test
    fun `the last ticket is the end of the stack`() {
        // Not a wrap-around. Somebody showing four tickets at a gate needs to know when they have
        // reached the last one, and silently returning them to the first hides exactly that.
        open("ticket-3")

        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()

        compose.onNodeWithText("payload-ticket-3").assertIsDisplayed()
        compose.onAllNodesWithText("payload-ticket-1").assertCountEquals(0)
    }

    @Test
    fun `the position says which of how many is showing`() {
        open("ticket-1")

        compose.onNodeWithText(position(1, 3)).assertIsDisplayed()

        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()

        compose.onNodeWithText(position(2, 3)).assertIsDisplayed()
    }

    @Test
    fun `a ticket that is not in the list still shows`() {
        // What a ticket opened from a share looks like: it belongs to no loaded event, so there is
        // nothing to page through. Showing it alone beats showing somebody else's first ticket.
        compose.setContent {
            TicketPager(
                ticketId = "elsewhere",
                tickets = emptyList(),
                load = { detailOf(it) },
                onBack = {},
                onOpenDocument = {},
            )
        }

        compose.onNodeWithText("payload-elsewhere").assertIsDisplayed()
    }
}
