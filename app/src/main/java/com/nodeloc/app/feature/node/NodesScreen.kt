package com.nodeloc.app.feature.node

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.WifiOff
import com.composables.icons.lucide.X
import com.nodeloc.app.R
import com.nodeloc.app.core.design.Avatar
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FilterPill
import com.nodeloc.app.core.design.FilterPillRow
import com.nodeloc.app.core.design.GuestLoginButton
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.model.NodeSummary
import com.nodeloc.app.core.network.isOfflineError
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.feature.feed.RowsSkeleton
import com.nodeloc.app.feature.nav.AppViewModel
import com.nodeloc.app.feature.nav.DrawerMenuButton
import com.nodeloc.app.feature.nav.Navigator

/** The 节点 tab, the node square: search, sections, the nodes you are in, the busiest of the rest. */
@Composable
fun NodesScreen(
    app: AppViewModel,
    navigator: Navigator,
    contentPadding: PaddingValues,
    onOpenDrawer: () -> Unit,
) {
    val viewModel: NodeCatalogViewModel = viewModel()
    val state by viewModel.squareState.collectAsState()
    val isSignedIn by app.isSignedIn.collectAsState()

    // Every visit re-reads, because membership changes elsewhere — the node
    // page has its own join button. A join clears the client's directory
    // cache; otherwise the read is answered from it and costs nothing.
    LaunchedEffect(isSignedIn) { viewModel.loadSquare() }

    Column(Modifier.fillMaxSize().background(Nocturne.bg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(64.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DrawerMenuButton(onClick = onOpenDrawer)
            Text(
                stringResource(R.string.nodes2_title),
                style = Type.heading(22, FontWeight.Normal),
                color = Nocturne.text,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (isSignedIn) {
                HeaderIconButton(Lucide.Plus, stringResource(R.string.node_create), onClick = { navigator.openCreateNode() })
            } else {
                GuestLoginButton(modifier = Modifier.padding(end = 8.dp), onClick = { navigator.openAuth() })
            }
        }

        SearchField(state.query, viewModel::updateQuery)

        if (state.sections.isNotEmpty()) {
            FilterPillRow {
                FilterPill(stringResource(R.string.nodes2_recommended), state.sectionId == null) { viewModel.selectSection(null) }
                state.sections.forEach { section ->
                    FilterPill(section.name, state.sectionId == section.id) { viewModel.selectSection(section.id) }
                }
            }
        }

        when {
            state.nodes.isEmpty() && state.isLoading -> RowsSkeleton()
            state.nodes.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val offline = state.error?.isOfflineError() == true
                EmptyStateView(
                    icon = Lucide.WifiOff,
                    title = stringResource(if (offline) R.string.error_offline else R.string.node_empty),
                    retryLabel = if (state.error != null) stringResource(R.string.common_retry) else null,
                    onRetry = { viewModel.loadSquare(force = true) },
                )
            }
            else -> {
                val shown = state.shown
                val joined = state.joined
                // Searching is looking for one node; the shelf of your own
                // would only push the answer down.
                val showJoined = isSignedIn && state.query.isBlank() && state.sectionId == null
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
                ) {
                    if (showJoined) {
                        item(key = "joined-label") {
                            SectionLabel(stringResource(R.string.nodes2_joined, joined.size))
                        }
                        item(key = "joined-row") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
                            ) {
                                items(joined, key = { it.id }) { node ->
                                    JoinedNode(node) { navigator.openNode(node.id, node.slug) }
                                }
                                item(key = "create") { CreateNodeTile { navigator.openCreateNode() } }
                            }
                        }
                    }
                    item(key = "popular-label") {
                        SectionLabel(
                            if (state.query.isNotBlank()) stringResource(R.string.nodes2_results)
                            else stringResource(R.string.nodes2_popular),
                        )
                    }
                    if (shown.isEmpty()) {
                        item(key = "none") {
                            EmptyStateView(icon = Lucide.Search, title = stringResource(R.string.nodes2_no_match))
                        }
                    }
                    items(shown, key = { it.id }) { node ->
                        NodeRow(
                            node = node,
                            isSignedIn = isSignedIn,
                            onClick = { navigator.openNode(node.id, node.slug) },
                            onToggleJoin = { if (isSignedIn) viewModel.toggleJoin(node.id) else navigator.openAuth() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp)
            .fillMaxWidth()
            .height(48.dp)
            .clip(CircleShape)
            .background(Nocturne.neutral300)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Lucide.Search, null, tint = Nocturne.textSecondary, modifier = Modifier.size(20.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = Type.body(15).copy(color = Nocturne.text),
            cursorBrush = SolidColor(Nocturne.accent),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(stringResource(R.string.nodes2_search_hint), style = Type.body(15), color = Nocturne.textSecondary)
                }
                inner()
            },
        )
        if (value.isNotEmpty()) {
            Icon(
                Lucide.X,
                stringResource(R.string.common_close),
                tint = Nocturne.textSecondary,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable { onChange("") },
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = Type.body(13, FontWeight.SemiBold),
        color = Nocturne.textSecondary,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
}

@Composable
private fun NodeMark(node: NodeSummary, size: androidx.compose.ui.unit.Dp) {
    if (node.logoUrl != null) {
        RemoteAvatar(node.logoUrl, node.letter, size = size)
    } else {
        Avatar(node.letter, variant = node.id % 2, size = size)
    }
}

@Composable
private fun JoinedNode(node: NodeSummary, onClick: () -> Unit) {
    Column(
        Modifier.width(76.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NodeMark(node, 52.dp)
        Text(node.handle, style = Type.body(12, FontWeight.Medium), color = Nocturne.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CreateNodeTile(onClick: () -> Unit) {
    Column(
        Modifier.width(76.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(52.dp).border(1.5.dp, Nocturne.neutral500, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.Plus, null, tint = Nocturne.textSecondary, modifier = Modifier.size(22.dp))
        }
        Text(stringResource(R.string.node_create), style = Type.body(12), color = Nocturne.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun NodeRow(node: NodeSummary, isSignedIn: Boolean, onClick: () -> Unit, onToggleJoin: () -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NodeMark(node, 44.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    // The handle is what the node is called in a link and in
                    // search; the display name is what it is called in prose.
                    Text(node.handle, style = Type.body(16, FontWeight.SemiBold), color = Nocturne.text, maxLines = 1)
                    Text(
                        node.name,
                        style = Type.body(13),
                        color = Nocturne.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                node.description?.let {
                    Text(
                        it,
                        style = Type.body(13),
                        color = Nocturne.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                node.memberCount?.let {
                    Text(
                        stringResource(R.string.nodes2_members, DiscourseFormat.count(it)),
                        style = Type.body(12),
                        color = Nocturne.textTertiary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            JoinButton(joined = isSignedIn && node.isJoined, onClick = onToggleJoin)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.neutral300))
    }
}

/** Filled when it is an offer, outlined when it is a state. */
@Composable
private fun JoinButton(joined: Boolean, onClick: () -> Unit) {
    val ink = if (Nocturne.isDark) Nocturne.neutral900 else Nocturne.neutral100
    Box(
        Modifier
            .height(32.dp)
            .clip(CircleShape)
            .then(
                if (joined) Modifier.border(1.dp, Nocturne.neutral500, CircleShape)
                else Modifier.background(Nocturne.accent),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(if (joined) R.string.node_joined else R.string.node_join),
            style = Type.body(13, FontWeight.SemiBold),
            color = if (joined) Nocturne.textSecondary else ink,
        )
    }
}
