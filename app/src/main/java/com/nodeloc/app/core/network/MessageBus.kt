package com.nodeloc.app.core.network

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal client for Discourse's MessageBus long-poll endpoint — the transport
 * its own web client uses for live chat.
 *
 * Deliberately signal-only: event payloads differ per channel, so parsing them
 * would mean a second, divergent mapping of every message shape. Instead an
 * event means "this channel changed" and the store refetches the latest page
 * through the mapper it already has. One cheap request buys permanent
 * consistency.
 */
class MessageBusClient(private val client: DiscourseClient) {

    /** What a subscriber holds; cancelling releases its channels. */
    fun interface Subscription {
        fun cancel()
    }

    private class Entry(val channels: Set<String>, val onEvent: (String) -> Unit)

    private val lock = Any()
    private val subscribers = linkedMapOf<Subscription, Entry>()
    private val positions = linkedMapOf<String, Int>()
    private var seq = 0
    private var foreground = true
    private var job: Job? = null

    /**
     * App-lifetime, not the caller's.
     *
     * There used to be one client per ChatViewModel, each with its own loop and
     * all sharing this install's single MessageBus client id. Discourse cancels
     * an existing long-poll when a second arrives with the same id and answers
     * the loser `[]` immediately — so two conversations on the back stack made
     * both loops spin, hammering the site until one was popped. One client with
     * a merged channel set cannot do that to itself.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Subscribes to a set of channels for as long as the returned handle lives.
     *
     * Takes no CoroutineScope on purpose: with the caller's, the loop would die
     * when the first subscriber was cleared while a second was still listening.
     */
    fun subscribe(channels: List<String>, onEvent: (String) -> Unit): Subscription {
        val handle = object : Subscription {
            override fun cancel() = release(this)
        }
        synchronized(lock) {
            subscribers[handle] = Entry(channels.toSet(), onEvent)
            // A channel joining the union starts at -1, which is how the server
            // is told "from now", not "replay what I missed".
            channels.forEach { positions.getOrPut(it) { -1 } }
        }
        restart()
        return handle
    }

    private fun release(handle: Subscription) {
        synchronized(lock) {
            subscribers.remove(handle) ?: return
            val live = subscribers.values.flatMapTo(mutableSetOf()) { it.channels }
            positions.keys.retainAll(live)
            // seq is deliberately not reset. The server's registration for this
            // client id outlives a cancelled poll by ~25s, and it closes any
            // poll arriving with a lower sequence — resubscribing inside that
            // window would spin at the floor until the registration expired.
        }
        restart()
    }

    /** Backgrounding stops the poll: an open long-poll is not free to hold. */
    fun setAppForeground(active: Boolean) {
        synchronized(lock) {
            if (foreground == active) return
            foreground = active
        }
        restart()
    }

    private fun restart() {
        synchronized(lock) {
            job?.cancel()
            job = if (positions.isEmpty() || !foreground) {
                null
            } else {
                scope.launch {
                    while (isActive) {
                        pollOnce()
                    }
                }
            }
        }
    }

    private suspend fun pollOnce() {
        val (snapshot, sequence) = synchronized(lock) {
            if (positions.isEmpty()) return
            positions.toMap() to seq++
        }
        val startedAt = System.nanoTime()
        try {
            dispatch(client.messageBusPoll(snapshot, sequence))
            failures.set(0)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            // Escalating, because a flat retry meant a signed-out client whose
            // poll 403s hammered the site every four seconds indefinitely.
            val step = failures.updateAndGet { (it + 1).coerceAtMost(MAX_BACKOFF_STEPS) }
            delay(BASE_BACKOFF_MILLIS shl (step - 1))
            return
        }
        // The server holds a poll for ~25s, but answers instantly when it has
        // nothing to say — or when it is cancelling a duplicate. Without a
        // floor, either case is a hot loop.
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000
        if (elapsed < MIN_POLL_INTERVAL_MILLIS) delay(MIN_POLL_INTERVAL_MILLIS - elapsed)
    }

    private val failures = AtomicInteger()

    private fun dispatch(body: String) {
        // Normally one JSON array. If something in front of the site strips the
        // Dont-Chunk header, message_bus streams instead: several arrays with a
        // `|` between them. That is not parseable as one document, and the
        // throw used to land in the backoff below — so the live layer went
        // quiet for half a minute at a time and nobody could see why.
        body.split(CHUNK_SEPARATOR)
            .map { it.trim() }
            .filter { it.startsWith("[") }
            .forEach { dispatchOne(JSONArray(it)) }
    }

    private fun dispatchOne(events: JSONArray) {
        val fired = linkedSetOf<String>()
        synchronized(lock) {
            for (index in 0 until events.length()) {
                val event = events.optJSONObject(index) ?: continue
                val channel = event.optString("channel").takeIf { it.isNotEmpty() } ?: continue
                if (channel == "/__status") {
                    // The server reports each channel's current position, which
                    // is how a -1 subscription starts from "now" rather than
                    // replaying history.
                    val statuses = event.optJSONObject("data") ?: continue
                    statuses.keys().forEach { name ->
                        if (positions.containsKey(name)) positions[name] = statuses.optInt(name, -1)
                    }
                    continue
                }
                if (!positions.containsKey(channel)) continue
                positions[channel] = event.optInt("message_id", positions[channel] ?: -1)
                fired += channel
            }
        }
        if (fired.isEmpty()) return
        // Outside the lock: a listener may subscribe or cancel in response.
        val listeners = synchronized(lock) { subscribers.values.toList() }
        fired.forEach { channel ->
            listeners.forEach { entry -> if (channel in entry.channels) entry.onEvent(channel) }
        }
    }

    private companion object {
        /** message_bus writes this between chunks when it streams a poll. */
        const val CHUNK_SEPARATOR = "|"
        const val MIN_POLL_INTERVAL_MILLIS = 500L
        const val BASE_BACKOFF_MILLIS = 4_000L
        const val MAX_BACKOFF_STEPS = 4   // 4s, 8s, 16s, 32s
    }
}

/**
 * Per-channel snapshot of the raw messages JSON, so a conversation renders
 * instantly from disk and the network fetch only reconciles. Stored verbatim so
 * it replays through the exact same decode path. Lives in cacheDir — purgeable,
 * and no database by design.
 */
class ChatDiskCache(context: Context) {
    private val directory = File(context.applicationContext.cacheDir, "ChatMessages")

    fun load(channelId: Int): String? = runCatching {
        file(channelId).takeIf { it.exists() }?.readText()
    }.getOrNull()

    fun store(raw: String, channelId: Int) {
        runCatching {
            directory.mkdirs()
            val temp = File(directory, "channel-$channelId.tmp")
            temp.writeText(raw)
            temp.renameTo(file(channelId))
        }
    }

    /** Transcripts are the previous account's mail; they do not survive a sign-out. */
    fun clear() {
        runCatching { directory.deleteRecursively() }
    }

    private fun file(channelId: Int) = File(directory, "channel-$channelId.json")
}

/** Bus channel names, kept in one place so subscriber and refetcher agree. */
object BusChannels {
    fun chat(channelId: Int) = "/chat/$channelId"
    fun chatThread(threadId: Int) = "/chat/thread/$threadId"

    /**
     * A channel's traffic as the *inbox* needs it.
     *
     * Separate from [chat], which carries every edit, reaction and deletion as
     * well: this one fires once per new message, which is the only thing a row
     * in a list is about.
     */
    fun chatNewMessages(channelId: Int) = "/chat/$channelId/new-messages"

    /** This account reading somewhere else — the web, another phone. */
    fun chatUserTracking(userId: Int) = "/chat/user-tracking-state/$userId"
    const val NOTIFICATIONS_PREFIX = "/notification/"
    fun notifications(userId: Int) = "$NOTIFICATIONS_PREFIX$userId"
}
