package com.nodeloc.app.feature.post

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.composables.icons.lucide.EllipsisVertical
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pin
import com.nodeloc.app.core.design.NodelocIcons
import com.composables.icons.lucide.Reply
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.ChevronUp
import com.nodeloc.app.R
import com.nodeloc.app.core.design.LocalReduceMotion
import com.nodeloc.app.core.design.LowScoreFold
import com.nodeloc.app.core.design.ConfirmDialog
import com.nodeloc.app.core.design.BadgeTitleText
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.TagChip
import com.nodeloc.app.core.design.TagStyle
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.VoteControl
import com.nodeloc.app.core.html.PostContentView
import com.nodeloc.app.core.html.PostVideo
import com.nodeloc.app.core.html.PostImage
import com.nodeloc.app.core.model.PostComment
import com.nodeloc.app.core.model.VoteDirection

/** Indent per nesting level; 8 levels is where the rails stop biting in. */
/**
 * One vertical guide per ancestor level, and the indent they measure out.
 *
 * Every level draws its line, including the levels whose branch has no further
 * siblings: a long chain of one-reply-each is the common shape in a real
 * thread, and without the lines those replies just drift rightwards with
 * nothing tying them to the reply they answer.
 */
private val IndentStep = 14.dp
private val RailInset = Space.page

/**
 * One row of the flattened reply tree.
 *
 * The rails are drawn from [PostComment.nestingDepth] rather than by nesting
 * composables, which is what lets the whole thread live in a flat LazyColumn:
 * each row measures on its own, no matter how deep the conversation goes.
 */
@Composable
fun ReplyRow(
    comment: PostComment,
    collapsed: Boolean,
    isLoadingChildren: Boolean,
    onToggleCollapse: () -> Unit,
    onOpenAuthor: (String) -> Unit,
    onLike: () -> Unit,
    onVote: (VoteDirection) -> Unit,
    /** Casts a named face; null where the reader cannot vote at all. */
    onPickFace: ((VoteDirection, String) -> Unit)?,
    /** The site folded this reply for its score; see LowScoreFold. */
    lowScore: Boolean,
    onExpandLowScore: () -> Unit,
    onReply: () -> Unit,
    onReward: () -> Unit,
    onRewardDetail: () -> Unit,
    onReactionDetail: () -> Unit,
    onShare: () -> Unit,
    /** Already bookmarked: the menu offers to take it off instead. */
    bookmarked: Boolean,
    onBookmark: () -> Unit,
    onReport: () -> Unit,
    onBlockAuthor: () -> Unit,
    /** Null when the reader is not staff: pinning is theirs alone. */
    onTogglePin: (() -> Unit)?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRecover: () -> Unit,
    onToggleLock: () -> Unit,
    /** Staff, for the lock item — everything else is gated by the server's flags. */
    isStaff: Boolean,
    onOpenLink: (String) -> Unit,
    onOpenImage: (PostImage) -> Unit,
    onOpenVideo: (PostVideo) -> Unit,
    onLoadMoreChildren: (Int) -> Unit,
    pollSlot: @Composable (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (comment.isLoadMore) {
        LoadMoreRow(comment, isLoadingChildren) { onLoadMoreChildren(comment.loadMoreParent) }
        return
    }

    val depth = comment.nestingDepth
    // The whole row collapses, not just the glyphs in it. Children that mean
    // something else — the avatar, links, the action icons — claim their own
    // taps first, so hoisting this costs them nothing and makes the padding,
    // the indent and the blank space beside the timestamp work too.
    Row(
        modifier
            .fillMaxWidth()
            .replyRails(depth, Nocturne.divider)
            .clickable(onClick = onToggleCollapse),
    ) {
        Spacer(Modifier.width(RailInset + IndentStep * depth))
        Column(
            Modifier
                .weight(1f)
                .padding(end = Space.page, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            AuthorRow(comment, collapsed, onOpenAuthor)

            // Collapsing hides a whole subtree, so the body slides rather than
            // vanishes — otherwise everything below it jumps and the reader
            // loses their place. Honours the reader's reduce-motion setting.
            val motion = !LocalReduceMotion.current
            AnimatedVisibility(
                visible = !collapsed,
                enter = if (motion) expandVertically(CollapseSize) + fadeIn(CollapseAlpha) else EnterTransition.None,
                exit = if (motion) shrinkVertically(CollapseSize) + fadeOut(CollapseAlpha) else ExitTransition.None,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    val body = @Composable {
                        PostContentView(
                            content = comment.content,
                            baseSize = 14,
                            lineExtra = 5,
                            onOpenLink = onOpenLink,
                            onOpenImage = onOpenImage,
                            // Both were left at their no-op defaults: a poll in
                            // a reply rendered as a blank gap where the
                            // placeholder was, and a video drew its poster and
                            // play button but did nothing when tapped.
                            onOpenVideo = onOpenVideo,
                            onOpenMention = onOpenAuthor,
                            pollSlot = pollSlot,
                        )
                    }
                    // Folded by score, which is a different thing from the
                    // collapse above: that one is the reader hiding a subtree,
                    // this one is the site saying the thread thought little of
                    // this reply. The actions stay out of the fold — voting on
                    // a folded post is how it gets unfolded for everyone.
                    if (lowScore) LowScoreFold(maxHeight = 64.dp, onExpand = onExpandLowScore) { body() }
                    else body()

                    comment.redEnvelopeClaim?.pointsReceived?.let { points ->
                        TagChip(stringResource(R.string.reader_claimed_points, points), style = TagStyle.Accent2)
                    }

                    // Only the tip total. The faces went back down beside the
                    // menu: a reply's actions already hug the trailing edge, so
                    // a face row above them reads as a second, emptier line.
                    if (comment.rewardTotal > 0) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            RewardSummary(comment.rewardTotal, onRewardDetail)
                        }
                    }

                    ActionRow(
                        comment = comment,
                        onLike = onLike,
                        onVote = onVote,
                        onPickFace = onPickFace,
                        onReply = onReply,
                        onReward = onReward,
                        onRewardDetail = onRewardDetail,
                        onReactionDetail = onReactionDetail,
                        onShare = onShare,
                        bookmarked = bookmarked,
                        onBookmark = onBookmark,
                        onReport = onReport,
                        onBlockAuthor = onBlockAuthor,
                        onTogglePin = onTogglePin,
                        onEdit = onEdit,
                        onDelete = onDelete,
                        onRecover = onRecover,
                        onToggleLock = onToggleLock,
                        isStaff = isStaff,
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthorRow(
    comment: PostComment,
    collapsed: Boolean,
    onOpenAuthor: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Everything identifying the author is one weighted group, and the
        // chevron follows it. Weighting the title *and* a trailing spacer split
        // the leftover space between them, which left the chevron parked
        // mid-row on anyone who has a title.
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RemoteAvatar(
                comment.avatarUrl,
                comment.author.take(1).uppercase(),
                variant = comment.id % 2,
                size = 22.dp,
                modifier = Modifier.clickable { onOpenAuthor(comment.author) },
            )
            // Only the avatar goes somewhere else; the name, the timestamp and
            // the gap after them all fall through to the row.
            // The bare name. `u/` earns its place where a handle has to be told
            // apart from a node's `n/` — a feed row, a header — and a reply row
            // is nothing but people, so it only costs three characters of the
            // width their names have to share with a title and a timestamp.
            Text(
                comment.author,
                style = Type.body(13, FontWeight.SemiBold),
                color = Nocturne.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            comment.flairUrl?.let {
                RemoteAvatar(it, "", size = 14.dp)
            }
            comment.authorTitle?.takeIf { it.isNotBlank() }?.let {
                BadgeTitleText(it, size = 11, modifier = Modifier.weight(1f, fill = false))
            }
            // Before the time rather than after: the server has already moved
            // this reply to the top, and the badge is what stops that looking
            // like the sort being wrong.
            if (comment.pinned) {
                Icon(
                    Lucide.Pin,
                    stringResource(R.string.reader_pinned_badge),
                    tint = Nocturne.accent,
                    modifier = Modifier.size(12.dp),
                )
            }
            Text(comment.time, style = Type.body(11), color = Nocturne.muted(0.4f))
            // Only staff are sent a deleted post at all, and when they are it
            // has to look deleted — otherwise the thread reads as though
            // nothing happened and the recover action makes no sense.
            if (comment.deleted) {
                TagChip(stringResource(R.string.reader_deleted_badge), style = TagStyle.Outline)
            }
            if (comment.locked) {
                TagChip(stringResource(R.string.reader_locked_badge))
            }
            // After the time, the same place the opening post keeps it. A line
            // of its own was the web's shape and it does not survive the move:
            // there a reply is a wide block with room under the name, here it
            // is a narrow indented one, and the extra line cost every reply in
            // the thread height for a badge most of them do not have.
            comment.source?.takeIf { it.isNotBlank() }?.let {
                TagChip(stringResource(R.string.post_source_from, it))
            }
        }
        if (comment.hasChildren || collapsed) {
            Icon(
                if (collapsed) Lucide.ChevronDown else Lucide.ChevronUp,
                stringResource(if (collapsed) R.string.common_expand else R.string.common_collapse),
                tint = Nocturne.muted(0.4f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun ActionRow(
    comment: PostComment,
    onLike: () -> Unit,
    onVote: (VoteDirection) -> Unit,
    onPickFace: ((VoteDirection, String) -> Unit)?,
    onReply: () -> Unit,
    onReward: () -> Unit,
    onRewardDetail: () -> Unit,
    onReactionDetail: () -> Unit,
    onShare: () -> Unit,
    /** Already bookmarked: the menu offers to take it off instead. */
    bookmarked: Boolean,
    onBookmark: () -> Unit,
    onReport: () -> Unit,
    onBlockAuthor: () -> Unit,
    onTogglePin: (() -> Unit)?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRecover: () -> Unit,
    onToggleLock: () -> Unit,
    isStaff: Boolean,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        // Every control sits at the trailing edge — the reply body is the thing
        // being read, and the actions are a margin note against it.
        horizontalArrangement = Arrangement.spacedBy(Space.s6, Alignment.End),
    ) {
        // Leftmost, so the tally reads as what the row is about rather than as
        // a fifth button on it.
        ReactionSummary(
            reactions = comment.reactions,
            total = comment.reactionCount,
            onClick = onReactionDetail,
        )

        // The rarely-wanted three live behind one glyph rather than beside the
        // three that are wanted often; a reply row is a margin note, and six
        // icons in a margin note is a toolbar.
        Box {
            Icon(
                Lucide.EllipsisVertical,
                stringResource(R.string.common_more),
                tint = Nocturne.muted(0.45f),
                modifier = Modifier.size(16.dp).clickable { menuOpen = true },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.common_share)) },
                    onClick = { menuOpen = false; onShare() },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (bookmarked) R.string.reader_unbookmark else R.string.reader_bookmark,
                            ),
                        )
                    },
                    onClick = { menuOpen = false; onBookmark() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reader_report)) },
                    onClick = { menuOpen = false; onReport() },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.post_more_block_author),
                            style = Type.body(14),
                            color = Nocturne.danger,
                        )
                    },
                    onClick = { menuOpen = false; onBlockAuthor() },
                )
                onTogglePin?.let { toggle ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (comment.pinned) R.string.reader_unpin else R.string.reader_pin,
                                ),
                            )
                        },
                        onClick = { menuOpen = false; toggle() },
                    )
                }
                // Offered exactly when the server said so. `can_edit` already
                // weighs the edit window, the trust level, the lock, whether
                // the topic is archived — deciding any of that here would be a
                // second opinion, and a stale one.
                if (comment.canEdit) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reader_edit)) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                }
                if (comment.canDelete) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reader_delete), color = Nocturne.danger) },
                        onClick = { menuOpen = false; confirmDelete = true },
                    )
                }
                if (comment.canRecover) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reader_recover)) },
                        onClick = { menuOpen = false; onRecover() },
                    )
                }
                if (isStaff) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (comment.locked) R.string.reader_unlock else R.string.reader_lock,
                                ),
                            )
                        },
                        onClick = { menuOpen = false; onToggleLock() },
                    )
                }
            }

            // Deleting is the one action here with nothing to undo from the
            // app, so it asks. The wording says where the post goes rather
            // than only that it goes.
            if (confirmDelete) {
                ConfirmDialog(
                    title = stringResource(R.string.reader_delete_confirm),
                    body = stringResource(R.string.reader_delete_confirm_body),
                    confirmLabel = stringResource(R.string.reader_delete),
                    onConfirm = { confirmDelete = false; onDelete() },
                    onDismiss = { confirmDelete = false },
                )
            }
        }
        Icon(
            Lucide.Reply,
            stringResource(R.string.reader_reply),
            tint = Nocturne.muted(0.45f),
            modifier = Modifier.size(16.dp).clickable(onClick = onReply),
        )
        Icon(
            NodelocIcons.Zap,
            stringResource(R.string.reader_reward),
            tint = Nocturne.muted(0.45f),
            modifier = Modifier.size(16.dp).clickable(onClick = onReward),
        )
        // discourse-vote replaces the heart rather than joining it: the upvote
        // *is* the like, so both would be the same button twice.
        if (comment.voteScore != null) {
            VoteControl(
                direction = comment.voteDirection,
                score = comment.voteScore,
                canVoteDown = comment.canVoteDown,
                onVote = onVote,
                onPickFace = onPickFace,
                iconSize = 16.dp,
                textSize = 12,
                ground = false,
            )
        } else {
            Row(
                Modifier.clickable(onClick = onLike),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    // Lucide has no solid heart, so the tint carries the state alone.
                    Lucide.Heart,
                    stringResource(R.string.reader_like),
                    tint = if (comment.isLiked) Nocturne.love else Nocturne.muted(0.45f),
                    modifier = Modifier.size(16.dp),
                )
                if (comment.votes > 0) {
                    Text("${comment.votes}", style = Type.body(12), color = Nocturne.muted(0.5f))
                }
            }
        }
    }
}

@Composable
private fun LoadMoreRow(comment: PostComment, isLoading: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().replyRails(comment.nestingDepth, Nocturne.divider)) {
        Spacer(Modifier.width(RailInset + IndentStep * comment.nestingDepth))
        Row(
            Modifier
                .weight(1f)
                .clickable(enabled = !isLoading, onClick = onClick)
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isLoading) {
                NodelocLoader(height = 16.dp)
            } else {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Nocturne.accent))
            }
            Text(
                pluralStringResource(R.plurals.reader_load_more_replies, comment.loadMoreRemaining, comment.loadMoreRemaining),
                style = Type.body(13, FontWeight.Medium),
                color = Nocturne.accent,
            )
        }
    }
}

/**
 * Collapse motion.
 *
 * A deceleration curve, not the default spring: a spring starts from zero
 * velocity and spends its first frames barely moving, which reads as a pause
 * before the collapse rather than the collapse itself. This leaves at full
 * speed on the first frame and settles into place.
 *
 * The fade is deliberately shorter than the height change. Run at the same
 * length it dims the text while the row is still full height, and the eye
 * reads that as "something happened" before it reads "this is closing".
 */
private val CollapseSize = tween<IntSize>(durationMillis = 160, easing = LinearOutSlowInEasing)
private val CollapseAlpha = tween<Float>(durationMillis = 90, easing = LinearEasing)

/**
 * The ancestor guides, drawn rather than laid out.
 *
 * A laid-out rail has to `fillMaxHeight`, which forces `IntrinsicSize.Min` on
 * the row — and an intrinsic pass reports [AnimatedVisibility]'s *target* size,
 * not its animated one. The rails and the row height would then sit still for
 * the whole collapse and snap on the final frame. Drawing them takes the real,
 * animated height every frame instead, and costs no measure pass at all.
 */
private fun Modifier.replyRails(depth: Int, color: Color): Modifier = drawBehind {
    val step = IndentStep.toPx()
    // One physical pixel, not one dp: a rail is a hairline, and scaling it with
    // density turns it into a bar on a phone with a dense screen.
    val lineWidth = 1f
    val start = RailInset.toPx()
    repeat(depth) { level ->
        drawRect(
            color = color,
            topLeft = Offset(start + level * step, 0f),
            size = Size(lineWidth, size.height),
        )
    }
}
