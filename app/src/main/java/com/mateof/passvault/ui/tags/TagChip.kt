package com.mateof.passvault.ui.tags

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mateof.passvault.ui.theme.LocalEventHues

/**
 * A label, drawn the way it is drawn everywhere.
 *
 * A dot in the label's colour beside its name, rather than a block of that colour with text on
 * top: a name has to stay readable against eight different backgrounds, and coloured text on a
 * coloured pill is the fastest way to make one of the eight illegible.
 *
 * `selected = false` fades it rather than hiding it, because a filter that removes the thing you
 * pressed leaves nothing to press again.
 */
@Composable
fun TagChip(
    name: String,
    colour: String,
    modifier: Modifier = Modifier,
    selected: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val hue = LocalEventHues.current.named(colour)
    val shape = RoundedCornerShape(50)

    Row(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape)
            .border(BorderStroke(1.dp, hue.copy(alpha = if (selected) 0.6f else 0.2f)), shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(hue.copy(alpha = if (selected) 1f else 0.35f), CircleShape),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
