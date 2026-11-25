package com.nodeloc.app.feature.chat

import com.nodeloc.app.core.network.ServerFeatures
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.model.InboxNotification
import com.nodeloc.app.core.store.MessageCenterState
import com.nodeloc.app.core.store.NoteCategory
import com.nodeloc.app.core.store.NotificationMeta
import com.nodeloc.app.core.util.rethrowIfCancellation
import com.nodeloc.app.core.util.runCatchingCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Declaration order is the order of the chips, and the first is what the inbox
 * opens on. The design leads with notifications: they are what the tab's
 * badge mostly counts.
 */
enum class InboxSegment(@StringRes val labelRes: Int) {
    Notifications(R.string.inbox_notifications),
    Messages(R.string.inbox_messages),
    Chats(R.string.inbox_chats),
    ;

    companion object {
        /** Topic-style private messages are gone; conversations are under Chats. */
        val visible: List<InboxSegment>
            get() = entries.filter { it != Messages || ServerFeatures.PRIVATE_MESSAGE_TOPICS }
    }
}

/**
 * The notification chips. [server] is the backend's own filter; [categories]
 * narrows its answer further where the server has no word for the split.
 *
 * 版务 has no server filter (moderation notes are `system` notes, told apart
 * by where they point), so it asks for `system` and keeps the moderation ones;
 * 系统 then leaves them out — but only for a moderator, who has the 版务 chip
 * to find them under. Anyone else would lose them entirely.
 */
enum class InboxFilter(@StringRes val labelRes: Int, val server: String?, val category: NoteCategory?) {
    All(R.string.inbox2_filter_all, null, null),
    Unread(R.string.inbox3_filter_unread, "unread", null),
    Reply(R.string.inbox2_filter_reply, "reply", NoteCategory.Reply),
    Mention(R.string.inbox3_filter_mention, "mention", NoteCategory.Mention),
    Vote(R.string.inbox3_filter_vote, "vote", NoteCategory.Vote),
    Follow(R.string.inbox3_filter_follow, "follow", NoteCategory.Follow),
    Points(R.string.inbox3_filter_points, "points", NoteCategory.Points),
    System(R.string.inbox2_filter_system, "system", NoteCategory.System),
    Moderation(R.string.inbox2_filter_mod, "system", NoteCategory.Moderation),
    ;

    fun keeps(meta: NotificationMeta, moderator: Boolean): Boolean = when (this) {
        Moderation -> meta.category == NoteCategory.Moderation
        System -> meta.category == NoteCategory.System || (!moderator && meta.category == NoteCategory.Moderation)
        else -> true
    }
}

data class InboxFilterState(
    val filter: InboxFilter = InboxFilter.All,
    /** Null for [InboxFilter.All], which reads the shared list instead. */
    val rows: List<InboxNotification>? = null,
    val meta: Map<Int, NotificationMeta> = emptyMap(),
    val nextBefore: Long? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: Throwable? = null,
)

/** Thin ViewModel over the shared inbox repository — the badge reads it too. */
class InboxViewModel : ViewModel() {
    private val repository = ServiceLocator.get.messageCenter
    private val client = ServiceLocator.get.client

    val state: StateFlow<MessageCenterState> = repository.state

    private val _filter = MutableStateFlow(InboxFilterState())
    val filter: StateFlow<InboxFilterState> = _filter.asStateFlow()
    private var filterJob: Job? = null

    /** Follow-backs in flight or done this visit, by actor; wins over what the list was fetched with. */
    private val _followed = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val followed: StateFlow<Map<String, Boolean>> = _followed.asStateFlow()

    private val _loadingMoreAll = MutableStateFlow(false)
    val loadingMoreAll: StateFlow<Boolean> = _loadingMoreAll.asStateFlow()

    private var moderator = false

    fun setModerator(value: Boolean) {
        if (moderator == value) return
        moderator = value
        if (_filter.value.filter == InboxFilter.System) fetchFilter(InboxFilter.System)
    }

    fun load() {
        viewModelScope.launch { repository.load() }
    }

    fun refresh() {
        viewModelScope.launch { repository.reload() }
        if (_filter.value.filter != InboxFilter.All) fetchFilter(_filter.value.filter)
    }

    fun selectFilter(filter: InboxFilter) {
        if (filter == InboxFilter.All) {
            filterJob?.cancel()
            _filter.value = InboxFilterState()
            return
        }
        if (filter == _filter.value.filter && _filter.value.rows != null) return
        _filter.value = InboxFilterState(filter = filter, loading = true)
        fetchFilter(filter)
    }

    private fun fetchFilter(filter: InboxFilter) {
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            runCatchingCancellable { repository.fetchNotificationPage(filter.server) }
                .onSuccess { page ->
                    val kept = page.rows.filter { filter.keeps(it.second, moderator) }
                    _filter.value = InboxFilterState(
                        filter = filter,
                        rows = kept.map { it.first },
                        meta = kept.associate { it.first.id to it.second },
                        nextBefore = page.nextBefore,
                    )
                }
                .onFailure { error ->
                    _filter.value = _filter.value.copy(loading = false, error = error, rows = _filter.value.rows ?: emptyList())
                }
        }
    }

    /** "加载更早的通知": the next page of whichever list is on screen. */
    fun loadMore() {
        val current = _filter.value
        if (current.filter == InboxFilter.All) {
            if (_loadingMoreAll.value) return
            viewModelScope.launch {
                _loadingMoreAll.value = true
                runCatchingCancellable { repository.loadMoreNotifications() }.onFailure { ToastCenter.showError(it) }
                _loadingMoreAll.value = false
            }
            return
        }
        val before = current.nextBefore ?: return
        if (current.loadingMore) return
        val filter = current.filter
        filterJob = viewModelScope.launch {
            _filter.value = _filter.value.copy(loadingMore = true)
            runCatchingCancellable { repository.fetchNotificationPage(filter.server, before) }
                .onSuccess { page ->
                    val now = _filter.value
                    if (now.filter != filter) return@onSuccess
                    val known = now.rows.orEmpty().map { it.id }.toSet()
                    val kept = page.rows.filter { it.first.id !in known && filter.keeps(it.second, moderator) }
                    _filter.value = now.copy(
                        rows = now.rows.orEmpty() + kept.map { it.first },
                        meta = now.meta + kept.associate { it.first.id to it.second },
                        nextBefore = page.nextBefore,
                        loadingMore = false,
                    )
                }
                .onFailure {
                    _filter.value = _filter.value.copy(loadingMore = false)
                    ToastCenter.showError(it)
                }
        }
    }

    /** The explicit "mark all read" action in the header. */
    fun markNotificationsRead() {
        viewModelScope.launch { repository.markNotificationsRead() }
    }

    /**
     * The header button clears whichever tab is open.
     *
     * It used to call [markNotificationsRead] whatever was on screen, so on
     * messages and chat it marked the notifications tab read and left the list
     * in front of the user exactly as it was — a button that did something
     * invisible somewhere else.
     */
    fun markSegmentRead(segment: InboxSegment) {
        viewModelScope.launch {
            when (segment) {
                InboxSegment.Notifications -> {
                    repository.markNotificationsRead()
                    _filter.value.rows?.let { rows ->
                        _filter.value = _filter.value.copy(rows = rows.map { it.copy(read = true) })
                    }
                }
                InboxSegment.Messages -> repository.markAllConversationsRead()
                InboxSegment.Chats -> repository.markAllChatRead()
            }
        }
    }

    fun markNotificationRead(id: Int) {
        // The filtered copy flips with the shared one; it is a separate fetch
        // and would otherwise keep its dot until the chip is chosen again.
        setFilteredRead(id, true)
        viewModelScope.launch { repository.markNotificationRead(id) }
    }

    /** The row's own dot: read becomes unread and back, as the reader chooses. */
    fun toggleRead(notification: InboxNotification) {
        if (!notification.read) {
            markNotificationRead(notification.id)
            return
        }
        setFilteredRead(notification.id, false)
        viewModelScope.launch {
            try {
                repository.markNotificationUnread(notification.id)
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                setFilteredRead(notification.id, true)
                ToastCenter.showError(error)
            }
        }
    }

    private fun setFilteredRead(id: Int, read: Boolean) {
        _filter.value.rows?.let { rows ->
            _filter.value = _filter.value.copy(rows = rows.map { if (it.id == id) it.copy(read = read) else it })
        }
    }

    /**
     * 回关, optimistic: the button flips at once, every note from the same
     * person with it, and all of it goes back if the server says no.
     */
    fun toggleFollowBack(actor: String, currentlyFollowing: Boolean) {
        val target = !currentlyFollowing
        val previous = _followed.value
        _followed.value = previous + (actor to target)
        viewModelScope.launch {
            runCatchingCancellable { if (target) client.follow(actor) else client.unfollow(actor) }
                .onSuccess { repository.setFollowingActor(actor, target) }
                .onFailure {
                    val before = previous[actor]
                    _followed.value = if (before != null) _followed.value + (actor to before) else _followed.value - actor
                    ToastCenter.showError(it)
                }
        }
    }

    fun markConversationRead(id: Int) = repository.markConversationRead(id)

    fun selectGroup(group: String?) {
        viewModelScope.launch { repository.selectGroup(group) }
    }
}
