package com.nodeloc.app.core.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Glyphs drawn here rather than taken from `icons-lucide`.
 *
 * The artifact's last release is 1.1.0, from December 2024, and Lucide has
 * redrawn icons since. Where the redraw is visible, the current drawing is
 * transcribed from lucide.dev and kept here; everything else still comes from
 * the library, which is where new icons should be looked for first.
 */
object NodelocIcons {
    /**
     * `zap`, as lucide.dev draws it now: the same bolt as 1.1.0's, but with
     * 1.5-unit corners instead of 1-unit and blunter tips. Close enough to pass
     * alone, different enough to notice beside the real one — and the reward
     * plugin's web sprite carries this same path, so a tip is one mark on the
     * phone and in the browser.
     */
    val Zap: ImageVector by lazy {
        ImageVector.Builder(
            name = "zap",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(ZAP_PATH),
            // Tinted by Icon's colour filter, so the colour set here is only a
            // placeholder; what matters is that the shape is stroked, not
            // filled, which is what makes it an outline icon.
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
    }
}

private const val ZAP_PATH =
    "M15.914 4a1.5 1.5 0 0 0 -2.474 -1.561l-9 9A1.5 1.5 0 0 0 5.5 14h4.002" +
        "a.5 .5 0 0 1 .471 .666L8.086 20a1.5 1.5 0 0 0 2.475 1.56l9 -9" +
        "A1.5 1.5 0 0 0 18.5 10h-3.997a.5 .5 0 0 1 -.472 -.667z"
