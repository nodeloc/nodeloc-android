package com.nodeloc.app.feature.moderation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CirclePlay
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Film
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Rss
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.Zap
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.PillTone
import com.nodeloc.app.core.design.StatusPill
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.colors
import com.nodeloc.app.core.network.ApiChannel
import com.nodeloc.app.core.network.ApiChannelList
import com.nodeloc.app.core.network.ChannelState
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.network.DiscourseError
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.feature.nav.Navigator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChannelsState(
    val list: ApiChannelList? = null,
    val loading: Boolean = false,
    val error: Throwable? = null,
    val syncing: Set<Long> = emptySet(),
)

sealed interface ChannelEvent {
    data class Synced(val count: Int) : ChannelEvent
    data class Problem(val detail: String) : ChannelEvent
}

class NodeAdminChannelsViewModel(private val slug: String) : ViewModel() {
    private val api = ServiceLocator.get.moderationApi
    private val _state = MutableStateFlow(ChannelsState())
    val state: StateFlow<ChannelsState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<ChannelEvent>(extraBufferCapacity = 2)
    val events: SharedFlow<ChannelEvent> = _events.asSharedFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatchingCancellable { api.channels(slug) }
                .onSuccess { list -> _state.update { it.copy(list = list, loading = false) } }
                .onFailure { error -> _state.update { it.copy(loading = false, error = error) } }
        }
    }

    /** Flipped first, put back if the server says no. */
    fun setEnabled(channel: ApiChannel, enabled: Boolean) {
        replace(channel.id) { it.copy(enabled = enabled) }
        viewModelScope.launch {
            runCatchingCancellable { api.setChannelEnabled(slug, channel, enabled) }
                .onSuccess { saved ->
                    // An update response carries no history: `recent`, `synced`
                    // and `today` come back empty, so only the state is taken.
                    replace(channel.id) {
                        it.copy(
                            enabled = saved.enabled,
                            pausedAt = saved.pausedAt,
                            nextPollAt = saved.nextPollAt,
                            lastError = saved.lastError,
                            lastErrorCode = saved.lastErrorCode,
                            failCount = saved.failCount,
                        )
                    }
                }
                .onFailure { error ->
                    replace(channel.id) { it.copy(enabled = channel.enabled) }
                    ToastCenter.showError(error)
                }
        }
    }

    fun sync(channel: ApiChannel) {
        if (channel.id in _state.value.syncing) return
        _state.update { it.copy(syncing = it.syncing + channel.id) }
        viewModelScope.launch {
            runCatchingCancellable { api.syncChannel(slug, channel.id) }
                .onSuccess { result ->
                    val problem = result.problem
                    _events.tryEmit(
                        if (problem != null) ChannelEvent.Problem(problem.detail?.takeIf { it.isNotBlank() } ?: problem.code)
                        else ChannelEvent.Synced(result.new),
                    )
                    runCatchingCancellable { api.channels(slug) }.getOrNull()?.let { list -> _state.update { it.copy(list = list) } }
                }
                .onFailure { error -> ToastCenter.showError(error) }
            _state.update { it.copy(syncing = it.syncing - channel.id) }
        }
    }

    private fun replace(id: Long, transform: (ApiChannel) -> ApiChannel) {
        _state.update { state ->
            val list = state.list ?: return@update state
            state.copy(list = list.copy(channels = list.channels.map { if (it.id == id) transform(it) else it }))
        }
    }
}

@Composable
internal fun NodeAdminChannels(slug: String, navigator: Navigator) {
    val viewModel: NodeAdminChannelsViewModel = viewModel(key = "node-channels-$slug") { NodeAdminChannelsViewModel(slug) }
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val resources = androidx.compose.ui.platform.LocalResources.current
    val manageUrl = "${DiscourseConfig.BASE_URL}/n/$slug/manage"

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is ChannelEvent.Synced ->
                    if (event.count > 0) resources.getString(R.string.mod_channel_synced, event.count)
                    else resources.getString(R.string.mod_channel_synced_none)
                is ChannelEvent.Problem -> resources.getString(R.string.mod_channel_sync_problem, event.detail)
            }
            snackbar.showSnackbar(message)
        }
    }

    Box(Modifier.fillMaxSize()) {
        ModScaffold(
            title = stringResource(R.string.mod_node_channels),
            onBack = navigator::back,
            actions = {
                // Adding a channel needs the source check and its preview; that flow lives on the web.
                HeaderIconButton(Lucide.Plus, stringResource(R.string.mod_channel_add_web)) { navigator.openBrowser(manageUrl) }
            },
        ) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Nocturne.surface)) {
                val list = state.list
                val error = state.error
                when {
                    list == null && error != null -> EmptyStateView(
                        Lucide.CircleAlert,
                        stringResource(R.string.mod_channel_load_failed),
                        detail = stringResource((error as? DiscourseError)?.messageRes ?: R.string.error_action_failed),
                        retryLabel = stringResource(R.string.common_retry),
                        onRetry = viewModel::load,
                    )
                    list == null -> NodelocLoader(Modifier.align(Alignment.Center), height = 40.dp)
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        item(key = "intro") {
                            Text(
                                stringResource(R.string.mod_channel_intro, slug),
                                style = Type.body(13),
                                color = Nocturne.textSecondary,
                                modifier = Modifier.padding(horizontal = 32.dp),
                            )
                        }
                        if (list.channels.isEmpty()) {
                            item(key = "empty") {
                                EmptyStateView(
                                    Lucide.Rss,
                                    stringResource(R.string.mod_channel_empty),
                                    retryLabel = stringResource(R.string.mod_channel_add_web),
                                    onRetry = { navigator.openBrowser(manageUrl) },
                                )
                            }
                        }
                        items(list.channels, key = { it.id }) { channel ->
                            ChannelCard(
                                channel = channel,
                                pollMinutes = list.pollMinutes,
                                syncing = channel.id in state.syncing,
                                onToggle = { viewModel.setEnabled(channel, it) },
                                onSync = { viewModel.sync(channel) },
                                onEditOnWeb = { navigator.openBrowser(manageUrl) },
                                onOpenTopic = { navigator.openTopic(it.toInt(), null) },
                            )
                        }
                    }
                }
            }
        }
        DashSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/** Platform names are proper nouns and stay as they are in every language. */
internal fun channelKindLabel(kind: String): String = when (kind) {
    "rss" -> "RSS"
    "youtube" -> "YouTube"
    "telegram" -> "Telegram"
    "bilibili" -> "Bilibili"
    "webhook" -> "Webhook"
    else -> kind
}

private fun channelKindIcon(kind: String): ImageVector = when (kind) {
    "youtube" -> Lucide.CirclePlay
    "bilibili" -> Lucide.Film
    "telegram" -> Lucide.Send
    "webhook" -> Lucide.Zap
    else -> Lucide.Rss
}

private fun channelKindTone(kind: String): PillTone = when (kind) {
    "youtube" -> PillTone.Danger
    "bilibili", "telegram" -> PillTone.Info
    "rss" -> PillTone.Warning
    else -> PillTone.Neutral
}

@Composable
private fun ChannelCard(
    channel: ApiChannel,
    pollMinutes: Int,
    syncing: Boolean,
    onToggle: (Boolean) -> Unit,
    onSync: () -> Unit,
    onEditOnWeb: () -> Unit,
    onOpenTopic: (Long) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Nocturne.bg)
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val (ground, ink) = channelKindTone(channel.kind).colors()
            DashIconWell(channelKindIcon(channel.kind), ground, ink, size = 40, corner = 12)
            Column(Modifier.weight(1f)) {
                Text(
                    "${channelKindLabel(channel.kind)} · ${channel.name}",
                    style = Type.body(16, FontWeight.SemiBold),
                    color = Nocturne.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val source = (channel.source ?: channel.siteUrl ?: channel.feedUrl)
                    ?.removePrefix("https://")?.removePrefix("http://")?.removePrefix("www.")
                if (!source.isNullOrBlank()) {
                    Text(source, style = Type.body(13), color = Nocturne.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            when (channel.state) {
                ChannelState.Ok -> StatusPill(stringResource(R.string.mod_channel_state_ok), PillTone.Accent)
                ChannelState.Paused -> StatusPill(stringResource(R.string.mod_channel_state_paused), PillTone.Neutral)
                ChannelState.Error -> StatusPill(stringResource(R.string.mod_channel_state_error), PillTone.Danger)
            }
            Box {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable { menuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.EllipsisVertical, stringResource(R.string.common_more), tint = Nocturne.textSecondary, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = Nocturne.bg) {
                    if (channel.kind != "webhook") {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.mod_channel_sync_now), style = Type.body(15), color = Nocturne.text) },
                            enabled = !syncing,
                            onClick = {
                                menuOpen = false
                                onSync()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(if (channel.enabled) R.string.mod_channel_pause else R.string.mod_channel_resume),
                                style = Type.body(15),
                                color = Nocturne.text,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onToggle(!channel.enabled)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.mod_node_edit_on_web), style = Type.body(15), color = Nocturne.text) },
                        onClick = {
                            menuOpen = false
                            onEditOnWeb()
                        },
                    )
                }
            }
        }

        if (channel.state == ChannelState.Error) {
            Row(
                Modifier
                    .padding(end = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Nocturne.dangerBg)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Lucide.CircleAlert, null, tint = Nocturne.danger, modifier = Modifier.size(16.dp))
                Text(
                    channel.lastError?.takeIf { it.isNotBlank() } ?: channel.lastErrorCode.orEmpty()
                        .ifEmpty { stringResource(R.string.mod_channel_error_unknown) },
                    style = Type.body(13),
                    color = Nocturne.danger,
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (channel.kind != "webhook") {
                    if (syncing) {
                        NodelocLoader(height = 18.dp, tint = Nocturne.danger)
                    } else {
                        Text(
                            stringResource(R.string.common_retry),
                            style = Type.body(13, FontWeight.Bold),
                            color = Nocturne.danger,
                            modifier = Modifier.clickable(onClick = onSync),
                        )
                    }
                }
            }
        }

        if (channel.recent.isNotEmpty()) {
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                channel.recent.forEach { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (item.topicId != null) Modifier.clickable { onOpenTopic(item.topicId) } else Modifier),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(modAgo(item.createdAt), style = Type.body(13), color = Nocturne.textTertiary)
                        Text(
                            item.title.ifBlank { item.error.orEmpty() },
                            style = Type.body(13),
                            color = if (item.status == "failed") Nocturne.danger else Nocturne.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            val footer = when {
                !channel.enabled -> channel.pausedAt?.let { stringResource(R.string.mod_channel_paused_since, modAgo(it)) }
                    ?: stringResource(R.string.mod_channel_state_paused)
                channel.kind == "webhook" -> stringResource(R.string.mod_channel_webhook)
                channel.lastPolledAt != null -> stringResource(R.string.mod_channel_poll_last, pollMinutes, modAgo(channel.lastPolledAt))
                else -> stringResource(R.string.mod_channel_poll, pollMinutes)
            }
            Text(footer, style = Type.body(12), color = Nocturne.textSecondary, modifier = Modifier.weight(1f))
            Switch(
                checked = channel.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Nocturne.bg,
                    checkedTrackColor = Nocturne.accent,
                    checkedBorderColor = Nocturne.accent,
                    uncheckedThumbColor = Nocturne.textTertiary,
                    uncheckedTrackColor = Nocturne.neutral400,
                    uncheckedBorderColor = Nocturne.textTertiary,
                ),
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}
