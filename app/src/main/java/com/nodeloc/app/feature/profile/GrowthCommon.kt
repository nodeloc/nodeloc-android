package com.nodeloc.app.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Award
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.Flag
import com.composables.icons.lucide.Flame
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Pen
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.User
import com.composables.icons.lucide.Zap
import com.nodeloc.app.R
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.util.runCatchingCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.NumberFormat

/**
 * The tier directory, fetched once per process.
 *
 * Public, localised, and configured server-side — nothing a screen should ask
 * for every time it opens. A failure is not cached, so the next screen retries.
 */
object TierDirectory {
    private val lock = Mutex()
    private var cached: List<ApiTierInfo>? = null

    suspend fun get(): List<ApiTierInfo> = lock.withLock {
        cached ?: runCatchingCancellable { GrowthApi.shared.tiers() }.getOrNull()
            ?.also { cached = it }
            .orEmpty()
    }
}

fun List<ApiTierInfo>.level(level: Int?): ApiTierInfo? = level?.let { l -> firstOrNull { it.level == l } }

/** Grouped digits in the reader's locale: balances run to six figures. */
fun formatCount(value: Long): String = NumberFormat.getIntegerInstance().format(value)

/** A true minus sign, not a hyphen, as the design draws debits. */
fun formatSigned(value: Long): String =
    if (value >= 0) "+${formatCount(value)}" else "−${formatCount(-value)}"

/** The membership tier as the site paints it, falling back to the neutral pill. */
@Composable
fun TierPill(name: String, info: ApiTierInfo?, modifier: Modifier = Modifier) {
    val (ground, ink) = info?.colors(Nocturne.isDark) ?: (Nocturne.infoBg to Nocturne.info)
    Box(
        modifier
            .height(20.dp)
            .clip(CircleShape)
            .background(ground)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(name, style = Type.body(11, FontWeight.Bold), color = ink, maxLines = 1)
    }
}

/**
 * An achievement's tier (铜 / 银 / 金 / 稀有) as a ground and an ink.
 *
 * The design's metal colours are not in the palette; these are the nearest
 * tokens that keep the four apart — warm, grey, gold, cool — in both themes.
 * Locked ones are all the same grey, so colour is something you earn.
 */
@Composable
fun achTierColors(tier: Int, unlocked: Boolean = true): Pair<Color, Color> = when {
    !unlocked -> Nocturne.neutral300 to Nocturne.textTertiary
    tier == 1 -> Nocturne.dangerBg to Nocturne.danger
    tier == 2 -> Nocturne.neutral300 to Nocturne.textSecondary
    tier == 3 -> Nocturne.warningBg to Nocturne.warning
    else -> Nocturne.infoBg to Nocturne.info
}

@Composable
fun achTierLabel(tier: Int): String = stringResource(
    when (tier) {
        1 -> R.string.ach_tier_bronze
        2 -> R.string.ach_tier_silver
        3 -> R.string.ach_tier_gold
        else -> R.string.ach_tier_rare
    },
)

/** The catalogue's icon names (achievement/catalog.go) as glyphs. */
fun achievementIcon(name: String?): ImageVector = when (name) {
    "pen" -> Lucide.Pen
    "bolt" -> Lucide.Zap
    "book" -> Lucide.BookOpen
    "chat" -> Lucide.MessageCircle
    "flag" -> Lucide.Flag
    "flame" -> Lucide.Flame
    "heart" -> Lucide.Heart
    "node" -> Lucide.LayoutGrid
    "star" -> Lucide.Star
    "up" -> Lucide.ArrowUp
    "user" -> Lucide.User
    else -> Lucide.Award
}

/** "发帖  3 / 4": a group heading on the grey pages, label left, figure right. */
@Composable
fun GrowthSectionHead(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = Type.body(13, FontWeight.SemiBold),
            color = Nocturne.textSecondary,
            modifier = Modifier.weight(1f),
        )
        trailing?.let { Text(it, style = Type.body(13, FontWeight.SemiBold), color = Nocturne.accent) }
    }
}

/** The white card the grey growth pages are built from. */
@Composable
fun GrowthCard(
    modifier: Modifier = Modifier,
    radius: Dp = 18.dp,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(radius))
            .background(Nocturne.bg)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** A flat, rounded bar; the Material indicator's gap and stop dot read as a slider here. */
@Composable
fun GrowthBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    track: Color = Nocturne.neutral400,
    fill: Color = Nocturne.accent,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(track),
    ) {
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(height / 2))
                    .background(fill),
            )
        }
    }
}
