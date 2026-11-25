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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.CheckCheck
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldAlert
import com.composables.icons.lucide.WifiOff
import com.composables.icons.lucide.MessageCircle
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.R
import com.nodeloc.app.core.design.CapsuleIconButton
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FilterPill
import com.nodeloc.app.core.design.FilterPillRow
import com.nodeloc.app.core.design.FloatingHeaderBar
import com.nodeloc.app.core.design.HeaderGroup
import com.nodeloc.app.core.design.GuestLoginButton
import com.nodeloc.app.core.design.HairLine
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocIcons
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.floatingHeaderInset
import com.nodeloc.app.core.design.rememberTapSafeOverscroll
import com.nodeloc.app.core.model.InboxNotification
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.network.isOfflineError
import com.nodeloc.app.core.store.NoteCategory
import com.nodeloc.app.core.store.NotificationFormatter
import com.nodeloc.app.core.store.NotificationMeta
import com.nodeloc.app.core.store.NotificationTarget
import com.nodeloc.app.feature.feed.RowsSkeleton
import com.nodeloc.app.feature.nav.AppViewModel
import com.nodeloc.app.feature.nav.Navigator

/** 收件箱: notifications, private messages and chat channels in one tab. */
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
    val isSignedIn by app.isSignedIn.collectAsState()
    val privileges by ServiceLocator.get.moderation.privileges.collectAsState()
    var segment by remember { mutableStateOf(InboxSegment.visible.first()) }

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
        if (!privileges.isModerator && filterState.filter == InboxFilter.Moderation) viewModel.selectFilter(InboxFilter.All)
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
                if (segment == InboxSegment.Notifications) {
                    val modUnread = state.notifications.count { row ->
                        !row.read && state.notificationMeta[row.id]?.category == NoteCategory.Moderation
                    }
                    FilterPillRow(contentPadding = PaddingValues(start = Space.page, end = Space.page, bottom = 10.dp)) {
                        InboxFilter.entries
                            .filter { it != InboxFilter.Moderation || privileges.isModerator }
                            .forEach { option ->
                                FilterPill(
                                    text = stringResource(option.labelRes),
                                    selected = option == filterState.filter,
                                    count = if (option == InboxFilter.Moderation) modUnread else null,
                                    onClick = { viewModel.selectFilter(option) },
                                )
                            }
                    }
                }
                HairLine()
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

                state.isLoading && state.notifications.isEmpty() -> RowsSkeleton()

                else -> when (segment) {
                    InboxSegment.Notifications -> {
                        val rows = filterState.rows
                        when {
                            rows == null -> NotificationList(
                                state.notifications, state.notificationMeta, navigator, contentPadding, state.error,
                                viewModel::markNotificationRead,
                            )
                            filterState.loading -> RowsSkeleton()
                            else -> NotificationList(
                                rows, filterState.meta, navigator, contentPadding, filterState.error,
                                viewModel::markNotificationRead,
                            )
                        }
                    }
                    InboxSegment.Messages -> MessageList(state, viewModel, navigator, contentPadding)
                    InboxSegment.Chats -> ChannelList(state, navigator, contentPadding)
                }
            }
        }

        FloatingHeaderBar(
            leading = { com.nodeloc.app.feature.nav.DrawerMenuButton(onClick = onOpenDrawer) },
            center = {
                HeaderGroup {
                    Text(
                        stringResource(R.string.inbox_title),
                        style = Type.heading(17, FontWeight.SemiBold),
                        color = Nocturne.headerText,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                }
            },
            trailing = {
                if (isSignedIn) {
                    HeaderGroup {
                        CapsuleIconButton(Lucide.CheckCheck, stringResource(R.string.inbox_mark_all_read)) {
                            viewModel.markSegmentRead(segment)
                        }
                    }
                } else {
                    GuestLoginButton(onClick = { navigator.openAuth() })
                }
            },
        )
    }
}

/** The design's segmented button: three equal cells in one outlined capsule. */
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
            .padding(start = Space.page, end = Space.page, top = 4.dp, bottom = 10.dp)
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
private fun NotificationList(
    notifications: List<InboxNotification>,
    meta: Map<Int, NotificationMeta>,
    navigator: Navigator,
    contentPadding: PaddingValues,
    error: Throwable?,
    onRead: (Int) -> Unit,
) {
    if (notifications.isEmpty()) {
        EmptyStateView(
            icon = if (error?.isOfflineError() == true) Lucide.WifiOff else Lucide.MessageCircle,
            title = stringResource(if (error?.isOfflineError() == true) R.string.error_offline else R.string.inbox_empty_notifications),
        )
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    LazyColumn(
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
        overscrollEffect = rememberTapSafeOverscroll(),
    ) {
        items(notifications, key = { it.id }) { notification ->
            val info = meta[notification.id]
            NotificationRow(notification, info) {
                // Opening it is what makes it read — the whole list no
                // longer clears itself just because the tab was tapped.
                onRead(notification.id)
                val path = info?.path
                when {
                    notification.topicId != null ->
                        navigator.openTopic(notification.topicId, notification.postNumber)
                    notification.chatChannelId != null ->
                        navigator.openChat(notification.chatChannelId, notification.chatMessageId)
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
            HairLine()
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

/** Icon, bold actor and what they did, a line of what it was about, time and the unread dot. */
@Composable
private fun NotificationRow(notification: InboxNotification, info: NotificationMeta?, onClick: () -> Unit) {
    val systemLike = info?.category in setOf(NoteCategory.Moderation, NoteCategory.Points, NoteCategory.System) ||
        info?.actor == null
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (notification.read) Nocturne.bg else Nocturne.accent100.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.page, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (systemLike) {
            val (icon, ground, ink) = when (info?.category) {
                NoteCategory.Moderation -> Triple(Lucide.ShieldAlert, Nocturne.warningBg, Nocturne.warning)
                NoteCategory.Points -> Triple(NodelocIcons.Zap, Nocturne.warningBg, Nocturne.warning)
                else -> Triple(Lucide.Bell, Nocturne.neutral300, Nocturne.text)
            }
            Box(Modifier.size(40.dp).clip(CircleShape).background(ground), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = ink, modifier = Modifier.size(20.dp))
            }
        } else {
            RemoteAvatar(notification.avatarUrl, notification.title.take(1).uppercase(), variant = notification.id % 2, size = 40.dp)
        }
        Column(Modifier.weight(1f)) {
            val headline = if (systemLike) {
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(info?.title ?: notification.title)
                    }
                }
            } else {
                val action = actionText(info)
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(info?.actor ?: notification.title) }
                    append(" ")
                    append(action)
                }
            }
            Text(headline, style = Type.body(15), color = Nocturne.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val second = if (systemLike) {
                info?.body ?: info?.topicTitle ?: notification.detail.takeIf { info == null }
            } else {
                info?.excerpt ?: info?.topicTitle ?: notification.detail
            }
            second?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = Type.body(14), color = Nocturne.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(notification.time, style = Type.body(12), color = Nocturne.textTertiary)
            if (!notification.read) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Nocturne.accent))
            }
        }
    }
}

@Composable
private fun actionText(info: NotificationMeta?): String = when (info?.kind) {
    "reply" -> stringResource(R.string.inbox2_act_reply)
    "watching" -> stringResource(R.string.inbox2_act_watching)
    "mention" -> stringResource(R.string.inbox2_act_mention)
    "vote" -> if ((info?.actorCount ?: 1) > 1) {
        stringResource(R.string.inbox2_act_vote_many, (info?.actorCount ?: 1) - 1)
    } else {
        stringResource(R.string.inbox2_act_vote)
    }
    "follow" -> stringResource(R.string.inbox2_act_follow)
    "followed_topic", "node_topic" -> stringResource(R.string.inbox2_act_new_topic)
    "node_hot_topic" -> stringResource(R.string.inbox2_act_hot_topic)
    "message" -> stringResource(R.string.inbox2_act_message)
    "repost" -> stringResource(R.string.inbox2_act_repost)
    else -> stringResource(R.string.inbox2_act_other)
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
