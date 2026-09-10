package com.nodeloc.app.feature.post

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Video
import com.nodeloc.app.feature.compose.ComposerBlock
import com.nodeloc.app.feature.compose.AutocompleteStrip
import com.nodeloc.app.feature.compose.EmojiPickerSheet
import com.nodeloc.app.feature.compose.insertEmoji
import com.nodeloc.app.feature.media.ImageEditRenderer
import com.nodeloc.app.feature.media.ImageEditorDialog
import com.nodeloc.app.feature.media.VideoEditorDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.composables.icons.lucide.Film
import com.nodeloc.app.feature.media.GifPickerSheet
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.nodeloc.app.core.design.UploadProgressRing
import com.nodeloc.app.core.design.HeaderNodeIdentity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ArrowUpDown
import com.composables.icons.lucide.MessageSquareOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composables.icons.lucide.WifiOff
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Smile
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.CircleAlert
import com.nodeloc.app.core.design.ReportDialog
import com.nodeloc.app.core.design.ReportTarget
import com.nodeloc.app.core.design.BlockUserDialog
import com.nodeloc.app.R
import com.nodeloc.app.core.design.ConfirmDialog
import com.nodeloc.app.core.design.CapsuleIconButton
import com.nodeloc.app.core.design.BadgeTitleText
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FloatingHeaderBar
import com.nodeloc.app.core.design.HeaderGroup
import com.nodeloc.app.core.design.GuestAvatar
import com.nodeloc.app.core.design.HairLine
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.LowScoreFold
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.PostActionRow
import com.nodeloc.app.core.design.PostActionState
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.OnReachedEnd
import com.nodeloc.app.core.design.Radius
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.TagChip
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.floatingHeaderInset
import com.nodeloc.app.core.design.isScrolledPastTop
import com.nodeloc.app.core.design.rememberDismissKeyboardOnPullDown
import com.nodeloc.app.core.design.rememberTapSafeOverscroll
import com.nodeloc.app.core.html.PostContentView
import com.nodeloc.app.core.model.ReplySort
import com.nodeloc.app.core.model.VoteDirection
import com.nodeloc.app.core.model.isVoteCollapsed
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.store.PostEdits
import com.nodeloc.app.core.network.DiscourseError
import com.nodeloc.app.core.network.isOfflineError
import com.nodeloc.app.core.html.PostImage
import com.nodeloc.app.feature.media.ImageViewerOverlay
import com.nodeloc.app.feature.media.ImageViewerSource
import com.nodeloc.app.feature.media.PostImageViewer
import com.nodeloc.app.feature.media.ViewerActions
import com.nodeloc.app.feature.media.imageViewerFor
import com.nodeloc.app.feature.media.VideoViewerOverlay
import com.nodeloc.app.feature.feed.ReaderSkeleton
import com.nodeloc.app.feature.feed.RepliesSkeleton
import com.nodeloc.app.feature.nav.AppViewModel
import com.nodeloc.app.feature.nav.Navigator
import kotlinx.coroutines.launch

/**
 * The reader.
 *
 * Everything — body, plugin cards, action bar and the whole reply tree — is one
 * LazyColumn. That is deliberate and load-bearing: putting a long thread inside
 * an eager column is what made the iOS build hang on specific devices, and the
 * same hazard exists here in the form of one enormous measure pass.
 */
@Composable
fun ReaderScreen(
    topicId: Int,
    postNumber: Int?,
    app: AppViewModel,
    navigator: Navigator,
) {
    val viewModel: ReaderViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val isSignedIn by app.isSignedIn.collectAsState()
    val currentUser by app.currentUser.collectAsState()
    val isStaff = currentUser?.let { it.admin == true || it.moderator == true } == true
    /** The reply whose source is being edited, if any; the topic goes to the composer. */
    var editingPost by remember { mutableStateOf<Int?>(null) }

    // Null while the source is still being fetched, which is also what tells
    // the sheet there is nothing to type into yet. Not saveable: a rotation
    // mid-edit refetches, and refetching is cheaper than storing somebody's
    // half-rewritten post in the instance bundle.
    var editSource by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(editingPost) {
        val postId = editingPost
        editSource = null
        // A source that never arrives leaves the sheet on its loader with the
        // toast already shown — closing it out from under the reader would look
        // like the tap did nothing.
        if (postId != null) editSource = viewModel.postSource(postId)
    }

    // Coming back from the composer with the opening post rewritten. The thread
    // in hand is the one that was left behind, so it is refetched — and the
    // signal is only taken if it is this thread's post, so a reader looking at
    // something else leaves it for the one it belongs to.
    val savedElsewhere by PostEdits.saved.collectAsState()
    LaunchedEffect(savedElsewhere, state.firstPostId) {
        if (savedElsewhere != null && savedElsewhere == state.firstPostId) {
            PostEdits.consume()
            viewModel.reloadAfterEdit()
        }
    }
    var confirmDeleteTopic by remember { mutableStateOf(false) }
    val avatarUrl by app.avatarUrl.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    val scope = rememberCoroutineScope()

    // Saveable, like the draft they belong to: a rotation used to keep the
    // half-written reply but forget who it was addressed to, so it posted as a
    // top-level reply to the thread instead.
    var replyTargetNumber by rememberSaveable { mutableIntStateOf(-1) }
    var replyTargetAuthor by rememberSaveable { mutableStateOf("") }
    val replyTarget = replyTargetNumber.takeIf { it >= 0 }?.let { it to replyTargetAuthor }
    fun setReplyTarget(target: Pair<Int, String>?) {
        replyTargetNumber = target?.first ?: -1
        replyTargetAuthor = target?.second.orEmpty()
    }
    var replySheetOpen by rememberSaveable { mutableStateOf(false) }
    // Hoisted out of the sheet: dismissing the sheet destroys its
    // composition, and a half-written reply must outlive that the same way
    // its attachments do.
    var replyDraft by rememberSaveable { mutableStateOf("") }
    var trimmingVideo by remember { mutableStateOf<Uri?>(null) }

    // The reply's picture on its way through the editor, and whether it is
    // replacing the one already attached rather than becoming the first.
    var editingReplyBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var decodingReplyImage by remember { mutableStateOf(false) }
    var replyImageReplacing by remember { mutableStateOf(false) }
    /** PNG in, PNG out, so a screenshot's transparency survives the round trip. */
    var replyImagePng by remember { mutableStateOf(false) }
    var replyVideoReplacing by remember { mutableStateOf(false) }
    var editingReplyVideoEdit by remember { mutableStateOf<com.nodeloc.app.feature.media.VideoEdit?>(null) }
    val replyImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val sourceMime = context.contentResolver.getType(uri) ?: "image/jpeg"
        // A GIF passes through untouched — it is animated, and decoding it to a
        // bitmap would post one frame of it.
        if (sourceMime == "image/gif") {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                }
                if (bytes == null) ToastCenter.show(R.string.error_action_failed)
                else viewModel.uploadReplyMedia(bytes, "reply.gif", sourceMime, ComposerBlock.MediaKind.Gif)
            }
            return@rememberLauncherForActivityResult
        }
        // Everything else goes through the editor first, as it does in the
        // composer: crop and annotate before the upload, not after it has
        // landed in a reply. Decoding a phone-camera JPEG takes long enough
        // that doing it inline would freeze the sheet.
        //
        // `decodeUpright` is still what reads it: this path used to send the
        // picked file's raw bytes, a 12-megapixel camera JPEG uploaded whole,
        // lying on its side because nothing read its EXIF, and named
        // `reply.heic` for a format the forum cannot display.
        replyImagePng = sourceMime == "image/png"
        replyImageReplacing = false
        decodingReplyImage = true
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { ImageEditRenderer.decodeUpright(context, uri) }
            decodingReplyImage = false
            if (bitmap == null) ToastCenter.show(R.string.error_action_failed) else editingReplyBitmap = bitmap
        }
    }

    val replyVideoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let {
            replyVideoReplacing = false
            editingReplyVideoEdit = null
            trimmingVideo = it
        }
    }

    // A picture has to be fetched back before it can be edited again; a clip is
    // still on the device and only needs to be pointed at.
    fun editReplyMedia(index: Int) {
        val media = state.replyMedia.getOrNull(index) ?: return
        val source = media.sourceUri
        if (media.kind == ComposerBlock.MediaKind.Image) {
            replyImagePng = media.url.substringBefore('?').endsWith(".png", ignoreCase = true)
            replyImageReplacing = true
            decodingReplyImage = true
            scope.launch {
                val bitmap = viewModel.loadBitmap(DiscourseConfig.absoluteUrl(media.displayUrl).orEmpty())
                decodingReplyImage = false
                if (bitmap == null) {
                    replyImageReplacing = false
                    ToastCenter.show(R.string.error_action_failed)
                } else {
                    editingReplyBitmap = bitmap
                }
            }
        } else if (source != null) {
            replyVideoReplacing = true
            editingReplyVideoEdit = media.videoEdit
            trimmingVideo = Uri.parse(source)
        }
    }
    // Reporting used to open the site in a browser, which is the one place
    // the reader cannot see the reasons the server offers. Both of these are
    // held by the screen so a reply scrolling out of view cannot close them.
    var reportingPost by remember { mutableStateOf<Int?>(null) }
    var reportingAuthor by remember { mutableStateOf<String?>(null) }
    var blockingUser by remember { mutableStateOf<String?>(null) }
    var rewardTarget by remember { mutableStateOf<Int?>(null) }
    var rewardDetail by remember { mutableStateOf<List<com.nodeloc.app.core.model.PostReward>?>(null) }
    // The post whose reactions are being read, if any.
    var reactionDetail by remember { mutableStateOf<Int?>(null) }
    var videoUrl by remember { mutableStateOf<String?>(null) }
    // The same viewer the feed opens, carrying the same bar. Built from the
    // topic for a reply's pictures too — see `imageViewerFor`.
    var viewer by remember { mutableStateOf<PostImageViewer?>(null) }

    LaunchedEffect(topicId) { viewModel.bind(topicId) }
    listState.OnReachedEnd { viewModel.loadMoreComments() }

    // Deep link: once the thread is in place, scroll to the linked floor. Once
    // only — this used to re-key on the comment count, so every "load more"
    // and every expanded subtree dragged the reader back to the linked post.
    var jumpedToLink by rememberSaveable(topicId) { mutableStateOf(false) }
    LaunchedEffect(postNumber, state.comments.isNotEmpty()) {
        val target = postNumber ?: return@LaunchedEffect
        if (jumpedToLink || target <= 1 || state.comments.isEmpty()) return@LaunchedEffect
        val index = state.visibleComments.indexOfFirst { it.postNumber == target }
        if (index >= 0) {
            jumpedToLink = true
            listState.animateScrollToItem(index + HeaderItemCount)
        }
    }

    // Read progress: credit time to whatever is actually on screen. The whole
    // set each frame, not just arrivals — anything else never stops counting.
    LaunchedEffect(listState, state.comments) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .collect { indices ->
                val visible = state.visibleComments
                val onScreen = buildSet {
                    if (indices.any { it < HeaderItemCount }) add(1)
                    indices.forEach { index ->
                        visible.getOrNull(index - HeaderItemCount)?.let { add(it.postNumber) }
                    }
                }
                viewModel.setVisiblePosts(onScreen)
            }
    }

    // Drag the thread downwards and the keyboard goes with it — and only that;
    // see rememberDismissKeyboardOnPullDown.
    val dismissKeyboardOnPullDown = rememberDismissKeyboardOnPullDown()

    Box(Modifier.fillMaxSize().background(Nocturne.bg)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(dismissKeyboardOnPullDown),
            contentPadding = PaddingValues(top = floatingHeaderInset, bottom = 96.dp),
            // At the end of a long thread this is what kept like, reply, tip
            // and collapse from answering at all; see rememberTapSafeOverscroll.
            overscrollEffect = rememberTapSafeOverscroll(),
        ) {
            item {
                Column(
                    Modifier.padding(horizontal = Space.page),
                    verticalArrangement = Arrangement.spacedBy(Space.s4),
                ) {
                    AuthorHeader(state, navigator)
                    // The title and the body select and copy; everything around
                    // them does not. One container each rather than one around
                    // both, so a drag cannot run from the title through the
                    // banner between them and come out holding all three.
                    SelectionContainer {
                        Text(
                            state.title,
                            style = Type.heading(18, FontWeight.SemiBold),
                            color = Nocturne.text,
                        )
                    }
                    state.redEnvelope?.let { RedEnvelopeBanner(it) }
                    val body = @Composable {
                      SelectionContainer {
                        PostContentView(
                            content = state.content,
                            // A notch under the component default: a forum post
                            // is dense text, and 16sp reads like a pull quote.
                            baseSize = 15,
                            onOpenLink = { navigator.openUrl(context, it) },
                            onOpenImage = { image ->
                                viewer = imageViewerFor(state.content.images, image, state.imageViewerSource())
                            },
                            onOpenVideo = { video ->
                                videoUrl = DiscourseConfig.absoluteUrl(video.src)
                            },
                            onOpenMention = { navigator.openProfile(it) },
                            pollSlot = { name ->
                                state.polls.firstOrNull { it.pollName == name }?.let { poll ->
                                    PollView(
                                        poll = poll,
                                        myVotes = state.myPollVotes[name].orEmpty(),
                                        canVote = isSignedIn,
                                        onVote = { viewModel.vote(name, it) },
                                        onRemoveVote = { viewModel.removeVote(name) },
                                    )
                                }
                            },
                        )
                      }
                    }
                    // The opening post folds by score like any other, which is
                    // what the web does: the plugin's class goes on every post
                    // in the stream, not only on replies.
                    val opFolded = isVoteCollapsed(
                        state.firstPostVoteScore,
                        state.voteCollapseThreshold,
                    ) && state.firstPostId !in state.expandedLowScore
                    if (opFolded) {
                        LowScoreFold(
                            maxHeight = 96.dp,
                            onExpand = { state.firstPostId?.let { viewModel.expandLowScore(it) } },
                        ) { body() }
                    } else {
                        body()
                    }
                    state.lottery?.let { lottery ->
                        LotteryCard(lottery, canParticipate = isSignedIn) { quantity, random ->
                            viewModel.participateInLottery(quantity, random)
                        }
                    }
                    TopicActionBar(
                        state = state,
                        isSignedIn = isSignedIn,
                        onLike = {
                            if (isSignedIn) viewModel.toggleFirstPostLike() else app.showLoginGate()
                        },
                        onCastVote = { direction, face ->
                            if (isSignedIn) viewModel.voteOnFirstPost(direction, face)
                            else app.showLoginGate()
                        },
                        onComment = {
                            setReplyTarget(null)
                            replySheetOpen = true
                        },
                        onRepost = {
                            if (isSignedIn) {
                                navigator.openCompose(repostTopicId = topicId, prefillTitle = state.title)
                            } else {
                                app.showLoginGate()
                            }
                        },
                        onReactionDetail = { reactionDetail = state.firstPostId },
                        onRewardDetail = { rewardDetail = state.firstPostRewards },
                        onReward = {
                            if (isSignedIn) rewardTarget = state.firstPostId else app.showLoginGate()
                        },
                    )
                }
                // The band is a divider, and a divider that touches the row
                // above it reads as that row's own edge.
                Spacer(Modifier.height(Space.s2))
                // The same band that separates one thread from the next: the
                // post and its replies are two different things to read, and a
                // hairline says "next row" where this needs to say "next part".
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(Space.s3)
                        .background(Nocturne.surface),
                )
            }

            if (state.repliesFailed) {
                item(key = "replies-failed") {
                    EmptyStateView(
                        // Deliberately not branching on state.error: that is set
                        // by a failed *thread* load and outlives it, so an old
                        // offline error would claim this unrelated failure was
                        // the network too.
                        icon = Lucide.MessageSquareOff,
                        title = stringResource(R.string.reader_replies_failed),
                        retryLabel = stringResource(R.string.common_retry),
                        onRetry = { viewModel.retryReplies() },
                    )
                }
            }

            itemsIndexed(
                state.visibleComments,
                key = { _, comment -> "${comment.id}-${comment.isLoadMore}" },
            ) { index, comment ->
                // A root reply and its descendants are one conversation. The
                // rails already say who answers whom inside a thread; the gap is
                // what says where one thread ends and the next begins.
                if (index > 0 && comment.nestingDepth == 0 && !comment.isLoadMore) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(Space.s3)
                            .background(Nocturne.surface),
                    )
                }
                ReplyRow(
                    comment = comment,
                    collapsed = comment.postNumber in state.collapsed,
                    isLoadingChildren = comment.loadMoreParent in state.loadingChildren,
                    onToggleCollapse = { viewModel.toggleCollapsed(comment.postNumber) },
                    onOpenAuthor = { navigator.openProfile(it) },
                    onLike = {
                        if (isSignedIn) viewModel.toggleReplyLike(comment.id) else app.showLoginGate()
                    },
                    onVote = {
                        if (isSignedIn) viewModel.voteOnReply(comment.id, it) else app.showLoginGate()
                    },
                    onPickFace = if (isSignedIn) { direction, face ->
                        viewModel.voteOnReply(comment.id, direction, face)
                    } else null,
                    lowScore = isVoteCollapsed(
                        comment.voteScore,
                        state.voteCollapseThreshold,
                    ) && comment.id !in state.expandedLowScore,
                    onExpandLowScore = { viewModel.expandLowScore(comment.id) },
                    onReply = {
                        if (isSignedIn) {
                            setReplyTarget(comment.postNumber to comment.author)
                            replySheetOpen = true
                        } else {
                            app.showLoginGate()
                        }
                    },
                    onRewardDetail = { rewardDetail = comment.rewards },
                    onReactionDetail = { reactionDetail = comment.id },
                    onReward = {
                        if (isSignedIn) rewardTarget = comment.id else app.showLoginGate()
                    },
                    // A reply has its own permalink; sharing the topic from a
                    // reply would drop whoever opens it at the wrong post.
                    onShare = {
                        navigator.share(
                            context,
                            "${DiscourseConfig.BASE_URL}/t/$topicId/${comment.postNumber}",
                            state.title,
                        )
                    },
                    bookmarked = comment.isBookmarked,
                    onBookmark = {
                        if (isSignedIn) viewModel.bookmarkPost(comment.id) else app.showLoginGate()
                    },
                    onReport = {
                        reportingPost = comment.id
                        reportingAuthor = comment.authorName ?: comment.author
                    },
                    onBlockAuthor = { blockingUser = comment.author },
                    // Staff only, and only a top-level reply — the server
                    // refuses the rest, and an option that always fails is
                    // worse than one that is not there.
                    onTogglePin = { viewModel.togglePinned(comment.id) }
                        .takeIf { isStaff && comment.nestingDepth == 0 },
                    onEdit = { editingPost = comment.id },
                    onDelete = { viewModel.deletePost(comment.id) },
                    onRecover = { viewModel.recoverPost(comment.id) },
                    onToggleLock = { viewModel.setPostLocked(comment.id, !comment.locked) },
                    isStaff = isStaff,
                    onOpenLink = { navigator.openUrl(context, it) },
                    onOpenImage = { image ->
                        viewer = imageViewerFor(comment.content.images, image, state.imageViewerSource(withActions = false))
                    },
                    onLoadMoreChildren = viewModel::loadMoreChildren,
                    onOpenVideo = { videoUrl = DiscourseConfig.absoluteUrl(it.src) },
                    // The reply's own polls, not the topic's: each post carries
                    // its own, and a vote is recorded against the post holding
                    // the poll.
                    pollSlot = { name ->
                        comment.polls.firstOrNull { it.pollName == name }?.let { poll ->
                            PollView(
                                poll = poll,
                                myVotes = comment.myPollVotes[name].orEmpty(),
                                canVote = isSignedIn,
                                onVote = { viewModel.vote(name, it, postId = comment.id) },
                                onRemoveVote = { viewModel.removeVote(name, postId = comment.id) },
                            )
                        }
                    },
                )
            }

            // The replies are a second request, so they are still coming when
            // the post itself has rendered and the full-screen skeleton has
            // come off. Continuing the skeleton reads as the same load
            // finishing; the spinner that used to be here read as a second one
            // starting, which is what made opening a topic look like it loaded
            // twice. Paging further down is a genuinely new request, and keeps
            // the spinner.
            when {
                state.isLoading && state.comments.isEmpty() -> item { RepliesSkeleton() }

                state.isLoadingMore -> item {
                    Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                        NodelocLoader(height = 40.dp)
                    }
                }
            }
        }

        // First load has nothing to decorate: no title, no body, no replies. The
        // empty shell gets covered the same way a failed one does, with a shape
        // that promises an article rather than a list.
        if (state.isLoading && state.title.isEmpty()) {
            Box(Modifier.fillMaxSize().background(Nocturne.bg)) {
                ReaderSkeleton(Modifier.padding(top = floatingHeaderInset))
            }
        }

        // A topic that never arrived has to say so. The empty shell — author
        // row, zero counts, reply bar — reads as "this topic is blank" rather
        // than "this didn't load", so it gets covered rather than decorated.
        if (state.failedToLoad) {
            val offline = state.error?.isOfflineError() == true
            Box(
                Modifier.fillMaxSize().background(Nocturne.bg),
                contentAlignment = Alignment.Center,
            ) {
                EmptyStateView(
                    icon = if (offline) Lucide.WifiOff else Lucide.CircleAlert,
                    title = stringResource(
                        when {
                            offline -> R.string.error_offline
                            (state.error as? DiscourseError)?.isNotFound == true -> R.string.error_not_found
                            else -> R.string.error_decoding
                        },
                    ),
                    retryLabel = stringResource(R.string.common_retry),
                    onRetry = { viewModel.load(topicId) },
                )
            }
        }

        ReaderHeader(
            state = state,
            avatarUrl = avatarUrl,
            isSignedIn = isSignedIn,
            showNodePill = listState.isScrolledPastTop(threshold = 60),
            scrolledUnder = listState.isScrolledPastTop(threshold = 0),
            onClose = navigator::back,
            onOpenNode = { state.categoryId?.let { navigator.openNode(it) } },
            onSort = viewModel::applySort,
            onCopyLink = {
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText("nodeloc", "${DiscourseConfig.BASE_URL}/t/$topicId"),
                )
                ToastCenter.show(R.string.reader_link_copied)
            },
            onShare = { navigator.share(context, "${DiscourseConfig.BASE_URL}/t/$topicId", state.title) },
            onBookmark = {
                if (isSignedIn) viewModel.bookmarkFirstPost() else app.showLoginGateAfterDismiss()
            },
            onOpenProfile = { navigator.openProfile(it) },
            onOpenMe = navigator::openMe,
            onLogin = { app.showLoginGate() },
            onReport = {
                state.firstPostId?.let {
                    reportingPost = it
                    reportingAuthor = state.firstAuthor?.displayName ?: state.firstAuthor?.username
                }
            },
            onBlockAuthor = { state.firstAuthor?.username?.let { blockingUser = it } },
            // The topic goes to the full composer, not the sheet: it has a
            // title, it may have pictures, and those are the composer's job.
            onEditFirstPost = {
                state.firstPostId?.let {
                    navigator.openCompose(prefillTitle = state.title, editPostId = it)
                }
            },
            isStaff = isStaff,
            onToggleClosed = { viewModel.setTopicClosed(!state.closed) },
            onDeleteTopic = { confirmDeleteTopic = true },
        )

        if (!state.failedToLoad) {
            ReplyBar(
                isSignedIn = isSignedIn,
                replyTarget = replyTarget,
                draft = replyDraft,
                onClearTarget = { setReplyTarget(null) },
                onLogin = { app.showLoginGate() },
                onOpen = { replySheetOpen = true },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // The first post's own buttons, under the picture and under the clip.
        // A clip swiped to in the video feed belongs to a different topic: the
        // viewer casts its vote itself, and the rest of what those buttons do
        // needs a sheet or the composer — neither of which can open over a
        // full-screen dialog — so they take the reader to that topic instead.
        fun closeViewers() {
            viewer = null
            videoUrl = null
        }

        fun leaveFor(post: PostActionState): Boolean {
            val other = post.topicId?.takeIf { it != topicId } ?: return false
            closeViewers()
            navigator.openTopic(other)
            return true
        }

        val viewerActions = ViewerActions(
            onLike = {
                if (!isSignedIn) app.showLoginGate() else if (!leaveFor(it)) viewModel.toggleFirstPostLike()
            },
            onCastVote = { post, direction, face ->
                if (!isSignedIn) app.showLoginGate()
                else if (!leaveFor(post)) viewModel.voteOnFirstPost(direction, face)
            },
            onComment = {
                if (!leaveFor(it)) {
                    closeViewers()
                    setReplyTarget(null)
                    replySheetOpen = true
                }
            },
            onRepost = {
                if (!isSignedIn) {
                    app.showLoginGate()
                } else if (!leaveFor(it)) {
                    closeViewers()
                    navigator.openCompose(repostTopicId = topicId, prefillTitle = state.title)
                }
            },
            onReward = {
                if (!isSignedIn) {
                    app.showLoginGate()
                } else if (!leaveFor(it)) {
                    closeViewers()
                    rewardTarget = state.firstPostId
                }
            },
        )

        videoUrl?.let { url ->
            VideoViewerOverlay(url, state.imageViewerSource(), topicId, viewerActions) { videoUrl = null }
        }

        if (replySheetOpen) {
            ReplySheet(
                replyTarget = replyTarget,
                text = replyDraft,
                onTextChange = { replyDraft = it },
                media = state.replyMedia,
                uploadProgress = state.replyUploadProgress,
                isSubmitting = state.isSubmitting,
                onClearTarget = { setReplyTarget(null) },
                onPickImage = {
                    replyImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onPickVideo = {
                    replyVideoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                },
                onSearchGifs = viewModel::searchGifs,
                onPickGif = viewModel::attachReplyGif,
                onRemoveMedia = viewModel::removeReplyMedia,
                onEditMedia = ::editReplyMedia,
                onCancelUpload = viewModel::cancelReplyUpload,
                onDismiss = { replySheetOpen = false },
                onSubmit = {
                    scope.launch {
                        val target = replyTarget?.first
                        // A failed send keeps the draft, its target and its
                        // attachments — the sheet stays open to try again — so
                        // the clearing happens only once the reply exists.
                        val created = viewModel.submitReply(replyDraft, target) {
                            replyDraft = ""
                            setReplyTarget(null)
                            replySheetOpen = false
                        }
                        if (created != null) {
                            val index = viewModel.state.value.visibleComments
                                .indexOfFirst { it.postNumber == created }
                            if (index >= 0) listState.animateScrollToItem(index + HeaderItemCount)
                        }
                    }
                },
            )
        }

        // The same sheet, not one shaped like it. Editing a reply is writing a
        // reply over again — the same field, autocomplete, attachments and send
        // button — and a second editor was a second place for all of that to
        // drift.
        editingPost?.let { postId ->
            ReplySheet(
                replyTarget = null,
                editing = true,
                pending = editSource == null,
                text = editSource.orEmpty(),
                onTextChange = { editSource = it },
                media = state.replyMedia,
                uploadProgress = state.replyUploadProgress,
                isSubmitting = state.isSubmitting,
                onClearTarget = {},
                onPickImage = {
                    replyImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onPickVideo = {
                    replyVideoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                },
                onSearchGifs = viewModel::searchGifs,
                onPickGif = viewModel::attachReplyGif,
                onRemoveMedia = viewModel::removeReplyMedia,
                onEditMedia = ::editReplyMedia,
                onCancelUpload = viewModel::cancelReplyUpload,
                onDismiss = { editingPost = null },
                onSubmit = {
                    viewModel.editPost(postId, editSource.orEmpty()) { editingPost = null }
                },
            )
        }

        if (confirmDeleteTopic) {
            ConfirmDialog(
                title = stringResource(R.string.reader_topic_delete_confirm),
                body = stringResource(R.string.reader_topic_delete_confirm_body),
                confirmLabel = stringResource(R.string.reader_topic_delete),
                onConfirm = {
                    confirmDeleteTopic = false
                    viewModel.deleteTopic { navigator.back() }
                },
                onDismiss = { confirmDeleteTopic = false },
            )
        }

        trimmingVideo?.let { uri ->
            VideoEditorDialog(
                source = uri,
                sends = false,
                initial = editingReplyVideoEdit,
                onCancel = {
                    trimmingVideo = null
                    replyVideoReplacing = false
                    editingReplyVideoEdit = null
                },
                onDone = { result ->
                    trimmingVideo = null
                    val replacing = replyVideoReplacing
                    replyVideoReplacing = false
                    editingReplyVideoEdit = null
                    viewModel.uploadReplyVideo(result, source = uri, replacing = replacing)
                },
            )
        }

        reportingPost?.let { postId ->
            ReportDialog(ReportTarget.Post(postId), reportingAuthor) {
                reportingPost = null
                reportingAuthor = null
            }
        }

        blockingUser?.let { username ->
            BlockUserDialog(username, onDismiss = { blockingUser = null })
        }

        editingReplyBitmap?.let { bitmap ->
            ImageEditorDialog(
                source = bitmap,
                onCancel = { editingReplyBitmap = null; replyImageReplacing = false },
                onDone = { edited ->
                    editingReplyBitmap = null
                    val replacing = replyImageReplacing
                    replyImageReplacing = false
                    scope.launch {
                        // PNG for a PNG: the editor can leave transparency
                        // behind it, and a screenshot re-encoded as JPEG
                        // composites that onto black.
                        val png = replyImagePng
                        val bytes = withContext(Dispatchers.Default) {
                            if (png) ImageEditRenderer.encodePng(edited) else ImageEditRenderer.encode(edited)
                        }
                        viewModel.uploadReplyMedia(
                            bytes = bytes,
                            fileName = if (png) "reply.png" else "reply.jpg",
                            mimeType = if (png) "image/png" else "image/jpeg",
                            replacing = replacing,
                        )
                    }
                },
            )
        }

        if (decodingReplyImage) {
            Box(
                Modifier.fillMaxSize().background(Nocturne.bg.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                NodelocLoader(height = 44.dp)
            }
        }

        viewer?.let { open ->
            ImageViewerOverlay(open.images, open.index, open.source, viewerActions) { viewer = null }
        }

        reactionDetail?.let { postId ->
            ReactionDetailSheet(
                postId = postId,
                onOpenProfile = { reactionDetail = null; navigator.openProfile(it) },
                onDismiss = { reactionDetail = null },
            )
        }

        rewardDetail?.let { rewards ->
            RewardDetailSheet(rewards) { rewardDetail = null }
        }

        rewardTarget?.let { postId ->
            RewardSheet(
                onDismiss = { rewardTarget = null },
                onConfirm = { amount, note ->
                    viewModel.giveReward(postId, amount, note)
                    rewardTarget = null
                },
            )
        }
    }
}

/** Header block + hairline occupy one list slot ahead of the replies. */
private const val HeaderItemCount = 1

@Composable
private fun AuthorHeader(state: ReaderState, navigator: Navigator) {
    val author = state.firstAuthor
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
        RemoteAvatar(
            author?.avatarUrl,
            author?.initial ?: "?",
            size = 34.dp,
            modifier = Modifier.clickable(enabled = author != null) {
                author?.let { navigator.openProfile(it.username) }
            },
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    // `name` comes back as "" — not null — for an account that
                    // never set a display name, so `?:` alone leaves the line
                    // blank instead of falling through to the username.
                    author?.displayName?.takeIf { it.isNotBlank() }
                        ?: author?.username.orEmpty(),
                    style = Type.body(14, FontWeight.SemiBold),
                    color = Nocturne.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                state.firstAuthorTitle?.takeIf { it.isNotBlank() }?.let {
                    BadgeTitleText(it, size = 11)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // The handle, as everywhere else a node is named in a list: it
                // is what the node is called in a link, and it is unambiguous
                // where two nodes share a display name.
                Text(
                    when {
                        state.isPrivateMessage -> state.pmHandle.ifBlank { state.pmName }
                        state.nodeSlug.isNotBlank() -> "n/${state.nodeSlug}"
                        else -> state.nodeName
                    },
                    style = Type.body(12),
                    color = Nocturne.accent,
                )
                Text("·", style = Type.body(12), color = Nocturne.muted(0.3f))
                Text(state.createdAt, style = Type.body(12), color = Nocturne.muted(0.45f))
                // After the time rather than on a line of its own: this row is
                // already the line under the name, and a third one under a
                // 34dp avatar would leave the header taller than the title it
                // sits beneath.
                state.firstPostSource?.takeIf { it.isNotBlank() }?.let {
                    TagChip(stringResource(R.string.post_source_from, it))
                }
            }
        }
    }
}

@Composable
private fun TopicActionBar(
    state: ReaderState,
    isSignedIn: Boolean,
    onLike: () -> Unit,
    onCastVote: (VoteDirection, String?) -> Unit,
    onComment: () -> Unit,
    onRepost: () -> Unit,
    onReward: () -> Unit,
    onRewardDetail: () -> Unit,
    onReactionDetail: () -> Unit,
) {
    val rewardTotal = state.firstPostRewards.filter { it.amount > 0 }.sumOf { it.amount }

    Column(
        Modifier.fillMaxWidth().padding(top = Space.s3),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        PostRecordLine(
            reactions = state.firstPostReactions,
            reactionCount = state.firstPostReactionCount,
            rewardTotal = rewardTotal,
            onReactionDetail = onReactionDetail,
            onRewardDetail = onRewardDetail,
        )

        PostActionRow(
            state = state.postActions(),
            onLike = onLike,
            onCastVote = onCastVote,
            onComment = onComment,
            onRepost = onRepost,
            onReward = onReward,
        )
    }
}

/**
 * The first post's buttons, as the shared row wants them.
 *
 * The same figures reach the full-screen viewer, which draws the same row over
 * the picture — see [ReaderState.imageViewerSource].
 */
private fun ReaderState.postActions(): PostActionState = PostActionState(
    postId = firstPostId,
    topicId = topicId,
    voteScore = firstPostVoteScore,
    voteDirection = firstPostVoteDirection,
    canVoteDown = firstPostCanVoteDown,
    liked = firstPostLiked,
    likeCount = firstPostLikeCount,
    commentCount = totalReplyCount,
    rewarded = firstPostRewards.any { it.amount > 0 },
)

@Composable
private fun ReaderHeader(
    state: ReaderState,
    avatarUrl: String?,
    isSignedIn: Boolean,
    showNodePill: Boolean,
    /** Anything at all under the bar; fills it in. Separate from the pill's cue. */
    scrolledUnder: Boolean,
    onClose: () -> Unit,
    onOpenNode: () -> Unit,
    onSort: (ReplySort) -> Unit,
    onCopyLink: () -> Unit,
    onShare: () -> Unit,
    onBookmark: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenMe: () -> Unit,
    onLogin: () -> Unit,
    onReport: () -> Unit,
    onBlockAuthor: () -> Unit,
    onEditFirstPost: () -> Unit,
    /** Staff only. Absent for everyone else rather than shown and refused. */
    isStaff: Boolean,
    onToggleClosed: () -> Unit,
    onDeleteTopic: () -> Unit,
) {
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }

    FloatingHeaderBar(
        scrolled = scrolledUnder,
        leading = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeaderIconButton(Lucide.X, stringResource(R.string.common_close), onClick = onClose)
                // The same three facts the node page's own bar shows, in the
                // same order: a topic's node should not look like a different
                // node depending on which screen names it.
                AnimatedVisibility(visible = showNodePill, enter = fadeIn(), exit = fadeOut()) {
                    // A private message has no node to name, so it names the
                    // conversation: the other person, or the group it went to.
                    if (state.isPrivateMessage) {
                        HeaderNodeIdentity(
                            handle = state.pmHandle,
                            name = state.pmName,
                            logoUrl = state.pmAvatarUrl,
                            letter = state.pmName.take(1).uppercase().ifEmpty { "?" },
                        )
                    } else {
                        HeaderNodeIdentity(
                            handle = "n/${state.nodeSlug}",
                            name = state.nodeName,
                            logoUrl = state.nodeLogoUrl,
                            letter = state.nodeName.take(1).uppercase().ifEmpty { "N" },
                            onClick = onOpenNode,
                        )
                    }
                }
            }
        },
        trailing = {
            HeaderGroup {
                Box {
                    CapsuleIconButton(Lucide.ArrowUpDown, stringResource(R.string.reader_reply_sort), onClick = { sortMenu = true })
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        ReplySort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(option.labelRes),
                                        color = if (option == state.replySort) Nocturne.accent else Nocturne.text,
                                    )
                                },
                                onClick = { sortMenu = false; onSort(option) },
                            )
                        }
                    }
                }
                Box {
                    CapsuleIconButton(Lucide.EllipsisVertical, stringResource(R.string.common_more), onClick = { moreMenu = true })
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        // Text only: four short labels in a column are read as
                        // a list, and a glyph beside each one is decoration
                        // that has to be looked past to get to them.
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.reader_copy_link)) },
                            onClick = { moreMenu = false; onCopyLink() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_share)) },
                            onClick = { moreMenu = false; onShare() },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (state.firstPostBookmarked) {
                                            R.string.reader_unbookmark
                                        } else {
                                            R.string.reader_bookmark
                                        },
                                    ),
                                )
                            },
                            onClick = { moreMenu = false; onBookmark() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.reader_report)) },
                            onClick = { moreMenu = false; onReport() },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.post_more_block_author),
                                    color = Nocturne.danger,
                                )
                            },
                            onClick = { moreMenu = false; onBlockAuthor() },
                        )
                        // The opening post's own actions. They are here rather
                        // than on the post because the opening post is not a
                        // row with a menu of its own — the topic header is what
                        // it has instead.
                        if (state.firstPostCanEdit) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.reader_edit_first)) },
                                onClick = { moreMenu = false; onEditFirstPost() },
                            )
                        }
                        // Moderation last and separated by nothing but order:
                        // these are the two that change the thread for
                        // everybody, and they belong below the ones that only
                        // change it for you.
                        if (isStaff) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (state.closed) R.string.reader_topic_reopen
                                            else R.string.reader_topic_close,
                                        ),
                                    )
                                },
                                onClick = { moreMenu = false; onToggleClosed() },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.reader_topic_delete),
                                        color = Nocturne.danger,
                                    )
                                },
                                onClick = { moreMenu = false; onDeleteTopic() },
                            )
                        }
                    }
                }
                if (isSignedIn) {
                    RemoteAvatar(
                        avatarUrl,
                        stringResource(R.string.common_me),
                        size = 26.dp,
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .clickable(onClick = onOpenMe),
                    )
                } else {
                    Box(Modifier.padding(horizontal = 4.dp).clickable(onClick = onLogin)) {
                        GuestAvatar(size = 26.dp)
                    }
                }
            }
        },
    )
}

/**
 * The bar at the foot of a thread — a door, not a desk.
 *
 * Writing a reply needs room: a place for attachments, a toolbar, and a field
 * that can hold more than one line. None of that fits under a keyboard in a
 * 40dp strip, so the strip stops pretending and opens [ReplySheet] instead.
 */
@Composable
private fun ReplyBar(
    isSignedIn: Boolean,
    replyTarget: Pair<Int, String>?,
    draft: String,
    onClearTarget: () -> Unit,
    onLogin: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Nocturne.bg)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        HairLine()
        if (replyTarget != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.reader_reply_to, replyTarget.second),
                    style = Type.body(12),
                    color = Nocturne.accent,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Lucide.X,
                    stringResource(R.string.reader_cancel_reply),
                    tint = Nocturne.muted(0.45f),
                    modifier = Modifier.size(16.dp).clickable(onClick = onClearTarget),
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.page, vertical = 8.dp)
                .clip(RoundedCornerShape(Radius.lg))
                .background(Nocturne.surface)
                .clickable { if (isSignedIn) onOpen() else onLogin() }
                .padding(horizontal = Space.card, vertical = 12.dp),
        ) {
            // Showing the draft here is the only proof the user gets that
            // closing the sheet did not throw their reply away.
            val hasDraft = isSignedIn && draft.isNotBlank()
            Text(
                if (hasDraft) draft else stringResource(
                    if (isSignedIn) R.string.reader_reply_hint else R.string.reader_reply_guest,
                ),
                style = Type.body(14),
                color = if (hasDraft) Nocturne.text else Nocturne.muted(0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The reply composer.
 *
 * A sheet rather than a screen: the thread stays visible behind it, which is
 * most of what a reply is answering.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplySheet(
    replyTarget: Pair<Int, String>?,
    text: String,
    onTextChange: (String) -> Unit,
    media: List<ComposerBlock.Media>,
    /** Non-null only while a clip is going up; pictures are gone too fast. */
    uploadProgress: Float?,
    isSubmitting: Boolean,
    /** Rewriting a post rather than writing one; only the heading differs. */
    editing: Boolean = false,
    /** The post's source is still on its way; there is nothing to type into yet. */
    pending: Boolean = false,
    onClearTarget: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onSearchGifs: suspend (String) -> List<com.nodeloc.app.core.model.KlipyGif>,
    onPickGif: (String) -> Unit,
    onRemoveMedia: (Int) -> Unit,
    onEditMedia: (Int) -> Unit,
    onCancelUpload: () -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
) {
    // The hoisted draft is a plain String; the sheet owns the caret, and a
    // reopened draft is resumed from its end rather than its start. Keyed on
    // the fetch so an edit picks its text up when it lands: a reply is never
    // pending, so its field is still remembered exactly once.
    var field by remember(pending) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    var gifPickerOpen by remember { mutableStateOf(false) }
    var emojiPickerOpen by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // The sheet was opened to type in.
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Nocturne.bg,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.page)
                .padding(bottom = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            // Says which of the two this is. Nothing else changes: the same
            // field, the same attachments, the same send button — rewriting a
            // reply is the same act as writing one.
            if (editing) {
                Text(
                    stringResource(R.string.reader_edit_title),
                    style = Type.body(13, FontWeight.SemiBold),
                    color = Nocturne.muted(0.6f),
                )
            }

            if (replyTarget != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.reader_reply_to, replyTarget.second),
                        style = Type.body(12),
                        color = Nocturne.accent,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Lucide.X,
                        stringResource(R.string.reader_cancel_reply),
                        tint = Nocturne.muted(0.45f),
                        modifier = Modifier.size(16.dp).clickable(onClick = onClearTarget),
                    )
                }
            }

            // Above the field, not over it: the keyboard already owns the
            // bottom half of the screen here.
            AutocompleteStrip(value = field, onPick = { field = it; onTextChange(it.text) })

            if (pending) {
                Box(Modifier.fillMaxWidth().heightIn(min = 96.dp), Alignment.Center) {
                    NodelocLoader(height = 20.dp, tint = Nocturne.accent)
                }
            } else {
                BasicTextField(
                    value = field,
                    onValueChange = { field = it; onTextChange(it.text) },
                    textStyle = Type.body(15).copy(color = Nocturne.text, lineHeight = Type.lineHeight(15, 5)),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Nocturne.accent),
                    enabled = !isSubmitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp)
                        .focusRequester(focusRequester),
                    decorationBox = { inner ->
                        if (field.text.isEmpty()) {
                            Text(stringResource(R.string.reader_reply_hint), style = Type.body(15), color = Nocturne.muted(0.32f))
                        }
                        inner()
                    },
                )
            }

            if (media.isNotEmpty()) {
                ReplyMediaStrip(media, uploadProgress, onRemoveMedia, onEditMedia, onCancelUpload)
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s6),
            ) {
                // One attachment per reply: whichever kind was picked first
                // is the kind this reply has, and there is no second slot.
                val canAttach = media.isEmpty()
                ReplyToolbarIcon(Lucide.Image, stringResource(R.string.compose_image), canAttach, onPickImage)
                ReplyToolbarIcon(Lucide.Video, stringResource(R.string.compose_video), canAttach, onPickVideo)
                ReplyToolbarIcon(Lucide.Film, stringResource(R.string.compose_gif), canAttach) {
                    gifPickerOpen = true
                }
                ReplyToolbarIcon(Lucide.Smile, stringResource(R.string.compose_emoji), true) {
                    emojiPickerOpen = true
                }
                Spacer(Modifier.weight(1f))
                val ready = (field.text.isNotBlank() || media.any { !it.isPending }) &&
                    !isSubmitting &&
                    !pending &&
                    media.none { it.isPending }
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (ready) Nocturne.accent else Nocturne.surface)
                        .clickable(enabled = ready) {
                            keyboard?.hide()
                            onSubmit()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSubmitting) {
                        NodelocLoader(height = 18.dp, tint = Nocturne.bg)
                    } else {
                        Icon(
                            Lucide.Send,
                            stringResource(R.string.reader_send),
                            tint = if (ready) Nocturne.bg else Nocturne.muted(0.35f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }

    // Stacked above the reply sheet rather than replacing it: picking a GIF or
    // an emoji is a detour, and the half-written reply stays where it was.
    if (emojiPickerOpen) {
        EmojiPickerSheet(
            onPick = {
                field = insertEmoji(field, it)
                onTextChange(field.text)
            },
            onDismiss = { emojiPickerOpen = false },
        )
    }

    if (gifPickerOpen) {
        GifPickerSheet(
            search = onSearchGifs,
            onDismiss = { gifPickerOpen = false },
            onPick = {
                onPickGif(it)
                gifPickerOpen = false
            },
        )
    }
}

/** Attachments staged for a reply, at the same size the composer uses. */
@Composable
private fun ReplyToolbarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Icon(
        icon,
        label,
        tint = Nocturne.muted(if (enabled) 0.55f else 0.22f),
        // A dimmed icon that does nothing on tap reads as a broken button; the
        // toast is the only place the rule can be said.
        modifier = Modifier.size(22.dp).clickable {
            if (enabled) onClick() else ToastCenter.show(R.string.reader_reply_one_media)
        },
    )
}


/**
 * What the full-screen viewer's bar says about this topic — the same five
 * things a feed row would have handed it.
 *
 * A private message has no node, so the conversation's own handle stands in
 * rather than leaving the top of the viewer blank.
 */
private fun ReaderState.imageViewerSource(
    /**
     * A reply's picture opens this same viewer, and the buttons under it would
     * be the *first* post's — a vote cast from a photograph in a reply would
     * land on the topic. It gets the counts and no buttons, as it always had.
     */
    withActions: Boolean = true,
): ImageViewerSource = ImageViewerSource(
    node = if (isPrivateMessage) pmHandle else "n/$nodeSlug",
    title = title,
    authorName = firstAuthor?.displayName?.takeIf { it.isNotBlank() }
        ?: firstAuthor?.username.orEmpty(),
    avatarUrl = firstAuthor?.avatarUrl,
    avatarLetter = firstAuthor?.initial ?: title.take(1),
    likeCount = firstPostLikeCount,
    commentCount = totalReplyCount,
    actions = postActions().takeIf { withActions },
)

@Composable
private fun ReplyMediaStrip(
    media: List<ComposerBlock.Media>,
    uploadProgress: Float?,
    onRemove: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    onCancelUpload: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val itemWidth = (maxWidth - Space.s3 * 2) / 2.5f
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            media.forEachIndexed { index, item ->
                Box(
                    Modifier
                        .width(itemWidth)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(Radius.md))
                        .background(Nocturne.surface),
                ) {
                    AsyncImage(
                        model = item.pendingPreview?.let { java.io.File(it) }
                            ?: DiscourseConfig.absoluteUrl(item.displayUrl)
                            // A clip whose poster upload failed still has the frame here.
                            ?: item.posterPath?.let { java.io.File(it) },
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (item.isPending) {
                        Box(
                            Modifier.matchParentSize().background(Nocturne.bg.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            // A clip is slow enough that a spinner is a worse
                            // answer than a number.
                            if (uploadProgress != null) {
                                UploadProgressRing(uploadProgress, onCancel = onCancelUpload, diameter = 38.dp)
                            } else {
                                NodelocLoader(height = 22.dp)
                            }
                        }
                    } else {
                        Row(
                            Modifier.align(Alignment.TopEnd).padding(5.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            // A GIF from Klipy is hosted elsewhere and a clip
                            // restored without its file has nothing to reopen.
                            if (item.isReeditable) {
                                ReplyMediaAction(
                                    Lucide.Pencil,
                                    stringResource(R.string.compose_media_edit),
                                ) { onEdit(index) }
                            }
                            ReplyMediaAction(
                                Lucide.Trash2,
                                stringResource(R.string.compose_media_remove),
                            ) { onRemove(index) }
                        }
                    }
                }
            }
        }
    }
}

/** One control on a reply's attachment: a dark disc with an icon in it. */
@Composable
private fun ReplyMediaAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(Nocturne.bg.copy(alpha = 0.82f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Nocturne.text, modifier = Modifier.size(13.dp))
    }
}
