package com.nodeloc.app.core.util

import java.security.MessageDigest

/**
 * The hex SHA-1 of some bytes.
 *
 * Here because of one Discourse quirk with three callers: a video's poster is
 * linked to the video by filename alone — `original_filename LIKE
 * '<video_sha1>.%'` — so every place that uploads a clip has to hash it first.
 */
fun sha1Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * The same digest, read a block at a time.
 *
 * Discourse keys a video's poster to the video's own SHA-1, so this has to
 * agree with the bytes actually uploaded — and those are now streamed from
 * disk rather than held in memory, which is the whole point.
 */
fun sha1Hex(file: java.io.File): String {
    val digest = MessageDigest.getInstance("SHA-1")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
