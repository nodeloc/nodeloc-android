package com.nodeloc.app.feature.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Lucide
import android.content.ClipData
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.BlockUserDialog
import com.nodeloc.app.core.design.ReportDialog
import com.nodeloc.app.core.design.ReportTarget
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.model.Post
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.feature.nav.Navigator
import kotlinx.coroutines.launch
import com.nodeloc.app.R
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Type

/**
 * What the menu on a row can do.
 *
 * Passed as one object rather than eight parameters: every row in a list gets
 * the same set, differing only in which topic and which author they close
 * over, and a signature with eight lambdas in it stops being readable at the
 * call site.
 *
 * Null where the action cannot apply — an author the row never learned the
 * username of has nothing to view or block.
 */
data class PostRowActions(
    val onShare: () -> Unit,
    val onCopyLink: () -> Unit,
    val onBookmark: () -> Unit,
    val onWatch: () -> Unit,
    val onRepost: () -> Unit,
    val onReport: () -> Unit,
    val onOpenAuthor: (() -> Unit)? = null,
    val onBlockAuthor: (() -> Unit)? = null,
)

/**
 * The three dots on a row, and everything behind them.
 *
 * Everything here is reachable inside the topic as well; this is the version
 * for somebody scrolling a list who has already decided, and for whom opening
 * the topic to block its author is a detour through the thing they are trying
 * not to read.
 */
@Composable
fun PostRowMenu(actions: PostRowActions, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Icon(
            Lucide.EllipsisVertical,
            stringResource(R.string.common_more),
            tint = Nocturne.muted(0.45f),
            modifier = Modifier
                .clip(CircleShape)
                .clickable { open = true }
                .padding(4.dp)
                .size(16.dp),
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = Nocturne.surface,
        ) {
            val close = { open = false }
            MenuItem(stringResource(R.string.common_share), close, actions.onShare)
            MenuItem(stringResource(R.string.reader_copy_link), close, actions.onCopyLink)
            MenuItem(stringResource(R.string.post_more_bookmark), close, actions.onBookmark)
            MenuItem(stringResource(R.string.post_more_watch), close, actions.onWatch)
            MenuItem(stringResource(R.string.post_more_repost), close, actions.onRepost)
            actions.onOpenAuthor?.let {
                MenuItem(stringResource(R.string.post_more_author), close, it)
            }
            MenuItem(stringResource(R.string.reader_report), close, actions.onReport, danger = true)
            actions.onBlockAuthor?.let {
                MenuItem(stringResource(R.string.post_more_block_author), close, it, danger = true)
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, close: () -> Unit, action: () -> Unit, danger: Boolean = false) {
    DropdownMenuItem(
        text = {
            Text(label, style = Type.body(14), color = if (danger) Nocturne.danger else Nocturne.text)
        },
        onClick = { close(); action() },
    )
}

/**
 * The row menu's actions, and the two dialogs it can open.
 *
 * Built once per screen rather than per row: a dialog belongs to the screen —
 * a row scrolls out of the list and takes its own composition with it, which
 * would close the dialog it had just opened.
 */
class PostRowMenuHost internal constructor(
    private val navigator: Navigator,
    private val context: android.content.Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val clipboard: androidx.compose.ui.platform.Clipboard,
    internal val reporting: androidx.compose.runtime.MutableState<Post?>,
    internal val blocking: androidx.compose.runtime.MutableState<String?>,
) {
    fun actionsFor(post: Post): PostRowActions {
        val url = "${DiscourseConfig.BASE_URL}/t/${post.id}"
        return PostRowActions(
            onShare = { navigator.share(context, url, post.title) },
            onCopyLink = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(post.title, url)))
                    ToastCenter.show(R.string.reader_link_copied)
                }
            },
            // The topic, not its first post: a row knows the one and not the
            // other, and Discourse serves an endpoint for exactly that.
            onBookmark = {
                scope.launch {
                    runCatchingCancellable { ServiceLocator.get.client.bookmarkTopic(post.id) }
                        .onSuccess { ToastCenter.show(R.string.reader_bookmarked) }
                        .onFailure { ToastCenter.showError(it) }
                }
            },
            onWatch = {
                scope.launch {
                    runCatchingCancellable {
                        ServiceLocator.get.client.setTopicNotificationLevel(post.id, WATCHING)
                    }
                        .onSuccess { ToastCenter.show(R.string.post_more_watching) }
                        .onFailure { ToastCenter.showError(it) }
                }
            },
            onRepost = { navigator.openCompose(repostTopicId = post.id, prefillTitle = post.title) },
            onReport = { reporting.value = post },
            onOpenAuthor = post.authorUsername?.let { name -> { navigator.openProfile(name) } },
            onBlockAuthor = post.authorUsername?.let { name -> { blocking.value = name } },
        )
    }

    private companion object {
        /** Discourse's own scale: 0 muted, 1 regular, 2 tracking, 3 watching. */
        const val WATCHING = 3
    }
}

@Composable
fun rememberPostRowMenuHost(navigator: Navigator): PostRowMenuHost {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val reporting = remember { mutableStateOf<Post?>(null) }
    val blocking = remember { mutableStateOf<String?>(null) }
    return remember(navigator) {
        PostRowMenuHost(navigator, context, scope, clipboard, reporting, blocking)
    }
}

/**
 * The dialogs, hosted by the screen. Call once, anywhere in it.
 *
 * Reporting needs a post id and a row only has a topic, so the first post is
 * fetched when the dialog is asked for rather than kept on every row against
 * the chance that one of them is reported.
 */
@Composable
fun PostRowMenuHost.Dialogs() {
    reporting.value?.let { post ->
        var firstPostId by remember(post.id) { mutableStateOf<Int?>(null) }
        LaunchedEffect(post.id) {
            firstPostId = runCatchingCancellable { ServiceLocator.get.client.topic(post.id) }
                .getOrNull()
                ?.postStream
                ?.posts
                ?.firstOrNull()
                ?.id
            if (firstPostId == null) {
                ToastCenter.show(R.string.error_action_failed)
                reporting.value = null
            }
        }
        firstPostId?.let { id ->
            ReportDialog(ReportTarget.Post(id), post.authorName ?: post.authorUsername) {
                reporting.value = null
            }
        }
    }
    blocking.value?.let { username ->
        BlockUserDialog(username, onDismiss = { blocking.value = null })
    }
}
