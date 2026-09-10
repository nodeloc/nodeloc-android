package com.nodeloc.app.core.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser is the highest-risk component in the app: it renders untrusted
 * HTML, and on iOS a single pathological post was enough to take the process
 * down. These tests pin the structures real posts use, plus the fuses.
 */
class PostHtmlParserTest {

    @Test
    fun `paragraphs and inline styling survive`() {
        val content = PostHtmlParser.parseSync("<p>hello <strong>bold</strong> and <em>italic</em></p>")
        val paragraph = content.blocks.single() as PostBlock.Paragraph
        assertEquals("hello bold and italic", paragraph.inlines.plainText)
        assertTrue(
            paragraph.inlines.any { it is PostInline.Styled && PostTextStyle.Bold in it.style },
        )
    }

    @Test
    fun `nested styles combine rather than replace`() {
        val content = PostHtmlParser.parseSync("<p><strong><em>both</em></strong></p>")
        val styled = (content.blocks.single() as PostBlock.Paragraph).inlines
            .filterIsInstance<PostInline.Styled>()
            .single()
        assertTrue(PostTextStyle.Bold in styled.style)
        assertTrue(PostTextStyle.Italic in styled.style)
    }

    @Test
    fun `mentions become mention runs, not links`() {
        val content = PostHtmlParser.parseSync("""<p><a class="mention" href="/u/ada">@ada</a> hi</p>""")
        val inlines = (content.blocks.single() as PostBlock.Paragraph).inlines
        assertEquals("ada", inlines.filterIsInstance<PostInline.Mention>().single().username)
    }

    @Test
    fun `lightbox anchors yield the image and drop their chrome`() {
        val html = """
            <div class="lightbox-wrapper">
              <a class="lightbox" href="/uploads/full.png">
                <img src="/uploads/thumb.png" width="800" height="600" alt="shot">
                <div class="meta"><span class="filename">full.png</span><span class="informations">800x600</span></div>
              </a>
            </div>
        """.trimIndent()
        val image = (PostHtmlParser.parseSync(html).blocks.single() as PostBlock.Image).image
        assertEquals("/uploads/thumb.png", image.src)
        assertEquals("/uploads/full.png", image.href)
        assertEquals(800, image.width)
        // The filename/dimension chrome must not leak in as text.
        assertTrue(PostHtmlParser.parseSync(html).plainText.contains("full.png").not())
    }

    @Test
    fun `video placeholders carry their data attributes`() {
        val html = """<div class="video-placeholder-container" data-video-src="/uploads/a.mp4"
            data-thumbnail-src="/uploads/a.png"></div>"""
        val video = (PostHtmlParser.parseSync(html).blocks.single() as PostBlock.Video).video
        assertEquals("/uploads/a.mp4", video.src)
        assertEquals("/uploads/a.png", video.posterSrc)
    }

    @Test
    fun `sha1 is taken from the basename at any directory depth`() {
        val sha = "0123456789abcdef0123456789abcdef01234567"
        val video = PostVideo(src = "https://x/uploads/original/3X/0/d/$sha.mp4")
        assertEquals(sha, video.sha1)
        assertEquals(null, PostVideo(src = "https://x/uploads/short.mp4").sha1)
    }

    @Test
    fun `quotes keep their author and inner blocks`() {
        val html = """
            <aside class="quote" data-username="ada">
              <div class="title"><img src="/avatar.png" class="avatar">ada:</div>
              <blockquote><p>quoted line</p></blockquote>
            </aside>
        """.trimIndent()
        val quote = (PostHtmlParser.parseSync(html).blocks.single() as PostBlock.Quote).quote
        assertEquals("ada", quote.username)
        assertEquals("quoted line", quote.blocks.joinToString("") { it.plainText })
    }

    @Test
    fun `poll markup becomes a placeholder rather than raw text`() {
        val html = """<div class="poll" data-poll-name="poll"><div class="poll-container">A<br>B</div></div>"""
        val block = PostHtmlParser.parseSync(html).blocks.single()
        assertEquals("poll", (block as PostBlock.PollPlaceholder).name)
        assertEquals("", block.plainText)
    }

    @Test
    fun `svg sprites are dropped whole`() {
        val html = """<p>text<svg class="fa"><use href="#icon"></use></svg></p>"""
        assertEquals("text", PostHtmlParser.parseSync(html).plainText)
    }

    @Test
    fun `code blocks keep their language and stay verbatim`() {
        val html = """<pre><code class="lang-kotlin">val a = 1
val b = 2</code></pre>"""
        val block = PostHtmlParser.parseSync(html).blocks.single() as PostBlock.CodeBlock
        assertEquals("kotlin", block.language)
        assertEquals("val a = 1\nval b = 2", block.code)
    }

    @Test
    fun `tables split headers from rows`() {
        val html = """
            <table><thead><tr><th>a</th><th>b</th></tr></thead>
            <tbody><tr><td>1</td><td>2</td></tr></tbody></table>
        """.trimIndent()
        val table = PostHtmlParser.parseSync(html).blocks.single() as PostBlock.Table
        assertEquals(2, table.headers.size)
        assertEquals(1, table.rows.size)
        assertEquals("1", table.rows[0][0].plainText)
    }

    @Test
    fun `entities decode, including numeric and hex forms`() {
        val content = PostHtmlParser.parseSync("<p>&amp; &#65; &#x42; &hellip;</p>")
        assertEquals("& A B …", content.plainText)
    }

    @Test
    fun `unterminated tag does not duplicate the trailing text`() {
        val content = PostHtmlParser.parseSync("<p>visible</p><div class=\"broken")
        assertEquals("visible", content.plainText)
    }

    @Test
    fun `stray closing tags do not abandon the rest of the document`() {
        val content = PostHtmlParser.parseSync("<p>one</p></div><p>two</p>")
        assertEquals(2, content.blocks.size)
    }

    @Test
    fun `pathological nesting degrades to text instead of overflowing the stack`() {
        // 400 levels — an order of magnitude past anything real, and exactly the
        // shape that crashed the iOS build before the depth fuse existed.
        val depth = 400
        val html = buildString {
            repeat(depth) { append("<div>") }
            append("deep")
            repeat(depth) { append("</div>") }
        }
        val content = PostHtmlParser.parseSync(html)
        assertTrue(content.plainText.contains("deep"))
    }

    @Test
    fun `long unbreakable runs get zero-width break opportunities`() {
        val url = "https://example.com/" + "a".repeat(60)
        val broken = url.breakingLongTokens()
        assertTrue(broken.contains('​'))
        // Re-running must not compound the marks.
        assertEquals(broken, broken.breakingLongTokens())
    }

    @Test
    fun `cjk text needs no break marks`() {
        val text = "这是一段很长的中文文本没有任何空格但是可以在任意位置换行所以不需要插入零宽空格"
        assertEquals(text, text.breakingLongTokens())
    }

    @Test
    fun `images and videos are collected in reading order`() {
        val html = """
            <p>a</p><img src="1.png"><blockquote><img src="2.png"></blockquote>
            <div class="video-placeholder-container" data-video-src="v.mp4"></div>
        """.trimIndent()
        val content = PostHtmlParser.parseSync(html)
        assertEquals(listOf("1.png", "2.png"), content.images.map { it.src })
        assertEquals(1, content.videos.size)
    }

    @Test
    fun `onebox prefers the heading over the source link text`() {
        val html = """
            <aside class="onebox">
              <header class="source"><a href="https://example.com">example.com</a></header>
              <article class="onebox-body"><h3>Real title</h3><p>Description here</p></article>
            </aside>
        """.trimIndent()
        val onebox = (PostHtmlParser.parseSync(html).blocks.single() as PostBlock.Onebox).onebox
        assertEquals("Real title", onebox.title)
        assertNotNull(onebox.url)
        assertTrue(onebox.descriptionText.orEmpty().contains("Description"))
    }

    @Test
    fun `plain text extraction strips tags and trims`() {
        assertEquals("hello world", plainTextFromHtml("  <p>hello <b>world</b></p> "))
    }

    // ------------------------------------------------------------- entities

    /**
     * A pass per entity cannot work: replacing `&amp;` first turns `&amp;lt;`
     * into `&lt;`, which the next pass turns into `<`. A post showing someone
     * how to escape a tag had its escaping silently undone.
     */
    @Test
    fun `an escaped entity is decoded once, not twice`() {
        val content = PostHtmlParser.parseSync("<p>write &amp;lt; for a tag</p>")
        assertEquals("write &lt; for a tag", content.blocks.single().plainTextOfParagraph())
    }

    @Test
    fun `named, decimal and hex entities all decode`() {
        val content = PostHtmlParser.parseSync("<p>&lt;a&gt; &#65; &#x42; caf&eacute;</p>")
        // eacute is not in the table, so it stays literal rather than vanishing.
        assertEquals("<a> A B caf&eacute;", content.blocks.single().plainTextOfParagraph())
    }

    @Test
    fun `a bare ampersand is not eaten`() {
        val content = PostHtmlParser.parseSync("<p>Tom &amp; Jerry; also A &amp; B</p>")
        assertEquals("Tom & Jerry; also A & B", content.blocks.single().plainTextOfParagraph())
    }

    // -------------------------------------------------------- line breaking

    /**
     * Markdown wraps a long line and the renderer keeps that newline inside
     * whatever tag it fell in. Only top-level text used to be collapsed, so
     * `**one⏎two**` reached the screen as two lines where a browser shows one.
     */
    @Test
    fun `a source newline inside bold is not a line break`() {
        val content = PostHtmlParser.parseSync("<p><strong>one\ntwo</strong></p>")
        assertEquals("one two", content.blocks.single().plainTextOfParagraph())
    }

    @Test
    fun `a source newline inside a link is not a line break`() {
        // A regular string, not a raw one: the newline has to be real.
        val content = PostHtmlParser.parseSync("<p><a href=\"/t/1\">one\ntwo</a></p>")
        assertEquals("one two", content.blocks.single().plainTextOfParagraph())
    }

    // -------------------------------------------------------------- embeds

    /**
     * The element is empty and everything lives in its attributes, so there was
     * no text to fall through to — a post whose whole body was a video link
     * rendered as nothing at all.
     */
    @Test
    fun `a lazyYT container becomes a onebox rather than nothing`() {
        val content = PostHtmlParser.parseSync(
            """<div class="onebox lazyYT lazyYT-container" data-youtube-id="abc123"></div>""",
        )
        val onebox = (content.blocks.single() as PostBlock.Onebox).onebox
        assertEquals("https://www.youtube.com/watch?v=abc123", onebox.url)
    }

    /**
     * skipSubtree counts depth from the opening tag, and a self-closing one
     * never opens — so it ran to the end of the post and ate everything after.
     */
    @Test
    fun `a self-closing iframe does not swallow the rest of the post`() {
        val content = PostHtmlParser.parseSync(
            """<p>before</p><iframe src="https://player.example/x"/><p>after</p>""",
        )
        assertEquals(3, content.blocks.size)
        assertEquals("after", content.blocks.last().plainTextOfParagraph())
    }

    @Test
    fun `a bare iframe becomes a onebox for its source`() {
        val content = PostHtmlParser.parseSync("""<iframe src="https://player.bilibili.com/x"></iframe>""")
        val onebox = (content.blocks.single() as PostBlock.Onebox).onebox
        assertEquals("https://player.bilibili.com/x", onebox.url)
    }

    // ------------------------------------------------------------ spoilers

    /** Styled as code, a spoiler is a spoiler nobody was protected from. */
    @Test
    fun `an inline spoiler carries its own style, not code`() {
        val content = PostHtmlParser.parseSync("""<p><span class="spoiler">the butler</span></p>""")
        val styled = (content.blocks.single() as PostBlock.Paragraph).inlines
            .filterIsInstance<PostInline.Styled>()
            .single()
        assertTrue(PostTextStyle.Spoiler in styled.style)
    }

    // -------------------------------------------------------------- images

    @Test
    fun `the largest srcset candidate wins over the 1x src`() {
        val content = PostHtmlParser.parseSync(
            """<img src="/small.png" srcset="/small.png 1x, /large.png 2x" width="800" height="600">""",
        )
        val image = (content.blocks.single() as PostBlock.Image).image
        assertEquals("/large.png", image.bestSrc)
        assertEquals(800, image.width)
    }

    @Test
    fun `an image with no srcset falls back to its src`() {
        val content = PostHtmlParser.parseSync("""<img src="/only.png">""")
        assertEquals("/only.png", (content.blocks.single() as PostBlock.Image).image.bestSrc)
    }

    // --------------------------------------------------------------- links

    /** `lang-auto` is what Discourse writes when the author named no language. */
    @Test
    fun `a generic language label is dropped`() {
        val content = PostHtmlParser.parseSync("""<pre><code class="lang-auto">x = 1</code></pre>""")
        assertEquals(null, (content.blocks.single() as PostBlock.CodeBlock).language)
    }

    @Test
    fun `a real language label is kept`() {
        val content = PostHtmlParser.parseSync("""<pre><code class="lang-kotlin">val x = 1</code></pre>""")
        assertEquals("kotlin", (content.blocks.single() as PostBlock.CodeBlock).language)
    }

    /** The name lives in a child span and the `#` in an icon. */
    @Test
    fun `a hashtag keeps its hash`() {
        val content = PostHtmlParser.parseSync(
            """<p><a class="hashtag-cooked" href="/c/vps"><span class="hashtag-icon-placeholder"></span><span>vps</span></a></p>""",
        )
        assertEquals("#vps", content.blocks.single().plainTextOfParagraph())
    }

    /** Resolved against the site root, a footnote marker linked to the home page. */
    @Test
    fun `a fragment anchor is text, not a link`() {
        val content = PostHtmlParser.parseSync("""<p>see<a href="#fn1">1</a></p>""")
        val inlines = (content.blocks.single() as PostBlock.Paragraph).inlines
        assertTrue(inlines.none { it is PostInline.Link })
        assertEquals("see1", inlines.plainText)
    }

    // ------------------------------------------------------- plain text

    /** Stripping tags blindly ran `<p>a</p><p>b</p>` together as "ab". */
    @Test
    fun `block boundaries become spaces in extracted text`() {
        assertEquals("first second", plainTextFromHtml("<p>first</p><p>second</p>"))
        assertEquals("one two", plainTextFromHtml("one<br>two"))
        assertEquals("a b", plainTextFromHtml("<ul><li>a</li><li>b</li></ul>"))
    }

    @Test
    fun `inline tags do not add spaces`() {
        assertEquals("bolded", plainTextFromHtml("<strong>bold</strong>ed"))
    }

    /** The name ends at the first space, or `<div class="x">` matches nothing. */
    @Test
    fun `attributes do not hide the tag name`() {
        assertEquals("a b", plainTextFromHtml("""<div class="x">a</div><div>b</div>"""))
        assertEquals("one two", plainTextFromHtml("""<p id="a">one</p><p>two</p>"""))
    }

    @Test
    fun `a self-closing break still breaks`() {
        assertEquals("one two", plainTextFromHtml("one<br/>two"))
        assertEquals("one two", plainTextFromHtml("one<br />two"))
    }

    @Test
    fun `a body keeps its paragraph breaks when asked to`() {
        assertEquals("a\nb", plainTextFromHtml("<p>a</p><p>b</p>", collapse = false))
        assertEquals("a b", plainTextFromHtml("<p>a</p><p>b</p>"))
    }

    /** `<p>&nbsp;</p>` is spacing markup and must not survive as a paragraph. */
    @Test
    fun `a spacer paragraph is still dropped`() {
        assertTrue(PostHtmlParser.parseSync("<p>real</p><p>&nbsp;</p>").blocks.size == 1)
    }
}

private fun PostBlock.plainTextOfParagraph(): String = (this as PostBlock.Paragraph).inlines.plainText
