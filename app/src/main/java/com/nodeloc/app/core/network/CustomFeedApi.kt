package com.nodeloc.app.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One of the reader's own custom feeds, as `GET /api/v1/custom-feeds` lists them. */
@Serializable
data class ApiCustomFeedSummary(
    val id: Long = 0,
    val name: String = "",
    val color: String? = null,
    val public: Boolean = false,
    val nodeCount: Int = 0,
)

@Serializable
data class ApiCustomFeedList(
    val feeds: List<ApiCustomFeedSummary> = emptyList(),
    /** How many feeds an account may have (20 on the server today). */
    val maxFeeds: Int? = null,
)

@Serializable
data class ApiCustomFeedNode(val slug: String = "", val name: String = "", val color: String? = null)

@Serializable
data class ApiCustomFeed(
    val id: Long = 0,
    val name: String = "",
    val description: String? = null,
    val public: Boolean = false,
    val color: String? = null,
    val owner: String? = null,
    val mine: Boolean = false,
    val nodes: List<ApiCustomFeedNode> = emptyList(),
)

@Serializable
private data class ApiCustomFeedEnvelope(val feed: ApiCustomFeed = ApiCustomFeed())

/**
 * 自定义信息流: a named set of nodes read as one list (backend
 * internal/customfeed). A feed's topics come through
 * [DiscourseClient.customFeedTopics], which maps them like every other list.
 */
class CustomFeedApi(private val api: NodelocApi) {

    companion object {
        /**
         * Bumped after an edit, so the feed screen under the editor picks up its
         * new name and nodes when back returns to it.
         */
        val revision = kotlinx.coroutines.flow.MutableStateFlow(0)
    }

    suspend fun mine(): ApiCustomFeedList = api.get("api/v1/custom-feeds")

    suspend fun feed(id: Long): ApiCustomFeed = api.get<ApiCustomFeedEnvelope>("api/v1/custom-feeds/$id").feed

    suspend fun create(name: String, public: Boolean): ApiCustomFeed =
        api.send<ApiCustomFeedEnvelope>("POST", "api/v1/custom-feeds", buildJsonObject {
            put("name", name)
            put("description", "")
            put("public", public)
        }).feed

    /** A full replace on the server: whatever is left out is cleared, so the description goes back as it was. */
    suspend fun update(id: Long, name: String, description: String, public: Boolean): ApiCustomFeed =
        api.send<ApiCustomFeedEnvelope>("PUT", "api/v1/custom-feeds/$id", buildJsonObject {
            put("name", name)
            put("description", description)
            put("public", public)
        }).feed

    suspend fun delete(id: Long) {
        api.sendRaw("DELETE", "api/v1/custom-feeds/$id")
    }

    suspend fun removeNode(id: Long, slug: String) {
        api.sendRaw("DELETE", "api/v1/custom-feeds/$id/nodes/${java.net.URLEncoder.encode(slug, "UTF-8")}")
    }

    /** Idempotent on the server (`ON CONFLICT DO NOTHING`), so a retry after a timeout is safe. */
    suspend fun addNode(id: Long, slug: String) {
        api.sendRaw("PUT", "api/v1/custom-feeds/$id/nodes/${java.net.URLEncoder.encode(slug, "UTF-8")}")
    }
}
