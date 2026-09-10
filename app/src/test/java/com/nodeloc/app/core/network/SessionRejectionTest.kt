package com.nodeloc.app.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Signing the user out is drastic, so the trigger has to be narrow: only
 * Discourse saying the session is gone, only when we believed we had one, never
 * during the sign-in that is trying to establish one, and never more than once.
 */
class SessionRejectionTest {

    private lateinit var server: MockWebServer
    private lateinit var auth: DiscourseAuth
    private lateinit var client: DiscourseClient
    private val rejections = AtomicInteger()

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        auth = DiscourseAuth().apply { onSessionRejected = { rejections.incrementAndGet() } }
        client = DiscourseClient(
            clientId = "test-client",
            auth = auth,
            cookieJar = PersistentCookieJar(InMemoryCookieStorage()),
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun stop() = server.close()

    private fun enqueueRejection(count: Int = 1) = repeat(count) {
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .body("""{"errors":["nope"],"error_type":"not_logged_in"}""")
                .build(),
        )
    }

    @Test
    fun `a rejected session signs the user out`() = runBlocking {
        auth.hasSession = true
        enqueueRejection()

        runCatching { client.getRaw("latest.json") }

        assertEquals(1, rejections.get())
    }

    /** Nothing to invalidate, and the sign-in flow runs in exactly this state. */
    @Test
    fun `a guest is not signed out of nothing`() = runBlocking {
        enqueueRejection()

        runCatching { client.getRaw("latest.json") }

        assertEquals(0, rejections.get())
    }

    /**
     * Signing in clears the credential first, so these calls are unauthenticated
     * anyway — but a session restored mid-flow must not turn the server's
     * "no" into a sign-out of the account the user is replacing.
     */
    @Test
    fun `a rejection while signing in is the answer, not a verdict`() = runBlocking {
        auth.hasSession = true
        enqueueRejection()

        runCatching { client.getRaw("session/csrf.json") }

        assertEquals(0, rejections.get())
    }

    /** A permission failure is not a dead session. */
    @Test
    fun `a plain 403 does not sign anybody out`() = runBlocking {
        auth.hasSession = true
        server.enqueue(MockResponse.Builder().code(403).body("""{"errors":["no"]}""").build())

        runCatching { client.getRaw("latest.json") }

        assertEquals(0, rejections.get())
    }

    /** A screen firing three requests would otherwise toast three times. */
    @Test
    fun `concurrent rejections sign out once`() = runBlocking {
        auth.hasSession = true
        enqueueRejection(count = 3)

        (1..3).map { async(Dispatchers.IO) { runCatching { client.getRaw("latest.json") } } }.awaitAll()

        assertEquals(1, rejections.get())
    }

    @Test
    fun `signing in again re-arms the trigger`() = runBlocking {
        auth.hasSession = true
        enqueueRejection(count = 2)

        runCatching { client.getRaw("latest.json") }
        auth.resetRejection()
        runCatching { client.getRaw("latest.json") }

        assertEquals(2, rejections.get())
    }

    /**
     * The user's password expired mid-session, they signed in again, and the
     * request still in flight from before came back "not logged in". That reply
     * is about the session they just replaced, and must not take the new one
     * with it.
     */
    @Test
    fun `a late answer about the old session does not end the new one`() = runBlocking {
        auth.hasSession = true
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .body("""{"errors":["nope"],"error_type":"not_logged_in"}""")
                .throttleBody(1, 1, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
        )

        val inFlight = async(Dispatchers.IO) { runCatching { client.getRaw("latest.json") } }
        delay(200)
        // Signing in again while it is still on the wire.
        auth.resetRejection()
        inFlight.await()

        assertEquals(0, rejections.get())
    }
}
