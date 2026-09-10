package com.nodeloc.app.core.network

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the server said has to survive the trip to a toast, and what it must
 * not say has to be filtered here rather than at the call sites.
 */
class DiscourseErrorClassifyTest {

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

    private fun failWith(code: Int, body: String, headers: Map<String, String> = emptyMap()): DiscourseError {
        val builder = MockResponse.Builder().code(code).body(body)
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        server.enqueue(builder.build())
        return runBlocking { runCatching { client.getRaw("probe.json") }.exceptionOrNull() } as DiscourseError
    }

    @Test
    fun `a validation message reaches the caller verbatim`() {
        val error = failWith(422, """{"errors":["Title is too short","Body is too short"]}""")

        assertEquals("Title is too short\nBody is too short", error.serverMessage)
    }

    /**
     * The plugins this site runs answer with a singular `error`, not core's
     * `errors: []`. Reading only the plural spelling turned "you have to reply
     * to this topic before you can enter" into "操作失败" — the tap looked
     * broken rather than refused for a reason the user could act on.
     */
    @Test
    fun `a plugin's singular error is quoted like core's plural one`() {
        val error = failWith(422, """{"success":false,"error":"您必须先回复此主题才能参与抽奖"}""")

        assertEquals("您必须先回复此主题才能参与抽奖", error.serverMessage)
    }

    /** Both spellings at once, without saying the same thing twice. */
    @Test
    fun `the two spellings do not duplicate each other`() {
        val error = failWith(422, """{"errors":["已达到最大参与人数"],"error":"已达到最大参与人数"}""")

        assertEquals("已达到最大参与人数", error.serverMessage)
    }

    /** A 5xx body can carry a Rails backtrace; none of it is for the user. */
    @Test
    fun `a server error contributes no message however it is dressed`() {
        val error = failWith(500, """{"errors":["undefined method for nil:NilClass"]}""")

        assertNull(error.serverMessage)
    }

    /** Our own wording beats "您没有权限查看请求的资源。" for a dead session. */
    @Test
    fun `a rejected session is recognised but not quoted`() {
        val error = failWith(403, """{"errors":["nope"],"error_type":"invalid_access"}""")

        assertTrue(error.isNotLoggedIn)
        assertNull(error.serverMessage)
    }

    @Test
    fun `a permission failure is not mistaken for a dead session`() {
        val error = failWith(403, """{"errors":["You cannot do that"],"error_type":"invalid_parameters"}""")

        assertFalse(error.isNotLoggedIn)
        assertEquals("You cannot do that", error.serverMessage)
    }

    @Test
    fun `a rate limit carries how long to wait`() {
        val error = failWith(429, """{"errors":["slow down"],"extras":{"wait_seconds":42}}""")

        assertTrue(error.isRateLimited)
        assertEquals(42, error.retryAfterSeconds)
    }

    @Test
    fun `a wall of text is capped`() {
        val error = failWith(422, """{"errors":["${"x".repeat(900)}"]}""")

        assertTrue((error.serverMessage?.length ?: 0) <= 300)
    }

    @Test
    fun `a body that is not the error envelope is simply absent`() {
        val error = failWith(404, "<html>Not Found</html>")

        assertNull(error.serverMessage)
        assertTrue(error.isNotFound)
    }

    /**
     * Cloudflare sits in front of the site, so `server: cloudflare` is on every
     * response — the tell is the mitigation header, or HTML where JSON belongs.
     */
    @Test
    fun `a Cloudflare challenge is not read as a permission failure`() {
        val error = failWith(403, "<html>challenge</html>", mapOf("cf-mitigated" to "challenge"))

        assertEquals(DiscourseError.Challenged, error)
    }

    /**
     * discourse-read-permission refuses a topic above the reader's trust level
     * with the same `invalid_access` Discourse uses for a credential it will
     * not accept — and the app asks for that topic three different ways. Taken
     * at its word it signed people out for having opened a locked thread, so
     * the session is checked before it is given up on.
     */
    @Test
    fun `a topic the reader may not read does not sign the session out`() {
        val auth = DiscourseAuth().apply { hasSession = true }
        var signedOut = false
        auth.onSessionRejected = { signedOut = true }
        val client = clientFor(auth)

        server.enqueue(forbidden())
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"current_user":{"id":1,"username":"reader"}}""")
                .build(),
        )

        runBlocking { runCatching { client.getRaw("n/topic/1.json") } }

        assertFalse(signedOut)
    }

    /** The other half: a credential the site really has stopped accepting. */
    @Test
    fun `a session the server no longer knows is given up`() {
        val auth = DiscourseAuth().apply { hasSession = true }
        var signedOut = false
        auth.onSessionRejected = { signedOut = true }
        val client = clientFor(auth)

        server.enqueue(forbidden())
        // What `session/current.json` answers when nobody is signed in.
        server.enqueue(MockResponse.Builder().code(404).body("{}").build())

        runBlocking { runCatching { client.getRaw("n/topic/1.json") } }

        assertTrue(signedOut)
    }

    private fun forbidden() = MockResponse.Builder()
        .code(403)
        .body("""{"errors":["您没有权限查看请求的资源。"],"error_type":"invalid_access"}""")
        .build()

    private fun clientFor(auth: DiscourseAuth) = DiscourseClient(
        clientId = "test-client",
        auth = auth,
        cookieJar = PersistentCookieJar(InMemoryCookieStorage()),
        baseUrl = server.url("/").toString().trimEnd('/'),
    )
}
