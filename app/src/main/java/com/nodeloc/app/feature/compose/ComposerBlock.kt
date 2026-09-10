package com.nodeloc.app.feature.compose

import androidx.annotation.StringRes
import com.nodeloc.app.feature.media.VideoEdit

/**
 * One piece of a post being written.
 *
 * The composer edits blocks; Discourse stores markdown. That split is forced:
 * `raw` is the wire format and the server renders it into `cooked`, so nothing
 * here can change what a post *is* — only what writing one looks like. Media
 * therefore lives as its own block while it is being edited and collapses back
 * into `![name](url)` on the way out.
 */
sealed interface ComposerBlock {

    /**
     * Identity that survives editing.
     *
     * Blocks are data classes, so two empty paragraphs are equal and a block
     * whose text changes is a different value. Neither works as a list key —
     * and without one, inserting a picture shifted every later text field into
     * a different slot, taking focus and any half-typed IME composition with
     * it. Assigned once, never derived from content.
     */
    val id: Long

    /** Prose. Markdown still works inside it; it is simply not rendered here. */
    data class Text(val value: String, override val id: Long = nextBlockId()) : ComposerBlock

    /**
     * What kind of upload a [Media] block holds.
     *
     * A GIF is not a still image and not a video: it is hosted elsewhere with
     * nothing to upload, it cannot be cropped, and — like the other two — a
     * post may only carry one kind at a time. Telling them apart is what makes
     * that rule expressible.
     */
    enum class MediaKind { Image, Video, Gif }

    /** An upload, shown as itself rather than as a link to itself. */
    data class Media(
        /**
         * What goes into markdown. Usually Discourse's `upload://base62sha1.ext`
         * pseudo-scheme, which the server resolves when it renders — it is not
         * fetchable, which is why [displayUrl] exists.
         */
        val url: String,
        /** The real, fetchable address, for showing the picture here. */
        val displayUrl: String,
        val name: String,
        val kind: MediaKind = MediaKind.Image,
        /**
         * A local copy shown while the upload is in flight. Its presence *is*
         * the pending state: the picture is on the page from the moment it is
         * chosen, and only its source changes when the server answers.
         */
        val pendingPreview: String? = null,
        /**
         * A local poster frame kept past the upload. A video's [displayUrl] is
         * the server's poster, which only exists if that second upload landed;
         * when it did not, this is the only frame anyone can be shown.
         */
        val posterPath: String? = null,
        /**
         * The clip this was made from, for a clip that was.
         *
         * A picture is re-edited by fetching the uploaded copy back, which for
         * a video would mean pulling down tens of megabytes to change a trim.
         * Holding the original instead costs nothing and is better besides:
         * re-editing starts from the whole clip, so a trim can be widened
         * again rather than only tightened.
         *
         * In memory only. The grant that came with it belongs to this task, so
         * a block restored from a draft has none and simply cannot be reopened.
         */
        val sourceUri: String? = null,
        /** What was decided last time, so reopening does not start from blank. */
        val videoEdit: VideoEdit? = null,
        override val id: Long = nextBlockId(),
    ) : ComposerBlock {
        val isPending: Boolean get() = pendingPreview != null
        val isVideo: Boolean get() = kind == MediaKind.Video

        /** Whether the editor can be opened on it again. */
        val isReeditable: Boolean get() = when (kind) {
            MediaKind.Image -> true
            else -> sourceUri != null
        }
    }

    /**
     * Plugin markup with no visual form of its own — a poll. Shown as a labelled
     * chip so it can be seen and removed without reading the markup.
     */
    data class Markup(
        val markdown: String,
        @StringRes val labelRes: Int,
        override val id: Long = nextBlockId(),
    ) : ComposerBlock

    /**
     * A topic being pointed at, drawn as the card it is about to become.
     *
     * Its markdown is the bare address, which is the whole trick: a link alone
     * in its paragraph is what Discourse unfurls. Showing that address here
     * would be showing the wire format — what gets posted is a card, so a card
     * is what the composer shows, and it is not a field anyone can type into.
     *
     * [title] is known from the moment it is made; the rest arrives when the
     * topic has been fetched, so the card fills in rather than appearing late.
     */
    data class Onebox(
        val url: String,
        val title: String,
        val excerpt: String? = null,
        val nodeName: String? = null,
        val author: String? = null,
        val avatarUrl: String? = null,
        override val id: Long = nextBlockId(),
    ) : ComposerBlock
}

private val blockIds = java.util.concurrent.atomic.AtomicLong()

private fun nextBlockId(): Long = blockIds.incrementAndGet()

/** The markdown this block becomes in `raw`. */
fun ComposerBlock.toMarkdown(): String = when (this) {
    is ComposerBlock.Text -> value
    is ComposerBlock.Media ->
        if (isPending) "" else if (isVideo) "![$name|video]($url)" else "![$name]($url)"
    is ComposerBlock.Markup -> markdown
    is ComposerBlock.Onebox -> url
}

/** True when this block contributes nothing to the post. */
fun ComposerBlock.isBlank(): Boolean = when (this) {
    is ComposerBlock.Text -> value.isBlank()
    is ComposerBlock.Media -> isPending
    else -> false
}

/**
 * Blocks joined the way Discourse's markdown wants them: a blank line between
 * paragraphs, so an image on its own line stays its own paragraph rather than
 * being swallowed into the sentence above it.
 */
fun List<ComposerBlock>.toMarkdown(): String =
    filterNot { it.isBlank() }.joinToString("\n\n") { it.toMarkdown() }.trim()

/**
 * Markdown back into blocks, for a draft that arrives as text.
 *
 * Only standalone image lines become media: an image sitting inside a sentence
 * is part of that sentence, and lifting it out would reorder the post.
 */
fun String.toComposerBlocks(): List<ComposerBlock> {
    if (isBlank()) return listOf(ComposerBlock.Text(""))
    val blocks = mutableListOf<ComposerBlock>()
    val prose = StringBuilder()

    fun flush() {
        if (prose.isNotBlank()) blocks += ComposerBlock.Text(prose.toString().trim())
        prose.setLength(0)
    }

    lines().forEach { line ->
        val match = STANDALONE_IMAGE.matchEntire(line.trim())
        if (match != null) {
            flush()
            val label = match.groupValues[1]
            val href = match.groupValues[2]
            blocks += ComposerBlock.Media(
                url = href,
                displayUrl = href,
                name = label.removeSuffix("|video"),
                kind = when {
                    label.endsWith("|video") -> ComposerBlock.MediaKind.Video
                    href.substringBefore('?').endsWith(".gif", ignoreCase = true) ->
                        ComposerBlock.MediaKind.Gif
                    else -> ComposerBlock.MediaKind.Image
                },
            )
        } else {
            prose.appendLine(line)
        }
    }
    flush()
    return blocks.ifEmpty { listOf(ComposerBlock.Text("")) }
}

private val STANDALONE_IMAGE = Regex("""!\[([^]]*)]\(([^)]+)\)""")

/** The one kind of media already in these blocks, if any. */
fun List<ComposerBlock>.mediaKind(): ComposerBlock.MediaKind? =
    firstNotNullOfOrNull { (it as? ComposerBlock.Media)?.kind }

private val POLL_NAME = Regex("""\[poll[^\]]*\bname=poll(\d+)""")

private fun String.isPollMarkup() = startsWith("[poll")

/**
 * The `name=` a new poll needs, given the markup already in the post.
 *
 * Discourse rejects a post carrying two unnamed polls *and* one carrying two
 * with the same name, so this has to be the lowest index not already present
 * rather than a count — deleting the middle of three and adding another would
 * otherwise reuse a name still in the body. Empty for the first poll, which
 * needs no name at all.
 *
 * Top-level because both mistakes are invisible until publishing fails with
 * nothing pointing at why.
 */
internal fun nextPollName(existingMarkup: List<String>): String {
    val polls = existingMarkup.filter { it.isPollMarkup() }
    if (polls.isEmpty()) return ""
    val taken = polls.mapNotNull { POLL_NAME.find(it)?.groupValues?.get(1)?.toIntOrNull() }.toSet()
    return " name=poll${generateSequence(1) { it + 1 }.first { it !in taken }}"
}
