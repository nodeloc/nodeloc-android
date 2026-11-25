package com.nodeloc.app.feature.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TrendingUp
import com.composables.icons.lucide.Zap
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.LocalReduceMotion
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.OnReachedEnd
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.animatedColor
import com.nodeloc.app.core.design.pressScale
import com.nodeloc.app.core.design.rememberPressSource
import com.nodeloc.app.core.network.NodelocApi
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.feature.nav.Navigator
import com.nodeloc.app.feature.nav.Route
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The two reads only the 声望与成就 page makes: this week's tasks and the
 * reputation ledger. Kept beside the page rather than in [GrowthApi] since
 * nothing else asks for them.
 */
class ReputationApi(private val api: NodelocApi) {
    suspend fun tasks(): ApiTaskOverview = api.get("api/v1/me/tasks")

    /** [before] is the previous page's opaque `next` cursor, not an id. */
    suspend fun ledger(before: String?, limit: Int = 20): ApiRepLedgerPage =
        api.get("api/v1/me/reputation/ledger", buildList {
            add("limit" to limit.toString())
            before?.let { add("before" to it) }
        })

    companion object {
        val shared: ReputationApi by lazy { ReputationApi(ServiceLocator.get.client.api) }
    }
}

@Serializable
data class ApiTaskItem(
    val key: String = "",
    /** `daily` or `weekly`. */
    val period: String? = null,
    val title: String = "",
    val progress: Int = 0,
    val target: Int = 1,
    val done: Boolean = false,
    val doneAt: String? = null,
    /** Done: what was paid. Not done: what finishing it now would pay, after the weekly cap. */
    val energy: Int = 0,
    val reputation: Int = 0,
    val cta: String? = null,
    val href: String? = null,
    val compose: Boolean = false,
)

@Serializable
data class ApiTaskOverview(
    val tasks: List<ApiTaskItem> = emptyList(),
    val weekReputation: Int = 0,
    val weekCap: Int = 0,
    /** False for 青铜: tasks pay energy only until 白银. */
    val reputationEligible: Boolean = true,
)

@Serializable
data class ApiRepLedgerItem(
    val id: Long = 0,
    val source: String = "",
    val amount: Int = 0,
    /** `settled` or `pending`; pending entries are not in the total yet. */
    val status: String? = null,
    val revoke: Boolean = false,
    val memo: String? = null,
    /** The topic's title, or for an achievement its name. */
    val title: String? = null,
    val url: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class ApiRepLedgerPage(val items: List<ApiRepLedgerItem> = emptyList(), val next: String? = null)

// ------------------------------------------------------------------ Hero

private const val TOP_TIER = 8

/** The hero's light ink on its dark ground, at the strengths the design uses. */
@Composable
private fun heroInk(alpha: Float = 1f): Color = Nocturne.neutral100.copy(alpha = alpha)

/**
 * The tier card: where you stand, how far into this tier, and the nine tiers
 * as a track you can tap through to read each one's requirement.
 *
 * The design paints a scene per tier behind the card; those are artwork the
 * app does not have, so the card keeps one dark ground and lets the tier's own
 * colours mark the current node.
 */
@Composable
fun RepHero(rep: ApiRepSummary, tiers: List<ApiTierInfo>) {
    val current = rep.tier.coerceIn(0, TOP_TIER)
    var selected by rememberSaveable(current) { mutableStateOf(current) }
    val steps = rep.tiers.sortedBy { it.level }
    val step = steps.firstOrNull { it.level == selected }
    fun nameOf(level: Int): String =
        tiers.level(level)?.name ?: steps.firstOrNull { it.level == level }?.name.orEmpty()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Nocturne.accent900)
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(nameOf(selected), style = Type.heading(30, FontWeight.Bold), color = heroInk(), maxLines = 1)
                    if (selected == current) {
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .background(heroInk(0.92f))
                                .padding(horizontal = 9.dp, vertical = 1.dp),
                        ) {
                            Text(
                                stringResource(R.string.upgrade2_current),
                                style = Type.body(12, FontWeight.Bold),
                                color = Nocturne.accent900,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Text(
                    tierLine(rep, selected, step, ::nameOf),
                    style = Type.body(13),
                    color = heroInk(0.78f),
                    modifier = Modifier.padding(top = 2.dp),
                )
                val (label, fraction) = when {
                    selected == current -> stringResource(R.string.ach_track_progress) to rep.progress.toFloat()
                    selected < current -> stringResource(R.string.ach_track_done) to 1f
                    else -> stringResource(R.string.ach_track_done) to
                        (step?.threshold?.takeIf { it > 0 }?.let { rep.reputation.toFloat() / it } ?: 0f)
                }
                Row(
                    Modifier.heightIn(min = 30.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "$label ${(fraction.coerceIn(0f, 1f) * 100).toInt()}%",
                        style = Type.body(13),
                        color = heroInk(0.78f),
                    )
                    if (selected != current) {
                        val press = rememberPressSource()
                        Box(
                            Modifier
                                .pressScale(press)
                                .height(28.dp)
                                .clip(CircleShape)
                                .background(heroInk(0.14f))
                                .clickable(interactionSource = press, indication = ripple(), role = Role.Button) { selected = current }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(R.string.ach_back_to_mine), style = Type.body(12, FontWeight.Bold), color = heroInk())
                        }
                    }
                }
            }
            Column {
                Text(stringResource(R.string.ach_my_rep), style = Type.body(12, FontWeight.Bold), color = heroInk(0.6f))
                Text(formatCount(rep.reputation.toLong()), style = Type.heading(36, FontWeight.Bold), color = heroInk())
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        Modifier
                            .height(22.dp)
                            .clip(CircleShape)
                            .background(heroInk(0.16f))
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(Lucide.TrendingUp, null, tint = heroInk(), modifier = Modifier.size(12.dp))
                        Text(
                            stringResource(R.string.upgrade2_week, formatSigned(rep.week.toLong())),
                            style = Type.body(12, FontWeight.Bold),
                            color = heroInk(),
                            maxLines = 1,
                        )
                    }
                }
                if (rep.pending > 0) {
                    Text(
                        stringResource(R.string.upgrade2_pending, formatCount(rep.pending.toLong())),
                        style = Type.body(11),
                        color = heroInk(0.6f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        if (steps.isNotEmpty()) {
            TierTrack(steps, current, rep.progress.toFloat(), selected, tiers, ::nameOf) { selected = it }
        }
    }
}

/** The line under the tier's name, as the web writes it for each state. */
@Composable
private fun tierLine(rep: ApiRepSummary, selected: Int, step: ApiRepStep?, nameOf: (Int) -> String): String {
    val current = rep.tier
    if (selected == current) {
        val next = rep.next
        val locked = rep.upgradeLockedUntil?.let(DiscourseFormat::instant)?.takeIf { it.isAfter(java.time.Instant.now()) }
        return when {
            next == null -> stringResource(R.string.me2_top_tier)
            locked != null -> stringResource(R.string.upgrade2_locked_until, DiscourseFormat.day(rep.upgradeLockedUntil))
            rep.legendReview == "pending" && next.level == TOP_TIER -> stringResource(R.string.upgrade2_awaiting_review)
            rep.tierReview == "pending" && rep.needed == 0 -> stringResource(R.string.upgrade2_awaiting_review)
            else -> stringResource(R.string.ach_next_needed, nameOf(next.level), formatCount(rep.needed.toLong()))
        }
    }
    val threshold = step?.threshold ?: 0
    return when {
        selected < current && selected == 0 -> stringResource(R.string.upgrade2_from_start)
        selected < current -> stringResource(R.string.ach_tier_threshold, formatCount(threshold.toLong()))
        else -> stringResource(
            R.string.ach_tier_ahead,
            formatCount(threshold.toLong()),
            formatCount((threshold - rep.reputation).coerceAtLeast(0).toLong()),
        )
    }
}

@Composable
private fun TierTrack(
    steps: List<ApiRepStep>,
    current: Int,
    progress: Float,
    selected: Int,
    tiers: List<ApiTierInfo>,
    nameOf: (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    val top = steps.maxOf { it.level }.coerceAtLeast(1)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        // The rail runs between the first and last node's centres.
        val inset = maxWidth / (steps.size * 2)
        val fill = if (current >= top) 1f else ((current + progress.coerceIn(0f, 1f)) / top)
        Box(
            Modifier
                .padding(horizontal = inset)
                .offset(y = 21.dp)
                .fillMaxWidth()
                .height(2.dp)
                .clip(CircleShape)
                .background(heroInk(0.2f)),
        ) {
            Box(Modifier.fillMaxWidth(fill).fillMaxHeight().background(heroInk(0.85f)))
        }
        Row(Modifier.fillMaxWidth()) {
            steps.forEach { s ->
                val state = when {
                    s.level < current -> stringResource(R.string.ach_tier_cd_past, nameOf(s.level))
                    s.level == current -> stringResource(R.string.ach_tier_cd_now, nameOf(s.level))
                    else -> stringResource(R.string.ach_tier_cd_ahead, nameOf(s.level))
                }
                val press = rememberPressSource()
                Column(
                    Modifier
                        .weight(1f)
                        .pressScale(press)
                        .clip(RoundedCornerShape(12.dp))
                        .semantics(mergeDescendants = true) {
                            contentDescription = state
                            this.selected = s.level == selected
                        }
                        .clickable(interactionSource = press, indication = ripple(), role = Role.Tab) { onSelect(s.level) }
                        .padding(top = 4.dp, bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.height(36.dp), contentAlignment = Alignment.Center) {
                        TierNode(s.level, current, tiers)
                    }
                    val ink by animatedColor(if (s.level == selected) heroInk() else heroInk(0.62f), "tierLabel")
                    Text(
                        nameOf(s.level),
                        style = Type.body(12, if (s.level == selected) FontWeight.Bold else FontWeight.Medium),
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}

@Composable
private fun TierNode(level: Int, current: Int, tiers: List<ApiTierInfo>) {
    when {
        level == current -> {
            val (ground, ink) = tiers.level(level)?.colors(Nocturne.isDark) ?: (Nocturne.accent100 to Nocturne.accent700)
            Box(
                Modifier
                    .size(36.dp)
                    .border(BorderStroke(2.dp, heroInk()), CircleShape)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(ground),
                contentAlignment = Alignment.Center,
            ) { Icon(Lucide.Check, null, tint = ink, modifier = Modifier.size(16.dp)) }
        }
        level < current -> Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(heroInk(0.16f))
                .border(BorderStroke(1.dp, heroInk(0.4f)), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Lucide.Check, null, tint = heroInk(), modifier = Modifier.size(13.dp)) }
        else -> Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Nocturne.neutral900.copy(alpha = 0.35f))
                .border(BorderStroke(1.dp, heroInk(0.18f)), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Lucide.Lock, null, tint = heroInk(0.5f), modifier = Modifier.size(12.dp)) }
    }
}

// ------------------------------------------------------------------ Tasks

@Composable
fun TasksSection(overview: ApiTaskOverview, navigator: Navigator) {
    if (overview.tasks.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.ach_tasks), style = Type.heading(20, FontWeight.Bold), color = Nocturne.text)
            Text(
                if (overview.reputationEligible) stringResource(R.string.ach_tasks_note, overview.weekCap)
                else stringResource(R.string.ach_tasks_note_bronze),
                style = Type.body(13),
                color = Nocturne.textSecondary,
            )
            if (overview.reputationEligible && overview.weekCap > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.ach_tasks_week, overview.weekReputation, overview.weekCap),
                        style = Type.body(12, FontWeight.Bold),
                        color = Nocturne.textSecondary,
                    )
                    GrowthBar(
                        overview.weekReputation.toFloat() / overview.weekCap,
                        Modifier.width(72.dp),
                        track = Nocturne.neutral400,
                    )
                }
            }
        }
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            overview.tasks.forEach { TaskCard(it, overview.reputationEligible, navigator) }
        }
    }
}

@Composable
private fun TaskCard(task: ApiTaskItem, repEligible: Boolean, navigator: Navigator) {
    val context = LocalContext.current
    Column(
        Modifier
            .width(232.dp)
            .heightIn(min = 140.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(BorderStroke(1.dp, Nocturne.divider), RoundedCornerShape(16.dp))
            .background(Nocturne.bg)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskRing(task)
            Box(
                Modifier.clip(CircleShape).background(Nocturne.accent100).padding(horizontal = 8.dp, vertical = 1.dp),
            ) {
                Text(
                    stringResource(if (task.period == "weekly") R.string.ach_task_weekly else R.string.ach_task_daily),
                    style = Type.body(11, FontWeight.Bold),
                    color = Nocturne.accent700,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            if (task.energy > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Icon(Lucide.Zap, null, tint = Nocturne.textSecondary, modifier = Modifier.size(12.dp))
                    Text("+${task.energy}", style = Type.body(12, FontWeight.Bold), color = Nocturne.textSecondary, maxLines = 1)
                }
            }
            if (repEligible && task.reputation > 0) {
                Text(
                    stringResource(R.string.ach_reward, task.reputation),
                    style = Type.body(12, FontWeight.Bold),
                    color = Nocturne.accent700,
                    maxLines = 1,
                )
            }
        }
        Text(
            task.title,
            style = Type.body(15, FontWeight.Bold),
            color = Nocturne.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.weight(1f))
        val cta = task.cta?.takeIf { it.isNotBlank() }
        when {
            task.done -> Text(stringResource(R.string.ach_task_done), style = Type.body(13, FontWeight.Bold), color = Nocturne.textSecondary)
            cta != null -> Row(
                Modifier
                    .heightIn(min = 24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button) { openTask(task, navigator, context) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(cta, style = Type.body(13, FontWeight.Bold), color = Nocturne.accent)
                Icon(Lucide.ChevronRight, null, tint = Nocturne.accent, modifier = Modifier.size(14.dp))
            }
            else -> Text(stringResource(R.string.ach_task_auto), style = Type.body(13), color = Nocturne.textSecondary)
        }
    }
}

/**
 * The task's own destination where the app has one. The server's hrefs are
 * web paths (`/topics?sort=new`, `/`, `/u/{me}?tab=posts`); the app has no
 * sorted topic index, so the lists land on the home feed, which is where
 * those same topics are read here.
 */
private fun openTask(task: ApiTaskItem, navigator: Navigator, context: android.content.Context) {
    val href = task.href.orEmpty()
    when {
        task.compose -> navigator.openCompose()
        href.startsWith("/u/") -> navigator.openUrl(context, href)
        else -> navigator.navigate(Route.Home) {
            popUpTo(Route.Home)
            launchSingleTop = true
        }
    }
}

@Composable
private fun TaskRing(task: ApiTaskItem) {
    val fraction = if (task.target > 0) (task.progress.toFloat() / task.target).coerceIn(0f, 1f) else 0f
    val track = Nocturne.neutral400
    val fill = Nocturne.accent
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(34.dp)) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            if (fraction > 0f) {
                drawArc(fill, -90f, 360f * fraction, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        if (task.done) {
            Icon(Lucide.Check, null, tint = Nocturne.accent, modifier = Modifier.size(16.dp))
        } else {
            Text("${task.progress}/${task.target}", style = Type.body(11, FontWeight.Bold), color = Nocturne.text, maxLines = 1)
        }
    }
}

// ------------------------------------------------------------------ Cards

/** The bordered white card the lower half of the page is built from. */
@Composable
fun AchOutlinedCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(BorderStroke(1.dp, Nocturne.divider), RoundedCornerShape(20.dp))
            .background(Nocturne.bg)
            .padding(padding),
        content = content,
    )
}

/** "主页展示  3 / 3": the small caps heading of the lower cards. */
@Composable
fun AchCardHead(title: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Type.body(12, FontWeight.Bold), color = Nocturne.textSecondary, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, style = Type.body(12), color = Nocturne.textSecondary) }
    }
}

// ------------------------------------------------------------------ Ledger

/** Each source's dot and bar colour; the design's purple and teal are the nearest tokens. */
@Composable
fun sourceColor(source: String): Color = when (source) {
    "like" -> Nocturne.accent
    "accept" -> Nocturne.info
    "featured" -> Nocturne.love
    "achievement" -> Nocturne.warning
    "revoke" -> Nocturne.danger
    else -> Nocturne.textTertiary
}

@Composable
fun sourceName(source: String): String = when (source) {
    "like" -> stringResource(R.string.upgrade2_src_like)
    "accept" -> stringResource(R.string.upgrade2_src_accept)
    "featured" -> stringResource(R.string.upgrade2_src_featured)
    "achievement" -> stringResource(R.string.upgrade2_src_achievement)
    "task" -> stringResource(R.string.upgrade2_src_task)
    else -> source
}

/**
 * The web's sentence for each entry (web/achievements.go `ledgerRows`).
 * Revocation memos are the server's own Chinese wording, so they are not
 * shown; the generic line says the same in the reader's language. A task's
 * memo is its key, named from this week's task list when it is in it.
 */
@Composable
private fun ledgerText(item: ApiRepLedgerItem, taskTitles: Map<String, ApiTaskItem>): String {
    val quoted = item.title?.takeIf { it.isNotBlank() && item.source != "achievement" }?.let { "《$it》" }
    val topic = quoted ?: stringResource(R.string.ach_l_a_topic)
    return when {
        item.revoke -> listOfNotNull(quoted, stringResource(R.string.ach_l_revoke)).joinToString(" · ")
        item.source == "like" -> quoted?.let { stringResource(R.string.ach_l_like_on, it) } ?: stringResource(R.string.ach_l_like)
        item.source == "accept" && item.memo == "采纳了回答" -> stringResource(R.string.ach_l_accepter, topic)
        item.source == "accept" -> stringResource(R.string.ach_l_accepted, topic)
        item.source == "featured" -> stringResource(R.string.ach_l_featured, topic)
        item.source == "achievement" ->
            stringResource(R.string.ach_l_achievement, item.title?.takeIf { it.isNotBlank() } ?: item.memo.orEmpty())
        item.source == "task" -> taskTitles[item.memo]?.let {
            stringResource(if (it.period == "weekly") R.string.ach_l_task_weekly else R.string.ach_l_task_daily, it.title)
        } ?: stringResource(R.string.ach_l_task)
        else -> item.memo.orEmpty()
    }
}

private val BEIJING: ZoneId = ZoneId.of("Asia/Shanghai")

/** 今天 14:20 · 昨天 · 3 天前 · a date: the web's `relativeDay`, in Beijing days like the ledger. */
@Composable
private fun ledgerWhen(raw: String?): String {
    val at = DiscourseFormat.instant(raw)?.atZone(BEIJING) ?: return ""
    val today = LocalDate.now(BEIJING)
    val days = ChronoUnit.DAYS.between(at.toLocalDate(), today)
    return when {
        days <= 0 -> stringResource(R.string.ach_when_today, at.format(DateTimeFormatter.ofPattern("HH:mm")))
        days == 1L -> stringResource(R.string.energy_yesterday)
        days < 7 -> stringResource(R.string.ach_when_days, days.toInt())
        else -> {
            val skeleton = if (at.year == today.year) "MMMd" else "yMMMd"
            val formatter = remember(skeleton) {
                DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton))
            }
            formatter.format(at)
        }
    }
}

/** `/t/12?view=flat#post-5`: the topic, and the floor when there is one. */
fun openLedgerUrl(url: String?, navigator: Navigator): (() -> Unit)? {
    val id = url?.let { Regex("^/t/(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: return null
    val post = Regex("#post-(\\d+)$").find(url)?.groupValues?.get(1)?.toIntOrNull()
    return { navigator.openTopic(id, post) }
}

@Composable
fun LedgerRow(item: ApiRepLedgerItem, taskTitles: Map<String, ApiTaskItem>, navigator: Navigator, divider: Boolean) {
    val pending = item.status == "pending"
    val open = openLedgerUrl(item.url, navigator)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = open != null) { open?.invoke() }
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(sourceColor(if (item.revoke) "revoke" else item.source).copy(alpha = if (pending) 0.4f else 1f)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                ledgerText(item, taskTitles),
                style = Type.body(13),
                color = if (pending) Nocturne.textSecondary else Nocturne.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    if (item.revoke) stringResource(R.string.ach_l_revoke_kind) else sourceName(item.source),
                    ledgerWhen(item.createdAt).takeIf { it.isNotEmpty() },
                    if (pending) stringResource(R.string.ach_l_pending) else null,
                ).joinToString(" · "),
                style = Type.body(12),
                color = Nocturne.textSecondary,
                maxLines = 1,
            )
        }
        Text(
            formatSigned(item.amount.toLong()).takeIf { item.amount != 0 } ?: "0",
            style = Type.body(14, FontWeight.Bold),
            color = when {
                pending -> Nocturne.textTertiary
                item.amount > 0 -> Nocturne.accent
                item.amount < 0 -> Nocturne.danger
                else -> Nocturne.textSecondary
            },
            maxLines = 1,
        )
    }
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.divider))
}

@Composable
fun RecentLedgerCard(
    items: List<ApiRepLedgerItem>,
    taskTitles: Map<String, ApiTaskItem>,
    hasMore: Boolean,
    navigator: Navigator,
    onAll: () -> Unit,
) {
    AchOutlinedCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
        AchCardHead(stringResource(R.string.ach_recent))
        Spacer(Modifier.height(4.dp))
        if (items.isEmpty()) {
            Text(
                stringResource(R.string.ach_ledger_empty),
                style = Type.body(13),
                color = Nocturne.textSecondary,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        }
        items.forEachIndexed { index, item ->
            LedgerRow(item, taskTitles, navigator, divider = index < items.lastIndex || hasMore)
        }
        if (hasMore) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClick = onAll),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.ach_ledger_all), style = Type.body(13, FontWeight.Bold), color = Nocturne.text)
                Icon(Lucide.ChevronRight, null, tint = Nocturne.text, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerSheet(
    items: List<ApiRepLedgerItem>,
    taskTitles: Map<String, ApiTaskItem>,
    loading: Boolean,
    hasMore: Boolean,
    navigator: Navigator,
    onMore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val list = rememberLazyListState()
    list.OnReachedEnd { if (hasMore) onMore() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Nocturne.bg,
    ) {
        Text(
            stringResource(R.string.ach_ledger_title),
            style = Type.heading(18, FontWeight.Bold),
            color = Nocturne.text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            items(items, key = { it.id }) { item ->
                LedgerRow(item, taskTitles, navigator, divider = true)
            }
            item(key = "foot") {
                Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                    when {
                        loading -> NodelocLoader(height = 22.dp)
                        !hasMore -> Text(stringResource(R.string.energy_end), style = Type.body(12), color = Nocturne.textTertiary)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Sources

private val SOURCE_ORDER = listOf("like", "accept", "featured", "achievement", "task")

@Composable
fun SourcesCard(rep: ApiRepSummary, onPermissions: () -> Unit) {
    val total = rep.reputation
    val sources = rep.sources.sortedBy { SOURCE_ORDER.indexOf(it.source).let { i -> if (i < 0) 99 else i } }
    var rulesOpen by rememberSaveable { mutableStateOf(false) }
    val reduceMotion = LocalReduceMotion.current
    AchOutlinedCard {
        AchCardHead(stringResource(R.string.ach_sources), stringResource(R.string.ach_sources_total, formatCount(total.toLong())))
        val shown = sources.filter { it.total > 0 }
        if (total > 0 && shown.isNotEmpty()) {
            Row(
                Modifier.padding(top = 12.dp).fillMaxWidth().height(10.dp).clip(CircleShape),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                shown.forEach {
                    Box(Modifier.weight(it.total.toFloat().coerceAtLeast(total * 0.02f)).fillMaxHeight().background(sourceColor(it.source)))
                }
            }
        }
        Column(Modifier.padding(top = 8.dp)) {
            sources.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 30.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(sourceColor(s.source)))
                    Text(sourceName(s.source), style = Type.body(13), color = Nocturne.text, modifier = Modifier.weight(1f))
                    Text(formatCount(s.total.toLong()), style = Type.body(13, FontWeight.Bold), color = Nocturne.text)
                    Text(
                        if (total > 0) "${Math.round(s.total.coerceAtLeast(0) * 100f / total)}%" else "—",
                        style = Type.body(12),
                        color = Nocturne.textSecondary,
                        modifier = Modifier.width(36.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.divider))
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clickable(role = Role.Button) { rulesOpen = !rulesOpen },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.ach_rules), style = Type.body(13, FontWeight.Bold), color = Nocturne.text, modifier = Modifier.weight(1f))
            Icon(
                Lucide.ChevronDown,
                null,
                tint = Nocturne.text,
                modifier = Modifier.size(16.dp).rotate(if (rulesOpen) 180f else 0f),
            )
        }
        AnimatedVisibility(
            rulesOpen,
            enter = if (reduceMotion) fadeIn(androidx.compose.animation.core.snap()) else expandVertically() + fadeIn(),
            exit = if (reduceMotion) fadeOut(androidx.compose.animation.core.snap()) else shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    R.string.upgrade2_src_like to R.string.upgrade2_src_like_desc,
                    R.string.upgrade2_src_accept to R.string.upgrade2_src_accept_desc,
                    R.string.upgrade2_src_featured to R.string.upgrade2_src_featured_desc,
                    R.string.upgrade2_src_achievement to R.string.upgrade2_src_achievement_desc,
                    R.string.upgrade2_src_task to R.string.upgrade2_src_task_desc,
                ).forEach { (name, desc) ->
                    Text(
                        "${stringResource(name)}：${stringResource(desc)}",
                        style = Type.body(12),
                        color = Nocturne.textSecondary,
                    )
                }
                Text(
                    stringResource(R.string.ach_rules_not_counted),
                    style = Type.body(12, FontWeight.Bold),
                    color = Nocturne.text,
                    modifier = Modifier.padding(top = 2.dp),
                )
                listOf(R.string.ach_rule_nc_1, R.string.ach_rule_nc_2, R.string.ach_rule_nc_3, R.string.ach_rule_nc_4).forEach {
                    Text("• ${stringResource(it)}", style = Type.body(12), color = Nocturne.textSecondary)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.divider))
        LinkLine(stringResource(R.string.ach_permissions), Nocturne.text, onPermissions)
    }
}

@Composable
fun LinkLine(text: String, ink: Color, onClick: () -> Unit, height: Dp = 40.dp) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Type.body(13, FontWeight.Bold), color = ink, modifier = Modifier.weight(1f))
        Icon(Lucide.ChevronRight, null, tint = ink, modifier = Modifier.size(16.dp))
    }
}
