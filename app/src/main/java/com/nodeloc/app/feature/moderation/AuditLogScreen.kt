package com.nodeloc.app.feature.moderation

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ArrowUpDown
import com.composables.icons.lucide.Ban
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ScrollText
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Undo2
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FilterPill
import com.nodeloc.app.core.design.FilterPillRow
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.OnReachedEnd
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.network.DiscourseError
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.feature.nav.Navigator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

enum class LogFilter(@StringRes val labelRes: Int) {
    All(R.string.mod_log_filter_all),
    Delete(R.string.mod_log_filter_delete),
    Penalty(R.string.mod_log_filter_penalty),
    Review(R.string.mod_log_filter_review),
    Mine(R.string.mod_log_filter_mine),
}

private val DELETE_ACTIONS = setOf("review.delete_post", "post.delete", "topic.delete")
private val PENALTY_ACTIONS = setOf("user.silence", "user.suspend", "user.unsilence", "user.unsuspend", "user.update_penalty")

data class AuditLogState(
    val entries: List<LogEntry> = emptyList(),
    val filter: LogFilter = LogFilter.All,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: Throwable? = null,
    /** The site log was asked for by someone without `admin.logs`. */
    val forbidden: Boolean = false,
    val undone: Set<Long> = emptySet(),
    val undoing: Set<Long> = emptySet(),
)

class AuditLogViewModel(private val nodeSlug: String?) : ViewModel() {
    private val api = ServiceLocator.get.moderationApi
    private val privileges = ServiceLocator.get.moderation.privileges
    private val me = ServiceLocator.get.session.username

    private val _state = MutableStateFlow(AuditLogState())
    val state: StateFlow<AuditLogState> = _state.asStateFlow()

    private val _undone = MutableSharedFlow<LogEntry>(extraBufferCapacity = 1)
    val undone: SharedFlow<LogEntry> = _undone.asSharedFlow()

    private var cursor: Long? = null
    private var job: Job? = null

    init {
        if (nodeSlug == null && !privileges.value.isModerator) {
            // Opened before the permissions arrived (a cold start straight
            // here): judging `admin.logs` off the empty default would call a
            // moderator forbidden.
            _state.update { it.copy(loading = true) }
            viewModelScope.launch {
                withTimeoutOrNull(8_000) { privileges.first { it.isModerator } }
                refresh()
            }
        } else {
            refresh()
        }
    }

    fun canUndo(entry: LogEntry): Boolean {
        val p = privileges.value
        return when (entry.undo()) {
            LogUndo.RevokeSilence, LogUndo.RevokeSuspend -> p.managesUsers
            LogUndo.RestorePost -> p.reviewsSite
            null -> false
        }
    }

    fun setFilter(filter: LogFilter) {
        if (filter == _state.value.filter) return
        _state.update { it.copy(filter = filter) }
        refresh()
    }

    fun refresh() {
        if (nodeSlug == null && !privileges.value.readsAuditLog) {
            _state.update { it.copy(forbidden = true, loading = false) }
            return
        }
        job?.cancel()
        cursor = null
        _state.update { it.copy(entries = emptyList(), loading = true, hasMore = false, error = null) }
        job = viewModelScope.launch { fill() }
    }

    fun loadMore() {
        val current = _state.value
        if (!current.hasMore || current.loading || current.loadingMore) return
        job = viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            fill()
        }
    }

    /**
     * Pages until something new is visible. The node log has no server
     * filters and the site log's are coarser than the chips, so a page can
     * filter down to nothing — and a list that does not grow never reaches the
     * end that would ask for the next page.
     */
    private suspend fun fill() {
        val filter = _state.value.filter
        var added = 0
        var pages = 0
        while (added < MIN_VISIBLE && pages < MAX_PAGES_PER_FILL) {
            val result = runCatchingCancellable { fetch(filter, cursor) }
            val page = result.getOrElse { error ->
                _state.update { it.copy(loading = false, loadingMore = false, error = error) }
                return
            }
            pages++
            cursor = page.second
            val visible = page.first.filter { matches(it, filter) }
            added += visible.size
            _state.update {
                it.copy(entries = (it.entries + visible).distinctBy { e -> e.id }, hasMore = cursor != null)
            }
            if (cursor == null) break
        }
        _state.update { it.copy(loading = false, loadingMore = false) }
    }

    private suspend fun fetch(filter: LogFilter, before: Long?): Pair<List<LogEntry>, Long?> {
        if (nodeSlug != null) {
            val page = api.nodeModLog(nodeSlug, before)
            return page.logs.map { it.toEntry() } to page.nextBefore
        }
        val page = api.auditLogs(
            actor = if (filter == LogFilter.Mine) me else null,
            action = when (filter) {
                LogFilter.Delete -> "review.delete_post"
                LogFilter.Penalty -> "user."
                LogFilter.Review -> "review."
                else -> null
            },
            before = before,
        )
        return page.logs.map { it.toEntry() } to page.nextBefore
    }

    private fun matches(entry: LogEntry, filter: LogFilter): Boolean = when (filter) {
        LogFilter.All -> true
        LogFilter.Delete -> entry.action in DELETE_ACTIONS
        LogFilter.Penalty -> entry.action in PENALTY_ACTIONS || entry.action.startsWith("restrict.")
        LogFilter.Review -> entry.action.startsWith("review.")
        LogFilter.Mine -> me != null && entry.actor.equals(me, ignoreCase = true)
    }

    fun undo(entry: LogEntry) {
        val kind = entry.undo() ?: return
        val target = entry.targetId ?: return
        if (entry.id in _state.value.undoing) return
        _state.update { it.copy(undoing = it.undoing + entry.id) }
        viewModelScope.launch {
            runCatchingCancellable {
                when (kind) {
                    LogUndo.RevokeSilence -> api.revokePenalty(target, "silence")
                    LogUndo.RevokeSuspend -> api.revokePenalty(target, "suspend")
                    LogUndo.RestorePost -> api.restoreFromReview(target)
                }
            }.onSuccess {
                _state.update { it.copy(undoing = it.undoing - entry.id, undone = it.undone + entry.id) }
                _undone.tryEmit(entry)
            }.onFailure { error ->
                _state.update { it.copy(undoing = it.undoing - entry.id) }
                ToastCenter.showError(error)
            }
        }
    }

    private companion object {
        const val MIN_VISIBLE = 20
        const val MAX_PAGES_PER_FILL = 5
    }
}

@Composable
fun AuditLogScreen(nodeSlug: String?, navigator: Navigator, initialFilter: String? = null) {
    val viewModel: AuditLogViewModel = viewModel(key = "audit-log-$nodeSlug") { AuditLogViewModel(nodeSlug) }
    val state by viewModel.state.collectAsState()
    LaunchedEffect(initialFilter) {
        LogFilter.entries.firstOrNull { it.name == initialFilter }?.let(viewModel::setFilter)
    }
    val privileges by ServiceLocator.get.moderation.privileges.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val resources = androidx.compose.ui.platform.LocalResources.current
    var confirming by remember { mutableStateOf<LogEntry?>(null) }
    // The entry opened on its own page; undo lives there, not on the row.
    var opened by remember { mutableStateOf<LogEntry?>(null) }
    val listState = rememberLazyListState()
    listState.OnReachedEnd { viewModel.loadMore() }

    LaunchedEffect(viewModel) {
        viewModel.undone.collect { entry ->
            val message = when (entry.undo()) {
                LogUndo.RevokeSilence -> resources.getString(R.string.mod_log_undone_silence, entry.targetName.orEmpty())
                LogUndo.RevokeSuspend -> resources.getString(R.string.mod_log_undone_suspend, entry.targetName.orEmpty())
                else -> resources.getString(R.string.mod_log_undone_restore)
            }
            snackbar.showSnackbar(message)
        }
    }

    Box(Modifier.fillMaxSize()) {
        ModScaffold(
            title = if (nodeSlug == null) stringResource(R.string.mod_log_title)
            else stringResource(R.string.mod_log_title_node, nodeSlug),
            onBack = navigator::back,
        ) {
            FilterPillRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                LogFilter.entries.forEach { filter ->
                    FilterPill(stringResource(filter.labelRes), selected = state.filter == filter) { viewModel.setFilter(filter) }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val error = state.error
                when {
                    state.forbidden -> EmptyStateView(Lucide.ScrollText, stringResource(R.string.mod_log_forbidden))
                    state.entries.isEmpty() && state.loading -> NodelocLoader(Modifier.align(Alignment.Center), height = 40.dp)
                    state.entries.isEmpty() && error != null -> EmptyStateView(
                        Lucide.CircleAlert,
                        stringResource(R.string.mod_log_load_failed),
                        detail = stringResource((error as? DiscourseError)?.messageRes ?: R.string.error_action_failed),
                        retryLabel = stringResource(R.string.common_retry),
                        onRetry = viewModel::refresh,
                    )
                    state.entries.isEmpty() -> EmptyStateView(Lucide.ScrollText, stringResource(R.string.mod_log_empty))
                    else -> LogList(
                        state = state,
                        listState = listState,
                        onOpen = { opened = it },
                    )
                }
            }
        }
        opened?.let { entry ->
            androidx.activity.compose.BackHandler { opened = null }
            LogDetailPage(
                entry = entry,
                undoable = viewModel.canUndo(entry),
                undone = entry.id in state.undone,
                undoing = entry.id in state.undoing,
                canOpenTarget = hasTarget(entry, privileges.managesUsers),
                onBack = { opened = null },
                onUndo = { confirming = entry },
                onOpenTarget = { openEntry(entry, navigator, privileges.managesUsers) },
            )
        }
        DashSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    confirming?.let { entry ->
        UndoDialog(
            entry = entry,
            onConfirm = {
                confirming = null
                viewModel.undo(entry)
            },
            onDismiss = { confirming = null },
        )
    }
}

private fun hasTarget(entry: LogEntry, managesUsers: Boolean): Boolean =
    entry.details.long("topic_id") != null ||
        (entry.targetType == "user" && entry.targetId != null && managesUsers) ||
        (entry.targetType == null && entry.targetName != null)

private fun openEntry(entry: LogEntry, navigator: Navigator, managesUsers: Boolean) {
    val topicId = entry.details.long("topic_id")
    when {
        topicId != null -> navigator.openTopic(topicId.toInt(), entry.details.long("post_number")?.toInt())
        entry.targetType == "user" && entry.targetId != null && managesUsers ->
            navigator.openModUser(entry.targetId, entry.targetName.orEmpty(), null)
        entry.targetType == null && entry.targetName != null -> navigator.openProfile(entry.targetName)
    }
}

@Composable
private fun LogList(
    state: AuditLogState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onOpen: (LogEntry) -> Unit,
) {
    val today = LocalDate.now()
    val groups = state.entries.groupBy { entry ->
        DiscourseFormat.instant(entry.createdAt)?.let(::localDate) ?: today
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
        groups.forEach { (day, entries) ->
            item(key = "day-$day") {
                DashSectionLabel(
                    when (day) {
                        today -> stringResource(R.string.mod_log_today)
                        today.minusDays(1) -> stringResource(R.string.mod_log_yesterday)
                        else -> day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                    },
                    Modifier.padding(top = 10.dp),
                )
            }
            items(entries, key = { it.id }) { entry ->
                val time = if (day == today) modAgo(entry.createdAt) else DiscourseFormat.clock(entry.createdAt)
                LogRow(
                    entry = entry,
                    meta = logMeta(entry, time),
                    undone = entry.id in state.undone,
                    onOpen = { onOpen(entry) },
                )
            }
        }
        if (state.loadingMore) {
            item(key = "more") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    NodelocLoader(height = 28.dp)
                }
            }
        }
    }
}

@Composable
private fun LogRow(
    entry: LogEntry,
    meta: String,
    undone: Boolean,
    onOpen: () -> Unit,
) {
    val trailing: (@Composable RowScope.() -> Unit)? = if (undone) {
        { Text(stringResource(R.string.mod_log_undone), style = Type.body(14), color = Nocturne.textTertiary) }
    } else {
        null
    }
    DashListRow(
        icon = logIcon(entry.action),
        title = logSentence(entry),
        supporting = meta.ifEmpty { null },
        chevron = !undone,
        onClick = onOpen,
        trailing = trailing,
    )
}

/**
 * One entry on its own page (App-Android-Log › detail): the sentence, who and
 * when, every detail the server recorded, a way to the thing acted on, and —
 * where this reader may — 撤销, confirmed first.
 */
@Composable
private fun LogDetailPage(
    entry: LogEntry,
    undoable: Boolean,
    undone: Boolean,
    undoing: Boolean,
    canOpenTarget: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onOpenTarget: () -> Unit,
) {
    ModScaffold(
        title = stringResource(R.string.mod_log_detail_title),
        onBack = onBack,
        bottomBar = if (undoable || undone) {
            {
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    ModButton(
                        stringResource(if (undone) R.string.mod_log_undone else R.string.common_undo),
                        ModButtonStyle.Filled,
                        Modifier.weight(1f),
                        enabled = undoable && !undone && !undoing,
                        onClick = onUndo,
                    )
                }
            }
        } else {
            null
        },
    ) {
        androidx.compose.foundation.layout.Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
            ) {
                Icon(logIcon(entry.action), null, tint = Nocturne.textSecondary)
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    Text(logSentence(entry), style = Type.heading(18, FontWeight.Bold), color = Nocturne.text)
                    Text(
                        listOfNotNull(
                            DiscourseFormat.dayAndTime(entry.createdAt).ifEmpty { null },
                            modAgo(entry.createdAt).ifEmpty { null },
                        ).joinToString(" · "),
                        style = Type.body(13),
                        color = Nocturne.textSecondary,
                    )
                }
            }
            DashSectionLabel(stringResource(R.string.mod_log_detail_section))
            DetailLine(stringResource(R.string.mod_log_detail_actor), entry.actor ?: stringResource(R.string.mod_log_system))
            entry.targetName?.let { DetailLine(stringResource(R.string.mod_log_detail_target), it) }
            DetailLine(stringResource(R.string.mod_log_detail_action), entry.action)
            val meta = logMeta(entry, "")
            if (meta.isNotBlank()) DetailLine(stringResource(R.string.mod_log_detail_context), meta.trim(' ', '·'))
            // What else the server wrote down, as it wrote it: a moderator
            // checking an action wants the record, not a paraphrase.
            entry.details?.forEach { (key, value) ->
                val text = (value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: value.toString()
                if (text.isNotBlank() && key !in setOf("topic_id", "post_number")) DetailLine(key, text)
            }
            if (canOpenTarget) {
                DashListRow(
                    icon = Lucide.ExternalLink,
                    title = stringResource(R.string.mod_log_detail_open),
                    onClick = onOpenTarget,
                )
            }
            if (!undoable && !undone) {
                Text(
                    stringResource(R.string.mod_log_detail_no_undo),
                    style = Type.body(12),
                    color = Nocturne.textTertiary,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = Type.body(14), color = Nocturne.textSecondary, modifier = Modifier.width(96.dp))
        Text(value, style = Type.body(14), color = Nocturne.text, modifier = Modifier.weight(1f))
    }
}

private fun logIcon(action: String): ImageVector = when {
    action in DELETE_ACTIONS -> Lucide.Trash2
    action in PENALTY_ACTIONS || action.startsWith("restrict.") -> Lucide.Ban
    action.startsWith("review.") -> Lucide.ShieldCheck
    action == "topic.move" -> Lucide.ArrowUpDown
    else -> Lucide.History
}

@Composable
private fun UndoDialog(entry: LogEntry, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val body = when (entry.undo()) {
        LogUndo.RevokeSilence -> stringResource(R.string.mod_log_undo_silence_body, entry.targetName.orEmpty())
        LogUndo.RevokeSuspend -> stringResource(R.string.mod_log_undo_suspend_body, entry.targetName.orEmpty())
        else -> stringResource(R.string.mod_log_undo_restore_body)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nocturne.bg,
        icon = { Icon(Lucide.Undo2, null, tint = Nocturne.accent) },
        title = { Text(stringResource(R.string.mod_log_undo_title), style = Type.heading(20), color = Nocturne.text) },
        text = { Text(body, style = Type.body(14), color = Nocturne.textSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.common_undo), color = Nocturne.accent, style = Type.body(14, FontWeight.Medium))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), color = Nocturne.textSecondary, style = Type.body(14, FontWeight.Medium))
            }
        },
    )
}
