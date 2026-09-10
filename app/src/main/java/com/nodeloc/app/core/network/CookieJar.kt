package com.nodeloc.app.core.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

/** Discourse's session cookie; the only one that means "signed in". */
private const val SESSION_COOKIE = "_t"

/**
 * Persistent cookie jar — the single most important piece of the auth stack.
 *
 * Discourse rotates the `_t` auth cookie: it hands out a fresh value in
 * Set-Cookie and only briefly honours the old one. Pinning the login-time
 * `Cookie` header (which is what the iOS build first did) therefore works for a
 * few minutes and then answers **every** request with `not_logged_in`. So the
 * rule is absolute: cookies are never written as a manual header anywhere in
 * this app — OkHttp owns them, this jar persists them, and rotations are
 * absorbed automatically.
 */
/**
 * Reconciles the WebView's cookie store back into the jar.
 *
 * `CookieManager.getCookie` hands back a *request* header — names and values,
 * nothing else — so a cookie here can only be updated, never created: domain,
 * path and flags have to come from the copy the jar already holds. That
 * limitation is also the security rule, and the reason this only ever returns
 * replacements for cookies passed in. A stale WebView store must not be able to
 * hand OkHttp a session it did not already have.
 *
 * Free of Android types on purpose, so the rotation logic runs as a plain JVM
 * test — it is the half that goes wrong silently.
 */
internal object WebViewCookies {

    /** Only the cookies whose value actually changed. */
    fun merge(existing: List<Cookie>, header: String?, url: HttpUrl): List<Cookie> {
        if (header.isNullOrBlank()) return emptyList()

        val incoming = header.split(';')
            .mapNotNull { pair ->
                val separator = pair.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val name = pair.substring(0, separator).trim()
                val value = pair.substring(separator + 1).trim()
                if (name.isEmpty()) null else name to value
            }
            .toMap()
        if (incoming.isEmpty()) return emptyList()

        return existing.mapNotNull { cookie ->
            if (!cookie.matches(url)) return@mapNotNull null
            val fresh = incoming[cookie.name] ?: return@mapNotNull null
            if (fresh == cookie.value) return@mapNotNull null
            Cookie.Builder()
                .name(cookie.name)
                .value(fresh)
                .path(cookie.path)
                .expiresAt(cookie.expiresAt)
                .apply {
                    if (cookie.hostOnly) hostOnlyDomain(cookie.domain) else domain(cookie.domain)
                    if (cookie.secure) secure()
                    if (cookie.httpOnly) httpOnly()
                }
                .build()
        }
    }
}

/**
 * Where the jar keeps its cookies between launches. An interface because the
 * jar does not care that the real one is Keystore-backed — and because the
 * rotation logic is worth testing without a device.
 */
interface CookieStorage {
    fun read(): String?
    fun write(value: String?)
}

class PersistentCookieJar(private val store: CookieStorage) : CookieJar {
    private val cookies = linkedMapOf<String, Cookie>()

    init {
        restore()
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        var changed = false
        for (cookie in cookies) {
            val key = cookieKey(cookie)
            if (cookie.expiresAt < System.currentTimeMillis() && cookie.persistent) {
                changed = this.cookies.remove(key) != null || changed
            } else {
                val previous = this.cookies.put(key, cookie)
                changed = changed || previous == null || previous.value != cookie.value
            }
        }
        if (changed) persist()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val expired = cookies.filterValues { it.persistent && it.expiresAt < now }.keys
        if (expired.isNotEmpty()) {
            expired.forEach { cookies.remove(it) }
            persist()
        }
        return cookies.values.filter { it.matches(url) }
    }

    /** True once a website session (rather than a bare CSRF cookie) exists. */
    @Synchronized
    fun hasSessionCookie(): Boolean = cookies.values.any { it.name == SESSION_COOKIE }

    /**
     * The jar's cookies as `Set-Cookie` values, for handing to the WebView.
     *
     * The WebView keeps a cookie store of its own that OkHttp never touches,
     * so anything loaded in it — the app sandbox, the authoring console —
     * arrives signed out until these are copied across.
     */
    @Synchronized
    fun asSetCookieHeaders(): List<String> = cookies.values.map { cookie ->
        buildString {
            append(cookie.name).append('=').append(cookie.value)
            append("; Domain=").append(cookie.domain)
            append("; Path=").append(cookie.path)
            if (cookie.secure) append("; Secure")
            if (cookie.httpOnly) append("; HttpOnly")
        }
    }

    /**
     * Takes back whatever the WebView's store rotated.
     *
     * Discourse rotates `_t` for pages loaded in the WebView too, and the two
     * cookie stores never see each other. Without this the app browses the site
     * in one window while OkHttp holds a value the server has already retired —
     * the same "works for minutes, then `not_logged_in` on everything" failure
     * the class comment above is about, arriving through a side door.
     *
     * @return true when something actually rotated.
     */
    @Synchronized
    fun mergeFromWebView(header: String?, url: HttpUrl): Boolean {
        val rotated = WebViewCookies.merge(cookies.values.toList(), header, url)
        if (rotated.isEmpty()) return false
        rotated.forEach { cookies[cookieKey(it)] = it }
        persist()
        return true
    }

    /**
     * The one place a WebView may hand this jar a session it did not already
     * have: the end of a sign-in run through the website, where the whole point
     * is that a `_t` now exists which did not before.
     *
     * Deliberately not part of [mergeFromWebView], which stays update-only. The
     * rule that a stale WebView store cannot conjure a session out of nothing
     * is what keeps a browsed page from silently re-authenticating the app, so
     * the exception is one cookie, on the site's own host, and only when a
     * caller says a sign-in just finished.
     *
     * @return true when a session cookie was adopted.
     */
    @Synchronized
    fun adoptSessionFromWebView(header: String?, url: HttpUrl): Boolean {
        if (url.host != DiscourseConfig.HOST) return false
        val value = header?.split(';')
            ?.firstNotNullOfOrNull { pair ->
                val separator = pair.indexOf('=')
                if (separator <= 0) return@firstNotNullOfOrNull null
                val name = pair.substring(0, separator).trim()
                if (name != SESSION_COOKIE) null else pair.substring(separator + 1).trim()
            }
            ?.takeIf { it.isNotEmpty() }
            ?: return false

        val cookie = Cookie.Builder()
            .name(SESSION_COOKIE)
            .value(value)
            .hostOnlyDomain(url.host)
            .path("/")
            .secure()
            .httpOnly()
            // The server's own expiry is not in `CookieManager.getCookie`,
            // which returns a request header and nothing else. A year matches
            // what Discourse issues, and a wrong guess costs a re-login rather
            // than a stale session: the server decides when `_t` stops working.
            .expiresAt(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000)
            .build()
        cookies[cookieKey(cookie)] = cookie
        persist()
        return true
    }

    /**
     * A sign-in attempt has to start from an empty jar to get a clean
     * `_forum_session` for CSRF, but a *failed* attempt must not cost the user
     * the session they already had. Paired with [restoreSnapshot].
     */
    @Synchronized
    fun snapshot(): List<Cookie> = cookies.values.toList()

    @Synchronized
    fun restoreSnapshot(items: List<Cookie>) {
        cookies.clear()
        items.forEach { cookies[cookieKey(it)] = it }
        persist()
    }

    @Synchronized
    fun clear() {
        cookies.clear()
        store.write(null)
    }

    private fun cookieKey(cookie: Cookie) = "${cookie.domain}|${cookie.path}|${cookie.name}"

    private fun persist() {
        val array = JSONArray()
        cookies.values.filter { it.name == SESSION_COOKIE || it.name.startsWith("_forum_session") || it.name == "_bypass_cache" }
            .forEach { cookie ->
                array.put(
                    JSONObject().apply {
                        put("name", cookie.name)
                        put("value", cookie.value)
                        put("domain", cookie.domain)
                        put("path", cookie.path)
                        put("expiresAt", cookie.expiresAt)
                        put("secure", cookie.secure)
                        put("httpOnly", cookie.httpOnly)
                        put("hostOnly", cookie.hostOnly)
                    },
                )
            }
        store.write(array.toString())
    }

    private fun restore() {
        val raw = store.read() ?: return
        runCatching {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val builder = Cookie.Builder()
                    .name(item.getString("name"))
                    .value(item.getString("value"))
                    .path(item.optString("path", "/"))
                    .expiresAt(item.optLong("expiresAt", Long.MAX_VALUE))
                if (item.optBoolean("secure")) builder.secure()
                if (item.optBoolean("httpOnly")) builder.httpOnly()
                val domain = item.getString("domain")
                if (item.optBoolean("hostOnly")) builder.hostOnlyDomain(domain) else builder.domain(domain)
                val cookie = builder.build()
                cookies[cookieKey(cookie)] = cookie
            }
        }
    }
}
