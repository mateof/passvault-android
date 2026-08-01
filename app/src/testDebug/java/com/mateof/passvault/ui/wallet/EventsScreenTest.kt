package com.mateof.passvault.ui.wallet

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import com.mateof.passvault.server.Tag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Finding one event among many.
 *
 * A wallet that has been used for a year is mostly things that already happened, and the two
 * questions it has to answer are "what am I going to" and "where is that thing from March".
 * Searching, ordering, filtering by label and sinking past events are all one answer to those.
 *
 * The past-event rule is the one worth pinning: a day's grace, so a concert last night is still
 * near the top on the way home, and no date never counts as past — "we do not know when" is not
 * "it already happened".
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class EventsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = Instant.parse("2026-08-01T12:00:00Z")

    private fun event(
        id: String,
        name: String,
        startsAt: String? = null,
        tagIds: List<String> = emptyList(),
    ) = EventRow(
        id = id,
        name = name,
        venue = null,
        startsAt = startsAt,
        ticketCount = 1,
        provisionalCount = 0,
        tagIds = tagIds,
    )

    private val vigo = Tag(id = "tag-vigo", name = "Vigo", colour = "teal", eventCount = 1)

    /**
     * A string as this device words it.
     *
     * Read from resources rather than written out, so the test asserts behaviour rather than
     * which of the three translations it happened to run under — Robolectric defaults to
     * English, and the first version of this file was written in Galician.
     */
    private fun text(id: Int): String =
        androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .getString(id)

    private fun show(events: List<EventRow>, tags: List<Tag> = emptyList()) {
        compose.setContent {
            EventsScreen(
                state = EventsUiState(events = events, isLoading = false, tags = tags),
                onEventClick = {},
            )
        }
    }

    @Test
    fun `an event with no date is never past`() {
        val undated = event("1", "Sen data")

        assertThat(undated.isPast(now.toEpochMilli())).isFalse()
    }

    @Test
    fun `last night is past by the morning after`() {
        // A timed event is over once its time is. The earlier day of grace read as a bug:
        // the concert had plainly happened and the wallet kept insisting it had not.
        val lastNight = event("1", "Concerto", startsAt = "2026-07-31T21:00:00Z")

        assertThat(lastNight.isPast(now.toEpochMilli())).isTrue()
    }

    @Test
    fun `a date-only event lasts its whole day`() {
        // Stored as midnight because only the day is known: "the 14th" is not over at a
        // minute past midnight on the 14th, it is over when the 14th is.
        val localMidnight = java.time.LocalDate.of(2026, 8, 1)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toString()
        val today = event("1", "Feira", startsAt = localMidnight)

        assertThat(today.isPast(now.toEpochMilli())).isFalse()
    }

    @Test
    fun `last week is past`() {
        val lastWeek = event("1", "Concerto", startsAt = "2026-07-24T21:00:00Z")

        assertThat(lastWeek.isPast(now.toEpochMilli())).isTrue()
    }

    @Test
    fun `searching narrows the list to what matches`() {
        show(listOf(event("1", "Festival do Norte"), event("2", "Teatro Rosalía")))

        compose.onNodeWithText(text(com.mateof.passvault.R.string.events_search)).performTextInput("teatro")
        compose.waitForIdle()

        compose.onNodeWithText("Teatro Rosalía").assertIsDisplayed()
        compose.onAllNodesWithText("Festival do Norte").assertCountEquals(0)
    }

    @Test
    fun `a search that matches nothing says so rather than showing an empty screen`() {
        // An empty list reads as "still loading" or as a bug. It has to say which it is.
        show(listOf(event("1", "Festival do Norte")))

        compose.onNodeWithText(text(com.mateof.passvault.R.string.events_search)).performTextInput("zzz")
        compose.waitForIdle()

        compose.onNodeWithText(text(com.mateof.passvault.R.string.events_no_match)).assertIsDisplayed()
    }

    @Test
    fun `a label filters from the menu, and the cross clears it`() {
        // The filter moved behind one compact control: a dozen labels as a chip strip took more
        // of the screen than the events did. The menu holds any number; the chip names the
        // active filter and carries the cross that clears it.
        show(
            events = listOf(
                event("1", "Festival do Norte", tagIds = listOf("tag-vigo")),
                event("2", "Teatro Rosalía"),
            ),
            tags = listOf(vigo),
        )

        compose.onNodeWithText(text(com.mateof.passvault.R.string.events_filter_tag)).performClick()
        compose.waitForIdle()
        // The menu's entry is drawn after the card's own chip, so it is the last "Vigo" there is.
        compose.onAllNodesWithText("Vigo").onLast().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Teatro Rosalía").assertCountEquals(0)

        compose
            .onNodeWithContentDescription(text(com.mateof.passvault.R.string.events_filter_clear))
            .performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Teatro Rosalía").assertIsDisplayed()
    }

    @Test
    fun `past events can be hidden altogether`() {
        show(
            listOf(
                event("1", "Xa pasou", startsAt = "2020-01-01T21:00:00Z"),
                event("2", "Aínda non"),
            ),
        )

        compose.onNodeWithText(text(com.mateof.passvault.R.string.events_show_past)).performClick()
        compose.waitForIdle()

        compose.onAllNodesWithText("Xa pasou").assertCountEquals(0)
        compose.onNodeWithText("Aínda non").assertIsDisplayed()
    }
}
