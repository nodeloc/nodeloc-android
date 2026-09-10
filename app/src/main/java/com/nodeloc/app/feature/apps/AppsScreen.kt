package com.nodeloc.app.feature.apps

import com.nodeloc.app.core.util.runCatchingCancellable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.LayoutGrid
import com.nodeloc.app.MiniAppActivity
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.HairLine
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.PrimaryButton
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.TagChip
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.html.PostContentView
import com.nodeloc.app.core.html.PostHtmlParser
import com.nodeloc.app.core.model.DirectoryApp
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.feature.feed.RowsSkeleton
import com.nodeloc.app.feature.nav.Navigator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppsViewModel : ViewModel() {
    private val client = ServiceLocator.get.client

    private val _apps = MutableStateFlow<List<DirectoryApp>>(emptyList())
    val apps: StateFlow<List<DirectoryApp>> = _apps.asStateFlow()

    private val _detail = MutableStateFlow<DirectoryApp?>(null)
    val detail: StateFlow<DirectoryApp?> = _detail.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Null until the host topic has been read; the sandbox cannot open before then. */
    private val _installId = MutableStateFlow<Int?>(null)
    val installId: StateFlow<Int?> = _installId.asStateFlow()

    fun loadDirectory() {
        if (_apps.value.isNotEmpty()) return
        viewModelScope.launch {
            _isLoading.value = true
            // Note: this endpoint answers with a bare array, unlike the detail one.
            _apps.value = runCatchingCancellable { client.appsDirectory() }.getOrDefault(emptyList())
            _isLoading.value = false
        }
    }

    fun loadApp(slug: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _installId.value = null
            val app = runCatchingCancellable { client.app(slug) }.getOrNull()?.directoryApp
            _detail.value = app
            _isLoading.value = false
            _installId.value = app?.let { resolveInstallId(it) }
        }
    }

    /**
     * The sandbox URL needs the *install* id, and no apps endpoint returns
     * one: it exists only as `data-app-install` in the host topic's first
     * post, which is what the web client reads too. Passing the app's own id
     * instead lands on a 404.
     */
    private suspend fun resolveInstallId(app: DirectoryApp): Int? {
        val topicId = app.hostTopicId ?: return null
        val cooked = runCatchingCancellable { client.topic(topicId) }.getOrNull()
            ?.postStream?.posts?.firstOrNull()?.cooked ?: return null
        return INSTALL_ID.find(cooked)?.groupValues?.get(1)?.toIntOrNull()
    }

    fun webviewUrl(installId: Int) = client.appWebviewUrl(installId)
}

@Composable
fun AppsScreen(navigator: Navigator) {
    val viewModel: AppsViewModel = viewModel()
    val apps by viewModel.apps.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadDirectory() }

    Column(Modifier.fillMaxSize().background(Nocturne.bg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = Space.page, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            HeaderIconButton(Lucide.ArrowLeft, stringResource(R.string.common_back), onClick = navigator::back)
            Text(stringResource(R.string.apps_title), style = Type.body(15, FontWeight.SemiBold), color = Nocturne.headerText)
        }
        HairLine()

        when {
            isLoading && apps.isEmpty() -> RowsSkeleton()
            apps.isEmpty() -> EmptyStateView(Lucide.LayoutGrid, stringResource(R.string.apps_empty))
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(apps, key = { it.id }) { app ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { navigator.openApp(app.slug) }
                            .padding(horizontal = Space.page, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.s3),
                    ) {
                        RemoteAvatar(
                            DiscourseConfig.absoluteUrl(app.logoUrl),
                            app.name.take(1),
                            size = 40.dp,
                            cornerRadius = 10.dp,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(app.name, style = Type.body(15, FontWeight.Medium), color = Nocturne.text)
                            app.description?.let {
                                Text(
                                    it,
                                    style = Type.body(12),
                                    color = Nocturne.muted(0.5f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        app.installsCount?.let {
                            Text(pluralStringResource(R.plurals.apps_installs, it, it), style = Type.body(11), color = Nocturne.muted(0.4f))
                        }
                    }
                    HairLine()
                }
            }
        }
    }
}

@Composable
fun AppDetailScreen(slug: String, navigator: Navigator) {
    val viewModel: AppsViewModel = viewModel()
    val app by viewModel.detail.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    LaunchedEffect(slug) { viewModel.loadApp(slug) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Nocturne.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = Space.page, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderIconButton(Lucide.ArrowLeft, stringResource(R.string.common_back), onClick = navigator::back)
        }

        if (isLoading && app == null) {
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                NodelocLoader(height = 48.dp)
            }
            return@Column
        }

        val detail = app ?: run {
            // A failed fetch used to leave a back button over an empty page,
            // with nothing said and no way to try again.
            EmptyStateView(
                icon = Lucide.CircleAlert,
                title = stringResource(R.string.apps_load_failed),
                retryLabel = stringResource(R.string.common_retry),
                onRetry = { viewModel.loadApp(slug) },
            )
            return@Column
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.page),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                RemoteAvatar(
                    DiscourseConfig.absoluteUrl(detail.logoUrl),
                    detail.name.take(1),
                    size = 56.dp,
                    cornerRadius = 14.dp,
                )
                Column(Modifier.weight(1f)) {
                    Text(detail.name, style = Type.heading(20, FontWeight.SemiBold), color = Nocturne.text)
                    detail.author?.username?.let {
                        Text("by $it", style = Type.body(12), color = Nocturne.muted(0.45f))
                    }
                }
            }

            detail.description?.let {
                Text(it, style = Type.body(14), color = Nocturne.muted(0.62f))
            }

            if (detail.isWebview) {
                val installId by viewModel.installId.collectAsState()
                val context = LocalContext.current
                PrimaryButton(stringResource(R.string.apps_open), enabled = installId != null) {
                    installId?.let {
                        MiniAppActivity.launch(
                            context = context,
                            url = viewModel.webviewUrl(it),
                            name = detail.name,
                            iconUrl = DiscourseConfig.absoluteUrl(detail.logoUrl),
                        )
                    }
                }
            } else {
                Text(stringResource(R.string.apps_web_only), style = Type.body(12), color = Nocturne.muted(0.45f))
            }

            detail.approvedScopes?.takeIf { it.isNotEmpty() }?.let { scopes ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.apps_permissions), style = Type.body(13, FontWeight.SemiBold), color = Nocturne.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        scopes.take(6).forEach { TagChip(it) }
                    }
                }
            }

            detail.readmeCooked?.let { readme ->
                PostContentView(
                    // remember, because this runs in composition: parsing a
                    // README on every frame is the parser's whole cost paid
                    // over and over on the main thread.
                    content = remember(readme) { PostHtmlParser.parseSync(readme) },
                    baseSize = 14,
                    onOpenLink = { navigator.openBrowser(it) },
                )
            }

            detail.hostTopicId?.let { topicId ->
                PrimaryButton(stringResource(R.string.apps_discussion)) { navigator.openTopic(topicId) }
            }
        }
        Box(Modifier.padding(bottom = 48.dp))
    }
}

/** `data-app-install="123"` in the host topic's cooked HTML. */
private val INSTALL_ID = Regex("data-app-install=\"(\\d+)\"")
