package com.nodeloc.app.feature.post

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.nodeloc.app.R
import com.nodeloc.app.core.design.FilterPill
import com.nodeloc.app.core.design.FilterPillRow
import com.nodeloc.app.core.design.HairLine
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.html.PostContentView
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

/** The accepted reply, lifted above the thread on a green ground. Tapping it goes to it. */
@Composable
internal fun BestAnswerCard(
    answer: BestAnswer,
    byOp: Boolean,
    onOpenAuthor: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onOpen: () -> Unit,
) {
    Column(
        Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Nocturne.accent100)
            .clickable(onClick = onOpen)
            .padding(horizontal = Space.card, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Lucide.CircleCheck, null, tint = Nocturne.accent700, modifier = Modifier.size(15.dp))
            Text(stringResource(R.string.reader2_best_answer), style = Type.body(13, FontWeight.Bold), color = Nocturne.accent700)
            Text(
                stringResource(if (byOp) R.string.reader2_accepted_by_op else R.string.reader2_accepted_by_mod),
                style = Type.body(13),
                color = Nocturne.textSecondary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteAvatar(
                answer.avatarUrl,
                answer.author.take(1).uppercase(),
                size = 24.dp,
                modifier = Modifier.clickable { onOpenAuthor(answer.author) },
            )
            Text(answer.author, style = Type.body(13, FontWeight.Bold), color = Nocturne.text, maxLines = 1)
            Text("· ${answer.time}", style = Type.body(13), color = Nocturne.textSecondary, maxLines = 1)
        }
        PostContentView(
            content = answer.content,
            baseSize = 15,
            lineExtra = 7,
            onOpenLink = onOpenLink,
            onOpenMention = onOpenAuthor,
        )
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
) {
    val list = state.related[source].orEmpty()
    val batches = (list.size + RELATED_BATCH - 1) / RELATED_BATCH
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(Space.s3).background(Nocturne.surface))
        Row(
            Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.reader2_related),
                style = Type.heading(17, FontWeight.SemiBold),
                color = Nocturne.text,
                modifier = Modifier.weight(1f),
            )
            if (batches > 1) {
                Row(
                    Modifier.clickable(onClick = onMore),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(Lucide.RefreshCw, null, tint = Nocturne.textSecondary, modifier = Modifier.size(14.dp))
                    Text(stringResource(R.string.reader2_related_more), style = Type.body(13, FontWeight.SemiBold), color = Nocturne.textSecondary)
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
        list.drop(state.relatedBatch * RELATED_BATCH).take(RELATED_BATCH).forEach { topic ->
            HairLine()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(topic.id) }
                    .padding(horizontal = Space.page, vertical = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RemoteAvatar(topic.logoUrl, topic.nodeName.take(1).ifEmpty { "N" }, variant = topic.id % 2, size = 32.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        topic.title,
                        style = Type.body(15, FontWeight.SemiBold),
                        color = Nocturne.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        stringResource(
                            R.string.reader2_related_meta,
                            topic.author,
                            topic.time,
                            DiscourseFormat.count(topic.votes),
                            DiscourseFormat.count(topic.replies),
                        ),
                        style = Type.body(12),
                        color = Nocturne.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
