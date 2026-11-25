package com.nodeloc.app.feature.search

import com.nodeloc.app.core.network.ApiTopicSummary
import com.nodeloc.app.core.network.NodelocApi
import kotlinx.serialization.Serializable

/**
 * One hit of `GET /api/v1/search`, with the fields the facade's
 * Discourse-shaped `SearchResponse` drops: the highlighted title and blurb
 * (`<mark>` around the matched words, the rest HTML-escaped), the score, the
 * floor and whether it is the opening post.
 */
@Serializable
data class ApiSearchHit(
    val id: Int = 0,
    val topicId: Int = 0,
    val postNumber: Int = 1,
    val topicTitle: String = "",
    val titleHtml: String? = null,
    val excerpt: String? = null,
    val blurb: String? = null,
    val author: String? = null,
    val nodeSlug: String? = null,
    val nodeName: String? = null,
    val createdAt: String? = null,
    val upvotes: Int = 0,
    val replyCount: Int = 0,
    val featured: Boolean = false,
    val folded: Boolean = false,
    val isFirstPost: Boolean = false,
)

@Serializable
data class ApiSearchHits(
    val posts: List<ApiSearchHit> = emptyList(),
    /** The posts deduplicated by topic, in the topic-list shape (no highlighting). */
    val topics: List<ApiTopicSummary> = emptyList(),
    val hasMore: Boolean? = null,
)

class SearchApi(private val api: NodelocApi) {
    /** [type] is `posts` or `topics`; both answer with `posts`, topics only deduplicated. */
    suspend fun hits(query: String, type: String, limit: Int = 30): List<ApiSearchHit> =
        api.get<ApiSearchHits>(
            "api/v1/search",
            listOf("q" to query, "type" to type, "offset" to "0", "limit" to limit.toString()),
        ).posts

    /** Topics inside one node: the API scopes by `node`; the old `#slug` prefix would be searched as text. */
    suspend fun nodeTopics(query: String, slug: String, limit: Int = 30): List<ApiTopicSummary> =
        api.get<ApiSearchHits>(
            "api/v1/search",
            listOf("q" to query, "type" to "topics", "node" to slug, "offset" to "0", "limit" to limit.toString()),
        ).topics
}
