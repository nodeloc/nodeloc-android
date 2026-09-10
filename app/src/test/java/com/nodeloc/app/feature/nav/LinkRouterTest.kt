package com.nodeloc.app.feature.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Routing is where a link either lands on a native screen or silently degrades
 * into a WebView of the same content, so every in-site shape is pinned here.
 */
class LinkRouterTest {

    @Test
    fun `topic links carry their post number`() {
        assertEquals(
            LinkDestination.Topic(104933, 12),
            LinkRouter.resolve("https://www.nodeloc.com/t/some-slug/104933/12"),
        )
        assertEquals(
            LinkDestination.Topic(104933, null),
            LinkRouter.resolve("/t/some-slug/104933"),
        )
    }

    @Test
    fun `the nested reader path is a topic, not a node`() {
        assertEquals(
            LinkDestination.Topic(104933, null),
            LinkRouter.resolve("https://www.nodeloc.com/n/some-slug/104933"),
        )
    }

    @Test
    fun `category links resolve to a node with its id`() {
        assertEquals(
            LinkDestination.Node("ai", 42),
            LinkRouter.resolve("https://www.nodeloc.com/c/technology/ai/42"),
        )
    }

    @Test
    fun `user links resolve to a profile, group inboxes to the inbox`() {
        assertEquals(LinkDestination.User("ada"), LinkRouter.resolve("/u/ada"))
        assertEquals(LinkDestination.GroupInbox, LinkRouter.resolve("/u/ada/messages/group/staff"))
    }

    @Test
    fun `chat links carry channel and message`() {
        assertEquals(
            LinkDestination.Chat(7, 99),
            LinkRouter.resolve("https://www.nodeloc.com/chat/c/-/7/99"),
        )
    }

    @Test
    fun `query strings and fragments do not break path matching`() {
        assertEquals(
            LinkDestination.Topic(1, null),
            LinkRouter.resolve("https://www.nodeloc.com/t/x/1?u=ada#reply"),
        )
    }

    @Test
    fun `off-site links open the in-app browser`() {
        val destination = LinkRouter.resolve("https://example.com/page")
        assertEquals(LinkDestination.InAppBrowser("https://example.com/page"), destination)
    }

    @Test
    fun `unknown in-site paths still open in-app rather than externally`() {
        assertEquals(
            LinkDestination.InAppBrowser("https://www.nodeloc.com/badges/3/nice"),
            LinkRouter.resolve("https://www.nodeloc.com/badges/3/nice"),
        )
    }

    @Test
    fun `mailto and tel hand off to the system`() {
        assertEquals(LinkDestination.External("mailto:a@b.c"), LinkRouter.resolve("mailto:a@b.c"))
        assertEquals(LinkDestination.External("tel:+123"), LinkRouter.resolve("tel:+123"))
    }

    @Test
    fun `blank input routes nowhere`() {
        assertNull(LinkRouter.resolve("   "))
    }

    @Test
    fun `protocol-relative and root-relative urls are absolutised`() {
        assertEquals(
            LinkDestination.InAppBrowser("https://cdn.example.com/a.png"),
            LinkRouter.resolve("//cdn.example.com/a.png"),
        )
        assertEquals(LinkDestination.User("ada"), LinkRouter.resolve("/u/ada"))
    }

    // ------------------------------------------------------------- schemes

    @Test
    fun `injection schemes are blocked, whatever their casing or padding`() {
        listOf(
            "javascript:alert(1)",
            "JavaScript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "file:///data/data/com.nodeloc.app/shared_prefs/nodeloc.secure.xml",
            "content://com.android.providers/documents",
            "about:blank",
            "blob:https://www.nodeloc.com/abc",
            // A tab inside the scheme is a real bypass, not a hypothetical.
            "java\tscript:alert(1)",
        ).forEach { url ->
            assertEquals("blocked: $url", LinkDestination.Blocked, LinkRouter.resolve(url))
        }
    }

    @Test
    fun `app schemes hand off to the system rather than a WebView`() {
        listOf(
            "market://details?id=com.nodeloc.app",
            "weixin://dl/business",
            "alipays://platformapi/startapp",
            "bilibili://video/12345",
        ).forEach { url ->
            assertEquals("external: $url", LinkDestination.External(url), LinkRouter.resolve(url))
        }
    }

    @Test
    fun `a colon inside a path or query is not a scheme`() {
        assertEquals(
            LinkDestination.InAppBrowser("https://www.nodeloc.com/search?q=a:b"),
            LinkRouter.resolve("/search?q=a:b"),
        )
    }

    /**
     * An http URL that merely *contains* a blocked word is a 404 on the site,
     * not a vector. Pinned so nobody "hardens" this in the wrong direction.
     */
    @Test
    fun `an http url is never blocked for what its path spells`() {
        assertEquals(
            LinkDestination.InAppBrowser("https://www.nodeloc.com/javascript:alert(1)"),
            LinkRouter.resolve("https://www.nodeloc.com/javascript:alert(1)"),
        )
    }
}
