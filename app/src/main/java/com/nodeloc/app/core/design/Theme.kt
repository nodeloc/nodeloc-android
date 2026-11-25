package com.nodeloc.app.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Nocturne design tokens, value-for-value with the iOS `Theme.swift`
 * (whose own source is `design_import/rendered.html`'s :root override block).
 *
 * Deliberately *not* Material's default palette: only the shapes and the
 * ripple behaviour come from Material 3, every colour comes from here.
 */
@Immutable
data class NocturneColors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val text: Color,
    val divider: Color,
    val accent: Color,
    val accent2: Color,
    val headerBg: Color,
    val headerText: Color,
    val selected: Color,
    val hover: Color,
    val highlight: Color,
    val danger: Color,
    val success: Color,
    val love: Color,
    val neutral100: Color,
    val neutral200: Color,
    val neutral300: Color,
    val neutral400: Color,
    val neutral500: Color,
    val neutral600: Color,
    val neutral700: Color,
    val neutral800: Color,
    val neutral900: Color,
    val accent100: Color,
    val accent200: Color,
    val accent300: Color,
    val accent400: Color,
    val accent500: Color,
    val accent600: Color,
    val accent700: Color,
    val accent800: Color,
    val accent900: Color,
    val accent2100: Color,
    val accent2500: Color,
    val accent2600: Color,
    /** Text weight for the second accent, and the ground it sits on. */
    val accent2700: Color,
    val accent2800: Color,
    /**
     * Fixed secondary and tertiary text. `muted()` stays for the many places
     * that fade the ink; the 2026-11 design draws metadata in a cool grey that
     * an alpha of the ink cannot reach, so new screens use these.
     */
    val textSecondary: Color,
    val textTertiary: Color,
    /** Grounds behind `danger`, the warning ink and the info ink: badges, report strips, status pills. */
    val dangerBg: Color,
    val warning: Color,
    val warningBg: Color,
    val info: Color,
    val infoBg: Color,
) {
    /** Secondary body text is always the main text colour at a fixed opacity. */
    fun muted(pct: Float): Color = text.copy(alpha = pct)

    /** Two-variant avatar palette used across posts, chats, nodes. */
    fun avatarColors(variant: Int): Pair<Color, Color> =
        if (variant == 0) accent800 to accent100 else neutral800 to neutral200
}

private fun hex(value: Long, alpha: Float = 1f): Color =
    Color(
        red = ((value shr 16) and 0xFF) / 255f,
        green = ((value shr 8) and 0xFF) / 255f,
        blue = (value and 0xFF) / 255f,
        alpha = alpha,
    )

// Light values are the 2026-11 community redesign's (design canvas, Android
// page): ink #0F1A1C on white, green #007A52, cool blue-grey neutrals. The
// orange danger is the design's — it marks counts and destructive actions
// alike, so a red here would read as an error on every badge.
val LightNocturne = NocturneColors(
    isDark = false,
    bg = hex(0xFFFFFF), surface = hex(0xF2F5F6), text = hex(0x0F1A1C),
    divider = hex(0xE5EBEE), accent = hex(0x007A52), accent2 = hex(0xFF9933),
    headerBg = hex(0xFFFFFF), headerText = hex(0x0F1A1C), selected = hex(0xE3F4EC),
    hover = hex(0xEEF2F3), highlight = hex(0xFFF3BF), danger = hex(0xA33A00),
    success = hex(0x007A52), love = hex(0xC2255C),
    neutral100 = hex(0xFFFFFF), neutral200 = hex(0xF2F5F6), neutral300 = hex(0xEEF2F3),
    neutral400 = hex(0xE5EBEE), neutral500 = hex(0xC9D3D8), neutral600 = hex(0x8BA2AD),
    neutral700 = hex(0x576F76), neutral800 = hex(0x2B3B3F), neutral900 = hex(0x0F1A1C),
    accent100 = hex(0xE3F4EC), accent200 = hex(0xC9E3D6), accent300 = hex(0x8FCFB2),
    accent400 = hex(0x3FA57D), accent500 = hex(0x007A52), accent600 = hex(0x006A47),
    accent700 = hex(0x00573B), accent800 = hex(0x00382A), accent900 = hex(0x0B1F18),
    accent2100 = hex(0xFFE9CC), accent2500 = hex(0xFF9933), accent2600 = hex(0xE37E1A),
    accent2700 = hex(0xA85A0D), accent2800 = hex(0xFFD9A8),
    textSecondary = hex(0x576F76), textTertiary = hex(0x8BA2AD),
    dangerBg = hex(0xFFE4D1), warning = hex(0x8A5200), warningBg = hex(0xFFEFD6),
    info = hex(0x1F5FBF), infoBg = hex(0xE4EEFF),
)

// The design has no dark page; these keep the existing dark ground and move
// its neutrals toward the same cool grey so the two modes feel like one app.
val DarkNocturne = NocturneColors(
    isDark = true,
    bg = hex(0x0B1012), surface = hex(0x162024), text = hex(0xEEF3F4),
    divider = hex(0xFFFFFF, 0.12f), accent = hex(0x2BD49A), accent2 = hex(0xFFB15C),
    headerBg = hex(0x0B1012), headerText = hex(0xEEF3F4), selected = hex(0x123A2D),
    hover = hex(0x1C272B), highlight = hex(0x5A4A12), danger = hex(0xFF8A57),
    success = hex(0x4ADB9A), love = hex(0xFF7A9A),
    neutral100 = hex(0xF7FAFA), neutral200 = hex(0xEEF2F3), neutral300 = hex(0x1F2A2E),
    neutral400 = hex(0x2E3B40), neutral500 = hex(0x6F858D), neutral600 = hex(0x8BA2AD),
    neutral700 = hex(0xB5C4C9), neutral800 = hex(0x1A2427), neutral900 = hex(0x05090A),
    accent100 = hex(0xD7FFF2), accent200 = hex(0x9FF5D9), accent300 = hex(0x6FE9C5),
    accent400 = hex(0x42DDB1), accent500 = hex(0x2BD49A), accent600 = hex(0x55E2B0),
    accent700 = hex(0x7BEBC5), accent800 = hex(0x064B38), accent900 = hex(0x033326),
    accent2100 = hex(0xFFE4BF), accent2500 = hex(0xFFB15C), accent2600 = hex(0xFFC078),
    accent2700 = hex(0xFFC078), accent2800 = hex(0x4A2A05),
    textSecondary = hex(0x9DB0B6), textTertiary = hex(0x6F858D),
    dangerBg = hex(0x3D1E10), warning = hex(0xFFC56B), warningBg = hex(0x3A2A0E),
    info = hex(0x8AB4FF), infoBg = hex(0x152640),
)

/** Spacing scale, verbatim from iOS (odd values are intentional). */
object Space {
    val s1: Dp = 2.8.dp
    val s2: Dp = 5.6.dp
    val s3: Dp = 8.4.dp
    val s4: Dp = 11.2.dp
    val s6: Dp = 16.8.dp
    val s8: Dp = 22.4.dp

    /** Page horizontal margin; feed card padding is 14. */
    val page: Dp = 16.dp
    val card: Dp = 14.dp
}

object Radius {
    val sm: Dp = 4.dp
    val md: Dp = 8.dp
    val lg: Dp = 14.dp
}

/** Feed media sizing: the media's own ratio, clamped at the extremes. */
object FeedMedia {
    const val MIN_ASPECT = 9f / 16f   // widest shape (height ÷ width)
    const val MAX_ASPECT = 5f / 4f    // tallest shape
    const val DEFAULT_ASPECT = 1f

    fun heightRatio(mediaWidth: Int?, mediaHeight: Int?): Float {
        val ratio = if (mediaWidth != null && mediaHeight != null && mediaWidth > 0 && mediaHeight > 0) {
            mediaHeight.toFloat() / mediaWidth.toFloat()
        } else {
            DEFAULT_ASPECT
        }
        return ratio.coerceIn(MIN_ASPECT, MAX_ASPECT)
    }
}

val LocalNocturne = staticCompositionLocalOf { LightNocturne }

/** Shorthand: `Nocturne.accent` anywhere inside the theme. */
val Nocturne: NocturneColors
    @Composable @ReadOnlyComposable get() = LocalNocturne.current

/** Whether the user asked the system to reduce motion (loaders freeze at full brightness). */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun NodelocTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkNocturne else LightNocturne
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.neutral900,
            secondary = colors.accent2,
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.neutral300,
            onSurfaceVariant = colors.muted(0.62f),
            error = colors.danger,
            outline = colors.divider,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.neutral100,
            secondary = colors.accent2,
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.neutral300,
            onSurfaceVariant = colors.muted(0.62f),
            error = colors.danger,
            outline = colors.divider,
        )
    }
    CompositionLocalProvider(
        LocalNocturne provides colors,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(colorScheme = scheme, typography = NodelocTypography, content = content)
    }
}
