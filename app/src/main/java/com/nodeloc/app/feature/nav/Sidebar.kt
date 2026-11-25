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
import com.composables.icons.lucide.Settings
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.PillTone
import com.nodeloc.app.core.design.StatusPill
import com.nodeloc.app.core.model.CurrentUser
import com.nodeloc.app.core.store.ModPrivileges
import com.nodeloc.app.core.store.ModeratedNode
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
    onOpenProfile: () -> Unit,
    onOpenModDashboard: (String?) -> Unit,
    onOpenNodeAdmin: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val catalog: NodeCatalogViewModel = viewModel()
    val state by catalog.sidebarState.collectAsState()
    val services = ServiceLocator.get
    val user by services.session.currentUser.collectAsState()
    val privileges by services.moderation.privileges.collectAsState()
    val nodePending by services.moderation.nodePending.collectAsState()

    LaunchedEffect(Unit) { catalog.loadSidebar() }
    // A node moderator has no pushed count; the drawer is where they look, so
    // opening it is when the per-node numbers are worth a fetch.
    LaunchedEffect(privileges.nodes) { services.moderation.refreshNodeCounts() }

    ModalDrawerSheet(
        drawerContainerColor = Nocturne.bg,
        modifier = Modifier.fillMaxHeight().width(300.dp),
    ) {
        Column(Modifier.windowInsetsPadding(WindowInsets.systemBars)) {
            LazyColumn(Modifier.weight(1f)) {
                user?.let { me ->
                    item { SidebarUserCard(me, privileges, onOpenProfile) }
                }

                if (privileges.isModerator) {
                    item {
                        SidebarSectionHeader(
                            stringResource(R.string.mod_entry_managed_nodes),
                            action = stringResource(R.string.mod_entry_dashboard),
                            onAction = { onOpenModDashboard(null) },
                        )
                    }
                    items(privileges.nodes, key = { "mod-${it.slug}" }) { node ->
                        SidebarManagedNodeRow(node, nodePending[node.slug] ?: 0) { onOpenNodeAdmin(node.slug) }
                    }
                }

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

                item {
                    Spacer(Modifier.height(Space.s4))
                    SidebarAction(Lucide.Settings, stringResource(R.string.common_settings)) { onOpenSettings() }
                    Spacer(Modifier.height(Space.s4))
                }
            }
        }
    }
}

@Composable
private fun SidebarUserCard(user: CurrentUser, privileges: ModPrivileges, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.page, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val avatar = DiscourseConfig.avatarUrl(user.avatarTemplate, 120)
        if (avatar != null) RemoteAvatar(avatar, user.username.take(1), size = 48.dp)
        else Avatar(user.username.take(1), variant = user.id % 2, size = 48.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    user.name?.takeIf { it.isNotBlank() } ?: user.username,
                    style = Type.body(18, FontWeight.Bold),
                    color = Nocturne.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                when {
                    privileges.isAdmin -> StatusPill(stringResource(R.string.mod_entry_role_admin), PillTone.Accent)
                    privileges.isModerator -> StatusPill(stringResource(R.string.mod_entry_role_moderator), PillTone.Accent)
                }
            }
            user.gamificationScore?.let { energy ->
                Text(
                    stringResource(R.string.mod_entry_energy, DiscourseFormat.count(energy)),
                    style = Type.body(13),
                    color = Nocturne.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun SidebarSectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = Space.page, end = 4.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Type.body(13), color = Nocturne.textSecondary, modifier = Modifier.weight(1f))
        if (action != null) {
            Text(
                action,
                style = Type.body(13, FontWeight.SemiBold),
                color = Nocturne.accent,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onAction).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** The design's managed-node row: a 52-high pill row with the node's pending count in a warning badge. */
@Composable
private fun SidebarManagedNodeRow(node: ModeratedNode, pending: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(52.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(node.name.take(1), variant = node.slug.hashCode() and 1, size = 28.dp)
        Text(
            "n/${node.slug}",
            style = Type.body(16, FontWeight.Medium),
            color = Nocturne.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (pending > 0) {
            Box(
                Modifier
                    .height(22.dp)
                    .clip(CircleShape)
                    .background(Nocturne.warningBg)
                    .padding(horizontal = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (pending >= 50) "50+" else pending.toString(), style = Type.body(12, FontWeight.Bold), color = Nocturne.warning)
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
