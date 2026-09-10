package com.nodeloc.app.feature.media

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Type
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.RefreshCw
import com.nodeloc.app.R
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.network.handOverSession

/**
 * The one web container in the app.
 *
 * Shaped like a WeChat mini program rather than a browser: the page owns the
 * whole screen, status bar to gesture bar, and the only chrome is the capsule
 * floating over it — more, close. No title bar, no address bar, no toolbar, and
 * deliberately no "open in system browser". This WebView is the only place
 * [handOverSession] has copied the signed-in cookies into, so a URL handed to
 * Chrome arrives logged out; every page reachable from inside the app is part
 * of the app.
 *
 * [onLinkOutsideShell] receives the links the shell refuses to load itself —
 * one with a native screen of its own, or a mailto/tel-style scheme. Where those
 * go is the caller's business.
 *
 * This shell is for mini programs only. An ordinary web page opened in the app
 * goes to [WebPageScreen] instead: a page nobody packaged as an app needs to
 * say where it came from and offer a way out to a real browser, and a mini
 * program needs to do neither.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MiniProgramShell(
    url: String,
    onClose: () -> Unit,
    onLinkOutsideShell: (String) -> Unit,
) {
    val context = LocalContext.current

    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(url) }
    var pageTitle by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var textZoom by remember { mutableIntStateOf(100) }
    var menuOpen by remember { mutableStateOf(false) }

    // Decided once: flipping mid-session would tear down a loaded page.
    var showNotice by rememberSaveable { mutableStateOf(needsWebSessionNotice(url)) }

    // Back walks the page's own history first; only an exhausted history closes
    // the container. Same order a mini program uses.
    BackHandler(enabled = canGoBack) { webView?.goBack() }
    WebViewLifecycle(webView)

    Box(Modifier.fillMaxSize().background(Nocturne.bg)) {
        if (showNotice) {
            WebSessionNotice(url = url, onContinueAnyway = { showNotice = false })
            return@Box
        }
        AndroidView(
            // edge-to-edge makes the manifest's adjustResize inert; a game or a
            // login form inside the shell would otherwise type under the keyboard.
            modifier = Modifier.fillMaxSize().imePadding(),
            factory = { ctx ->
                nodelocWebView(
                    context = ctx,
                    textZoom = textZoom,
                    // Only the share sheet reads the title now.
                    onTitle = { pageTitle = it },
                    onPageStarted = { started ->
                        // With no progress bar left to show, the cover is the
                        // only loading feedback there is — so it comes back for
                        // every navigation, not just the first.
                        loading = true
                        if (started.isNotEmpty()) currentUrl = started
                    },
                    onPageFinished = { finished, back ->
                        loading = false
                        if (finished.isNotEmpty()) currentUrl = finished
                        canGoBack = back
                    },
                    // Only a link with somewhere better to be leaves. Off-site
                    // pages stay here; sending them out is what "opens a browser
                    // window" meant.
                    onLeave = onLinkOutsideShell,
                ).also {
                    handOverSession()
                    if (!it.loadHardened(url)) ToastCenter.show(R.string.link_blocked)
                    webView = it
                }
            },
            update = { view -> view.settings.textZoom = textZoom },
            onRelease = ::releaseWebView,
        )

        // The container opens on its own colour rather than on a white WebView
        // flash, the way a mini program shows its splash first.
        if (loading) {
            Box(
                Modifier.fillMaxSize().background(Nocturne.bg),
                contentAlignment = Alignment.Center,
            ) {
                NodelocLoader(height = 48.dp)
            }
        }

        // The capsule floats over the page, so it carries its own elevation and
        // outline: the content behind it is arbitrary and often the same colour.
        Surface(
            shape = CircleShape,
            color = Nocturne.surface.copy(alpha = 0.94f),
            contentColor = Nocturne.headerText,
            border = BorderStroke(1.dp, Nocturne.divider),
            shadowElevation = 6.dp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(end = 10.dp, top = 6.dp),
        ) {
            Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    CapsuleAction(Lucide.EllipsisVertical, stringResource(R.string.common_more)) {
                        menuOpen = true
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browser_text_size, textZoom)) },
                            leadingIcon = { Icon(Lucide.Type, null) },
                            onClick = {
                                textZoom = if (textZoom >= 200) 50 else (textZoom + 10).coerceAtMost(200)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_refresh)) },
                            leadingIcon = { Icon(Lucide.RefreshCw, null) },
                            onClick = { menuOpen = false; webView?.reload() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_share)) },
                            leadingIcon = { Icon(Lucide.Share2, null) },
                            onClick = { menuOpen = false; shareWebUrl(context, currentUrl, pageTitle) },
                        )
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 7.dp).background(Nocturne.divider))
                CapsuleAction(Lucide.X, stringResource(R.string.common_close), onClose)
            }
        }
    }
}

@Composable
private fun CapsuleAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(width = 44.dp, height = 32.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Nocturne.headerText, modifier = Modifier.size(17.dp))
    }
}
