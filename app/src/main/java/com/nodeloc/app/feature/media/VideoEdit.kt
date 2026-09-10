package com.nodeloc.app.feature.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.StringRes
import androidx.core.graphics.scale
import com.nodeloc.app.R
import java.io.File

/**
 * What the editor hands back.
 *
 * A file on disk, not the bytes. A minute of 1080p is around a hundred
 * megabytes; reading that into an array — and then handing the same array to
 * the request body, and hashing it again for the poster — is three copies of a
 * thing that never needed to be in memory once. The caller deletes it when the
 * upload is done with it.
 */
data class TrimmedMedia(
    val file: File,
    val fileName: String,
    val mimeType: String,
    /** Poster frame; uploaded separately and linked by the clip's own digest. */
    val posterBytes: ByteArray?,
    /**
     * What was decided to make it.
     *
     * Carried so a composer can reopen the editor on the same clip with the
     * same trim and crop already in place. Without it, going back in starts
     * from nothing and the second edit is done blind.
     */
    val edit: VideoEdit = VideoEdit(),
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * The clip as it arrived, measured once.
 *
 * [width] and [height] are as displayed — a phone films 1920x1080 and writes
 * `rotation: 90` beside it, and everything the editor does is in terms of what
 * the person is looking at, not what the container happens to store.
 */
data class VideoSource(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val hasAudio: Boolean,
    /** Bits per second of the whole file, or 0 when the container will not say. */
    val bitrate: Int,
) {
    val aspect: Float get() = if (height > 0) width.toFloat() / height else 1f
    val shortSide: Int get() = minOf(width, height)
}

/**
 * How large the clip goes out.
 *
 * Named by short side rather than by width, because a portrait video and a
 * landscape one at "720p" should cost about the same — which is true of the
 * short side and not of either dimension on its own.
 */
enum class VideoQuality(@StringRes val labelRes: Int, val shortSide: Int?, val bitrate: Int) {
    Low(R.string.video_quality_480, 480, 1_200_000),
    Medium(R.string.video_quality_720, 720, 2_500_000),
    High(R.string.video_quality_1080, 1080, 4_500_000),

    /** Untouched, and the only rung that can avoid a re-encode entirely. */
    Original(R.string.video_quality_original, null, 0),
}

/**
 * A rectangle of the frame as displayed, each edge a fraction from 0 to 1.
 *
 * As displayed, meaning after any turn the person has applied — so the export
 * crops after it rotates, and the frame drawn over the preview needs no
 * conversion to become the crop that is actually made. The alternative, holding
 * these in the pre-rotation frame, means every drag on a turned clip goes
 * through a change of basis, and every bug in it looks like a crop that drifts.
 */
data class CropFraction(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /**
     * Where the kept rectangle sits, as a fraction of the frame.
     *
     * The preview is placed from the centre outwards rather than from a corner:
     * a centred crop then needs no displacement at all, which is a thing that
     * can be checked by looking rather than by arithmetic. Placing from a
     * corner needs the same displacement expressed against wherever the layout
     * happened to put the picture, and getting that wrong shows the bottom of
     * every clip whatever was cropped.
     */
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    companion object {
        val Whole = CropFraction(0f, 0f, 1f, 1f)
    }
}

/** Everything the editor decided, and the only thing the export reads. */
data class VideoEdit(
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val quality: VideoQuality = VideoQuality.Medium,
    val muted: Boolean = false,
    /** Clockwise, as the person turned it: 0, 90, 180 or 270. */
    val rotation: Int = 0,
    /** Fractions of the displayed frame, or null for the whole of it. */
    val crop: CropFraction? = null,
    val asGif: Boolean = false,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

    /** Whether the turn swaps what width and height mean. */
    val turned: Boolean get() = rotation % 180 != 0

    fun displayWidth(source: VideoSource): Int = if (turned) source.height else source.width

    fun displayHeight(source: VideoSource): Int = if (turned) source.width else source.height

    /**
     * Whether the frames themselves have to be redrawn.
     *
     * Worth knowing because Media3 will otherwise rewrite the container and
     * leave the samples alone, which takes a second or two rather than the
     * minutes a re-encode of the same clip costs.
     *
     * Turning is absent on purpose. A right-angle rotation with nothing else
     * beside it is applied by Media3 as the container orientation hint rather
     * than by redrawing anything — see `maybeSetMuxerWrapperAdditionalRotation
     * Degrees` — so on its own it is free, and only costs when a crop or a
     * scale has already committed us to re-encoding.
     */
    fun reencodes(source: VideoSource): Boolean =
        crop != null || (quality.shortSide != null && source.shortSide > quality.shortSide)

    /** The short side once the crop has taken its bite; what the cap applies to. */
    fun croppedShortSide(source: VideoSource): Int {
        val box = crop ?: CropFraction.Whole
        return minOf(displayWidth(source) * box.width, displayHeight(source) * box.height).toInt()
    }
}

/**
 * Roughly how many bytes the export will be.
 *
 * A guess, and shown as one. When nothing forces a re-encode it is the source
 * bitrate over the trimmed length, which is close to exact; when something does,
 * it is the rung nominal bitrate, which an encoder is free to miss in either
 * direction. Either is worth more than no number at all: the whole point of the
 * quality row is choosing between sizes, and that cannot be done without seeing
 * them.
 */
fun estimateBytes(source: VideoSource, edit: VideoEdit): Long {
    val seconds = edit.durationMs / 1000.0
    if (seconds <= 0) return 0L
    val keepsAudio = source.hasAudio && !edit.muted
    val bits = if (edit.reencodes(source) && edit.quality.bitrate > 0) {
        // A rung is a video bitrate, so the audio is added on top of it.
        // Cropping throws pixels away, and fewer pixels need fewer bits.
        val area = edit.crop?.let { (it.width * it.height).coerceIn(0.05f, 1f) } ?: 1f
        edit.quality.bitrate * area + if (keepsAudio) AUDIO_BITRATE else 0
    } else {
        // METADATA_KEY_BITRATE is the whole container and the audio is already
        // inside it, so this direction subtracts rather than adds. Counting it
        // twice put every untouched clip over by the length of its own sound.
        val whole = if (source.bitrate > 0) {
            source.bitrate.toFloat()
        } else {
            VideoQuality.Medium.bitrate.toFloat() + AUDIO_BITRATE
        }
        if (source.hasAudio && edit.muted) (whole - AUDIO_BITRATE).coerceAtLeast(whole * 0.5f) else whole
    }
    return (bits * seconds / 8).toLong()
}

private const val AUDIO_BITRATE = 128_000

/** "12.4 MB", or "840 KB" when that reads better. */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%d KB".format((bytes / 1024).coerceAtLeast(1))
}

/** "1:04", or "12.4s" under a minute where the tenths still mean something. */
fun formatClipLength(ms: Long): String {
    val seconds = ms / 1000.0
    return if (seconds < 60) "%.1fs".format(seconds)
    else "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
}

/** Everything about the clip that the editor needs before it can draw. */
fun probeVideo(context: Context, source: Uri): VideoSource? = runCatching {
    withRetriever(context, source) { retriever ->
        val stored = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val storedHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        // A quarter turn either way swaps what the two numbers mean.
        val turned = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0) % 180 != 0
        VideoSource(
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
            width = if (turned) storedHeight else stored,
            height = if (turned) stored else storedHeight,
            hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
            bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0,
        )
    }
}.getOrNull()?.takeIf { it.durationMs > 0 && it.width > 0 && it.height > 0 }

/**
 * The still that stands in for the clip.
 *
 * JPEG, and no bigger than a page will draw it. It used to be a PNG at full
 * quality and full resolution: several megabytes of lossless photograph
 * uploaded alongside every video, to be shown at a few hundred pixels.
 */
fun posterFrame(context: Context, source: Uri, atMs: Long, edit: VideoEdit? = null): ByteArray? = runCatching {
    withRetriever(context, source) { retriever ->
        retriever.getFrameAtTime(atMs * 1000)?.let { frame ->
            // The still has to agree with the clip, or the card in the feed
            // shows a picture the video never contains.
            val shaped = edit?.let { shapeLikeExport(frame, it) } ?: frame
            val longest = maxOf(shaped.width, shaped.height)
            val shown = if (longest <= POSTER_MAX_EDGE) {
                shaped
            } else {
                val ratio = POSTER_MAX_EDGE.toFloat() / longest
                shaped.scale(
                    (shaped.width * ratio).toInt().coerceAtLeast(2),
                    (shaped.height * ratio).toInt().coerceAtLeast(2),
                )
            }
            val stream = java.io.ByteArrayOutputStream()
            shown.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            stream.toByteArray()
        }
    }
}.getOrNull()

private const val POSTER_MAX_EDGE = 1280

/**
 * The same turn and crop the encoder will make, applied to one frame.
 *
 * In that order, because the crop is expressed against the displayed frame.
 * Getting the two the wrong way round on a portrait clip produces a still that
 * is a plausible-looking crop of the wrong part of the picture, which is the
 * kind of wrong that survives review.
 */
private fun shapeLikeExport(frame: Bitmap, edit: VideoEdit): Bitmap {
    val turned = if (edit.rotation == 0) {
        frame
    } else {
        val matrix = android.graphics.Matrix().apply { postRotate(edit.rotation.toFloat()) }
        Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
    }
    return edit.crop?.let { box ->
        val left = (box.left * turned.width).toInt().coerceIn(0, turned.width - 1)
        val top = (box.top * turned.height).toInt().coerceIn(0, turned.height - 1)
        val width = (box.width * turned.width).toInt().coerceIn(1, turned.width - left)
        val height = (box.height * turned.height).toInt().coerceIn(1, turned.height - top)
        Bitmap.createBitmap(turned, left, top, width, height)
    } ?: turned
}

/**
 * MediaMetadataRetriever only became AutoCloseable in API 29, so `use` is off
 * the table at minSdk 26 — this is the same contract, done by hand.
 */
inline fun <T> withRetriever(context: Context, source: Uri, block: (MediaMetadataRetriever) -> T): T {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, source)
        block(retriever)
    } finally {
        retriever.release()
    }
}

/**
 * A clip the editor has finished deciding about but not yet encoded.
 *
 * Chat leaves the editor the moment the send is tapped and does the encoding
 * in the conversation, where the message is already sitting with a ring on it.
 * Encoding first meant staring at the editor for however long a re-encode took
 * and then being dropped into the channel with the thing already sent.
 */
data class VideoSendRequest(
    val source: android.net.Uri,
    val probe: VideoSource,
    val edit: VideoEdit,
    /** Read before leaving, so the bubble has its still from the first frame. */
    val posterBytes: ByteArray?,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * What the site is likely to take.
 *
 * Discourse's own default is ten megabytes and it is not readable from any
 * endpoint this app can reach, so this is a guess made out loud: past it the
 * clip is refused with something that says why, rather than after a long
 * upload with an error that says nothing.
 */
const val UPLOAD_MAX_BYTES = 50L * 1024 * 1024
