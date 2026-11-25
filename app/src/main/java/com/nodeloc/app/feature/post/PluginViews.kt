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
