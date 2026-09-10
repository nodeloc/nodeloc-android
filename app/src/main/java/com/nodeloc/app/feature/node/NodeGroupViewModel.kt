package com.nodeloc.app.feature.node

import com.nodeloc.app.core.util.runCatchingCancellable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.model.NodeSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NodeGroupState(
    val nodes: List<NodeSummary> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: Throwable? = null,
) {
    val showSkeleton: Boolean get() = isLoading && nodes.isEmpty()
}

/**
 * Every node under one group, paged.
 *
 * Unlike the browse shelves this one owns a page cursor, because it is the only
 * caller: nothing else has already fetched a different-sized first page under
 * it, so page N of a fixed [PAGE_SIZE] means what it says.
 */
class NodeGroupViewModel : ViewModel() {
    private val services = ServiceLocator.get
    private val client = services.client

    private val _state = MutableStateFlow(NodeGroupState())
    val state: StateFlow<NodeGroupState> = _state.asStateFlow()

    private var categoryId: Int? = null
    private var page = 0

    fun bind(categoryId: Int) {
        if (this.categoryId == categoryId) return
        this.categoryId = categoryId
        load(reset = true)
    }

    fun retry() = load(reset = true)

    fun loadMore() {
        if (_state.value.isLoading || _state.value.isLoadingMore || !_state.value.hasMore) return
        load(reset = false)
    }

    private fun load(reset: Boolean) {
        val id = categoryId ?: return
        if (reset) page = 0
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isLoading = reset,
                isLoadingMore = !reset,
                error = if (reset) null else _state.value.error,
            )
            val response = runCatchingCancellable {
                client.nodeBrowse(id, page = if (reset) 0 else page + 1, perPage = PAGE_SIZE)
            }
            val fetched = response.getOrNull()
                ?.let { it.communities ?: it.recommended }
                .orEmpty()
                .map(NodeSummaryFactory::summary)

            if (response.isFailure) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    error = response.exceptionOrNull(),
                )
                return@launch
            }
            if (!reset && fetched.isNotEmpty()) page += 1

            val existing = if (reset) emptyList() else _state.value.nodes
            val ids = existing.mapTo(mutableSetOf()) { it.id }
            _state.value = NodeGroupState(
                nodes = existing + fetched.filterNot { it.id in ids },
                hasMore = response.getOrNull()?.meta?.hasMore == true && fetched.isNotEmpty(),
            )
        }
    }

    /** Optimistic, and restored on failure — as membership is everywhere else. */
    fun toggleJoin(nodeId: Int) {
        val joining = _state.value.nodes.firstOrNull { it.id == nodeId }?.isJoined != true
        setJoined(nodeId, joining)
        viewModelScope.launch {
            val result = runCatchingCancellable {
                if (joining) client.joinNode(nodeId) else client.leaveNode(nodeId)
            }
            if (result.isFailure) {
                setJoined(nodeId, !joining)
                result.exceptionOrNull()?.let(ToastCenter::showError)
            } else {
                ToastCenter.show(if (joining) R.string.node_join_success else R.string.node_leave_success)
                services.siteRepository.refresh()
            }
        }
    }

    private fun setJoined(nodeId: Int, joined: Boolean) {
        _state.value = _state.value.copy(
            nodes = _state.value.nodes.map { if (it.id == nodeId) it.copy(isJoined = joined) else it },
        )
    }
}

private const val PAGE_SIZE = 20
