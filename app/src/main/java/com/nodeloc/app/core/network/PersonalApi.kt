package com.nodeloc.app.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The personal pages' calls on the 2.0 backend: the invite page, the drafts
 * box and the paged app directory.
 *
 * Apart from [DiscourseClient] for the same reason as [ModerationApi]: none of
 * these had a Discourse-era shape to map onto, so the screens read the native
 * one. Every DTO is optional-by-default — one mistyped field takes the whole
 * response down with it.
 */
class PersonalApi(private val api: NodelocApi) {

    // -------------------------------------------------------------- invites

    /** `code` is null for an account whose level cannot invite yet; the page still opens. */
    suspend fun inviteOverview(): ApiInviteOverviewEnvelope = api.get("api/v1/invites/me")

    /** Resends the friend's verification mail. 429 when reminded in the last 24 hours. */
    suspend fun remindFriend(userId: Long) {
        api.sendRaw("POST", "api/v1/invites/friends/$userId/remind")
    }

    // --------------------------------------------------------------- drafts

    suspend fun drafts(): ApiDraftBox = api.get("api/v1/drafts")

    /**
     * Overwrites one draft. No `revision`: that opts into the server's
     * conflict hold, which needs a chooser this client does not have — it
     * saves the way the older clients did, last write wins.
     */
    suspend fun saveDraft(key: String, data: JsonObject) {
        api.sendRaw("PUT", "api/v1/drafts/${key.enc()}", kotlinx.serialization.json.buildJsonObject {
            put("data", data)
            put("device", kotlinx.serialization.json.JsonPrimitive("phone"))
        })
    }

    suspend fun deleteDraft(key: String) {
        api.sendRaw("DELETE", "api/v1/drafts/${key.enc()}")
    }

    // ----------------------------------------------------------------- apps

    /**
     * One page of the directory. It answered a bare array until
     * `discourse-apps` gave it an envelope and paged it at 24 — a caller that
     * folds failure into an empty list just goes blank when the shape moves.
     */
    suspend fun appDirectory(kind: String?, page: Int): ApiAppDirectoryPage =
        api.get("apps/directory.json", buildList {
            kind?.let { add("kind" to it) }
            if (page > 0) add("page" to page.toString())
        })

    private fun String.enc(): String = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}

@Serializable
data class ApiInviteOverviewEnvelope(
    val overview: ApiInviteOverview = ApiInviteOverview(),
    val code: String? = null,
)

@Serializable
data class ApiInviteOverview(
    val rewards: ApiInviteRewards = ApiInviteRewards(),
    val invited: Int = 0,
    val effective: Int = 0,
    val pendingCount: Int = 0,
    val earned: Long = 0,
    val earnedSecond: Long = 0,
    val pending: Long = 0,
    val milestones: List<ApiInviteMilestone> = emptyList(),
    val milestoneCount: Int = 0,
    val friends: List<ApiInviteFriend> = emptyList(),
)

@Serializable
data class ApiInviteRewards(
    val inviter: Long = 0,
    val welcome: Long = 0,
    val level: Int = 0,
    val levelName: String = "",
    val levelBonus: Long = 0,
    val second: Long = 0,
    val monthlyCap: Int = 0,
    val verifyDays: Int = 0,
)

@Serializable
data class ApiInviteMilestone(
    val target: Int = 0,
    val badge: String? = null,
    val reward: Long = 0,
    val reached: Boolean = false,
)

@Serializable
data class ApiInviteFriend(
    val id: Long = 0,
    val username: String = "",
    val name: String? = null,
    val avatarUrl: String? = null,
    val joinedAt: String? = null,
    /** pending · expired · verified · leveled */
    val stage: String = "",
    val levelName: String? = null,
    val canRemind: Boolean = false,
    val remindedAt: String? = null,
    val expiresAt: String? = null,
)

@Serializable
data class ApiDraftBox(
    val drafts: List<ApiDraftEntry> = emptyList(),
    val lifetimeDays: Int? = null,
)

@Serializable
data class ApiDraftEntry(
    val key: String = "",
    /** Client-defined: the web editor writes `node`, `title`, `raw`, `kind`, `topic_title`… */
    val data: JsonElement? = null,
    val updatedAt: String? = null,
    val publishAt: String? = null,
    val scheduleError: String? = null,
    val expiresAt: String? = null,
) {
    val fields: JsonObject? get() = data as? JsonObject
}

@Serializable
data class ApiAppDirectoryPage(
    val apps: List<ApiDirectoryApp> = emptyList(),
    val total: Int = 0,
    val page: Int = 0,
    val perPage: Int = 24,
    val counts: Map<String, Int> = emptyMap(),
)

@Serializable
data class ApiDirectoryApp(
    val id: Int = 0,
    val slug: String = "",
    val name: String = "",
    val description: String? = null,
    val installsCount: Int? = null,
    /** game · applet · bot */
    val kind: String? = null,
    val logoUrl: String? = null,
)
