package com.nodeloc.app.feature.node

import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.nodeloc.app.core.design.HeaderNodeIdentity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.nodeloc.app.core.design.floatingHeaderInset
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.Inbox
import com.composables.icons.lucide.CircleSlash
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.WifiOff
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.EllipsisVertical
import com.nodeloc.app.feature.feed.rememberPostRowMenuHost
import com.nodeloc.app.feature.feed.Dialogs
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.R
import com.nodeloc.app.core.design.Avatar
import com.nodeloc.app.core.design.CapsuleIconButton
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FloatingHeaderBar
import com.nodeloc.app.core.design.HeaderGroup
import com.nodeloc.app.core.design.HairLine
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.OnReachedEnd
import com.nodeloc.app.core.design.PrimaryButton
import com.nodeloc.app.core.design.PullToRefreshBox
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.RemoteImage
import com.nodeloc.app.core.design.SecondaryButton
import com.nodeloc.app.core.design.SkeletonBox
import com.nodeloc.app.core.design.SkeletonLine
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.skeletonPulsing
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.isScrolledPastTop
import com.nodeloc.app.core.design.rememberPullToRefreshState
import com.nodeloc.app.core.design.requestScrollToTop
import com.nodeloc.app.core.design.rememberTapSafeOverscroll
import com.nodeloc.app.core.model.NodeNotificationLevel
import com.nodeloc.app.core.model.NodeReadingMode
import com.nodeloc.app.core.model.NodeSort
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.network.isOfflineError
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.feature.feed.FeedSkeleton
import com.nodeloc.app.feature.feed.PostRow
import com.nodeloc.app.feature.nav.AppViewModel
import com.nodeloc.app.feature.media.ImageViewerOverlay
import com.nodeloc.app.feature.media.PostImageViewer
import com.nodeloc.app.feature.media.imageViewerAt
import com.nodeloc.app.feature.nav.Navigator
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.shadow
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.LayoutList
import com.nodeloc.app.core.design.FilterPill
import com.nodeloc.app.core.html.breakingLongTokens
import com.nodeloc.app.core.model.Post

/**
 * A node page scrolls as one surface — banner, identity card, sort bar and the
 * topic list all in the same list, so the header genuinely scrolls away instead
 * of being a collapsing toolbar bolted on top.
 */
@Composable
fun NodeDetailScreen(
    categoryId: Int,
    slug: String?,
    app: AppViewModel,
    navigator: Navigator,
) {
    val viewModel: NodeDetailViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    // Hosted by the screen: a row scrolls away and takes its own
    // composition with it, which would close a dialog it had just opened.
    val menu = rememberPostRowMenuHost(navigator)
    // Dropped here rather than by the server: `TopicQuery` has no ignore
    // filter, so a blocked person's topics come back in every list exactly as
    // they were. Their replies inside a topic *are* filtered, by `TopicView`,
    // which is why this is the only place that needs it.
    val blocked by ServiceLocator.get.blockedUsers.usernames.collectAsState()
    val posts = remember(state.posts, blocked) {
        if (blocked.isEmpty()) state.posts
        else state.posts.filterNot { it.authorUsername in blocked }
    }
    // Lifted out of the list into the carousel above the tabs, as the website
    // does, so a pinned topic is not shown twice on the same screen.
    val pinned = remember(posts, state.pinnedIds) { posts.filter { it.id in state.pinnedIds } }
    val listed = remember(posts, pinned) {
        if (pinned.isEmpty()) posts else posts.filterNot { it.id in state.pinnedIds }
    }
    menu.Dialogs()
    val mode by viewModel.readingMode.collectAsState()
    val isSignedIn by app.isSignedIn.collectAsState()
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()
    val context = LocalContext.current
    // Tapping a picture in a row opens it full-screen rather than the topic;
    // the row itself still opens the topic everywhere else.
    var viewer by remember { mutableStateOf<PostImageViewer?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(categoryId) { viewModel.bind(categoryId) }
    listState.OnReachedEnd { viewModel.loadMore() }

    Box(Modifier.fillMaxSize().background(Nocturne.bg)) {
        // See FeedScreen: a keyed LazyColumn holds its place across a refresh,
        // so newly arrived topics land above the viewport unless the list is
        // sent back to the top.
        PullToRefreshBox(
            pullState,
            onRefresh = {
                viewModel.refresh()
                listState.requestScrollToTop()
            },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
                overscrollEffect = rememberTapSafeOverscroll(),
            ) {
                // The node's own details arrive with the same request as its
                // topics, so until they do this was a banner-coloured band, a
                // letter avatar and two empty lines — a header that looked
                // built rather than pending, while the list under it was
                // honestly skeletal.
                item {
                    if (state.node == null && state.showSkeleton) {
                        NodeHeaderSkeleton()
                    } else {
                        NodeHeaderBlock(state, viewModel, isSignedIn, onManage = { state.node?.slug?.let(navigator::openNodeAdmin) })
                    }
                }
                if (pinned.isNotEmpty() && !state.showAbout) {
                    item(key = "pins") {
                        PinnedCarousel(pinned) { post ->
                            viewModel.markRead(post.id)
                            navigator.openTopic(post.id)
                        }
                    }
                }
                item {
                    SortBar(
                        sort = state.sort,
                        showAbout = state.showAbout,
                        mode = mode,
                        onSelectSort = viewModel::selectSort,
                        onSelectAbout = viewModel::selectAbout,
                        onSelectMode = viewModel::selectReadingMode,
                    )
                }

                when {
                    state.showAbout -> item {
                        NodeAboutSection(
                            about = state.about,
                            fallbackDescription = state.node?.description,
                            loading = state.aboutLoading,
                            failed = state.aboutFailed,
                            onRetry = viewModel::loadAbout,
                        )
                    }

                    state.showSkeleton -> item { FeedSkeleton(mode) }

                    listed.isEmpty() && pinned.isEmpty() -> item {
                        val offline = state.error?.isOfflineError() == true
                        // The icon used to be WifiOff whatever the reason, so a
                        // node that simply had no topics yet looked broken.
                        EmptyStateView(
                            icon = when {
                                state.notFound -> Lucide.CircleSlash
                                offline -> Lucide.WifiOff
                                state.error != null -> Lucide.CircleAlert
                                else -> Lucide.Inbox
                            },
                            title = stringResource(
                                when {
                                    state.notFound -> R.string.node_not_found
                                    offline -> R.string.error_offline
                                    else -> R.string.node_empty_topics
                                },
                            ),
                            retryLabel = if (state.error != null) stringResource(R.string.common_retry) else null,
                            onRetry = { viewModel.load() },
                        )
                    }

                    else -> {
                        items(listed, key = { it.id }) { post ->
                            PostRow(
                                post = post,
                                more = menu.actionsFor(post),
                                mode = mode,
                                onOpen = {
                                    viewModel.markRead(post.id)
                                    navigator.openTopic(post.id)
                                },
                                onOpenAuthor = { navigator.openProfile(it) },
                                onShare = {
                                    navigator.share(context, "${DiscourseConfig.BASE_URL}/t/${post.id}", post.title)
                                },
                                onVote = { direction, face -> viewModel.vote(post.id, direction, face) },
                                onOpenImage = { index -> viewer = post.imageViewerAt(index) },
                            )
                        }
                        if (state.isLoadingMore) {
                            item {
                                Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                                    NodelocLoader(height = 24.dp)
                                }
                            }
                        }
                    }
                }
            }
        }

        NodeHeaderBar(
            title = state.node?.name.orEmpty(),
            handle = state.node?.handle.orEmpty(),
            logoUrl = state.node?.logoUrl,
            letter = state.node?.letter ?: "N",
            showIdentity = listState.isScrolledPastTop(threshold = 120),
            scrolledUnder = listState.isScrolledPastTop(threshold = 0),
            hasBanner = state.node?.bannerUrl != null,
            isSignedIn = isSignedIn,
            level = state.notificationLevel,
            onTapIdentity = { scope.launch { listState.animateScrollToItem(0) } },
            onBack = navigator::back,
            onCompose = { navigator.openCompose(categoryId = categoryId) },
            onLogin = { navigator.openAuth() },
            onSearch = { state.node?.slug?.let(navigator::openNodeSearch) },
            onShare = {
                state.node?.let {
                    navigator.share(context, "${DiscourseConfig.BASE_URL}/c/${it.slug}/${it.id}", it.name)
                }
            },
            onSelectLevel = viewModel::setNotificationLevel,
        )

        viewer?.let { open ->
            ImageViewerOverlay(open.images, open.index, open.source) { viewer = null }
        }
    }
}

/**
 * The design's identity card: a raised white card that overlaps the banner,
 * with the handle as the title and the name, members and topics under it.
 *
 * The design's "N 在线" is left out: the online count is only rendered into the
 * website's pages, and no `/api/v1` response carries it.
 */
@Composable
private fun NodeHeaderBlock(
    state: NodeDetailState,
    viewModel: NodeDetailViewModel,
    isSignedIn: Boolean,
    onManage: () -> Unit,
) {
    val node = state.node
    val moderation = ServiceLocator.get.moderation
    val privileges by moderation.privileges.collectAsState()
    val nodePending by moderation.nodePending.collectAsState()
    val canManage = node != null && privileges.moderates(node.slug)
    LaunchedEffect(canManage) { if (canManage) moderation.refreshNodeCounts() }
    val bandHeight = if (node?.bannerUrl != null) maxOf(140.dp, floatingHeaderInset) else floatingHeaderInset
    Box(Modifier.fillMaxWidth()) {
        if (node?.bannerUrl != null) {
            // Tall enough to be a picture, and at least as tall as the bar that
            // floats over it — a short banner puts the back button on the logo.
            RemoteImage(
                node.bannerUrl,
                modifier = Modifier.fillMaxWidth().height(bandHeight),
                contentScale = ContentScale.Crop,
            )
        } else {
            // No picture, so nothing to show off: the band is exactly the bar's
            // own height, and the card starts directly under it.
            Box(Modifier.fillMaxWidth().height(bandHeight).background(Nocturne.surface))
        }
        Column(
            Modifier
                // Overlaps a banner by a third of the card's top so the card
                // reads as sitting on the picture; with no banner there is
                // nothing to overlap, and it clears the bar instead.
                .padding(top = if (node?.bannerUrl != null) bandHeight - 36.dp else bandHeight + 8.dp)
                .padding(horizontal = Space.page)
                .fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(Nocturne.bg)
                .padding(horizontal = Space.page, vertical = Space.card),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (node?.logoUrl != null) {
                    RemoteAvatar(node.logoUrl, node.letter, size = 52.dp)
                } else {
                    Avatar(node?.letter ?: "N", variant = (node?.id ?: 0) % 2, size = 52.dp)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        node?.handle.orEmpty(),
                        style = Type.heading(20, FontWeight.Bold),
                        color = Nocturne.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            node?.name?.takeIf { it.isNotBlank() },
                            node?.memberCount?.let { pluralStringResource(R.plurals.common_members, it, DiscourseFormat.count(it)) },
                            node?.topicCount?.let { pluralStringResource(R.plurals.common_topics, it, DiscourseFormat.count(it)) },
                        ).joinToString(" · "),
                        style = Type.body(13),
                        color = Nocturne.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            node?.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = Type.body(14).copy(lineHeight = Type.lineHeight(14, 7)),
                    color = Nocturne.text,
                )
            }
            if (canManage || isSignedIn) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canManage) {
                        // The design's "管理 · N 待处理": a node moderator's way in from the node itself.
                        val pending = node?.slug?.let { nodePending[it] } ?: 0
                        Text(
                            if (pending > 0) stringResource(R.string.mod_entry_manage_node, pending) else stringResource(R.string.mod_entry_manage),
                            style = Type.body(14, FontWeight.SemiBold),
                            color = Nocturne.warning,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Nocturne.warningBg)
                                .clickable(onClick = onManage)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (isSignedIn) {
                        JoinButton(joined = state.isJoined, onClick = viewModel::toggleJoin)
                        var levelMenu by remember { mutableStateOf(false) }
                        Box {
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Nocturne.neutral300)
                                    .clickable { levelMenu = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Lucide.Bell,
                                    stringResource(R.string.node2_notifications),
                                    tint = Nocturne.text,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            NodeLevelMenu(
                                expanded = levelMenu,
                                level = state.notificationLevel,
                                onDismiss = { levelMenu = false },
                                onSelect = { levelMenu = false; viewModel.setNotificationLevel(it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Outlined once joined — a state, not an offer; filled accent while it is still an offer. */
@Composable
private fun JoinButton(joined: Boolean, onClick: () -> Unit) {
    val shape = CircleShape
    Box(
        Modifier
            .height(38.dp)
            .clip(shape)
            .then(
                if (joined) Modifier.border(1.dp, Nocturne.textTertiary, shape)
                else Modifier.background(Nocturne.accent),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(if (joined) R.string.node_joined else R.string.node_join),
            style = Type.body(14, FontWeight.SemiBold),
            color = if (joined) Nocturne.text else Nocturne.bg,
        )
    }
}

/** [NodeHeaderBlock]'s shape: the band, then the card's logo, name and counts. */
@Composable
private fun NodeHeaderSkeleton() {
    Column(Modifier.fillMaxWidth().skeletonPulsing()) {
        Box(Modifier.fillMaxWidth().height(maxOf(140.dp, floatingHeaderInset)).background(Nocturne.surface))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.page * 2, vertical = Space.card),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkeletonBox(Modifier.size(52.dp), corner = 26.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonLine(widthFraction = 0.52f, height = 20.dp)
                SkeletonLine(widthFraction = 0.34f, height = 12.dp)
            }
        }
    }
}

/**
 * The node's pinned topics, one card at a time. A pager rather than a stack:
 * a node with five pins would otherwise push its own topic list off the first
 * screen, which is the whole reason the website moved pins into a carousel.
 */
@Composable
private fun PinnedCarousel(pinned: List<Post>, onOpen: (Post) -> Unit) {
    val pagerState = rememberPagerState { pinned.size }
    Column(
        Modifier
            .padding(start = Space.page, end = Space.page, top = Space.card)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Nocturne.accent100),
    ) {
        HorizontalPager(state = pagerState, verticalAlignment = Alignment.Top) { page ->
            val post = pinned[page]
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(post) }
                    .padding(start = Space.card, end = Space.card, top = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.feed_pinned),
                            style = Type.body(12, FontWeight.Bold),
                            color = Nocturne.accent700,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Nocturne.bg)
                                .padding(horizontal = 9.dp, vertical = 3.dp),
                        )
                        RemoteAvatar(post.avatarUrl, post.avatarLetter, variant = post.variant, size = 18.dp)
                        Text(
                            listOfNotNull(post.authorUsername, post.time).joinToString(" · "),
                            style = Type.body(12),
                            color = Nocturne.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        post.title.breakingLongTokens(),
                        style = Type.body(16, FontWeight.SemiBold).copy(lineHeight = Type.lineHeight(16, 6)),
                        color = Nocturne.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (post.imageUrl != null) {
                    RemoteImage(
                        post.media.firstOrNull()?.bestUrl(240) ?: post.imageUrl,
                        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
        val current = pinned.getOrNull(pagerState.currentPage)
        Row(
            Modifier.fillMaxWidth().padding(start = Space.card, end = Space.card, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (current != null) {
                Text(
                    stringResource(
                        R.string.node2_pin_stats,
                        DiscourseFormat.count(current.voteScore ?: current.baseVotes),
                        DiscourseFormat.count(current.comments),
                    ),
                    style = Type.body(12),
                    color = Nocturne.textSecondary,
                )
            }
            Spacer(Modifier.weight(1f))
            if (pinned.size > 1) {
                repeat(pinned.size) { index ->
                    val selected = index == pagerState.currentPage
                    Box(
                        Modifier
                            .height(6.dp)
                            .width(if (selected) 14.dp else 6.dp)
                            .clip(CircleShape)
                            .background(if (selected) Nocturne.accent else Nocturne.accent200),
                    )
                }
            }
        }
    }
}

/**
 * 最新 / 精华 / 关于, then the reading mode. The design also draws 热门, but
 * `GET /api/v1/topics?sort=hot` ignores `node=` — it would be the site's hot
 * list under this node's name — so it is left out until the server scopes it.
 * 新 and 热门榜 went for the same reason: the node list served them as plain
 * latest.
 */
@Composable
private fun SortBar(
    sort: NodeSort,
    showAbout: Boolean,
    mode: NodeReadingMode,
    onSelectSort: (NodeSort) -> Unit,
    onSelectAbout: () -> Unit,
    onSelectMode: (NodeReadingMode) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NODE_SORTS.forEach { option ->
            FilterPill(stringResource(option.labelRes), selected = !showAbout && option == sort) { onSelectSort(option) }
        }
        FilterPill(stringResource(R.string.node2_about), selected = showAbout, onClick = onSelectAbout)
        Spacer(Modifier.weight(1f))
        if (!showAbout) {
            var modeMenu by remember { mutableStateOf(false) }
            Box {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Nocturne.neutral300)
                        .clickable { modeMenu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Lucide.LayoutList,
                        stringResource(R.string.feed_reading_mode),
                        tint = Nocturne.text,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                    NodeReadingMode.entries.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(option.labelRes),
                                    color = if (option == mode) Nocturne.accent else Nocturne.text,
                                )
                            },
                            onClick = {
                                onSelectMode(option)
                                modeMenu = false
                            },
                        )
                    }
                }
            }
        }
    }
}

private val NODE_SORTS = listOf(NodeSort.Latest, NodeSort.Featured)

/** The 关于 tab: the node's description in full, then its rules, numbered as the website numbers them. */
@Composable
private fun NodeAboutSection(
    about: NodeAbout?,
    fallbackDescription: String?,
    loading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
) {
    val description = about?.description ?: fallbackDescription?.takeIf { it.isNotBlank() }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.card),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        description?.let {
            Text(it, style = Type.body(15).copy(lineHeight = Type.lineHeight(15, 7)), color = Nocturne.text)
        }
        when {
            loading -> Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                NodelocLoader(height = 24.dp)
            }

            failed -> EmptyStateView(
                icon = Lucide.CircleAlert,
                title = stringResource(R.string.node2_about_failed),
                retryLabel = stringResource(R.string.common_retry),
                onRetry = onRetry,
            )

            about != null -> {
                val rules = about.rules.filter { it.title.isNotBlank() }
                if (rules.isNotEmpty()) {
                    Text(stringResource(R.string.node2_rules), style = Type.heading(16, FontWeight.SemiBold), color = Nocturne.text)
                    rules.forEachIndexed { index, rule ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${index + 1}.", style = Type.body(14, FontWeight.SemiBold), color = Nocturne.textSecondary)
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(rule.title, style = Type.body(14, FontWeight.SemiBold), color = Nocturne.text)
                                rule.description?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = Type.body(14), color = Nocturne.textSecondary)
                                }
                            }
                        }
                    }
                } else if (description == null) {
                    Text(stringResource(R.string.node2_about_empty), style = Type.body(14), color = Nocturne.textSecondary)
                }
            }
        }
    }
}

/** The notification levels, shared by the card's bell and the top bar's overflow. */
@Composable
private fun NodeLevelMenu(
    expanded: Boolean,
    level: NodeNotificationLevel,
    onDismiss: () -> Unit,
    onSelect: (NodeNotificationLevel) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        NodeNotificationLevel.menuOrder.forEach { option ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            stringResource(option.labelRes),
                            style = Type.body(14, FontWeight.Medium),
                            color = if (option == level) Nocturne.accent else Nocturne.text,
                        )
                        Text(
                            stringResource(option.detailRes),
                            style = Type.body(11),
                            color = Nocturne.textTertiary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                },
                onClick = { onSelect(option) },
            )
        }
    }
}

@Composable
private fun NodeHeaderBar(
    title: String,
    handle: String,
    logoUrl: String?,
    letter: String,
    showIdentity: Boolean,
    /** Anything at all under the bar; fills it in. Separate from the pill's cue. */
    scrolledUnder: Boolean,
    hasBanner: Boolean,
    isSignedIn: Boolean,
    onTapIdentity: () -> Unit,
    level: NodeNotificationLevel,
    onBack: () -> Unit,
    onCompose: () -> Unit,
    onLogin: () -> Unit,
    onSearch: () -> Unit,
    onShare: () -> Unit,
    onSelectLevel: (NodeNotificationLevel) -> Unit,
) {
    var moreMenu by remember { mutableStateOf(false) }
    var levelMenu by remember { mutableStateOf(false) }

    FloatingHeaderBar(
        scrolled = scrolledUnder,
        overMedia = hasBanner,
        leading = {
            // Beside the back button rather than centred: it names the page the
            // back button leaves, and the two belong together.
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeaderIconButton(Lucide.ArrowLeft, stringResource(R.string.common_back), onClick = onBack)
                AnimatedVisibility(visible = showIdentity, enter = fadeIn(), exit = fadeOut()) {
                    HeaderNodeIdentity(
                        handle = handle,
                        name = title,
                        logoUrl = logoUrl,
                        letter = letter,
                        onClick = onTapIdentity,
                    )
                }
            }
        },
        trailing = {
            HeaderGroup {
                if (isSignedIn) {
                    CapsuleIconButton(Lucide.Plus, stringResource(R.string.feed_compose), onClick = onCompose)
                }
                CapsuleIconButton(Lucide.Search, stringResource(R.string.node_search_in), onClick = onSearch)
                Box {
                    CapsuleIconButton(Lucide.EllipsisVertical, stringResource(R.string.common_more), onClick = { moreMenu = true })
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_share)) },
                            onClick = { moreMenu = false; onShare() },
                        )
                        if (isSignedIn) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.node_notification_level, stringResource(level.labelRes))) },
                                onClick = { moreMenu = false; levelMenu = true },
                            )
                        }
                    }
                    NodeLevelMenu(
                        expanded = levelMenu,
                        level = level,
                        onDismiss = { levelMenu = false },
                        onSelect = { levelMenu = false; onSelectLevel(it) },
                    )
                }
            }
            if (!isSignedIn) {
                com.nodeloc.app.core.design.GuestLoginButton(onClick = onLogin)
            }
        },
    )
}
