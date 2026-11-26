package com.nodeloc.app.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.Award
import com.composables.icons.lucide.Ban
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.FilePen
import com.composables.icons.lucide.Flag
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.TrendingUp
import com.composables.icons.lucide.Trophy
import com.composables.icons.lucide.User
import com.composables.icons.lucide.UserPlus
import com.composables.icons.lucide.WifiOff
import com.composables.icons.lucide.Zap
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.BadgeTitleText
import com.nodeloc.app.core.design.BlockUserDialog
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FilterPill
import com.nodeloc.app.core.design.FilterPillRow
import com.nodeloc.app.core.design.FlairBadge
import com.nodeloc.app.core.design.FloatingHeaderBar
import com.nodeloc.app.core.design.GuestAvatar
import com.nodeloc.app.core.design.GuestLoginButton
import com.nodeloc.app.core.design.HairLine
import com.nodeloc.app.core.design.HeaderGroup
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.PillTone
import com.nodeloc.app.core.design.PrimaryButton
import com.nodeloc.app.core.design.SecondaryButton
import com.nodeloc.app.core.design.pressScale
import com.nodeloc.app.core.design.rememberPressSource
import com.nodeloc.app.core.design.PullToRefreshBox
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.RemoteImage
import com.nodeloc.app.core.design.SkeletonBox
import com.nodeloc.app.core.design.SkeletonLine
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.StatusPill
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.floatingHeaderInset
import com.nodeloc.app.core.design.isScrolledPastTop
import com.nodeloc.app.core.design.rememberPullToRefreshState
import com.nodeloc.app.core.design.rememberTapSafeOverscroll
import com.nodeloc.app.core.design.requestScrollToTop
import com.nodeloc.app.core.design.skeletonPulsing
import com.nodeloc.app.core.model.PointsHistoryEntry
import com.nodeloc.app.core.model.UserActionItem
import com.nodeloc.app.core.model.UserProfile
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.network.isOfflineError
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.feature.feed.RowsSkeleton
import com.nodeloc.app.feature.moderation.ModTopBar
import com.nodeloc.app.feature.nav.AppViewModel
import com.nodeloc.app.feature.nav.Navigator
import com.nodeloc.app.feature.nav.Route
import java.time.ZoneId

/** 我的 — the signed-in user's own profile, or the guest placeholder. */
@Composable
fun ProfileScreen(
    app: AppViewModel,
    navigator: Navigator,
    contentPadding: PaddingValues,
    onOpenDrawer: () -> Unit,
) {
    val isSignedIn by app.isSignedIn.collectAsState()
    val currentUser by app.currentUser.collectAsState()
    val viewModel: ProfileViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()

    // Bound unconditionally: the view model falls back to the stored session
    // when the current user has not been fetched yet, and reports when it
    // cannot resolve a name at all.
    LaunchedEffect(currentUser?.username, isSignedIn) {
        if (isSignedIn) viewModel.bind(currentUser?.username)
    }

    Box(Modifier.fillMaxSize().background(Nocturne.bg)) {
        if (!isSignedIn) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GuestAvatar(size = 72.dp)
                Spacer(Modifier.height(Space.s4))
                Text(stringResource(R.string.profile_guest), style = Type.body(15), color = Nocturne.muted(0.6f))
                Spacer(Modifier.height(Space.s4))
                GuestLoginButton(onClick = { navigator.openAuth() })
            }
        } else {
            // See FeedScreen. The profile's own header is above the list, so a
            // reader who pulled is looking at that; landing back on it is also
            // what makes the refreshed counts visible.
            PullToRefreshBox(
                pullState,
                onRefresh = {
                    viewModel.refresh()
                    listState.requestScrollToTop()
                },
            ) {
                ProfileBody(
                    state = state,
                    listState = listState,
                    contentPadding = PaddingValues(
                        top = floatingHeaderInset,
                        bottom = contentPadding.calculateBottomPadding(),
                    ),
                    isOwn = true,
                    isSignedIn = true,
                    navigator = navigator,
                    viewModel = viewModel,
                    onCheckin = {
                        // A claimed day is answered from the device. The
                        // endpoint rate-limits every attempt and only forgives
                        // a successful one, so asking it something already
                        // known costs the account its next tries.
                        if (state.checkedInToday) ToastCenter.show(R.string.checkin_done) else viewModel.checkin()
                    },
                )
            }
        }

        FloatingHeaderBar(
            scrolled = listState.isScrolledPastTop(threshold = 0),
            leading = { com.nodeloc.app.feature.nav.DrawerMenuButton(onClick = onOpenDrawer) },
            centerVisible = listState.isScrolledPastTop(threshold = 120),
            center = {
                HeaderGroup {
                    Text(
                        state.profile?.username.orEmpty(),
                        style = Type.body(14, FontWeight.SemiBold),
                        color = Nocturne.headerText,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                }
            },
            trailing = {
                HeaderIconButton(ImageVector.vectorResource(R.drawable.ic_me_settings), stringResource(R.string.common_settings), onClick = { navigator.openSettings() })
            },
        )
    }
}

/** Someone else's profile: the same page, with message and follow in place of edit and check-in. */
@Composable
fun PublicProfileScreen(username: String, app: AppViewModel, navigator: Navigator) {
    val viewModel: ProfileViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val isSignedIn by app.isSignedIn.collectAsState()
    val listState = rememberLazyListState()
    var menuOpen by remember { mutableStateOf(false) }
    var notifyMenuOpen by remember { mutableStateOf(false) }
    var blocking by remember { mutableStateOf(false) }
    val reportTitle = stringResource(R.string.profile_report_title, username)

    if (blocking) {
        BlockUserDialog(
            username,
            onDismiss = { blocking = false },
            // Nothing left on this page is worth staying for once the person
            // on it is blocked.
            onBlocked = { navigator.back() },
        )
    }

    LaunchedEffect(username) { viewModel.bind(username) }

    // The design's plain Material bar over a short cover band, not the own
    // page's floating bar: here the person is the content.
    Column(Modifier.fillMaxSize().background(Nocturne.bg)) {
        ModTopBar(
            title = if (listState.isScrolledPastTop(threshold = 120)) username else "",
            onBack = navigator::back,
            actions = {
                // What the website offers on someone's page beyond the 私信
                // button: how loudly they reach you, reporting, blocking.
                Box {
                    HeaderIconButton(
                        Lucide.EllipsisVertical,
                        stringResource(R.string.viewer_more),
                        onClick = { menuOpen = true },
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_notification_level)) },
                            leadingIcon = { Icon(Lucide.Bell, null, Modifier.size(18.dp)) },
                            onClick = {
                                menuOpen = false
                                notifyMenuOpen = true
                            },
                        )
                        // Discourse has no flag for a person — only for a post
                        // — and its own advice for one is to write to the
                        // moderators. So that is what this opens, addressed and
                        // titled, rather than a reason list the server would
                        // have nowhere to put.
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(R.string.profile_report), color = Nocturne.danger)
                            },
                            leadingIcon = { Icon(Lucide.Flag, null, Modifier.size(18.dp)) },
                            onClick = {
                                menuOpen = false
                                navigator.openCompose(
                                    pmRecipient = MODERATORS_GROUP,
                                    prefillTitle = reportTitle,
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(R.string.profile_block), color = Nocturne.danger)
                            },
                            leadingIcon = { Icon(Lucide.Ban, null, Modifier.size(18.dp)) },
                            onClick = {
                                menuOpen = false
                                blocking = true
                            },
                        )
                    }

                    // The three levels replace the menu they came from rather
                    // than nesting inside it: a submenu at the edge of the
                    // screen opens off it.
                    DropdownMenu(expanded = notifyMenuOpen, onDismissRequest = { notifyMenuOpen = false }) {
                        listOf(
                            Triple("normal", R.string.profile_notify_normal, R.string.profile_notify_normal_detail),
                            Triple("mute", R.string.profile_notify_muted, R.string.profile_notify_muted_detail),
                            Triple("ignore", R.string.profile_notify_ignored, R.string.profile_notify_ignored_detail),
                        ).forEach { (level, label, detail) ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            stringResource(label),
                                            style = Type.body(14, FontWeight.Medium),
                                            color = Nocturne.text,
                                        )
                                        Text(
                                            stringResource(detail),
                                            style = Type.body(11),
                                            color = Nocturne.muted(0.45f),
                                            modifier = Modifier.padding(top = 2.dp),
                                        )
                                    }
                                },
                                onClick = {
                                    notifyMenuOpen = false
                                    viewModel.setNotificationLevel(level)
                                },
                            )
                        }
                    }
                }
            },
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            ProfileBody(
                state = state,
                listState = listState,
                contentPadding = PaddingValues(),
                isOwn = false,
                isSignedIn = isSignedIn,
                navigator = navigator,
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun ProfileBody(
    state: ProfileState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    isOwn: Boolean,
    isSignedIn: Boolean,
    navigator: Navigator,
    viewModel: ProfileViewModel,
    onCheckin: () -> Unit = {},
) {
    val profile = state.profile

    // Nothing on this page can be drawn from a half-loaded profile: the header
    // is one block of the user's own things, so it either arrives or it is a
    // shape. Empty text and "--" counters read as an account with nothing in it.
    //
    // Keyed on the absence of the profile rather than on `isLoading`, because
    // the load cannot even start until the stored session has been read back —
    // and it is exactly that gap the empty header used to show through.
    if (profile == null) {
        if (state.error != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val offline = state.error.isOfflineError()
                EmptyStateView(
                    icon = if (offline) Lucide.WifiOff else Lucide.User,
                    title = stringResource(if (offline) R.string.error_offline else R.string.error_action_failed),
                    retryLabel = stringResource(R.string.common_retry),
                    onRetry = viewModel::retry,
                )
            }
        } else {
            ProfileSkeleton(Modifier.padding(contentPadding))
        }
        return
    }

    val context = LocalContext.current
    val tabs = if (isOwn) ActivityTab.own else ActivityTab.public
    val displayName = profile.name?.takeIf { it.isNotBlank() } ?: profile.username
    val openNode: (String) -> Unit = { slug -> navigator.openUrl(context, "${DiscourseConfig.BASE_URL}/n/$slug") }
    val share: (String, String?) -> Unit = { path, title -> navigator.share(context, DiscourseConfig.BASE_URL + path, title) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        overscrollEffect = rememberTapSafeOverscroll(),
    ) {
        item(key = "header") {
            ProfileHeader(
                state = state,
                profile = profile,
                isOwn = isOwn,
                navigator = navigator,
                onToggleFollow = {
                    if (isSignedIn) viewModel.toggleFollow() else navigator.openAuth()
                },
                onCheckin = onCheckin,
                onMessage = {
                    when {
                        !isSignedIn -> navigator.openAuth()
                        // Their privacy setting or a block closes it. Said
                        // here, in the server's words, rather than after a
                        // first message has been typed and refused.
                        profile.canMessage == false ->
                            profile.messageNote?.let(ToastCenter::show) ?: ToastCenter.show(R.string.profile_message_closed)
                        else -> viewModel.openChatWith { navigator.openChat(it) }
                    }
                },
            )
        }

        item(key = "tabs") {
            FilterPillRow(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 10.dp)) {
                tabs.forEach { tab ->
                    FilterPill(
                        text = stringResource(tab.labelRes),
                        selected = tab == state.tab,
                        onClick = { viewModel.selectTab(tab) },
                    )
                }
            }
            HairLine()
        }

        val tab = state.tab
        when {
            tab == ActivityTab.About -> aboutItems(state, profile)
            tab == ActivityTab.Achievements -> showcaseItems(state.achievementBadges)
            tab.isPrivateFor(isOwn) -> item(key = "private") { PrivateListState(profile.username) }
            state.isLoadingTab -> item(key = "loading") { RowsSkeleton(rows = 5, avatar = false) }
            state.tabError != null -> item(key = "error") {
                val offline = state.tabError.isOfflineError()
                EmptyStateView(
                    icon = if (offline) Lucide.WifiOff else Lucide.CircleAlert,
                    title = stringResource(
                        if (offline) R.string.error_offline else R.string.profile_tab_load_failed,
                    ),
                    retryLabel = stringResource(R.string.common_retry),
                    onRetry = { viewModel.selectTab(state.tab, force = true) },
                )
            }
            tab == ActivityTab.Points -> pointsItems(state, navigator)
            tab == ActivityTab.Bookmarks -> bookmarkItems(state, navigator, viewModel::loadMoreActivity)
            tab == ActivityTab.Topics -> {
                if (state.topics.isEmpty()) {
                    item(key = "empty") { ActivityEmpty(stringResource(R.string.me3_empty_posts)) }
                } else {
                    // Indexed: a topic that moves between pages while the
                    // reader scrolls arrives twice, and a duplicate key is a
                    // crash rather than a glitch.
                    itemsIndexed(state.topics) { index, topic ->
                        if (index == state.topics.lastIndex) {
                            LaunchedEffect(tab, state.topics.size) { viewModel.loadMoreActivity() }
                        }
                        TopicRow(topic, state, isSignedIn, navigator, viewModel, openNode, share)
                    }
                }
            }
            else -> {
                // 概览 and 评论: the posts list, openers drawn as topics.
                val rows = if (tab == ActivityTab.Comments) state.posts.filter { it.postNumber > 1 } else state.posts
                if (rows.isEmpty()) {
                    item(key = "empty") {
                        ActivityEmpty(
                            stringResource(if (tab == ActivityTab.Comments) R.string.me3_empty_comments else R.string.common_empty),
                        )
                    }
                } else {
                    val byId = state.topicsById
                    val opened = state.openedTopics
                    itemsIndexed(rows) { index, post ->
                        if (index == rows.lastIndex) {
                            LaunchedEffect(tab, rows.size) { viewModel.loadMoreActivity() }
                        }
                        val summary = byId[post.topicId]
                        if (post.postNumber == 1) {
                            if (summary != null) {
                                TopicRow(summary, state, isSignedIn, navigator, viewModel, openNode, share)
                            } else {
                                val saved = state.saved[post.id] ?: false
                                TopicActivityRow(
                                    title = post.topicTitle.orEmpty(),
                                    nodeSlug = post.nodeSlug,
                                    nodeName = post.nodeName,
                                    createdAt = post.createdAt,
                                    imageUrl = null,
                                    vote = null,
                                    replyCount = null,
                                    saved = saved,
                                    onOpen = { navigator.openTopic(post.topicId) },
                                    onNode = post.nodeSlug?.let { { openNode(it) } },
                                    onVote = null,
                                    onShare = { share("/t/${post.topicId}", post.topicTitle) },
                                    onSave = if (isSignedIn) ({ viewModel.toggleSave(post.id, saved) }) else null,
                                )
                            }
                        } else {
                            CommentActivityRow(
                                author = displayName,
                                isOp = post.topicId in opened,
                                nodeSlug = post.nodeSlug,
                                nodeName = post.nodeName,
                                topicTitle = post.topicTitle,
                                createdAt = post.createdAt,
                                excerpt = post.excerpt,
                                onOpen = { navigator.openTopic(post.topicId, post.postNumber) },
                                onNode = post.nodeSlug?.let { { openNode(it) } },
                                onShare = { share("/t/${post.topicId}/${post.postNumber}", post.topicTitle) },
                            )
                        }
                    }
                }
            }
        }

        if (state.isLoadingMoreTab) {
            item(key = "more") {
                Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                    NodelocLoader(height = 22.dp)
                }
            }
        }
    }
}

@Composable
private fun TopicRow(
    topic: ApiUserTopicRow,
    state: ProfileState,
    isSignedIn: Boolean,
    navigator: Navigator,
    viewModel: ProfileViewModel,
    openNode: (String) -> Unit,
    share: (String, String?) -> Unit,
) {
    val postId = topic.firstPostId
    val saved = postId?.let { state.saved[it] } ?: false
    TopicActivityRow(
        title = topic.title.orEmpty(),
        nodeSlug = topic.nodeSlug,
        nodeName = topic.nodeName,
        createdAt = topic.createdAt,
        // An adult topic's picture stays folded here as it does in the feed.
        imageUrl = topic.imageUrl?.takeIf { !topic.adult },
        vote = state.votes[topic.id] ?: TopicVote(voteOf(topic.viewerVote), topic.voteScore),
        replyCount = topic.replyCount,
        saved = saved,
        onOpen = { navigator.openTopic(topic.id) },
        onNode = topic.nodeSlug?.let { { openNode(it) } },
        onVote = if (postId == null) null else { direction ->
            if (isSignedIn) viewModel.vote(topic, direction) else navigator.openAuth()
        },
        onShare = { share("/t/${topic.id}", topic.title) },
        onSave = if (postId != null && isSignedIn) ({ viewModel.toggleSave(postId, saved) }) else null,
    )
}

// ------------------------------------------------------------------ header

/** The member's own cover when they have one; the site's night colour when not. */
@Composable
private fun Banner(profile: UserProfile, modifier: Modifier) {
    val banner = DiscourseConfig.absoluteUrl(profile.profileBackgroundUploadUrl ?: profile.cardBackgroundUploadUrl)
    if (banner != null) {
        RemoteImage(banner, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(Nocturne.accent900))
    }
}

/**
 * The profile head, one layout for both pages since the 2026-11 v3 boards: a
 * short cover, the avatar breaking out of it with the page's two actions
 * beside it (编辑资料 / 签到 on your own, 私信 / 关注 on someone else's), then
 * name, handle and join month, bio, three figures, the energy line and the
 * achievements strip — each a quiet grey block.
 */
@Composable
private fun ProfileHeader(
    state: ProfileState,
    profile: UserProfile,
    isOwn: Boolean,
    navigator: Navigator,
    onToggleFollow: () -> Unit,
    onCheckin: () -> Unit,
    onMessage: () -> Unit,
) {
    val tier = state.tiers.level(profile.trustLevel)
    val privileges by ServiceLocator.get.moderation.privileges.collectAsState()
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(114.dp)) {
            Banner(profile, Modifier.fillMaxWidth().height(64.dp))
            Box(
                Modifier
                    .padding(start = 16.dp, top = 32.dp)
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Nocturne.bg)
                    .padding(3.dp),
            ) {
                RemoteAvatar(
                    DiscourseConfig.avatarUrl(profile.avatarTemplate, 240),
                    profile.username.take(1).uppercase().ifEmpty { "?" },
                    size = 66.dp,
                )
            }
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isOwn) {
                    SecondaryButton(stringResource(R.string.me3_edit_profile)) {
                        navigator.openSettingsRoute(Route.ProfileEdit)
                    }
                    // Greyed once the day is claimed — spent, not broken. Still
                    // live underneath: the tap is what says *why* it is grey,
                    // and costs nothing, since the answer comes from the device.
                    if (state.checkedInToday) {
                        SecondaryButton(stringResource(R.string.me2_checked_in), modifier = Modifier.widthIn(min = 84.dp), onClick = onCheckin)
                    } else {
                        PrimaryButton(
                            stringResource(R.string.checkin_action),
                            modifier = Modifier.widthIn(min = 84.dp),
                            enabled = !state.isCheckingIn,
                            onClick = onCheckin,
                        )
                    }
                } else {
                    // Straight into the conversation: private messages are
                    // chats on the 2.0 server, and the compose screen this
                    // used to open wrote a topic-style message it no longer
                    // has. The conversation itself starts with the first line
                    // sent; opening it does not create an empty one.
                    SecondaryButton(stringResource(R.string.profile_message), onClick = onMessage)
                    if (state.isFollowing) {
                        SecondaryButton(stringResource(R.string.profile_following), modifier = Modifier.widthIn(min = 84.dp), onClick = onToggleFollow)
                    } else {
                        PrimaryButton(stringResource(R.string.profile_follow), modifier = Modifier.widthIn(min = 84.dp), onClick = onToggleFollow)
                    }
                }
            }
        }

        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
            // Wraps rather than truncating: on your own page the tier and role
            // ride beside the name (the 2026-11 v292 board), and a long name
            // pushes them to a second line instead of eating into itself.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    profile.name?.takeIf { it.isNotBlank() } ?: profile.username,
                    style = Type.heading(22, FontWeight.Bold),
                    color = Nocturne.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (tier != null) TierBadge(tier)
                // Your own role comes from your permissions, which know about
                // node moderators the profile flags do not; someone else's
                // nodes come with their profile as `owned_nodes`.
                val admin = profile.admin == true || (isOwn && privileges.isAdmin)
                val moderator = profile.moderator == true || (isOwn && privileges.role == "moderator")
                val owned = profile.ownedNodes
                when {
                    admin -> StatusPill(stringResource(R.string.profile_admin), PillTone.Accent)
                    moderator -> StatusPill(stringResource(R.string.profile_moderator), PillTone.Accent)
                    owned.size == 1 -> StatusPill(stringResource(R.string.me4_node_owner_of, owned.first()), PillTone.Accent)
                    owned.isNotEmpty() || (isOwn && privileges.nodes.isNotEmpty()) ->
                        StatusPill(stringResource(R.string.me4_node_owner), PillTone.Accent)
                }
            }
            Text(
                listOfNotNull("u/${profile.username}", joinedLine(profile.createdAt)).joinToString(" · "),
                style = Type.body(13),
                color = Nocturne.textSecondary,
            )
            profile.bioExcerpt?.let { com.nodeloc.app.core.html.plainTextFromHtml(it) }?.trim()?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = Type.body(14), color = Nocturne.text, modifier = Modifier.padding(top = 6.dp))
            }

            // The badge title and group flair the member chose: not on the
            // board, but their own choice of how to be seen, so they stay. A
            // title that merely repeats the tier is the server filling a
            // blank, and is dropped.
            val title = profile.title?.takeIf { it.isNotBlank() && it != tier?.name }
            if (title != null || profile.flairUrl != null || profile.flairName != null) {
                FlowRow(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    title?.let { BadgeTitleText(it, size = 13) }
                    FlairBadge(
                        flairUrl = profile.flairUrl,
                        flairName = profile.flairName,
                        backgroundColor = profile.flairBgColor,
                        foregroundColor = profile.flairColor,
                    )
                }
            }

            Row(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Nocturne.surface)
                    .padding(4.dp),
            ) {
                val summary = state.summary
                val topics = (summary?.topicCount ?: profile.topicCount)?.toLong()
                val posts = (summary?.postCount ?: profile.postCount)?.toLong()
                // 贡献 is topics and replies together, as the web counts it.
                val contribution = if (topics == null && posts == null) null else (topics ?: 0) + (posts ?: 0)
                val reputation = if (isOwn) state.reputation?.reputation?.toLong() else state.publicReputation
                // Your own balance comes from the points API; someone else's
                // from the board when they are on its first page, otherwise
                // from the website's hover card.
                val balance = if (isOwn) state.pointsTotal?.toLong() ?: state.boardRow?.balance
                else state.boardRow?.balance ?: state.publicEnergy
                // Four figures on both pages since v292. On your own the ones
                // with a page behind them are the way there: the shortcut grid
                // they replaced is gone, so 声望 is how 升级进度 is reached.
                Figure(
                    stringResource(R.string.profile_reputation),
                    reputation,
                    Modifier.weight(1f),
                    padded = true,
                    onClick = if (isOwn) ({ navigator.openUpgradeProgress(profile.username) }) else null,
                )
                Figure(
                    stringResource(R.string.me2_sc_energy),
                    balance,
                    Modifier.weight(1f),
                    padded = true,
                    onClick = when {
                        isOwn -> ({ navigator.openEnergyHistory() })
                        // Only for someone the board actually lists; anyone else would not be found there.
                        state.boardRow != null -> ({ navigator.openWealthRank() })
                        else -> null
                    },
                )
                Figure(stringResource(R.string.me2_contribution), contribution, Modifier.weight(1f), padded = true)
                Figure(stringResource(R.string.me2_followers_label), profile.totalFollowers?.toLong(), Modifier.weight(1f), padded = true)
            }

            Row(Modifier.padding(top = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val unlocked = state.achievementsUnlocked
                val total = state.achievementsTotal
                // Yours: the latest unlocks. Someone else's: what they pinned,
                // the only ones they let be seen; the count covers the rest.
                StackCard(
                    title = stringResource(R.string.me2_sc_achievements),
                    count = when {
                        unlocked != null && total != null -> "$unlocked / $total"
                        unlocked != null -> unlocked.toString()
                        else -> null
                    },
                    modifier = Modifier.weight(1f),
                    onClick = { if (isOwn) navigator.openAchievements() else navigator.openAchievements(profile.username) },
                    shown = state.achievementBadges.size,
                    overflow = (unlocked ?: 0) - state.achievementBadges.size,
                ) { index -> AchievementDisc(state.achievementBadges[index], 40.dp) }

                val nodes = if (isOwn) state.joinedNodes.orEmpty() else frequentNodes(state, profile)
                val shownNodes = minOf(nodes.size, STACK_NODES)
                StackCard(
                    title = stringResource(R.string.me4_frequent_nodes),
                    count = if (isOwn && state.joinedNodes == null) null else stringResource(R.string.me4_nodes_count, nodes.size),
                    modifier = Modifier.weight(1f),
                    onClick = if (isOwn) ({ navigator.openNodes() }) else null,
                    shown = shownNodes,
                    overflow = nodes.size - shownNodes,
                ) { index -> com.nodeloc.app.feature.node.NodeMark(nodes[index], 40.dp) }
            }
        }
    }
}

/**
 * Someone else's 常去节点. The server keeps no visit history it would share,
 * so this is where their topics and replies went, busiest first, with the
 * nodes they own ahead of all of it — a member's own node is where they are.
 */
@Composable
private fun frequentNodes(state: ProfileState, profile: UserProfile): List<com.nodeloc.app.core.model.NodeSummary> =
    remember(state.posts, state.topics, state.nodeDirectory, profile.ownedNodes) {
        val counts = (state.posts.mapNotNull { it.nodeSlug } + state.topics.mapNotNull { it.nodeSlug })
            .groupingBy { it }
            .eachCount()
        val slugs = (profile.ownedNodes + counts.entries.sortedByDescending { it.value }.map { it.key }).distinct()
        slugs.mapNotNull { state.nodeDirectory[it] }
    }

/** "2022 年 9 月加入": the month, in the reader's own date order. */
@Composable
private fun joinedLine(createdAt: String?): String? {
    val start = DiscourseFormat.instant(createdAt) ?: return null
    val month = remember(start) {
        java.time.format.DateTimeFormatter
            .ofPattern(android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "yMMM"))
            .format(start.atZone(ZoneId.systemDefault()))
    }
    return stringResource(R.string.me2_joined_at, month)
}

/**
 * A figure, and on your own page a way into the page behind it. The cell is
 * padded either way so the four line up whether or not they press.
 */
@Composable
private fun Figure(label: String, value: Long?, modifier: Modifier, padded: Boolean = false, onClick: (() -> Unit)? = null) {
    val press = rememberPressSource()
    Column(
        modifier
            .then(if (onClick != null) Modifier.pressScale(press) else Modifier)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = press, indication = ripple(), role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .then(if (padded) Modifier.padding(8.dp) else Modifier),
    ) {
        Text(value?.let(::formatCount) ?: "--", style = Type.heading(18, FontWeight.ExtraBold), color = Nocturne.text, maxLines = 1)
        Text(label, style = Type.body(12), color = Nocturne.textSecondary, maxLines = 1)
    }
}

@Composable
private fun AchievementDisc(item: ApiAchItem, size: androidx.compose.ui.unit.Dp) {
    val (ground, ink) = achTierColors(item.tier)
    Box(
        Modifier.size(size).clip(CircleShape).background(ground),
        contentAlignment = Alignment.Center,
    ) {
        Icon(achievementIcon(item.icon), item.name, tint = ink, modifier = Modifier.size(size * 0.5f))
    }
}

/** Joined nodes drawn before the "+N" coin; the card is half the page wide, and four is what fits. */
private const val STACK_NODES = 4

/** The tier beside your name: its glyph and name in the site's own colours for it. */
@Composable
private fun TierBadge(tier: ApiTierInfo) {
    val (ground, ink) = tier.colors(Nocturne.isDark) ?: (Nocturne.infoBg to Nocturne.info)
    Row(
        Modifier
            .height(24.dp)
            .clip(CircleShape)
            .background(ground)
            .border(1.dp, ink.copy(alpha = 0.3f), CircleShape)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(ImageVector.vectorResource(tierIconRes(tier.level)), null, tint = ink, modifier = Modifier.size(14.dp))
        Text(tier.name.orEmpty(), style = Type.body(12, FontWeight.Bold), color = ink, maxLines = 1)
    }
}

/**
 * 成就 and 常去节点 on your own page: a title with its count, then the first
 * few as overlapping coins and a "+N" for the rest. Each coin is ringed in the
 * card's own grey, which is what separates it from the one it overlaps.
 */
@Composable
private fun StackCard(
    title: String,
    count: String?,
    shown: Int,
    overflow: Int,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    coin: @Composable (Int) -> Unit,
) {
    val press = rememberPressSource()
    Column(
        modifier
            .pressScale(press)
            .clip(RoundedCornerShape(16.dp))
            .background(Nocturne.surface)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = press, indication = ripple(), role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = Type.body(14, FontWeight.Bold), color = Nocturne.text, maxLines = 1)
            count?.let { Text(it, style = Type.body(12), color = Nocturne.textSecondary, maxLines = 1) }
        }
        Row(
            Modifier.padding(top = 8.dp).height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((-14).dp),
        ) {
            val total = shown + if (overflow > 0) 1 else 0
            repeat(shown) { index ->
                Box(
                    Modifier
                        .zIndex((total - index).toFloat())
                        .clip(CircleShape)
                        .background(Nocturne.surface)
                        .padding(2.dp),
                ) { coin(index) }
            }
            if (overflow > 0) {
                Box(
                    Modifier
                        // Above its neighbour, unlike the coins: under it, the
                        // overlap ate the "+" of a two-digit count.
                        .zIndex((total + 1).toFloat())
                        .clip(CircleShape)
                        .background(Nocturne.surface)
                        .padding(2.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Nocturne.bg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+$overflow", style = Type.body(13, FontWeight.Bold), color = Nocturne.text, maxLines = 1)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ tabs

private fun LazyListScope.showcaseItems(badges: List<ApiAchItem>) {
    if (badges.isEmpty()) {
        item { EmptyStateView(Lucide.Award, stringResource(R.string.me2_showcase_empty)) }
        return
    }
    itemsIndexed(badges, key = { _, it -> "ach-${it.key}" }) { _, item ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AchievementDisc(item, 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.name, style = Type.body(15, FontWeight.SemiBold), color = Nocturne.text)
                    val (ground, ink) = achTierColors(item.tier)
                    Box(
                        Modifier.height(16.dp).clip(CircleShape).background(ground).padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(achTierLabel(item.tier), style = Type.body(10, FontWeight.Bold), color = ink) }
                }
                (item.description ?: item.how)?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = Type.body(13), color = Nocturne.textSecondary)
                }
            }
        }
        HairLine()
    }
}

private fun LazyListScope.aboutItems(state: ProfileState, profile: UserProfile) {
    item(key = "about") {
        val rows = buildList {
            profile.bioExcerpt?.let { com.nodeloc.app.core.html.plainTextFromHtml(it) }?.takeIf { it.isNotBlank() }
                ?.let { add(R.string.me2_about_bio to it) }
            state.tiers.level(profile.trustLevel)?.name?.let { add(R.string.me2_about_tier to it) }
            profile.location?.takeIf { it.isNotBlank() }?.let { add(R.string.me2_about_location to it) }
            profile.website?.takeIf { it.isNotBlank() }?.let { add(R.string.me2_about_website to it) }
            profile.createdAt?.let { add(R.string.me2_about_joined to DiscourseFormat.day(it)) }
            profile.lastSeenAt?.let { add(R.string.me2_about_last_seen to DiscourseFormat.relative(it)) }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            rows.forEach { (label, value) ->
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(stringResource(label), style = Type.body(12), color = Nocturne.textSecondary)
                    Text(value, style = Type.body(15), color = Nocturne.text)
                }
            }
        }
    }
}

private fun LazyListScope.pointsItems(state: ProfileState, navigator: Navigator) {
    item(key = "points-summary") {
        PointsSummary(
            balance = state.pointsTotal?.toLong() ?: state.boardRow?.balance,
            rank = state.boardRow?.rank,
            onRank = navigator::openWealthRank,
            onLedger = navigator::openEnergyHistory,
        )
    }
    if (state.points.isEmpty()) {
        item(key = "points-empty") { ActivityEmpty(stringResource(R.string.profile_empty_points)) }
    } else {
        // Indexed: the fields here are not unique — two "daily check-in +5"
        // entries with no timestamp produce the same key, and a duplicate key
        // is a crash rather than a glitch.
        itemsIndexed(state.points) { _, entry -> PointsRow(entry) }
    }
}

private fun LazyListScope.bookmarkItems(state: ProfileState, navigator: Navigator, onLoadMore: () -> Unit) {
    val rows = state.bookmarks
    if (rows.isEmpty()) {
        item(key = "saved-empty") { ActivityEmpty(stringResource(R.string.me3_empty_saved)) }
        return
    }
    // Indexed: every field of a bookmark row is nullable, and two with all of
    // them absent would collide as keys.
    itemsIndexed(rows) { index, action ->
        if (index == rows.lastIndex) {
            LaunchedEffect(ActivityTab.Bookmarks, rows.size) { onLoadMore() }
        }
        SavedRow(action) {
            navigator.openTopic(action.topicId ?: return@SavedRow, action.postNumber)
        }
    }
}

/** The 能量记录 tab's head: the balance, its place on the board, and the way to the full ledger. */
@Composable
private fun PointsSummary(balance: Long?, rank: Int?, onRank: () -> Unit, onLedger: () -> Unit) {
    FlowRow(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Nocturne.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f).widthIn(min = 120.dp)) {
            Text(stringResource(R.string.energy_current), style = Type.body(12), color = Nocturne.textSecondary)
            Text(balance?.let(::formatCount) ?: "--", style = Type.heading(24, FontWeight.ExtraBold), color = Nocturne.text)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.CenterVertically)) {
            rank?.let { SecondaryButton(stringResource(R.string.me2_board_rank, it), onClick = onRank) }
            PrimaryButton(stringResource(R.string.me2_full_ledger), onClick = onLedger)
        }
    }
}

/** The header's own shape: cover, avatar, name lines, the figures block and a few rows. */
@Composable
private fun ProfileSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(114.dp)) {
            Box(Modifier.fillMaxWidth().height(64.dp).background(Nocturne.neutral300))
            Box(
                Modifier.padding(start = 16.dp, top = 32.dp).size(72.dp).clip(CircleShape).background(Nocturne.bg).padding(3.dp),
            ) {
                SkeletonBox(Modifier.size(66.dp).skeletonPulsing(), corner = 33.dp)
            }
        }
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).skeletonPulsing(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SkeletonLine(Modifier.width(140.dp), height = 22.dp)
            SkeletonLine(Modifier.width(200.dp), height = 13.dp)
            SkeletonLine(widthFraction = 0.9f, height = 13.dp)
            SkeletonBox(Modifier.fillMaxWidth().height(64.dp), corner = 12.dp)
            SkeletonBox(Modifier.fillMaxWidth().height(48.dp), corner = 12.dp)
        }
        Spacer(Modifier.height(Space.s4))
        RowsSkeleton(rows = 4, avatar = false)
    }
}

/** A saved post: when, the topic it is in, and what it said. */
@Composable
private fun SavedRow(action: UserActionItem, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = action.topicId != null, onClick = onClick)
            .padding(horizontal = Space.page, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            listOfNotNull(action.username, DiscourseFormat.relative(action.createdAt).takeIf { it.isNotEmpty() }).joinToString(" · "),
            style = Type.body(12),
            color = Nocturne.textSecondary,
        )
        Text(
            action.title.orEmpty(),
            style = Type.body(16, FontWeight.Bold),
            color = Nocturne.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        action.excerpt?.takeIf { it.isNotBlank() }?.let {
            Text(
                com.nodeloc.app.core.html.plainTextFromHtml(it),
                style = Type.body(14),
                color = Nocturne.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HairLine()
}

/** One ledger line: what it was, when, and the amount in green or orange. */
@Composable
private fun PointsRow(entry: PointsHistoryEntry) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Space.page, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.description.orEmpty(), style = Type.body(14), color = Nocturne.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                DiscourseFormat.day(entry.createdAt ?: entry.date),
                style = Type.body(12),
                color = Nocturne.textSecondary,
            )
        }
        val points = (entry.points ?: 0).toLong()
        Text(
            formatSigned(points),
            style = Type.body(14, FontWeight.Bold),
            color = if (points >= 0) Nocturne.accent700 else Nocturne.danger,
        )
    }
    HairLine()
}

/**
 * Who a report about a person goes to.
 *
 * The group every Discourse install has, and the one its own guidance points
 * at: there is no endpoint that flags a user, only posts.
 */
private const val MODERATORS_GROUP = "moderators"
