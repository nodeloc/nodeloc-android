package com.nodeloc.app.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.WifiOff
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composables.icons.lucide.SearchX
import com.composables.icons.lucide.Search
import com.nodeloc.app.R
import com.nodeloc.app.core.network.isOfflineError
import com.nodeloc.app.core.design.Avatar
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.HairLine
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.Radius
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.SectionKicker
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.rememberTapSafeOverscroll
import com.nodeloc.app.core.model.NodeReadingMode
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.feature.feed.PostRow
import com.nodeloc.app.feature.nav.AppViewModel
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nodeloc.app.feature.media.ImageViewerOverlay
import com.nodeloc.app.feature.media.PostImageViewer
import com.nodeloc.app.feature.media.imageViewerAt
import com.nodeloc.app.feature.nav.Navigator

/**
 * Search is a real tab, with a permanent field at the top.
 *
 * Android has no equivalent of iOS's "tab bar morphs into a search field", and
 * imitating it would fight every platform convention — so the field simply
 * lives here, with the scope chips under it.
 */
@Composable
fun SearchScreen(
    app: AppViewModel,
    navigator: Navigator,
    contentPadding: PaddingValues,
) {
    val viewModel: SearchViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val history by viewModel.history.collectAsState()
    val hotTerms by viewModel.hotTerms.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().background(Nocturne.bg)) {
        SearchField(
            value = state.query,
            onValueChange = viewModel::updateQuery,
            onSubmit = {
                viewModel.submit()
                keyboard?.hide()
            },
            onClear = { viewModel.updateQuery("") },
        )

        if (state.hasSearched) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Space.page, vertical = Space.s2),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                overscrollEffect = rememberTapSafeOverscroll(),
            ) {
                items(SearchScope.entries) { scope ->
                    ScopeChip(stringResource(scope.labelRes), scope == state.scope) { viewModel.selectScope(scope) }
                }
            }
        }
        HairLine()

        when {
            state.isSearching && state.scopeIsEmpty -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { NodelocLoader(height = 48.dp) }

            !state.hasSearched -> SuggestionsPane(
                history = history,
                hotTerms = hotTerms,
                onPick = { viewModel.updateQuery(it); viewModel.submit() },
                onRemove = viewModel::removeHistory,
                onClearAll = viewModel::clearHistory,
                contentPadding = contentPadding,
            )

            // Before the empty state: a search that could not run has not
            // found nothing, and telling an offline reader "nothing matched"
            // sends them looking for a different word.
            state.error != null && state.scopeIsEmpty -> {
                val offline = state.error?.isOfflineError() == true
                EmptyStateView(
                    icon = if (offline) Lucide.WifiOff else Lucide.CircleAlert,
                    title = stringResource(if (offline) R.string.error_offline else R.string.search_failed),
                    retryLabel = stringResource(R.string.common_retry),
                    onRetry = viewModel::submit,
                )
            }

            state.scopeIsEmpty -> EmptyStateView(
                icon = Lucide.SearchX,
                title = stringResource(R.string.search_empty),
                detail = stringResource(R.string.search_empty_detail),
            )

            else -> ResultsPane(state, navigator, contentPadding)
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = Space.page, vertical = 10.dp)
            .clip(RoundedCornerShape(Radius.lg))
            .background(Nocturne.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Lucide.Search, null, tint = Nocturne.muted(0.4f), modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = Type.body(15).copy(color = Nocturne.text),
                cursorBrush = SolidColor(Nocturne.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(stringResource(R.string.search_hint), style = Type.body(15), color = Nocturne.muted(0.35f))
                    }
                    inner()
                },
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Lucide.X,
                stringResource(R.string.common_clear),
                tint = Nocturne.muted(0.4f),
                modifier = Modifier.size(18.dp).clickable(onClick = onClear),
            )
        }
    }
}

@Composable
private fun ScopeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = Type.body(13, if (selected) FontWeight.SemiBold else FontWeight.Normal),
        color = if (selected) Nocturne.accent else Nocturne.muted(0.55f),
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) Nocturne.selected else Nocturne.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.card, vertical = 6.dp),
    )
}

@Composable
private fun SuggestionsPane(
    history: List<String>,
    hotTerms: List<String>,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        overscrollEffect = rememberTapSafeOverscroll(),
    ) {
        if (history.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.s3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionKicker(stringResource(R.string.search_recent), Modifier.weight(1f))
                    Text(
                        stringResource(R.string.common_clear_all),
                        style = Type.body(12),
                        color = Nocturne.accent,
                        modifier = Modifier.clickable(onClick = onClearAll),
                    )
                }
            }
            items(history) { term ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(term) }
                        .padding(horizontal = Space.page, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s3),
                ) {
                    Icon(Lucide.History, null, tint = Nocturne.muted(0.35f), modifier = Modifier.size(16.dp))
                    Text(term, style = Type.body(14), color = Nocturne.text, modifier = Modifier.weight(1f))
                    Icon(
                        Lucide.X,
                        stringResource(R.string.common_delete),
                        tint = Nocturne.muted(0.3f),
                        modifier = Modifier.size(15.dp).clickable { onRemove(term) },
                    )
                }
            }
        }
        item {
            SectionKicker(stringResource(R.string.search_hot), Modifier.padding(horizontal = Space.page, vertical = Space.s3))
            LazyRow(
                contentPadding = PaddingValues(horizontal = Space.page),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                overscrollEffect = rememberTapSafeOverscroll(),
            ) {
                items(hotTerms) { term ->
                    Text(
                        term,
                        style = Type.body(13),
                        color = Nocturne.text,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Nocturne.surface)
                            .clickable { onPick(term) }
                            .padding(horizontal = Space.card, vertical = 7.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultsPane(state: SearchState, navigator: Navigator, contentPadding: PaddingValues) {
    val context = LocalContext.current
    // Tapping a picture in a row opens it full-screen rather than the topic;
    // the row itself still opens the topic everywhere else.
    var viewer by remember { mutableStateOf<PostImageViewer?>(null) }
    viewer?.let { open ->
        ImageViewerOverlay(open.images, open.index, open.source) { viewer = null }
    }
    LazyColumn(
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        overscrollEffect = rememberTapSafeOverscroll(),
    ) {
        val showNodes = state.scope == SearchScope.All || state.scope == SearchScope.Nodes
        val showUsers = state.scope == SearchScope.All || state.scope == SearchScope.Users
        val showApps = state.scope == SearchScope.All || state.scope == SearchScope.Apps
        val showPosts = state.scope != SearchScope.Nodes && state.scope != SearchScope.Users && state.scope != SearchScope.Apps

        if (showNodes && state.nodes.isNotEmpty()) {
            item {
                // The node search caps at 25 server-side, so say so when the
                // term matched more than the list can be showing.
                val capped = state.nodeTotal?.takeIf {
                    state.scope == SearchScope.Nodes && it > state.nodes.size
                }
                SectionKicker(
                    capped?.let { stringResource(R.string.search_nodes_capped, state.nodes.size, it) }
                        ?: stringResource(R.string.search_scope_nodes),
                    Modifier.padding(horizontal = Space.page, vertical = Space.s3),
                )
            }
            // "全部" shows a preview of each group; a scope shows everything.
            val nodes = if (state.scope == SearchScope.All) state.nodes.take(3) else state.nodes
            items(nodes, key = { "node-${it.id}" }) { node ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { navigator.openNode(node.id, node.slug) }
                        .padding(horizontal = Space.page, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s3),
                ) {
                    if (node.logoUrl != null) {
                        RemoteAvatar(node.logoUrl, node.letter, size = 32.dp)
                    } else {
                        Avatar(node.letter, variant = node.id % 2, size = 32.dp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(node.name, style = Type.body(14, FontWeight.Medium), color = Nocturne.text)
                        node.description?.let {
                            Text(
                                it,
                                style = Type.body(12),
                                color = Nocturne.muted(0.45f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        if (showUsers && state.users.isNotEmpty()) {
            item { SectionKicker(stringResource(R.string.search_scope_users), Modifier.padding(horizontal = Space.page, vertical = Space.s3)) }
            val users = if (state.scope == SearchScope.All) state.users.take(3) else state.users
            items(users, key = { "user-${it.id}" }) { user ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { navigator.openProfile(user.username) }
                        .padding(horizontal = Space.page, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s3),
                ) {
                    RemoteAvatar(
                        DiscourseConfig.avatarUrl(user.avatarTemplate, 80),
                        user.username.take(1).uppercase(),
                        size = 32.dp,
                    )
                    Column {
                        Text(
                            user.name?.takeIf { it.isNotBlank() } ?: user.username,
                            style = Type.body(14, FontWeight.Medium),
                            color = Nocturne.text,
                        )
                        Text("@${user.username}", style = Type.body(12), color = Nocturne.muted(0.45f))
                    }
                }
            }
        }

        if (showApps && state.apps.isNotEmpty()) {
            item { SectionKicker(stringResource(R.string.search_scope_apps), Modifier.padding(horizontal = Space.page, vertical = Space.s3)) }
            items(state.apps, key = { "app-${it.id}" }) { appItem ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { navigator.openApp(appItem.slug) }
                        .padding(horizontal = Space.page, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s3),
                ) {
                    RemoteAvatar(appItem.logoUrl, appItem.name.take(1), size = 32.dp, cornerRadius = 8.dp)
                    Column(Modifier.weight(1f)) {
                        Text(appItem.name, style = Type.body(14, FontWeight.Medium), color = Nocturne.text)
                        appItem.description?.let {
                            Text(
                                it,
                                style = Type.body(12),
                                color = Nocturne.muted(0.45f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        if (showPosts) {
            val posts = state.posts
            if (posts.isNotEmpty()) {
                item { SectionKicker(stringResource(R.string.search_scope_posts), Modifier.padding(horizontal = Space.page, vertical = Space.s3)) }
                items(posts, key = { "post-${it.id}" }) { post ->
                    PostRow(
                        post = post,
                        mode = if (state.scope == SearchScope.Media) NodeReadingMode.Card else NodeReadingMode.Expand,
                        onOpen = { navigator.openTopic(post.id) },
                        onOpenAuthor = { navigator.openProfile(it) },
                        onShare = {
                            navigator.share(context, "${DiscourseConfig.BASE_URL}/t/${post.id}", post.title)
                        },
                        onOpenImage = { index -> viewer = post.imageViewerAt(index) },
                    )
                }
            }
        }
    }
}
