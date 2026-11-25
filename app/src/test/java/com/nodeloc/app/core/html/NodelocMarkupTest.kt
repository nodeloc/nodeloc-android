package com.nodeloc.app.core.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The NodeLoc backend's own markup (`internal/markup`), from posts captured off
 * a real instance rather than written by hand: the fixtures under
 * `fixtures/html/` are the `html` field of `GET /api/v1/topics/{id}` verbatim,
 * so a change in what the server emits shows up as a failure here first.
 */
class NodelocMarkupTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("fixtures/html/$name")) { "missing fixture $name" }.readText()

    private val rich by lazy { PostHtmlParser.parseSync(fixture("nodeloc-rich-signed-in.html")) }

    private fun List<PostBlock>.flatten(): List<PostBlock> = flatMap { block ->
        listOf(block) + when (block) {
            is PostBlock.BlockQuote -> block.blocks.flatten()
            is PostBlock.Quote -> block.quote.blocks.flatten()
            is PostBlock.Details -> block.blocks.flatten()
            is PostBlock.Spoiler -> block.blocks.flatten()
            is PostBlock.ListBlock -> block.items.flatMap { it.flatten() }
            else -> emptyList()
        }
    }

    private fun List<PostInline>.flattenInlines(): List<PostInline> = flatMap { inline ->
        listOf(inline) + if (inline is PostInline.Link) inline.children.flattenInlines() else emptyList()
    }

    private val richInlines: List<PostInline> by lazy {
        rich.blocks.flatten().flatMap { block ->
            when (block) {
                is PostBlock.Paragraph -> block.inlines
                is PostBlock.Heading -> block.inlines
                else -> emptyList()
            }
        }.flattenInlines()
    }

    @Test
    fun `a user mention opens the profile`() {
        val mention = richInlines.filterIsInstance<PostInline.Mention>().single()
        assertEquals("bob", mention.username)
    }

    @Test
    fun `node and tag references are links, not people`() {
        val links = richInlines.filterIsInstance<PostInline.Link>()
        val node = links.single { it.href == "/n/chit-chat" }
        assertEquals("n/chit-chat", node.children.plainText)
        val tag = links.single { it.href == "/tag/vps" }
        assertEquals("#vps", tag.children.plainText)
        assertTrue(richInlines.filterIsInstance<PostInline.Mention>().none { it.username.startsWith("n/") })
    }

    @Test
    fun `standard emoji shortcodes arrive as characters`() {
        assertTrue(rich.plainText.contains("😄 表情和 ❤️"))
    }

    @Test
    fun `headings code tables and lists survive`() {
        val blocks = rich.blocks
        assertEquals(1, (blocks.first() as PostBlock.Heading).level)
        val code = blocks.filterIsInstance<PostBlock.CodeBlock>().single()
        assertEquals("go", code.language)
        assertEquals("func main() {}", code.code)
        val table = blocks.filterIsInstance<PostBlock.Table>().single()
        assertEquals(listOf("商家", "延迟"), table.headers.map { it.plainText })
        assertEquals(1, table.rows.size)
    }

    @Test
    fun `a callout keeps its title as the first line in bold`() {
        val callout = rich.blocks.filterIsInstance<PostBlock.BlockQuote>()
            .single { it.plainText.contains("升级前先备份") }
        val title = callout.blocks.first() as PostBlock.Paragraph
        assertEquals("升级前先备份", title.inlines.plainText)
        assertTrue(title.inlines.all { it is PostInline.Styled && PostTextStyle.Bold in it.style })
        assertEquals("这一版改了数据库结构。", callout.blocks[1].plainText)
    }

    @Test
    fun `external images keep their size, consecutive images stay in order`() {
        val images = rich.images
        assertEquals(3, images.size)
        assertEquals("https://picsum.photos/800/600", images[0].src)
        assertEquals(800, images[0].width)
        assertEquals(600, images[0].height)
        // Not an upload of this site: nothing to swap for a display copy.
        assertNull(images[0].href)
        assertEquals(listOf("a", "b"), images.drop(1).map { it.alt })
    }

    @Test
    fun `a youtube embed becomes a onebox for the watch page`() {
        val onebox = rich.blocks.filterIsInstance<PostBlock.Onebox>()
            .single { it.onebox.url?.contains("youtube") == true }
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", onebox.onebox.url)
    }

    @Test
    fun `a pasted link marked onebox becomes a card rather than inline text`() {
        val onebox = rich.blocks.filterIsInstance<PostBlock.Onebox>()
            .single { it.onebox.url == "https://example.com/some/article" }
        assertEquals("https://example.com/some/article", onebox.onebox.title)
    }

    @Test
    fun `details keep summary and body`() {
        val details = rich.blocks.filterIsInstance<PostBlock.Details>().single()
        assertEquals("点开看看", details.summary.plainText)
        assertEquals("折叠里的内容", details.blocks.single().plainText)
    }

    @Test
    fun `block and inline spoilers are both hidden`() {
        val block = rich.blocks.filterIsInstance<PostBlock.Spoiler>().single()
        assertEquals("整段剧透", block.plainText)
        val inline = richInlines.filterIsInstance<PostInline.Styled>().single { it.value == "行内剧透" }
        assertTrue(PostTextStyle.Spoiler in inline.style)
    }

    @Test
    fun `an unlocked hidden block shows its content as ordinary text`() {
        val paragraph = rich.blocks.single { it.plainText == "登录后可见的内容" }
        assertTrue(paragraph is PostBlock.Paragraph)
    }

    @Test
    fun `a locked hidden block shows the server notice set apart`() {
        val guest = PostHtmlParser.parseSync(fixture("nodeloc-rich-guest.html"))
        assertFalse(guest.plainText.contains("登录后可见的内容"))
        val notice = guest.blocks.filterIsInstance<PostBlock.BlockQuote>()
            .single { it.plainText.contains("登录后可见") }
        assertEquals("这部分内容登录后可见", notice.plainText)
    }

    @Test
    fun `a poll is a placeholder named by data-poll and its options do not leak`() {
        val poll = rich.blocks.filterIsInstance<PostBlock.PollPlaceholder>().single()
        assertEquals("poll", poll.name)
        assertFalse(rich.plainText.contains("选项一"))
    }

    @Test
    fun `an app embed points at the install's webview`() {
        val onebox = rich.blocks.filterIsInstance<PostBlock.Onebox>()
            .single { it.onebox.url?.startsWith("/apps/") == true }
        assertEquals("/apps/installs/12/webview", onebox.onebox.url)
    }

    @Test
    fun `an uploaded video is named by its hash, to be resolved before playing`() {
        val video = rich.videos.single()
        assertEquals("", video.src)
        assertEquals("0123456789abcdef0123456789abcdef01234567", video.sha1)
        assertEquals("video:0123456789abcdef0123456789abcdef01234567", PostBlock.Video(video).stableKey)
    }

    @Test
    fun `tab panes are laid out one after another under their titles`() {
        val headings = rich.blocks.filterIsInstance<PostBlock.Heading>().map { it.inlines.plainText }
        assertTrue(headings.containsAll(listOf("第一页", "第二页")))
        val first = rich.blocks.indexOfFirst { it is PostBlock.Heading && it.inlines.plainText == "第一页" }
        assertEquals("第一页内容", rich.blocks[first + 1].plainText)
    }

    @Test
    fun `task list boxes keep their state`() {
        val list = rich.blocks.filterIsInstance<PostBlock.ListBlock>().last()
        assertEquals(listOf("☑ 已完成", "☐ 未完成"), list.items.map { item -> item.joinToString("") { it.plainText } })
    }

    @Test
    fun `divider and kaomoji survive`() {
        assertTrue(rich.blocks.contains(PostBlock.Divider))
        assertTrue(rich.plainText.contains("( ´ ▽ ` )"))
    }

    @Test
    fun `an attributed quote gets the author chrome`() {
        val reply = PostHtmlParser.parseSync(fixture("nodeloc-reply-quote-image.html"))
        val quote = (reply.blocks.first() as PostBlock.Quote).quote
        assertEquals("bob", quote.username)
        assertEquals("支持！请问以前的帖子会迁移过来吗？", quote.blocks.single().plainText)
    }

    @Test
    fun `a site upload loads its display copy and keeps the original for full size`() {
        val reply = PostHtmlParser.parseSync(fixture("nodeloc-reply-quote-image.html"))
        val sha = "b62cc6df205482950a75062516fb51846ffef471"
        val image = reply.images.first()
        assertEquals("/uploads/thumb/post/b6/$sha.png", image.src)
        assertEquals("/uploads/original/b6/$sha.png", image.fullSizeUrl)
        assertEquals(640, image.width)
        // The same picture written inline in a sentence is still a picture.
        assertEquals(2, reply.images.size)
    }

    @Test
    fun `a gif upload is left at its original address`() {
        val sha = "c1" + "0".repeat(38)
        val html = """<p><img src="/uploads/original/c1/$sha.gif" alt="anim"></p>"""
        val image = PostHtmlParser.parseSync(html).images.single()
        assertEquals("/uploads/original/c1/$sha.gif", image.src)
        assertNull(image.href)
    }

    @Test
    fun `a pasted site link stays an ordinary link`() {
        val reply = PostHtmlParser.parseSync(fixture("nodeloc-reply-quote-image.html"))
        val last = reply.blocks.last() as PostBlock.Paragraph
        assertTrue(last.inlines.any { it is PostInline.Link && it.href == "http://127.0.0.1:8091/t/1" })
        assertEquals("nodeadmin", last.inlines.filterIsInstance<PostInline.Mention>().single().username)
    }

    @Test
    fun `a site emoji image is inline with its shortcode`() {
        // The shape `markup.WithEmoji` writes for a custom emoji (see its tests);
        // the seed instance ships no custom emoji to capture one from.
        val html = """<p>好 <img class="emoji em-img" src="/uploads/site-heart.png" alt=":heart:" data-em="heart" loading="lazy" draggable="false"></p>"""
        val emoji = (PostHtmlParser.parseSync(html).blocks.single() as PostBlock.Paragraph)
            .inlines.filterIsInstance<PostInline.Emoji>().single()
        assertEquals("/uploads/site-heart.png", emoji.url)
        assertEquals(":heart:", emoji.shortcode)
    }

    @Test
    fun `a group mention is a link to the group`() {
        val html = """<p><a class="mention mention-group" href="/g/staff">@staff</a></p>"""
        val inline = (PostHtmlParser.parseSync(html).blocks.single() as PostBlock.Paragraph).inlines.single()
        assertEquals(PostInline.Link("/g/staff", listOf(PostInline.Text("@staff"))), inline)
    }

    @Test
    fun `the seed instance's plain mention reply parses`() {
        val content = PostHtmlParser.parseSync(fixture("nodeloc-seed-mention.html"))
        val paragraph = content.blocks.single() as PostBlock.Paragraph
        assertEquals("bob", paragraph.inlines.filterIsInstance<PostInline.Mention>().single().username)
        assertEquals("@bob 会的，迁移方案已经在做了。", paragraph.inlines.plainText)
    }
}
