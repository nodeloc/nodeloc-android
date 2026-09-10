package com.nodeloc.app.feature.chat

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.CheckCheck
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.WifiOff
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Menu
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.R
import com.nodeloc.app.core.design.Avatar
import com.nodeloc.app.core.design.CapsuleIconButton
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FloatingHeaderBar
import com.nodeloc.app.core.design.HeaderGroup
import com.nodeloc.app.core.design.GuestLoginButton
import com.nodeloc.app.core.design.HairLine
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.floatingHeaderInset
import com.nodeloc.app.core.design.rememberTapSafeOverscroll
import com.nodeloc.app.core.model.InboxNotification
import com.nodeloc.app.core.network.isOfflineError
import com.nodeloc.app.core.store.NotificationFormatter
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
    val isSignedIn by app.isSignedIn.collectAsState()
    var segment by remember { mutableStateOf(InboxSegment.entries.first()) }

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
                    InboxSegment.Notifications ->
                        NotificationList(state.notifications, navigator, contentPadding, state.error, viewModel::markNotificationRead)
                    InboxSegment.Messages -> MessageList(state, viewModel, navigator, contentPadding)
                    InboxSegment.Chats -> ChannelList(state, navigator, contentPadding)
                }
            }
        }

        FloatingHeaderBar(
            leading = { HeaderIconButton(Lucide.Menu, stringResource(R.string.common_menu), onClick = onOpenDrawer) },
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

@Composable
private fun SegmentRow(
    segment: InboxSegment,
    notifications: Int,
    messages: Int,
    chats: Int,
    onSelect: (InboxSegment) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.s3),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        InboxSegment.entries.forEach { option ->
            val selected = option == segment
            val badge = when (option) {
                InboxSegment.Notifications -> notifications
                InboxSegment.Messages -> messages
                InboxSegment.Chats -> chats
            }
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(if (selected) Nocturne.selected else Nocturne.surface)
                    // selectable, not clickable: colour and weight are the only
                    // things that said which segment was current, and neither
                    // reaches a screen reader.
                    .selectable(selected = selected, role = Role.Tab) { onSelect(option) }
                    .padding(horizontal = Space.card, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    stringResource(option.labelRes),
                    style = Type.body(13, if (selected) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (selected) Nocturne.accent else Nocturne.muted(0.6f),
                )
                if (badge > 0) {
                    Box(
                        Modifier.clip(CircleShape).background(Nocturne.accent).padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text("$badge", style = Type.body(10, FontWeight.SemiBold), color = Nocturne.bg)
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationList(
    notifications: List<InboxNotification>,
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
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        // Opening it is what makes it read — the whole list no
                        // longer clears itself just because the tab was tapped.
                        onRead(notification.id)
                        when {
                            notification.topicId != null ->
                                navigator.openTopic(notification.topicId, notification.postNumber)
                            notification.chatChannelId != null ->
                                navigator.openChat(notification.chatChannelId, notification.chatMessageId)
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
                    .padding(horizontal = Space.page, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(Space.s3),
            ) {
                Avatar(notification.title.take(1), variant = notification.id % 2, size = 34.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(notification.title, style = Type.body(14, FontWeight.SemiBold), color = Nocturne.text)
                    Text(
                        notification.detail,
                        style = Type.body(13),
                        color = Nocturne.muted(0.6f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(notification.time, style = Type.body(11), color = Nocturne.muted(0.4f))
                    if (!notification.read) {
                        Spacer(Modifier.size(4.dp))
                        Box(Modifier.size(7.dp).clip(CircleShape).background(Nocturne.accent))
                    }
                }
            }
            HairLine()
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
