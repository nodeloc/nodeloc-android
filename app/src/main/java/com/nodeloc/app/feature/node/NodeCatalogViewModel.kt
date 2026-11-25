package com.nodeloc.app.feature.node

import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.core.util.rethrowIfCancellation
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.html.plainTextFromHtml
import com.nodeloc.app.core.model.DiscourseCategory
import com.nodeloc.app.core.network.ApiNode
import com.nodeloc.app.core.model.NodeSummary
import com.nodeloc.app.core.model.SidebarDiscourseApp
import com.nodeloc.app.core.model.SiteResponse
import com.nodeloc.app.core.network.DiscourseConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SidebarState(
    val nodes: List<NodeSummary> = emptyList(),
    val recent: List<NodeSummary> = emptyList(),
    val apps: List<SidebarDiscourseApp> = emptyList(),
)

/** A section of the directory, offered as a chip on the node square. */
data class NodeSquareSection(val id: Int, val name: String)

data class NodeSquareState(
    val nodes: List<NodeSummary> = emptyList(),
    val sections: List<NodeSquareSection> = emptyList(),
    /** Null is 推荐: every section, busiest first. */
    val sectionId: Int? = null,
    val query: String = "",
    /** How many of [shown] are drawn; 显示更多 adds a page. */
    val limit: Int = SQUARE_PAGE,
    val isLoading: Boolean = false,
    val error: Throwable? = null,
) {
    val joined: List<NodeSummary> get() = nodes.filter { it.isJoined }

    /** Busiest first, narrowed by the chip and by what is typed into search. */
    val shown: List<NodeSummary>
        get() {
            val needle = query.trim().removePrefix("n/").lowercase()
            return nodes
                .filter { sectionId == null || it.parentCategoryId == sectionId }
                .filter {
                    needle.isEmpty() ||
                        it.slug.lowercase().contains(needle) ||
                        it.name.lowercase().contains(needle) ||
                        it.description?.lowercase()?.contains(needle) == true
                }
                .sortedByDescending { it.memberCount ?: 0 }
        }
}

/**
 * The web square's phone layout pages at 12 (design-sync 16.8). The whole
 * directory is already on the phone, so a page here is only how much is drawn.
 */
const val SQUARE_PAGE = 12

/** Turns Discourse categories into the flat [NodeSummary] rows the UI draws. */
object NodeSummaryFactory {
    fun summary(category: DiscourseCategory): NodeSummary = NodeSummary(
        id = category.id,
        name = category.name,
        slug = category.slug,
        // Both fields arrive as markup: `description` is the rendered HTML
        // and even the excerpt keeps its entities. Everything that shows this
        // shows it as a label, never as a document.
        description = (category.descriptionExcerpt ?: category.description)
            ?.let(::plainTextFromHtml),
        logoUrl = DiscourseConfig.absoluteUrl(category.uploadedLogo?.url),
        bannerUrl = DiscourseConfig.absoluteUrl(category.uploadedBackground?.url),
        colorHex = category.color,
        memberCount = category.memberCount,
        topicCount = category.topicCount,
        parentCategoryId = category.parentCategoryId,
        isJoined = category.isJoined == true,
        notificationLevel = category.notificationLevel,
    )
}

/**
 * The node directory: the drawer's list and the node square. Both read the
 * client's five-minute directory cache, so neither pays for the other.
 */
class NodeCatalogViewModel : ViewModel() {
    private val services = ServiceLocator.get
    private val client = services.client

    private val _sidebarState = MutableStateFlow(SidebarState())
    val sidebarState: StateFlow<SidebarState> = _sidebarState.asStateFlow()

    private val _squareState = MutableStateFlow(NodeSquareState())
    val squareState: StateFlow<NodeSquareState> = _squareState.asStateFlow()

    private var sidebarLoaded = false

    fun loadSidebar() {
        if (sidebarLoaded) return
        sidebarLoaded = true
        viewModelScope.launch {
            val nodesCall = async { runCatchingCancellable { client.sidebarNodes() }.getOrNull() }
            // "Recently visited" is per-account: a guest gets a 403 and the same
            // empty list either way, so the call is pure noise on every drawer open.
            val recentCall = async {
                if (client.auth.isAuthenticated) runCatchingCancellable { client.recentlyVisitedNodes() }.getOrNull() else null
            }
            val siteCall = async { services.siteRepository.siteResponse() }
            val nodes = nodesCall.await()
            val recent = recentCall.await()
            _sidebarState.value = SidebarState(
                nodes = (nodes?.communities.orEmpty() + nodes?.recommended.orEmpty())
                    .distinctBy { it.id }
                    .map(NodeSummaryFactory::summary),
                recent = recent?.communities.orEmpty().map(NodeSummaryFactory::summary),
                apps = sidebarApps(siteCall.await()),
            )
        }
    }

    /**
     * What the reader last played, then the site's own picks, then the most
     * installed. Both earlier sources come back empty often enough — a guest
     * has no history, and `site.json` only carries `popular_apps` on some
     * requests — that the directory is worth the one extra call it costs.
     */
    private suspend fun sidebarApps(site: SiteResponse?): List<SidebarDiscourseApp> {
        services.session.currentUser.value?.recentApps
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it.take(SIDEBAR_APP_COUNT) }

        site?.popularApps
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it.take(SIDEBAR_APP_COUNT) }

        return runCatchingCancellable { client.appsDirectory() }.getOrNull().orEmpty()
            .sortedByDescending { it.installsCount ?: 0 }
            .take(SIDEBAR_APP_COUNT)
            .map { SidebarDiscourseApp(id = it.id, slug = it.slug, name = it.name, logoUrl = it.logoUrl) }
    }

    /**
     * The node square: the whole directory in one call — `GET /nodes` answers
     * every visible node with `joined` already folded in — then sections,
     * search and ordering on the phone, where they cost nothing per keystroke.
     */
    fun loadSquare(force: Boolean = false) {
        if (_squareState.value.isLoading) return
        _squareState.value = _squareState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val all = client.nodeDirectory(force = force)
                val parents = all.filter { it.parentId == null }
                val leaves = all.filter { it.parentId != null }
                _squareState.value = _squareState.value.copy(
                    isLoading = false,
                    // A section with no visible children would be a chip that
                    // empties the page.
                    sections = parents
                        .filter { parent -> leaves.any { it.parentId == parent.id } }
                        .map { NodeSquareSection(it.id, it.name) },
                    nodes = leaves.map(::squareNode),
                )
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                _squareState.value = _squareState.value.copy(isLoading = false, error = error)
            }
        }
    }

    fun selectSection(id: Int?) {
        _squareState.value = _squareState.value.copy(sectionId = id, limit = SQUARE_PAGE)
    }

    fun updateQuery(value: String) {
        _squareState.value = _squareState.value.copy(query = value, limit = SQUARE_PAGE)
    }

    fun showMore() {
        _squareState.value = _squareState.value.copy(limit = _squareState.value.limit + SQUARE_PAGE)
    }

    /**
     * Join or leave from the square. Flips first and restores on failure, the
     * way every other membership toggle in the app does.
     */
    fun toggleJoin(nodeId: Int) {
        val joining = _squareState.value.nodes.firstOrNull { it.id == nodeId }?.isJoined != true
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
        _squareState.value = _squareState.value.copy(
            nodes = _squareState.value.nodes.map { node ->
                if (node.id != nodeId) node
                else node.copy(
                    isJoined = joined,
                    // The count moves with the button, so the two never disagree.
                    memberCount = node.memberCount?.let { (it + if (joined) 1 else -1).coerceAtLeast(0) },
                )
            },
        )
    }

    private fun squareNode(node: ApiNode): NodeSummary = NodeSummary(
        id = node.id,
        name = node.name,
        slug = node.slug,
        description = node.description?.let(::plainTextFromHtml)?.takeIf { it.isNotBlank() },
        logoUrl = DiscourseConfig.absoluteUrl(node.logoUrl),
        colorHex = node.color,
        memberCount = node.memberCount,
        topicCount = node.topicCount,
        parentCategoryId = node.parentId,
        isJoined = node.joined,
    )
}

/** The drawer shows five, as the site's own sidebar does. */
private const val SIDEBAR_APP_COUNT = 5
