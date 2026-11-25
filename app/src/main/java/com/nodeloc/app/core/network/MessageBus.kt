package com.nodeloc.app.core.network

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * The live layer: the server's event stream (`/api/v1/notifications/stream`),
 * presented to the app as the channel-keyed signals its screens were written
 * against.
 *
 * Deliberately signal-only, as before: an event means "this changed" and the
 * store refetches through the mapper it already has. The stream carries
 * `unread` (the bell's count changed) and `chat` (`{type, conversation, …}`);
 * both are translated onto the old channel names in [BusChannels], so the
 * subscribers did not have to change.
 */
class MessageBusClient(private val client: DiscourseClient) {

    /** What a subscriber holds; cancelling releases its channels. */
    fun interface Subscription {
        fun cancel()
    }

    private class Entry(val channels: Set<String>, val onEvent: (String) -> Unit)

    private val lock = Any()
    private val subscribers = linkedMapOf<Subscription, Entry>()
    private var foreground = true
    private var job: Job? = null

    /** App-lifetime: one stream for every subscriber, whoever came first. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun subscribe(channels: List<String>, onEvent: (String) -> Unit): Subscription {
        val handle = object : Subscription {
            override fun cancel() = release(this)
        }
        synchronized(lock) { subscribers[handle] = Entry(channels.toSet(), onEvent) }
        restart()
        return handle
    }

    private fun release(handle: Subscription) {
        synchronized(lock) { subscribers.remove(handle) ?: return }
        restart()
    }

    /** Backgrounding closes the stream: an open connection is not free to hold. */
    fun setAppForeground(active: Boolean) {
        synchronized(lock) {
            if (foreground == active) return
            foreground = active
        }
        restart()
    }

    private fun restart() {
        synchronized(lock) {
            val wanted = subscribers.isNotEmpty() && foreground
            if (wanted && job?.isActive == true) return
            job?.cancel()
            job = if (!wanted) null else scope.launch {
                while (isActive) {
                    if (!client.auth.isAuthenticated) {
                        delay(BASE_BACKOFF_MILLIS)
                        continue
                    }
                    val started = System.nanoTime()
                    val ok = runCatching { streamOnce() }.isSuccess
                    if (!isActive) break
                    val lived = (System.nanoTime() - started) / 1_000_000
                    if (ok && lived > HEALTHY_MILLIS) failures.set(0)
                    // Escalating: a stream that keeps failing at once (signed out,
                    // server down) must not turn into a reconnect loop.
                    val step = failures.updateAndGet { (it + 1).coerceAtMost(MAX_BACKOFF_STEPS) }
                    delay(if (ok && lived > HEALTHY_MILLIS) MIN_RECONNECT_MILLIS else BASE_BACKOFF_MILLIS shl (step - 1))
                }
            }
        }
    }

    private val failures = AtomicInteger()

    /** One connection, read until the server closes it (it does, every half hour). */
    private suspend fun streamOnce() {
        val api = client.api
        val request = api.requestBuilder(api.url("api/v1/notifications/stream"))
            .header("Accept", "text/event-stream")
            .get()
            .build()
        val call = api.streamHttp.newCall(request)
        val handle = kotlin.coroutines.coroutineContext[Job]?.invokeOnCompletion { runCatching { call.cancel() } }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("stream ${response.code}")
                // Something may have changed while disconnected.
                fireAll()
                val source = response.body.source()
                var event = "message"
                val data = StringBuilder()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.isEmpty() -> {
                            if (data.isNotEmpty() || event != "message") dispatch(event, data.toString())
                            event = "message"
                            data.setLength(0)
                        }
                        line.startsWith(":") -> Unit
                        line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.removePrefix("data:").trim())
                        }
                    }
                }
            }
        } finally {
            handle?.dispose()
        }
    }

    /**
     * The site review queue's pending total, from the stream's `review` event.
     * Unlike every other event this carries the value itself, and only
     * `admin.review` holders ever receive it, so null means "not told", not zero.
     */
    private val _reviewPending = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    val reviewPending: kotlinx.coroutines.flow.StateFlow<Int?> = _reviewPending

    internal fun dispatch(event: String, data: String) {
        if (event == "review") {
            val pending = runCatching {
                ((NodelocApi.json.parseToJsonElement(data) as? JsonObject)?.get("pending") as? JsonPrimitive)?.intOrNull
            }.getOrNull()
            if (pending != null) _reviewPending.value = pending
        }
        val fired = channelsFor(event, data, synchronized(lock) { subscribers.values.flatMap { it.channels }.toSet() })
        if (fired.isEmpty()) return
        val listeners = synchronized(lock) { subscribers.values.toList() }
        fired.forEach { channel ->
            listeners.forEach { entry -> if (channel in entry.channels) entry.onEvent(channel) }
        }
    }

    private fun fireAll() {
        val listeners = synchronized(lock) { subscribers.values.toList() }
        listeners.forEach { entry -> entry.channels.forEach(entry.onEvent) }
    }

    companion object {
        const val MIN_RECONNECT_MILLIS = 1_000L
        const val HEALTHY_MILLIS = 30_000L
        const val BASE_BACKOFF_MILLIS = 4_000L
        const val MAX_BACKOFF_STEPS = 4   // 4s, 8s, 16s, 32s

        /**
         * Which of the [subscribed] channels an event touches. Pure, so the
         * translation is pinned by a JVM test.
         */
        fun channelsFor(event: String, data: String, subscribed: Set<String>): Set<String> {
            val out = linkedSetOf<String>()
            when (event) {
                "unread" -> subscribed.filterTo(out) { it.startsWith(BusChannels.NOTIFICATIONS_PREFIX) }
                "chat" -> {
                    val payload = runCatching { NodelocApi.json.parseToJsonElement(data) as? JsonObject }.getOrNull()
                        ?: return out
                    val type = (payload["type"] as? JsonPrimitive)?.contentOrNull
                    if (type == "resync") return subscribed
                    val conversation = (payload["conversation"] as? JsonPrimitive)?.intOrNull ?: 0
                    if (conversation != 0) {
                        out += BusChannels.chat(conversation)
                        if (type == "new") out += BusChannels.chatNewMessages(conversation)
                    }
                    // Any change moves the inbox's counts, including a conversation
                    // it has not seen yet, which it can only learn of this way.
                    subscribed.filterTo(out) { it.startsWith("/chat/user-tracking-state/") }
                }
            }
            return out.filterTo(linkedSetOf()) { it in subscribed }
        }
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
