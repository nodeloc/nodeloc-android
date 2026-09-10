package com.nodeloc.app.core.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Telegram-style pull to refresh: crossing the 72dp threshold fires immediately
 * (no release required), with a light haptic, and the indicator is held for at
 * least 600ms so a fast response doesn't blink.
 *
 * Hand-rolled rather than Material's PullToRefreshBox because the design names
 * the Lc loader — with its own fade/scale timing — as the indicator, and that
 * box can't restyle its own animation.
 */
class PullToRefreshState internal constructor(private val thresholdPx: Float) {
    var progress by mutableFloatStateOf(0f)
        private set
    var isRefreshing by mutableStateOf(false)
        internal set

    internal var pull = 0f
    internal var triggered = false

    internal fun updatePull(value: Float) {
        pull = value.coerceAtLeast(0f)
        if (!isRefreshing) progress = (pull / thresholdPx).coerceIn(0f, 1f)
        if (pull <= 2f) triggered = false
    }

    internal fun shouldTrigger(): Boolean = pull >= thresholdPx && !triggered && !isRefreshing

    internal fun reset() {
        pull = 0f
        progress = 0f
    }
}

@Composable
fun rememberPullToRefreshState(): PullToRefreshState {
    val thresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    return remember { PullToRefreshState(thresholdPx) }
}

/**
 * Sends a refreshed list back to the top.
 *
 * A LazyColumn keeps the *keyed* item it was showing in view, so rows that
 * arrive above the viewport stay above it: the pull runs, the list reloads, and
 * the reader is left looking at exactly the row they were already looking at.
 *
 * It has to be `requestScrollToItem` and not `scrollToItem`, for two separate
 * reasons — both of which this got wrong before.
 *
 * `scrollToItem` is a scroll *mutation*, and this box fires the refresh
 * mid-drag rather than on release. A `Default` mutation raised while the finger
 * still holds the list at `UserInput` priority is not queued behind it, it is
 * cancelled outright — and that cancellation then disappears into the
 * `runCatching` around `onRefresh`.
 *
 * Worse, when it did run it undid itself. `scrollToItem` force-remeasures
 * immediately, which happens *before* the new list has been composed, so it
 * re-recorded the old first item's key — and the measure that then brought the
 * new rows in anchored right back to it.
 *
 * `requestScrollToItem` does neither: it takes no mutex, and it asks for the
 * position at the *next* measure, which is the measure that carries the new
 * rows. That is the pass whose anchoring needed overriding.
 */
fun LazyListState.requestScrollToTop() {
    requestScrollToItem(0)
}

/**
 * Wraps a scrollable and drives [state] from its over-scroll at the top.
 * The content is *not* offset: the indicator floats over the rubber-band gap,
 * matching iOS where the list itself stays put.
 */
@Composable
fun PullToRefreshBox(
    state: PullToRefreshState,
    onRefresh: suspend () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    val connection = remember(state) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    state.updatePull(state.pull + available.y)
                    if (state.shouldTrigger()) {
                        state.triggered = true
                        state.isRefreshing = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch {
                            val start = System.currentTimeMillis()
                            runCatching { onRefresh() }
                            val elapsed = System.currentTimeMillis() - start
                            if (elapsed < 600) delay(600 - elapsed)
                            state.isRefreshing = false
                            state.reset()
                        }
                    }
                }
                return Offset.Zero
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f && state.pull > 0f) state.updatePull(state.pull + available.y)
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!state.isRefreshing) state.reset()
                return Velocity.Zero
            }
        }
    }

    Box(modifier.nestedScroll(connection)) {
        content()
        if (state.progress > 0.02f || state.isRefreshing) {
            NodelocLoader(
                height = 26.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .alpha(if (state.isRefreshing) 1f else 0.25f + 0.75f * state.progress)
                    .scale(if (state.isRefreshing) 1f else 0.7f + 0.3f * state.progress),
            )
        }
    }
}

