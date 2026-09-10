package com.nodeloc.app.feature.compose

import android.graphics.Bitmap
import com.nodeloc.app.core.util.sha1Hex
import com.nodeloc.app.core.util.runCatchingCancellable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.ToastCenter
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.time.Instant
import kotlinx.serialization.json.Json
import com.nodeloc.app.core.model.DraftPayload
import com.nodeloc.app.core.model.DraftItem
import com.nodeloc.app.core.model.DiscourseUpload
import com.nodeloc.app.core.model.KlipyGif
import com.nodeloc.app.core.model.NodeSummary
import com.nodeloc.app.core.html.plainTextFromHtml
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.store.PostEdits
import com.nodeloc.app.feature.media.ImageEditRenderer
import com.nodeloc.app.feature.node.NodeSummaryFactory
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A red envelope, staged until the topic exists. */
/**
 * A draft the composer can pick up.
 *
 * [local] is the phone's own slot; everything else came from the site and is
 * identified by its draft key.
 */
data class DraftChoice(
    val key: String,
    val local: Boolean,
    val title: String,
    val excerpt: String,
    val categoryId: Int?,
    val body: String?,
)

data class RedEnvelopeDraft(val totalPoints: Int = 100, val totalCount: Int = 10)

/** One prize tier of a staged lottery. */
data class LotteryLevelDraft(val name: String = "", val prize: String = "", val quantity: Int = 1)

/**
 * What `POST /lottery` accepts, in the shape the plugin's own composer builds.
 *
 * Defaults follow the plugin's: five participants, the site's ticket bounds,
 * the site's trust floor. They are constants rather than read from the server
 * because `site.json` carries no `site_settings` — the web reads them from the
 * page's preload store, which the app has no equivalent of.
 */
data class LotteryDraft(
    val title: String = "",
    val minParticipants: Int = 5,
    /** Zero is the plugin's "no cap"; it stores 1_000_000. */
    val maxParticipants: Int = 0,
    val minTicketsPerUser: Int = LOTTERY_MIN_TICKETS,
    val maxTicketsPerUser: Int = LOTTERY_MAX_TICKETS,
    val minTrustLevel: Int = LOTTERY_MIN_TRUST_LEVEL,
    /** Turned into an absolute `draw_at` at submit time. */
    val drawDays: Int = 3,
    val levels: List<LotteryLevelDraft> = listOf(LotteryLevelDraft()),
) {
    /**
     * The same conditions `LotteryController` re-checks, so the sheet refuses
     * what the server would refuse. It cannot check the median-participants
     * cap — that one is per-user and only the server knows it.
     */
    val error: LotteryDraftError?
        get() = when {
            title.isBlank() -> LotteryDraftError.Title
            levels.none { it.prize.isNotBlank() } -> LotteryDraftError.Prize
            minParticipants < 1 -> LotteryDraftError.MinParticipants
            maxParticipants in 1..minParticipants -> LotteryDraftError.MaxBelowMin
            minTicketsPerUser > maxTicketsPerUser -> LotteryDraftError.MinTicketsAboveMax
            drawDays !in 1..LOTTERY_MAX_DRAW_DAYS -> LotteryDraftError.DrawDays
            else -> null
        }
}

enum class LotteryDraftError { Title, Prize, MinParticipants, MaxBelowMin, MinTicketsAboveMax, DrawDays }

const val LOTTERY_MIN_TICKETS = 1
const val LOTTERY_MAX_TICKETS = 10
const val LOTTERY_MIN_TRUST_LEVEL = 1
const val LOTTERY_MAX_DRAW_DAYS = 30

/** A sentence or two of the topic being carried, which is what a card shows. */
private const val REPOST_EXCERPT_LENGTH = 140

data class ComposeState(
    val title: String = "",
    val blocks: List<ComposerBlock> = listOf(ComposerBlock.Text("")),
    val node: NodeSummary? = null,
    val nodeQuery: String = "",
    val nodeOptions: List<NodeSummary> = emptyList(),
    val uploads: List<DiscourseUpload> = emptyList(),
    val isUploading: Boolean = false,
    /** How far the current video upload has got, or null when none is. */
    val uploadProgress: Float? = null,
    val isSubmitting: Boolean = false,
    /** An edit is still fetching the post it is about to replace. */
    val isLoading: Boolean = false,
    /**
     * Drafts worth offering when the composer opens: the one on this phone
     * plus whatever the site is holding. Empty means there was nothing to
     * choose between and the composer just opened.
     */
    val draftChoices: List<DraftChoice> = emptyList(),
    val redEnvelope: RedEnvelopeDraft? = null,
    val lottery: LotteryDraft? = null,
    val error: String? = null,
    /** Addressed to a person rather than a node; see [Route.Compose]. */
    val pmRecipient: String? = null,
) {
    /** What actually goes over the wire; the blocks are only how it is edited. */
    val body: String get() = blocks.toMarkdown()

    /**
     * The one kind of media this post carries. Discourse renders a mixed post
     * badly enough that the site treats a post as one kind or the other, so the
     * composer offers whichever kind was picked first and no other.
     */
    val mediaKind: ComposerBlock.MediaKind? get() = blocks.mediaKind()

    /** True while any picture is still on its way to the server. */
    val hasPendingMedia: Boolean
        get() = blocks.any { it is ComposerBlock.Media && it.isPending }

    /**
     * Saving an edit asks less than publishing: there is no node to pick — the
     * topic is already in one — and nothing to wait for but the fetch of what
     * is being replaced.
     */
    val canSaveEdit: Boolean
        get() = title.trim().length >= 5 &&
            body.isNotEmpty() &&
            !hasPendingMedia &&
            !isLoading &&
            !isSubmitting

    val canSubmit: Boolean
        get() = title.trim().length >= 5 &&
            body.isNotEmpty() &&
            (node != null || pmRecipient != null) &&
            // Publishing now would drop the picture: a pending block has no
            // markdown yet, so it would simply not be in the post.
            !hasPendingMedia &&
            !isSubmitting
}

/**
 * The composer.
 *
 * Red envelopes and lotteries are **second requests** after the topic is
 * created: the plugins have no markdown form, and the web client posts them
 * from an `afterCreate` hook once the id exists.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class ComposeViewModel : ViewModel() {
    private val services = ServiceLocator.get
    private val client = services.client
    private val site = services.siteRepository
    private val prefs = services.preferences

    /** Long enough that a sentence is one write, short enough to survive a kill. */
    private val draftSaveDelayMs = 800L

    private val _state = MutableStateFlow(ComposeState())
    val state: StateFlow<ComposeState> = _state.asStateFlow()

    private var bound = false

    /**
     * Whether this composer is the one that owns the draft slot.
     *
     * A private message and a repost both arrive with their own subject already
     * decided, and neither is the thing somebody was half-way through writing —
     * restoring a draft into them, or saving over one from them, would lose the
     * draft that mattered.
     */
    private var draftable = false

    /** Set once the stored draft has been read, so autosave cannot pre-empt it. */
    private var draftRestored = false

    init {
        // Autosave rather than a save button: the composer is lost to a phone
        // call or a notification tap, not to a decision, and nobody presses
        // save on the way out of an app they did not mean to leave.
        viewModelScope.launch {
            _state
                .map { ComposerDraft.of(it.title, it.node?.id, it.blocks) }
                .distinctUntilChanged()
                .debounce(draftSaveDelayMs)
                .collect { draft ->
                    if (!draftable || !draftRestored) return@collect
                    runCatchingCancellable {
                        if (draft.isEmpty) prefs.setComposerDraft(null)
                        else prefs.setComposerDraft(ComposerDraft.encode(draft))
                    }
                }
        }
    }

    fun bind(
        categoryId: Int?,
        prefillTitle: String?,
        pmRecipient: String? = null,
        /** The topic being carried over; laid into the body as its own card. */
        repostTopicId: Int? = null,
        /** An existing opening post to load and save over; see [Route.Compose]. */
        editPostId: Int? = null,
    ) {
        // Once. A rotation re-ran this and reset the node to whatever the route
        // said — null, when composing from the feed — and put the prefilled
        // title back over one the user had already edited.
        if (bound) return
        bound = true
        draftable = pmRecipient == null && prefillTitle.isNullOrEmpty() && editPostId == null
        // The body arrives from the server. Showing an empty one meanwhile
        // invites somebody to start typing into what is about to be replaced.
        if (editPostId != null) _state.value = _state.value.copy(isLoading = true)
        if (repostTopicId != null) {
            // The card goes under the caret: what you have to say goes above
            // the thing you are passing on, which is the way round everybody
            // already knows from quoting elsewhere. An empty paragraph under it
            // gives the caret somewhere to go — the card is not a field, so
            // without it the space below the card belongs to nothing.
            //
            // The paragraph already there is kept rather than replaced, id and
            // all. The screen asks for the caret as soon as it is composed,
            // which is before this runs; a fresh block would be a different
            // key, disposing the field that had just taken focus and leaving
            // the composer with none.
            val first = _state.value.blocks.firstOrNull() as? ComposerBlock.Text
                ?: ComposerBlock.Text("")
            _state.value = _state.value.copy(
                blocks = listOf(
                    first,
                    ComposerBlock.Onebox(
                        url = "${DiscourseConfig.BASE_URL}/t/$repostTopicId",
                        title = prefillTitle.orEmpty(),
                    ),
                    ComposerBlock.Text(""),
                ),
            )
        }
        viewModelScope.launch {
            site.siteResponse()
            val nodes = site.categories().values
                .filter { it.parentCategoryId != null }
                .map(NodeSummaryFactory::summary)
            val draft = if (draftable) {
                runCatchingCancellable { ComposerDraft.decode(prefs.composerDraft()) }.getOrNull()
            } else {
                null
            }
            // Only into a composer still untouched. This lands after a network
            // call, and words typed while site.json was in flight are newer
            // than the draft — restoring over them is the loss this was meant
            // to prevent.
            val untouched = _state.value.title.isEmpty() &&
                _state.value.blocks.all { block -> block.isBlank() }
            // Drafts the site is holding, which the phone knew nothing about.
            // Only for a plain new topic: a reply, an edit or a repost is
            // about one specific thing and has no business offering others.
            val remote = if (draftable) {
                runCatchingCancellable { client.drafts() }.getOrNull()
                    ?.drafts
                    ?.filter { it.draftKey == NEW_TOPIC_DRAFT_KEY }
                    ?.mapNotNull { it.toChoice() }
                    .orEmpty()
            } else {
                emptyList()
            }
            val localChoice = draft
                ?.takeIf { untouched && !it.isEmpty }
                ?.let {
                    DraftChoice(
                        key = LOCAL_DRAFT_KEY,
                        local = true,
                        title = it.title,
                        excerpt = it.toBlocks().toMarkdown().trim().take(DRAFT_EXCERPT),
                        categoryId = it.categoryId,
                        body = null,
                    )
                }
            val choices = listOfNotNull(localChoice) + remote
            // One candidate and it is ours: restoring it silently is what the
            // slot was for. More than that, or one that came from elsewhere,
            // and the composer has to ask rather than guess.
            val askFirst = choices.size > 1 || remote.isNotEmpty()
            val restore = if (askFirst) null else draft?.takeIf { untouched && !it.isEmpty }
            _state.value = _state.value.copy(
                pmRecipient = pmRecipient,
                nodeOptions = nodes,
                draftChoices = if (askFirst) choices else emptyList(),
                // The route's node wins over the draft's: opening the composer
                // from inside a node is a statement about where this post goes.
                node = _state.value.node
                    ?: categoryId?.let { id -> nodes.firstOrNull { it.id == id } }
                    ?: restore?.categoryId?.let { id -> nodes.firstOrNull { it.id == id } },
                title = _state.value.title.ifEmpty { restore?.title ?: prefillTitle.orEmpty() },
                blocks = restore?.toBlocks() ?: _state.value.blocks,
            )
            draftRestored = true
            if (restore != null) ToastCenter.show(R.string.compose_draft_restored)
            if (repostTopicId != null) fillRepostCard(repostTopicId)
            if (editPostId != null) loadForEdit(editPostId)
        }
    }

    /** Dismisses the picker and leaves the composer empty. */
    fun startFreshDraft() {
        _state.value = _state.value.copy(draftChoices = emptyList())
    }

    /** Loads one of the offered drafts and closes the picker. */
    fun useDraft(choice: DraftChoice) {
        viewModelScope.launch {
            val node = choice.categoryId?.let { id -> _state.value.nodeOptions.firstOrNull { it.id == id } }
            val blocks = if (choice.local) {
                runCatchingCancellable { ComposerDraft.decode(prefs.composerDraft()) }
                    .getOrNull()?.toBlocks()
            } else {
                choice.body?.toComposerBlocks()
            }
            _state.value = _state.value.copy(
                draftChoices = emptyList(),
                title = choice.title,
                node = node ?: _state.value.node,
                blocks = blocks ?: _state.value.blocks,
            )
            ToastCenter.show(R.string.compose_draft_restored)
        }
    }

    /**
     * Fills the composer with what the post already says.
     *
     * The *source*, not what the reader is showing: the thread carries `cooked`
     * HTML, and handing that to an editor would rewrite everyone's markdown
     * into tag soup the first time anybody saved.
     */
    private suspend fun loadForEdit(postId: Int) {
        val raw = runCatchingCancellable { client.postDetail(postId).raw }
            .onFailure { ToastCenter.showError(it) }
            .getOrNull()
        if (raw == null) {
            // Left empty rather than closed. The screen is already open, the
            // toast has said why, and an editor that vanishes on a flaky
            // network is worse than one that waits.
            _state.value = _state.value.copy(isLoading = false)
            return
        }
        _state.value = _state.value.copy(blocks = raw.toComposerBlocks(), isLoading = false)
    }

    /**
     * Saves an edit.
     *
     * No optimistic write and no local patching: the server may refuse — past
     * the edit window it will — and it is the only thing that knows which of a
     * dozen rules said no. On success the reader is told to reload, because it
     * is still holding the version this replaced.
     */
    fun saveEdit(postId: Int, onDone: () -> Unit) {
        val current = _state.value
        val body = current.body.trim()
        if (body.isEmpty()) return
        viewModelScope.launch {
            _state.value = current.copy(isSubmitting = true)
            runCatchingCancellable {
                client.updatePost(postId, body, title = current.title.trim().takeIf { it.isNotEmpty() })
            }
                .onSuccess {
                    ToastCenter.show(R.string.reader_edit_saved)
                    PostEdits.mark(postId)
                    onDone()
                }
                .onFailure {
                    _state.value = _state.value.copy(isSubmitting = false)
                    ToastCenter.showError(it)
                }
        }
    }

    /**
     * Fetches what the card cannot be built from a route: the node it came
     * from, a first picture, a sentence of what it says.
     *
     * A failure is left alone rather than reported. The card already shows the
     * title and already posts correctly; a toast about a preview would be
     * complaining about the decoration.
     */
    private suspend fun fillRepostCard(topicId: Int) {
        val topic = runCatchingCancellable { client.topic(topicId) }.getOrNull() ?: return
        val firstPost = topic.postStream.posts.firstOrNull()
        val nodeName = topic.categoryId?.let { id -> site.categories()[id]?.name }
        val excerpt = plainTextFromHtml(firstPost?.cooked).take(REPOST_EXCERPT_LENGTH)
        _state.value = _state.value.copy(
            blocks = _state.value.blocks.map { block ->
                if (block !is ComposerBlock.Onebox) block
                else block.copy(
                    title = topic.title.ifBlank { block.title },
                    excerpt = excerpt.ifBlank { null },
                    nodeName = nodeName,
                    author = firstPost?.username?.takeIf { it.isNotBlank() },
                    avatarUrl = DiscourseConfig.avatarUrl(firstPost?.avatarTemplate, 90),
                )
            },
        )
    }

    /**
     * Forgets the draft. Called when the post lands and when the writer says to
     * discard it — the two moments the words stop being wanted.
     */
    fun clearDraft() {
        draftable = false
        services.appScope.launch { runCatching { prefs.setComposerDraft(null) } }
    }

    fun updateTitle(value: String) {
        _state.value = _state.value.copy(title = value)
    }

    fun updateText(index: Int, value: String) {
        val blocks = _state.value.blocks.toMutableList()
        val existing = blocks.getOrNull(index) as? ComposerBlock.Text ?: return
        // copy, so the block keeps its id: a fresh one per keystroke would make
        // the list key change on every character, which is the opposite of the
        // point of having one.
        blocks[index] = existing.copy(value = value)
        _state.value = _state.value.copy(blocks = blocks)
    }

    /**
     * An emoji joins the paragraph last written in, at its end.
     *
     * Not at the caret: the fields own their own [TextFieldValue] and only the
     * text comes back here, so there is no caret to consult. Picking one mid
     * sentence is rare enough that the end of the paragraph is the right guess,
     * and it is where the caret already was in the ordinary case.
     */
    fun appendEmoji(preferredIndex: Int, shortcode: String) {
        val blocks = _state.value.blocks
        val target = preferredIndex.takeIf { blocks.getOrNull(it) is ComposerBlock.Text }
            ?: blocks.indexOfLast { it is ComposerBlock.Text }
        if (target < 0) return
        val current = (blocks[target] as ComposerBlock.Text).value
        val lead = if (current.isEmpty() || current.last().isWhitespace()) "" else " "
        updateText(target, "$current$lead:$shortcode: ")
    }

    /**
     * Media lands as its own block, followed by an empty paragraph — writing
     * continues below the picture rather than stopping at it.
     */
    /**
     * Put [block] where the one at [index] was.
     *
     * Re-editing has to keep the clip where the writer put it. Removing and
     * appending would move it to the end of the post, which is a surprise
     * nobody asked for by tapping edit.
     */
    private fun swapBlock(index: Int, block: ComposerBlock) {
        val blocks = _state.value.blocks.toMutableList()
        if (index !in blocks.indices) return
        blocks[index] = block
        _state.value = _state.value.copy(blocks = blocks)
    }

    private fun appendBlock(block: ComposerBlock) {
        val blocks = _state.value.blocks.toMutableList()
        // The opening paragraph survives even when empty — it carries the body
        // placeholder, and a picture arriving should not swallow the invitation
        // to write. Only a *later* untouched paragraph is the spot the media
        // was meant for.
        if (blocks.size > 1 && blocks.lastOrNull()?.isBlank() == true) blocks.removeAt(blocks.lastIndex)
        blocks += block
        blocks += ComposerBlock.Text("")
        _state.value = _state.value.copy(blocks = blocks)
    }

    /** Removing media closes the gap: two adjacent paragraphs become one. */
    fun removeBlock(index: Int) {
        val blocks = _state.value.blocks.toMutableList()
        if (index !in blocks.indices) return
        blocks.removeAt(index)
        val before = blocks.getOrNull(index - 1)
        val after = blocks.getOrNull(index)
        if (before is ComposerBlock.Text && after is ComposerBlock.Text) {
            // The surviving block keeps the earlier one's identity, so the
            // field the cursor is in is not torn down and rebuilt.
            blocks[index - 1] = before.copy(
                value = listOf(before.value, after.value).filter { it.isNotBlank() }.joinToString("\n\n"),
            )
            blocks.removeAt(index)
        }
        _state.value = _state.value.copy(
            blocks = blocks.ifEmpty { listOf(ComposerBlock.Text("")) },
        )
    }

    /** Fetches an already-uploaded image back so it can be edited again. */
    suspend fun loadBitmap(url: String): android.graphics.Bitmap? = runCatchingCancellable {
        val bytes = client.fetchBytes(url)
        withContext(Dispatchers.Default) {
            // Downscaled here and not at the call site: scaling a full-size
            // bitmap costs about what decoding it does, and the only caller is
            // a LaunchedEffect, which resumes on the main thread.
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?.let { ImageEditRenderer.downscale(it) }
        }
    }.getOrNull()

    /** Uploads the edited copy and swaps it in where the original was. */
    fun replaceUpload(index: Int, bytes: ByteArray, fileName: String, mimeType: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isUploading = true)
            runCatchingCancellable { client.uploadComposerMedia(bytes, fileName, mimeType) }
                .onSuccess { upload ->
                    replaceMedia(index, upload)
                }
                .onFailure { ToastCenter.showError(it) }
            _state.value = _state.value.copy(isUploading = false)
        }
    }

    /** A GIF is already hosted; there is nothing to upload. */
    fun appendGif(url: String) {
        if (!accepts(ComposerBlock.MediaKind.Gif)) return
        appendBlock(
            ComposerBlock.Media(
                url = url,
                displayUrl = url,
                name = "gif",
                kind = ComposerBlock.MediaKind.Gif,
            ),
        )
    }

    /** Swaps an edited image in where the old one was. */
    fun replaceMedia(index: Int, upload: DiscourseUpload) {
        val markdownUrl = upload.composerUrl ?: return
        val blocks = _state.value.blocks.toMutableList()
        val existing = blocks.getOrNull(index) as? ComposerBlock.Media ?: return
        blocks[index] = existing.copy(
            url = markdownUrl,
            displayUrl = upload.url ?: markdownUrl,
            name = upload.displayFilename,
        )
        _state.value = _state.value.copy(blocks = blocks)
    }

    /** Picking also clears the search, so the picker reopens on the whole list. */
    fun selectNode(node: NodeSummary) {
        _state.value = _state.value.copy(node = node, nodeQuery = "")
    }

    fun updateNodeQuery(value: String) {
        _state.value = _state.value.copy(nodeQuery = value)
    }

    /**
     * The slug counts as much as the name, and nothing is cut off the end.
     *
     * Most nodes here are named in Chinese and slugged in English, and the row
     * leads with `n/slug` — matching the name alone meant typing what was on
     * screen found nothing. The unsearched list used to stop at forty of a
     * hundred and seventy-odd, which put the rest behind a search that could
     * not reach them.
     */
    fun filteredNodes(): List<NodeSummary> {
        val query = _state.value.nodeQuery.trim()
        val all = _state.value.nodeOptions
        if (query.isEmpty()) return all
        return all.filter {
            it.name.contains(query, ignoreCase = true) || it.slug.contains(query, ignoreCase = true)
        }
    }



    /**
     * The picture goes on the page first and uploads underneath it.
     *
     * Waiting for the server before showing anything leaves the composer blank
     * for as long as the network takes, with nothing to say why — and the bytes
     * are already in hand, so there is nothing to wait for.
     */
    fun upload(bytes: ByteArray, fileName: String, mimeType: String) {
        if (!accepts(ComposerBlock.MediaKind.Image)) return
        viewModelScope.launch {
            val preview = cachePreview(bytes) ?: return@launch
            val slot = ComposerBlock.Media(url = "", displayUrl = "", name = fileName, pendingPreview = preview)
            appendBlock(slot)
            _state.value = _state.value.copy(isUploading = true)
            runCatchingCancellable { client.uploadComposerMedia(bytes, fileName, mimeType) }
                .onSuccess { upload ->
                    _state.value = _state.value.copy(uploads = _state.value.uploads + upload)
                    if (upload.composerUrl == null) removePending(slot) else resolvePending(slot, upload)
                }
                .onFailure {
                    // The slot came from an action that failed; leaving it would
                    // promise a picture the post does not have.
                    removePending(slot)
                    ToastCenter.showError(it)
                }
            _state.value = _state.value.copy(isUploading = false)
        }
    }

    /**
     * The editor returns a Bitmap, but a 2048-edge JPEG encode is a couple of
     * hundred milliseconds — and the editor dismisses on the same frame, so on
     * the caller's thread it stalls the animation. Held on viewModelScope
     * rather than the composable's: leaving the composer mid-encode must not
     * drop an upload the user already confirmed.
     */
    fun uploadEdited(bitmap: Bitmap, replacing: Int?) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.Default) { ImageEditRenderer.encode(bitmap) }
            if (replacing == null) {
                upload(bytes, "upload.jpg", "image/jpeg")
            } else {
                replaceUpload(replacing, bytes, "upload.jpg", "image/jpeg")
            }
        }
    }

    /** Local copy for the preview; the editor already handed us the bytes. */
    private suspend fun cachePreview(bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val dir = java.io.File(services.appContext.cacheDir, "composer").apply { mkdirs() }
            java.io.File(dir, "preview-${System.nanoTime()}.jpg").also { it.writeBytes(bytes) }.absolutePath
        }.getOrNull()
    }

    /** [displayUrl] overrides what the thumbnail is fetched from — a video's poster. */
    private fun resolvePending(
        slot: ComposerBlock.Media,
        upload: DiscourseUpload,
        displayUrl: String? = null,
    ) {
        val markdownUrl = upload.composerUrl ?: return
        val blocks = _state.value.blocks.toMutableList()
        val index = blocks.indexOf(slot)
        if (index < 0) return
        blocks[index] = slot.copy(
            url = markdownUrl,
            // `upload://` is for the server, not for us: the picture is fetched
            // from the real path or it does not appear at all.
            displayUrl = displayUrl ?: upload.url ?: markdownUrl,
            name = upload.displayFilename,
            pendingPreview = null,
        )
        _state.value = _state.value.copy(blocks = blocks)
    }

    private fun removePending(slot: ComposerBlock.Media) {
        val index = _state.value.blocks.indexOf(slot)
        if (index >= 0) removeBlock(index)
    }

    /**
     * Video upload is two calls: the file, then a poster frame **named after
     * the video's SHA1**. Discourse links the two purely by filename
     * (`original_filename LIKE '<sha1>.%'`) — there is no markdown for it.
     */
    /**
     * Stop the video going up, and take the half-uploaded block with it.
     *
     * Cancelling the job unwinds everything the upload owns: the pending block
     * is removed by the same `onFailure` a network error would have taken, and
     * the temp file is deleted by the `finally` below it.
     */
    fun cancelUpload() {
        videoUpload?.cancel()
        videoUpload = null
    }

    private var videoUpload: kotlinx.coroutines.Job? = null

    fun uploadVideo(
        media: com.nodeloc.app.feature.media.TrimmedMedia,
        source: android.net.Uri? = null,
        /** The block this replaces, when the editor was opened on one. */
        replacing: Int? = null,
    ) {
        val kind =
            if (media.mimeType == "image/gif") ComposerBlock.MediaKind.Gif
            else ComposerBlock.MediaKind.Video
        // Re-editing swaps a block for one of the same kind, so the
        // one-kind-per-post rule has already been satisfied by the first.
        if (replacing == null && !accepts(kind)) return
        videoUpload = viewModelScope.launch {
            // A clip has no frame of its own to show: the poster is a separate
            // file that only exists locally until the second upload lands, so
            // it goes on the page as the pending preview and stays as the
            // fallback after.
            // A GIF is its own preview and is already a file on disk, so it
            // needs no second copy; a clip has a still that does.
            val poster = if (kind == ComposerBlock.MediaKind.Gif) {
                media.file.absolutePath
            } else {
                media.posterBytes?.let { cachePreview(it) }
            }
            if (poster == null) {
                // Without a frame there is nothing to show for the clip — not
                // while it uploads and not after, since the poster is also what
                // the server gets. Attaching it would leave a blank square.
                ToastCenter.show(R.string.error_action_failed)
                return@launch
            }
            val slot = ComposerBlock.Media(
                url = "",
                displayUrl = "",
                name = media.fileName,
                kind = kind,
                pendingPreview = poster,
                posterPath = poster,
                sourceUri = source?.toString(),
                videoEdit = media.edit,
            )
            if (replacing == null) appendBlock(slot) else swapBlock(replacing, slot)
            _state.value = _state.value.copy(isUploading = true, uploadProgress = 0f)
            runCatchingCancellable {
                client.uploadMediaFile(media.file, media.fileName, media.mimeType) { sent ->
                    _state.value = _state.value.copy(uploadProgress = sent)
                }
            }
                .onSuccess { upload ->
                    _state.value = _state.value.copy(uploads = _state.value.uploads + upload)
                    if (upload.composerUrl == null) {
                        removePending(slot)
                    } else {
                        // The poster is fetched before the block resolves, because
                        // its URL is what the thumbnail becomes — an mp4 address
                        // renders as an empty frame.
                        val posterUrl = media.posterBytes?.let { bytes ->
                            // Digested off the file, which is what was sent.
                            val digest = withContext(Dispatchers.IO) { sha1Hex(media.file) }
                            runCatchingCancellable { client.uploadVideoPoster(bytes, digest) }
                                .getOrNull()?.url
                        }
                        resolvePending(
                            slot,
                            upload,
                            displayUrl = if (kind == ComposerBlock.MediaKind.Video) posterUrl.orEmpty() else null,
                        )
                    }
                }
                .onFailure {
                    removePending(slot)
                    ToastCenter.showError(it)
                }
            _state.value = _state.value.copy(isUploading = false, uploadProgress = null)
            // The trimmer left it in the cache for us; it has been read now.
            // A GIF's file is still the on-screen preview, so that one stays.
            if (kind != ComposerBlock.MediaKind.Gif) {
                withContext(Dispatchers.IO) { runCatching { media.file.delete() } }
            }
        }
    }

    /**
     * True when [kind] may still be added. The first upload decides the kind;
     * the screen dims the other two, so reaching here with a mismatch means a
     * race, not a tap.
     */
    private fun accepts(kind: ComposerBlock.MediaKind): Boolean =
        _state.value.mediaKind.let { it == null || it == kind }

    private fun sha1(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** GIF search has no draft behind it, so the sheet keeps its own results. */
    suspend fun searchGifs(query: String): List<KlipyGif> =
        runCatchingCancellable { client.klipySearch(query) }.getOrNull()?.results.orEmpty()

    fun setRedEnvelope(draft: RedEnvelopeDraft?) {
        _state.value = _state.value.copy(redEnvelope = draft)
    }

    fun setLottery(draft: LotteryDraft?) {
        _state.value = _state.value.copy(lottery = draft)
    }

    /** Inserts a poll in Discourse's own `[poll]` markup. */
    fun insertPoll(options: List<String>, multiple: Boolean) {
        // Named, and numbered by how many are already in the body. Discourse
        // rejects a post carrying two unnamed polls, so the second one anybody
        // added made publishing fail with nothing pointing at why.
        val name = nextPollName(
            _state.value.blocks.filterIsInstance<ComposerBlock.Markup>().map { it.markdown },
        )
        val body = buildString {
            append(if (multiple) "[poll type=multiple public=true$name]\n" else "[poll type=regular public=true$name]\n")
            options.filter { it.isNotBlank() }.forEach { append("* $it\n") }
            append("[/poll]")
        }
        appendBlock(ComposerBlock.Markup(body, R.string.poll_title))
    }

    fun submit(onCreated: (topicId: Int) -> Unit) {
        val current = _state.value
        val recipient = current.pmRecipient
        val node = current.node
        if (node == null && recipient == null) return
        viewModelScope.launch {
            _state.value = current.copy(isSubmitting = true, error = null)
            runCatchingCancellable {
                if (recipient != null) {
                    client.createPrivateMessage(current.title.trim(), current.body.trim(), recipient)
                } else {
                    client.createTopic(current.title.trim(), current.body.trim(), node!!.id)
                }
            }
                .onSuccess { created ->
                    val topicId = created.topicId
                    val postId = created.id
                    // Plugin widgets can only be attached once the ids exist.
                    current.redEnvelope?.let { envelope ->
                        if (topicId != null) {
                            runCatchingCancellable {
                                client.createRedEnvelope(topicId, envelope.totalPoints, envelope.totalCount)
                            }.onFailure { ToastCenter.show(R.string.envelope_create_failed) }
                        }
                    }
                    current.lottery?.let { lottery ->
                        if (postId != null) {
                            runCatchingCancellable { client.createLottery(postId, lotteryPayload(postId, lottery)) }
                                .onFailure { ToastCenter.show(R.string.lottery_create_failed) }
                        }
                    }
                    ToastCenter.show(R.string.compose_published)
                    clearDraft()
                    _state.value = ComposeState()
                    topicId?.let(onCreated)
                }
                .onFailure {
                    _state.value = _state.value.copy(isSubmitting = false, error = it.message)
                    ToastCenter.showError(it)
                }
        }
    }

    /** Repost — discourse-community republishes an existing topic into a node. */
    fun repost(topicId: Int, onDone: () -> Unit) {
        val current = _state.value
        val node = current.node ?: return
        viewModelScope.launch {
            _state.value = current.copy(isSubmitting = true)
            runCatchingCancellable {
                client.repost(topicId, node.id, current.title.trim(), current.blocks.toMarkdown())
            }
                .onSuccess {
                    ToastCenter.show(R.string.reader_repost_to, node.name)
                    _state.value = ComposeState()
                    onDone()
                }
                .onFailure {
                    _state.value = _state.value.copy(isSubmitting = false)
                    ToastCenter.showError(it)
                }
        }
    }

    /** The lottery controller reads a nested `levels` array, so this is JSON. */
    /**
     * `draw_at`, not `draw_days`. The server parses an absolute time —
     * `Time.parse(nil)` raises, the create is rescued into a 422, and every
     * lottery published from the app failed with the post already up. The
     * other three fields were simply never sent.
     */
    private fun lotteryPayload(postId: Int, draft: LotteryDraft): String {
        val levels = draft.levels.filter { it.prize.isNotBlank() }.joinToString(",") { level ->
            """{"name":${level.name.jsonString()},"prize":${level.prize.jsonString()},"quantity":${level.quantity}}"""
        }
        val drawAt = DateTimeFormatter.ISO_INSTANT.format(
            Instant.now().plus(draft.drawDays.toLong(), ChronoUnit.DAYS),
        )
        return """
            {"post_id":$postId,"title":${draft.title.jsonString()},
            "min_participants":${draft.minParticipants},
            "max_participants":${draft.maxParticipants},
            "min_tickets_per_user":${draft.minTicketsPerUser},
            "max_tickets_per_user":${draft.maxTicketsPerUser},
            "min_trust_level":${draft.minTrustLevel},
            "draw_at":${drawAt.jsonString()},
            "levels":[$levels]}
        """.trimIndent().replace("\n", "")
    }

    private fun String.jsonString(): String =
        "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}

private const val LOCAL_DRAFT_KEY = "__local__"
private const val NEW_TOPIC_DRAFT_KEY = "new_topic"
private const val DRAFT_EXCERPT = 120

private val draftJson = Json { ignoreUnknownKeys = true }

/**
 * A site draft as something the picker can show.
 *
 * Drops the ones with nothing in them: Discourse keeps a row the moment the
 * composer opens on the web, so an empty draft means somebody looked at the
 * editor once, not that they were writing something.
 */
private fun DraftItem.toChoice(): DraftChoice? {
    val payload = data?.let { runCatching { draftJson.decodeFromString<DraftPayload>(it) }.getOrNull() }
    val body = payload?.reply.orEmpty()
    val heading = payload?.title?.takeIf { it.isNotBlank() } ?: title.orEmpty()
    if (body.isBlank() && heading.isBlank()) return null
    return DraftChoice(
        key = draftKey,
        local = false,
        title = heading,
        excerpt = body.trim().take(DRAFT_EXCERPT),
        categoryId = payload?.categoryId ?: categoryId,
        body = body,
    )
}
