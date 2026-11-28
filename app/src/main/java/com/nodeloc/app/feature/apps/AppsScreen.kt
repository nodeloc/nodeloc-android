package com.nodeloc.app.feature.apps

import com.nodeloc.app.core.util.runCatchingCancellable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ripple
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.nodeloc.app.core.design.pressScale
import com.nodeloc.app.core.design.rememberPressSource
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

    private val _games = MutableStateFlow<List<ApiDirectoryApp>>(emptyList())

    /**
     * The strip of playable games above the directory. The web page orders its
     * "daily games" by thirty-day players, which only exists server-rendered;
     * the directory's own first page of games, by installs, is the nearest
     * thing the app can ask for, so the strip makes no "updated daily" claim.
     */
    val games: StateFlow<List<ApiDirectoryApp>> = _games.asStateFlow()

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
        if (force || _games.value.isEmpty()) loadGames()
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

    // A failure leaves the strip out; the directory below still has every game.
    private fun loadGames() {
        viewModelScope.launch {
            runCatchingCancellable { personal.appDirectory("game", 0) }
                .onSuccess { _games.value = it.apps.take(GAME_STRIP_SIZE) }
        }
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

    private val _starting = MutableStateFlow<Int?>(null)
    /** The game whose install is being looked up, for its card's spinner. */
    val starting: StateFlow<Int?> = _starting.asStateFlow()

    /**
     * 开始 starts the game itself, as iOS does, rather than opening the post
     * it is placed in; the discussion moves to the game's menu. The install
     * id lives only in that post's markup, so it is read from there first.
     * Without one — placed nowhere public, or the post is unreadable — the
     * game's own page is the way in.
     */
    fun startGame(game: ApiDirectoryApp, onLaunch: (url: String, topicId: Int) -> Unit, onFallback: () -> Unit) {
        val home = game.homeUrl?.let { GAME_HOME.find(it) }
        val topicId = home?.groupValues?.get(1)?.toIntOrNull()
        if (topicId == null) {
            onFallback()
            return
        }
        val postNumber = home.groupValues[2].toIntOrNull()
        if (_starting.value != null) return
        _starting.value = game.id
        viewModelScope.launch {
            val posts = runCatchingCancellable { client.topic(topicId) }.getOrNull()?.postStream?.posts.orEmpty()
            val post = posts.firstOrNull { it.postNumber == postNumber } ?: posts.firstOrNull()
            val installId = post?.cooked?.let { INSTALL_ID.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            _starting.value = null
            if (installId != null) onLaunch(client.appWebviewUrl(installId), topicId) else onFallback()
        }
    }
}

@Composable
fun AppsScreen(navigator: Navigator) {
    val viewModel: AppsViewModel = viewModel()
    val state by viewModel.directory.collectAsState()
    val me by ServiceLocator.get.session.currentUser.collectAsState()
    val recentApps = me?.recentApps.orEmpty()
    val gridState = rememberLazyGridState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.loadDirectory() }

    // Paged at 24: the next page is asked for as the last cards come into view.
    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= gridState.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, state.apps.size) { if (nearEnd) viewModel.loadMore() }

    val games by viewModel.games.collectAsState()
    val chips: @Composable () -> Unit = {
        FilterPillRow(contentPadding = PaddingValues(top = 4.dp, bottom = 4.dp)) {
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
    }

    // The bar carries only the way back; the page names itself in its heading.
    ModScaffold("", navigator::back) {
        val cards: List<ApiDirectoryApp> = if (state.filter == AppFilter.Recent) {
            recentApps.map { ApiDirectoryApp(id = it.id, slug = it.slug, name = it.name, logoUrl = it.logoUrl) }
        } else {
            state.apps
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            modifier = Modifier.fillMaxSize().background(Nocturne.bg),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.apps2_title), style = Type.heading(24, FontWeight.Bold), color = Nocturne.text)
            }
            if (games.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    GameStrip(games) { game ->
                        viewModel.startGame(
                            game,
                            onLaunch = { url, topicId ->
                                MiniAppActivity.launch(
                                    context = context,
                                    url = url,
                                    name = game.name,
                                    iconUrl = DiscourseConfig.absoluteUrl(game.logoUrl),
                                    discussionUrl = "${DiscourseConfig.BASE_URL}/t/$topicId",
                                )
                            },
                            onFallback = { navigator.openApp(game.slug) },
                        )
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { chips() }
            when {
                state.loading && cards.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { RowsSkeleton() }
                state.error != null && cards.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyStateView(
                        icon = Lucide.CircleAlert,
                        title = stringResource(R.string.apps2_load_failed),
                        retryLabel = stringResource(R.string.common_retry),
                        onRetry = { viewModel.loadDirectory(force = true) },
                    )
                }
                cards.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyStateView(Lucide.LayoutGrid, stringResource(R.string.apps_empty))
                }
                else -> {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(if (state.filter == AppFilter.Recent) R.string.apps2_section_recent else R.string.apps2_section_all),
                                style = Type.heading(17, FontWeight.Bold),
                                color = Nocturne.text,
                                modifier = Modifier.weight(1f),
                            )
                            // The directory is ordered by installs, so the label
                            // states the order the server actually used.
                            if (state.filter != AppFilter.Recent) {
                                Text(stringResource(R.string.apps2_by_popularity), style = Type.body(12), color = Nocturne.textSecondary)
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
}

/** How many games the strip shows — the web page's own count. */
private const val GAME_STRIP_SIZE = 8

/** `/t/<topic>?post=<n>` from a directory entry's `home_url`. */
private val GAME_HOME = Regex("/t/(\\d+)(?:\\?post=(\\d+))?")

/**
 * The board's horizontally swiped game cards. Each takes one of four tinted
 * grounds by app id, as the web page assigns them, so a game keeps its colour
 * between visits.
 */
@Composable
private fun GameStrip(games: List<ApiDirectoryApp>, onStart: (ApiDirectoryApp) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.apps2_games_section), style = Type.heading(17, FontWeight.Bold), color = Nocturne.text)
        // The grid's side padding would clip the strip at the margins; the
        // row reaches the screen edges and pads its own content instead.
        Row(
            Modifier
                .layout { measurable, constraints ->
                    val bleed = 16.dp.roundToPx()
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = constraints.maxWidth + bleed * 2))
                    layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
                }
                .horizontalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            games.forEach { game -> GameCard(game) { onStart(game) } }
        }
    }
}

@Composable
private fun GameCard(game: ApiDirectoryApp, onStart: () -> Unit) {
    val (ground, ink) = when (game.id % 4) {
        0 -> Nocturne.warningBg to Nocturne.warning
        1 -> Nocturne.infoBg to Nocturne.info
        2 -> Nocturne.accent100 to Nocturne.accent700
        else -> Nocturne.dangerBg to Nocturne.danger
    }
    Column(
        Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ground)
            .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val logo = DiscourseConfig.absoluteUrl(game.logoUrl)
        if (logo.isNullOrEmpty()) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(ink), contentAlignment = Alignment.Center) {
                Text(game.name.take(1), style = Type.heading(30, FontWeight.Bold), color = ground)
            }
        } else {
            RemoteAvatar(logo, game.name.take(1), size = 72.dp)
        }
        Text(
            game.name,
            style = Type.heading(16, FontWeight.Bold),
            color = Nocturne.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            game.description.orEmpty(),
            style = Type.body(13),
            color = Nocturne.textSecondary,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
        Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(1.dp).background(Nocturne.text.copy(alpha = 0.12f)))
        // Installs, not players: the thirty-day player count is only on the
        // server-rendered page, and "N playing" would claim a number this
        // client never sees.
        Text(
            game.installsCount?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.apps_installs, it, it) }.orEmpty(),
            style = Type.body(12, FontWeight.Bold),
            color = Nocturne.text,
            maxLines = 1,
            minLines = 1,
        )
        val press = rememberPressSource()
        val label = stringResource(R.string.apps2_start_a11y, game.name)
        Box(
            Modifier
                .padding(top = 10.dp)
                .pressScale(press)
                .fillMaxWidth()
                .height(36.dp)
                .clip(CircleShape)
                .background(Nocturne.text)
                .clickable(interactionSource = press, indication = ripple(), role = Role.Button, onClick = onStart)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.apps2_start), style = Type.body(13, FontWeight.Bold), color = Nocturne.bg)
        }
    }
}

@Composable
private fun AppCard(app: ApiDirectoryApp, onClick: () -> Unit) {
    val press = rememberPressSource()
    Column(
        Modifier
            .pressScale(press)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Nocturne.divider, RoundedCornerShape(16.dp))
            .clickable(interactionSource = press, indication = ripple(), onClick = onClick)
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
                            discussionUrl = detail.hostTopicId?.let { topic -> "${DiscourseConfig.BASE_URL}/t/$topic" },
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
