package com.nodeloc.app.feature.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lucide
import com.nodeloc.app.R
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.PrimaryButton
import com.nodeloc.app.core.design.SecondaryButton
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.network.WebSessionState
import com.nodeloc.app.core.network.webSessionState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.feature.nav.LinkDestination
import com.nodeloc.app.feature.nav.LinkRouter
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Every WebView in the app, built one way.
 *
 * Two containers show web content — the in-app browser and the mini-program
 * shell — and they had drifted into two copies of three settings with none of
 * the hardening and neither half of the session handling. Anything the two must
 * not differ on lives here; anything they may differ on is a parameter.
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun nodelocWebView(
    context: Context,
    textZoom: Int,
    onTitle: (String?) -> Unit = {},
    onProgress: (Float) -> Unit = {},
    onPageStarted: (url: String) -> Unit = {},
    onPageFinished: (url: String, canGoBack: Boolean) -> Unit = { _, _ -> },
    /** A link this container will not load itself. */
    onLeave: (String) -> Unit,
): WebView = WebView(context).apply {
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    // The site is an Ember app: both of these are load-bearing.
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.textZoom = textZoom

    // Everything below is off. `allowFileAccess` in particular defaults to true
    // below API 30, which would let a file:// page walk the app's own sandbox.
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    @Suppress("DEPRECATION")
    settings.allowFileAccessFromFileURLs = false
    @Suppress("DEPRECATION")
    settings.allowUniversalAccessFromFileURLs = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.setGeolocationEnabled(false)
    // Without this a target=_blank link opens outside the router's reach.
    settings.setSupportMultipleWindows(false)

    webChromeClient = object : WebChromeClient() {
        override fun onReceivedTitle(view: WebView?, title: String?) {
            // A WebView falls back to the URL when a page carries no <title>;
            // showing that would put the link above the host twice.
            onTitle(title?.takeIf { it.isNotBlank() && !it.startsWith("http") })
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            onProgress(newProgress / 100f)
        }
    }

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val target = request?.url?.toString() ?: return false
            return when (LinkRouter.resolve(target)) {
                // A link with a native screen behind it belongs on that screen,
                // not in a WebView of it.
                null, is LinkDestination.InAppBrowser -> false
                // Refused without comment: a javascript: href is page
                // machinery, not something the reader asked to open.
                LinkDestination.Blocked -> true
                else -> {
                    onLeave(target)
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            onPageStarted(url.orEmpty())
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            syncSessionBack(url)
            onPageFinished(url.orEmpty(), view?.canGoBack() == true)
        }
    }
}

/**
 * Takes back whatever the site rotated in the WebView's cookie store.
 *
 * Discourse rotates `_t` here exactly as it does for OkHttp, and the two stores
 * never see each other. Without this, browsing the site in the app leaves
 * OkHttp holding a value the server has already retired — and the next native
 * request comes back `not_logged_in`, which the session repository correctly
 * reads as "signed out". That is the failure the cookie jar's own comment calls
 * load-bearing, arriving through a side door.
 */
private fun syncSessionBack(url: String?) {
    if (url?.toHttpUrlOrNull()?.host != DiscourseConfig.HOST) return
    ServiceLocator.get.cookieJar.mergeFromWebView(
        CookieManager.getInstance().getCookie(DiscourseConfig.BASE_URL),
        DiscourseConfig.BASE_URL.toHttpUrl(),
    )
}

/**
 * Refuses anything that is not http(s), so a mangled URL can never load.
 *
 * @return false when the URL was refused — the caller has a container on
 * screen with nothing in it, and has to say so rather than leave the reader
 * watching a blank page.
 */
internal fun WebView.loadHardened(url: String): Boolean {
    val target = url.toHttpUrlOrNull() ?: return false
    loadUrl(target.toString())
    return true
}

/**
 * Pauses the page with the screen.
 *
 * Per-WebView rather than `pauseTimers()`, which is process-wide: the mini
 * program shell shares this process, so pausing globally would freeze a
 * visible mini program whenever the browser went to the background.
 */
@Composable
internal fun WebViewLifecycle(webView: WebView?) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, webView) {
        val view = webView ?: return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/**
 * Detaches before destroying: `destroy()` on a WebView still in the hierarchy
 * crashes on some OEM builds.
 */
internal fun releaseWebView(view: WebView) {
    view.stopLoading()
    view.loadUrl("about:blank")
    (view.parent as? ViewGroup)?.removeView(view)
    view.destroy()
}

/**
 * Shown instead of loading a site page that would come up signed out.
 *
 * A User-Api-Key session authenticates the top-level document but not the XHRs
 * the site then fires, so the page renders as the account and fails on the
 * first save — see [webSessionState]. "Open in browser" is a real way through
 * rather than a shrug: the website sign-in that produced this key ran in a
 * Custom Tab, which is the system browser, so it already holds a cookie
 * session for the site.
 */
@Composable
internal fun WebSessionNotice(
    url: String,
    onContinueAnyway: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier.fillMaxSize().padding(horizontal = Space.s4),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyStateView(
            icon = Lucide.Globe,
            title = stringResource(R.string.web_session_api_only_title),
            detail = stringResource(R.string.web_session_api_only_detail),
        )
        PrimaryButton(stringResource(R.string.browser_open_external)) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        }
        Spacer(Modifier.height(Space.s2))
        SecondaryButton(stringResource(R.string.web_session_continue_anyway), onClick = onContinueAnyway)
    }
}

/**
 * True when this page should carry the account and will not.
 *
 * Scoped to site URLs: an off-site page was never going to be signed in, so
 * warning about it would be noise.
 */
internal fun needsWebSessionNotice(url: String): Boolean =
    webSessionState(ServiceLocator.get.auth) == WebSessionState.ApiKeyOnly &&
        url.toHttpUrlOrNull()?.host == DiscourseConfig.HOST
