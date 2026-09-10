package com.nodeloc.app.feature.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.rememberCoroutineScope
import com.composables.icons.lucide.Plus
import com.nodeloc.app.core.design.UploadProgressRing
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.feature.media.ImageEditRenderer
import com.nodeloc.app.feature.media.VideoEditorDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.WifiOff
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.Smile
import com.nodeloc.app.core.design.ReportDialog
import com.nodeloc.app.core.design.ReportTarget
import com.nodeloc.app.core.design.BlockUserDialog
import com.nodeloc.app.R
import com.nodeloc.app.core.network.isOfflineError
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.ConfirmDialog
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.HairLine
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.Radius
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.RemoteImage
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.rememberTapSafeOverscroll
import com.nodeloc.app.core.design.rememberDismissKeyboardOnPullDown
import com.nodeloc.app.core.html.plainTextFromHtml
import com.nodeloc.app.core.html.InlineText
import com.nodeloc.app.core.html.PostBlock
import com.nodeloc.app.core.html.PostHtmlParser
import com.nodeloc.app.core.html.PostInline
import com.nodeloc.app.core.model.ChatMessage
import com.nodeloc.app.core.model.ChatUser
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.feature.compose.EmojiPickerSheet
import com.nodeloc.app.feature.compose.insertEmoji
import com.nodeloc.app.feature.media.ImageViewerOverlay
import com.nodeloc.app.feature.nav.Navigator
import com.composables.icons.lucide.Play
import com.nodeloc.app.core.model.DiscourseUpload
import com.nodeloc.app.feature.media.VideoViewerOverlay
import com.nodeloc.app.feature.media.ImageEditorDialog
import androidx.compose.foundation.layout.heightIn
import com.nodeloc.app.feature.media.RemoteVideoPreview
import com.nodeloc.app.feature.media.RemoteVideoPreviews
import com.nodeloc.app.feature.media.formatClock
import androidx.compose.ui.graphics.Color
import coil3.compose.AsyncImage

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatConversationScreen(
    channelId: Int,
    messageId: Int?,
    threadId: Int?,
    navigator: Navigator,
) {
    val viewModel: ChatViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val me = com.nodeloc.app.ServiceLocator.get.session.username
    var viewerImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var viewerVideo by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf(TextFieldValue()) }
    var emojiPickerOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var attachMenuOpen by remember { mutableStateOf(false) }
    // Held by the screen: the sheet that opens them closes on the tap.
    var reportingMessage by remember { mutableStateOf<Int?>(null) }
    var blockingUser by remember { mutableStateOf<String?>(null) }
    var trimmingVideo by remember { mutableStateOf<Uri?>(null) }
    var editingBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    /** PNG in, PNG out: flattening one to JPEG would fill its transparency. */
    var editingPng by remember { mutableStateOf(false) }
    var decodingImage by remember { mutableStateOf(false) }

    var channelMenuOpen by remember { mutableStateOf(false) }
    var membersOpen by remember { mutableStateOf(false) }
    var levelPickerOpen by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    /** The message whose long-press menu is open, if any. */
    var acting by remember { mutableStateOf<ChatMessage?>(null) }
    var reactingTo by remember { mutableStateOf<ChatMessage?>(null) }
    var deleting by remember { mutableStateOf<ChatMessage?>(null) }
    val clipboard = LocalClipboardManager.current

    // One list: history, the "new messages" mark, and whatever this device has
    // sent and the server has not confirmed. Built here rather than in three
    // `item` blocks so that the anchor an older page has to be restored
    // against is a plain index into it.
    val rows = remember(state.visibleMessages, state.pending, state.isLoadingOlder, state.unreadFrom) {
        buildList {
            if (state.isLoadingOlder) add(ChatRow.Loading)
            val unreadFrom = state.unreadFrom
            state.visibleMessages.forEach { message ->
                if (unreadFrom != null && message.id > unreadFrom &&
                    none { it is ChatRow.Divider }
                ) {
                    add(ChatRow.Divider(message.id))
                }
                add(ChatRow.Message(message))
            }
            state.pending.forEach { add(ChatRow.Pending(it)) }
        }
    }

    // Same treatment as a topic reply: a camera JPEG is re-encoded upright and
    // at a sane size rather than sent whole and sideways, PNG stays PNG so a
    // screenshot keeps its transparency, and a GIF passes through untouched
    // because decoding it to a bitmap would send one frame of it.
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val sourceMime = context.contentResolver.getType(uri) ?: "image/jpeg"
        // An animated GIF goes up as it is. The editor works on a single
        // bitmap, so opening one there would quietly hand back the first frame
        // and call it a picture.
        if (sourceMime == "image/gif") {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                        .getOrNull()
                }
                if (bytes == null) ToastCenter.show(R.string.error_action_failed)
                else viewModel.sendMedia(bytes, "chat.gif", sourceMime)
            }
            return@rememberLauncherForActivityResult
        }
        // Everything else goes through the same editor a post uses: crop and
        // annotate before it is sent, not after it is somebody else's to read.
        // Decoding a phone-camera JPEG takes long enough to freeze the
        // conversation if it happens on this thread.
        decodingImage = true
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { ImageEditRenderer.decodeUpright(context, uri) }
            decodingImage = false
            if (bitmap == null) {
                ToastCenter.show(R.string.error_action_failed)
            } else {
                editingPng = sourceMime == "image/png"
                editingBitmap = bitmap
            }
        }
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { trimmingVideo = it }
    }

    LaunchedEffect(channelId) { viewModel.bind(channelId, messageId, threadId) }

    // Opening a conversation lands at its end, and gets there without playing
    // the history back: `animateScrollToItem` from the top flings through every
    // message that has ever been sent. Keyed on the last message's id as well
    // as the count, because the cached snapshot and the network answer often
    // hold the same number of messages — and when they do, a count-keyed
    // effect never fires and the view keeps the snapshot's position under the
    // new content.
    /**
     * Whether the view follows the end of the conversation.
     *
     * True until the reader scrolls away from it, and true again the moment
     * they come back — which is the whole rule. The old flag was "have we
     * landed once", and the first landing happened on the *cached* page: the
     * network answer arrived a moment later with messages the snapshot did not
     * have, the one-shot had already been spent, and the new ones sat below
     * the fold. Leaving and re-entering worked only because by then the cache
     * held the newer page.
     */
    var stickToEnd by remember(channelId) { mutableStateOf(true) }
    var jumpedToTarget by remember(channelId) { mutableStateOf(messageId == null) }
    val lastMessageId = state.visibleMessages.lastOrNull()?.id

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
        }.collect { (last, total) ->
            if (total > 0) stickToEnd = last >= total - 2
        }
    }

    LaunchedEffect(rows.size, lastMessageId) {
        // The row index, not the message index: the list also carries the
        // unread mark and anything still being sent.
        val last = rows.lastIndex
        if (last < 0) return@LaunchedEffect

        // A notification names a message. The fetch centres the page on it,
        // and scrolling past it to the bottom would land the reader on
        // whatever happened to be newest instead.
        if (!jumpedToTarget) {
            val target = rows.indexOfFirst { it is ChatRow.Message && it.message.id == messageId }
            if (target >= 0) {
                jumpedToTarget = true
                stickToEnd = false
                listState.scrollToItem(target)
                return@LaunchedEffect
            }
        }

        if (!stickToEnd) return@LaunchedEffect
        listState.scrollToEnd(last)
        // Anything without a declared size — a link preview, an upload the
        // server never measured — settles a frame or two late. Hold the end
        // down while that happens, and stop the moment the reader takes the
        // scroll back.
        withTimeoutOrNull(1_000) {
            snapshotFlow { listState.canScrollForward }
                .collect { if (it && stickToEnd) listState.scrollToEnd(rows.lastIndex) }
        }
    }

    // Older history, asked for as the top comes into view. The page arrives
    // above everything on screen, which in a list measured from the top means
    // the content the reader was looking at is pushed down by exactly the
    // height of what was added — so the message that was at the top is put
    // back where it was, by id rather than by index.
    var anchor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // Keyed on the flag as well as the list: a snapshot flow of the index only
    // emits when the index *changes*, and a conversation short enough to open
    // already at its top never changed it — so the first page the server said
    // it could follow was never asked for.
    LaunchedEffect(listState, state.canLoadMorePast) {
        if (!state.canLoadMorePast) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                if (index > 2) return@collect
                // Read through the view model, never through the `state` this
                // effect closed over: a LaunchedEffect keyed on the list keeps
                // the composition it started in, so the captured copy still
                // held the first page's oldest message — every scroll to the
                // top asked for the same page again, and the second answer,
                // being entirely messages already held, looked like the end of
                // the conversation.
                val messages = viewModel.state.value.messages
                anchor = messages.firstOrNull()?.id
                    ?.let { it to listState.firstVisibleItemScrollOffset }
                viewModel.loadOlder()
            }
    }
    LaunchedEffect(state.visibleMessages.firstOrNull()?.id) {
        val (anchorId, offset) = anchor ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it is ChatRow.Message && it.message.id == anchorId }
        anchor = null
        if (index > 0) listState.scrollToItem(index, offset)
    }

    // Read as far as the reader actually got. Opening the channel is not the
    // claim — reaching the end of it is.
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.layoutInfo.let { it.visibleItemsInfo.lastOrNull()?.index to it.totalItemsCount }
        }
            .collect { (last, total) ->
                if (last != null && total > 0 && last >= total - 1 && viewModel.state.value.atNewest) {
                    viewModel.markReadToBottom()
                }
            }
    }

    // The keyboard shortens the list rather than covering it, and a shortened
    // list keeps its top — so the last message, the one being replied to, slides
    // out of sight. Keyed on the inset itself rather than on a visible/hidden
    // flag: it changes every frame of the keyboard's own animation, so the
    // conversation rides up with it instead of jumping afterwards.
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && state.visibleMessages.isNotEmpty()) {
            listState.scrollToEnd(state.visibleMessages.lastIndex)
        }
    }

    Column(Modifier.fillMaxSize().background(Nocturne.bg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = Space.page, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            HeaderIconButton(Lucide.ArrowLeft, stringResource(R.string.common_back), onClick = navigator::back)
            // In a thread the header names the thread and keeps the channel
            // under it: a reply tree that looked exactly like the channel gave
            // no clue which of the two the composer was about to post to.
            val openThread = state.threads.firstOrNull { it.id == state.openThreadId }
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        state.openThreadId == null -> state.title.ifEmpty { stringResource(R.string.chat_title) }
                        else -> openThread?.title
                            ?: openThread?.originalMessage?.excerpt?.let(::plainTextFromHtml)
                            ?: stringResource(R.string.chat_thread)
                    },
                    style = Type.body(15, FontWeight.SemiBold),
                    color = Nocturne.headerText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.openThreadId != null && state.title.isNotEmpty()) {
                    Text(
                        state.title,
                        style = Type.body(11),
                        color = Nocturne.muted(0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (state.openThreadId != null) {
                HeaderIconButton(Lucide.X, stringResource(R.string.chat_leave_thread)) { viewModel.openThread(null) }
            }
            Box {
                HeaderIconButton(Lucide.EllipsisVertical, stringResource(R.string.common_more)) {
                    channelMenuOpen = true
                }
                DropdownMenu(
                    expanded = channelMenuOpen,
                    onDismissRequest = { channelMenuOpen = false },
                    containerColor = Nocturne.surface,
                ) {
                    // A category channel has no "profile" to show: its people
                    // are whoever is in the node, which is the node's own page.
                    if (state.channel?.isDirectMessage == true) {
                        val others = state.members.filterNot { it.username == me }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (others.size > 1) R.string.chat_members else R.string.chat_profile,
                                    ),
                                    style = Type.body(14),
                                    color = Nocturne.text,
                                )
                            },
                            onClick = {
                                channelMenuOpen = false
                                // One other person is a profile; a group is a
                                // list of them.
                                val only = others.singleOrNull()
                                if (only != null) navigator.openProfile(only.username) else membersOpen = true
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.chat_notification_level),
                                style = Type.body(14),
                                color = Nocturne.text,
                            )
                        },
                        onClick = {
                            channelMenuOpen = false
                            levelPickerOpen = true
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.chat_leave),
                                style = Type.body(14),
                                color = Nocturne.danger,
                            )
                        },
                        onClick = {
                            channelMenuOpen = false
                            leaving = true
                        },
                    )
                }
            }
        }
        // Who is at the other end, right now. Presence is the only thing
        // Discourse offers here — there is no "typing" event, only being in the
        // reply channel — so this appears while somebody has the composer open.
        if (state.typing.isNotEmpty()) {
            Text(
                if (state.typing.size == 1) {
                    stringResource(R.string.chat_typing_one, state.typing.first())
                } else {
                    pluralStringResource(R.plurals.chat_typing_many, state.typing.size, state.typing.size)
                },
                style = Type.body(11),
                color = Nocturne.accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.page)
                    .padding(bottom = 4.dp),
            )
        }
        HairLine()

        if (state.threads.isNotEmpty() && state.openThreadId == null) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Space.page, vertical = Space.s2),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                overscrollEffect = rememberTapSafeOverscroll(),
            ) {
                items(state.threads, key = { it.id }) { thread ->
                    Column(
                        Modifier
                            .width(160.dp)
                            .clip(RoundedCornerShape(Radius.md))
                            .background(Nocturne.surface)
                            .clickable { viewModel.openThread(thread.id) }
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            thread.title ?: thread.originalMessage?.excerpt?.let(::plainTextFromHtml) ?: stringResource(R.string.chat_thread),
                            style = Type.body(12, FontWeight.Medium),
                            color = Nocturne.text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(
                                pluralStringResource(
                                    R.plurals.common_replies_count,
                                    thread.replyCount ?: 0,
                                    thread.replyCount ?: 0,
                                ),
                                style = Type.body(11),
                                color = Nocturne.muted(0.45f),
                            )
                            // A thread keeps its own unread, which the channel's
                            // badge does not carry — without this the only way
                            // to find a new reply was to open every thread.
                            val unread = thread.currentUserMembership?.unreadCount ?: 0
                            if (unread > 0) {
                                Box(
                                    Modifier
                                        .clip(CircleShape)
                                        .background(Nocturne.accent)
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        if (unread > 99) "99+" else "$unread",
                                        style = Type.body(10, FontWeight.SemiBold),
                                        color = Nocturne.bg,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Box(Modifier.weight(1f)) {
            if (state.isLoading && state.visibleMessages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    NodelocLoader(height = 48.dp)
                }
            } else if (state.error != null && state.visibleMessages.isEmpty()) {
                // With no cached snapshot and no network this used to be an
                // empty list under a working composer: nothing said the
                // conversation had failed to load rather than being new.
                val offline = state.error?.isOfflineError() == true
                EmptyStateView(
                    icon = if (offline) Lucide.WifiOff else Lucide.CircleAlert,
                    title = stringResource(if (offline) R.string.error_offline else R.string.chat_load_failed),
                    retryLabel = stringResource(R.string.common_retry),
                    onRetry = viewModel::retry,
                )
            } else {
                LazyColumn(
                    state = listState,
                    // Down only. `imeNestedScroll` used to be here and drags
                    // both ways, so scrolling up through the history hauled the
                    // keyboard back into view a notch at a time — see
                    // rememberDismissKeyboardOnPullDown.
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(rememberDismissKeyboardOnPullDown()),
                    contentPadding = PaddingValues(horizontal = Space.page, vertical = Space.s3),
                    verticalArrangement = Arrangement.spacedBy(Space.s3),
                    overscrollEffect = rememberTapSafeOverscroll(),
                ) {
                    items(rows, key = ChatRow::key) { row ->
                        when (row) {
                            is ChatRow.Loading ->
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    NodelocLoader(height = 20.dp)
                                }

                            is ChatRow.Divider -> UnreadDivider()

                            is ChatRow.Message -> MessageBubble(
                                message = row.message,
                                isMine = row.message.user?.username == me,
                                onOpenImage = { url -> viewerImages = listOf(url) },
                                onOpenVideo = { url -> viewerVideo = url },
                                onOpenLink = { navigator.openUrl(context, it) },
                                onOpenMention = { navigator.openProfile(it) },
                                onOpenProfile = { navigator.openProfile(it) },
                                onMention = { draft = insertMention(draft, it) },
                                onLongPress = { acting = row.message },
                                onReact = { emoji -> viewModel.react(row.message, emoji) },
                            )

                            is ChatRow.Pending -> PendingBubble(row.message)
                        }
                    }
                }
            }
        }

        HairLine()
        // What the next message will do, when it is not simply a new one.
        val composing = state.editing ?: state.replyTo
        if (composing != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Nocturne.surface)
                    .padding(horizontal = Space.page, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (state.editing != null) {
                            stringResource(R.string.chat_editing)
                        } else {
                            stringResource(
                                R.string.chat_replying_to,
                                composing.user?.name?.takeIf { it.isNotBlank() }
                                    ?: composing.user?.username.orEmpty(),
                            )
                        },
                        style = Type.body(11, FontWeight.SemiBold),
                        color = Nocturne.accent,
                    )
                    Text(
                        composing.cooked?.let(::plainTextFromHtml)?.takeIf { it.isNotBlank() }
                            ?: composing.message.orEmpty(),
                        style = Type.body(12),
                        color = Nocturne.muted(0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Lucide.X,
                    stringResource(R.string.common_cancel),
                    tint = Nocturne.muted(0.45f),
                    modifier = Modifier
                        .size(18.dp)
                        .clickable {
                            if (state.editing != null) draft = TextFieldValue()
                            viewModel.cancelCompose()
                        },
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding()
                .padding(horizontal = Space.page, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Box {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Nocturne.surface)
                        .clickable(enabled = !state.isSending) { attachMenuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Lucide.Plus,
                        stringResource(R.string.chat_attach),
                        tint = Nocturne.muted(0.6f),
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(
                    expanded = attachMenuOpen,
                    onDismissRequest = { attachMenuOpen = false },
                    containerColor = Nocturne.surface,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.compose_image), style = Type.body(14), color = Nocturne.text) },
                        onClick = {
                            attachMenuOpen = false
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.compose_video), style = Type.body(14), color = Nocturne.text) },
                        onClick = {
                            attachMenuOpen = false
                            videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                        },
                    )
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Radius.lg))
                    .background(Nocturne.surface)
                    .padding(horizontal = Space.card, vertical = 10.dp),
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        // Presence is what the other end sees as "typing", and
                        // it lapses on its own — so it is renewed here rather
                        // than announced once.
                        if (it.text.isNotBlank()) viewModel.typing() else viewModel.stoppedTyping()
                    },
                    // Never disabled. Disabling it while a message was in
                    // flight took the focus away, and with the focus went the
                    // keyboard — every message cost a tap to get back to.
                    textStyle = Type.body(14).copy(color = Nocturne.text),
                    cursorBrush = SolidColor(Nocturne.accent),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (draft.text.isEmpty()) {
                            Text(stringResource(R.string.chat_message_hint), style = Type.body(14), color = Nocturne.muted(0.35f))
                        }
                        inner()
                    },
                )
            }
            // The site's own emoji, custom ones included: the picker reads
            // `emojis.json`, which is where nodeloc's own sets live, and what
            // it inserts is the `:shortcode:` the server cooks back into the
            // right picture.
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Nocturne.surface)
                    .clickable { emojiPickerOpen = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.Smile,
                    stringResource(R.string.compose_emoji),
                    tint = Nocturne.muted(0.6f),
                    modifier = Modifier.size(20.dp),
                )
            }
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (draft.text.isBlank()) Nocturne.surface else Nocturne.accent)
                    .clickable(enabled = draft.text.isNotBlank()) {
                        // Cleared the moment it is on screen, put back only if
                        // the server refuses it.
                        viewModel.send(
                            text = draft.text,
                            onAccepted = {
                                draft = TextFieldValue()
                                viewModel.stoppedTyping()
                                scope.launch { listState.scrollToEnd(rows.lastIndex) }
                            },
                            onRestore = { draft = TextFieldValue(it, TextRange(it.length)) },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                val sending = state.uploadProgress
                when {
                    // The send button becomes the stop button while a clip is
                    // going up, which is where a hand already is and where
                    // every other messenger puts it. The ring takes its own
                    // taps; the button underneath is disabled on a blank draft.
                    sending != null ->
                        UploadProgressRing(sending, onCancel = viewModel::cancelUpload, diameter = 34.dp)

                    state.isSending -> NodelocLoader(height = 18.dp, tint = Nocturne.bg)

                    else -> Icon(
                        Lucide.Send,
                        stringResource(R.string.reader_send),
                        tint = if (draft.text.isBlank()) Nocturne.muted(0.35f) else Nocturne.bg,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }

    acting?.let { message ->
        MessageActionSheet(
            isMine = message.user?.username == me,
            onReport = {
                acting = null
                reportingMessage = message.id
            },
            onBlock = {
                acting = null
                message.user?.username?.let { blockingUser = it }
            },
            onQuickReact = { emoji ->
                acting = null
                viewModel.react(message, emoji)
            },
            onReply = {
                acting = null
                viewModel.startReply(message)
            },
            onEdit = {
                acting = null
                viewModel.startEdit(message).let { draft = TextFieldValue(it, TextRange(it.length)) }
            },
            onCopy = {
                acting = null
                clipboard.setText(
                    AnnotatedString(
                        message.message?.takeIf { it.isNotBlank() }
                            ?: message.cooked?.let(::plainTextFromHtml).orEmpty(),
                    ),
                )
                ToastCenter.show(R.string.chat_copied)
            },
            onPickReaction = {
                acting = null
                reactingTo = message
            },
            onDelete = {
                acting = null
                deleting = message
            },
            onDismiss = { acting = null },
        )
    }

    if (membersOpen) {
        ChatMembersSheet(
            members = state.members,
            onOpenProfile = {
                membersOpen = false
                navigator.openProfile(it)
            },
            onDismiss = { membersOpen = false },
        )
    }

    if (levelPickerOpen) {
        NotificationLevelSheet(
            current = state.channel?.currentUserMembership?.notificationLevel,
            onPick = {
                levelPickerOpen = false
                viewModel.setNotificationLevel(it)
            },
            onDismiss = { levelPickerOpen = false },
        )
    }

    if (leaving) {
        ConfirmDialog(
            title = stringResource(R.string.chat_leave_title),
            body = stringResource(R.string.chat_leave_detail),
            confirmLabel = stringResource(R.string.chat_leave),
            onConfirm = {
                leaving = false
                viewModel.leaveChannel { navigator.back() }
            },
            onDismiss = { leaving = false },
        )
    }

    if (emojiPickerOpen) {
        EmojiPickerSheet(
            onPick = { draft = insertEmoji(draft, it) },
            onDismiss = { emojiPickerOpen = false },
        )
    }

    reactingTo?.let { message ->
        EmojiPickerSheet(
            onPick = { emoji ->
                reactingTo = null
                viewModel.react(message, emoji)
            },
            onDismiss = { reactingTo = null },
        )
    }

    deleting?.let { message ->
        ConfirmDialog(
            title = stringResource(R.string.chat_delete_title),
            body = stringResource(R.string.chat_delete_detail),
            confirmLabel = stringResource(R.string.common_delete),
            onConfirm = {
                deleting = null
                viewModel.delete(message)
            },
            onDismiss = { deleting = null },
        )
    }

    reportingMessage?.let { messageId ->
        ReportDialog(ReportTarget.ChatMessage(channelId, messageId), null) { reportingMessage = null }
    }

    blockingUser?.let { username ->
        BlockUserDialog(username, onDismiss = { blockingUser = null })
    }

    editingBitmap?.let { bitmap ->
        ImageEditorDialog(
            source = bitmap,
            // Chat has nowhere to park a picture, so the editor sends it.
            sends = true,
            onCancel = { editingBitmap = null },
            onDone = { edited ->
                editingBitmap = null
                val png = editingPng
                scope.launch {
                    val bytes = withContext(Dispatchers.Default) {
                        if (png) ImageEditRenderer.encodePng(edited) else ImageEditRenderer.encode(edited)
                    }
                    viewModel.sendMedia(
                        bytes,
                        if (png) "chat.png" else "chat.jpg",
                        if (png) "image/png" else "image/jpeg",
                    )
                }
            },
        )
    }

    if (decodingImage) {
        Box(
            Modifier.fillMaxSize().background(Nocturne.bg.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center,
        ) {
            NodelocLoader(height = 44.dp)
        }
    }

    trimmingVideo?.let { uri ->
        VideoEditorDialog(
            source = uri,
            sends = true,
            onCancel = { trimmingVideo = null },
            onSend = { request ->
                trimmingVideo = null
                viewModel.sendVideo(request)
            },
        )
    }

    viewerVideo?.let { url ->
        VideoViewerOverlay(url = url) { viewerVideo = null }
    }

    if (viewerImages.isNotEmpty()) {
        ImageViewerOverlay(viewerImages, 0) { viewerImages = emptyList() }
    }
}

/**
 * Chat bubbles have no block structure to show — no headings, tables or code
 * fences survive the composer — so the blocks are flattened back to one inline
 * run, with the paragraph breaks that *are* content kept as line breaks.
 */
private fun chatInlines(cooked: String): List<PostInline> =
    PostHtmlParser.parseSync(cooked).blocks
        .mapNotNull {
            when (it) {
                is PostBlock.Paragraph -> it.inlines
                is PostBlock.Heading -> it.inlines
                else -> null
            }
        }
        .reduceOrNull { acc, next -> acc + PostInline.LineBreak + next }
        .orEmpty()

@Composable
private fun MessageBubble(
    message: ChatMessage,
    isMine: Boolean,
    onOpenImage: (String) -> Unit,
    onOpenVideo: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onOpenMention: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onMention: (String) -> Unit,
    onLongPress: () -> Unit,
    onReact: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        if (!isMine) {
            val username = message.user?.username.orEmpty()
            RemoteAvatar(
                DiscourseConfig.avatarUrl(message.user?.avatarTemplate, 80),
                username.take(1).uppercase().ifEmpty { "?" },
                size = 30.dp,
                // Clipped before the click so the ripple is the circle the
                // avatar already is, rather than the square around it.
                //
                // Tap opens who they are, hold addresses them: the two things
                // you want from someone else's face in a conversation, and the
                // second is otherwise typing a name you have to read off the
                // screen first.
                modifier = Modifier
                    .clip(CircleShape)
                    .combinedClickable(
                        enabled = username.isNotEmpty(),
                        onClick = { onOpenProfile(username) },
                        onLongClick = { onMention(username) },
                    ),
            )
            Spacer(Modifier.width(Space.s3))
        }
        Column(
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // Who and when, above the bubble rather than inside it.
            //
            // Neither is the message. Inside, they took a line of the bubble's
            // width and pushed the words along, and a picture that reaches the
            // bubble's edges had a caption bar under it that was not a caption.
            // Out here they read as what they are: a label on the message.
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                if (!isMine) {
                    Text(
                        message.user?.name?.takeIf { it.isNotBlank() } ?: message.user?.username.orEmpty(),
                        style = Type.body(11, FontWeight.SemiBold),
                        color = Nocturne.accent,
                    )
                }
                Text(
                    buildString {
                        append(DiscourseFormat.clock(message.createdAt))
                        if (message.edited == true) append("  ").append(stringResource(R.string.chat_edited))
                    },
                    style = Type.body(10),
                    color = Nocturne.muted(0.4f),
                )
            }
        Column(
            Modifier
                .widthIn(max = ChatBubbleMaxWidth)
                .clip(ChatBubbleShape)
                .background(if (isMine) Nocturne.selected else Nocturne.surface)
                // Drawn over whatever is inside, which since a picture fills
                // the bubble is often the picture: a white screenshot on a
                // white page had no edge at all, and the message stopped
                // looking like a message.
                .border(1.dp, Nocturne.divider, ChatBubbleShape)
                // A long press is the whole menu — reply, react, copy, and for
                // your own words edit and delete.
                .combinedClickable(onClick = {}, onLongClick = onLongPress),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // No padding on the bubble itself: a picture is meant to reach its
            // edges, and a margin around it makes the bubble a frame with a
            // photograph inside rather than the photograph. Everything that is
            // words asks for the margin, so only the media goes without.
            if (message.inReplyTo != null) {
                Column(
                    Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
            // What this message answers, quoted the way the web quotes it: a
            // reply with nothing above it is a non-sequitur in a busy channel.
            message.inReplyTo?.let { parent ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(Nocturne.bg.copy(alpha = 0.5f))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Text(
                        parent.user?.name?.takeIf { it.isNotBlank() } ?: parent.user?.username.orEmpty(),
                        style = Type.body(10, FontWeight.SemiBold),
                        color = Nocturne.muted(0.55f),
                    )
                    Text(
                        parent.excerpt?.let(::plainTextFromHtml)?.takeIf { it.isNotBlank() }
                            ?: parent.message.orEmpty(),
                        style = Type.body(11),
                        color = Nocturne.muted(0.5f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
                }
            }
            message.uploads.orEmpty().forEach { upload ->
                DiscourseConfig.absoluteUrl(upload.url)?.let { url ->
                if (upload.isVideoUpload) {
                    ChatVideoTile(url, upload.displayFilename) { onOpenVideo(url) }
                } else {
                    // The upload knows its own size, so the bubble can be the
                    // right height before the bytes arrive. Without this the
                    // picture is zero-high at layout time and the conversation
                    // grows underneath whatever position it was just scrolled
                    // to — which is what leaves an opened chat short of its end.
                    val ratio = upload.width?.toFloat()
                        ?.div((upload.height ?: 0).toFloat())
                        ?.takeIf { it.isFinite() && it > 0f }
                    RemoteImage(
                        url,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(chatMediaHeight(ratio))
                            .clickable { onOpenImage(url) },
                        contentScale = ContentScale.Crop,
                    )
                }
                }
            }
            // `cooked` first, not `message`: the raw field is the markdown the
            // author typed, so every custom emoji rendered as `:xhj001:` and
            // every link as its bracket syntax. The parser is the same one the
            // reader uses, so chat gets emoji, mentions and links for free.
            //
            // A whole message, not an excerpt: its paragraph breaks are content,
            // and collapsing them ran a multi-paragraph message onto one line.
            val inlines = remember(message.cooked, message.message) {
                message.cooked?.takeIf { it.isNotBlank() }?.let { chatInlines(it) }
                    // No cooked HTML yet — a message this client just sent and
                    // is echoing optimistically. Raw is all there is.
                    ?: message.message?.takeIf { it.isNotBlank() }
                        ?.let { listOf(PostInline.Text(it)) }
                    .orEmpty()
            }
            if (inlines.isNotEmpty()) Column(
                Modifier.padding(
                    start = 12.dp,
                    end = 12.dp,
                    top = if (message.uploads.isNullOrEmpty() && message.inReplyTo == null) 8.dp else 6.dp,
                    bottom = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
            if (inlines.isNotEmpty()) InlineText(
                inlines = inlines,
                size = 14,
                lineExtra = 4,
                onOpenLink = onOpenLink,
                onOpenMention = onOpenMention,
            )
            }
        }
            ReactionRow(message, onReact)
        }
    }
}


/**
 * The true bottom of the list.
 *
 * `scrollToItem` lines an item's *top* up with the viewport, which leaves the
 * tail of a tall message — a picture, a long quote — hanging below the fold.
 * The overscroll afterwards is clamped at the end of the content, so it lands
 * flush whatever the last message turns out to be.
 */
private suspend fun LazyListState.scrollToEnd(lastIndex: Int) {
    scrollToItem(lastIndex)
    scrollBy(100_000f)
}

/**
 * What the list holds, in one type.
 *
 * The history, the mark where the reader had got to, and the messages this
 * device has sent but the server has not confirmed — all in one list, so that
 * putting the scroll position back after an older page lands is an index into
 * it rather than arithmetic across three `item` blocks.
 */
private sealed interface ChatRow {
    val key: String

    data object Loading : ChatRow {
        override val key get() = "loading"
    }

    data class Divider(val beforeId: Int) : ChatRow {
        override val key get() = "divider"
    }

    data class Message(val message: ChatMessage) : ChatRow {
        override val key get() = "m${message.id}"
    }

    data class Pending(val message: PendingChatMessage) : ChatRow {
        override val key get() = "p${message.localId}"
    }
}

/** Where the unread began when the channel was opened. */
@Composable
private fun UnreadDivider() {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(Nocturne.accent.copy(alpha = 0.35f)))
        Text(
            stringResource(R.string.chat_unread_divider),
            style = Type.body(11, FontWeight.SemiBold),
            color = Nocturne.accent,
        )
        Box(Modifier.weight(1f).height(1.dp).background(Nocturne.accent.copy(alpha = 0.35f)))
    }
}

/**
 * A message on its way.
 *
 * The same shape as a sent one, dimmed, with the clock replaced by what it is
 * actually doing — so the conversation reads as continuous while the round
 * trip happens underneath it.
 */
@Composable
private fun PendingBubble(message: PendingChatMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // Where the time goes on a message that has been sent, so a
            // conversation with one still going does not shuffle when it lands.
            Text(
                stringResource(R.string.chat_sending),
                style = Type.body(10),
                color = Nocturne.muted(0.4f),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Column(
                Modifier
                    .widthIn(max = ChatBubbleMaxWidth)
                    .clip(ChatBubbleShape)
                    .background(Nocturne.selected.copy(alpha = 0.6f))
                    .border(1.dp, Nocturne.divider, ChatBubbleShape),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
            // The picture it will be, at the size it will be, with how far it
            // has got drawn over it. The bubble is in the right place in the
            // conversation from the first frame, so nothing jumps when the
            // server answers and the real one takes its place.
            message.mediaPath?.let { path ->
                PendingMedia(path, message.isVideo, message.durationMs, message.progress)
            }
                if (message.text.isNotEmpty()) {
                    Text(
                        message.text,
                        style = Type.body(14),
                        color = Nocturne.muted(0.7f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** The outgoing picture or clip, dimmed, with the upload drawn over it. */
@Composable
private fun PendingMedia(path: String, isVideo: Boolean, durationMs: Long, progress: Float?) {
    val ratio = remember(path) {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, options)
        (options.outWidth.toFloat() / options.outHeight).takeIf { it.isFinite() && it > 0f }
    }
    Box(Modifier.fillMaxWidth().height(chatMediaHeight(ratio))) {
        AsyncImage(
            model = java.io.File(path),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            UploadProgressRing(progress ?: 0f, onCancel = null)
        }
        if (isVideo && durationMs > 0) {
            Text(
                formatClock(durationMs),
                style = Type.body(11, FontWeight.Medium),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * One emoji, by the name the server calls it.
 *
 * Chat's reaction payload carries names and no URLs, and where a name's
 * picture lives is `emojis.json`'s business — the same lookup the reader's
 * reaction line goes through.
 */
@Composable
private fun EmojiImage(name: String, size: Dp) {
    var url by remember(name) { mutableStateOf<String?>(null) }
    LaunchedEffect(name) {
        url = runCatchingCancellable { ServiceLocator.get.siteRepository.emojiUrl(name) }.getOrNull()
    }
    RemoteImage(
        DiscourseConfig.absoluteUrl(url),
        modifier = Modifier.size(size),
        contentScale = ContentScale.Fit,
        placeholder = false,
    )
}

/** The faces already on a message; tapping one adds or takes back your own. */
@Composable
private fun ReactionRow(message: ChatMessage, onReact: (String) -> Unit) {
    val reactions = message.reactions.orEmpty().filter { it.count > 0 }
    if (reactions.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        reactions.forEach { reaction ->
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(if (reaction.reacted) Nocturne.accent100 else Nocturne.bg)
                    .clickable { onReact(reaction.emoji) }
                    .padding(horizontal = 7.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                EmojiImage(reaction.emoji, 14.dp)
                Text(
                    "${reaction.count}",
                    style = Type.body(11, FontWeight.Medium),
                    color = if (reaction.reacted) Nocturne.accent else Nocturne.muted(0.55f),
                )
            }
        }
    }
}

/**
 * What can be done to one message.
 *
 * A sheet rather than a menu anchored to the bubble: a bubble can be most of
 * the width of the screen, and a menu hanging off one covers the conversation
 * around it — which is the thing being talked about.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionSheet(
    isMine: Boolean,
    onQuickReact: (String) -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onPickReaction: () -> Unit,
    onDelete: () -> Unit,
    /** Somebody else's message: the two things you can do about one. */
    onReport: () -> Unit,
    onBlock: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = Nocturne.bg,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = Space.s6)) {
            // The handful of faces that carry most of what people mean, and
            // the whole picker behind them for everything else.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.s2),
                horizontalArrangement = Arrangement.spacedBy(Space.s3),
            ) {
                QuickReactions.forEach { emoji ->
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Nocturne.surface)
                            .clickable { onQuickReact(emoji) },
                        contentAlignment = Alignment.Center,
                    ) {
                        EmojiImage(emoji, 22.dp)
                    }
                }
            }
            ActionSheetRow(stringResource(R.string.chat_react), onPickReaction)
            ActionSheetRow(stringResource(R.string.reader_reply), onReply)
            ActionSheetRow(stringResource(R.string.chat_copy), onCopy)
            if (isMine) {
                ActionSheetRow(stringResource(R.string.chat_edit), onEdit)
                ActionSheetRow(stringResource(R.string.common_delete), onDelete, danger = true)
            } else {
                ActionSheetRow(stringResource(R.string.reader_report), onReport, danger = true)
                ActionSheetRow(stringResource(R.string.post_more_block_author), onBlock, danger = true)
            }
        }
    }
}

/** The four the web puts on its hover bar, in the same order. */
private val QuickReactions = listOf("+1", "heart", "joy", "tada")

@Composable
private fun ActionSheetRow(label: String, onClick: () -> Unit, danger: Boolean = false) {
    Text(
        label,
        style = Type.body(15),
        color = if (danger) Nocturne.danger else Nocturne.text,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.page, vertical = 14.dp),
    )
}

/**
 * Everyone in a group message.
 *
 * A one-to-one conversation goes straight to the other person's profile
 * instead — a list of one is a menu item that costs an extra tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatMembersSheet(
    members: List<ChatUser>,
    onOpenProfile: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = Nocturne.bg,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = Space.s6)) {
            Text(
                pluralStringResource(R.plurals.chat_member_count, members.size, members.size),
                style = Type.body(12, FontWeight.SemiBold),
                color = Nocturne.muted(0.5f),
                modifier = Modifier.padding(horizontal = Space.page, vertical = Space.s2),
            )
            members.forEach { member ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenProfile(member.username) }
                        .padding(horizontal = Space.page, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s3),
                ) {
                    RemoteAvatar(
                        DiscourseConfig.avatarUrl(member.avatarTemplate, 80),
                        member.username.take(1).uppercase(),
                        size = 32.dp,
                    )
                    Text(
                        member.name?.takeIf { it.isNotBlank() } ?: member.username,
                        style = Type.body(14),
                        color = Nocturne.text,
                    )
                }
            }
        }
    }
}

/**
 * How loudly a channel talks.
 *
 * The three the server keeps — `always`, `mention`, `never` — in that order,
 * because that is loudest-first and the one being turned down is the one
 * people come here to change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationLevelSheet(
    current: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = Nocturne.bg,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = Space.s6)) {
            Text(
                stringResource(R.string.chat_notification_level),
                style = Type.body(12, FontWeight.SemiBold),
                color = Nocturne.muted(0.5f),
                modifier = Modifier.padding(horizontal = Space.page, vertical = Space.s2),
            )
            ChatNotificationLevels.forEach { (level, labels) ->
                val (label, detail) = labels
                val selected = level == (current ?: "mention")
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(level) }
                        .padding(horizontal = Space.page, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        stringResource(label),
                        style = Type.body(14, if (selected) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (selected) Nocturne.accent else Nocturne.text,
                    )
                    Text(stringResource(detail), style = Type.body(12), color = Nocturne.muted(0.5f))
                }
            }
        }
    }
}

/** The server's own enum names, paired with what they mean. */
private val ChatNotificationLevels = listOf(
    "always" to (R.string.chat_level_always to R.string.chat_level_always_detail),
    "mention" to (R.string.chat_level_mention to R.string.chat_level_mention_detail),
    "never" to (R.string.chat_level_never to R.string.chat_level_never_detail),
)

/**
 * Whether this upload is a clip rather than a picture.
 *
 * By extension, because that is all chat gives us: the message processor only
 * measures and thumbnails images, so a video arrives with no width, no height
 * and nothing else to tell it apart.
 */
private val DiscourseUpload.isVideoUpload: Boolean
    get() = fileExtension?.lowercase() in setOf("mp4", "mov", "m4v", "webm", "mkv", "avi", "3gp")

/**
 * How large a picture or a clip is allowed to be in a bubble.
 *
 * A message is read at a glance and scrolled past; a portrait photograph at the
 * full width of the bubble is four hundred points tall and pushes everything
 * said around it off the screen. Tapping it opens the viewer, which is where a
 * picture is meant to be looked at.
 */
/** The widest a bubble gets; its media takes the same, so it reaches both edges. */
private val ChatBubbleMaxWidth = 280.dp

/** Named once, because the clip, the border and the media all have to agree. */
private val ChatBubbleShape = RoundedCornerShape(Radius.lg)
private val ChatMediaMaxWidth = ChatBubbleMaxWidth
private val ChatMediaMaxHeight = 320.dp

/**
 * How tall the media is when it spans the bubble.
 *
 * Width is not a question: it takes the whole bubble, which is what filling it
 * means, and leaves no band of bubble colour down either side. Height follows
 * the shape until the cap, past which a very tall picture is shown cropped
 * rather than turned into a column half a screen high. Tapping it opens the
 * viewer, which shows the whole of it.
 */
private fun chatMediaHeight(ratio: Float?): androidx.compose.ui.unit.Dp {
    val shape = ratio?.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
    return (ChatMediaMaxWidth / shape).coerceAtMost(ChatMediaMaxHeight)
}

/**
 * A clip in a bubble: its own first frame, a play badge and how long it runs.
 *
 * The frame and the length are read off the file itself, because chat's
 * serialiser has neither — see [RemoteVideoPreviews]. Until that answers, and
 * for a clip it cannot open, the tile keeps the filename it used to be, so a
 * video never disappears from a message just because it could not be measured.
 */
@Composable
private fun ChatVideoTile(url: String, filename: String, onOpen: () -> Unit) {
    val context = LocalContext.current
    var preview by remember(url) { mutableStateOf<RemoteVideoPreview?>(null) }
    LaunchedEffect(url) { preview = RemoteVideoPreviews.of(context, url) }

    val shown = preview
    if (shown == null) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.sm))
                .background(Nocturne.bg)
                .clickable(onClick = onOpen)
                .padding(horizontal = Space.card, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Icon(
                Lucide.Play,
                stringResource(R.string.common_play),
                tint = Nocturne.accent,
                modifier = Modifier.size(18.dp),
            )
            Text(
                filename,
                style = Type.body(13),
                color = Nocturne.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }

    val poster = remember(shown.posterPath) { java.io.File(shown.posterPath) }
    val ratio = remember(shown.posterPath) {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(shown.posterPath, options)
        (options.outWidth.toFloat() / options.outHeight).takeIf { it.isFinite() && it > 0f }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(chatMediaHeight(ratio))
            .background(Nocturne.bg)
            .clickable(onClick = onOpen),
    ) {
        AsyncImage(
            model = poster,
            contentDescription = filename,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // On the frame rather than beside it, and dark under the glyph, because
        // a white play arrow on a pale still is nothing at all.
        Box(
            Modifier
                .align(Alignment.Center)
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Lucide.Play,
                stringResource(R.string.common_play),
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        if (shown.durationMs > 0) {
            Text(
                formatClock(shown.durationMs),
                style = Type.body(11, FontWeight.Medium),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * `@name ` at the caret, with a space in front of it where one is needed.
 *
 * The same shape as the emoji picker's insert, and for the same reason: what
 * Discourse stores is text, and a mention is only a mention because of how it
 * is written.
 */
private fun insertMention(value: TextFieldValue, username: String): TextFieldValue {
    val at = value.selection.start.coerceIn(0, value.text.length)
    val end = value.selection.end.coerceIn(at, value.text.length)
    val before = value.text.take(at)
    val lead = if (before.isEmpty() || before.last().isWhitespace()) "" else " "
    val inserted = "$lead@$username "
    return TextFieldValue(
        value.text.replaceRange(at, end, inserted),
        TextRange(at + inserted.length),
    )
}
