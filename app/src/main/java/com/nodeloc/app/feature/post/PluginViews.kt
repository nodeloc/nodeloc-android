package com.nodeloc.app.feature.post

import androidx.compose.foundation.background
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import com.composables.icons.lucide.ChartBar
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.SquareCheck
import com.nodeloc.app.core.design.LocalReduceMotion
import com.nodeloc.app.core.design.animatedColor
import com.nodeloc.app.core.util.DiscourseFormat
import kotlin.math.roundToInt
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Gift
import com.composables.icons.lucide.CircleCheck
import com.nodeloc.app.R
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.PrimaryButton
import com.nodeloc.app.core.design.Radius
import com.nodeloc.app.core.design.SecondaryButton
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.TagChip
import com.nodeloc.app.core.design.TagStyle
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.html.plainTextFromHtml
import com.nodeloc.app.core.model.PostPoll
import com.nodeloc.app.core.model.TopicRedEnvelope

/**
 * poll plugin (App-Android-PostPoll). Rendered where the author placed it (the
 * parser leaves a placeholder), never appended to the end of the body.
 *
 * Choices are staged and sent with 投票, single or multiple, so a mis-tap is
 * not a vote. Once results are visible the bars grow from where they were —
 * zero the first time — a row at a time; with reduced motion they are simply
 * there.
 */
@Composable
fun PollView(
    poll: PostPoll,
    myVotes: List<String>,
    canVote: Boolean,
    onVote: (List<String>) -> Unit,
    onRemoveVote: () -> Unit,
) {
    var pending by remember(poll.pollName, myVotes) { mutableStateOf(myVotes.toSet()) }
    val staged = pending != myVotes.toSet()
    val hasVoted = myVotes.isNotEmpty()
    // The server leaves `votes` out until the reader may see them.
    val showResults = poll.options.orEmpty().any { it.votes != null }
    val total = poll.totalVotes.coerceAtLeast(1)
    val leaderVotes = poll.options.orEmpty().mapNotNull { it.votes }.maxOrNull() ?: 0
    val interactive = canVote && !poll.isClosed && poll.isVotable
    val reduceMotion = LocalReduceMotion.current
    val shape = RoundedCornerShape(18.dp)
    val stagedIsValid = if (poll.isMultiple) {
        val low = (poll.min ?: 1).coerceAtLeast(1)
        val high = poll.max ?: Int.MAX_VALUE
        pending.size in low..maxOf(low, high)
    } else {
        pending.size == 1
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Nocturne.bg)
            .border(1.dp, Nocturne.divider, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PollHeader(poll)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            poll.options.orEmpty().forEachIndexed { index, option ->
                val selected = option.id in pending
                val mine = option.id in myVotes && !staged
                val votes = option.votes ?: 0
                val leader = showResults && votes > 0 && votes == leaderVotes
                val share = if (showResults) votes.toFloat() / total else 0f
                // Starts at zero and animates to its share: the growth is the point.
                var target by remember(poll.pollName, option.id) { mutableStateOf(0f) }
                LaunchedEffect(share) { target = share }
                val drawn by animateFloatAsState(
                    target,
                    if (reduceMotion) snap() else tween(durationMillis = 700, delayMillis = index * 70, easing = FastOutSlowInEasing),
                    label = "pollBar",
                )
                val rowShape = RoundedCornerShape(12.dp)
                val outline by animatedColor(if (selected) Nocturne.accent else Nocturne.divider, "pollOutline")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp)
                        .clip(rowShape)
                        .background(Nocturne.surface)
                        .border(if (selected) 1.5.dp else 1.dp, outline, rowShape)
                        .clickable(enabled = interactive, role = if (poll.isMultiple) Role.Checkbox else Role.RadioButton) {
                            pending = when {
                                !poll.isMultiple -> setOf(option.id)
                                selected -> pending - option.id
                                else -> pending + option.id
                            }
                        },
                ) {
                    Box(Modifier.matchParentSize()) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(drawn.coerceIn(0f, 1f))
                                .background(
                                    Nocturne.accent.copy(
                                        alpha = when {
                                            mine -> 0.24f
                                            leader -> 0.16f
                                            else -> 0.09f
                                        },
                                    ),
                                ),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            when {
                                poll.isMultiple && selected -> Lucide.SquareCheck
                                poll.isMultiple -> Lucide.Square
                                selected -> Lucide.CircleCheck
                                else -> Lucide.Circle
                            },
                            null,
                            tint = if (selected) Nocturne.accent else Nocturne.textTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                        Row(
                            Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                plainTextFromHtml(option.html),
                                style = Type.body(15, if (leader) FontWeight.SemiBold else FontWeight.Medium),
                                color = Nocturne.text,
                                maxLines = 2,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (mine && showResults) PollTag(stringResource(R.string.poll_my_choice), Nocturne.bg, Nocturne.accent)
                            if (leader) PollTag(stringResource(R.string.poll_leader), Nocturne.gold, Nocturne.goldBg)
                        }
                        // Always laid out, so the row keeps its height and its
                        // text its width when the results appear.
                        Row(
                            Modifier.alpha(if (showResults) 1f else 0f),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text("${(share * 100).roundToInt()}%", style = Type.body(14, FontWeight.Bold), color = Nocturne.text)
                            Text(
                                pluralStringResource(R.plurals.poll_votes, votes, votes),
                                style = Type.body(12),
                                color = Nocturne.textSecondary,
                            )
                        }
                    }
                }
            }
        }

        if (interactive && (!hasVoted || staged)) {
            Text(
                if (poll.isMultiple && poll.min != null && poll.max != null) {
                    stringResource(R.string.poll_hint_multiple, pending.size, poll.min, poll.max)
                } else {
                    stringResource(R.string.poll_hint)
                },
                style = Type.body(12),
                color = Nocturne.textSecondary,
            )
        }
        if (interactive) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hasVoted) SecondaryButton(stringResource(R.string.poll_revoke)) { onRemoveVote() }
                PrimaryButton(
                    stringResource(if (hasVoted) R.string.poll_change else R.string.poll_vote),
                    enabled = staged && stagedIsValid,
                ) { onVote(pending.toList()) }
            }
        }
    }
}

/** 投票 · 单选, who took part and when it closes, and whether it is still open. */
@Composable
private fun PollHeader(poll: PostPoll) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Nocturne.selected),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.ChartBar, null, tint = Nocturne.accent, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val kind = stringResource(if (poll.isMultiple) R.string.poll_kind_multiple else R.string.poll_kind_single)
            Text(
                poll.title?.takeIf { it.isNotBlank() }?.let { "$it · $kind" } ?: stringResource(R.string.poll_headline, kind),
                style = Type.body(16, FontWeight.Bold),
                color = Nocturne.text,
            )
            val voters = poll.voters ?: poll.totalVotes
            val parts = buildList {
                add(pluralStringResource(R.plurals.poll_voters, voters, voters))
                poll.close?.let { close ->
                    add(stringResource(R.string.poll_closes, DiscourseFormat.dayAndTime(close)))
                }
                if (poll.isMultiple && poll.min != null && poll.max != null) {
                    add(stringResource(R.string.poll_range, poll.min, poll.max))
                }
            }
            Text(parts.joinToString(" · "), style = Type.body(12), color = Nocturne.textSecondary)
        }
        Text(
            stringResource(if (poll.isClosed) R.string.poll_closed else R.string.poll_open),
            style = Type.body(12, FontWeight.Bold),
            color = if (poll.isClosed) Nocturne.textSecondary else Nocturne.accent,
            modifier = Modifier
                .clip(CircleShape)
                .background(if (poll.isClosed) Nocturne.neutral300 else Nocturne.selected)
                .padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun PollTag(text: String, ink: androidx.compose.ui.graphics.Color, ground: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = Type.body(11, FontWeight.Bold),
        color = ink,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(ground).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * red-envelope plugin. There is no claim action — the plugin auto-claims on
 * `post_created`, so replying is what opens it. The banner says exactly that.
 */
@Composable
fun RedEnvelopeBanner(envelope: TopicRedEnvelope, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(Nocturne.accent2.copy(alpha = 0.14f))
            .padding(Space.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Icon(Lucide.Gift, null, tint = Nocturne.accent2, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    R.string.envelope_title,
                    envelope.totalPoints ?: 0,
                    (envelope.totalCount ?: 0).let { pluralStringResource(R.plurals.envelope_packets, it, it) },
                ),
                style = Type.body(14, FontWeight.SemiBold),
                color = Nocturne.text,
            )
            Text(
                if (envelope.exhausted == true) {
                    stringResource(R.string.envelope_exhausted)
                } else {
                    stringResource(R.string.envelope_remaining, envelope.availableCount ?: 0)
                },
                style = Type.body(12),
                color = Nocturne.muted(0.55f),
            )
        }
    }
}
