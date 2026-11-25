package com.nodeloc.app.smoke

import com.nodeloc.app.core.model.VoteDirection
import com.nodeloc.app.core.network.DiscourseAuth
import com.nodeloc.app.core.network.DiscourseClient
import com.nodeloc.app.core.network.InMemoryCookieStorage
import com.nodeloc.app.core.network.MessageBusClient
import com.nodeloc.app.core.network.NodelocApi
import com.nodeloc.app.core.network.PersistentCookieJar
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The app's data layer against a real 2.0 backend: what a user does in the first
 * minutes, through the same facade the screens call.
 *
 * Runs only when `NODELOC_SMOKE_URL` names a disposable instance seeded with the
 * demo data (`nodeloc seed`; user alice / nodeloc-demo-2026). It writes: a
 * reply, a vote, a bookmark, a DM. Never point it at a site people use.
 */
class BackendSmokeTest {

    private val base: String? = System.getenv("NODELOC_SMOKE_URL")?.trimEnd('/')
    private val log = StringBuilder()

    private fun step(name: String, detail: Any?) {
        val line = "[smoke] $name: $detail"
        log.appendLine(line)
        println(line)
    }

    @Test
    fun `sign in, read, write, chat and listen`() = runBlocking {
        assumeTrue("NODELOC_SMOKE_URL not set", !base.isNullOrEmpty())
        val auth = DiscourseAuth()
        val client = DiscourseClient("smoke", auth, PersistentCookieJar(InMemoryCookieStorage()), base!!)

        // Sign in as AuthService does: OkHttp sends no Sec-Fetch-Mode, so the
        // answer carries the bearer token.
        val login = client.api.sendRaw(
            "POST",
            "api/v1/auth/login",
            buildJsonObject {
                put("login", System.getenv("NODELOC_SMOKE_USER") ?: "alice")
                put("password", System.getenv("NODELOC_SMOKE_PASSWORD") ?: "nodeloc-demo-2026")
                put("device", "smoke test")
            },
        )
        val root = NodelocApi.json.parseToJsonElement(login).jsonObject
        val token = (root["token"] as? JsonPrimitive)?.contentOrNull
        assertNotNull("login answered without a token", token)
        auth.token = token
        auth.username = ((root["user"] as JsonObject)["username"] as JsonPrimitive).content
        // The token alone must be enough: no cookies from here on.
        client.cookieJar.clear()
        step("login", "bearer token (${token!!.length} chars), cookies dropped")

        val me = client.currentUser().currentUser
        step("me", "${me.username} id=${me.id} admin=${me.admin} energy=${me.gamificationScore} unread=${me.unreadNotifications}")
        assertEquals(auth.username, me.username)

        val site = client.site()
        step("site", "${site.categories?.size} nodes, ${site.postActionTypes.size} report reasons, providers=${site.authProviders?.map { "${it.name}->${it.providerUrl}" }}")
        assertTrue(site.categories.orEmpty().isNotEmpty())

        val feed = client.topics("latest")
        step("feed", "${feed.topicList.topics.size} topics, more=${feed.topicList.moreTopicsUrl != null}")
        assertTrue(feed.topicList.topics.isNotEmpty())
        val hot = client.topics("hot")
        step("hot", "${hot.topicList.topics.size} topics")

        val ownId = feed.users.orEmpty().firstOrNull { it.username == me.username }?.id
        val target = feed.topicList.topics.first {
            it.categoryId != null && it.pinned != true && it.posters?.firstOrNull()?.userId != ownId
        }
        val nodeTopics = client.nodeTopics("", target.categoryId!!)
        step("node feed", "${nodeTopics.topicList?.topics?.size} topics in node ${target.categoryId}")

        val topic = client.topic(target.id)
        step("topic", "#${topic.id} '${topic.title}' ${topic.postStream.posts.size} posts, node=${topic.categoryId}")
        assertTrue(topic.postStream.posts.isNotEmpty())
        val nested = client.nestedTopic(topic.id, sort = "old")
        step("thread", "${nested.roots?.size} root replies")

        val reply = client.reply(topic.id, "App 2.0 smoke reply ${System.currentTimeMillis()}")
        step("reply", "post ${reply.id} #${reply.postNumber}")
        assertNotNull(reply.id)

        val voteTarget = topic.postStream.posts.first { !it.yours }
        val vote = client.castVote(voteTarget.id, VoteDirection.Up)
        step("vote", "post ${voteTarget.id} score=${vote.voteScore} dir=${vote.voteDirection}")
        assertEquals("up", vote.voteDirection)

        val bookmark = client.bookmark(voteTarget.id)
        val saved = client.bookmarks(me.username).userBookmarkList.bookmarks
        step("bookmark", "added ${bookmark.id}; list has ${saved.size}")
        assertTrue(saved.any { it.postId == voteTarget.id })
        client.removeBookmark(bookmark.id!!)

        val notifications = client.notifications().notifications
        step("notifications", "${notifications.size} (types ${notifications.map { it.notificationType }.distinct()})")
        client.markNotificationsRead()

        // Listen before writing, so the DM's own event can be seen arriving.
        val bus = MessageBusClient(client)
        val connected = CountDownLatch(1)
        val heard = java.util.concurrent.atomic.AtomicReference(CountDownLatch(1))
        val subscription = bus.subscribe(listOf("/notification/${me.id}", "/chat/user-tracking-state/${me.id}")) {
            connected.countDown()
            heard.get().countDown()
        }
        // Connecting fires every channel once (anything may have changed while
        // away); only what arrives after that is the DM's own event.
        assertTrue("the event stream did not connect", connected.await(15, TimeUnit.SECONDS))
        Thread.sleep(500)
        heard.set(CountDownLatch(1))

        val dm = client.openDirectMessageChannel("bob").channel!!
        val sent = client.createChatMessage(dm.id, "App 2.0 smoke DM ${System.currentTimeMillis()}")
        val history = client.chatMessages(dm.id)
        step("dm", "conversation ${dm.id}, sent ${sent.messageId}, history ${history.messages.size}")
        assertTrue(history.messages.any { it.id == sent.messageId })

        val channels = client.chatChannels()
        step("chat", "${channels.publicChannels?.size} channels, ${channels.directMessageChannels?.size} conversations")

        val stream = heard.get().await(15, TimeUnit.SECONDS)
        subscription.cancel()
        step("stream", if (stream) "event received" else "no event within 15s")
        assertTrue("the event stream delivered nothing", stream)

        val search = client.search("小鸡")
        step("search", "${search.topics?.size} topics, ${search.posts?.size} posts, ${search.users?.size} users")

        val profile = client.user("bob")
        step("profile", "bob posts=${profile.user.postCount} avatar=${profile.user.avatarTemplate}")

        val topicsOfBob = client.userActions("bob", filter = 4)
        val repliesOfBob = client.userActions("bob", filter = 5)
        step("activity", "bob: ${topicsOfBob.userActions.size} topics, ${repliesOfBob.userActions.size} posts")
        assertTrue(topicsOfBob.userActions.isNotEmpty() && repliesOfBob.userActions.isNotEmpty())

        val joined = client.nodeDirectory(force = true).filter { it.joined }
        step("my nodes", "${joined.size} joined: ${joined.map { "${it.slug}(${it.notificationLevel})" }}")

        val votable = client.topics("latest").topicList.topics.count { it.opPostId != null }
        step("list voting", "$votable rows carry first_post_id")
        assertTrue(votable > 0)

        val checkin = client.checkinStatus()
        step("checkin", "today=${checkin.checkedInToday} next=${checkin.nextPoints}")

        val update = client.latestRelease(1, "smoke")
        step("update", update.release?.let { "${it.versionName} (${it.versionCode}) mandatory=${it.mandatory}" } ?: "none published")

        // A browser sign-in handed back to the app: what Custom Tabs does, played
        // by a second client with its own cookies, then the app's PKCE exchange.
        val verifier = com.nodeloc.app.core.network.Pkce.verifier()
        val challenge = com.nodeloc.app.core.network.Pkce.challenge(verifier)
        val browserJar = PersistentCookieJar(InMemoryCookieStorage())
        val browser = okhttp3.OkHttpClient.Builder().cookieJar(browserJar).followRedirects(false).build()
        fun visit(path: String, json: String? = null): okhttp3.Response {
            val builder = okhttp3.Request.Builder().url("$base$path")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) Chrome/140")
                .header("Accept", "text/html")
                .header("Sec-Fetch-Mode", if (json == null) "navigate" else "cors")
            if (json != null) builder.post(json.toRequestBody("application/json".toMediaType()))
            return browser.newCall(builder.build()).execute().also { it.close() }
        }
        val start = visit("/login?app=android&redirect_uri=nodeloc%3A%2F%2Fauth&code_challenge=$challenge")
        step("browser /login", "${start.code} -> ${start.header("Location")}")
        visit(start.header("Location")!!)
        val browserLogin = visit("/api/v1/auth/login", """{"login":"bob","password":"nodeloc-demo-2026"}""")
        assertEquals(200, browserLogin.code)
        val back = visit("/app-login")
        val redirect = back.header("Location").orEmpty()
        step("browser /app-login", "${back.code} -> ${redirect.substringBefore("code=")}code=…")
        assertTrue(redirect.startsWith("nodeloc://auth?code="))
        val code = redirect.substringAfter("code=").substringBefore('&')
        val appAuth = DiscourseAuth()
        val appClient = DiscourseClient("smoke2", appAuth, PersistentCookieJar(InMemoryCookieStorage()), base!!)
        val exchanged = NodelocApi.json.parseToJsonElement(
            appClient.api.sendRaw(
                "POST",
                "api/v1/auth/app/exchange",
                buildJsonObject {
                    put("code", java.net.URLDecoder.decode(code, "UTF-8"))
                    put("code_verifier", verifier)
                    put("device", "smoke test")
                },
            ),
        ).jsonObject
        appAuth.token = (exchanged["token"] as JsonPrimitive).content
        appClient.cookieJar.clear()
        val bob = appClient.currentUser().currentUser
        step("exchange", "signed in as ${bob.username} with the exchanged token")
        assertEquals("bob", bob.username)

        // Sign-out: the session dies on the server, not just on the phone.
        client.api.sendRaw("POST", "api/v1/auth/logout")
        val after = runCatching { client.api.getRaw("api/v1/me") }.exceptionOrNull()
        step("logout", "me afterwards -> ${(after as? com.nodeloc.app.core.network.DiscourseError.BadResponse)?.code}")
        assertEquals(401, (after as? com.nodeloc.app.core.network.DiscourseError.BadResponse)?.code)
    }
}
