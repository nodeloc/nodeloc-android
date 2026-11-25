package com.nodeloc.app.core.util

import com.nodeloc.app.core.util.TextDiff.Block
import com.nodeloc.app.core.util.TextDiff.Op
import com.nodeloc.app.core.util.TextDiff.Span
import org.junit.Assert.assertEquals
import org.junit.Test

class TextDiffTest {

    @Test
    fun `a one-character edit in Chinese diffs by character`() {
        assertEquals(
            listOf(Span(Op.Same, "每天有几"), Span(Op.Removed, "百"), Span(Op.Added, "千"), Span(Op.Same, "人打开它")),
            TextDiff.chars("每天有几百人打开它", "每天有几千人打开它"),
        )
    }

    @Test
    fun `untouched paragraphs fold into a count`() {
        val before = "第一段\n\n第二段\n\n第三段"
        val after = "第一段\n\n第二段改了\n\n第三段"
        val blocks = TextDiff.paragraphs(before, after)
        assertEquals(Block.Unchanged(1), blocks[0])
        assertEquals(
            Block.Changed(listOf(Span(Op.Same, "第二段"), Span(Op.Added, "改了"))),
            blocks[1],
        )
        assertEquals(Block.Unchanged(1), blocks[2])
        assertEquals(3, blocks.size)
    }

    @Test
    fun `an appended paragraph shows as added on its own`() {
        val blocks = TextDiff.paragraphs("一\n\n二", "一\n\n二\n\n三")
        assertEquals(listOf(Block.Unchanged(2), Block.Changed(listOf(Span(Op.Added, "三")))), blocks)
    }

    @Test
    fun `identical text is one unchanged run`() {
        assertEquals(listOf(Block.Unchanged(2)), TextDiff.paragraphs("a\n\nb", "a\n\nb"))
    }
}
