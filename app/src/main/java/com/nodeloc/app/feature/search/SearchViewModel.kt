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
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.util.FeedMapper
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

enum class SearchScope(@StringRes val labelRes: Int) {
    All(R.string.search_scope_all),
    Nodes(R.string.search_scope_nodes),
    Posts(R.string.search_scope_posts),
    Users(R.string.search_scope_users),
    Apps(R.string.search_scope_apps),
    Media(R.string.search_scope_media),
}

data class SearchState(
    val query: String = "",
    val scope: SearchScope = SearchScope.All,
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
    val isEmpty: Boolean
        get() = posts.isEmpty() && users.isEmpty() && nodes.isEmpty() && apps.isEmpty()

    /**
     * Whether the scope the reader is actually looking at has nothing in it.
     * A term can match posts and no nodes, and the Nodes tab must then say so
     * rather than render a blank page.
     */
    val scopeIsEmpty: Boolean
        get() = when (scope) {
            SearchScope.All -> isEmpty
            SearchScope.Nodes -> nodes.isEmpty()
            SearchScope.Posts -> posts.isEmpty()
            SearchScope.Users -> users.isEmpty()
            SearchScope.Apps -> apps.isEmpty()
            SearchScope.Media -> posts.isEmpty()
        }
}

/**
 * Global search, one endpoint per scope.
 *
 * There is no single call that answers all of them: `search.json` hard-codes
 * `type_filter: "topic"` server-side and returns empty `users`/`categories`
 * whatever the term, while `search/query.json` answers every facet but caps
 * each at five and cannot page. So the overview tab uses the latter and each
 * dedicated tab goes to the endpoint built for it — categories and users have
 * their own, with their own much higher ceilings.
 */
class SearchViewModel : ViewModel() {
    private val services = ServiceLocator.get
    private val client = services.client
    private val site = services.siteRepository
    private val preferences = services.preferences

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
    private var appsCache: List<AppSummary>? = null

    /** One entry per term and scope, so flipping between tabs is free. */
    private val results = mutableMapOf<String, ScopeResults>()

    private data class ScopeResults(
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
                posts = emptyList(), users = emptyList(), nodes = emptyList(), apps = emptyList(),
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
        val key = "$query|$scope"
        results[key]?.let { apply(it); return }

        _state.value = _state.value.copy(isSearching = true, error = null)
        try {
            val found = when (scope) {
                SearchScope.All -> overview(query)
                SearchScope.Posts -> posts(query)
                SearchScope.Media -> media(query)
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

    /** Five of everything, for the tab that shows a bit of each. */
    private suspend fun overview(query: String): ScopeResults {
        site.siteResponse()
        val response = client.searchEverything(query)
        val users = response.users.orEmpty()
        val usersById = users.associateBy { it.id }
        return ScopeResults(
            posts = response.topics.orEmpty().map { FeedMapper.post(it, usersById, site.category(it.categoryId)) },
            users = users,
            nodes = response.categories.orEmpty().map(NodeSummaryFactory::summary),
            apps = apps(query),
        )
    }

    private suspend fun posts(query: String): ScopeResults {
        site.siteResponse()
        val response = client.search(query)
        val usersById = response.users.orEmpty().associateBy { it.id }
        return ScopeResults(
            posts = response.topics.orEmpty().map { FeedMapper.post(it, usersById, site.category(it.categoryId)) },
        )
    }

    /**
     * Media is the post search with Discourse's own `with:images` filter, not a
     * local one: `search.json` topics carry no `image_url` or `thumbnails` at
     * all, so there is nothing here to filter on. The server knows which posts
     * have an `image_upload_id`; we cannot.
     */
    private suspend fun media(query: String): ScopeResults {
        site.siteResponse()
        val response = client.search("$query with:images")
        val usersById = response.users.orEmpty().associateBy { it.id }
        return ScopeResults(
            posts = response.topics.orEmpty().map { FeedMapper.post(it, usersById, site.category(it.categoryId)) },
        )
    }

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
