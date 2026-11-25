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
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ripple
import androidx.compose.ui.graphics.Brush
import com.composables.icons.lucide.BellOff
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.ThumbsUp
import com.nodeloc.app.core.design.LocalReduceMotion
import com.nodeloc.app.core.design.pressScale
import com.nodeloc.app.core.design.rememberPressSource
import com.nodeloc.app.feature.feed.TopicListFooter

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
    val privileges by ServiceLocator.get.moderation.privileges.collectAsState()
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
                if (isSignedIn && state.isBlocked) {
                    item(key = "blocked") {
                        BlockedNote(
                            handle = state.node?.handle.orEmpty(),
                            busy = state.unblocking,
                            onUnblock = viewModel::unblock,
                        )
                    }
                }
                item {
                    SortBar(
                        sort = state.sort,
                        panel = state.panel,
                        mode = mode,
                        onSelectSort = viewModel::selectSort,
                        onSelectPanel = viewModel::selectPanel,
                        onSelectMode = viewModel::selectReadingMode,
                    )
                }
                // Under the tabs, as the v3 board has it: the pins belong to the
                // list the tabs choose, not to the node's identity above them.
                if (pinned.isNotEmpty() && state.panel == null) {
                    item(key = "pins") {
                        PinnedCarousel(pinned) { post ->
                            viewModel.markRead(post.id)
                            navigator.openTopic(post.id)
                        }
                    }
                }

                when {
                    state.panel == NodePanel.Rules -> item {
                        NodeRulesSection(
                            about = state.about,
                            loading = state.aboutLoading,
                            failed = state.aboutFailed,
                            canManage = state.node?.slug?.let { privileges.moderates(it) } == true,
                            onRetry = viewModel::loadAbout,
                            onManage = { state.node?.slug?.let(navigator::openNodeAdmin) },
                        )
                    }

                    state.panel == NodePanel.About -> item {
                        NodeAboutSection(
                            node = state.node,
                            about = state.about,
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
                                attributeToAuthor = true,
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
                        item(key = "footer") {
                            TopicListFooter(
                                count = listed.size + pinned.size,
                                hasMore = state.hasMore,
                                isLoadingMore = state.isLoadingMore,
                                failed = state.loadMoreFailed,
                                onLoadMore = { viewModel.loadMore(retry = true) },
                                onBackToTop = { listState.requestScrollToTop() },
                                modifier = Modifier.padding(top = 8.dp),
                            )
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
            // The v3 banner sits under the bar rather than behind it.
            hasBanner = false,
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
 * The v3 header: a banner under the bar, the logo overlapping its foot, the
 * bell, membership and 管理 beside the logo, then the handle, the counts and
 * the description.
 *
 * The board's "N 在线" is left out: the online count is only rendered into the
 * website's pages, and no `/api/v1` response carries it. Topics stand in.
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
    Column(Modifier.fillMaxWidth().padding(top = floatingHeaderInset)) {
        Box(Modifier.fillMaxWidth()) {
            NodeBanner(node?.bannerUrl)
            Row(
                Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, top = BANNER_HEIGHT - LOGO_OVERLAP),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // A ring of page colour, so the logo reads as lifted off the
                // banner rather than cut out of it.
                Box(
                    Modifier.size(LOGO_SIZE).clip(CircleShape).background(Nocturne.bg).padding(3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (node?.logoUrl != null) {
                        RemoteAvatar(node.logoUrl, node.letter, size = LOGO_SIZE - 6.dp)
                    } else {
                        Avatar(node?.letter ?: "N", variant = (node?.id ?: 0) % 2, size = LOGO_SIZE - 6.dp)
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(
                    // Level with the banner's foot plus the board's 8.
                    Modifier.padding(top = LOGO_OVERLAP + 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isSignedIn) {
                        var levelMenu by remember { mutableStateOf(false) }
                        Box {
                            HeaderAction(
                                outlined = true,
                                onClick = { levelMenu = true },
                                modifier = Modifier.size(HEADER_ACTION),
                            ) {
                                Icon(
                                    if (state.isBlocked) Lucide.BellOff else Lucide.Bell,
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
                        JoinButton(joined = state.isJoined, onClick = viewModel::toggleJoin)
                    }
                    if (canManage) {
                        // The design's 管理; the pending count rides along when
                        // there is one, as the node moderator's way into the queue.
                        val pending = node?.slug?.let { nodePending[it] } ?: 0
                        HeaderAction(outlined = false, onClick = onManage, modifier = Modifier.height(HEADER_ACTION)) {
                            Row(
                                Modifier.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Lucide.ShieldCheck, null, tint = Nocturne.bg, modifier = Modifier.size(16.dp))
                                Text(
                                    if (pending > 0) stringResource(R.string.mod_entry_manage_node, pending) else stringResource(R.string.mod_entry_manage),
                                    style = Type.body(14, FontWeight.Bold),
                                    color = Nocturne.bg,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, top = 8.dp, bottom = 12.dp)) {
            Text(
                node?.handle.orEmpty(),
                style = Type.heading(22, FontWeight.ExtraBold),
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
                style = Type.body(12),
                color = Nocturne.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            node?.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = Type.body(13).copy(lineHeight = Type.lineHeight(13, 7)),
                    color = Nocturne.muted(0.86f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        HairLine()
    }
}

private val BANNER_HEIGHT = 84.dp
private val LOGO_SIZE = 64.dp
private val LOGO_OVERLAP = 28.dp
private val HEADER_ACTION = 44.dp

/** The node's banner, or the board's green wash where it has none. */
@Composable
private fun NodeBanner(url: String?) {
    val modifier = Modifier.fillMaxWidth().height(BANNER_HEIGHT)
    if (url != null) {
        RemoteImage(url, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(Brush.linearGradient(listOf(Nocturne.accent800, Nocturne.accent))))
    }
}

/** The header's 44-high round actions: outlined for a state, filled green for 管理. */
@Composable
private fun HeaderAction(
    outlined: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val press = rememberPressSource()
    Box(
        modifier
            .pressScale(press)
            .clip(CircleShape)
            .then(
                if (outlined) Modifier.border(1.dp, Nocturne.text, CircleShape).background(Nocturne.bg)
                else Modifier.background(Nocturne.accent),
            )
            .clickable(interactionSource = press, indication = ripple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Outlined once joined — a state, not an offer; filled accent while it is still an offer. */
@Composable
private fun JoinButton(joined: Boolean, onClick: () -> Unit) {
    val press = rememberPressSource()
    val shape = CircleShape
    Box(
        Modifier
            .pressScale(press)
            .height(HEADER_ACTION)
            .widthIn(min = 84.dp)
            .clip(shape)
            .then(
                if (joined) Modifier.border(1.dp, Nocturne.text, shape).background(Nocturne.bg)
                else Modifier.background(Nocturne.accent),
            )
            .clickable(interactionSource = press, indication = ripple(), onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(if (joined) R.string.node_joined else R.string.node_join),
            style = Type.body(14, FontWeight.Bold),
            color = if (joined) Nocturne.text else Nocturne.bg,
        )
    }
}

/** [NodeHeaderBlock]'s shape: the band, the logo over its foot, the handle and counts. */
@Composable
private fun NodeHeaderSkeleton() {
    Column(Modifier.fillMaxWidth().padding(top = floatingHeaderInset).skeletonPulsing()) {
        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(BANNER_HEIGHT).background(Nocturne.surface))
            Box(
                Modifier
                    .padding(start = Space.page, top = BANNER_HEIGHT - LOGO_OVERLAP)
                    .size(LOGO_SIZE)
                    .clip(CircleShape)
                    .background(Nocturne.bg)
                    .padding(3.dp),
            ) {
                SkeletonBox(Modifier.fillMaxSize(), corner = LOGO_SIZE / 2)
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SkeletonLine(widthFraction = 0.4f, height = 20.dp)
            SkeletonLine(widthFraction = 0.3f, height = 12.dp)
            SkeletonLine(widthFraction = 0.86f, height = 12.dp)
        }
    }
}

/**
 * The board's note for a node the reader has blocked, with the way back. The
 * node still opens and reads normally; the note only explains why its topics
 * are missing everywhere else.
 */
@Composable
private fun BlockedNote(handle: String, busy: Boolean, onUnblock: () -> Unit) {
    Column(
        Modifier
            .padding(start = Space.page, end = Space.page, top = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Nocturne.dangerBg)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Lucide.EyeOff, null, tint = Nocturne.danger, modifier = Modifier.padding(top = 2.dp).size(18.dp))
            Text(
                stringResource(R.string.node3_blocked, handle),
                style = Type.body(14).copy(lineHeight = Type.lineHeight(14, 7)),
                color = Nocturne.danger,
            )
        }
        val press = rememberPressSource()
        Box(
            Modifier
                .align(Alignment.End)
                .pressScale(press)
                .height(36.dp)
                .clip(CircleShape)
                .border(1.dp, Nocturne.danger, CircleShape)
                .background(Nocturne.bg)
                .clickable(interactionSource = press, indication = ripple(), enabled = !busy, onClick = onUnblock)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.node3_unblock),
                style = Type.body(13, FontWeight.Bold),
                color = Nocturne.danger.copy(alpha = if (busy) 0.5f else 1f),
            )
        }
    }
}

/**
 * The node's pinned topics, one card at a time. A pager rather than a stack:
 * a node with five pins would otherwise push its own topic list off the first
 * screen, which is the whole reason the website moved pins into a carousel.
 *
 * Each card wears the board's 置顶 label, or 精华 when the topic is featured.
 * The board's third kind, 活动, has no flag on the server (see
 * docs/backend-api-requests.md #42).
 */
@Composable
private fun PinnedCarousel(pinned: List<Post>, onOpen: (Post) -> Unit) {
    val pagerState = rememberPagerState { pinned.size }
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)
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
                    .padding(start = Space.card, end = Space.card, top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(Space.card),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            Modifier
                                .clip(CircleShape)
                                .background(Nocturne.bg.copy(alpha = 0.75f))
                                .padding(horizontal = 7.dp, vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(Lucide.Pin, null, tint = Nocturne.accent, modifier = Modifier.size(12.dp))
                            Text(
                                stringResource(if (post.featured) R.string.node3_kind_featured else R.string.feed_pinned),
                                style = Type.body(12, FontWeight.Bold),
                                color = Nocturne.accent,
                            )
                        }
                        RemoteAvatar(post.avatarUrl, post.avatarLetter, variant = post.variant, size = 18.dp)
                        Text(
                            post.authorHandle.orEmpty(),
                            style = Type.body(12, FontWeight.Bold),
                            color = Nocturne.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text("· ${post.time}", style = Type.body(12), color = Nocturne.textSecondary, maxLines = 1)
                    }
                    Text(
                        post.title.breakingLongTokens(),
                        style = Type.body(15, FontWeight.Bold).copy(lineHeight = Type.lineHeight(15, 6)),
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
            Modifier.fillMaxWidth().padding(start = Space.card, end = 8.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (current != null) {
                PinStat(Lucide.ThumbsUp, stringResource(R.string.node3_pin_votes, DiscourseFormat.count(current.voteScore ?: current.baseVotes)))
                PinStat(Lucide.MessageCircle, stringResource(R.string.node3_pin_comments, DiscourseFormat.count(current.comments)))
            }
            Spacer(Modifier.weight(1f))
            if (pinned.size > 1) {
                Row {
                    repeat(pinned.size) { index ->
                        val selected = index == pagerState.currentPage
                        // A 22×28 target around a 6-high dot: tappable without
                        // the dots themselves growing.
                        Box(
                            Modifier
                                .size(width = 22.dp, height = 28.dp)
                                .clickable { scope.launch { pagerState.animateScrollToPage(index) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .height(6.dp)
                                    .width(if (selected) 16.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(Nocturne.text.copy(alpha = if (selected) 0.85f else 0.25f)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinStat(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, tint = Nocturne.textSecondary, modifier = Modifier.size(14.dp))
        Text(text, style = Type.body(12), color = Nocturne.textSecondary)
    }
}

/**
 * 最新 / 精华 / 规则 / 关于, then the reading mode. The design also draws 热门,
 * but `GET /api/v1/topics?sort=hot` ignores `node=` — it would be the site's
 * hot list under this node's name — so it is left out until the server scopes
 * it. 新 and 热门榜 went for the same reason: the node list served them as
 * plain latest.
 */
@Composable
private fun SortBar(
    sort: NodeSort,
    panel: NodePanel?,
    mode: NodeReadingMode,
    onSelectSort: (NodeSort) -> Unit,
    onSelectPanel: (NodePanel) -> Unit,
    onSelectMode: (NodeReadingMode) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NODE_SORTS.forEach { option ->
                FilterPill(stringResource(option.labelRes), selected = panel == null && option == sort) { onSelectSort(option) }
            }
            FilterPill(stringResource(R.string.node2_rules), selected = panel == NodePanel.Rules) { onSelectPanel(NodePanel.Rules) }
            FilterPill(stringResource(R.string.node2_about), selected = panel == NodePanel.About) { onSelectPanel(NodePanel.About) }
        }
        if (panel == null) {
            var modeMenu by remember { mutableStateOf(false) }
            Box(Modifier.padding(start = 8.dp)) {
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

/** The loading and failure states the 规则 and 关于 tabs share: both wait on the one node detail. */
@Composable
private fun PanelState(loading: Boolean, failed: Boolean, onRetry: () -> Unit): Boolean {
    when {
        loading -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).skeletonPulsing()) {
            repeat(4) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SkeletonBox(Modifier.size(24.dp), corner = 12.dp)
                    SkeletonLine(widthFraction = 0.7f, height = 14.dp)
                }
            }
        }

        failed -> EmptyStateView(
            icon = Lucide.CircleAlert,
            title = stringResource(R.string.node2_about_failed),
            retryLabel = stringResource(R.string.common_retry),
            onRetry = onRetry,
        )

        else -> return false
    }
    return true
}

/**
 * The 规则 tab: numbered rules that open to their explanation, as the board
 * draws them, and for the node's managers the way to edit them.
 */
@Composable
private fun NodeRulesSection(
    about: NodeAbout?,
    loading: Boolean,
    failed: Boolean,
    canManage: Boolean,
    onRetry: () -> Unit,
    onManage: () -> Unit,
) {
    if (PanelState(loading, failed, onRetry) || about == null) return
    val rules = about.listedRules
    Column(Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, top = 8.dp, bottom = 24.dp)) {
        if (rules.isEmpty()) {
            Text(
                stringResource(R.string.node3_rules_empty),
                style = Type.body(14),
                color = Nocturne.textSecondary,
                modifier = Modifier.padding(vertical = 14.dp),
            )
        }
        rules.forEachIndexed { index, rule -> RuleRow(index + 1, rule) }
        if (canManage) {
            SecondaryButton(
                stringResource(R.string.node3_edit_rules),
                block = true,
                modifier = Modifier.padding(top = 16.dp),
                onClick = onManage,
            )
        }
    }
}

@Composable
private fun RuleRow(number: Int, rule: NodeRule) {
    val detail = rule.description?.takeIf { it.isNotBlank() }
    var open by remember(rule) { mutableStateOf(false) }
    val reduceMotion = LocalReduceMotion.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = detail != null) { open = !open }
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(Nocturne.neutral400),
                contentAlignment = Alignment.Center,
            ) {
                Text(number.toString(), style = Type.body(12, FontWeight.Bold), color = Nocturne.text)
            }
            Text(
                rule.title,
                style = Type.body(14, FontWeight.Bold),
                color = Nocturne.text,
                modifier = Modifier.weight(1f).padding(top = 1.dp),
            )
            if (detail != null) {
                Icon(
                    if (open) Lucide.ChevronUp else Lucide.ChevronDown,
                    null,
                    tint = Nocturne.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (detail != null) {
            AnimatedVisibility(
                visible = open,
                enter = if (reduceMotion) EnterTransition.None else expandVertically() + fadeIn(),
                exit = if (reduceMotion) ExitTransition.None else shrinkVertically() + fadeOut(),
            ) {
                Text(
                    detail,
                    style = Type.body(13).copy(lineHeight = Type.lineHeight(13, 8)),
                    color = Nocturne.muted(0.86f),
                    modifier = Modifier.padding(start = 36.dp, bottom = 14.dp),
                )
            }
        }
        HairLine()
    }
}

/**
 * The 关于 tab: the board's node-information card — handle, name, description,
 * when it was made and who can see it, and its counts.
 *
 * The board's 在线 and 今日新帖, the seven-day data, the sidebar widgets
 * (links, tags, related nodes, events) and the moderator list have no public
 * `/api/v1` source, so the card stops at what `GET /nodes/{slug}` serves.
 */
@Composable
private fun NodeAboutSection(
    node: com.nodeloc.app.core.model.NodeSummary?,
    about: NodeAbout?,
    loading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
) {
    if (PanelState(loading, failed, onRetry) || about == null) return
    val info = about.node
    Column(
        Modifier
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Nocturne.neutral200)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(node?.handle.orEmpty(), style = Type.body(15, FontWeight.Bold), color = Nocturne.text)
        (info?.name ?: node?.name)?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = Type.body(15), color = Nocturne.textSecondary)
        }
        (about.description ?: node?.description?.takeIf { it.isNotBlank() })?.let {
            Text(it, style = Type.body(14).copy(lineHeight = Type.lineHeight(14, 7)), color = Nocturne.muted(0.86f))
        }
        val created = info?.createdAt?.let(DiscourseFormat::instant)?.let { instant ->
            val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
            val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "yMMMM")
            java.time.format.DateTimeFormatter.ofPattern(pattern, locale)
                .format(instant.atZone(java.time.ZoneId.systemDefault()))
        }
        val visibility = when (info?.visibility) {
            "public" -> stringResource(R.string.node3_public)
            "members" -> stringResource(R.string.node3_members_only)
            else -> null
        }
        listOfNotNull(created?.let { stringResource(R.string.node3_created, it) }, visibility)
            .takeIf { it.isNotEmpty() }
            ?.let { Text(it.joinToString(" · "), style = Type.body(12), color = Nocturne.textSecondary) }
        val stats = listOfNotNull(
            (info?.memberCount ?: node?.memberCount)?.let { R.string.node3_stat_members to it },
            (info?.topicCount ?: node?.topicCount)?.let { R.string.node3_stat_topics to it },
            info?.postCount?.let { R.string.node3_stat_posts to it },
        )
        if (stats.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                stats.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(DiscourseFormat.count(value), style = Type.body(16, FontWeight.Bold), color = Nocturne.text)
                        Text(stringResource(label), style = Type.body(12), color = Nocturne.textSecondary)
                    }
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
