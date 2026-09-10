package com.nodeloc.app.core.store

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import com.nodeloc.app.core.network.MessageBusClient
import com.nodeloc.app.core.network.BusChannels
import com.nodeloc.app.core.util.runCatchingCancellable
import android.content.Context
import com.nodeloc.app.R
import com.nodeloc.app.core.model.ChatChannelSummary
import com.nodeloc.app.core.model.ChatChannelsResponse
import com.nodeloc.app.core.model.DiscourseNotification
import com.nodeloc.app.core.model.InboxNotification
import com.nodeloc.app.core.model.PrivateMessageSummary
import com.nodeloc.app.core.model.PrivateMessagesResponse
import com.nodeloc.app.core.network.DiscourseClient
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.util.DiscourseFormat
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where a notification goes when tapped. */
sealed interface NotificationTarget {
    data class Topic(val topicId: Int, val postNumber: Int?) : NotificationTarget
    data class Chat(val channelId: Int, val messageId: Int?) : NotificationTarget
    data class Web(val url: String) : NotificationTarget
}

/**
 * One source of truth for notification wording, shared by the inbox rows and
 * the push banners so the same event never gets described two different ways.
 */
object NotificationFormatter {

    enum class Kind { Like, Comment, Message, System, Star }

    /**
     * Discourse's `notification_type` ids, as this deployment reports them in
     * `site.json`'s `notification_types` map — the core set plus the plugins
     * nodeloc runs. Plugin ids are assigned at registration, so they are
     * site-specific rather than universal; the `else` branch keeps an unknown
     * one merely miscategorised rather than lost.
     */
    object Type {
        const val MENTIONED = 1
        const val REPLIED = 2
        const val QUOTED = 3
        const val EDITED = 4
        const val LIKED = 5
        const val PRIVATE_MESSAGE = 6
        const val INVITED_TO_PRIVATE_MESSAGE = 7
        const val INVITEE_ACCEPTED = 8
        const val POSTED = 9
        const val MOVED_POST = 10
        const val LINKED = 11
        const val GRANTED_BADGE = 12
        const val INVITED_TO_TOPIC = 13
        const val CUSTOM = 14
        const val GROUP_MENTIONED = 15
        const val GROUP_MESSAGE_SUMMARY = 16
        const val WATCHING_FIRST_POST = 17
        const val TOPIC_REMINDER = 18
        const val LIKED_CONSOLIDATED = 19
        const val POST_APPROVED = 20
        const val MEMBERSHIP_REQUEST_ACCEPTED = 22
        const val MEMBERSHIP_REQUEST_CONSOLIDATED = 23
        const val BOOKMARK_REMINDER = 24
        const val REACTION = 25
        const val VOTES_RELEASED = 26
        const val EVENT_REMINDER = 27
        const val EVENT_INVITATION = 28
        const val CHAT_MENTION = 29
        const val CHAT_MESSAGE = 30
        const val CHAT_INVITATION = 31
        const val CHAT_GROUP_MENTION = 32
        const val CHAT_QUOTED = 33
        const val ASSIGNED = 34
        const val WATCHING_CATEGORY_OR_TAG = 36
        const val NEW_FEATURES = 37
        const val ADMIN_PROBLEMS = 38
        const val LINKED_CONSOLIDATED = 39
        const val CHAT_WATCHED_THREAD = 40
        const val BOOST = 43

        /** nodeloc's own plugins. */
        const val FOLLOWING = 800
        const val FOLLOWING_CREATED_TOPIC = 801
        const val FOLLOWING_REPLIED = 802
        const val REWARD_RECEIVED = 5000
        const val TOPIC_FEATURED = 6001
        const val LOTTERY_RESULT = 6002
    }

    fun kind(type: Int): Kind = when (type) {
        // Someone valued something you wrote. `reaction` is discourse-reactions;
        // `reward_received` is the site's own points plugin, which is one of the
        // things people are here for — it used to land in "other".
        Type.LIKED, Type.LIKED_CONSOLIDATED, Type.REACTION, Type.REWARD_RECEIVED -> Kind.Like

        // Addressed to you personally, and chat counts: these fell through to
        // Star, so a chat mention escaped the "private messages" toggle and
        // banner as "other".
        Type.PRIVATE_MESSAGE, Type.INVITED_TO_PRIVATE_MESSAGE, Type.EVENT_INVITATION,
        Type.CHAT_MENTION, Type.CHAT_MESSAGE, Type.CHAT_INVITATION, Type.CHAT_GROUP_MENTION,
        Type.CHAT_QUOTED, Type.CHAT_WATCHED_THREAD, Type.INVITED_TO_TOPIC,
        -> Kind.Message

        // Someone wrote something you follow.
        Type.MENTIONED, Type.REPLIED, Type.QUOTED, Type.POSTED, Type.EDITED, Type.LINKED,
        Type.GROUP_MENTIONED, Type.WATCHING_FIRST_POST, Type.WATCHING_CATEGORY_OR_TAG,
        Type.LINKED_CONSOLIDATED, Type.FOLLOWING, Type.FOLLOWING_CREATED_TOPIC,
        Type.FOLLOWING_REPLIED,
        -> Kind.Comment

        // The site talking about itself rather than about a person.
        Type.GROUP_MESSAGE_SUMMARY, Type.INVITEE_ACCEPTED, Type.MOVED_POST, Type.CUSTOM,
        Type.TOPIC_REMINDER, Type.POST_APPROVED, Type.MEMBERSHIP_REQUEST_ACCEPTED,
        Type.MEMBERSHIP_REQUEST_CONSOLIDATED, Type.BOOKMARK_REMINDER, Type.VOTES_RELEASED,
        Type.EVENT_REMINDER, Type.ASSIGNED, Type.NEW_FEATURES, Type.ADMIN_PROBLEMS,
        Type.BOOST, Type.LOTTERY_RESULT, Type.TOPIC_FEATURED,
        -> Kind.System

        else -> Kind.Star
    }

    fun displayName(context: Context, notification: DiscourseNotification, kind: Kind): String =
        if (kind == Kind.System) {
            context.getString(R.string.notif_system)
        } else {
            // Not the site's name: a row headed by the forum's own brand tells
            // the reader the one thing they already know, and it was a literal
            // in code rather than a string anyone could translate.
            notification.data?.displayUsername
                ?: notification.data?.username
                ?: context.getString(R.string.notif_system)
        }

    fun text(context: Context, notification: DiscourseNotification, kind: Kind): String {
        val data = notification.data
        if (kind == Kind.System) {
            val group = data?.groupName ?: context.getString(R.string.notif_group_fallback)
            return data?.inboxCount
                ?.let { context.resources.getQuantityString(R.plurals.notif_group_inbox_count, it, group, it) }
                ?: context.getString(R.string.notif_group_inbox, group)
        }
        if (kind == Kind.Message) {
            return data?.topicTitle ?: context.getString(R.string.notif_private_message)
        }
        data?.topicTitle?.let { title ->
            return when (kind) {
                Kind.Like -> context.getString(R.string.notif_liked, title)
                Kind.Comment -> context.getString(R.string.notif_replied, title)
                else -> title
            }
        }
        data?.badgeName?.let { return context.getString(R.string.notif_badge, it) }
        return context.getString(R.string.notif_generic)
    }

    fun target(notification: DiscourseNotification): NotificationTarget? {
        notification.topicId?.let { return NotificationTarget.Topic(it, notification.postNumber) }
        val data = notification.data
        data?.chatChannelId?.let { return NotificationTarget.Chat(it, data.chatMessageId) }
        data?.badgeId?.let {
            return NotificationTarget.Web("${DiscourseConfig.BASE_URL}/badges/$it/${data.badgeSlug ?: "-"}")
        }
        if (data?.groupName != null && data.username != null) {
            return NotificationTarget.Web(
                "${DiscourseConfig.BASE_URL}/u/${data.username}/messages/group/${data.groupName}",
            )
        }
        return null
    }

    fun row(context: Context, notification: DiscourseNotification): InboxNotification {
        val kind = kind(notification.notificationType)
        return InboxNotification(
            id = notification.id,
            type = notification.notificationType,
            read = notification.read,
            title = displayName(context, notification, kind),
            detail = text(context, notification, kind),
            time = DiscourseFormat.relative(notification.createdAt),
            topicId = notification.topicId,
            postNumber = notification.postNumber,
            slug = notification.slug,
            username = notification.data?.username,
            chatChannelId = notification.data?.chatChannelId,
            chatMessageId = notification.data?.chatMessageId,
            badgeId = notification.data?.badgeId,
            badgeSlug = notification.data?.badgeSlug,
            groupName = notification.data?.groupName,
        )
    }
}

data class MessageCenterState(
    val notifications: List<InboxNotification> = emptyList(),
    val conversations: List<PrivateMessageSummary> = emptyList(),
    val groupConversations: List<PrivateMessageSummary> = emptyList(),
    val messageGroups: List<String> = emptyList(),
    val selectedGroup: String? = null,
    val channels: List<ChatChannelSummary> = emptyList(),
    val unreadNotifications: Int = 0,
    val unreadPrivateMessages: Int = 0,
    val unreadChat: Int = 0,
    val isLoading: Boolean = false,
    val isLoadingGroup: Boolean = false,
    val needsLogin: Boolean = false,
    val error: Throwable? = null,
) {
    /** What the bottom Message tab badges. */
    val unreadTotal: Int get() = unreadNotifications + unreadPrivateMessages + unreadChat

    val visibleConversations: List<PrivateMessageSummary>
        get() = if (selectedGroup == null) conversations else groupConversations
}

/**
 * Shared inbox state: the tab badge and the inbox screen read the same counts
 * rather than each running its own fetch.
 */
class MessageCenterRepository(
    private val context: Context,
    private val client: DiscourseClient,
    private val session: SessionRepository,
    private val bus: MessageBusClient,
) : AccountScoped {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var badgeSubscription: MessageBusClient.Subscription? = null

    /** One subscription covering every channel this account follows. */
    private var chatSubscription: MessageBusClient.Subscription? = null
    private var watchedChatChannels: Set<String> = emptySet()
    private var chatRefresh: Job? = null
    private val _state = MutableStateFlow(MessageCenterState())
    val state: StateFlow<MessageCenterState> = _state.asStateFlow()

    private var loaded = false

    // The tab badge and the inbox screen both call load(); without this they
    // raced and each fired the same four requests.
    private val loadMutex = Mutex()

    suspend fun reload() = load(force = true)

    suspend fun load(force: Boolean = false) = loadMutex.withLock {
        if (!client.auth.isAuthenticated) {
            _state.value = MessageCenterState(needsLogin = true)
            loaded = false
            return@withLock
        }
        // Inside the lock, so a refresh requested while a load is in flight is
        // honoured rather than absorbed by it — that dropped the very
        // notification the bus had just signalled.
        if (loaded && !force) return@withLock
        _state.value = _state.value.copy(isLoading = true, needsLogin = false, error = null)

        var error: Throwable? = null
        // force: the cached CurrentUser is whatever bootstrap fetched, so its
        // unread count never moved and the badge was frozen at launch value.
        val current = session.refresh(force = true)
        val groups = current?.groups.orEmpty().filter { it.hasMessages == true }.map { it.name }

        val notifications = runCatchingCancellable { client.notifications().notifications }
            .onFailure { error = it }
            .getOrDefault(emptyList())
            .map { NotificationFormatter.row(context, it) }

        val username = session.username
        val conversations = if (username != null) {
            runCatchingCancellable { conversations(client.privateMessages(username), username) }
                .onFailure { if (error == null) error = it }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val channels = runCatchingCancellable { channels(client.chatChannels()) }
            .onFailure { if (error == null) error = it }
            .getOrDefault(emptyList())

        // copy, not a fresh state: rebuilding it silently dropped whichever
        // group inbox the user had selected.
        _state.value = _state.value.copy(
            notifications = notifications,
            conversations = conversations,
            messageGroups = groups,
            channels = channels,
            // Counted from the rows themselves, so the badge and the list it
            // opens onto cannot disagree. The server's own count is the
            // fallback for when that fetch is the one that failed.
            //
            // Messages and chat are excluded because the two counts below
            // already carry them — Discourse's own `unread_notifications`
            // leaves out the same high-priority kinds for the same reason, and
            // counting every row would show each private message twice.
            unreadNotifications = if (error == null || notifications.isNotEmpty()) {
                countUnread(notifications)
            } else {
                current?.unreadNotifications ?: 0
            },
            unreadPrivateMessages = conversations.count { it.unread },
            unreadChat = channels.sumOf { it.unreadCount },
            isLoading = false,
            error = error,
        )
        // Only a clean pass counts as loaded, or a failure caches itself until
        // something calls reload().
        loaded = error == null

        // Not `current?.id`. That is this minute's `session/current.json`, and
        // one flaky answer to it used to leave the whole session with nothing
        // subscribed — no badge, no live chat — until the inbox was opened by
        // hand. The cached user serves just as well for a subscription, and
        // the chat channels need no user at all.
        val userId = current?.id ?: session.currentUser.value?.id
        userId?.let(::watchNotifications)
        watchChat(userId, channels.map { channel -> channel.id })
    }

    /**
     * Keeps the chat rows and their unread counts live.
     *
     * A chat message raises no notification unless it names you, so the
     * notification channel above never hears about the ordinary traffic of a
     * group — the inbox sat on whatever it had fetched, and a conversation
     * could go all day without the list noticing. Discourse has no single
     * channel that says "your chat changed" either: the web subscribes to
     * `/chat/{id}/new-messages` per channel, which is why this resubscribes
     * whenever the set of channels does.
     *
     * `/chat/user-tracking-state/{id}` is the same user reading somewhere else
     * — the web, another device — and is what takes the badge back down.
     */
    private fun watchChat(userId: Int?, channelIds: List<Int>) {
        val wanted = buildList {
            // Only this one needs to know who is asking.
            userId?.let { add(BusChannels.chatUserTracking(it)) }
            channelIds.forEach { add(BusChannels.chatNewMessages(it)) }
        }
        if (wanted.isEmpty() || wanted.toSet() == watchedChatChannels) return
        watchedChatChannels = wanted.toSet()
        chatSubscription?.cancel()
        chatSubscription = bus.subscribe(wanted) { refreshChatSoon() }
    }

    /**
     * One refetch per burst.
     *
     * A busy group carries several messages a second, and each of them is an
     * event: without this, an active channel would have the inbox refetching
     * on a loop. The channel list alone is refetched — [reload] is four
     * requests, and nothing else on the inbox changed.
     */
    private fun refreshChatSoon() {
        chatRefresh?.cancel()
        chatRefresh = scope.launch {
            delay(CHAT_REFRESH_DEBOUNCE_MILLIS)
            refreshChannels()
        }
    }

    /** The chat rows, and nothing else. Silent: this is nobody's own action. */
    suspend fun refreshChannels() {
        if (!client.auth.isAuthenticated) return
        val channels = runCatchingCancellable { channels(client.chatChannels()) }.getOrNull() ?: return
        _state.value = _state.value.copy(
            channels = channels,
            unreadChat = channels.sumOf { it.unreadCount },
        )
    }

    /**
     * Keeps the badge live.
     *
     * Signal-only, like every other bus subscription here: the event says this
     * user's notifications changed, and the refetch goes through the mapper
     * that already exists rather than a second, divergent one.
     */
    private fun watchNotifications(userId: Int) {
        if (badgeSubscription != null) return
        badgeSubscription = bus.subscribe(listOf(BusChannels.notifications(userId))) {
            scope.launch { reload() }
        }
    }

    /**
     * One notification, read because the user opened it.
     *
     * Counted off the rows like [unreadNotifications] itself, so the segment
     * badge, the tab badge and the list cannot drift apart.
     */
    suspend fun markNotificationRead(id: Int) {
        val previous = _state.value
        val row = previous.notifications.firstOrNull { it.id == id } ?: return
        if (row.read) return
        _state.value = previous.copy(
            notifications = previous.notifications.map { if (it.id == id) it.copy(read = true) else it },
        ).let { it.copy(unreadNotifications = countUnread(it.notifications)) }
        runCatchingCancellable { client.markNotificationsRead(id) }.onFailure {
            _state.value = _state.value.copy(
                notifications = previous.notifications,
                unreadNotifications = previous.unreadNotifications,
            )
        }
    }

    /** The explicit "mark all read" action in the inbox header. */
    suspend fun markNotificationsRead() {
        val previous = _state.value
        if (previous.unreadNotifications == 0) return
        _state.value = previous.copy(
            unreadNotifications = 0,
            notifications = previous.notifications.map { it.copy(read = true) },
        )
        runCatchingCancellable { client.markNotificationsRead() }.onFailure {
            // Rolled back rather than toasted: opening the inbox is not an
            // action the user asked to succeed, but a badge that clears itself
            // and then silently comes back is worse than one that never moved.
            _state.value = _state.value.copy(
                unreadNotifications = previous.unreadNotifications,
                notifications = previous.notifications,
            )
        }
    }

    fun markConversationRead(id: Int) {
        val updated = _state.value.conversations.map { if (it.id == id) it.copy(unread = false) else it }
        _state.value = _state.value.copy(
            conversations = updated,
            unreadPrivateMessages = updated.count { it.unread },
        )
    }

    /**
     * The header's "mark all read", on the messages tab.
     *
     * A conversation is a topic and its unread flag is `last_read_post_number <
     * highest_post_number`, so the server-side move is the same bulk dismissal
     * the web client uses. Rolled back on failure for the same reason as
     * [markNotificationsRead].
     */
    suspend fun markAllConversationsRead() {
        val previous = _state.value
        val unread = previous.conversations.filter { it.unread }
        if (unread.isEmpty()) return
        _state.value = previous.copy(
            conversations = previous.conversations.map { it.copy(unread = false) },
            unreadPrivateMessages = 0,
        )
        runCatchingCancellable { client.dismissTopicPosts(unread.map { it.id }) }.onFailure {
            _state.value = _state.value.copy(
                conversations = previous.conversations,
                unreadPrivateMessages = previous.unreadPrivateMessages,
            )
        }
    }

    /**
     * The same, on the chat tab.
     *
     * Chat has no bulk endpoint: a channel is marked read by naming the message
     * the reader got to, so this is one call per channel. A channel whose last
     * message we do not know is left alone rather than guessed at.
     */
    suspend fun markAllChatRead() {
        val targets = _state.value.channels.filter { it.unreadCount > 0 && it.lastMessageId != null }
        targets.forEach { markChatChannelRead(it.id, it.lastMessageId!!) }
    }

    suspend fun markChatChannelRead(channelId: Int, messageId: Int) {
        val updated = _state.value.channels.map { if (it.id == channelId) it.copy(unreadCount = 0) else it }
        _state.value = _state.value.copy(channels = updated, unreadChat = updated.sumOf { it.unreadCount })
        runCatchingCancellable { client.markChatChannelRead(channelId, messageId) }
    }

    /**
     * Messages and chat are excluded because the two other counts already carry
     * them — Discourse's own `unread_notifications` leaves out the same
     * high-priority kinds for the same reason, and counting every row would
     * show each private message twice.
     */
    private fun countUnread(rows: List<InboxNotification>): Int = rows.count {
        !it.read && NotificationFormatter.kind(it.type) != NotificationFormatter.Kind.Message
    }

    /** `null` shows the personal inbox; a group name fetches that group's PMs. */
    suspend fun selectGroup(group: String?) {
        val username = session.username
        if (group == null || username == null) {
            _state.value = _state.value.copy(selectedGroup = null, groupConversations = emptyList())
            return
        }
        // A group arriving from a notification may not be in the fetched list
        // yet; surface it as a chip so the filter reads as selected.
        val groups = if (group in _state.value.messageGroups) {
            _state.value.messageGroups
        } else {
            _state.value.messageGroups + group
        }
        _state.value = _state.value.copy(selectedGroup = group, messageGroups = groups, isLoadingGroup = true)
        val result = runCatchingCancellable { conversations(client.groupPrivateMessages(username, group), username) }
            .getOrDefault(emptyList())
        // Guard against a slow response landing after the user switched away.
        if (_state.value.selectedGroup == group) {
            _state.value = _state.value.copy(groupConversations = result, isLoadingGroup = false)
        }
    }

    fun clear() {
        loaded = false
        _state.value = MessageCenterState(needsLogin = true)
    }

    override suspend fun resetForSignOut() {
        badgeSubscription?.cancel()
        badgeSubscription = null
        chatSubscription?.cancel()
        chatSubscription = null
        watchedChatChannels = emptySet()
        chatRefresh?.cancel()
        clear()
    }

    private companion object {
        /** Long enough to swallow a burst, short enough to feel immediate. */
        const val CHAT_REFRESH_DEBOUNCE_MILLIS = 500L
    }

    /**
     * The counterpart is whoever on the thread isn't the current user, avatar
     * resolved from the top-level `users`; unread is last-read trailing the
     * highest post.
     */
    private fun conversations(response: PrivateMessagesResponse, me: String): List<PrivateMessageSummary> {
        val usersById = response.users.orEmpty().associateBy { it.id }
        return response.topicList.topics.map { topic ->
            val participantIds = topic.participants.orEmpty().mapNotNull { it.userId }
            val counterpartId = participantIds.firstOrNull { usersById[it]?.username != me }
                ?: participantIds.firstOrNull()
            val counterpart = counterpartId?.let { usersById[it] }
            val name = counterpart?.name?.takeIf { it.isNotEmpty() } ?: counterpart?.username ?: context.getString(R.string.inbox_messages)
            PrivateMessageSummary(
                id = topic.id,
                title = topic.fancyTitle ?: topic.title ?: name,
                excerpt = DiscourseFormat.plainText(topic.excerpt),
                time = DiscourseFormat.relative(topic.lastPostedAt ?: topic.bumpedAt),
                unread = (topic.lastReadPostNumber ?: 0) < (topic.highestPostNumber ?: 0),
                counterpartName = name,
                avatarUrl = DiscourseConfig.avatarUrl(counterpart?.avatarTemplate),
                letter = name.take(1).uppercase(),
            )
        }
    }

    private fun channels(response: ChatChannelsResponse): List<ChatChannelSummary> {
        val tracking = response.tracking
        return response.allChannels.map { channel ->
            val title = channel.title ?: channel.unicodeTitle
                ?: channel.chatable?.users?.firstOrNull()?.username
                ?: channel.chatable?.name
                ?: context.getString(R.string.chat_channel)
            val membershipUnread = channel.currentUserMembership?.unreadCount ?: 0
            val trackedUnread = tracking?.state(channel.id)?.totalUnreadCount ?: 0
            ChatChannelSummary(
                id = channel.id,
                title = title,
                letter = title.take(1).uppercase(),
                avatarUrl = DiscourseConfig.avatarUrl(
                    channel.chatable?.users?.firstOrNull()?.avatarTemplate,
                ),
                lastMessage = channel.lastMessage?.excerpt?.let { DiscourseFormat.plainText(it) }
                    ?: channel.lastMessage?.message.orEmpty(),
                time = DiscourseFormat.relative(channel.lastMessage?.createdAt),
                unreadCount = maxOf(membershipUnread, trackedUnread),
                isDirectMessage = channel.isDirectMessage,
                lastMessageId = channel.lastMessage?.id,
                lastReadMessageId = channel.currentUserMembership?.lastReadMessageId,
                lastActivityAt = channel.lastMessage?.createdAt,
                memberUsernames = channel.chatable?.users.orEmpty().map { it.username }.filter { it.isNotEmpty() },
            )
        }
            // The response hands back every direct message first and every
            // group after, whatever has happened in them — which buries an
            // active group under conversations that ended weeks ago. Sorted by
            // what actually happened last instead. ISO-8601 in UTC sorts
            // chronologically as text, so no parsing is needed to do it.
            .sortedWith(
                compareByDescending<ChatChannelSummary> { it.lastActivityAt.orEmpty() }
                    .thenBy { it.title },
            )
    }
}
