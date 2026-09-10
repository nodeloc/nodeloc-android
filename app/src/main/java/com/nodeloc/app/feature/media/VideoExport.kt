package com.nodeloc.app.feature.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.graphics.scale
import androidx.media3.common.MediaItem
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.nodeloc.app.core.util.runCatchingCancellable
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** GIF export is capped: past this the file stops being sendable. */
private const val GIF_MAX_SECONDS = 10
private const val GIF_FPS = 8
private const val GIF_MAX_EDGE = 480

/** How often the encoder is asked how far along it is. */
private const val PROGRESS_POLL_MS = 150L

/**
 * The clip, as the editor left it.
 *
 * [onProgress] is called with 0..1 on the caller thread. Media3 will only
 * answer once the encoder has started, so it stays at zero for the first
 * second or so of a long clip; that is worth showing anyway, because the
 * alternative is a spinner that means nothing for two minutes.
 */
@androidx.annotation.OptIn(UnstableApi::class)
suspend fun exportClip(
    context: Context,
    source: Uri,
    probe: VideoSource,
    edit: VideoEdit,
    onProgress: (Float) -> Unit,
): TrimmedMedia = withContext(Dispatchers.Main) {
    val output = File(context.cacheDir, "trim-${System.currentTimeMillis()}.mp4")
    val mediaItem = MediaItem.Builder()
        .setUri(source)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(edit.startMs)
                .setEndPositionMs(edit.endMs)
                .build(),
        )
        .build()

    val edited = EditedMediaItem.Builder(mediaItem)
        .setRemoveAudio(edit.muted || !probe.hasAudio)
        .setEffects(Effects(emptyList(), videoEffects(probe, edit)))
        .build()

    val scope: CoroutineScope = this
    suspendCancellableCoroutine { continuation ->
        var poller: kotlinx.coroutines.Job? = null
        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    poller?.cancel()
                    // Transformer calls back on the thread it was built on,
                    // which is the main one, and the poster seeks the source.
                    // That belongs on IO.
                    scope.launch {
                        val media = runCatchingCancellable {
                            withContext(Dispatchers.IO) {
                                // The file stays; whoever uploads it deletes it.
                                TrimmedMedia(
                                    output,
                                    "video-${System.currentTimeMillis()}.mp4",
                                    "video/mp4",
                                    posterFrame(context, source, edit.startMs, edit),
                                    edit,
                                )
                            }
                        }
                        media
                            .onSuccess { continuation.resume(it) }
                            // Not `cancel()`: the caller distinguishes a failed
                            // export from an abandoned one, and a clip that will
                            // not read back is the former.
                            .onFailure {
                                output.delete()
                                continuation.resumeWithException(it)
                            }
                    }
                }

                override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                    poller?.cancel()
                    output.delete()
                    continuation.resumeWithException(exception)
                }
            })
            .build()

        transformer.start(edited, output.absolutePath)
        poller = scope.launch {
            val holder = ProgressHolder()
            while (isActive) {
                // Has to be asked on the thread it was built on, which is why
                // this is a Main-dispatched job and not an IO one.
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress / 100f)
                }
                delay(PROGRESS_POLL_MS)
            }
        }
        continuation.invokeOnCancellation {
            poller.cancel()
            transformer.cancel()
            output.delete()
        }
    }
}

/**
 * What the encoder is asked to do to each frame, in order.
 *
 * Media3 hands the chain frames that the decoder has already turned upright
 * (`VideoEncoderGraphInput.applyDecoderRotation`), so all of this is in the
 * orientation the person was looking at while they made the decisions.
 *
 * Kept as short as it can be: an empty list is a container rewrite, one that
 * is only a right-angle turn is an orientation hint, and anything else is a
 * full re-encode. Adding an effect that changes nothing costs minutes.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun videoEffects(probe: VideoSource, edit: VideoEdit): List<Effect> {
    val effects = mutableListOf<Effect>()
    // Turn first, crop second: the crop is fractions of the frame as the person
    // saw it, and they saw it after the turn.
    if (edit.rotation != 0) {
        // The transformation turns anticlockwise and the person turned
        // clockwise, so the two have to be read against each other.
        effects += ScaleAndRotateTransformation.Builder()
            .setRotationDegrees((360 - edit.rotation).toFloat() % 360f)
            .build()
    }
    edit.crop?.let { box ->
        // Normalised device coordinates: -1..1 with y pointing up, which is
        // neither the range nor the direction the crop frame was drawn in.
        effects += Crop(
            2f * box.left - 1f,
            2f * box.right - 1f,
            1f - 2f * box.bottom,
            1f - 2f * box.top,
        )
    }
    val cap = edit.quality.shortSide
    // Against the cropped frame, not the original: a tight crop of a 4K clip
    // can already be under the cap, and asking for the cap anyway would scale
    // it back up to meet it.
    if (cap != null && edit.croppedShortSide(probe) > cap) {
        effects += Presentation.createForShortSide(cap)
    }
    return effects
}

/**
 * Frames are sampled with MediaMetadataRetriever rather than decoded in real
 * time: at 8fps a 10-second clip is 80 seeks, which is slower than playback but
 * has no surface, no threading and no lifecycle to get wrong.
 */
suspend fun exportGif(
    context: Context,
    source: Uri,
    edit: VideoEdit,
    onProgress: (Float) -> Unit,
): TrimmedMedia = withContext(Dispatchers.IO) {
    val clampedEnd = minOf(edit.endMs, edit.startMs + GIF_MAX_SECONDS * 1000L)
    val span = clampedEnd - edit.startMs
    val frameCount = ((span / 1000f) * GIF_FPS).toInt().coerceIn(2, GIF_MAX_SECONDS * GIF_FPS)
    val stepMs = span / frameCount

    val frames = mutableListOf<Bitmap>()
    withRetriever(context, source) { retriever ->
        repeat(frameCount) { index ->
            // Each seek blocks for tens of milliseconds and nothing inside the
            // loop suspends, so without this a cancelled export keeps decoding
            // for seconds after the screen is gone.
            ensureActive()
            val timeUs = (edit.startMs + index * stepMs) * 1000
            // CLOSEST, not CLOSEST_SYNC: sync frames are keyframes, and with a
            // two-second GOP every one of the sixteen samples in that window
            // returned the same picture, a "GIF" that did not move.
            val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            if (frame != null) frames += scaleForGif(frame)
            onProgress((index + 1f) / frameCount)
        }
    }
    if (frames.isEmpty()) error("no frames")

    val bytes = GifEncoder.encode(frames, delayMs = 1000 / GIF_FPS)
    // Onto disk like the clip, so one path uploads both.
    val output = File(context.cacheDir, "clip-${System.currentTimeMillis()}.gif")
    output.writeBytes(bytes)
    TrimmedMedia(output, output.name, "image/gif", posterBytes = null, edit = edit)
}

/** How long a GIF is allowed to be before the rest is dropped. */
fun gifCapSeconds(): Int = GIF_MAX_SECONDS

private fun scaleForGif(frame: Bitmap): Bitmap {
    val longest = maxOf(frame.width, frame.height)
    if (longest <= GIF_MAX_EDGE) return frame
    val scale = GIF_MAX_EDGE.toFloat() / longest
    return frame.scale(
        (frame.width * scale).toInt().coerceAtLeast(2),
        (frame.height * scale).toInt().coerceAtLeast(2),
    )
}
