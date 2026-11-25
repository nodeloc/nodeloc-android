package com.nodeloc.app.feature.apps

import com.nodeloc.app.core.util.runCatchingCancellable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.LayoutGrid
import com.nodeloc.app.MiniAppActivity
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FilterPill
import com.nodeloc.app.core.design.FilterPillRow
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.PrimaryButton
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.TagChip
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.html.PostContentView
import com.nodeloc.app.core.html.PostHtmlParser
import com.nodeloc.app.core.model.DirectoryApp
import com.nodeloc.app.core.network.ApiDirectoryApp
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.network.PersonalApi
import com.nodeloc.app.feature.feed.RowsSkeleton
import com.nodeloc.app.feature.moderation.ModScaffold
import com.nodeloc.app.feature.nav.Navigator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Which slice of the directory is showing. [Recent] is not a server kind: it
 * is the account's own play history, and only offered when there is one.
 */
enum class AppFilter(val kind: String?) { All(null), Games("game"), Tools("applet"), Bots("bot"), Recent(null) }

data class AppDirectoryState(
    val filter: AppFilter = AppFilter.All,
    val apps: List<ApiDirectoryApp> = emptyList(),
    /** Per-kind totals; a chip whose kind has none is not drawn. */
    val counts: Map<String, Int> = emptyMap(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val error: Throwable? = null,
)

class AppsViewModel : ViewModel() {
    private val client = ServiceLocator.get.client
    private val personal = PersonalApi(client.api)

    private val _directory = MutableStateFlow(AppDirectoryState())
    val directory: StateFlow<AppDirectoryState> = _directory.asStateFlow()

    private val _detail = MutableStateFlow<DirectoryApp?>(null)
    val detail: StateFlow<DirectoryApp?> = _detail.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Null until the host topic has been read; the sandbox cannot open before then. */
    private val _installId = MutableStateFlow<Int?>(null)
    val installId: StateFlow<Int?> = _installId.asStateFlow()

    private var pageJob: Job? = null

    fun loadDirectory(force: Boolean = false) {
        val current = _directory.value
        if (!force && (current.apps.isNotEmpty() || current.loading)) return
        select(current.filter)
    }

    /** A chip switch starts over at page 0; a page in flight for the old one is dropped. */
    fun select(filter: AppFilter) {
        pageJob?.cancel()
        _directory.value = _directory.value.copy(filter = filter, apps = emptyList(), page = 0, hasMore = false, loading = false, error = null)
        if (filter == AppFilter.Recent) return
        fetch(filter, 0)
    }

    fun loadMore() {
        val current = _directory.value
        if (current.loading || !current.hasMore || current.filter == AppFilter.Recent) return
        fetch(current.filter, current.page + 1)
    }

    private fun fetch(filter: AppFilter, page: Int) {
        _directory.value = _directory.value.copy(loading = true, error = null)
        pageJob = viewModelScope.launch {
            runCatchingCancellable { personal.appDirectory(filter.kind, page) }
                .onSuccess { result ->
                    val apps = (if (page == 0) emptyList() else _directory.value.apps) + result.apps
                    _directory.value = _directory.value.copy(
                        apps = apps.distinctBy { it.id },
                        // Every page carries the per-kind totals; they describe the
                        // whole directory, so whichever page came last is right.
                        counts = result.counts.ifEmpty { _directory.value.counts },
                        page = page,
                        hasMore = apps.size < result.total && result.apps.isNotEmpty(),
                        loading = false,
                    )
                }
                .onFailure { _directory.value = _directory.value.copy(loading = false, error = it) }
        }
    }

    fun loadApp(slug: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _installId.value = null
            val app = runCatchingCancellable { client.app(slug) }.getOrNull()?.directoryApp
            _detail.value = app
            _isLoading.value = false
            _installId.value = app?.let { resolveInstallId(it) }
        }
    }

    /**
     * The sandbox URL needs the *install* id, and no apps endpoint returns
     * one: it exists only as `data-app-install` in the host topic's first
     * post, which is what the web client reads too. Passing the app's own id
     * instead lands on a 404.
     */
    private suspend fun resolveInstallId(app: DirectoryApp): Int? {
        val topicId = app.hostTopicId ?: return null
        val cooked = runCatchingCancellable { client.topic(topicId) }.getOrNull()
            ?.postStream?.posts?.firstOrNull()?.cooked ?: return null
        return INSTALL_ID.find(cooked)?.groupValues?.get(1)?.toIntOrNull()
    }

    fun webviewUrl(installId: Int) = client.appWebviewUrl(installId)
}

@Composable
fun AppsScreen(navigator: Navigator) {
    val viewModel: AppsViewModel = viewModel()
    val state by viewModel.directory.collectAsState()
    val me by ServiceLocator.get.session.currentUser.collectAsState()
    val recentApps = me?.recentApps.orEmpty()
    val gridState = rememberLazyGridState()

    LaunchedEffect(Unit) { viewModel.loadDirectory() }

    // Paged at 24: the next page is asked for as the last cards come into view.
    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= gridState.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, state.apps.size) { if (nearEnd) viewModel.loadMore() }

    ModScaffold(stringResource(R.string.apps2_title), navigator::back) {
        FilterPillRow {
            FilterPill(stringResource(R.string.apps2_filter_all), state.filter == AppFilter.All) { viewModel.select(AppFilter.All) }
            if ((state.counts["game"] ?: 0) > 0) {
                FilterPill(stringResource(R.string.apps2_filter_games), state.filter == AppFilter.Games) { viewModel.select(AppFilter.Games) }
            }
            if ((state.counts["applet"] ?: 0) > 0) {
                FilterPill(stringResource(R.string.apps2_filter_tools), state.filter == AppFilter.Tools) { viewModel.select(AppFilter.Tools) }
            }
            if ((state.counts["bot"] ?: 0) > 0) {
                FilterPill(stringResource(R.string.apps2_filter_bots), state.filter == AppFilter.Bots) { viewModel.select(AppFilter.Bots) }
            }
            if (recentApps.isNotEmpty()) {
                FilterPill(stringResource(R.string.apps2_filter_recent), state.filter == AppFilter.Recent) { viewModel.select(AppFilter.Recent) }
            }
        }

        val cards: List<ApiDirectoryApp> = if (state.filter == AppFilter.Recent) {
            recentApps.map { ApiDirectoryApp(id = it.id, slug = it.slug, name = it.name, logoUrl = it.logoUrl) }
        } else {
            state.apps
        }

        when {
            state.loading && cards.isEmpty() -> RowsSkeleton()
            state.error != null && cards.isEmpty() -> EmptyStateView(
                icon = Lucide.CircleAlert,
                title = stringResource(R.string.apps2_load_failed),
                retryLabel = stringResource(R.string.common_retry),
                onRetry = { viewModel.loadDirectory(force = true) },
            )
            cards.isEmpty() -> EmptyStateView(Lucide.LayoutGrid, stringResource(R.string.apps_empty))
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                modifier = Modifier.fillMaxSize().background(Nocturne.surface),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp)) {
                        Text(
                            stringResource(if (state.filter == AppFilter.Recent) R.string.apps2_section_recent else R.string.apps2_section_all),
                            style = Type.body(13, FontWeight.SemiBold),
                            color = Nocturne.textSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        // The directory is ordered by installs, so the label
                        // states the order the server actually used.
                        if (state.filter != AppFilter.Recent) {
                            Text(stringResource(R.string.apps2_by_popularity), style = Type.body(13, FontWeight.SemiBold), color = Nocturne.accent)
                        }
                    }
                }
                items(cards, key = { it.id }) { app -> AppCard(app) { navigator.openApp(app.slug) } }
                if (state.loading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            NodelocLoader(height = 20.dp, tint = Nocturne.accent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppCard(app: ApiDirectoryApp, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Nocturne.bg)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RemoteAvatar(DiscourseConfig.absoluteUrl(app.logoUrl), app.name.take(1), variant = app.id % 2, size = 48.dp, cornerRadius = 14.dp)
        Text(app.name, style = Type.body(15, FontWeight.SemiBold), color = Nocturne.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        app.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = Type.body(12), color = Nocturne.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        // Installs, not players: the directory counts where an app was placed,
        // and "N playing" would claim a number nobody keeps.
        app.installsCount?.takeIf { it > 0 }?.let {
            Text(pluralStringResource(R.plurals.apps_installs, it, it), style = Type.body(11), color = Nocturne.textTertiary)
        }
    }
}

@Composable
fun AppDetailScreen(slug: String, navigator: Navigator) {
    val viewModel: AppsViewModel = viewModel()
    val app by viewModel.detail.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    LaunchedEffect(slug) { viewModel.loadApp(slug) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Nocturne.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = Space.page, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderIconButton(Lucide.ArrowLeft, stringResource(R.string.common_back), onClick = navigator::back)
        }

        if (isLoading && app == null) {
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                NodelocLoader(height = 48.dp)
            }
            return@Column
        }

        val detail = app ?: run {
            // A failed fetch used to leave a back button over an empty page,
            // with nothing said and no way to try again.
            EmptyStateView(
                icon = Lucide.CircleAlert,
                title = stringResource(R.string.apps_load_failed),
                retryLabel = stringResource(R.string.common_retry),
                onRetry = { viewModel.loadApp(slug) },
            )
            return@Column
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.page),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                RemoteAvatar(
                    DiscourseConfig.absoluteUrl(detail.logoUrl),
                    detail.name.take(1),
                    size = 56.dp,
                    cornerRadius = 14.dp,
                )
                Column(Modifier.weight(1f)) {
                    Text(detail.name, style = Type.heading(20, FontWeight.SemiBold), color = Nocturne.text)
                    detail.author?.username?.let {
                        Text("by $it", style = Type.body(12), color = Nocturne.muted(0.45f))
                    }
                }
            }

            detail.description?.let {
                Text(it, style = Type.body(14), color = Nocturne.muted(0.62f))
            }

            if (detail.isWebview) {
                val installId by viewModel.installId.collectAsState()
                val context = LocalContext.current
                PrimaryButton(stringResource(R.string.apps_open), enabled = installId != null) {
                    installId?.let {
                        MiniAppActivity.launch(
                            context = context,
                            url = viewModel.webviewUrl(it),
                            name = detail.name,
                            iconUrl = DiscourseConfig.absoluteUrl(detail.logoUrl),
                        )
                    }
                }
            } else {
                Text(stringResource(R.string.apps_web_only), style = Type.body(12), color = Nocturne.muted(0.45f))
            }

            detail.approvedScopes?.takeIf { it.isNotEmpty() }?.let { scopes ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.apps_permissions), style = Type.body(13, FontWeight.SemiBold), color = Nocturne.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        scopes.take(6).forEach { TagChip(it) }
                    }
                }
            }

            detail.readmeCooked?.let { readme ->
                PostContentView(
                    // remember, because this runs in composition: parsing a
                    // README on every frame is the parser's whole cost paid
                    // over and over on the main thread.
                    content = remember(readme) { PostHtmlParser.parseSync(readme) },
                    baseSize = 14,
                    onOpenLink = { navigator.openBrowser(it) },
                )
            }

            detail.hostTopicId?.let { topicId ->
                PrimaryButton(stringResource(R.string.apps_discussion)) { navigator.openTopic(topicId) }
            }
        }
        Box(Modifier.padding(bottom = 48.dp))
    }
}

/** `data-app-install="123"` in the host topic's cooked HTML. */
private val INSTALL_ID = Regex("data-app-install=\"(\\d+)\"")
