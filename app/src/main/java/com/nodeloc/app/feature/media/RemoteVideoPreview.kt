package com.nodeloc.app.feature.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.core.graphics.scale
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.network.HttpMediaDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** A still from a remote clip, and how long the clip runs. */
data class RemoteVideoPreview(val posterPath: String, val durationMs: Long)

/**
 * The first frame and the length of a clip nobody has told us about.
 *
 * Chat serialises an upload as id, url, filename, size and extension. A video
 * has no width, no height, no poster and no duration: `UploadSerializer`'s
 * thumbnail is an `optimized_image`, which only exists for pictures, and the
 * `<sha1>.jpg` poster convention this app uploads into lives in PrettyText,
 * which cooks posts — chat's uploads never go through it.
 *
 * So the client reads it, over ranges, through the app's own HTTP stack. A few
 * hundred kilobytes for a still and a number, once per clip.
 */
object RemoteVideoPreviews {

    private val lock = Mutex()
    private val known = mutableMapOf<String, RemoteVideoPreview?>()

    suspend fun of(context: Context, url: String): RemoteVideoPreview? {
        // Held across the probe on purpose: two tiles for the same clip scroll
        // into view together, and letting both read it doubles the traffic to
        // arrive at the same answer.
        lock.withLock {
            if (known.containsKey(url)) return known[url]
            val found = cached(context, url) ?: probe(context, url)
            known[url] = found
            return found
        }
    }

    /**
     * Survives the process, because otherwise every cold start pays for every
     * clip on screen again. The length is in the name: one file rather than
     * two, and nothing to keep in step.
     */
    private fun cached(context: Context, url: String): RemoteVideoPreview? {
        val dir = dir(context)
        val prefix = key(url) + "_"
        val file = dir.listFiles()?.firstOrNull { it.name.startsWith(prefix) } ?: return null
        val ms = file.name.removePrefix(prefix).removeSuffix(".jpg").toLongOrNull() ?: return null
        return RemoteVideoPreview(file.absolutePath, ms)
    }

    private suspend fun probe(context: Context, url: String): RemoteVideoPreview? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val source = HttpMediaDataSource(url, ServiceLocator.get.client.http)
        try {
            retriever.setDataSource(source)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frame = retriever.frameAtStart() ?: return@withContext null
            val file = File(dir(context), "${key(url)}_$ms.jpg")
            file.outputStream().use { out ->
                frame.compress(Bitmap.CompressFormat.JPEG, 82, out)
            }
            RemoteVideoPreview(file.absolutePath, ms)
        } catch (_: Throwable) {
            // A clip that will not open here is still playable in the viewer,
            // which has the whole ExoPlayer stack behind it. The tile falls
            // back to its own name rather than the message losing the video.
            null
        } finally {
            runCatching { retriever.release() }
            runCatching { source.close() }
        }
    }

    /**
     * A frame from the front, but not frame zero.
     *
     * Clips very often open on a dark or half-exposed frame, and a chat tile
     * showing black says less about the video than the filename did.
     */
    private fun MediaMetadataRetriever.frameAtStart(): Bitmap? {
        val frame = getFrameAtTime(400_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: getFrameAtTime(0L)
            ?: return null
        val longest = maxOf(frame.width, frame.height)
        if (longest <= POSTER_EDGE) return frame
        val ratio = POSTER_EDGE.toFloat() / longest
        return frame.scale(
            (frame.width * ratio).toInt().coerceAtLeast(2),
            (frame.height * ratio).toInt().coerceAtLeast(2),
        )
    }

    private fun dir(context: Context): File =
        File(context.cacheDir, "chat-video").apply { mkdirs() }

    private fun key(url: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private const val POSTER_EDGE = 640
}

/** "0:07", "1:04", "12:30" — the way a player writes a length. */
fun formatClock(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
