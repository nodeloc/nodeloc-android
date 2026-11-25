package com.nodeloc.app.core.network

import com.nodeloc.app.core.model.VoteDirection
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The transport and the facade together, against a scripted server: what goes
 * out on the wire (token, agent, paths, bodies) and what the screens get back.
 */
class NodelocApiTest {

    private lateinit var server: MockWebServer
    private lateinit var auth: DiscourseAuth
    private lateinit var client: DiscourseClient
    private val rejections = AtomicInteger()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val routes = mutableMapOf<String, MockResponse>()

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("fixtures/api/$name.json")).readText()

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.url.encodedPath
                val key = "${request.method} $path"
                return routes[key] ?: routes[path] ?: json("""{"error":{"code":"not_found","message":"no"}}""", 404)
            }
        }
        server.start()
        auth = DiscourseAuth().apply { onSessionRejected = { rejections.incrementAndGet() } }
        client = DiscourseClient(
            clientId = "test",
            auth = auth,
            cookieJar = PersistentCookieJar(InMemoryCookieStorage()),
            // An address rather than a name, so a test can reach "another host" as localhost.
            baseUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/'),
        )
        routes["/api/v1/nodes"] = json(fixture("nodes"))
    }

    @After
    fun stop() = server.close()

    @Test
    fun `the token and the app's own agent go out with every request`() = runBlocking {
        auth.token = "secret-token"
        routes["/api/v1/topics"] = json(fixture("topics"))
        client.topics("latest")
        val sent = requests.first { it.url.encodedPath == "/api/v1/topics" }
        assertEquals("Bearer secret-token", sent.headers["Authorization"])
        val agent = sent.headers["User-Agent"].orEmpty()
        assertTrue("not OkHttp's default, which the server answers 410: $agent", agent.startsWith("NodeLoc/"))
    }

    @Test
    fun `the token never leaves for another host`() = runBlocking {
        auth.token = "secret-token"
        val other = MockWebServer().apply { start() }
        try {
            other.enqueue(MockResponse.Builder().body("x").build())
            // 127.0.0.1 versus localhost: same machine, different host name.
            val url = other.url("/image.png").newBuilder().host("localhost").build().toString()
            client.fetchBytes(url)
            assertNull(other.takeRequest().headers["Authorization"])
        } finally {
            other.close()
        }
    }

    @Test
    fun `the server's own message reaches the screen`() = runBlocking {
        auth.token = "t"
        routes["POST /api/v1/topics/5/posts"] =
            json("""{"error":{"code":"invalid","message":"内容太短","fields":{"raw":"内容太短"}}}""", 422)
        try {
            client.reply(5, "x")
            fail("expected a rejection")
        } catch (error: DiscourseError) {
            assertEquals("内容太短", error.serverMessage)
            assertFalse(error.isSessionGone)
        }
        assertEquals(0, rejections.get())
    }

    @Test
    fun `a 401 signs out once, and never from the sign-in endpoint`() = runBlocking {
        auth.token = "dead"
        routes["/api/v1/notifications"] = json("""{"error":{"code":"unauthorized","message":"请先登录"}}""", 401)
        runCatching { client.notifications() }
        runCatching { client.notifications() }
        assertEquals(1, rejections.get())

        auth.resetRejection()
        routes["POST /api/v1/auth/login"] = json("""{"error":{"code":"unauthorized","message":"密码不对"}}""", 401)
        runCatching { client.api.sendRaw("POST", "api/v1/auth/login") }
        assertEquals(1, rejections.get())
    }

    @Test
    fun `a 403 is a permission answer, not a dead session`() = runBlocking {
        auth.token = "t"
        routes["/api/v1/topics/9"] = json("""{"error":{"code":"forbidden","message":"没有权限"}}""", 403)
        runCatching { client.topic(9) }
        assertEquals(0, rejections.get())
    }

    @Test
    fun `cursor paging walks forward page by page`() = runBlocking {
        routes["/api/v1/topics"] = json(fixture("topics"))
        val first = client.topics("latest", 0)
        assertTrue(first.topicList.moreTopicsUrl != null)
        client.topics("latest", 1)
        val second = requests.last { it.url.encodedPath == "/api/v1/topics" }
        assertTrue(second.url.queryParameter("cursor").orEmpty().isNotEmpty())
        // Rows know their node by id again.
        val vps = first.topicList.topics.firstOrNull { it.categoryId != null }
        assertTrue(vps != null)
    }

    @Test
    fun `hot pages by offset`() = runBlocking {
        routes["/api/v1/topics"] = json(fixture("topics"))
        client.topics("hot", 2)
        val sent = requests.last { it.url.encodedPath == "/api/v1/topics" }
        assertEquals("hot", sent.url.queryParameter("sort"))
        assertEquals("60", sent.url.queryParameter("offset"))
    }

    @Test
    fun `a topic carries its polls and skips the envelope it does not have`() = runBlocking {
        routes["/api/v1/topics/19"] = json(fixture("topic_19"))
        routes["/api/v1/posts/59/polls"] = json(fixture("polls_59"))
        val topic = client.topic(19)
        val first = topic.postStream.posts.first()
        assertEquals("poll", first.polls.orEmpty().single().pollName)
        assertNull(topic.redEnvelope)
        assertNull(first.lottery)
    }

    @Test
    fun `votes send the end state and read back the server's tally`() = runBlocking {
        auth.token = "t"
        routes["PUT /api/v1/posts/7/vote"] = json("""{"upvotes":4,"downvotes":1,"viewer_vote":-1}""")
        val result = client.castVote(7, VoteDirection.Down)
        assertEquals(3, result.voteScore)
        assertEquals("down", result.voteDirection)
        val sent = requests.last { it.url.encodedPath == "/api/v1/posts/7/vote" }
        assertEquals("""{"value":-1}""", sent.body?.utf8())
    }

    @Test
    fun `a new topic is addressed to its node by slug`() = runBlocking {
        auth.token = "t"
        routes["POST /api/v1/topics"] = json("""{"topic":{"id":77,"title":"t"},"post":{"id":901,"post_number":1}}""", 201)
        val created = client.createTopic("Title here", "Body", categoryId = 8)
        assertEquals(77, created.topicId)
        assertEquals(901, created.id)
        val sent = requests.last { it.method == "POST" && it.url.encodedPath == "/api/v1/topics" }
        assertTrue(sent.body?.utf8().orEmpty().contains("\"node\":\"vps\""))
    }

    @Test
    fun `composer video markdown becomes the native video tag`() = runBlocking {
        auth.token = "t"
        routes["POST /api/v1/topics/5/posts"] = json("""{"post":{"id":1,"post_number":2},"held":false}""", 201)
        val sha = "0123456789abcdef0123456789abcdef01234567"
        client.reply(5, "look\n\n![clip.mp4|video](video:$sha)")
        val body = requests.last { it.url.encodedPath == "/api/v1/topics/5/posts" }.body?.utf8().orEmpty()
        assertTrue(body, body.contains("[video id=$sha]"))
    }

    @Test
    fun `a direct conversation is read and written by username`() = runBlocking {
        auth.token = "t"
        routes["/api/v1/chat"] = json(fixture("chat"))
        routes["/api/v1/messages/bob"] = json(fixture("dm_bob"))
        routes["POST /api/v1/messages/bob"] =
            json("""{"message":{"id":50,"from_me":true,"sender":"alice","html":"<p>hi</p>"}}""", 201)
        val channels = client.chatChannels()
        val direct = channels.directMessageChannels.orEmpty().single { it.title == "bob" }
        val messages = client.chatMessages(direct.id)
        assertTrue(messages.messages.isNotEmpty())
        val sent = client.createChatMessage(direct.id, "hi")
        assertEquals(50, sent.messageId)
        assertTrue(requests.none { it.url.encodedPath.startsWith("/api/v1/chat/channels/${direct.id}") })
    }

    @Test
    fun `a vote from a list says so, and does not count for reputation`() = runBlocking {
        auth.token = "t"
        routes["PUT /api/v1/posts/14/vote"] = json("""{"upvotes":1,"downvotes":0,"viewer_vote":1}""")
        client.castVote(14, VoteDirection.Up, fromFeed = true)
        val body = requests.last { it.url.encodedPath == "/api/v1/posts/14/vote" }.body?.utf8()
        assertEquals("""{"value":1,"from":"feed"}""", body)
    }

    @Test
    fun `the node directory carries my memberships and mutes`() = runBlocking {
        auth.token = "t"
        routes["/api/v1/me/nodes"] = json(
            """{"joined":[{"slug":"ai","notification_level":"watching"}],"favorites":[],"muted":["deals"]}""",
        )
        val nodes = client.nodeDirectory(force = true)
        val ai = nodes.first { it.slug == "ai" }
        assertTrue(ai.joined)
        assertEquals(3, Legacy.category(ai).notificationLevel)
        assertEquals(0, Legacy.category(nodes.first { it.slug == "deals" }).notificationLevel)
    }

    @Test
    fun `a user's replies page by the server's cursor`() = runBlocking {
        routes["/api/v1/users/bob/posts"] = json(fixture("user_bob_posts"))
        val first = client.userActions("bob", filter = 5, offset = 0)
        assertEquals(listOf(42, 39), first.userActions.map { it.postId })
        assertEquals("节点活动：回帖抽三位送周边", first.userActions.first().title)
        client.userActions("bob", filter = 5, offset = 30)
        assertEquals("39", requests.last { it.url.encodedPath == "/api/v1/users/bob/posts" }.url.queryParameter("cursor"))
    }

    @Test
    fun `saved posts come back keyed by post`() = runBlocking {
        auth.token = "t"
        routes["/api/v1/saved"] = json(
            """{"bookmarks":[{"id":7,"post_id":57,"post_number":3,"topic_id":1,"topic_title":"T","excerpt":"e","author":"bob","node_slug":"vps"}],"total":1,"next_offset":null}""",
        )
        val saved = client.bookmarks("alice").userBookmarkList
        assertEquals(57, saved.bookmarks.single().id)
        assertEquals(8, saved.bookmarks.single().categoryId)
        assertNull(saved.moreBookmarksUrl)
    }

    @Test
    fun `the stream's events reach the old channel names`() {
        val subscribed = setOf("/notification/3", "/chat/4", "/chat/4/new-messages", "/chat/1", "/chat/user-tracking-state/3")
        assertEquals(setOf("/notification/3"), MessageBusClient.channelsFor("unread", """{"unread":2}""", subscribed))
        assertEquals(
            setOf("/chat/4", "/chat/4/new-messages", "/chat/user-tracking-state/3"),
            MessageBusClient.channelsFor("chat", """{"type":"new","conversation":4,"kind":"direct"}""", subscribed),
        )
        assertEquals(subscribed, MessageBusClient.channelsFor("chat", """{"type":"resync"}""", subscribed))
        assertTrue(MessageBusClient.channelsFor("chat", "not json", subscribed).isEmpty())
    }
}

/** RFC 7636's own example pins the S256 transform the server checks. */
class PkceTest {
    @Test
    fun `challenge is base64url sha256 of the verifier`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
        val verifier = Pkce.verifier()
        assertTrue(verifier.length >= 43 && verifier.none { it == '=' || it == '+' || it == '/' })
    }
}
