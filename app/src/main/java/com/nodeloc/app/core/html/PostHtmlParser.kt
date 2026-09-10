package com.nodeloc.app.core.html

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns Discourse's `cooked` HTML into a [PostContent] block tree.
 *
 * Hand-written rather than a DOM library because the input is narrow: `cooked`
 * is server-generated from markdown, so it is well-formed and uses a small,
 * stable tag vocabulary. The scanner walks the string by index with an explicit
 * element stack; regex is deliberately avoided, since nested structures (a
 * quote containing a list containing a link) cannot be matched correctly by
 * regular expressions — and the naive `<[^>]+>` strip is exactly the behaviour
 * this replaces.
 */

private class HtmlTag(
    val name: String,
    val attributes: Map<String, String>,
    val isClosing: Boolean,
    val isSelfClosing: Boolean,
)

private sealed interface HtmlToken {
    class Text(val value: String) : HtmlToken
    class Tag(val tag: HtmlTag) : HtmlToken
}

/** Elements that never have a closing tag. */
private val VOID_ELEMENTS = setOf("br", "img", "hr", "input", "meta", "link", "source", "col", "area", "wbr")

/**
 * Elements whose entire subtree is dropped. Discourse injects inline SVG icon
 * sprites into lightbox chrome; without this their `<use>`/`<path>` contents
 * leak through as stray text.
 */
private val DROPPED_ELEMENTS = setOf("svg", "script", "style", "head")

object PostHtmlParser {

    /**
     * Nesting deeper than this degrades to plain text.
     *
     * Block and inline assembly are mutually recursive, one frame per tag
     * level, and a pathological post (hundreds of nested tags) would otherwise
     * overflow the worker thread's stack — on iOS that was the "one specific
     * post kills the app" crash. Real content measures 6–15 levels, so 40 is a
     * fuse, not a tunable feature.
     */
    private const val MAX_NESTING_DEPTH = 40

    /** Parses off the main thread; Default, not IO — this is CPU work. */
    suspend fun parse(html: String?): PostContent = withContext(Dispatchers.Default) { parseSync(html) }

    fun parseSync(html: String?): PostContent {
        if (html.isNullOrEmpty()) return PostContent.Empty
        val tokens = tokenize(html)
        val cursor = Cursor()
        val blocks = parseBlocks(tokens, cursor, null, 0)
        return PostContent(normalize(blocks))
    }

    /** Boxed index so the mutually recursive walkers share one position. */
    private class Cursor(var index: Int = 0)

    // ------------------------------------------------------------ scanning

    private fun tokenize(html: String): List<HtmlToken> {
        val tokens = mutableListOf<HtmlToken>()
        val chars = html
        var index = 0
        var textStart = 0

        fun flushText(end: Int) {
            if (end <= textStart) return
            val decoded = decodeEntities(chars.substring(textStart, end))
            if (decoded.isNotEmpty()) tokens += HtmlToken.Text(decoded)
        }

        while (index < chars.length) {
            if (chars[index] != '<') {
                index++
                continue
            }
            val next = if (index + 1 < chars.length) chars[index + 1] else ' '
            // A '<' not followed by a name is literal text, not a tag.
            if (next != '/' && next != '!' && !next.isLetter()) {
                index++
                continue
            }
            flushText(index)

            if (next == '!') {
                // Comments and doctypes carry nothing we render.
                val commentEnd = chars.indexOf("-->", index)
                val plainEnd = chars.indexOf('>', index)
                index = when {
                    commentEnd >= 0 && (plainEnd < 0 || commentEnd < plainEnd) -> commentEnd + 3
                    plainEnd >= 0 -> plainEnd + 1
                    else -> chars.length
                }
                textStart = index
                continue
            }

            val tagEnd = findTagEnd(chars, index)
            if (tagEnd < 0) {
                // Unterminated '<': drop the partial tag. textStart must advance
                // too, or the tail flushed above would be emitted twice.
                index = chars.length
                textStart = index
                break
            }
            parseTag(chars.substring(index + 1, tagEnd))?.let { tokens += HtmlToken.Tag(it) }
            index = tagEnd + 1
            textStart = index
        }
        flushText(chars.length)
        return tokens
    }

    /** Finds the '>' that closes a tag, skipping any inside quoted attributes. */
    private fun findTagEnd(chars: String, start: Int): Int {
        var index = start + 1
        var quote: Char? = null
        while (index < chars.length) {
            val c = chars[index]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return index
            }
            index++
        }
        return -1
    }

    private fun parseTag(raw: String): HtmlTag? {
        var body = raw.trim()
        if (body.isEmpty()) return null
        val isClosing = body.startsWith("/")
        if (isClosing) body = body.substring(1)
        var selfClosing = false
        if (body.endsWith("/")) {
            selfClosing = true
            body = body.dropLast(1)
        }
        var index = 0
        while (index < body.length && !body[index].isWhitespace()) index++
        val name = body.substring(0, index).lowercase()
        if (name.isEmpty()) return null
        val attributes = if (isClosing) emptyMap() else parseAttributes(body.substring(index))
        return HtmlTag(name, attributes, isClosing, selfClosing || name in VOID_ELEMENTS)
    }

    private fun parseAttributes(source: String): Map<String, String> {
        val attributes = mutableMapOf<String, String>()
        var index = 0
        while (index < source.length) {
            while (index < source.length && source[index].isWhitespace()) index++
            if (index >= source.length) break

            var nameEnd = index
            while (nameEnd < source.length && !source[nameEnd].isWhitespace() && source[nameEnd] != '=') nameEnd++
            val name = source.substring(index, nameEnd).lowercase()
            index = nameEnd

            while (index < source.length && source[index].isWhitespace()) index++
            if (index >= source.length || source[index] != '=') {
                // Valueless attribute, e.g. `hidden`.
                if (name.isNotEmpty()) attributes[name] = ""
                continue
            }
            index++
            while (index < source.length && source[index].isWhitespace()) index++
            if (index >= source.length) break

            val value: String
            if (source[index] == '"' || source[index] == '\'') {
                val quote = source[index]
                index++
                val valueStart = index
                while (index < source.length && source[index] != quote) index++
                value = source.substring(valueStart, index)
                if (index < source.length) index++
            } else {
                val valueStart = index
                while (index < source.length && !source[index].isWhitespace()) index++
                value = source.substring(valueStart, index)
            }
            if (name.isNotEmpty()) attributes[name] = decodeEntities(value)
        }
        return attributes
    }

    // ------------------------------------------------------ recursion guard

    /** Over-depth fallback: consumes the subtree keeping only its text. */
    private fun flattenedText(tokens: List<HtmlToken>, cursor: Cursor, closing: String?): String {
        var open = 1
        val text = StringBuilder()
        while (cursor.index < tokens.size) {
            when (val token = tokens[cursor.index]) {
                is HtmlToken.Text -> text.append(token.value)
                is HtmlToken.Tag -> {
                    val tag = token.tag
                    if (closing != null && tag.name == closing) {
                        if (tag.isClosing) {
                            open--
                            if (open == 0) {
                                cursor.index++
                                return text.toString()
                            }
                        } else if (!tag.isSelfClosing) {
                            open++
                        }
                    }
                }
            }
            cursor.index++
        }
        return text.toString()
    }

    // ------------------------------------------------------ block assembly

    private fun parseBlocks(
        tokens: List<HtmlToken>,
        cursor: Cursor,
        closing: String?,
        depth: Int,
    ): List<PostBlock> {
        if (depth >= MAX_NESTING_DEPTH) {
            val text = flattenedText(tokens, cursor, closing).trim()
            return if (text.isEmpty()) emptyList() else listOf(PostBlock.Paragraph(listOf(PostInline.Text(text))))
        }

        val blocks = mutableListOf<PostBlock>()
        var pending = mutableListOf<PostInline>()

        fun flushInlines() {
            val trimmed = trimEdges(pending)
            if (!trimmed.isEffectivelyEmpty) blocks += PostBlock.Paragraph(trimmed)
            pending = mutableListOf()
        }

        while (cursor.index < tokens.size) {
            when (val token = tokens[cursor.index]) {
                is HtmlToken.Text -> {
                    pending += PostInline.Text(token.value)
                    cursor.index++
                }
                is HtmlToken.Tag -> {
                    val tag = token.tag
                    if (tag.isClosing) {
                        cursor.index++
                        if (closing != null && tag.name == closing) {
                            flushInlines()
                            return blocks
                        }
                        // Stray or mismatched close: ignore it and keep going,
                        // the way a browser would, rather than abandoning the
                        // rest of the subtree.
                        continue
                    }
                    if (tag.name in DROPPED_ELEMENTS) {
                        cursor.index++
                        if (!tag.isSelfClosing) skipSubtree(tokens, cursor, tag.name)
                        continue
                    }
                    if (isBlockLevel(tag)) {
                        flushInlines()
                        cursor.index++
                        parseBlockElement(tag, tokens, cursor, depth + 1)?.let { blocks += it }
                    } else {
                        pending += parseInlineElement(tag, tokens, cursor, depth + 1)
                    }
                }
            }
        }
        flushInlines()
        return blocks
    }

    private fun isBlockLevel(tag: HtmlTag): Boolean = when (tag.name) {
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li",
        "blockquote", "aside", "pre", "hr", "table", "details", "figure",
        "section", "article", "video", "iframe",
        -> true
        // A bare image is a block; an emoji is inline.
        "img" -> !isEmoji(tag)
        // Only the lightbox flavour, which wraps a full-width image.
        "a" -> "lightbox" in classList(tag)
        else -> false
    }

    private fun parseBlockElement(
        tag: HtmlTag,
        tokens: List<HtmlToken>,
        cursor: Cursor,
        depth: Int,
    ): List<PostBlock>? {
        val classes = classList(tag)
        return when (tag.name) {
            "hr" -> listOf(PostBlock.Divider)

            "img" -> listOf(PostBlock.Image(imageBlock(tag, null)))

            "br" -> null

            // `a.lightbox` wraps the thumbnail and carries the full-size href;
            // its subtree also holds `.meta` chrome that must not surface.
            "a" -> extractLightboxImage(tokens, cursor, tag.attributes["href"])?.let { listOf(PostBlock.Image(it)) }

            "video" -> {
                val source = tag.attributes["src"] ?: nestedVideoSource(tokens, cursor, "video")
                if (source.isNullOrEmpty()) {
                    null
                } else {
                    listOf(PostBlock.Video(PostVideo(src = source, posterSrc = tag.attributes["poster"])))
                }
            }

            "p", "div", "section", "article", "figure" -> when {
                "poll" in classes -> {
                    val name = tag.attributes["data-poll-name"] ?: "poll"
                    skipSubtree(tokens, cursor, tag.name)
                    listOf(PostBlock.PollPlaceholder(name))
                }
                // How core cooks `![name|video](upload://…)`: the element is
                // empty, everything lives in its data attributes.
                "video-placeholder-container" in classes && !tag.attributes["data-video-src"].isNullOrEmpty() -> {
                    val video = PostVideo(
                        src = tag.attributes["data-video-src"]!!,
                        originalSrc = tag.attributes["data-orig-src"],
                        posterSrc = tag.attributes["data-thumbnail-src"],
                    )
                    skipSubtree(tokens, cursor, tag.name)
                    listOf(PostBlock.Video(video))
                }
                "spoiler" in classes || "spoiled" in classes ->
                    listOf(PostBlock.Spoiler(parseBlocks(tokens, cursor, tag.name, depth)))

                // An embed carries its content in attributes and an iframe, so
                // there is no text to fall through to: a post whose whole body
                // was a YouTube or Bilibili link rendered as nothing at all.
                // Lowercased: classList normalises, so the camel-case forms
                // Discourse writes could never have matched.
                "onebox" in classes || "lazyyt" in classes || "lazyyt-container" in classes ->
                    listOf(PostBlock.Onebox(parseEmbedOnebox(tag, tokens, cursor)))

                else -> parseBlocks(tokens, cursor, tag.name, depth).takeIf { it.isNotEmpty() }
            }

            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val level = tag.name.drop(1).toIntOrNull() ?: 1
                val trimmed = trimEdges(parseInlinesUntil(tokens, cursor, tag.name, depth))
                if (trimmed.isEffectivelyEmpty) null else listOf(PostBlock.Heading(level, trimmed))
            }

            // A bare iframe — a player Discourse embedded directly. Consumed to
            // its close, or its contents leak out as stray text.
            "iframe" -> {
                val src = tag.attributes["src"]
                // Only when there is a close tag to find. skipSubtree counts
                // depth from the opening tag, and a self-closing one never
                // opens — so it ran to the end of the post and ate everything
                // after the embed.
                if (!tag.isSelfClosing) skipSubtree(tokens, cursor, "iframe")
                src?.let { listOf(PostBlock.Onebox(PostOnebox(url = it))) }
            }

            "pre" -> listOf(parseCodeBlock(tokens, cursor))

            "blockquote" -> parseBlocks(tokens, cursor, "blockquote", depth)
                .takeIf { it.isNotEmpty() }
                ?.let { listOf(PostBlock.BlockQuote(it)) }

            "aside" -> when {
                "quote" in classes -> listOf(PostBlock.Quote(parseQuote(tag, tokens, cursor, depth)))
                "onebox" in classes -> listOf(PostBlock.Onebox(parseOnebox(tokens, cursor, "aside")))
                else -> parseBlocks(tokens, cursor, "aside", depth).takeIf { it.isNotEmpty() }
            }

            "details" -> listOf(parseDetails(tokens, cursor, depth))

            "ul", "ol" -> listOf(parseList(tag.name == "ol", tokens, cursor, depth))

            // A list item outside a list; treat its contents as loose blocks.
            "li" -> parseBlocks(tokens, cursor, "li", depth).takeIf { it.isNotEmpty() }

            "table" -> listOf(parseTable(tokens, cursor, depth))

            else -> parseBlocks(tokens, cursor, tag.name, depth).takeIf { it.isNotEmpty() }
        }
    }

    /** `<video>` without a `src` carries `<source>` children. */
    private fun nestedVideoSource(tokens: List<HtmlToken>, cursor: Cursor, closing: String): String? {
        var source: String? = null
        while (cursor.index < tokens.size) {
            val token = tokens[cursor.index]
            if (token is HtmlToken.Tag) {
                val tag = token.tag
                if (tag.isClosing && tag.name == closing) {
                    cursor.index++
                    return source
                }
                if (tag.name == "source" && source == null) source = tag.attributes["src"]
            }
            cursor.index++
        }
        return source
    }

    /** Pulls the `<img>` out of a lightbox anchor and skips its chrome. */
    private fun extractLightboxImage(tokens: List<HtmlToken>, cursor: Cursor, href: String?): PostImage? {
        var image: PostImage? = null
        var depth = 1
        while (cursor.index < tokens.size) {
            val token = tokens[cursor.index]
            if (token is HtmlToken.Tag) {
                val tag = token.tag
                if (tag.isClosing) {
                    if (tag.name == "a") {
                        depth--
                        cursor.index++
                        if (depth == 0) return image
                        continue
                    }
                    cursor.index++
                    continue
                }
                if (tag.name == "a") depth++
                if (tag.name == "img" && image == null && !isEmoji(tag)) image = imageBlock(tag, href)
            }
            cursor.index++
        }
        return image
    }

    // --------------------------------------------------- specific structures

    private fun parseCodeBlock(tokens: List<HtmlToken>, cursor: Cursor): PostBlock {
        var language: String? = null
        val code = StringBuilder()
        while (cursor.index < tokens.size) {
            when (val token = tokens[cursor.index]) {
                is HtmlToken.Text -> {
                    code.append(token.value)
                    cursor.index++
                }
                is HtmlToken.Tag -> {
                    val tag = token.tag
                    if (tag.isClosing && tag.name == "pre") {
                        cursor.index++
                        return PostBlock.CodeBlock(language, trimEdgeNewlines(code.toString()))
                    }
                    if (!tag.isClosing && tag.name == "code") {
                        // Discourse marks the language `lang-x` / `language-x`.
                        language = classList(tag)
                            .firstOrNull { it.startsWith("lang-") || it.startsWith("language-") }
                            ?.removePrefix("language-")?.removePrefix("lang-")
                            // `lang-auto` is what Discourse writes when the
                            // author named no language — which is most fenced
                            // blocks. Printing "auto" above them says nothing.
                            ?.takeUnless { it in GENERIC_LANGUAGES }
                    }
                    if (!tag.isClosing && tag.name == "br") code.append('\n')
                    cursor.index++
                }
            }
        }
        return PostBlock.CodeBlock(language, trimEdgeNewlines(code.toString()))
    }

    private fun parseQuote(tag: HtmlTag, tokens: List<HtmlToken>, cursor: Cursor, depth: Int): PostQuote {
        // Discourse puts the author on the aside itself and repeats it in a
        // `<div class="title">` header that also holds the avatar.
        var username = tag.attributes["data-username"]
        var avatarUrl: String? = null
        var topicTitle: String? = null
        val blocks = mutableListOf<PostBlock>()
        var asideDepth = 1

        while (cursor.index < tokens.size) {
            val token = tokens[cursor.index]
            if (token is HtmlToken.Text) {
                // Loose text inside the aside chrome; the blockquote carries
                // the real content.
                cursor.index++
                continue
            }
            val inner = (token as HtmlToken.Tag).tag
            if (inner.isClosing) {
                if (inner.name == "aside") {
                    asideDepth--
                    cursor.index++
                    if (asideDepth == 0) return PostQuote(username, avatarUrl, topicTitle, blocks)
                    continue
                }
                cursor.index++
                continue
            }
            if (inner.name == "aside") asideDepth++
            val classes = classList(inner)
            when {
                inner.name == "img" && avatarUrl == null && !isEmoji(inner) -> {
                    avatarUrl = inner.attributes["src"]
                    cursor.index++
                }
                inner.name == "blockquote" -> {
                    cursor.index++
                    blocks += parseBlocks(tokens, cursor, "blockquote", depth)
                }
                inner.name == "a" && "badge-category" !in classes && topicTitle == null &&
                    inner.attributes["href"]?.contains("/t/") == true -> {
                    cursor.index++
                    val title = parseInlinesUntil(tokens, cursor, "a", depth).plainText.trim()
                    if (title.isNotEmpty()) topicTitle = title
                }
                else -> cursor.index++
            }
        }
        if (username?.isEmpty() == true) username = null
        return PostQuote(username, avatarUrl, topicTitle, blocks)
    }

    /**
     * Onebox markup is `<header class="source">` (favicon + domain link)
     * followed by `<article class="onebox-body">` with an `<h3>` title. The
     * heading is the real title — the header link is only the domain — so the
     * two regions are tracked separately.
     */
    /**
     * A onebox whose link is in its attributes rather than its markup.
     *
     * `lazyYT` gives only a video id; a generic embed wrapper gives a URL or an
     * iframe. Whatever markup it does carry still goes through [parseOnebox],
     * so a title and thumbnail are picked up when they exist.
     */
    private fun parseEmbedOnebox(tag: HtmlTag, tokens: List<HtmlToken>, cursor: Cursor): PostOnebox {
        val fromAttributes = tag.attributes["data-youtube-id"]
            ?.let { "https://www.youtube.com/watch?v=$it" }
            ?: tag.attributes["data-onebox-src"]
            ?: tag.attributes["href"]

        val parsed = parseOnebox(tokens, cursor, tag.name)
        val title = tag.attributes["data-youtube-title"]?.takeIf { it.isNotBlank() } ?: parsed.title
        return parsed.copy(
            url = parsed.url ?: fromAttributes,
            title = title,
        )
    }

    private fun parseOnebox(tokens: List<HtmlToken>, cursor: Cursor, closing: String): PostOnebox {
        var url: String? = null
        var title: String? = null
        var imageUrl: String? = null
        var faviconUrl: String? = null
        val bodyRuns = mutableListOf<String>()
        var depth = 1
        var inHeading = false
        var inHeader = false

        while (cursor.index < tokens.size) {
            when (val token = tokens[cursor.index]) {
                is HtmlToken.Text -> {
                    val trimmed = token.value.trim()
                    if (trimmed.isNotEmpty()) {
                        if (inHeading) title = (title ?: "") + trimmed else if (!inHeader) bodyRuns += trimmed
                    }
                    cursor.index++
                }
                is HtmlToken.Tag -> {
                    val tag = token.tag
                    val classes = classList(tag)
                    if (tag.isClosing) {
                        when (tag.name) {
                            closing -> {
                                depth--
                                cursor.index++
                                if (depth == 0) return finishOnebox(url, title, imageUrl, faviconUrl, bodyRuns)
                                continue
                            }
                            "h1", "h2", "h3", "h4", "h5", "h6" -> inHeading = false
                            "header" -> inHeader = false
                        }
                        cursor.index++
                        continue
                    }
                    if (tag.name == closing) depth++
                    if (tag.name == "header" || "source" in classes) inHeader = true
                    if (tag.name in setOf("h1", "h2", "h3", "h4", "h5", "h6")) inHeading = true
                    if (tag.name == "a" && url == null) url = tag.attributes["href"]
                    // An embed's link is its iframe. Without this a onebox
                    // wrapping only a player had no url at all and rendered as
                    // an empty bordered box with nothing to tap.
                    if (tag.name == "iframe" && url == null) url = tag.attributes["src"]
                    if (tag.name == "img") {
                        if ("site-icon" in classes || "favicon" in classes) {
                            faviconUrl = faviconUrl ?: tag.attributes["src"]
                        } else if (imageUrl == null && !isEmoji(tag)) {
                            imageUrl = tag.attributes["src"]
                        }
                    }
                    cursor.index++
                }
            }
        }
        return finishOnebox(url, title, imageUrl, faviconUrl, bodyRuns)
    }

    private fun finishOnebox(
        url: String?,
        title: String?,
        imageUrl: String?,
        faviconUrl: String?,
        bodyRuns: List<String>,
    ): PostOnebox {
        val resolvedTitle = title ?: bodyRuns.firstOrNull()
        val description = if (resolvedTitle == bodyRuns.firstOrNull()) bodyRuns.drop(1) else bodyRuns
        return PostOnebox(
            url = url,
            title = resolvedTitle,
            descriptionText = description.takeIf { it.isNotEmpty() }?.joinToString(" "),
            imageUrl = imageUrl,
            faviconUrl = faviconUrl,
        )
    }

    private fun parseDetails(tokens: List<HtmlToken>, cursor: Cursor, depth: Int): PostBlock {
        var summary: List<PostInline> = emptyList()
        val blocks = mutableListOf<PostBlock>()
        var pending = mutableListOf<PostInline>()

        fun flushInlines() {
            val trimmed = trimEdges(pending)
            if (!trimmed.isEffectivelyEmpty) blocks += PostBlock.Paragraph(trimmed)
            pending = mutableListOf()
        }

        while (cursor.index < tokens.size) {
            when (val token = tokens[cursor.index]) {
                is HtmlToken.Text -> {
                    pending += PostInline.Text(token.value)
                    cursor.index++
                }
                is HtmlToken.Tag -> {
                    val tag = token.tag
                    if (tag.isClosing && tag.name == "details") {
                        cursor.index++
                        flushInlines()
                        return PostBlock.Details(summary, blocks)
                    }
                    if (tag.isClosing) {
                        cursor.index++
                        continue
                    }
                    if (tag.name == "summary") {
                        cursor.index++
                        summary = trimEdges(parseInlinesUntil(tokens, cursor, "summary", depth))
                        continue
                    }
                    if (tag.name in DROPPED_ELEMENTS) {
                        cursor.index++
                        if (!tag.isSelfClosing) skipSubtree(tokens, cursor, tag.name)
                        continue
                    }
                    if (isBlockLevel(tag)) {
                        flushInlines()
                        cursor.index++
                        parseBlockElement(tag, tokens, cursor, depth + 1)?.let { blocks += it }
                    } else {
                        pending += parseInlineElement(tag, tokens, cursor, depth + 1)
                    }
                }
            }
        }
        flushInlines()
        return PostBlock.Details(summary, blocks)
    }

    private fun parseList(ordered: Boolean, tokens: List<HtmlToken>, cursor: Cursor, depth: Int): PostBlock {
        val items = mutableListOf<List<PostBlock>>()
        val listTag = if (ordered) "ol" else "ul"
        while (cursor.index < tokens.size) {
            val token = tokens[cursor.index]
            if (token is HtmlToken.Text) {
                cursor.index++
                continue
            }
            val tag = (token as HtmlToken.Tag).tag
            if (tag.isClosing && tag.name == listTag) {
                cursor.index++
                return PostBlock.ListBlock(ordered, items)
            }
            if (tag.isClosing) {
                cursor.index++
                continue
            }
            if (tag.name == "li") {
                cursor.index++
                items += parseBlocks(tokens, cursor, "li", depth)
                continue
            }
            cursor.index++
        }
        return PostBlock.ListBlock(ordered, items)
    }

    private fun parseTable(tokens: List<HtmlToken>, cursor: Cursor, depth: Int): PostBlock {
        var headers: List<List<PostInline>> = emptyList()
        val rows = mutableListOf<List<List<PostInline>>>()
        var currentRow = mutableListOf<List<PostInline>>()
        var rowIsHeader = false

        while (cursor.index < tokens.size) {
            val token = tokens[cursor.index]
            if (token is HtmlToken.Text) {
                cursor.index++
                continue
            }
            val tag = (token as HtmlToken.Tag).tag
            if (tag.isClosing) {
                if (tag.name == "table") {
                    cursor.index++
                    if (currentRow.isNotEmpty()) rows += currentRow
                    return PostBlock.Table(headers, rows)
                }
                if (tag.name == "tr") {
                    if (rowIsHeader) headers = currentRow else if (currentRow.isNotEmpty()) rows += currentRow
                    currentRow = mutableListOf()
                    rowIsHeader = false
                }
                cursor.index++
                continue
            }
            if (tag.name == "tr") {
                currentRow = mutableListOf()
                rowIsHeader = false
                cursor.index++
                continue
            }
            if (tag.name == "th" || tag.name == "td") {
                if (tag.name == "th") rowIsHeader = true
                cursor.index++
                currentRow += trimEdges(parseInlinesUntil(tokens, cursor, tag.name, depth))
                continue
            }
            cursor.index++
        }
        if (currentRow.isNotEmpty()) rows += currentRow
        return PostBlock.Table(headers, rows)
    }

    // ----------------------------------------------------- inline assembly

    private fun parseInlinesUntil(
        tokens: List<HtmlToken>,
        cursor: Cursor,
        closing: String,
        depth: Int,
    ): List<PostInline> {
        if (depth >= MAX_NESTING_DEPTH) {
            return listOf(PostInline.Text(flattenedText(tokens, cursor, closing)))
        }
        val inlines = mutableListOf<PostInline>()
        while (cursor.index < tokens.size) {
            when (val token = tokens[cursor.index]) {
                is HtmlToken.Text -> {
                    inlines += PostInline.Text(token.value)
                    cursor.index++
                }
                is HtmlToken.Tag -> {
                    val tag = token.tag
                    if (tag.isClosing) {
                        cursor.index++
                        if (tag.name == closing) return inlines
                        continue
                    }
                    if (tag.name in DROPPED_ELEMENTS) {
                        cursor.index++
                        if (!tag.isSelfClosing) skipSubtree(tokens, cursor, tag.name)
                        continue
                    }
                    inlines += parseInlineElement(tag, tokens, cursor, depth + 1)
                }
            }
        }
        return inlines
    }

    private fun parseInlineElement(
        tag: HtmlTag,
        tokens: List<HtmlToken>,
        cursor: Cursor,
        depth: Int,
    ): List<PostInline> {
        val classes = classList(tag)
        return when (tag.name) {
            "br" -> {
                cursor.index++
                listOf(PostInline.LineBreak)
            }

            "img" -> {
                cursor.index++
                if (isEmoji(tag)) {
                    val shortcode = tag.attributes["alt"] ?: tag.attributes["title"] ?: ""
                    listOf(PostInline.Emoji(tag.attributes["src"].orEmpty(), shortcode))
                } else {
                    // A non-emoji image reached inline (e.g. inside a link):
                    // keep its alt text so the sentence still reads.
                    tag.attributes["alt"]?.let { listOf(PostInline.Text(it)) } ?: emptyList()
                }
            }

            "a" -> {
                cursor.index++
                val children = parseInlinesUntil(tokens, cursor, "a", depth)
                val href = tag.attributes["href"].orEmpty()
                when {
                    "mention" in classes -> {
                        val username = children.plainText.trim().trim('@', ' ')
                        if (username.isEmpty()) children else listOf(PostInline.Mention(username))
                    }

                    // The newer `hashtag-cooked` markup puts the name in a
                    // child span and the `#` in an icon, so the text alone read
                    // as a bare word with no sign it was a tag.
                    "hashtag-cooked" in classes || "hashtag" in classes -> {
                        val name = children.plainText.trim().trimStart('#')
                        if (name.isEmpty()) {
                            emptyList()
                        } else {
                            listOf(PostInline.Link(href, listOf(PostInline.Text("#$name"))))
                        }
                    }

                    children.isEffectivelyEmpty -> emptyList()
                    href.isEmpty() -> children

                    // A fragment is a position in this page, not a place to go.
                    // Resolved against the site root it became a link to the
                    // home page, which is where every footnote marker led.
                    href.startsWith("#") -> children

                    else -> listOf(PostInline.Link(href, children))
                }
            }

            "strong", "b" -> {
                cursor.index++
                styled(parseInlinesUntil(tokens, cursor, tag.name, depth), PostTextStyle.Bold)
            }

            "em", "i" -> {
                cursor.index++
                styled(parseInlinesUntil(tokens, cursor, tag.name, depth), PostTextStyle.Italic)
            }

            "del", "s", "strike" -> {
                cursor.index++
                styled(parseInlinesUntil(tokens, cursor, tag.name, depth), PostTextStyle.Strikethrough)
            }

            // Lightbox chrome: the filename and "1920×1080 200 KB" that the
            // web client shows under a zoomable image. It is caption furniture,
            // not content, and inline it leaked out as "x.png1920×1080 200 KB"
            // glued to whatever followed.
            "div", "span" -> if ("meta" in classes) {
                cursor.index++
                if (!tag.isSelfClosing) skipSubtree(tokens, cursor, tag.name)
                emptyList()
            } else if (tag.isSelfClosing) {
                cursor.index++
                emptyList()
            } else {
                cursor.index++
                val children = parseInlinesUntil(tokens, cursor, tag.name, depth)
                if ("spoiler" in classes || "spoiled" in classes) {
                    // discourse-spoiler-alert emits a span for the inline
                    // `[spoiler]` form, which is the common one. This used to
                    // style it as code and show it in the clear.
                    styled(children, PostTextStyle.Spoiler)
                } else {
                    children
                }
            }

            "code" -> {
                cursor.index++
                styled(parseInlinesUntil(tokens, cursor, "code", depth), PostTextStyle.Code)
            }

            else -> {
                cursor.index++
                if (tag.isSelfClosing) emptyList() else parseInlinesUntil(tokens, cursor, tag.name, depth)
            }
        }
    }

    private fun styled(inlines: List<PostInline>, style: PostTextStyle): List<PostInline> = inlines.map { inline ->
        when (inline) {
            is PostInline.Text -> PostInline.Styled(inline.value, style)
            is PostInline.Styled -> PostInline.Styled(inline.value, inline.style or style)
            is PostInline.Link -> PostInline.Link(inline.href, styled(inline.children, style))
            else -> inline
        }
    }

    // --------------------------------------------------------------- helpers

    private fun imageBlock(tag: HtmlTag, href: String?) = PostImage(
        src = tag.attributes["src"].orEmpty(),
        href = href ?: tag.attributes["data-download-href"],
        alt = tag.attributes["alt"],
        width = tag.attributes["width"]?.toIntOrNull(),
        height = tag.attributes["height"]?.toIntOrNull(),
        srcsetBest = largestSrcsetCandidate(tag.attributes["srcset"]),
    )

    /**
     * The highest-density candidate in a `srcset`.
     *
     * Discourse writes `url 1x, url2 2x` (or `w` descriptors). The plain `src`
     * beside it is the 1× copy, which a phone draws at two or three times that
     * — so ignoring this meant every full-width post image was upscaled from a
     * thumbnail.
     */
    private fun largestSrcsetCandidate(srcset: String?): String? {
        if (srcset.isNullOrBlank()) return null
        var bestUrl: String? = null
        var bestWeight = 0f
        srcset.split(',').forEach { candidate ->
            val parts = candidate.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val url = parts.firstOrNull() ?: return@forEach
            val descriptor = parts.getOrNull(1)
            val weight = when {
                descriptor == null -> 1f
                descriptor.endsWith("x") -> descriptor.dropLast(1).toFloatOrNull() ?: 1f
                descriptor.endsWith("w") -> descriptor.dropLast(1).toFloatOrNull() ?: 1f
                else -> 1f
            }
            if (weight > bestWeight) {
                bestWeight = weight
                bestUrl = url
            }
        }
        // Only when it beats the plain `src`, which is always the 1x copy.
        return bestUrl.takeIf { bestWeight > 1f }
    }

    private val GENERIC_LANGUAGES = setOf("auto", "nohighlight", "plaintext", "text", "none")

    private fun classList(tag: HtmlTag): List<String> =
        tag.attributes["class"].orEmpty().split(" ").filter { it.isNotEmpty() }.map { it.lowercase() }

    private fun isEmoji(tag: HtmlTag): Boolean =
        classList(tag).any { it == "emoji" || it.startsWith("emoji-") }

    /** Advances past the matching close tag, honouring nesting. */
    private fun skipSubtree(tokens: List<HtmlToken>, cursor: Cursor, name: String) {
        var depth = 1
        while (cursor.index < tokens.size) {
            val token = tokens[cursor.index]
            if (token is HtmlToken.Tag) {
                val tag = token.tag
                if (tag.name == name && !tag.isSelfClosing) {
                    depth += if (tag.isClosing) -1 else 1
                    if (depth == 0) {
                        cursor.index++
                        return
                    }
                }
            }
            cursor.index++
        }
    }

    private fun trimEdges(inlines: List<PostInline>): List<PostInline> {
        var result = inlines.toMutableList()
        while (result.isNotEmpty()) {
            val first = result.first()
            val drop = (first is PostInline.Text && first.value.isBlank()) || first is PostInline.LineBreak
            if (drop) result.removeAt(0) else break
        }
        while (result.isNotEmpty()) {
            val last = result.last()
            val drop = (last is PostInline.Text && last.value.isBlank()) || last is PostInline.LineBreak
            if (drop) result.removeAt(result.size - 1) else break
        }
        // Collapse the newlines Discourse leaves between tags: source
        // formatting, not content.
        return result.map(::collapseSourceNewlines)
    }

    /**
     * Recurses, because only top-level text used to be collapsed.
     *
     * Markdown wraps a long line and the renderer keeps that newline inside
     * whatever tag it fell in, so `**one⏎two**` reached the screen as two
     * lines where the browser shows one. Styled runs, link text, headings and
     * table cells all went through the same trim and all had the same bug.
     */
    private fun collapseSourceNewlines(inline: PostInline): PostInline = when (inline) {
        is PostInline.Text -> PostInline.Text(inline.value.replace('\n', ' '))
        is PostInline.Styled -> PostInline.Styled(inline.value.replace('\n', ' '), inline.style)
        is PostInline.Link -> PostInline.Link(inline.href, inline.children.map(::collapseSourceNewlines))
        else -> inline
    }

    private fun trimEdgeNewlines(code: String): String = code.trim('\n', '\r')

    /** Drops empty paragraphs and merges adjacent dividers. */
    private fun normalize(blocks: List<PostBlock>): List<PostBlock> {
        val result = mutableListOf<PostBlock>()
        for (block in blocks) {
            if (block is PostBlock.Paragraph && block.inlines.isEffectivelyEmpty) continue
            if (block is PostBlock.Divider && result.lastOrNull() is PostBlock.Divider) continue
            result += block
        }
        return result
    }

    // -------------------------------------------------------------- entities

    /** Keyed without the `&` and `;`, so the scanner can look one body up. */
    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "hellip" to "…", // A plain space, not U+00A0: `<p>&nbsp;</p>` is spacing markup,
        // and as a non-breaking space it stops counting as blank and starts
        // rendering as an empty paragraph.
        "nbsp" to " ", "mdash" to "—",
        "ndash" to "–", "ldquo" to "\u201C", "rdquo" to "\u201D",
        "lsquo" to "\u2018", "rsquo" to "\u2019", "middot" to "·",
        "times" to "×", "copy" to "©", "reg" to "®", "trade" to "™",
    )

    /**
     * The longest name above, plus room for `#x10FFFF`. Anything longer than
     * this between `&` and `;` is prose that happens to contain both.
     */
    private const val MAX_ENTITY_BODY = 10

    /**
     * Named plus numeric (decimal and hex) entities, in one left-to-right pass.
     *
     * A pass per entity cannot work: replacing `&amp;` first turns `&amp;lt;`
     * into `&lt;`, which the next pass then turns into `<`. A post showing
     * someone how to escape a tag had its escaping silently undone.
     */
    fun decodeEntities(value: String): String {
        if (!value.contains('&')) return value

        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val amp = value.indexOf('&', index)
            if (amp < 0) {
                result.append(value, index, value.length)
                break
            }
            result.append(value, index, amp)

            // Bounded: an unbalanced `&` used to scan to the end of the
            // string, so a post full of them cost O(n^2) on the main thread.
            val limit = minOf(value.length, amp + MAX_ENTITY_BODY + 2)
            var semicolon = -1
            for (index in amp + 1 until limit) {
                if (value[index] == ';') {
                    semicolon = index
                    break
                }
            }
            val decoded = if (semicolon < 0) null else decodeEntityBody(value.substring(amp + 1, semicolon))

            if (decoded == null) {
                // Not an entity: emit the ampersand and carry on from after it,
                // never from after the semicolon, or the text between them is
                // swallowed.
                result.append('&')
                index = amp + 1
            } else {
                result.append(decoded)
                index = semicolon + 1
            }
        }
        return result.toString()
    }

    /** The text between `&` and `;`, or null when it is not an entity at all. */
    private fun decodeEntityBody(body: String): String? {
        if (body.isEmpty()) return null
        if (!body.startsWith("#")) return NAMED_ENTITIES[body]

        val digits = body.drop(1)
        val isHex = digits.startsWith("x") || digits.startsWith("X")
        val code = (if (isHex) digits.drop(1) else digits).toIntOrNull(if (isHex) 16 else 10) ?: return null
        if (code !in 1..0x10FFFF) return null
        return runCatching { String(Character.toChars(code)) }.getOrNull()
    }
}

/**
 * Inserts zero-width spaces into runs with no natural break, so a long URL or
 * hash wraps instead of forcing its container wider. CJK wraps between any two
 * characters, so it never forms an unbreakable run and needs no help.
 */
fun String.breakingLongTokens(maxRun: Int = 18): String {
    if (!containsUnbreakableRun(maxRun)) return this
    val result = StringBuilder(length + length / maxRun)
    var runLength = 0
    for (character in this) {
        when {
            character.isWhitespace() || isNaturallyBreakable(character) -> runLength = 0
            character in BREAK_OPPORTUNITIES -> {
                // Break *after* URL punctuation, which reads naturally.
                runLength = 0
                result.append(character)
                result.append('​')
                continue
            }
            else -> {
                runLength++
                if (runLength > maxRun) {
                    result.append('​')
                    runLength = 1
                }
            }
        }
        result.append(character)
    }
    return result.toString()
}

private fun String.containsUnbreakableRun(maxRun: Int): Boolean {
    var runLength = 0
    for (character in this) {
        if (character.isWhitespace() || character == '​' ||
            isNaturallyBreakable(character) || character in BREAK_OPPORTUNITIES
        ) {
            runLength = 0
        } else {
            runLength++
            if (runLength > maxRun) return true
        }
    }
    return false
}

private fun isNaturallyBreakable(character: Char): Boolean = when (character.code) {
    in 0x2E80..0x9FFF,   // CJK radicals through unified ideographs
    in 0xAC00..0xD7AF,   // Hangul syllables
    in 0xF900..0xFAFF,   // CJK compatibility ideographs
    in 0xFF00..0xFFEF,   // Full-width forms and punctuation
    -> true
    else -> false
}

private val BREAK_OPPORTUNITIES = charArrayOf('/', '-', '_', '.', '?', '&', '=', ':', ',', '+')

/** Strips tags and decodes entities for list-row excerpts only — post bodies
 *  go through the parser instead. */
/**
 * @param collapse true for a one-line excerpt — a list row, a subtitle. False
 * where the text is shown as a body and its paragraph breaks are content: a
 * profile bio, or a chat message with no raw markdown to fall back on.
 */
fun plainTextFromHtml(html: String?, collapse: Boolean = true): String {
    if (html.isNullOrEmpty()) return ""
    val builder = StringBuilder(html.length)
    val tagName = StringBuilder(12)
    var inTag = false
    var nameComplete = false

    for (character in html) {
        when {
            character == '<' -> {
                inTag = true
                nameComplete = false
                tagName.setLength(0)
            }
            character == '>' -> {
                inTag = false
                // A tag boundary is a word boundary. Stripping them blindly ran
                // `<p>a</p><p>b</p>` together as "ab", which is how bios and
                // node descriptions lost the spaces between their sentences.
                if (tagName.toString().trim('/').lowercase() in TEXT_BREAKING_TAGS) {
                    builder.append(if (collapse) ' ' else '\n')
                }
            }
            inTag -> when {
                nameComplete -> Unit
                // The name ends at the first space; `<div class="x">` would
                // otherwise accumulate as "divclassx" and match nothing. The
                // trailing slash of `<br/>` is trimmed at the boundary above.
                character.isLetterOrDigit() || character == '/' -> tagName.append(character)
                else -> nameComplete = true
            }
            else -> builder.append(character)
        }
    }

    val decoded = PostHtmlParser.decodeEntities(builder.toString())
    val normalised = if (collapse) {
        decoded.replace(Regex("\\s+"), " ")
    } else {
        // Runs of blank lines collapse to one; a paragraph break survives.
        decoded.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{2,}"), "\n").replace(Regex(" ?\\n ?"), "\n")
    }
    return normalised.trim().breakingLongTokens()
}

private val TEXT_BREAKING_TAGS = setOf(
    "p", "div", "br", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6",
    "blockquote", "pre", "section", "article", "aside", "td", "th",
)
