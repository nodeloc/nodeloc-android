package com.nodeloc.app.feature.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.Inbox
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.WifiOff
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Menu
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.R
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FloatingHeaderBar
import com.nodeloc.app.core.design.FilterPill
import com.nodeloc.app.core.design.FilterPillRow
import com.nodeloc.app.core.model.NodeSort
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.nodeloc.app.core.design.GuestLoginButton
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.NodelocLogo
import com.nodeloc.app.core.design.OnReachedEnd
import com.nodeloc.app.core.design.PullToRefreshBox
import com.nodeloc.app.core.design.floatingHeaderInset
import com.nodeloc.app.core.design.isScrolledPastTop
import com.nodeloc.app.core.design.rememberPullToRefreshState
import com.nodeloc.app.core.design.requestScrollToTop
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.network.isOfflineError
import com.nodeloc.app.feature.nav.AppViewModel
import com.nodeloc.app.feature.media.ImageViewerOverlay
import com.nodeloc.app.feature.media.PostImageViewer
import com.nodeloc.app.feature.media.imageViewerAt
import com.nodeloc.app.feature.nav.Navigator
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type

@Composable
fun FeedScreen(
    app: AppViewModel,
    navigator: Navigator,
    contentPadding: PaddingValues,
    onOpenDrawer: () -> Unit,
) {
    val viewModel: FeedViewModel = viewModel()
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
    val headerInset = floatingHeaderInset
    val context = LocalContext.current
    // Tapping a picture in a row opens it full-screen rather than the topic;
    // the row itself still opens the topic everywhere else.
    var viewer by remember { mutableStateOf<PostImageViewer?>(null) }

    val sortChips: @Composable () -> Unit = {
        FeedSortChips(state.sort, isSignedIn, onSelect = { sort ->
            viewModel.selectSort(sort)
            listState.requestScrollToTop()
        })
    }

    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }
    listState.OnReachedEnd { viewModel.loadMore() }

    Box(Modifier.fillMaxSize().background(Nocturne.bg)) {
        // Back to the top afterwards; see scrollToTopAfterRefresh for why that
        // is not simply a scrollToItem. Pulling means "show me what arrived",
        // and what arrived is at the top.
        PullToRefreshBox(
            pullState,
            onRefresh = {
                viewModel.refresh()
                listState.requestScrollToTop()
            },
        ) {
            // One list for all three states. The skeleton and the empty state used
            // to be Columns of their own, padded differently from the list, so on
            // every chip tap the chips and rows dropped down and sprang back when
            // the new list arrived. Inside the list the chips never move.
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = headerInset,
                    bottom = contentPadding.calculateBottomPadding() + 24.dp,
                ),
            ) {
                item(key = "sorts") { sortChips() }
                if (state.showSkeleton) {
                    item(key = "skeleton") { FeedSkeleton(mode) }
                } else if (state.showEmptyState) {
                    // The chips stay above an empty list: "关注" with no nodes
                    // joined is empty, and the way out is another chip.
                    item(key = "empty") {
                        Box(Modifier.fillParentMaxHeight(0.75f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            val offline = state.error?.isOfflineError() == true
                            EmptyStateView(
                                icon = if (offline) Lucide.WifiOff else Lucide.Inbox,
                                title = stringResource(if (offline) R.string.error_offline else R.string.common_empty),
                                detail = if (offline) null else stringResource(R.string.common_empty_hint),
                                retryLabel = if (state.error != null) stringResource(R.string.common_retry) else null,
                                onRetry = { viewModel.load() },
                            )
                        }
                    }
                }
                itemsIndexed(posts, key = { _, post -> post.id }) { _, post ->
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
                if (posts.isNotEmpty()) item(key = "footer") {
                    TopicListFooter(
                        count = posts.size,
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

        viewer?.let { open ->
            ImageViewerOverlay(open.images, open.index, open.source) { viewer = null }
        }

        FeedHeader(
            scrolled = listState.isScrolledPastTop(threshold = 0),
            isSignedIn = isSignedIn,
            onOpenDrawer = onOpenDrawer,
            onCompose = { navigator.openCompose() },
            onLogin = { navigator.openAuth() },
        )
    }
}




/**
 * The design's order: 热门 / 最新 / 精华 / 关注, then 推荐 kept from the old
 * home. 上升中 is on the website but `GET /api/v1/topics` has no such sort yet.
 */
private val FEED_SORTS = listOf(NodeSort.Hot, NodeSort.Latest, NodeSort.Featured, NodeSort.Following, NodeSort.Best)

@Composable
private fun FeedSortChips(selected: NodeSort, isSignedIn: Boolean, onSelect: (NodeSort) -> Unit) {
    val label = stringResource(R.string.feed_sorts)
    FilterPillRow(Modifier.semantics { contentDescription = label }) {
        FEED_SORTS.filter { isSignedIn || !it.requiresAuth }.forEach { sort ->
            FilterPill(stringResource(sort.labelRes), selected = sort == selected) { onSelect(sort) }
        }
    }
}

@Composable
private fun FeedHeader(
    scrolled: Boolean,
    isSignedIn: Boolean,
    onOpenDrawer: () -> Unit,
    onCompose: () -> Unit,
    onLogin: () -> Unit,
) {
    FloatingHeaderBar(
        scrolled = scrolled,
        leading = { com.nodeloc.app.feature.nav.DrawerMenuButton(onClick = onOpenDrawer) },
        // Static branding that stays put: the bar fills in behind it on scroll,
        // so there is nothing left for it to get in the way of.
        center = { NodelocLogo(height = 26.dp) },
        // Reading mode lives in settings only: it is chosen once and then not
        // thought about, which is not what a permanent seat in the header is
        // for.
        trailing = {
            if (isSignedIn) {
                HeaderIconButton(Lucide.Plus, stringResource(R.string.feed_compose), onClick = onCompose)
            } else {
                GuestLoginButton(onClick = onLogin)
            }
        },
    )
}

