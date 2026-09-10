package com.nodeloc.app.feature.post

import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Gift
import com.composables.icons.lucide.Dices
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
import com.nodeloc.app.core.model.PostLottery
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.core.model.PostPoll
import com.nodeloc.app.core.model.TopicRedEnvelope

/**
 * poll plugin. Rendered where the author placed it (the parser leaves a
 * placeholder), never appended to the end of the body.
 */
@Composable
fun PollView(
    poll: PostPoll,
    myVotes: List<String>,
    canVote: Boolean,
    onVote: (List<String>) -> Unit,
    onRemoveVote: () -> Unit,
) {
    var selection by remember(poll.pollName, myVotes) { mutableStateOf(myVotes.toSet()) }
    val hasVoted = myVotes.isNotEmpty()
    // Results hide until you vote when the poll is configured that way.
    val showResults = hasVoted || poll.isClosed || poll.results == "always"
    val total = poll.totalVotes.coerceAtLeast(1)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .border(1.dp, Nocturne.divider, RoundedCornerShape(Radius.md))
            .padding(Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(poll.title ?: stringResource(R.string.poll_title), style = Type.body(15, FontWeight.SemiBold), color = Nocturne.text)
            if (poll.isClosed) TagChip(stringResource(R.string.poll_closed), style = TagStyle.Neutral)
            if (poll.isMultiple) TagChip(stringResource(R.string.poll_multiple), style = TagStyle.Outline)
        }

        poll.options.orEmpty().forEach { option ->
            val selected = option.id in selection
            val votes = option.votes ?: 0
            val fraction = if (showResults) votes.toFloat() / total else 0f
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(Nocturne.surface)
                    .clickable(enabled = canVote && !poll.isClosed && poll.isVotable) {
                        selection = when {
                            !poll.isMultiple -> setOf(option.id)
                            selected -> selection - option.id
                            else -> selection + option.id
                        }
                        if (!poll.isMultiple) onVote(selection.toList())
                    },
            ) {
                if (showResults) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(36.dp)
                            .background(Nocturne.accent.copy(alpha = 0.16f)),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        if (selected) Lucide.CircleCheck else Lucide.Circle,
                        null,
                        tint = if (selected) Nocturne.accent else Nocturne.muted(0.35f),
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        plainTextFromHtml(option.html),
                        style = Type.body(14),
                        color = Nocturne.text,
                        modifier = Modifier.weight(1f),
                    )
                    if (showResults) {
                        Text("$votes", style = Type.body(12), color = Nocturne.muted(0.5f))
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            Text(pluralStringResource(R.plurals.poll_voters, poll.voters ?: 0, poll.voters ?: 0), style = Type.body(12), color = Nocturne.muted(0.45f))
            Spacer(Modifier.weight(1f))
            if (canVote && poll.isMultiple && !poll.isClosed) {
                PrimaryButton(stringResource(R.string.poll_vote)) { onVote(selection.toList()) }
            }
            if (canVote && hasVoted && !poll.isClosed) {
                SecondaryButton(stringResource(R.string.poll_revoke)) { onRemoveVote() }
            }
        }
    }
}

/** lottery plugin: ticket purchase, thresholds, and the drawn winners. */
@Composable
fun LotteryCard(
    lottery: PostLottery,
    canParticipate: Boolean,
    onParticipate: (quantity: Int, random: Boolean) -> Unit,
) {
    var quantity by remember { mutableIntStateOf(1) }
    var random by remember { mutableStateOf(false) }
    val maxTickets = (lottery.maxTicketsPerUser ?: 1).coerceAtLeast(1)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(Nocturne.surface)
            .padding(Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Lucide.Dices, null, tint = Nocturne.accent2, modifier = Modifier.size(18.dp))
            Text(lottery.title ?: stringResource(R.string.lottery_title), style = Type.body(15, FontWeight.SemiBold), color = Nocturne.text)
            if (!lottery.isOpen) TagChip(stringResource(R.string.lottery_drawn), style = TagStyle.Neutral)
        }

        lottery.levels.orEmpty().forEach { level ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(level.name ?: stringResource(R.string.lottery_prize), style = Type.body(13, FontWeight.Medium), color = Nocturne.text)
                Text(level.prize.orEmpty(), style = Type.body(13), color = Nocturne.muted(0.62f))
                Text(stringResource(R.string.lottery_quantity, level.quantity ?: 1), style = Type.body(12), color = Nocturne.muted(0.45f))
            }
        }

        // Joined here rather than baked into the strings: the separator used
        // to live as a leading space inside each fragment, which aapt trims.
        val entrants = lottery.participantsCount ?: 0
        val tickets = lottery.ticketsCount ?: 0
        val parts = listOfNotNull(
            pluralStringResource(R.plurals.lottery_entrants, entrants, entrants),
            pluralStringResource(R.plurals.lottery_tickets, tickets, tickets),
            lottery.minParticipants?.let { pluralStringResource(R.plurals.lottery_min_participants, it, it) },
            lottery.maxParticipants?.takeIf { lottery.hasParticipantCap }
                ?.let { pluralStringResource(R.plurals.lottery_max_participants, it, it) },
        )
        Text(parts.joinToString(" · "), style = Type.body(12), color = Nocturne.muted(0.45f))

        // The entry rules, as the plugin's own widget lists them. Without
        // these the card said what the prize was but never what it took to
        // have a chance at it.
        val conditions = listOfNotNull(
            lottery.drawAt?.let { stringResource(R.string.lottery_draws_at, DiscourseFormat.dayAndTime(it)) },
            lottery.minTicketsPerUser?.takeIf { it > 1 }
                ?.let { stringResource(R.string.lottery_condition_min_tickets, it) },
            lottery.maxTicketsPerUser?.takeIf { it > 0 }
                ?.let { stringResource(R.string.lottery_condition_max_tickets, it) },
            lottery.minTrustLevel?.takeIf { it > 0 }
                ?.let { stringResource(R.string.lottery_condition_trust_level, it) },
        )
        if (conditions.isNotEmpty()) {
            Text(conditions.joinToString(" · "), style = Type.body(12), color = Nocturne.muted(0.45f))
        }

        if (lottery.isOpen && canParticipate) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                Stepper(quantity, 1..maxTickets) { quantity = it }
                Row(
                    Modifier.clickable { random = !random },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        if (random) Lucide.CircleCheck else Lucide.Circle,
                        null,
                        tint = if (random) Nocturne.accent else Nocturne.muted(0.35f),
                        modifier = Modifier.size(16.dp),
                    )
                    Text(stringResource(R.string.lottery_random), style = Type.body(13), color = Nocturne.muted(0.62f))
                }
                Spacer(Modifier.weight(1f))
                PrimaryButton(stringResource(if (lottery.isParticipating == true) R.string.lottery_add else R.string.lottery_join)) {
                    onParticipate(quantity, random)
                }
            }
            lottery.userTickets?.takeIf { it > 0 }?.let {
                Text(pluralStringResource(R.plurals.lottery_my_tickets, it, it), style = Type.body(12), color = Nocturne.accent)
            }
        }

        lottery.winners?.takeIf { it.isNotEmpty() }?.let { winners ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.lottery_winners), style = Type.body(13, FontWeight.SemiBold), color = Nocturne.text)
                winners.forEach { winner ->
                    Text(
                        "${winner.username.orEmpty()} — ${winner.levelName ?: winner.prize.orEmpty()}",
                        style = Type.body(12),
                        color = Nocturne.muted(0.62f),
                    )
                }
            }
        }
    }
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

@Composable
private fun Stepper(value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .border(1.dp, Nocturne.divider, CircleShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperButton("−", enabled = value > range.first) { onChange(value - 1) }
        Text(
            value.toString(),
            style = Type.body(14, FontWeight.Medium),
            color = Nocturne.text,
            modifier = Modifier.width(32.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        StepperButton("+", enabled = value < range.last) { onChange(value + 1) }
    }
}

@Composable
private fun StepperButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = Type.body(16, FontWeight.Medium),
            color = if (enabled) Nocturne.text else Nocturne.muted(0.25f),
        )
    }
}
