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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.CirclePlus
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Layers
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
    onOpenRank: () -> Unit,
    onOpenInvite: () -> Unit,
    onOpenDrafts: () -> Unit,
    onOpenApp: (String) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenModDashboard: (String?) -> Unit,
    onOpenNodeAdmin: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFeed: (Long, String) -> Unit,
    onCreateFeed: () -> Unit,
    /** Whether the drawer is open: the parts that change between openings are asked for again then. */
    visible: Boolean,
) {
    val catalog: NodeCatalogViewModel = viewModel()
    val state by catalog.sidebarState.collectAsState()
    val services = ServiceLocator.get
    val user by services.session.currentUser.collectAsState()
    val privileges by services.moderation.privileges.collectAsState()
    val nodePending by services.moderation.nodePending.collectAsState()

    LaunchedEffect(Unit) { catalog.loadSidebar() }
    LaunchedEffect(visible, user?.id) { if (visible) catalog.refreshPersonal() }
    // A node moderator has no pushed count; the drawer is where they look, so
    // opening it is when the per-node numbers are worth a fetch.
    LaunchedEffect(privileges.nodes) { services.moderation.refreshNodeCounts() }
    var showAllManaged by remember { mutableStateOf(false) }
    var showAllJoined by remember { mutableStateOf(false) }
    // `/me/nodes` carries no logos; the directory the drawer already holds does.
    val logos = remember(state.nodes, state.recent, state.joined) {
        (state.joined + state.nodes + state.recent).filter { it.logoUrl != null }.associate { it.slug to it.logoUrl }
    }

    ModalDrawerSheet(
        drawerContainerColor = Nocturne.bg,
        modifier = Modifier.fillMaxHeight().width(300.dp),
    ) {
        Column(Modifier.windowInsetsPadding(WindowInsets.systemBars)) {
            LazyColumn(Modifier.weight(1f)) {
                user?.let { me ->
                    item { SidebarUserCard(me, privileges, onOpenProfile) }
                }

                // What left 我的 with its shortcut grid (the v292 board): the
                // board, the app directory, and the two that need an account.
                item {
                    SidebarAction(ImageVector.vectorResource(R.drawable.ic_me_rank), stringResource(R.string.me2_sc_rank)) { onOpenRank() }
                    SidebarAction(ImageVector.vectorResource(R.drawable.ic_me_apps), stringResource(R.string.sidebar_apps)) { onOpenApps() }
                    if (user != null) {
                        SidebarAction(ImageVector.vectorResource(R.drawable.ic_me_invite), stringResource(R.string.me2_sc_invite)) { onOpenInvite() }
                        SidebarAction(
                            ImageVector.vectorResource(R.drawable.ic_me_drafts),
                            stringResource(R.string.me2_sc_drafts),
                            detail = state.drafts?.takeIf { it > 0 }?.let { stringResource(R.string.sidebar_drafts_count, it) },
                        ) { onOpenDrafts() }
                    }
                }

                if (privileges.isModerator) {
                    item {
                        SidebarSectionHeader(
                            stringResource(R.string.mod_entry_managed_nodes),
                            action = stringResource(R.string.mod_entry_dashboard),
                            onAction = { onOpenModDashboard(null) },
                        )
                    }
                    // An account that runs dozens of nodes would push the rest of the
                    // drawer out of reach; the busiest few show, the rest on request.
                    val ordered = privileges.nodes.sortedByDescending { nodePending[it.slug] ?: 0 }
                    val shown = if (showAllManaged) ordered else ordered.take(MANAGED_PREVIEW)
                    items(shown, key = { "mod-${it.slug}" }) { node ->
                        SidebarManagedNodeRow(node, logos[node.slug], nodePending[node.slug] ?: 0) { onOpenNodeAdmin(node.slug) }
                    }
                    if (ordered.size > MANAGED_PREVIEW) {
                        item(key = "mod-more") {
                            SidebarAction(
                                if (showAllManaged) Lucide.ChevronUp else Lucide.ChevronDown,
                                if (showAllManaged) stringResource(R.string.mod_entry_show_less)
                                else stringResource(R.string.mod_entry_show_all, ordered.size),
                            ) { showAllManaged = !showAllManaged }
                        }
                    }
                }

                // 自定义信息流 and 常去节点, after the nodes this account runs:
                // the board's order, and the drawer's shortcuts into reading.
                if (user != null) {
                    item(key = "feeds-header") {
                        SidebarSectionHeader(
                            stringResource(R.string.sidebar_custom_feeds),
                            action = stringResource(R.string.sidebar_feed_new),
                            onAction = onCreateFeed,
                        )
                    }
                    items(state.feeds, key = { "feed-${it.id}" }) { feed ->
                        SidebarFeedRow(feed) { onOpenFeed(feed.id, feed.name) }
                    }
                }

                if (state.joined.isNotEmpty()) {
                    item(key = "joined-header") { SidebarSectionHeader(stringResource(R.string.me4_frequent_nodes)) }
                    // Someone in eighty nodes would otherwise scroll past all of them to
                    // reach anything below; the first few, the rest on request.
                    val joinedShown = if (showAllJoined) state.joined else state.joined.take(JOINED_PREVIEW)
                    items(joinedShown, key = { "joined-${it.id}" }) { node ->
                        SidebarNodeRow(node) { onOpenNode(node) }
                    }
                    if (state.joined.size > JOINED_PREVIEW) {
                        item(key = "joined-more") {
                            SidebarAction(
                                if (showAllJoined) Lucide.ChevronUp else Lucide.ChevronDown,
                                if (showAllJoined) stringResource(R.string.mod_entry_show_less)
                                else stringResource(R.string.mod_entry_show_all, state.joined.size),
                            ) { showAllJoined = !showAllJoined }
                        }
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
                    item { Spacer(Modifier.height(Space.s4)) }
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

                // Offered only to someone who has joined nothing yet: beside
                // their own nodes, twenty strangers' would bury them.
                if (state.nodes.isNotEmpty() && state.joined.isEmpty()) {
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
private fun SidebarManagedNodeRow(node: ModeratedNode, logoUrl: String?, pending: Int, onClick: () -> Unit) {
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
        if (logoUrl != null) RemoteAvatar(logoUrl, node.name.take(1), size = 28.dp)
        else Avatar(node.name.take(1), variant = node.slug.hashCode() and 1, size = 28.dp)
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
private fun SidebarAction(icon: ImageVector, label: String, detail: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.page, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Icon(icon, null, tint = Nocturne.muted(0.6f), modifier = Modifier.size(20.dp))
        Text(
            buildAnnotatedString {
                append(label)
                detail?.let { withStyle(SpanStyle(fontSize = Type.body(13).fontSize, color = Nocturne.textSecondary)) { append(it) } }
            },
            style = Type.body(15),
            color = Nocturne.text,
        )
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

/** How many managed nodes the drawer lists before "show all". */
private const val MANAGED_PREVIEW = 5

/** 常去节点 before "展开全部". */
private const val JOINED_PREVIEW = 8

/**
 * One custom feed: its colour square (the server hands every feed one of its
 * palette's bare `rrggbb`s), its name and how many nodes it reads.
 */
@Composable
private fun SidebarFeedRow(feed: com.nodeloc.app.core.network.ApiCustomFeedSummary, onClick: () -> Unit) {
    val ground = feed.color?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)
        ?.let { androidx.compose.ui.graphics.Color(0xFF000000 or it) }
        ?: Nocturne.accent700
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.page, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Box(
            Modifier.size(26.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(ground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.Layers, null, tint = Nocturne.neutral100, modifier = Modifier.size(15.dp))
        }
        Text(
            buildAnnotatedString {
                append(feed.name)
                withStyle(SpanStyle(fontSize = Type.body(13).fontSize, color = Nocturne.textSecondary)) {
                    append(stringResource(R.string.sidebar_feed_nodes, feed.nodeCount))
                }
            },
            style = Type.body(14, FontWeight.Medium),
            color = Nocturne.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
