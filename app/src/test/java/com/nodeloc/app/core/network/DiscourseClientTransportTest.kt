package com.nodeloc.app.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlin.system.measureTimeMillis
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The transport promises two things that fail silently when broken: a cancelled
 * coroutine stops its request, and a write is never sent twice.
 */
class DiscourseClientTransportTest {

    private lateinit var server: MockWebServer
    private lateinit var client: DiscourseClient

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        client = DiscourseClient(
            clientId = "test-client",
            auth = DiscourseAuth(),
            cookieJar = PersistentCookieJar(InMemoryCookieStorage()),
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun stop() = server.close()

    /**
     * The regression this guards: a blocking `execute()` left the call running
     * after its caller was gone, so leaving a screen kept its request alive and
     * `MessageBusClient.stop()` did not stop the poll for another 25 seconds.
     */
    @Test
    fun `cancelling the caller cancels the request`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body("{}")
                .throttleBody(1, 10, TimeUnit.SECONDS)
                .build(),
        )

        val job = launch(Dispatchers.IO) { runCatching { client.getRaw("latest.json") } }
        // Let it reach the socket before pulling the rug.
        delay(300)

        val elapsed = measureTimeMillis {
            job.cancelAndJoin()
        }

        // Returning promptly is the assertion: an uncancellable call would sit
        // on the socket until the 20 s read timeout regardless of its caller.
        assertTrue("took ${elapsed}ms to unwind", elapsed < 2_000)
        assertEquals(1, server.requestCount)
    }

    /**
     * A reset connection used to resend the request. On a POST that means a
     * reply posted twice, a reward charged twice, or two lotteries.
     */
    @Test
    fun `a write is not replayed after the connection drops`() = runBlocking {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build())
        server.enqueue(MockResponse.Builder().body("{}").build())

        runCatching { client.postJson("posts", "{}") }

        assertEquals("the write must not be retried", 1, server.requestCount)
    }

    /**
     * Reads stay retryable: replaying a GET costs nothing and hides a blip.
     * The first exchange is there to put a connection in the pool — okhttp only
     * retries when a *reused* connection dies, which is the case that matters.
     */
    @Test
    fun `a read is still retried after a pooled connection drops`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("{}").build())
        client.getRaw("latest.json")

        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build())
        server.enqueue(MockResponse.Builder().body("{\"ok\":true}").build())

        val body = client.getRaw("latest.json")

        assertTrue(body.contains("ok"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a non-2xx becomes a BadResponse carrying its code`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).body("{}").build())

        val error = runCatching { client.getRaw("t/1.json") }.exceptionOrNull()

        assertTrue("actual: $error", error is DiscourseError.BadResponse)
        assertTrue((error as DiscourseError.BadResponse).isNotFound)
    }
}
