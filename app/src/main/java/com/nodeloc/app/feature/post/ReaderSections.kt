package com.nodeloc.app.feature.post

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowBigUp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.RefreshCw
import com.nodeloc.app.R
import com.nodeloc.app.core.design.FilterPill
import com.nodeloc.app.core.design.FilterPillRow
import com.nodeloc.app.core.design.HairLine
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.RemoteImage
import com.nodeloc.app.core.design.pressScale
import com.nodeloc.app.core.design.rememberPressSource
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.model.ReplySort
import com.nodeloc.app.core.util.DiscourseFormat

/** "96 条回复 · 按热度▾": the count, and the sort as a menu of its own. */
@Composable
internal fun RepliesHeading(count: Int, sort: ReplySort, onSort: (ReplySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            pluralStringResource(R.plurals.common_replies_count, count, count),
            style = Type.body(15, FontWeight.SemiBold),
            color = Nocturne.text,
            modifier = Modifier.weight(1f),
        )
        Box {
            Row(
                Modifier.clickable { open = true },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(stringResource(sort.labelRes), style = Type.body(13), color = Nocturne.textSecondary)
                Icon(Lucide.ChevronDown, stringResource(R.string.reader_reply_sort), tint = Nocturne.textSecondary, modifier = Modifier.size(14.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                ReplySort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(option.labelRes),
                                color = if (option == sort) Nocturne.accent else Nocturne.text,
                            )
                        },
                        onClick = { open = false; onSort(option) },
                    )
                }
            }
        }
    }
}

/**
 * The accepted reply, lifted above the thread: who answered, a three-line
 * teaser, and the way to its floor. The teaser is plain text on purpose — the
 * full body, pictures and all, is one tap away and would push the thread off
 * the screen here.
 */
@Composable
internal fun BestAnswerCard(
    answer: BestAnswer,
    byOp: Boolean,
    onOpenAuthor: (String) -> Unit,
    onOpen: () -> Unit,
    /** Null unless the reader may withdraw it: the topic's author or a moderator. */
    onWithdraw: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .padding(start = Space.page, end = Space.page, top = 4.dp, bottom = 8.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Nocturne.accent100.copy(alpha = 0.5f))
            .border(1.dp, Nocturne.accent300, shape)
            .padding(Space.card),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier
                    .height(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Nocturne.accent)
                    .padding(start = 8.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Lucide.CircleCheck, null, tint = Nocturne.bg, modifier = Modifier.size(14.dp))
                Text(stringResource(R.string.reader2_best_answer), style = Type.body(12, FontWeight.Bold), color = Nocturne.bg)
            }
            // The board adds "回答者获得 20 声望" here. The +20 is conditional
            // (weekly caps, the asker's standing) and nothing tells a client
            // whether it was paid, so the line says only who accepted it.
            Text(
                stringResource(if (byOp) R.string.reader3_accepted_by_op else R.string.reader3_accepted_by_mod),
                style = Type.body(12),
                color = Nocturne.neutral800,
            )
        }
        Row(
            Modifier.padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RemoteAvatar(
                answer.avatarUrl,
                answer.author.take(1).uppercase(),
                size = 28.dp,
                modifier = Modifier.clickable { onOpenAuthor(answer.author) },
            )
            val votes = answer.votes?.let { stringResource(R.string.reader3_votes, DiscourseFormat.count(it)) }
            val meta = listOfNotNull(answer.time, votes).joinToString(" · ", prefix = "· ")
            Text(answer.author, style = Type.body(12, FontWeight.Bold), color = Nocturne.text, maxLines = 1)
            Text(meta, style = Type.body(12), color = Nocturne.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val teaser = remember(answer.content) { answer.content.excerpt(400) }
        if (teaser.isNotEmpty()) {
            Text(
                teaser,
                style = Type.body(14).copy(lineHeight = Type.body(14).fontSize * 1.65f),
                color = Nocturne.text,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(
            Modifier.padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val press = rememberPressSource()
            Row(
                Modifier
                    .pressScale(press)
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Nocturne.bg)
                    .border(1.dp, Nocturne.accent200, RoundedCornerShape(18.dp))
                    .clickable(interactionSource = press, indication = ripple(), onClick = onOpen)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.reader3_view_floor), style = Type.body(13, FontWeight.Bold), color = Nocturne.text)
                Icon(Lucide.ChevronRight, null, tint = Nocturne.text, modifier = Modifier.size(14.dp))
            }
            onWithdraw?.let {
                Box(
                    Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(onClick = it)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.reader3_withdraw_answer), style = Type.body(13, FontWeight.Bold), color = Nocturne.textSecondary)
                }
            }
        }
    }
}

/**
 * 相关主题. The web's column has three sources; a client can be served two of
 * them (see [ReaderViewModel.relatedTopics]), and with one left the chips go,
 * as they do there.
 */
@Composable
internal fun RelatedTopicsSection(
    state: ReaderState,
    source: RelatedSource,
    onSelect: (RelatedSource) -> Unit,
    onMore: () -> Unit,
    onOpen: (Int) -> Unit,
    onBrowseMore: () -> Unit,
) {
    val list = state.related[source].orEmpty()
    val batches = (list.size + RELATED_BATCH - 1) / RELATED_BATCH
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Box(Modifier.fillMaxWidth().height(8.dp).background(Nocturne.surface))
        Row(
            Modifier.fillMaxWidth().padding(start = Space.page, end = 10.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.reader2_related),
                style = Type.heading(16, FontWeight.Bold),
                color = Nocturne.text,
                modifier = Modifier.weight(1f),
            )
            if (batches > 1) {
                Text(
                    stringResource(R.string.reader3_related_page, state.relatedBatch + 1, batches),
                    style = Type.body(12),
                    color = Nocturne.textTertiary,
                )
                val press = rememberPressSource()
                Row(
                    Modifier
                        .pressScale(press)
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(interactionSource = press, indication = ripple(), onClick = onMore)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Lucide.RefreshCw, null, tint = Nocturne.textSecondary, modifier = Modifier.size(14.dp))
                    Text(stringResource(R.string.reader2_related_more), style = Type.body(13, FontWeight.Bold), color = Nocturne.textSecondary)
                }
            }
        }
        if (state.related.size > 1) {
            FilterPillRow(contentPadding = PaddingValues(start = Space.page, end = Space.page, bottom = 10.dp)) {
                state.related.keys.forEach { option ->
                    FilterPill(
                        text = when (option) {
                            RelatedSource.Node -> "n/${state.nodeSlug}"
                            RelatedSource.Hot -> stringResource(R.string.reader2_related_hot)
                        },
                        selected = option == source,
                        onClick = { onSelect(option) },
                    )
                }
            }
        }
        HairLine()
        list.drop(state.relatedBatch * RELATED_BATCH).take(RELATED_BATCH).forEachIndexed { index, topic ->
            if (index > 0) HairLine()
            RelatedRow(topic, onOpen)
        }
        HairLine()
        Row(
            Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onBrowseMore),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            Text(stringResource(R.string.reader3_related_browse), style = Type.body(13, FontWeight.Bold), color = Nocturne.accent)
            Icon(Lucide.ChevronRight, null, tint = Nocturne.accent, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun RelatedRow(topic: RelatedTopic, onOpen: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(topic.id) }
            .padding(horizontal = Space.page, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RemoteAvatar(
            topic.logoUrl,
            topic.nodeName.take(1).ifEmpty { topic.nodeSlug.take(1).uppercase().ifEmpty { "N" } },
            variant = topic.id % 2,
            size = 32.dp,
            cornerRadius = 10.dp,
            modifier = Modifier.align(Alignment.Top).padding(top = 2.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                topic.title,
                style = Type.body(14, FontWeight.Bold).copy(lineHeight = Type.body(14).fontSize * 1.45f),
                color = Nocturne.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val secondary = Nocturne.textSecondary
                Text(
                    buildAnnotatedString {
                        if (topic.nodeSlug.isNotEmpty()) {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = secondary)) {
                                append("n/${topic.nodeSlug} · ")
                            }
                        }
                        append(listOf(topic.author, topic.time).filter { it.isNotEmpty() }.joinToString(" · "))
                    },
                    style = Type.body(12),
                    color = Nocturne.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(Lucide.ArrowBigUp, stringResource(R.string.reader3_related_votes), tint = Nocturne.textTertiary, modifier = Modifier.size(13.dp))
                    Text(DiscourseFormat.count(topic.votes), style = Type.body(12), color = Nocturne.textTertiary)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(Lucide.MessageCircle, stringResource(R.string.reader3_related_replies), tint = Nocturne.textSecondary, modifier = Modifier.size(13.dp))
                    Text(DiscourseFormat.count(topic.replies), style = Type.body(12, FontWeight.Medium), color = Nocturne.textSecondary)
                }
                if (topic.read) {
                    Text(stringResource(R.string.reader3_related_read), style = Type.body(12), color = Nocturne.textTertiary)
                }
            }
        }
        topic.imageUrl?.let {
            RemoteImage(
                it,
                modifier = Modifier.size(width = 64.dp, height = 48.dp).clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
