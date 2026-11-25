package com.nodeloc.app.core.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Moderation and node management on the 2.0 backend.
 *
 * Kept apart from [DiscourseClient]'s compatibility facade: none of this ever
 * existed in the Discourse-era models, so there is nothing to map onto, and the
 * screens use these shapes directly.
 *
 * Every body is built field by field. The server's decoder rejects unknown
 * fields *and* empty bodies with 400 `invalid_json`, so a "no body" call still
 * sends `{}` (NodelocApi does that) and nothing optional is sent unless set.
 */
class ModerationApi(private val api: NodelocApi) {

    suspend fun permissions(): ApiPermissions = api.get("api/v1/me/permissions")

    suspend fun myNodes(): ApiMyNodes = api.get("api/v1/me/nodes")

    // -------------------------------------------------------------- review

    /** The site queue (`admin.review`). [kind] filters exactly; [status] defaults to pending. */
    suspend fun siteQueue(status: String = "pending", kind: String? = null, before: Long? = null, limit: Int = 30): ApiReviewPage =
        api.get("api/v1/admin/review", buildList {
            add("status" to status)
            kind?.let { add("kind" to it) }
            before?.let { add("before" to it.toString()) }
            add("limit" to limit.toString())
        })

    suspend fun siteItem(id: Long): ApiReviewItem? = siteItemPage(id).items.firstOrNull()

    /** One item as a page, for the page's reputation_penalty_max. */
    suspend fun siteItemPage(id: Long): ApiReviewPage =
        api.get("api/v1/admin/review", listOf("item" to id.toString()))

    /** One node's queue: post items only, no counts, no kind filter. */
    suspend fun nodeQueue(slug: String, status: String = "pending", before: Long? = null, limit: Int = 30): ApiReviewPage =
        api.get("api/v1/nodes/${slug.enc()}/review", buildList {
            add("status" to status)
            before?.let { add("before" to it.toString()) }
            add("limit" to limit.toString())
        })

    /**
     * `delete_post`, `hide_post`, `reject` (un-hides), `ignore`, `approve` (held posts only).
     * [note] reaches the author as the notification body for delete/hide.
     * A node moderator resolves through the node route, which takes no reason/days.
     */
    suspend fun resolve(itemId: Long, action: String, note: String?, nodeSlug: String? = null, reputationPenalty: Int = 0) {
        val body = buildJsonObject {
            put("action", action)
            if (!note.isNullOrBlank()) put("note", note.trim())
            // Only delete_post / hide_post / confirm take one; anything else with a non-zero value is a 422.
            if (reputationPenalty > 0) put("reputation_penalty", reputationPenalty)
        }
        val path = if (nodeSlug != null) "api/v1/nodes/${nodeSlug.enc()}/review/$itemId/resolve"
        else "api/v1/admin/review/$itemId/resolve"
        api.sendRaw("POST", path, body)
    }

    /**
     * Gives back what resolving this item took from the author, and nothing
     * else: the verdict and the post stay as they are. 409 nothing_to_refund
     * when nothing was taken or it was already returned.
     */
    suspend fun refundReputation(itemId: Long, nodeSlug: String? = null) {
        val path = if (nodeSlug != null) "api/v1/nodes/${nodeSlug.enc()}/review/$itemId/refund-reputation"
        else "api/v1/admin/review/$itemId/refund-reputation"
        api.sendRaw("POST", path, buildJsonObject {})
    }

    /** Status only: a deleted post stays deleted. Site staff only. */
    suspend fun reopen(itemId: Long, note: String? = null) {
        api.sendRaw("POST", "api/v1/admin/review/$itemId/reopen", noteBody(note))
    }

    /** Puts a resolved item's post back. Site staff only. */
    suspend fun restoreFromReview(itemId: Long, note: String? = null) {
        api.sendRaw("POST", "api/v1/admin/review/$itemId/restore-post", noteBody(note))
    }

    // -------------------------------------------------------------- users

    suspend fun users(query: String? = null, status: String? = null, page: Int = 1): ApiAdminUserList =
        api.get("api/v1/admin/users", buildList {
            if (!query.isNullOrBlank()) add("q" to query.trim().removePrefix("@"))
            status?.let { add("status" to it) }
            add("page" to page.toString())
        })

    suspend fun userDetail(userId: Long): ApiAdminUserDetail = api.get("api/v1/admin/users/$userId")

    /** [kind] `silence` or `suspend`; [days] 0 is permanent. Notifies and emails the user. */
    suspend fun penalize(userId: Long, kind: String, reason: String, days: Int) {
        api.sendRaw("POST", "api/v1/admin/users/$userId/penalties", buildJsonObject {
            put("kind", kind)
            put("reason", reason)
            put("days", days)
        })
    }

    suspend fun updatePenalty(userId: Long, kind: String, days: Int, reason: String? = null) {
        api.sendRaw("PUT", "api/v1/admin/users/$userId/penalties/$kind", buildJsonObject {
            put("days", days)
            if (!reason.isNullOrBlank()) put("reason", reason)
        })
    }

    suspend fun revokePenalty(userId: Long, kind: String) {
        api.sendRaw("DELETE", "api/v1/admin/users/$userId/penalties/$kind")
    }

    suspend fun notes(userId: Long): List<ApiUserNote> = api.get<ApiUserNotes>("api/v1/admin/users/$userId/notes").notes

    suspend fun addNote(userId: Long, body: String) {
        api.sendRaw("POST", "api/v1/admin/users/$userId/notes", buildJsonObject { put("body", body.trim()) })
    }

    suspend fun deleteNote(noteId: Long) {
        api.sendRaw("DELETE", "api/v1/admin/notes/$noteId")
    }

    // -------------------------------------------------------------- node-scoped restrictions

    /** [kind] `mute` (no posting in the node) or `ban` (cannot see it). [days] 0 is no expiry. The user is not notified. */
    suspend fun restrictInNode(slug: String, username: String, kind: String, reason: String, days: Int) {
        api.sendRaw("POST", "api/v1/nodes/${slug.enc()}/restrictions", buildJsonObject {
            put("username", username)
            put("kind", kind)
            if (reason.isNotBlank()) put("reason", reason)
            put("days", days)
        })
    }

    suspend fun liftNodeRestriction(slug: String, userId: Long) {
        api.sendRaw("DELETE", "api/v1/nodes/${slug.enc()}/restrictions/$userId")
    }

    suspend fun nodeRestrictions(slug: String): List<ApiNodeRestriction> =
        api.get<ApiNodeRestrictions>("api/v1/nodes/${slug.enc()}/restrictions").restrictions

    // -------------------------------------------------------------- logs

    /** [action] is exact, or a prefix when it ends with "." (`user.`, `review.`). */
    suspend fun auditLogs(actor: String? = null, action: String? = null, before: Long? = null): ApiAuditLogPage =
        api.get("api/v1/admin/audit-logs", buildList {
            actor?.let { add("actor" to it) }
            action?.let { add("action" to it) }
            before?.let { add("before" to it.toString()) }
            add("limit" to "50")
        })

    suspend fun nodeModLog(slug: String, before: Long? = null): ApiNodeModLogPage =
        api.get("api/v1/nodes/${slug.enc()}/mod-log", buildList { before?.let { add("before" to it.toString()) } })

    // -------------------------------------------------------------- topics & posts

    /** Only `closed` and `pinned`/`pin_days` exist on this route. [pinDays] ∈ 0 (permanent), 1, 3, 7, 30. */
    suspend fun setTopicState(topicId: Long, closed: Boolean? = null, pinned: Boolean? = null, pinDays: Int? = null) {
        api.sendRaw("PUT", "api/v1/topics/$topicId/state", buildJsonObject {
            closed?.let { put("closed", it) }
            pinned?.let { put("pinned", it) }
            if (pinned == true && pinDays != null) put("pin_days", pinDays)
        })
    }

    suspend fun setFeatured(topicId: Long, featured: Boolean) {
        api.sendRaw("PUT", "api/v1/topics/$topicId/featured", buildJsonObject { put("featured", featured) })
    }

    /** [reason] ∈ `violation`, `wrong_node`, `duplicate`, `promotion`, `other`. The author is notified. */
    suspend fun moveTopic(topicId: Long, nodeSlug: String, reason: String, note: String?) {
        api.sendRaw("POST", "api/v1/topics/$topicId/move", buildJsonObject {
            put("node", nodeSlug)
            put("reason", reason)
            if (!note.isNullOrBlank()) put("note", note.trim().take(200))
        })
    }

    /** Topic deletion takes no reason and notifies no one. */
    suspend fun deleteTopic(topicId: Long) {
        api.sendRaw("DELETE", "api/v1/topics/$topicId")
    }

    /** A moderator's reason shows on the reply's placeholder. Ignored for post #1 (that deletes the topic). */
    suspend fun deletePost(postId: Long, reason: String?) {
        api.sendRaw("DELETE", "api/v1/posts/$postId", if (reason.isNullOrBlank()) null else buildJsonObject { put("reason", reason.take(200)) })
    }

    suspend fun restorePost(postId: Long) {
        api.sendRaw("POST", "api/v1/posts/$postId/restore")
    }

    suspend fun revisions(postId: Long): ApiRevisions = api.get("api/v1/posts/$postId/revisions")

    /**
     * There is no revert route: rolling back is an ordinary edit carrying the
     * old text, which is what the web client does, so it becomes a new revision.
     */
    suspend fun rollback(postId: Long, raw: String, title: String?, reason: String) {
        api.sendRaw("PATCH", "api/v1/posts/$postId", buildJsonObject {
            put("raw", raw)
            if (title != null) put("title", title)
            put("reason", reason)
        })
    }

    // -------------------------------------------------------------- node management

    suspend fun nodeManage(slug: String): ApiNodeManage = api.get("api/v1/nodes/${slug.enc()}/manage")

    suspend fun nodeStats(slug: String): ApiNodeStats = api.get("api/v1/nodes/${slug.enc()}/stats")

    suspend fun nodeAccess(slug: String): ApiNodeAccess = api.get("api/v1/nodes/${slug.enc()}/access")

    suspend fun setNodeRules(slug: String, rules: List<ApiNodeRule>) {
        api.sendRaw("PUT", "api/v1/nodes/${slug.enc()}/rules", buildJsonObject {
            putJsonArray("rules") {
                rules.forEach { rule ->
                    add(buildJsonObject {
                        put("title", rule.title)
                        put("description", rule.description.orEmpty())
                    })
                }
            }
        })
    }

    // -------------------------------------------------------------- channels (owner-level only)

    suspend fun channels(slug: String): ApiChannelList = api.get("api/v1/nodes/${slug.enc()}/channels")

    /**
     * Pause or resume. The update route resets every editable field from the
     * body and treats a missing `enabled` as true, so the whole channel is sent back.
     */
    suspend fun setChannelEnabled(slug: String, channel: ApiChannel, enabled: Boolean): ApiChannel =
        api.send<ApiChannelEnvelope>("PUT", "api/v1/nodes/${slug.enc()}/channels/${channel.id}", buildJsonObject {
            put("name", channel.name)
            put("post_as", channel.postAs)
            put("title_prefix", channel.titlePrefix)
            putJsonArray("tags") { channel.tags.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            put("daily_limit", channel.dailyLimit)
            put("review", channel.review)
            put("embed", channel.embed)
            put("enabled", enabled)
        }).channel

    suspend fun syncChannel(slug: String, channelId: Long): ApiChannelSync =
        api.send("POST", "api/v1/nodes/${slug.enc()}/channels/$channelId/sync")

    // -------------------------------------------------------------- dashboard / appeals

    suspend fun adminStats(days: Int = 7): ApiAdminStats = api.get("api/v1/admin/stats", listOf("days" to days.toString()))

    suspend fun appeals(status: String = "pending"): ApiAppealList =
        api.get("api/v1/admin/risk/appeals", listOf("status" to status))

    /** Accept as `false_positive` or `lenient`; a rejection sends an empty decision. */
    suspend fun resolveAppeal(appealId: Long, accept: Boolean, decision: String = "") {
        api.sendRaw("POST", "api/v1/admin/risk/appeals/$appealId/resolve", buildJsonObject {
            put("accept", accept)
            put("decision", if (accept) decision else "")
        })
    }

    private fun noteBody(note: String?): JsonObject = buildJsonObject {
        if (!note.isNullOrBlank()) put("note", note.trim().take(500))
    }

    private fun String.enc(): String = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}
