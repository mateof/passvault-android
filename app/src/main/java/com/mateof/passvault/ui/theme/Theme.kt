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
 * Material 3 with a palette of its own. Dynamic colour is available and off by default, which is
 * the opposite of what this file used to do and the reason it was changed: taking the wallpaper
 * palette made the app *feel* like part of the phone and look like nothing in particular — a
 * beige wallpaper produced a beige wallet, and the event marks, the one thing that makes a list
 * of twelve events scannable, came out as twelve shades of the same beige.
 *
 * So the identity is fixed and the user can opt into theirs. The seed is a deep indigo with a
 * teal accent, dark enough to hold white text outdoors: a ticket wallet is read in a queue, in
 * sunlight, by somebody who is already slightly anxious.
 */
private val Indigo = Color(0xFF4B3FD0)
private val IndigoBright = Color(0xFF9B8CFF)
private val IndigoDeep = Color(0xFF2E2593)
private val Teal = Color(0xFF0E9C93)
private val TealBright = Color(0xFF3FD9CB)
private val Paper = Color(0xFFF6F5FC)
private val Ink = Color(0xFF14141C)

private val LightScheme = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E0FF),
    onPrimaryContainer = IndigoDeep,
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFF3F0),
    onSecondaryContainer = Color(0xFF04443F),
    tertiary = Color(0xFFC2419A),
    onTertiary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFAFF),
    surfaceContainer = Color(0xFFF2F1FA),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFEDEBF7),
    surfaceVariant = Color(0xFFE7E5F3),
    onSurfaceVariant = Color(0xFF56546B),
    outline = Color(0xFFC9C6DC),
    outlineVariant = Color(0xFFE2E0EE),
)

private val DarkScheme = darkColorScheme(
    primary = IndigoBright,
    onPrimary = Color(0xFF1B1547),
    primaryContainer = Color(0xFF322B78),
    onPrimaryContainer = Color(0xFFE4E0FF),
    secondary = TealBright,
    onSecondary = Color(0xFF00322E),
    secondaryContainer = Color(0xFF0A4B46),
    onSecondaryContainer = Color(0xFFCFF3F0),
    tertiary = Color(0xFFE57BC0),
    onTertiary = Color(0xFF440F33),
    background = Color(0xFF0E0E15),
    onBackground = Color(0xFFE9E8F2),
    surface = Color(0xFF15151F),
    onSurface = Color(0xFFE9E8F2),
    surfaceContainerLowest = Color(0xFF0B0B12),
    surfaceContainerLow = Color(0xFF13131C),
    surfaceContainer = Color(0xFF191922),
    surfaceContainerHigh = Color(0xFF1F1F2B),
    surfaceContainerHighest = Color(0xFF272733),
    surfaceVariant = Color(0xFF2A2A38),
    onSurfaceVariant = Color(0xFFB6B4C8),
    outline = Color(0xFF474557),
    outlineVariant = Color(0xFF2E2D3C),
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

/**
 * Status colours, deliberately outside the colour scheme.
 *
 * Running the app on a device with dynamic colour showed why. The wallpaper palette replaced the
 * whole scheme, so the amber that marks a provisional claim came out the same grey as everything
 * else and the distinction the user needs most simply vanished.
 *
 * Dynamic colour is right for surfaces and chrome — it makes the app feel part of the phone. It is
 * wrong for anything that carries meaning, for the same reason an error is always red: the user has
 * to be able to learn what a colour means, and a colour that changes with the wallpaper cannot be
 * learned. These four are fixed, and each is paired with a shape difference so the meaning survives
 * for a colour-blind user and in sunlight.
 */
@Immutable
data class StatusColours(
    val held: Color = Color(0xFF0F7B6C),
    val provisional: Color = Color(0xFFD9A404),
    val free: Color = Color(0xFF8A948F),
    val transferred: Color = Color(0xFF6B7A73),
)

val LocalStatusColours = androidx.compose.runtime.staticCompositionLocalOf { StatusColours() }

/**
 * The hues an event can be marked with.
 *
 * The same eight names the server stores and the web draws, so a mark chosen on a phone survives
 * a synchronisation and looks the same in a browser. Outside the colour scheme for the same reason
 * as the status colours: these are how a user recognises their own events, and a hue that follows
 * the wallpaper cannot be recognised.
 */
@Immutable
data class EventHues(
    val violet: Color = Color(0xFF7C5CF0),
    val blue: Color = Color(0xFF2F7AE5),
    val teal: Color = Color(0xFF0D9488),
    val green: Color = Color(0xFF3F9142),
    val amber: Color = Color(0xFFC08A08),
    val orange: Color = Color(0xFFDD6B20),
    val red: Color = Color(0xFFD23F57),
    val pink: Color = Color(0xFFC2419A),
) {
    /** By the name the server and the web use. Anything unknown falls back rather than crashing. */
    fun named(name: String?): Color = when (name) {
        "violet" -> violet
        "blue" -> blue
        "teal" -> teal
        "green" -> green
        "amber" -> amber
        "orange" -> orange
        "red" -> red
        "pink" -> pink
        else -> violet
    }

    val all: List<Pair<String, Color>>
        get() = listOf(
            "violet" to violet,
            "blue" to blue,
            "teal" to teal,
            "green" to green,
            "amber" to amber,
            "orange" to orange,
            "red" to red,
            "pink" to pink,
        )
}

val LocalEventHues = androidx.compose.runtime.staticCompositionLocalOf { EventHues() }

private val PassVaultTypography = Typography().run {
    copy(
        // Tighter and heavier at the top of the scale. A screen title that is merely larger than
        // the body reads as a paragraph; one that is also tighter reads as a heading, which is
        // what lets somebody find their event without reading the list.
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        // Barcode payloads and seat numbers are read by a person comparing them against a screen at
        // a gate, so they get a tabular, roomier treatment wherever they appear.
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    )
}

@Composable
fun PassVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * Off by default, which is a reversal.
     *
     * Wallpaper colour is a lovely idea and it cost this application its identity: every surface
     * became a tint of whatever was behind the home screen, and the marks that tell twelve events
     * apart became twelve versions of the same one. It remains available for anybody who prefers
     * their phone to look like one piece.
     */
    dynamicColor: Boolean = false,
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
        shapes = PassVaultShapes,
        content = content,
    )
}
