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
import com.nodeloc.app.core.model.NodeGroupSummary
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

data class NodeBrowseState(
    val groups: List<NodeGroupSummary> = emptyList(),
    val isLoading: Boolean = false,
    val error: Throwable? = null,
) {
    /**
     * Group names arrive one call before their nodes do. Dropping the skeleton
     * on the names alone shows a page of empty shelves, which reads as a bug
     * rather than as loading.
     */
    val showSkeleton: Boolean get() = isLoading && groups.all { it.nodes.isEmpty() }
}

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
 * The node directory: the drawer's list, the browse page's groups, and each
 * group's own pagination. Shared so opening the drawer doesn't refetch what
 * the browse page already has.
 */
class NodeCatalogViewModel : ViewModel() {
    private val services = ServiceLocator.get
    private val client = services.client

    private val _sidebarState = MutableStateFlow(SidebarState())
    val sidebarState: StateFlow<SidebarState> = _sidebarState.asStateFlow()

    private val _browseState = MutableStateFlow(NodeBrowseState())
    val browseState: StateFlow<NodeBrowseState> = _browseState.asStateFlow()

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

    fun loadBrowse(force: Boolean = false) {
        if (!force && _browseState.value.groups.isNotEmpty()) return
        viewModelScope.launch {
            _browseState.value = _browseState.value.copy(isLoading = true, error = null)
            try {
                val response = client.sidebarNodes()
                // A grouped bucket describes the *section* only — its nodes are
                // not in the payload, so each shelf needs its own browse call.
                val groups = response.grouped.orEmpty().values
                    .map { bucket ->
                        NodeGroupSummary(
                            id = bucket.category.id,
                            name = bucket.category.name,
                            slug = bucket.category.slug,
                            nodes = emptyList(),
                            totalCount = bucket.totalCount ?: bucket.category.topicCount ?: 0,
                            hasMore = bucket.hasMore == true,
                        )
                    }
                    .sortedWith(compareByDescending<NodeGroupSummary> { it.totalCount }.thenBy { it.name })
                    .ifEmpty {
                        // Some deployments answer flat rather than grouped; the
                        // site's own category tree is the same information.
                        groupsFromSite()
                    }

                _browseState.value = NodeBrowseState(groups = groups, isLoading = groups.any { it.nodes.isEmpty() })

                // Shelves fill in parallel: one slow section shouldn't hold up
                // the rest of the page.
                val previews = groups
                    .filter { it.nodes.isEmpty() }
                    .map { group -> group.id to async { previewNodes(group.id) } }
                    .associate { (id, job) -> id to job.await() }

                _browseState.value = _browseState.value.copy(
                    isLoading = false,
                    groups = _browseState.value.groups.map { group ->
                        previews[group.id]?.takeIf { it.isNotEmpty() }?.let { group.copy(nodes = it) } ?: group
                    },
                )
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                _browseState.value = _browseState.value.copy(isLoading = false, error = error)
            }
        }
    }

    private suspend fun previewNodes(parentCategoryId: Int): List<NodeSummary> =
        runCatchingCancellable { client.nodeBrowse(parentCategoryId, perPage = PREVIEW_COUNT) }
            .getOrNull()
            ?.let { it.communities ?: it.recommended }
            .orEmpty()
            .map(NodeSummaryFactory::summary)

    private suspend fun groupsFromSite(): List<NodeGroupSummary> {
        val site = services.siteRepository.siteResponse() ?: return emptyList()
        return site.categories.orEmpty()
            .filter { it.parentCategoryId == null }
            .map { parent ->
                NodeGroupSummary(
                    id = parent.id,
                    name = parent.name,
                    slug = parent.slug,
                    nodes = parent.subcategoryList.orEmpty().map(NodeSummaryFactory::summary),
                )
            }
            .filter { it.nodes.isNotEmpty() }
    }

    /**
     * Join or leave, from the browse page. Flips first and restores on failure,
     * the way every other membership toggle in the app does.
     */
    fun toggleJoin(nodeId: Int) {
        val joining = !_browseState.value.groups
            .firstNotNullOfOrNull { group -> group.nodes.firstOrNull { it.id == nodeId } }
            ?.isJoined.let { it == true }
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
        _browseState.value = _browseState.value.copy(
            groups = _browseState.value.groups.map { group ->
                group.copy(
                    nodes = group.nodes.map { if (it.id == nodeId) it.copy(isJoined = joined) else it },
                )
            },
        )
    }

}

/** The drawer shows five, as the site's own sidebar does. */
private const val SIDEBAR_APP_COUNT = 5

/** What a shelf opens with: two rows of two, the rest behind "更多". */
private const val PREVIEW_COUNT = 4
