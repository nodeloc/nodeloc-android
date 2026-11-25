package com.nodeloc.app.feature.moderation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Flag
import com.composables.icons.lucide.Hourglass
import com.composables.icons.lucide.ListChecks
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Scale
import com.composables.icons.lucide.ScrollText
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.ShieldAlert
import com.composables.icons.lucide.Users
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FilterPill
import com.nodeloc.app.core.design.FilterPillRow
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.PillTone
import com.nodeloc.app.core.design.StatusPill
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.network.ApiReviewItem
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.network.DiscourseError
import com.nodeloc.app.core.store.ModPrivileges
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.feature.nav.Navigator
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Item kinds that are somebody's report rather than content held for a look. */
internal val REPORT_KINDS = setOf("report", "node_report", "link_report")

data class DashTiles(val pending: Int, val reports: Int, val appeals: Int?)

/** Today's work, counted off the log; there is no response-time data anywhere. */
data class DashToday(val handled: Int, val deleted: Int, val penalized: Int)

data class DashboardState(
    /** Null is the whole site. Meaningful only once [scopeChosen]. */
    val scope: String? = null,
    val scopeChosen: Boolean = false,
    val loading: Boolean = false,
    val tiles: DashTiles? = null,
    val urgent: List<ApiReviewItem> = emptyList(),
    val today: DashToday? = null,
    val error: Throwable? = null,
)

class DashboardViewModel(private val initialSlug: String?) : ViewModel() {
    private val api = ServiceLocator.get.moderationApi
    private val moderation = ServiceLocator.get.moderation

    val privileges: StateFlow<ModPrivileges> = moderation.privileges
    val nodePending: StateFlow<Map<String, Int>> = moderation.nodePending
    val siteBadge: StateFlow<Int> = moderation.menuBadge

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        if (initialSlug != null) {
            select(initialSlug)
        } else {
            // The site scope needs to know the viewer reviews the site; until
            // the permissions land there is nothing honest to pick.
            viewModelScope.launch {
                privileges.collect { p ->
                    if (_state.value.scopeChosen) return@collect
                    when {
                        p.reviewsSite -> select(null)
                        p.nodes.isNotEmpty() -> select(p.nodes.first().slug)
                    }
                }
            }
            if (!privileges.value.isModerator) moderation.refresh()
        }
    }

    private var resumedOnce = false

    /** The first resume is the screen opening, which [init] already loads for. */
    fun onResume() {
        if (resumedOnce) load() else resumedOnce = true
    }

    fun select(scope: String?) {
        if (_state.value.scopeChosen && _state.value.scope == scope) return
        _state.value = DashboardState(scope = scope, scopeChosen = true)
        load()
    }

    fun load() {
        val current = _state.value
        if (!current.scopeChosen) return
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val scope = current.scope
            runCatchingCancellable { if (scope == null) loadSite(privileges.value) else loadNode(scope) }
                .onSuccess { loaded -> _state.update { loaded.copy(scope = scope, scopeChosen = true) } }
                .onFailure { error -> _state.update { it.copy(loading = false, error = error) } }
        }
    }

    private suspend fun loadSite(p: ModPrivileges): DashboardState = coroutineScope {
        val queue = async { api.siteQueue(limit = 50) }
        val appeals = if (p.handlesAppeals) async { runCatchingCancellable { api.appeals() }.getOrNull() } else null
        val today = if (p.readsAuditLog) async { runCatchingCancellable { siteToday() }.getOrNull() } else null
        val page = queue.await()
        val kinds = page.kindCounts
        val reports = REPORT_KINDS.sumOf { kinds[it] ?: 0 }
        val appealKind = kinds["risk_appeal"] ?: 0
        val appealList = appeals?.await()
        val appealCount = if (!p.handlesAppeals) null
        else appealList?.counts?.get("pending") ?: appealList?.total ?: appealKind
        DashboardState(
            tiles = DashTiles(
                pending = (kinds.values.sum() - reports - appealKind).coerceAtLeast(0),
                reports = reports,
                appeals = appealCount,
            ),
            urgent = urgentOf(page.items),
            today = today?.await(),
        )
    }

    private suspend fun loadNode(slug: String): DashboardState = coroutineScope {
        val queue = async { api.nodeQueue(slug, limit = 50) }
        val today = async { runCatchingCancellable { nodeToday(slug) }.getOrNull() }
        val items = queue.await().items
        val reports = items.count { it.kind in REPORT_KINDS }
        DashboardState(
            tiles = DashTiles(pending = items.size - reports, reports = reports, appeals = null),
            urgent = urgentOf(items),
            today = today.await(),
        )
    }

    /**
     * Priority reports first, then whatever has waited longest. "Longest" is
     * within the first page: the queue is newest-first with no oldest-first
     * order, and walking it to the end for three rows is not worth the calls.
     */
    private fun urgentOf(items: List<ApiReviewItem>): List<ApiReviewItem> {
        val (priority, rest) = items.partition { it.priority }
        return (priority.sortedBy { it.id } + rest.sortedBy { it.id }).take(3)
    }

    /** The site log is newest first; walk back until yesterday, a few pages at most. */
    private suspend fun siteToday(): DashToday {
        val since = startOfToday()
        val actions = mutableListOf<String>()
        var before: Long? = null
        repeat(TODAY_PAGES) {
            val page = api.auditLogs(before = before)
            val fresh = page.logs.filter { (DiscourseFormat.instant(it.createdAt) ?: since) >= since }
            actions += fresh.map { it.action }
            if (fresh.size < page.logs.size || page.nextBefore == null) return countToday(actions, node = false)
            before = page.nextBefore
        }
        return countToday(actions, node = false)
    }

    private suspend fun nodeToday(slug: String): DashToday {
        val since = startOfToday()
        val actions = mutableListOf<String>()
        var before: Long? = null
        repeat(TODAY_PAGES) {
            val page = api.nodeModLog(slug, before)
            val fresh = page.logs.filter { (DiscourseFormat.instant(it.createdAt) ?: since) >= since }
            actions += fresh.map { it.action }
            if (fresh.size < page.logs.size || page.nextBefore == null) return countToday(actions, node = true)
            before = page.nextBefore
        }
        return countToday(actions, node = true)
    }

    private fun countToday(actions: List<String>, node: Boolean): DashToday = DashToday(
        handled = actions.count { it.startsWith("review.") },
        deleted = actions.count { it == "review.delete_post" || (node && (it == "post.delete" || it == "topic.delete")) },
        penalized = actions.count {
            if (node) it == "restrict.mute" || it == "restrict.ban" else it == "user.silence" || it == "user.suspend"
        },
    )

    private companion object {
        const val TODAY_PAGES = 6
    }
}

@Composable
fun ModDashboardScreen(nodeSlug: String?, navigator: Navigator) {
    val viewModel: DashboardViewModel = viewModel(key = "mod-dashboard-$nodeSlug") { DashboardViewModel(nodeSlug) }
    val state by viewModel.state.collectAsState()
    val privileges by viewModel.privileges.collectAsState()
    val nodePending by viewModel.nodePending.collectAsState()
    val siteBadge by viewModel.siteBadge.collectAsState()

    // Back from the queue, the numbers should already have moved.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    LaunchedEffect(Unit) { runCatchingCancellable { ServiceLocator.get.moderation.refreshNodeCounts() } }

    ModScaffold(
        title = stringResource(R.string.mod_dash_title),
        onBack = navigator::back,
        actions = { HeaderIconButton(Lucide.RefreshCw, stringResource(R.string.common_refresh), onClick = viewModel::load) },
    ) {
        val current = state.scope
        val scopes: List<String> = buildList {
            privileges.nodes.forEach { add(it.slug) }
            if (current != null && current !in this) add(current)
        }
        if (privileges.reviewsSite || scopes.size > 1) {
            FilterPillRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                if (privileges.reviewsSite) {
                    FilterPill(
                        stringResource(R.string.mod_dash_scope_all),
                        selected = state.scopeChosen && state.scope == null,
                        count = siteBadge,
                        onClick = { viewModel.select(null) },
                    )
                }
                scopes.forEach { slug ->
                    FilterPill(
                        stringResource(R.string.mod_dash_node_slug, slug),
                        selected = state.scope == slug,
                        count = nodePending[slug],
                        onClick = { viewModel.select(slug) },
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Nocturne.surface)) {
            val error = state.error
            when {
                state.tiles == null && error != null -> EmptyStateView(
                    Lucide.CircleAlert,
                    stringResource(R.string.mod_dash_load_failed),
                    detail = stringResource((error as? DiscourseError)?.messageRes ?: R.string.error_action_failed),
                    retryLabel = stringResource(R.string.common_retry),
                    onRetry = viewModel::load,
                )
                state.tiles == null -> NodelocLoader(Modifier.align(Alignment.Center), height = 40.dp)
                else -> DashboardBody(state, privileges, navigator)
            }
        }
    }
}

@Composable
private fun DashboardBody(state: DashboardState, privileges: ModPrivileges, navigator: Navigator) {
    val tiles = state.tiles ?: return
    val scope = state.scope
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DashTile(
                Lucide.Hourglass, tiles.pending, stringResource(R.string.mod_dash_tile_pending),
                Nocturne.warningBg, Nocturne.warning, Modifier.weight(1f),
            ) { navigator.openReviewQueue(scope) }
            DashTile(
                Lucide.Flag, tiles.reports, stringResource(R.string.mod_dash_tile_reports),
                Nocturne.dangerBg, Nocturne.danger, Modifier.weight(1f),
            ) { navigator.openReviewQueue(scope) }
            if (tiles.appeals != null) {
                DashTile(
                    Lucide.Scale, tiles.appeals, stringResource(R.string.mod_dash_tile_appeals),
                    Nocturne.infoBg, Nocturne.info, Modifier.weight(1f),
                ) { navigator.openReviewQueue(null) }
            }
        }

        Column {
            DashSectionLabel(stringResource(R.string.mod_dash_urgent))
            if (state.urgent.isEmpty()) {
                Text(
                    stringResource(R.string.mod_dash_urgent_empty),
                    style = Type.body(14),
                    color = Nocturne.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            state.urgent.forEach { item ->
                UrgentRow(item, scope) { navigator.openReviewItem(item.id, scope) }
            }
        }

        state.today?.let { today ->
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    stringResource(R.string.mod_dash_today),
                    style = Type.body(14, FontWeight.Medium),
                    color = Nocturne.accent,
                    modifier = Modifier.padding(bottom = 7.dp),
                )
                Row(Modifier.fillMaxWidth().dashCard(Nocturne.bg).padding(vertical = 14.dp, horizontal = 4.dp)) {
                    TodayCell(today.handled, stringResource(R.string.mod_dash_today_handled), Modifier.weight(1f))
                    TodayCell(today.deleted, stringResource(R.string.mod_dash_today_deleted), Modifier.weight(1f))
                    TodayCell(today.penalized, stringResource(R.string.mod_dash_today_penalized), Modifier.weight(1f))
                }
            }
        }

        Column {
            DashSectionLabel(stringResource(R.string.mod_dash_tools))
            if (privileges.managesUsers) {
                DashListRow(
                    Lucide.Users,
                    stringResource(R.string.mod_dash_tool_users),
                    supporting = stringResource(R.string.mod_dash_tool_users_detail),
                    onClick = navigator::openModUsers,
                )
            }
            if (scope != null || privileges.readsAuditLog) {
                DashListRow(
                    Lucide.ScrollText,
                    stringResource(R.string.mod_dash_tool_log),
                    supporting = stringResource(R.string.mod_dash_tool_log_detail),
                    onClick = { navigator.openAuditLog(scope) },
                )
            }
            if (scope == null) {
                // Watched words have no API the app could edit them through;
                // the admin page is the only place they live.
                DashListRow(
                    Lucide.ListChecks,
                    stringResource(R.string.mod_dash_tool_rules),
                    supporting = stringResource(R.string.mod_dash_tool_rules_detail),
                    onClick = { navigator.openBrowser("${DiscourseConfig.BASE_URL}/admin/watched-words") },
                )
            } else {
                DashListRow(
                    Lucide.Settings,
                    stringResource(R.string.mod_dash_tool_node),
                    supporting = stringResource(R.string.mod_dash_tool_node_detail),
                    onClick = { navigator.openNodeAdmin(scope) },
                )
            }
        }
    }
}

@Composable
private fun DashTile(
    icon: ImageVector,
    value: Int,
    label: String,
    ground: Color,
    ink: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .dashCard(Nocturne.bg)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DashIconWell(icon, ground, ink)
        Text(value.toString(), style = Type.heading(28, FontWeight.Bold), color = Nocturne.text)
        Text(label, style = Type.body(13), color = Nocturne.textSecondary)
    }
}

@Composable
private fun TodayCell(value: Int, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = Type.heading(20, FontWeight.Bold), color = Nocturne.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(2.dp))
        Text(label, style = Type.body(12), color = Nocturne.textSecondary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun UrgentRow(item: ApiReviewItem, scope: String?, onClick: () -> Unit) {
    val icon = when {
        item.kind in REPORT_KINDS -> Lucide.Flag
        item.kind == "pending_post" -> Lucide.Hourglass
        else -> Lucide.ShieldAlert
    }
    val meta = listOfNotNull(
        item.nodeSlug?.takeIf { scope == null }?.let { stringResource(R.string.mod_dash_node_slug, it) },
        reviewReasonText(item),
        modAgo(item.createdAt).takeIf { it.isNotEmpty() },
    ).joinToString(" · ")
    val trailing: (@Composable RowScope.() -> Unit)? = if (item.priority) {
        { StatusPill(stringResource(R.string.mod_dash_priority), PillTone.Danger) }
    } else {
        null
    }
    DashListRow(icon, reviewItemSummary(item), supporting = meta.ifEmpty { null }, onClick = onClick, trailing = trailing)
}

/** One line saying what an item is: "一条回复被 6 人举报". */
@Composable
internal fun reviewItemSummary(item: ApiReviewItem): String {
    val reporters = maxOf(item.reportCount.toInt(), item.reports.size, 1)
    val isTopic = item.postNumber == 1
    val who = item.targetUsername.orEmpty()
    return when (item.kind) {
        "report" -> stringResource(
            if (isTopic) R.string.mod_dash_item_reported_topic else R.string.mod_dash_item_reported_reply,
            reporters,
        )
        "pending_post" -> stringResource(if (isTopic) R.string.mod_dash_item_held_topic else R.string.mod_dash_item_held_reply)
        "node_report" -> stringResource(R.string.mod_dash_item_node_report, item.nodeName ?: item.nodeSlug.orEmpty(), reporters)
        "link_report" -> stringResource(R.string.mod_dash_item_link_report, item.payload.str("host").orEmpty())
        "risk_flag" -> if (item.targetType == "user") stringResource(R.string.mod_dash_item_risk_user, who)
        else stringResource(R.string.mod_dash_item_risk_post)
        "risk_appeal" -> stringResource(R.string.mod_dash_item_appeal, who)
        "registration" -> stringResource(R.string.mod_dash_item_registration, item.payload.str("username") ?: who)
        else -> stringResource(R.string.mod_dash_item_other)
    }
}

/** The most common reporter reason, or why a post is held (server prose, already Chinese). */
@Composable
private fun reviewReasonText(item: ApiReviewItem): String? {
    item.heldReason?.let { return it }
    val reason = item.reports.groupingBy { it.reason }.eachCount().maxByOrNull { it.value }?.key ?: return null
    return stringResource(reportReasonLabel(reason))
}
