package com.nodeloc.app.feature.post

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nodeloc.app.core.design.NodelocIcons
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.Radius
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.RemoteImage
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.model.PostReaction
import com.nodeloc.app.core.model.ReactionUserGroup
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.util.runCatchingCancellable

/** Three faces is what fits beside a count without becoming a second row. */
private const val FACES_SHOWN = 3

/** A 20dp face plus the padding around it; every chip is held to it. */
private val FACE_CHIP_HEIGHT = 32.dp

/**
 * The faces a post carries, and how many people put them there.
 *
 * The arrows a few icons to the left net the same reactions down to one number,
 * which is what a score is for. This says the other half: not how the post did,
 * but what people actually said — and those are different enough to be worth
 * both.
 */
@Composable
fun ReactionSummary(
    reactions: List<PostReaction>,
    total: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (reactions.isEmpty() || total <= 0) return
    var urls by remember(reactions) { mutableStateOf<List<String>>(emptyList()) }

    // Ordered by the server, so the three shown are the three most used.
    val shown = remember(reactions) { reactions.take(FACES_SHOWN) }

    LaunchedEffect(shown) {
        val site = ServiceLocator.get.siteRepository
        urls = runCatchingCancellable { shown.mapNotNull { site.emojiUrl(it.id) } }
            .getOrDefault(emptyList())
    }

    Row(
        modifier
            .clip(RoundedCornerShape(Radius.md))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        urls.forEach { url ->
            RemoteImage(
                DiscourseConfig.absoluteUrl(url),
                modifier = Modifier.size(17.dp),
                contentScale = ContentScale.Fit,
                placeholder = false,
            )
        }
        Text(
            "$total",
            style = Type.body(13, FontWeight.Medium),
            color = Nocturne.muted(0.55f),
        )
    }
}

/**
 * What has already happened to a post: the faces it drew, and what it was
 * tipped.
 *
 * A line of its own above the buttons, because none of it is a button. Sat
 * among the actions, a tally reads as a fourth thing to press and the row stops
 * being a row of verbs; on its own line the two questions separate — what
 * people did, then what you can do.
 *
 * Draws nothing when there is neither, which is most posts. A reply uses only
 * half of this — its faces sit in the action row, where there is room for them
 * beside four icons but not above them.
 */
@Composable
fun PostRecordLine(
    reactions: List<PostReaction>,
    reactionCount: Int,
    rewardTotal: Int,
    onReactionDetail: () -> Unit,
    onRewardDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasReactions = reactions.isNotEmpty() && reactionCount > 0
    if (!hasReactions && rewardTotal <= 0) return

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (hasReactions) {
            ReactionSummary(
                reactions = reactions,
                total = reactionCount,
                onClick = onReactionDetail,
            )
        }
        RewardSummary(total = rewardTotal, onClick = onRewardDetail)
    }
}

/** What a post has been tipped, and the way in to who tipped it. */
@Composable
fun RewardSummary(
    total: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (total <= 0) return
    Row(
        modifier
            .clip(RoundedCornerShape(Radius.md))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(NodelocIcons.Zap, null, tint = Nocturne.accent2, modifier = Modifier.size(15.dp))
        Text("$total", style = Type.body(13, FontWeight.Medium), color = Nocturne.accent2)
    }
}

/**
 * Who reacted, grouped by face.
 *
 * Fetched rather than carried on the post: the payload says how many chose each
 * face, never which people, and the whole point of opening this is the names.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactionDetailSheet(
    postId: Int,
    onOpenProfile: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var groups by remember(postId) { mutableStateOf<List<ReactionUserGroup>?>(null) }
    var faceUrls by remember(postId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(postId) {
        val services = ServiceLocator.get
        val fetched = runCatchingCancellable { services.client.postReactionUsers(postId) }
            .getOrNull()
            ?.reactionUsers
            .orEmpty()
        faceUrls = fetched.mapNotNull { group ->
            services.siteRepository.emojiUrl(group.id)?.let { group.id to it }
        }.toMap()
        groups = fetched
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Nocturne.bg) {
        val loaded = groups
        when {
            loaded == null -> Box(Modifier.fillMaxWidth().height(160.dp), Alignment.Center) {
                NodelocLoader(height = 20.dp, tint = Nocturne.accent)
            }

            loaded.isEmpty() -> Text(
                stringResource(R.string.reaction_none),
                style = Type.body(14),
                color = Nocturne.muted(0.45f),
                modifier = Modifier.padding(horizontal = Space.page, vertical = 24.dp),
            )

            else -> {
                // Filter, not sections. A face is the interesting axis — who
                // clapped, who was appalled — and headings buried in a scroll
                // make you hunt for it; a chip row keeps every face and its
                // tally in sight, and one tap narrows to it.
                var selected by remember(loaded) { mutableStateOf<String?>(null) }
                val rows = remember(loaded, selected) {
                    loaded
                        .filter { selected == null || it.id == selected }
                        .flatMap { group -> group.users.map { it to group.id } }
                }

                LazyRow(
                    Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = Space.page),
                    horizontalArrangement = Arrangement.spacedBy(Space.s2),
                ) {
                    item(key = "all") {
                        FaceChip(
                            selected = selected == null,
                            count = loaded.sumOf { it.count },
                            onClick = { selected = null },
                        ) {
                            Text(
                                stringResource(R.string.reaction_all),
                                style = Type.body(13, FontWeight.SemiBold),
                                color = Nocturne.text,
                            )
                        }
                    }
                    items(loaded, key = { it.id }) { group ->
                        FaceChip(
                            selected = selected == group.id,
                            count = group.count,
                            onClick = { selected = group.id },
                        ) {
                            FaceImage(faceUrls[group.id], 20.dp)
                        }
                    }
                }

                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f, fill = false),
                    contentPadding = PaddingValues(top = Space.s3, bottom = 24.dp),
                ) {
                    items(rows, key = { (user, face) -> "$face-${user.username}" }) { (user, face) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenProfile(user.username) }
                                .padding(horizontal = Space.page, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space.s3),
                        ) {
                            RemoteAvatar(
                                DiscourseConfig.avatarUrl(user.avatarTemplate, 120),
                                user.username.take(1).uppercase(),
                                size = 38.dp,
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                val name = user.name?.takeIf { it.isNotBlank() }
                                Text(
                                    name ?: user.username,
                                    style = Type.body(15, FontWeight.SemiBold),
                                    color = Nocturne.text,
                                )
                                // Only where the name did not already say it.
                                // Most accounts here have no display name, and
                                // a handle repeated under itself is noise.
                                if (name != null) {
                                    Text(
                                        "@${user.username}",
                                        style = Type.body(13),
                                        color = Nocturne.muted(0.45f),
                                    )
                                }
                            }
                            // Which face this person chose, kept on every row
                            // even under a filter: it is what the row is about.
                            FaceImage(faceUrls[face], 22.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FaceChip(
    selected: Boolean,
    count: Int,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        Modifier
            // Fixed, not wrapped: "All" carries a word where the others carry a
            // 20dp face, and a chip row that sizes to its contents leaves the
            // one without a picture visibly shorter than its neighbours.
            .height(FACE_CHIP_HEIGHT)
            .clip(CircleShape)
            .background(if (selected) Nocturne.accent100 else Nocturne.surface)
            .then(
                if (selected) Modifier.border(1.dp, Nocturne.accent, CircleShape)
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        content()
        Text(
            "$count",
            style = Type.body(13, FontWeight.Medium),
            color = Nocturne.muted(0.6f),
        )
    }
}

@Composable
private fun FaceImage(url: String?, size: Dp) {
    RemoteImage(
        DiscourseConfig.absoluteUrl(url),
        modifier = Modifier.size(size),
        contentScale = ContentScale.Fit,
        placeholder = false,
    )
}
