package com.nodeloc.app.feature.search

import com.nodeloc.app.core.util.runCatchingCancellable
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.model.AppSummary
import com.nodeloc.app.core.model.DiscourseUser
import com.nodeloc.app.core.model.NodeSummary
import com.nodeloc.app.core.model.Post
import com.nodeloc.app.core.network.Legacy
import com.nodeloc.app.core.util.FeedMapper
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.feature.node.NodeSummaryFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The design's four tabs, and Apps after them: the app directory is searched
 * here too, and nothing else in the app finds an app by what it does.
 */
enum class SearchScope(@StringRes val labelRes: Int) {
    Topics(R.string.search2_tab_topics),
    Replies(R.string.search2_tab_replies),
    Nodes(R.string.search_scope_nodes),
    Users(R.string.search_scope_users),
    Apps(R.string.search_scope_apps),
}

/** A topic or reply hit, ready to draw. */
data class SearchHit(
    val postId: Int,
    val topicId: Int,
    val postNumber: Int,
    val isFirstPost: Boolean,
    /** HTML-escaped, the matched words in `<mark>`; see markedText. */
    val titleHtml: String,
    val blurbHtml: String,
    val nodeSlug: String,
    val nodeName: String,
    val nodeId: Int?,
    val nodeLogoUrl: String?,
    val time: String,
    val upvotes: Int,
    val replyCount: Int,
)

data class SearchState(
    val query: String = "",
    val scope: SearchScope = SearchScope.Topics,
    val hits: List<SearchHit> = emptyList(),
    /** In-node search only (NodeSearchScreen), drawn with the feed's PostRow. */
    val posts: List<Post> = emptyList(),
    val users: List<DiscourseUser> = emptyList(),
    val nodes: List<NodeSummary> = emptyList(),
    val apps: List<AppSummary> = emptyList(),
    /** Matching nodes on the server, which can exceed the 25 it will return. */
    val nodeTotal: Int? = null,
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val error: Throwable? = null,
) {
    /**
     * Whether the scope the reader is actually looking at has nothing in it.
     * A term can match posts and no nodes, and the Nodes tab must then say so
     * rather than render a blank page.
     */
    val scopeIsEmpty: Boolean
        get() = when (scope) {
            SearchScope.Topics, SearchScope.Replies -> hits.isEmpty()
            SearchScope.Nodes -> nodes.isEmpty()
            SearchScope.Users -> users.isEmpty()
            SearchScope.Apps -> apps.isEmpty()
        }

    /** Nothing found in any list; the in-node screen reads it. */
    val isEmpty: Boolean
        get() = hits.isEmpty() && posts.isEmpty() && users.isEmpty() && nodes.isEmpty() && apps.isEmpty()
}

/**
 * Global search, one call per scope.
 *
 * Topics and replies come from the site's own search (`/api/v1/search`) read
 * natively, for the highlighting the compatibility mapping has no field for.
 * Nodes are matched against the node directory, which knows their ids; people
 * through the mention lookup; apps against the directory, locally.
 */
class SearchViewModel : ViewModel() {
    private val services = ServiceLocator.get
    private val client = services.client
    private val site = services.siteRepository
    private val preferences = services.preferences
    private val search = SearchApi(client.api)

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    val history: StateFlow<List<String>> = preferences.searchHistory
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Suggestions, taken from the tags on this week's top topics.
     *
     * Not from search logs: `SearchLog.trending` exists but only `/admin/logs`
     * exposes it, and only to staff. Not from the topic titles either — they are
     * whole sentences, useless as terms. Tags are short, real and searchable,
     * and the weekly window keeps moderation tags like 精华 out of the way that
     * the all-time window does not.
     */
    private val _hotTerms = MutableStateFlow(FALLBACK_TERMS)
    val hotTerms: StateFlow<List<String>> = _hotTerms.asStateFlow()

    init {
        loadHotTerms()
    }

    private fun loadHotTerms() {
        viewModelScope.launch {
            val ranked = runCatchingCancellable { client.top(period = "weekly").topicList.topics }
                .getOrDefault(emptyList())
                .flatMap { it.tags.orEmpty() }
                .mapNotNull { it.name?.trim()?.takeIf(String::isNotEmpty) }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key }
                .take(8)
            // A near-empty row reads as breakage; the fixed list is better.
            if (ranked.size >= 4) _hotTerms.value = ranked
        }
    }

    private var searchJob: Job? = null

    /**
     * Set by the in-node search: every query is then scoped to this node through
     * the API's `node` parameter, whatever tab the state says.
     */
    private var nodeScope: String? = null

    fun bindNode(slug: String) {
        if (nodeScope == slug) return
        nodeScope = slug
        results.clear()
    }
    private var appsCache: List<AppSummary>? = null

    /** One entry per term and scope, so flipping between tabs is free. */
    private val results = mutableMapOf<String, ScopeResults>()

    private data class ScopeResults(
        val hits: List<SearchHit> = emptyList(),
        val posts: List<Post> = emptyList(),
        val users: List<DiscourseUser> = emptyList(),
        val nodes: List<NodeSummary> = emptyList(),
        val apps: List<AppSummary> = emptyList(),
        val nodeTotal: Int? = null,
    )

    fun updateQuery(value: String) {
        _state.value = _state.value.copy(query = value)
        searchJob?.cancel()
        results.clear()
        // Two characters is the floor; anything shorter matches half the forum.
        if (value.trim().length < 2) {
            _state.value = _state.value.copy(
                hits = emptyList(), posts = emptyList(), users = emptyList(), nodes = emptyList(), apps = emptyList(),
                nodeTotal = null, hasSearched = false, isSearching = false,
            )
            return
        }
        searchJob = viewModelScope.launch {
            delay(320)
            runSearch(value.trim(), _state.value.scope)
        }
    }

    /** Switching tabs is a new search: each scope has its own endpoint. */
    fun selectScope(scope: SearchScope) {
        _state.value = _state.value.copy(scope = scope)
        val query = _state.value.query.trim()
        if (query.length < 2) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runSearch(query, scope) }
    }

    fun submit() {
        val query = _state.value.query.trim()
        if (query.length < 2) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runSearch(query, _state.value.scope) }
    }

    fun removeHistory(term: String) {
        viewModelScope.launch { preferences.removeSearchTerm(term) }
    }

    fun clearHistory() {
        viewModelScope.launch { preferences.clearSearchHistory() }
    }

    private suspend fun runSearch(query: String, scope: SearchScope) {
        val key = "$query|$scope|${nodeScope.orEmpty()}"
        results[key]?.let { apply(it); return }

        _state.value = _state.value.copy(isSearching = true, error = null)
        try {
            val found = nodeScope?.let { ScopeResults(posts = nodeTopics(query, it)) } ?: when (scope) {
                SearchScope.Topics -> ScopeResults(hits = topics(query))
                SearchScope.Replies -> ScopeResults(hits = replies(query))
                SearchScope.Nodes -> nodes(query)
                SearchScope.Users -> users(query)
                SearchScope.Apps -> ScopeResults(apps = apps(query))
            }
            results[key] = found
            preferences.addSearchTerm(query)
            apply(found)
        } catch (cancel: CancellationException) {
            // Typing another character, clearing the field and switching tabs all
            // cancel the search in flight. Catching that as a failure leaves
            // hasSearched stuck true, and the suggestions pane — which is where
            // the history lives — never comes back.
            throw cancel
        } catch (error: Throwable) {
            _state.value = _state.value.copy(isSearching = false, hasSearched = true, error = error)
        }
    }

    private fun apply(found: ScopeResults) {
        _state.value = _state.value.copy(
            hits = found.hits,
            posts = found.posts,
            users = found.users,
            nodes = found.nodes,
            apps = found.apps,
            nodeTotal = found.nodeTotal,
            isSearching = false,
            hasSearched = true,
            error = null,
        )
    }

    /**
     * One row per topic, from whichever of its posts matched best. The server's
     * own `topics` list is the same dedup without the highlighting, so the
     * posts are deduplicated here instead.
     */
    private suspend fun topics(query: String): List<SearchHit> {
        site.siteResponse()
        return search.hits(query, "topics").distinctBy { it.topicId }.map(::hit)
    }

    /** Hits in replies only; an opening post is its topic, and the Topics tab has those. */
    private suspend fun replies(query: String): List<SearchHit> {
        site.siteResponse()
        return search.hits(query, "posts").filterNot { it.isFirstPost }.map(::hit)
    }

    private fun hit(found: ApiSearchHit): SearchHit {
        val slug = found.nodeSlug.orEmpty()
        val node = site.categoryBySlug(slug)
        return SearchHit(
            postId = found.id,
            topicId = found.topicId,
            postNumber = found.postNumber,
            isFirstPost = found.isFirstPost,
            titleHtml = found.titleHtml ?: escape(found.topicTitle),
            // A folded hit (adult content the reader folds) carries no excerpt
            // on purpose; it stays a title.
            blurbHtml = if (found.folded) "" else found.blurb ?: escape(found.excerpt.orEmpty()),
            nodeSlug = slug,
            nodeName = found.nodeName.orEmpty(),
            nodeId = node?.id,
            nodeLogoUrl = DiscourseConfig.absoluteUrl(node?.uploadedLogo?.url),
            time = DiscourseFormat.relative(found.createdAt),
            upvotes = found.upvotes,
            replyCount = found.replyCount,
        )
    }

    private suspend fun nodeTopics(query: String, slug: String): List<Post> {
        site.siteResponse()
        val topics = search.nodeTopics(query, slug)
        val usersById = Legacy.topicUsers(topics).associateBy { it.id }
        val node = site.categoryBySlug(slug)
        return topics.map { FeedMapper.post(Legacy.topicListItem(it, node?.id), usersById, node) }
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private suspend fun nodes(query: String): ScopeResults {
        val response = client.searchCategories(query)
        return ScopeResults(
            nodes = response.categories.orEmpty().map(NodeSummaryFactory::summary),
            nodeTotal = response.categoriesCount,
        )
    }

    private suspend fun users(query: String): ScopeResults =
        ScopeResults(users = client.searchUsers(query).users.orEmpty())

    /** Descriptions count too: no app here is *named* "游戏", four describe themselves that way. */
    private suspend fun apps(query: String): List<AppSummary> =
        loadApps().filter {
            it.name.contains(query, ignoreCase = true) ||
                it.description?.contains(query, ignoreCase = true) == true
        }

    private companion object {
        /** Used until the tag fetch lands, and if it comes back too thin. */
        val FALLBACK_TERMS = listOf("VPS", "优惠", "教程", "白嫖", "折腾", "IPv6", "免费", "面板")
    }

    private suspend fun loadApps(): List<AppSummary> {
        appsCache?.let { return it }
        val apps = runCatchingCancellable { client.appsDirectory() }.getOrDefault(emptyList()).map {
            AppSummary(
                id = it.id,
                slug = it.slug,
                name = it.name,
                description = it.description,
                logoUrl = DiscourseConfig.absoluteUrl(it.logoUrl),
                installs = it.installsCount,
                isWebview = it.isWebview,
            )
        }
        appsCache = apps
        return apps
    }
}
