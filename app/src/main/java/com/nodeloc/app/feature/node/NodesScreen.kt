package com.nodeloc.app.feature.node

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
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
import com.nodeloc.app.core.design.GuestLoginButton
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.SecondaryButton
import com.nodeloc.app.core.design.SkeletonBox
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.animatedColor
import com.nodeloc.app.core.design.pressScale
import com.nodeloc.app.core.design.rememberPressSource
import com.nodeloc.app.core.design.skeletonPulsing
import com.nodeloc.app.core.model.NodeSummary
import com.nodeloc.app.core.network.isOfflineError
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.feature.nav.AppViewModel
import com.nodeloc.app.feature.nav.DrawerMenuButton
import com.nodeloc.app.feature.nav.Navigator

/**
 * The 节点 tab, the node square: a heading, search, category chips, then the
 * directory as cards.
 *
 * The design's 为你推荐 / 和 n/x 相似 / 最受欢迎（按周访客）groups are computed
 * by the server for the web square only — no JSON endpoint carries them or the
 * weekly visitor count — so the phone shows the one ordering it can stand
 * behind, by members, and says so in the section note.
 */
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

    val createNode = { if (isSignedIn) navigator.openCreateNode() else navigator.openAuth() }

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
            Spacer(Modifier.weight(1f))
            if (isSignedIn) {
                HeaderIconButton(Lucide.Plus, stringResource(R.string.node_create), onClick = { navigator.openCreateNode() })
            } else {
                GuestLoginButton(modifier = Modifier.padding(end = 8.dp), onClick = { navigator.openAuth() })
            }
        }

        val shown = state.shown
        val joined = state.joined
        val searching = state.query.isNotBlank()
        // Searching is looking for one node; the shelf of your own would only
        // push the answer down.
        val showJoined = isSignedIn && !searching && state.sectionId == null && joined.isNotEmpty()

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        ) {
            item(key = "header") { SquareHeader(state.query, viewModel::updateQuery, createNode) }

            if (state.sections.isNotEmpty()) {
                item(key = "chips") {
                    CategoryChips(state.sections, state.sectionId, viewModel::selectSection)
                }
            }

            when {
                state.nodes.isEmpty() && state.isLoading -> item(key = "skeleton") { CardsSkeleton() }
                state.nodes.isEmpty() -> item(key = "error") {
                    val offline = state.error?.isOfflineError() == true
                    EmptyStateView(
                        icon = Lucide.WifiOff,
                        title = stringResource(if (offline) R.string.error_offline else R.string.node_empty),
                        retryLabel = if (state.error != null) stringResource(R.string.common_retry) else null,
                        onRetry = { viewModel.loadSquare(force = true) },
                    )
                }
                searching && shown.isEmpty() -> item(key = "none") { NoMatch(state.query.trim(), createNode) }
                else -> {
                    if (showJoined) {
                        item(key = "joined-label") {
                            SectionHeading(stringResource(R.string.nodes2_joined, joined.size), top = 20.dp)
                        }
                        item(key = "joined-row") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 8.dp),
                            ) {
                                items(joined, key = { it.id }) { node ->
                                    JoinedNode(node) { navigator.openNode(node.id, node.slug) }
                                }
                                item(key = "create") { CreateNodeTile(createNode) }
                            }
                        }
                    }
                    item(key = "list-label") {
                        val section = state.sections.firstOrNull { it.id == state.sectionId }
                        when {
                            searching -> SectionHeading(
                                stringResource(R.string.nodes2_results),
                                note = stringResource(R.string.nodes2_count_note, shown.size),
                                top = if (showJoined) 28.dp else 20.dp,
                            )
                            section != null -> SectionHeading(
                                section.name,
                                note = stringResource(R.string.nodes2_count_note, shown.size),
                                top = 20.dp,
                            )
                            else -> SectionHeading(
                                stringResource(R.string.nodes2_popular_title),
                                note = stringResource(R.string.nodes2_popular_note),
                                top = if (showJoined) 28.dp else 20.dp,
                            )
                        }
                    }
                    if (shown.isEmpty()) {
                        item(key = "empty-section") {
                            EmptyStateView(icon = Lucide.Search, title = stringResource(R.string.nodes2_no_match))
                        }
                    }
                    items(shown.take(state.limit), key = { it.id }) { node ->
                        NodeCardRow(
                            node = node,
                            joined = isSignedIn && node.isJoined,
                            onClick = { navigator.openNode(node.id, node.slug) },
                            onToggleJoin = { if (isSignedIn) viewModel.toggleJoin(node.id) else navigator.openAuth() },
                        )
                    }
                    if (shown.size > state.limit) {
                        item(key = "more") {
                            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                                SecondaryButton(stringResource(R.string.nodes2_show_more), onClick = viewModel::showMore)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SquareHeader(query: String, onQuery: (String) -> Unit, onCreate: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        Text(stringResource(R.string.nodes2_title), style = Type.heading(24, FontWeight.Bold), color = Nocturne.text)
        Text(
            stringResource(R.string.nodes2_subtitle),
            style = Type.body(13),
            color = Nocturne.textSecondary,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchField(query, onQuery, Modifier.weight(1f))
            val press = rememberPressSource()
            val create = stringResource(R.string.node_create)
            Box(
                Modifier
                    .pressScale(press)
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(Nocturne.accent)
                    .semantics { contentDescription = create }
                    .clickable(interactionSource = press, indication = ripple(), role = Role.Button, onClick = onCreate)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.Plus,
                    null,
                    tint = if (Nocturne.isDark) Nocturne.neutral900 else Nocturne.neutral100,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(44.dp)
            .clip(CircleShape)
            .border(1.dp, Nocturne.textTertiary, CircleShape)
            .background(Nocturne.bg)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Lucide.Search, null, tint = Nocturne.textSecondary, modifier = Modifier.size(18.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = Type.body(14).copy(color = Nocturne.text),
            cursorBrush = SolidColor(Nocturne.accent),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        stringResource(R.string.nodes2_search_placeholder),
                        style = Type.body(14),
                        color = Nocturne.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
private fun CategoryChips(sections: List<NodeSquareSection>, selectedId: Int?, onSelect: (Int?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            item(key = "all") { CategoryChip(stringResource(R.string.nodes2_all), selectedId == null) { onSelect(null) } }
            items(sections, key = { it.id }) { section ->
                CategoryChip(section.name, selectedId == section.id) { onSelect(section.id) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.divider))
    }
}

/**
 * Outlined rather than the grey [com.nodeloc.app.core.design.FilterPill]:
 * on the square the chips sit right under an outlined search field, and the
 * board draws them as white capsules with a hairline.
 */
@Composable
private fun CategoryChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val ground by animatedColor(if (selected) Nocturne.text else Nocturne.bg, "chipGround")
    val edge by animatedColor(if (selected) Nocturne.text else Nocturne.divider, "chipEdge")
    val ink by animatedColor(if (selected) Nocturne.bg else Nocturne.text, "chipInk")
    val press = rememberPressSource()
    Box(
        Modifier
            .pressScale(press)
            .height(36.dp)
            .clip(CircleShape)
            .background(ground)
            .border(1.dp, edge, CircleShape)
            .semantics { this.selected = selected }
            .clickable(interactionSource = press, indication = ripple(), role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.body(13, FontWeight.Bold), color = ink, maxLines = 1)
    }
}

@Composable
private fun SectionHeading(title: String, note: String? = null, top: Dp) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = top),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = Type.heading(18, FontWeight.Bold), color = Nocturne.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (note != null) {
            Text(
                note,
                style = Type.body(13),
                color = Nocturne.textSecondary,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}

/** The node's own colour behind its letter, as on the web square; a logo wins when there is one. */
@Composable
private fun NodeMark(node: NodeSummary, size: Dp) {
    val ground = nodeColor(node.colorHex)
    when {
        node.logoUrl != null -> RemoteAvatar(node.logoUrl, node.letter, size = size)
        ground != null -> Avatar(node.letter, size = size, bg = ground, fg = Nocturne.neutral100)
        else -> Avatar(node.letter, variant = node.id % 2, size = size)
    }
}

/** Server colours arrive as bare `rrggbb`; anything else falls back to the palette. */
private fun nodeColor(hex: String?): Color? {
    val digits = hex?.trim()?.removePrefix("#") ?: return null
    if (digits.length != 6) return null
    return digits.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}

@Composable
private fun JoinedNode(node: NodeSummary, onClick: () -> Unit) {
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
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
        Modifier.width(76.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
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
private fun NodeCardRow(node: NodeSummary, joined: Boolean, onClick: () -> Unit, onToggleJoin: () -> Unit) {
    val press = rememberPressSource()
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .pressScale(press)
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, Nocturne.divider, shape)
            .clickable(interactionSource = press, indication = ripple(), onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NodeMark(node, 40.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    node.name,
                    style = Type.body(15, FontWeight.Bold),
                    color = Nocturne.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The board puts weekly visitors here; the JSON directory has
                // no such count, so it carries the member count instead.
                val meta = node.memberCount?.let {
                    "${node.handle} · ${stringResource(R.string.nodes2_members, DiscourseFormat.count(it))}"
                } ?: node.handle
                Text(meta, style = Type.body(12), color = Nocturne.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            JoinButton(node.name, joined, onToggleJoin)
        }
        // Two lines always reserved, so cards with and without a blurb line up.
        Text(
            node.description.orEmpty(),
            style = Type.body(13),
            color = Nocturne.textSecondary,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Grey when it is an offer, outlined when it is a state. */
@Composable
private fun JoinButton(name: String, joined: Boolean, onClick: () -> Unit) {
    val ground by animatedColor(if (joined) Nocturne.bg else Nocturne.neutral400, "joinGround")
    val edge by animatedColor(if (joined) Nocturne.neutral500 else Nocturne.neutral400, "joinEdge")
    val ink by animatedColor(if (joined) Nocturne.textSecondary else Nocturne.text, "joinInk")
    val press = rememberPressSource()
    val label = stringResource(if (joined) R.string.nodes2_leave_named else R.string.nodes2_join_named, name)
    Box(
        Modifier
            .pressScale(press)
            .height(32.dp)
            .defaultMinSize(minWidth = 64.dp)
            .clip(CircleShape)
            .background(ground)
            .border(1.dp, edge, CircleShape)
            .semantics { contentDescription = label }
            .clickable(interactionSource = press, indication = ripple(), role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(if (joined) R.string.node_joined else R.string.node_join),
            style = Type.body(13, FontWeight.Bold),
            color = ink,
        )
    }
}

@Composable
private fun NoMatch(query: String, onCreate: () -> Unit) {
    val linkText = stringResource(R.string.nodes2_create_own)
    val template = stringResource(R.string.nodes2_not_found_hint, "\u0000")
    val hint = buildAnnotatedString {
        val at = template.indexOf('\u0000')
        append(template.substring(0, at.coerceAtLeast(0)))
        withLink(
            LinkAnnotation.Clickable(
                tag = "create",
                styles = TextLinkStyles(SpanStyle(color = Nocturne.accent)),
            ) { onCreate() },
        ) { append(linkText) }
        if (at >= 0) append(template.substring(at + 1))
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.nodes2_not_found, query),
            style = Type.body(16, FontWeight.Bold),
            color = Nocturne.text,
            textAlign = TextAlign.Center,
        )
        Text(
            hint,
            style = Type.body(13).copy(color = Nocturne.textSecondary, textAlign = TextAlign.Center),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun CardsSkeleton() {
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp).skeletonPulsing(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(4) { SkeletonBox(Modifier.fillMaxWidth().heightIn(min = 116.dp), corner = 16.dp) }
    }
}
