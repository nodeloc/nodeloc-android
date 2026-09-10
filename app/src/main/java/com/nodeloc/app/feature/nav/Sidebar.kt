package com.nodeloc.app.feature.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.CirclePlus
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.LayoutGrid
import com.nodeloc.app.R
import com.nodeloc.app.core.design.Avatar
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.SectionKicker
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.model.NodeSummary
import com.nodeloc.app.core.model.SidebarDiscourseApp
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.feature.node.NodeCatalogViewModel

/**
 * The global drawer: nodes, recent visits, the app directory and settings.
 * Reached from every root screen's hamburger, so it is defined once.
 */
@Composable
fun SidebarDrawer(
    onClose: () -> Unit,
    onOpenNode: (NodeSummary) -> Unit,
    onBrowseNodes: () -> Unit,
    onCreateNode: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenApp: (String) -> Unit,
) {
    val catalog: NodeCatalogViewModel = viewModel()
    val state by catalog.sidebarState.collectAsState()

    LaunchedEffect(Unit) { catalog.loadSidebar() }

    ModalDrawerSheet(
        drawerContainerColor = Nocturne.bg,
        modifier = Modifier.fillMaxHeight().width(300.dp),
    ) {
        Column(Modifier.windowInsetsPadding(WindowInsets.systemBars)) {
            LazyColumn(Modifier.weight(1f)) {
                item {
                    Spacer(Modifier.height(Space.s4))
                    SidebarAction(Lucide.LayoutGrid, stringResource(R.string.node_browse)) { onBrowseNodes() }
                    SidebarAction(Lucide.CirclePlus, stringResource(R.string.node_create_title)) { onCreateNode() }
                    Spacer(Modifier.height(Space.s4))
                }

                if (state.apps.isNotEmpty()) {
                    item {
                        SectionKicker(
                            stringResource(R.string.apps_recent),
                            Modifier.padding(horizontal = Space.page, vertical = Space.s3),
                        )
                    }
                    items(state.apps, key = { "app-${it.id}" }) { app ->
                        SidebarAppRow(app) { onOpenApp(app.slug) }
                    }
                    item {
                        SidebarAction(Lucide.LayoutGrid, stringResource(R.string.apps_more)) { onOpenApps() }
                        Spacer(Modifier.height(Space.s4))
                    }
                }

                if (state.recent.isNotEmpty()) {
                    item {
                        SectionKicker(
                            stringResource(R.string.node_recent),
                            Modifier.padding(horizontal = Space.page, vertical = Space.s3),
                        )
                    }
                    items(state.recent, key = { "recent-${it.id}" }) { node ->
                        SidebarNodeRow(node) { onOpenNode(node) }
                    }
                    item { Spacer(Modifier.height(Space.s4)) }
                }

                if (state.nodes.isNotEmpty()) {
                    item {
                        SectionKicker(
                            stringResource(R.string.tab_nodes),
                            Modifier.padding(horizontal = Space.page, vertical = Space.s3),
                        )
                    }
                    items(state.nodes, key = { "node-${it.id}" }) { node ->
                        SidebarNodeRow(node) { onOpenNode(node) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.page, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Icon(icon, null, tint = Nocturne.muted(0.6f), modifier = Modifier.size(20.dp))
        Text(label, style = Type.body(15), color = Nocturne.text)
    }
}

@Composable
private fun SidebarNodeRow(node: NodeSummary, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.page, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        if (node.logoUrl != null) {
            RemoteAvatar(node.logoUrl, node.letter, size = 26.dp)
        } else {
            Avatar(node.letter, variant = node.id % 2, size = 26.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                node.name,
                style = Type.body(14, FontWeight.Medium),
                color = Nocturne.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            node.memberCount?.let {
                Text(pluralStringResource(R.plurals.common_members, it, DiscourseFormat.count(it)), style = Type.body(11), color = Nocturne.muted(0.4f))
            }
        }
    }
}

@Composable
private fun SidebarAppRow(app: SidebarDiscourseApp, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.page, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        val letter = app.name.take(1).ifBlank { "?" }
        if (app.logoUrl != null) {
            RemoteAvatar(DiscourseConfig.absoluteUrl(app.logoUrl), letter, size = 26.dp, cornerRadius = 6.dp)
        } else {
            Avatar(letter, variant = app.id % 2, size = 26.dp, cornerRadius = 6.dp)
        }
        Text(
            app.name,
            style = Type.body(14, FontWeight.Medium),
            color = Nocturne.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
