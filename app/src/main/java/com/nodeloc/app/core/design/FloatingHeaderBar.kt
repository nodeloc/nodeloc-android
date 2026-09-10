package com.nodeloc.app.core.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The floating header shared by the root screens.
 *
 * It rides above the content rather than pushing it down. At rest it is
 * nothing — the page's own top edge, banner and all, is what the reader sees.
 * Once the page scrolls beneath it, it fills in — flatly, with no divider: the
 * fill alone is what separates the bar from the page, and a rule under it only
 * draws a line across a page that has none anywhere else.
 */
@Composable
fun FloatingHeaderBar(
    modifier: Modifier = Modifier,
    /** True once content has scrolled under the bar; fills it in. */
    scrolled: Boolean = false,
    /**
     * True when the bar rests over a picture — a node banner, a profile header.
     * A bare dark glyph on someone's photograph is a coin toss, so while the
     * bar is still clear it lays a scrim over the top of the image and turns
     * its own controls white. Both undo themselves as the bar fills in.
     */
    overMedia: Boolean = false,
    leading: @Composable () -> Unit,
    center: @Composable () -> Unit = {},
    centerVisible: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    // Asymmetric on purpose. Filling in has to be instant: the content is
    // already under the bar by the time this flips, so fading the fill in shows
    // that content through a half-painted bar — which is the flash. Clearing it
    // can take its time, because by then there is nothing underneath.
    val fill by animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        animationSpec = if (scrolled) snap() else tween(durationMillis = 220),
        label = "headerFill",
    )
    val onMedia = overMedia && fill < 1f

    // The system's own clock and icons sit inside this bar's area, so they have
    // to follow it: dark glyphs vanish into the scrim exactly the way the app's
    // own did. Restored on the way out, or the next screen inherits it.
    val view = LocalView.current
    val darkTheme = Nocturne.isDark
    DisposableEffect(onMedia, darkTheme, view) {
        val controller = (view.context as? android.app.Activity)?.window
            ?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = if (onMedia) false else !darkTheme
        onDispose { controller?.isAppearanceLightStatusBars = !darkTheme }
    }
    val content = lerp(Nocturne.headerText, Color.White, if (overMedia) 1f - fill else 0f)
    Box(
        modifier
            .fillMaxWidth()
            .background(Nocturne.headerBg.copy(alpha = fill))
            // The bar covers the page, so it has to answer for the area it
            // covers: without this a tap in the empty space beside the title
            // falls through and opens whatever row happens to be underneath.
            // Its own controls sit deeper in the tree and are dispatched first,
            // so they keep their taps; only what they leave is swallowed.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { if (!it.isConsumed) it.consume() }
                    }
                }
            },
    ) {
        if (onMedia) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.38f * (1f - fill)),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
        }
        CompositionLocalProvider(LocalHeaderContentColor provides content) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(
                    horizontal = FloatingHeader.horizontalInset,
                    vertical = (FloatingHeader.barHeight - FloatingHeader.touchHeight) / 2,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            leading()
            Spacer(Modifier.weight(1f))
            trailing()
        }
        // Centred on the bar, not between the controls: the two sides are not
        // the same width (a login chip is wider than a menu button), and a
        // wordmark that shifts with them does not read as centred at all.
        Box(
            Modifier
                .align(Alignment.Center)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(vertical = (FloatingHeader.barHeight - FloatingHeader.touchHeight) / 2)
                .height(FloatingHeader.touchHeight),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(visible = centerVisible, enter = fadeIn(), exit = fadeOut()) {
                center()
            }
        }
        }
    }
}

/**
 * What the bar's own controls are drawn in. It changes with what is behind the
 * bar, so controls read it rather than reaching for a fixed token.
 */
val LocalHeaderContentColor = androidx.compose.runtime.compositionLocalOf { Color.Unspecified }

/**
 * How much room the floating header takes: its own 48dp control plus padding,
 * plus the status bar it sits under.
 *
 * Every screen with a floating header must open its list this far down, or the
 * first row starts life underneath the header — which looks like a layout bug
 * on exactly the screen a reader sees first.
 */
val floatingHeaderInset: Dp
    @Composable get() =
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + FloatingHeader.barHeight

/** True once the list has scrolled past the first row — drives header fades. */
@Composable
fun LazyListState.isScrolledPastTop(threshold: Int = 12): Boolean {
    val scrolled by remember(this) {
        derivedStateOf { firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > threshold }
    }
    return scrolled
}

/**
 * Fires [onLoadMore] when the tail sentinel comes into view. Cheap because it
 * only reads the last visible index, not the whole layout info.
 */
@Composable
fun LazyListState.OnReachedEnd(buffer: Int = 3, onLoadMore: () -> Unit) {
    val shouldLoad by remember(this) {
        derivedStateOf {
            val total = layoutInfo.totalItemsCount
            val last = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            total > 0 && last >= total - 1 - buffer
        }
    }
    androidx.compose.runtime.LaunchedEffect(shouldLoad) {
        if (shouldLoad) onLoadMore()
    }
}
