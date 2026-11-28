package com.nodeloc.app.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `GET /api/v1/bookmark-folders`. */
@Serializable
data class ApiBookmarkFolders(
    val folders: List<ApiBookmarkFolder> = emptyList(),
    val total: Int? = null,
    val unfiled: Int? = null,
)

@Serializable
data class ApiBookmarkFolder(val id: Int, val name: String = "", val count: Int? = null)

@Serializable
private data class ApiCreated(val id: Int)

/**
 * 收藏夹. The server saves a bookmark without a folder
 * (`PUT /api/v1/posts/{id}/bookmark`, no body, no id back) and files
 * bookmarks by their own id, so one made in the reader is found again in
 * `/saved` by its post before it can be moved.
 */
class BookmarkApi(private val api: NodelocApi) {

    suspend fun folders(): ApiBookmarkFolders = api.get("api/v1/bookmark-folders")

    /** Newest first. [folder] is a folder id, `none` for 未分类, or null for everything. */
    suspend fun saved(folder: String? = null, offset: Int = 0, query: String? = null, limit: Int = 30): ApiSavedList =
        api.get(
            "api/v1/saved",
            buildList {
                add("limit" to limit.toString())
                add("offset" to offset.toString())
                folder?.let { add("folder" to it) }
                query?.takeIf { it.isNotBlank() }?.let { add("q" to it) }
            },
        )

    /** 1–16 characters, at most 50 folders, no two alike; the server says which rule was broken. */
    suspend fun createFolder(name: String): Int =
        api.send<ApiCreated>("POST", "api/v1/bookmark-folders", buildJsonObject { put("name", name) }).id

    suspend fun renameFolder(id: Int, name: String) {
        api.sendRaw("PATCH", "api/v1/bookmark-folders/$id", buildJsonObject { put("name", name) })
    }

    /** Its bookmarks go back to 未分类; none are deleted. */
    suspend fun deleteFolder(id: Int) {
        api.sendRaw("DELETE", "api/v1/bookmark-folders/$id")
    }

    /** `folder_id` 0 is 未分类. */
    suspend fun move(ids: List<Int>, folderId: Int?) {
        api.sendRaw(
            "POST",
            "api/v1/bookmarks/move",
            buildJsonObject {
                put("ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
                put("folder_id", folderId ?: 0)
            },
        )
    }

    suspend fun remove(ids: List<Int>) {
        api.sendRaw(
            "POST",
            "api/v1/bookmarks/remove",
            buildJsonObject { put("ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }) },
        )
    }

    /**
     * The reader's bookmark of one post. There is no lookup by post, so the
     * newest saves are searched by the topic's title, then the newest page
     * alone — an edited title misses the search.
     */
    suspend fun find(postId: Int, topicTitle: String?): ApiSaved? {
        val title = topicTitle?.trim()?.take(40)
        if (!title.isNullOrEmpty()) {
            saved(limit = 50, query = title).bookmarks.firstOrNull { it.postId == postId }?.let { return it }
        }
        return saved(limit = 50).bookmarks.firstOrNull { it.postId == postId }
    }
}
