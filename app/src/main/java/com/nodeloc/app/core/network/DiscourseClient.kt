@file:OptIn(ExperimentalSerializationApi::class)

package com.nodeloc.app.core.network

import com.nodeloc.app.core.model.*
import com.nodeloc.app.core.util.runCatchingCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The session a request was issued under. See [DiscourseAuth.generation]. */
@JvmInline
value class AuthGeneration(val value: Int)

/** Mutable auth state shared by every request. */
class DiscourseAuth {
    /** The bearer token the native API issued at sign-in. */
    @Volatile var token: String? = null
    @Volatile var username: String? = null

    /**
     * A cookie session instead of a token: what a sign-in completed inside a
     * WebView leaves behind when the server handed back no token. The jar sends
     * it; nothing else needs to.
     */
    @Volatile var hasSession: Boolean = false

    /**
     * The token of a session just signed out of, kept until the sign-out's own
     * last calls (unregistering push, revoking the session) have used it.
     */
    @Volatile var retiredToken: String? = null

    val isAuthenticated: Boolean get() = token != null || hasSession

    /** A failed sign-in attempt must not cost the user the session they already had. */
    data class Snapshot(val token: String?, val username: String?, val hasSession: Boolean)

    fun snapshot(): Snapshot = Snapshot(token, username, hasSession)

    fun restore(snapshot: Snapshot) {
        token = snapshot.token
        username = snapshot.username
        hasSession = snapshot.hasSession
    }

    /**
     * Raised when the server rejects the session itself, from any call. Set after
     * construction by `ServiceLocator`, which builds the client first.
     */
    @Volatile
    var onSessionRejected: (() -> Unit)? = null

    private val rejected = AtomicBoolean(false)

    /**
     * Bumped whenever the credential changes, and stamped on every request, so a
     * request issued under the old session and answered after a new sign-in
     * cannot sign the new one straight back out.
     */
    private val generationCounter = AtomicInteger()

    val generation: Int get() = generationCounter.get()

    fun resetRejection() {
        generationCounter.incrementAndGet()
        rejected.set(false)
    }

    /**
     * Once per session, only for the credential still held, and never for the
     * sign-in endpoints themselves, where a 401 is the answer rather than a
     * verdict on an existing session.
     */
    internal fun noteSessionRejected(path: String, requestGeneration: Int) {
        if (requestGeneration != generation) return
        if (!isAuthenticated) return
        if (path.trimEnd('/') in SESSION_SAFE_PATHS) return
        if (!rejected.compareAndSet(false, true)) return
        onSessionRejected?.invoke()
    }

    private companion object {
        val SESSION_SAFE_PATHS = setOf(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/passkey",
            "/api/v1/auth/password-reset",
            "/api/v1/me/reauth",
        )
    }
}

/**
 * Everything the screens ask of the server, in the shapes they were written for.
 *
 * Since 2.0 this is a compatibility facade: every method calls the native
 * `/api/v1` interface through [NodelocApi] and maps the answer back onto the
 * Discourse-era models in `core.model` (see [Legacy]). ViewModels and screens
 * are untouched by the backend change; what the new server cannot do answers
 * with an empty result or an "unavailable" error, and the entry points to it
 * are hidden in the UI.
 */
class DiscourseClient(
    /** This install's stable id; still used to label the device. */
    @Suppress("unused") private val clientId: String,
    val auth: DiscourseAuth,
    val cookieJar: PersistentCookieJar,
    baseUrl: String = DiscourseConfig.BASE_URL,
) {
    val api = NodelocApi(auth, cookieJar, baseUrl)

    val http: OkHttpClient get() = api.http

    companion object {
        /** The legacy models' decoder, kept for the caches that store them as JSON. */
        val json: Json = NodelocApi.json

        private const val PAGE = 30
        private const val THREAD_PAGE = 20

        /** Signals a feature the 2.0 backend does not have; its UI entry points are hidden. */
        fun unavailable(): DiscourseError =
            DiscourseError.BadResponse(404, null, ServerFault(type = "unavailable"))
    }

    /** Kept for source compatibility; the native API does not accept a post source. */
    @Volatile
    var postSourceFields: (suspend () -> Map<String, String>)? = null

    @Volatile private var me: ApiUser? = null

    private val cursors = ConcurrentHashMap<String, String>()
    private val directUsers = ConcurrentHashMap<Int, String>()
    private val pollIds = ConcurrentHashMap<String, Int>()
    private val uploadUrls = ConcurrentHashMap<Int, String>()

    private fun viewer(moderate: Boolean = false) = Legacy.Viewer(me?.id, moderate || me?.isModerator == true)

    // ------------------------------------------------------------ plumbing

    suspend fun fetchBytes(absoluteUrl: String): ByteArray = api.fetchBytes(absoluteUrl)

    private suspend inline fun <reified T> get(path: String, query: List<Pair<String, String>> = emptyList()): T =
        api.get(path, query)

    private suspend inline fun <reified T> send(method: String, path: String, body: JsonObject? = null): T =
        api.send(method, path, body)

    private suspend fun call(method: String, path: String, body: JsonObject? = null) {
        api.sendRaw(method, path, body)
    }

    /** A 404 from an optional endpoint means "none", not a failure. */
    private suspend inline fun <T> optional(block: () -> T): T? = try {
        block()
    } catch (error: DiscourseError.BadResponse) {
        if (error.code == 404) null else throw error
    }

    // -------------------------------------------------------- node directory

    private val nodeMutex = Mutex()
    @Volatile private var nodes: List<ApiNode> = emptyList()
    @Volatile private var nodesAt: Long = 0

    /** Every node, cached briefly: ids, slugs and the tree are needed on most screens. */
    suspend fun nodeDirectory(force: Boolean = false): List<ApiNode> = nodeMutex.withLock {
        val fresh = System.currentTimeMillis() - nodesAt < 5 * 60_000
        if (!force && fresh && nodes.isNotEmpty()) return@withLock nodes
        val directory = get<ApiNodeList>("api/v1/nodes").nodes
        // Membership, the bell and blocking come from one call rather than per node.
        val mine = if (auth.isAuthenticated) runCatchingCancellable { get<ApiMyNodes>("api/v1/me/nodes") }.getOrNull() else null
        val fetched = if (mine == null) directory else {
            val joined = mine.joined.associateBy { it.slug }
            val muted = mine.muted.toSet()
            directory.map { node ->
                val membership = joined[node.slug]
                node.copy(
                    joined = membership != null || node.joined,
                    notificationLevel = if (node.slug in muted) "muted" else membership?.notificationLevel ?: node.notificationLevel,
                )
            }
        }
        nodes = fetched
        nodesAt = System.currentTimeMillis()
        fetched
    }

    private suspend fun nodeId(slug: String?): Int? {
        if (slug.isNullOrEmpty()) return null
        return (nodes.takeIf { it.isNotEmpty() } ?: runCatchingCancellable { nodeDirectory() }.getOrDefault(emptyList()))
            .firstOrNull { it.slug == slug }?.id
    }

    private suspend fun nodeSlug(id: Int): String {
        nodes.firstOrNull { it.id == id }?.let { return it.slug }
        return nodeDirectory(force = true).firstOrNull { it.id == id }?.slug
            ?: throw DiscourseError.BadResponse(404)
    }

    // ------------------------------------------------------- public reading

    private suspend fun topicPage(
        key: String,
        path: String,
        query: List<Pair<String, String>>,
        page: Int,
        offsetPaging: Boolean,
    ): ApiTopicList {
        val params = query.toMutableList()
        params += "limit" to PAGE.toString()
        if (offsetPaging) {
            if (page > 0) params += "offset" to (page * PAGE).toString()
        } else if (page > 0) {
            // Cursor paging cannot jump: a page is reachable only from the one
            // before it, which is the only way the screens ask for them.
            val cursor = cursors["$key|$page"] ?: return ApiTopicList()
            params += "cursor" to cursor
        }
        val list = get<ApiTopicList>(path, params)
        list.nextCursor?.let { cursors["$key|${page + 1}"] = it }
        return list
    }

    private suspend fun latestResponse(list: ApiTopicList): LatestResponse {
        val ids = list.topics.associate { it.id to nodeId(it.nodeSlug) }
        val hasMore = list.nextCursor != null || list.nextOffset != null
        return LatestResponse(
            users = Legacy.topicUsers(list.topics),
            topicList = TopicList(
                topics = list.topics.map { Legacy.topicListItem(it, ids[it.id]) },
                moreTopicsUrl = if (hasMore) "next" else null,
            ),
        )
    }

    /**
     * The site-wide lists. `hot` and `top` are the server's hot ranking (offset
     * paged); `best` is the personal feed; the rest are the latest list.
     */
    suspend fun topics(sort: String = "latest", page: Int = 0): LatestResponse {
        val list = when (sort) {
            "hot", "top" -> topicPage("hot", "api/v1/topics", listOf("sort" to "hot"), page, offsetPaging = true)
            "rising" -> topicPage("rising", "api/v1/topics", listOf("sort" to "rising"), page, offsetPaging = true)
            "best" -> topicPage("best", "api/v1/feed/personal", emptyList(), page, offsetPaging = true)
            "featured" -> topicPage("featured", "api/v1/topics", listOf("featured" to "1"), page, offsetPaging = false)
            "following" -> topicPage("following", "api/v1/topics", listOf("joined" to "1"), page, offsetPaging = false)
            else -> topicPage("latest", "api/v1/topics", emptyList(), page, offsetPaging = false)
        }
        return latestResponse(list)
    }

    suspend fun latest(page: Int = 0): LatestResponse = topics("latest", page)

    /** A custom feed's topics, newest activity first, cursor paged like 最新. */
    suspend fun customFeedTopics(feedId: Long, page: Int = 0): LatestResponse =
        latestResponse(topicPage("feed-$feedId", "api/v1/custom-feeds/$feedId/topics", emptyList(), page, offsetPaging = false))

    suspend fun top(@Suppress("UNUSED_PARAMETER") period: String? = null): LatestResponse = topics("hot", 0)

    suspend fun site(): SiteResponse {
        val all = nodeDirectory(force = true)
        // The server lists what it has configured; a failed fetch shows no
        // buttons rather than ones that might lead nowhere.
        val providers = runCatchingCancellable { get<ApiAuthProviders>("api/v1/auth/providers").providers }
            .getOrDefault(emptyList())
            .map { AuthProvider(name = it.name, prettyNameOverride = it.label, providerUrl = it.url, iconOverride = it.icon) }
        return SiteResponse(
            categories = all.map { Legacy.category(it) },
            postActionTypes = Legacy.reportTypes(Locale.getDefault().language == "zh"),
            // The server folds a reply whose score drops below -5; the app folds
            // at or below its threshold.
            voteCollapseScoreThreshold = -6,
            authProviders = providers,
            siteSettings = DiscourseSiteSettings(
                pollEnabled = true,
                redEnvelopeEnabled = true,
                lotteryEnabled = true,
            ),
        )
    }

    suspend fun categories(includeSubcategories: Boolean = false): CategoriesResponse {
        val all = nodeDirectory()
        val byParent = all.groupBy { it.parentId }
        val roots = all.filter { it.parentId == null }.map { root ->
            Legacy.category(root, if (includeSubcategories) byParent[root.id].orEmpty().map { Legacy.category(it) } else null)
        }
        return CategoriesResponse(CategoryList(roots))
    }

    suspend fun nodeTopics(path: String, categoryId: Int, sort: String = "latest", page: Int = 0): CategoryTopicsResponse {
        val slug = runCatchingCancellable { nodeSlug(categoryId) }.getOrNull() ?: path.substringAfterLast('/')
        val query = mutableListOf("node" to slug)
        if (sort == "featured") query += "featured" to "1"
        val list = topicPage("node:$slug:${sort == "featured"}", "api/v1/topics", query, page, offsetPaging = false)
        val latest = latestResponse(list)
        return CategoryTopicsResponse(topicList = latest.topicList, users = latest.users)
    }

    /** Joined nodes first, then the busiest of the rest; sections become the grouped shelves. */
    suspend fun sidebarNodes(): SidebarCommunitiesResponse {
        val all = nodeDirectory()
        val leaves = all.filter { it.parentId != null }
        val joined = leaves.filter { it.joined }
        val popular = leaves.filterNot { it.joined }.sortedByDescending { it.memberCount ?: 0 }.take(20)
        val byParent = leaves.groupBy { it.parentId }
        val grouped = all.filter { it.parentId == null }.associate { section ->
            val children = byParent[section.id].orEmpty()
            section.slug to SidebarGroupedNodeBucket(
                category = Legacy.category(section),
                totalCount = children.size,
                hasMore = children.size > 6,
            )
        }
        return SidebarCommunitiesResponse(
            communities = joined.map { Legacy.category(it) },
            recommended = popular.map { Legacy.category(it) },
            grouped = grouped,
        )
    }

    suspend fun nodeBrowse(parentCategoryId: Int, page: Int = 0, perPage: Int? = null): SidebarCommunitiesResponse {
        val size = perPage ?: PAGE
        val children = nodeDirectory().filter { it.parentId == parentCategoryId }
            .sortedByDescending { it.memberCount ?: 0 }
        val slice = children.drop(page * size).take(size)
        return SidebarCommunitiesResponse(
            communities = slice.map { Legacy.category(it) },
            meta = SidebarCommunitiesMeta(
                total = children.size,
                page = page,
                perPage = size,
                hasMore = (page + 1) * size < children.size,
            ),
        )
    }

    /** The native API keeps no visit history; the shelf stays empty and is hidden. */
    suspend fun recentlyVisitedNodes(): SidebarCommunitiesResponse = SidebarCommunitiesResponse()


    suspend fun checkNodeSlug(slug: String): NodeSlugAvailabilityResponse {
        val body = get<JsonObject>("api/v1/nodes/slug-available", listOf("slug" to slug))
        val available = (body["available"] as? JsonPrimitive)?.contentOrNull == "true"
        val message = (body["message"] as? JsonPrimitive)?.contentOrNull
            ?: (body["slug_message"] as? JsonPrimitive)?.contentOrNull
        return NodeSlugAvailabilityResponse(available = available, message = message)
    }

    /**
     * A topic's first page, plus what the old payload carried inline and the new
     * one serves separately: polls, the lottery on the first post, the red
     * envelope on the topic, and playable addresses for uploaded videos.
     */
    suspend fun topic(id: Int): TopicResponse {
        val page = get<ApiTopicPage>("api/v1/topics/$id", listOf("limit" to PAGE.toString()))
        val nodeId = nodeId(page.topic.nodeSlug)
        val viewer = viewer(page.permissions.moderate)
        val base = Legacy.topicResponse(withVideos(page), nodeId, viewer)
        return coroutineScope {
            val envelope = async {
                runCatchingCancellable { optional { get<ApiRedEnvelopeEnvelope>("api/v1/topics/$id/red-envelope") } }
                    .getOrNull()?.redEnvelope
            }
            val firstId = page.posts.firstOrNull { it.postNumber == 1 }?.id
            val lottery = async {
                firstId?.let { postId ->
                    runCatchingCancellable { optional { get<ApiLotteryEnvelope>("api/v1/posts/$postId/lottery") } }
                        .getOrNull()?.lottery
                }
            }
            val polls = page.posts.filter { POLL_MARKUP.containsMatchIn(it.html) }.map { post ->
                async { post.id to runCatchingCancellable { pollsFor(post.id) }.getOrDefault(emptyList()) }
            }.awaitAll().toMap()
            val claims = envelope.await()?.claims.orEmpty()
            val lotteryValue = lottery.await()
            base.copy(
                redEnvelope = envelope.await()?.let { Legacy.redEnvelope(it, id) },
                postStream = base.postStream.copy(
                    posts = base.postStream.posts.map { post ->
                        val postPolls = polls[post.id].orEmpty()
                        val claim = claims.firstOrNull { it.postId == post.id }
                        post.copy(
                            polls = postPolls.map(Legacy::poll).takeIf { it.isNotEmpty() },
                            pollsVotes = Legacy.pollVotes(postPolls).takeIf { it.isNotEmpty() },
                            lottery = if (post.id == firstId) lotteryValue?.let(Legacy::lottery) else null,
                            redEnvelopeClaim = claim?.let {
                                RedEnvelopeClaim(postId = it.postId, pointsReceived = it.points, redEnvelopeId = envelope.await()?.id)
                            },
                        )
                    },
                ),
            )
        }
    }

    private suspend fun pollsFor(postId: Int): List<ApiPoll> {
        val polls = get<ApiPollList>("api/v1/posts/$postId/polls").polls
        polls.forEach { pollIds["$postId/${it.name.ifEmpty { "poll" }}"] = it.id }
        return polls
    }

    /**
     * Uploaded videos arrive as a bare id; the player needs the stream. Resolved
     * here into the placeholder markup the parser already plays, once each video
     * has finished transcoding — until then it stays a poster-less placeholder.
     */
    private suspend fun withVideos(page: ApiTopicPage): ApiTopicPage {
        val ids = page.posts.flatMap { post -> VIDEO_MARKUP.findAll(post.html).map { it.groupValues[1] } }.toSet()
        if (ids.isEmpty()) return page
        val videos = coroutineScope {
            ids.map { sha1 ->
                async { sha1 to runCatchingCancellable { get<ApiVideoEnvelope>("api/v1/videos/$sha1").video }.getOrNull() }
            }.awaitAll().toMap()
        }
        return page.copy(posts = page.posts.map { post -> post.copy(html = rewriteVideos(post.html, videos)) })
    }

    private fun rewriteVideos(html: String, videos: Map<String, ApiVideo?>): String =
        VIDEO_MARKUP.replace(html) { match ->
            val video = videos[match.groupValues[1]]
            val stream = video?.hlsUrl?.takeIf { video.status == null || video.status == "ready" }
            if (stream == null) {
                match.value
            } else {
                val poster = video.posterUrl?.let { " data-thumbnail-src=\"${it.escapeAttr()}\"" }.orEmpty()
                "<div class=\"video-placeholder-container\" data-video-src=\"${stream.escapeAttr()}\"$poster></div>"
            }
        }

    private fun String.escapeAttr(): String = replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    private suspend fun firstPostId(topicId: Int): Int =
        get<ApiTopicPage>("api/v1/topics/$topicId", listOf("limit" to "1")).posts.firstOrNull()?.id
            ?: throw DiscourseError.BadResponse(404)

    suspend fun bookmarkTopic(topicId: Int) {
        call("PUT", "api/v1/posts/${firstPostId(topicId)}/bookmark")
    }

    suspend fun removeTopicBookmarks(topicId: Int) {
        call("DELETE", "api/v1/posts/${firstPostId(topicId)}/bookmark")
    }

    /** 0 muted, 1 regular, 2 tracking, 3 watching — the native API has watching / normal / muted. */
    suspend fun setTopicNotificationLevel(topicId: Int, level: Int) {
        val value = when (level) {
            3, 2 -> "watching"
            0 -> "muted"
            else -> "normal"
        }
        call("PUT", "api/v1/topics/$topicId/follow", buildJsonObject { put("level", value) })
    }

    private fun replySort(sort: String): String = when (sort) {
        "top" -> "best"
        "new" -> "new"
        else -> "old"
    }

    suspend fun nestedTopic(id: Int, slug: String = "topic", sort: String, page: Int = 0): NestedTopicResponse {
        val thread = get<ApiThreadPage>(
            "api/v1/topics/$id/thread",
            listOf("sort" to replySort(sort), "offset" to (page * THREAD_PAGE).toString(), "limit" to THREAD_PAGE.toString()),
        )
        return NestedTopicResponse(
            roots = Legacy.tree(thread.posts, viewer()),
            hasMoreRoots = FlexibleBool(thread.nextOffset != null),
            page = page,
            sort = sort,
            effectiveSort = sort,
        )
    }

    suspend fun nestedChildren(topicId: Int, postNumber: Int, slug: String = "topic", sort: String, page: Int = 0): NestedChildrenResponse {
        val key = "children:$topicId:$postNumber"
        val after = if (page == 0) "0" else cursors["$key|$page"] ?: return NestedChildrenResponse(emptyList(), FlexibleBool(false), page)
        val body = api.getRaw(
            "api/v1/topics/$topicId/thread",
            listOf("parent" to postNumber.toString(), "after" to after, "limit" to THREAD_PAGE.toString()),
        )
        val thread = api.decode<ApiThreadPage>(body)
        val nextAfter = (api.decode<JsonObject>(body)["next_after"] as? JsonPrimitive)?.intOrNull
        nextAfter?.let { cursors["$key|${page + 1}"] = it.toString() }
        val below = thread.posts.filter { it.postNumber != postNumber }
        return NestedChildrenResponse(
            children = Legacy.tree(below, viewer()),
            hasMore = FlexibleBool(nextAfter != null),
            page = page,
        )
    }

    /** Pinning replies has no native counterpart; the menu item is hidden. */
    suspend fun toggleNestedPin(topicId: Int, postId: Int, slug: String = "topic"): NestedPinResponse = throw unavailable()

    suspend fun topicPosts(topicId: Int, postIds: List<Int>): TopicPostsResponse {
        val page = get<ApiTopicPage>("api/v1/topics/$topicId", listOf("limit" to "100"))
        val wanted = postIds.toSet()
        val viewer = viewer(page.permissions.moderate)
        return TopicPostsResponse(
            PostStream(posts = page.posts.filter { it.id in wanted }.map { Legacy.topicPost(it, viewer) }),
        )
    }

    suspend fun videoSuggestions(excludeTopicId: Int? = null): VideoSuggestionsResponse = VideoSuggestionsResponse()

    private suspend fun searchNative(term: String, page: Int, limit: Int): SearchResponse {
        val result = optional {
            get<ApiSearchResults>(
                "api/v1/search",
                listOf("q" to term, "offset" to (page * limit).toString(), "limit" to limit.toString()),
            )
        } ?: return SearchResponse(topics = emptyList())
        val ids = result.topics.associate { it.id to nodeId(it.nodeSlug) }
        return SearchResponse(
            topics = result.topics.map { Legacy.topicListItem(it, ids[it.id]) },
            posts = result.posts.map {
                SearchPost(id = it.id, topicId = it.topicId, blurb = it.blurb ?: it.excerpt, username = it.author)
            },
            categories = result.nodes.mapNotNull { found ->
                val id = nodeId(found.slug) ?: return@mapNotNull null
                Legacy.category(
                    ApiNode(
                        id = id,
                        slug = found.slug,
                        name = found.name,
                        color = found.color,
                        description = found.description,
                        memberCount = found.memberCount,
                        joined = found.joined,
                        parentId = nodes.firstOrNull { it.id == id }?.parentId,
                    ),
                )
            },
            users = result.users.map { Legacy.user(it.username, it.avatar, it.name) },
        )
    }

    /** Pages are 1-based here, as they were. */
    suspend fun search(term: String, page: Int = 1): SearchResponse = searchNative(term, (page - 1).coerceAtLeast(0), PAGE)

    suspend fun searchEverything(term: String): SearchResponse = searchNative(term, 0, 5)

    suspend fun searchCategories(term: String, limit: Int = 25): CategorySearchResponse {
        val needle = term.trim().lowercase(Locale.ROOT)
        val found = nodeDirectory().filter { it.parentId != null }
            .filter { needle.isEmpty() || it.name.lowercase(Locale.ROOT).contains(needle) || it.slug.contains(needle) }
            .sortedByDescending { it.memberCount ?: 0 }
        return CategorySearchResponse(found.size, found.take(limit).map { Legacy.category(it) })
    }

    suspend fun searchUsers(term: String, limit: Int = 50): UserSearchResponse {
        if (!auth.isAuthenticated) return UserSearchResponse(emptyList())
        val users = get<ApiMentionCandidates>("api/v1/mention-candidates", listOf("q" to term)).users
        return UserSearchResponse(users.take(limit).map { Legacy.user(it.username, it.avatar, it.name) })
    }

    /**
     * Tags for the composer's picker. An empty [term] is the site's common
     * tags; [nodeSlug] lets the server leave out tags that belong to other
     * nodes, as the web's composer does.
     */
    suspend fun composerTags(term: String, nodeSlug: String?, limit: Int = 12): List<ApiTag> =
        get<ApiTagList>(
            "api/v1/tags",
            listOfNotNull("q" to term, nodeSlug?.let { "node" to it }, "limit" to limit.coerceIn(1, 30).toString()),
        ).tags

    /** A node's tag rules for a new topic; see [ApiNodePosting]. */
    suspend fun nodePosting(slug: String): ApiNodePosting? =
        get<ApiNodeDetail>("api/v1/nodes/$slug").posting

    suspend fun searchTags(term: String, limit: Int = 5): TagSearchResponse {
        val tags = get<ApiTagList>("api/v1/tags", listOf("q" to term, "limit" to limit.coerceIn(1, 30).toString())).tags
        return TagSearchResponse(tags.map { DiscourseTag(id = it.id, name = it.name, slug = it.name, count = it.topicCount) })
    }

    /**
     * The site's own emoji. Standard Unicode emoji come from the keyboard now —
     * the server turns their shortcodes into characters — so the picker carries
     * the custom packs only.
     */
    suspend fun emojis(): Map<String, List<DiscourseEmoji>> {
        val catalog = get<ApiEmojiCatalog>("api/v1/emojis")
        val grouped = linkedMapOf<String, List<DiscourseEmoji>>()
        val packed = mutableSetOf<String>()
        catalog.packs.filter { it.enabled }.forEach { pack ->
            val items = pack.emojis.map { DiscourseEmoji(name = it.code.ifEmpty { it.name }, url = it.url, group = pack.name) }
            packed += items.map { it.name }
            if (items.isNotEmpty()) grouped[pack.name.ifEmpty { "custom" }] = items
        }
        val loose = catalog.emojis.filterKeys { it !in packed }.map { (code, url) -> DiscourseEmoji(name = code, url = url, group = "custom") }
        if (loose.isNotEmpty()) grouped["custom"] = grouped["custom"].orEmpty() + loose
        return grouped
    }

    // ------------------------------------------------- editing and moderating

    suspend fun postDetail(postId: Int): PostDetail {
        val raw = get<ApiRaw>("api/v1/posts/$postId/raw")
        return PostDetail(id = postId, raw = raw.raw, canEdit = true)
    }

    suspend fun updatePost(postId: Int, raw: String, editReason: String? = null, title: String? = null) {
        call(
            "PATCH",
            "api/v1/posts/$postId",
            buildJsonObject {
                put("raw", toNativeMarkdown(raw))
                editReason?.takeIf { it.isNotBlank() }?.let { put("reason", it) }
                title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
            },
        )
    }

    suspend fun deletePost(postId: Int) {
        call("DELETE", "api/v1/posts/$postId")
    }

    suspend fun recoverPost(postId: Int) {
        call("POST", "api/v1/posts/$postId/restore")
    }

    /** No per-face voter list in the native API; the sheet shows nothing and its entry is hidden. */
    suspend fun postReactionUsers(postId: Int): ReactionUsersResponse = ReactionUsersResponse()

    suspend fun castVote(
        postId: Int,
        direction: VoteDirection,
        reaction: String? = null,
        /** Cast from a list rather than the topic: the server does not count it towards reputation. */
        fromFeed: Boolean = false,
    ): PostVoteResult {
        val result = send<ApiVoteResult>(
            "PUT",
            "api/v1/posts/$postId/vote",
            buildJsonObject {
                put("value", Legacy.voteValue(direction))
                reaction?.let { put("emoji", it) }
                if (fromFeed) put("from", "feed")
            },
        )
        return PostVoteResult(
            id = postId,
            voteScore = result.upvotes - result.downvotes,
            voteDirection = Legacy.direction(result.viewerVote),
            canVoteDown = true,
        )
    }

    suspend fun flagPost(postId: Int, actionTypeId: Int, message: String? = null) {
        call(
            "POST",
            "api/v1/posts/$postId/reports",
            buildJsonObject {
                put("reason", Legacy.reportReasonFor(actionTypeId))
                message?.takeIf { it.isNotBlank() }?.let { put("note", it) }
            },
        )
    }

    suspend fun setPostLocked(postId: Int, locked: Boolean) {
        call("PUT", "api/v1/posts/$postId/flags", buildJsonObject { put("locked", locked) })
    }

    suspend fun deleteTopic(topicId: Int) {
        call("DELETE", "api/v1/topics/$topicId")
    }

    /** `closed` and `pinned` exist natively; archiving and unlisting do not. */
    suspend fun setTopicStatus(topicId: Int, status: String, enabled: Boolean) {
        val field = when (status) {
            "closed" -> "closed"
            "pinned", "pinned_globally" -> "pinned"
            else -> throw unavailable()
        }
        call("PUT", "api/v1/topics/$topicId/state", buildJsonObject { put(field, enabled) })
    }

    suspend fun user(username: String): UserResponse = Legacy.profile(get("api/v1/users/$username"))

    suspend fun userSummary(username: String): UserSummaryResponse {
        val stats = get<ApiUserEnvelope>("api/v1/users/$username").stats
        return UserSummaryResponse(
            UserSummary(
                likesReceived = stats?.votesReceived,
                daysVisited = stats?.daysVisited,
                topicCount = stats?.topicCount,
                postCount = stats?.postCount,
            ),
        )
    }

    suspend fun accountDetail(username: String): AccountDetail = coroutineScope {
        val identities = async { runCatchingCancellable { get<ApiIdentities>("api/v1/me/identities") }.getOrNull() }
        val sessions = async { runCatchingCancellable { get<ApiSessions>("api/v1/me/sessions") }.getOrNull() }
        val twoFactor = async { runCatchingCancellable { get<ApiTwoFactorStatus>("api/v1/me/two-factor") }.getOrNull() }
        AccountDetail(
            AccountDetailPayload(
                associatedAccounts = identities.await()?.identities?.map { AssociatedAccount(it.provider, it.name ?: it.username) },
                userAuthTokens = sessions.await()?.sessions?.map {
                    UserAuthToken(
                        id = it.id,
                        clientName = it.userAgent,
                        deviceName = it.ip,
                        seenAt = it.lastSeenAt,
                        isActive = it.current,
                    )
                },
                secondFactorEnabled = twoFactor.await()?.enabled,
            ),
        )
    }

    suspend fun userBadges(username: String): UserBadgesResponse {
        val showcase = optional { get<ApiAchievements>("api/v1/users/$username/achievements") }?.showcase.orEmpty()
        return UserBadgesResponse(
            showcase.mapIndexed { index, item ->
                BadgeDefinition(id = index + 1, name = item.name, description = item.description, allowTitle = false)
            },
        )
    }

    /**
     * Only your own, newest first, across every folder: the app has no folder
     * screen, so `/api/v1/saved` without `folder` is the whole collection.
     */
    suspend fun bookmarks(username: String, page: Int = 0): UserBookmarksResponse {
        val list = get<ApiSavedList>(
            "api/v1/saved",
            listOf("offset" to (page * PAGE).toString(), "limit" to PAGE.toString()),
        )
        val ids = list.bookmarks.associate { it.postId to nodeId(it.nodeSlug) }
        return UserBookmarksResponse(
            UserBookmarkList(
                bookmarks = list.bookmarks.map {
                    UserBookmark(
                        // Keyed by post: taking one off is `DELETE /posts/{id}/bookmark`.
                        id = it.postId,
                        title = it.topicTitle,
                        excerpt = it.excerpt,
                        createdAt = it.createdAt,
                        topicId = it.topicId,
                        categoryId = ids[it.postId],
                        postId = it.postId,
                        linkedPostNumber = it.postNumber,
                        user = it.author?.let { name -> Legacy.user(name, null) },
                    )
                },
                moreBookmarksUrl = if (list.nextOffset != null) "next" else null,
            ),
        )
    }

    suspend fun drafts(): DraftsResponse {
        val list = get<ApiDraftList>("api/v1/drafts")
        return DraftsResponse(
            list.drafts.map { draft ->
                val data = draft.data as? JsonObject
                fun field(vararg names: String) = names.firstNotNullOfOrNull { (data?.get(it) as? JsonPrimitive)?.contentOrNull }
                val payload = buildJsonObject {
                    field("raw", "body", "reply")?.let { put("reply", it) }
                    field("title")?.let { put("title", it) }
                }
                DraftItem(
                    draftKey = draft.key,
                    data = payload.toString(),
                    title = field("title"),
                    createdAt = draft.updatedAt,
                    topicId = (data?.get("topic_id") as? JsonPrimitive)?.intOrNull,
                )
            },
        )
    }

    /** filter: 4 topics, 5 replies; likes given (1) is not kept by the native API. */
    suspend fun userActions(username: String, filter: Int, offset: Int = 0): UserActionsResponse {
        val page = offset / PAGE
        return when (filter) {
            4 -> {
                val list = optional {
                    topicPage("user-topics:$username", "api/v1/users/$username/topics", emptyList(), page, offsetPaging = false)
                } ?: return UserActionsResponse()
                UserActionsResponse(
                    list.topics.map {
                        UserActionItem(
                            actionType = 4,
                            title = it.title,
                            excerpt = it.excerpt,
                            createdAt = it.createdAt,
                            avatarTemplate = it.authorAvatar,
                            username = it.author,
                            categoryId = nodeId(it.nodeSlug),
                            topicId = it.id,
                            postNumber = 1,
                        )
                    },
                )
            }
            5 -> {
                val key = "user-posts:$username"
                val query = mutableListOf("limit" to PAGE.toString())
                if (page > 0) query += "cursor" to (cursors["$key|$page"] ?: return UserActionsResponse())
                val body = optional { get<JsonObject>("api/v1/users/$username/posts", query) } ?: return UserActionsResponse()
                (body["next_cursor"] as? JsonPrimitive)?.contentOrNull?.let { cursors["$key|${page + 1}"] = it }
                val posts = (body["posts"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val item = element as? JsonObject ?: return@mapNotNull null
                    fun s(name: String) = (item[name] as? JsonPrimitive)?.contentOrNull
                    fun i(name: String) = (item[name] as? JsonPrimitive)?.intOrNull
                    UserActionItem(
                        actionType = 5,
                        title = s("topic_title"),
                        excerpt = s("excerpt") ?: s("html")?.let { com.nodeloc.app.core.util.DiscourseFormat.plainText(it) },
                        createdAt = s("created_at"),
                        username = username,
                        topicId = i("topic_id"),
                        postNumber = i("post_number"),
                        postId = i("id"),
                    )
                }
                UserActionsResponse(posts)
            }
            else -> UserActionsResponse()
        }
    }

    suspend fun pointsHistory(username: String, page: Int = 0): PointsHistoryResponse {
        val key = "points"
        val query = mutableListOf("limit" to PAGE.toString())
        if (page > 0) query += "before" to (cursors["$key|$page"] ?: return PointsHistoryResponse(page = page, hasMore = false))
        val history = get<ApiPointsHistory>("api/v1/points/history", query)
        history.nextBefore?.let { cursors["$key|${page + 1}"] = it.toString() }
        return PointsHistoryResponse(
            pointsHistory = history.entries.map {
                PointsHistoryEntry(
                    date = it.createdAt,
                    points = it.amount.toInt(),
                    description = it.memo ?: it.counterpart ?: it.kind,
                    createdAt = it.createdAt,
                    isPositive = it.amount >= 0,
                )
            },
            page = page,
            hasMore = history.nextBefore != null,
        )
    }

    suspend fun pointsTotal(username: String): PointsScoresResponse =
        PointsScoresResponse(get<ApiPointsSummary>("api/v1/points").balance.toInt())

    // ------------------------------------------------------- site features

    /**
     * What this server offers to the app. Check-in exists natively; the post
     * source tail does not, so its settings page stays hidden.
     */
    suspend fun mobileMeta(): MobileMeta {
        val flags = runCatchingCancellable { get<ApiFlags>("api/v1/mobile/flags").flags }.getOrDefault(emptyMap())
        return MobileMeta(apiVersion = 2, features = mapOf("checkin" to true, "post_source" to false, "devices" to true) + flags)
    }

    suspend fun checkinStatus(): CheckinStatus {
        val status = get<ApiCheckinStatus>("api/v1/checkin")
        return CheckinStatus(
            checkedInToday = status.checkedInToday,
            pointsToday = if (status.checkedInToday) status.todayPoints else null,
            nextPoints = if (status.checkedInToday) null else status.todayPoints,
            streak = status.streak,
            totalDays = status.total,
        )
    }

    suspend fun postSourcePreference(): PostSourcePreference = throw unavailable()

    suspend fun setPostSourcePreference(level: Int): PostSourcePreference = throw unavailable()

    suspend fun clearPostSources() { throw unavailable() }

    /** Where to deliver this account's notifications on this handset (FCM). */
    suspend fun registerDevice(
        token: String,
        appVersion: String,
        locale: String,
        timezone: String,
        categories: List<String>,
    ) {
        call(
            "POST",
            "api/v1/mobile/devices",
            buildJsonObject {
                put("platform", "android")
                put("push_token", token)
                put("app_build", com.nodeloc.app.BuildConfig.VERSION_CODE)
                put("name", "${android.os.Build.MANUFACTURER ?: ""} ${android.os.Build.MODEL ?: ""}".trim().ifEmpty { "Android" })
            },
        )
    }

    /** Also runs during sign-out, after the credential is gone: hence the retired token. */
    suspend fun unregisterDevice(token: String) {
        api.sendRaw(
            "POST",
            "api/v1/mobile/devices/unregister",
            buildJsonObject { put("push_token", token) },
            bearer = auth.token ?: auth.retiredToken,
        )
    }

    suspend fun latestRelease(versionCode: Int, clientIdForRollout: String): ReleaseResponse {
        val check = get<ApiUpdateCheck>(
            "api/v1/mobile/update",
            listOf("platform" to "android", "build" to versionCode.toString()),
        )
        val latest = check.latest
        if (latest == null || !check.updateAvailable || latest.downloadUrl.isNullOrEmpty()) return ReleaseResponse(null)
        return ReleaseResponse(
            ReleaseInfo(
                platform = "android",
                versionCode = latest.build,
                versionName = latest.version,
                url = latest.downloadUrl,
                sha256 = latest.sha256,
                notes = latest.notes,
                mandatory = check.updateRequired,
            ),
        )
    }

    /**
     * Progress towards the next tier. Tiers are earned by reputation alone now,
     * so the checklist the page draws has exactly one line.
     */
    suspend fun upgradeProgress(username: String): UpgradeProgress {
        val rep = get<ApiReputation>("api/v1/me/reputation")
        val next = rep.next
        val target = next?.threshold
        return UpgradeProgress(
            currentLevel = rep.tier,
            currentLevelName = rep.tierName,
            nextLevel = next?.level,
            nextLevelName = next?.name,
            conditions = if (target == null) emptyList() else listOf(
                UpgradeCondition(
                    key = "reputation",
                    label = if (Locale.getDefault().language == "zh") "声望" else "Reputation",
                    text = "${rep.reputation} / $target",
                    group = "standing",
                    comparison = "min",
                    value = rep.reputation,
                    target = target,
                    met = rep.reputation >= target,
                ),
            ),
            totalConditions = if (target == null) 0 else 1,
            metCount = if (target != null && rep.reputation >= target) 1 else 0,
            maxLevelReached = next == null,
        )
    }

    suspend fun appsDirectory(): List<DirectoryApp> {
        val body = get<JsonObject>("apps/directory.json")
        val apps = body["apps"] as? JsonArray ?: return emptyList()
        return apps.mapNotNull { runCatching { json.decodeFromJsonElement(DirectoryApp.serializer(), it) }.getOrNull() }
    }

    suspend fun app(slug: String): DirectoryAppResponse = get("apps/$slug.json")

    fun appWebviewUrl(installId: Int): String = "${DiscourseConfig.BASE_URL}/apps/installs/$installId/webview"

    suspend fun customGroupStyles(): List<CustomGroupStyleItem> = emptyList()

    suspend fun customBadgeStyles(): List<CustomBadgeStyleItem> = emptyList()

    suspend fun checkUsername(username: String): UsernameCheckResponse = try {
        api.getRaw("api/v1/auth/username-check", listOf("username" to username))
        UsernameCheckResponse(available = true)
    } catch (error: DiscourseError.BadResponse) {
        if (error.code in 400..499) UsernameCheckResponse(available = false) else throw error
    }

    suspend fun honeypot(): HoneypotResponse = HoneypotResponse()

    /** The server checks the address when the code is sent; nothing to ask beforehand. */
    suspend fun checkEmail(email: String): EmailCheckResponse = EmailCheckResponse(success = "OK")

    // -------------------------------------------------- authenticated reads

    suspend fun currentUser(): CurrentUserResponse = coroutineScope {
        val balance = async { runCatchingCancellable { get<ApiPointsSummary>("api/v1/points").balance }.getOrNull() }
        val user = get<ApiUserEnvelope>("api/v1/me").user
        me = user
        val unread = runCatchingCancellable {
            get<ApiNotificationList>("api/v1/notifications", listOf("limit" to "1")).unread
        }.getOrNull()
        CurrentUserResponse(Legacy.currentUser(user, balance.await()).copy(unreadNotifications = unread))
    }

    suspend fun notifications(limit: Int = 60): NotificationsResponse {
        val list = get<ApiNotificationList>("api/v1/notifications", listOf("limit" to limit.coerceIn(1, 50).toString()))
        return NotificationsResponse(list.notifications.map(Legacy::notification))
    }

    /** Private messages are chat conversations now; they are listed there. */
    suspend fun privateMessages(username: String): PrivateMessagesResponse = PrivateMessagesResponse()

    suspend fun groupPrivateMessages(username: String, group: String): PrivateMessagesResponse = PrivateMessagesResponse()

    suspend fun dismissTopicPosts(topicIds: List<Int>) = Unit

    // -------------------------------------------------------------- writing

    /** The daily check-in. Already-claimed answers 4xx; reported as not checked in, with the reason. */
    suspend fun checkin(): CheckinResponse = try {
        val result = send<ApiCheckinResult>("POST", "api/v1/checkin")
        CheckinResponse(success = FlexibleBool(true), points = result.points, userDate = result.day)
    } catch (error: DiscourseError.BadResponse) {
        if (error.code in 400..499 && error.code != 401 && error.code != 429) {
            CheckinResponse(success = FlexibleBool(false), message = error.serverMessage)
        } else {
            throw error
        }
    }

    suspend fun likePost(id: Int) {
        castVote(id, VoteDirection.Up)
    }

    suspend fun unlikePost(id: Int) {
        castVote(id, VoteDirection.None)
    }

    /** Bookmarks are keyed by post; the "bookmark id" handed back is the post id. */
    suspend fun bookmark(postId: Int): BookmarkCreated {
        call("PUT", "api/v1/posts/$postId/bookmark")
        return BookmarkCreated(id = postId, success = "OK")
    }

    suspend fun removeBookmark(bookmarkId: Int) {
        call("DELETE", "api/v1/posts/$bookmarkId/bookmark")
    }

    suspend fun reply(topicId: Int, raw: String, replyToPostNumber: Int? = null): CreatePostResponse {
        val created = send<ApiCreatedReply>(
            "POST",
            "api/v1/topics/$topicId/posts",
            buildJsonObject {
                put("raw", toNativeMarkdown(raw))
                replyToPostNumber?.let { put("reply_to_post_number", it) }
            },
        )
        return CreatePostResponse(id = created.post?.id, topicId = topicId, postNumber = created.post?.postNumber)
    }

    suspend fun createTopic(title: String, raw: String, categoryId: Int, tags: List<String> = emptyList()): CreatePostResponse {
        val created = send<ApiCreatedTopic>(
            "POST",
            "api/v1/topics",
            buildJsonObject {
                put("node", nodeSlug(categoryId))
                put("title", title)
                put("raw", toNativeMarkdown(raw))
                if (tags.isNotEmpty()) put("tags", JsonArray(tags.map(::JsonPrimitive)))
            },
        )
        return CreatePostResponse(id = created.post?.id, topicId = created.topic?.id, postNumber = 1)
    }

    /**
     * A private message is a chat message now. The subject line has nowhere to
     * go except the message itself, so it leads.
     */
    suspend fun createPrivateMessage(title: String, raw: String, recipient: String): CreatePostResponse {
        val text = listOf(title.trim(), raw.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")
        val sent = send<ApiChatSent>("POST", "api/v1/messages/$recipient", buildJsonObject { put("raw", toNativeMarkdown(text)) })
        return CreatePostResponse(id = sent.message?.id)
    }

    /**
     * The conversation with [username], or a placeholder for one not started
     * yet. Directs are addressed by username either way; the id only has to be
     * stable while the screen is open.
     */
    suspend fun openDirectMessageChannel(username: String): ChatChannelResponse {
        val page = get<ApiChatPage>("api/v1/messages/$username", listOf("limit" to "1", "read" to "0"))
        val id = page.conversationId ?: -(Legacy.syntheticUserId(username))
        directUsers[id] = username
        return ChatChannelResponse(Legacy.direct(ApiDirect(id = id, with = username)))
    }

    suspend fun setUserNotificationLevel(username: String, level: String) {
        call(if (level == "normal") "DELETE" else "PUT", "api/v1/users/$username/block")
    }

    suspend fun ignoreUser(username: String) {
        call("PUT", "api/v1/users/$username/block")
    }

    suspend fun unignoreUser(username: String) = setUserNotificationLevel(username, "normal")

    suspend fun flagChatMessage(channelId: Int, messageId: Int, flagTypeId: Int, message: String? = null) {
        throw unavailable()
    }

    suspend fun joinNode(categoryId: Int): NodeMembershipResponse {
        call("PUT", "api/v1/nodes/${nodeSlug(categoryId)}/membership")
        nodesAt = 0
        return NodeMembershipResponse(success = FlexibleBool(true), joined = true)
    }

    suspend fun leaveNode(categoryId: Int): NodeMembershipResponse {
        call("DELETE", "api/v1/nodes/${nodeSlug(categoryId)}/membership")
        nodesAt = 0
        return NodeMembershipResponse(success = FlexibleBool(true), joined = false)
    }

    suspend fun setCategoryNotification(categoryId: Int, level: Int) {
        call("PUT", "api/v1/nodes/${nodeSlug(categoryId)}/notification", buildJsonObject { put("level", Legacy.nodeLevel(level)) })
    }

    suspend fun createNode(
        name: String,
        slug: String,
        description: String,
        colorHex: String?,
        parentCategoryId: Int,
    ): CreateCommunityResponse {
        val created = send<ApiNodeDetail>(
            "POST",
            "api/v1/nodes",
            buildJsonObject {
                put("parent", nodeSlug(parentCategoryId))
                put("slug", slug)
                put("name", name)
                put("description", description)
                colorHex?.takeIf { it.isNotEmpty() }?.let { put("color", it.trim('#').lowercase(Locale.ROOT)) }
            },
        )
        nodesAt = 0
        return CreateCommunityResponse(created.node?.let { Legacy.category(it) })
    }

    suspend fun repost(topicId: Int, categoryId: Int, title: String, raw: String? = null) {
        call(
            "POST",
            "api/v1/topics/$topicId/reposts",
            buildJsonObject {
                put("to", "node")
                put("node", nodeSlug(categoryId))
                put("title", title)
                raw?.takeIf { it.isNotBlank() }?.let { put("comment", it) }
            },
        )
    }

    suspend fun follow(username: String) {
        call("PUT", "api/v1/users/$username/follow")
    }

    suspend fun unfollow(username: String) {
        call("DELETE", "api/v1/users/$username/follow")
    }

    suspend fun markNotificationsRead(id: Int? = null) {
        call("POST", "api/v1/notifications/read", buildJsonObject { id?.let { put("id", it) } })
    }

    /** Read progress: the furthest post seen, and how long the reader spent. */
    suspend fun sendTopicTimings(topicId: Int, topicTimeMs: Int, timings: Map<Int, Int>) {
        val furthest = timings.keys.maxOrNull() ?: return
        call(
            "PUT",
            "api/v1/topics/$topicId/read",
            buildJsonObject {
                put("post_number", furthest)
                put("seconds", (topicTimeMs / 1000).coerceAtLeast(1))
            },
        )
    }

    private suspend fun pollId(postId: Int, pollName: String): Int =
        pollIds["$postId/$pollName"] ?: run {
            pollsFor(postId)
            pollIds["$postId/$pollName"] ?: throw DiscourseError.BadResponse(404)
        }

    /** Option ids travel as strings in the old model; the native API wants the numbers. */
    suspend fun votePoll(postId: Int, pollName: String, options: List<String>): PollVoteResponse {
        val id = pollId(postId, pollName)
        val result = send<ApiPollEnvelope>(
            "PUT",
            "api/v1/polls/$id/votes",
            buildJsonObject { putJsonArray("option_ids") { options.mapNotNull { it.toLongOrNull() }.forEach { add(JsonPrimitive(it)) } } },
        )
        val poll = result.poll
        return PollVoteResponse(
            poll = poll?.let(Legacy::poll),
            vote = poll?.options?.filter { it.chosen }?.map { it.id.toString() } ?: options,
        )
    }

    suspend fun removePollVote(postId: Int, pollName: String): PollVoteResponse {
        val id = pollId(postId, pollName)
        val result = send<ApiPollEnvelope>("PUT", "api/v1/polls/$id/votes", buildJsonObject { put("option_ids", buildJsonArray { }) })
        return PollVoteResponse(poll = result.poll?.let(Legacy::poll), vote = emptyList())
    }

    /**
     * Buys tickets and answers the buyer's total afterwards. [key] is the
     * Idempotency-Key the server requires: a retry of the same purchase after a
     * timeout must reuse it, or tickets that were in fact bought are paid twice.
     * With [isRandom] the server picks the count and ignores [quantity].
     */
    suspend fun participateInLottery(lotteryId: Int, quantity: Int, isRandom: Boolean, key: String): Int? =
        api.decode<ApiLotteryPurchase>(
            api.sendRaw(
                "POST",
                "api/v1/lotteries/$lotteryId/tickets",
                buildJsonObject {
                    put("tickets", if (isRandom) 0 else quantity)
                    put("random", isRandom)
                },
                headers = mapOf("Idempotency-Key" to key),
            ),
        ).tickets

    /** The first post's lottery on its own, for a refresh that should not reload the thread. */
    suspend fun lottery(postId: Int): PostLottery? =
        optional { get<ApiLotteryEnvelope>("api/v1/posts/$postId/lottery") }?.lottery?.let(Legacy::lottery)

    suspend fun lotteryParticipants(lotteryId: Int): List<LotteryParticipant> =
        get<ApiLotteryParticipants>("api/v1/lotteries/$lotteryId/participants").participants.map(Legacy::lotteryHolder)

    /**
     * The composer still builds the old plugin's payload; the field names are
     * translated here rather than in the screen.
     */
    suspend fun createLottery(postId: Int, payloadJson: String): LotteryCreateResponse {
        val old = json.parseToJsonElement(payloadJson).jsonObject
        fun s(name: String) = (old[name] as? JsonPrimitive)?.contentOrNull
        fun i(name: String) = (old[name] as? JsonPrimitive)?.intOrNull
        val body = buildJsonObject {
            put("title", s("title").orEmpty())
            s("draw_at")?.let { put("draw_at", it) }
            i("min_tickets_per_user")?.let { put("min_tickets", it) }
            i("max_tickets_per_user")?.let { put("max_tickets", it) }
            i("min_participants")?.let { put("min_participants", it) }
            i("max_participants")?.let { put("max_participants", it) }
            i("min_trust_level")?.let { put("min_trust_level", it) }
            put("prizes", old["levels"] as? JsonArray ?: JsonArray(emptyList()))
        }
        val created = send<ApiLotteryEnvelope>("POST", "api/v1/posts/$postId/lottery", body)
        return LotteryCreateResponse(success = FlexibleBool(true), id = created.lottery?.id)
    }

    suspend fun createRedEnvelope(topicId: Int, totalPoints: Int, totalCount: Int): RedEnvelopeResponse {
        val created = send<ApiRedEnvelopeEnvelope>(
            "POST",
            "api/v1/topics/$topicId/red-envelope",
            buildJsonObject {
                put("total", totalPoints)
                put("count", totalCount)
            },
        )
        return RedEnvelopeResponse(success = FlexibleBool(true), id = created.redEnvelope?.id)
    }

    // --------------------------------------------------- profile / settings

    /** Account preferences live on the website; only the profile fields carry over. */
    suspend fun updatePreferences(username: String, items: List<Pair<String, String>>) {
        updateProfile(username, items)
    }

    suspend fun updateProfile(username: String, items: List<Pair<String, String>>) {
        val fields = items.toMap()
        val current = runCatchingCancellable { get<ApiUserEnvelope>("api/v1/me").user }.getOrNull()
        val known = setOf("name", "bio_raw", "website", "location")
        if (fields.keys.none { it in known }) return
        call(
            "PUT",
            "api/v1/me",
            buildJsonObject {
                put("name", fields["name"] ?: current?.name.orEmpty())
                put("bio", fields["bio_raw"] ?: current?.bioRaw.orEmpty())
                put("website", fields["website"] ?: current?.website.orEmpty())
                put("location", fields["location"] ?: current?.location.orEmpty())
            },
        )
    }

    suspend fun pickAvatar(username: String, uploadId: Int, type: String = "uploaded") {
        val path = if (type == "profile_background" || type == "card_background") "api/v1/me/cover" else "api/v1/me/avatar"
        call("PUT", path, buildJsonObject { put("upload_id", uploadId) })
    }

    /** The profile cover: an upload made first, then pointed at by id. */
    suspend fun setCover(uploadId: Int) {
        call("PUT", "api/v1/me/cover", buildJsonObject { put("upload_id", uploadId) })
    }

    suspend fun removeCover() {
        call("DELETE", "api/v1/me/cover")
    }

    suspend fun revokeAssociatedAccount(username: String, provider: String) {
        call("DELETE", "api/v1/me/identities/$provider")
    }

    /** Throws [CaptchaCancelled] if the reader closes the challenge. */
    suspend fun requestPasswordReset(login: String) {
        Captcha.guarded(api, Captcha.Action.PasswordReset, upfront = true) { captcha ->
            call("POST", "api/v1/auth/password-reset", buildJsonObject {
                put("email", login.trim())
                captcha?.let { put("captcha", it) }
            })
        }
    }

    /** The native reauth window is short and unknown to the client; always ask. */
    suspend fun trustedSession(): SessionTrustResponse = SessionTrustResponse(failed = "FAILED")

    suspend fun confirmSession(password: String): SessionTrustResponse {
        call("POST", "api/v1/me/reauth", buildJsonObject { put("password", password) })
        return SessionTrustResponse(success = "OK")
    }

    suspend fun listSecondFactors(): SecondFactorsResponse {
        val status = get<ApiTwoFactorStatus>("api/v1/me/two-factor")
        return SecondFactorsResponse(totps = if (status.enabled) listOf(TOTPDevice(id = 1, name = "TOTP")) else emptyList())
    }

    /** The QR the old screen drew came from the server; it gets the otpauth URI to render instead. */
    suspend fun createTotp(): TOTPCreateResponse {
        val setup = send<ApiTwoFactorSetup>("POST", "api/v1/me/two-factor")
        return TOTPCreateResponse(key = setup.secret, qr = setup.uri)
    }

    suspend fun enableTotp(token: String, name: String) {
        call("PUT", "api/v1/me/two-factor", buildJsonObject { put("code", token) })
    }

    suspend fun disableSecondFactor() {
        call("DELETE", "api/v1/me/two-factor")
    }

    suspend fun generateBackupCodes(): BackupCodesResponse =
        BackupCodesResponse(send<ApiBackupCodes>("POST", "api/v1/me/two-factor/backup-codes").backupCodes)

    suspend fun revokeAuthToken(username: String, tokenId: Int?) {
        if (tokenId == null) call("DELETE", "api/v1/me/sessions") else call("DELETE", "api/v1/me/sessions/$tokenId")
    }

    // -------------------------------------------------------------- uploads

    private suspend fun uploadImage(body: okhttp3.RequestBody, fileName: String, mimeType: String): DiscourseUpload {
        val kind = if (mimeType.startsWith("image/")) "image" else "attachment"
        val raw = api.multipart("api/v1/uploads", mapOf("kind" to kind), fileName, mimeType, body)
        val upload = api.decode<ApiUploadEnvelope>(raw).upload
        upload.url?.let { uploadUrls[upload.id] = it }
        return Legacy.upload(upload)
    }

    private suspend fun uploadVideo(body: okhttp3.RequestBody, fileName: String, mimeType: String): DiscourseUpload {
        val raw = api.multipart("api/v1/videos", emptyMap(), fileName, mimeType, body)
        val upload = Legacy.videoUpload(api.decode<ApiVideoEnvelope>(raw).video, fileName)
        // Chat attaches by upload id; a video's "id" points at its tag instead.
        upload.shortUrl?.let { uploadUrls[upload.id] = it }
        return upload
    }

    suspend fun uploadComposerMedia(data: ByteArray, fileName: String, mimeType: String): DiscourseUpload =
        if (mimeType.startsWith("video/")) uploadVideo(api.bytesBody(data, mimeType), fileName, mimeType)
        else uploadImage(api.bytesBody(data, mimeType), fileName, mimeType)

    suspend fun uploadUserImage(data: ByteArray, fileName: String, mimeType: String, type: String): DiscourseUpload =
        uploadImage(api.bytesBody(data, mimeType), fileName, mimeType)

    suspend fun uploadMediaFile(
        file: java.io.File,
        fileName: String,
        mimeType: String,
        onProgress: ((Float) -> Unit)? = null,
    ): DiscourseUpload {
        val body = api.fileBody(file, mimeType, onProgress)
        return if (mimeType.startsWith("video/")) uploadVideo(body, fileName, mimeType) else uploadImage(body, fileName, mimeType)
    }

    /**
     * The server makes its own poster for a video; this one only ever serves as
     * the composer's preview, so an ordinary image upload is enough.
     */
    suspend fun uploadVideoPoster(data: ByteArray, videoSha1: String): DiscourseUpload =
        uploadImage(api.bytesBody(data, "image/jpeg"), "$videoSha1.jpg", "image/jpeg")

    /** `upload://` and `|video` markdown are Discourse's; the native renderer has its own. */
    private fun toNativeMarkdown(raw: String): String =
        VIDEO_COMPOSER_MARKDOWN.replace(raw) { "[video id=${it.groupValues[1]}]" }

    // ----------------------------------------------------------------- chat

    @Volatile private var overview: ApiChatOverview? = null

    private suspend fun chatOverview(): ApiChatOverview {
        val fresh = get<ApiChatOverview>("api/v1/chat")
        fresh.directs.forEach { directUsers[it.id] = it.with }
        overview = fresh
        return fresh
    }

    private fun isDirect(channelId: Int): Boolean = directUsers.containsKey(channelId)

    /** Channels (joined first), then groups and directs, which the old model calls direct messages. */
    suspend fun chatChannels(): ChatChannelsResponse {
        val data = chatOverview()
        val me = auth.username ?: this.me?.username
        val channels = data.channels.sortedByDescending { it.joined }.map(Legacy::channel)
        val dms = data.groups.map { Legacy.group(it, me) } + data.directs.map(Legacy::direct)
        val tracking = (data.channels.map { it.id to (it.unreadCount to it.mentionCount) } +
            data.groups.map { it.id to (it.unreadCount to 0) } +
            data.directs.map { it.id to (it.unreadCount to 0) })
            .associate { (id, counts) ->
                id.toString() to ChatTrackingState(unreadCount = counts.first, mentionCount = counts.second)
            }
        return ChatChannelsResponse(
            publicChannels = channels,
            directMessageChannels = dms.sortedByDescending { it.lastMessage?.createdAt.orEmpty() },
            tracking = ChatTrackingReport(tracking),
        )
    }

    private suspend fun chatPage(channelId: Int, pageSize: Int, targetMessageId: Int?, direction: String?): ApiChatPage {
        val query = mutableListOf("limit" to pageSize.coerceIn(1, 100).toString(), "read" to "0")
        if (targetMessageId != null) {
            query += if (direction == "future") "after" to targetMessageId.toString() else "before" to targetMessageId.toString()
        }
        val user = directUsers[channelId]
        return if (user != null) {
            if (channelId < 0) {
                val page = get<ApiChatPage>("api/v1/messages/$user", query)
                page.conversationId?.let { directUsers[it] = user }
                page
            } else {
                get("api/v1/messages/$user", query)
            }
        } else {
            get("api/v1/chat/channels/$channelId/messages", query)
        }
    }

    suspend fun chatMessages(
        channelId: Int,
        pageSize: Int = 50,
        targetMessageId: Int? = null,
        direction: String? = null,
        fetchFromLastRead: Boolean = false,
    ): ChatMessagesResponse {
        val page = chatPage(channelId, pageSize, targetMessageId, direction)
        return Legacy.chatPage(page, channelId, chatVideos(page))
    }

    /** Streams for the uploaded videos on a chat page, looked up like a topic's (see [withVideos]). */
    private suspend fun chatVideos(page: ApiChatPage): Map<String, ApiVideo?> {
        val ids = page.messages.flatMap { message -> Legacy.chatVideoMarkup.findAll(message.html).map { it.groupValues[1] } }.toSet()
        if (ids.isEmpty()) return emptyMap()
        return coroutineScope {
            ids.map { sha1 ->
                async { sha1 to runCatchingCancellable { get<ApiVideoEnvelope>("api/v1/videos/$sha1").video }.getOrNull() }
            }.awaitAll().toMap()
        }
    }

    /** The disk cache replays through the legacy decoder, so it stores the legacy shape. */
    suspend fun chatMessagesWithRaw(
        channelId: Int,
        pageSize: Int = 50,
        targetMessageId: Int? = null,
        direction: String? = null,
        fetchFromLastRead: Boolean = false,
    ): Pair<ChatMessagesResponse, String> {
        val response = chatMessages(channelId, pageSize, targetMessageId, direction, fetchFromLastRead)
        return response to json.encodeToString(ChatMessagesResponse.serializer(), response)
    }

    // Threads, chat search, edits, reactions and typing are not in the native
    // chat; their entry points are hidden and these answer empty.
    suspend fun chatThreads(channelId: Int, limit: Int = 10, offset: Int = 0): ChatThreadsResponse = ChatThreadsResponse()

    suspend fun currentUserChatThreads(limit: Int = 10, offset: Int = 0): ChatThreadsResponse = ChatThreadsResponse()

    suspend fun chatThreadMessages(
        channelId: Int,
        threadId: Int,
        pageSize: Int = 50,
        targetMessageId: Int? = null,
        direction: String? = null,
    ): ChatMessagesResponse = ChatMessagesResponse()

    suspend fun chatSearch(
        query: String,
        limit: Int = 20,
        offset: Int = 0,
        sort: String = "latest",
        excludeThreads: Boolean = false,
    ): ChatSearchResponse = ChatSearchResponse()

    /**
     * Files travel inside the message as markdown now; `upload_ids` was the old
     * server's separate attachment list.
     */
    suspend fun createChatMessage(
        channelId: Int,
        message: String,
        threadId: Int? = null,
        inReplyToId: Int? = null,
        uploadIds: List<Int> = emptyList(),
    ): ChatCreateMessageResponse {
        val images = uploadIds.mapNotNull { uploadUrls[it] }.map { url ->
            if (url.startsWith(Legacy.VIDEO_URL_PREFIX)) "[video id=${url.removePrefix(Legacy.VIDEO_URL_PREFIX)}]" else "![]($url)"
        }
        val raw = (listOf(message.trim()).filter { it.isNotEmpty() } + images).joinToString("\n")
        val body = buildJsonObject {
            put("raw", toNativeMarkdown(raw))
            inReplyToId?.let { put("reply_to", it) }
        }
        val user = directUsers[channelId]
        val sent = if (user != null) {
            send<ApiChatSent>("POST", "api/v1/messages/$user", body)
        } else {
            send<ApiChatSent>("POST", "api/v1/chat/channels/$channelId/messages", body)
        }
        return ChatCreateMessageResponse(success = "OK", messageId = sent.message?.id)
    }

    suspend fun updateChatMessage(channelId: Int, messageId: Int, message: String) {
        throw unavailable()
    }

    suspend fun deleteChatMessage(channelId: Int, messageId: Int) {
        call("DELETE", "api/v1/chat/messages/$messageId")
    }

    suspend fun reactToChatMessage(channelId: Int, messageId: Int, emoji: String, add: Boolean) {
        throw unavailable()
    }

    suspend fun presenceUpdate(present: List<String>, leave: List<String>) = Unit

    suspend fun presenceGet(channels: List<String>): PresenceGetResponse = PresenceGetResponse()

    suspend fun chatChannel(channelId: Int): ChatChannelResponse {
        val data = overview?.takeIf { cached ->
            cached.channels.any { it.id == channelId } || cached.groups.any { it.id == channelId } || cached.directs.any { it.id == channelId }
        } ?: chatOverview()
        val me = auth.username ?: this.me?.username
        data.channels.firstOrNull { it.id == channelId }?.let { return ChatChannelResponse(Legacy.channel(it)) }
        data.groups.firstOrNull { it.id == channelId }?.let { return ChatChannelResponse(Legacy.group(it, me)) }
        data.directs.firstOrNull { it.id == channelId }?.let { return ChatChannelResponse(Legacy.direct(it)) }
        directUsers[channelId]?.let { return ChatChannelResponse(Legacy.direct(ApiDirect(id = channelId, with = it))) }
        throw DiscourseError.BadResponse(404)
    }

    suspend fun chatChannelMembers(channelId: Int, limit: Int = 50): ChatMembershipsResponse {
        if (isDirect(channelId) || overview?.groups?.none { it.id == channelId } != false) return ChatMembershipsResponse()
        val members = get<ApiGroupMembers>("api/v1/chat/groups/$channelId/members").members
        return ChatMembershipsResponse(members.take(limit).map { ChatMemberRow(Legacy.chatUser(it.username, it.avatar)) })
    }

    /** `never` is mute; anything else takes the mute off. */
    suspend fun setChatNotificationLevel(channelId: Int, level: String) {
        if (channelId < 0) return
        call(if (level == "never") "PUT" else "DELETE", "api/v1/chat/conversations/$channelId/mute")
    }

    suspend fun leaveChatChannel(channelId: Int) {
        if (isDirect(channelId)) throw unavailable()
        call("DELETE", "api/v1/chat/channels/$channelId/membership")
    }

    suspend fun markChatChannelRead(channelId: Int, messageId: Int) {
        if (channelId < 0) return
        call("PUT", "api/v1/chat/conversations/$channelId/read", buildJsonObject { put("message_id", messageId) })
    }

    suspend fun markChatThreadRead(channelId: Int, threadId: Int, messageId: Int) = Unit

    // -------------------------------------------------------------- externals

    /** Klipy is an external host, not the site. */
    suspend fun klipySearch(query: String, pos: String? = null): KlipySearchResponse {
        if (DiscourseConfig.KLIPY_API_KEY.isEmpty()) throw DiscourseError.BadResponse(401)
        val target = "https://api.klipy.com/v2/search".toHttpUrl().newBuilder()
            .addQueryParameter("key", DiscourseConfig.KLIPY_API_KEY)
            .addQueryParameter("q", query)
            .addQueryParameter("media_filter", "gif")
            .addQueryParameter("limit", "24")
            .addQueryParameter("pos", pos ?: "0")
            .build()
        return api.decode(api.getExternal(target))
    }

    // ---------------------------------------------------------------- misc

    /**
     * The native server answers in whatever language the request asks for, so
     * there is no account language to adopt: the app's own choice is the one.
     */
    suspend fun effectiveLocale(): String? = null

    val timezone: String get() = TimeZone.getDefault().id

    fun avatarUrl(template: String?, size: Int = 120): String? = DiscourseConfig.avatarUrl(template, size)
}

private val POLL_MARKUP = Regex("""class="[^"]*\bpoll\b""")
private val VIDEO_MARKUP = Regex("""<div class="video-embed" data-provider="upload" data-video-id="([0-9a-fA-F]{40})"></div>""")
private val VIDEO_COMPOSER_MARKDOWN = Regex("""!\[[^\]]*\|video]\(video:([0-9a-fA-F]{40})\)""")
