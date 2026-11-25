package com.nodeloc.app.core.store

import com.nodeloc.app.core.network.MessageBusClient
import com.nodeloc.app.core.network.ModerationApi
import com.nodeloc.app.core.network.Perm
import com.nodeloc.app.core.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A node the viewer moderates, as `/me/nodes` lists it. */
data class ModeratedNode(val slug: String, val name: String, val color: String)

/**
 * What the viewer may do. Derived, never assumed: the server's permission
 * defaults are editable by admins, so the keys come from `/me/permissions`.
 */
data class ModPrivileges(
    val role: String = "user",
    val permissions: Set<String> = emptySet(),
    val nodes: List<ModeratedNode> = emptyList(),
) {
    val isAdmin: Boolean get() = role == "admin"
    fun can(key: String): Boolean = isAdmin || key in permissions

    /** Sees the site-wide review queue. */
    val reviewsSite: Boolean get() = can(Perm.ADMIN_REVIEW)
    val managesUsers: Boolean get() = can(Perm.ADMIN_USERS)
    val readsAuditLog: Boolean get() = can(Perm.ADMIN_LOGS)
    val curatesTopics: Boolean get() = can(Perm.TOPICS_CURATE)
    val handlesAppeals: Boolean get() = can(Perm.ADMIN_APPEALS)

    /** Any moderation surface at all: the "管理" entry points show only then. */
    val isModerator: Boolean get() = reviewsSite || managesUsers || nodes.isNotEmpty()

    fun moderates(nodeSlug: String?): Boolean =
        can(Perm.NODES_MODERATE) || can(Perm.NODES_MANAGE) || nodes.any { it.slug == nodeSlug }
}

/**
 * Privileges and pending-work counts for the moderation UI.
 *
 * Counts come from two places because the server only offers one to some
 * people: `admin.review` holders get the site total pushed over the stream;
 * node moderators get nothing, so their per-node numbers are the length of
 * each node's pending queue, fetched when asked (drawer open, dashboard).
 */
class ModerationRepository(
    private val api: ModerationApi,
    private val bus: MessageBusClient,
    private val session: SessionRepository,
    private val scope: CoroutineScope,
) {
    private val _privileges = MutableStateFlow(ModPrivileges())
    val privileges: StateFlow<ModPrivileges> = _privileges.asStateFlow()

    private val _nodePending = MutableStateFlow<Map<String, Int>>(emptyMap())
    val nodePending: StateFlow<Map<String, Int>> = _nodePending.asStateFlow()

    private val _sitePending = MutableStateFlow<Int?>(null)

    /** The number on the menu button: the site queue when the viewer sees it, else their nodes' queues. */
    val menuBadge: StateFlow<Int> = combine(_privileges, _sitePending, _nodePending) { privileges, site, nodes ->
        when {
            privileges.reviewsSite -> site ?: 0
            else -> nodes.values.sum()
        }
    }.stateIn(scope, SharingStarted.Eagerly, 0)

    private var streamHold: MessageBusClient.Subscription? = null
    private var loadJob: Job? = null

    init {
        scope.launch {
            session.isSignedIn.collect { signedIn -> if (signedIn) refresh() else clear() }
        }
        scope.launch {
            bus.reviewPending.collect { pending -> if (pending != null) _sitePending.value = pending }
        }
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = scope.launch {
            val permissions = runCatchingCancellable { api.permissions() }.getOrNull() ?: return@launch
            val mine = runCatchingCancellable { api.myNodes() }.getOrNull()
            val user = session.currentUser.value ?: runCatchingCancellable { session.refresh() }.getOrNull()
            val role = when {
                user?.admin == true -> "admin"
                user?.moderator == true -> "moderator"
                else -> "user"
            }
            val nodes = mine?.joined.orEmpty()
                // An owner without a membership row comes back with no role at all.
                .filter { it.role == "moderator" || it.role.isNullOrEmpty() }
                .map { ModeratedNode(it.slug, it.name.ifBlank { it.slug }, it.color) }
            val privileges = ModPrivileges(role, permissions.permissions.toSet(), nodes)
            _privileges.value = privileges
            if (privileges.reviewsSite) {
                holdStream()
                // The stream's first value can take a while; seed from the queue.
                if (_sitePending.value == null) {
                    _sitePending.value = runCatchingCancellable { api.siteQueue(limit = 1).counts["pending"] }.getOrNull()
                }
            }
            refreshNodeCounts(force = true)
        }
    }

    @Volatile private var countsAt = 0L
    private val countLimit = Semaphore(2)

    /**
     * Per-node pending counts. Capped at a page: past 50 the exact number stops
     * mattering.
     *
     * The server has no per-node count (see docs/backend-api-requests.md #1),
     * so this costs requests, and an account that moderates dozens of nodes
     * firing one per node at once got the whole app rate-limited by nginx —
     * notifications and `/me/nodes` included. So: a site reviewer gets every
     * node's number from one page of the site queue, which already names each
     * item's node; a node moderator's queues go two at a time; and the drawer,
     * the node page and sign-in asking within a minute share one answer.
     */
    suspend fun refreshNodeCounts(force: Boolean = false) {
        val privileges = _privileges.value
        val nodes = privileges.nodes
        if (nodes.isEmpty()) {
            _nodePending.value = emptyMap()
            return
        }
        if (!force && System.currentTimeMillis() - countsAt < 60_000) return
        countsAt = System.currentTimeMillis()
        if (privileges.reviewsSite) {
            val items = runCatchingCancellable { api.siteQueue(limit = 50).items }.getOrNull() ?: return
            val bySlug = items.filter { it.isPost }.groupingBy { it.nodeSlug }.eachCount()
            _nodePending.value = nodes.associate { it.slug to (bySlug[it.slug] ?: 0) }
            return
        }
        val counts = coroutineScope {
            nodes.map { node ->
                async {
                    countLimit.withPermit {
                        node.slug to (runCatchingCancellable { api.nodeQueue(node.slug, limit = 50).items.size }.getOrNull() ?: 0)
                    }
                }
            }.awaitAll()
        }
        _nodePending.value = counts.toMap()
    }

    /** After the viewer resolves something, the numbers they are looking at should move at once. */
    fun noteResolved(nodeSlug: String?) {
        _sitePending.value = _sitePending.value?.let { (it - 1).coerceAtLeast(0) }
        if (nodeSlug != null) {
            _nodePending.value = _nodePending.value.mapValues { (slug, n) -> if (slug == nodeSlug) (n - 1).coerceAtLeast(0) else n }
        }
    }

    /**
     * The stream only runs while someone subscribes; the review count rides on
     * it, so a reviewer keeps it open. Nothing is ever delivered to this channel.
     */
    private fun holdStream() {
        if (streamHold == null) streamHold = bus.subscribe(listOf(REVIEW_CHANNEL)) {}
    }

    private fun clear() {
        loadJob?.cancel()
        streamHold?.cancel()
        streamHold = null
        _privileges.value = ModPrivileges()
        _nodePending.value = emptyMap()
        _sitePending.value = null
        countsAt = 0L
    }

    private companion object {
        const val REVIEW_CHANNEL = "/admin/review"
    }
}

