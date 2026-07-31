package com.mateof.passvault.ui.wallet

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The mark an event with nothing chosen still gets.
 *
 * A wallet that predates this feature is a column of identical squares unless something fills the
 * gap, and asking somebody to sit down and label twelve old events before their wallet looks like
 * anything is not a reasonable thing to ask. So the mark is derived — and the property that makes
 * a derived mark useful rather than annoying is that it never changes.
 */
class EventMarkTest {

    private val id = "019fb79c-7154-7360-afee-885db51765cd"

    @Test
    fun `the same event always gets the same icon`() {
        assertThat(defaultIconFor(id)).isEqualTo(defaultIconFor(id))
    }

    @Test
    fun `the same event always gets the same colour`() {
        assertThat(defaultColourFor(id, 8)).isEqualTo(defaultColourFor(id, 8))
    }

    @Test
    fun `the icon is one this version can draw`() {
        assertThat(EVENT_ICONS).contains(defaultIconFor(id))
    }

    @Test
    fun `the colour is inside the palette it was asked about`() {
        assertThat(defaultColourFor(id, 8)).isIn(0..7)
    }

    @Test
    fun `different events do not all land on the same mark`() {
        // The point of a derived mark is telling a list apart. One that gave every event the same
        // icon would be worse than none, because it would look deliberate.
        val ids = (1..40).map { "019fb79c-7154-7360-afee-8850000000%02d".format(it) }

        assertThat(ids.map(::defaultIconFor).toSet().size).isAtLeast(3)
        assertThat(ids.map { defaultColourFor(it, 8) }.toSet().size).isAtLeast(3)
    }

    @Test
    fun `an identifier that hashes negative still lands inside the set`() {
        // `hashCode` is signed, and a plain remainder on a negative value indexes out of bounds —
        // which would crash on exactly the events whose identifiers happened to hash that way.
        val negative = generateSequence(0) { it + 1 }
            .map { "event-$it" }
            .first { it.hashCode() < 0 }

        assertThat(EVENT_ICONS).contains(defaultIconFor(negative))
        assertThat(defaultColourFor(negative, 8)).isIn(0..7)
    }
}
