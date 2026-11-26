package com.nodeloc.app.feature.feed

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Inbox
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.WifiOff
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.ConfirmDialog
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FieldSurface
import com.nodeloc.app.core.design.FloatingHeaderBar
import com.nodeloc.app.core.design.HeaderGroup
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.OnReachedEnd
import com.nodeloc.app.core.design.PrimaryButton
import com.nodeloc.app.core.design.SettingsToggleRow
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.floatingHeaderInset
import com.nodeloc.app.core.design.isScrolledPastTop
import com.nodeloc.app.core.design.requestScrollToTop
import com.nodeloc.app.core.model.DiscourseUser
import com.nodeloc.app.core.model.NodeReadingMode
import com.nodeloc.app.core.model.NodeSummary
import com.nodeloc.app.core.model.Post
import com.nodeloc.app.core.model.VoteDirection
import com.nodeloc.app.core.network.ApiNode
import com.nodeloc.app.core.network.ApiNodePage
import com.nodeloc.app.core.network.CustomFeedApi
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.network.Legacy
import com.nodeloc.app.core.network.isOfflineError
import com.nodeloc.app.core.util.FeedMapper
import com.nodeloc.app.core.util.TopicVoting
import com.nodeloc.app.core.util.rethrowIfCancellation
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.feature.nav.Navigator
import com.nodeloc.app.feature.node.NodeMark
import com.nodeloc.app.feature.node.NodeSummaryFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ reading a feed

data class CustomFeedState(
    val posts: List<Post> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val loadMoreFailed: Boolean = false,
    val error: Throwable? = null,
    /** The name as the server has it now; null until fetched, when the route's copy stands in. */
    val title: String? = null,
    /** Only the owner gets the edit button. */
    val mine: Boolean = false,
)

/** A custom feed's topics: the nodes it names, read as one list, newest activity first. */
class CustomFeedViewModel : ViewModel() {
    private val services = ServiceLocator.get
    private val client = services.client
    private val site = services.siteRepository
    private val api = CustomFeedApi(client.api)

    private val _state = MutableStateFlow(CustomFeedState())
    val state: StateFlow<CustomFeedState> = _state.asStateFlow()

    val readingMode: StateFlow<NodeReadingMode> = services.preferences.readingMode(services.session.isSignedIn)
        .stateIn(viewModelScope, SharingStarted.Eagerly, NodeReadingMode.default(services.session.isSignedIn.value))

    private var feedId = 0L
    private var page = 0
    private val usersById = linkedMapOf<Int, DiscourseUser>()

    fun bind(id: Long) {
        if (feedId == id) return
        feedId = id
        // The first value is the current revision: that is the initial load.
        // Every later one is an edit made in the editor over this screen.
        viewModelScope.launch { CustomFeedApi.revision.collect { load() } }
    }

    private fun loadInfo() {
        viewModelScope.launch {
            runCatchingCancellable { api.feed(feedId) }.onSuccess { feed ->
                _state.value = _state.value.copy(title = feed.name.ifEmpty { null }, mine = feed.mine)
            }
        }
    }

    fun load() {
        loadInfo()
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                site.siteResponse()
                val latest = client.customFeedTopics(feedId)
                page = 0
                usersById.clear()
                latest.users.orEmpty().forEach { usersById[it.id] = it }
                _state.value = _state.value.copy(
                    posts = latest.topicList.topics.map(::map),
                    isLoadingMore = false,
                    loadMoreFailed = false,
                    error = null,
                    isLoading = false,
                    hasMore = latest.topicList.moreTopicsUrl != null,
                )
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                _state.value = _state.value.copy(isLoading = false, error = error)
            }
        }
    }

    /** As the home feed's: a no-op while a page is in flight, and after a failure only an explicit retry. */
    fun loadMore(retry: Boolean = false) {
        val current = _state.value
        if (!current.hasMore || current.isLoadingMore || current.isLoading) return
        if (current.loadMoreFailed && !retry) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoadingMore = true, loadMoreFailed = false)
            try {
                val latest = client.customFeedTopics(feedId, page + 1)
                page += 1
                latest.users.orEmpty().forEach { usersById[it.id] = it }
                val existing = _state.value.posts.mapTo(mutableSetOf()) { it.id }
                val fresh = latest.topicList.topics.filterNot { it.id in existing }.map(::map)
                _state.value = _state.value.copy(
                    posts = _state.value.posts + fresh,
                    isLoadingMore = false,
                    hasMore = latest.topicList.moreTopicsUrl != null && fresh.isNotEmpty(),
                )
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                _state.value = _state.value.copy(isLoadingMore = false, loadMoreFailed = true)
            }
        }
    }

    fun vote(topicId: Int, tapped: VoteDirection, face: String? = null) {
        TopicVoting.cast(
            scope = viewModelScope,
            client = client,
            topicId = topicId,
            tapped = tapped,
            face = face,
            current = { _state.value.posts },
            publish = { _state.value = _state.value.copy(posts = it) },
        )
    }

    private fun map(topic: com.nodeloc.app.core.model.TopicListItem): Post =
        FeedMapper.post(topic, usersById, site.category(topic.categoryId))
}

@Composable
fun CustomFeedScreen(feedId: Long, name: String, navigator: Navigator) {
    val viewModel: CustomFeedViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val mode by viewModel.readingMode.collectAsState()
    val menu = rememberPostRowMenuHost(navigator)
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(feedId) { viewModel.bind(feedId) }
    listState.OnReachedEnd { viewModel.loadMore() }
    menu.Dialogs()

    Box(Modifier.fillMaxSize().background(Nocturne.bg)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = floatingHeaderInset, bottom = 24.dp),
        ) {
            when {
                state.isLoading && state.posts.isEmpty() -> item(key = "skeleton") { FeedSkeleton(mode) }
                state.posts.isEmpty() -> item(key = "empty") {
                    Box(Modifier.fillParentMaxHeight(0.75f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val offline = state.error?.isOfflineError() == true
                        EmptyStateView(
                            icon = if (offline) Lucide.WifiOff else Lucide.Inbox,
                            title = stringResource(
                                when {
                                    offline -> R.string.error_offline
                                    state.error != null -> R.string.error_action_failed
                                    else -> R.string.feed_empty
                                },
                            ),
                            retryLabel = if (state.error != null) stringResource(R.string.common_retry) else null,
                            onRetry = viewModel::load,
                        )
                    }
                }
            }
            items(state.posts, key = { it.id }) { post ->
                PostRow(
                    post = post,
                    more = menu.actionsFor(post),
                    mode = mode,
                    onOpen = { navigator.openTopic(post.id) },
                    onOpenAuthor = { navigator.openProfile(it) },
                    onShare = { navigator.share(context, "${DiscourseConfig.BASE_URL}/t/${post.id}", post.title) },
                    onVote = { direction, face -> viewModel.vote(post.id, direction, face) },
                )
            }
            if (state.posts.isNotEmpty()) item(key = "footer") {
                TopicListFooter(
                    count = state.posts.size,
                    hasMore = state.hasMore,
                    isLoadingMore = state.isLoadingMore,
                    failed = state.loadMoreFailed,
                    onLoadMore = { viewModel.loadMore(retry = true) },
                    onBackToTop = { listState.requestScrollToTop() },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        FloatingHeaderBar(
            scrolled = listState.isScrolledPastTop(threshold = 0),
            leading = { HeaderIconButton(Lucide.ArrowLeft, stringResource(R.string.common_back), onClick = navigator::back) },
            center = {
                HeaderGroup {
                    Text(
                        state.title ?: name,
                        style = Type.heading(17, FontWeight.SemiBold),
                        color = Nocturne.headerText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                }
            },
            trailing = {
                if (state.mine) {
                    HeaderIconButton(Lucide.Pencil, stringResource(R.string.feed_edit_title)) {
                        navigator.openEditCustomFeed(feedId)
                    }
                }
            },
        )
    }
}

// ------------------------------------------------------------------ making or editing one

data class CreateFeedState(
    /** What is ticked, by slug: kept apart from [results], which a new search replaces. */
    val picked: Map<String, NodeSummary> = emptyMap(),
    /**
     * Editing only: the nodes the feed had when opened, listed above the
     * catalogue whether still ticked or not, so one unticked by mistake is
     * where it was to tick again. Ticks made here stay in place in the list.
     */
    val original: List<NodeSummary> = emptyList(),
    /** The current search's pages so far, sections already dropped. */
    val results: List<NodeSummary> = emptyList(),
    val query: String = "",
    val nextCursor: String? = null,
    val loadingNodes: Boolean = true,
    val loadingMore: Boolean = false,
    val nodesFailed: Boolean = false,
    val saving: Boolean = false,
    val deleting: Boolean = false,
    /** Editing only: false until the feed itself has arrived, so the form never offers blank values as its own. */
    val loaded: Boolean = true,
    val name: String = "",
    val public: Boolean = false,
) {
    val selected: Set<String> get() = picked.keys
}

/** One form for both: [bind] with an id turns it into the editor of that feed. */
class CreateCustomFeedViewModel : ViewModel() {
    private val client = ServiceLocator.get.client
    private val api = CustomFeedApi(client.api)

    private val _state = MutableStateFlow(CreateFeedState())
    val state: StateFlow<CreateFeedState> = _state.asStateFlow()

    private var editId: Long? = null
    private var bound = false

    /** The feed as loaded: the description goes back unchanged, and the nodes are what a save diffs against. */
    private var description = ""
    private var original: Set<String> = emptySet()

    fun bind(id: Long?) {
        if (bound) return
        bound = true
        editId = id
        if (id != null) _state.value = _state.value.copy(loaded = false)
        viewModelScope.launch {
            val feed = id?.let { feedId ->
                runCatchingCancellable { api.feed(feedId) }
                    .onFailure { ToastCenter.showError(it) }
                    .getOrNull()
            }
            if (feed != null) {
                description = feed.description.orEmpty()
                original = feed.nodes.mapTo(linkedSetOf()) { it.slug }
                // The feed names its nodes without logos. The site's own
                // copy of the catalogue, loaded at start, has them; failing
                // that, a search page carrying the node fills one in later.
                val site = ServiceLocator.get.siteRepository
                val picked = feed.nodes.associate { node ->
                    node.slug to NodeSummaryFactory.summary(
                        site.categoryBySlug(node.slug)
                            ?: Legacy.category(ApiNode(id = 0, parentId = 0, slug = node.slug, name = node.name, color = node.color)),
                    )
                }
                _state.value = _state.value.copy(name = feed.name, public = feed.public, picked = picked, original = picked.values.toList(), loaded = true)
            }
            // After the feed, so the first page can fill in its nodes' logos.
            if (_state.value.query.isEmpty()) fetchFirstPage("")
        }
    }

    private var searchJob: Job? = null

    /** Typing searches on the server, a beat after the last key; clearing the box goes back at once. */
    fun setQuery(value: String) {
        if (value == _state.value.query) return
        _state.value = _state.value.copy(query = value)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (value.isNotBlank()) delay(SEARCH_DEBOUNCE_MS)
            loadFirstPage(value)
        }
    }

    fun retryNodes() = if (_state.value.results.isEmpty()) fetchFirstPage(_state.value.query) else loadMore(retry = true)

    private fun fetchFirstPage(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch { loadFirstPage(query) }
    }

    private suspend fun loadFirstPage(query: String) {
        _state.value = _state.value.copy(loadingNodes = true, nodesFailed = false, loadingMore = false)
        try {
            val page = api.nodePage(query, cursor = null)
            publish(page, replace = true)
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            _state.value = _state.value.copy(results = emptyList(), nextCursor = null, loadingNodes = false, nodesFailed = true)
        }
    }

    /**
     * The next page of the same search. Runs as the search job, so a new
     * query cancels a page still in flight for the old one rather than letting
     * it land in the wrong list.
     */
    fun loadMore(retry: Boolean = false) {
        val current = _state.value
        val cursor = current.nextCursor ?: return
        if (current.loadingNodes || current.loadingMore || (current.nodesFailed && !retry)) return
        searchJob = viewModelScope.launch {
            _state.value = _state.value.copy(loadingMore = true, nodesFailed = false)
            try {
                publish(api.nodePage(current.query, cursor), replace = false)
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                _state.value = _state.value.copy(loadingMore = false, nodesFailed = true)
            }
        }
    }

    private fun publish(page: ApiNodePage, replace: Boolean) {
        // Sections only group nodes; a feed is made of the nodes that hold topics.
        val fresh = page.nodes.filter { it.parentId != null }.map { NodeSummaryFactory.summary(Legacy.category(it)) }
        val now = _state.value
        val known = if (replace) emptySet() else now.results.mapTo(hashSetOf()) { it.slug }
        val results = (if (replace) emptyList() else now.results) + fresh.filterNot { it.slug in known }
        val bySlug = fresh.associateBy { it.slug }
        _state.value = now.copy(
            results = results,
            nextCursor = page.nextCursor?.takeIf { it.isNotEmpty() },
            loadingNodes = false,
            loadingMore = false,
            nodesFailed = false,
            picked = now.picked.mapValues { (slug, node) -> bySlug[slug] ?: node },
            original = now.original.map { bySlug[it.slug] ?: it },
        )
    }

    fun setName(value: String) {
        _state.value = _state.value.copy(name = value)
    }

    fun setPublic(value: Boolean) {
        _state.value = _state.value.copy(public = value)
    }

    fun toggle(node: NodeSummary) {
        val picked = _state.value.picked
        _state.value = _state.value.copy(
            picked = when {
                node.slug in picked -> picked - node.slug
                // The server's own ceiling; past it the add would be refused one node at a time.
                picked.size >= MAX_FEED_NODES -> picked
                else -> picked + (node.slug to node)
            },
        )
    }

    /**
     * Creates or updates the feed, then brings its nodes in line one call at a
     * time; the API has no way to set them all at once. A node that fails is
     * left as it was rather than failing the whole save: the rest still landed.
     */
    fun save(onDone: (Long, String) -> Unit) {
        val current = _state.value
        if (current.saving || current.deleting) return
        val name = current.name.trim()
        _state.value = current.copy(saving = true)
        viewModelScope.launch {
            try {
                val id = editId
                val feed = if (id == null) api.create(name, current.public) else api.update(id, name, description, current.public)
                (current.selected - original).forEach { slug -> runCatchingCancellable { api.addNode(feed.id, slug) } }
                (original - current.selected).forEach { slug -> runCatchingCancellable { api.removeNode(feed.id, slug) } }
                CustomFeedApi.revision.value += 1
                onDone(feed.id, feed.name.ifEmpty { name })
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                _state.value = _state.value.copy(saving = false)
                ToastCenter.showError(error)
            }
        }
    }

    fun delete(onDone: () -> Unit) {
        val id = editId ?: return
        if (_state.value.saving || _state.value.deleting) return
        _state.value = _state.value.copy(deleting = true)
        viewModelScope.launch {
            try {
                api.delete(id)
                onDone()
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                _state.value = _state.value.copy(deleting = false)
                ToastCenter.showError(error)
            }
        }
    }
}

private const val MAX_FEED_NODES = 50
private const val SEARCH_DEBOUNCE_MS = 300L

@Composable
fun CreateCustomFeedScreen(navigator: Navigator, editId: Long? = null) {
    val viewModel: CreateCustomFeedViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    val editing = editId != null
    val searching = state.query.isNotBlank()
    // Without a search the feed's own nodes lead, and the catalogue below
    // leaves them out; with one, the results stand alone, ticks and all.
    val pinned = if (searching) emptyList() else state.original
    val results = remember(state.results, pinned) {
        if (pinned.isEmpty()) state.results else pinned.mapTo(hashSetOf()) { it.slug }.let { slugs -> state.results.filterNot { it.slug in slugs } }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(editId) { viewModel.bind(editId) }
    listState.OnReachedEnd { viewModel.loadMore() }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.feed_delete_title),
            body = stringResource(R.string.feed_delete_body, state.name),
            confirmLabel = stringResource(R.string.feed_delete),
            onConfirm = {
                confirmDelete = false
                viewModel.delete { navigator.leaveDeletedCustomFeed() }
            },
            onDismiss = { confirmDelete = false },
        )
    }

    Column(Modifier.fillMaxSize().background(Nocturne.bg).imePadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = Space.page, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderIconButton(Lucide.ArrowLeft, stringResource(R.string.common_back), onClick = navigator::back)
            Spacer(Modifier.weight(1f))
            if (editing && state.loaded) {
                HeaderIconButton(Lucide.Trash2, stringResource(R.string.feed_delete)) {
                    if (!state.deleting) confirmDelete = true
                }
            }
        }
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "form") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Space.page),
                    verticalArrangement = Arrangement.spacedBy(Space.s4),
                ) {
                    Text(
                        stringResource(if (editing) R.string.feed_edit_title else R.string.feed_create_title),
                        style = Type.heading(26, FontWeight.Bold),
                        color = Nocturne.text,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.feed_create_name), style = Type.body(12), color = Nocturne.muted(0.55f))
                        PlainField(state.name, stringResource(R.string.feed_create_name_hint), onValueChange = viewModel::setName)
                    }
                }
                SettingsToggleRow(stringResource(R.string.feed_create_public), state.public) { viewModel.setPublic(it) }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        stringResource(R.string.feed_create_nodes, state.selected.size),
                        style = Type.body(12),
                        color = Nocturne.muted(0.55f),
                    )
                    PlainField(state.query, stringResource(R.string.feed_create_search), leading = true, onValueChange = viewModel::setQuery)
                }
            }
            // Keys carry the section: the two lists are filtered apart, but a
            // stale frame must not trip the duplicate-key check.
            items(pinned, key = { "picked:" + it.slug }) { node ->
                PickRow(node, checked = node.slug in state.picked) { viewModel.toggle(node) }
            }
            if (state.loadingNodes) {
                item(key = "loading") { NodelocLoader(Modifier.fillMaxWidth().padding(vertical = 16.dp)) }
            } else {
                items(results, key = { "result:" + it.slug }) { node ->
                    PickRow(node, checked = node.slug in state.picked) { viewModel.toggle(node) }
                }
                when {
                    state.loadingMore -> item(key = "more") { NodelocLoader(Modifier.fillMaxWidth().padding(vertical = 8.dp), height = 32.dp) }
                    state.nodesFailed -> item(key = "failed") {
                        Text(
                            stringResource(R.string.common_retry),
                            style = Type.body(14, FontWeight.Medium),
                            color = Nocturne.accent,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().clickable(onClick = viewModel::retryNodes).padding(vertical = 16.dp),
                        )
                    }
                    searching && results.isEmpty() -> item(key = "none") {
                        Text(
                            stringResource(R.string.nodes2_no_match),
                            style = Type.body(14),
                            color = Nocturne.textSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        )
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = Space.page, vertical = 10.dp),
        ) {
            PrimaryButton(
                stringResource(if (editing) R.string.feed_edit_save else R.string.feed_create_save),
                modifier = Modifier.fillMaxWidth(),
                enabled = state.loaded && state.name.isNotBlank() && !state.saving && !state.deleting,
                onClick = {
                    viewModel.save { id, feedName ->
                        if (editing) navigator.back() else navigator.replaceWithCustomFeed(id, feedName)
                    }
                },
            )
        }
    }
}

@Composable
private fun PickRow(node: NodeSummary, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = Space.page, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NodeMark(node, 32.dp)
        Column(Modifier.weight(1f)) {
            Text(node.name, style = Type.body(15, FontWeight.Medium), color = Nocturne.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("n/${node.slug}", style = Type.body(12), color = Nocturne.textSecondary, maxLines = 1)
        }
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = Nocturne.accent, uncheckedColor = Nocturne.neutral400),
        )
    }
}

@Composable
private fun PlainField(value: String, placeholder: String, leading: Boolean = false, onValueChange: (String) -> Unit) {
    FieldSurface {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (leading) Icon(Lucide.Search, null, tint = Nocturne.textSecondary, modifier = Modifier.size(16.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = Type.body(15).copy(color = Nocturne.text),
                cursorBrush = SolidColor(Nocturne.accent),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (value.isEmpty()) Text(placeholder, style = Type.body(15), color = Nocturne.muted(0.3f))
                    inner()
                },
            )
        }
    }
}
