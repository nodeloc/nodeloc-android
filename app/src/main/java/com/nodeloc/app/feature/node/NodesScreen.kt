package com.nodeloc.app.feature.node

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.nodeloc.app.core.design.isScrolledPastTop
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.WifiOff
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Menu
import com.nodeloc.app.R
import com.nodeloc.app.core.design.Avatar
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FloatingHeaderBar
import com.nodeloc.app.core.design.GhostButton
import com.nodeloc.app.core.design.HeaderGroup
import com.nodeloc.app.core.design.GuestLoginButton
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Radius
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.SkeletonBox
import com.nodeloc.app.core.design.SkeletonLine
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.floatingHeaderInset
import com.nodeloc.app.core.design.skeletonPulsing
import com.nodeloc.app.core.design.rememberTapSafeOverscroll
import com.nodeloc.app.core.model.NodeSummary
import com.nodeloc.app.core.network.isOfflineError
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.feature.nav.AppViewModel
import com.nodeloc.app.feature.nav.Navigator

/** The 节点 tab: grouped horizontal shelves, each paging on its own "更多". */
@Composable
fun NodesScreen(
    app: AppViewModel,
    navigator: Navigator,
    contentPadding: PaddingValues,
    onOpenDrawer: () -> Unit,
) {
    val viewModel: NodeCatalogViewModel = viewModel()
    val state by viewModel.browseState.collectAsState()
    val isSignedIn by app.isSignedIn.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { viewModel.loadBrowse() }

    Box(Modifier.fillMaxSize().background(Nocturne.bg)) {
        when {
            state.showSkeleton -> BrowseSkeleton(Modifier.padding(top = floatingHeaderInset))

            state.groups.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val offline = state.error?.isOfflineError() == true
                EmptyStateView(
                    icon = Lucide.WifiOff,
                    title = stringResource(if (offline) R.string.error_offline else R.string.node_empty),
                    retryLabel = if (state.error != null) stringResource(R.string.common_retry) else null,
                    onRetry = { viewModel.loadBrowse(force = true) },
                )
            }

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = floatingHeaderInset,
                    bottom = contentPadding.calculateBottomPadding() + 24.dp,
                ),
                overscrollEffect = rememberTapSafeOverscroll(),
            ) {
                items(state.groups, key = { it.id }) { group ->
                    Column(Modifier.padding(bottom = Space.s6)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.s3),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                group.name,
                                style = Type.heading(17, FontWeight.SemiBold),
                                color = Nocturne.text,
                                modifier = Modifier.weight(1f),
                            )
                            if (group.hasMore) {
                                GhostButton(stringResource(R.string.common_more)) {
                                    navigator.openNodeGroup(group.id, group.name)
                                }
                            }
                        }
                        // Two rows that scroll sideways together. A card is
                        // 80% of the page, so the column beside it is always
                        // part-visible — the shelf says it continues without a
                        // scrollbar or a caption saying so.
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val cardWidth = maxWidth * NODE_CARD_FRACTION
                            LazyHorizontalGrid(
                                rows = GridCells.Fixed(2),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(NODE_CARD_HEIGHT * 2 + Space.s3),
                                contentPadding = PaddingValues(horizontal = Space.page),
                                horizontalArrangement = Arrangement.spacedBy(Space.s3),
                                verticalArrangement = Arrangement.spacedBy(Space.s3),
                            ) {
                                items(group.nodes, key = { it.id }) { node ->
                                    NodeCard(
                                        node = node,
                                        isSignedIn = isSignedIn,
                                        onToggleJoin = {
                                            if (isSignedIn) viewModel.toggleJoin(node.id) else navigator.openAuth()
                                        },
                                        onClick = { navigator.openNode(node.id, node.slug) },
                                        modifier = Modifier.width(cardWidth),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        FloatingHeaderBar(
            scrolled = listState.isScrolledPastTop(threshold = 0),
            leading = { HeaderIconButton(Lucide.Menu, stringResource(R.string.common_menu), onClick = onOpenDrawer) },
            center = {
                // Glassed like the controls either side of it: the title floats
                // over the scrolling cards, and bare text on them is unreadable.
                HeaderGroup {
                    Text(
                        stringResource(R.string.tab_nodes),
                        style = Type.heading(17, FontWeight.SemiBold),
                        color = Nocturne.headerText,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                }
            },
            trailing = {
                if (isSignedIn) {
                    HeaderIconButton(Lucide.Plus, stringResource(R.string.node_create), onClick = { navigator.openCreateNode() })
                } else {
                    GuestLoginButton(onClick = { navigator.openAuth() })
                }
            },
        )
    }
}

@Composable
private fun BrowseSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().skeletonPulsing()) {
        repeat(3) {
            Column(Modifier.padding(bottom = Space.s6)) {
                SkeletonLine(
                    Modifier.padding(horizontal = Space.page, vertical = Space.s3).width(120.dp),
                    height = 16.dp,
                )
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val cardWidth = maxWidth * NODE_CARD_FRACTION
                    Column(
                        Modifier.padding(horizontal = Space.page),
                        verticalArrangement = Arrangement.spacedBy(Space.s3),
                    ) {
                        repeat(2) {
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                                repeat(2) {
                                    SkeletonBox(Modifier.width(cardWidth).height(NODE_CARD_HEIGHT))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A card takes most of the page; the rest is the next column showing through. */
private const val NODE_CARD_FRACTION = 0.8f
