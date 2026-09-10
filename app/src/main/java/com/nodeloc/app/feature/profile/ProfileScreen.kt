package com.nodeloc.app.feature.profile

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.RowScope
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.SkeletonBox
import com.nodeloc.app.core.design.SkeletonLine
import com.nodeloc.app.core.design.skeletonPulsing
import com.composables.icons.lucide.WifiOff
import com.nodeloc.app.core.network.isOfflineError
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.CalendarHeart
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.User
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Menu
import com.nodeloc.app.core.design.BlockUserDialog
import com.composables.icons.lucide.Ban
import com.composables.icons.lucide.Flag
import com.nodeloc.app.R
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Mail
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Bell
import com.nodeloc.app.core.design.BadgeTitleText
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FlairBadge
import com.nodeloc.app.core.design.FloatingHeaderBar
import com.nodeloc.app.core.design.HeaderGroup
import com.nodeloc.app.core.design.GuestAvatar
import com.nodeloc.app.core.design.GuestLoginButton
import com.nodeloc.app.core.design.HairLine
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.LocalHeaderContentColor
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Radius
import com.nodeloc.app.core.design.PrimaryButton
import com.nodeloc.app.core.design.PullToRefreshBox
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.RemoteImage
import com.nodeloc.app.core.design.SecondaryButton
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.TagChip
import com.nodeloc.app.core.design.TagStyle
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.floatingHeaderInset
import com.nodeloc.app.core.design.isScrolledPastTop
import com.nodeloc.app.core.design.rememberPullToRefreshState
import com.nodeloc.app.core.design.requestScrollToTop
import com.nodeloc.app.core.design.rememberTapSafeOverscroll
import com.nodeloc.app.core.model.PointsHistoryEntry
import com.nodeloc.app.core.model.UserActionItem
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.feature.feed.RowsSkeleton
import com.nodeloc.app.feature.nav.AppViewModel
import com.nodeloc.app.feature.nav.Navigator

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
                    onRetry = viewModel::retry,
                    onSelectTab = viewModel::selectTab,
                    onOpenTopic = { topicId, postNumber -> navigator.openTopic(topicId, postNumber) },
                    onLoadMoreActivity = viewModel::loadMoreActivity,
                    onToggleFollow = {},
                    onOpenUpgradeProgress = {
                        state.profile?.username?.let(navigator::openUpgradeProgress)
                    },
                )
            }
        }

        FloatingHeaderBar(
            scrolled = listState.isScrolledPastTop(threshold = 0),
            overMedia = true,
            leading = { HeaderIconButton(Lucide.Menu, stringResource(R.string.common_menu), onClick = onOpenDrawer) },
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
                // One cluster, not two controls the bar spaces apart. The bar's
                // own gap is sized for items that mean different things; these
                // two are both "this page's own switches", and at 48dp targets
                // the standard gap left the calendar stranded mid-bar.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSignedIn) {
                        val done = state.checkedInToday
                        // Greyed once the day is claimed — spent, not broken, and
                        // the same 35% the bar gives a disabled icon. Still live
                        // underneath: the tap is what says *why* it is grey, and
                        // costs nothing, since the answer comes from the device.
                        //
                        // Dimmed against the bar's own colour rather than a fixed
                        // grey, so the glyph keeps following it over the banner
                        // picture, where the content is white rather than dark.
                        val barContent = LocalHeaderContentColor.current
                            .takeIf { it.isSpecified } ?: Nocturne.headerText
                        CompositionLocalProvider(
                            LocalHeaderContentColor provides
                                if (done) barContent.copy(alpha = 0.35f) else barContent,
                        ) {
                            HeaderIconButton(
                                Lucide.CalendarHeart,
                                stringResource(if (done) R.string.checkin_done else R.string.checkin_action),
                                enabled = !state.isCheckingIn,
                                // A claimed day is answered from the device. The
                                // endpoint rate-limits every attempt and only
                                // forgives a successful one, so asking it something
                                // already known costs the account its next tries.
                                onClick = {
                                    if (done) ToastCenter.show(R.string.checkin_done) else viewModel.checkin()
                                },
                            )
                        }
                    }
                    HeaderIconButton(Lucide.Settings, stringResource(R.string.common_settings), onClick = { navigator.openSettings() })
                }
            },
        )
    }
}

/** Someone else's profile: the same body, plus a follow button. */
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
            onRetry = viewModel::retry,
            onSelectTab = viewModel::selectTab,
            onOpenTopic = { topicId, postNumber -> navigator.openTopic(topicId, postNumber) },
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
                            text = { Text(stringResource(R.string.profile_message)) },
                            leadingIcon = { Icon(Lucide.Mail, null, Modifier.size(18.dp)) },
                            onClick = {
                                menuOpen = false
                                navigator.openCompose(pmRecipient = username)
                            },
                        )
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

/** Tall enough to hold the avatar inside it with air above and below. */
private val BannerHeight = 200.dp

/**
 * Trust level as a climb rather than a number.
 *
 * Vertical because that is what the thing being shown is: the next rung is
 * above, the one already held is below, and the fill rises between them. A
 * horizontal bar would have said the same thing while reading as a download.
 *
 * The labels are levels, not the words "next" and "current" — a rail with
 * 「基本用户」 under it and 「成员」 over it explains itself, where a legend
 * would have to be read first.
 */
@Composable
private fun UpgradeRail(
    upgrade: com.nodeloc.app.core.model.UpgradeProgress,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val next = upgrade.nextLevelName?.takeIf { it.isNotBlank() }
    val current = upgrade.currentLevelName?.takeIf { it.isNotBlank() }
    // Nothing above and nothing to climb towards: at the top of the ladder the
    // rail is a full bar with one name under it, not the same name twice.
    if (next == null && !upgrade.maxLevelReached) return

    // The rail has to end inside the banner: the list item below it is opaque
    // and paints over anything that runs past. So the row is given exactly the
    // room there is — banner less the header above it — rather than a fixed
    // height that fits on one device and loses a label on the next.
    Row(
        modifier
            .height(BannerHeight - floatingHeaderInset - Space.s2)
            .clip(RoundedCornerShape(Radius.md))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.dp))
                // A mid grey, not a wash of the page colour: the line lies on
                // the member's own banner, which can be white sky or a black
                // frame, and only something from the middle of the range is
                // legible against both.
                .background(RailTrack),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    // Rises from the bottom, because that is the end you are
                    // standing on.
                    .fillMaxHeight(if (upgrade.maxLevelReached) 1f else upgrade.fraction)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Nocturne.accent),
            )
        }
        // Beside the line rather than over it: the labels are level names and
        // can run long, and a name centred on a 2dp line would decide the
        // whole rail's width.
        Column(
            Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start,
        ) {
            // At the top of the ladder there is no rung above. What there is
            // instead is the work of staying on this one, which the site calls
            // retention — so the top of the rail says that rather than repeating
            // the level named at its foot.
            RailLabel(
                next ?: stringResource(R.string.upgrade_maintaining),
                dim = true,
            )
            RailLabel(current.orEmpty(), dim = false)
        }
    }
}

/**
 * Mid grey, fixed rather than themed. Both themes' neutrals are picked to sit
 * on the page's own background; this one sits on a photograph.
 */
private val RailTrack = androidx.compose.ui.graphics.Color(0x998E8E93)

@Composable
private fun RailLabel(text: String, dim: Boolean) {
    if (text.isBlank()) return
    Text(
        text,
        style = Type.body(10, FontWeight.Medium),
        // On a photograph, so it carries its own plate rather than trusting
        // the picture underneath to be readable.
        color = if (dim) Nocturne.muted(0.55f) else Nocturne.text,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(Nocturne.bg.copy(alpha = 0.72f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun ProfileBody(
    state: ProfileState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    contentPadding: PaddingValues,
    isOwn: Boolean,
    onRetry: () -> Unit,
    onSelectTab: (ActivityTab) -> Unit,
    onOpenTopic: (Int, Int?) -> Unit,
    onLoadMoreActivity: () -> Unit,
    onToggleFollow: () -> Unit,
    /**
     * Null on someone else's page. The endpoint would answer for any profile
     * this account can see, but how close a stranger is to their next level is
     * their business — the site itself only ever shows it to its owner.
     */
    onOpenUpgradeProgress: (() -> Unit)? = null,
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

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        overscrollEffect = rememberTapSafeOverscroll(),
    ) {
        item {
            // The avatar sits inside the picture rather than straddling its
            // edge: a portrait cut in half by the banner's border is the one
            // shape a profile header cannot afford to get wrong, and the
            // banner is the user's own image — it should be seen whole.
            Box(
                Modifier.fillMaxWidth().height(BannerHeight),
                contentAlignment = Alignment.BottomCenter,
            ) {
                val banner = DiscourseConfig.absoluteUrl(
                    profile.profileBackgroundUploadUrl ?: profile.cardBackgroundUploadUrl,
                )
                if (banner != null) {
                    RemoteImage(
                        banner,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(Nocturne.accent800))
                }
                // The climb, drawn as one: next rung at the top, the rung
                // already held at the bottom, and the fill between them showing
                // how much of the way is done. Against the left edge and clear
                // of the hamburger above it, where nothing else in the header
                // wants to be.
                state.upgrade?.let { upgrade ->
                    if (onOpenUpgradeProgress != null) {
                        UpgradeRail(
                            upgrade = upgrade,
                            onClick = onOpenUpgradeProgress,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                // Starts below the header rather than beside
                                // it: the hamburger owns that corner, and a
                                // rail drawn through it reads as a mistake.
                                .padding(start = Space.page, top = floatingHeaderInset),
                        )
                    }
                }
                // A ring in the page colour, because the avatar lands on a
                // photograph that could be any colour at all.
                Box(
                    Modifier
                        .padding(bottom = Space.s6)
                        .clip(CircleShape)
                        .background(Nocturne.bg)
                        .padding(3.dp),
                ) {
                    RemoteAvatar(
                        DiscourseConfig.avatarUrl(profile.avatarTemplate, 240),
                        profile.username.take(1).uppercase().ifEmpty { "?" },
                        size = 78.dp,
                    )
                }
            }
        }

        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Space.page).padding(top = Space.s4),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.s3),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        profile.name?.takeIf { it.isNotBlank() } ?: profile.username,
                        style = Type.heading(20, FontWeight.SemiBold),
                        color = Nocturne.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // The site's own styling for this title, not a generic
                    // chip: a badge here can be gold, or shimmer, or glitch.
                    profile.title?.takeIf { it.isNotBlank() }?.let {
                        BadgeTitleText(it, size = 13)
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "@${profile.username}",
                        style = Type.body(13),
                        color = Nocturne.muted(0.5f),
                    )
                    // The group flair the member chose, where Discourse puts
                    // it: against the handle rather than the display name.
                    FlairBadge(
                        flairUrl = profile.flairUrl,
                        flairName = profile.flairName,
                        backgroundColor = profile.flairBgColor,
                        foregroundColor = profile.flairColor,
                    )
                    // The trust level used to be a chip here. It is the rail in
                    // the banner now — a level is a position on a ladder, and
                    // the rail says that where a word could only name it.
                    // On the identity line rather than beside the display name:
                    // at full size it was the loudest thing in the header,
                    // competing with the name it was about.
                    if (!isOwn && profile.canFollow == true) {
                        if (state.isFollowing) {
                            SecondaryButton(
                                stringResource(R.string.profile_following),
                                compact = true,
                                onClick = onToggleFollow,
                            )
                        } else {
                            PrimaryButton(
                                stringResource(R.string.profile_follow),
                                compact = true,
                                onClick = onToggleFollow,
                            )
                        }
                    }
                }

                // Who they are and how long they have been here are two
                // different claims, so they get a line each — and the identity
                // line disappears entirely for an ordinary account rather than
                // leaving a gap where a badge would have been.
                if (profile.admin == true || profile.moderator == true) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (profile.admin == true) TagChip(stringResource(R.string.profile_admin), style = TagStyle.Accent2)
                        if (profile.moderator == true) TagChip(stringResource(R.string.profile_moderator), style = TagStyle.Accent2)
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    profile.lastSeenAt?.let { TagChip(stringResource(R.string.profile_last_seen, DiscourseFormat.relative(it))) }
                    profile.createdAt?.let { TagChip(stringResource(R.string.profile_joined, DiscourseFormat.day(it))) }
                }

                profile.bioExcerpt?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        com.nodeloc.app.core.html.plainTextFromHtml(it),
                        style = Type.body(14),
                        color = Nocturne.muted(0.62f),
                        textAlign = TextAlign.Center,
                    )
                }

                StatsRow(state)
            }
            Spacer(Modifier.height(Space.s4))
            HairLine()
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.s3),
                horizontalArrangement = Arrangement.spacedBy(Space.s3),
            ) {
                ActivityTab.entries.forEach { tab ->
                    val selected = tab == state.tab
                    Text(
                        stringResource(tab.labelRes),
                        style = Type.body(13, if (selected) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (selected) Nocturne.accent else Nocturne.muted(0.5f),
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (selected) Nocturne.selected else Nocturne.bg)
                            // The current tab was signalled by colour and
                            // weight alone, neither of which a screen reader
                            // can see.
                            .selectable(selected = selected, role = Role.Tab) { onSelectTab(tab) }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }
            HairLine()
        }

        if (state.isLoadingTab) {
            item { RowsSkeleton(rows = 5, avatar = false) }
        } else if (state.tabError != null) {
            item {
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
        } else if (state.tab == ActivityTab.Points) {
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
        } else {
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
                    ActivityRow(action) { onOpenTopic(action.topicId ?: return@ActivityRow, action.postNumber) }
                    HairLine()
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

/** The header's own shape: banner, portrait, a centred column, then rows. */
@Composable
private fun ProfileSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().skeletonPulsing()) {
        Box(
            Modifier.fillMaxWidth().height(BannerHeight),
            contentAlignment = Alignment.BottomCenter,
        ) {
            SkeletonBox(Modifier.fillMaxSize(), corner = 0.dp)
            // The ring is the real header's, and it is what makes the portrait
            // legible here: without it a grey circle on a grey banner is a
            // banner.
            Box(
                Modifier
                    .padding(bottom = Space.s6)
                    .clip(CircleShape)
                    .background(Nocturne.bg)
                    .padding(3.dp),
            ) {
                // A shade darker than the banner behind it: two placeholders in
                // the same grey are one placeholder.
                Box(
                    Modifier
                        .size(78.dp)
                        .clip(CircleShape)
                        .background(Nocturne.neutral400),
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.page).padding(top = Space.s4),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            SkeletonLine(Modifier.width(140.dp), height = 20.dp)
            SkeletonLine(Modifier.width(88.dp), height = 13.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBox(Modifier.size(96.dp, 26.dp), corner = 13.dp)
                SkeletonBox(Modifier.size(128.dp, 26.dp), corner = 13.dp)
            }
            SkeletonLine(Modifier.width(200.dp), height = 12.dp)
            Row(
                Modifier.fillMaxWidth().padding(top = Space.s2),
                horizontalArrangement = Arrangement.spacedBy(Space.s3),
            ) {
                repeat(4) {
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        SkeletonLine(Modifier.width(52.dp), height = 16.dp)
                        SkeletonLine(Modifier.width(32.dp), height = 10.dp)
                    }
                }
            }
        }
        Spacer(Modifier.height(Space.s4))
        HairLine()
        RowsSkeleton(rows = 4, avatar = false)
    }
}

@Composable
private fun StatsRow(state: ProfileState) {
    val profile = state.profile
    // Four equal columns rather than a left-packed run: under a centred name
    // the numbers have to sit on their own axis, or the block leans left.
    Row(
        Modifier.fillMaxWidth().padding(top = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        // Counts come from summary.json first: `/u/{name}.json` does not
        // serialise likes_received at all, and its topic and post counts are
        // only there for a request allowed to see them. The profile is the
        // fallback for an account whose summary stats are private.
        val summary = state.summary
        Stat(stringResource(R.string.profile_energy), state.pointsTotal?.toString() ?: "--")
        Stat(
            stringResource(R.string.profile_reputation),
            (summary?.likesReceived ?: profile?.likesReceived)?.toString() ?: "--",
        )
        Stat(
            stringResource(R.string.profile_topics),
            (summary?.topicCount ?: profile?.topicCount)?.toString() ?: "--",
        )
        Stat(
            stringResource(R.string.profile_posts),
            (summary?.postCount ?: profile?.postCount)?.toString() ?: "--",
        )
    }
}

@Composable
private fun RowScope.Stat(label: String, value: String) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = Type.body(16, FontWeight.SemiBold), color = Nocturne.text)
        Text(label, style = Type.body(11), color = Nocturne.muted(0.45f))
    }
}

@Composable
private fun ActivityRow(action: UserActionItem, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = action.topicId != null, onClick = onClick)
            .padding(horizontal = Space.page, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            action.title.orEmpty(),
            style = Type.body(14, FontWeight.Medium),
            color = Nocturne.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        action.excerpt?.takeIf { it.isNotBlank() }?.let {
            Text(
                com.nodeloc.app.core.html.plainTextFromHtml(it),
                style = Type.body(12),
                color = Nocturne.muted(0.5f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(DiscourseFormat.relative(action.createdAt), style = Type.body(11), color = Nocturne.muted(0.4f))
    }
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
        val points = entry.points ?: 0
        Text(
            if (points >= 0) "+$points" else "$points",
            style = Type.body(14, FontWeight.SemiBold),
            color = if (points >= 0) Nocturne.success else Nocturne.danger,
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
