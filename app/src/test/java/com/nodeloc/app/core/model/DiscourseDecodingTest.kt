@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.nodeloc.app.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoding regressions are the expensive kind: one wrong field type takes the
 * *whole* response with it, and on iOS exactly that left every post's node
 * silently blank for weeks. These fixtures pin the shapes the backend is known
 * to be inconsistent about.
 *
 * The Json here mirrors `DiscourseClient.json` — same naming strategy, same
 * leniency — so a change there without a change here fails loudly.
 */
class DiscourseDecodingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    @Test
    fun `latest decodes topics and their poster users`() {
        val payload = """
            {"users":[{"id":7,"username":"ada","name":"Ada","avatar_template":"/u/7/{size}.png"}],
             "topic_list":{"more_topics_url":"/latest?page=1","topics":[
               {"id":1,"title":"Hi","slug":"hi","posts_count":3,"reply_count":2,"like_count":5,
                "category_id":12,"posters":[{"user_id":7,"description":"Original Poster"}],
                "thumbnails":[{"max_width":400,"width":400,"height":300,"url":"/a_400.png"},
                              {"width":1024,"height":768,"url":"/a.png"}],
                "tags":[{"id":3,"name":"vps"}],"topic_video_url":"/uploads/v.mp4"}]}}
        """.trimIndent()

        val latest = json.decodeFromString<LatestResponse>(payload)
        val topic = latest.topicList.topics.single()
        assertEquals("Hi", topic.title)
        assertEquals(7, latest.users?.single()?.id)
        assertEquals(2, topic.thumbnails?.size)
        assertEquals("/uploads/v.mp4", topic.topicVideoUrl)
        assertEquals("/latest?page=1", latest.topicList.moreTopicsUrl)
    }

    @Test
    fun `trust levels are a name to id map, not an array`() {
        val site = json.decodeFromString<SiteResponse>(
            """{"trust_levels":{"newuser":0,"basic":1},"categories":[{"id":1,"name":"N","slug":"n"}]}""",
        )
        assertEquals(0, site.trustLevels?.get("newuser"))
        assertEquals(1, site.categories?.size)
    }

    @Test
    fun `has_more arrives as a bool on one path and 0-1 on another`() {
        val asBool = json.decodeFromString<NestedTopicResponse>("""{"has_more_roots":true,"roots":[]}""")
        val asInt = json.decodeFromString<NestedChildrenResponse>("""{"has_more":1,"children":[]}""")
        val asZero = json.decodeFromString<NestedChildrenResponse>("""{"has_more":0}""")
        assertEquals(true, asBool.hasMoreRoots?.value)
        assertEquals(true, asInt.hasMore?.value)
        assertEquals(false, asZero.hasMore?.value)
    }

    @Test
    fun `upload maps the reserved extension field`() {
        val upload = json.decodeFromString<DiscourseUpload>(
            """{"id":9,"url":"/u/a.png","original_filename":"a.png","extension":"png","short_url":"upload://x"}""",
        )
        assertEquals("png", upload.fileExtension)
        // The composer prefers the short form, which is what markdown wants.
        assertEquals("upload://x", upload.composerUrl)
    }

    @Test
    fun `user_option enums that Rails serialises as strings do not break the decode`() {
        val response = json.decodeFromString<CurrentUserResponse>(
            """{"current_user":{"id":1,"username":"ada","user_option":{
                 "default_calendar":"none_enabled","text_size":"normal","email_level":1,
                 "community_view_mode":"card","watched_category_ids":[1,2]}}}""",
        )
        val option = response.currentUser.userOption
        assertEquals("none_enabled", option?.defaultCalendar)
        assertEquals("card", option?.communityViewMode)
        assertEquals(listOf(1, 2), option?.watchedCategoryIds)
    }

    @Test
    fun `likes come from actions_summary, not a top-level field`() {
        val post = json.decodeFromString<TopicPost>(
            """{"id":5,"username":"ada","actions_summary":[{"id":2,"count":4,"acted":true},{"id":6}]}""",
        )
        assertEquals(4, post.likeCount)
        assertTrue(post.likedByMe)
    }

    @Test
    fun `reward totals exclude negative system deducts`() {
        val post = json.decodeFromString<TopicPost>(
            """{"id":5,"username":"ada","rewards":[
                 {"id":1,"amount":10},{"id":2,"amount":5},{"id":3,"amount":-20,"is_system_reward":true}]}""",
        )
        assertEquals(15, post.rewardTotal)
    }

    @Test
    fun `poll keeps its reserved public field and derives its name`() {
        val poll = json.decodeFromString<PostPoll>(
            """{"id":1,"type":"multiple","status":"open","public":true,
                "options":[{"id":"a","html":"A","votes":2},{"id":"b","html":"B","votes":3}]}""",
        )
        assertEquals(true, poll.isPublic)
        // An unnamed poll is "poll" — what polls_votes and the vote endpoint key on.
        assertEquals("poll", poll.pollName)
        assertTrue(poll.isMultiple)
        assertEquals(5, poll.totalVotes)
    }

    @Test
    fun `unlimited lottery participants use a sentinel rather than null`() {
        val unlimited = json.decodeFromString<PostLottery>("""{"id":1,"max_participants":1000000}""")
        val capped = json.decodeFromString<PostLottery>("""{"id":1,"max_participants":50}""")
        assertTrue(!unlimited.hasParticipantCap)
        assertTrue(capped.hasParticipantCap)
    }

    @Test
    fun `apps directory is a bare array while the detail payload is wrapped`() {
        val list = json.decodeFromString<List<DirectoryApp>>(
            """[{"id":1,"slug":"a","name":"A","surface":"webview","home_url":"/t/topic/103048/1"}]""",
        )
        assertEquals(103048, list.single().hostTopicId)
        assertTrue(list.single().isWebview)

        val detail = json.decodeFromString<DirectoryAppResponse>(
            """{"directory_app":{"id":2,"slug":"b","name":"B"}}""",
        )
        assertEquals("b", detail.directoryApp.slug)
    }

    @Test
    fun `chat tracking is keyed by channel id as a string`() {
        val response = json.decodeFromString<ChatChannelsResponse>(
            """{"public_channels":[{"id":3,"title":"General"}],
                "tracking":{"channel_tracking":{"3":{"unread_count":2,"mention_count":1}}}}""",
        )
        assertEquals(3, response.allChannels.single().id)
        assertEquals(3, response.tracking?.state(3)?.totalUnreadCount)
    }

    @Test
    fun `unknown fields never fail a decode`() {
        val topic = json.decodeFromString<TopicListItem>(
            """{"id":1,"title":"t","some_new_plugin_field":{"nested":true}}""",
        )
        assertEquals(1, topic.id)
        assertNull(topic.excerpt)
    }

    /**
     * Rails' `success_json` renders `{"success":"OK"}` — a string under a name
     * that promises a bool. Declared as `Boolean?` it took the whole response
     * down with it, so joining a node reported a failure it had just carried
     * out. Every endpoint using that helper is decoded through [FlexibleBool].
     */
    @Test
    fun `success arrives as the string OK`() {
        val payload = """{"success":"OK","joined":true,"community":{"id":83}}"""
        val decoded = json.decodeFromString<NodeMembershipResponse>(payload)
        assertEquals(true, decoded.success?.value)
        assertEquals(true, decoded.joined)
    }

    @Test
    fun `a flexible bool still reads bools and ints`() {
        assertEquals(true, json.decodeFromString<NodeMembershipResponse>("""{"success":true}""").success?.value)
        assertEquals(true, json.decodeFromString<NodeMembershipResponse>("""{"success":1}""").success?.value)
        assertEquals(false, json.decodeFromString<NodeMembershipResponse>("""{"success":0}""").success?.value)
        assertEquals(false, json.decodeFromString<NodeMembershipResponse>("""{"success":"nope"}""").success?.value)
    }

    /**
     * `u/check_email.json` speaks two shapes, and which one it uses is a site
     * setting: with `hide_email_address_taken` on — as nodeloc runs today —
     * every address, valid or not, comes back as the first. The second is what
     * arrives the moment that setting is turned off, and the form shows the
     * server's own sentence, so this pins the day it flips.
     */
    @Test
    fun `check email decodes both the silent and the speaking answer`() {
        val silent = json.decodeFromString<EmailCheckResponse>("""{"success":"OK"}""")
        assertEquals("OK", silent.success)
        assertNull(silent.failed)
        assertTrue(silent.errors.isEmpty())

        val taken = json.decodeFromString<EmailCheckResponse>(
            """{"failed":"FAILED","errors":["邮箱 已被使用"]}""",
        )
        assertEquals("FAILED", taken.failed)
        assertEquals("邮箱 已被使用", taken.errors.first())
    }

    /**
     * The claim's amount is `points_received`. Named `points` it decoded to
     * null on every post and the badge never rendered — the field was there
     * the whole time, spelled differently.
     */
    @Test
    fun `red envelope claim decodes the amount and the post it belongs to`() {
        val claim = json.decodeFromString<RedEnvelopeClaim>(
            """{"id":21544,"red_envelope_id":512,"user_id":59224,"post_id":943851,
                "points_received":16,"created_at":"2026-08-27T01:23:22.418Z"}""",
        )
        assertEquals(16, claim.pointsReceived)
        assertEquals(943851, claim.postId)
        assertEquals(512, claim.redEnvelopeId)
    }

    /** `lottery_json` verbatim, including the "no cap" sentinel it stores. */
    @Test
    fun `lottery decodes the ticket bounds and the uncapped participant count`() {
        val lottery = json.decodeFromString<PostLottery>(
            """{"id":9,"title":"中元节","user_id":1,"post_id":2,"min_participants":5,
                "max_participants":1000000,"max_tickets_per_user":10,
                "min_tickets_per_user":2,"min_trust_level":1,
                "draw_at":"2026-09-01T12:00:00.000Z","status":"open",
                "levels":[{"id":1,"name":"一等奖","prize":"100 能量","quantity":2}],
                "tickets_count":7,"participants_count":4}""",
        )
        assertEquals(2, lottery.minTicketsPerUser)
        assertEquals(10, lottery.maxTicketsPerUser)
        assertTrue(lottery.isOpen)
        // 1_000_000 is the plugin's stand-in for "no cap"; showing it would be
        // reporting a limit nobody set.
        assertFalse(lottery.hasParticipantCap)
    }

    /**
     * `emojis.json` is a bare map keyed by group, and the group names carry
     * `&` and spaces — decoding it as a typed object would need one class per
     * group, which is why it is a map.
     */
    @Test
    fun `emojis decode as a map of group to list`() {
        val groups = json.decodeFromString<Map<String, List<DiscourseEmoji>>>(
            """{"smileys_&_emotion":[
                  {"name":"grinning_face","tonable":false,
                   "url":"/images/emoji/unicode/grinning_face.png?v=15",
                   "group":"smileys_&_emotion"}],
                "simsimi":[{"name":"xhj001","tonable":false,
                   "url":"/uploads/default/original/1X/abc.png","group":"simsimi"}]}""",
        )
        assertEquals(2, groups.size)
        // The site's own uploads are the reason the picker exists; they arrive
        // in their own group, after the Unicode ones.
        assertEquals("xhj001", groups.getValue("simsimi").single().name)
    }

    /**
     * discourse-vote serializes its fields only where voting is enabled, and
     * the app has no way to read the setting that decides that — so a null
     * `vote_down_count` is the whole signal, and it must survive decoding as
     * null rather than as a zero that would put arrows on every post on the
     * site.
     */
    @Test
    fun `a post outside a voting category carries no vote fields`() {
        val post = json.decodeFromString<TopicPost>(
            """{"id":1,"username":"ada","created_at":"2026-08-31T00:00:00.000Z",
                "cooked":"<p>hi</p>","post_number":2,
                "actions_summary":[{"id":2,"count":3,"acted":true}]}""",
        )
        assertNull(post.voteScore)
        assertNull(post.voteDirection)
        assertFalse(post.canVoteDown)
        assertEquals(3, post.likeCount)
    }

    @Test
    fun `a post inside one carries the score and the reader's own direction`() {
        val post = json.decodeFromString<TopicPost>(
            """{"id":1,"username":"ada","created_at":"2026-08-31T00:00:00.000Z",
                "cooked":"<p>hi</p>","post_number":2,"vote_score":-1,
                "vote_direction":"down","can_vote_down":true,
                "actions_summary":[{"id":2,"count":3,"acted":false}]}""",
        )
        // The server's arithmetic, not the app's. A vote is a reaction now, and
        // which faces count against a post is a setting no client is sent, so
        // there is nothing here to derive a score from.
        assertEquals(-1, post.voteScore)
        assertEquals(VoteDirection.Down, VoteDirection.from(post.voteDirection))
        assertTrue(post.canVoteDown)
    }

    /** A list row votes on the topic's first post, so it is sent the id of one. */
    @Test
    fun `a topic list row carries its opening post's ballot`() {
        val row = json.decodeFromString<TopicListItem>(
            """{"id":9,"title":"Hi","op_post_id":41,"op_vote_score":-3,
                "op_vote_direction":"down","op_can_vote_down":true}""",
        )
        assertEquals(41, row.opPostId)
        assertEquals(-3, row.opVoteScore)
        assertEquals(VoteDirection.Down, VoteDirection.from(row.opVoteDirection))
    }

    /**
     * The reaction sheet's payload names people by handle and nothing else.
     *
     * Reusing the ordinary user shape here — whose `id` is required — made the
     * whole response fail to decode, and the sheet then reported no reactions
     * on a post visibly carrying twenty-three.
     */
    @Test
    fun `reaction users decode without an id`() {
        val response = json.decodeFromString<ReactionUsersResponse>(
            """{"reaction_users":[{"id":"heart","count":2,"users":[
                {"username":"ada","name":"Ada","avatar_template":"/u/7/{size}.png",
                 "can_undo":false,"created_at":"2026-08-31 08:29:39 UTC"},
                {"username":"bo","name":null,"avatar_template":null,
                 "can_undo":false,"created_at":"2026-08-31 08:21:33 UTC"}]}]}""",
        )
        val group = response.reactionUsers.single()
        assertEquals("heart", group.id)
        assertEquals(2, group.count)
        assertEquals(listOf("ada", "bo"), group.users.map { it.username })
        assertNull(group.users[1].name)
    }

    /**
     * discourse-vote serializes the fold line onto `site.json` expressly for
     * this app; the web gets it from a preload store no API client can reach.
     * A server without the field yields null, and then nothing folds.
     */
    @Test
    fun `the site carries the fold line, and its absence is not a zero`() {
        val withIt = json.decodeFromString<SiteResponse>(
            """{"categories":[],"vote_collapse_score_threshold":-7}""",
        )
        assertEquals(-7, withIt.voteCollapseScoreThreshold)
        assertNull(json.decodeFromString<SiteResponse>("""{"categories":[]}""").voteCollapseScoreThreshold)
    }
}
