package com.nodeloc.app.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Moderation and administration shapes from `/api/v1` (2.0 backend).
 *
 * Optional-by-default like the rest of ApiModels. Ids are Long because the
 * server's are int64 and review/log ids are not bounded the way topic ids are.
 * Times stay strings and go through DiscourseFormat.instant.
 */

/** `GET /api/v1/me/permissions`. Admins get every key; defaults are editable server-side, so never assume them. */
@Serializable
data class ApiPermissions(
    val permissions: List<String> = emptyList(),
    val limits: Map<String, Long> = emptyMap(),
    val subjects: List<String> = emptyList(),
)

/** Permission keys the app acts on. */
object Perm {
    const val NODES_MODERATE = "nodes.moderate"
    const val NODES_MANAGE = "nodes.manage"
    const val TOPICS_CURATE = "topics.curate"
    const val ADMIN_DASHBOARD = "admin.dashboard"
    const val ADMIN_USERS = "admin.users"
    const val ADMIN_REVIEW = "admin.review"
    const val ADMIN_LOGS = "admin.logs"
    const val ADMIN_APPEALS = "admin.appeals"
}

// ------------------------------------------------------------------ review

@Serializable
data class ApiReviewReport(
    val reporter: String = "",
    val reason: String = "",
    val note: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class ApiReviewItem(
    val id: Long = 0,
    /** `report`, `pending_post`, `node_report`, `link_report`, `risk_flag`, `risk_appeal`, `registration`, … */
    val kind: String = "",
    /** `pending`, `approved`, `rejected`, `ignored`. */
    val status: String = "",
    val score: Double = 0.0,
    val targetType: String = "",
    val targetId: Long = 0,
    val targetUsername: String? = null,
    val targetUserId: Long? = null,
    val nodeSlug: String? = null,
    val nodeName: String? = null,
    val reportCount: Long = 0,
    val priority: Boolean = false,
    val payload: JsonObject? = null,
    val topicId: Long? = null,
    val topicTitle: String? = null,
    val postNumber: Int? = null,
    val postHtml: String? = null,
    val reports: List<ApiReviewReport> = emptyList(),
    val resolution: String? = null,
    val resolvedBy: String? = null,
    val resolvedAt: String? = null,
    val createdAt: String? = null,
    val postDeleted: Boolean = false,
    val postHidden: Boolean = false,
) {
    val isPost: Boolean get() = targetType == "post"

    /**
     * Why a held post is held. The server only says so in Chinese prose in
     * `payload.reason` — there is no code — so the filters match on it.
     */
    val heldReason: String? get() = (payload?.get("reason") as? JsonPrimitive)?.contentOrNull
}

@Serializable
data class ApiReviewPage(
    val items: List<ApiReviewItem> = emptyList(),
    val nextBefore: Long? = null,
    /** Site queue only: totals per status. */
    val counts: Map<String, Int> = emptyMap(),
    /** Site queue only: per kind, for the requested status. */
    val kindCounts: Map<String, Int> = emptyMap(),
)

// ------------------------------------------------------------------ users

@Serializable
data class ApiAdminUserRow(
    val id: Long = 0,
    val username: String = "",
    val name: String? = null,
    val role: String? = null,
    val trustLevel: Int? = null,
    val createdAt: String? = null,
    val lastSeenAt: String? = null,
    val silencedUntil: String? = null,
    val suspendedUntil: String? = null,
    val deleted: Boolean = false,
    /** `normal`, `silenced`, `suspended`, `unverified`, `deleted`. */
    val status: String = "normal",
    val points: Long = 0,
    val posts: Int = 0,
    val avatarUrl: String? = null,
)

@Serializable
data class ApiAdminUserList(
    val users: List<ApiAdminUserRow> = emptyList(),
    val total: Int = 0,
    val counts: Map<String, Int> = emptyMap(),
)

@Serializable
data class ApiAdminUser(
    val id: Long = 0,
    val username: String = "",
    val name: String? = null,
    val avatar: String? = null,
    val role: String? = null,
    val trustLevel: Int? = null,
    val tierName: String? = null,
    val silencedUntil: String? = null,
    val suspendedUntil: String? = null,
    val silenced: Boolean = false,
    val suspended: Boolean = false,
    val createdAt: String? = null,
    val lastSeenAt: String? = null,
    val deleted: Boolean = false,
    val points: Long = 0,
)

@Serializable
data class ApiAdminUserStats(
    val topics: Int = 0,
    val posts: Int = 0,
    val timesReported: Int = 0,
    val daysVisited: Int = 0,
)

@Serializable
data class ApiPenalty(
    val id: Long = 0,
    /** `silence` (禁言) or `suspend` (封号). */
    val kind: String = "",
    val reason: String = "",
    /** Null means permanent. */
    val endsAt: String? = null,
    val revokedAt: String? = null,
    val createdAt: String? = null,
    val createdBy: String? = null,
)

@Serializable
data class ApiAuditEntry(
    val id: Long = 0,
    val action: String = "",
    val details: JsonObject? = null,
    val actor: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class ApiReportAgainst(
    val itemId: Long = 0,
    val kind: String = "",
    val status: String = "",
    val targetType: String = "",
    val targetId: Long = 0,
    val resolution: String? = null,
    val reportCount: Int = 0,
    val createdAt: String? = null,
)

@Serializable
data class ApiAdminUserDetail(
    val user: ApiAdminUser = ApiAdminUser(),
    val stats: ApiAdminUserStats = ApiAdminUserStats(),
    val penalties: List<ApiPenalty> = emptyList(),
    val logs: List<ApiAuditEntry> = emptyList(),
    val reportsAgainst: List<ApiReportAgainst> = emptyList(),
)

@Serializable
data class ApiUserNote(
    val id: Long = 0,
    val author: String = "",
    val body: String = "",
    val createdAt: String? = null,
)

@Serializable
data class ApiUserNotes(val notes: List<ApiUserNote> = emptyList())

// ------------------------------------------------------------------ logs

@Serializable
data class ApiAuditLog(
    val id: Long = 0,
    val actor: String? = null,
    val action: String = "",
    val targetType: String? = null,
    val targetId: Long? = null,
    val targetName: String = "",
    val details: JsonObject? = null,
    val createdAt: String? = null,
)

@Serializable
data class ApiAuditLogPage(
    val logs: List<ApiAuditLog> = emptyList(),
    val nextBefore: Long? = null,
)

/** `GET /nodes/{slug}/mod-log`: `target` is a username; `next_before` is omitted on the last page. */
@Serializable
data class ApiNodeModLog(
    val id: Long = 0,
    val action: String = "",
    val details: JsonObject? = null,
    val actor: String = "",
    val target: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class ApiNodeModLogPage(
    val logs: List<ApiNodeModLog> = emptyList(),
    val nextBefore: Long? = null,
)

// ------------------------------------------------------------------ node management

@Serializable
data class ApiNodeRestriction(
    val id: Long = 0,
    /** `mute` (cannot post in the node) or `ban` (cannot see it). */
    val kind: String = "",
    val reason: String = "",
    val expiresAt: String? = null,
    val createdAt: String? = null,
    val userId: Long = 0,
    val username: String = "",
    val createdBy: String = "",
)

@Serializable
data class ApiNodeRestrictions(val restrictions: List<ApiNodeRestriction> = emptyList())

@Serializable
data class ApiNodeRule(val title: String = "", val description: String? = null)

@Serializable
data class ApiNodeModerator(val id: Long = 0, val username: String = "", val owner: Boolean = false)

@Serializable
data class ApiManagedNode(
    val id: Long = 0,
    val slug: String = "",
    val name: String = "",
    val description: String = "",
    val ownerId: Long = 0,
    val color: String = "",
    val iconText: String = "",
    val logoUrl: String = "",
    val memberCount: Int = 0,
    val topicCount: Int = 0,
    val visibility: String = "",
)

@Serializable
data class ApiNodeManage(
    /** `owner`, `moderator` or `staff` (site staff who is neither). */
    val role: String = "",
    val canEdit: Boolean = false,
    val isStaff: Boolean = false,
    val isAdmin: Boolean = false,
    val node: ApiManagedNode = ApiManagedNode(),
    val rules: List<ApiNodeRule> = emptyList(),
    val moderators: List<ApiNodeModerator> = emptyList(),
)

@Serializable
data class ApiNodeAccessEntry(val subject: String = "", val level: Int = 0)

@Serializable
data class ApiNodeAccessGroup(val subject: String = "", val kind: String = "", val name: String? = null, val level: Int? = null)

@Serializable
data class ApiNodeAccess(
    val entries: List<ApiNodeAccessEntry> = emptyList(),
    val groups: List<ApiNodeAccessGroup> = emptyList(),
)

@Serializable
data class ApiNodeWeekStats(val topics: Int = 0, val posts: Int = 0, val newMembers: Int = 0, val activeMembers: Int = 0)

@Serializable
data class ApiNodeStats(val week: ApiNodeWeekStats = ApiNodeWeekStats())

// ------------------------------------------------------------------ channels

@Serializable
data class ApiChannelRecent(
    val id: Long = 0,
    val status: String = "",
    val title: String = "",
    val topicId: Long? = null,
    val error: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class ApiChannel(
    val id: Long = 0,
    /** `rss`, `youtube`, `telegram`, `bilibili`, `webhook`. */
    val kind: String = "",
    val name: String = "",
    val feedUrl: String? = null,
    val siteUrl: String? = null,
    val source: String? = null,
    val postAs: String = "owner",
    val titlePrefix: String = "",
    val tags: List<String> = emptyList(),
    val dailyLimit: Int = 10,
    val review: Boolean = false,
    val embed: Boolean = true,
    val enabled: Boolean = true,
    val lastSyncedAt: String? = null,
    val lastPolledAt: String? = null,
    val nextPollAt: String? = null,
    val pausedAt: String? = null,
    val lastError: String? = null,
    val lastErrorCode: String? = null,
    val failCount: Int = 0,
    val synced: Int = 0,
    val today: Int = 0,
    val recent: List<ApiChannelRecent> = emptyList(),
) {
    /** The server has no status field; this is the web client's derivation. */
    val state: ChannelState
        get() = when {
            !enabled -> ChannelState.Paused
            lastErrorCode != null || failCount > 0 -> ChannelState.Error
            else -> ChannelState.Ok
        }
}

enum class ChannelState { Ok, Paused, Error }

@Serializable
data class ApiChannelList(
    val channels: List<ApiChannel> = emptyList(),
    val pollMinutes: Int = 15,
    val maxChannels: Int = 10,
)

@Serializable
data class ApiChannelEnvelope(val channel: ApiChannel = ApiChannel())

@Serializable
data class ApiChannelProblem(val code: String = "", val detail: String? = null)

@Serializable
data class ApiChannelSync(val new: Int = 0, val problem: ApiChannelProblem? = null)

// ------------------------------------------------------------------ dashboard / appeals / revisions

@Serializable
data class ApiStatsDay(
    val day: String = "",
    val signups: Int = 0,
    val activeUsers: Int = 0,
    val topics: Int = 0,
    val replies: Int = 0,
    val reports: Int = 0,
)

@Serializable
data class ApiAdminStats(val days: List<ApiStatsDay> = emptyList())

@Serializable
data class ApiAppeal(
    val id: Long = 0,
    val userId: Long = 0,
    val username: String = "",
    /** `wrong` (判错了) or `will_improve` (我会改). */
    val kind: String = "",
    val body: String = "",
    val status: String = "",
    val createdAt: String? = null,
)

@Serializable
data class ApiAppealList(
    val appeals: List<ApiAppeal> = emptyList(),
    val total: Int = 0,
    val counts: Map<String, Int> = emptyMap(),
)

@Serializable
data class ApiTopicMove(
    val from: String = "",
    val to: String = "",
    val actor: String = "",
    val reason: String = "",
    val note: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class ApiRevision(
    val number: Int = 0,
    val editor: String = "",
    val editorAvatar: String? = null,
    val byAuthor: Boolean = false,
    val reason: String = "",
    /** `[before, after]`. */
    val raw: List<String> = emptyList(),
    /** `[before, after]`, first post only. */
    val title: List<String>? = null,
    val createdAt: String? = null,
)

@Serializable
data class ApiRevisions(
    val postId: Long = 0,
    val postNumber: Int = 0,
    val author: String = "",
    val authorAvatar: String? = null,
    val postedAt: String? = null,
    val canRestore: Boolean = false,
    val revisions: List<ApiRevision> = emptyList(),
)
