package com.nodeloc.app.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Reply
import com.composables.icons.lucide.Share2
import com.nodeloc.app.R
import com.nodeloc.app.core.design.HairLine
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.RemoteImage
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.VoteControl
import com.nodeloc.app.core.design.pressScale
import com.nodeloc.app.core.design.rememberPressSource
import com.nodeloc.app.core.model.VoteDirection
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.network.NodelocApi
import com.nodeloc.app.core.util.DiscourseFormat
import kotlinx.serialization.Serializable

/*
 * The profile's lists in the backend's own shapes: `/users/{u}/topics` and
 * `/users/{u}/posts`. The Discourse-era facade folded both into a user-action
 * row with no node, no score and no first-post id, which is everything the
 * 2026-11 rows draw and act on.
 *
 * The posts list is the account's whole activity, openers included — a post
 * numbered 1 *is* the topic — so 概览 is that list as it comes, and 评论 is the
 * same list without the openers. One fetch serves both.
 */

@Serializable
data class ApiUserTopicRow(
    val id: Int = 0,
    val title: String? = null,
    val excerpt: String? = null,
    val nodeSlug: String? = null,
    val nodeName: String? = null,
    val replyCount: Int = 0,
    val voteScore: Int = 0,
    val firstPostId: Int? = null,
    /** 1, -1 or 0: how the reader voted on the opener. */
    val viewerVote: Int = 0,
    val imageUrl: String? = null,
    val adult: Boolean = false,
    val createdAt: String? = null,
)

@Serializable
data class ApiUserTopicPage(val topics: List<ApiUserTopicRow> = emptyList(), val nextCursor: String? = null)

@Serializable
data class ApiUserPostRow(
    val id: Int = 0,
    val topicId: Int = 0,
    val postNumber: Int = 0,
    val topicTitle: String? = null,
    val nodeSlug: String? = null,
    val nodeName: String? = null,
    val excerpt: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class ApiUserPostPage(val posts: List<ApiUserPostRow> = emptyList(), val nextCursor: String? = null)

class ProfileActivityApi(private val api: NodelocApi) {
    suspend fun topics(username: String, cursor: String?): ApiUserTopicPage =
        api.get("api/v1/users/${username.enc()}/topics", page(cursor))

    suspend fun posts(username: String, cursor: String?): ApiUserPostPage =
        api.get("api/v1/users/${username.enc()}/posts", page(cursor))

    private fun page(cursor: String?) = buildList {
        add("limit" to PAGE.toString())
        cursor?.takeIf { it.isNotEmpty() }?.let { add("cursor" to it) }
    }

    private fun String.enc(): String = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    companion object {
        const val PAGE = 30
    }
}

/** The reader's own vote on an opener, as last confirmed or optimistically set. */
data class TopicVote(val direction: VoteDirection, val score: Int)

fun voteOf(value: Int): VoteDirection = when {
    value > 0 -> VoteDirection.Up
    value < 0 -> VoteDirection.Down
    else -> VoteDirection.None
}

// ------------------------------------------------------------------ rows

/** A node's mark: its first letter on the green, as the board draws it before an icon is known. */
@Composable
private fun NodeMark(name: String?, size: Int) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Nocturne.accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name?.trim()?.take(1).orEmpty(),
            style = Type.body(if (size > 20) 12 else 10, FontWeight.Bold),
            color = Nocturne.neutral100,
            maxLines = 1,
        )
    }
}

/**
 * A topic the person started: picture or placeholder, node and age, title,
 * then the actions — vote, the comment count, share, save. Without a summary
 * (an opener met in 概览 before 帖子 was loaded) the vote and the count have
 * nothing true to show and are left off; share and save need only the post.
 */
@Composable
fun TopicActivityRow(
    title: String,
    nodeSlug: String?,
    nodeName: String?,
    createdAt: String?,
    imageUrl: String?,
    vote: TopicVote?,
    replyCount: Int?,
    saved: Boolean,
    onOpen: () -> Unit,
    onNode: (() -> Unit)?,
    onVote: ((VoteDirection) -> Unit)?,
    onShare: () -> Unit,
    onSave: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.padding(top = 2.dp).size(width = 72.dp, height = 54.dp).clip(RoundedCornerShape(12.dp)).background(Nocturne.neutral400),
            contentAlignment = Alignment.Center,
        ) {
            if (imageUrl != null) {
                RemoteImage(DiscourseConfig.absoluteUrl(imageUrl), Modifier.size(width = 72.dp, height = 54.dp), contentScale = ContentScale.Crop)
            } else {
                Icon(Lucide.FileText, null, tint = Nocturne.textSecondary, modifier = Modifier.size(22.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (nodeSlug != null) {
                    Row(
                        Modifier.clip(RoundedCornerShape(6.dp)).then(
                            if (onNode != null) Modifier.clickable(role = Role.Button, onClick = onNode) else Modifier,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        NodeMark(nodeName ?: nodeSlug, 18)
                        Text("n/$nodeSlug", style = Type.body(12, FontWeight.Bold), color = Nocturne.text, maxLines = 1)
                    }
                    Text("·", style = Type.body(12), color = Nocturne.textSecondary)
                }
                Text(DiscourseFormat.relative(createdAt), style = Type.body(12), color = Nocturne.textSecondary, maxLines = 1)
            }
            Text(
                title,
                style = Type.body(16, FontWeight.Bold),
                color = Nocturne.text,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (vote != null && onVote != null) {
                    VoteControl(direction = vote.direction, score = vote.score, canVoteDown = true, onVote = onVote)
                }
                if (replyCount != null) {
                    TextAction(stringResource(R.string.me3_comment_count, replyCount), null, onOpen)
                }
                TextAction(stringResource(R.string.common_share), null, onShare)
                if (onSave != null) {
                    TextAction(
                        stringResource(if (saved) R.string.me3_saved else R.string.me3_save),
                        null,
                        onSave,
                        strong = saved,
                    )
                }
            }
        }
    }
    HairLine()
}

/**
 * A comment: where it was left, who left it and when, what it said, and the
 * ways on — reply in the thread, or share it. "楼主" when the author is known
 * to have started that topic; nothing when it is not known, rather than a guess.
 */
@Composable
fun CommentActivityRow(
    author: String,
    isOp: Boolean,
    nodeSlug: String?,
    nodeName: String?,
    topicTitle: String?,
    createdAt: String?,
    excerpt: String?,
    onOpen: () -> Unit,
    onNode: (() -> Unit)?,
    onShare: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NodeMark(nodeName ?: nodeSlug, 24)
        Column(Modifier.weight(1f)) {
            val where = buildAnnotatedString {
                nodeSlug?.let {
                    withStyle(SpanStyle(color = Nocturne.text, fontWeight = FontWeight.Bold)) { append("n/$it") }
                    append(" · ")
                }
                withStyle(SpanStyle(color = Nocturne.muted(0.82f))) { append(topicTitle.orEmpty()) }
            }
            Text(
                where,
                style = Type.body(13),
                color = Nocturne.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (onNode != null) Modifier.clickable(role = Role.Button, onClick = onNode) else Modifier,
            )
            val opLabel = stringResource(R.string.me3_op)
            val commented = stringResource(R.string.me3_commented)
            val byline = buildAnnotatedString {
                withStyle(SpanStyle(color = Nocturne.text, fontWeight = FontWeight.Bold)) { append(author) }
                if (isOp) {
                    append(" ")
                    withStyle(SpanStyle(color = Nocturne.accent700, fontWeight = FontWeight.Bold)) {
                        append(opLabel)
                    }
                }
                append(" ")
                append(commented)
                append(" ")
                append(DiscourseFormat.relative(createdAt))
            }
            Text(byline, style = Type.body(13), color = Nocturne.textSecondary, modifier = Modifier.padding(top = 4.dp))
            excerpt?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = Type.body(15),
                    color = Nocturne.text,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                TextAction(stringResource(R.string.me3_reply), Lucide.Reply, onOpen)
                TextAction(stringResource(R.string.common_share), Lucide.Share2, onShare)
            }
        }
    }
    HairLine()
}

/** The rows' quiet text buttons: 44 high to be hit, drawn as just their words. */
@Composable
private fun TextAction(label: String, icon: ImageVector?, onClick: () -> Unit, strong: Boolean = false) {
    val press = rememberPressSource()
    Row(
        Modifier
            .pressScale(press)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(interactionSource = press, indication = ripple(), role = Role.Button, onClick = onClick)
            .padding(horizontal = if (icon != null) 10.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { Icon(it, null, tint = Nocturne.textSecondary, modifier = Modifier.size(16.dp)) }
        Text(
            label,
            style = Type.body(13, FontWeight.Medium),
            color = if (strong) Nocturne.accent else Nocturne.textSecondary,
            maxLines = 1,
        )
    }
}

/** "这里的内容仅本人可见" — someone else's energy and saves, which the server keeps to its owner. */
@Composable
fun PrivateListState(username: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Lucide.Lock, null, tint = Nocturne.textTertiary, modifier = Modifier.size(28.dp).padding(bottom = 4.dp))
        Text(stringResource(R.string.me3_private_title), style = Type.body(16, FontWeight.Bold), color = Nocturne.text)
        Text(stringResource(R.string.me3_private_detail, username), style = Type.body(14), color = Nocturne.textSecondary)
    }
}

/** An empty tab, in the same quiet voice as the private one. */
@Composable
fun ActivityEmpty(text: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Lucide.MessageSquare, null, tint = Nocturne.textTertiary, modifier = Modifier.size(28.dp))
        Text(text, style = Type.body(14), color = Nocturne.textSecondary)
    }
}
