package com.nodeloc.app.feature.moderation

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.CheckCheck
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Inbox
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldAlert
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.Avatar
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FilterPill
import com.nodeloc.app.core.design.FilterPillRow
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.OnReachedEnd
import com.nodeloc.app.core.design.PillTone
import com.nodeloc.app.core.design.StatusPill
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.network.ApiAdminUserDetail
import com.nodeloc.app.core.network.ApiAppeal
import com.nodeloc.app.core.network.ApiReviewItem
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.feature.nav.Navigator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Duration
import java.time.Instant

// ------------------------------------------------------------------ shared bits

/**
 * Items the queue has already fetched, and which ones were resolved since.
 *
 * The node queue has no `item` filter, so the detail page starts from what
 * the list had rather than paging the queue again; and the list hides what
 * the detail page resolved without waiting for a reload.
 */
internal object ReviewItemStore {
    private val items = mutableMapOf<Long, ApiReviewItem>()
    private val _resolved = MutableStateFlow<Set<Long>>(emptySet())
    val resolved: StateFlow<Set<Long>> = _resolved.asStateFlow()

    fun put(list: List<ApiReviewItem>) = list.forEach { items[it.id] = it }
    fun get(id: Long): ApiReviewItem? = items[id]
    fun markResolved(id: Long) = _resolved.update { it + id }
    fun markPending(id: Long) = _resolved.update { it - id }
}

/** Site-queue kinds that are reports in the design's sense. The rest belong to other admin pages. */
private val QUEUE_REPORT_KINDS = setOf("report", "node_report", "link_report", "risk_flag")
private const val QUEUE_HELD_KIND = "pending_post"

/** The server's held-post reasons, verbatim: they are prose, not codes. */
private const val HELD_NEW_USER = "新注册账号，发帖先审核"
private const val HELD_KEYWORD = "命中屏蔽词"
private const val HELD_WATCHED = "作者正在被观察"

private enum class HeldFilter(@StringRes val label: Int, val reason: String?) {
    All(R.string.mod_review_filter_all, null),
    NewUser(R.string.mod_review_filter_new_user, HELD_NEW_USER),
    Keyword(R.string.mod_review_filter_keyword, HELD_KEYWORD),
    Watched(R.string.mod_review_filter_watched, HELD_WATCHED),
}

enum class ReviewTab { Held, Reports, Appeals }

private val STATUSES = listOf(
    "pending" to R.string.mod_review_status_pending,
    "approved" to R.string.mod_review_status_approved,
    "rejected" to R.string.mod_review_status_rejected,
    "ignored" to R.string.mod_review_status_ignored,
)

@StringRes
private fun resolutionLabel(resolution: String?): Int = when (resolution) {
    "delete_post" -> R.string.mod_review_res_deleted
    "hide_post" -> R.string.mod_review_res_hidden
    "reject" -> R.string.mod_review_res_rejected
    "ignore" -> R.string.mod_review_res_ignored
    "approve" -> R.string.mod_review_res_approved
    "restore_post" -> R.string.mod_review_res_restored
    "confirm" -> R.string.mod_review_res_confirmed
    "flag_link" -> R.string.mod_review_res_flagged
    else -> R.string.mod_review_res_other
}

@StringRes
private fun kindLabel(item: ApiReviewItem): Int = when {
    item.kind == "node_report" -> R.string.mod_review_kind_node
    item.kind == "link_report" -> R.string.mod_review_kind_link
    item.kind == "risk_flag" && !item.isPost -> R.string.mod_review_kind_account
    item.postNumber == 1 -> R.string.mod_review_kind_topic
    item.isPost -> R.string.mod_review_kind_reply
    else -> R.string.mod_review_kind_other
}

@Composable
private fun heldPill(reason: String?) {
    when (reason) {
        HELD_NEW_USER -> StatusPill(stringResource(R.string.mod_review_pill_new_user), PillTone.Info)
        HELD_KEYWORD -> StatusPill(stringResource(R.string.mod_review_pill_keyword), PillTone.Danger)
        HELD_WATCHED -> StatusPill(stringResource(R.string.mod_review_pill_watched), PillTone.Warning)
        null -> Unit
        else -> StatusPill(reason, PillTone.Warning)
    }
}

private fun ApiReviewItem.payloadString(key: String): String? = (payload?.get(key) as? JsonPrimitive)?.contentOrNull

/** What resolving this item took from the author (payload, set by the server); 0 when nothing. */
private val ApiReviewItem.reputationDeducted: Int get() = payloadString("reputation_penalty")?.toIntOrNull() ?: 0
private val ApiReviewItem.reputationRefunded: Boolean get() = payloadString("reputation_refunded") == "true"

/** What a card or the detail page shows as the body. */
private fun ApiReviewItem.bodyText(): String = when (kind) {
    "link_report" -> payloadString("url") ?: payloadString("host").orEmpty()
    "node_report" -> nodeName ?: nodeSlug.orEmpty()
    else -> DiscourseFormat.plainText(postHtml).trim()
}

// ------------------------------------------------------------------ queue

data class ReviewQueueState(
    val tab: ReviewTab = ReviewTab.Held,
    val status: String = "pending",
    val items: List<ApiReviewItem> = emptyList(),
    val appeals: List<ApiAppeal> = emptyList(),
    val nextBefore: Long? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val failed: Throwable? = null,
    val heldCount: Int? = null,
    val reportCount: Int? = null,
    val appealCount: Int? = null,
    val selected: Set<Long> = emptySet(),
    val busy: Boolean = false,
)

class ReviewQueueViewModel(private val nodeSlug: String?) : ViewModel() {
    private val api = ServiceLocator.get.moderationApi
    private val privileges get() = ServiceLocator.get.moderation.privileges.value
    private val site get() = nodeSlug == null

    private val _state = MutableStateFlow(ReviewQueueState())
    val state: StateFlow<ReviewQueueState> = _state.asStateFlow()
    private var loadJob: Job? = null

    val handlesAppeals: Boolean get() = site && privileges.handlesAppeals

    init {
        load()
    }

    fun selectTab(tab: ReviewTab) {
        if (tab == _state.value.tab) return
        _state.update { it.copy(tab = tab, selected = emptySet()) }
        // The node queue is one mixed list split on the client; only the site fetches per tab.
        if (site || tab == ReviewTab.Appeals || _state.value.items.isEmpty()) load()
    }

    fun selectStatus(status: String) {
        if (status == _state.value.status) return
        _state.update { it.copy(status = status, selected = emptySet()) }
        load()
    }

    fun load() {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, failed = null, nextBefore = null) }
        loadJob = viewModelScope.launch {
            val current = _state.value
            if (current.tab == ReviewTab.Appeals) {
                runCatchingCancellable { fetchAppeals(current.status) }
                    .onSuccess { appeals -> _state.update { it.copy(appeals = appeals, loading = false) } }
                    .onFailure { error -> _state.update { it.copy(loading = false, failed = error) } }
                return@launch
            }
            runCatchingCancellable { fetch(current.tab, current.status, null) }
                .onSuccess { page ->
                    ReviewItemStore.put(page.items)
                    _state.update {
                        it.copy(
                            items = page.items,
                            nextBefore = page.nextBefore,
                            loading = false,
                            heldCount = if (site) page.kindCounts[QUEUE_HELD_KIND] ?: it.heldCount else null,
                            // A held-only fetch may count only its own kind; keep the last report total then.
                            reportCount = if (site && QUEUE_REPORT_KINDS.any { k -> k in page.kindCounts }) QUEUE_REPORT_KINDS.sumOf { k -> page.kindCounts[k] ?: 0 } else it.reportCount,
                        )
                    }
                }
                .onFailure { error -> _state.update { it.copy(loading = false, failed = error) } }
            if (handlesAppeals && current.status == "pending") {
                runCatchingCancellable { api.appeals("pending") }
                    .onSuccess { list -> _state.update { it.copy(appealCount = list.counts["pending"] ?: list.total) } }
            }
        }
    }

    fun loadMore() {
        val current = _state.value
        val before = current.nextBefore ?: return
        if (current.loading || current.loadingMore || current.tab == ReviewTab.Appeals) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            runCatchingCancellable { fetch(current.tab, current.status, before) }
                .onSuccess { page ->
                    ReviewItemStore.put(page.items)
                    _state.update { state ->
                        val known = state.items.map { it.id }.toSet()
                        state.copy(
                            items = state.items + page.items.filter { it.id !in known },
                            nextBefore = page.nextBefore,
                            loadingMore = false,
                        )
                    }
                }
                .onFailure { error ->
                    ToastCenter.showError(error)
                    _state.update { it.copy(loadingMore = false) }
                }
        }
    }

    private suspend fun fetch(tab: ReviewTab, status: String, before: Long?) = when {
        nodeSlug != null -> api.nodeQueue(nodeSlug, status, before)
        tab == ReviewTab.Held -> api.siteQueue(status, QUEUE_HELD_KIND, before)
        // Several kinds make up "reports" and the filter takes one, so this
        // fetches unfiltered and the screen drops the kinds that are not reports.
        else -> api.siteQueue(status, null, before)
    }

    private suspend fun fetchAppeals(status: String): List<ApiAppeal> = when (status) {
        "pending" -> api.appeals("pending").appeals
        "approved" -> api.appeals("accepted").appeals
        "rejected" -> api.appeals("rejected").appeals
        else -> emptyList()
    }

    fun toggle(id: Long) = _state.update {
        it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
    }

    fun selectAll(ids: List<Long>) = _state.update { it.copy(selected = ids.toSet()) }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    /**
     * Resolves each selected item in turn; there is no batch endpoint. [action]
     * picks the server action per item, since one button means different
     * things to a held post, a reported post and a reported node.
     */
    fun resolveSelected(action: (ApiReviewItem) -> String) {
        val chosen = _state.value.items.filter { it.id in _state.value.selected }
        if (chosen.isEmpty()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            var done = 0
            var firstError: Throwable? = null
            for (item in chosen) {
                runCatchingCancellable { api.resolve(item.id, action(item), null, nodeSlug) }
                    .onSuccess {
                        done++
                        ReviewItemStore.markResolved(item.id)
                        ServiceLocator.get.moderation.noteResolved(item.nodeSlug ?: nodeSlug)
                    }
                    .onFailure { if (firstError == null) firstError = it }
            }
            _state.update { state ->
                state.copy(busy = false, selected = state.selected - ReviewItemStore.resolved.value)
            }
            firstError?.let { ToastCenter.showError(it) }
            if (done > 0) ToastCenter.show(R.string.mod_review_batch_done, done)
        }
    }

    fun resolveAppeal(appeal: ApiAppeal, accept: Boolean, decision: String) {
        viewModelScope.launch {
            runCatchingCancellable { api.resolveAppeal(appeal.id, accept, decision) }
                .onSuccess {
                    ToastCenter.show(if (accept) R.string.mod_review_appeal_accepted else R.string.mod_review_appeal_rejected)
                    _state.update { state ->
                        state.copy(
                            appeals = state.appeals.filterNot { it.id == appeal.id },
                            appealCount = state.appealCount?.minus(1)?.coerceAtLeast(0),
                        )
                    }
                    ServiceLocator.get.moderation.noteResolved(null)
                }
                .onFailure { ToastCenter.showError(it) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReviewQueueScreen(nodeSlug: String?, navigator: Navigator, initialTab: String? = null) {
    val viewModel: ReviewQueueViewModel = viewModel(key = "review-queue-$nodeSlug") { ReviewQueueViewModel(nodeSlug) }
    val state by viewModel.state.collectAsState()
    // Once, on arrival: the dashboard's count said which tab it meant.
    LaunchedEffect(initialTab) {
        ReviewTab.entries.firstOrNull { it.name == initialTab }?.let(viewModel::selectTab)
    }
    val resolved by ReviewItemStore.resolved.collectAsState()
    var filter by remember { mutableStateOf(HeldFilter.All) }
    var statusMenu by remember { mutableStateOf(false) }
    var openAppeal by remember { mutableStateOf<ApiAppeal?>(null) }
    val listState = rememberLazyListState()
    listState.OnReachedEnd { viewModel.loadMore() }

    val tabKinds: (ApiReviewItem) -> Boolean = when (state.tab) {
        ReviewTab.Held -> { item -> item.kind == QUEUE_HELD_KIND }
        else -> { item -> if (nodeSlug != null) item.kind != QUEUE_HELD_KIND else item.kind in QUEUE_REPORT_KINDS }
    }
    val visible = state.items
        .filter(tabKinds)
        .filter { state.status != "pending" || it.id !in resolved }
        .filter { state.tab != ReviewTab.Held || filter.reason == null || it.heldReason == filter.reason }
    val selecting = state.selected.isNotEmpty()
    // The node queue has no counts: the loaded list is the best number there is.
    val heldCount = state.heldCount ?: state.items.count { it.kind == QUEUE_HELD_KIND && it.id !in resolved }.takeIf { nodeSlug != null && state.status == "pending" }
    val reportCount = state.reportCount ?: state.items.count { it.kind != QUEUE_HELD_KIND && it.id !in resolved }.takeIf { nodeSlug != null && state.status == "pending" }

    val statusLabel = stringResource(STATUSES.first { it.first == state.status }.second)
    val title = when {
        selecting -> stringResource(R.string.mod_review_selected, state.selected.size)
        state.status != "pending" -> stringResource(R.string.mod_review_title_status, statusLabel)
        nodeSlug != null -> stringResource(R.string.mod_review_title_node, nodeSlug)
        else -> stringResource(R.string.mod_review_title)
    }

    ModUndoHost {
        ModScaffold(
            title = title,
            onBack = { if (selecting) viewModel.clearSelection() else navigator.back() },
            actions = {
                if (selecting) {
                    HeaderIconButton(Lucide.CheckCheck, stringResource(R.string.mod_review_select_all)) {
                        viewModel.selectAll(visible.map { it.id })
                    }
                } else {
                    Box {
                        HeaderIconButton(Lucide.History, stringResource(R.string.mod_review_status_menu)) { statusMenu = true }
                        DropdownMenu(statusMenu, onDismissRequest = { statusMenu = false }, containerColor = Nocturne.bg) {
                            STATUSES.forEach { (code, label) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(label),
                                            style = Type.body(15, if (code == state.status) FontWeight.Bold else FontWeight.Normal),
                                            color = Nocturne.text,
                                        )
                                    },
                                    onClick = {
                                        statusMenu = false
                                        viewModel.selectStatus(code)
                                    },
                                )
                            }
                        }
                    }
                }
            },
            bottomBar = if (selecting && state.tab != ReviewTab.Appeals) {
                { BatchBar(state, viewModel) }
            } else {
                null
            },
        ) {
            ReviewTabs(state, heldCount, reportCount, viewModel.handlesAppeals) {
                filter = HeldFilter.All
                viewModel.selectTab(it)
            }
            if (state.tab == ReviewTab.Held) {
                FilterPillRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                    HeldFilter.entries.forEach { option ->
                        FilterPill(stringResource(option.label), option == filter) { filter = option }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { NodelocLoader() }
                    state.failed != null && state.items.isEmpty() && state.appeals.isEmpty() -> EmptyStateView(
                        Lucide.ShieldAlert,
                        stringResource(R.string.mod_review_load_failed),
                        retryLabel = stringResource(R.string.common_retry),
                        onRetry = viewModel::load,
                    )
                    state.tab == ReviewTab.Appeals -> AppealList(state.appeals, state.status == "pending") { openAppeal = it }
                    else -> LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (visible.isEmpty() && state.nextBefore == null) {
                            item {
                                EmptyStateView(
                                    Lucide.Inbox,
                                    stringResource(if (state.status == "pending") R.string.mod_review_empty else R.string.mod_review_empty_status),
                                )
                            }
                        }
                        items(visible, key = { it.id }) { item ->
                            ReviewCard(
                                item = item,
                                selected = item.id in state.selected,
                                showNode = nodeSlug == null,
                                onClick = {
                                    if (selecting) {
                                        viewModel.toggle(item.id)
                                    } else {
                                        navigator.openReviewItem(item.id, nodeSlug)
                                    }
                                },
                                onLongClick = if (state.status == "pending") {
                                    { viewModel.toggle(item.id) }
                                } else {
                                    null
                                },
                            )
                        }
                        if (state.nextBefore != null) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    if (state.loadingMore) {
                                        NodelocLoader()
                                    } else {
                                        ModButton(stringResource(R.string.common_more), ModButtonStyle.Text, onClick = viewModel::loadMore)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    openAppeal?.let { appeal ->
        AppealSheet(appeal, onDismiss = { openAppeal = null }) { accept, decision ->
            openAppeal = null
            viewModel.resolveAppeal(appeal, accept, decision)
        }
    }
}

@Composable
private fun ReviewTabs(
    state: ReviewQueueState,
    heldCount: Int?,
    reportCount: Int?,
    showAppeals: Boolean,
    onSelect: (ReviewTab) -> Unit,
) {
    Row(Modifier.fillMaxWidth().background(Nocturne.bg)) {
        val tabs = buildList {
            add(Triple(ReviewTab.Held, R.string.mod_review_tab_held, heldCount))
            add(Triple(ReviewTab.Reports, R.string.mod_review_tab_reports, reportCount))
            if (showAppeals) add(Triple(ReviewTab.Appeals, R.string.mod_review_tab_appeals, state.appealCount))
        }
        tabs.forEach { (tab, label, count) ->
            val on = tab == state.tab
            Column(
                Modifier.weight(1f).height(48.dp).clickable { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                val text = stringResource(label) + if (count != null && count > 0) " $count" else ""
                Text(
                    text,
                    style = Type.body(14, FontWeight.Medium),
                    color = if (on) Nocturne.accent else Nocturne.textSecondary,
                    modifier = Modifier.padding(bottom = 11.dp),
                )
                Box(
                    Modifier
                        .width(44.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(if (on) Nocturne.accent else Nocturne.bg),
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.divider))
}

@Composable
private fun BatchBar(state: ReviewQueueState, viewModel: ReviewQueueViewModel) {
    val n = state.selected.size
    // Every batch decision is confirmed: it lands on n items at once.
    var pending by remember { mutableStateOf<BatchDecision?>(null) }
    Row(
        Modifier.fillMaxWidth().background(Nocturne.neutral300).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.tab == ReviewTab.Held) {
            ModButton(stringResource(R.string.mod_review_batch_reject, n), ModButtonStyle.Outlined, Modifier.weight(1f), enabled = !state.busy) {
                pending = BatchDecision.RejectHeld
            }
            ModButton(stringResource(R.string.mod_review_batch_approve, n), ModButtonStyle.Filled, Modifier.weight(1f), enabled = !state.busy) {
                pending = BatchDecision.Approve
            }
        } else {
            ModButton(stringResource(R.string.mod_review_batch_ignore, n), ModButtonStyle.Outlined, Modifier.weight(1f), enabled = !state.busy) {
                pending = BatchDecision.Ignore
            }
            ModButton(stringResource(R.string.mod_review_batch_dismiss, n), ModButtonStyle.Filled, Modifier.weight(1f), enabled = !state.busy) {
                pending = BatchDecision.Dismiss
            }
        }
    }
    pending?.let { decision ->
        DecisionDialog(
            title = stringResource(decision.titleRes, n),
            body = stringResource(decision.bodyRes),
            confirmLabel = stringResource(R.string.common_confirm),
            destructive = decision == BatchDecision.RejectHeld,
            onDismiss = { pending = null },
        ) {
            pending = null
            when (decision) {
                // Rejecting a held post is deleting it; the author is told it did not pass review.
                BatchDecision.RejectHeld -> viewModel.resolveSelected { "delete_post" }
                BatchDecision.Approve -> viewModel.resolveSelected { "approve" }
                BatchDecision.Ignore -> viewModel.resolveSelected { "ignore" }
                // `reject` (report unfounded, un-hide) exists only for posts and links; a node or account flag is ignored.
                BatchDecision.Dismiss -> viewModel.resolveSelected { item -> if (item.isPost || item.kind == "link_report") "reject" else "ignore" }
            }
        }
    }
}

private enum class BatchDecision(val titleRes: Int, val bodyRes: Int) {
    RejectHeld(R.string.mod_confirm_batch_reject, R.string.mod_confirm_batch_reject_d),
    Approve(R.string.mod_confirm_batch_approve, R.string.mod_confirm_approve_d),
    Ignore(R.string.mod_confirm_batch_ignore, R.string.mod_confirm_ignore_d),
    Dismiss(R.string.mod_confirm_batch_dismiss, R.string.mod_confirm_reject_d),
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ReviewCard(
    item: ApiReviewItem,
    selected: Boolean,
    showNode: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .shadow(1.dp, shape)
            .clip(shape)
            .background(if (selected) Nocturne.selected else Nocturne.bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KindChip(stringResource(kindLabel(item)))
            if (showNode && item.nodeSlug != null) {
                Text("n/${item.nodeSlug}", style = Type.body(13, FontWeight.SemiBold), color = Nocturne.text, maxLines = 1)
            }
            Text(
                item.targetUsername?.let { "· $it" }.orEmpty(),
                style = Type.body(13),
                color = Nocturne.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(DiscourseFormat.relative(item.createdAt), style = Type.body(13), color = Nocturne.textSecondary)
        }
        if (item.postNumber == 1 && !item.topicTitle.isNullOrBlank()) {
            Text(item.topicTitle, style = Type.body(16, FontWeight.Medium), color = Nocturne.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val body = item.bodyText()
        if (body.isNotBlank()) {
            Text(
                body,
                style = Type.body(14),
                color = if (item.postNumber == 1) Nocturne.textSecondary else Nocturne.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ItemPills(item)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemPills(item: ApiReviewItem) {
    val any = item.heldReason != null || item.reportCount > 0 || item.priority || item.postDeleted || item.postHidden ||
        item.status != "pending"
    if (!any) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (item.priority) StatusPill(stringResource(R.string.mod_review_pill_priority), PillTone.Danger)
        if (item.kind == QUEUE_HELD_KIND) heldPill(item.heldReason)
        if (item.reportCount > 0) StatusPill(stringResource(R.string.mod_review_pill_reports, item.reportCount.toInt()), PillTone.Danger)
        // The post's state and the item's resolution usually say the same thing
        // ("deleted" twice); the state pill is only worth its room when it
        // differs — a post deleted outside the queue, or restored since.
        val resolutionSaysIt = (item.postDeleted && item.resolution == "delete_post") ||
            (item.postHidden && item.resolution == "hide_post")
        if (!resolutionSaysIt || item.status == "pending") {
            if (item.postDeleted) StatusPill(stringResource(R.string.mod_review_pill_deleted), PillTone.Neutral)
            else if (item.postHidden) StatusPill(stringResource(R.string.mod_review_pill_hidden), PillTone.Warning)
        }
        if (item.status != "pending") StatusPill(stringResource(resolutionLabel(item.resolution)), PillTone.Accent)
    }
}

@Composable
private fun KindChip(text: String) {
    Box(
        Modifier
            .height(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Nocturne.neutral400)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.body(12, FontWeight.SemiBold), color = Nocturne.textSecondary, maxLines = 1)
    }
}

// ------------------------------------------------------------------ appeals

@Composable
private fun AppealList(appeals: List<ApiAppeal>, pending: Boolean, onOpen: (ApiAppeal) -> Unit) {
    if (appeals.isEmpty()) {
        EmptyStateView(Lucide.Inbox, stringResource(if (pending) R.string.mod_review_appeals_empty else R.string.mod_review_empty_status))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(appeals, key = { it.id }) { appeal ->
            ModListRow(
                title = "${appeal.username} · ${stringResource(appealKindLabel(appeal.kind))}",
                supporting = listOf(appeal.body.trim(), DiscourseFormat.relative(appeal.createdAt)).filter { it.isNotBlank() }.joinToString(" · "),
                modifier = Modifier.clickable(enabled = appeal.status == "pending") { onOpen(appeal) },
                leading = { Avatar(appeal.username, size = 40.dp) },
                trailing = if (appeal.status == "pending") {
                    { androidx.compose.material3.Icon(Lucide.ChevronRight, null, tint = Nocturne.textSecondary) }
                } else {
                    { StatusPill(stringResource(if (appeal.status == "accepted") R.string.mod_review_res_approved else R.string.mod_review_res_rejected), PillTone.Neutral) }
                },
            )
        }
    }
}

@StringRes
private fun appealKindLabel(kind: String): Int =
    if (kind == "will_improve") R.string.mod_review_appeal_will_improve else R.string.mod_review_appeal_wrong

/** An appeal is about a risk-control decision; accepting it undoes everything that decision did. */
@Composable
private fun AppealSheet(appeal: ApiAppeal, onDismiss: () -> Unit, onResolve: (accept: Boolean, decision: String) -> Unit) {
    SanctionSheet(
        stringResource(R.string.mod_review_appeal_title, appeal.username),
        onDismiss,
        subtitle = stringResource(appealKindLabel(appeal.kind)),
    ) {
        if (appeal.body.isNotBlank()) {
            Text(
                appeal.body,
                style = Type.body(15),
                color = Nocturne.text,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp),
            )
        }
        SanctionRow(
            stringResource(R.string.mod_review_appeal_false_positive),
            Lucide.CheckCheck,
            supporting = stringResource(R.string.mod_review_appeal_false_positive_hint),
            onClick = { onResolve(true, "false_positive") },
        )
        SanctionRow(
            stringResource(R.string.mod_review_appeal_lenient),
            Lucide.CheckCheck,
            supporting = stringResource(R.string.mod_review_appeal_lenient_hint),
            onClick = { onResolve(true, "lenient") },
        )
        SanctionDivider()
        SanctionRow(
            stringResource(R.string.mod_review_appeal_reject),
            Lucide.ShieldAlert,
            supporting = stringResource(R.string.mod_review_appeal_reject_hint),
            danger = true,
            onClick = { onResolve(false, "") },
        )
    }
}

// ------------------------------------------------------------------ item detail

data class ReviewItemState(
    val item: ApiReviewItem? = null,
    val author: ApiAdminUserDetail? = null,
    val loading: Boolean = true,
    val failed: Throwable? = null,
    val busy: Boolean = false,
    /** The most this reader may deduct; null when the server did not say, which hides the choice. */
    val penaltyMax: Int? = null,
)

class ReviewItemViewModel(private val itemId: Long, private val nodeSlug: String?) : ViewModel() {
    private val api = ServiceLocator.get.moderationApi
    private val privileges get() = ServiceLocator.get.moderation.privileges.value

    private val _state = MutableStateFlow(ReviewItemState(item = ReviewItemStore.get(itemId)))
    val state: StateFlow<ReviewItemState> = _state.asStateFlow()

    /** The site's queue can reopen and restore; a node moderator only has the per-post restore. */
    val siteTools: Boolean get() = nodeSlug == null && privileges.reviewsSite

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.item == null, failed = null) }
            runCatchingCancellable { fetch() }
                .onSuccess { (item, penaltyMax) ->
                    if (item != null) ReviewItemStore.put(listOf(item))
                    _state.update { it.copy(item = item ?: it.item, loading = false, penaltyMax = penaltyMax ?: it.penaltyMax) }
                    val userId = item?.targetUserId
                    if (userId != null && privileges.managesUsers && _state.value.author == null) {
                        runCatchingCancellable { api.userDetail(userId) }
                            .onSuccess { detail -> _state.update { it.copy(author = detail) } }
                    }
                }
                .onFailure { error -> _state.update { it.copy(loading = false, failed = error) } }
        }
    }

    /**
     * The node queue has no `item` filter. Its order is id-descending, so a
     * one-item page with `before = id + 1` is this item when it has that status.
     */
    private suspend fun fetch(): Pair<ApiReviewItem?, Int?> {
        if (nodeSlug == null) {
            val page = api.siteItemPage(itemId)
            return page.items.firstOrNull() to page.reputationPenaltyMax
        }
        var max: Int? = null
        for ((status, _) in STATUSES) {
            val page = api.nodeQueue(nodeSlug, status, before = itemId + 1, limit = 1)
            max = page.reputationPenaltyMax ?: max
            val hit = page.items.firstOrNull()
            if (hit?.id == itemId) return hit to max
        }
        return null to max
    }

    fun resolve(action: String, note: String?, reputationPenalty: Int = 0, onDone: () -> Unit = {}) {
        val item = _state.value.item ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatchingCancellable { api.resolve(item.id, action, note, nodeSlug, reputationPenalty) }
                .onSuccess {
                    ReviewItemStore.markResolved(item.id)
                    ServiceLocator.get.moderation.noteResolved(item.nodeSlug ?: nodeSlug)
                    _state.update { it.copy(busy = false) }
                    if (action == "delete_post" && item.isPost) offerRestore(item)
                    else ToastCenter.show(R.string.mod_review_resolved)
                    onDone()
                    load()
                }
                .onFailure { error ->
                    _state.update { it.copy(busy = false) }
                    ToastCenter.showError(error)
                }
        }
    }

    private fun offerRestore(item: ApiReviewItem) {
        val undo: suspend () -> Unit = when {
            siteTools -> { { api.restoreFromReview(item.id) } }
            else -> { { api.restorePost(item.targetId) } }
        }
        ModUndo.offer(R.string.mod_review_deleted, afterUndo = { load() }, undo = undo)
    }

    /** Gives the deducted reputation back; the verdict and the post stay. */
    fun refundReputation() = write(R.string.mod_rep_refunded) {
        api.refundReputation(itemId, nodeSlug)
    }

    fun reopen() = write(R.string.mod_review_reopened) {
        api.reopen(itemId)
        ReviewItemStore.markPending(itemId)
    }

    /** Site staff restore through the review item (it handles deleted topics); node moderators per post. */
    fun restore() = write(R.string.mod_review_restored) {
        val item = _state.value.item ?: return@write
        if (siteTools) api.restoreFromReview(item.id) else api.restorePost(item.targetId)
    }

    private fun write(@StringRes success: Int, block: suspend () -> Unit) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatchingCancellable { block() }
                .onSuccess {
                    ToastCenter.show(success)
                    load()
                }
                .onFailure { ToastCenter.showError(it) }
            _state.update { it.copy(busy = false) }
        }
    }
}

private enum class ItemSheet { None, Delete, DeleteThenPenalty, Hide, Reject, Penalty }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewItemScreen(itemId: Long, nodeSlug: String?, navigator: Navigator) {
    val viewModel: ReviewItemViewModel = viewModel(key = "review-item-$itemId-$nodeSlug") { ReviewItemViewModel(itemId, nodeSlug) }
    val state by viewModel.state.collectAsState()
    val privileges by ServiceLocator.get.moderation.privileges.collectAsState()
    var sheet by remember { mutableStateOf(ItemSheet.None) }
    var pendingDecision by remember { mutableStateOf<String?>(null) }
    val item = state.item

    val title = when (item?.kind) {
        QUEUE_HELD_KIND -> stringResource(R.string.mod_review_item_title_held)
        null -> stringResource(R.string.mod_review_item_title)
        else -> stringResource(R.string.mod_review_item_title)
    }

    ModUndoHost {
        ModScaffold(
            title = title,
            onBack = navigator::back,
            bottomBar = if (item != null) {
                { ItemActions(item, state.busy, viewModel.siteTools, viewModel, confirm = { pendingDecision = it }) { sheet = it } }
            } else {
                null
            },
        ) {
            when {
                item == null && state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { NodelocLoader() }
                item == null -> EmptyStateView(
                    Lucide.ShieldAlert,
                    stringResource(if (state.failed != null) R.string.mod_review_load_failed else R.string.mod_review_item_missing),
                    retryLabel = stringResource(R.string.common_retry),
                    onRetry = viewModel::load,
                )
                else -> ItemBody(item, state.author, privileges.managesUsers || privileges.moderates(item.nodeSlug), navigator)
            }
        }
    }

    if (item != null) {
        val subtitle = listOfNotNull(item.targetUsername, item.bodyText().take(60).takeIf { it.isNotBlank() }?.let { "“$it”" })
            .joinToString(" · ")
        when (sheet) {
            ItemSheet.Delete, ItemSheet.DeleteThenPenalty -> DeleteContentSheet(
                title = stringResource(if (item.postNumber == 1) R.string.mod_review_delete_topic else R.string.mod_review_delete_reply),
                subtitle = subtitle,
                confirmLabel = stringResource(R.string.common_delete),
                onDismiss = { sheet = ItemSheet.None },
                penaltyMax = state.penaltyMax?.takeIf { item.targetUserId != null },
            ) { reason, reputationPenalty ->
                val thenPenalty = sheet == ItemSheet.DeleteThenPenalty
                sheet = ItemSheet.None
                viewModel.resolve("delete_post", reason, reputationPenalty) { if (thenPenalty) sheet = ItemSheet.Penalty }
            }
            ItemSheet.Hide -> DeleteContentSheet(
                title = stringResource(R.string.mod_review_hide_title),
                subtitle = subtitle,
                confirmLabel = stringResource(R.string.mod_review_hide),
                onDismiss = { sheet = ItemSheet.None },
                penaltyMax = state.penaltyMax?.takeIf { item.targetUserId != null },
            ) { reason, reputationPenalty ->
                sheet = ItemSheet.None
                viewModel.resolve("hide_post", reason, reputationPenalty)
            }
            ItemSheet.Reject -> DeleteContentSheet(
                title = stringResource(R.string.mod_review_reject_title),
                subtitle = subtitle,
                confirmLabel = stringResource(R.string.mod_review_reject),
                onDismiss = { sheet = ItemSheet.None },
            ) { reason, _ ->
                sheet = ItemSheet.None
                viewModel.resolve("delete_post", reason)
            }
            ItemSheet.Penalty -> {
                val author = state.author
                val now = Instant.now()
                PenaltySheet(
                    username = item.targetUsername.orEmpty(),
                    userId = item.targetUserId,
                    nodeSlug = item.nodeSlug,
                    avatarUrl = author?.user?.avatar,
                    activeSiteKinds = author?.penalties.orEmpty()
                        .filter { it.revokedAt == null && (it.endsAt == null || (DiscourseFormat.instant(it.endsAt) ?: now) > now) }
                        .map { it.kind }.toSet(),
                    onDismiss = { sheet = ItemSheet.None },
                    onApplied = viewModel::load,
                )
            }
            ItemSheet.None -> Unit
        }
        pendingDecision?.let { action ->
            val copy = decisionCopy(action, item)
            DecisionDialog(
                title = stringResource(copy.first),
                body = copy.second?.let { stringResource(it) }.orEmpty(),
                confirmLabel = stringResource(decisionConfirmLabel(action)),
                destructive = copy.third,
                onDismiss = { pendingDecision = null },
            ) {
                pendingDecision = null
                viewModel.resolve(action, null)
            }
        }
    }
}

/** What the confirmation says will happen: title, detail, and whether it is the severe kind. */
private fun decisionCopy(action: String, item: ApiReviewItem?): Triple<Int, Int?, Boolean> = when (action) {
    "reject" -> Triple(R.string.mod_confirm_reject, R.string.mod_confirm_reject_d, false)
    "ignore" -> Triple(R.string.mod_confirm_ignore, R.string.mod_confirm_ignore_d, false)
    "approve" -> Triple(R.string.mod_confirm_approve, R.string.mod_confirm_approve_d, false)
    "confirm" -> if (item?.targetType == "user") {
        Triple(R.string.mod_confirm_risk_account, R.string.mod_confirm_risk_account_d, true)
    } else {
        Triple(R.string.mod_confirm_risk, R.string.mod_confirm_risk_d, true)
    }
    "flag_link" -> Triple(R.string.mod_confirm_flag_link, R.string.mod_confirm_flag_link_d, true)
    else -> Triple(R.string.mod_confirm_generic, null, false)
}

private fun decisionConfirmLabel(action: String): Int = when (action) {
    "approve" -> R.string.mod_review_approve
    "ignore" -> R.string.mod_review_ignore
    "reject" -> R.string.mod_review_dismiss
    "flag_link" -> R.string.mod_review_flag_link
    else -> R.string.common_confirm
}

/** [ConfirmDialog], but only a severe decision is drawn in the danger colour. */
@Composable
internal fun DecisionDialog(
    title: String,
    body: String,
    confirmLabel: String,
    destructive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nocturne.bg,
        title = { Text(title, style = Type.heading(17), color = Nocturne.text) },
        text = if (body.isNotEmpty()) {
            { Text(body, style = Type.body(13), color = Nocturne.textSecondary) }
        } else {
            null
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) Nocturne.danger else Nocturne.accent)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), color = Nocturne.textSecondary)
            }
        },
    )
}

@Composable
private fun ItemActions(
    item: ApiReviewItem,
    busy: Boolean,
    siteTools: Boolean,
    viewModel: ReviewItemViewModel,
    /** A decision that neither removes nor hides content, waiting for 确认. */
    confirm: (String) -> Unit,
    open: (ItemSheet) -> Unit,
) {
    val canPenalize = !item.targetUsername.isNullOrBlank()
    Row(
        Modifier.fillMaxWidth().background(Nocturne.neutral300).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (item.status != "pending") {
            Spacer(Modifier.weight(1f))
            if (siteTools && item.kind in setOf("report", QUEUE_HELD_KIND, "node_report")) {
                ModButton(stringResource(R.string.mod_review_reopen), ModButtonStyle.Outlined, enabled = !busy, onClick = viewModel::reopen)
            }
            if (item.reputationDeducted > 0 && !item.reputationRefunded) {
                ModButton(stringResource(R.string.mod_rep_refund), ModButtonStyle.Outlined, enabled = !busy, onClick = viewModel::refundReputation)
            }
            if (item.isPost && (item.postDeleted || item.postHidden)) {
                ModButton(stringResource(R.string.mod_review_restore), ModButtonStyle.Filled, enabled = !busy, onClick = viewModel::restore)
            }
            return@Row
        }
        when {
            item.kind == QUEUE_HELD_KIND -> {
                Spacer(Modifier.weight(1f))
                ModButton(stringResource(R.string.mod_review_reject), ModButtonStyle.Outlined, enabled = !busy) { open(ItemSheet.Reject) }
                ModButton(stringResource(R.string.mod_review_approve), ModButtonStyle.Filled, enabled = !busy) { confirm("approve") }
            }
            item.isPost -> {
                // An auto-hidden post stays hidden when ignored; "unfounded" is what puts it back.
                if (item.postHidden) {
                    ModButton(stringResource(R.string.mod_review_dismiss), ModButtonStyle.Text, enabled = !busy) { confirm("reject") }
                } else {
                    ModButton(stringResource(R.string.mod_review_ignore), ModButtonStyle.Text, enabled = !busy) { confirm("ignore") }
                }
                Spacer(Modifier.weight(1f))
                // Hiding takes the same reason and reputation as deleting: the
                // author sees why, and can edit the post back into view.
                if (!item.postHidden) {
                    ModButton(stringResource(R.string.mod_review_hide), ModButtonStyle.Outlined, enabled = !busy) { open(ItemSheet.Hide) }
                }
                ModButton(stringResource(R.string.common_delete), ModButtonStyle.Outlined, enabled = !busy) { open(ItemSheet.Delete) }
                if (canPenalize) {
                    ModButton(stringResource(R.string.mod_review_delete_penalize), ModButtonStyle.Danger, enabled = !busy) {
                        open(ItemSheet.DeleteThenPenalty)
                    }
                }
            }
            item.kind == "link_report" -> {
                ModButton(stringResource(R.string.mod_review_ignore), ModButtonStyle.Text, enabled = !busy) { confirm("ignore") }
                Spacer(Modifier.weight(1f))
                ModButton(stringResource(R.string.mod_review_dismiss), ModButtonStyle.Outlined, enabled = !busy) { confirm("reject") }
                ModButton(stringResource(R.string.mod_review_flag_link), ModButtonStyle.Danger, enabled = !busy) { confirm("flag_link") }
            }
            item.kind == "risk_flag" -> {
                ModButton(stringResource(R.string.mod_review_ignore), ModButtonStyle.Text, enabled = !busy) { confirm("ignore") }
                Spacer(Modifier.weight(1f))
                ModButton(stringResource(R.string.mod_review_confirm_risk), ModButtonStyle.Filled, enabled = !busy) { confirm("confirm") }
            }
            item.kind == "node_report" -> {
                Text(stringResource(R.string.mod_review_node_report_hint), style = Type.body(13), color = Nocturne.textSecondary, modifier = Modifier.weight(1f))
                ModButton(stringResource(R.string.mod_review_ignore), ModButtonStyle.Outlined, enabled = !busy) { confirm("ignore") }
            }
            else -> {
                // Registrations, app reviews, certifications…: their own pages do the real work;
                // closing the item here would leave the underlying record untouched.
                Text(stringResource(R.string.mod_review_elsewhere), style = Type.body(13), color = Nocturne.textSecondary, modifier = Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemBody(item: ApiReviewItem, author: ApiAdminUserDetail?, canManage: Boolean, navigator: Navigator) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (item.status != "pending") {
            val who = item.resolvedBy?.let { stringResource(R.string.mod_review_resolved_by, it) }.orEmpty()
            val deducted = when {
                item.reputationDeducted <= 0 -> ""
                item.reputationRefunded -> stringResource(R.string.mod_rep_deducted_refunded, item.reputationDeducted)
                else -> stringResource(R.string.mod_rep_deducted, item.reputationDeducted)
            }
            val text = listOf(stringResource(resolutionLabel(item.resolution)), who, deducted, DiscourseFormat.dayAndTime(item.resolvedAt))
                .filter { it.isNotBlank() }.joinToString(" · ")
            Box(
                Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Nocturne.accent100).padding(horizontal = 14.dp, vertical = 10.dp),
            ) { Text(text, style = Type.body(14, FontWeight.Medium), color = Nocturne.accent700) }
        }

        ItemCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Avatar(item.targetUsername ?: "?", size = 36.dp)
                Column(Modifier.weight(1f)) {
                    Text(item.targetUsername.orEmpty(), style = Type.body(15, FontWeight.SemiBold), color = Nocturne.text)
                    Text(
                        listOfNotNull(
                            stringResource(kindLabel(item)),
                            item.nodeSlug?.let { "n/$it" },
                            DiscourseFormat.relative(item.createdAt),
                        ).joinToString(" · "),
                        style = Type.body(13),
                        color = Nocturne.textSecondary,
                    )
                }
            }
            ItemPills(item)
            val body = item.bodyText()
            if (body.isNotBlank()) {
                Text(body, style = Type.body(16), color = Nocturne.text)
            }
            when {
                item.isPost && item.topicId != null -> LinkStrip(
                    stringResource(
                        if (item.postNumber == 1) R.string.mod_review_in_topic_first else R.string.mod_review_in_topic,
                        item.topicTitle.orEmpty(),
                    ),
                ) { navigator.openTopic(item.topicId.toInt(), item.postNumber) }
                item.kind == "link_report" -> item.payloadString("url")?.let { url ->
                    LinkStrip(url) { navigator.openBrowser(url) }
                }
                item.kind == "node_report" && item.nodeSlug != null -> LinkStrip("n/${item.nodeSlug}") {
                    navigator.openNode(item.targetId.toInt(), item.nodeSlug)
                }
            }
        }

        if (item.reports.isNotEmpty()) {
            Column {
                val groups = item.reports.groupingBy { it.reason }.eachCount().entries.sortedByDescending { it.value }
                val breakdown = groups.map { (reason, count) -> "${stringResource(reportReasonLabel(reason))} $count" }
                Text(
                    (listOf(stringResource(R.string.mod_review_reporters, item.reports.size)) + breakdown).joinToString(" · "),
                    style = Type.body(14, FontWeight.Medium),
                    color = Nocturne.accent,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                )
                item.reports.forEach { report ->
                    ModListRow(
                        title = "${report.reporter} · ${stringResource(reportReasonLabel(report.reason))}",
                        supporting = listOfNotNull(
                            report.note?.takeIf { it.isNotBlank() }?.let { "“$it”" },
                            DiscourseFormat.relative(report.createdAt).takeIf { it.isNotBlank() },
                        ).joinToString(" · ").ifBlank { null },
                        modifier = Modifier.clickable { navigator.openProfile(report.reporter) },
                        leading = { Avatar(report.reporter, size = 30.dp, variant = 1) },
                    )
                }
            }
        }

        val username = item.targetUsername
        val userId = item.targetUserId
        if (username != null && userId != null) {
            ItemCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.mod_review_author),
                        style = Type.body(15, FontWeight.SemiBold),
                        color = Nocturne.text,
                        modifier = Modifier.weight(1f),
                    )
                    if (canManage) {
                        Text(
                            stringResource(R.string.mod_review_manage_user),
                            style = Type.body(13, FontWeight.SemiBold),
                            color = Nocturne.accent,
                            modifier = Modifier.clickable { navigator.openModUser(userId, username, item.nodeSlug) },
                        )
                    }
                }
                if (author != null) {
                    val deleted = author.reportsAgainst.count { it.resolution == "delete_post" }
                    Row(Modifier.fillMaxWidth()) {
                        Stat(joinedFor(author.user.createdAt), stringResource(R.string.mod_review_stat_joined), Modifier.weight(1f))
                        Stat(author.stats.posts.toString(), stringResource(R.string.mod_review_stat_posts), Modifier.weight(1f))
                        Stat(deleted.toString(), stringResource(R.string.mod_review_stat_deleted), Modifier.weight(1f), warn = deleted > 0)
                        Stat(author.stats.timesReported.toString(), stringResource(R.string.mod_review_stat_reported), Modifier.weight(1f), warn = author.stats.timesReported > 0)
                    }
                } else {
                    Text(
                        stringResource(R.string.mod_review_author_profile, username),
                        style = Type.body(14),
                        color = Nocturne.accent,
                        modifier = Modifier.clickable { navigator.openProfile(username) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemCard(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .shadow(1.dp, shape)
            .clip(shape)
            .background(Nocturne.bg)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun LinkStrip(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Nocturne.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Type.body(13), color = Nocturne.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        androidx.compose.material3.Icon(Lucide.ChevronRight, null, tint = Nocturne.textSecondary)
    }
}

@Composable
internal fun Stat(value: String, label: String, modifier: Modifier = Modifier, warn: Boolean = false) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = Type.heading(18, FontWeight.Bold), color = if (warn) Nocturne.danger else Nocturne.text, maxLines = 1)
        Text(label, style = Type.body(12), color = Nocturne.textSecondary, maxLines = 1)
    }
}

/** "3 天" since an account was created; empty when unknown. */
@Composable
internal fun joinedFor(createdAt: String?): String {
    val created = DiscourseFormat.instant(createdAt) ?: return "—"
    val days = Duration.between(created, Instant.now()).toDays().coerceAtLeast(0).toInt()
    return stringResource(R.string.mod_duration_days, days)
}
