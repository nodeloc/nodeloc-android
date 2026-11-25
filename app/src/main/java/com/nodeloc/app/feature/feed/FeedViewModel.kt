package com.nodeloc.app.feature.feed

import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.util.rethrowIfCancellation
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.model.NodeSort
import com.nodeloc.app.core.model.DiscourseUser
import com.nodeloc.app.core.model.NodeReadingMode
import com.nodeloc.app.core.model.Post
import com.nodeloc.app.core.model.LatestResponse
import com.nodeloc.app.core.network.servesSnapshot
import com.nodeloc.app.core.model.VoteDirection
import com.nodeloc.app.core.util.FeedMapper
import com.nodeloc.app.core.util.TopicVoting
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FeedUiState(
    val posts: List<Post> = emptyList(),
    /**
     * Which list the feed is showing. The 2026-11 design puts the sorts back on
     * the page as chips; the choice is still stored as the default home, so it
     * survives a restart and the setting shows the same thing.
     */
    val sort: NodeSort = NodeSort.Hot,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    /** The last next-page request failed; the foot of the list offers a retry. */
    val loadMoreFailed: Boolean = false,
    val error: Throwable? = null,
    /**
     * Set while the list on screen is the saved one and the server could not
     * be reached: when it was saved, for the "离线 · 上次更新" line above it.
     */
    val offlineSavedAt: Long? = null,
) {
    val showSkeleton: Boolean get() = isLoading && posts.isEmpty()
    val showEmptyState: Boolean get() = !isLoading && posts.isEmpty()
}

/**
 * The home feed: `latest.json`, paged, mapped onto the shared [Post] row model.
 *
 * A failed load leaves the list empty and surfaces the error so the screen can
 * show the retry state. It never falls back to sample content — on iOS that
 * once had readers convinced they were looking at the real forum.
 */
class FeedViewModel : ViewModel() {
    private val services = ServiceLocator.get
    private val client = services.client
    private val site = services.siteRepository
    private val preferences = services.preferences
    private val snapshots = services.snapshots

    private val _state = MutableStateFlow(FeedUiState())
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    val readingMode: StateFlow<NodeReadingMode> = preferences.readingMode(services.session.isSignedIn)
        // The same fallback as the flow's, so a guest is not shown one frame
        // of the signed-in default before the stored value arrives.
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            NodeReadingMode.default(services.session.isSignedIn.value),
        )

    private var page = 0

    /** Accumulated across pages so later pages' authors still resolve. */
    private val usersById = linkedMapOf<Int, DiscourseUser>()

    private val locallyRead = mutableSetOf<Int>()

    /**
     * Whether the stored choice of home has arrived yet.
     *
     * The screen asks for a load as soon as it is composed, and answering that
     * before the preference is read would fetch whichever list the default
     * happens to be and then fetch again when the real one lands. The first
     * emission does the loading instead.
     */
    private var homeResolved = false

    init {
        viewModelScope.launch {
            preferences.readTopics.collect { locallyRead.addAll(it) }
        }
        // A saved list up because the network was gone is replaced as soon
        // as it is back, not when the reader thinks to pull.
        viewModelScope.launch {
            services.network.online.collect { online ->
                if (online && _state.value.offlineSavedAt != null && !_state.value.isLoading) load()
            }
        }
        viewModelScope.launch {
            preferences.defaultHome.collect { key ->
                val stored = NodeSort.entries.firstOrNull { it.key == key } ?: NodeSort.Hot
                // "Following" kept from a signed-in session would load an
                // empty list for a guest with no way to tell why.
                val chosen = if (stored.requiresAuth && !services.session.isSignedIn.value) NodeSort.Hot else stored
                val first = !homeResolved
                homeResolved = true
                when {
                    first -> {
                        _state.value = _state.value.copy(sort = chosen)
                        if (_state.value.posts.isEmpty()) load()
                    }
                    // Changed in settings while the feed was already up.
                    // Switching list empties the old one: they are not the
                    // same topics.
                    chosen != _state.value.sort -> {
                        _state.value = _state.value.copy(sort = chosen, posts = emptyList(), hasMore = false, offlineSavedAt = null)
                        load()
                    }
                }
            }
        }
    }

    /**
     * A chip on the page and the setting are the same choice: writing the
     * preference is what switches the list, through the collector above, so
     * the two can never disagree.
     */
    fun selectSort(sort: NodeSort) {
        if (sort == _state.value.sort) return
        viewModelScope.launch { preferences.setDefaultHome(sort.key) }
    }

    fun loadIfNeeded() {
        if (!homeResolved) return
        if (_state.value.posts.isEmpty() && !_state.value.isLoading) load()
    }

    fun load() {
        viewModelScope.launch {
            val sort = _state.value.sort
            // Still the saved list if this is a retry over it; the line stays
            // until something fresh replaces what is under it.
            val showingSaved = _state.value.offlineSavedAt
            _state.value = _state.value.copy(isLoading = true, error = null)
            page = 0
            // An empty list starts from the one saved last time, and the
            // network only reconciles it — the skeleton is for a first run.
            // The saved first page has no "more": paging it would mix two
            // moments of the same list.
            var saved: Long? = showingSaved
            if (_state.value.posts.isEmpty()) {
                snapshots.load(snapshotKey(sort), LatestResponse.serializer())?.let { snapshot ->
                    site.warmFromSnapshot()
                    usersById.clear()
                    snapshot.value.users.orEmpty().forEach { usersById[it.id] = it }
                    saved = snapshot.savedAt
                    _state.value = _state.value.copy(posts = snapshot.value.topicList.topics.map { map(it) }, hasMore = false)
                }
            }
            // The category map and the topic list are independent; the feed
            // needs both before a row can name its node.
            val siteJob = async { site.siteResponse() }
            try {
                val latest = client.topics(sort.key)
                siteJob.await()
                snapshots.store(snapshotKey(sort), LatestResponse.serializer(), latest)
                usersById.clear()
                latest.users.orEmpty().forEach { usersById[it.id] = it }
                _state.value = FeedUiState(
                    sort = _state.value.sort,
                    posts = latest.topicList.topics.map { map(it) },
                    isLoading = false,
                    hasMore = latest.topicList.moreTopicsUrl != null,
                )
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                siteJob.cancel()
                // With the saved list up, a dead connection is a line above
                // it, not an error page in its place.
                val offline = saved != null && _state.value.posts.isNotEmpty() && error.servesSnapshot()
                _state.value = _state.value.copy(
                    isLoading = false,
                    hasMore = false,
                    error = if (offline) null else error,
                    offlineSavedAt = if (offline) saved else null,
                )
            }
        }
    }

    /** One saved first page per list: 热门 and 最新 are different topics. */
    private fun snapshotKey(sort: NodeSort) = "feed-${sort.key}"

    suspend fun refresh() {
        page = 0
        val siteRefresh = viewModelScope.async { site.siteResponse() }
        try {
            val latest = client.topics(_state.value.sort.key)
            siteRefresh.await()
            snapshots.store(snapshotKey(_state.value.sort), LatestResponse.serializer(), latest)
            usersById.clear()
            latest.users.orEmpty().forEach { usersById[it.id] = it }
            _state.value = FeedUiState(
                sort = _state.value.sort,
                posts = latest.topicList.topics.map { map(it) },
                hasMore = latest.topicList.moreTopicsUrl != null,
            )
        } catch (error: Throwable) {
            // Cancel first: this async is a child of the scope, not of the
            // caller, so rethrowing ahead of it orphans the request.
            siteRefresh.cancel()
            error.rethrowIfCancellation()
            _state.value = _state.value.copy(error = error)
            // A pull that fails while the old list is still there has no empty
            // state to land in, so the gesture just sprang back and said
            // nothing at all.
            if (_state.value.posts.isNotEmpty()) ToastCenter.showError(error)
        }
    }

    /**
     * Safe to call repeatedly: a no-op while a page is in flight. After a
     * failure only an explicit [retry] tries again — the end-of-list trigger
     * fires on every layout pass, and would otherwise hammer a dead connection.
     */
    fun loadMore(retry: Boolean = false) {
        val current = _state.value
        if (!current.hasMore || current.isLoadingMore || current.isLoading) return
        if (current.loadMoreFailed && !retry) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoadingMore = true, loadMoreFailed = false)
            try {
                val latest = client.topics(_state.value.sort.key, page + 1)
                page += 1
                latest.users.orEmpty().forEach { usersById[it.id] = it }
                val existing = _state.value.posts.mapTo(mutableSetOf()) { it.id }
                val fresh = latest.topicList.topics.filterNot { it.id in existing }.map { map(it) }
                _state.value = _state.value.copy(
                    posts = _state.value.posts + fresh,
                    isLoadingMore = false,
                    hasMore = latest.topicList.moreTopicsUrl != null && fresh.isNotEmpty(),
                )
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                // Keep what is shown; the foot of the list says so and offers a retry.
                _state.value = _state.value.copy(isLoadingMore = false, loadMoreFailed = true)
            }
        }
    }

    /** Opening a topic clears its unread dot immediately, before the server catches up. */
    /** discourse-vote from a row; see [com.nodeloc.app.core.util.TopicVoting]. */
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

    fun markRead(topicId: Int) {
        if (!locallyRead.add(topicId)) return
        _state.value = _state.value.copy(
            posts = _state.value.posts.map { if (it.id == topicId) it.copy(isUnread = false) else it },
        )
        viewModelScope.launch { preferences.markTopicRead(topicId) }
    }

    fun selectReadingMode(mode: NodeReadingMode) {
        viewModelScope.launch { preferences.selectReadingMode(mode) }
    }

    private fun map(topic: com.nodeloc.app.core.model.TopicListItem): Post {
        val post = FeedMapper.post(topic, usersById, site.category(topic.categoryId))
        return if (post.id in locallyRead) post.copy(isUnread = false) else post
    }
}
