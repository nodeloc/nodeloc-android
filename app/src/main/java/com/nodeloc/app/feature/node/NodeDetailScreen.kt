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
                        NodeHeaderBlock(state, viewModel, isSignedIn)
                    }
                }
                item {
                    SortBar(
                        sort = state.sort,
                        mode = mode,
                        isSignedIn = isSignedIn,
                        onSelectSort = viewModel::selectSort,
                        onSelectMode = viewModel::selectReadingMode,
                    )
                }

                when {
                    state.showSkeleton -> item { FeedSkeleton(mode) }

                    posts.isEmpty() -> item {
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
                        items(posts, key = { it.id }) { post ->
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

@Composable
private fun NodeHeaderBlock(state: NodeDetailState, viewModel: NodeDetailViewModel, isSignedIn: Boolean) {
    val node = state.node
    Column {
        if (node?.bannerUrl != null) {
            // Tall enough to be a picture, and at least as tall as the bar that
            // floats over it — a short banner puts the back button on the logo.
            RemoteImage(
                node.bannerUrl,
                modifier = Modifier.fillMaxWidth().height(maxOf(140.dp, floatingHeaderInset)),
                contentScale = ContentScale.Crop,
            )
        } else {
            // No picture, so nothing to show off: the band is exactly the bar's
            // own height, and the node's name starts directly under it.
            Box(Modifier.fillMaxWidth().height(floatingHeaderInset).background(Nocturne.surface))
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.s4),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                if (node?.logoUrl != null) {
                    RemoteAvatar(node.logoUrl, node.letter, size = 52.dp, cornerRadius = 12.dp)
                } else {
                    Avatar(node?.letter ?: "N", size = 52.dp, cornerRadius = 12.dp)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        node?.name.orEmpty(),
                        style = Type.heading(20, FontWeight.SemiBold),
                        color = Nocturne.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            node?.memberCount?.let { pluralStringResource(R.plurals.common_members, it, DiscourseFormat.count(it)) },
                            node?.topicCount?.let { pluralStringResource(R.plurals.common_topics, it, DiscourseFormat.count(it)) },
                        ).joinToString(" · "),
                        style = Type.body(12),
                        color = Nocturne.muted(0.45f),
                    )
                }
                if (isSignedIn) {
                    if (state.isJoined) {
                        SecondaryButton(stringResource(R.string.node_joined), onClick = viewModel::toggleJoin)
                    } else {
                        PrimaryButton(stringResource(R.string.node_join), onClick = viewModel::toggleJoin)
                    }
                }
            }
            node?.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = Type.body(14), color = Nocturne.muted(0.62f))
            }
        }
        HairLine()
    }
}

/** [NodeHeaderBlock]'s shape: the band, the logo, the name and the counts. */
@Composable
private fun NodeHeaderSkeleton() {
    Column(Modifier.fillMaxWidth().skeletonPulsing()) {
        Box(Modifier.fillMaxWidth().height(maxOf(140.dp, floatingHeaderInset)).background(Nocturne.surface))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.s4),
            horizontalArrangement = Arrangement.spacedBy(Space.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkeletonBox(Modifier.size(52.dp), corner = 12.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonLine(widthFraction = 0.52f, height = 20.dp)
                SkeletonLine(widthFraction = 0.34f, height = 12.dp)
            }
        }
        HairLine()
    }
}

@Composable
private fun SortBar(
    sort: NodeSort,
    mode: NodeReadingMode,
    isSignedIn: Boolean,
    onSelectSort: (NodeSort) -> Unit,
    onSelectMode: (NodeReadingMode) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        NodeSort.entries
            // A node has no "best": the route is the root's alone.
            .filter { !it.feedOnly }
            // `new` is only meaningful — and only permitted — when signed in.
            .filter { isSignedIn || !it.requiresAuth }
            .forEach { option ->
                val selected = option == sort
                Text(
                    stringResource(option.labelRes),
                    style = Type.body(13, if (selected) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (selected) Nocturne.accent else Nocturne.muted(0.5f),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onSelectSort(option) }
                        .background(if (selected) Nocturne.selected else Nocturne.bg)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        Spacer(Modifier.weight(1f))
        var modeMenu by remember { mutableStateOf(false) }
        Box {
            Text(
                stringResource(mode.labelRes),
                style = Type.body(13),
                color = Nocturne.muted(0.5f),
                modifier = Modifier.clickable { modeMenu = true },
            )
            DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                NodeReadingMode.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(option.labelRes)) },
                        onClick = {
                            onSelectMode(option)
                            modeMenu = false
                        },
                    )
                }
            }
        }
    }
    HairLine()
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
                    DropdownMenu(expanded = levelMenu, onDismissRequest = { levelMenu = false }) {
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
                                            color = Nocturne.muted(0.45f),
                                            modifier = Modifier.padding(top = 2.dp),
                                        )
                                    }
                                },
                                onClick = { levelMenu = false; onSelectLevel(option) },
                            )
                        }
                    }
                }
            }
            if (!isSignedIn) {
                com.nodeloc.app.core.design.GuestLoginButton(onClick = onLogin)
            }
        },
    )
}
