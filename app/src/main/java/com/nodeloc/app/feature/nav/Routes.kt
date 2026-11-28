package com.nodeloc.app.feature.nav

import androidx.annotation.StringRes
import com.nodeloc.app.R
import kotlinx.serialization.Serializable

/**
 * Type-safe destinations. Full-screen pages (reader, composer, settings) are
 * ordinary destinations rather than the hand-drawn overlay stack iOS uses, so
 * the system back gesture unwinds them one level at a time for free.
 */
object Route {
    @Serializable data object Home
    @Serializable data object Nodes
    @Serializable data object Inbox
    @Serializable data object Search
    @Serializable data object Profile

    @Serializable data class Reader(val topicId: Int, val postNumber: Int? = null)
    @Serializable data class NodeDetail(val categoryId: Int, val slug: String? = null)
    @Serializable data class NodeGroup(val categoryId: Int, val name: String)
    @Serializable data object CreateNode
    @Serializable data class CustomFeed(val id: Long, val name: String)
    @Serializable data object CreateCustomFeed
    @Serializable data class EditCustomFeed(val id: Long)
    @Serializable data class Compose(
        val categoryId: Int? = null,
        val repostTopicId: Int? = null,
        val prefillTitle: String? = null,
        /** Set for a private message: the composer addresses a person, not a node. */
        val pmRecipient: String? = null,
        /**
         * Editing an existing post rather than writing a new one. Only the
         * opening post arrives here: it is a topic, with a title and pictures
         * and everything else the composer already knows how to handle. A reply
         * stays in the sheet it was written in.
         */
        val editPostId: Int? = null,
        /** A server draft to pick up (`new_topic` or `new_topic:…`), from the drafts box. */
        val draftKey: String? = null,
    )

    @Serializable data class PublicProfile(val username: String)

    /** Trust-level progress for one account; reached from the profile header. */
    @Serializable data class UpgradeProgress(val username: String)
    @Serializable data class ChatConversation(val channelId: Int, val messageId: Int? = null, val threadId: Int? = null)
    @Serializable data class Browser(val url: String)
    @Serializable data class NodeSearch(val slug: String)

    @Serializable data object Apps
    @Serializable data class AppDetail(val slug: String)

    @Serializable data object Settings
    @Serializable data object SettingsPush
    @Serializable data object SettingsPostSource
    @Serializable data object SettingsSecurity
    @Serializable data object SettingsBlocked
    @Serializable data object ProfileEdit

    @Serializable data object Auth

    /** The same flow, entered at the sign-up page. */
    @Serializable data object AuthSignup
    @Serializable data object AuthLogin

    /** Signing in through the website, for a provider Discourse owns the keys to. */
    /**
     * The sign-up page carries nothing.
     *
     * Route arguments are serialised into the back stack's saved state, which
     * the system writes to disk to survive process death — not where a
     * plaintext password goes. The form lives in `AuthViewModel`, scoped to
     * the enclosing [Auth] entry.
     */
    @Serializable data object SignupEmail
    @Serializable data class SignupActivation(val email: String)

    @Serializable data object Welcome

    // Moderation. A null nodeSlug means the site-wide view (staff); a slug
    // scopes the page to one node, which is all a node moderator can see.
    @Serializable data class ModDashboard(val nodeSlug: String? = null)
    /** [tab] is a `ReviewTab` name, for the dashboard's counts that open one tab. */
    @Serializable data class ReviewQueue(val nodeSlug: String? = null, val tab: String? = null)
    @Serializable data class ReviewItem(val itemId: Long, val nodeSlug: String? = null)
    @Serializable data object ModUsers
    @Serializable data class ModUser(val userId: Long, val username: String, val nodeSlug: String? = null)
    /** [filter] is a `LogFilter` name, for the dashboard's 今日 counts. */
    @Serializable data class AuditLog(val nodeSlug: String? = null, val filter: String? = null)
    @Serializable data class NodeAdmin(val slug: String)
    @Serializable data class NodeChannels(val slug: String)

    // Personal pages (2026-11 design, 个人 1–10).
    @Serializable data class Achievements(val username: String? = null)
    @Serializable data object EnergyHistory
    @Serializable data object WealthRank
    @Serializable data object Invite
    @Serializable data object Drafts
    @Serializable data object Bookmarks
}

/**
 * The bottom tabs. Icons are the 2026-11 design's own glyphs (res/drawable
 * ic_tab_*), not a library set: 节点 is the nodeloc mark, which no icon font has.
 */
enum class TabItem(
    @StringRes val labelRes: Int,
    @androidx.annotation.DrawableRes val icon: Int,
    @androidx.annotation.DrawableRes val selectedIcon: Int = icon,
    /**
     * The selected 节点 mark is two-coloured (green petals, orange dots), so it
     * is drawn as it is; every other icon is one colour and takes the tab's tint.
     */
    val selectedKeepsColors: Boolean = false,
) {
    Home(R.string.tab_home, R.drawable.ic_tab_home),
    Nodes(R.string.tab_nodes, R.drawable.ic_tab_nodes, R.drawable.ic_tab_nodes_selected, selectedKeepsColors = true),
    Inbox(R.string.tab_inbox, R.drawable.ic_tab_inbox),
    Search(R.string.tab_search, R.drawable.ic_tab_search),
    Profile(R.string.tab_profile, R.drawable.ic_tab_me),
}
