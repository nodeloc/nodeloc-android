package com.nodeloc.app.feature.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ArrowUpRight
import com.composables.icons.lucide.Award
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Crown
import com.composables.icons.lucide.Feather
import com.composables.icons.lucide.Gem
import com.composables.icons.lucide.ListChecks
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Medal
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Sprout
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.Sun
import com.composables.icons.lucide.Swords
import com.composables.icons.lucide.ThumbsUp
import com.composables.icons.lucide.WifiOff
import com.nodeloc.app.R
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.LocalReduceMotion
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.SkeletonBox
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.animatedColor
import com.nodeloc.app.core.design.pressScale
import com.nodeloc.app.core.design.rememberPressSource
import com.nodeloc.app.core.design.skeletonPulsing
import com.nodeloc.app.core.network.DiscourseConfig
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
        Box(Modifier.fillMaxSize().background(Nocturne.bg)) {
            val rep = state.reputation
            when {
                rep == null && state.isLoading -> Column(
                    Modifier.padding(16.dp).skeletonPulsing(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SkeletonBox(Modifier.fillMaxWidth().height(240.dp), corner = 24.dp)
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
                    contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item(key = "hero") { Hero(rep, state.tiers) }
                    item(key = "how") { HowToEarn(rep) }
                    item(key = "perms") {
                        PermissionsEntry { navigator.openBrowser("${DiscourseConfig.BASE_URL}/permissions") }
                    }
                    item(key = "links") {
                        Column(
                            Modifier.padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            LinkCard(
                                title = stringResource(R.string.upgrade2_kicker),
                                detail = achievementsLine(rep, state.tiers),
                            ) { navigator.openAchievements() }
                            LinkCard(
                                title = stringResource(R.string.upgrade2_guidelines),
                                detail = stringResource(R.string.upgrade2_guidelines_desc),
                            ) { navigator.openBrowser("${DiscourseConfig.BASE_URL}/guidelines") }
                        }
                    }
                    item(key = "foot") {
                        Text(
                            stringResource(R.string.upgrade2_footnote),
                            style = Type.body(12),
                            color = Nocturne.textTertiary,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun tierName(level: Int, rep: ApiRepSummary, tiers: List<ApiTierInfo>): String =
    tiers.level(level)?.name ?: rep.tiers.firstOrNull { it.level == level }?.name.orEmpty()

/** One glyph per tier, climbing from a sprout to a burst; the web draws its own art, the app has no copy of it. */
private fun tierGlyph(level: Int): ImageVector = when (level) {
    0 -> Lucide.Sprout
    1 -> Lucide.Feather
    2 -> Lucide.Medal
    3 -> Lucide.Award
    4 -> Lucide.Gem
    5 -> Lucide.Crown
    6 -> Lucide.Swords
    7 -> Lucide.Sun
    else -> Lucide.Sparkles
}

/**
 * The sentence under the tier name, as the web's hero writes it
 * (web/achievements.go `currentLine`): the review and lock states win over
 * the plain "N to go", because in them the number is not what is holding
 * the account back.
 */
@Composable
private fun currentLine(rep: ApiRepSummary, tiers: List<ApiTierInfo>): String {
    val next = rep.next ?: return stringResource(R.string.me2_top_tier)
    val locked = rep.upgradeLockedUntil?.let { DiscourseFormat.instant(it) }?.takeIf { it.isAfter(Instant.now()) }
    return when {
        locked != null -> stringResource(R.string.upgrade2_locked_until, DiscourseFormat.day(rep.upgradeLockedUntil))
        rep.legendReview == "pending" && rep.needed <= 0 -> stringResource(R.string.upgrade2_awaiting_review)
        rep.tierReview == "pending" && rep.needed <= 0 -> stringResource(R.string.upgrade2_line_tier_review)
        // Bronze is the one tier left by finishing onboarding, not by reputation alone.
        rep.tier == 0 && rep.onboardingDoneAt != null -> stringResource(R.string.upgrade2_line_onboarded)
        rep.tier == 0 -> stringResource(R.string.upgrade2_line_bronze, formatCount(rep.needed.toLong()))
        else -> stringResource(R.string.upgrade2_line_next, tierName(next.level, rep, tiers), formatCount(rep.needed.toLong()))
    }
}

@Composable
private fun achievementsLine(rep: ApiRepSummary, tiers: List<ApiTierInfo>): String {
    val now = tierName(rep.tier, rep, tiers)
    val next = rep.next ?: return stringResource(R.string.upgrade2_link_ach_top, now)
    return stringResource(
        R.string.upgrade2_link_ach_line,
        now,
        formatCount(rep.needed.coerceAtLeast(0).toLong()),
        tierName(next.level, rep, tiers),
    )
}

/**
 * Always dark, in both themes: the climb is the page's one dramatic surface,
 * and its inks are the light end of the scale regardless of theme. Tapping a
 * rung previews it — what it took, or what it will take — without leaving
 * the card; "back to my tier" undoes that.
 */
@Composable
private fun Hero(rep: ApiRepSummary, tiers: List<ApiTierInfo>) {
    val light = Nocturne.neutral100
    val steps = rep.tiers.sortedBy { it.level }
    var preview by rememberSaveable { mutableStateOf<Int?>(null) }
    val shown = preview ?: rep.tier
    val step = steps.firstOrNull { it.level == shown }

    val stateLabel = when {
        shown == rep.tier -> stringResource(R.string.upgrade2_current)
        shown < rep.tier -> stringResource(R.string.upgrade2_state_past)
        else -> stringResource(R.string.upgrade2_state_ahead)
    }
    // Mirrors web/achievements.go `tierTrack`: a past rung is complete, a
    // rung ahead shows how much of its whole threshold is already in hand.
    val line: String
    val pctLabel: String
    val fraction: Double
    when {
        shown == rep.tier -> {
            line = currentLine(rep, tiers)
            pctLabel = stringResource(R.string.upgrade2_pct_now)
            fraction = if (rep.next == null) 1.0 else rep.progress
        }
        shown < rep.tier -> {
            line = if ((step?.threshold ?: 0) <= 0) {
                stringResource(R.string.upgrade2_from_start)
            } else {
                stringResource(R.string.upgrade2_line_threshold, formatCount(step!!.threshold.toLong()))
            }
            pctLabel = stringResource(R.string.upgrade2_pct_done)
            fraction = 1.0
        }
        else -> {
            val threshold = step?.threshold ?: 0
            line = stringResource(
                R.string.upgrade2_line_ahead,
                formatCount(threshold.toLong()),
                formatCount((threshold - rep.reputation).coerceAtLeast(0).toLong()),
            )
            pctLabel = stringResource(R.string.upgrade2_pct_done)
            fraction = if (threshold > 0) rep.reputation.toDouble() / threshold else 0.0
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Nocturne.accent900, Nocturne.accent800)))
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.upgrade2_kicker),
                    style = Type.body(12, FontWeight.Bold),
                    color = light.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        tierName(shown, rep, tiers),
                        style = Type.heading(30, FontWeight.ExtraBold),
                        color = light,
                        maxLines = 1,
                    )
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(light.copy(alpha = 0.92f))
                            .padding(horizontal = 9.dp, vertical = 1.dp),
                    ) {
                        Text(stateLabel, style = Type.body(12, FontWeight.Bold), color = Nocturne.neutral900, maxLines = 1)
                    }
                }
                Text(line, style = Type.body(13), color = light.copy(alpha = 0.78f), modifier = Modifier.padding(top = 2.dp))
                Row(
                    Modifier.heightIn(min = 30.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "$pctLabel ${(fraction.coerceIn(0.0, 1.0) * 100).roundToInt()}%",
                        style = Type.body(13),
                        color = light.copy(alpha = 0.78f),
                    )
                    AnimatedVisibility(
                        visible = preview != null && preview != rep.tier,
                        enter = if (LocalReduceMotion.current) fadeIn(tween(0)) else fadeIn(),
                        exit = if (LocalReduceMotion.current) fadeOut(tween(0)) else fadeOut(),
                    ) {
                        val press = rememberPressSource()
                        Box(
                            Modifier
                                .pressScale(press)
                                .height(28.dp)
                                .clip(CircleShape)
                                .background(light.copy(alpha = 0.14f))
                                .clickable(interactionSource = press, indication = ripple(), role = Role.Button) { preview = null }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(R.string.upgrade2_back), style = Type.body(12, FontWeight.Bold), color = light)
                        }
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.upgrade2_my_rep), style = Type.body(12, FontWeight.Bold), color = light.copy(alpha = 0.6f))
                Text(
                    formatCount(rep.reputation.toLong()),
                    style = Type.heading(36, FontWeight.ExtraBold),
                    color = light,
                    maxLines = 1,
                )
                Column(
                    Modifier.padding(top = 6.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HeroPill(
                        stringResource(R.string.upgrade2_week, formatSigned(rep.week.toLong())),
                        icon = if (rep.week > 0) Lucide.ArrowUpRight else null,
                        ground = light.copy(alpha = 0.16f),
                    )
                    // Likes settle the next day; until then they are counted nowhere,
                    // which looks like lost reputation unless the card says so.
                    if (rep.pending > 0) {
                        HeroPill(
                            stringResource(R.string.upgrade2_pending_pill, formatCount(rep.pending.toLong())),
                            icon = null,
                            ground = light.copy(alpha = 0.10f),
                        )
                    }
                }
            }
        }

        if (steps.isNotEmpty()) {
            TierTrack(
                rep = rep,
                steps = steps,
                tiers = tiers,
                selected = shown,
                modifier = Modifier.padding(top = 20.dp),
            ) { level -> preview = if (level == rep.tier) null else level }
        }
    }
}

@Composable
private fun HeroPill(text: String, icon: ImageVector?, ground: androidx.compose.ui.graphics.Color) {
    val light = Nocturne.neutral100
    Row(
        Modifier
            .height(22.dp)
            .clip(CircleShape)
            .background(ground)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        icon?.let { Icon(it, null, tint = light, modifier = Modifier.size(12.dp)) }
        Text(text, style = Type.body(12, FontWeight.Bold), color = light, maxLines = 1)
    }
}

/**
 * The nine rungs on one rail. The rail runs centre to centre, so its fill is
 * (tier + progress) / 8 of it — the web's `TrackFill`.
 */
@Composable
private fun TierTrack(
    rep: ApiRepSummary,
    steps: List<ApiRepStep>,
    tiers: List<ApiTierInfo>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val light = Nocturne.neutral100
    val top = steps.maxOf { it.level }.coerceAtLeast(1)
    val fill = if (rep.next == null) 1f else ((rep.tier + rep.progress.coerceIn(0.0, 1.0)) / top).toFloat().coerceIn(0f, 1f)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val slot = maxWidth / steps.size
        val rail = maxWidth - slot
        Box(
            Modifier
                .offset(x = slot / 2, y = 21.dp)
                .width(rail)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(light.copy(alpha = 0.2f)),
        ) {
            Box(Modifier.fillMaxWidth(fill).height(2.dp).background(light.copy(alpha = 0.85f)))
        }
        Row(Modifier.fillMaxWidth()) {
            steps.forEach { step ->
                TierNode(
                    step = step,
                    name = tiers.level(step.level)?.name ?: step.name.orEmpty(),
                    info = tiers.level(step.level),
                    isCurrent = step.level == rep.tier,
                    reached = step.level <= rep.tier,
                    isSelected = step.level == selected,
                    modifier = Modifier.weight(1f),
                ) { onSelect(step.level) }
            }
        }
    }
}

@Composable
private fun TierNode(
    step: ApiRepStep,
    name: String,
    info: ApiTierInfo?,
    isCurrent: Boolean,
    reached: Boolean,
    isSelected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val light = Nocturne.neutral100
    val reduce = LocalReduceMotion.current
    val size by animateDpAsState(
        targetValue = if (isSelected || isCurrent) 36.dp else 28.dp,
        animationSpec = tween(if (reduce) 0 else 250),
        label = "tierDot",
    )
    // The current rung wears its tier's own pair — the light ground the site
    // paints the tier pill in — so it reads as the one that is yours.
    val tierPair = info?.colors(dark = false)
    val ground by animatedColor(
        when {
            isCurrent -> tierPair?.first ?: Nocturne.accent100
            reached -> light.copy(alpha = 0.18f)
            else -> Nocturne.neutral900.copy(alpha = 0.35f)
        },
        "tierGround",
    )
    val ink = when {
        isCurrent -> tierPair?.second ?: Nocturne.accent700
        reached -> light
        else -> light.copy(alpha = 0.5f)
    }
    val ring by animatedColor(
        when {
            isSelected || isCurrent -> light
            reached -> light.copy(alpha = 0.45f)
            else -> light.copy(alpha = 0.18f)
        },
        "tierRing",
    )
    val press = rememberPressSource()
    val state = stringResource(
        when {
            isCurrent -> R.string.upgrade2_current
            reached -> R.string.upgrade2_state_past
            else -> R.string.upgrade2_state_ahead
        },
    )
    val description = stringResource(R.string.upgrade2_node_desc, name, state)
    Column(
        modifier
            .pressScale(press)
            .clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = press, indication = ripple(), role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                selected = isSelected
            }
            .padding(top = 4.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.height(36.dp), contentAlignment = Alignment.Center) {
            if (isCurrent && !reduce) PulseRing()
            Box(
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(ground)
                    .border(if (isSelected || isCurrent) 2.dp else 1.dp, ring, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(tierGlyph(step.level), null, tint = ink, modifier = Modifier.size(if (size > 30.dp) 18.dp else 14.dp))
            }
        }
        Text(
            name,
            style = Type.body(12, if (isSelected) FontWeight.Bold else FontWeight.Medium),
            color = if (isSelected) light else light.copy(alpha = 0.62f),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

/** The design's slow halo around the current rung; skipped entirely under reduce-motion. */
@Composable
private fun PulseRing() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "pulseT",
    )
    Box(
        Modifier
            .size(50.dp)
            .graphicsLayer {
                val s = 0.82f + 0.68f * t
                scaleX = s
                scaleY = s
                alpha = 0.75f * (1f - t)
            }
            .border(2.dp, Nocturne.neutral100.copy(alpha = 0.55f), CircleShape),
    )
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
 * The five ways to earn, in the bordered, grouped list the permissions table
 * uses. The figure is the all-time total for that source — the summary does
 * not split the week by source, and the week's whole gain is on the card above.
 */
@Composable
private fun HowToEarn(rep: ApiRepSummary) {
    OutlinedGroup(stringResource(R.string.upgrade2_how)) {
        REP_SOURCES.forEachIndexed { index, source ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.neutral300))
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(source.icon, null, tint = Nocturne.textSecondary, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(source.title), style = Type.body(14), color = Nocturne.text)
                    Text(stringResource(source.detail), style = Type.body(12), color = Nocturne.textSecondary)
                }
                Text(
                    stringResource(
                        R.string.upgrade2_total,
                        formatSigned((rep.sources.firstOrNull { it.source == source.key }?.total ?: 0).toLong()),
                    ),
                    style = Type.body(13, FontWeight.Bold),
                    color = Nocturne.text,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A 16-radius bordered box with a grey heading band, as the design draws its grouped lists. */
@Composable
private fun OutlinedGroup(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Nocturne.neutral400, RoundedCornerShape(16.dp)),
    ) {
        Text(
            title,
            style = Type.body(13, FontWeight.Bold),
            color = Nocturne.textSecondary,
            modifier = Modifier.fillMaxWidth().background(Nocturne.surface).padding(horizontal = 14.dp, vertical = 10.dp),
        )
        content()
    }
}

/**
 * The design's 用户组与权限 section heads the page's lower half with a full
 * tier-by-tier table. The app has no source for it — the comparison is only
 * rendered server-side at `/permissions` (the JSON form is admin-only) — so
 * the heading stays and the table opens on the site.
 */
@Composable
private fun PermissionsEntry(onOpen: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text(stringResource(R.string.upgrade2_perm_title), style = Type.heading(24, FontWeight.ExtraBold), color = Nocturne.text)
            Text(
                stringResource(R.string.upgrade2_perm_sub),
                style = Type.body(14),
                color = Nocturne.textSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        val press = rememberPressSource()
        Row(
            Modifier
                .pressScale(press)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Nocturne.text)
                .clickable(interactionSource = press, indication = ripple(), role = Role.Button, onClick = onOpen)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Nocturne.bg.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Lucide.ShieldCheck, null, tint = Nocturne.bg, modifier = Modifier.size(22.dp)) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.upgrade2_perm_open), style = Type.body(16, FontWeight.Bold), color = Nocturne.bg)
                Text(stringResource(R.string.upgrade2_perm_open_desc), style = Type.body(12), color = Nocturne.bg.copy(alpha = 0.72f))
            }
            Icon(Lucide.ChevronRight, null, tint = Nocturne.bg.copy(alpha = 0.72f), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun LinkCard(title: String, detail: String, onClick: () -> Unit) {
    val press = rememberPressSource()
    Row(
        Modifier
            .pressScale(press)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Nocturne.neutral400, RoundedCornerShape(14.dp))
            .clickable(interactionSource = press, indication = ripple(), role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body(14, FontWeight.Bold), color = Nocturne.text)
            Text(detail, style = Type.body(12), color = Nocturne.textSecondary)
        }
        Icon(Lucide.ChevronRight, null, tint = Nocturne.textTertiary, modifier = Modifier.size(18.dp))
    }
}
