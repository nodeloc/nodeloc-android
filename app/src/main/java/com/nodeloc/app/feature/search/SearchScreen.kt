package com.nodeloc.app.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ArrowBigUp
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.SearchX
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.WifiOff
import com.composables.icons.lucide.X
import com.nodeloc.app.R
import com.nodeloc.app.core.design.Avatar
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FilterPill
import com.nodeloc.app.core.design.FilterPillRow
import com.nodeloc.app.core.design.HairLine
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.SectionKicker
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.rememberTapSafeOverscroll
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.network.isOfflineError
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.feature.nav.AppViewModel
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
    val pick: (String) -> Unit = {
        viewModel.updateQuery(it)
        viewModel.submit()
        keyboard?.hide()
    }

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
            FilterPillRow {
                SearchScope.entries.forEach { scope ->
                    FilterPill(
                        text = stringResource(scope.labelRes),
                        selected = scope == state.scope,
                        onClick = { viewModel.selectScope(scope) },
                    )
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
                onPick = pick,
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

            else -> ResultsPane(state, history, navigator, contentPadding, onPickRecent = pick)
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
            .padding(start = Space.page, end = Space.page, top = 8.dp, bottom = 12.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Nocturne.neutral300)
            .padding(horizontal = Space.page),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Lucide.Search, null, tint = Nocturne.textSecondary, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = Type.body(16).copy(color = Nocturne.text),
                cursorBrush = SolidColor(Nocturne.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(stringResource(R.string.search_hint), style = Type.body(16), color = Nocturne.textSecondary)
                    }
                    inner()
                },
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Lucide.X,
                stringResource(R.string.common_clear),
                tint = Nocturne.textSecondary,
                modifier = Modifier.size(20.dp).clickable(onClick = onClear),
            )
        }
    }
}

/** A grey capsule: a recent or a suggested term. Recent ones carry their own ×. */
@Composable
private fun TermChip(term: String, onPick: () -> Unit, onRemove: (() -> Unit)? = null) {
    Row(
        Modifier
            .height(34.dp)
            .clip(CircleShape)
            .background(Nocturne.neutral400)
            .clickable(onClick = onPick)
            .padding(start = 14.dp, end = if (onRemove != null) 8.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(term, style = Type.body(14, FontWeight.Medium), color = Nocturne.text, maxLines = 1)
        if (onRemove != null) {
            Icon(
                Lucide.X,
                stringResource(R.string.common_delete),
                tint = Nocturne.textTertiary,
                modifier = Modifier.size(14.dp).clickable(onClick = onRemove),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentSearches(
    history: List<String>,
    onPick: (String) -> Unit,
    onRemove: ((String) -> Unit)?,
    onClearAll: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.search_recent),
            style = Type.body(13),
            color = Nocturne.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (onClearAll != null) {
            Text(
                stringResource(R.string.common_clear_all),
                style = Type.body(13),
                color = Nocturne.accent,
                modifier = Modifier.clickable(onClick = onClearAll),
            )
        }
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        history.forEach { term ->
            TermChip(term, onPick = { onPick(term) }, onRemove = onRemove?.let { remove -> { remove(term) } })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
            item { RecentSearches(history, onPick, onRemove, onClearAll) }
        }
        item {
            SectionKicker(stringResource(R.string.search_hot), Modifier.padding(horizontal = Space.page, vertical = Space.s3))
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = Space.page),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                hotTerms.forEach { term -> TermChip(term, onPick = { onPick(term) }) }
            }
        }
    }
}

@Composable
private fun ResultsPane(
    state: SearchState,
    history: List<String>,
    navigator: Navigator,
    contentPadding: PaddingValues,
    onPickRecent: (String) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        overscrollEffect = rememberTapSafeOverscroll(),
    ) {
        when (state.scope) {
            SearchScope.Topics, SearchScope.Replies -> items(state.hits, key = { "hit-${it.postId}" }) { hit ->
                HitRow(
                    hit = hit,
                    reply = state.scope == SearchScope.Replies,
                    onOpen = {
                        navigator.openTopic(hit.topicId, hit.postNumber.takeIf { !hit.isFirstPost })
                    },
                    onOpenNode = { hit.nodeId?.let { navigator.openNode(it, hit.nodeSlug) } },
                    onShare = {
                        val link = if (hit.isFirstPost) "${DiscourseConfig.BASE_URL}/t/${hit.topicId}"
                        else "${DiscourseConfig.BASE_URL}/t/${hit.topicId}/${hit.postNumber}"
                        navigator.share(context, link, unmark(hit.titleHtml))
                    },
                )
            }

            SearchScope.Nodes -> {
                // The node search caps at 25 server-side, so say so when the
                // term matched more than the list can be showing.
                state.nodeTotal?.takeIf { it > state.nodes.size }?.let { total ->
                    item {
                        SectionKicker(
                            stringResource(R.string.search_nodes_capped, state.nodes.size, total),
                            Modifier.padding(horizontal = Space.page, vertical = Space.s3),
                        )
                    }
                }
                items(state.nodes, key = { "node-${it.id}" }) { node ->
                    ListRow(onClick = { navigator.openNode(node.id, node.slug) }) {
                        if (node.logoUrl != null) {
                            RemoteAvatar(node.logoUrl, node.letter, size = 40.dp)
                        } else {
                            Avatar(node.letter, variant = node.id % 2, size = 40.dp)
                        }
                        TwoLines(node.name, node.description ?: "n/${node.slug}")
                    }
                }
            }

            SearchScope.Users -> items(state.users, key = { "user-${it.id}" }) { user ->
                ListRow(onClick = { navigator.openProfile(user.username) }) {
                    RemoteAvatar(
                        DiscourseConfig.avatarUrl(user.avatarTemplate, 80),
                        user.username.take(1).uppercase(),
                        size = 40.dp,
                    )
                    TwoLines(user.name?.takeIf { it.isNotBlank() } ?: user.username, "@${user.username}")
                }
            }

            SearchScope.Apps -> items(state.apps, key = { "app-${it.id}" }) { appItem ->
                ListRow(onClick = { navigator.openApp(appItem.slug) }) {
                    RemoteAvatar(appItem.logoUrl, appItem.name.take(1), size = 40.dp, cornerRadius = 8.dp)
                    TwoLines(appItem.name, appItem.description)
                }
            }
        }

        // The design keeps the recent terms under the results too: a search
        // that found the wrong thing is usually followed by an earlier one.
        val recent = history.filterNot { it.equals(state.query.trim(), ignoreCase = true) }
        if (recent.isNotEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(Space.s3).background(Nocturne.surface))
                RecentSearches(recent, onPickRecent, onRemove = null, onClearAll = null)
            }
        }
    }
}

/** A Material two-line list row: 72 high with a 40dp leading avatar. */
@Composable
private fun ListRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.page, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.page),
        content = content,
    )
}

@Composable
private fun RowScope.TwoLines(title: String, detail: String?) {
    Column(Modifier.weight(1f)) {
        Text(title, style = Type.body(16), color = Nocturne.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        detail?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = Type.body(14), color = Nocturne.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HitRow(
    hit: SearchHit,
    reply: Boolean,
    onOpen: () -> Unit,
    onOpenNode: () -> Unit,
    onShare: () -> Unit,
) {
    val highlight = Nocturne.highlight
    val title = remember(hit.titleHtml, highlight) { markedText(hit.titleHtml, highlight) }
    val blurb = remember(hit.blurbHtml, highlight) { markedText(hit.blurbHtml, highlight) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = Space.page, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteAvatar(
                hit.nodeLogoUrl,
                hit.nodeName.take(1).ifEmpty { "N" },
                variant = hit.topicId % 2,
                size = 28.dp,
                modifier = Modifier.clickable(enabled = hit.nodeId != null, onClick = onOpenNode),
            )
            Text(
                "n/${hit.nodeSlug}",
                style = Type.body(14, FontWeight.SemiBold),
                color = Nocturne.text,
                maxLines = 1,
                modifier = Modifier.clickable(enabled = hit.nodeId != null, onClick = onOpenNode),
            )
            Text("· ${hit.time}", style = Type.body(14), color = Nocturne.textSecondary, maxLines = 1)
        }
        // On the Replies tab the title names the topic the reply sits in; the
        // matched words are the reply's, underneath.
        if (reply) {
            Text(
                stringResource(R.string.search2_reply_in, title.text),
                style = Type.body(14),
                color = Nocturne.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(title, style = Type.heading(18, FontWeight.SemiBold), color = Nocturne.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (blurb.isNotEmpty()) {
            // A topic found through one of its replies says so, or the excerpt
            // reads as though it were the opening post.
            val template = stringResource(R.string.search2_in_replies, "\u0000")
            val shown = if (!reply && !hit.isFirstPost) {
                buildAnnotatedString {
                    val at = template.indexOf('\u0000')
                    append(template.substring(0, at.coerceAtLeast(0)))
                    append(blurb)
                    if (at >= 0) append(template.substring(at + 1))
                }
            } else {
                blurb
            }
            Text(
                shown,
                style = Type.body(if (reply) 16 else 15),
                color = if (reply) Nocturne.text else Nocturne.textSecondary,
                maxLines = if (reply) 4 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CountPill(Lucide.ArrowBigUp, DiscourseFormat.count(hit.upvotes), onOpen)
            CountPill(Lucide.MessageCircle, DiscourseFormat.count(hit.replyCount), onOpen)
            CountPill(Lucide.Share2, stringResource(R.string.common_share), onShare)
        }
    }
    HairLine()
}

@Composable
private fun CountPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(Nocturne.neutral300)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = Nocturne.text, modifier = Modifier.size(18.dp))
        Text(label, style = Type.body(14, FontWeight.SemiBold), color = Nocturne.text)
    }
}

private val MarkPattern = Regex("<mark>(.*?)</mark>", RegexOption.DOT_MATCHES_ALL)

/**
 * The server's highlight: text already HTML-escaped, matched words wrapped in
 * `<mark>`. Nothing else is markup, so this is a split, not an HTML parse.
 */
private fun markedText(html: String, highlight: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (match in MarkPattern.findAll(html)) {
        append(unescape(html.substring(last, match.range.first)))
        withStyle(SpanStyle(background = highlight)) { append(unescape(match.groupValues[1])) }
        last = match.range.last + 1
    }
    append(unescape(html.substring(last)))
}

private fun unmark(html: String): String = unescape(html.replace("<mark>", "").replace("</mark>", ""))

private fun unescape(text: String): String = text
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#34;", "\"")
    .replace("&#39;", "'")
    .replace("&amp;", "&")
