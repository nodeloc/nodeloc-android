package com.nodeloc.app.feature.chat

import com.nodeloc.app.core.network.ServerFeatures
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.model.InboxNotification
import com.nodeloc.app.core.store.MessageCenterState
import com.nodeloc.app.core.store.NoteCategory
import com.nodeloc.app.core.store.NotificationMeta
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
 * The notification chips. [server] is the list filter the backend takes;
 * 版务 and 系统 both ask for `system` and are told apart here, by
 * [NotificationMeta.category].
 */
enum class InboxFilter(@StringRes val labelRes: Int, val server: String?, val category: NoteCategory?) {
    All(R.string.inbox2_filter_all, null, null),
    Reply(R.string.inbox2_filter_reply, "reply", null),
    Mention(R.string.inbox2_filter_mention, "mention", null),
    Moderation(R.string.inbox2_filter_mod, "system", NoteCategory.Moderation),
    System(R.string.inbox2_filter_system, "system", NoteCategory.System),
}

data class InboxFilterState(
    val filter: InboxFilter = InboxFilter.All,
    /** Null for [InboxFilter.All], which reads the shared list instead. */
    val rows: List<InboxNotification>? = null,
    val meta: Map<Int, NotificationMeta> = emptyMap(),
    val loading: Boolean = false,
    val error: Throwable? = null,
)

/** Thin ViewModel over the shared inbox repository — the badge reads it too. */
class InboxViewModel : ViewModel() {
    private val repository = ServiceLocator.get.messageCenter

    val state: StateFlow<MessageCenterState> = repository.state

    private val _filter = MutableStateFlow(InboxFilterState())
    val filter: StateFlow<InboxFilterState> = _filter.asStateFlow()
    private var filterJob: Job? = null

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
            runCatchingCancellable { repository.fetchNotifications(filter.server) }
                .onSuccess { found ->
                    val kept = found.filter { filter.category == null || it.second.category == filter.category }
                    _filter.value = InboxFilterState(
                        filter = filter,
                        rows = kept.map { it.first },
                        meta = kept.associate { it.first.id to it.second },
                    )
                }
                .onFailure { error ->
                    _filter.value = _filter.value.copy(loading = false, error = error, rows = _filter.value.rows ?: emptyList())
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
        _filter.value.rows?.let { rows ->
            _filter.value = _filter.value.copy(rows = rows.map { if (it.id == id) it.copy(read = true) else it })
        }
        viewModelScope.launch { repository.markNotificationRead(id) }
    }

    fun markConversationRead(id: Int) = repository.markConversationRead(id)

    fun selectGroup(group: String?) {
        viewModelScope.launch { repository.selectGroup(group) }
    }

}
