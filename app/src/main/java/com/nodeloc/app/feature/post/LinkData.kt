package com.nodeloc.app.feature.post

import com.nodeloc.app.core.html.LinkCard
import com.nodeloc.app.core.html.PostBlock
import com.nodeloc.app.core.html.PostContent
import com.nodeloc.app.core.html.PostInline
import com.nodeloc.app.core.html.RepoMeta
import com.nodeloc.app.core.network.NodelocApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Serializable
data class ApiLinkPreview(
    val url: String = "",
    /** ready, pending, failed or none; only `ready` has anything to draw. */
    val status: String = "",
    val provider: String? = null,
    val title: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val siteName: String? = null,
    val iconUrl: String? = null,
    val data: JsonElement? = null,
)

@Serializable
data class ApiLinkPreviews(val previews: List<ApiLinkPreview> = emptyList())

@Serializable
data class ApiLinkStats(
    /** Post id (as a JSON key, so a string) → URL as written in the post → clicks. */
    val clicks: Map<String, Map<String, Long>> = emptyMap(),
)

@Serializable
data class ApiLinkClick(val clicks: Long? = null, val counted: Boolean = false)

/**
 * The two things the web page draws around a post's links that its HTML does
 * not carry: the card behind a bare pasted URL (the server marks it `onebox`
 * and fetches the preview separately) and how often each link was opened.
 */
class LinkDataApi(private val api: NodelocApi) {

    suspend fun previews(urls: List<String>): Map<String, LinkCard> =
        api.get<ApiLinkPreviews>("api/v1/link-previews", urls.map { "url" to it })
            .previews
            .filter { it.status == "ready" && it.url.isNotEmpty() }
            .associate { it.url to it.toCard() }

    suspend fun stats(topicId: Int, postIds: List<Int>): Map<Int, Map<String, Long>> =
        api.get<ApiLinkStats>("api/v1/topics/$topicId/link-stats", postIds.map { "post" to it.toString() })
            .clicks
            .mapNotNull { (id, links) -> id.toIntOrNull()?.let { it to links } }
            .toMap()

    suspend fun click(postId: Int, url: String): ApiLinkClick =
        api.send(
            "POST",
            "api/v1/link-clicks",
            buildJsonObject {
                put("post_id", postId)
                put("url", url)
            },
        )

    companion object {
        /** The server's own caps: 20 URLs per preview lookup, 60 posts per stats call. */
        const val MAX_PREVIEWS = 20
        const val MAX_STATS_POSTS = 60
    }
}

private fun ApiLinkPreview.toCard(): LinkCard {
    val data = data as? JsonObject
    fun text(key: String) = (data?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val repo = if (provider == "github" && text("kind") == "repo") {
        RepoMeta(
            language = text("language"),
            stars = (data?.get("stars") as? JsonPrimitive)?.longOrNull,
            license = text("license"),
        )
    } else {
        null
    }
    return LinkCard(
        title = title?.takeIf { it.isNotBlank() },
        description = description?.takeIf { it.isNotBlank() },
        siteName = siteName?.takeIf { it.isNotBlank() },
        iconUrl = iconUrl?.takeIf { it.isNotBlank() },
        imageUrl = imageUrl?.takeIf { it.isNotBlank() },
        repo = repo,
    )
}

/** Every onebox URL in a body, in reading order, nested ones included. */
internal fun PostContent.oneboxUrls(): List<String> = buildList {
    fun walk(blocks: List<PostBlock>) {
        blocks.forEach { block ->
            when (block) {
                is PostBlock.Onebox -> block.onebox.url?.takeIf { it.startsWith("http") }?.let(::add)
                is PostBlock.Quote -> walk(block.quote.blocks)
                is PostBlock.BlockQuote -> walk(block.blocks)
                is PostBlock.ListBlock -> block.items.forEach(::walk)
                is PostBlock.Details -> walk(block.blocks)
                is PostBlock.Spoiler -> walk(block.blocks)
                else -> Unit
            }
        }
    }
    walk(blocks)
}

/**
 * Whether a body links anywhere off-page. Only those are worth a stats call:
 * most replies carry no link at all, and asking for each page of them anyway is
 * the kind of burst that got the app rate-limited before.
 */
internal fun PostContent.hasOutboundLinks(): Boolean {
    fun inlines(items: List<PostInline>): Boolean = items.any { inline ->
        when (inline) {
            is PostInline.Link -> inline.href.startsWith("http") || inlines(inline.children)
            else -> false
        }
    }
    fun blocks(items: List<PostBlock>): Boolean = items.any { block ->
        when (block) {
            is PostBlock.Paragraph -> inlines(block.inlines)
            is PostBlock.Heading -> inlines(block.inlines)
            is PostBlock.Onebox -> true
            is PostBlock.Quote -> blocks(block.quote.blocks)
            is PostBlock.BlockQuote -> blocks(block.blocks)
            is PostBlock.ListBlock -> block.items.any(::blocks)
            is PostBlock.Table -> (block.headers + block.rows.flatten()).any(::inlines)
            is PostBlock.Details -> inlines(block.summary) || blocks(block.blocks)
            is PostBlock.Spoiler -> blocks(block.blocks)
            else -> false
        }
    }
    return blocks(blocks)
}
