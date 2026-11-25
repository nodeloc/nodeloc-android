package com.nodeloc.app.feature.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.AtSign
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Repeat
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.ShieldAlert
import com.composables.icons.lucide.ThumbsUp
import com.composables.icons.lucide.UserPlus
import com.composables.icons.lucide.WifiOff
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.R
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FilterPill
import com.nodeloc.app.core.design.FilterPillRow
import com.nodeloc.app.core.design.FloatingHeaderBar
import com.nodeloc.app.core.design.GuestLoginButton
import com.nodeloc.app.core.design.HairLine
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocIcons
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.PrimaryButton
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.SecondaryButton
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.animatedColor
import com.nodeloc.app.core.design.floatingHeaderInset
import com.nodeloc.app.core.design.pressScale
import com.nodeloc.app.core.design.rememberPressSource
import com.nodeloc.app.core.design.rememberTapSafeOverscroll
import com.nodeloc.app.core.model.InboxNotification
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.network.isOfflineError
import com.nodeloc.app.core.store.NoteCategory
import com.nodeloc.app.core.store.NotificationFormatter
import com.nodeloc.app.core.store.NotificationMeta
import com.nodeloc.app.core.store.NotificationTarget
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.feature.feed.RowsSkeleton
import com.nodeloc.app.feature.nav.AppViewModel
import com.nodeloc.app.feature.nav.Navigator
import com.nodeloc.app.feature.nav.Route
import java.time.LocalDate
import java.time.ZoneId

/** 消息: notifications, and the chat conversations beside them. */
@Composable
fun InboxScreen(
    app: AppViewModel,
    navigator: Navigator,
    contentPadding: PaddingValues,
    onOpenDrawer: () -> Unit,
) {
    val viewModel: InboxViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val filterState by viewModel.filter.collectAsState()
    val followed by viewModel.followed.collectAsState()
    val loadingMoreAll by viewModel.loadingMoreAll.collectAsState()
    val isSignedIn by app.isSignedIn.collectAsState()
    val moderation = ServiceLocator.get.moderation
    val privileges by moderation.privileges.collectAsState()
    val nodePending by moderation.nodePending.collectAsState()
    val pendingTotal by moderation.menuBadge.collectAsState()
    var segment by remember { mutableStateOf(InboxSegment.visible.first()) }
    val context = LocalContext.current

    // Loading only. Marking everything read here dropped the badge from four
    // to one the instant the tab was tapped — before the list had rendered,
    // whichever segment the user landed on, and with no way afterwards to tell
    // which rows had been the new ones. Reading is what marks a notification
    // read now; the header keeps the explicit "mark all read".
    // Refetched every time the inbox comes back, not just the first time. The
    // repository caches its last good pass, so `load()` on a second visit was
    // a no-op: coming out of a conversation left the row above it saying
    // whatever it had said before the conversation was read, and a chat that
    // had moved on all afternoon looked untouched.
    LaunchedEffect(isSignedIn) {
        if (isSignedIn) viewModel.refresh()
    }

    // 版务 is a moderator's chip; losing the role while it is selected must not
    // leave the list filtered by a chip that is no longer drawn.
    LaunchedEffect(privileges.isModerator) {
        viewModel.setModerator(privileges.isModerator)
        if (!privileges.isModerator && filterState.filter == InboxFilter.Moderation) viewModel.selectFilter(InboxFilter.All)
        if (privileges.isModerator) runCatchingCancellable { moderation.refreshNodeCounts() }
    }

    Box(Modifier.fillMaxSize().background(Nocturne.bg)) {
        Column(Modifier.fillMaxSize().padding(top = floatingHeaderInset)) {
            if (isSignedIn) {
                SegmentRow(
                    segment = segment,
                    notifications = state.unreadNotifications,
                    messages = state.unreadPrivateMessages,
                    chats = state.unreadChat,
                    onSelect = { segment = it },
                )
            }

            when {
                !isSignedIn -> EmptyStateView(
                    icon = Lucide.MessageCircle,
                    title = stringResource(R.string.inbox_guest),
                    detail = stringResource(R.string.inbox_guest_detail),
                    // The header has a login button too, but a reader who came
                    // here to read messages should not have to go find it.
                    retryLabel = stringResource(R.string.common_login),
                    onRetry = { navigator.openAuth() },
                )

                segment == InboxSegment.Notifications -> {
                    val all = filterState.filter == InboxFilter.All
                    val rows = if (all) state.notifications else filterState.rows.orEmpty()
                    val meta = if (all) state.notificationMeta else filterState.meta
                    val loading = if (all) state.isLoading && state.notifications.isEmpty() else filterState.loading
                    val modCard = privileges.isModerator && pendingTotal > 0
                    // A lone node with work in it is named; anything wider is
                    // the queue the viewer can reach.
                    val busyNodes = nodePending.filterValues { it > 0 }.keys
                    val modNode = busyNodes.singleOrNull()?.takeIf { !privileges.reviewsSite }
                    NotificationList(
                        rows = rows,
                        meta = meta,
                        loading = loading,
                        error = if (all) state.error else filterState.error,
                        hasMore = if (all) state.notificationsNextBefore != null else filterState.nextBefore != null,
                        loadingMore = if (all) loadingMoreAll else filterState.loadingMore,
                        followed = followed,
                        contentPadding = contentPadding,
                        header = {
                            item(key = "header") {
                                InboxHeader(
                                    title = stringResource(R.string.inbox_notifications),
                                    unread = state.unreadNotifications,
                                    onMarkAll = { viewModel.markSegmentRead(InboxSegment.Notifications) },
                                    onSettings = { navigator.openSettingsRoute(Route.SettingsPush) },
                                )
                            }
                            item(key = "chips") {
                                FilterChips(
                                    selected = filterState.filter,
                                    moderator = privileges.isModerator,
                                    unread = state.unreadNotifications,
                                    shared = state.notifications,
                                    sharedMeta = state.notificationMeta,
                                    onSelect = viewModel::selectFilter,
                                )
                            }
                            if (modCard) {
                                item(key = "mod") {
                                    ModerationCard(
                                        node = modNode,
                                        count = if (modNode != null) nodePending[modNode] ?: pendingTotal else pendingTotal,
                                        onClick = { navigator.openReviewQueue(modNode) },
                                    )
                                }
                            }
                        },
                        onOpen = { notification, info ->
                            // Opening it is what makes it read — the whole list
                            // no longer clears itself just because the tab was tapped.
                            viewModel.markNotificationRead(notification.id)
                            openNotification(notification, info, navigator, context)
                        },
                        onToggleRead = viewModel::toggleRead,
                        onFollowBack = viewModel::toggleFollowBack,
                        onLoadMore = viewModel::loadMore,
                        navigator = navigator,
                    )
                }

                else -> Column {
                    InboxHeader(
                        title = stringResource(segment.labelRes),
                        unread = if (segment == InboxSegment.Chats) state.unreadChat else state.unreadPrivateMessages,
                        onMarkAll = { viewModel.markSegmentRead(segment) },
                        onSettings = null,
                    )
                    HairLine()
                    if (segment == InboxSegment.Messages) {
                        MessageList(state, viewModel, navigator, contentPadding)
                    } else {
                        ChannelList(state, navigator, contentPadding)
                    }
                }
            }
        }

        FloatingHeaderBar(
            leading = { com.nodeloc.app.feature.nav.DrawerMenuButton(onClick = onOpenDrawer) },
            trailing = {
                if (isSignedIn) {
                    HeaderIconButton(
                        Lucide.Settings,
                        stringResource(R.string.inbox3_settings),
                        onClick = { navigator.openSettingsRoute(Route.SettingsPush) },
                    )
                } else {
                    GuestLoginButton(onClick = { navigator.openAuth() })
                }
            },
        )
    }
}

/** Where a note leads: its post, its chat, its page, or — for a follow — the person. */
private fun openNotification(
    notification: InboxNotification,
    info: NotificationMeta?,
    navigator: Navigator,
    context: android.content.Context,
) {
    val path = info?.path
    when {
        notification.topicId != null -> navigator.openTopic(notification.topicId, notification.postNumber)
        notification.chatChannelId != null -> navigator.openChat(notification.chatChannelId, notification.chatMessageId)
        info?.kind == "follow" && info.actor != null -> navigator.openProfile(info.actor)
        path != null -> openNotePath(path, navigator, context)
        else -> {
            val target = NotificationFormatter.target(
                com.nodeloc.app.core.model.DiscourseNotification(
                    id = notification.id,
                    notificationType = notification.type,
                    read = notification.read,
                    data = com.nodeloc.app.core.model.NotificationData(
                        badgeId = notification.badgeId,
                        badgeSlug = notification.badgeSlug,
                        groupName = notification.groupName,
                        username = notification.username,
                    ),
                ),
            )
            (target as? NotificationTarget.Web)?.let { navigator.openBrowser(it.url) }
        }
    }
}

private val NodeManagePath = Regex("^/n/([^/?#]+)/manage(/[^?#]*)?")

/**
 * A system note's link. The moderation ones point at the web's back office;
 * the review queue and a node's admin have native screens, so those open
 * here and everything else goes through the ordinary link router.
 */
private fun openNotePath(path: String, navigator: Navigator, context: android.content.Context) {
    val node = NodeManagePath.find(path)
    when {
        path.startsWith("/admin/review") -> navigator.openReviewQueue()
        node != null && node.groupValues[2].startsWith("/review") -> navigator.openReviewQueue(node.groupValues[1])
        node != null -> navigator.openNodeAdmin(node.groupValues[1])
        else -> navigator.openUrl(context, DiscourseConfig.BASE_URL + path)
    }
}

/** "通知 / 5 条未读" with 全部标为已读 beside it — the board's page head. */
@Composable
private fun InboxHeader(title: String, unread: Int, onMarkAll: () -> Unit, onSettings: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.heading(24, FontWeight.ExtraBold), color = Nocturne.text, maxLines = 1)
            Text(
                if (unread > 0) stringResource(R.string.inbox3_unread_count, unread) else stringResource(R.string.inbox3_all_read),
                style = Type.body(14),
                color = Nocturne.textSecondary,
            )
        }
        SecondaryButton(stringResource(R.string.inbox3_mark_all), enabled = unread > 0, onClick = onMarkAll)
        if (onSettings != null) {
            val press = rememberPressSource()
            Box(
                Modifier
                    .pressScale(press)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Nocturne.neutral400)
                    .clickable(interactionSource = press, indication = ripple(), role = Role.Button, onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.Bell, stringResource(R.string.inbox3_settings), tint = Nocturne.text, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * The chips with what is unread under each. Counted off the shared list, the
 * same rows the badge counts — a chip's number and the list it opens onto
 * come from one place.
 */
@Composable
private fun FilterChips(
    selected: InboxFilter,
    moderator: Boolean,
    unread: Int,
    shared: List<InboxNotification>,
    sharedMeta: Map<Int, NotificationMeta>,
    onSelect: (InboxFilter) -> Unit,
) {
    val unreadByCategory = remember(shared, sharedMeta) {
        shared.filter { !it.read }.mapNotNull { sharedMeta[it.id]?.category }.groupingBy { it }.eachCount()
    }
    Column {
        FilterPillRow(contentPadding = PaddingValues(start = Space.page, end = Space.page, top = 12.dp, bottom = 12.dp)) {
            InboxFilter.entries
                .filter { it != InboxFilter.Moderation || moderator }
                .forEach { option ->
                    val count = when (option) {
                        InboxFilter.All -> null
                        InboxFilter.Unread -> unread
                        InboxFilter.System -> (unreadByCategory[NoteCategory.System] ?: 0) +
                            if (moderator) 0 else unreadByCategory[NoteCategory.Moderation] ?: 0
                        else -> option.category?.let { unreadByCategory[it] }
                    }
                    FilterPill(
                        text = stringResource(option.labelRes),
                        selected = option == selected,
                        count = count?.takeIf { it > 0 },
                        onClick = { onSelect(option) },
                    )
                }
        }
        HairLine()
    }
}

/** 版务 at the top of the list: how much is waiting, one tap from the queue. */
@Composable
private fun ModerationCard(node: String?, count: Int, onClick: () -> Unit) {
    val press = rememberPressSource()
    Row(
        Modifier
            .padding(start = Space.page, end = Space.page, top = 14.dp)
            .fillMaxWidth()
            .pressScale(press)
            .clip(RoundedCornerShape(16.dp))
            .background(Nocturne.warningBg)
            .clickable(interactionSource = press, indication = ripple(), role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Nocturne.accent2800),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.ShieldAlert, null, tint = Nocturne.warning, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                if (node != null) stringResource(R.string.inbox3_mod_node, node, count) else stringResource(R.string.inbox3_mod_all, count),
                style = Type.body(15, FontWeight.Bold),
                color = Nocturne.text,
            )
            Text(stringResource(R.string.inbox3_mod_hint), style = Type.body(13), color = Nocturne.warning)
        }
        Icon(Lucide.ChevronRight, null, tint = Nocturne.warning, modifier = Modifier.size(20.dp))
    }
}

private enum class DayGroup(val labelRes: Int) {
    Today(R.string.inbox3_today),
    Yesterday(R.string.inbox3_yesterday),
    Earlier(R.string.inbox3_earlier),
}

private fun dayGroup(raw: String?): DayGroup {
    val instant = DiscourseFormat.instant(raw) ?: return DayGroup.Earlier
    val day = instant.atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (day) {
        today -> DayGroup.Today
        today.minusDays(1) -> DayGroup.Yesterday
        else -> DayGroup.Earlier
    }
}

@Composable
private fun NotificationList(
    rows: List<InboxNotification>,
    meta: Map<Int, NotificationMeta>,
    loading: Boolean,
    error: Throwable?,
    hasMore: Boolean,
    loadingMore: Boolean,
    followed: Map<String, Boolean>,
    contentPadding: PaddingValues,
    header: LazyListScope.() -> Unit,
    onOpen: (InboxNotification, NotificationMeta?) -> Unit,
    onToggleRead: (InboxNotification) -> Unit,
    onFollowBack: (String, Boolean) -> Unit,
    onLoadMore: () -> Unit,
    navigator: Navigator,
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
        overscrollEffect = rememberTapSafeOverscroll(),
    ) {
        header()
        when {
            loading -> item(key = "loading") { RowsSkeleton() }
            rows.isEmpty() -> item(key = "empty") {
                if (error?.isOfflineError() == true) {
                    EmptyStateView(icon = Lucide.WifiOff, title = stringResource(R.string.error_offline))
                } else {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 72.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            stringResource(R.string.inbox3_empty_title),
                            style = Type.body(18, FontWeight.Bold),
                            color = Nocturne.text,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            stringResource(R.string.inbox3_empty_detail),
                            style = Type.body(14),
                            color = Nocturne.textSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            else -> {
                var lastGroup: DayGroup? = null
                rows.forEach { notification ->
                    val info = meta[notification.id]
                    val group = dayGroup(info?.createdAt)
                    if (group != lastGroup) {
                        lastGroup = group
                        item(key = "day-${group.name}-${notification.id}") {
                            Text(
                                stringResource(group.labelRes),
                                style = Type.body(13, FontWeight.Bold),
                                color = Nocturne.textSecondary,
                                modifier = Modifier.padding(start = Space.page, end = Space.page, top = 20.dp, bottom = 6.dp),
                            )
                        }
                    }
                    item(key = notification.id) {
                        val actor = info?.actor
                        val following = actor?.let { followed[it] } ?: info?.followingActor ?: false
                        NotificationRow(
                            notification = notification,
                            info = info,
                            following = following,
                            onClick = { onOpen(notification, info) },
                            onAvatar = actor?.let { { navigator.openProfile(it) } },
                            onToggleRead = { onToggleRead(notification) },
                            onFollowBack = actor?.let { { onFollowBack(it, following) } },
                        )
                    }
                }
                item(key = "footer") {
                    Box(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
                        when {
                            loadingMore -> NodelocLoader(height = 22.dp)
                            hasMore -> SecondaryButton(stringResource(R.string.inbox3_load_earlier), onClick = onLoadMore)
                            else -> Text(stringResource(R.string.inbox3_no_more), style = Type.body(13), color = Nocturne.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

/** The kind's small disc on the avatar's corner: what happened, at a glance. */
private data class KindMark(val icon: ImageVector, val ground: Color, val ink: Color)

@Composable
private fun kindMark(info: NotificationMeta?): KindMark = when (info?.category) {
    NoteCategory.Reply -> KindMark(Lucide.MessageCircle, Nocturne.accent, Nocturne.neutral100)
    NoteCategory.Mention -> KindMark(Lucide.AtSign, Nocturne.info, Nocturne.neutral100)
    NoteCategory.Vote -> KindMark(Lucide.ThumbsUp, Nocturne.accent2500, Nocturne.accent900)
    NoteCategory.Follow -> KindMark(Lucide.UserPlus, Nocturne.info, Nocturne.neutral100)
    NoteCategory.Points -> KindMark(NodelocIcons.Zap, Nocturne.warning, Nocturne.neutral100)
    NoteCategory.Moderation -> KindMark(Lucide.ShieldAlert, Nocturne.warning, Nocturne.neutral100)
    NoteCategory.System -> KindMark(Lucide.Bell, Nocturne.accent900, Nocturne.accent300)
    else -> if (info?.kind == "repost") {
        KindMark(Lucide.Repeat, Nocturne.accent, Nocturne.neutral100)
    } else {
        KindMark(Lucide.Bell, Nocturne.accent900, Nocturne.accent300)
    }
}

/** The word after the time: which chip this row lives under. */
@Composable
private fun kindLabel(info: NotificationMeta?): String? = when (info?.category) {
    NoteCategory.Reply -> stringResource(R.string.inbox2_filter_reply)
    NoteCategory.Mention -> stringResource(R.string.inbox3_filter_mention)
    NoteCategory.Vote -> stringResource(R.string.inbox3_filter_vote)
    NoteCategory.Follow -> stringResource(R.string.inbox3_filter_follow)
    NoteCategory.Points -> stringResource(R.string.inbox3_filter_points)
    NoteCategory.Moderation -> stringResource(R.string.inbox2_filter_mod)
    NoteCategory.System -> stringResource(R.string.inbox2_filter_system)
    else -> null
}

/**
 * What the actor did, worded by what it was done to: the post number says
 * whether a mention or a vote landed on the topic itself or on a comment in it.
 * A reply's number is the reply's own, so it says nothing about what was
 * answered and the sentence stays plain.
 */
@Composable
private fun actionText(info: NotificationMeta?): String {
    val onTopic = (info?.postNumber ?: 1) <= 1
    return when (info?.kind) {
        "reply" -> stringResource(R.string.inbox2_act_reply)
        "watching" -> stringResource(R.string.inbox2_act_watching)
        "mention" -> stringResource(if (onTopic) R.string.inbox3_act_mention_topic else R.string.inbox3_act_mention_comment)
        "vote" -> stringResource(if (onTopic) R.string.inbox3_act_vote_topic else R.string.inbox3_act_vote_comment)
        "follow" -> stringResource(R.string.inbox3_act_follow)
        "followed_topic", "node_topic" -> stringResource(R.string.inbox2_act_new_topic)
        "node_hot_topic" -> stringResource(R.string.inbox2_act_hot_topic)
        "message" -> stringResource(R.string.inbox2_act_message)
        "repost" -> stringResource(R.string.inbox2_act_repost)
        else -> stringResource(R.string.inbox2_act_other)
    }
}

/**
 * Avatar with its kind mark; who did what to which post, in one sentence; the
 * quoted excerpt; "3 分钟前 · 回复". 回关 on a follower not yet followed, and
 * the dot that marks it read — or back to unread.
 */
@Composable
private fun NotificationRow(
    notification: InboxNotification,
    info: NotificationMeta?,
    following: Boolean,
    onClick: () -> Unit,
    onAvatar: (() -> Unit)?,
    onToggleRead: () -> Unit,
    onFollowBack: (() -> Unit)?,
) {
    val systemLike = info?.category in setOf(NoteCategory.Moderation, NoteCategory.Points, NoteCategory.System) ||
        info?.actor == null
    val ground by animatedColor(if (notification.read) Nocturne.bg else Nocturne.accent100.copy(alpha = 0.6f), "noteGround")
    val press = rememberPressSource()
    Row(
        Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .pressScale(press)
            .clip(RoundedCornerShape(12.dp))
            .background(ground)
            .clickable(interactionSource = press, indication = ripple(), onClick = onClick)
            .padding(start = 8.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val mark = kindMark(info)
        Box(
            Modifier
                .size(40.dp)
                .then(if (onAvatar != null && !systemLike) Modifier.clickable(role = Role.Button, onClick = onAvatar) else Modifier),
        ) {
            if (systemLike) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Nocturne.accent900), contentAlignment = Alignment.Center) {
                    Icon(mark.icon, null, tint = Nocturne.accent300, modifier = Modifier.size(20.dp))
                }
            } else {
                RemoteAvatar(notification.avatarUrl, (info?.actor ?: notification.title).take(1).uppercase(), variant = notification.id % 2, size = 40.dp)
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 4.dp, y = 4.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Nocturne.bg)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(mark.ground),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(mark.icon, null, tint = mark.ink, modifier = Modifier.size(10.dp))
                }
            }
        }

        Column(Modifier.weight(1f)) {
            val weight = if (notification.read) FontWeight.Normal else FontWeight.Medium
            val headline = if (systemLike) {
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(info?.title ?: notification.title) }
                }
            } else {
                val actor = info?.actor ?: notification.title
                val many = (info?.actorCount ?: 1) > 1
                val who = if (many) stringResource(R.string.inbox3_actor_many, actor, info?.actorCount ?: 1) else actor
                val title = info?.topicTitle?.takeIf { it.isNotBlank() && info?.kind != "follow" }
                // Vote's old wording counted "and N others" inside the verb;
                // the actor now carries the count, so the verb stays plain.
                val action = actionText(info)
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(who) }
                    append(" ")
                    append(action)
                    if (title != null) {
                        append(" · ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(title) }
                    }
                }
            }
            Text(headline, style = Type.body(15, weight), color = Nocturne.text, maxLines = 3, overflow = TextOverflow.Ellipsis)

            val quote = if (systemLike) info?.body else info?.excerpt
            quote?.takeIf { it.isNotBlank() }?.let {
                val rule = Nocturne.divider
                Text(
                    it,
                    style = Type.body(14),
                    color = Nocturne.muted(0.82f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .drawBehind {
                            drawLine(rule, Offset(1.dp.toPx(), 0f), Offset(1.dp.toPx(), size.height), strokeWidth = 2.dp.toPx())
                        }
                        .padding(start = 10.dp),
                )
            }
            Text(
                listOfNotNull(notification.time.takeIf { it.isNotBlank() }, kindLabel(info)).joinToString(" · "),
                style = Type.body(12),
                color = Nocturne.textSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (info?.kind == "follow" && onFollowBack != null) {
            if (following) {
                SecondaryButton(stringResource(R.string.profile_following), modifier = Modifier.padding(top = 2.dp), onClick = onFollowBack)
            } else {
                PrimaryButton(stringResource(R.string.inbox3_follow_back), modifier = Modifier.padding(top = 2.dp), onClick = onFollowBack)
            }
        }

        // The dot is the switch: filled is unread, a ring is read, and
        // tapping it flips which — without opening anything.
        val dotLabel = stringResource(if (notification.read) R.string.inbox3_mark_unread else R.string.inbox3_mark_read)
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = dotLabel, onClick = onToggleRead),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (notification.read) Nocturne.textTertiary else Nocturne.accent, CircleShape)
                    .then(if (notification.read) Modifier else Modifier.background(Nocturne.accent)),
            )
        }
    }
}

/** The design's segmented button: equal cells in one outlined capsule. */
@Composable
private fun SegmentRow(
    segment: InboxSegment,
    notifications: Int,
    messages: Int,
    chats: Int,
    onSelect: (InboxSegment) -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Space.page, end = Space.page, top = 4.dp, bottom = 6.dp)
            .height(40.dp)
            .clip(shape)
            .border(1.dp, Nocturne.textTertiary, shape),
    ) {
        InboxSegment.visible.forEachIndexed { index, option ->
            if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Nocturne.textTertiary))
            val selected = option == segment
            val badge = when (option) {
                InboxSegment.Notifications -> notifications
                InboxSegment.Messages -> messages
                InboxSegment.Chats -> chats
            }
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (selected) Nocturne.accent100 else Nocturne.bg)
                    // selectable, not clickable: colour is the only thing that
                    // says which segment is current, and it reaches no screen reader.
                    .selectable(selected = selected, role = Role.Tab) { onSelect(option) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                Text(
                    stringResource(option.labelRes),
                    style = Type.body(14, FontWeight.Medium),
                    color = if (selected) Nocturne.accent800 else Nocturne.text,
                    maxLines = 1,
                )
                if (badge > 0) {
                    Text(
                        if (badge > 99) "99+" else "$badge",
                        style = Type.body(14, FontWeight.Medium),
                        color = if (selected) Nocturne.accent800 else Nocturne.text,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageList(
    state: com.nodeloc.app.core.store.MessageCenterState,
    viewModel: InboxViewModel,
    navigator: Navigator,
    contentPadding: PaddingValues,
) {
    Column {
        if (state.messageGroups.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Space.page, vertical = Space.s2),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                overscrollEffect = rememberTapSafeOverscroll(),
            ) {
                item {
                    GroupChip(stringResource(R.string.inbox_personal), state.selectedGroup == null) { viewModel.selectGroup(null) }
                }
                items(state.messageGroups) { group ->
                    GroupChip(group, state.selectedGroup == group) { viewModel.selectGroup(group) }
                }
            }
        }
        val conversations = state.visibleConversations
        if (conversations.isEmpty()) {
            EmptyStateView(icon = Lucide.MessageCircle, title = stringResource(R.string.inbox_empty_messages))
            return
        }
        LazyColumn(
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
            overscrollEffect = rememberTapSafeOverscroll(),
        ) {
            items(conversations, key = { it.id }) { conversation ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.markConversationRead(conversation.id)
                            navigator.openTopic(conversation.id)
                        }
                        .padding(horizontal = Space.page, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(Space.s3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RemoteAvatar(conversation.avatarUrl, conversation.letter, size = 38.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            conversation.title,
                            style = Type.body(14, FontWeight.SemiBold),
                            color = Nocturne.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            conversation.excerpt.ifEmpty { conversation.counterpartName.orEmpty() },
                            style = Type.body(12),
                            color = Nocturne.muted(0.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(conversation.time, style = Type.body(11), color = Nocturne.muted(0.4f))
                        if (conversation.unread) {
                            Spacer(Modifier.size(4.dp))
                            Box(Modifier.size(7.dp).clip(CircleShape).background(Nocturne.accent))
                        }
                    }
                }
                HairLine()
            }
        }
    }
}

@Composable
private fun ChannelList(
    state: com.nodeloc.app.core.store.MessageCenterState,
    navigator: Navigator,
    contentPadding: PaddingValues,
) {
    // A blocked person's direct message is still a place he is: it sits in
    // the inbox with his name on it and his last line under it. A group he is
    // in stays — the other people in it are not blocked, and his messages are
    // dropped inside.
    val blocked by ServiceLocator.get.blockedUsers.usernames.collectAsState()
    val channels = remember(state.channels, blocked) {
        if (blocked.isEmpty()) state.channels
        else state.channels.filterNot { channel ->
            channel.isDirectMessage && channel.memberUsernames.any { it in blocked }
        }
    }

    if (state.isLoading && channels.isEmpty()) {
        RowsSkeleton()
        return
    }
    if (channels.isEmpty()) {
        EmptyStateView(icon = Lucide.MessageCircle, title = stringResource(R.string.inbox_empty_chats))
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
        overscrollEffect = rememberTapSafeOverscroll(),
    ) {
        items(channels, key = { it.id }) { channel ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .clickable { navigator.openChat(channel.id) }
                    .padding(horizontal = Space.page, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(Space.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RemoteAvatar(channel.avatarUrl, channel.letter, size = 38.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(channel.title, style = Type.body(14, FontWeight.SemiBold), color = Nocturne.text, maxLines = 1)
                    Text(
                        channel.lastMessage,
                        style = Type.body(12),
                        color = Nocturne.muted(0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(channel.time, style = Type.body(11), color = Nocturne.muted(0.4f))
                    if (channel.unreadCount > 0) {
                        Spacer(Modifier.size(4.dp))
                        Box(
                            Modifier.clip(CircleShape).background(Nocturne.accent).padding(horizontal = 5.dp, vertical = 1.dp),
                        ) {
                            Text(
                                if (channel.unreadCount > 99) "99+" else "${channel.unreadCount}",
                                style = Type.body(10, FontWeight.SemiBold),
                                color = Nocturne.bg,
                            )
                        }
                    }
                }
            }
            HairLine()
        }
    }
}

@Composable
private fun GroupChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = Type.body(12, if (selected) FontWeight.SemiBold else FontWeight.Normal),
        color = if (selected) Nocturne.accent else Nocturne.muted(0.55f),
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) Nocturne.selected else Nocturne.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}
