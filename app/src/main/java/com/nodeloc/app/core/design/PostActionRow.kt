package com.nodeloc.app.core.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Repeat
import com.nodeloc.app.R
import com.nodeloc.app.core.model.VoteDirection

/**
 * What a post's action row shows, without the callbacks that work it.
 *
 * Kept apart from them because the full-screen viewer carries one of these per
 * clip, and a clip swiped to belongs to a topic the host screen never opened:
 * [topicId] is how the host tells its own post from someone else's.
 */
data class PostActionState(
    val postId: Int? = null,
    val topicId: Int? = null,
    /** Null where discourse-vote does not apply; the heart stands in for it. */
    val voteScore: Int? = null,
    val voteDirection: VoteDirection = VoteDirection.None,
    val canVoteDown: Boolean = false,
    val liked: Boolean = false,
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    /** Only the tint: the running total belongs to the record line above. */
    val rewarded: Boolean = false,
)

/**
 * A post's buttons: what it scored on the left, what you can do with it
 * elsewhere on the right.
 *
 * One composable rather than one per screen. The reader and the full-screen
 * viewer put this same row under the same post, and the viewer's copy is read
 * against the reader's from memory, a screen away — two of them would be two
 * things to keep in step.
 */
@Composable
fun PostActionRow(
    state: PostActionState,
    onLike: () -> Unit,
    onCastVote: (VoteDirection, String?) -> Unit,
    onComment: () -> Unit,
    onRepost: () -> Unit,
    onReward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Over a clip the pills go translucent and their glyphs go white; on a page
    // they are the tokens. Either way it is this one row — see [ActionPillStyle].
    val style = LocalActionPillStyle.current
    val glyph = style?.icon ?: Nocturne.muted(0.45f)
    val count = style?.label ?: Nocturne.muted(0.55f)

    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        // Where discourse-vote is on, the heart *is* the upvote — the plugin
        // stores it as a like — so the two do not sit side by side; the arrows
        // take its place, as they do on the web.
        if (state.voteScore != null) {
            VoteControl(
                direction = state.voteDirection,
                score = state.voteScore,
                canVoteDown = state.canVoteDown,
                onVote = { onCastVote(it, null) },
                onPickFace = { direction, face -> onCastVote(direction, face) },
                iconSize = 20.dp,
            )
        } else {
            ActionPill(onClick = onLike) {
                Icon(
                    // Lucide has no solid heart, so the tint carries the state alone.
                    Lucide.Heart,
                    stringResource(R.string.reader_like),
                    tint = if (state.liked) Nocturne.love else glyph,
                    modifier = Modifier.size(19.dp),
                )
                Text("${state.likeCount}", style = Type.body(13), color = count)
            }
        }
        ActionPill(onClick = onComment) {
            Icon(
                Lucide.MessageCircle,
                stringResource(R.string.reader_comment),
                tint = glyph,
                modifier = Modifier.size(18.dp),
            )
            Text("${state.commentCount}", style = Type.body(13), color = count)
        }

        // Splits the row in two: what the post scored on the left, what you
        // can do with it elsewhere on the right. The counted pills grow with
        // their numbers, so anchoring these two to the trailing edge is what
        // keeps them from drifting as a topic collects votes and replies.
        Spacer(Modifier.weight(1f))

        ActionPill(onClick = onRepost, circular = true) {
            Icon(
                Lucide.Repeat,
                stringResource(R.string.reader_repost),
                tint = glyph,
                modifier = Modifier.size(18.dp),
            )
        }
        // The running total moved up to the record line; this is only the
        // verb, so it stays a disc whether or not anybody has tipped.
        ActionPill(onClick = onReward, circular = true) {
            Icon(
                NodelocIcons.Zap,
                stringResource(R.string.reader_reward),
                tint = if (state.rewarded) Nocturne.accent2 else glyph,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

