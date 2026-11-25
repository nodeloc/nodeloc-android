package com.nodeloc.app.core.network

import com.nodeloc.app.core.model.VoteDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mappers against answers captured from a real 2.0 server (seed data on a
 * local instance). If the server's shapes move, these are what notice.
 */
class LegacyMappingTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("fixtures/api/$name.json")) { "missing fixture $name" }.readText()

    private inline fun <reified T> decode(name: String): T = NodelocApi.json.decodeFromString(fixture(name))

    @Test
    fun `topic list rows keep the old meaning`() {
        val list = decode<ApiTopicList>("topics")
        assertNotNull("the first page carries a cursor", list.nextCursor)
        val source = list.topics.first { it.author == "alice" || it.author != null }
        val row = Legacy.topicListItem(source, nodeId = 8)
        assertEquals(source.id, row.id)
        assertEquals(8, row.categoryId)
        assertEquals((source.replyCount ?: 0) + 1, row.postsCount)
        assertEquals(source.voteScore, row.opVoteScore)
        assertEquals(source.tags, row.tags.orEmpty().map { it.name })

        val users = Legacy.topicUsers(list.topics)
        val author = users.first { it.id == row.posters!!.single().userId }
        assertEquals(source.author, author.username)
    }

    @Test
    fun `unread is rebuilt from the reader's mark`() {
        val read = ApiTopicSummary(id = 1, replyCount = 9, lastReadPostNumber = 4, unreadCount = 6)
        val row = Legacy.topicListItem(read, null)
        assertEquals(4, row.lastReadPostNumber)
        assertEquals(10, row.highestPostNumber)

        val never = Legacy.topicListItem(ApiTopicSummary(id = 2, replyCount = 3), null)
        assertNull(never.lastReadPostNumber)
        assertEquals(4, never.highestPostNumber)
    }

    @Test
    fun `a topic page becomes a post stream without action rows`() {
        val page = decode<ApiTopicPage>("topic_1")
        assertTrue("fixture has a small action post", page.posts.any { it.kind == "action" })
        val topic = Legacy.topicResponse(page, nodeId = 22, viewer = Legacy.Viewer(id = 3, canModerate = false))
        assertEquals(page.topic.id, topic.id)
        assertEquals(22, topic.categoryId)
        assertTrue(topic.postStream.posts.none { it.cooked.isNullOrEmpty() })
        val first = topic.postStream.posts.first()
        assertEquals(1, first.postNumber)
        assertEquals(page.posts.first().upvotes, first.likeCount)
        // Alice voted the first post up in the seed.
        assertEquals(VoteDirection.Up.wire, first.voteDirection)
        assertTrue(first.likedByMe)
        val mine = topic.postStream.posts.first { it.username == "alice" }
        assertTrue(mine.yours)
        assertTrue(mine.canEdit)
        assertFalse(first.canEdit)
    }

    @Test
    fun `the thread's pre-order list becomes a tree`() {
        val thread = decode<ApiThreadPage>("thread_2")
        val roots = Legacy.tree(thread.posts, Legacy.Viewer(null, false))
        assertEquals(listOf(2, 3, 5), roots.map { it.postNumber })
        assertEquals(listOf(4), roots.first().children.orEmpty().map { it.postNumber })
        assertEquals(1, roots.first().directReplyCount)
    }

    @Test
    fun `polls keep their names and option ids`() {
        val polls = decode<ApiPollList>("polls_59").polls
        val poll = Legacy.poll(polls.single())
        assertEquals("poll", poll.pollName)
        assertTrue(poll.isMultiple)
        assertEquals(listOf("1", "2", "3"), poll.options.orEmpty().map { it.id })
        assertTrue(Legacy.pollVotes(polls).isEmpty())
    }

    @Test
    fun `notifications land on the types the inbox branches on`() {
        val list = decode<ApiNotificationList>("notifications")
        val mapped = list.notifications.map(Legacy::notification)
        val message = mapped.first { it.notificationType == NotificationTypes.CHAT_MESSAGE }
        assertNotNull("a message notification opens its conversation", message.data?.chatChannelId)
        assertEquals("bob", message.data?.username)
        val vote = list.notifications.indexOfFirst { it.kind == "vote" }
        assertTrue(mapped[vote].notificationType in setOf(NotificationTypes.LIKED, NotificationTypes.LIKED_CONSOLIDATED))
        assertNotNull(mapped[vote].topicId)
        val system = mapped[list.notifications.indexOfFirst { it.kind == "system" }]
        assertEquals(0, system.notificationType)
        assertNotNull("a system notice shows its own title", system.data?.topicTitle)
    }

    @Test
    fun `chat overview splits into channels and conversations`() {
        val overview = decode<ApiChatOverview>("chat")
        val channels = overview.channels.map(Legacy::channel)
        assertTrue(channels.none { it.isDirectMessage })
        val direct = Legacy.direct(overview.directs.single())
        assertTrue(direct.isDirectMessage)
        assertEquals("bob", direct.chatable?.users?.single()?.username)
        assertEquals(overview.directs.single().unreadCount, direct.currentUserMembership?.unreadCount)
    }

    @Test
    fun `chat pages come out oldest first`() {
        val page = decode<ApiChatPage>("channel_1_messages")
        val mapped = Legacy.chatPage(page, 1)
        assertEquals(mapped.messages.map { it.id }.sorted(), mapped.messages.map { it.id })
        assertTrue(mapped.messages.all { it.chatChannelId == 1 && it.user != null })
        assertEquals(page.nextBefore != null, mapped.meta?.canLoadMorePast)
    }

    @Test
    fun `profiles carry stats and role`() {
        val profile = Legacy.profile(decode("user_bob"))
        assertEquals("bob", profile.user.username)
        assertEquals(10, profile.user.postCount)
        assertEquals(32, profile.user.likesReceived)
        assertEquals(false, profile.user.admin)
    }

    @Test
    fun `nodes keep ids, slugs and parents`() {
        val nodes = decode<ApiNodeList>("nodes").nodes
        val vps = nodes.first { it.slug == "vps" }
        val category = Legacy.category(vps)
        assertEquals(vps.id, category.id)
        assertEquals(vps.parentId, category.parentCategoryId)
        assertEquals("/n/vps", category.url)
    }

    @Test
    fun `report reasons round-trip through the old action type ids`() {
        val types = Legacy.reportTypes(chinese = false)
        types.forEach { assertEquals(it.nameKey, Legacy.reportReasonFor(it.id)) }
        assertTrue(types.all { it.isFlag && "Post" in it.appliesTo })
    }

    @Test
    fun `synthetic user ids are stable and never collide with small real ids`() {
        assertEquals(Legacy.syntheticUserId("Alice"), Legacy.syntheticUserId("alice"))
        assertTrue(Legacy.syntheticUserId("alice") > 1_000_000)
        assertEquals(0, Legacy.syntheticUserId(null))
    }

    @Test
    fun `list rows can be voted on through their first post`() {
        val list = decode<ApiTopicList>("topics")
        val source = list.topics.first { it.firstPostId != null }
        val row = Legacy.topicListItem(source, null)
        assertEquals(source.firstPostId, row.opPostId)
        assertEquals(Legacy.direction(source.viewerVote ?: 0), row.opVoteDirection)
        assertTrue(row.opCanVoteDown)
    }

    @Test
    fun `profiles carry the social counts`() {
        val envelope = decode<ApiUserEnvelope>("user_bob")
        assertEquals(true, envelope.user.canMessage)
        val profile = Legacy.profile(envelope)
        assertEquals(envelope.user.followerCount, profile.user.totalFollowers)
        assertEquals(envelope.user.followingCount, profile.user.totalFollowing)
        assertEquals(envelope.user.viewerFollowing, profile.user.isFollowed)
    }

    @Test
    fun `my nodes decode joined, favourites and muted`() {
        val mine = decode<ApiMyNodes>("me_nodes")
        assertTrue(mine.joined.isNotEmpty())
        assertTrue(mine.joined.all { it.slug.isNotEmpty() && it.notificationLevel != null })
    }

    @Test
    fun chatPicturesMoveFromHtmlToUploads() {
        val (html, images) = Legacy.chatImages(
            "<p>看这个 <img class=\"emoji em-img\" src=\"/emoji/smile.png\" alt=\":smile:\"></p>" +
                "<p><img src=\"/uploads/original/ad/ad39.png\" alt=\"\"></p>",
        )
        assertEquals(1, images.size)
        assertEquals("/uploads/original/ad/ad39.png", images[0].url)
        assertEquals("png", images[0].fileExtension)
        assertTrue("emoji stays inline", html.contains("emoji"))
        assertFalse(html.contains("ad39"))
        assertFalse("no empty paragraph left", html.contains("<p></p>"))

        val plain = "<p>只有文字</p>"
        assertEquals(plain to emptyList<Any>(), Legacy.chatImages(plain))
    }

    @Test
    fun chatVideosBecomeStreamTilesOnceReady() {
        val ready = "a".repeat(40)
        val pending = "b".repeat(40)
        val html = "<p>片段</p><div class=\"video-embed\" data-provider=\"upload\" data-video-id=\"$ready\"></div>" +
            "<div class=\"video-embed\" data-provider=\"upload\" data-video-id=\"$pending\"></div>"
        val (cooked, uploads) = Legacy.chatImages(
            html,
            mapOf(
                ready to ApiVideo(sha1 = ready, status = "ready", hlsUrl = "/uploads/videos/$ready/master.m3u8", width = 720, height = 1280),
                pending to ApiVideo(sha1 = pending, status = "processing"),
            ),
        )
        assertEquals(1, uploads.size)
        assertEquals("/uploads/videos/$ready/master.m3u8", uploads[0].url)
        assertEquals("m3u8", uploads[0].fileExtension)
        assertEquals(720, uploads[0].width)
        assertFalse(cooked.contains(ready))
        assertTrue("a video still transcoding stays as markup", cooked.contains(pending))
    }

    @Test
    fun chatVideoFilesBecomeTiles() {
        val (cooked, uploads) = Legacy.chatImages(
            "<p><video class=\"file-video\" src=\"/uploads/files/27/27bb.mp4\" controls preload=\"metadata\" playsinline></video></p>" +
                "<p><img src=\"/uploads/files/7c/7cab.mov\" alt=\"\"></p>",
        )
        assertEquals(listOf("/uploads/files/27/27bb.mp4", "/uploads/files/7c/7cab.mov"), uploads.map { it.url })
        assertEquals(listOf("mp4", "mov"), uploads.map { it.fileExtension })
        assertEquals("", cooked)
    }
}
