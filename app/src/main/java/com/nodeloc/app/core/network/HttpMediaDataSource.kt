package com.nodeloc.app.core.network

import android.media.MediaDataSource
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A remote file that Android's media stack can read a piece at a time.
 *
 * `MediaMetadataRetriever` will take a URL directly, but then it uses its own
 * HTTP stack: no cookies, no auth, and none of the app's timeouts. This hands
 * it the app's client instead, one range request at a time, which is what makes
 * reading the header of an upload cost a few kilobytes rather than the file.
 *
 * That matters for our own exports: `MediaMuxer` writes the moov atom at the
 * end, so an extractor reads the front, jumps to the tail for the index, and
 * comes back. Three ranges, not twenty megabytes.
 */
internal class HttpMediaDataSource(
    private val url: String,
    private val client: OkHttpClient,
) : MediaDataSource() {

    private var length: Long = -1L

    /**
     * The last few blocks read, whole.
     *
     * An extractor asks for a few bytes at a time and walks forward, so serving
     * those from an aligned block turns hundreds of requests into a handful.
     * Four blocks is enough for the pattern it actually uses: header, tail,
     * back to the header.
     */
    private val blocks = object : LinkedHashMap<Long, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = size > 4
    }

    /**
     * How long the file is, which the extractor needs before it will start.
     *
     * An mp4 written by `MediaMuxer` keeps its index at the end, so a reader
     * that does not know where the end is cannot find it and gives up — which
     * is a clip showing as its filename rather than its first frame.
     *
     * Two ways of asking, because a CDN in front of the uploads may answer HEAD
     * with 405 and still range GETs perfectly well.
     */
    override fun getSize(): Long {
        if (length >= 0) return length
        length = byHead() ?: byRange() ?: -1L
        return length
    }

    private fun byHead(): Long? = runCatching {
        client.newCall(Request.Builder().url(url).head().build()).execute().use {
            if (it.isSuccessful) it.header("Content-Length")?.toLongOrNull() else null
        }
    }.getOrNull()

    /** `Content-Range: bytes 0-0/12345` — the total is the part after the slash. */
    private fun byRange(): Long? = runCatching {
        val request = Request.Builder().url(url).header("Range", "bytes=0-0").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: response.header("Content-Length")?.toLongOrNull()
        }
    }.getOrNull()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0) return 0
        val total = getSize()
        if (total in 0..position) return -1

        var written = 0
        var at = position
        while (written < size) {
            if (total in 0..at) break
            val start = at / BLOCK * BLOCK
            val block = blocks[start] ?: fetch(start) ?: break
            val within = (at - start).toInt()
            if (within >= block.size) break
            val take = minOf(size - written, block.size - within)
            System.arraycopy(block, within, buffer, offset + written, take)
            written += take
            at += take
        }
        return if (written == 0) -1 else written
    }

    private fun fetch(start: Long): ByteArray? = runCatching {
        val end = start + BLOCK - 1
        val request = Request.Builder().url(url).header("Range", "bytes=$start-$end").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            // A server that ignores the range hands back the whole file; taking
            // the front of it is still the block that was asked for.
            val bytes = response.body?.bytes() ?: return@use null
            val block = if (response.code == 206) bytes else bytes.copyOfRange(
                start.toInt().coerceAtMost(bytes.size),
                (start + BLOCK).toInt().coerceAtMost(bytes.size),
            )
            block.also { blocks[start] = it }
        }
    }.getOrNull()

    override fun close() {
        blocks.clear()
    }

    private companion object {
        const val BLOCK = 256L * 1024L
    }
}
