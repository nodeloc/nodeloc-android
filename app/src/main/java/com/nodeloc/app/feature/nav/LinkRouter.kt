package com.nodeloc.app.feature.nav

import com.nodeloc.app.core.network.DiscourseConfig

/**
 * The single place a URL becomes a destination.
 *
 * Every tapped link funnels through here — post bodies, oneboxes, notifications,
 * the in-app browser — so an in-site link always lands on the native screen
 * rather than sometimes opening a WebView of the same thing.
 *
 * Deliberately free of `android.net.Uri`: the routing table is the part most
 * worth testing, and this way it runs as a plain JVM test.
 */
sealed interface LinkDestination {
    data class Topic(val topicId: Int, val postNumber: Int?) : LinkDestination
    data class User(val username: String) : LinkDestination
    data class Node(val slug: String, val categoryId: Int?) : LinkDestination
    data class Chat(val channelId: Int, val messageId: Int?) : LinkDestination
    data object GroupInbox : LinkDestination
    data class InAppBrowser(val url: String) : LinkDestination
    data class External(val url: String) : LinkDestination

    /**
     * A scheme that is only ever an injection vector. Kept distinct from `null`
     * so a WebView can refuse it silently — a `javascript:` href is page
     * machinery, not something the user asked for — while a tap that reached
     * the navigator can still say so.
     */
    data object Blocked : LinkDestination
}

object LinkRouter {

    /**
     * Schemes that can only ever be an attack on the WebView that would load
     * them. Everything else unrecognised is handed to the system instead —
     * see [resolve].
     */
    private val blockedSchemes = setOf(
        "javascript", "data", "file", "content", "blob",
        "about", "jar", "resource", "filesystem",
    )

    /** RFC 3986: a scheme is letter, then letters/digits/`+`/`-`/`.`, then `:`. */
    private val schemePattern = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*):")

    /**
     * Resolves a tapped URL to a destination.
     *
     * The contract the WebViews depend on: this never returns a loadable URL
     * whose scheme is not http or https. Anything else is either
     * [LinkDestination.External] — the system knows who owns `market://` or
     * `weixin://`, and a failed `startActivity` is harmless — or
     * [LinkDestination.Blocked].
     *
     * The default used to be the other way round, and it was the bug: an
     * unknown scheme fell through to [DiscourseConfig.absoluteUrl], which
     * prefixes anything unrecognised with the site root, so `javascript:alert(1)`
     * became `https://www.nodeloc.com/javascript:alert(1)`, matched the site
     * host, and was handed to a WebView.
     */
    fun resolve(rawUrl: String): LinkDestination? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null

        // Only for the scheme test: `java\tscript:` is a real bypass, but the
        // URL that gets opened must stay exactly what the page wrote.
        val scheme = schemePattern.find(trimmed.filterNot { it.isWhitespace() || it.isISOControl() })
            ?.groupValues?.get(1)
            ?.lowercase()

        if (scheme != null && scheme != "http" && scheme != "https") {
            return if (scheme in blockedSchemes) LinkDestination.Blocked
            else LinkDestination.External(trimmed)
        }

        val absolute = DiscourseConfig.absoluteUrl(trimmed) ?: return null
        val host = absolute.substringAfter("://", "").substringBefore('/').substringBefore('?').lowercase()

        val isSiteLink = host == DiscourseConfig.HOST || host == "nodeloc.com"
        if (!isSiteLink) return LinkDestination.InAppBrowser(absolute)

        val path = absolute.substringAfter("://", "").substringAfter('/', "")
            .substringBefore('?')
            .substringBefore('#')
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return LinkDestination.InAppBrowser(absolute)

        return when (segments[0]) {
            // /t/{slug}/{id}/{postNumber} — the slug is optional in practice.
            "t" -> {
                val numbers = segments.drop(1).mapNotNull { it.toIntOrNull() }
                numbers.firstOrNull()
                    ?.let { LinkDestination.Topic(it, numbers.getOrNull(1)) }
                    ?: LinkDestination.InAppBrowser(absolute)
            }

            "u" -> when {
                // /u/{name}/messages/group/{group} is the group inbox.
                segments.getOrNull(2) == "messages" && segments.getOrNull(3) == "group" ->
                    LinkDestination.GroupInbox
                segments.size >= 2 -> LinkDestination.User(segments[1])
                else -> LinkDestination.InAppBrowser(absolute)
            }

            // /n/{slug}/{id} is a *topic* in the nested reader view;
            // /c/{parent}/{child}/{id} is a node.
            "n" -> segments.drop(1).firstNotNullOfOrNull { it.toIntOrNull() }
                ?.let { LinkDestination.Topic(it, null) }
                ?: LinkDestination.InAppBrowser(absolute)

            "c" -> {
                val id = segments.drop(1).lastOrNull()?.toIntOrNull()
                val slug = segments.drop(1).lastOrNull { it.toIntOrNull() == null }
                if (slug != null) LinkDestination.Node(slug, id) else LinkDestination.InAppBrowser(absolute)
            }

            // /chat/c/-/{channelId}/{messageId}
            "chat" -> {
                val numbers = segments.mapNotNull { it.toIntOrNull() }
                numbers.firstOrNull()
                    ?.let { LinkDestination.Chat(it, numbers.getOrNull(1)) }
                    ?: LinkDestination.InAppBrowser(absolute)
            }

            else -> LinkDestination.InAppBrowser(absolute)
        }
    }
}
