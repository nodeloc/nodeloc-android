package com.nodeloc.app.feature.auth

import android.annotation.SuppressLint
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.nodeloc.app.BuildConfig
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.network.DiscourseConfig
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Signing in through the website, which is the only way a social provider can
 * work: Google and the rest hold a client registered to *this site*, not to
 * this app, so the whole exchange happens between them and Discourse and the
 * app's business is only to be holding the session when it ends.
 *
 * A WebView rather than a Custom Tab, deliberately. A Custom Tab's cookie jar
 * belongs to the browser and the app cannot read it, which is exactly why the
 * User API Key flow it replaces could never produce a session.
 *
 * A sheet rather than a screen, also deliberately: it is somebody else's page,
 * borrowed for one errand, and leaving the form visible above it says so. The
 * host is on the header for the same reason — on a page asking for a password,
 * which site is asking is the one thing worth showing.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SocialLoginSheet(provider: String, onDone: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var host by remember { mutableStateOf(DiscourseConfig.HOST) }
    // Starts collapsed so the first frame animates: the sheet is meant to be
    // seen arriving from the bottom edge, not to be already there.
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }

    BackHandler { onDone(false) }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(SCRIM)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDone(false) },
        )

        AnimatedVisibility(
            visibleState = shown,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 44.dp)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .background(Nocturne.bg)
                    // The keyboard when it is up, the navigation bar when it
                    // is not — the sheet reaches the bottom edge either way.
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HeaderIconButton(Lucide.X, stringResource(R.string.common_close)) { onDone(false) }
                    Text(
                        host,
                        style = Type.body(13, FontWeight.Medium),
                        color = Nocturne.muted(0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                    // Balances the close button so the host stays centred.
                    Spacer(Modifier.size(40.dp))
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    SocialLoginWeb(
                        provider = provider,
                        onLoading = { loading = it },
                        onHost = { host = it },
                        scope = scope,
                        onDone = onDone,
                    )
                    if (loading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            NodelocLoader(height = 44.dp)
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SocialLoginWeb(
    provider: String,
    onLoading: (Boolean) -> Unit,
    onHost: (String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
    onDone: (Boolean) -> Unit,
) {
    // Latched: `onPageFinished` fires for every hop of the redirect chain, and
    // the last few are on the site with the session already set.
    var finished by remember { mutableStateOf(false) }
    // What the WebView was already holding before any of this started.
    //
    // Browsing the site in-app leaves a `_t` in this store, and the first hop
    // of the flow is on the site's own host — so "there is a session cookie"
    // fires immediately, on a session that predates the sign-in and proves
    // nothing. A *new* value is the only honest signal, and it works whether
    // the provider redirects away (Google, GitHub, X) or does its business on
    // the page (Telegram).
    val before = remember { sessionCookie() }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // The providers' own pages are the other half of this flow
                    // and several refuse anything that looks like a WebView.
                    settings.userAgentString = WebSettings.getDefaultUserAgent(context)
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                            if (BuildConfig.DEBUG) {
                                Log.w(TAG, "console: ${message.message()} @${message.sourceId()}:${message.lineNumber()}")
                            }
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        // A sign-in that fails behind glass is the worst kind:
                        // the page simply stops moving and there is nothing to
                        // tell the user, or us, why.
                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (!request.isForMainFrame) return
                            if (BuildConfig.DEBUG) {
                                Log.w(TAG, "load failed ${request.url}: ${error.errorCode} ${error.description}")
                            }
                            ToastCenter.show(R.string.auth_failed)
                        }

                        override fun onReceivedHttpError(
                            view: WebView,
                            request: WebResourceRequest,
                            response: WebResourceResponse,
                        ) {
                            if (!request.isForMainFrame) return
                            if (BuildConfig.DEBUG) {
                                Log.w(TAG, "http ${response.statusCode} for ${request.url}")
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            if (BuildConfig.DEBUG) Log.w(TAG, "nav ${request.method} ${request.url}")
                            if (request.isForMainFrame) request.url.host?.let(onHost)
                            return false
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            onLoading(false)
                            url?.toHttpUrlOrNull()?.host?.let(onHost)
                            if (finished) return
                            val target = url?.toHttpUrlOrNull() ?: return
                            if (target.host != DiscourseConfig.HOST) return
                            val header = CookieManager.getInstance().getCookie(DiscourseConfig.BASE_URL)
                            if (sessionCookie() == before) return
                            val jar = ServiceLocator.get.cookieJar
                            val site = DiscourseConfig.BASE_URL.toHttpUrlOrNull() ?: return
                            if (!jar.adoptSessionFromWebView(header, site)) return
                            finished = true
                            scope.launch {
                                // The cookie is the credential; these two turn
                                // it into a session the rest of the app can
                                // use, and then into a user it can see. The
                                // fetch is also the check: a cookie the server
                                // will not honour surfaces here rather than as
                                // a signed-in shell with nothing in it.
                                val services = ServiceLocator.get
                                services.authService.adoptWebSession()
                                services.session.onSignedIn()
                                val ok = services.session.currentUser.value != null
                                if (!ok) ToastCenter.show(R.string.auth_failed)
                                onDone(ok)
                            }
                        }
                    }
                    loadUrl("${DiscourseConfig.BASE_URL}/auth/$provider")
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = {
                it.stopLoading()
                it.loadUrl("about:blank")
                (it.parent as? ViewGroup)?.removeView(it)
                it.destroy()
            },
        )

    }
}

/** Dims the form behind the sheet without hiding what it is. */
private val SCRIM = Color.Black.copy(alpha = 0.55f)

/** The `_t` the WebView is holding for the site, if any. */
private fun sessionCookie(): String? =
    CookieManager.getInstance().getCookie(DiscourseConfig.BASE_URL)
        ?.split(';')
        ?.firstNotNullOfOrNull { pair ->
            val separator = pair.indexOf('=')
            if (separator <= 0) return@firstNotNullOfOrNull null
            if (pair.substring(0, separator).trim() != "_t") null else pair.substring(separator + 1).trim()
        }

private const val TAG = "SocialLogin"
