package com.nodeloc.app.core.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Two families, as on iOS: `heading` for titles, `body` for everything else.
 *
 * Sizes are in sp but the app deliberately does **not** follow the system font
 * scale — the in-app "文本大小" preference only syncs to the server, matching
 * iOS. That keeps the reader's dense layouts (quote nesting, reply rails)
 * predictable.
 */
object Type {
    fun heading(size: Int, weight: FontWeight = FontWeight.Medium): TextStyle =
        TextStyle(fontFamily = FontFamily.Default, fontSize = size.sp, fontWeight = weight)

    fun body(size: Int, weight: FontWeight = FontWeight.Normal): TextStyle =
        TextStyle(fontFamily = FontFamily.Default, fontSize = size.sp, fontWeight = weight)

    fun body(size: Float, weight: FontWeight = FontWeight.Normal): TextStyle =
        TextStyle(fontFamily = FontFamily.Default, fontSize = size.sp, fontWeight = weight)

    /** Post body line spacing: 16sp text with 6sp extra leading. */
    fun lineHeight(size: Int, extra: Int): TextUnit = (size + extra).sp
}

val NodelocTypography = Typography(
    displayLarge = Type.heading(32, FontWeight.Bold),
    headlineLarge = Type.heading(26, FontWeight.Bold),
    headlineMedium = Type.heading(25, FontWeight.Bold),
    headlineSmall = Type.heading(24, FontWeight.SemiBold),
    titleLarge = Type.heading(20, FontWeight.SemiBold),
    titleMedium = Type.heading(17, FontWeight.SemiBold),
    titleSmall = Type.heading(15, FontWeight.SemiBold),
    bodyLarge = Type.body(16),
    bodyMedium = Type.body(14),
    bodySmall = Type.body(12),
    labelLarge = Type.body(14, FontWeight.SemiBold),
    labelMedium = Type.body(13, FontWeight.SemiBold),
    labelSmall = Type.body(11, FontWeight.Medium),
)
