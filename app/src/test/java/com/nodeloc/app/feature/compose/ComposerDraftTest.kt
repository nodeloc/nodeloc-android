package com.nodeloc.app.feature.compose

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The draft has to come back as what was written, not as an approximation of
 * it: a restored draft is the only copy left, so anything this drops is gone.
 */
class ComposerDraftTest {

    @Test
    fun `a picture keeps both of its addresses`() {
        val blocks = listOf(
            ComposerBlock.Text("look at this"),
            ComposerBlock.Media(
                url = "upload://abc.png",
                displayUrl = "https://example.com/uploads/abc.png",
                name = "shot.png",
            ),
        )
        val restored = ComposerDraft
            .decode(ComposerDraft.encode(ComposerDraft.of("Title", 27, blocks)))!!

        assertEquals("Title", restored.title)
        assertEquals(27, restored.categoryId)
        val media = restored.toBlocks().filterIsInstance<ComposerBlock.Media>().single()
        // The markdown address is what the post carries; the display one is the
        // only thing that can actually be shown while it is still a draft.
        assertEquals("upload://abc.png", media.url)
        assertEquals("https://example.com/uploads/abc.png", media.displayUrl)
    }

    @Test
    fun `an upload still in flight is not saved`() {
        val blocks = listOf(
            ComposerBlock.Media(
                url = "",
                displayUrl = "",
                name = "pending.png",
                pendingPreview = "/data/cache/composer/pending.png",
            ),
        )
        val draft = ComposerDraft.of("", null, blocks)
        assertTrue(draft.blocks.isEmpty())
    }

    @Test
    fun `a draft from an older build is discarded rather than thrown`() {
        assertNull(ComposerDraft.decode("{\"title\":"))
        assertNull(ComposerDraft.decode(null))
    }

    @Test
    fun `nothing written is not a draft`() {
        val draft = ComposerDraft.of("  ", null, listOf(ComposerBlock.Text("")))
        assertTrue(draft.isEmpty)
    }

    @Test
    fun `an emoji lands at the caret with a space before it`() {
        val value = TextFieldValue("hello", TextRange(5))
        assertEquals("hello :tada: ", insertEmoji(value, "tada").text)
    }

    @Test
    fun `an emoji after a space does not get a second one`() {
        val value = TextFieldValue("hello ", TextRange(6))
        val result = insertEmoji(value, "tada")
        assertEquals("hello :tada: ", result.text)
        assertEquals(TextRange(13), result.selection)
    }

    @Test
    fun `an emoji in the middle does not disturb the tail`() {
        val value = TextFieldValue("ab cd", TextRange(2))
        assertEquals("ab :tada:  cd", insertEmoji(value, "tada").text)
    }
}
