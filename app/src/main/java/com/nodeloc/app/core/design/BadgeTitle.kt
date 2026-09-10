package com.nodeloc.app.core.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.model.CustomBadgeStyle
import androidx.compose.material3.Text

/**
 * A user title, coloured and animated the way discourse-custom-badge says.
 *
 * The plugin stores one style per badge name and per group title — a colour and
 * optionally an effect — and the app had modelled all of it and then never
 * asked: `SiteRepository.titleStyle` had no callers, so every title rendered in
 * the same flat accent whatever the site had configured.
 *
 * Styles are fetched once per session and cached, so resolving one here rather
 * than threading it through every screen's state costs a map lookup.
 */
@Composable
fun BadgeTitleText(
    title: String,
    size: Int,
    modifier: Modifier = Modifier,
    fallback: Color = Nocturne.accent,
) {
    var style by remember(title) { mutableStateOf<CustomBadgeStyle?>(null) }
    LaunchedEffect(title) {
        style = runCatching { ServiceLocator.get.siteRepository.titleStyle(title) }.getOrNull()
    }

    val color = style?.textColor?.let(::parseCssColor) ?: fallback
    val effect = style?.textEffect
    val ramp = effect?.let { gradientFor(it, color) }
    val reduceMotion = LocalReduceMotion.current

    // The sweep is the whole point of an effect, so with motion off the colour
    // stands alone rather than freezing a gradient mid-slide, which reads as a
    // rendering fault rather than a decision.
    val shift = if (ramp == null || reduceMotion) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "badge-flow")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationFor(effect), easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "badge-flow-shift",
        ).value
    }

    val textStyle = when {
        effect == GLITCH -> Type.body(size).copy(
            color = color,
            shadow = Shadow(
                color = style?.glitchLeftColor?.let(::parseCssColor) ?: DEFAULT_GLITCH_LEFT,
                offset = Offset(-2f, 0f),
                blurRadius = 1f,
            ),
        )

        ramp != null && !reduceMotion -> Type.body(size).copy(
            brush = Brush.linearGradient(
                colors = ramp,
                // Two widths of travel so the ramp leaves as it arrives; the
                // plugin does the same with a 250–360% background.
                start = Offset(-SWEEP * shift, 0f),
                end = Offset(SWEEP * (1f - shift), 0f),
            ),
        )

        else -> Type.body(size).copy(color = color)
    }

    Text(title, modifier = modifier, style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private const val GLITCH = "glitch-shadow"
private const val SWEEP = 900f
private val DEFAULT_GLITCH_LEFT = Color(0xFF25F4EE)

/** Matches the plugin's per-effect durations, in milliseconds. */
private fun durationFor(effect: String?): Int = when (effect) {
    "laser-sweep" -> 2200
    "shimmer" -> 2800
    "fire-flow" -> 3500
    "dual-flow" -> 3800
    "gold-flow" -> 4400
    "holographic-flow" -> 4800
    "silver-flow", "rainbow-flow" -> 5000
    "ocean-flow" -> 6000
    "aurora-flow" -> 7000
    "galaxy-flow" -> 8000
    else -> 4000
}

/**
 * The plugin builds each ramp from the badge's own colour plus Discourse theme
 * variables. These are those ramps with the variables resolved against this
 * app's palette — `--tertiary` is the accent, `--quaternary` the second one,
 * `--secondary` the page itself, and so on.
 */
@Composable
private fun gradientFor(effect: String, color: Color): List<Color>? {
    val bg = Nocturne.bg
    val accent = Nocturne.accent
    val accent2 = Nocturne.accent2
    val highlight = Nocturne.highlight
    val danger = Nocturne.danger
    val success = Nocturne.success
    val love = Nocturne.love
    val mid = Nocturne.muted(0.55f)
    val high = Nocturne.muted(0.78f)
    return when (effect) {
        "shimmer" -> listOf(color, color, bg.copy(alpha = 0.65f), color, color)
        "gold-flow" -> listOf(color, highlight, accent, highlight, color)
        "silver-flow" -> listOf(color, mid, bg, high, color)
        "rainbow-flow" -> listOf(color, accent, love, danger, highlight, success, accent2, color)
        "aurora-flow" -> listOf(color, success, accent, accent2, success, color)
        "fire-flow" -> listOf(color, danger, highlight, danger, color)
        "ocean-flow" -> listOf(color, accent, accent2, accent.copy(alpha = 0.6f), color)
        "galaxy-flow" -> listOf(color, high, love, accent2, Nocturne.text, color)
        "lava-flow" -> listOf(color, danger, danger, highlight, danger, color)
        "laser-sweep" -> listOf(color, color, bg, accent, bg, color, color)
        "holographic-flow" -> listOf(color, accent, success, accent2, love, accent, color)
        "dual-flow" -> listOf(color, accent, accent, color, color)
        else -> null
    }
}

/**
 * `#rgb`, `#rrggbb` and `#aarrggbb` — the shapes the plugin's validator lets
 * through that a phone can draw. Its other two, `rgb()`/`hsl()` functions and
 * CSS colour names, would each need a parser of their own and no badge on this
 * site uses them; an unreadable value falls back rather than throwing.
 */
private fun parseCssColor(raw: String): Color? {
    val hex = raw.trim().removePrefix("#")
    if (hex.isEmpty() || !hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
    val expanded = when (hex.length) {
        3 -> hex.map { "$it$it" }.joinToString("")
        6, 8 -> hex
        else -> return null
    }
    val value = expanded.toLongOrNull(16) ?: return null
    return if (expanded.length == 8) Color(value) else Color(value or 0xFF000000L)
}
