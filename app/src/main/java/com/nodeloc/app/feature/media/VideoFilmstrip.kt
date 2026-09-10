package com.nodeloc.app.feature.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import com.nodeloc.app.core.design.Nocturne
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** How tall the strip is, and how many stills are laid along it. */
private val STRIP_HEIGHT = 52.dp
private const val STRIP_FRAMES = 10
private const val THUMB_PIXELS = 140

/** The grabbable bar at each end of the selection. */
private val HANDLE_WIDTH = 14.dp
private val HANDLE_TOUCH = 30.dp

/** What the drag is currently moving. */
private enum class StripGrip { Start, End, Playhead }

/**
 * The clip laid out end to end, with the kept part between two handles.
 *
 * A bare range slider says how long the selection is and nothing whatever
 * about what is in it, which means trimming is done by scrubbing back and
 * forth and remembering. Stills along the track make the decision visible:
 * the handles land on a shot rather than on a number of seconds.
 */
@Composable
fun VideoFilmstrip(
    source: Uri,
    probe: VideoSource,
    startMs: Long,
    endMs: Long,
    playheadMs: Long,
    onRangeChange: (Long, Long) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val frames = remember(source) { mutableStateListOf<Bitmap>() }

    LaunchedEffect(source) {
        // Sampled once at a size a strip can show. Ten seeks is under a second
        // on anything current, and holding ten 140px stills is a rounding error
        // next to the clip itself.
        val sampled = withContext(Dispatchers.IO) { stripFrames(context, source, probe) }
        frames.clear()
        frames.addAll(sampled)
    }

    // The gesture reads these every move, and a pointerInput block that closed
    // over the values instead would keep reporting whatever they were when it
    // was installed. That bug has been paid for once already in this app.
    val start by rememberUpdatedState(startMs)
    val end by rememberUpdatedState(endMs)
    val onRange by rememberUpdatedState(onRangeChange)
    val seek by rememberUpdatedState(onSeek)

    val density = LocalDensity.current
    val touchPx = with(density) { HANDLE_TOUCH.toPx() }
    val handlePx = with(density) { HANDLE_WIDTH.toPx() }
    var width by remember { mutableStateOf(0f) }
    var grip by remember { mutableStateOf<StripGrip?>(null) }

    fun msAt(x: Float): Long =
        (x / width.coerceAtLeast(1f) * probe.durationMs).toLong().coerceIn(0L, probe.durationMs)

    fun xOf(ms: Long): Float = ms.toFloat() / probe.durationMs.coerceAtLeast(1L) * width

    Box(
        modifier
            .fillMaxWidth()
            .height(STRIP_HEIGHT)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .background(Color(0xFF17171A))
            // Measured here rather than read off the draw scope: assigning
            // state during a draw asks for the next frame to draw again.
            .onSizeChanged { width = it.width.toFloat() }
            .pointerInput(probe) {
                detectTapGestures { offset -> seek(msAt(offset.x)) }
            }
            .pointerInput(probe) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val toStart = abs(offset.x - xOf(start))
                        val toEnd = abs(offset.x - xOf(end))
                        grip = when {
                            toStart <= touchPx && toStart <= toEnd -> StripGrip.Start
                            toEnd <= touchPx -> StripGrip.End
                            else -> StripGrip.Playhead
                        }
                        if (grip == StripGrip.Playhead) seek(msAt(offset.x))
                    },
                    onDragEnd = { grip = null },
                    onDragCancel = { grip = null },
                ) { change, _ ->
                    change.consume()
                    val at = msAt(change.position.x)
                    when (grip) {
                        // A selection is not allowed to close up: below this the
                        // handles overlap and neither can be picked up again.
                        StripGrip.Start -> onRange(at.coerceAtMost(end - MIN_SELECTION_MS), end)
                        StripGrip.End -> onRange(start, at.coerceAtLeast(start + MIN_SELECTION_MS))
                        StripGrip.Playhead -> seek(at)
                        null -> Unit
                    }
                }
            },
    ) {
        Row(Modifier.fillMaxSize()) {
            frames.forEach { frame ->
                androidx.compose.foundation.Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                )
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val left = xOf(startMs)
            val right = xOf(endMs)
            drawTrimOverlay(left, right, handlePx, playheadMs.let(::xOf))
        }
    }
}

/** Below this the two handles overlap and the selection cannot be reopened. */
private const val MIN_SELECTION_MS = 400L

/**
 * The dimming, the frame and the two grips.
 *
 * Everything outside the selection is knocked back rather than hidden: what
 * was cut is still worth seeing, because deciding a trim is mostly deciding
 * where the uninteresting part starts.
 */
private fun DrawScope.drawTrimOverlay(left: Float, right: Float, handleWidth: Float, playhead: Float) {
    val dim = Color(0xCC0B0B0C)
    drawRect(dim, Offset.Zero, Size(left.coerceAtLeast(0f), size.height))
    drawRect(dim, Offset(right, 0f), Size((size.width - right).coerceAtLeast(0f), size.height))

    val accent = Color(0xFFF5C518)
    val rail = 3.dp.toPx()
    drawRect(accent, Offset(left, 0f), Size((right - left).coerceAtLeast(0f), rail))
    drawRect(accent, Offset(left, size.height - rail), Size((right - left).coerceAtLeast(0f), rail))

    // Drawn inward from each edge so the whole grip stays on the strip even
    // when the selection runs to the very end of the clip.
    drawHandle(left, handleWidth, accent)
    drawHandle(right - handleWidth, handleWidth, accent)

    if (playhead in left..right) {
        drawRect(Color.White, Offset(playhead - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
    }
}

private fun DrawScope.drawHandle(x: Float, width: Float, colour: Color) {
    drawRect(colour, Offset(x, 0f), Size(width, size.height))
    val gripWidth = 1.5.dp.toPx()
    val gripHeight = size.height * 0.34f
    val centre = x + width / 2f
    drawRect(
        Color(0xFF17171A),
        Offset(centre - gripWidth / 2f, (size.height - gripHeight) / 2f),
        Size(gripWidth, gripHeight),
    )
}

/** Stills at even intervals across the whole clip, small enough to hold. */
private fun stripFrames(context: Context, source: Uri, probe: VideoSource): List<Bitmap> =
    runCatching {
        withRetriever(context, source) { retriever ->
            (0 until STRIP_FRAMES).mapNotNull { index ->
                val at = probe.durationMs * index / STRIP_FRAMES * 1000
                retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { frame ->
                    val ratio = THUMB_PIXELS.toFloat() / maxOf(frame.height, 1)
                    if (ratio >= 1f) frame
                    else frame.scale(
                        (frame.width * ratio).toInt().coerceAtLeast(2),
                        THUMB_PIXELS,
                    )
                }
            }
        }
    }.getOrDefault(emptyList())
