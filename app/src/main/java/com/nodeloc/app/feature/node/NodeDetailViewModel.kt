package com.nodeloc.app.feature.node

import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.core.util.rethrowIfCancellation
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.R
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.model.DiscourseUser
import com.nodeloc.app.core.model.NodeNotificationLevel
import com.nodeloc.app.core.model.NodeReadingMode
import com.nodeloc.app.core.model.NodeSort
import com.nodeloc.app.core.model.NodeSummary
import com.nodeloc.app.core.model.Post
import com.nodeloc.app.core.model.VoteDirection
import com.nodeloc.app.core.util.FeedMapper
import com.nodeloc.app.core.util.TopicVoting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NodeDetailState(
    val node: NodeSummary? = null,
    val posts: List<Post> = emptyList(),
    val sort: NodeSort = NodeSort.Latest,
    val notificationLevel: NodeNotificationLevel = NodeNotificationLevel.Regular,
    val isJoined: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: Throwable? = null,
    /** The id resolves to no node at all — not merely an empty one. */
    val notFound: Boolean = false,
) {
    val showSkeleton: Boolean get() = isLoading && posts.isEmpty()
}

/** One node: its header, its membership, and its topic list under a sort. */
class NodeDetailViewModel : ViewModel() {
    private val services = ServiceLocator.get
    private val client = services.client
    private val site = services.siteRepository
    private val preferences = services.preferences

    private val _state = MutableStateFlow(NodeDetailState())
    val state: StateFlow<NodeDetailState> = _state.asStateFlow()

    val readingMode: StateFlow<NodeReadingMode> = preferences.readingMode(services.session.isSignedIn)
        // The same fallback as the flow's, so a guest is not shown one frame
        // of the signed-in default before the stored value arrives.
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            NodeReadingMode.default(services.session.isSignedIn.value),
        )

    private var categoryId: Int = 0
    private var page = 0
    private val usersById = linkedMapOf<Int, DiscourseUser>()

    fun bind(categoryId: Int) {
        if (this.categoryId == categoryId && _state.value.node != null) return
        this.categoryId = categoryId
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null, notFound = false)
            page = 0
            usersById.clear()

            site.siteResponse()
            // A node created moments ago is not in the cached site response
            // yet, and arriving from a deep link is the same story — so a
            // miss is worth one refetch before calling it gone.
            var category = site.category(categoryId)
            if (category == null) {
                site.refresh()
                category = site.category(categoryId)
            }
            val node = category?.let(NodeSummaryFactory::summary)
            val path = category?.let { site.slugPath(it) }

            if (path == null) {
                // Distinct from "this node has no topics", which is what an
                // unresolved id used to look like.
                _state.value = _state.value.copy(isLoading = false, notFound = true)
                return@launch
            }

            try {
                val response = client.nodeTopics(path, categoryId, _state.value.sort.key)
                response.users.orEmpty().forEach { usersById[it.id] = it }
                _state.value = _state.value.copy(
                    node = node,
                    isJoined = node?.isJoined == true,
                    notificationLevel = NodeNotificationLevel.from(node?.notificationLevel),
                    posts = response.topicList?.topics.orEmpty().map(::map),
                    hasMore = response.topicList?.moreTopicsUrl != null,
                    isLoading = false,
                )
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                _state.value = _state.value.copy(node = node, isLoading = false, error = error)
            }
        }
    }

    suspend fun refresh() {
        page = 0
        val category = site.category(categoryId) ?: return
        val path = site.slugPath(category)
        runCatchingCancellable { client.nodeTopics(path, categoryId, _state.value.sort.key) }
            .onSuccess { response ->
                usersById.clear()
                response.users.orEmpty().forEach { usersById[it.id] = it }
                _state.value = _state.value.copy(
                    posts = response.topicList?.topics.orEmpty().map(::map),
                    hasMore = response.topicList?.moreTopicsUrl != null,
                    error = null,
                )
            }
            // Same as the feed: with a list already on screen there is no empty
            // state to show the failure in.
            .onFailure { if (_state.value.posts.isNotEmpty()) ToastCenter.showError(it) }
    }

    fun loadMore() {
        val current = _state.value
        if (!current.hasMore || current.isLoadingMore || current.isLoading) return
        val category = site.category(categoryId) ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoadingMore = true)
            val path = site.slugPath(category)
            runCatchingCancellable { client.nodeTopics(path, categoryId, _state.value.sort.key, page + 1) }
                .onSuccess { response ->
                    page += 1
                    response.users.orEmpty().forEach { usersById[it.id] = it }
                    val existing = _state.value.posts.mapTo(mutableSetOf()) { it.id }
                    val fresh = response.topicList?.topics.orEmpty()
                        .filterNot { it.id in existing }
                        .map(::map)
                    _state.value = _state.value.copy(
                        posts = _state.value.posts + fresh,
                        hasMore = response.topicList?.moreTopicsUrl != null && fresh.isNotEmpty(),
                        isLoadingMore = false,
                    )
                }
                .onFailure { _state.value = _state.value.copy(isLoadingMore = false) }
        }
    }

    fun selectSort(sort: NodeSort) {
        if (sort == _state.value.sort) return
        _state.value = _state.value.copy(sort = sort, posts = emptyList())
        load()
    }

    fun selectReadingMode(mode: NodeReadingMode) {
        viewModelScope.launch { preferences.selectReadingMode(mode) }
    }

    /** Optimistic: the button flips at once and rolls back on failure. */
    fun toggleJoin() {
        val joining = !_state.value.isJoined
        _state.value = _state.value.copy(isJoined = joining)
        viewModelScope.launch {
            val result = runCatchingCancellable {
                if (joining) client.joinNode(categoryId) else client.leaveNode(categoryId)
            }
            if (result.isFailure) {
                _state.value = _state.value.copy(isJoined = !joining)
                result.exceptionOrNull()?.let(ToastCenter::showError)
            } else {
                ToastCenter.show(if (joining) R.string.node_join_success else R.string.node_leave_success)
                site.refresh()
            }
        }
    }

    fun setNotificationLevel(level: NodeNotificationLevel) {
        val previous = _state.value.notificationLevel
        _state.value = _state.value.copy(notificationLevel = level)
        viewModelScope.launch {
            runCatchingCancellable { client.setCategoryNotification(categoryId, level.level) }
                .onSuccess { ToastCenter.show(R.string.node_level_set, services.appContext.getString(level.labelRes)) }
                .onFailure {
                    _state.value = _state.value.copy(notificationLevel = previous)
                    ToastCenter.showError(it)
                }
        }
    }

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
        _state.value = _state.value.copy(
            posts = _state.value.posts.map { if (it.id == topicId) it.copy(isUnread = false) else it },
        )
        viewModelScope.launch { preferences.markTopicRead(topicId) }
    }

    private fun map(topic: com.nodeloc.app.core.model.TopicListItem): Post =
        FeedMapper.post(topic, usersById, site.category(topic.categoryId ?: categoryId))
}
