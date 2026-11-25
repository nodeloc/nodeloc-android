package com.nodeloc.app.feature.post

import com.nodeloc.app.core.html.PostContent
import com.nodeloc.app.core.network.ApiPost
import com.nodeloc.app.core.network.ApiRaw
import com.nodeloc.app.core.network.ApiThreadPage
import com.nodeloc.app.core.network.ApiTopicList
import com.nodeloc.app.core.network.ApiTopicSummary
import com.nodeloc.app.core.network.NodelocApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

@Serializable
data class ApiTopicLogActor(val username: String = "", val avatar: String? = null)

@Serializable
data class ApiTopicLogEntry(
    val id: Long = 0,
    val action: String = "",
    val postNumber: Int? = null,
    val actor: ApiTopicLogActor? = null,
    val system: Boolean = false,
    val details: JsonElement? = null,
    val createdAt: String? = null,
)

@Serializable
data class ApiTopicLogPage(val entries: List<ApiTopicLogEntry> = emptyList(), val nextBefore: Long? = null)

/** How a reply came to be deleted, as far as the topic's activity log says. */
data class ReplyDeletion(val reason: String?, val actor: String?)

/**
 * What the activity log knows that the topic JSON does not.
 *
 * `GET /api/v1/topics/{id}` carries neither the accepted answer nor why a reply
 * was deleted; the web page renders both server-side. The log is public and
 * records `solved`/`unsolved` (with the floor) and `post_deleted` (with
 * `details.reason`), so it is the only place a client can read them from.
 */
data class TopicLogFacts(
    val acceptedPostNumber: Int? = null,
    val acceptedBy: String? = null,
    val deletions: Map<Int, ReplyDeletion> = emptyMap(),
)

/** The accepted reply, drawn as a card above the thread. */
data class BestAnswer(
    val postId: Int,
    val postNumber: Int,
    val author: String,
    val avatarUrl: String?,
    val time: String,
    val content: PostContent,
    /** Net votes, when the site votes; the card says "N 赞同" only then. */
    val votes: Int? = null,
)

enum class RelatedSource { Node, Hot }

data class RelatedTopic(
    val id: Int,
    val title: String,
    val nodeSlug: String,
    val nodeName: String,
    val logoUrl: String?,
    val author: String,
    val time: String,
    val votes: Int,
    val replies: Int,
    /** The reader has opened it before (`last_read_post_number` is set). */
    val read: Boolean = false,
    /** The card picture the lists show, when the topic has one. */
    val imageUrl: String? = null,
)

/**
 * The reader's calls that the compatibility facade never had: the activity log,
 * accepting an answer, one floor of the thread, a post's source, and the lists
 * the related-topics section draws from.
 */
class ReaderExtrasApi(private val api: NodelocApi) {

    /**
     * Newest first. The first `solved`/`unsolved` met decides the answer, so the
     * walk stops as soon as it has one; three pages is where a topic busy enough
     * to bury it stops being worth the requests.
     */
    suspend fun topicLogFacts(topicId: Int): TopicLogFacts {
        var accepted: Int? = null
        var acceptedBy: String? = null
        var decided = false
        val deletions = mutableMapOf<Int, ReplyDeletion>()
        var before: Long? = null
        repeat(MAX_LOG_PAGES) {
            val page = api.get<ApiTopicLogPage>(
                "api/v1/topics/$topicId/log",
                before?.let { listOf("before" to it.toString()) }.orEmpty(),
            )
            for (entry in page.entries) {
                when (entry.action) {
                    "solved", "unsolved" -> if (!decided) {
                        decided = true
                        if (entry.action == "solved") {
                            accepted = entry.postNumber
                            acceptedBy = entry.actor?.username
                        }
                    }
                    "post_deleted" -> entry.postNumber?.let { number ->
                        if (number !in deletions) {
                            val reason = ((entry.details as? JsonObject)?.get("reason") as? JsonPrimitive)
                                ?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                            deletions[number] = ReplyDeletion(reason, entry.actor?.username)
                        }
                    }
                }
            }
            before = page.nextBefore?.takeIf { it > 0 }
            if (decided || before == null) return TopicLogFacts(accepted, acceptedBy, deletions)
        }
        return TopicLogFacts(accepted, acceptedBy, deletions)
    }

    suspend fun setAccepted(postId: Int, accepted: Boolean) {
        api.sendRaw("PUT", "api/v1/posts/$postId/accepted", buildJsonObject { put("accepted", accepted) })
    }

    /** One floor on its own: `parent` answers with that post first and its replies after. */
    suspend fun floor(topicId: Int, postNumber: Int): ApiPost? =
        api.get<ApiThreadPage>(
            "api/v1/topics/$topicId/thread",
            listOf("parent" to postNumber.toString(), "levels" to "2", "limit" to "1"),
        ).posts.firstOrNull { it.postNumber == postNumber }

    /** Markdown source; the server hands it to the author and moderators, deleted or not. */
    suspend fun raw(postId: Int): String = api.get<ApiRaw>("api/v1/posts/$postId/raw").raw

    suspend fun nodeTopics(slug: String, limit: Int): List<ApiTopicSummary> =
        api.get<ApiTopicList>("api/v1/topics", listOf("node" to slug, "limit" to limit.toString())).topics

    suspend fun hotTopics(limit: Int): List<ApiTopicSummary> =
        api.get<ApiTopicList>("api/v1/topics", listOf("sort" to "hot", "limit" to limit.toString())).topics

    private companion object {
        const val MAX_LOG_PAGES = 3
    }
}
