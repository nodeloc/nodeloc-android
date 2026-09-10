package com.nodeloc.app.feature.compose

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import coil3.compose.AsyncImage
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Trash2
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nodeloc.app.core.design.UploadProgressRing
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.ToastCenter
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import com.composables.icons.lucide.ChevronDown
import com.nodeloc.app.core.design.FloatingHeader
import androidx.compose.ui.graphics.Color
import com.nodeloc.app.core.design.Avatar
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.model.NodeSummary
import com.composables.icons.lucide.Film
import com.composables.icons.lucide.Mail
import com.composables.icons.lucide.Smile
import com.composables.icons.lucide.Video
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ChartNoAxesColumn
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Gift
import com.nodeloc.app.R
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.design.ConfirmDialog
import com.nodeloc.app.core.design.FieldSurface
import com.nodeloc.app.core.design.FilledAccentButton
import com.nodeloc.app.core.design.HairLine
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Radius
import com.nodeloc.app.core.design.RemoteImage
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.TagChip
import com.nodeloc.app.core.design.TagStyle
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.feature.media.ImageEditRenderer
import com.nodeloc.app.feature.media.ImageEditorDialog
import com.nodeloc.app.feature.media.GifPickerSheet
import com.nodeloc.app.feature.media.VideoEditorDialog
import com.nodeloc.app.feature.nav.Navigator
import com.nodeloc.app.feature.nav.Route

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(route: Route.Compose, navigator: Navigator) {
    val viewModel: ComposeViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val titleFocus = remember { FocusRequester() }
    val bodyFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val editPostId = route.editPostId

    // The composer is opened to write in, not to look at. A repost already has
    // its title, so the caret goes where there is something left to say: the
    // paragraph above the topic being carried over. An edit claims nothing —
    // its body is still arriving, and the words are already written, so the
    // reader of them should pick the place to change.
    LaunchedEffect(Unit) {
        if (editPostId != null) return@LaunchedEffect
        runCatching {
            if (route.repostTopicId == null) titleFocus.requestFocus() else bodyFocus.requestFocus()
        }
        keyboard?.show()
    }

    var nodePickerOpen by remember { mutableStateOf(false) }

    // Closing with something written still asks, but the question changed once
    // there was a draft store: leaving keeps the words, and only "discard"
    // throws them away. A staged red envelope or lottery is the exception — it
    // is not in the draft, so leaving does drop those two.
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    // Pending media counts: `isBlank` is true while an upload is in flight, so
    // a composer holding only a picture that has not landed yet looked empty.
    // A staged red envelope or lottery is a draft too.
    val hasDraft = state.title.isNotBlank() ||
        state.blocks.any { !it.isBlank() || it is ComposerBlock.Media } ||
        state.redEnvelope != null ||
        state.lottery != null
    fun closeComposer() {
        if (hasDraft) confirmDiscard = true else navigator.back()
    }
    BackHandler(enabled = hasDraft) { confirmDiscard = true }
    var gifPickerOpen by remember { mutableStateOf(false) }
    var emojiPickerOpen by remember { mutableStateOf(false) }
    // Which paragraph an emoji joins. The fields keep their own caret, so the
    // last one focused is the only thing the sheet can aim at.
    var focusedTextIndex by remember { mutableIntStateOf(-1) }
    var pollBuilderOpen by remember { mutableStateOf(false) }
    var envelopeSheetOpen by remember { mutableStateOf(false) }
    var lotterySheetOpen by remember { mutableStateOf(false) }

    var editingBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    // Set while an already-uploaded image is being re-edited; null when the
    // editor is being used for a freshly picked one.
    var editingMediaIndex by remember { mutableStateOf<Int?>(null) }
    // A clip being re-edited: which block it is, and what was decided about it
    // last time so the editor opens where it was left rather than at nothing.
    var editingVideoIndex by remember { mutableStateOf<Int?>(null) }
    var editingVideoEdit by remember { mutableStateOf<com.nodeloc.app.feature.media.VideoEdit?>(null) }
    var trimmingVideo by remember { mutableStateOf<Uri?>(null) }
    // Covers both the decode before the editor and the fetch before a re-edit.
    var decodingImage by remember { mutableStateOf(false) }
    // Every tool inserts into the body. With the caret in the title they have
    // nowhere to put anything, so offering them is a lie about what they do.
    var titleFocused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        if (mime.startsWith("video/")) {
            trimmingVideo = uri
            return@rememberLauncherForActivityResult
        }
        // Images go through the editor first — crop and annotate before the
        // upload, not after it lands in a post. Decoding a phone-camera JPEG
        // takes long enough that doing it here would freeze the composer.
        decodingImage = true
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                ImageEditRenderer.decodeUpright(context, uri)
            }
            decodingImage = false
            if (bitmap == null) ToastCenter.show(R.string.error_action_failed) else editingBitmap = bitmap
        }
    }

    // A picture has to be fetched back before it can be edited; a clip is
    // still on the device and only needs to be pointed at again.
    fun editMedia(index: Int) {
        val media = state.blocks.getOrNull(index) as? ComposerBlock.Media ?: return
        val source = media.sourceUri
        if (media.kind == ComposerBlock.MediaKind.Image || source == null) {
            editingMediaIndex = index
        } else {
            editingVideoIndex = index
            editingVideoEdit = media.videoEdit
            trimmingVideo = android.net.Uri.parse(source)
        }
    }

    // Re-editing needs the picture back: only its URL survived the upload.
    LaunchedEffect(editingMediaIndex) {
        val index = editingMediaIndex ?: return@LaunchedEffect
        val media = state.blocks.getOrNull(index) as? ComposerBlock.Media
        if (media == null) {
            editingMediaIndex = null
            return@LaunchedEffect
        }
        decodingImage = true
        val bitmap = viewModel.loadBitmap(DiscourseConfig.absoluteUrl(media.displayUrl).orEmpty())
        decodingImage = false
        if (bitmap == null) {
            editingMediaIndex = null
            ToastCenter.show(R.string.error_action_failed)
        } else {
            editingBitmap = bitmap
        }
    }

    LaunchedEffect(route.categoryId) {
        viewModel.bind(
            route.categoryId,
            route.prefillTitle,
            route.pmRecipient,
            route.repostTopicId,
            route.editPostId,
        )
    }

    Column(Modifier.fillMaxSize().background(Nocturne.bg).imePadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = Space.page, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            // Flat, not the floating HeaderIconButton: this bar is a solid
            // surface, and a shadow on one of three equal controls makes that
            // one read as taller than the others.
            HeaderPillButton(Lucide.X, stringResource(R.string.common_close), onClick = { closeComposer() })
            // The node is the one choice that gates publishing, so it takes the
            // centre — a screen title would only repeat what the button says.
            // A private message is already addressed, so the pill names the
            // recipient and has nothing to open.
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                val recipient = state.pmRecipient
                when {
                    // An edit does not choose a node: the topic is already in
                    // one, and `posts#update` could not move it if it wanted to.
                    editPostId != null -> Unit
                    recipient != null -> NodePill(label = "u/$recipient", selected = true, onClick = {})
                    else -> NodePill(
                        label = state.node?.name ?: stringResource(R.string.compose_select_node),
                        selected = state.node != null,
                        onClick = { nodePickerOpen = true },
                    )
                }
            }
            PublishPill(
                text = stringResource(
                    when {
                        editPostId != null -> R.string.common_save
                        route.repostTopicId != null -> R.string.reader_repost
                        else -> R.string.compose_publish
                    },
                ),
                enabled = when {
                    editPostId != null -> state.canSaveEdit
                    route.repostTopicId != null -> state.node != null
                    else -> state.canSubmit
                },
                loading = state.isSubmitting,
            ) {
                val repostId = route.repostTopicId
                when {
                    editPostId != null -> viewModel.saveEdit(editPostId) { navigator.back() }
                    repostId != null -> viewModel.repost(repostId) { navigator.back() }
                    else -> viewModel.submit { topicId ->
                        navigator.back()
                        navigator.openTopic(topicId)
                    }
                }
            }
        }

        // An edit has no draft behind it — nothing here was ever written to
        // disk — so the two-way "keep or discard" question would be offering a
        // choice that does not exist.
        if (confirmDiscard && editPostId != null) {
            ConfirmDialog(
                title = stringResource(R.string.compose_edit_discard_title),
                body = stringResource(R.string.compose_edit_discard_detail),
                confirmLabel = stringResource(R.string.compose_discard_confirm),
                onConfirm = {
                    confirmDiscard = false
                    navigator.back()
                },
                onDismiss = { confirmDiscard = false },
            )
        } else if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                containerColor = Nocturne.bg,
                title = {
                    Text(
                        stringResource(R.string.compose_discard_title),
                        style = Type.heading(17),
                        color = Nocturne.text,
                    )
                },
                text = {
                    Text(
                        stringResource(R.string.compose_discard_detail),
                        style = Type.body(13),
                        color = Nocturne.muted(0.6f),
                    )
                },
                // Leaving is now the safe half — the draft is already on disk —
                // so it takes the confirming position, and the destructive one
                // has to be reached for.
                confirmButton = {
                    TextButton(onClick = {
                        confirmDiscard = false
                        navigator.back()
                    }) {
                        Text(stringResource(R.string.compose_draft_keep), color = Nocturne.accent)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        confirmDiscard = false
                        viewModel.clearDraft()
                        navigator.back()
                    }) {
                        Text(stringResource(R.string.compose_discard_confirm), color = Nocturne.danger)
                    }
                },
            )
        }

        // One scrolling document, not a stack of form fields. Every mature
        // composer — Discourse's own, Reddit, X — writes straight onto the page:
        // a boxed title above a boxed body reads as paperwork, and the empty box
        // is the loudest thing on screen before a word is typed.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.page),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            // Only the attachments that exist claim a row; an empty one would
            // push the title down for nothing.
            if (state.redEnvelope != null || state.lottery != null) {
                Row(
                    Modifier.padding(top = Space.s3),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.redEnvelope?.let {
                        Box(Modifier.clickable { viewModel.setRedEnvelope(null) }) {
                            TagChip(stringResource(R.string.envelope_chip, it.totalPoints, it.totalCount), style = TagStyle.Accent2)
                        }
                    }
                    state.lottery?.let {
                        Box(Modifier.clickable { viewModel.setLottery(null) }) {
                            TagChip(stringResource(R.string.compose_lottery_chip), style = TagStyle.Accent2)
                        }
                    }
                }
            }

            OwnedTextField(
                external = state.title,
                onChange = viewModel::updateTitle,
                textStyle = Type.heading(20, FontWeight.SemiBold).copy(color = Nocturne.text),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(vertical = Space.s3)
                    .focusRequester(titleFocus)
                    .onFocusChanged { titleFocused = it.isFocused },
                decorationBox = { inner ->
                    if (state.title.isEmpty()) {
                        Text(stringResource(R.string.compose_title_hint), style = Type.heading(20, FontWeight.SemiBold), color = Nocturne.muted(0.28f))
                    }
                    inner()
                },
            )

            // An edit's body is on its way. Nothing below is real yet, and an
            // empty page under a filled-in title reads as a post that lost its
            // text rather than as one still loading.
            if (state.isLoading) {
                Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), Alignment.Center) {
                    NodelocLoader(height = 20.dp, tint = Nocturne.accent)
                }
            }

            // The body is blocks, not one string: an uploaded image is shown as
            // itself, in place, and can be replaced or removed there. It only
            // becomes `![]()` on the way to the server.
            // Consecutive pictures share one scrolling strip rather than each
            // claiming a full-width row: a post with four screenshots should not
            // be four screens tall before a word is written.
            var cursor = 0
            while (cursor < state.blocks.size) {
                val index = cursor
                val block = state.blocks[index]
                if (block is ComposerBlock.Media) {
                    var end = index
                    while (end < state.blocks.size && state.blocks[end] is ComposerBlock.Media) end++
                    MediaStrip(
                        blocks = state.blocks.subList(index, end).filterIsInstance<ComposerBlock.Media>(),
                        uploadProgress = state.uploadProgress,
                        onRemove = { offset -> viewModel.removeBlock(index + offset) },
                        onEdit = { offset -> editMedia(index + offset) },
                        onCancelUpload = viewModel::cancelUpload,
                    )
                    cursor = end
                    continue
                }
                cursor = index + 1
                when (block) {
                    // Keyed by the block's own id, so inserting a picture above
                    // does not shift this field into a different slot and take
                    // its focus and IME state with it.
                    is ComposerBlock.Text -> key(block.id) {
                        OwnedTextField(
                        external = block.value,
                        onChange = { viewModel.updateText(index, it) },
                        autocomplete = true,
                        // Same leading the reader uses for post bodies, so what is
                        // typed here looks like what it will become.
                        textStyle = Type.body(16).copy(color = Nocturne.text, lineHeight = Type.lineHeight(16, 6)),
                        // Only the last paragraph claims the empty page below it,
                        // so the caret always has somewhere to go.
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(bodyFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) focusedTextIndex = index }
                            .then(if (index == state.blocks.lastIndex) Modifier.heightIn(min = 200.dp) else Modifier),
                        decorationBox = { inner ->
                            if (block.value.isEmpty() && index == 0) {
                                Text(stringResource(R.string.compose_body_hint), style = Type.body(16), color = Nocturne.muted(0.28f))
                            }
                            inner()
                        },
                        )
                    }

                    is ComposerBlock.Markup -> MarkupBlock(
                        label = stringResource(block.labelRes),
                        onRemove = { viewModel.removeBlock(index) },
                    )

                    is ComposerBlock.Onebox -> OneboxBlock(block)

                    is ComposerBlock.Media -> Unit // handled by the strip above
                }
            }
            Spacer(Modifier.height(Space.s6))
        }

        AnimatedVisibility(
            visible = !titleFocused,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                HairLine()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.page, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s6),
                ) {
                    // The first upload decides the kind: a post carries
                    // pictures, or a clip, or a GIF. The other two stay visible
                    // but dimmed, so the rule is legible before it is hit.
                    val kind = state.mediaKind
                    ToolbarIcon(
                        Lucide.Image,
                        stringResource(R.string.compose_image),
                        enabled = kind == null || kind == ComposerBlock.MediaKind.Image,
                    ) {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    // Video was always supported, but only by picking it from
                    // behind the image button — a path nobody would guess at.
                    ToolbarIcon(
                        Lucide.Video,
                        stringResource(R.string.compose_video),
                        enabled = kind == null || kind == ComposerBlock.MediaKind.Video,
                    ) {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                    }
                    // Lucide has no GIF glyph; film is the nearest thing that
                    // still reads as "a picture that moves".
                    ToolbarIcon(
                        Lucide.Film,
                        stringResource(R.string.compose_gif),
                        enabled = kind == null || kind == ComposerBlock.MediaKind.Gif,
                    ) { gifPickerOpen = true }
                    ToolbarIcon(Lucide.Smile, stringResource(R.string.compose_emoji)) {
                        emojiPickerOpen = true
                    }
                    ToolbarIcon(Lucide.ChartNoAxesColumn, stringResource(R.string.poll_title)) { pollBuilderOpen = true }
                    ToolbarIcon(Lucide.Mail, stringResource(R.string.compose_envelope)) {
                        envelopeSheetOpen = true
                    }
                    ToolbarIcon(Lucide.Gift, stringResource(R.string.lottery_title)) {
                        lotterySheetOpen = true
                    }
                }
            }
        }
    }

    // Only ever raised when there is a real choice: one draft of our own is
    // still restored silently, the same as before.
    if (state.draftChoices.isNotEmpty()) {
        ModalBottomSheet(
            onDismissRequest = viewModel::startFreshDraft,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Nocturne.bg,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = Space.s6)) {
                Text(
                    stringResource(R.string.compose_draft_choose),
                    style = Type.body(15, FontWeight.SemiBold),
                    color = Nocturne.text,
                    modifier = Modifier.padding(horizontal = Space.page, vertical = Space.s3),
                )
                state.draftChoices.forEach { choice ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.useDraft(choice) }
                            .padding(horizontal = Space.page, vertical = Space.s3),
                    ) {
                        Text(
                            choice.title.ifBlank { stringResource(R.string.compose_draft_untitled) },
                            style = Type.body(15),
                            color = Nocturne.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (choice.excerpt.isNotBlank()) {
                            Text(
                                choice.excerpt,
                                style = Type.body(13),
                                color = Nocturne.muted(0.45f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            stringResource(
                                if (choice.local) R.string.compose_draft_on_phone else R.string.compose_draft_on_site,
                            ),
                            style = Type.body(11),
                            color = Nocturne.muted(0.35f),
                        )
                    }
                    HairLine()
                }
                Text(
                    stringResource(R.string.compose_draft_new),
                    style = Type.body(15),
                    color = Nocturne.accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.startFreshDraft() }
                        .padding(horizontal = Space.page, vertical = Space.s4),
                )
            }
        }
    }

    if (nodePickerOpen) {
        // Full height and keyboard-aware, both for the same reason: the sheet
        // used to open half-way with a fixed-height list under the field, so
        // raising the keyboard buried every result. Typing looked like it did
        // nothing until the sheet was closed and opened again.
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val searchFocus = remember { FocusRequester() }
        ModalBottomSheet(
            onDismissRequest = { nodePickerOpen = false },
            sheetState = sheetState,
            containerColor = Nocturne.bg,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .imePadding(),
            ) {
                Box(Modifier.padding(horizontal = Space.page, vertical = Space.s3)) {
                    FieldSurface {
                        OwnedTextField(
                            external = state.nodeQuery,
                            onChange = viewModel::updateNodeQuery,
                            singleLine = true,
                            textStyle = Type.body(15).copy(color = Nocturne.text),
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                            decorationBox = { inner ->
                                if (state.nodeQuery.isEmpty()) {
                                    Text(stringResource(R.string.compose_search_node), style = Type.body(15), color = Nocturne.muted(0.3f))
                                }
                                inner()
                            },
                        )
                    }
                }
                val nodes = viewModel.filteredNodes()
                if (nodes.isEmpty()) {
                    Text(
                        stringResource(R.string.compose_node_none),
                        style = Type.body(14),
                        color = Nocturne.muted(0.45f),
                        modifier = Modifier.padding(horizontal = Space.page, vertical = 24.dp),
                    )
                } else {
                    LazyColumn(
                        Modifier.weight(1f, fill = false),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(nodes, key = { it.id }) { node ->
                            NodePickerRow(node) {
                                viewModel.selectNode(node)
                                nodePickerOpen = false
                            }
                        }
                    }
                }
            }
        }
        // A hundred and seventy nodes are not browsed; the caret belongs in the
        // search box the moment the sheet is up.
        LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
    }

    if (emojiPickerOpen) {
        EmojiPickerSheet(
            onPick = { viewModel.appendEmoji(focusedTextIndex, it) },
            onDismiss = { emojiPickerOpen = false },
        )
    }

    if (gifPickerOpen) {
        GifPickerSheet(
            search = viewModel::searchGifs,
            onDismiss = { gifPickerOpen = false },
            onPick = { url ->
                viewModel.appendGif(url)
                gifPickerOpen = false
            },
        )
    }

    if (pollBuilderOpen) {
        PollBuilderSheet(
            onDismiss = { pollBuilderOpen = false },
            onInsert = { options, multiple ->
                viewModel.insertPoll(options, multiple)
                pollBuilderOpen = false
            },
        )
    }

    if (envelopeSheetOpen) {
        RedEnvelopeSheet(
            initial = state.redEnvelope ?: RedEnvelopeDraft(),
            onDismiss = { envelopeSheetOpen = false },
            onConfirm = {
                viewModel.setRedEnvelope(it)
                envelopeSheetOpen = false
            },
        )
    }

    if (lotterySheetOpen) {
        LotterySheet(
            initial = state.lottery ?: LotteryDraft(),
            onDismiss = { lotterySheetOpen = false },
            onConfirm = {
                viewModel.setLottery(it)
                lotterySheetOpen = false
            },
        )
    }

    if (decodingImage) {
        Box(
            Modifier.fillMaxSize().background(Nocturne.bg.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            NodelocLoader(height = 44.dp)
        }
    }

    editingBitmap?.let { bitmap ->
        ImageEditorDialog(
            source = bitmap,
            onCancel = { editingBitmap = null; editingMediaIndex = null },
            onDone = { edited ->
                editingBitmap = null
                val replacing = editingMediaIndex
                editingMediaIndex = null
                viewModel.uploadEdited(edited, replacing)
            },
        )
    }

    trimmingVideo?.let { uri ->
        VideoEditorDialog(
            source = uri,
            sends = false,
            initial = editingVideoEdit,
            onCancel = {
                trimmingVideo = null
                editingVideoIndex = null
                editingVideoEdit = null
            },
            onDone = { result ->
                trimmingVideo = null
                val replacing = editingVideoIndex
                editingVideoIndex = null
                editingVideoEdit = null
                viewModel.uploadVideo(result, source = uri, replacing = replacing)
            },
        )
    }
}

@Composable
private fun ToolbarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Icon(
        icon,
        label,
        tint = Nocturne.muted(if (enabled) 0.55f else 0.22f),
        // A dimmed icon that does nothing on tap reads as a broken button; the
        // toast is the only place the rule can be said.
        modifier = Modifier.size(22.dp).clickable {
            if (enabled) onClick() else ToastCenter.show(R.string.compose_media_one_kind)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PollBuilderSheet(onDismiss: () -> Unit, onInsert: (List<String>, Boolean) -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    var options by remember { mutableStateOf(listOf("", "")) }
    var multiple by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Nocturne.bg) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.page).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Text(stringResource(R.string.poll_create), style = Type.heading(18, FontWeight.SemiBold), color = Nocturne.text)
            options.forEachIndexed { index, option ->
                FieldSurface {
                    BasicTextField(
                        value = option,
                        onValueChange = { value ->
                            options = options.toMutableList().also { it[index] = value }
                        },
                        singleLine = true,
                        textStyle = Type.body(15).copy(color = Nocturne.text),
                        cursorBrush = SolidColor(Nocturne.accent),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (option.isEmpty()) {
                                Text(stringResource(R.string.poll_option_hint, index + 1), style = Type.body(15), color = Nocturne.muted(0.3f))
                            }
                            inner()
                        },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                Text(
                    stringResource(R.string.poll_add_option),
                    style = Type.body(13),
                    color = Nocturne.accent,
                    modifier = Modifier.clickable { options = options + "" },
                )
                Text(
                    stringResource(if (multiple) R.string.poll_multi_choice else R.string.poll_single_choice),
                    style = Type.body(13),
                    color = if (multiple) Nocturne.accent else Nocturne.muted(0.5f),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { multiple = !multiple }
                        .padding(horizontal = 8.dp),
                )
            }
            FilledAccentButton(
                stringResource(R.string.poll_insert),
                enabled = options.count { it.isNotBlank() } >= 2,
            ) { onInsert(options, multiple) }
        }
    }
}


/**
 * A run of uploads, as a strip.
 *
 * Sized so two and a half fit across: the half is the point — a picture cut by
 * the edge is the only honest way to say the row keeps going, and it costs no
 * scrollbar or caption to say it.
 *
 * The controls sit on each thumbnail rather than beside it: a row of buttons
 * under every picture turns a post with four screenshots into a wall of chrome.
 */
@Composable
private fun MediaStrip(
    blocks: List<ComposerBlock.Media>,
    /** Non-null only while a clip is going up; pictures are gone too fast. */
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
            blocks.forEachIndexed { offset, block ->
                MediaThumb(
                    block = block,
                    width = itemWidth,
                    uploadProgress = uploadProgress.takeIf { block.isPending },
                    onRemove = { onRemove(offset) },
                    onEdit = { onEdit(offset) },
                    onCancelUpload = onCancelUpload,
                )
            }
        }
    }
}

@Composable
private fun MediaThumb(
    block: ComposerBlock.Media,
    width: androidx.compose.ui.unit.Dp,
    uploadProgress: Float?,
    onRemove: () -> Unit,
    onEdit: () -> Unit,
    onCancelUpload: () -> Unit,
) {
    Box(
        Modifier
            .width(width)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(Radius.md))
            .background(Nocturne.surface),
    ) {
        AsyncImage(
            model = block.pendingPreview?.let { java.io.File(it) }
                ?: DiscourseConfig.absoluteUrl(block.displayUrl)
                // A clip whose poster upload failed still has the frame here.
                ?: block.posterPath?.let { java.io.File(it) },
            contentDescription = block.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (block.isPending) {
            // The picture is already here; what is unfinished is the upload, so
            // the scrim sits over it rather than replacing it.
            Box(
                Modifier.matchParentSize().background(Nocturne.bg.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                // A clip is slow enough that a spinner is a worse answer than
                // a number: it looks the same at one percent and ninety-nine.
                if (uploadProgress != null) {
                    UploadProgressRing(uploadProgress, onCancel = onCancelUpload)
                } else {
                    NodelocLoader(height = 24.dp)
                }
            }
        } else {
            Row(
                Modifier.align(Alignment.TopEnd).padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // A clip is reopened on the file it came from, so one that
                // arrived without it — from a draft, whose grant died with the
                // task that held it — has nothing to reopen.
                if (block.isReeditable) {
                    MediaAction(Lucide.Pencil, stringResource(R.string.compose_media_edit), onEdit)
                }
                MediaAction(Lucide.Trash2, stringResource(R.string.compose_media_remove), onRemove)
            }
        }
    }
}

@Composable
private fun MediaAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Nocturne.bg.copy(alpha = 0.82f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Nocturne.text, modifier = Modifier.size(13.dp))
    }
}

/** Markup with no visual form: shown by name so it can be removed on sight. */
@Composable
private fun MarkupBlock(label: String, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(Nocturne.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Icon(Lucide.ChartNoAxesColumn, null, tint = Nocturne.accent, modifier = Modifier.size(16.dp))
        Text(label, style = Type.body(14, FontWeight.Medium), color = Nocturne.text, modifier = Modifier.weight(1f))
        Icon(
            Lucide.Trash2,
            stringResource(R.string.compose_media_remove),
            tint = Nocturne.muted(0.45f),
            modifier = Modifier.size(16.dp).clickable(onClick = onRemove),
        )
    }
}

/**
 * The topic a repost carries, drawn as the card it becomes.
 *
 * Deliberately not a control: no remove, no tap. It is the repost — take it out
 * and there is nothing being carried, which is why the server puts it back.
 * Shaped after Discourse's own internal onebox, which leads with who wrote it
 * rather than with a picture.
 */
@Composable
private fun OneboxBlock(block: ComposerBlock.Onebox) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .border(1.dp, Nocturne.divider, RoundedCornerShape(Radius.md))
            .padding(Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            block.author?.let { author ->
                RemoteAvatar(block.avatarUrl, author.take(1).uppercase(), size = 20.dp)
                Text(author, style = Type.body(12, FontWeight.Medium), color = Nocturne.muted(0.55f))
            }
            block.nodeName?.let {
                Text("n/$it", style = Type.body(12), color = Nocturne.accent, maxLines = 1)
            }
        }
        Text(
            block.title,
            style = Type.body(15, FontWeight.SemiBold),
            color = Nocturne.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        block.excerpt?.let {
            Text(
                it,
                style = Type.body(13),
                color = Nocturne.muted(0.5f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The publishing target, as a pill in the header.
 *
 * It sits where a screen title would because it is the more useful thing to
 * know: which node this is going to is the one decision that blocks publishing,
 * and the button on the right already names the action.
 */
@Composable
private fun NodePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .height(FloatingHeader.touchHeight)
            .clip(CircleShape)
            .background(if (selected) Nocturne.selected else Nocturne.surface)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            label,
            style = Type.body(14, FontWeight.Medium),
            color = if (selected) Nocturne.accent else Nocturne.muted(0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            Lucide.ChevronDown,
            null,
            tint = if (selected) Nocturne.accent else Nocturne.muted(0.45f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Publish, as a pill the same height as the controls beside it.
 *
 * Not [FilledAccentButton]: that one is the full-width button every form ends
 * with, and stretching it into a header slot is what made this one look like a
 * misplaced form control.
 */
@Composable
private fun PublishPill(
    text: String,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .height(FloatingHeader.touchHeight)
            .clip(CircleShape)
            .background(Nocturne.accent.copy(alpha = if (enabled && !loading) 1f else 0.3f))
            .clickable(enabled = enabled && !loading, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            NodelocLoader(height = 18.dp, tint = Nocturne.bg)
        } else {
            Text(
                text,
                style = Type.body(15, FontWeight.SemiBold),
                color = if (Nocturne.isDark) Nocturne.neutral900 else Color.White,
            )
        }
    }
}

/**
 * One node to publish into.
 *
 * The slug leads the name because that is what the post will carry: every row
 * in the feed is stamped `n/slug`, so choosing by it is choosing what readers
 * will actually see. The description is the tiebreaker between nodes whose
 * names sound alike.
 */
@Composable
private fun NodePickerRow(node: NodeSummary, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.page, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        if (node.logoUrl != null) {
            RemoteAvatar(node.logoUrl, node.letter, size = 38.dp)
        } else {
            Avatar(node.letter, variant = node.id % 2, size = 38.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "n/${node.slug}",
                    style = Type.body(13, FontWeight.Medium),
                    color = Nocturne.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    node.name,
                    style = Type.body(14, FontWeight.Medium),
                    color = Nocturne.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            node.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = Type.body(12),
                    color = Nocturne.muted(0.45f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A circular control the same height as the pills beside it, and as flat. */
@Composable
private fun HeaderPillButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(FloatingHeader.touchHeight)
            .clip(CircleShape)
            .background(Nocturne.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Nocturne.text, modifier = Modifier.size(18.dp))
    }
}

/**
 * A text field that owns what it shows.
 *
 * Routing the value out to a ViewModel and back through a StateFlow puts a
 * frame between the keystroke and the field. A Latin keyboard survives that;
 * an IME does not — pinyin composition is stateful, and a value arriving late
 * or out of order drops or duplicates characters mid-word, which on this site
 * is most of what anyone types. The field keeps the text, and the ViewModel is
 * told afterwards.
 *
 * [external] re-seeds it when the value changes from somewhere other than
 * typing — a prefilled title, a cleared composer.
 */
@Composable
private fun OwnedTextField(
    external: String,
    onChange: (String) -> Unit,
    textStyle: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    /** Prose only. A title and a poll option are neither a mention nor a node. */
    autocomplete: Boolean = false,
    decorationBox: @Composable (@Composable () -> Unit) -> Unit = { it() },
) {
    var field by remember { mutableStateOf(TextFieldValue(external)) }
    if (external != field.text) {
        field = TextFieldValue(external, TextRange(external.length))
    }
    Column {
        // Above this paragraph rather than above the whole composer: the body
        // is a stack of fields and the suggestions belong beside the one being
        // typed in, not at the far end of the page.
        if (autocomplete) {
            AutocompleteStrip(value = field, onPick = { field = it; onChange(it.text) })
        }
        BasicTextField(
            value = field,
            onValueChange = {
                field = it
                onChange(it.text)
            },
            singleLine = singleLine,
            textStyle = textStyle,
            cursorBrush = SolidColor(Nocturne.accent),
            modifier = modifier,
            decorationBox = decorationBox,
        )
    }
}
