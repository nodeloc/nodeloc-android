package com.nodeloc.app.feature.moderation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Flag
import com.composables.icons.lucide.Hourglass
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Key
import com.composables.icons.lucide.ListChecks
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.Rss
import com.composables.icons.lucide.ScrollText
import com.composables.icons.lucide.ShieldCheck
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.Avatar
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.network.ApiChannelList
import com.nodeloc.app.core.network.ApiNodeAccess
import com.nodeloc.app.core.network.ApiNodeManage
import com.nodeloc.app.core.network.ApiNodeRule
import com.nodeloc.app.core.network.ChannelState
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.network.DiscourseError
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.feature.nav.Navigator
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NodeAdminState(
    val manage: ApiNodeManage? = null,
    val pending: Int = 0,
    val reports: Int = 0,
    /** Owner-level only; null for a node moderator. */
    val access: ApiNodeAccess? = null,
    /** Owner-level only; null for a node moderator. */
    val channels: ApiChannelList? = null,
    /** The node page route takes a category id the manage endpoint does not give. */
    val categoryId: Int? = null,
    val loading: Boolean = false,
    val error: Throwable? = null,
    val savingRules: Boolean = false,
)

class NodeAdminViewModel(private val slug: String) : ViewModel() {
    private val api = ServiceLocator.get.moderationApi
    private val client = ServiceLocator.get.client

    private val _state = MutableStateFlow(NodeAdminState())
    val state: StateFlow<NodeAdminState> = _state.asStateFlow()

    private val _rulesSaved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val rulesSaved: SharedFlow<Unit> = _rulesSaved.asSharedFlow()

    private var job: Job? = null
    private var resumedOnce = false

    init {
        load()
    }

    fun onResume() {
        if (resumedOnce) load() else resumedOnce = true
    }

    fun load() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatchingCancellable {
                coroutineScope {
                    val queue = async { runCatchingCancellable { api.nodeQueue(slug, limit = 50).items }.getOrNull() }
                    val category = async {
                        runCatchingCancellable { client.nodeDirectory().firstOrNull { it.slug == slug }?.id }.getOrNull()
                    }
                    val manage = api.nodeManage(slug)
                    // Both are owner-level: a node moderator would only collect 403s.
                    val access = if (manage.canEdit) async { runCatchingCancellable { api.nodeAccess(slug) }.getOrNull() } else null
                    val channels = if (manage.canEdit) async { runCatchingCancellable { api.channels(slug) }.getOrNull() } else null
                    val items = queue.await().orEmpty()
                    val reports = items.count { it.kind in REPORT_KINDS }
                    NodeAdminState(
                        manage = manage,
                        pending = items.size - reports,
                        reports = reports,
                        access = access?.await(),
                        channels = channels?.await(),
                        categoryId = category.await(),
                    )
                }
            }.onSuccess { loaded -> _state.value = loaded }
                .onFailure { error -> _state.update { it.copy(loading = false, error = error) } }
        }
    }

    fun saveRules(rules: List<ApiNodeRule>, onSaved: () -> Unit) {
        if (_state.value.savingRules) return
        _state.update { it.copy(savingRules = true) }
        viewModelScope.launch {
            runCatchingCancellable { api.setNodeRules(slug, rules) }
                .onSuccess {
                    _state.update { it.copy(savingRules = false, manage = it.manage?.copy(rules = rules)) }
                    onSaved()
                    _rulesSaved.tryEmit(Unit)
                }
                .onFailure { error ->
                    _state.update { it.copy(savingRules = false) }
                    ToastCenter.showError(error)
                }
        }
    }
}

private enum class NodeSheet { Moderators, Rules, Access }

@Composable
fun NodeAdminScreen(slug: String, navigator: Navigator) {
    val viewModel: NodeAdminViewModel = viewModel(key = "node-admin-$slug") { NodeAdminViewModel(slug) }
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val resources = androidx.compose.ui.platform.LocalResources.current
    var sheet by remember { mutableStateOf<NodeSheet?>(null) }
    val manageUrl = "${DiscourseConfig.BASE_URL}/n/$slug/manage"

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    LaunchedEffect(viewModel) {
        viewModel.rulesSaved.collect { snackbar.showSnackbar(resources.getString(R.string.mod_node_rules_saved)) }
    }

    Box(Modifier.fillMaxSize()) {
        ModScaffold(
            title = stringResource(R.string.mod_node_title, slug),
            onBack = navigator::back,
            actions = {
                HeaderIconButton(Lucide.ExternalLink, stringResource(R.string.mod_node_open_web)) {
                    navigator.openBrowser(manageUrl)
                }
            },
        ) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Nocturne.surface)) {
                val manage = state.manage
                val error = state.error
                when {
                    manage == null && error != null -> EmptyStateView(
                        Lucide.CircleAlert,
                        stringResource(R.string.mod_node_load_failed),
                        detail = stringResource((error as? DiscourseError)?.messageRes ?: R.string.error_action_failed),
                        retryLabel = stringResource(R.string.common_retry),
                        onRetry = viewModel::load,
                    )
                    manage == null -> NodelocLoader(Modifier.align(Alignment.Center), height = 40.dp)
                    else -> NodeAdminBody(
                        slug = slug,
                        state = state,
                        manage = manage,
                        navigator = navigator,
                        manageUrl = manageUrl,
                        onSheet = { sheet = it },
                    )
                }
            }
        }
        DashSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    val manage = state.manage ?: return
    when (sheet) {
        NodeSheet.Moderators -> NodeAdminModeratorsSheet(manage.moderators, onOpen = navigator::openProfile) { sheet = null }
        NodeSheet.Rules -> NodeAdminRulesSheet(
            rules = manage.rules,
            editable = manage.canEdit,
            saving = state.savingRules,
            onSave = { rules -> viewModel.saveRules(rules) { sheet = null } },
            onDismiss = { sheet = null },
        )
        NodeSheet.Access -> NodeAdminAccessSheet(
            access = state.access ?: ApiNodeAccess(),
            onEditOnWeb = {
                sheet = null
                navigator.openBrowser(manageUrl)
            },
            onDismiss = { sheet = null },
        )
        null -> Unit
    }
}

@Composable
private fun NodeAdminBody(
    slug: String,
    state: NodeAdminState,
    manage: ApiNodeManage,
    navigator: Navigator,
    manageUrl: String,
    onSheet: (NodeSheet) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        NodeHeader(slug, manage)

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NodeTile(Lucide.Hourglass, state.pending, stringResource(R.string.mod_dash_tile_pending), Nocturne.warningBg, Nocturne.warning, Modifier.weight(1f)) {
                navigator.openReviewQueue(slug)
            }
            NodeTile(Lucide.Flag, state.reports, stringResource(R.string.mod_dash_tile_reports), Nocturne.dangerBg, Nocturne.danger, Modifier.weight(1f)) {
                navigator.openReviewQueue(slug)
            }
        }

        Column {
            DashSectionLabel(stringResource(R.string.mod_node_section_node))
            // Settings have no editing route the app could honour field by
            // field; the web manage page is where they are changed.
            DashListRow(
                Lucide.Info,
                stringResource(R.string.mod_node_basic),
                trailingText = stringResource(R.string.mod_node_basic_detail),
                onClick = { navigator.openBrowser(manageUrl) },
            )
            state.categoryId?.let { categoryId ->
                // There is no node-pins route: pins are per topic, made in the
                // reader. The node page is where they are seen and changed.
                DashListRow(
                    Lucide.Pin,
                    stringResource(R.string.mod_node_pins),
                    trailingText = stringResource(R.string.mod_node_pins_detail),
                    onClick = { navigator.openNode(categoryId, slug) },
                )
            }
            DashListRow(
                Lucide.ShieldCheck,
                stringResource(R.string.mod_node_team),
                trailingText = stringResource(R.string.mod_node_team_count, manage.moderators.size),
                onClick = { onSheet(NodeSheet.Moderators) },
            )
            DashListRow(
                Lucide.ListChecks,
                stringResource(R.string.mod_node_rules),
                trailingText = stringResource(R.string.mod_node_rules_count, manage.rules.size),
                onClick = { onSheet(NodeSheet.Rules) },
            )
        }

        if (manage.canEdit) {
            Column {
                DashSectionLabel(stringResource(R.string.mod_node_section_extend))
                DashListRow(
                    Lucide.Key,
                    stringResource(R.string.mod_node_access),
                    supporting = accessSummary(state.access),
                    onClick = { onSheet(NodeSheet.Access) },
                )
                DashListRow(
                    Lucide.Rss,
                    stringResource(R.string.mod_node_channels),
                    supporting = channelsSummary(state.channels),
                    onClick = { navigator.openNodeChannels(slug) },
                )
            }
        }

        DashListRow(
            Lucide.ScrollText,
            stringResource(R.string.mod_dash_tool_log),
            onClick = { navigator.openAuditLog(slug) },
        )
    }
}

@Composable
private fun NodeHeader(slug: String, manage: ApiNodeManage) {
    val node = manage.node
    val letter = node.iconText.ifBlank { node.name.ifBlank { slug } }
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .dashCard(Nocturne.bg, corner = 14)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val logo = DiscourseConfig.absoluteUrl(node.logoUrl)
        if (logo != null) {
            RemoteAvatar(logo, letter, size = 44.dp)
        } else {
            Avatar(letter, size = 44.dp, bg = Nocturne.accent, fg = Nocturne.bg)
        }
        Column(Modifier.weight(1f)) {
            Text(
                node.name.ifBlank { stringResource(R.string.mod_dash_node_slug, slug) },
                style = Type.heading(17, FontWeight.Bold),
                color = Nocturne.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val role = when (manage.role) {
                "owner" -> stringResource(R.string.mod_node_role_owner)
                "moderator" -> stringResource(R.string.mod_node_role_moderator)
                else -> stringResource(R.string.mod_node_role_staff)
            }
            Text(
                listOf(
                    role,
                    stringResource(R.string.mod_node_moderators_count, manage.moderators.size),
                    stringResource(R.string.mod_node_members_count, DiscourseFormat.count(node.memberCount)),
                ).joinToString(" · "),
                style = Type.body(13),
                color = Nocturne.textSecondary,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun NodeTile(
    icon: ImageVector,
    value: Int,
    label: String,
    ground: Color,
    ink: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier.dashCard(Nocturne.bg, corner = 14).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DashIconWell(icon, ground, ink, size = 36, corner = 10)
        Column {
            Text(value.toString(), style = Type.heading(22, FontWeight.Bold), color = Nocturne.text)
            Text(label, style = Type.body(12), color = Nocturne.textSecondary)
        }
    }
}

@Composable
private fun accessSummary(access: ApiNodeAccess?): String? {
    access ?: return null
    return if (access.entries.isEmpty()) stringResource(R.string.mod_node_access_open)
    else stringResource(R.string.mod_node_access_listed, access.entries.size)
}

@Composable
private fun channelsSummary(list: ApiChannelList?): String? {
    list ?: return null
    if (list.channels.isEmpty()) return stringResource(R.string.mod_node_channels_none)
    val kinds = list.channels.map { channelKindLabel(it.kind) }.distinct().joinToString(" / ")
    val failing = list.channels.count { it.state == ChannelState.Error }
    val pulled = stringResource(R.string.mod_node_channels_kinds, kinds)
    return if (failing > 0) "$pulled · ${stringResource(R.string.mod_node_channels_failing, failing)}" else pulled
}

@Composable
fun NodeChannelsScreen(slug: String, navigator: Navigator) {
    NodeAdminChannels(slug, navigator)
}
