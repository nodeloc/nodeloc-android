package com.nodeloc.app.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Award
import com.composables.icons.lucide.Ban
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.CalendarHeart
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.FilePen
import com.composables.icons.lucide.Flag
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mail
import com.composables.icons.lucide.MessageCircle
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
import com.nodeloc.app.feature.nav.AppViewModel
import com.nodeloc.app.feature.nav.Navigator
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

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
                    contentPadding = contentPadding,
                    isOwn = true,
                    navigator = navigator,
                    onRetry = viewModel::retry,
                    onSelectTab = viewModel::selectTab,
                    onLoadMoreActivity = viewModel::loadMoreActivity,
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
            // Only over the signed-in header's dark band; a guest page is plain white
            // and the scrim would leave white icons on nothing.
            overMedia = isSignedIn,
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
                HeaderIconButton(Lucide.Settings, stringResource(R.string.common_settings), onClick = { navigator.openSettings() })
            },
        )
    }
}

/** Someone else's profile: the same header, with follow and message in place of the shortcuts. */
@Composable
fun PublicProfileScreen(username: String, app: AppViewModel, navigator: Navigator) {
    val viewModel: ProfileViewModel = viewModel()
    val state by viewModel.state.collectAsState()
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

    Box(Modifier.fillMaxSize().background(Nocturne.bg)) {
        ProfileBody(
            state = state,
            listState = listState,
            contentPadding = PaddingValues(),
            isOwn = false,
            navigator = navigator,
            onRetry = viewModel::retry,
            onSelectTab = viewModel::selectTab,
            onLoadMoreActivity = viewModel::loadMoreActivity,
            onToggleFollow = viewModel::toggleFollow,
        )

        FloatingHeaderBar(
            scrolled = listState.isScrolledPastTop(threshold = 0),
            overMedia = true,
            leading = { HeaderIconButton(Lucide.ArrowLeft, stringResource(R.string.common_back), onClick = navigator::back) },
            centerVisible = listState.isScrolledPastTop(threshold = 120),
            center = {
                HeaderGroup {
                    Text(
                        username,
                        style = Type.body(14, FontWeight.SemiBold),
                        color = Nocturne.headerText,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                }
            },
            trailing = {
                // What the website offers on someone's page: write to them,
                // chat, or decide how loudly they reach you.
                Box {
                    HeaderIconButton(
                        Lucide.EllipsisVertical,
                        stringResource(R.string.viewer_more),
                        onClick = { menuOpen = true },
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profile_chat)) },
                            leadingIcon = { Icon(Lucide.MessageCircle, null, Modifier.size(18.dp)) },
                            onClick = {
                                menuOpen = false
                                viewModel.openChatWith { navigator.openChat(it) }
                            },
                        )
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
    }
}

/**
 * The dark band the header card overlaps: the bar's own height plus as much
 * again as the design leaves above the card's middle.
 */
private val BandExtra = 62.dp

@Composable
private fun ProfileBody(
    state: ProfileState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    isOwn: Boolean,
    navigator: Navigator,
    onRetry: () -> Unit,
    onSelectTab: (ActivityTab) -> Unit,
    onLoadMoreActivity: () -> Unit,
    onToggleFollow: () -> Unit = {},
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
                    onRetry = onRetry,
                )
            }
        } else {
            ProfileSkeleton(Modifier.padding(bottom = contentPadding.calculateBottomPadding()))
        }
        return
    }

    val tabs = if (isOwn) ActivityTab.own else ActivityTab.public

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        overscrollEffect = rememberTapSafeOverscroll(),
    ) {
        item(key = "header") {
            Box(Modifier.fillMaxWidth()) {
                Banner(profile, Modifier.fillMaxWidth().height(floatingHeaderInset + BandExtra))
                HeaderCard(
                    state = state,
                    profile = profile,
                    isOwn = isOwn,
                    navigator = navigator,
                    onToggleFollow = onToggleFollow,
                    onCheckin = onCheckin,
                    onShowAchievements = { onSelectTab(ActivityTab.Achievements) },
                    modifier = Modifier.padding(
                        top = floatingHeaderInset + if (isOwn) 16.dp else 28.dp,
                        start = Space.page,
                        end = Space.page,
                    ),
                )
            }
        }

        if (isOwn) {
            item(key = "shortcuts") { Shortcuts(navigator, profile.username) }
        }

        item(key = "tabs") {
            FilterPillRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
                tabs.forEach { tab ->
                    FilterPill(
                        text = stringResource(
                            // The same list, named for whose it is: on your own
                            // page they are your posts, on someone else's they
                            // are that person's replies.
                            if (!isOwn && tab == ActivityTab.Posts) R.string.me2_replies else tab.labelRes,
                        ),
                        selected = tab == state.tab,
                        onClick = { onSelectTab(tab) },
                    )
                }
            }
        }

        when {
            state.tab == ActivityTab.About -> aboutItems(state, profile)
            state.tab == ActivityTab.Achievements -> showcaseItems(state.achievementBadges)
            state.isLoadingTab -> item { RowsSkeleton(rows = 5, avatar = false) }
            state.tabError != null -> item {
                val offline = state.tabError.isOfflineError()
                EmptyStateView(
                    icon = if (offline) Lucide.WifiOff else Lucide.CircleAlert,
                    title = stringResource(
                        if (offline) R.string.error_offline else R.string.profile_tab_load_failed,
                    ),
                    retryLabel = stringResource(R.string.common_retry),
                    onRetry = { onSelectTab(state.tab) },
                )
            }
            state.tab == ActivityTab.Points -> {
                if (state.points.isEmpty()) {
                    item { EmptyStateView(Lucide.User, stringResource(R.string.profile_empty_points)) }
                } else {
                    // Indexed: the fields here are not unique — two "daily check-in
                    // +5" entries with no timestamp produce the same key, and a
                    // duplicate key is a crash rather than a glitch.
                    itemsIndexed(state.points) { _, entry ->
                        PointsRow(entry)
                        HairLine()
                    }
                }
            }
            else -> {
                val actions = state.currentActivity
                if (actions.isEmpty()) {
                    item { EmptyStateView(Lucide.User, stringResource(R.string.common_empty)) }
                } else {
                    // Indexed for the same reason the points list is: every field
                    // in that composite key is nullable, so two entries the server
                    // returns with all four absent crash the list rather than
                    // merely drawing oddly. The list is replaced wholesale, never
                    // appended to, so position is a truthful identity here.
                    itemsIndexed(actions) { index, action ->
                        // Asking from the row itself rather than watching scroll
                        // offsets: the list is inside the profile's own column, so
                        // there is no separate LazyListState to observe.
                        if (index == actions.lastIndex) {
                            LaunchedEffect(state.tab, actions.size) { onLoadMoreActivity() }
                        }
                        ActivityRow(action) {
                            navigator.openTopic(action.topicId ?: return@ActivityRow, action.postNumber)
                        }
                    }
                    if (state.isLoadingMoreTab) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().height(56.dp),
                                contentAlignment = Alignment.Center,
                            ) { NodelocLoader(height = 22.dp) }
                        }
                    }
                }
            }
        }
    }
}

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

@Composable
private fun HeaderCard(
    state: ProfileState,
    profile: UserProfile,
    isOwn: Boolean,
    navigator: Navigator,
    onToggleFollow: () -> Unit,
    onCheckin: () -> Unit,
    onShowAchievements: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    val privileges by ServiceLocator.get.moderation.privileges.collectAsState()
    val tier = state.tiers.level(profile.trustLevel)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(12.dp, shape, clip = false)
            .clip(shape)
            .background(Nocturne.bg)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RemoteAvatar(
                DiscourseConfig.avatarUrl(profile.avatarTemplate, 240),
                profile.username.take(1).uppercase().ifEmpty { "?" },
                size = 60.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        profile.name?.takeIf { it.isNotBlank() } ?: profile.username,
                        style = Type.heading(if (isOwn) 22 else 21, FontWeight.Bold),
                        color = Nocturne.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // Your own page names your role; someone else's names their
                    // tier, which is what a stranger wants to know first.
                    if (isOwn) {
                        val admin = privileges.isAdmin || profile.admin == true
                        val moderator = privileges.role == "moderator" || profile.moderator == true
                        when {
                            admin -> StatusPill(stringResource(R.string.profile_admin), PillTone.Accent)
                            moderator -> StatusPill(stringResource(R.string.profile_moderator), PillTone.Accent)
                        }
                    } else {
                        tier?.name?.let { TierPill(it, tier) }
                        when {
                            profile.admin == true -> StatusPill(stringResource(R.string.profile_admin), PillTone.Accent)
                            profile.moderator == true -> StatusPill(stringResource(R.string.profile_moderator), PillTone.Accent)
                        }
                    }
                }
                Text(
                    subtitle(profile, isOwn),
                    style = Type.body(13),
                    color = Nocturne.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isOwn) CheckinButton(done = state.checkedInToday, busy = state.isCheckingIn, onClick = onCheckin)
        }

        // The badge title and group flair the member chose. Not in the design,
        // which has nowhere to put them, but both are the member's own choice
        // of how to be seen, so they stay — under the name, quietly. A title
        // that merely repeats the tier is the server filling a blank, and is
        // dropped.
        val title = profile.title?.takeIf { it.isNotBlank() && it != tier?.name }
        if (title != null || profile.flairUrl != null || profile.flairName != null) {
            FlowRow(
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

        StatsRow(state, profile, isOwn)

        if (isOwn) {
            state.reputation?.let { rep ->
                TierProgressLine(rep, tier?.name ?: rep.tierName.orEmpty()) {
                    navigator.openUpgradeProgress(profile.username)
                }
            }
            val total = state.achievementsTotal
            if (total != null) {
                AchievementsLine(
                    badges = state.achievementBadges,
                    label = stringResource(R.string.me2_achievements_count, state.achievementsUnlocked ?: 0, total),
                    onClick = { navigator.openAchievements() },
                )
            }
        } else {
            if (state.achievementBadges.isNotEmpty()) {
                AchievementsLine(
                    badges = state.achievementBadges,
                    label = stringResource(R.string.me2_showcase_label),
                    onClick = onShowAchievements,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FollowButton(
                    following = state.isFollowing,
                    onClick = onToggleFollow,
                    modifier = Modifier.weight(1f),
                )
                OutlinedPillButton(
                    text = stringResource(R.string.profile_message),
                    icon = Lucide.Mail,
                    onClick = { navigator.openCompose(pmRecipient = profile.username) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** "@moss · 5,912 关注者 · 2022 年加入" on your page; "@linjc · bio" on someone else's. */
@Composable
private fun subtitle(profile: UserProfile, isOwn: Boolean): String {
    val parts = mutableListOf("@${profile.username}")
    if (isOwn) {
        profile.totalFollowers?.let { parts += stringResource(R.string.me2_followers, formatCount(it.toLong())) }
        DiscourseFormat.instant(profile.createdAt)?.let {
            parts += stringResource(R.string.me2_joined_year, it.atZone(ZoneId.systemDefault()).year.toString())
        }
    } else {
        profile.bioExcerpt?.let { com.nodeloc.app.core.html.plainTextFromHtml(it) }
            ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
            ?.let { parts += it }
    }
    return parts.joinToString(" · ")
}

@Composable
private fun CheckinButton(done: Boolean, busy: Boolean, onClick: () -> Unit) {
    // Greyed once the day is claimed — spent, not broken. Still live
    // underneath: the tap is what says *why* it is grey, and costs nothing,
    // since the answer comes from the device.
    Row(
        Modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(if (done) Nocturne.neutral400 else Nocturne.accent)
            .clickable(enabled = !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        val ink = if (done) Nocturne.textSecondary else Nocturne.bg
        Icon(Lucide.CalendarHeart, null, tint = ink, modifier = Modifier.size(16.dp))
        Text(
            stringResource(if (done) R.string.me2_checked_in else R.string.checkin_action),
            style = Type.body(14, FontWeight.SemiBold),
            color = ink,
            maxLines = 1,
        )
    }
}

@Composable
private fun FollowButton(following: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (following) {
        OutlinedPillButton(stringResource(R.string.profile_following), null, onClick, modifier)
    } else {
        Box(
            modifier
                .height(40.dp)
                .clip(CircleShape)
                .background(Nocturne.accent)
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.profile_follow), style = Type.body(14, FontWeight.SemiBold), color = Nocturne.bg)
        }
    }
}

@Composable
private fun OutlinedPillButton(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(40.dp)
            .clip(CircleShape)
            .border(1.dp, Nocturne.textTertiary, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = Nocturne.accent, modifier = Modifier.size(16.dp)) }
        Text(text, style = Type.body(14, FontWeight.SemiBold), color = Nocturne.accent)
    }
}

@Composable
private fun StatsRow(state: ProfileState, profile: UserProfile, isOwn: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Nocturne.surface)
            .padding(vertical = 10.dp),
    ) {
        // Counts come from the profile's own stats block first, then the
        // summary call; either may be missing when the account hides them.
        val summary = state.summary
        val topics = (summary?.topicCount ?: profile.topicCount)?.toLong()
        if (isOwn) {
            Stat(stringResource(R.string.profile_energy), state.pointsTotal?.toLong())
            Stat(stringResource(R.string.profile_reputation), state.reputation?.reputation?.toLong())
            Stat(stringResource(R.string.profile_topics), topics)
            Stat(stringResource(R.string.me2_replies), (summary?.postCount ?: profile.postCount)?.toLong())
        } else {
            Stat(stringResource(R.string.profile_reputation), state.publicReputation)
            Stat(stringResource(R.string.profile_topics), topics)
            Stat(stringResource(R.string.me2_followers_label), profile.totalFollowers?.toLong())
            StatText(stringResource(R.string.me2_joined_label), membershipLength(profile.createdAt) ?: "--")
        }
    }
}

@Composable
private fun RowScope.Stat(label: String, value: Long?) = StatText(label, value?.let(::formatCount) ?: "--")

@Composable
private fun RowScope.StatText(label: String, value: String) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = Type.body(18, FontWeight.Bold), color = Nocturne.text, maxLines = 1)
        Text(label, style = Type.body(12), color = Nocturne.textSecondary, maxLines = 1)
    }
}

/** "2 年" — whole years once there are any, then months, then days. */
@Composable
private fun membershipLength(createdAt: String?): String? {
    val start = DiscourseFormat.instant(createdAt) ?: return null
    val zone = ZoneId.systemDefault()
    val from = start.atZone(zone).toLocalDate()
    val to = Instant.now().atZone(zone).toLocalDate()
    val years = ChronoUnit.YEARS.between(from, to)
    val months = ChronoUnit.MONTHS.between(from, to)
    return when {
        years >= 1 -> stringResource(R.string.me2_years, years.toInt())
        months >= 1 -> stringResource(R.string.me2_months, months.toInt())
        else -> stringResource(R.string.me2_days, ChronoUnit.DAYS.between(from, to).toInt().coerceAtLeast(1))
    }
}

/** "钻石 · 升级进度 ··· 81%" over a bar; at the top tier, the name and nothing to climb. */
@Composable
private fun TierProgressLine(rep: ApiRepSummary, tierName: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tierName, style = Type.body(13, FontWeight.Bold), color = Nocturne.text)
            Text(
                " · " + stringResource(if (rep.next == null) R.string.me2_top_tier else R.string.me2_upgrade_progress),
                style = Type.body(13),
                color = Nocturne.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (rep.next != null) {
                Text("${(rep.progress * 100).roundToInt()}%", style = Type.body(13), color = Nocturne.textSecondary)
            }
        }
        GrowthBar(if (rep.next == null) 1f else rep.progress.toFloat())
    }
}

@Composable
private fun AchievementsLine(badges: List<ApiAchItem>, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        badges.take(4).forEach { AchievementDisc(it, 34.dp) }
        Text(label, style = Type.body(13), color = Nocturne.textSecondary, modifier = Modifier.weight(1f))
        Icon(Lucide.ChevronRight, null, tint = Nocturne.textTertiary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun AchievementDisc(item: ApiAchItem, size: Dp) {
    val (ground, ink) = achTierColors(item.tier)
    Box(
        Modifier.size(size).clip(CircleShape).background(ground),
        contentAlignment = Alignment.Center,
    ) {
        Icon(achievementIcon(item.icon), item.name, tint = ink, modifier = Modifier.size(size * 0.5f))
    }
}

private data class Shortcut(val icon: ImageVector, val label: Int, val tone: PillTone, val open: () -> Unit)

/** The eight ways out of 我的, four to a row. */
@Composable
private fun Shortcuts(navigator: Navigator, username: String) {
    val items = listOf(
        Shortcut(Lucide.TrendingUp, R.string.me2_sc_upgrade, PillTone.Info) { navigator.openUpgradeProgress(username) },
        Shortcut(Lucide.Award, R.string.me2_sc_achievements, PillTone.Warning) { navigator.openAchievements() },
        Shortcut(Lucide.Zap, R.string.me2_sc_energy, PillTone.Warning) { navigator.openEnergyHistory() },
        Shortcut(Lucide.Trophy, R.string.me2_sc_rank, PillTone.Info) { navigator.openWealthRank() },
        Shortcut(Lucide.UserPlus, R.string.me2_sc_invite, PillTone.Accent) { navigator.openInvite() },
        Shortcut(Lucide.FilePen, R.string.me2_sc_drafts, PillTone.Neutral) { navigator.openDrafts() },
        Shortcut(Lucide.LayoutGrid, R.string.me2_sc_apps, PillTone.Danger) { navigator.openApps() },
        Shortcut(Lucide.Settings, R.string.me2_sc_settings, PillTone.Neutral) { navigator.openSettings() },
    )
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        items.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { item ->
                    val (ground, ink) = item.tone.colorsForShortcut()
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(role = Role.Button, onClick = item.open)
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(ground),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(item.icon, null, tint = ink, modifier = Modifier.size(20.dp))
                        }
                        Text(
                            stringResource(item.label),
                            style = Type.body(12),
                            color = Nocturne.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** The neutral tile is a shade lighter than a neutral pill: it sits on the page, not on a card. */
@Composable
private fun PillTone.colorsForShortcut() = when (this) {
    PillTone.Neutral -> Nocturne.neutral300 to Nocturne.text
    PillTone.Accent -> Nocturne.accent100 to Nocturne.accent700
    PillTone.Warning -> Nocturne.warningBg to Nocturne.warning
    PillTone.Danger -> Nocturne.dangerBg to Nocturne.danger
    PillTone.Info -> Nocturne.infoBg to Nocturne.info
}

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

/** The header's own shape: band, overlapping card, then rows. */
@Composable
private fun ProfileSkeleton(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(floatingHeaderInset + BandExtra).background(Nocturne.accent900))
        Column(Modifier.fillMaxSize().padding(top = floatingHeaderInset + 16.dp)) {
            Column(
                Modifier
                    .padding(horizontal = Space.page)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Nocturne.bg)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .skeletonPulsing(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SkeletonBox(Modifier.size(60.dp), corner = 30.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SkeletonLine(Modifier.width(120.dp), height = 20.dp)
                        SkeletonLine(Modifier.width(180.dp), height = 12.dp)
                    }
                }
                SkeletonBox(Modifier.fillMaxWidth().height(56.dp), corner = 14.dp)
                SkeletonLine(Modifier.fillMaxWidth(), height = 12.dp)
            }
            Spacer(Modifier.height(Space.s4))
            RowsSkeleton(rows = 4, avatar = false)
        }
    }
}

@Composable
private fun ActivityRow(action: UserActionItem, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = action.topicId != null, onClick = onClick)
            .padding(horizontal = Space.page, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(DiscourseFormat.relative(action.createdAt), style = Type.body(13), color = Nocturne.textSecondary)
        Text(
            action.title.orEmpty(),
            style = Type.body(17, FontWeight.SemiBold),
            color = Nocturne.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        action.excerpt?.takeIf { it.isNotBlank() }?.let {
            Text(
                com.nodeloc.app.core.html.plainTextFromHtml(it),
                style = Type.body(15),
                color = Nocturne.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HairLine()
}

@Composable
private fun PointsRow(entry: PointsHistoryEntry) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.description.orEmpty(), style = Type.body(14), color = Nocturne.text)
            Text(
                DiscourseFormat.day(entry.createdAt ?: entry.date),
                style = Type.body(11),
                color = Nocturne.muted(0.4f),
            )
        }
        val points = (entry.points ?: 0).toLong()
        Text(
            formatSigned(points),
            style = Type.body(14, FontWeight.SemiBold),
            color = if (points >= 0) Nocturne.accent else Nocturne.danger,
        )
    }
}

/**
 * Who a report about a person goes to.
 *
 * The group every Discourse install has, and the one its own guidance points
 * at: there is no endpoint that flags a user, only posts.
 */
private const val MODERATORS_GROUP = "moderators"
