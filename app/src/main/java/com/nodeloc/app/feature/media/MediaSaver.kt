package com.nodeloc.app.feature.media

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Puts a picture or a clip from a post into the device gallery.
 *
 * The bytes come back through the app's own client rather than a fresh
 * connection: uploads on this site can sit behind the session cookie, and a
 * bare download of one returns the login page.
 */
object MediaSaver {

    /**
     * Scoped storage owns `Pictures/` from Android 10, and inserting there
     * needs no permission. Below that the column is a real path the app has to
     * be allowed to write, which is the only reason the permission exists in
     * the manifest at all — capped at 28 so it is never requested above it.
     */
    val needsLegacyPermission: Boolean
        get() = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P

    suspend fun save(context: Context, url: String): Result<Unit> = runCatchingCancellable {
        val bytes = ServiceLocator.get.client.fetchBytes(url)
        withContext(Dispatchers.IO) { write(context, url, bytes) }
    }

    private fun write(context: Context, url: String, bytes: ByteArray) {
        val extension = url.substringBefore('?').substringAfterLast('.', "").lowercase()
            .takeIf { it.length in 3..4 && it.all(Char::isLetterOrDigit) }
            ?: "jpg"
        val name = "nodeloc-${System.currentTimeMillis()}.$extension"
        val mime = when (extension) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            else -> "image/jpeg"
        }
        // A clip filed under Images is a clip the gallery will not play.
        val video = mime.startsWith("video/")
        val collection =
            if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val folder = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pending = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "$folder/NodeLoc")
                // Hidden from the gallery until the bytes are all there, so a
                // failed download cannot leave a truncated picture behind.
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, pending)
                ?: error("insert refused")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream")
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } else {
            val directory = File(
                Environment.getExternalStoragePublicDirectory(folder),
                "NodeLoc",
            ).apply { mkdirs() }
            val file = File(directory, name)
            file.writeBytes(bytes)
            // The row is what makes it show up; the file alone is invisible to
            // the gallery until the media scanner happens to run.
            resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    @Suppress("DEPRECATION")
                    put(MediaStore.Images.Media.DATA, file.absolutePath)
                },
            )
        }
    }
}
