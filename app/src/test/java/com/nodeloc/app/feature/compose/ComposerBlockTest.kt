package com.nodeloc.app.feature.compose

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round-tripping markdown through the block editor. Kind is inferred on the way
 * in and has to survive the way out: a clip that comes back as a still image
 * loses its `|video` marker, and with it the player in the rendered post.
 */
class ComposerBlockTest {

    @Test
    fun `video markdown keeps its kind`() {
        val blocks = "![clip.mp4|video](upload://abc.mp4)".toComposerBlocks()
        val media = blocks.filterIsInstance<ComposerBlock.Media>().single()
        assertEquals(ComposerBlock.MediaKind.Video, media.kind)
        assertEquals("clip.mp4", media.name)
        assertEquals("![clip.mp4|video](upload://abc.mp4)", blocks.toMarkdown())
    }

    @Test
    fun `a gif is neither a picture nor a clip`() {
        val blocks = "![party](https://media.example.com/party.gif?x=1)".toComposerBlocks()
        assertEquals(
            ComposerBlock.MediaKind.Gif,
            blocks.filterIsInstance<ComposerBlock.Media>().single().kind,
        )
    }

    @Test
    fun `everything else is a picture`() {
        val blocks = "![shot](upload://abc.png)".toComposerBlocks()
        assertEquals(
            ComposerBlock.MediaKind.Image,
            blocks.filterIsInstance<ComposerBlock.Media>().single().kind,
        )
    }

    @Test
    fun `the kind of a post is the kind of its first upload`() {
        val blocks = listOf(
            ComposerBlock.Text("look"),
            ComposerBlock.Media("upload://a.gif", "", "a", ComposerBlock.MediaKind.Gif),
        )
        assertEquals(ComposerBlock.MediaKind.Gif, blocks.mediaKind())
        assertEquals(null, listOf<ComposerBlock>(ComposerBlock.Text("look")).mediaKind())
    }

    @Test
    fun `a pending upload contributes nothing to raw`() {
        val blocks = listOf(
            ComposerBlock.Text("wait"),
            ComposerBlock.Media("", "", "x", pendingPreview = "/tmp/x.jpg"),
        )
        assertEquals("wait", blocks.toMarkdown())
    }

    // ---------------------------------------------------------- poll names

    /** Discourse rejects a post with two unnamed polls, so the second needs one. */
    @Test
    fun `the first poll needs no name and the second does`() {
        assertEquals("", nextPollName(emptyList()))
        assertEquals(" name=poll1", nextPollName(listOf("[poll type=regular]\n* a\n[/poll]")))
    }

    /**
     * The bug this exists for: naming by *count* reused an index still in the
     * post after a deletion, and a duplicate name is rejected exactly as an
     * unnamed duplicate is.
     */
    @Test
    fun `a name still in the post is never reused`() {
        val remaining = listOf(
            "[poll type=regular]\n* a\n[/poll]",
            "[poll type=regular name=poll2]\n* c\n[/poll]",
        )
        assertEquals(" name=poll1", nextPollName(remaining))

        val afterThat = remaining + "[poll type=regular name=poll1]\n* d\n[/poll]"
        assertEquals(" name=poll3", nextPollName(afterThat))
    }

    @Test
    fun `markup that is not a poll is ignored`() {
        assertEquals("", nextPollName(listOf("[wrap=discourse-lottery]\n[/wrap]")))
    }
}
