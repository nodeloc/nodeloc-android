package com.nodeloc.app.feature.feed

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Inbox
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.WifiOff
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FieldSurface
import com.nodeloc.app.core.design.FloatingHeaderBar
import com.nodeloc.app.core.design.HeaderGroup
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
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
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ reading a feed

data class CustomFeedState(
    val posts: List<Post> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val loadMoreFailed: Boolean = false,
    val error: Throwable? = null,
)

/** A custom feed's topics: the nodes it names, read as one list, newest activity first. */
class CustomFeedViewModel : ViewModel() {
    private val services = ServiceLocator.get
    private val client = services.client
    private val site = services.siteRepository

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
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                site.siteResponse()
                val latest = client.customFeedTopics(feedId)
                page = 0
                usersById.clear()
                latest.users.orEmpty().forEach { usersById[it.id] = it }
                _state.value = CustomFeedState(
                    posts = latest.topicList.topics.map(::map),
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
                        name,
                        style = Type.heading(17, FontWeight.SemiBold),
                        color = Nocturne.headerText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                }
            },
        )
    }
}

// ------------------------------------------------------------------ making one

data class CreateFeedState(
    val nodes: List<NodeSummary> = emptyList(),
    val selected: Set<String> = emptySet(),
    val loadingNodes: Boolean = true,
    val saving: Boolean = false,
)

class CreateCustomFeedViewModel : ViewModel() {
    private val client = ServiceLocator.get.client
    private val api = CustomFeedApi(client.api)

    private val _state = MutableStateFlow(CreateFeedState())
    val state: StateFlow<CreateFeedState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // The nodes that hold topics (sections only group them), the
            // reader's own first: a feed is most often made of those.
            val nodes = runCatchingCancellable { client.nodeDirectory() }.getOrDefault(emptyList())
                .filter { it.parentId != null }
                .sortedWith(compareByDescending<com.nodeloc.app.core.network.ApiNode> { it.joined }.thenByDescending { it.memberCount ?: 0 })
                .map { NodeSummaryFactory.summary(Legacy.category(it)) }
            _state.value = _state.value.copy(nodes = nodes, loadingNodes = false)
        }
    }

    fun toggle(slug: String) {
        val selected = _state.value.selected
        _state.value = _state.value.copy(
            selected = when {
                slug in selected -> selected - slug
                // The server's own ceiling; past it the add would be refused one node at a time.
                selected.size >= MAX_FEED_NODES -> selected
                else -> selected + slug
            },
        )
    }

    /**
     * Creates the feed, then adds its nodes one by one — the API has no way to
     * do both at once. A node that fails is left out rather than failing the
     * whole feed: what was made is still the reader's, and nodes can be added
     * on the web.
     */
    fun create(name: String, public: Boolean, onDone: (Long, String) -> Unit) {
        if (_state.value.saving) return
        _state.value = _state.value.copy(saving = true)
        viewModelScope.launch {
            try {
                val feed = api.create(name.trim(), public)
                _state.value.selected.forEach { slug -> runCatchingCancellable { api.addNode(feed.id, slug) } }
                onDone(feed.id, feed.name.ifEmpty { name.trim() })
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                _state.value = _state.value.copy(saving = false)
                ToastCenter.showError(error)
            }
        }
    }
}

private const val MAX_FEED_NODES = 50

@Composable
fun CreateCustomFeedScreen(navigator: Navigator) {
    val viewModel: CreateCustomFeedViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    var name by remember { mutableStateOf("") }
    var public by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val shown = remember(state.nodes, query) {
        val q = query.trim()
        if (q.isEmpty()) state.nodes
        else state.nodes.filter { it.name.contains(q, ignoreCase = true) || it.slug.contains(q, ignoreCase = true) }
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
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "form") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Space.page),
                    verticalArrangement = Arrangement.spacedBy(Space.s4),
                ) {
                    Text(stringResource(R.string.feed_create_title), style = Type.heading(26, FontWeight.Bold), color = Nocturne.text)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.feed_create_name), style = Type.body(12), color = Nocturne.muted(0.55f))
                        PlainField(name, stringResource(R.string.feed_create_name_hint)) { name = it }
                    }
                }
                SettingsToggleRow(stringResource(R.string.feed_create_public), public) { public = it }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        stringResource(R.string.feed_create_nodes, state.selected.size),
                        style = Type.body(12),
                        color = Nocturne.muted(0.55f),
                    )
                    PlainField(query, stringResource(R.string.feed_create_search), leading = true) { query = it }
                }
            }
            items(shown, key = { it.slug }) { node ->
                val checked = node.slug in state.selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggle(node.slug) }
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
                        onCheckedChange = { viewModel.toggle(node.slug) },
                        colors = CheckboxDefaults.colors(checkedColor = Nocturne.accent, uncheckedColor = Nocturne.neutral400),
                    )
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
                stringResource(R.string.feed_create_save),
                modifier = Modifier.fillMaxWidth(),
                enabled = name.isNotBlank() && !state.saving,
                onClick = { viewModel.create(name, public) { id, feedName -> navigator.replaceWithCustomFeed(id, feedName) } },
            )
        }
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
