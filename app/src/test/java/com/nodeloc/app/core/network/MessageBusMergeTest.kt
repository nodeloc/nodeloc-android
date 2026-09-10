package com.nodeloc.app.core.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One poll, whoever is listening.
 *
 * Every ChatViewModel used to build its own client, and they all shared this
 * install's single MessageBus client id — which Discourse treats as a
 * reconnection, cancelling the older poll and answering it `[]` at once. Two
 * conversations on the back stack therefore span both loops against the site.
 */
class MessageBusMergeTest {

    private lateinit var server: MockWebServer
    private lateinit var bus: MessageBusClient
    private val bodies = CopyOnWriteArrayList<String>()

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                request.body?.utf8()?.let(bodies::add)
                return MockResponse.Builder().body("[]").build()
            }
        }
        bus = MessageBusClient(
            DiscourseClient(
                clientId = "test-client",
                auth = DiscourseAuth(),
                cookieJar = PersistentCookieJar(InMemoryCookieStorage()),
                baseUrl = server.url("/").toString().trimEnd('/'),
            ),
        )
    }

    @After
    fun stop() = server.close()

    private fun channelsOf(body: String): Set<String> =
        body.split('&')
            .mapNotNull { it.substringBefore('=').takeIf { name -> name.startsWith("%2F") } }
            .map { java.net.URLDecoder.decode(it, "UTF-8") }
            .toSet()

    @Test
    fun `two subscribers share one poll carrying the union of their channels`() = runBlocking {
        val a = bus.subscribe(listOf("/chat/1")) {}
        val b = bus.subscribe(listOf("/chat/2")) {}
        delay(700)

        val merged = bodies.map(::channelsOf).firstOrNull { it.size == 2 }
        assertEquals(setOf("/chat/1", "/chat/2"), merged)

        a.cancel()
        b.cancel()
    }

    @Test
    fun `cancelling one subscriber leaves the other listening`() = runBlocking {
        val a = bus.subscribe(listOf("/chat/1")) {}
        val b = bus.subscribe(listOf("/chat/2")) {}
        delay(400)
        a.cancel()
        bodies.clear()
        delay(700)

        val latest = bodies.map(::channelsOf).lastOrNull { it.isNotEmpty() }
        assertEquals(setOf("/chat/2"), latest)

        b.cancel()
    }

    /**
     * Without `__seq` the server cannot tell a fresh poll from a stale
     * duplicate, and treats the newcomer as a reconnection to cancel.
     */
    @Test
    fun `every poll carries an increasing sequence`() = runBlocking {
        val a = bus.subscribe(listOf("/chat/1")) {}
        delay(1_600)
        a.cancel()

        val seqs = bodies.mapNotNull { body ->
            body.split('&').firstOrNull { it.startsWith("__seq=") }?.removePrefix("__seq=")?.toIntOrNull()
        }
        assertTrue("expected several polls, got ${seqs.size}", seqs.size >= 2)
        assertEquals(seqs.sorted(), seqs)
        assertEquals(seqs.distinct(), seqs)
    }

    /**
     * The server answers an empty poll instantly. Without a floor that is a hot
     * loop — which is exactly what the duplicate-client-id case produced.
     */
    @Test
    fun `an instant answer does not become a hot loop`() = runBlocking {
        val a = bus.subscribe(listOf("/chat/1")) {}
        delay(1_100)
        a.cancel()

        assertTrue("polled ${bodies.size} times in ~1s", bodies.size <= 4)
    }

    @Test
    fun `backgrounding stops polling and returning resumes it`() = runBlocking {
        val a = bus.subscribe(listOf("/chat/1")) {}
        delay(400)
        bus.setAppForeground(false)
        delay(200)
        bodies.clear()
        delay(800)
        assertTrue("kept polling in the background: ${bodies.size}", bodies.isEmpty())

        bus.setAppForeground(true)
        delay(700)
        assertTrue("did not resume", bodies.isNotEmpty())

        a.cancel()
    }
}
