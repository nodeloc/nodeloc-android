package com.nodeloc.app.core.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.nodeloc.app.R

/**
 * A ring that fills as the upload goes up, with the way out in the middle.
 *
 * Only worth drawing for something slow enough to watch, which in this app
 * means video: an image is gone before the first arc is drawn. Cancelling is
 * part of the same control rather than a second one, because the only reason
 * to look at the number is to decide whether to keep waiting.
 */
@Composable
fun UploadProgressRing(
    progress: Float,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
    diameter: androidx.compose.ui.unit.Dp = 42.dp,
) {
    Box(
        modifier
            .size(diameter)
            .let { if (onCancel == null) it else it.clickable(onClick = onCancel) },
        contentAlignment = Alignment.Center,
    ) {
        val track = Nocturne.text.copy(alpha = 0.25f)
        val accent = Nocturne.accent
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2f
            val box = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = box,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = accent,
                startAngle = -90f,
                // Never a full ring until it truly is: a closed circle at 99%
                // reads as finished, and then nothing happens for a while.
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = box,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        if (onCancel != null) {
            Icon(
                Lucide.X,
                stringResource(R.string.common_cancel),
                tint = Nocturne.text,
                modifier = Modifier.size(diameter / 2.6f),
            )
        }
    }
}
