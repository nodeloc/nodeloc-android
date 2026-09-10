package com.nodeloc.app.core.store

import com.nodeloc.app.core.model.DiscourseNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which notifications become banners.
 *
 * The watermark is the only thing standing between "you missed nothing" and
 * "here is your entire unread backlog at once", and it is invisible when wrong:
 * a dropped notification looks exactly like no notification.
 */
class PushSelectionTest {

    private val allOn = AppPreferences.PushSettings(enabled = true)

    private fun notification(id: Int, type: Int = NotificationFormatter.Type.REPLIED, read: Boolean = false) =
        DiscourseNotification(id = id, notificationType = type, read = read)

    @Test
    fun `only what arrived after the watermark`() {
        val fresh = PushRepository.selectFresh(
            listOf(notification(1), notification(2), notification(3)),
            watermark = 2,
            settings = allOn,
        )

        assertEquals(listOf(3), fresh.map { it.id })
    }

    @Test
    fun `something already read is not news`() {
        val fresh = PushRepository.selectFresh(
            listOf(notification(3, read = true), notification(4)),
            watermark = 2,
            settings = allOn,
        )

        assertEquals(listOf(4), fresh.map { it.id })
    }

    @Test
    fun `a silenced category is not banner-ed`() {
        val fresh = PushRepository.selectFresh(
            listOf(
                notification(3, type = NotificationFormatter.Type.LIKED),
                notification(4, type = NotificationFormatter.Type.REPLIED),
            ),
            watermark = 2,
            settings = allOn.copy(likes = false),
        )

        assertEquals(listOf(4), fresh.map { it.id })
    }

    /** A long gap should not produce a wall of banners. */
    @Test
    fun `a backlog is capped at the most recent few`() {
        val fresh = PushRepository.selectFresh(
            (1..20).map { notification(it) },
            watermark = 0,
            settings = allOn,
        )

        assertEquals(5, fresh.size)
        assertEquals(listOf(16, 17, 18, 19, 20), fresh.map { it.id })
    }

    @Test
    fun `banners stack oldest first, the order they arrived`() {
        val fresh = PushRepository.selectFresh(
            listOf(notification(9), notification(7), notification(8)),
            watermark = 0,
            settings = allOn,
        )

        assertEquals(listOf(7, 8, 9), fresh.map { it.id })
    }

    @Test
    fun `nothing new is nothing to say`() {
        assertTrue(PushRepository.selectFresh(listOf(notification(1)), watermark = 5, settings = allOn).isEmpty())
    }
}
