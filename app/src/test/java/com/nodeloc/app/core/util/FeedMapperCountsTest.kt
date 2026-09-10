package com.nodeloc.app.core.util

import com.nodeloc.app.core.model.TopicListItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two numbers on a feed row have to be the two the topic shows once it is
 * opened. Both of the fields the list offers for them are wrong, in opposite
 * directions, and both are plausible enough to be picked again by mistake.
 */
class FeedMapperCountsTest {

    private fun post(topic: TopicListItem) = FeedMapper.post(topic, emptyMap())

    /**
     * Real values from `/latest.json` for topic 103946, whose own detail
     * endpoint reports the first post at 26 likes and the topic at 33 posts.
     * The row used to say 67 and 5.
     */
    @Test
    fun `a row shows the first post's likes, not the whole thread's`() {
        val row = post(
            TopicListItem(id = 103946, likeCount = 67, opLikeCount = 26, postsCount = 33, replyCount = 5),
        )

        assertEquals(26, row.baseVotes)
        assertEquals(32, row.comments)
    }

    /** Not every list carries it; the thread total beats showing nothing. */
    @Test
    fun `without op_like_count the thread total stands in`() {
        val row = post(TopicListItem(id = 1, likeCount = 67, postsCount = 33))

        assertEquals(67, row.baseVotes)
    }

    /**
     * `top.json` returns `posts_count: 0` for some topics — seven of fifty when
     * this was written — including one sitting at post number 2450. Trusting it
     * would have shown "no comments" on the busiest threads on the site.
     */
    @Test
    fun `a zero posts_count falls back to the highest post number`() {
        val row = post(TopicListItem(id = 2, postsCount = 0, highestPostNumber = 2450, replyCount = 63))

        assertEquals(2449, row.comments)
    }

    @Test
    fun `a topic with no replies counts none`() {
        assertEquals(0, post(TopicListItem(id = 3, postsCount = 1, highestPostNumber = 1)).comments)
        assertEquals(0, post(TopicListItem(id = 4)).comments)
    }
}
