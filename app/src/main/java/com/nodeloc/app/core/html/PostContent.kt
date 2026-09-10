package com.nodeloc.app.core.html

/**
 * Typed intermediate representation of a Discourse post's `cooked` HTML.
 *
 * Posts arrive as server-rendered HTML. Stripping it to plain text loses
 * formatting, images, quotes and code; hosting a WebView per post is heavy and
 * impossible to style consistently. So the HTML is parsed once into this
 * block/inline tree and rendered with native composables.
 *
 * Two levels, mirroring HTML itself:
 *  - [PostBlock] stacks vertically and owns its own layout.
 *  - [PostInline] flows inside a line of text.
 */

/** Inline styling as a bit set, because Discourse nests `<strong><em>…`. */
@JvmInline
value class PostTextStyle(val bits: Int) {
    infix fun or(other: PostTextStyle) = PostTextStyle(bits or other.bits)
    operator fun contains(other: PostTextStyle) = (bits and other.bits) == other.bits

    companion object {
        val None = PostTextStyle(0)
        val Bold = PostTextStyle(1 shl 0)
        val Italic = PostTextStyle(1 shl 1)
        val Strikethrough = PostTextStyle(1 shl 2)
        val Code = PostTextStyle(1 shl 3)

        /**
         * Hidden until tapped. Its own bit rather than reusing [Code]: a
         * spoiler rendered as monospace text is a spoiler nobody was
         * protected from.
         */
        val Spoiler = PostTextStyle(1 shl 4)
    }
}

sealed interface PostInline {
    data class Text(val value: String) : PostInline
    data class Styled(val value: String, val style: PostTextStyle) : PostInline

    /** Children rather than a flat string, so a link can contain styled runs. */
    data class Link(val href: String, val children: List<PostInline>) : PostInline
    data class Mention(val username: String) : PostInline

    /** `<img class="emoji">`; the shortcode is the fallback while it loads. */
    data class Emoji(val url: String, val shortcode: String) : PostInline
    data object LineBreak : PostInline
}

val PostInline.plainText: String
    get() = when (this) {
        is PostInline.Text -> value
        is PostInline.Styled -> value
        is PostInline.Link -> children.plainText
        is PostInline.Mention -> "@$username"
        is PostInline.Emoji -> shortcode
        PostInline.LineBreak -> "\n"
    }

val List<PostInline>.plainText: String get() = joinToString("") { it.plainText }

val List<PostInline>.isEffectivelyEmpty: Boolean
    get() = plainText.isBlank() && none { it is PostInline.Emoji }

/**
 * `div.lightbox-wrapper > a.lightbox[href=full] > img[src=thumb,width,height]`.
 * The intrinsic size matters: without it the page reflows as each image lands.
 */
data class PostImage(
    val src: String,
    /** Full-resolution target from the enclosing lightbox link, when present. */
    val href: String? = null,
    val alt: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** The largest candidate Discourse offered in `srcset`, if any. */
    val srcsetBest: String? = null,
) {
    /**
     * What to actually load.
     *
     * `src` is Discourse's 1× optimised copy, capped around 690px — fine on a
     * desktop, visibly soft when a phone draws it full-width at 2.5× or 3×.
     * The `srcset` candidates exist for exactly this and were being ignored.
     */
    val bestSrc: String get() = srcsetBest ?: src

    val aspectRatio: Float?
        get() = if (width != null && height != null && width > 0 && height > 0) {
            width.toFloat() / height.toFloat()
        } else {
            null
        }

    val fullSizeUrl: String get() = href ?: src
}

/** `<aside class="quote">` — a quote of another post, with attribution chrome. */
data class PostQuote(
    val username: String? = null,
    val avatarUrl: String? = null,
    val topicTitle: String? = null,
    val blocks: List<PostBlock> = emptyList(),
)

/**
 * `<div class="video-placeholder-container" data-video-src="…">` — how core
 * Discourse cooks `![name|video](upload://…)`.
 */
data class PostVideo(
    val src: String,
    val originalSrc: String? = null,
    /** Attached server-side by matching an upload named after the video SHA1. */
    val posterSrc: String? = null,
) {
    /**
     * Upload SHA1, from the filename — deliberately *not* the plugin's regex,
     * which assumes a fixed directory depth and returns null for real nodeloc
     * URLs like `/original/3X/0/d/<sha1>.mp4`. The basename is always
     * `<sha1>.<ext>` at any depth.
     */
    val sha1: String?
        get() {
            val path = src.substringBefore("?")
            val stem = path.substringAfterLast('/').substringBeforeLast('.')
            return stem.takeIf { it.length == 40 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } }
                ?.lowercase()
        }
}

/** `<aside class="onebox">` — an unfurled link preview. */
data class PostOnebox(
    val url: String? = null,
    val title: String? = null,
    val descriptionText: String? = null,
    val imageUrl: String? = null,
    val faviconUrl: String? = null,
)

sealed interface PostBlock {
    data class Paragraph(val inlines: List<PostInline>) : PostBlock
    data class Heading(val level: Int, val inlines: List<PostInline>) : PostBlock
    data class Image(val image: PostImage) : PostBlock
    data class Video(val video: PostVideo) : PostBlock
    data class CodeBlock(val language: String?, val code: String) : PostBlock
    data class Quote(val quote: PostQuote) : PostBlock
    data class BlockQuote(val blocks: List<PostBlock>) : PostBlock
    data class ListBlock(val ordered: Boolean, val items: List<List<PostBlock>>) : PostBlock
    data class Table(val headers: List<List<PostInline>>, val rows: List<List<List<PostInline>>>) : PostBlock
    data class Details(val summary: List<PostInline>, val blocks: List<PostBlock>) : PostBlock
    data class Spoiler(val blocks: List<PostBlock>) : PostBlock
    data class Onebox(val onebox: PostOnebox) : PostBlock
    data object Divider : PostBlock

    /**
     * Marks where `<div class="poll">` sat. The poll is rendered from
     * `post.polls` matched on this name, so it stays where the author put it
     * instead of being appended to the end.
     */
    data class PollPlaceholder(val name: String) : PostBlock
}

/** Structural identity for list keys — content-derived so re-parsing is stable. */
val PostBlock.stableKey: String
    get() = when (this) {
        is PostBlock.Paragraph -> "p:${inlines.plainText.take(48)}"
        is PostBlock.Heading -> "h$level:${inlines.plainText.take(48)}"
        is PostBlock.Image -> "img:${image.src}"
        is PostBlock.Video -> "video:${video.src}"
        is PostBlock.CodeBlock -> "code:${language.orEmpty()}${code.take(32)}"
        is PostBlock.Quote -> "quote:${quote.username.orEmpty()}${quote.blocks.size}"
        is PostBlock.BlockQuote -> "bq:${blocks.size}:${blocks.firstOrNull()?.stableKey.orEmpty()}"
        is PostBlock.ListBlock -> "list:$ordered:${items.size}:${items.firstOrNull()?.firstOrNull()?.stableKey.orEmpty()}"
        is PostBlock.Table -> "table:${headers.size}x${rows.size}"
        is PostBlock.Details -> "details:${summary.plainText.take(32)}"
        is PostBlock.Spoiler -> "spoiler:${blocks.size}:${blocks.firstOrNull()?.stableKey.orEmpty()}"
        is PostBlock.Onebox -> "onebox:${onebox.url ?: onebox.title.orEmpty()}"
        PostBlock.Divider -> "hr"
        is PostBlock.PollPlaceholder -> "poll:$name"
    }

val PostBlock.plainText: String
    get() = when (this) {
        is PostBlock.Paragraph -> inlines.plainText
        is PostBlock.Heading -> inlines.plainText
        is PostBlock.Image -> image.alt.orEmpty()
        is PostBlock.Video -> "[video]"
        is PostBlock.CodeBlock -> code
        is PostBlock.Quote -> quote.blocks.joinToString("\n") { it.plainText }
        is PostBlock.BlockQuote -> blocks.joinToString("\n") { it.plainText }
        is PostBlock.ListBlock -> items.joinToString("\n") { item -> item.joinToString("") { it.plainText } }
        is PostBlock.Table -> (listOf(headers.map { it.plainText }) + rows.map { row -> row.map { it.plainText } })
            .joinToString("\n") { it.joinToString(" ") }
        is PostBlock.Details -> (listOf(summary.plainText) + blocks.map { it.plainText }).joinToString("\n")
        is PostBlock.Spoiler -> blocks.joinToString("\n") { it.plainText }
        is PostBlock.Onebox -> listOfNotNull(onebox.title, onebox.descriptionText).joinToString(" ")
        PostBlock.Divider -> ""
        is PostBlock.PollPlaceholder -> ""
    }

/** A parsed post body, plus the derived bits the UI asks for repeatedly. */
data class PostContent(val blocks: List<PostBlock> = emptyList()) {
    val isEmpty: Boolean get() = blocks.isEmpty()

    /** Every image in reading order, for the full-screen viewer's paging. */
    val images: List<PostImage> by lazy { collectImages(blocks) }

    /** Every video in reading order, for the same reason. */
    val videos: List<PostVideo> by lazy { collectVideos(blocks) }

    val plainText: String by lazy {
        blocks.map { it.plainText }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    fun excerpt(limit: Int = 120): String {
        val flat = plainText.replace("\n", " ").trim()
        return if (flat.length > limit) flat.take(limit) + "…" else flat
    }

    companion object {
        val Empty = PostContent()

        private fun collectImages(blocks: List<PostBlock>): List<PostImage> = blocks.flatMap { block ->
            when (block) {
                is PostBlock.Image -> listOf(block.image)
                is PostBlock.Quote -> collectImages(block.quote.blocks)
                is PostBlock.BlockQuote -> collectImages(block.blocks)
                is PostBlock.ListBlock -> block.items.flatMap { collectImages(it) }
                is PostBlock.Details -> collectImages(block.blocks)
                is PostBlock.Spoiler -> collectImages(block.blocks)
                else -> emptyList()
            }
        }

        private fun collectVideos(blocks: List<PostBlock>): List<PostVideo> = blocks.flatMap { block ->
            when (block) {
                is PostBlock.Video -> listOf(block.video)
                is PostBlock.Quote -> collectVideos(block.quote.blocks)
                is PostBlock.BlockQuote -> collectVideos(block.blocks)
                is PostBlock.ListBlock -> block.items.flatMap { collectVideos(it) }
                is PostBlock.Details -> collectVideos(block.blocks)
                is PostBlock.Spoiler -> collectVideos(block.blocks)
                else -> emptyList()
            }
        }
    }
}
