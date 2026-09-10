package com.nodeloc.app.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowBigDown
import com.composables.icons.lucide.ArrowBigUp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.model.VoteDirection
import com.nodeloc.app.core.model.VoteFace
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.util.runCatchingCancellable

/**
 * A post the site has folded for its score, and the way back to it.
 *
 * The same shape the plugin's stylesheet makes on the web: the body clipped to
 * a few lines, dimmed, fading into the page, with one control to show the rest.
 * Hiding it outright would be a different decision — the site's is that a badly
 * received post is still there to be read, just not in the way.
 *
 * Folding is undone for this reading of the thread only. It is a display rule
 * about the score, not a preference, and it goes back on the next time the
 * thread is opened — which is also what the web does.
 */
@Composable
fun LowScoreFold(
    maxHeight: Dp,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier) {
        Box(Modifier.heightIn(max = maxHeight).clipToBounds()) {
            Box(Modifier.alpha(0.55f)) { content() }
            // Anchored to the bottom of what is *shown*, not of the content, so
            // the fade lands on the cut rather than somewhere past it.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(maxHeight / 2)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Nocturne.bg))),
            )
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(Radius.md))
                .clickable(onClick = onExpand)
                .padding(vertical = 4.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Lucide.ChevronDown,
                null,
                tint = Nocturne.muted(0.5f),
                modifier = Modifier.size(14.dp),
            )
            Text(
                stringResource(R.string.vote_expand),
                style = Type.body(12, FontWeight.Medium),
                color = Nocturne.muted(0.5f),
            )
        }
    }
}

/**
 * Up, score, down — the control discourse-vote puts where the like was.
 *
 * Horizontal rather than the web's vertical stack: every row this replaces is
 * a line of icons with counts beside them, and a two-storey control in the
 * middle of one would set the row's height on its own.
 *
 * The score is shown even at zero. A number that appears only once somebody
 * votes reads as an error the first time it turns up, and the count beside a
 * like never behaved that way because there was no down half to net against.
 *
 * [canVoteDown] is the server's answer, not a guess: downvoting is limited to
 * groups the site names, and the app has no way to work out which. It dims the
 * arrow but does not disable it — the tap is what gets the rule said out loud,
 * and a signed-out reader needs it to reach the login gate.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VoteControl(
    direction: VoteDirection,
    score: Int,
    canVoteDown: Boolean,
    onVote: (VoteDirection) -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp,
    textSize: Int = 13,
    /**
     * Casts a named face. Null leaves the arrows tap-only — the picker is a
     * signed-in affordance, and a list that cannot vote has nothing to pick.
     */
    onPickFace: ((VoteDirection, String) -> Unit)? = null,
    /**
     * The rounded ground behind the arrows. Off in a reply row, where the
     * actions are a margin note on somebody else's sentence rather than a bar
     * of buttons under a post.
     */
    ground: Boolean = true,
) {
    // Which arrow is showing its faces, if either. Held here rather than
    // hoisted: it is where a finger is, not something a screen needs to know.
    var picking by remember { mutableStateOf<VoteDirection?>(null) }

    // White over a clip, the body text colour on a page — see [ActionPillStyle].
    val style = LocalActionPillStyle.current
    val idle = style?.icon ?: Nocturne.muted(0.45f)
    val barred = style?.icon?.copy(alpha = 0.35f) ?: Nocturne.muted(0.2f)
    val plain = style?.label ?: Nocturne.muted(0.55f)

    // One ground for the three of them, not three: up, score and down are a
    // single control that happens to have a number in the middle, and pilling
    // each separately would say they were three buttons.
    val arrows: @Composable RowScope.() -> Unit = {
        VoteArrow(
            icon = Lucide.ArrowBigUp,
            label = stringResource(
                if (direction == VoteDirection.Up) R.string.vote_undo_upvote else R.string.vote_upvote,
            ),
            tint = if (direction == VoteDirection.Up) Nocturne.love else idle,
            iconSize = iconSize,
            onClick = { onVote(VoteDirection.Up) },
            onLongClick = onPickFace?.let { { picking = VoteDirection.Up } },
        )
        Text(
            "$score",
            style = Type.body(textSize, FontWeight.Medium),
            color = when (direction) {
                VoteDirection.Up -> Nocturne.love
                VoteDirection.Down -> Nocturne.accent2
                VoteDirection.None -> plain
            },
        )
        // Dimmed and inert rather than hidden: a control that is up on one post
        // and gone on the next reads as a bug, and the rule — which groups may
        // downvote — is the same for every post on the page.
        VoteArrow(
            icon = Lucide.ArrowBigDown,
            label = stringResource(
                if (direction == VoteDirection.Down) R.string.vote_undo_downvote else R.string.vote_downvote,
            ),
            tint = if (direction == VoteDirection.Down) Nocturne.accent2 else if (canVoteDown) idle else barred,
            iconSize = iconSize,
            onClick = { onVote(VoteDirection.Down) },
            onLongClick = onPickFace?.let { { picking = VoteDirection.Down } },
        )
    }

    if (ground) {
        ActionPill(modifier = modifier, horizontalPadding = 5.dp, content = arrows)
    } else {
        Row(
            modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            content = arrows,
        )
    }

    picking?.let { side ->
        VoteFacePicker(
            direction = side,
            onPick = { face ->
                picking = null
                onPickFace?.invoke(side, face)
            },
            onDismiss = { picking = null },
        )
    }
}

/**
 * One arrow: the glyph, and something big enough to hit it by.
 *
 * The padding is the point: the tap area was the glyph itself, and at 16dp
 * that is a third of the 48dp a finger is reckoned to need — two of them a few
 * dp apart is how a downvote gets cast by someone aiming at the score.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoteArrow(
    icon: ImageVector,
    label: String,
    tint: Color,
    iconSize: Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Box(
        Modifier
            .clip(CircleShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/**
 * The faces one arrow offers.
 *
 * A sheet rather than the web's hover popover: a popover anchored to a 16dp
 * glyph on a phone is a row of targets too small to hit, and the gesture that
 * opens this one is a hold — the finger is already committed to a second step.
 *
 * Picking a face always *casts* that direction; only the bare arrow toggles a
 * vote back off. Choosing a face to un-vote with would be a contradiction.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoteFacePicker(
    direction: VoteDirection,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var faces by remember(direction) { mutableStateOf<List<VoteFace>>(emptyList()) }
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(direction) {
        faces = runCatchingCancellable { ServiceLocator.get.siteRepository.voteFaces(direction) }
            .getOrDefault(emptyList())
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Nocturne.bg) {
        Text(
            stringResource(
                if (direction == VoteDirection.Up) R.string.vote_pick_upvote else R.string.vote_pick_downvote,
            ),
            style = Type.body(13, FontWeight.SemiBold),
            color = Nocturne.muted(0.6f),
            modifier = Modifier.padding(horizontal = Space.page).padding(bottom = Space.s2),
        )
        if (faces.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                NodelocLoader(height = 20.dp, tint = Nocturne.accent)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(56.dp),
                contentPadding = PaddingValues(Space.page),
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
                verticalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                items(faces, key = { it.name }) { face ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(Radius.md))
                            .clickable { onPick(face.name) }
                            .padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        RemoteImage(
                            DiscourseConfig.absoluteUrl(face.url),
                            modifier = Modifier.size(30.dp),
                            contentScale = ContentScale.Fit,
                            contentDescription = face.name,
                            placeholder = false,
                        )
                    }
                }
            }
        }
    }
}
