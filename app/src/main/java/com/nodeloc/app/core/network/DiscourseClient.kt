@file:OptIn(ExperimentalSerializationApi::class)

package com.nodeloc.app.core.network

import com.nodeloc.app.core.util.rethrowIfCancellation
import com.nodeloc.app.core.util.runCatchingCancellable
import android.util.Log
import com.nodeloc.app.BuildConfig
import com.nodeloc.app.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonNamingStrategy
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

/** The session a request was issued under. See [DiscourseAuth.generation]. */
@JvmInline
value class AuthGeneration(val value: Int)

/** Mutable auth state shared by every request. */
class DiscourseAuth {
    @Volatile var userApiKey: String? = null
    @Volatile var csrfToken: String? = null
    @Volatile var username: String? = null

    /** Set when a website (cookie) session is active. */
    @Volatile var hasSession: Boolean = false

    val isAuthenticated: Boolean get() = userApiKey != null || hasSession

    /**
     * A sign-in attempt has to clear these to get a clean CSRF token, but a
     * *failed* attempt must not cost the user the session they already had.
     */
    data class Snapshot(
        val userApiKey: String?,
        val csrfToken: String?,
        val username: String?,
        val hasSession: Boolean,
    )

    fun snapshot(): Snapshot = Snapshot(userApiKey, csrfToken, username, hasSession)

    fun restore(snapshot: Snapshot) {
        userApiKey = snapshot.userApiKey
        csrfToken = snapshot.csrfToken
        username = snapshot.username
        hasSession = snapshot.hasSession
    }

    /**
     * Raised when the server rejects the session itself, from any call.
     *
     * Set post-construction by `ServiceLocator`: the client is built before the
     * session repository exists, so this cannot be a constructor argument.
     */
    @Volatile
    var onSessionRejected: (() -> Unit)? = null

    private val rejected = AtomicBoolean(false)

    /**
     * Bumped whenever the credential changes, and stamped on every request.
     *
     * Without it, a request issued under the *old* session and answered after
     * the user signed in again would sign them straight back out — the reply
     * is about a session that no longer exists.
     */
    private val generationCounter = AtomicInteger()

    val generation: Int get() = generationCounter.get()

    /** A sign-in makes the session worth trusting again. */
    fun resetRejection() {
        generationCounter.incrementAndGet()
        rejected.set(false)
    }

    /**
     * Four conditions, none of them optional:
     *
     * - the reply is about the session we still hold, not one already replaced.
     * - not authenticated, so nothing to invalidate. This alone covers the
     *   sign-in flow, which clears the credential before fetching CSRF.
     * - the path is part of signing in, where a rejection is the *answer*, not
     *   a verdict on an existing session. The OAuth flow's own `currentUser`
     *   call is deliberately absent: rejected there means the key really is
     *   dead.
     * - only Discourse's own `not_logged_in` / `invalid_access`; a bare 403 is
     *   far more often a permission problem, and behind Cloudflare it is a
     *   challenge. `invalid_access` is itself checked before it gets this far —
     *   see `DiscourseClient.sessionIsGone` — because Discourse says it both
     *   for a dead credential and for a topic somebody has not earned.
     * - once. A screen firing three requests in parallel would otherwise sign
     *   out three times and toast three times.
     */
    internal fun noteSessionRejected(path: String, requestGeneration: Int) {
        if (requestGeneration != generation) return
        if (!isAuthenticated) return
        if (path.trimStart('/') in SESSION_SAFE_PATHS) return
        // Compare-and-set, not read-then-write: three requests failing
        // together each read the flag before any of them had written it, so
        // the "once" this latch exists to guarantee was two or three.
        if (!rejected.compareAndSet(false, true)) return
        onSessionRejected?.invoke()
    }

    private companion object {
        val SESSION_SAFE_PATHS = setOf(
            "session/csrf.json",
            "session",
            "users",
            "u/confirm-session",
            "session/forgot_password",
        )
    }
}

/**
 * The one place network I/O happens.
 *
 * Reading is public (`login_required = false`); authenticated calls attach
 * either a User-Api-Key header or the website session — never a hand-built
 * Cookie header, see [PersistentCookieJar].
 */
class DiscourseClient(
    /** This install's stable id, sent to the MessageBus so the server can tell devices apart. */
    private val clientId: String,
    val auth: DiscourseAuth,
    val cookieJar: PersistentCookieJar,
    /** Only the tests override this; everything else talks to the site. */
    private val baseUrl: String = DiscourseConfig.BASE_URL,
) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        // Every request in the app, plus all of Coil's image loads, goes to one
        // host. A blocking `execute()` ignored this cap; `enqueue` respects it,
        // and the default of 5 would throttle the whole app to a crawl.
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 15 })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        // readTimeout only bounds the gap between bytes, so a slow trickle can
        // hang a screen indefinitely without this.
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Writes never replay.
     *
     * OkHttp resends a request after a connection reset when it judges it
     * recoverable, which for a POST means a reply posted twice, a reward
     * charged twice, or two lotteries. The flag is client-scoped with no
     * per-request override, so writes need a client of their own.
     */
    private val writeHttp: OkHttpClient = http.newBuilder()
        .retryOnConnectionFailure(false)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)   // uploads
        .build()

    /**
     * The long poll is *supposed* to hang, so it gets a dispatcher of its own —
     * otherwise it would hold a foreground slot for 25 seconds at a time.
     */
    private val busHttp: OkHttpClient = http.newBuilder()
        .readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .dispatcher(Dispatcher())
        .build()

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            isLenient = true
            coerceInputValues = true
            namingStrategy = JsonNamingStrategy.SnakeCase
        }

        /**
         * How long an ignore lasts.
         *
         * Long enough to read as permanent, and it has to be *some* number:
         * see [ignoreUser]. Taken back by hand from the settings list.
         */
        private const val IGNORE_DAYS = 3650L

        private const val TAG = "DiscourseAPI"

        /** What [currentUser] asks; 200 with a session, 404 without one. */
        private const val SESSION_PROBE_PATH = "session/current.json"

        /** The one attribute [effectiveLocale] wants out of a whole page. */
        private val HTML_LANG = Regex("""<html[^>]*\blang="([^"]+)"""")

        /**
         * Faults whose `errors[]` is worse than the app string it would
         * replace: these are already understood here, and ours are in the
         * device's language rather than the site's.
         */
        private val NON_QUOTABLE_FAULTS =
            setOf("not_logged_in", "invalid_access", "not_found", "rate_limit")
    }

    // ------------------------------------------------------------- pipeline

    private fun url(path: String, query: List<Pair<String, String>> = emptyList()): HttpUrl {
        val builder = "$baseUrl/${path.trimStart('/')}".toHttpUrl().newBuilder()
        query.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build()
    }

    private fun requestBuilder(url: HttpUrl, includeCsrf: Boolean): Request.Builder {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
            // Which session this request belongs to, so a late answer cannot
            // be mistaken for a verdict on a newer one.
            .tag(AuthGeneration::class.java, AuthGeneration(auth.generation))
        val key = auth.userApiKey
        if (key != null) {
            builder.header("User-Api-Key", key)
            builder.header("User-Api-Client-Id", clientId)
        }
        if (auth.hasSession) {
            builder.header("Origin", DiscourseConfig.BASE_URL)
            builder.header("Referer", DiscourseConfig.BASE_URL)
        }
        if (key != null || auth.hasSession) {
            builder.header("X-Requested-With", "XMLHttpRequest")
            builder.header("Discourse-Present", "true")
        }
        if (includeCsrf || auth.hasSession) {
            auth.csrfToken?.let { builder.header("X-CSRF-Token", it) }
        }
        return builder
    }

    /**
     * Raw bytes from an absolute URL — an already-uploaded image fetched back
     * for editing. Goes through the same cancellable path as everything else
     * rather than handing out the OkHttp client.
     */
    suspend fun fetchBytes(absoluteUrl: String): ByteArray {
        val target = absoluteUrl.toHttpUrlOrNull() ?: throw DiscourseError.BadResponse(400)
        val request = Request.Builder().url(target).get().build()
        val response = try {
            http.newCall(request).await()
        } catch (error: IOException) {
            throw DiscourseError.Transport(error)
        }
        return response.use {
            if (!it.isSuccessful) throw DiscourseError.BadResponse(it.code)
            withContext(Dispatchers.IO) { it.body.bytes() }
        }
    }

    /** Which client a request belongs on. */
    private enum class Channel { Read, Write, Bus }

    /** Single funnel: maps transport failures and non-2xx statuses. */
    suspend fun perform(request: Request): String = perform(request, Channel.Write)

    private suspend fun perform(request: Request, channel: Channel): String {
        val client = when (channel) {
            Channel.Read -> http
            Channel.Write -> writeHttp
            Channel.Bus -> busHttp
        }
        val (response, body) = try {
            client.newCall(request).awaitWithBody()
        } catch (error: IOException) {
            throw DiscourseError.Transport(error)
        }
        if (!response.isSuccessful) {
            val error = classify(response, body, request)
            if (error.isNotLoggedIn) {
                val stamped = request.tag(AuthGeneration::class.java)?.value
                if (stamped != null && sessionIsGone(error, request)) {
                    auth.noteSessionRejected(request.url.encodedPath, stamped)
                }
            }
            throw error
        }
        return body
    }

    /**
     * Whether a rejection means the session is dead, or only that this reader
     * may not have that particular thing.
     *
     * `invalid_access` is Discourse's word for both, and discourse-read-permission
     * raises it for a topic above the reader's trust level: every request the
     * reader makes for one — the nested tree, `t/{id}/posts` — comes back in the
     * exact shape of a revoked credential. Believing it signed people out of the
     * app for having scrolled into a thread they had not earned yet.
     *
     * So it is asked instead: `session/current.json` answers 200 for a live
     * session and 404 for none. Only a definite "nobody is signed in" counts —
     * a timeout or a 500 leaves the session alone, because a network that
     * cannot answer is not the same as an answer.
     */
    private suspend fun sessionIsGone(error: DiscourseError, request: Request): Boolean {
        if (error.isSessionGone) return true
        // The probe rejected in its own right is the answer, not a question to
        // ask again.
        if (request.url.encodedPath.trimStart('/') == SESSION_PROBE_PATH) return true
        return runCatchingCancellable { currentUser() }.fold(
            onSuccess = { false },
            onFailure = { probe -> probe is DiscourseError && (probe.isNotFound || probe.isNotLoggedIn) },
        )
    }

    /**
     * Runs a call and reads its body under one cancellable suspension.
     *
     * A blocking `execute()` left the call running after the coroutine that
     * wanted it was gone: every abandoned screen kept a request alive, and
     * stopping the message bus did not stop its poll for another 25 seconds.
     *
     * The body is read here, on okhttp's own callback thread, rather than in a
     * `withContext(IO)` afterwards. That is not a detail: headers arrive long
     * before a slow body finishes, and only `invokeOnCancellation` fires the
     * moment cancellation starts. A handler on the caller's Job fires when that
     * job *completes* — which means waiting for the very read it means to stop.
     *
     * okhttp 5's own `executeAsync` lives in a side artifact that also drags
     * coroutines forward a version, so this is the twenty lines instead.
     */
    /** Headers only; the caller consumes the body itself. */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> runCatching { value.close() } }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
        })
    }

    private suspend fun Call.awaitWithBody(): Pair<Response, String> =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { runCatching { cancel() } }
            enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.use { it.body.string() }
                        continuation.resume(response to body) { _, _, _ -> }
                    } catch (error: Throwable) {
                        // Not just IOException: okhttp marks the callback
                        // signalled before calling this, so it will not call
                        // onFailure afterwards — anything escaping here would
                        // strand the coroutine forever.
                        runCatching { response.close() }
                        if (!continuation.isCancelled) continuation.resumeWithException(error)
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
            })
        }

    /**
     * `server: cloudflare` rides on *every* response here, so it proves
     * nothing. A real block carries `cf-mitigated`, or is a 403 with an HTML
     * body; Discourse's own 403 is JSON (`{"error_type":"not_logged_in"}`).
     */
    private fun classify(response: Response, body: String, request: Request): DiscourseError {
        val cfMitigated = response.header("cf-mitigated")
        val server = response.header("Server")?.lowercase().orEmpty()
        val contentType = response.header("Content-Type")?.lowercase().orEmpty()
        val challenged = cfMitigated != null ||
            (response.code == 403 && server.contains("cloudflare") && contentType.contains("text/html"))

        if (BuildConfig.DEBUG) {
            Log.w(
                TAG,
                "${response.code} ${request.method} ${request.url}\n" +
                    "  server=$server cf-mitigated=${cfMitigated ?: "-"} cf-ray=${response.header("cf-ray") ?: "-"}\n" +
                    "  body: ${body.take(200)}",
            )
        }
        if (challenged) return DiscourseError.Challenged
        // Parsed from the whole body, not the 200-char prefix: a long
        // validation message used to be truncated into invalid JSON and thrown
        // away, leaving the generic wording in its place.
        return DiscourseError.BadResponse(response.code, body.take(200), parseFault(response.code, body))
    }

    /**
     * Discourse's error envelope, where there is one.
     *
     * Deliberately narrow about which text is allowed to reach a screen. A 5xx
     * body can carry a Rails backtrace, and the wording for a dead session
     * ("您没有权限查看请求的资源。") is worse than our own — so both are read
     * for their `error_type` and neither contributes a message.
     */
    private fun parseFault(code: Int, body: String): ServerFault? {
        if (body.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null

        val type = (root["error_type"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
        val waitSeconds = (root["extras"] as? JsonObject)
            ?.get("wait_seconds")
            ?.let { it as? JsonPrimitive }
            ?.contentOrNull
            ?.toIntOrNull()

        // Only where `errors[]` says something the user can act on — a rejected
        // write. The rest have an app string that is better than the server's:
        // "not found" and "too many requests" are already known here, and ours
        // are in the device's language rather than the site's.
        val quotable = code in 400..499 && type !in NON_QUOTABLE_FAULTS
        val messages = if (!quotable) {
            emptyList()
        } else {
            // Core answers with `errors: []`; the plugins this site runs on
            // answer with a single `error`. Both were written for a person to
            // read — "您必须先回复此主题才能参与抽奖" is the whole answer to
            // why a tap did nothing, and reading only the plural spelling
            // replaced it with "操作失败".
            val plural = (root["errors"] as? JsonArray)
                .orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val singular = listOfNotNull((root["error"] as? JsonPrimitive)?.contentOrNull)
            // And `message`, which is how the check-in endpoint says why it
            // turned an attempt away — "尝试次数过多，请稍后再试" is the only
            // thing that distinguishes its 429 from any other.
            val named = listOfNotNull((root["message"] as? JsonPrimitive)?.contentOrNull)
            (plural + singular + named)
                .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .distinct()
                .take(3)
                // A pathological body should not become a wall of toast.
                .map { it.take(300) }
        }

        if (messages.isEmpty() && type == null && waitSeconds == null) return null
        return ServerFault(messages, type, waitSeconds)
    }

    @PublishedApi
    internal inline fun <reified T> decode(body: String): T = try {
        json.decodeFromString<T>(body)
    } catch (error: Throwable) {
        error.rethrowIfCancellation()
        if (BuildConfig.DEBUG) Log.e("DiscourseAPI", "decode ${T::class.simpleName} failed: ${error.message}")
        throw DiscourseError.Decoding(error)
    }

    suspend inline fun <reified T> get(path: String, query: List<Pair<String, String>> = emptyList()): T =
        decode(getRaw(path, query))

    suspend fun getRaw(path: String, query: List<Pair<String, String>> = emptyList()): String =
        perform(requestBuilder(url(path, query), includeCsrf = false).get().build(), Channel.Read)

    /**
     * The language the site renders this session in.
     *
     * Not `user.locale` from the profile, though that field exists and is
     * tempting. Discourse honours it only while `allow_user_locale` is on, and
     * otherwise serves everyone the site default while leaving whatever the
     * member once picked sitting in the column — and no endpoint this app can
     * reach reports that setting, so from here the column is unreadable on its
     * own. What the server actually renders needs no such caveat.
     *
     * `safe-mode` because it is the smallest page that still goes through the
     * normal locale machinery: five kilobytes against a hundred for the feed.
     */
    suspend fun effectiveLocale(): String? {
        // Every other request here asks for JSON, and Discourse answers a page
        // request carrying that header with 406 rather than rendering it. This
        // is the one call that genuinely wants markup.
        val request = requestBuilder(url("safe-mode"), includeCsrf = false)
            .header("Accept", "text/html")
            .get()
            .build()
        val html = perform(request, Channel.Read)
        return HTML_LANG.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    /** RFC 3986 unreserved set; everything else is percent-encoded. */
    private fun formEncode(items: List<Pair<String, String>>): String =
        items.joinToString("&") { (key, value) ->
            "${encodeComponent(key)}=${encodeComponent(value)}"
        }

    private fun encodeComponent(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")

    /**
     * Ordered, possibly repeated form keys. Rails reads `options[]=a&options[]=b`
     * as an array — a Map simply cannot express that, and poll votes and
     * `watched_category_ids[]` both need it.
     */
    suspend fun formItems(method: String, path: String, items: List<Pair<String, String>>): String {
        val body = formEncode(items).toRequestBody("application/x-www-form-urlencoded".toMediaType())
        return perform(requestBuilder(url(path), includeCsrf = true).method(method, body).build())
    }

    suspend fun post(path: String, form: Map<String, String>): String =
        formItems("POST", path, form.toList())

    /** Bodyless authenticated request (PUT / DELETE). */
    suspend fun send(method: String, path: String, query: List<Pair<String, String>> = emptyList()): String {
        val body = if (method == "GET" || method == "HEAD") null else ByteArray(0).toRequestBody(null)
        return perform(requestBuilder(url(path, query), includeCsrf = true).method(method, body).build())
    }

    suspend fun postJson(path: String, jsonBody: String): String {
        val body = jsonBody.toRequestBody("application/json".toMediaType())
        return perform(requestBuilder(url(path), includeCsrf = true).post(body).build())
    }

    /**
     * The same upload, streamed off disk.
     *
     * A trimmed clip is tens of megabytes and a phone has no business holding
     * one twice — once as the file and once as the request body. OkHttp reads
     * a file body in blocks, so this is flat in memory whatever the size.
     */
    suspend fun postMultipartFile(
        path: String,
        fields: Map<String, String>,
        fieldName: String,
        fileName: String,
        mimeType: String,
        file: java.io.File,
        onProgress: ((Float) -> Unit)? = null,
    ): String {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (name, value) -> builder.addFormDataPart(name, value) }
        builder.addFormDataPart(
            fieldName,
            fileName,
            // Both stream off disk; only one of them counts as it goes.
            if (onProgress == null) file.asRequestBody(mimeType.toMediaType())
            else ProgressRequestBody(file, mimeType.toMediaType(), onProgress),
        )
        return perform(requestBuilder(url(path), includeCsrf = true).post(builder.build()).build())
    }

    suspend fun postMultipart(
        path: String,
        fields: Map<String, String>,
        fieldName: String,
        fileName: String,
        mimeType: String,
        data: ByteArray,
    ): String {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (name, value) -> builder.addFormDataPart(name, value) }
        builder.addFormDataPart(
            fieldName,
            fileName,
            data.toRequestBody(mimeType.toMediaType()),
        )
        return perform(requestBuilder(url(path), includeCsrf = true).post(builder.build()).build())
    }

    // ------------------------------------------------------- public reading

    /**
     * Any of the site-wide topic lists: latest, hot, top, featured, new.
     *
     * The same names the category route spells as `l/{sort}` — Discourse
     * serves them at the root as well, which is why the feed and a node can
     * share one set of sort options. `new` answers 403 to a guest, so the
     * picker only offers it to someone signed in.
     */
    suspend fun topics(sort: String = "latest", page: Int = 0): LatestResponse =
        get("$sort.json", if (page > 0) listOf("page" to page.toString()) else emptyList())

    suspend fun latest(page: Int = 0): LatestResponse = topics("latest", page)

    /** Same `topic_list` envelope as [latest]; `period` is daily/weekly/monthly/all. */
    suspend fun top(period: String? = null): LatestResponse =
        get("top.json", period?.let { listOf("period" to it) } ?: emptyList())

    suspend fun site(): SiteResponse = get("site.json")

    suspend fun categories(includeSubcategories: Boolean = false): CategoriesResponse =
        get(
            "categories.json",
            if (includeSubcategories) listOf("include_subcategories" to "true") else emptyList(),
        )

    /** `path` is the parent/child slug pair (e.g. "technology/ai"). */
    suspend fun nodeTopics(path: String, categoryId: Int, sort: String = "latest", page: Int = 0): CategoryTopicsResponse =
        get(
            "c/$path/$categoryId/l/$sort.json",
            if (page > 0) listOf("page" to page.toString()) else emptyList(),
        )

    suspend fun sidebarNodes(): SidebarCommunitiesResponse = get("nodes.json")

    suspend fun nodeBrowse(parentCategoryId: Int, page: Int = 0, perPage: Int? = null): SidebarCommunitiesResponse {
        val query = mutableListOf("page" to page.toString())
        perPage?.let { query += "per_page" to it.toString() }
        return get("node/browse/$parentCategoryId.json", query)
    }

    suspend fun recentlyVisitedNodes(): SidebarCommunitiesResponse = get("node/recently-visited.json")

    suspend fun customFeeds(): SidebarCustomFeedsResponse = get("custom-feeds.json")

    suspend fun checkNodeSlug(slug: String): NodeSlugAvailabilityResponse =
        get("node/check-slug", listOf("slug" to slug))

    suspend fun topic(id: Int): TopicResponse = get("t/$id.json")

    /**
     * Bookmark the topic rather than a post in it.
     *
     * A row in a list knows the topic and nothing else — the first post's id
     * arrives with the topic itself — and this is the endpoint that takes the
     * one it has.
     */
    suspend fun bookmarkTopic(topicId: Int) { send("PUT", "t/$topicId/bookmark") }

    suspend fun removeTopicBookmarks(topicId: Int) { send("PUT", "t/$topicId/remove_bookmarks") }

    /** 0 muted, 1 regular, 2 tracking, 3 watching. */
    suspend fun setTopicNotificationLevel(topicId: Int, level: Int) {
        formItems("POST", "t/$topicId/notifications", listOf("notification_level" to level.toString()))
    }

    /**
     * Discourse's nested-replies view with server-side sort (top / new / old).
     * The slug only shapes the canonical URL — the id resolves the topic.
     */
    suspend fun nestedTopic(id: Int, slug: String = "topic", sort: String, page: Int = 0): NestedTopicResponse =
        get("n/$slug/$id.json", listOf("sort" to sort, "page" to page.toString()))

    suspend fun nestedChildren(topicId: Int, postNumber: Int, slug: String = "topic", sort: String, page: Int = 0): NestedChildrenResponse =
        get("n/$slug/$topicId/children/$postNumber.json", listOf("sort" to sort, "page" to page.toString()))

    /**
     * Pins a reply to the top of the thread, or takes the pin off.
     *
     * One endpoint for both, as the server has one: it answers with the whole
     * new list rather than with what happened, so the caller reads its own
     * post's membership out of the reply instead of assuming it flipped.
     * Staff only, root replies only, and the server caps the list at ten.
     */
    suspend fun toggleNestedPin(topicId: Int, postId: Int, slug: String = "topic"): NestedPinResponse =
        decode(formItems("PUT", "n/$slug/$topicId/pin", listOf("post_id" to postId.toString())))

    suspend fun topicPosts(topicId: Int, postIds: List<Int>): TopicPostsResponse =
        get("t/$topicId/posts.json", postIds.map { "post_ids[]" to it.toString() })

    /**
     * discourse-anyvideo: what to play after this one. Anonymous-readable.
     *
     * The extension matters here. The plugin's engine is mounted outside
     * Discourse's own routes, and the bare path falls through to the Ember
     * shell — a 200 of HTML — for anything not asking for JSON outright.
     */
    suspend fun videoSuggestions(excludeTopicId: Int? = null): VideoSuggestionsResponse =
        get(
            "anyvideo/videos/suggestions.json",
            excludeTopicId?.takeIf { it > 0 }
                ?.let { listOf("exclude_topic_id" to it.toString()) }
                .orEmpty(),
        )

    /**
     * Full-page search, and topics only whatever the term.
     *
     * `SearchController#show` hard-codes `type_filter: "topic"`, and
     * `Search#find_grouped_results` runs exactly one facet once a filter is
     * set — so `users` and `categories` come back empty here every time. The
     * other scopes each need their own endpoint; this is the only one that pages.
     */
    suspend fun search(term: String, page: Int = 1): SearchResponse =
        get("search.json", listOf("q" to term, "page" to page.toString()))

    /** Header search: every facet, but only five of each and no paging. */
    suspend fun searchEverything(term: String): SearchResponse =
        get("search/query.json", listOf("term" to term))

    /** Name-substring match on categories. 25 is the server ceiling. */
    suspend fun searchCategories(term: String, limit: Int = 25): CategorySearchResponse =
        decode(post("categories/search", mapOf("term" to term, "limit" to limit.toString())))

    /** The @-mention backend. 50 is `SEARCH_USERS_LIMIT`; the default is 20. */
    suspend fun searchUsers(term: String, limit: Int = 50): UserSearchResponse =
        get("u/search/users.json", listOf("term" to term, "limit" to limit.toString()))

    /** The other half of `#`: nodes come from the cached site, tags from here. */
    suspend fun searchTags(term: String, limit: Int = 5): TagSearchResponse =
        get("tags/filter/search.json", listOf("q" to term, "limit" to limit.toString()))

    /**
     * Every emoji the site knows, grouped.
     *
     * A quarter of a megabyte and unauthenticated, so it is fetched once and
     * held; the custom groups at the end are what makes it worth having.
     */
    suspend fun emojis(): Map<String, List<DiscourseEmoji>> = get("emojis.json")

    // ------------------------------------------------- editing and moderating

    /** The source text of one post — the only endpoint that carries `raw`. */
    suspend fun postDetail(postId: Int): PostDetail = get("posts/$postId.json")

    /**
     * Rewrites a post.
     *
     * The server decides whether this is allowed and says why when it is not:
     * past the edit window it answers `too_late_to_edit` rather than a bare
     * 403, and those words are what the reader should see. Nothing here
     * second-guesses that.
     */
    suspend fun updatePost(
        postId: Int,
        raw: String,
        editReason: String? = null,
        /**
         * Only the first post has one, and the endpoint only reads it there —
         * `changes[:title]` is set inside `if post.is_first_post?`. Top level
         * rather than under `post[]`, which is where the controller looks.
         */
        title: String? = null,
    ) {
        val items = mutableListOf("post[raw]" to raw)
        editReason?.takeIf { it.isNotBlank() }?.let { items += "post[edit_reason]" to it }
        title?.takeIf { it.isNotBlank() }?.let { items += "title" to it }
        formItems("PUT", "posts/$postId", items)
    }

    /**
     * Deletes a post — a soft delete, which staff can still see and recover.
     *
     * Never the first post: Discourse treats deleting that as deleting the
     * whole topic, and refuses it here (`can_delete_post?` returns false for
     * `is_first_post?`). [deleteTopic] is the other half.
     */
    suspend fun deletePost(postId: Int) {
        send("DELETE", "posts/$postId")
    }

    suspend fun recoverPost(postId: Int) {
        send("PUT", "posts/$postId/recover")
    }

    /**
     * discourse-vote. The direction is the state to end up in, so taking a vote
     * back is [VoteDirection.None] rather than a second call.
     */
    /** Who reacted to a post, grouped by face. Readable signed out, as the tally is. */
    suspend fun postReactionUsers(postId: Int): ReactionUsersResponse =
        get("discourse-reactions/posts/$postId/reactions-users.json")

    suspend fun castVote(
        postId: Int,
        direction: VoteDirection,
        /** Which face to vote with; without one the direction's default is cast. */
        reaction: String? = null,
    ): PostVoteResult = decode(
        formItems(
            "PUT",
            "vote/posts/$postId",
            listOfNotNull("direction" to direction.wire, reaction?.let { "reaction" to it }),
        ),
    )

    /**
     * Discourse's own flag — what the web's flag dialog posts.
     *
     * The reason is an id out of the site's `post_action_types` rather than a
     * constant here: this deployment has added two reasons of its own, and the
     * ids of those are the site's to choose. `flag_topic` stays false because
     * what the viewer has in front of it is a post, not the thread.
     */
    suspend fun flagPost(postId: Int, actionTypeId: Int, message: String? = null) {
        formItems(
            "POST",
            "post_actions",
            listOfNotNull(
                "id" to postId.toString(),
                "post_action_type_id" to actionTypeId.toString(),
                "flag_topic" to "false",
                message?.takeIf { it.isNotBlank() }?.let { "message" to it },
            ),
        )
    }

    /** Staff only. A locked post cannot be edited by its author. */
    suspend fun setPostLocked(postId: Int, locked: Boolean) {
        formItems("PUT", "posts/$postId/locked", listOf("locked" to locked.toString()))
    }

    suspend fun deleteTopic(topicId: Int) {
        send("DELETE", "t/$topicId")
    }

    /** `status` is Discourse's own: closed, pinned, archived, visible. */
    suspend fun setTopicStatus(topicId: Int, status: String, enabled: Boolean) {
        formItems(
            "PUT",
            "t/$topicId/status",
            listOf("status" to status, "enabled" to enabled.toString()),
        )
    }

    suspend fun user(username: String): UserResponse = get("u/$username.json")

    suspend fun userSummary(username: String): UserSummaryResponse = get("u/$username/summary.json")

    suspend fun accountDetail(username: String): AccountDetail = get("u/$username.json")

    suspend fun userBadges(username: String): UserBadgesResponse = get("user-badges/$username.json")

    /**
     * Saved bookmarks, which do not come from [userActions].
     *
     * There is no bookmark filter to pass it: `UserAction` dropped the type
     * years ago and nothing writes those rows any more, so asking for filter 3
     * returns an empty list forever rather than an error. Bookmarks have their
     * own table and their own route, and this is it. Pages, not offsets.
     */
    suspend fun bookmarks(username: String, page: Int = 0): UserBookmarksResponse =
        get("u/$username/bookmarks.json", listOf("page" to page.toString()))

    /**
     * Drafts the site is holding for this account, newest first.
     *
     * Includes the ones started in a browser, which is the point: the app has
     * always had a draft slot of its own and no idea any others existed.
     */
    suspend fun drafts(): DraftsResponse = get("drafts.json")

    /** filter: 1 likes given, 4 topics, 5 replies. Bookmarks are [bookmarks]. */
    suspend fun userActions(username: String, filter: Int, offset: Int = 0): UserActionsResponse =
        get(
            "user_actions.json",
            listOf("username" to username, "filter" to filter.toString(), "offset" to offset.toString()),
        )

    suspend fun pointsHistory(username: String, page: Int = 0): PointsHistoryResponse =
        get("u/$username/points-history.json", listOf("page" to page.toString()))

    suspend fun pointsTotal(username: String): PointsScoresResponse =
        get("u/$username/points-scores.json", listOf("page" to "0"))

    // ------------------------------------------------------- nodeloc plugin

    /** Whether the site runs the app plugin, and which optional pieces it has. */
    suspend fun mobileMeta(): MobileMeta = get("mobile/meta")

    /** Today, as the server counts it — the read side the check-in plugin lacks. */
    suspend fun checkinStatus(): CheckinStatus = get("mobile/checkin")

    suspend fun postSourcePreference(): PostSourcePreference = get("mobile/preferences/post_source")

    suspend fun setPostSourcePreference(level: Int): PostSourcePreference =
        decode(formItems("PUT", "mobile/preferences/post_source", listOf("level" to level.toString())))

    /** Takes the tails off everything already posted; see the plugin. */
    suspend fun clearPostSources() {
        send("DELETE", "mobile/preferences/post_source/history")
    }

    /** Where to deliver this account.s notifications on this handset. */
    suspend fun registerDevice(
        token: String,
        appVersion: String,
        locale: String,
        timezone: String,
        categories: List<String>,
    ) {
        post(
            "mobile/devices",
            mapOf(
                "token" to token,
                "platform" to "android",
                "app_version" to appVersion,
                "locale" to locale,
                "timezone" to timezone,
                "categories" to categories.joinToString(","),
            ),
        )
    }

    suspend fun unregisterDevice(token: String) {
        formItems("DELETE", "mobile/devices", listOf("token" to token))
    }

    suspend fun latestRelease(versionCode: Int, clientIdForRollout: String): ReleaseResponse =
        get(
            "mobile/releases/latest",
            listOf(
                "platform" to "android",
                "version_code" to versionCode.toString(),
                "client_id" to clientIdForRollout,
            ),
        )

    /**
     * Progress towards the next trust level.
     *
     * No `.json` suffix: the plugin's route already defaults the format, and
     * the username is matched by a route constraint that a suffix confuses.
     */
    suspend fun upgradeProgress(username: String): UpgradeProgress =
        get("u/$username/upgrade-progress")

    /** The directory endpoint returns a **bare JSON array**, unlike the detail one. */
    suspend fun appsDirectory(): List<DirectoryApp> = get("apps/directory.json")

    suspend fun app(slug: String): DirectoryAppResponse = get("apps/$slug.json")

    fun appWebviewUrl(installId: Int): String = "${DiscourseConfig.BASE_URL}/apps/installs/$installId/webview"

    suspend fun customGroupStyles(): List<CustomGroupStyleItem> = get("discourse_custom_badge/group-styles/list")

    suspend fun customBadgeStyles(): List<CustomBadgeStyleItem> = get("discourse_custom_badge/badge-styles/list")

    suspend fun checkUsername(username: String): UsernameCheckResponse =
        get("u/check_username.json", listOf("username" to username))

    suspend fun honeypot(): HoneypotResponse = get("session/hp.json")

    suspend fun checkEmail(email: String): EmailCheckResponse =
        get("u/check_email.json", listOf("email" to email))

    // -------------------------------------------------- authenticated reads

    /** Returns **404** when not signed in — that is the signal, not an error. */
    suspend fun currentUser(): CurrentUserResponse = get("session/current.json")

    /**
     * [limit] because the badge is counted from these rows: at the server's
     * default of 30, a user with more unread than that would see the count
     * stop at 30 while the list said otherwise.
     */
    suspend fun notifications(limit: Int = 60): NotificationsResponse =
        get("notifications.json", listOf("limit" to limit.toString()))

    suspend fun privateMessages(username: String): PrivateMessagesResponse =
        get("topics/private-messages/$username.json")

    suspend fun groupPrivateMessages(username: String, group: String): PrivateMessagesResponse =
        get("topics/private-messages-group/$username/$group.json")

    /**
     * Marks whole topics read — which for a conversation is the same thing.
     *
     * `dismiss_posts` sets `last_read_post_number` to the topic's highest, and
     * that pair is exactly what the inbox reads back as unread. One request for
     * the lot rather than a timing per conversation, and Discourse caps the
     * list at its own MAX_BULK_TOPIC_IDS.
     */
    suspend fun dismissTopicPosts(topicIds: List<Int>) {
        if (topicIds.isEmpty()) return
        val items = topicIds.map { "topic_ids[]" to it.toString() } +
            ("operation[type]" to "dismiss_posts")
        formItems("PUT", "topics/bulk", items)
    }

    // -------------------------------------------------------------- writing

    /**
     * The daily check-in.
     *
     * The endpoint is armoured against scripted claiming rather than against
     * clients: it wants a per-attempt nonce — echoed in both a header and the
     * body, and held in redis for an hour so a replay is refused — plus a
     * header naming the feature. Everything else it inspects (the JSON accept,
     * the XHR marker, the origin and the CSRF token) [requestBuilder] already
     * sends, so nothing here is a browser impersonation.
     *
     * Never retry this automatically. The rate limiter counts every attempt,
     * runs *ahead* of the already-checked-in branch, and only clears itself on
     * a successful claim — so a handful of hopeful calls locks the account out
     * of the feature for the rest of the window.
     */
    suspend fun checkin(): CheckinResponse {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val form = listOf("nonce" to nonce, "timestamp" to System.currentTimeMillis().toString())
        val body = formEncode(form).toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val request = requestBuilder(url("checkin"), includeCsrf = true)
            .header("X-Discourse-Checkin", "true")
            .header("X-Checkin-Nonce", nonce)
            // Only read when the browser-fingerprint gate is switched on, and
            // absent from okhttp's defaults — the one header this endpoint can
            // ask for that the rest of the app never needed.
            .header("Accept-Language", Locale.getDefault().toLanguageTag())
            .post(body)
            .build()
        return decode(perform(request))
    }

    suspend fun likePost(id: Int) {
        post("post_actions", mapOf("id" to id.toString(), "post_action_type_id" to "2", "flag_topic" to "false"))
    }

    suspend fun unlikePost(id: Int) {
        formItems("DELETE", "post_actions/$id", listOf("post_action_type_id" to "2"))
    }

    /** Answers with the new bookmark's id, which is what takes it off again. */
    suspend fun bookmark(postId: Int): BookmarkCreated =
        decode(
            post(
                "bookmarks",
                mapOf("bookmarkable_id" to postId.toString(), "bookmarkable_type" to "Post"),
            ),
        )

    /** Keyed on the bookmark, not the post — there is no delete-by-post route. */
    suspend fun removeBookmark(bookmarkId: Int) {
        send("DELETE", "bookmarks/$bookmarkId")
    }

    /**
     * What this handset would say about itself, when its owner has said it may.
     *
     * Set by `ServiceLocator` rather than passed down through every composer:
     * a tail belongs to the request, not to the screen that started it, and
     * threading it through would mean every future caller remembering to.
     */
    @Volatile
    var postSourceFields: (suspend () -> Map<String, String>)? = null

    private suspend fun withSource(form: Map<String, String>): Map<String, String> =
        form + (postSourceFields?.invoke().orEmpty())

    suspend fun reply(topicId: Int, raw: String, replyToPostNumber: Int? = null): CreatePostResponse {
        val form = mutableMapOf("raw" to raw, "topic_id" to topicId.toString())
        replyToPostNumber?.let { form["reply_to_post_number"] = it.toString() }
        return decode(post("posts", withSource(form)))
    }

    suspend fun createTopic(title: String, raw: String, categoryId: Int): CreatePostResponse =
        decode(
            post(
                "posts",
                withSource(
                    mapOf(
                        "title" to title,
                        "raw" to raw,
                        "category" to categoryId.toString(),
                        "archetype" to "regular",
                    ),
                ),
            ),
        )

    /**
     * A private message is a topic with a different archetype and people
     * instead of a node — the same endpoint, and the same response.
     */
    suspend fun createPrivateMessage(title: String, raw: String, recipient: String): CreatePostResponse =
        decode(
            post(
                "posts",
                mapOf(
                    "title" to title,
                    "raw" to raw,
                    "archetype" to "private_message",
                    "target_recipients" to recipient,
                ),
            ),
        )

    /**
     * The direct-message channel with [username], created if this is the first
     * time. Discourse returns the existing one otherwise, so this is safe to
     * call on every tap.
     */
    suspend fun openDirectMessageChannel(username: String): ChatChannelResponse =
        decode(formItems("POST", "chat/api/direct-message-channels", listOf("target_usernames[]" to username)))

    /**
     * How loudly this user's activity should reach you: normal, mute, ignore.
     *
     * PUT, not POST — the route is only declared for PUT, and posting to it
     * answers a 404 that reads like a missing user rather than a wrong verb.
     */
    suspend fun setUserNotificationLevel(username: String, level: String) {
        formItems("PUT", "u/$username/notification_level", listOf("notification_level" to level))
    }

    /**
     * Stop seeing someone: their posts, their replies, their messages.
     *
     * `ignore` is the only level of the three that will not save without a
     * deadline — `IgnoredUser` validates `expiring_at` for presence and the
     * controller runs it through `Time.parse`, so omitting it is a 500 rather
     * than a default of forever. Discourse's own dialog makes you pick a
     * duration for the same reason; this picks one far enough out to mean
     * "until it is taken back".
     *
     * Two more rules live on the server and are not worth duplicating here,
     * because the answer comes back as a message worth showing: staff cannot
     * be ignored, and the site decides through `ignore_allowed_groups` who is
     * allowed to ignore at all.
     */
    suspend fun ignoreUser(username: String) {
        val until = java.time.Instant.now()
            .plus(java.time.Duration.ofDays(IGNORE_DAYS))
            .toString()
        formItems(
            "PUT",
            "u/$username/notification_level",
            listOf("notification_level" to "ignore", "expiring_at" to until),
        )
    }

    /** Back to normal, which clears both the ignore and any mute beside it. */
    suspend fun unignoreUser(username: String) = setUserNotificationLevel(username, "normal")

    /**
     * Flag a chat message.
     *
     * A separate endpoint from `post_actions` and a differently named field —
     * `flag_type_id` here, `post_action_type_id` there — for the same set of
     * reasons out of `site.json`.
     */
    suspend fun flagChatMessage(
        channelId: Int,
        messageId: Int,
        flagTypeId: Int,
        message: String? = null,
    ) {
        formItems(
            "POST",
            "chat/api/channels/$channelId/messages/$messageId/flags",
            listOfNotNull(
                "flag_type_id" to flagTypeId.toString(),
                message?.takeIf { it.isNotBlank() }?.let { "message" to it },
            ),
        )
    }

    suspend fun joinNode(categoryId: Int): NodeMembershipResponse = decode(send("POST", "node/join/$categoryId"))

    suspend fun leaveNode(categoryId: Int): NodeMembershipResponse = decode(send("DELETE", "node/leave/$categoryId"))

    /** Without a CSRF header this answers 403 BAD CSRF. */
    suspend fun setCategoryNotification(categoryId: Int, level: Int) {
        post("category/$categoryId/notifications", mapOf("notification_level" to level.toString()))
    }

    suspend fun createNode(
        name: String,
        slug: String,
        description: String,
        colorHex: String?,
        parentCategoryId: Int,
    ): CreateCommunityResponse {
        val form = mutableMapOf(
            "name" to name,
            "description" to description,
            "slug" to slug,
            "parent_category_id" to parentCategoryId.toString(),
        )
        colorHex?.takeIf { it.isNotEmpty() }?.let { form["color"] = it.trim('#').uppercase() }
        return decode(post("node/create", form))
    }

    /**
     * [raw] is what the reposter wrote above the pointer. Left out, the server
     * posts the bare link, which is what a repost was before it could carry a
     * remark; sent, the server still guarantees the link is in there.
     */
    suspend fun repost(topicId: Int, categoryId: Int, title: String, raw: String? = null) {
        val form = mutableMapOf(
            "topic_id" to topicId.toString(),
            "category_id" to categoryId.toString(),
            "title" to title,
        )
        raw?.takeIf { it.isNotBlank() }?.let { form["raw"] = it }
        post("node/repost", form)
    }

    /** 打赏 — discourse-reward. */
    suspend fun giveReward(postId: Int, amount: Int, note: String? = null) {
        val form = mutableMapOf("post_id" to postId.toString(), "amount" to amount.toString())
        note?.takeIf { it.isNotEmpty() }?.let { form["note"] = it }
        post("reward/give", form)
    }

    suspend fun follow(username: String) { send("PUT", "follow/$username") }

    suspend fun unfollow(username: String) { send("DELETE", "follow/$username") }

    /**
     * Every unread notification, or just [id].
     *
     * The single-id form degrades safely: a deployment that ignored the
     * parameter would mark them all read, which is what this did
     * unconditionally before.
     */
    suspend fun markNotificationsRead(id: Int? = null) {
        val items = id?.let { listOf("id" to it.toString()) }.orEmpty()
        if (items.isEmpty()) send("PUT", "notifications/mark-read") else formItems("PUT", "notifications/mark-read", items)
    }

    /**
     * Read progress. `timings` maps post number → ms on screen; the server
     * marks those posts read and clears the topic's unread state.
     */
    suspend fun sendTopicTimings(topicId: Int, topicTimeMs: Int, timings: Map<Int, Int>) {
        val items = mutableListOf("topic_id" to topicId.toString(), "topic_time" to topicTimeMs.toString())
        timings.forEach { (postNumber, ms) -> items += "timings[$postNumber]" to ms.toString() }
        formItems("POST", "topics/timings", items)
    }

    /** `options[]` repeats once per selected option — a Map can't express it. */
    suspend fun votePoll(postId: Int, pollName: String, options: List<String>): PollVoteResponse {
        val items = mutableListOf("post_id" to postId.toString(), "poll_name" to pollName)
        options.forEach { items += "options[]" to it }
        return decode(formItems("PUT", "polls/vote", items))
    }

    suspend fun removePollVote(postId: Int, pollName: String): PollVoteResponse =
        decode(formItems("DELETE", "polls/vote", listOf("post_id" to postId.toString(), "poll_name" to pollName)))

    suspend fun participateInLottery(lotteryId: Int, quantity: Int, isRandom: Boolean): LotteryActionResponse =
        decode(
            formItems(
                "POST",
                "lottery/$lotteryId/participate",
                listOf("quantity" to quantity.toString(), "random" to isRandom.toString()),
            ),
        )

    /**
     * Keyed by **post id**, not topic id, and always a second request after the
     * post is saved — the plugin has no markup, the web client posts it from an
     * afterCreate hook.
     */
    suspend fun createLottery(postId: Int, payloadJson: String): LotteryCreateResponse =
        decode(postJson("lottery", payloadJson))

    /** Also a second request after the topic exists, for the same reason. */
    suspend fun createRedEnvelope(topicId: Int, totalPoints: Int, totalCount: Int): RedEnvelopeResponse =
        decode(
            post(
                "red-envelopes.json",
                mapOf(
                    "topic_id" to topicId.toString(),
                    "total_points" to totalPoints.toString(),
                    "total_count" to totalCount.toString(),
                ),
            ),
        )

    // --------------------------------------------------- profile / settings

    /** `PUT /u/:username` is users#update: ordered repeated keys, CSRF required. */
    suspend fun updatePreferences(username: String, items: List<Pair<String, String>>) {
        formItems("PUT", "u/$username.json", items)
    }

    suspend fun updateProfile(username: String, items: List<Pair<String, String>>) {
        formItems("PUT", "u/$username.json", items)
    }

    suspend fun pickAvatar(username: String, uploadId: Int, type: String = "uploaded") {
        formItems(
            "PUT",
            "u/$username/preferences/avatar/pick",
            listOf("upload_id" to uploadId.toString(), "type" to type),
        )
    }

    suspend fun revokeAssociatedAccount(username: String, provider: String) {
        formItems("POST", "u/$username/preferences/revoke-account", listOf("provider_name" to provider))
    }

    /** Discourse has no in-app password change; this mails a reset link. */
    suspend fun requestPasswordReset(login: String) {
        formItems("POST", "session/forgot_password", listOf("login" to login))
    }

    suspend fun trustedSession(): SessionTrustResponse = get("u/trusted-session")

    suspend fun confirmSession(password: String): SessionTrustResponse =
        decode(formItems("POST", "u/confirm-session", listOf("password" to password)))

    /** Note: the 2FA routes carry no username segment. */
    suspend fun listSecondFactors(): SecondFactorsResponse = decode(formItems("POST", "u/second_factors", emptyList()))

    suspend fun createTotp(): TOTPCreateResponse = decode(formItems("POST", "u/create_second_factor_totp", emptyList()))

    suspend fun enableTotp(token: String, name: String) {
        formItems(
            "POST",
            "u/enable_second_factor_totp",
            listOf("second_factor_token" to token, "name" to name),
        )
    }

    suspend fun disableSecondFactor() { formItems("PUT", "u/disable_second_factor", emptyList()) }

    suspend fun generateBackupCodes(): BackupCodesResponse = decode(formItems("PUT", "u/second_factors_backup", emptyList()))

    /** Revokes one session, or all others when [tokenId] is null. */
    suspend fun revokeAuthToken(username: String, tokenId: Int?) {
        val items = tokenId?.let { listOf("token_id" to it.toString()) } ?: emptyList()
        formItems("POST", "u/$username/preferences/revoke-auth-token", items)
    }

    // -------------------------------------------------------------- uploads

    /** `upload_type` replaces the `type` param Discourse dropped in 3.5. */
    suspend fun uploadComposerMedia(data: ByteArray, fileName: String, mimeType: String): DiscourseUpload =
        decode(
            postMultipart(
                "uploads.json",
                mapOf("upload_type" to "composer", "synchronous" to "true"),
                "file", fileName, mimeType, data,
            ),
        )

    suspend fun uploadUserImage(data: ByteArray, fileName: String, mimeType: String, type: String): DiscourseUpload =
        decode(
            postMultipart(
                "uploads.json",
                mapOf("upload_type" to type, "synchronous" to "true"),
                "file", fileName, mimeType, data,
            ),
        )

    /**
     * A video poster is linked to its video purely by **filename**: pretty_text
     * looks up `original_filename LIKE '<video_sha1>.%'`. There is no markdown
     * that references it, so the name is the entire contract.
     */
    /** A clip, streamed rather than held. See [postMultipartFile]. */
    suspend fun uploadMediaFile(
        file: java.io.File,
        fileName: String,
        mimeType: String,
        onProgress: ((Float) -> Unit)? = null,
    ): DiscourseUpload =
        decode(
            postMultipartFile(
                "uploads.json",
                mapOf("upload_type" to "composer", "synchronous" to "true"),
                "file", fileName, mimeType, file, onProgress,
            ),
        )

    suspend fun uploadVideoPoster(data: ByteArray, videoSha1: String): DiscourseUpload =
        decode(
            postMultipart(
                "uploads.json",
                mapOf("upload_type" to "thumbnail", "synchronous" to "true"),
                "file", "$videoSha1.jpg", "image/jpeg", data,
            ),
        )

    // ----------------------------------------------------------------- chat

    suspend fun chatChannels(): ChatChannelsResponse = try {
        get("chat/api/me/channels.json")
    } catch (error: DiscourseError.BadResponse) {
        if (error.code == 404) get("chat/api/channels.json") else throw error
    }

    /**
     * A page of history.
     *
     * `direction` is what makes this pageable: without it the server answers
     * around the target and nothing older ever arrives. "past" reads backwards
     * from [targetMessageId], which is the oldest message already on screen.
     */
    /**
     * `fetch_from_last_read` centres the page on the reader's last-read mark
     * rather than on the end of the channel, so with more unread than half a
     * page the newest messages are not in the answer at all. Off by default:
     * opening a conversation should show what was just said.
     */
    private fun chatMessageQuery(
        pageSize: Int,
        fetchFromLastRead: Boolean,
        targetMessageId: Int?,
        direction: String? = null,
    ) = buildList {
        add("page_size" to pageSize.toString())
        if (fetchFromLastRead) add("fetch_from_last_read" to "true")
        targetMessageId?.let { add("target_message_id" to it.toString()) }
        direction?.let { add("direction" to it) }
    }

    suspend fun chatMessages(
        channelId: Int,
        pageSize: Int = 50,
        targetMessageId: Int? = null,
        direction: String? = null,
        fetchFromLastRead: Boolean = false,
    ): ChatMessagesResponse =
        get(
            "chat/api/channels/$channelId/messages.json",
            chatMessageQuery(pageSize, fetchFromLastRead, targetMessageId, direction),
        )

    /** Raw variant for the disk snapshot: stored exactly as served, so the
     *  cache re-decodes through this same path later. */
    suspend fun chatMessagesWithRaw(
        channelId: Int,
        pageSize: Int = 50,
        targetMessageId: Int? = null,
        direction: String? = null,
        fetchFromLastRead: Boolean = false,
    ): Pair<ChatMessagesResponse, String> {
        val raw = getRaw(
            "chat/api/channels/$channelId/messages.json",
            chatMessageQuery(pageSize, fetchFromLastRead, targetMessageId, direction),
        )
        return decode<ChatMessagesResponse>(raw) to raw
    }

    private fun chatThreadsQuery(limit: Int, offset: Int) = listOf(
        "limit" to limit.coerceIn(1, 10).toString(),
        "offset" to offset.coerceAtLeast(0).toString(),
    )

    suspend fun chatThreads(channelId: Int, limit: Int = 10, offset: Int = 0): ChatThreadsResponse =
        get("chat/api/channels/$channelId/threads.json", chatThreadsQuery(limit, offset))

    suspend fun currentUserChatThreads(limit: Int = 10, offset: Int = 0): ChatThreadsResponse =
        get("chat/api/me/threads.json", chatThreadsQuery(limit, offset))

    suspend fun chatThreadMessages(
        channelId: Int,
        threadId: Int,
        pageSize: Int = 50,
        targetMessageId: Int? = null,
        direction: String? = null,
    ): ChatMessagesResponse =
        get(
            "chat/api/channels/$channelId/threads/$threadId/messages.json",
            chatMessageQuery(pageSize, fetchFromLastRead = false, targetMessageId, direction),
        )

    suspend fun chatSearch(
        query: String,
        limit: Int = 20,
        offset: Int = 0,
        sort: String = "latest",
        excludeThreads: Boolean = false,
    ): ChatSearchResponse =
        get(
            "chat/api/search.json",
            listOf(
                "query" to query,
                "limit" to limit.coerceIn(1, 40).toString(),
                "offset" to offset.coerceAtLeast(0).toString(),
                "sort" to sort,
                "exclude_threads" to excludeThreads.toString(),
            ),
        )

    /**
     * Sends a message, with any uploads attached as ids.
     *
     * Chat does not take markdown for an upload the way a post does: the
     * message body and the files are separate, and `upload_ids` is how a file
     * reaches a message at all. Putting `![](upload://…)` in the body instead
     * stores exactly that text and shows it as one — the server has no reason
     * to think it means a picture.
     *
     * A message with an upload may be empty; the server only requires words
     * when there is nothing else to send.
     */
    suspend fun createChatMessage(
        channelId: Int,
        message: String,
        threadId: Int? = null,
        inReplyToId: Int? = null,
        uploadIds: List<Int> = emptyList(),
    ): ChatCreateMessageResponse {
        val form = mutableListOf("message" to message)
        threadId?.let { form += "thread_id" to it.toString() }
        inReplyToId?.let { form += "in_reply_to_id" to it.toString() }
        // Repeated key, not a joined string: Rails reads `upload_ids[]` into an
        // array, and a map could only hold one of them.
        uploadIds.forEach { form += "upload_ids[]" to it.toString() }
        return decode(formItems("POST", "chat/$channelId.json", form))
    }

    suspend fun updateChatMessage(channelId: Int, messageId: Int, message: String) {
        formItems("PUT", "chat/api/channels/$channelId/messages/$messageId", listOf("message" to message))
    }

    suspend fun deleteChatMessage(channelId: Int, messageId: Int) {
        send("DELETE", "chat/api/channels/$channelId/messages/$messageId")
    }

    /**
     * One emoji on one message. `react_action` is the verb — the same endpoint
     * takes it back off, which is why this is a toggle rather than two calls.
     */
    suspend fun reactToChatMessage(channelId: Int, messageId: Int, emoji: String, add: Boolean) {
        formItems(
            "PUT",
            "chat/$channelId/react/$messageId",
            listOf(
                "emoji" to emoji,
                "react_action" to if (add) "add" else "remove",
            ),
        )
    }

    /**
     * Discourse's presence, which is how the web knows who is typing: being
     * *in* `/chat-reply/{id}` is the whole signal. It has to be repeated — the
     * server drops anyone who stops saying so — hence the caller's timer.
     */
    suspend fun presenceUpdate(present: List<String>, leave: List<String>) {
        formItems(
            "POST",
            "presence/update",
            buildList {
                add("client_id" to clientId)
                present.forEach { add("present_channels[]" to it) }
                leave.forEach { add("leave_channels[]" to it) }
            },
        )
    }

    suspend fun presenceGet(channels: List<String>): PresenceGetResponse =
        get("presence/get", channels.map { "channels[]" to it })

    /** One channel with its chatable and this reader's membership. */
    suspend fun chatChannel(channelId: Int): ChatChannelResponse =
        get("chat/api/channels/$channelId.json")

    /** Everyone in a channel; a direct message names them on the chatable instead. */
    suspend fun chatChannelMembers(channelId: Int, limit: Int = 50): ChatMembershipsResponse =
        get("chat/api/channels/$channelId/memberships.json", listOf("limit" to limit.toString()))

    /** `never`, `mention` or `always` — the enum's names, not its numbers. */
    suspend fun setChatNotificationLevel(channelId: Int, level: String) {
        formItems(
            "PUT",
            "chat/api/channels/$channelId/notifications-settings/me",
            listOf("notifications_settings[notification_level]" to level),
        )
    }

    /**
     * Leaves the channel: unfollows it, which is what takes it off the list.
     * For a direct message that is as close to deleting it as chat offers —
     * the transcript stays, and a new message brings it back.
     */
    suspend fun leaveChatChannel(channelId: Int) {
        send("DELETE", "chat/api/channels/$channelId/memberships/me")
    }

    suspend fun markChatChannelRead(channelId: Int, messageId: Int) {
        send("PUT", "chat/api/channels/$channelId/read", listOf("message_id" to messageId.toString()))
    }

    /**
     * A thread keeps its own last-read, and the channel's does not cover it —
     * so an unread reply in a watched thread holds the channel's badge up
     * however often the channel itself is opened.
     */
    suspend fun markChatThreadRead(channelId: Int, threadId: Int, messageId: Int) {
        send(
            "PUT",
            "chat/api/channels/$channelId/threads/$threadId/read",
            listOf("message_id" to messageId.toString()),
        )
    }

    /**
     * One MessageBus long-poll round — the transport Discourse's own web client
     * uses. `positions` maps bus channel ("/chat/123") to the last seen id;
     * -1 subscribes to new events only. The server holds the request ~25s.
     */
    /**
     * [sequence] is MessageBus's `__seq`. Without it the server cannot tell a
     * fresh poll from a stale duplicate arriving under the same client id, and
     * treats the newcomer as a reconnection to cancel.
     */
    suspend fun messageBusPoll(positions: Map<String, Int>, sequence: Int): String {
        val fields = positions.entries.map { (key, value) -> "${encodeComponent(key)}=$value" } +
            "__seq=$sequence"
        val body = fields.joinToString("&")
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        return perform(
            requestBuilder(url("message-bus/$clientId/poll"), includeCsrf = true)
                // Without this, message_bus hijacks the socket and *streams*
                // the long poll: it writes each message as a chunk and closes
                // only at the end of its window. This client reads a body, not
                // a stream, so a message published two seconds in was not seen
                // until the server hung up twenty-five seconds later — every
                // chat message arrived up to half a minute late. Asking it not
                // to chunk turns the same request into what it looks like: one
                // response, sent the moment there is something to say.
                .header("Dont-Chunk", "true")
                .post(body)
                .build(),
            Channel.Bus,
        )
    }

    // -------------------------------------------------------------- externals

    /** Klipy is an external host, not the Discourse backend. */
    suspend fun klipySearch(query: String, pos: String? = null): KlipySearchResponse {
        if (DiscourseConfig.KLIPY_API_KEY.isEmpty()) throw DiscourseError.BadResponse(401)
        val target = "https://api.klipy.com/v2/search".toHttpUrl().newBuilder()
            .addQueryParameter("key", DiscourseConfig.KLIPY_API_KEY)
            .addQueryParameter("q", query)
            .addQueryParameter("media_filter", "gif")
            .addQueryParameter("limit", "24")
            .addQueryParameter("pos", pos ?: "0")
            .build()
        val body = perform(
            Request.Builder().url(target).header("Accept", "application/json").get().build(),
            Channel.Read,
        )
        return decode(body)
    }

    // ---------------------------------------------------------------- misc

    val timezone: String get() = TimeZone.getDefault().id

    fun avatarUrl(template: String?, size: Int = 120): String? = DiscourseConfig.avatarUrl(template, size)

    private fun RequestBody?.orEmptyBody(): RequestBody = this ?: ByteArray(0).toRequestBody(null)
}
