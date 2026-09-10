package com.nodeloc.app.feature.node

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nodeloc.app.R
import com.nodeloc.app.core.design.Avatar
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Radius
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.model.NodeSummary
import com.nodeloc.app.core.util.DiscourseFormat

/**
 * One node, as a card: logo, handle and name, its numbers, and its description
 * along the foot. Shared by the browse shelves and the per-group page so both
 * say the same things in the same order.
 */
@Composable
fun NodeCard(
    node: NodeSummary,
    isSignedIn: Boolean,
    onToggleJoin: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .height(NODE_CARD_HEIGHT)
            .clip(RoundedCornerShape(Radius.md))
            .background(Nocturne.surface)
            .clickable(onClick = onClick)
            .padding(Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (node.logoUrl != null) {
                RemoteAvatar(node.logoUrl, node.letter, size = 38.dp)
            } else {
                Avatar(node.letter, variant = node.id % 2, size = 38.dp)
            }
            Spacer(Modifier.width(Space.s3))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    // The handle is what the node is called in a link and in
                    // search; the display name is what it is called in prose.
                    Text(
                        node.handle,
                        style = Type.body(12, FontWeight.Medium),
                        color = Nocturne.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        node.name,
                        style = Type.body(14, FontWeight.Medium),
                        color = Nocturne.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    listOfNotNull(
                        node.memberCount?.let { pluralStringResource(R.plurals.common_members, it, DiscourseFormat.count(it)) },
                        node.topicCount?.let { pluralStringResource(R.plurals.common_topics, it, DiscourseFormat.count(it)) },
                    ).joinToString(" · "),
                    style = Type.body(11),
                    color = Nocturne.muted(0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(Space.s2))
            JoinPill(joined = node.isJoined && isSignedIn, onClick = onToggleJoin)
        }

        // Pushed to the foot rather than following the stats: a shelf of
        // cards with the description at a different height each is a ragged
        // block, and the height is fixed anyway.
        Spacer(Modifier.weight(1f))
        node.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = Type.body(12),
                color = Nocturne.muted(0.55f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Membership, as a pill: joined reads as a state, not as an offer. */
@Composable
fun JoinPill(joined: Boolean, onClick: () -> Unit) {
    Text(
        stringResource(if (joined) R.string.node_joined else R.string.node_join),
        style = Type.body(12, FontWeight.Medium),
        color = if (joined) Nocturne.muted(0.5f) else Nocturne.accent,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (joined) Nocturne.bg else Nocturne.selected)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/**
 * Fixed, and shared with the skeleton. A shelf is a grid: rows only line up if
 * every card is the same height whatever its description runs to.
 */
val NODE_CARD_HEIGHT = 118.dp
