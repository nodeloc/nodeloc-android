package com.nodeloc.app.feature.media

import android.content.ClipData
import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Type as TypeIcon
import com.composables.icons.lucide.X
import com.nodeloc.app.R
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.network.handOverSession
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.feature.nav.Navigator

/**
 * The in-app browser for ordinary web pages.
 *
 * Deliberately not the mini-program shell. A mini program is something the site
 * packaged and the user chose to open, so it earns the whole screen with only a
 * capsule over it. An arbitrary link is not that: the reader needs to see which
 * site they landed on before they trust it, and needs a way out to a real
 * browser when the page wants one — a password manager, a bank, a download.
 * Both of those are exactly what this bar adds and the shell refuses.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPageScreen(url: String, navigator: Navigator) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(url) }
    var pageTitle by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var canGoBack by remember { mutableStateOf(false) }
    var textZoom by remember { mutableIntStateOf(100) }
    var menuOpen by remember { mutableStateOf(false) }

    // Decided once: flipping mid-session would tear down a loaded page.
    var showNotice by rememberSaveable { mutableStateOf(needsWebSessionNotice(url)) }

    // The page's own history first, as a browser does; only an exhausted
    // history leaves the page.
    BackHandler(enabled = canGoBack) { webView?.goBack() }
    WebViewLifecycle(webView)

    Column(Modifier.fillMaxSize().background(Nocturne.bg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Nocturne.headerBg)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(end = Space.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderIconButton(Lucide.X, stringResource(R.string.common_close), onClick = navigator::back)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    pageTitle ?: stringResource(R.string.common_loading),
                    style = Type.body(14, FontWeight.SemiBold),
                    color = Nocturne.headerText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The host, not the full URL: it is the part that says who is
                // being trusted, and the only part that fits.
                Text(
                    runCatching { currentUrl.toUri().host }.getOrNull().orEmpty(),
                    style = Type.body(11),
                    color = Nocturne.muted(0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                HeaderIconButton(Lucide.EllipsisVertical, stringResource(R.string.common_more)) { menuOpen = true }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_refresh)) },
                        leadingIcon = { Icon(Lucide.RefreshCw, null) },
                        onClick = { menuOpen = false; webView?.reload() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_text_size, textZoom)) },
                        leadingIcon = { Icon(Lucide.TypeIcon, null) },
                        onClick = { textZoom = if (textZoom >= 200) 50 else (textZoom + 10).coerceAtMost(200) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reader_copy_link)) },
                        leadingIcon = { Icon(Lucide.Copy, null) },
                        onClick = {
                            menuOpen = false
                            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(pageTitle, currentUrl))) }
                            ToastCenter.show(R.string.reader_link_copied)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_share)) },
                        leadingIcon = { Icon(Lucide.Share2, null) },
                        onClick = { menuOpen = false; shareWebUrl(context, currentUrl, pageTitle) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_open_external)) },
                        leadingIcon = { Icon(Lucide.ExternalLink, null) },
                        onClick = {
                            menuOpen = false
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, currentUrl.toUri()))
                            }.onFailure { ToastCenter.show(R.string.error_action_failed) }
                        },
                    )
                }
            }
        }

        // Only while it means something: a bar parked at zero on a loaded page
        // is a permanent line under the header.
        if (progress > 0f && progress < 1f) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Nocturne.accent,
                trackColor = Nocturne.surface,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showNotice) {
                WebSessionNotice(url = url, onContinueAnyway = { showNotice = false })
                return@Box
            }
            AndroidView(
                // enableEdgeToEdge() takes the decor out of fitsSystemWindows,
                // which makes the manifest's adjustResize inert — without
                // imePadding a focused field sits under the keyboard.
                modifier = Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .imePadding(),
                factory = { ctx ->
                    nodelocWebView(
                        context = ctx,
                        textZoom = textZoom,
                        onTitle = { pageTitle = it },
                        onProgress = { progress = it },
                        onPageStarted = { started -> if (started.isNotEmpty()) currentUrl = started },
                        onPageFinished = { finished, back ->
                            if (finished.isNotEmpty()) currentUrl = finished
                            canGoBack = back
                        },
                        onLeave = { target -> navigator.leaveBrowserFor(context, target) },
                    ).also {
                        handOverSession()
                        if (!it.loadHardened(url)) ToastCenter.show(R.string.link_blocked)
                        webView = it
                    }
                },
                update = { view -> view.settings.textZoom = textZoom },
                onRelease = ::releaseWebView,
            )
        }
    }
}
