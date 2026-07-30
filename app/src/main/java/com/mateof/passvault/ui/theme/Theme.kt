package com.mateof.passvault.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The visual system.
 *
 * Material 3 with dynamic colour where the platform offers it, so the app takes on the user's
 * wallpaper palette on Android 12 and later. Below that it falls back to a fixed scheme rather than
 * approximating one — a wrong guess at the user's colours looks worse than a deliberate palette.
 *
 * The seed colours are a deep teal and a warm amber: a ticket wallet is looked at in a queue, often
 * outdoors, so contrast between the card surface and the barcode matters more than subtlety.
 */
private val Teal = Color(0xFF0F3D3E)
private val TealLight = Color(0xFF3C6E71)
private val Amber = Color(0xFFD9A404)
private val Sand = Color(0xFFF2F7F5)
private val Ink = Color(0xFF101613)

private val LightScheme = lightColorScheme(
    primary = Teal,
    onPrimary = Sand,
    primaryContainer = TealLight,
    onPrimaryContainer = Sand,
    secondary = Amber,
    onSecondary = Ink,
    background = Sand,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE3EAE7),
    onSurfaceVariant = Color(0xFF3F4B46),
)

private val DarkScheme = darkColorScheme(
    primary = TealLight,
    onPrimary = Ink,
    primaryContainer = Teal,
    onPrimaryContainer = Sand,
    secondary = Amber,
    onSecondary = Ink,
    background = Color(0xFF0B0F0D),
    onBackground = Sand,
    surface = Color(0xFF141A17),
    onSurface = Sand,
    surfaceVariant = Color(0xFF2A322E),
    onSurfaceVariant = Color(0xFFC3CCC7),
)

/**
 * Spacing as a scale rather than loose numbers.
 *
 * `Immutable` so Compose can skip recomposition of anything that only reads it: an unstable object
 * threaded through the tree defeats skipping everywhere it lands, which is the most common cause of
 * a Compose screen that recomposes far more than it should.
 */
@Immutable
data class Spacing(
    val hairline: androidx.compose.ui.unit.Dp = 2.dp,
    val tight: androidx.compose.ui.unit.Dp = 4.dp,
    val small: androidx.compose.ui.unit.Dp = 8.dp,
    val medium: androidx.compose.ui.unit.Dp = 16.dp,
    val large: androidx.compose.ui.unit.Dp = 24.dp,
    val huge: androidx.compose.ui.unit.Dp = 32.dp,
)

val LocalSpacing = androidx.compose.runtime.staticCompositionLocalOf { Spacing() }

private val PassVaultTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        // Barcode payloads and seat numbers are read by a person comparing them against a screen at
        // a gate, so they get a tabular, roomier treatment wherever they appear.
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    )
}

@Composable
fun PassVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = PassVaultTypography,
        content = content,
    )
}
