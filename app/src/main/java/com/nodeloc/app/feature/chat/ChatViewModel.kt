package com.nodeloc.app.feature.chat

import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.core.util.rethrowIfCancellation
import com.nodeloc.app.core.util.sha1Hex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.model.ChatChannel
import com.nodeloc.app.core.model.ChatMembership
import com.nodeloc.app.core.model.ChatMessage
import com.nodeloc.app.core.model.ChatMessagesResponse
import com.nodeloc.app.core.model.ChatReaction
import com.nodeloc.app.core.model.ChatThreadSummary
import com.nodeloc.app.core.model.ChatUser
import com.nodeloc.app.core.network.BusChannels
import com.nodeloc.app.core.network.DiscourseClient
import com.nodeloc.app.core.network.MessageBusClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A message this device has sent and the server has not confirmed.
 *
 * Chat is judged on the gap between pressing send and seeing the words, and
 * that gap used to be a whole round trip — upload, post, refetch — with an
 * empty conversation in the meantime. This stands in for that.
 */
data class PendingChatMessage(
    val localId: Long,
    val text: String = "",
    val failed: Boolean = false,
    /**
     * A local picture or the poster of a local clip, shown while it goes up.
     *
     * The point of it is that the message is in the conversation from the
     * moment it is sent. Waiting for the upload meant staring at the editor
     * for however long it took and then being dropped into the channel with
     * the thing already sent, which reads as though nothing happened until
     * suddenly everything had.
     */
    val mediaPath: String? = null,
    val isVideo: Boolean = false,
    /** Shown on the clip's own tile, as it will be once the server has it. */
    val durationMs: Long = 0L,
    /** 0..1 while the bytes go up, null before the request starts. */
    val progress: Float? = null,
)

data class ChatState(
    val channelId: Int = 0,
    val title: String = "",
    val messages: List<ChatMessage> = emptyList(),
    /**
     * Whose messages to leave out.
     *
     * Chat does none of this itself: the plugin's queries have no mention of
     * `IgnoredUser` anywhere, so a blocked person keeps talking in every
     * channel exactly as before. Held on the state rather than filtered at the
     * list, because the rows are built once and everything after them —
     * sticking to the end, jumping to a linked message — counts into them.
     */
    val blocked: Set<String> = emptySet(),
    val pending: List<PendingChatMessage> = emptyList(),
    val threads: List<ChatThreadSummary> = emptyList(),
    val openThreadId: Int? = null,
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    /** How far the current video upload has got, or null when none is. */
    val uploadProgress: Float? = null,
    val fromSnapshot: Boolean = false,
    /** More history above; false once the top of the conversation is loaded. */
    val canLoadMorePast: Boolean = false,
    val isLoadingOlder: Boolean = false,
    /** Nothing newer than what is loaded — so the end of the list is the end. */
    val atNewest: Boolean = true,
    /**
     * Where this reader had got to when the channel was opened, frozen for the
     * length of the visit: the divider must not walk down the screen as the
     * messages under it are marked read.
     */
    val unreadFrom: Int? = null,
    val replyTo: ChatMessage? = null,
    val editing: ChatMessage? = null,
    /** The channel itself, for everything the overflow menu offers. */
    val channel: ChatChannel? = null,
    /** Only fetched for a channel; a direct message names its people already. */
    val members: List<ChatUser> = emptyList(),
    /** Names, already excluding this reader. */
    val typing: List<String> = emptyList(),
    val error: Throwable? = null,
) {
    /** What the conversation shows, which is not everything it was sent. */
    val visibleMessages: List<ChatMessage>
        get() = if (blocked.isEmpty()) messages
        else messages.filterNot { it.user?.username in blocked }
}

/**
 * One chat conversation.
 *
 * Opening renders the disk snapshot immediately and reconciles against the
 * network behind it, which is what makes a channel feel instant on a cold
 * start. Live updates arrive over MessageBus, and an event is treated purely
 * as "this channel changed": the page is refetched through the same mapper
 * rather than the event payload being parsed into a second, divergent shape.
 */
class ChatViewModel : ViewModel() {
    private val services = ServiceLocator.get
    private val client: DiscourseClient = services.client
    private val cache = services.chatDiskCache
    private val bus = services.messageBus
    private var subscription: MessageBusClient.Subscription? = null
    private var presenceJob: Job? = null
    private var typingUntil = 0L
    private var lastMarkedRead = 0
    private var nextLocalId = -1L

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private var bound = false

    init {
        // Blocking from a message's own sheet should take the message with it,
        // and the only thing that changes at that moment is this set.
        viewModelScope.launch {
            services.blockedUsers.usernames.collect { names ->
                if (names != _state.value.blocked) _state.value = _state.value.copy(blocked = names)
            }
        }
    }

    fun bind(channelId: Int, targetMessageId: Int?, threadId: Int?) {
        if (bound) return
        bound = true
        // The inbox already knows this channel's name; reuse it so the header
        // has a title before the first network round-trip lands.
        val known = services.messageCenter.state.value.channels.firstOrNull { it.id == channelId }
        _state.value = ChatState(
            channelId = channelId,
            title = known?.title.orEmpty(),
            isLoading = true,
            openThreadId = threadId,
        )

        // A snapshot is only correct for the default "latest" view — jumping to
        // a specific message must not replay a cached page.
        if (targetMessageId == null && threadId == null) {
            cache.load(channelId)?.let { raw ->
                runCatchingCancellable { DiscourseClient.json.decodeFromString<ChatMessagesResponse>(raw) }
                    .getOrNull()
                    ?.let { snapshot ->
                        _state.value = _state.value.copy(messages = snapshot.messages, fromSnapshot = true)
                    }
            }
        }

        viewModelScope.launch {
            markUnreadFrom(channelId)
            reconcile(targetMessageId, threadId)
            loadThreads()
            loadChannel()
            subscribe()
            watchPresence()
        }
    }

    /**
     * The channel record, which the messages do not carry in full: who is in a
     * direct message, and what this reader's notification level is.
     *
     * A group's members are a separate call — a direct message lists its people
     * on the chatable, but a category channel has however many the node has.
     */
    private suspend fun loadChannel() {
        val channelId = _state.value.channelId
        val channel = runCatchingCancellable { client.chatChannel(channelId).channel }.getOrNull()
            ?: return
        _state.value = _state.value.copy(
            channel = channel,
            title = _state.value.title.ifEmpty { channel.title ?: channel.unicodeTitle.orEmpty() },
            members = channel.chatable?.users.orEmpty(),
        )
        if (channel.isDirectMessage) return
        runCatchingCancellable { client.chatChannelMembers(channelId) }
            .getOrNull()
            ?.let { response ->
                _state.value = _state.value.copy(members = response.memberships.mapNotNull { it.user })
            }
    }

    fun setNotificationLevel(level: String) {
        val channelId = _state.value.channelId
        val channel = _state.value.channel ?: return
        // Optimistic: the menu closes on the tap, so a level that only appears
        // after a round trip appears to have been ignored.
        _state.value = _state.value.copy(
            channel = channel.copy(
                currentUserMembership = (channel.currentUserMembership ?: ChatMembership())
                    .copy(notificationLevel = level),
            ),
        )
        viewModelScope.launch {
            runCatchingCancellable { client.setChatNotificationLevel(channelId, level) }
                .onFailure {
                    ToastCenter.showError(it)
                    _state.value = _state.value.copy(channel = channel)
                }
        }
    }

    /** [onLeft] runs on success only — the screen closes on it. */
    fun leaveChannel(onLeft: () -> Unit) {
        val channelId = _state.value.channelId
        viewModelScope.launch {
            runCatchingCancellable { client.leaveChatChannel(channelId) }
                .onSuccess {
                    // The inbox is the list this just left; without a refresh
                    // the row stays until something else happens to fetch.
                    services.messageCenter.refreshChannels()
                    onLeft()
                }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    /**
     * The divider's anchor, read before anything is marked read.
     *
     * Taken from the inbox's copy of the membership rather than fetched: the
     * channel list carries `last_read_message_id`, and asking again would race
     * the mark-read this screen is about to do.
     */
    private fun markUnreadFrom(channelId: Int) {
        val membership = services.messageCenter.state.value.channels
            .firstOrNull { it.id == channelId }
        val lastRead = membership?.lastReadMessageId ?: return
        if ((membership.unreadCount) <= 0) return
        _state.value = _state.value.copy(unreadFrom = lastRead)
    }

    /**
     * Rising on every reconcile, so a slower earlier one cannot land last.
     *
     * Opening a thread starts a fetch while a bus-triggered refetch of the
     * channel may still be in flight — and whichever answered second won, so
     * the thread could be replaced by the channel the reader had just left.
     */
    private var reconcileGeneration = 0

    private suspend fun reconcile(
        targetMessageId: Int? = null,
        threadId: Int? = null,
        /**
         * Stand-ins whose own send has finished.
         *
         * They go in the same state change that brings the real messages in.
         * Removing them afterwards meant a frame with both on screen and a
         * second pass of the scroll-to-end effect, which is the flicker.
         */
        settling: Set<Long> = emptySet(),
    ) {
        val channelId = _state.value.channelId
        val generation = ++reconcileGeneration
        try {
            val response = if (threadId != null) {
                client.chatThreadMessages(channelId, threadId, targetMessageId = targetMessageId)
            } else {
                val (decoded, raw) = client.chatMessagesWithRaw(channelId, targetMessageId = targetMessageId)
                // Store exactly what the server sent, so the snapshot replays
                // through the same decode path next time.
                if (targetMessageId == null) cache.store(raw, channelId)
                decoded
            }
            if (generation != reconcileGeneration) return
            val messages = response.messages
            val channelTitle = messages.firstNotNullOfOrNull { it.channel?.title ?: it.channel?.unicodeTitle }
            _state.value = _state.value.copy(
                messages = messages,
                // Anything the server has now confirmed stops being pending.
                pending = _state.value.pending.filterNot { local ->
                    // Either this send has just finished, or the server is
                    // already showing the words back to us. A media message
                    // has no words, so only the first can retire it: an empty
                    // text would otherwise match the first empty message in
                    // the channel and take the stand-in away mid-upload.
                    local.localId in settling ||
                        (local.mediaPath == null && messages.any { it.message?.trim() == local.text })
                },
                title = _state.value.title.ifEmpty { channelTitle.orEmpty() },
                isLoading = false,
                fromSnapshot = false,
                canLoadMorePast = response.meta?.canLoadMorePast ?: (messages.size >= PAGE_SIZE),
                atNewest = response.meta?.canLoadMoreFuture != true,
                error = null,
            )
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            if (generation != reconcileGeneration) return
            _state.value = _state.value.copy(
                isLoading = false,
                // A snapshot on screen means the failure is invisible to the
                // reader; only an empty view surfaces it.
                error = if (_state.value.messages.isEmpty()) error else null,
            )
        }
    }

    /**
     * The page above the one on screen.
     *
     * A conversation used to end wherever the first fetch happened to stop —
     * fifty messages back, with nothing above it and no way to ask. The oldest
     * message loaded is the target, and "past" is the direction.
     */
    fun loadOlder() {
        val current = _state.value
        if (current.isLoadingOlder || !current.canLoadMorePast) return
        val oldest = current.messages.firstOrNull()?.id ?: return
        val channelId = current.channelId
        val threadId = current.openThreadId
        _state.value = current.copy(isLoadingOlder = true)
        viewModelScope.launch {
            val response = runCatchingCancellable {
                if (threadId != null) {
                    client.chatThreadMessages(channelId, threadId, targetMessageId = oldest, direction = PAST)
                } else {
                    client.chatMessages(channelId, targetMessageId = oldest, direction = PAST)
                }
            }.getOrNull()
            if (response == null) {
                _state.value = _state.value.copy(isLoadingOlder = false)
                return@launch
            }
            // The server includes the target itself, and a live refetch may
            // have arrived in the meantime: keyed by id rather than appended.
            val known = _state.value.messages.map { it.id }.toSet()
            val older = response.messages.filterNot { it.id in known }
            _state.value = _state.value.copy(
                messages = older + _state.value.messages,
                canLoadMorePast = response.meta?.canLoadMorePast ?: older.isNotEmpty(),
                isLoadingOlder = false,
            )
        }
    }

    /** For the empty-and-failed state, which is the only place it can be seen. */
    fun retry() {
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            reconcile(threadId = _state.value.openThreadId)
            loadThreads()
        }
    }

    private suspend fun loadThreads() {
        val channelId = _state.value.channelId
        runCatchingCancellable { client.chatThreads(channelId) }
            .onSuccess { _state.value = _state.value.copy(threads = it.threads) }
    }

    private fun subscribe() {
        val channels = buildList {
            add(BusChannels.chat(_state.value.channelId))
            _state.value.openThreadId?.let { add(BusChannels.chatThread(it)) }
        }
        // Replaces this conversation's subscription, not the whole bus: another
        // conversation may be listening on the same shared client.
        subscription?.cancel()
        subscription = bus.subscribe(channels) {
            viewModelScope.launch { reconcile(threadId = _state.value.openThreadId) }
        }
    }

    fun openThread(threadId: Int?) {
        _state.value = _state.value.copy(
            openThreadId = threadId,
            isLoading = true,
            canLoadMorePast = false,
            replyTo = null,
            editing = null,
        )
        viewModelScope.launch {
            reconcile(threadId = threadId)
            subscribe()
        }
    }

    // ------------------------------------------------------------- composing

    fun startReply(message: ChatMessage) {
        _state.value = _state.value.copy(replyTo = message, editing = null)
    }

    /** The draft the screen should show; empty for a reply, the words for an edit. */
    fun startEdit(message: ChatMessage): String {
        _state.value = _state.value.copy(editing = message, replyTo = null)
        return message.message.orEmpty()
    }

    fun cancelCompose() {
        _state.value = _state.value.copy(replyTo = null, editing = null)
    }

    /**
     * [onAccepted] runs the moment the message is on screen, not when the
     * server confirms it — the draft is cleared and the keyboard stays up, the
     * way every other messenger behaves. A rejected message hands its text
     * back through [onRestore].
     */
    fun send(text: String, onAccepted: () -> Unit, onRestore: (String) -> Unit) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val editing = _state.value.editing
        if (editing != null) {
            editMessage(editing, trimmed, onAccepted, onRestore)
            return
        }
        val channelId = _state.value.channelId
        val replyTo = _state.value.replyTo
        val local = PendingChatMessage(localId = nextLocalId--, text = trimmed)
        _state.value = _state.value.copy(pending = _state.value.pending + local, replyTo = null)
        onAccepted()
        viewModelScope.launch {
            val sent = runCatchingCancellable {
                client.createChatMessage(channelId, trimmed, _state.value.openThreadId, replyTo?.id)
            }
            if (sent.isFailure) {
                _state.value = _state.value.copy(pending = _state.value.pending - local)
                sent.exceptionOrNull()?.let { ToastCenter.showError(it) }
                onRestore(trimmed)
                return@launch
            }
            // A light refresh rather than a full reload: the page is already
            // the newest one, it just needs the new tail. The stand-in retires
            // inside it, so the swap is one change rather than two.
            reconcile(threadId = _state.value.openThreadId, settling = setOf(local.localId))
        }
    }

    private fun editMessage(
        message: ChatMessage,
        text: String,
        onAccepted: () -> Unit,
        onRestore: (String) -> Unit,
    ) {
        val channelId = _state.value.channelId
        _state.value = _state.value.copy(editing = null)
        onAccepted()
        viewModelScope.launch {
            runCatchingCancellable { client.updateChatMessage(channelId, message.id, text) }
                .onFailure {
                    ToastCenter.showError(it)
                    onRestore(text)
                    _state.value = _state.value.copy(editing = message)
                }
            reconcile(threadId = _state.value.openThreadId)
        }
    }

    fun delete(message: ChatMessage) {
        val channelId = _state.value.channelId
        // Off the screen first: a message the reader has just deleted staying
        // put for a round trip reads as a failure.
        _state.value = _state.value.copy(messages = _state.value.messages.filterNot { it.id == message.id })
        viewModelScope.launch {
            runCatchingCancellable { client.deleteChatMessage(channelId, message.id) }
                .onFailure { ToastCenter.showError(it) }
            reconcile(threadId = _state.value.openThreadId)
        }
    }

    /**
     * Toggling, not adding: tapping a face already given takes it back, which
     * is the same endpoint with the other verb.
     */
    fun react(message: ChatMessage, emoji: String) {
        val channelId = _state.value.channelId
        val mine = message.reactions.orEmpty().firstOrNull { it.emoji == emoji }?.reacted == true
        _state.value = _state.value.copy(messages = _state.value.messages.map { row ->
            if (row.id != message.id) return@map row
            val existing = row.reactions.orEmpty()
            val updated = existing.mapNotNull { reaction ->
                when {
                    reaction.emoji != emoji -> reaction
                    mine && reaction.count <= 1 -> null
                    mine -> reaction.copy(count = reaction.count - 1, reacted = false)
                    else -> reaction.copy(count = reaction.count + 1, reacted = true)
                }
            }
            val added = if (!mine && existing.none { it.emoji == emoji }) {
                updated + ChatReaction(emoji = emoji, count = 1, reacted = true)
            } else {
                updated
            }
            row.copy(reactions = added)
        })
        viewModelScope.launch {
            runCatchingCancellable { client.reactToChatMessage(channelId, message.id, emoji, !mine) }
                .onFailure {
                    ToastCenter.showError(it)
                    reconcile(threadId = _state.value.openThreadId)
                }
        }
    }

    // -------------------------------------------------------------- presence

    /**
     * Who else is typing.
     *
     * Discourse has no typing event: being present in `/chat-reply/{id}` *is*
     * the signal, and the presence channel republishes on the bus whenever its
     * membership changes. So the app joins that channel while the reader is
     * actually typing, and reads it back when the bus says it moved.
     */
    private fun watchPresence() {
        val channel = presenceChannel() ?: return
        presenceJob?.cancel()
        presenceJob = viewModelScope.launch {
            refreshTyping(channel)
            while (isActive) {
                delay(PRESENCE_POLL_MILLIS)
                // Only while somebody is looking at this screen and only while
                // the app is in the foreground — the bus loop pauses itself in
                // the background and this should not outlive it.
                refreshTyping(channel)
                if (System.currentTimeMillis() < typingUntil) {
                    runCatchingCancellable { client.presenceUpdate(listOf(channel), emptyList()) }
                }
            }
        }
    }

    private suspend fun refreshTyping(channel: String) {
        val me = services.session.username
        val state = runCatchingCancellable { client.presenceGet(listOf(channel)) }
            .getOrNull()?.channels?.get(channel) ?: return
        val names = state.users.orEmpty()
            .filterNot { it.username.equals(me, ignoreCase = true) }
            .mapNotNull { user -> user.name?.takeIf { it.isNotBlank() } ?: user.username }
        _state.value = _state.value.copy(typing = names)
    }

    /**
     * Called as the reader types. Presence lapses on the server after about a
     * minute, so this is repeated rather than sent once — but not per
     * keystroke: at most one request per burst.
     */
    fun typing() {
        val channel = presenceChannel() ?: return
        val now = System.currentTimeMillis()
        val wasTyping = now < typingUntil
        typingUntil = now + TYPING_LINGER_MILLIS
        if (wasTyping) return
        viewModelScope.launch {
            runCatchingCancellable { client.presenceUpdate(listOf(channel), emptyList()) }
        }
    }

    /** Called when the draft empties: presence otherwise lapses on its own, a minute later. */
    fun stoppedTyping() {
        val channel = presenceChannel() ?: return
        typingUntil = 0L
        viewModelScope.launch {
            runCatchingCancellable { client.presenceUpdate(emptyList(), listOf(channel)) }
        }
    }

    private fun presenceChannel(): String? {
        val channelId = _state.value.channelId.takeIf { it != 0 } ?: return null
        val threadId = _state.value.openThreadId
        return if (threadId != null) "/chat-reply/$channelId/thread/$threadId" else "/chat-reply/$channelId"
    }

    // ------------------------------------------------------------------ read

    /**
     * A picture or a clip, as its own message.
     *
     * Sent rather than parked in the draft: chat is a stream of small things,
     * and an attachment waiting for a covering sentence is a worse fit here
     * than in a topic reply. Whatever is already typed stays typed.
     *
     * The markdown Discourse hands back is the whole message — the same string
     * the composer would have inserted, which is what makes the bubble render
     * a picture rather than a link.
     */
    /**
     * A clip, streamed from the file the trimmer left behind.
     *
     * Separate from the picture path because a video is not something to hold
     * in memory: it goes up straight off disk and the file is deleted after.
     */
    /**
     * A clip that has been decided about but not yet encoded.
     *
     * The stand-in goes up first with the still the editor read, then the
     * encode and the upload run behind it. One ring covers both, weighted:
     * encoding is the long half on anything that had to be re-drawn, and
     * splitting them into two bars would say more about our pipeline than
     * about how much longer there is to wait.
     */
    fun sendVideo(request: com.nodeloc.app.feature.media.VideoSendRequest) {
        if (_state.value.isSending) return
        val channelId = _state.value.channelId
        val local = PendingChatMessage(
            localId = nextLocalId--,
            mediaPath = request.posterBytes?.let { cachePreview(it) },
            isVideo = !request.edit.asGif,
            durationMs = request.edit.durationMs,
            progress = 0f,
        )
        _state.value = _state.value.copy(pending = _state.value.pending + local, isSending = true)

        fun step(fraction: Float) {
            _state.value = _state.value.copy(
                uploadProgress = fraction,
                pending = _state.value.pending.map {
                    if (it.localId == local.localId) it.copy(progress = fraction) else it
                },
            )
        }

        videoUpload = viewModelScope.launch {
            val context = services.appContext
            val media = runCatchingCancellable {
                if (request.edit.asGif) {
                    com.nodeloc.app.feature.media.exportGif(context, request.source, request.edit) {
                        step(it * ENCODE_SHARE)
                    }
                } else {
                    com.nodeloc.app.feature.media.exportClip(
                        context, request.source, request.probe, request.edit,
                    ) { step(it * ENCODE_SHARE) }
                }
            }.onFailure { ToastCenter.show(R.string.trim_failed) }.getOrNull()

            if (media == null) {
                _state.value = _state.value.copy(
                    isSending = false,
                    uploadProgress = null,
                    pending = _state.value.pending.filterNot { it.localId == local.localId },
                )
                return@launch
            }

            // Checked here rather than in the editor: chat leaves before the
            // encode, so this is the first moment there is a size to check.
            if (media.file.length() > com.nodeloc.app.feature.media.UPLOAD_MAX_BYTES) {
                withContext(Dispatchers.IO) { runCatching { media.file.delete() } }
                ToastCenter.show(R.string.trim_too_large)
                _state.value = _state.value.copy(
                    isSending = false,
                    uploadProgress = null,
                    pending = _state.value.pending.filterNot { it.localId == local.localId },
                )
                return@launch
            }

            val upload = runCatchingCancellable {
                client.uploadMediaFile(media.file, media.fileName, media.mimeType) { sent ->
                    step(ENCODE_SHARE + sent * (1f - ENCODE_SHARE))
                }
            }.onFailure { ToastCenter.showError(it) }.getOrNull()

            val uploadId = upload?.id
            if (uploadId != null) {
                request.posterBytes?.let { poster ->
                    val digest = withContext(Dispatchers.IO) { sha1Hex(media.file) }
                    runCatchingCancellable { client.uploadVideoPoster(poster, digest) }
                }
                runCatchingCancellable {
                    client.createChatMessage(
                        channelId = channelId,
                        message = "",
                        threadId = _state.value.openThreadId,
                        uploadIds = listOf(uploadId),
                    )
                }.onFailure { ToastCenter.showError(it) }
                reconcile(threadId = _state.value.openThreadId, settling = setOf(local.localId))
            }
            withContext(Dispatchers.IO) { runCatching { media.file.delete() } }
            _state.value = _state.value.copy(
                isSending = false,
                uploadProgress = null,
                // Already gone if the reconcile above ran; this is the path
                // where the upload failed and there is nothing to stand for.
                pending = _state.value.pending.filterNot { it.localId == local.localId },
            )
        }
    }

    /**
     * Abandon the clip on its way out.
     *
     * Cancelling the job unwinds the whole of it — the encode if it is still
     * running, the upload if it has started — and the stand-in goes with it,
     * because a bubble left behind would promise a message nobody sent.
     */
    fun cancelUpload() {
        videoUpload?.cancel()
        videoUpload = null
        _state.value = _state.value.copy(
            isSending = false,
            uploadProgress = null,
            pending = _state.value.pending.filter { it.mediaPath == null },
        )
    }

    private var videoUpload: kotlinx.coroutines.Job? = null

    fun sendMedia(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        posterBytes: ByteArray? = null,
    ) {
        if (_state.value.isSending) return
        val channelId = _state.value.channelId
        // Written out so the bubble has something to show at once. A picture
        // small enough to send is small enough to keep a copy of for as long
        // as the upload takes.
        val local = PendingChatMessage(
            localId = nextLocalId--,
            mediaPath = cachePreview(posterBytes ?: bytes),
            isVideo = posterBytes != null,
            progress = 0f,
        )
        _state.value = _state.value.copy(pending = _state.value.pending + local)
        viewModelScope.launch {
            _state.value = _state.value.copy(isSending = true)
            val upload = runCatchingCancellable { client.uploadComposerMedia(bytes, fileName, mimeType) }
                .onFailure { ToastCenter.showError(it) }
                .getOrNull()
            val uploadId = upload?.id
            if (uploadId == null) {
                _state.value = _state.value.copy(
                    isSending = false,
                    pending = _state.value.pending.filterNot { it.localId == local.localId },
                )
                return@launch
            }
            // Discourse ties a poster to its video by filename alone, so this
            // has to go up before anything renders the clip. Best effort: a
            // missing thumbnail is a duller bubble, not a failed send.
            posterBytes?.let { poster ->
                runCatchingCancellable { client.uploadVideoPoster(poster, sha1Hex(bytes)) }
            }
            runCatchingCancellable {
                client.createChatMessage(
                    channelId = channelId,
                    // No body: the picture is the message. Anything typed here
                    // would sit above it as a caption nobody asked for.
                    message = "",
                    threadId = _state.value.openThreadId,
                    uploadIds = listOf(uploadId),
                )
            }.onFailure { ToastCenter.showError(it) }
            reconcile(threadId = _state.value.openThreadId, settling = setOf(local.localId))
            _state.value = _state.value.copy(
                isSending = false,
                // Already gone if the reconcile above ran; this is the path
                // where the upload failed and there is nothing to stand for.
                pending = _state.value.pending.filterNot { it.localId == local.localId },
            )
        }
    }

    /**
     * A copy on disk for the bubble to show while the real one uploads.
     *
     * Written rather than held: the stand-in outlives the call that made it,
     * and a few megabytes of picture kept in the view model for the length of
     * an upload is a few megabytes nobody needs twice.
     */
    private fun cachePreview(bytes: ByteArray): String? = runCatching {
        val dir = java.io.File(services.appContext.cacheDir, "chat-outgoing").apply { mkdirs() }
        java.io.File(dir, "send-${System.nanoTime()}.jpg").also { it.writeBytes(bytes) }.absolutePath
    }.getOrNull()

    /**
     * Read as far as the reader has actually got, called by the screen when the
     * end of the list is on screen.
     *
     * Opening a channel is no longer enough on its own: a channel three hundred
     * messages behind was being cleared by someone who had seen fifty of them.
     * Nothing above the newest loaded message is claimed either — there is no
     * knowing whether it was read.
     *
     * Through the message centre rather than the client, because the inbox row
     * and the tab badge are its state: a channel marked read behind its back
     * stayed unread on both until something else happened to refetch.
     */
    fun markReadToBottom() {
        val current = _state.value
        val threadId = current.openThreadId
        val newest = current.messages.lastOrNull()?.id ?: return
        if (newest <= lastMarkedRead) return
        lastMarkedRead = newest
        viewModelScope.launch {
            if (threadId != null) {
                // A thread's own last-read, which the channel's does not cover.
                runCatchingCancellable { client.markChatThreadRead(current.channelId, threadId, newest) }
            } else {
                services.messageCenter.markChatChannelRead(current.channelId, newest)
            }
        }
    }

    override fun onCleared() {
        subscription?.cancel()
        presenceJob?.cancel()
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val PAST = "past"

        /** Discourse's own client refreshes presence on about this cadence. */
        const val PRESENCE_POLL_MILLIS = 8_000L
        const val TYPING_LINGER_MILLIS = 15_000L
    }
}

/**
 * How much of the wait is encoding.
 *
 * A guess with a reason: a clip that had to be re-drawn spends far longer in
 * the encoder than on the wire, and one that did not is through the first half
 * in a second or two either way.
 */
private const val ENCODE_SHARE = 0.6f
