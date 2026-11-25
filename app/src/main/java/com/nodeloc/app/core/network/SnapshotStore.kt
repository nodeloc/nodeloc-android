package com.nodeloc.app.core.network

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import java.io.File

/** What was on screen last time, and when it was saved (epoch millis). */
data class Snapshot<T>(val value: T, val savedAt: Long)

/**
 * The last answer of a few reads, kept on disk so a cold start on a bad
 * connection shows what was there before instead of a blank page (issue #1).
 *
 * The same idea as [ChatDiskCache], for the feed, a topic, the inbox and the
 * site's node directory: what is stored is the decoded server answer, encoded
 * back through the app's own [NodelocApi.json], so a replay goes through the
 * same mapping a fresh answer does — relative times are worked out again from
 * the stored timestamps, never frozen as "5 分钟前". Display only: nothing read
 * from here is ever written back, and the next good answer replaces it.
 *
 * Lives in cacheDir (the system may purge it, and that is fine) and is cleared
 * on sign-out, because most of it is one account's view of the site.
 */
class SnapshotStore(private val directory: File) {
    constructor(context: Context) : this(File(context.applicationContext.cacheDir, "Snapshots"))

    /** Writes happen off whatever thread the answer arrived on; a lost write costs nothing. */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun <T> load(key: String, serializer: KSerializer<T>): Snapshot<T>? = runCatching {
        val file = file(key).takeIf { it.exists() } ?: return null
        Snapshot(NodelocApi.json.decodeFromString(serializer, file.readText()), file.lastModified())
    }.getOrNull()

    /** The text as it was stored, for answers kept verbatim (see [storeRaw]). */
    fun loadRaw(key: String): Snapshot<String>? = runCatching {
        val file = file(key).takeIf { it.exists() } ?: return null
        Snapshot(file.readText(), file.lastModified())
    }.getOrNull()

    fun <T> store(key: String, serializer: KSerializer<T>, value: T) {
        io.launch { write(key, NodelocApi.json.encodeToString(serializer, value)) }
    }

    /** For a body that is decoded more than one way and so is best kept exactly as sent. */
    fun storeRaw(key: String, raw: String) {
        io.launch { write(key, raw) }
    }

    /**
     * Keeps the [keep] most recent snapshots whose key starts with [prefix].
     * Topics are the only open-ended family: one file per topic ever opened
     * would grow without bound.
     */
    fun prune(prefix: String, keep: Int) {
        io.launch {
            runCatching {
                directory.listFiles { file -> file.name.startsWith(prefix) && file.name.endsWith(".json") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(keep)
                    ?.forEach { it.delete() }
            }
        }
    }

    fun clear() {
        runCatching { directory.deleteRecursively() }
    }

    private fun write(key: String, text: String) {
        runCatching {
            directory.mkdirs()
            // Written aside and moved into place, so a reader never sees half a file.
            val temp = File(directory, "$key.tmp")
            temp.writeText(text)
            if (!temp.renameTo(file(key))) {
                file(key).delete()
                temp.renameTo(file(key))
            }
        }
    }

    private fun file(key: String) = File(directory, "$key.json")
}

/**
 * Whether a failed read should fall back to a snapshot: the network or the
 * server failed, not the request. A 404 or 403 means the thing is gone or no
 * longer ours to see, and showing a saved copy of it would be wrong.
 */
fun Throwable.servesSnapshot(): Boolean = when (this) {
    is DiscourseError.Transport -> true
    is DiscourseError.BadResponse -> code >= 500
    is java.io.IOException -> true
    else -> false
}
