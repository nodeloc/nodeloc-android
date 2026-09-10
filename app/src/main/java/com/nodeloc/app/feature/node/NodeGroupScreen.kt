package com.nodeloc.app.feature.node

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.WifiOff
import com.nodeloc.app.R
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FloatingHeaderBar
import com.nodeloc.app.core.design.HeaderGroup
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.SkeletonBox
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.floatingHeaderInset
import com.nodeloc.app.core.design.isScrolledPastTop
import com.nodeloc.app.core.design.skeletonPulsing
import com.nodeloc.app.core.design.rememberTapSafeOverscroll
import com.nodeloc.app.core.network.isOfflineError
import com.nodeloc.app.feature.nav.AppViewModel
import com.nodeloc.app.feature.nav.Navigator

/**
 * Every node in one group.
 *
 * The browse page shows four per group and sends the rest here rather than
 * growing a shelf without end: a list that is only reachable by scrolling a row
 * sideways is a list nobody finishes.
 */
@Composable
fun NodeGroupScreen(
    categoryId: Int,
    groupName: String,
    app: AppViewModel,
    navigator: Navigator,
) {
    val viewModel: NodeGroupViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val isSignedIn by app.isSignedIn.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(categoryId) { viewModel.bind(categoryId) }

    // Paged on approach, not on arrival: waiting for the last card to appear
    // puts a stall at the bottom of every screenful.
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { nearEnd }.collect { if (it) viewModel.loadMore() }
    }

    Box(Modifier.fillMaxSize().background(Nocturne.bg)) {
        when {
            state.showSkeleton -> Column(
                Modifier
                    .padding(top = floatingHeaderInset, start = Space.page, end = Space.page)
                    .skeletonPulsing(),
                verticalArrangement = Arrangement.spacedBy(Space.s3),
            ) {
                repeat(6) { SkeletonBox(Modifier.fillMaxWidth().height(NODE_CARD_HEIGHT)) }
            }

            state.nodes.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val offline = state.error?.isOfflineError() == true
                EmptyStateView(
                    icon = Lucide.WifiOff,
                    title = stringResource(if (offline) R.string.error_offline else R.string.node_empty),
                    retryLabel = if (state.error != null) stringResource(R.string.common_retry) else null,
                    onRetry = viewModel::retry,
                )
            }

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = floatingHeaderInset,
                    start = Space.page,
                    end = Space.page,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(Space.s3),
                overscrollEffect = rememberTapSafeOverscroll(),
            ) {
                items(state.nodes, key = { it.id }) { node ->
                    NodeCard(
                        node = node,
                        isSignedIn = isSignedIn,
                        onToggleJoin = {
                            if (isSignedIn) viewModel.toggleJoin(node.id) else navigator.openAuth()
                        },
                        onClick = { navigator.openNode(node.id, node.slug) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.isLoadingMore) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = Space.s4), Alignment.Center) {
                            NodelocLoader(height = 22.dp)
                        }
                    }
                }
            }
        }

        FloatingHeaderBar(
            scrolled = listState.isScrolledPastTop(threshold = 0),
            leading = {
                HeaderIconButton(Lucide.ArrowLeft, stringResource(R.string.common_back), onClick = navigator::back)
            },
            center = {
                HeaderGroup {
                    Text(
                        groupName,
                        style = Type.heading(17, FontWeight.SemiBold),
                        color = Nocturne.headerText,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                }
            },
        )
    }
}
