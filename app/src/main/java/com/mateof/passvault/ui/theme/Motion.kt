package com.mateof.passvault.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.ui.unit.IntOffset

/**
 * One motion vocabulary for the whole app.
 *
 * Springs rather than durations for anything the user drives. A spring carries the velocity of the
 * gesture that started it, so an interrupted animation continues from where it was instead of
 * snapping back and restarting — which is the difference between an interface that feels responsive
 * and one that feels like it is playing a video at you.
 *
 * Durations are kept for the few transitions nothing is driving, where a spring has no velocity to
 * inherit and only adds unpredictability.
 *
 * Everything here is short. A wallet is opened at a gate with people behind you: animation should
 * make it obvious what changed, then get out of the way.
 */
object Motion {
    /** Press feedback and small state changes. Stiff enough to feel immediate. */
    fun <T> quick() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessHigh,
    )

    /** Layout changes: a card expanding, a list reordering after a claim is confirmed. */
    fun <T> standard() = spring<T>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Anything crossing the whole screen, where overshoot reads as sloppiness. */
    fun <T> deliberate() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    const val FADE_MILLIS = 120

    /**
     * How a ticket appears in the list.
     *
     * A short rise plus a fade. Deliberately not staggered across the list: a stagger looks
     * pleasant in a demo of four items and turns a wallet of forty into a wave the user waits out.
     */
    val itemEnter: EnterTransition =
        fadeIn(animationSpec = tween(FADE_MILLIS)) +
            slideInVertically(animationSpec = standard()) { height -> height / 6 }

    val itemExit: ExitTransition = fadeOut(animationSpec = tween(FADE_MILLIS))

    /** Offset helper for gesture-driven surfaces, kept here so the easing stays consistent. */
    fun offsetSpec() = spring<IntOffset>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
}
