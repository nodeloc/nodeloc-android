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
     * Which list the feed is showing.
     *
     * A setting now rather than a control on the page: the home feed had a row
     * of sorts above it that most readers set once and then scrolled past
     * forever. It lives in preferences, where the rest of what the home page is
     * lives, and the page itself is just the page.
     */
    val sort: NodeSort = NodeSort.Best,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: Throwable? = null,
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
        viewModelScope.launch {
            preferences.defaultHome.collect { key ->
                val chosen = NodeSort.entries.firstOrNull { it.key == key } ?: NodeSort.Best
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
                        _state.value = _state.value.copy(sort = chosen, posts = emptyList(), hasMore = false)
                        load()
                    }
                }
            }
        }
    }

    fun loadIfNeeded() {
        if (!homeResolved) return
        if (_state.value.posts.isEmpty() && !_state.value.isLoading) load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            page = 0
            // The category map and the topic list are independent; the feed
            // needs both before a row can name its node.
            val siteJob = async { site.siteResponse() }
            try {
                val latest = client.topics(_state.value.sort.key)
                siteJob.await()
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
                _state.value = _state.value.copy(isLoading = false, hasMore = false, error = error)
            }
        }
    }

    suspend fun refresh() {
        page = 0
        val siteRefresh = viewModelScope.async { site.siteResponse() }
        try {
            val latest = client.topics(_state.value.sort.key)
            siteRefresh.await()
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

    /** Safe to call repeatedly: a no-op while a page is in flight. */
    fun loadMore() {
        val current = _state.value
        if (!current.hasMore || current.isLoadingMore || current.isLoading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoadingMore = true)
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
                // Keep what is shown; the sentinel retries when it reappears.
                _state.value = _state.value.copy(isLoadingMore = false)
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
