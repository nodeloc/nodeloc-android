package com.nodeloc.app.core.design

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nodeloc.app.R

/**
 * The app's one loading indicator.
 *
 * A plain circular indicator rather than a branded animation. A spinner is read,
 * not looked at: it appears when something is late, which is the worst moment to
 * ask for attention — and the brand already gets a full run of the logo on the
 * splash screen.
 *
 * Kept as a wrapper rather than calling Material directly at ~25 sites so size,
 * colour and the "loading" label stay decided in one place.
 */
@Composable
fun NodelocLoader(
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    tint: Color? = null,
) {
    val loadingLabel = stringResource(R.string.common_loading)
    val stroke = (height / 12f).coerceIn(2.dp, 4.dp)
    val color = tint ?: Nocturne.accent

    if (LocalReduceMotion.current) {
        // A quarter arc, held still: the shape still says "waiting" without
        // anything moving for a reader who asked for nothing to move.
        CircularProgressIndicator(
            progress = { 0.25f },
            modifier = modifier.size(height).semantics { contentDescription = loadingLabel },
            color = color,
            trackColor = Color.Transparent,
            strokeWidth = stroke,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
        )
    } else {
        CircularProgressIndicator(
            modifier = modifier.size(height).semantics { contentDescription = loadingLabel },
            color = color,
            trackColor = Color.Transparent,
            strokeWidth = stroke,
            strokeCap = StrokeCap.Round,
        )
    }
}
