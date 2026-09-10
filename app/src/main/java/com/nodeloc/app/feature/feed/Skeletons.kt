package com.nodeloc.app.feature.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nodeloc.app.core.design.HairLine
import com.nodeloc.app.core.design.SkeletonBox
import com.nodeloc.app.core.design.SkeletonLine
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.skeletonPulsing
import com.nodeloc.app.core.model.NodeReadingMode

/**
 * First-load placeholders shaped like whatever mode is active — 3 cards,
 * 10 compact rows, or 5 expanded rows.
 *
 * The pulse is attached **once**, on the container: per-row pulses drift out of
 * phase and read as flicker rather than loading.
 */
@Composable
fun FeedSkeleton(mode: NodeReadingMode, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().skeletonPulsing()) {
        when (mode) {
            NodeReadingMode.Card -> repeat(3) { CardSkeleton() }
            NodeReadingMode.Compact -> repeat(10) { CompactSkeleton() }
            NodeReadingMode.Expand -> repeat(5) { ExpandedSkeleton() }
        }
    }
}

@Composable
private fun CompactSkeleton() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        SkeletonBox(Modifier.size(30.dp), corner = 15.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SkeletonLine(widthFraction = 0.86f)
            SkeletonLine(widthFraction = 0.42f, height = 10.dp)
        }
    }
}

@Composable
private fun ExpandedSkeleton() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            SkeletonBox(Modifier.size(26.dp), corner = 13.dp)
            SkeletonLine(Modifier.width(120.dp), height = 10.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SkeletonLine(widthFraction = 0.94f)
                SkeletonLine(widthFraction = 0.6f)
            }
            SkeletonBox(Modifier.size(78.dp))
        }
    }
}

@Composable
private fun CardSkeleton() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.card, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            SkeletonBox(Modifier.size(26.dp), corner = 13.dp)
            SkeletonLine(Modifier.width(140.dp), height = 10.dp)
        }
        SkeletonLine(widthFraction = 0.92f)
        SkeletonLine(widthFraction = 0.55f)
        SkeletonBox(Modifier.fillMaxWidth().aspectRatio(1.4f))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s6)) {
            SkeletonLine(Modifier.width(44.dp), height = 10.dp)
            SkeletonLine(Modifier.width(44.dp), height = 10.dp)
        }
    }
    HairLine()
}

/** Generic list skeleton for the inbox, search results and profile tabs. */
@Composable
fun RowsSkeleton(modifier: Modifier = Modifier, rows: Int = 8, avatar: Boolean = true) {
    Column(modifier.fillMaxWidth().skeletonPulsing()) {
        repeat(rows) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(Space.s4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (avatar) SkeletonBox(Modifier.size(38.dp), corner = 19.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkeletonLine(widthFraction = 0.7f, height = 12.dp)
                    SkeletonLine(widthFraction = 0.45f, height = 10.dp)
                }
            }
        }
    }
}

/**
 * The reader's own first-load shape: author, title, a paragraph of body, the
 * action bar, then the first few replies.
 *
 * Shaped rather than generic because the reader opens on one tall post, not a
 * list — [RowsSkeleton] here would promise a feed and deliver an article.
 */
@Composable
fun ReaderSkeleton(modifier: Modifier = Modifier, replies: Int = 4) {
    Column(modifier.fillMaxWidth().skeletonPulsing()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.s4),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBox(Modifier.size(36.dp), corner = 18.dp)
                Column(Modifier.width(140.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkeletonLine(widthFraction = 0.9f, height = 12.dp)
                    SkeletonLine(widthFraction = 0.55f, height = 10.dp)
                }
            }
            SkeletonLine(widthFraction = 0.94f, height = 19.dp)
            SkeletonLine(widthFraction = 0.62f, height = 19.dp)
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                SkeletonLine(widthFraction = 0.99f, height = 12.dp)
                SkeletonLine(widthFraction = 0.96f, height = 12.dp)
                SkeletonLine(widthFraction = 0.98f, height = 12.dp)
                SkeletonLine(widthFraction = 0.5f, height = 12.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s6)) {
                repeat(3) { SkeletonBox(Modifier.size(width = 54.dp, height = 26.dp), corner = 13.dp) }
            }
        }
        HairLine()
        RepliesSkeletonRows(replies)
    }
}

/**
 * The reply half of [ReaderSkeleton], on its own.
 *
 * The reader fetches the topic and its replies as two requests, so the moment
 * the first lands the full-screen skeleton has to come off — and the replies
 * are still a second away. Continuing these underneath the post reads as one
 * load still finishing; a spinner in their place read as a second load
 * starting.
 */
@Composable
fun RepliesSkeleton(modifier: Modifier = Modifier, replies: Int = 4) {
    Column(modifier.fillMaxWidth().skeletonPulsing()) { RepliesSkeletonRows(replies) }
}

@Composable
private fun RepliesSkeletonRows(replies: Int) {
    repeat(replies) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            SkeletonBox(Modifier.size(30.dp), corner = 15.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                SkeletonLine(widthFraction = 0.32f, height = 10.dp)
                SkeletonLine(widthFraction = 0.95f, height = 11.dp)
                SkeletonLine(widthFraction = 0.68f, height = 11.dp)
            }
        }
    }
}
