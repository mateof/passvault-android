package com.mateof.passvault.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Museum
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mateof.passvault.ui.theme.LocalEventHues

/**
 * The mark an event is recognised by: an icon, in a colour.
 *
 * The same eight names the server stores and the web draws, so a mark chosen here survives a
 * synchronisation and looks the same in a browser. Choosing them from a closed set rather than
 * letting anybody type one is what makes a wallet scannable: a concert is always the same shape,
 * and the eye finds it before the name is read.
 *
 * An event with nothing chosen still gets a mark, derived from its identifier. A wallet that has
 * existed since before this feature would otherwise be a column of identical grey squares, which
 * is exactly the problem the mark exists to solve — and asking somebody to sit down and label
 * twelve old events before their wallet looks like anything is not a reasonable thing to ask.
 */
val EVENT_ICONS: List<String> = listOf(
    "concert",
    "football",
    "theatre",
    "cinema",
    "travel",
    "museum",
    "party",
    "other",
)

private fun vectorFor(icon: String): ImageVector = when (icon) {
    "concert" -> Icons.Filled.MusicNote
    "football" -> Icons.Filled.SportsSoccer
    "theatre" -> Icons.Filled.TheaterComedy
    "cinema" -> Icons.Filled.Theaters
    "travel" -> Icons.Filled.Flight
    "museum" -> Icons.Filled.Museum
    "party" -> Icons.Filled.Celebration
    else -> Icons.Filled.ConfirmationNumber
}

/**
 * What an event with no chosen mark looks like.
 *
 * Derived from the identifier rather than random, so it is the same on every launch and on every
 * device that holds the same event — a mark that changed each time somebody opened the app would
 * be worse than no mark at all.
 */
fun defaultIconFor(eventId: String): String =
    EVENT_ICONS[(eventId.hashCode().toUInt() % EVENT_ICONS.size.toUInt()).toInt()]

fun defaultColourFor(eventId: String, hues: Int): Int =
    ((eventId.hashCode().toUInt() / 31u) % hues.toUInt()).toInt()

@Composable
fun EventMark(
    eventId: String,
    icon: String?,
    colour: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    val hues = LocalEventHues.current
    val chosenIcon = icon?.takeIf { it in EVENT_ICONS } ?: defaultIconFor(eventId)
    val chosenColour: Color = colour
        ?.takeIf { name -> hues.all.any { it.first == name } }
        ?.let(hues::named)
        ?: hues.all[defaultColourFor(eventId, hues.all.size)].second

    Box(
        modifier = modifier
            .size(size)
            .background(chosenColour, RoundedCornerShape(size / 3.4f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = vectorFor(chosenIcon),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size / 2f),
        )
    }
}
