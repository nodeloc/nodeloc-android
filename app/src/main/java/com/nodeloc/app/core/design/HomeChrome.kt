package com.nodeloc.app.core.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * How far the home page's chrome has folded away: 0 is all shown, 1 is the
 * sort row tucked under the bar and the bottom tab bar off the screen. The
 * two move as one, finger for finger.
 *
 * The navigation bar itself never moves. The fold opens again only on a fast
 * pull back towards the top, or once the list is back at its top — a slow
 * drag down to reread something leaves the reading room alone.
 */
object HomeChrome {
    var collapse by mutableFloatStateOf(0f)
        private set

    private var animation: Job? = null

    /** Pixels of finger travel the fold spans; set from the sort row's measured height. */
    var spanPx = 1f

    fun reset() {
        animation?.cancel()
        collapse = 0f
    }

    /** Back at the top: everything shows. */
    fun expand(scope: CoroutineScope) = animateTo(scope, 0f)

    private fun animateTo(scope: CoroutineScope, target: Float) {
        if (collapse == target) return
        animation?.cancel()
        animation = scope.launch {
            val anim = Animatable(collapse)
            anim.animateTo(target, tween(durationMillis = 220)) { collapse = value }
        }
    }

    /**
     * Folds on an upward drag as the list moves. Consumes nothing: the list
     * scrolls exactly as it would without it.
     */
    fun connection(scope: CoroutineScope): NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y < 0f) {
                animation?.cancel()
                collapse = (collapse - available.y / spanPx).coerceIn(0f, 1f)
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            // A deliberate flick back up the page: the reader is heading for
            // the chrome, so it comes to meet them.
            if (available.y > FAST_PULL_BACK) animateTo(scope, 0f)
            // Left half-folded, it settles to the nearer end.
            else if (available.y < 0f || collapse in 0.01f..0.99f) animateTo(scope, if (collapse > 0.5f || available.y < 0f) 1f else 0f)
            return Velocity.Zero
        }
    }

    /** px/s: about what a flick, rather than a drag, reaches. */
    private const val FAST_PULL_BACK = 2400f
}
