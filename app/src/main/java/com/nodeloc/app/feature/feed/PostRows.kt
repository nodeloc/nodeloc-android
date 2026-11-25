package com.nodeloc.app.feature.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowBigUp
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.ThumbsUp
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.BlurredMediaBackdrop
import com.nodeloc.app.core.design.FeedMedia
import com.nodeloc.app.core.design.HairLine
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Radius
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.RemoteImage
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.TagChip
import com.nodeloc.app.core.design.TagStyle
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.VoteControl
import com.nodeloc.app.core.design.pressScale
import com.nodeloc.app.core.design.rememberPressSource
import com.nodeloc.app.core.html.breakingLongTokens
import com.nodeloc.app.core.model.NodeReadingMode
import com.nodeloc.app.core.model.Post
import com.nodeloc.app.core.model.VoteDirection
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.feature.media.InlineVideoPlayer
import kotlinx.coroutines.launch

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
    val byline = rememberByline(post, attributeToAuthor)
    when (mode) {
        NodeReadingMode.Compact -> CompactRow(post, byline, modifier, onOpen, onOpenAuthor, more)
        NodeReadingMode.Expand ->
            ExpandedRow(post, byline, attributeToAuthor, modifier, onOpen, onOpenAuthor, onShare, onVote, onOpenImage, more)
        NodeReadingMode.Card ->
            CardRow(post, byline, attributeToAuthor, modifier, onOpen, onOpenAuthor, onShare, onVote, onOpenImage, more)
    }
}

/** Who or where a row is credited to: the label, and the picture beside it. */
private data class Byline(
    val label: String,
    val imageUrl: String?,
    val letter: String,
    val variant: Int,
    /** Set when the picture is a person's, so tapping it can open their profile. */
    val username: String?,
)

/**
 * The design leads every row with its node, not its author: on a page of
 * mixed nodes, where a topic lives says more about it than who started it.
 * The node's logo is looked up in the site's directory rather than carried on
 * the row, because the topic list only names the node; a node the directory
 * has not loaded yet falls back to its initial, which is what a logo-less node
 * shows anyway.
 */
@Composable
private fun rememberByline(post: Post, attributeToAuthor: Boolean): Byline {
    val author = if (attributeToAuthor) post.authorHandle else null
    return remember(post.id, post.categoryId, post.avatarUrl, post.node, author) {
        if (author != null) {
            Byline(author, post.avatarUrl, post.avatarLetter, post.variant, post.authorUsername)
        } else {
            val node = ServiceLocator.get.siteRepository.category(post.categoryId)
            Byline(
                label = post.node,
                imageUrl = DiscourseConfig.absoluteUrl(node?.uploadedLogo?.url),
                letter = (node?.name?.takeIf { it.isNotBlank() } ?: post.node.removePrefix("n/")).take(1).uppercase(),
                variant = (post.categoryId ?: 0) % 2,
                username = null,
            )
        }
    }
}

@Composable
private fun BylineAvatar(byline: Byline, size: androidx.compose.ui.unit.Dp, onOpenAuthor: (String) -> Unit) {
    RemoteAvatar(
        byline.imageUrl,
        byline.letter,
        variant = byline.variant,
        size = size,
        modifier = Modifier.clickable(enabled = byline.username != null) {
            byline.username?.let(onOpenAuthor)
        },
    )
}

/** The dense variant: still node-first, but one line of meta and no actions. */
@Composable
private fun CompactRow(
    post: Post,
    byline: Byline,
    modifier: Modifier,
    onOpen: () -> Unit,
    onOpenAuthor: (String) -> Unit,
    more: PostRowActions?,
) {
    Column(modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            Modifier.padding(horizontal = Space.page, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            BylineAvatar(byline, 28.dp, onOpenAuthor)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    post.title.breakingLongTokens(),
                    style = Type.body(15, FontWeight.SemiBold),
                    color = Nocturne.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                MetaLine(post, byline.label)
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
                    color = Nocturne.textTertiary,
                )
            }
        }
        HairLine()
    }
}

@Composable
private fun ExpandedRow(
    post: Post,
    byline: Byline,
    attributeToAuthor: Boolean,
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
            Modifier.padding(horizontal = Space.page, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RowHeader(post, byline, onOpenAuthor, attributeToAuthor)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RowTitle(post, maxLines = 3)
                    RowExcerpt(post)
                    // Inside a node the header already carries the first tag.
                    if (post.tags.isNotEmpty() && !attributeToAuthor) {
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
                            .clip(MEDIA_SHAPE)
                            .clickable { onOpenImage(0) },
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            ActionRow(post, onShare, onVote, more)
        }
        HairLine()
    }
}

@Composable
private fun CardRow(
    post: Post,
    byline: Byline,
    attributeToAuthor: Boolean,
    modifier: Modifier,
    onOpen: () -> Unit,
    onOpenAuthor: (String) -> Unit,
    onShare: () -> Unit,
    onVote: ((VoteDirection, String?) -> Unit)?,
    onOpenImage: (Int) -> Unit,
    more: PostRowActions?,
) {
    Column(modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        // The v3 board's rhythm: 6 under the byline, 4 under the title, 8
        // before the picture and 10 before the actions.
        Column(Modifier.padding(horizontal = Space.page, vertical = 12.dp)) {
            RowHeader(post, byline, onOpenAuthor, attributeToAuthor)
            Spacer(Modifier.height(6.dp))
            RowTitle(post, maxLines = 4)
            if (post.excerpt.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                RowExcerpt(post)
            }
            if (post.media.isNotEmpty() || post.videoUrl != null) {
                Spacer(Modifier.height(8.dp))
                CardMedia(post, onOpen, onOpenImage)
            }
            Spacer(Modifier.height(10.dp))
            ActionRow(post, onShare, onVote, more)
        }
        HairLine()
    }
}

/** The v3 boards round media to 12, a step under the card radius. */
private val MEDIA_SHAPE = RoundedCornerShape(12.dp)

@Composable
private fun RowTitle(post: Post, maxLines: Int) {
    Text(
        post.title.breakingLongTokens(),
        style = Type.body(16, FontWeight.Bold).copy(lineHeight = Type.lineHeight(16, 6)),
        color = Nocturne.text,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RowExcerpt(post: Post) {
    if (post.excerpt.isEmpty()) return
    Text(
        post.excerpt,
        style = Type.body(13).copy(lineHeight = Type.lineHeight(13, 6)),
        // The board sets the excerpt in a near-ink grey, darker than the
        // secondary text of the byline beside it.
        color = Nocturne.muted(0.86f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
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
    val shape = MEDIA_SHAPE
    val first = post.media.firstOrNull()
    // The 2.0 topic list sends only `image_url`, no dimensions. Without them the
    // ratio fell back to a square and the picture was letterboxed inside it — a
    // wide banner became a grey box with a strip in the middle. Unknown size
    // gets the design's short landscape card and a crop instead.
    val known = first?.width != null && first.height != null
    val ratio = if (known) 1f / FeedMedia.heightRatio(first?.width, first?.height) else 16f / 9f

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
                contentScale = if (known) ContentScale.Fit else ContentScale.Crop,
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
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    "${pagerState.currentPage + 1} / ${post.media.size}",
                    style = Type.body(11, FontWeight.Bold),
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun PagerArrow(
    icon: ImageVector,
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

/**
 * "n/slug · time", led by the node's logo. Inside a node it is "u/name · time"
 * and the topic's first tag, which is how the v3 node board tells its rows
 * apart when every one of them lives in the same place.
 *
 * The menu moved from here to the end of the action row in v3, where it shares
 * one button with 分享.
 */
@Composable
private fun RowHeader(
    post: Post,
    byline: Byline,
    onOpenAuthor: (String) -> Unit,
    showTag: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BylineAvatar(byline, 24.dp, onOpenAuthor)
        Text(
            byline.label,
            style = Type.body(12, FontWeight.Bold),
            color = Nocturne.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text("· ${post.time}", style = Type.body(12), color = Nocturne.textSecondary, maxLines = 1)
        if (showTag) {
            post.tags.firstOrNull()?.let { tag ->
                Text(
                    tag,
                    style = Type.body(12, FontWeight.Medium),
                    color = Nocturne.text,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Nocturne.neutral400)
                        .padding(horizontal = 8.dp),
                )
            }
        }
        if (post.isUnread) UnreadDot()
        if (post.pinned) {
            Icon(
                Lucide.Pin,
                stringResource(R.string.feed_pinned),
                tint = Nocturne.accent2,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
private fun MetaLine(post: Post, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = Type.body(12, FontWeight.SemiBold),
            color = Nocturne.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text("· ${post.time}", style = Type.body(12), color = Nocturne.textSecondary, maxLines = 1)
    }
}

@Composable
private fun ActionRow(
    post: Post,
    onShare: () -> Unit,
    onVote: ((VoteDirection, String?) -> Unit)?,
    more: PostRowActions?,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val score = post.voteScore
        when {
            // Votable, and this list knows how to cast one. The capsule is
            // drawn here and the control's own ground turned off, so the
            // pills in the row share one height and one grey.
            score != null && onVote != null -> RowCapsule(horizontalPadding = 8.dp) {
                VoteControl(
                    direction = post.voteDirection,
                    score = score,
                    canVoteDown = post.canVoteDown,
                    onVote = { onVote(it, null) },
                    onPickFace = { direction, face -> onVote(direction, face) },
                    iconSize = 18.dp,
                    textSize = 14,
                    ground = false,
                )
            }
            // Votable, but the screen showing it has nowhere to put the write —
            // search results are a view of other lists, not one of their own.
            // Better the same number the topic will show than a like count that
            // disagrees with it.
            score != null -> MetricCapsule(Lucide.ArrowBigUp, DiscourseFormat.count(score))
            else -> MetricCapsule(Lucide.ThumbsUp, DiscourseFormat.count(post.baseVotes))
        }
        MetricCapsule(Lucide.MessageCircle, DiscourseFormat.count(post.comments))
        Spacer(Modifier.weight(1f))
        // v3 folds 分享 and the overflow menu into one round button at the far
        // end; the menu already leads with 分享. A list with nowhere to host the
        // menu's dialogs gets the same button doing just the share.
        if (more != null) {
            PostRowMenu(more) { open -> RoundActionButton(onClick = open) }
        } else {
            RoundActionButton(onClick = onShare)
        }
    }
}

@Composable
private fun RoundActionButton(onClick: () -> Unit) {
    val press = rememberPressSource()
    Box(
        Modifier
            .pressScale(press)
            .size(ACTION_HEIGHT)
            .clip(CircleShape)
            .background(Nocturne.neutral400)
            .clickable(interactionSource = press, indication = ripple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Lucide.Share2,
            stringResource(R.string.feed3_share_more),
            tint = Nocturne.text,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** The v3 boards' action pills are 44 high: a full touch target each. */
private val ACTION_HEIGHT = 44.dp

/** The design's action pill: 44 high, neutral ground, ink text. */
@Composable
private fun RowCapsule(
    onClick: (() -> Unit)? = null,
    horizontalPadding: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier
            .height(ACTION_HEIGHT)
            .clip(CircleShape)
            .background(Nocturne.neutral400)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun MetricCapsule(icon: ImageVector, value: String, onClick: (() -> Unit)? = null) {
    RowCapsule(onClick = onClick) {
        Icon(icon, null, tint = Nocturne.text, modifier = Modifier.size(18.dp))
        Text(value, style = Type.body(13, FontWeight.Bold), color = Nocturne.text)
    }
}

@Composable
private fun UnreadDot() {
    Box(Modifier.size(7.dp).clip(CircleShape).background(Nocturne.accent))
}
