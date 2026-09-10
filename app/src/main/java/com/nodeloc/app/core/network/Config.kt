package com.nodeloc.app.core.network

import android.content.Context
import androidx.core.content.edit
import com.nodeloc.app.BuildConfig
import java.util.UUID

object DiscourseConfig {
    const val BASE_URL = "https://www.nodeloc.com"
    const val HOST = "www.nodeloc.com"

    /** Custom scheme registered for the User API Key redirect. */
    const val AUTH_REDIRECT = "nodeloc://auth"
    const val APP_NAME = "NODELOC Android"

    const val SCOPES = "session_info,read,write,notifications,push,message_bus,chat"

    /**
     * Klipy API key for the GIF picker (nodeloc's discourse-gifs runs the Klipy
     * provider); it is a public theme setting on the web. Empty disables the
     * GIF button rather than showing a broken sheet.
     */
    const val KLIPY_API_KEY = "EzZHqISrqNDXf1Jy8TdgG9WQzM1gqPlUYHoQrkZhL0X8WZIM8KL3XTSYatDZ83Bt"

    /**
     * The site's `invite_code`, when it has one.
     *
     * Discourse compares this against `SiteSetting.invite_code` and rejects a
     * signup that gets it wrong — which is what lets registration work here
     * while the website turns strangers away. Sent only when non-empty:
     * `params.require(:invite_code)` treats a blank one as missing, and a site
     * with no code set ignores the field entirely.
     */
    val INVITE_CODE: String = BuildConfig.INVITE_CODE

    /**
     * The topic whose first post carries the update manifest.
     *
     * A topic id rather than a slug: slugs follow the title and a renamed
     * topic would quietly stop the app from ever seeing another release.
     * Zero disables update checking entirely, which is the right state for a
     * build that has nowhere to point.
     */
    const val UPDATE_TOPIC_ID = 105608

    private const val CLIENT_ID_KEY = "nodeloc.client_id"

    @Volatile
    private var cachedClientId: String? = null

    /** Stable per-install id, generated once. */
    fun clientId(context: Context): String = cachedClientId ?: synchronized(this) {
        cachedClientId ?: run {
            val prefs = context.applicationContext.getSharedPreferences("nodeloc.install", Context.MODE_PRIVATE)
            val existing = prefs.getString(CLIENT_ID_KEY, null)
            val id = existing ?: UUID.randomUUID().toString().also {
                prefs.edit { putString(CLIENT_ID_KEY, it) }
            }
            cachedClientId = id
            id
        }
    }

    /** Absolute URL for a possibly-relative Discourse path. */
    fun absoluteUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        // Case-insensitively: a scheme is case-insensitive per RFC 3986, and
        // treating `HTTPS://…` as site-relative would prefix it with the site
        // root and route it to a 404 of itself.
        return when {
            raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true) -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> BASE_URL + raw
            else -> "$BASE_URL/$raw"
        }
    }

    /** Resolves a Discourse `avatar_template` into a concrete image URL. */
    fun avatarUrl(template: String?, size: Int = 120): String? {
        if (template.isNullOrBlank()) return null
        return absoluteUrl(template.replace("{size}", size.toString()))
    }
}
