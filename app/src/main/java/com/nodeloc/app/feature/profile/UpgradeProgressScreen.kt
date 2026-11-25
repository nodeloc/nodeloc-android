package com.nodeloc.app.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.Award
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Gem
import com.composables.icons.lucide.ListChecks
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.ThumbsUp
import com.composables.icons.lucide.WifiOff
import com.nodeloc.app.R
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.SkeletonBox
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.skeletonPulsing
import com.nodeloc.app.core.network.isOfflineError
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.feature.moderation.ModScaffold
import com.nodeloc.app.feature.nav.Navigator
import java.time.Instant
import kotlin.math.roundToInt

/**
 * How close the account is to its next tier, and what moves it.
 *
 * Only ever your own: the endpoint is `/me`, and how near a stranger is to
 * their next tier is their business. [username] keys the load, nothing more.
 */
@Composable
fun UpgradeProgressScreen(username: String, navigator: Navigator) {
    val viewModel: UpgradeProgressViewModel = viewModel()
    val state by viewModel.state.collectAsState()

    LaunchedEffect(username) { viewModel.bind(username) }

    ModScaffold(title = stringResource(R.string.upgrade_title), onBack = navigator::back) {
        Box(Modifier.fillMaxSize().background(Nocturne.surface)) {
            val rep = state.reputation
            when {
                rep == null && state.isLoading -> Column(
                    Modifier.padding(16.dp).skeletonPulsing(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SkeletonBox(Modifier.fillMaxWidth().height(200.dp), corner = 22.dp)
                    repeat(5) { SkeletonBox(Modifier.fillMaxWidth().height(56.dp)) }
                }

                rep == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val offline = state.error?.isOfflineError() == true
                    EmptyStateView(
                        icon = if (offline) Lucide.WifiOff else Lucide.CircleAlert,
                        title = stringResource(if (offline) R.string.error_offline else R.string.upgrade_load_failed),
                        retryLabel = stringResource(R.string.common_retry),
                        onRetry = viewModel::retry,
                    )
                }

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item(key = "hero") { Hero(rep, state.tiers) }
                    item(key = "how") {
                        GrowthSectionHead(
                            stringResource(R.string.upgrade2_how),
                            trailing = stringResource(R.string.upgrade2_week, formatSigned(rep.week.toLong())),
                        )
                        Column(Modifier.padding(top = 4.dp)) {
                            REP_SOURCES.forEach { source ->
                                SourceRow(source, rep.sources.firstOrNull { it.source == source.key }?.total ?: 0)
                            }
                        }
                    }
                    item(key = "ladder") {
                        GrowthSectionHead(stringResource(R.string.upgrade2_ladder))
                        Ladder(rep, state.tiers, Modifier.padding(top = 8.dp))
                    }
                    item(key = "foot") {
                        Text(
                            stringResource(R.string.upgrade2_footnote),
                            style = Type.body(12),
                            color = Nocturne.textTertiary,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Always dark, in both themes: the climb is the page's one dramatic surface,
 * and its inks are the light end of the scale regardless of theme.
 */
@Composable
private fun Hero(rep: ApiRepSummary, tiers: List<ApiTierInfo>) {
    val light = Nocturne.neutral100
    val next = rep.next
    // The dark-theme ink of each tier is the light one, which is what reads
    // on this ground in either theme.
    val currentInk = tiers.level(rep.tier)?.colors(dark = true)?.second ?: Nocturne.accent300
    val nextInk = next?.let { tiers.level(it.level)?.colors(dark = true)?.second } ?: light
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Nocturne.accent900, Nocturne.accent800)))
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(light.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Lucide.Gem, null, tint = currentInk, modifier = Modifier.size(28.dp)) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.upgrade2_current), style = Type.body(13), color = light.copy(alpha = 0.75f))
                Text(
                    tiers.level(rep.tier)?.name ?: rep.tierName.orEmpty(),
                    style = Type.heading(26, FontWeight.Bold),
                    color = light,
                )
            }
            if (next != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(stringResource(R.string.upgrade2_next), style = Type.body(13), color = light.copy(alpha = 0.75f))
                    Text(
                        tiers.level(next.level)?.name ?: next.name.orEmpty(),
                        style = Type.heading(18, FontWeight.Bold),
                        color = nextInk,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(formatCount(rep.reputation.toLong()), style = Type.heading(22, FontWeight.Bold), color = light)
                Text(
                    " " + if (next != null) {
                        stringResource(R.string.upgrade2_of_rep, formatCount(next.threshold.toLong()))
                    } else {
                        stringResource(R.string.profile_reputation)
                    },
                    style = Type.body(13),
                    color = light,
                    modifier = Modifier.weight(1f).padding(bottom = 3.dp),
                )
                if (next != null) {
                    Text(
                        "${(rep.progress * 100).roundToInt()}%",
                        style = Type.body(13),
                        color = light.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
            GrowthBar(
                if (next == null) 1f else rep.progress.toFloat(),
                height = 10.dp,
                track = light.copy(alpha = 0.16f),
                fill = Nocturne.accent300,
            )
        }

        val note = when {
            next == null -> stringResource(R.string.me2_top_tier)
            // The last rung is granted, not reached: the number can be met and
            // the tier still wait on a person.
            rep.needed <= 0 -> stringResource(R.string.upgrade2_awaiting_review)
            else -> stringResource(R.string.upgrade2_needed, formatCount(rep.needed.toLong()))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(light.copy(alpha = 0.08f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(note, style = Type.body(13, FontWeight.Medium), color = light)
            // Likes settle the next day; until then they are counted nowhere,
            // which looks like lost reputation unless the page says so.
            if (rep.pending > 0) {
                Text(
                    stringResource(R.string.upgrade2_pending, formatCount(rep.pending.toLong())),
                    style = Type.body(12),
                    color = light.copy(alpha = 0.75f),
                )
            }
            rep.upgradeLockedUntil
                ?.let { DiscourseFormat.instant(it) }
                ?.takeIf { it.isAfter(Instant.now()) }
                ?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Lucide.Lock, null, tint = light.copy(alpha = 0.75f), modifier = Modifier.size(13.dp))
                        Text(
                            stringResource(R.string.upgrade2_locked_until, DiscourseFormat.day(rep.upgradeLockedUntil)),
                            style = Type.body(12),
                            color = light.copy(alpha = 0.75f),
                        )
                    }
                }
        }
    }
}

private class RepSource(val key: String, val icon: ImageVector, val title: Int, val detail: Int)

/** The five sources, in the order the server's summary lists them. */
private val REP_SOURCES = listOf(
    RepSource("like", Lucide.ThumbsUp, R.string.upgrade2_src_like, R.string.upgrade2_src_like_desc),
    RepSource("accept", Lucide.CircleCheck, R.string.upgrade2_src_accept, R.string.upgrade2_src_accept_desc),
    RepSource("featured", Lucide.Star, R.string.upgrade2_src_featured, R.string.upgrade2_src_featured_desc),
    RepSource("achievement", Lucide.Award, R.string.upgrade2_src_achievement, R.string.upgrade2_src_achievement_desc),
    RepSource("task", Lucide.ListChecks, R.string.upgrade2_src_task, R.string.upgrade2_src_task_desc),
)

/**
 * One way to earn. The figure is the all-time total for that source — the
 * summary does not split the week by source, and the week's whole gain is
 * already in the heading above.
 */
@Composable
private fun SourceRow(source: RepSource, total: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(start = 16.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(source.icon, null, tint = Nocturne.textSecondary, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(source.title), style = Type.body(16), color = Nocturne.text)
            Text(stringResource(source.detail), style = Type.body(14), color = Nocturne.textSecondary)
        }
        Text(
            stringResource(R.string.upgrade2_total, formatSigned(total.toLong())),
            style = Type.body(14),
            color = Nocturne.textSecondary,
            maxLines = 1,
        )
    }
}

/** Every rung with its threshold; reached ones filled in their tier's colour, the current one ringed. */
@Composable
private fun Ladder(rep: ApiRepSummary, tiers: List<ApiTierInfo>, modifier: Modifier = Modifier) {
    val steps = rep.tiers.sortedBy { it.level }
    GrowthCard(modifier, padding = 16.dp) {
        Column {
            steps.forEachIndexed { index, step ->
                val reached = step.level <= rep.tier
                val current = step.level == rep.tier
                val info = tiers.level(step.level)
                val ink = info?.colors(Nocturne.isDark)?.second ?: Nocturne.accent
                Box(Modifier.fillMaxWidth().height(46.dp)) {
                    // The connector to the next rung, coloured once that rung is reached too.
                    if (index < steps.lastIndex) {
                        val nextReached = steps[index + 1].level <= rep.tier
                        Box(
                            Modifier
                                .offset(x = 11.dp, y = 35.dp)
                                .width(2.dp)
                                .height(22.dp)
                                .background(if (nextReached) ink else Nocturne.neutral400),
                        )
                    }
                    Row(
                        Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(if (reached) ink else Nocturne.bg)
                                .border(2.dp, if (reached) ink else Nocturne.neutral400, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (reached) Icon(Lucide.Check, null, tint = Nocturne.bg, modifier = Modifier.size(14.dp))
                        }
                        val name = info?.name ?: step.name.orEmpty()
                        Text(
                            if (current) stringResource(R.string.upgrade2_ladder_current, name) else name,
                            style = Type.body(15, if (current) FontWeight.Bold else FontWeight.Medium),
                            color = if (reached) Nocturne.text else Nocturne.textSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (step.threshold <= 0) {
                                stringResource(R.string.upgrade2_from_start)
                            } else {
                                stringResource(R.string.upgrade2_rep_value, formatCount(step.threshold.toLong()))
                            },
                            style = Type.body(13),
                            color = Nocturne.textSecondary,
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
        }
    }
}
