package com.nodeloc.app.core.network

import android.webkit.CookieManager
import android.webkit.WebStorage
import com.nodeloc.app.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The WebView's own session, which OkHttp never sees.
 *
 * Lives in `core/network` rather than beside the screens that use it because
 * sign-out has to reach it, and `core/store` must not depend on `feature/`.
 */

/**
 * Copies the OkHttp jar's cookies into the WebView's store.
 *
 * Pages opened in the app are part of the signed-in site — a directory app, the
 * authoring console, account preferences — and the two cookie stores are
 * entirely separate, so without this they load signed out. It has to happen
 * before the first `loadUrl`, not from a `LaunchedEffect`, which would run a
 * frame too late.
 */
internal fun handOverSession() {
    val manager = CookieManager.getInstance()
    manager.setAcceptCookie(true)
    ServiceLocator.get.cookieJar.asSetCookieHeaders().forEach {
        manager.setCookie(DiscourseConfig.BASE_URL, it)
    }
    manager.flush()
}

/**
 * The other half of [handOverSession], and the reason sign-out is not just a
 * matter of forgetting a token: the WebView keeps its cookies and DOM storage
 * on disk. Without this, signing out and opening any in-app page lands straight
 * back in the previous account's session — including a mini program still
 * parked in Recents.
 *
 * Main thread only, and wrapped: instantiating the WebView provider throws on a
 * device that is mid-update, and that must not take sign-out down with it.
 */
internal suspend fun clearWebViewSession() {
    withContext(Dispatchers.Main) {
        runCatching {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
            WebStorage.getInstance().deleteAllData()
        }
    }
}

/**
 * Whether a page opened in the app will actually be signed in.
 *
 * A User-Api-Key does authenticate the top-level document — Discourse's
 * current-user provider reads the header on HTML routes, which is why a bogus
 * key answers 403 where anonymous answers 302 — so the page boots looking
 * signed in. But `loadUrl(url, headers)` only decorates the main-frame request:
 * every XHR the site then fires goes anonymous, and `shouldInterceptRequest`
 * cannot carry a POST body, so the first save fails. A page that looks signed
 * in and cannot save is worse than saying so.
 */
internal enum class WebSessionState { Cookie, ApiKeyOnly, Anonymous }

internal fun webSessionState(auth: DiscourseAuth): WebSessionState = when {
    auth.hasSession -> WebSessionState.Cookie
    auth.userApiKey != null -> WebSessionState.ApiKeyOnly
    else -> WebSessionState.Anonymous
}
