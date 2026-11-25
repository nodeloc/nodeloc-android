package com.nodeloc.app.feature.profile

import androidx.compose.ui.graphics.Color
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.network.ApiCheckinResult
import com.nodeloc.app.core.network.ApiCheckinStatus
import com.nodeloc.app.core.network.ApiPointsSummary
import com.nodeloc.app.core.network.NodelocApi
import kotlinx.serialization.Serializable

/**
 * Reputation, tiers, achievements and energy on the 2.0 backend.
 *
 * Kept out of the Discourse-era facade: none of these pages existed there, so
 * there is no legacy model to map onto, and the screens read these shapes
 * directly. Every field is optional because one mistyped field fails the
 * whole response, and these endpoints are still moving.
 */
class GrowthApi(private val api: NodelocApi) {

    /** Mine: every achievement with progress. Fetching it also unlocks anything already earned. */
    suspend fun achievements(): ApiAchOverview = api.get("api/v1/achievements")

    /** Anyone's: only what they chose to pin, plus reputation unless they hide it. */
    suspend fun showcase(username: String): ApiAchShowcase =
        api.get("api/v1/users/${username.enc()}/achievements")

    suspend fun setPinned(key: String, pinned: Boolean) {
        api.sendRaw(if (pinned) "PUT" else "DELETE", "api/v1/achievements/${key.enc()}/pin")
    }

    suspend fun reputation(): ApiRepSummary = api.get("api/v1/me/reputation")

    /** Public, localised names and the colours the web draws each tier in. */
    suspend fun tiers(): List<ApiTierInfo> = api.get<ApiTiers>("api/v1/tiers").tiers

    suspend fun balance(): Long = api.get<ApiPointsSummary>("api/v1/points").balance

    /** The current month in Beijing time, which is how the ledger counts months. */
    suspend fun monthSummary(): ApiMonthSummary = api.get("api/v1/points/summary")

    /**
     * [direction] is `in`/`out`, [group] a ledger group key; the server
     * intersects them, so the screen sends at most one of the two.
     */
    suspend fun history(direction: String?, group: String?, before: Long?, limit: Int = 30): ApiEnergyPage =
        api.get("api/v1/points/history", buildList {
            add("limit" to limit.toString())
            direction?.let { add("direction" to it) }
            group?.let { add("group" to it) }
            before?.let { add("before" to it.toString()) }
        })

    suspend fun checkinStatus(): ApiCheckinStatus = api.get("api/v1/checkin")

    suspend fun checkin(): ApiCheckinResult = api.send("POST", "api/v1/checkin")

    /** All-time balances only: the server keeps no weekly or monthly board, and no movement. */
    suspend fun leaderboard(limit: Int = LEADERBOARD_LIMIT): List<ApiLeaderRow> =
        api.get<ApiLeaderboard>("api/v1/points/leaderboard", listOf("limit" to limit.toString())).users

    private fun String.enc(): String = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    companion object {
        /** The server's ceiling for one board request. */
        const val LEADERBOARD_LIMIT = 100

        val shared: GrowthApi by lazy { GrowthApi(ServiceLocator.get.client.api) }
    }
}

@Serializable
data class ApiAchItem(
    val key: String = "",
    val category: String? = null,
    val categoryKey: String? = null,
    val tier: Int = 0,
    val tierName: String? = null,
    val icon: String? = null,
    val name: String = "",
    val description: String? = null,
    val how: String? = null,
    val reputation: Int = 0,
    val target: Long = 0,
    val repeatable: Boolean = false,
    val manual: Boolean = false,
    val closed: Boolean = false,
    val current: Long = 0,
    val times: Int = 0,
    val unlocked: Boolean = false,
    val unlockedAt: String? = null,
    val lastAt: String? = null,
    val off: Boolean = false,
    val pinned: Boolean = false,
)

@Serializable
data class ApiAchOverview(
    val achievements: List<ApiAchItem> = emptyList(),
    val showcase: List<String> = emptyList(),
    val maxShowcase: Int = 3,
    val unlockedCount: Int = 0,
    val total: Int = 0,
    val reputation: Long = 0,
)

@Serializable
data class ApiAchShowcase(val showcase: List<ApiAchItem> = emptyList(), val reputation: Long? = null)

@Serializable
data class ApiRepStep(val level: Int = 0, val name: String? = null, val threshold: Int = 0)

@Serializable
data class ApiRepSource(val source: String = "", val total: Int = 0)

@Serializable
data class ApiRepSummary(
    val reputation: Int = 0,
    val pending: Int = 0,
    val tier: Int = 0,
    val tierName: String? = null,
    val next: ApiRepStep? = null,
    val needed: Int = 0,
    val progress: Double = 0.0,
    val week: Int = 0,
    val sources: List<ApiRepSource> = emptyList(),
    val tiers: List<ApiRepStep> = emptyList(),
    val legendReview: String? = null,
    val upgradeLockedUntil: String? = null,
)

@Serializable
data class ApiTierInfo(
    val level: Int = 0,
    val name: String? = null,
    val bg: String? = null,
    val fg: String? = null,
    val darkBg: String? = null,
    val darkFg: String? = null,
)

@Serializable
data class ApiTiers(val tiers: List<ApiTierInfo> = emptyList())

@Serializable
data class ApiMonthSummary(val month: String? = null, val income: Long = 0, val expense: Long = 0)

@Serializable
data class ApiEnergyRef(val type: String? = null, val label: String? = null, val url: String? = null)

@Serializable
data class ApiEnergyEntry(
    val id: Long = 0,
    val kind: String = "",
    val amount: Long = 0,
    val memo: String? = null,
    val ref: ApiEnergyRef? = null,
    val counterpart: String? = null,
    val streak: Int = 0,
    val createdAt: String? = null,
)

@Serializable
data class ApiEnergyPage(val entries: List<ApiEnergyEntry> = emptyList(), val total: Int? = null, val nextBefore: Long? = null)

@Serializable
data class ApiLeaderRow(val rank: Int = 0, val username: String = "", val balance: Long = 0)

@Serializable
data class ApiLeaderboard(val users: List<ApiLeaderRow> = emptyList())

/**
 * A tier's ground and ink as the server paints it. Data rather than design
 * tokens: the tiers are configured server-side and the web draws the same
 * pair, so a pill here matches the one on the site.
 */
fun ApiTierInfo.colors(dark: Boolean): Pair<Color, Color>? {
    val ground = parseHex(if (dark) darkBg else bg) ?: return null
    val ink = parseHex(if (dark) darkFg else fg) ?: return null
    return ground to ink
}

private fun parseHex(raw: String?): Color? {
    val hex = raw?.trim()?.removePrefix("#") ?: return null
    if (hex.length != 6) return null
    val value = hex.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or value)
}
