package com.nodeloc.app.core.network

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.buffer
import okio.source
import java.io.File

/**
 * A file body that says how far it has got.
 *
 * A video is the one upload in this app long enough for the question to come
 * up, and the answer used to be a spinner that looked the same at one percent
 * and ninety-nine. OkHttp writes the body in blocks, so the count is free; it
 * just has to be reported.
 *
 * Reported at most [MIN_STEP] apart so a fast connection does not push a
 * hundred state changes a second through Compose for no visible gain.
 */
internal class ProgressRequestBody(
    private val file: File,
    private val contentType: MediaType?,
    private val onProgress: (Float) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = file.length().coerceAtLeast(1L)
        var written = 0L
        var reported = 0f
        file.source().buffer().use { source ->
            val buffer = okio.Buffer()
            while (true) {
                val read = source.read(buffer, SEGMENT)
                if (read == -1L) break
                sink.write(buffer, read)
                written += read
                val fraction = written.toFloat() / total
                if (fraction - reported >= MIN_STEP || written == total) {
                    reported = fraction
                    onProgress(fraction.coerceIn(0f, 1f))
                }
            }
        }
    }

    private companion object {
        const val SEGMENT = 64L * 1024L
        const val MIN_STEP = 0.01f
    }
}
