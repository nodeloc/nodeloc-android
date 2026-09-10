package com.nodeloc.app.feature.chat

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.store.MessageCenterState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Declaration order is the order of the chips, and the first is what the inbox
 * opens on. Chat leads: it is the only one of the three where somebody is
 * waiting for an answer.
 */
enum class InboxSegment(@StringRes val labelRes: Int) {
    Chats(R.string.inbox_chats),
    Messages(R.string.inbox_messages),
    Notifications(R.string.inbox_notifications),
}

/** Thin ViewModel over the shared inbox repository — the badge reads it too. */
class InboxViewModel : ViewModel() {
    private val repository = ServiceLocator.get.messageCenter

    val state: StateFlow<MessageCenterState> = repository.state

    fun load() {
        viewModelScope.launch { repository.load() }
    }

    fun refresh() {
        viewModelScope.launch { repository.reload() }
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
                InboxSegment.Notifications -> repository.markNotificationsRead()
                InboxSegment.Messages -> repository.markAllConversationsRead()
                InboxSegment.Chats -> repository.markAllChatRead()
            }
        }
    }

    fun markNotificationRead(id: Int) {
        viewModelScope.launch { repository.markNotificationRead(id) }
    }

    fun markConversationRead(id: Int) = repository.markConversationRead(id)

    fun selectGroup(group: String?) {
        viewModelScope.launch { repository.selectGroup(group) }
    }

}
