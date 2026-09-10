package com.nodeloc.app.feature.nav

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.network.DiscourseConfig
import androidx.navigation.NavOptionsBuilder

/**
 * Thin wrapper over the nav controller so screens depend on intent
 * ("open this topic") rather than on route construction. It also owns the one
 * URL-routing path, so a link behaves identically wherever it was tapped.
 */
class Navigator(private val controller: NavHostController) {

    fun back() {
        controller.popBackStack()
    }

    /**
     * Leaves the sign-in flow.
     *
     * Popping alone returns wherever it was opened from — and on first launch
     * that is the welcome screen, so a new account signed up and then landed
     * back on "get started", with the feed reachable only by choosing to browse
     * as a guest.
     */
    fun finishAuth() {
        controller.popBackStack()
        if (controller.currentBackStackEntry?.destination?.hasRoute(Route.Welcome::class) == true) {
            controller.navigate(Route.Home) { popUpTo(Route.Welcome) { inclusive = true } }
        }
    }

    /**
     * Pushes unless that destination is already on top.
     *
     * Every push here was bare, so a double tap on a feed row — or on the
     * login gate's button, or a notification arriving twice — put two
     * identical screens on the stack and took two presses of back to leave.
     */
    private fun push(route: Any) {
        controller.navigate(route) { launchSingleTop = true }
    }

    fun openTopic(topicId: Int, postNumber: Int? = null) = push(Route.Reader(topicId, postNumber))

    fun openNode(categoryId: Int, slug: String? = null) = push(Route.NodeDetail(categoryId, slug))

    /** Every node under one browse group — where "更多" goes. */
    fun openNodeGroup(categoryId: Int, name: String) = push(Route.NodeGroup(categoryId, name))

    fun openCreateNode() = push(Route.CreateNode)

    fun openCompose(
        categoryId: Int? = null,
        repostTopicId: Int? = null,
        prefillTitle: String? = null,
        pmRecipient: String? = null,
        editPostId: Int? = null,
    ) = push(Route.Compose(categoryId, repostTopicId, prefillTitle, pmRecipient, editPostId))

    fun openProfile(username: String) = push(Route.PublicProfile(username))

    fun openChat(channelId: Int, messageId: Int? = null, threadId: Int? = null) =
        push(Route.ChatConversation(channelId, messageId, threadId))

    fun openBrowser(url: String) = push(Route.Browser(url))

    fun openNodeSearch(slug: String) = push(Route.NodeSearch(slug))

    fun openUpgradeProgress(username: String) = push(Route.UpgradeProgress(username))

    fun openApps() = push(Route.Apps)

    fun openApp(slug: String) = push(Route.AppDetail(slug))

    fun openSettings() = push(Route.Settings)

    fun openSettingsRoute(route: Any) = push(route)

    /** [signup] starts the flow on the first signup step rather than the login form. */
    fun openAuth(signup: Boolean = false) = push(if (signup) Route.AuthSignup else Route.Auth)

    fun openInbox() = controller.navigate(Route.Inbox) {
        popUpTo(Route.Home) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    /**
     * Your own profile — the same tab the bar's last entry opens, reached from
     * places that show your avatar. Navigated like a tab rather than pushed, or
     * a reader opened three levels deep would leave your profile stacked on top
     * of a thread it has nothing to do with.
     */
    fun openMe() = controller.navigate(Route.Profile) {
        popUpTo(Route.Home) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    /** The nodes tab, reached from the drawer rather than the tab bar. */
    fun openNodes() = controller.navigate(Route.Nodes) {
        popUpTo(Route.Home) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    fun navigate(route: Any, builder: NavOptionsBuilder.() -> Unit = {}) = controller.navigate(route, builder)

    /**
     * Resolves any tapped URL. In-site links land on the native screen;
     * everything else opens the in-app browser, and only mailto/tel-style
     * schemes hand off to the system.
     */
    /**
     * A link the in-app browser will not load itself.
     *
     * The browser replaces itself rather than being pushed under the screen it
     * hands off to. Left on the stack it becomes a trap: back lands on a page
     * still sitting at that address, the WebView hands off again, and the
     * screen the user was trying to leave comes straight back — indis-
     * tinguishable from a back button that does nothing.
     *
     * Only for a destination inside the app. Handing a link to another app
     * leaves the browser where it is, because that is where returning from
     * that app belongs.
     */
    fun leaveBrowserFor(context: Context, url: String) {
        val destination = LinkRouter.resolve(url)
        val leavesThisApp = destination == null ||
            destination is LinkDestination.External ||
            destination == LinkDestination.Blocked
        // Popped before the push, which is what makes it a replacement: the
        // back stack has no way to remove an entry once something sits on it.
        if (!leavesThisApp) controller.popBackStack()
        openUrl(context, url)
    }

    fun openUrl(context: Context, url: String) {
        when (val destination = LinkRouter.resolve(url)) {
            is LinkDestination.Topic -> openTopic(destination.topicId, destination.postNumber)
            is LinkDestination.User -> openProfile(destination.username)
            // A node link without an id has no native screen to open. The
            // absolutised form, not the raw href: a relative one reaches the
            // browser as something it cannot load.
            // Discourse writes category links both ways: /c/{slug}/{id} and a
            // bare /c/{slug}. Only the first carries an id, so without the slug
            // lookup every bare one fell through to the browser — a WebView
            // where the native node screen was one map read away.
            is LinkDestination.Node -> {
                val categoryId = destination.categoryId
                    ?: ServiceLocator.get.siteRepository.categoryBySlug(destination.slug)?.id
                categoryId?.let { openNode(it, destination.slug) }
                    ?: DiscourseConfig.absoluteUrl(url)?.let(::openBrowser)
                    ?: Unit
            }
            is LinkDestination.Chat -> openChat(destination.channelId, destination.messageId)
            LinkDestination.GroupInbox -> openInbox()
            is LinkDestination.InAppBrowser -> openBrowser(destination.url)
            is LinkDestination.External -> runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, destination.url.toUri()))
            }
            // A tap that goes nowhere needs saying; a blank href does not.
            LinkDestination.Blocked -> ToastCenter.show(R.string.link_blocked)
            null -> Unit
        }
    }

    /** Share sheet, used by feed rows, the reader and the browser. */
    fun share(context: Context, url: String, title: String? = null) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, if (title != null) "$title\n$url" else url)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, context.getString(R.string.common_share))) }
    }
}
