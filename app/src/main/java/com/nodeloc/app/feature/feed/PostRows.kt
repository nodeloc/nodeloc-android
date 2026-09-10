package com.nodeloc.app.feature.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowBigUp
import com.composables.icons.lucide.ThumbsUp
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.MessageCircle
import com.nodeloc.app.R
import com.nodeloc.app.core.html.breakingLongTokens
import com.nodeloc.app.core.design.ActionPill
import com.nodeloc.app.core.design.FeedMedia
import com.nodeloc.app.core.design.HairLine
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.BlurredMediaBackdrop
import com.nodeloc.app.core.design.RemoteImage
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.TagChip
import com.nodeloc.app.core.design.TagStyle
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.VoteControl
import com.nodeloc.app.core.model.NodeReadingMode
import com.nodeloc.app.core.model.Post
import com.nodeloc.app.core.model.VoteDirection
import com.nodeloc.app.core.util.DiscourseFormat
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.nodeloc.app.feature.media.InlineVideoPlayer

/**
 * The three feed row shapes. One entry point so the feed, node pages and search
 * results can't drift: whichever mode is active, every list renders it the same.
 */
@Composable
fun PostRow(
    post: Post,
    mode: NodeReadingMode,
    modifier: Modifier = Modifier,
    /**
     * Attributes the row to its author rather than to its node. Inside a node
     * every row carries the same `n/slug`, which says nothing; who wrote it
     * does.
     */
    attributeToAuthor: Boolean = false,
    onOpen: () -> Unit,
    onOpenAuthor: (String) -> Unit = {},
    onShare: () -> Unit = {},
    /** Null where the screen has no place to keep an optimistic vote. */
    onVote: ((VoteDirection, String?) -> Unit)? = null,
    /** A picture was tapped, at this index into the row's own media. */
    onOpenImage: (Int) -> Unit = {},
    /**
     * The overflow menu, or nothing where a screen has no place to host the
     * dialogs it opens — reporting and blocking both need one.
     */
    more: PostRowActions? = null,
) {
    val label = if (attributeToAuthor) post.authorHandle ?: post.node else post.node
    when (mode) {
        NodeReadingMode.Compact -> CompactRow(post, label, modifier, onOpen, more)
        NodeReadingMode.Expand -> ExpandedRow(post, label, modifier, onOpen, onShare, onVote, onOpenImage, more)
        NodeReadingMode.Card ->
            CardRow(post, label, modifier, onOpen, onOpenAuthor, onShare, onVote, onOpenImage, more)
    }
}

@Composable
private fun CompactRow(
    post: Post,
    label: String,
    modifier: Modifier,
    onOpen: () -> Unit,
    more: PostRowActions?,
) {
    Column(modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            Modifier.padding(horizontal = Space.page, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            RemoteAvatar(post.avatarUrl, post.avatarLetter, variant = post.variant, size = 30.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    post.title.breakingLongTokens(),
                    style = Type.body(15, FontWeight.Medium),
                    color = Nocturne.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                MetaLine(post, label)
            }
            Column(horizontalAlignment = Alignment.End) {
                // Above the count rather than over it: this column is the only
                // thing on the right of a compact row, and an overlay would
                // land on the number.
                more?.let { PostRowMenu(it) }
                if (post.isUnread) UnreadDot()
                Spacer(Modifier.height(4.dp))
                Text(
                    DiscourseFormat.count(post.comments),
                    style = Type.body(12),
                    color = Nocturne.muted(0.45f),
                )
            }
        }
        HairLine()
    }
}

@Composable
private fun ExpandedRow(
    post: Post,
    label: String,
    modifier: Modifier,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onVote: ((VoteDirection, String?) -> Unit)?,
    onOpenImage: (Int) -> Unit,
    more: PostRowActions?,
) {
    Column(modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(
            Modifier.padding(horizontal = Space.page, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            AuthorLine(post, label, more = more)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    Text(
                        post.title.breakingLongTokens(),
                        style = Type.body(16, FontWeight.Medium),
                        color = Nocturne.text,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (post.tags.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            post.tags.take(3).forEach { TagChip(it, style = TagStyle.Outline) }
                        }
                    }
                }
                if (post.imageUrl != null) {
                    RemoteImage(
                        post.media.firstOrNull()?.bestUrl(240) ?: post.imageUrl,
                        modifier = Modifier
                            .size(78.dp)
                            .clip(RoundedCornerShape(com.nodeloc.app.core.design.Radius.md))
                            .clickable { onOpenImage(0) },
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            ActionRow(post, onShare, onVote)
        }
        HairLine()
    }
}

@Composable
private fun CardRow(
    post: Post,
    label: String,
    modifier: Modifier,
    onOpen: () -> Unit,
    onOpenAuthor: (String) -> Unit,
    onShare: () -> Unit,
    onVote: ((VoteDirection, String?) -> Unit)?,
    onOpenImage: (Int) -> Unit,
    more: PostRowActions?,
) {
    Column(modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(
            Modifier.padding(horizontal = Space.card, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            AuthorLine(post, label, onOpenAuthor = onOpenAuthor, more = more)
            Text(
                post.title.breakingLongTokens(),
                style = Type.body(17, FontWeight.Medium),
                color = Nocturne.text,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (post.excerpt.isNotEmpty() && post.media.isEmpty()) {
                Text(
                    post.excerpt,
                    style = Type.body(14),
                    color = Nocturne.muted(0.62f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (post.media.isNotEmpty() || post.videoUrl != null) CardMedia(post, onOpen, onOpenImage)
            ActionRow(post, onShare, onVote)
        }
        HairLine()
    }
}

/**
 * Card media sizes itself by the media's own aspect ratio and clamps only the
 * extremes (9:16 … 5:4), then shows whatever it holds whole rather than
 * cropping it to fit. What is left over at the sides is the same picture
 * blurred — see [BlurredMediaBackdrop] — so a portrait screenshot in a landscape
 * card keeps all of itself instead of losing its top and bottom.
 */
@Composable
private fun CardMedia(post: Post, onOpen: () -> Unit, onOpenImage: (Int) -> Unit) {
    val shape = RoundedCornerShape(com.nodeloc.app.core.design.Radius.md)
    val first = post.media.firstOrNull()
    val ratio = 1f / FeedMedia.heightRatio(first?.width, first?.height)

    val videoUrl = post.videoUrl
    if (videoUrl != null) {
        // Muted autoplay while the card is on screen; the player judges that
        // for itself. The poster doubles as the backdrop, which is the video's
        // own first frame — Discourse uploads it alongside the clip.
        InlineVideoPlayer(
            url = videoUrl,
            aspectRatio = ratio,
            backdropUrl = first?.bestUrl(480),
            // The row's own tap: opening the topic is what tapping a card does,
            // and the clip is most of this one.
            onClick = onOpen,
        )
    } else if (post.media.size == 1) {
        Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(shape).clickable { onOpenImage(0) }) {
            // Small on purpose: it is about to be blurred, so a bigger one buys
            // nothing but bytes and a longer decode.
            BlurredMediaBackdrop(first?.bestUrl(480), Modifier.fillMaxSize())
            RemoteImage(
                first?.bestUrl(1080),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    } else {
        val pagerState = rememberPagerState { post.media.size }
        val scope = rememberCoroutineScope()
        Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(shape)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
                Box(Modifier.fillMaxWidth().aspectRatio(ratio).clickable { onOpenImage(page) }) {
                    // Per page, not per card: a gallery's pictures are rarely
                    // all the same shape, and the backdrop has to match the one
                    // being looked at.
                    BlurredMediaBackdrop(post.media[page].bestUrl(480), Modifier.fillMaxSize())
                    RemoteImage(
                        post.media[page].bestUrl(1080),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }

            // Arrows as well as the swipe: on a card that fills the width there
            // is nothing to say the picture moves, and the row underneath is
            // itself horizontally scrollable in some feeds.
            if (pagerState.currentPage > 0) {
                PagerArrow(
                    Lucide.ChevronLeft,
                    stringResource(R.string.viewer_previous),
                    Modifier.align(Alignment.CenterStart),
                ) { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } }
            }
            if (pagerState.currentPage < post.media.lastIndex) {
                PagerArrow(
                    Lucide.ChevronRight,
                    stringResource(R.string.viewer_next),
                    Modifier.align(Alignment.CenterEnd),
                ) { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } }
            }

            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    "${pagerState.currentPage + 1}/${post.media.size}",
                    style = Type.body(11),
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun PagerArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .padding(horizontal = 6.dp)
            .size(32.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun AuthorLine(
    post: Post,
    label: String,
    onOpenAuthor: (String) -> Unit = {},
    more: PostRowActions? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
        RemoteAvatar(
            post.avatarUrl,
            post.avatarLetter,
            variant = post.variant,
            size = 26.dp,
            modifier = Modifier.clickable(enabled = post.authorUsername != null) {
                post.authorUsername?.let(onOpenAuthor)
            },
        )
        if (post.isUnread) UnreadDot()
        Text(label, style = Type.body(12, FontWeight.Medium), color = Nocturne.accent)
        Text("·", style = Type.body(12), color = Nocturne.muted(0.35f))
        Text(post.time, style = Type.body(12), color = Nocturne.muted(0.45f))
        if (post.pinned) {
            Icon(
                Lucide.Pin,
                stringResource(R.string.feed_pinned),
                tint = Nocturne.accent2,
                modifier = Modifier.size(13.dp),
            )
        }
        // Pushed to the end of the line the row already has, so it lands at the
        // top right without an overlay that would sit on whatever is there.
        more?.let {
            Spacer(Modifier.weight(1f))
            PostRowMenu(it)
        }
    }
}

@Composable
private fun MetaLine(post: Post, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = Type.body(12, FontWeight.Medium),
            color = Nocturne.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text("·", style = Type.body(12), color = Nocturne.muted(0.35f))
        Text(post.time, style = Type.body(12), color = Nocturne.muted(0.45f))
    }
}

@Composable
private fun ActionRow(post: Post, onShare: () -> Unit, onVote: ((VoteDirection, String?) -> Unit)?) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        val score = post.voteScore
        when {
            // Votable, and this list knows how to cast one.
            score != null && onVote != null -> VoteControl(
                direction = post.voteDirection,
                score = score,
                canVoteDown = post.canVoteDown,
                onVote = { onVote(it, null) },
                onPickFace = { direction, face -> onVote(direction, face) },
                iconSize = 17.dp,
                textSize = 12,
            )
            // Votable, but the screen showing it has nowhere to put the write —
            // search results are a view of other lists, not one of their own.
            // Better the same number the topic will show than a like count that
            // disagrees with it.
            score != null -> MetricPill(Lucide.ArrowBigUp, DiscourseFormat.count(score))
            else -> MetricPill(Lucide.ThumbsUp, DiscourseFormat.count(post.baseVotes))
        }
        MetricPill(Lucide.MessageCircle, DiscourseFormat.count(post.comments))
        Spacer(Modifier.weight(1f))
        ActionPill(onClick = onShare, circular = true) {
            Icon(
                Lucide.Share2,
                stringResource(R.string.common_share),
                tint = Nocturne.muted(0.45f),
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@Composable
private fun MetricPill(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String) {
    ActionPill {
        Icon(icon, null, tint = Nocturne.muted(0.45f), modifier = Modifier.size(16.dp))
        Text(value, style = Type.body(12), color = Nocturne.muted(0.55f))
    }
}

@Composable
private fun UnreadDot() {
    Box(Modifier.size(7.dp).clip(CircleShape).background(Nocturne.accent))
}
