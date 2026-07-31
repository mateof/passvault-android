package com.mateof.passvault.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The corner scale.
 *
 * Larger than Material's defaults, and deliberately so. The wallet is a stack of cards that stand
 * for physical objects, and a card with a 4dp corner reads as a panel while one with a 24dp corner
 * reads as a thing you could pick up. The barcode sheet is the one exception — it is square,
 * because it stands for a printed ticket rather than for a card.
 *
 * Kept as one scale rather than a radius per component so the whole interface stays in proportion:
 * a dialog and a card that round differently look like two applications.
 */
val PassVaultShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
