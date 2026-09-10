package com.nodeloc.app.feature.search

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.WifiOff
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.SearchX
import com.composables.icons.lucide.Search
import com.nodeloc.app.R
import com.nodeloc.app.core.network.isOfflineError
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.HairLine
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.Radius
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.model.NodeReadingMode
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.feature.feed.PostRow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nodeloc.app.feature.media.ImageViewerOverlay
import com.nodeloc.app.feature.media.PostImageViewer
import com.nodeloc.app.feature.media.imageViewerAt
import com.nodeloc.app.feature.nav.Navigator

/**
 * Search scoped to one node. Discourse expresses that as a `#slug` prefix in
 * the query, so the field opens pre-filled with it and the user types after.
 */
@Composable
fun NodeSearchScreen(slug: String, navigator: Navigator) {
    val viewModel: SearchViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    // Tapping a picture in a row opens it full-screen rather than the topic;
    // the row itself still opens the topic everywhere else.
    var viewer by remember { mutableStateOf<PostImageViewer?>(null) }
    val focusRequester = remember { FocusRequester() }

    // This screen exists only to be typed into.
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    LaunchedEffect(slug) { viewModel.updateQuery("#$slug ") }

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
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Radius.lg))
                    .background(Nocturne.surface)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Lucide.Search, null, tint = Nocturne.muted(0.4f), modifier = Modifier.size(18.dp))
                BasicTextField(
                    value = state.query,
                    onValueChange = viewModel::updateQuery,
                    singleLine = true,
                    textStyle = Type.body(15).copy(color = Nocturne.text),
                    cursorBrush = SolidColor(Nocturne.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.submit(); keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
            }
        }
        HairLine()

        when {
            state.isSearching && state.isEmpty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                NodelocLoader(height = 48.dp)
            }

            // Same order as the main search: a failure is not an empty result.
            state.error != null && state.posts.isEmpty() -> {
                val offline = state.error?.isOfflineError() == true
                EmptyStateView(
                    icon = if (offline) Lucide.WifiOff else Lucide.CircleAlert,
                    title = stringResource(if (offline) R.string.error_offline else R.string.search_failed),
                    retryLabel = stringResource(R.string.common_retry),
                    onRetry = viewModel::submit,
                )
            }

            state.hasSearched && state.posts.isEmpty() -> EmptyStateView(
                icon = Lucide.SearchX,
                title = stringResource(R.string.search_node_empty),
            )

            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.posts, key = { it.id }) { post ->
                    PostRow(
                        post = post,
                        mode = NodeReadingMode.Expand,
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

        viewer?.let { open ->
            ImageViewerOverlay(open.images, open.index, open.source) { viewer = null }
        }
    }
}
