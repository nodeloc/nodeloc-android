package com.nodeloc.app.core.util

/**
 * Paragraph-then-character diff for the edit history sheet.
 *
 * Paragraphs are matched first so an untouched paragraph can fold into
 * "… N 段未改动"; only paragraphs that changed get a character diff, which is
 * what makes a one-word edit in Chinese readable (there are no word breaks to
 * diff on).
 */
object TextDiff {

    enum class Op { Same, Added, Removed }

    data class Span(val op: Op, val text: String)

    sealed interface Block {
        /** [count] consecutive paragraphs that did not change. */
        data class Unchanged(val count: Int) : Block

        /** One paragraph's worth of spans: an edited, added or removed paragraph. */
        data class Changed(val spans: List<Span>) : Block
    }

    /** Character diffs above this many cells fall back to "all removed, all added". */
    private const val MAX_CELLS = 1_500_000

    fun paragraphs(before: String, after: String): List<Block> {
        val a = split(before)
        val b = split(after)
        val pairs = lcs(a, b)
        val out = mutableListOf<Block>()
        var unchanged = 0
        fun flushSame() {
            if (unchanged > 0) out += Block.Unchanged(unchanged)
            unchanged = 0
        }
        var i = 0
        var j = 0
        for ((pi, pj) in pairs + (a.size to b.size)) {
            // Between two matched paragraphs: removed ones in `a`, added ones in
            // `b`. Pair them up as edits so a rewritten paragraph diffs by
            // character rather than showing as one block out, one block in.
            val removed = a.subList(i, pi)
            val added = b.subList(j, pj)
            if (removed.isNotEmpty() || added.isNotEmpty()) flushSame()
            val paired = minOf(removed.size, added.size)
            for (k in 0 until paired) out += Block.Changed(chars(removed[k], added[k]))
            for (k in paired until removed.size) out += Block.Changed(listOf(Span(Op.Removed, removed[k])))
            for (k in paired until added.size) out += Block.Changed(listOf(Span(Op.Added, added[k])))
            if (pi < a.size) unchanged++
            i = pi + 1
            j = pj + 1
        }
        flushSame()
        return out
    }

    /** Character diff of two strings, adjacent spans of the same kind merged. */
    fun chars(before: String, after: String): List<Span> {
        if (before == after) return listOf(Span(Op.Same, before))
        if (before.length.toLong() * after.length > MAX_CELLS) {
            return listOfNotNull(
                before.takeIf { it.isNotEmpty() }?.let { Span(Op.Removed, it) },
                after.takeIf { it.isNotEmpty() }?.let { Span(Op.Added, it) },
            )
        }
        val a = before.toList()
        val b = after.toList()
        val matched = lcs(a, b)
        val spans = mutableListOf<Span>()
        fun add(op: Op, text: String) {
            if (text.isEmpty()) return
            val last = spans.lastOrNull()
            if (last != null && last.op == op) spans[spans.lastIndex] = last.copy(text = last.text + text)
            else spans += Span(op, text)
        }
        var i = 0
        var j = 0
        for ((pi, pj) in matched + (a.size to b.size)) {
            add(Op.Removed, before.substring(i, pi))
            add(Op.Added, after.substring(j, pj))
            if (pi < a.size) add(Op.Same, before[pi].toString())
            i = pi + 1
            j = pj + 1
        }
        return spans
    }

    private fun split(text: String): List<String> =
        text.replace("\r\n", "\n").split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }

    /** Index pairs of a longest common subsequence, in order. */
    private fun <T> lcs(a: List<T>, b: List<T>): List<Pair<Int, Int>> {
        val n = a.size
        val m = b.size
        val table = Array(n + 1) { IntArray(m + 1) }
        for (x in n - 1 downTo 0) {
            for (y in m - 1 downTo 0) {
                table[x][y] = if (a[x] == b[y]) table[x + 1][y + 1] + 1 else maxOf(table[x + 1][y], table[x][y + 1])
            }
        }
        val out = ArrayList<Pair<Int, Int>>()
        var x = 0
        var y = 0
        while (x < n && y < m) {
            when {
                a[x] == b[y] -> { out += x to y; x++; y++ }
                table[x + 1][y] >= table[x][y + 1] -> x++
                else -> y++
            }
        }
        return out
    }
}
