package com.nodeloc.app.core.store

import com.nodeloc.app.core.store.NotificationFormatter.Kind
import com.nodeloc.app.core.store.NotificationFormatter.Type
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ids come from this deployment's own `site.json` `notification_types` map,
 * so they include the site's plugins as well as core Discourse. Getting one
 * wrong is invisible: the notification still arrives, just in the wrong channel
 * and under the wrong toggle.
 */
class NotificationTypeTest {

    @Test
    fun `something you wrote was valued`() {
        listOf(Type.LIKED, Type.LIKED_CONSOLIDATED, Type.REACTION, Type.REWARD_RECEIVED)
            .forEach { assertEquals("type $it", Kind.Like, NotificationFormatter.kind(it)) }
    }

    /**
     * Chat used to fall through to Star, so a chat mention was banner-ed as
     * "other" and escaped the private-messages toggle entirely.
     */
    @Test
    fun `chat is addressed to you, not site news`() {
        listOf(
            Type.CHAT_MENTION, Type.CHAT_MESSAGE, Type.CHAT_INVITATION,
            Type.CHAT_GROUP_MENTION, Type.CHAT_QUOTED, Type.CHAT_WATCHED_THREAD,
        ).forEach { assertEquals("type $it", Kind.Message, NotificationFormatter.kind(it)) }
    }

    @Test
    fun `private messages and invitations are addressed to you`() {
        listOf(Type.PRIVATE_MESSAGE, Type.INVITED_TO_PRIVATE_MESSAGE, Type.INVITED_TO_TOPIC)
            .forEach { assertEquals("type $it", Kind.Message, NotificationFormatter.kind(it)) }
    }

    @Test
    fun `replies, mentions and follows are conversation`() {
        listOf(
            Type.MENTIONED, Type.REPLIED, Type.QUOTED, Type.POSTED, Type.LINKED,
            Type.GROUP_MENTIONED, Type.WATCHING_FIRST_POST, Type.WATCHING_CATEGORY_OR_TAG,
            Type.FOLLOWING, Type.FOLLOWING_CREATED_TOPIC, Type.FOLLOWING_REPLIED,
        ).forEach { assertEquals("type $it", Kind.Comment, NotificationFormatter.kind(it)) }
    }

    @Test
    fun `the site talking about itself is system`() {
        listOf(
            Type.GROUP_MESSAGE_SUMMARY, Type.POST_APPROVED, Type.BOOKMARK_REMINDER,
            Type.NEW_FEATURES, Type.LOTTERY_RESULT, Type.TOPIC_FEATURED,
        ).forEach { assertEquals("type $it", Kind.System, NotificationFormatter.kind(it)) }
    }

    /** A plugin id this build has never heard of must not crash or vanish. */
    @Test
    fun `an unknown type is merely uncategorised`() {
        assertEquals(Kind.Star, NotificationFormatter.kind(9_999))
    }

    @Test
    fun `push channels follow the kinds`() {
        assertEquals(PushCategory.PrivateMessages, PushCategory.of(Kind.Message))
        assertEquals(PushCategory.Likes, PushCategory.of(Kind.Like))
        assertEquals(PushCategory.Replies, PushCategory.of(Kind.Comment))
        assertEquals(PushCategory.System, PushCategory.of(Kind.System))
    }
}
