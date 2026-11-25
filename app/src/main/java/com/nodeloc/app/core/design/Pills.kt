package com.nodeloc.app.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material3.ripple
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The design's filter chip: a 34-high capsule, ink when selected and pale grey
 * otherwise. Used for the home sorts and every in-page filter (queue, log,
 * achievements, drafts), so they all switch the same way.
 */
@Composable
fun FilterPill(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    count: Int? = null,
    onClick: () -> Unit,
) {
    val ground by animatedColor(if (selected) Nocturne.text else Nocturne.neutral400, "pillGround")
    val ink by animatedColor(if (selected) Nocturne.bg else Nocturne.text, "pillInk")
    val press = rememberPressSource()
    Row(
        modifier
            .pressScale(press)
            .height(34.dp)
            .clip(CircleShape)
            .background(ground)
            .semantics { this.selected = selected }
            .clickable(interactionSource = press, indication = ripple(), role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(text, style = Type.body(14, if (selected) FontWeight.Bold else FontWeight.Medium), color = ink)
        if (count != null && count > 0) {
            Text(count.toString(), style = Type.body(13, FontWeight.Medium), color = ink.copy(alpha = 0.7f))
        }
    }
}

/** A horizontally scrolling row of [FilterPill]s with the page margins. */
@Composable
fun FilterPillRow(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** The tone of a [StatusPill]; each is an ink on its own pale ground. */
enum class PillTone { Neutral, Accent, Warning, Danger, Info }

@Composable
fun PillTone.colors(): Pair<Color, Color> = when (this) {
    PillTone.Neutral -> Nocturne.neutral400 to Nocturne.text
    PillTone.Accent -> Nocturne.accent100 to Nocturne.accent700
    PillTone.Warning -> Nocturne.warningBg to Nocturne.warning
    PillTone.Danger -> Nocturne.dangerBg to Nocturne.danger
    PillTone.Info -> Nocturne.infoBg to Nocturne.info
}

/** Small 22-high label: "置顶", "新用户首帖", "禁言中", "正常". */
@Composable
fun StatusPill(text: String, tone: PillTone = PillTone.Neutral, modifier: Modifier = Modifier) {
    val (ground, ink) = tone.colors()
    Box(
        modifier
            .height(22.dp)
            .clip(CircleShape)
            .background(ground)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.body(12, FontWeight.Bold), color = ink, maxLines = 1)
    }
}

/**
 * The design's count badge — the pending-work number on the menu button, a
 * drawer row or a tab. Orange rather than red: it is work waiting, not an error.
 */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier
            .defaultMinSize(minWidth = 16.dp)
            .height(16.dp)
            .clip(CircleShape)
            .background(Nocturne.danger)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            style = Type.body(11, FontWeight.Medium),
            color = if (Nocturne.isDark) Nocturne.neutral900 else Nocturne.neutral100,
            textAlign = TextAlign.Center,
        )
    }
}
