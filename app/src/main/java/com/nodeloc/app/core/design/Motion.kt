package com.nodeloc.app.core.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The 2026-11 design's motion, in one place so every control moves alike:
 * a press shrinks a control to 95% (`button:active { scale: .95 }`), and
 * colour changes ease over 180ms instead of snapping. Both are off when the
 * reader asked the system to reduce motion — the design's own
 * `prefers-reduced-motion` rule, which [LocalReduceMotion] carries here.
 */
object Motion {
    const val PRESSED_SCALE = 0.95f
    const val COLOR_MILLIS = 180
}

/**
 * Shrinks while pressed. Pass the same [interactionSource] the control's
 * `clickable` uses, so the scale follows the real press (and its cancel when a
 * scroll steals the gesture) rather than a guess.
 */
fun Modifier.pressScale(interactionSource: InteractionSource): Modifier = composed {
    if (LocalReduceMotion.current) return@composed this
    val pressed by interactionSource.collectIsPressedAsState()
    // A spring, not a tween: a quick tap releases before a 120ms tween would
    // have shown anything, and the spring still gives it a visible give.
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PRESSED_SCALE else 1f,
        animationSpec = spring(stiffness = 1400f, dampingRatio = 0.7f),
        label = "pressScale",
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** An interaction source to share between `clickable` and [pressScale]. */
@Composable
fun rememberPressSource(): MutableInteractionSource = remember { MutableInteractionSource() }

/** A colour that eases to its new value (selection, state), or snaps under reduce-motion. */
@Composable
fun animatedColor(target: Color, label: String = "color"): State<Color> =
    animateColorAsState(
        targetValue = target,
        animationSpec = if (LocalReduceMotion.current) tween(0) else tween(Motion.COLOR_MILLIS, easing = FastOutSlowInEasing),
        label = label,
    )
