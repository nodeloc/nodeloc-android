package com.nodeloc.app.feature.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.WifiOff
import com.nodeloc.app.R
import com.nodeloc.app.core.design.HairLine
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.pressScale
import com.nodeloc.app.core.design.rememberPressSource

/**
 * The foot of a paged topic list, in the four states the 2026-11 v3 boards
 * draw: more to come, loading, failed, and the end.
 *
 * The explicit 加载更多 stays beside the scroll trigger: the trigger only fires
 * on a scroll, and a first page short enough to fit the screen never scrolls.
 * The board's 改用分页浏览 is not here — the topic lists page by cursor and
 * carry no total, so there is nothing to number the pages with.
 */
@Composable
fun TopicListFooter(
    count: Int,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    failed: Boolean,
    onLoadMore: () -> Unit,
    onBackToTop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            isLoadingMore -> {
                // Shaped like the rows about to arrive, so the list grows into
                // them rather than jumping when they land.
                FeedSkeleton(com.nodeloc.app.core.model.NodeReadingMode.Card, rows = 2)
                Status(stringResource(R.string.feed3_loading_more), spinning = true)
            }

            failed -> Row(
                Modifier
                    .padding(horizontal = Space.page)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Nocturne.dangerBg)
                    .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Lucide.WifiOff, null, tint = Nocturne.danger, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(R.string.feed3_load_failed),
                    style = Type.body(14),
                    color = Nocturne.danger,
                    modifier = Modifier.weight(1f, fill = false),
                )
                val press = rememberPressSource()
                Box(
                    Modifier
                        .pressScale(press)
                        .height(34.dp)
                        .clip(CircleShape)
                        .background(Nocturne.danger)
                        .clickable(interactionSource = press, indication = ripple(), onClick = onLoadMore)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.common_retry), style = Type.body(13, FontWeight.Bold), color = Nocturne.bg)
                }
            }

            hasMore -> {
                Status(stringResource(R.string.feed3_auto_load), spinning = false)
                OutlinePill(stringResource(R.string.feed3_load_more), icon = null, onClick = onLoadMore)
            }

            count > 0 -> {
                Row(
                    Modifier.width(260.dp).padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HairLine(Modifier.weight(1f))
                    Text(stringResource(R.string.feed3_end, count), style = Type.body(13), color = Nocturne.textTertiary)
                    HairLine(Modifier.weight(1f))
                }
                OutlinePill(stringResource(R.string.feed3_back_to_top), icon = Lucide.ArrowUp, onClick = onBackToTop)
            }
        }
    }
}

@Composable
private fun Status(text: String, spinning: Boolean) {
    Row(
        Modifier.heightIn(min = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (spinning) {
            CircularProgressIndicator(Modifier.size(14.dp), color = Nocturne.accent, strokeWidth = 2.dp)
        }
        Text(text, style = Type.body(13), color = Nocturne.textSecondary)
    }
}

@Composable
private fun OutlinePill(text: String, icon: ImageVector?, onClick: () -> Unit) {
    val press = rememberPressSource()
    Row(
        Modifier
            .pressScale(press)
            .height(40.dp)
            .clip(CircleShape)
            .border(1.dp, Nocturne.divider, CircleShape)
            .background(Nocturne.bg)
            .clickable(interactionSource = press, indication = ripple(), onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { Icon(it, null, tint = Nocturne.text, modifier = Modifier.size(16.dp)) }
        Text(text, style = Type.body(14, FontWeight.Bold), color = Nocturne.text)
    }
}
