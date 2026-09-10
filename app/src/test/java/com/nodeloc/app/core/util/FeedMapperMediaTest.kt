package com.nodeloc.app.core.util

import com.nodeloc.app.core.model.TopicListItem
import com.nodeloc.app.core.model.TopicThumbnail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `thumbnails` and `topic_images` look interchangeable and are not: the first
 * is one picture at several widths, the second is the gallery. Reading the
 * wrong one meant a row with ten pictures showed one, and the card's pager —
 * which has been in the code all along — could never run.
 */
class FeedMapperMediaTest {

    /** Shapes taken from `/latest.json` for topic 105510, which has ten. */
    private val variantsOfTheFirst = listOf(
        TopicThumbnail(width = 1080, height = 1861, url = "https://s/original/3X/2/2/aaa.jpeg"),
        TopicThumbnail(width = 594, height = 1024, url = "https://s/optimized/3X/2/2/aaa_2_594x1024.jpeg"),
    )
    private val gallery = listOf(
        "https://s/original/3X/2/2/aaa.jpeg",
        "https://s/original/3X/e/d/bbb.jpeg",
        "https://s/original/3X/1/8/ccc.jpeg",
    )
    private val galleryThumbnails = listOf(
        "https://s/optimized/3X/2/2/aaa_2_290x499.jpeg",
        "https://s/optimized/3X/e/d/bbb_2_408x500.jpeg",
        "https://s/optimized/3X/1/8/ccc_2_296x500.jpeg",
    )

    private fun post(topic: TopicListItem) = FeedMapper.post(topic, emptyMap())

    @Test
    fun `a gallery becomes one media item per picture`() {
        val row = post(
            TopicListItem(
                id = 105510,
                thumbnails = variantsOfTheFirst,
                topicImages = gallery,
                topicThumbnails = galleryThumbnails,
            ),
        )

        assertEquals(3, row.media.size)
        assertEquals(gallery, row.media.map { it.fullSizeUrl })
    }

    /** The card draws the optimised copy; only the viewer wants the original. */
    @Test
    fun `later pictures display the optimised copy`() {
        val row = post(
            TopicListItem(id = 1, topicImages = gallery, topicThumbnails = galleryThumbnails),
        )

        assertEquals(galleryThumbnails[1], row.media[1].url)
        assertEquals(gallery[1], row.media[1].fullSizeUrl)
        // Parsed out of the file name, so the card can size itself before the
        // bytes arrive.
        assertEquals(408, row.media[1].width)
        assertEquals(500, row.media[1].height)
    }

    /** The first keeps the widths `thumbnails` described. */
    @Test
    fun `the first picture keeps its responsive variants`() {
        val row = post(
            TopicListItem(
                id = 2,
                thumbnails = variantsOfTheFirst,
                topicImages = gallery,
                topicThumbnails = galleryThumbnails,
            ),
        )

        assertTrue(row.media.first().variants.size >= 2)
        assertEquals(gallery[0], row.media.first().fullSizeUrl)
    }

    /** A single-picture topic is unchanged: one item, its variants intact. */
    @Test
    fun `one picture still resolves through its variants`() {
        val row = post(TopicListItem(id = 3, thumbnails = variantsOfTheFirst))

        assertEquals(1, row.media.size)
        assertEquals("https://s/original/3X/2/2/aaa.jpeg", row.media.first().bestUrl(2048))
    }

    @Test
    fun `no pictures at all is empty`() {
        assertEquals(emptyList<Any>(), post(TopicListItem(id = 4)).media)
    }
}
