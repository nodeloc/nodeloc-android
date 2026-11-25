package com.nodeloc.app.feature.settings

import com.nodeloc.app.core.network.ApiTwoFactorStatus
import com.nodeloc.app.core.network.ApiUserEnvelope
import com.nodeloc.app.core.network.NodelocApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The settings page's calls on the 2.0 backend — the same set the web
 * settings page (`web/static/settings.js`) reads, so the two agree on what
 * every switch means.
 *
 * The older preference pages wrote Discourse `user_option` keys through
 * [com.nodeloc.app.core.network.DiscourseClient.updatePreferences], which
 * forwards only the four profile fields: every privacy and email switch on
 * them flipped on screen and was dropped on the way out. These go to the
 * endpoints that actually store them.
 */
class AccountSettingsApi(private val api: NodelocApi) {

    suspend fun me(): ApiUserEnvelope = api.get("api/v1/me")

    suspend fun settings(): ApiSettingsEnvelope = api.get("api/v1/me/settings")

    /** A partial write: absent keys stay as they are. Answers with the whole bag. */
    suspend fun putSettings(patch: JsonObject): ApiSettingsEnvelope = api.send("PUT", "api/v1/me/settings", patch)

    suspend fun account(): ApiAccountInfo = api.get<ApiAccountEnvelope>("api/v1/me/account").account

    suspend fun twoFactor(): ApiTwoFactorStatus = api.get("api/v1/me/two-factor")

    suspend fun identities(): List<ApiLoginIdentity> = api.get<ApiLoginIdentities>("api/v1/me/identities").identities

    suspend fun unlinkIdentity(provider: String) {
        api.sendRaw("DELETE", "api/v1/me/identities/$provider")
    }

    suspend fun setPhone(phone: String) {
        api.sendRaw("PUT", "api/v1/me/phone", buildJsonObject { put("phone", phone) })
    }

    /** 202 with the address waiting for its confirmation click; nothing changes until then. */
    suspend fun requestEmailChange(email: String, password: String): String? =
        api.send<ApiPendingEmail>("POST", "api/v1/me/email", buildJsonObject {
            put("email", email)
            put("password", password)
        }).pendingEmail

    suspend fun changePassword(current: String, new: String) {
        api.sendRaw("PUT", "api/v1/me/password", buildJsonObject {
            put("current_password", current)
            put("new_password", new)
        })
    }

    /** Null when nothing was ever requested; the web reads a failure the same way. */
    suspend fun latestExport(): ApiDataExport? = api.get<ApiExportEnvelope>("api/v1/me/export").export

    suspend fun requestExport(): ApiDataExport? = api.send<ApiExportEnvelope>("POST", "api/v1/me/export").export

    /** `password` is the username for an account that has none. */
    suspend fun deleteAccount(password: String) {
        api.sendRaw("POST", "api/v1/me/deletion", buildJsonObject { put("password", password) })
    }

    suspend fun telegram(): ApiTelegramStatus = api.get("api/v1/me/telegram")

    /** Turning it on sends a probe message first and refuses if the bot cannot reach the reader. */
    suspend fun setTelegram(enabled: Boolean) {
        api.sendRaw("PUT", "api/v1/me/telegram", buildJsonObject { put("enabled", enabled) })
    }

    /** `mail` or `tg`. 429 inside a minute of the last one. */
    suspend fun notifyTest(channel: String) {
        api.sendRaw("POST", "api/v1/me/notify-test", buildJsonObject { put("channel", channel) })
    }
}

@Serializable
data class ApiSettingsEnvelope(
    val settings: ApiAccountSettings = ApiAccountSettings(),
    val delivery: ApiDelivery = ApiDelivery(),
    /** What each notification row means when the reader never touched it. */
    val notifyDefaults: Map<String, ApiChannels> = emptyMap(),
)

/** Defaults are the server's own (`defaultSettings`), so a half-decoded bag still reads sensibly. */
@Serializable
data class ApiAccountSettings(
    /** `reply` is the site channel, `reply.mail` and `reply.tg` the others; plus the `email`/`tg`/`push` channel switches. */
    val notify: Map<String, Boolean> = emptyMap(),
    val messagePolicy: String = "everyone",
    val mentionPolicy: String = "everyone",
    val allowFollow: Boolean = true,
    val searchIndex: Boolean = true,
    val showUpvotes: Boolean = false,
    val showJoinedNodes: Boolean = true,
    val showOnline: Boolean = true,
    val showLevel: Boolean = true,
    val showAdult: Boolean = false,
    val digest: String = "off",
    val showSignatures: Boolean = false,
    val nodeTint: Boolean = true,
    val signature: String = "",
    val topicListLoad: String = "auto",
)

@Serializable
data class ApiDelivery(
    val quiet: Boolean = false,
    val quietFrom: String = "23:00",
    val quietTo: String = "08:00",
    val tz: String = "",
    val skipSeen: Boolean = true,
    /** now · hour · day */
    val mailFreq: String = "now",
    val tgPreview: Boolean = true,
)

@Serializable
data class ApiChannels(val site: Boolean = true, val mail: Boolean = false, val tg: Boolean = false)

@Serializable
data class ApiAccountEnvelope(val account: ApiAccountInfo = ApiAccountInfo())

@Serializable
data class ApiAccountInfo(
    val phone: String? = null,
    val hasPassword: Boolean = true,
    val passwordChangedAt: String? = null,
    val createdAt: String? = null,
    val pendingEmail: String? = null,
    val coverUrl: String? = null,
)

@Serializable
data class ApiPendingEmail(val pendingEmail: String? = null)

@Serializable
data class ApiLoginIdentities(val identities: List<ApiLoginIdentity> = emptyList(), val hasPassword: Boolean = true)

/** Every sign-in the site offers, linked or not — unlike the older model, which read them all as linked. */
@Serializable
data class ApiLoginIdentity(
    val provider: String = "",
    val label: String = "",
    val linked: Boolean = false,
    val name: String? = null,
    val email: String? = null,
)

@Serializable
data class ApiExportEnvelope(val export: ApiDataExport? = null)

@Serializable
data class ApiDataExport(
    val id: Long = 0,
    /** pending · ready · failed · expired */
    val status: String = "",
    val expiresAt: String? = null,
    val downloadUrl: String? = null,
)

@Serializable
data class ApiTelegramStatus(
    /** The site has a bot configured. */
    val available: Boolean = false,
    /** The account has a Telegram sign-in linked. */
    val linked: Boolean = false,
    /** Notifications are going out to it. */
    val enabled: Boolean = false,
    val bot: String? = null,
)
