package com.nodeloc.app.feature.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.nodeloc.app.R
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.feature.apps.AppDetailScreen
import com.nodeloc.app.feature.apps.AppsScreen
import com.nodeloc.app.feature.auth.AuthNavHost
import com.nodeloc.app.feature.auth.WelcomeScreen
import com.nodeloc.app.feature.chat.ChatConversationScreen
import com.nodeloc.app.feature.chat.InboxScreen
import com.nodeloc.app.feature.compose.ComposeScreen
import com.nodeloc.app.feature.feed.FeedScreen
import com.nodeloc.app.feature.media.WebPageScreen
import com.nodeloc.app.feature.node.CreateNodeScreen
import com.nodeloc.app.feature.node.NodeGroupScreen
import com.nodeloc.app.feature.node.NodeDetailScreen
import com.nodeloc.app.feature.node.NodesScreen
import com.nodeloc.app.feature.post.ReaderScreen
import com.nodeloc.app.feature.profile.ProfileScreen
import com.nodeloc.app.feature.profile.PublicProfileScreen
import com.nodeloc.app.feature.profile.UpgradeProgressScreen
import com.nodeloc.app.feature.search.NodeSearchScreen
import com.nodeloc.app.feature.search.SearchScreen
import com.nodeloc.app.feature.settings.AssociatedAccountsScreen
import com.nodeloc.app.feature.settings.EmailSettingsScreen
import com.nodeloc.app.feature.settings.InterfaceSettingsScreen
import com.nodeloc.app.feature.settings.NotificationPreferencesScreen
import com.nodeloc.app.feature.settings.PostSourceScreen
import com.nodeloc.app.feature.settings.PrivacySettingsScreen
import com.nodeloc.app.feature.settings.ProfileEditScreen
import com.nodeloc.app.feature.settings.PushSettingsScreen
import com.nodeloc.app.feature.settings.BlockedUsersScreen
import com.nodeloc.app.feature.settings.SecurityScreen
import com.nodeloc.app.feature.settings.SettingsScreen
import kotlinx.coroutines.launch

/**
 * Single-Activity host.
 *
 * Everything the iOS build draws as a stacked overlay is a navigation
 * destination here, so predictive back unwinds the reader → list → tab chain
 * without any bespoke gesture handling.
 */
@Composable
fun NodelocApp(startDestination: Any = Route.Home) {
    val navController = rememberNavController()
    val app: AppViewModel = viewModel()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val showsTabBar = backStackEntry.isTabDestination()
    val unread by app.unreadTotal.collectAsState()
    val isSignedIn by app.isSignedIn.collectAsState()

    LaunchedEffect(isSignedIn) {
        if (isSignedIn) app.refreshBadge()
    }

    val navigator = remember(navController) { Navigator(navController) }
    val context = LocalContext.current

    // A link that arrived as an intent — before the graph existed, or while it
    // was already up and a mini program handed one back.
    val pendingLink by com.nodeloc.app.MainActivity.pendingLink.collectAsState()
    LaunchedEffect(pendingLink) {
        pendingLink?.let { link ->
            com.nodeloc.app.MainActivity.consumePendingLink()
            navigator.openUrl(context, link)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = showsTabBar,
        drawerContent = {
            SidebarDrawer(
                onClose = { scope.launch { drawerState.close() } },
                onOpenNode = { navigator.openNode(it.id, it.slug); scope.launch { drawerState.close() } },
                onBrowseNodes = { navigator.openNodes(); scope.launch { drawerState.close() } },
                // Creating a node needs an account, so a guest meets the same
                // gate the nodes tab's own button raises.
                onCreateNode = {
                    if (isSignedIn) navigator.openCreateNode() else navigator.openAuth()
                    scope.launch { drawerState.close() }
                },
                onOpenApps = { navigator.openApps(); scope.launch { drawerState.close() } },
                onOpenApp = { navigator.openApp(it); scope.launch { drawerState.close() } },
            )
        },
    ) {
        Scaffold(
            containerColor = Nocturne.bg,
            bottomBar = {
                AnimatedVisibility(
                    visible = showsTabBar,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    BottomTabBar(navController, unread)
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().background(Nocturne.bg)) {
                NodelocNavHost(
                    navController = navController,
                    navigator = navigator,
                    app = app,
                    startDestination = startDestination,
                    contentPadding = padding,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                )
            }
        }
    }

    LoginGateSheet(app, onOpenAuth = { signup -> navigator.openAuth(signup = signup) })
}

@Composable
private fun BottomTabBar(navController: NavHostController, unread: Int) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    NavigationBar(containerColor = Nocturne.headerBg, tonalElevation = 0.dp0()) {
        TabItem.entries.forEach { tab ->
            val selected = backStackEntry.matches(tab)
            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(tab.route()) {
                        popUpTo(Route.Home) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    if (tab == TabItem.Inbox && unread > 0) {
                        BadgedBox(badge = { Badge { Text(if (unread > 99) "99+" else unread.toString()) } }) {
                            Icon(if (selected) tab.selectedIcon else tab.icon, stringResource(tab.labelRes))
                        }
                    } else {
                        Icon(if (selected) tab.selectedIcon else tab.icon, stringResource(tab.labelRes))
                    }
                },
                label = { Text(stringResource(tab.labelRes), style = Type.body(11)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Nocturne.accent,
                    selectedTextColor = Nocturne.accent,
                    // The accent on the glyph carries the selection; the pill behind
                    // it just crowds a five-item bar.
                    indicatorColor = Color.Transparent,
                    unselectedIconColor = Nocturne.muted(0.5f),
                    unselectedTextColor = Nocturne.muted(0.5f),
                ),
            )
        }
    }
}

private fun Int.dp0() = androidx.compose.ui.unit.Dp(0f)

private fun TabItem.route(): Any = when (this) {
    TabItem.Home -> Route.Home
    TabItem.Nodes -> Route.Nodes
    TabItem.Inbox -> Route.Inbox
    TabItem.Search -> Route.Search
    TabItem.Profile -> Route.Profile
}

private fun NavBackStackEntry?.matches(tab: TabItem): Boolean {
    val destination = this?.destination ?: return false
    return when (tab) {
        TabItem.Home -> destination.hasRoute(Route.Home::class)
        TabItem.Nodes -> destination.hasRoute(Route.Nodes::class)
        TabItem.Inbox -> destination.hasRoute(Route.Inbox::class)
        TabItem.Search -> destination.hasRoute(Route.Search::class)
        TabItem.Profile -> destination.hasRoute(Route.Profile::class)
    }
}

private fun NavBackStackEntry?.isTabDestination(): Boolean =
    TabItem.entries.any { matches(it) }

@Composable
private fun NodelocNavHost(
    navController: NavHostController,
    navigator: Navigator,
    app: AppViewModel,
    startDestination: Any,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    onOpenDrawer: () -> Unit,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier.fillMaxSize(),
        enterTransition = { slideInHorizontally { it / 6 } + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally { it / 6 } + fadeOut() },
    ) {
        composable<Route.Home> {
            FeedScreen(app, navigator, contentPadding, onOpenDrawer)
        }
        composable<Route.Nodes> {
            NodesScreen(app, navigator, contentPadding, onOpenDrawer)
        }
        composable<Route.Inbox> {
            InboxScreen(app, navigator, contentPadding, onOpenDrawer)
        }
        composable<Route.Search> {
            SearchScreen(app, navigator, contentPadding)
        }
        composable<Route.Profile> {
            ProfileScreen(app, navigator, contentPadding, onOpenDrawer)
        }

        composable<Route.Reader> { entry ->
            val route = entry.toRoute<Route.Reader>()
            ReaderScreen(route.topicId, route.postNumber, app, navigator)
        }
        composable<Route.NodeDetail> { entry ->
            val route = entry.toRoute<Route.NodeDetail>()
            NodeDetailScreen(route.categoryId, route.slug, app, navigator)
        }
        composable<Route.NodeGroup> { entry ->
            val route = entry.toRoute<Route.NodeGroup>()
            NodeGroupScreen(route.categoryId, route.name, app, navigator)
        }
        composable<Route.CreateNode> { CreateNodeScreen(navigator) }
        composable<Route.Compose> { entry ->
            val route = entry.toRoute<Route.Compose>()
            ComposeScreen(route, navigator)
        }
        composable<Route.PublicProfile> { entry ->
            PublicProfileScreen(entry.toRoute<Route.PublicProfile>().username, app, navigator)
        }
        composable<Route.UpgradeProgress> { entry ->
            UpgradeProgressScreen(entry.toRoute<Route.UpgradeProgress>().username, navigator)
        }
        composable<Route.ChatConversation> { entry ->
            val route = entry.toRoute<Route.ChatConversation>()
            ChatConversationScreen(route.channelId, route.messageId, route.threadId, navigator)
        }
        composable<Route.Browser> { entry ->
            WebPageScreen(entry.toRoute<Route.Browser>().url, navigator)
        }
        composable<Route.NodeSearch> { entry ->
            NodeSearchScreen(entry.toRoute<Route.NodeSearch>().slug, navigator)
        }

        composable<Route.Apps> { AppsScreen(navigator) }
        composable<Route.AppDetail> { entry ->
            AppDetailScreen(entry.toRoute<Route.AppDetail>().slug, navigator)
        }

        composable<Route.Settings> { SettingsScreen(app, navigator) }
        composable<Route.SettingsInterface> { InterfaceSettingsScreen(navigator) }
        composable<Route.SettingsNotifications> { NotificationPreferencesScreen(navigator) }
        composable<Route.SettingsPush> { PushSettingsScreen(navigator) }
        composable<Route.SettingsPostSource> { PostSourceScreen(navigator) }
        composable<Route.SettingsPrivacy> { PrivacySettingsScreen(navigator) }
        composable<Route.SettingsEmail> { EmailSettingsScreen(navigator) }
        composable<Route.SettingsSecurity> { SecurityScreen(navigator) }
        composable<Route.SettingsBlocked> { BlockedUsersScreen(navigator) }
        composable<Route.SettingsAssociated> { AssociatedAccountsScreen(navigator) }
        composable<Route.ProfileEdit> { ProfileEditScreen(navigator) }

        composable<Route.Auth> { AuthNavHost(app, navigator) }
        composable<Route.AuthSignup> { AuthNavHost(app, navigator, startAtSignup = true) }
        composable<Route.Welcome> {
            WelcomeScreen(
                // "Get started" is for people without an account; sending them
                // to the login form made them find the signup link themselves.
                onGetStarted = { navigator.openAuth(signup = true) },
                onLogin = { navigator.openAuth() },
                onBrowseAsGuest = {
                    navController.navigate(Route.Home) { popUpTo(Route.Welcome) { inclusive = true } }
                },
            )
        }
    }
}
