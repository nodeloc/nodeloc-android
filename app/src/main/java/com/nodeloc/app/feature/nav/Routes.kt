package com.nodeloc.app.feature.nav

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.lucide.House
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.User
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Rows3
import com.composables.icons.lucide.MessageCircle
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
    @Serializable data object SettingsInterface
    @Serializable data object SettingsNotifications
    @Serializable data object SettingsPush
    @Serializable data object SettingsPostSource
    @Serializable data object SettingsPrivacy
    @Serializable data object SettingsEmail
    @Serializable data object SettingsSecurity
    @Serializable data object SettingsBlocked
    @Serializable data object SettingsAssociated
    @Serializable data object ProfileEdit

    @Serializable data object Auth

    /** The same flow, entered at the first signup step. */
    @Serializable data object AuthSignup
    @Serializable data object AuthLogin

    /** Signing in through the website, for a provider Discourse owns the keys to. */
    /**
     * The signup steps carry nothing.
     *
     * Route arguments are serialised into the back stack's saved state, which
     * the system writes to disk to survive process death — not where a
     * plaintext password goes. The draft lives in `AuthViewModel`, scoped to
     * the enclosing [Auth] entry, which is where every step already read it
     * from; the arguments were passed and never used.
     */
    @Serializable data object SignupEmail
    @Serializable data object SignupUsername
    @Serializable data object SignupPassword
    @Serializable data object SignupGender
    @Serializable data object SignupInterests
    @Serializable data class SignupActivation(val email: String)

    @Serializable data object Welcome
}

enum class TabItem(
    @StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
) {
    Home(R.string.tab_home, Lucide.House, Lucide.House),
    Nodes(R.string.tab_nodes, Lucide.Rows3, Lucide.Rows3),
    Inbox(R.string.tab_inbox, Lucide.MessageCircle, Lucide.MessageCircle),
    Search(R.string.tab_search, Lucide.Search, Lucide.Search),
    Profile(R.string.tab_profile, Lucide.User, Lucide.User),
}
