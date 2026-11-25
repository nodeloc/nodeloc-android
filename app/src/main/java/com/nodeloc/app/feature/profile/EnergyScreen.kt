package com.nodeloc.app.feature.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ArrowDownLeft
import com.composables.icons.lucide.ArrowUpRight
import com.composables.icons.lucide.CalendarCheck
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Coins
import com.composables.icons.lucide.Gift
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.ShoppingBag
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.WifiOff
import com.composables.icons.lucide.Zap
import com.nodeloc.app.R
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FilterPill
import com.nodeloc.app.core.design.FilterPillRow
import com.nodeloc.app.core.design.LocalReduceMotion
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.OnReachedEnd
import com.nodeloc.app.core.design.SecondaryButton
import com.nodeloc.app.core.design.SkeletonBox
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.animatedColor
import com.nodeloc.app.core.design.pressScale
import com.nodeloc.app.core.design.rememberPressSource
import com.nodeloc.app.core.design.skeletonPulsing
import com.nodeloc.app.core.network.isOfflineError
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.feature.moderation.ModScaffold
import com.nodeloc.app.feature.nav.Navigator
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun EnergyScreen(navigator: Navigator) {
    val viewModel: EnergyViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    listState.OnReachedEnd { viewModel.loadMore() }
    // One row open at a time, as on the web: the detail is a glance, not a comparison.
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    var transferring by rememberSaveable { mutableStateOf(false) }

    ModScaffold(title = stringResource(R.string.energy_title), onBack = navigator::back) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp),
        ) {
            item(key = "hero") {
                BalanceHero(
                    state,
                    onRank = navigator::openWealthRank,
                    onCheckin = viewModel::checkin,
                    onTransfer = { transferring = true },
                )
            }
            item(key = "filters") {
                Filters(
                    state,
                    onDirection = { openId = null; viewModel.setDirection(it) },
                    onGroup = { openId = null; viewModel.setGroup(it) },
                    onMonth = { openId = null; viewModel.setMonth(it) },
                )
            }
            when {
                state.entries.isEmpty() && state.isLoading -> item(key = "skeleton") {
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 16.dp).skeletonPulsing(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        repeat(5) { SkeletonBox(Modifier.fillMaxWidth().height(56.dp), corner = 12.dp) }
                    }
                }
                state.entries.isEmpty() && state.error != null -> item(key = "error") {
                    val offline = state.error?.isOfflineError() == true
                    EmptyStateView(
                        icon = if (offline) Lucide.WifiOff else Lucide.CircleAlert,
                        title = stringResource(if (offline) R.string.error_offline else R.string.energy_load_failed),
                        retryLabel = stringResource(R.string.common_retry),
                        onRetry = viewModel::retry,
                    )
                }
                state.entries.isEmpty() -> item(key = "empty") { EmptyLedger(state, onReset = viewModel::resetFilters) }
                else -> {
                    // Days as the ledger counts them: Beijing time, which is also
                    // how the server cuts months. The day's totals only cover what
                    // has loaded, as the web's do; a half-loaded day fills in as it pages.
                    val days = state.entries.groupBy { dayOf(it.createdAt) }
                    days.forEach { (day, entries) ->
                        item(key = "day-$day") { DayHead(day, entries) }
                        items(entries, key = { "e-${it.id}" }) { entry ->
                            EntryRow(
                                entry,
                                expanded = openId == entry.id,
                                navigator = navigator,
                                onToggle = { openId = if (openId == entry.id) null else entry.id },
                            )
                        }
                    }
                    item(key = "foot") { LedgerFoot(state, onMore = viewModel::loadMore) }
                }
            }
        }
    }

    if (transferring) {
        TransferSheet(viewModel = viewModel, onDismiss = { transferring = false })
    }
}

// ------------------------------------------------------------------ Hero

/**
 * The design's dark balance card. Its ground is the deepest accent and the
 * ink the lightest neutral, so it reads the same dark-on-light card in both
 * themes instead of inverting with the page.
 */
@Composable
private fun BalanceHero(state: EnergyState, onRank: () -> Unit, onCheckin: () -> Unit, onTransfer: () -> Unit) {
    val ink = Nocturne.neutral100
    val soft = ink.copy(alpha = 0.78f)
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Nocturne.accent900)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Lucide.Zap, null, tint = Nocturne.accent2500, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.energy_current), style = Type.body(13), color = soft)
        }
        Text(
            state.balance?.let(::formatCount) ?: "--",
            style = Type.heading(32, FontWeight.ExtraBold),
            color = ink,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            Modifier.padding(top = 12.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.checkin?.let { status ->
                val press = rememberPressSource()
                Row(
                    Modifier
                        .pressScale(press)
                        .height(40.dp)
                        .clip(CircleShape)
                        .background(if (status.checkedInToday) ink.copy(alpha = 0.12f) else Nocturne.accent)
                        .clickable(
                            interactionSource = press,
                            indication = ripple(),
                            enabled = !status.checkedInToday && !state.isCheckingIn,
                            role = Role.Button,
                            onClick = onCheckin,
                        )
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Lucide.CalendarCheck, null, tint = if (status.checkedInToday) soft else ink, modifier = Modifier.size(16.dp))
                    Text(
                        buildString {
                            append(stringResource(if (status.checkedInToday) R.string.energy_checked_in else R.string.checkin_action))
                            if (status.streak > 0) append(" · ").append(stringResource(R.string.energy_streak, status.streak))
                        },
                        style = Type.body(14, if (status.checkedInToday) FontWeight.Medium else FontWeight.Bold),
                        color = if (status.checkedInToday) soft else ink,
                        maxLines = 1,
                    )
                }
            }
            val press = rememberPressSource()
            Row(
                Modifier
                    .pressScale(press)
                    .height(40.dp)
                    .clip(CircleShape)
                    .border(BorderStroke(1.dp, ink.copy(alpha = 0.35f)), CircleShape)
                    .clickable(interactionSource = press, indication = ripple(), role = Role.Button, onClick = onTransfer)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Lucide.Send, null, tint = ink, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.energy_transfer), style = Type.body(14, FontWeight.Bold), color = ink, maxLines = 1)
            }
        }
        Box(Modifier.padding(top = 16.dp).fillMaxWidth().height(1.dp).background(ink.copy(alpha = 0.14f)))
        Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeroFigure(stringResource(R.string.energy_month_income), state.income?.let(::formatSigned), Nocturne.accent300, soft, Modifier.weight(1f))
            HeroFigure(stringResource(R.string.energy_month_expense), state.expense?.let { formatSigned(-it) }, ink, soft, Modifier.weight(1f))
            HeroFigure(
                stringResource(R.string.energy_rank),
                when {
                    state.rank != null -> stringResource(R.string.energy_rank_value, state.rank)
                    // Off the first page of the board, which is all the server serves.
                    state.balance != null && state.rankLoaded -> stringResource(R.string.energy_rank_outside, GrowthApi.LEADERBOARD_LIMIT)
                    else -> null
                },
                ink,
                soft,
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onRank),
            )
        }
    }
}

@Composable
private fun HeroFigure(label: String, value: String?, ink: Color, soft: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = Type.body(12), color = soft, maxLines = 1)
        Text(value ?: "--", style = Type.body(16, FontWeight.Bold), color = if (value == null) soft else ink, maxLines = 1)
    }
}

// --------------------------------------------------------------- Filters

@Composable
private fun Filters(
    state: EnergyState,
    onDirection: (EnergyDirection) -> Unit,
    onGroup: (EnergyGroup) -> Unit,
    onMonth: (YearMonth?) -> Unit,
) {
    Column(Modifier.padding(top = 16.dp)) {
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Segmented(
                options = EnergyDirection.entries.map { stringResource(it.labelRes) },
                selected = state.direction.ordinal,
                onSelect = { onDirection(EnergyDirection.entries[it]) },
            )
            Spacer(Modifier.weight(1f))
            MonthPicker(state.month, onMonth)
        }
        FilterPillRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)) {
            EnergyGroup.entries.forEach { g ->
                FilterPill(stringResource(g.labelRes), selected = g == state.group) { onGroup(g) }
            }
        }
    }
}

/** The design's segmented control: a grey track with the chosen segment raised in white. */
@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(Nocturne.neutral400).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val on = index == selected
            val ground by animatedColor(if (on) Nocturne.bg else Nocturne.neutral400, "segGround")
            val ink by animatedColor(if (on) Nocturne.text else Nocturne.textSecondary, "segInk")
            val press = rememberPressSource()
            Box(
                Modifier
                    .pressScale(press)
                    .height(36.dp)
                    .then(if (on) Modifier.shadow(1.dp, CircleShape) else Modifier)
                    .clip(CircleShape)
                    .background(ground)
                    .semantics { this.selected = on }
                    .clickable(interactionSource = press, indication = ripple(), role = Role.Tab) { onSelect(index) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Type.body(14, FontWeight.Bold), color = ink, maxLines = 1)
            }
        }
    }
}

/**
 * Months the ledger can be cut by. The server would say which months hold
 * entries only to the web page, so the last year is offered and an empty one
 * says so rather than being hidden.
 */
private const val MONTHS_OFFERED = 12

@Composable
private fun MonthPicker(month: YearMonth?, onMonth: (YearMonth?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val formatter = remember {
        DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "yMMM"))
    }
    val allTime = stringResource(R.string.energy_month_all)
    val pickLabel = stringResource(R.string.energy_month_pick)
    Box {
        val press = rememberPressSource()
        Row(
            Modifier
                .pressScale(press)
                .height(40.dp)
                .clip(CircleShape)
                .border(BorderStroke(1.dp, Nocturne.divider), CircleShape)
                .semantics { contentDescription = pickLabel }
                .clickable(interactionSource = press, indication = ripple(), role = Role.DropdownList) { open = true }
                .padding(start = 14.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(month?.let(formatter::format) ?: allTime, style = Type.body(14, FontWeight.Medium), color = Nocturne.text, maxLines = 1)
            Icon(Lucide.ChevronDown, null, tint = Nocturne.textSecondary, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Nocturne.bg) {
            val current = YearMonth.now(BEIJING)
            (listOf<YearMonth?>(null) + (0 until MONTHS_OFFERED).map { current.minusMonths(it.toLong()) }).forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option?.let(formatter::format) ?: allTime,
                            style = Type.body(14, if (option == month) FontWeight.Bold else FontWeight.Normal),
                            color = if (option == month) Nocturne.accent else Nocturne.text,
                        )
                    },
                    onClick = { open = false; onMonth(option) },
                )
            }
        }
    }
}

// ----------------------------------------------------------------- Days

private val BEIJING: ZoneId = ZoneId.of("Asia/Shanghai")

private fun dayOf(raw: String?): LocalDate? = DiscourseFormat.instant(raw)?.atZone(BEIJING)?.toLocalDate()

@Composable
private fun DayHead(day: LocalDate?, entries: List<ApiEnergyEntry>) {
    val income = entries.filter { it.amount > 0 }.sumOf { it.amount }
    val expense = entries.filter { it.amount < 0 }.sumOf { -it.amount }
    val inLabel = stringResource(R.string.energy_day_in, formatSigned(income))
    val outLabel = stringResource(R.string.energy_day_out, formatSigned(-expense))
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (day != null) {
                val (date, note) = dayLabel(day)
                Text(date, style = Type.body(14, FontWeight.Bold), color = Nocturne.text, maxLines = 1)
                Text(note, style = Type.body(14), color = Nocturne.textSecondary, maxLines = 1, modifier = Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                listOfNotNull(inLabel.takeIf { income > 0 }, outLabel.takeIf { expense > 0 }).joinToString(" · "),
                style = Type.body(12),
                color = Nocturne.textSecondary,
                maxLines = 1,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.neutral500.copy(alpha = 0.5f)))
    }
}

/** "11月15日" and its note: 今天, 昨天, or the weekday. */
@Composable
private fun dayLabel(day: LocalDate): Pair<String, String> {
    val today = LocalDate.now(BEIJING)
    val skeleton = if (day.year == today.year) "MMMd" else "yMMMd"
    val date = remember(skeleton, day) {
        DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)).format(day)
    }
    val note = when (day) {
        today -> stringResource(R.string.energy_today)
        today.minusDays(1) -> stringResource(R.string.energy_yesterday)
        else -> remember(day) { DateTimeFormatter.ofPattern("EEE", Locale.getDefault()).format(day) }
    }
    return date to note
}

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val FULL_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")

// ------------------------------------------------------------------ Rows

/**
 * What an entry is, as the web ledger names and colours it (static/energy.js
 * `TYPES`): the detail's 类型 line and the disc. Tips and transfers split by
 * direction because "收到打赏" and "打赏他人" are different events to a reader.
 */
private enum class EntryType(val labelRes: Int) {
    Checkin(R.string.energy_t_checkin),
    TipIn(R.string.energy_t_tip_in),
    TipOut(R.string.energy_t_tip_out),
    Games(R.string.energy_t_games),
    Reward(R.string.energy_t_reward),
    Invite(R.string.energy_t_invite),
    SendIn(R.string.energy_t_send_in),
    SendOut(R.string.energy_t_send_out),
    Spend(R.string.energy_t_spend),
    Other(R.string.energy_t_other),
}

private val GAMES = setOf("red_envelope_fund", "red_envelope_claim", "red_envelope_refund", "lottery_ticket", "lottery_payout", "lottery_refund")
private val REWARDS = setOf(
    "featured_reward", "featured_reward_revoke", "node_owner_reward", "node_owner_reward_revoke", "moderator_stipend",
    "system_reward", "app_award", "app_award_revert", "task_reward", "onboarding_reward",
)
private val INVITES = setOf("invite_reward", "invite_welcome", "invite_level_reward", "invite_second_reward", "invite_milestone")
private val SPENDS = setOf("content_unlock", "pay", "pay_refund", "pay_transfer", "app_spend")

private fun ApiEnergyEntry.type(): EntryType = when {
    kind == "checkin" -> EntryType.Checkin
    kind == "tip" || kind == "tip_revoke" -> if (amount > 0) EntryType.TipIn else EntryType.TipOut
    kind == "transfer" -> if (amount > 0) EntryType.SendIn else EntryType.SendOut
    kind in GAMES -> EntryType.Games
    kind in INVITES -> EntryType.Invite
    kind in REWARDS -> EntryType.Reward
    kind in SPENDS -> EntryType.Spend
    else -> EntryType.Other
}

@Composable
private fun EntryType.disc(): Triple<Color, Color, ImageVector> = when (this) {
    EntryType.Checkin -> Triple(Nocturne.accent100, Nocturne.accent, Lucide.CalendarCheck)
    EntryType.TipIn -> Triple(Nocturne.love.copy(alpha = 0.12f), Nocturne.love, Lucide.Heart)
    EntryType.TipOut -> Triple(Nocturne.neutral400, Nocturne.text, Lucide.Heart)
    EntryType.Games -> Triple(Nocturne.dangerBg, Nocturne.danger, Lucide.Gift)
    EntryType.Reward, EntryType.Invite -> Triple(Nocturne.warningBg, Nocturne.warning, Lucide.Star)
    EntryType.SendIn -> Triple(Nocturne.infoBg, Nocturne.info, Lucide.ArrowDownLeft)
    EntryType.SendOut -> Triple(Nocturne.infoBg, Nocturne.info, Lucide.ArrowUpRight)
    EntryType.Spend -> Triple(Nocturne.neutral400, Nocturne.text, Lucide.ShoppingBag)
    EntryType.Other -> Triple(Nocturne.neutral400, Nocturne.text, Lucide.Coins)
}

/** The web ledger's titles (static/energy.js `TITLES`), with the other party named where there is one. */
@Composable
private fun entryTitle(entry: ApiEnergyEntry): String {
    val who = entry.counterpart?.takeIf { it.isNotBlank() }
    val incoming = entry.amount > 0
    // (with the other party, without) for the two kinds that name one.
    val named: Pair<Int, Int>? = when (entry.kind) {
        "tip" -> if (incoming) R.string.energy_k_tip_in_from to R.string.energy_k_tip_in
        else R.string.energy_k_tip_out_to to R.string.energy_k_tip_out
        "transfer" -> if (incoming) R.string.energy_k_transfer_in_from to R.string.energy_k_transfer_in
        else R.string.energy_k_transfer_out_to to R.string.energy_k_transfer_out
        else -> null
    }
    if (named != null) return if (who != null) stringResource(named.first, who) else stringResource(named.second)
    return when (entry.kind) {
        "checkin" -> stringResource(R.string.energy_k_checkin)
        "tip_revoke" -> stringResource(R.string.energy_k_tip_revoke)
        "content_unlock" -> stringResource(if (incoming) R.string.energy_k_content_unlock_in else R.string.energy_k_content_unlock_out)
        else -> KIND_TITLES[entry.kind]?.let { stringResource(it) }
            ?: entry.memo?.takeIf { it.isNotBlank() }
            ?: entry.kind
    }
}

private val KIND_TITLES = mapOf(
    "red_envelope_fund" to R.string.energy_k_red_envelope_fund,
    "red_envelope_claim" to R.string.energy_k_red_envelope_claim,
    "red_envelope_refund" to R.string.energy_k_red_envelope_refund,
    "lottery_ticket" to R.string.energy_k_lottery_ticket,
    "lottery_payout" to R.string.energy_k_lottery_payout,
    "lottery_refund" to R.string.energy_k_lottery_refund,
    "featured_reward" to R.string.energy_k_featured_reward,
    "featured_reward_revoke" to R.string.energy_k_featured_reward_revoke,
    "node_owner_reward" to R.string.energy_k_node_owner_reward,
    "node_owner_reward_revoke" to R.string.energy_k_node_owner_reward_revoke,
    "moderator_stipend" to R.string.energy_k_moderator_stipend,
    "system_reward" to R.string.energy_k_system_reward,
    "system_deduct" to R.string.energy_k_system_deduct,
    "app_award" to R.string.energy_k_app_award,
    "app_spend" to R.string.energy_k_app_spend,
    "app_award_revert" to R.string.energy_k_app_award_revert,
    "task_reward" to R.string.energy_k_task_reward,
    "onboarding_reward" to R.string.energy_k_onboarding_reward,
    "invite_reward" to R.string.energy_k_invite_reward,
    "invite_welcome" to R.string.energy_k_invite_welcome,
    "invite_level_reward" to R.string.energy_k_invite_level_reward,
    "invite_second_reward" to R.string.energy_k_invite_second_reward,
    "invite_milestone" to R.string.energy_k_invite_milestone,
    "pay" to R.string.energy_k_pay,
    "pay_refund" to R.string.energy_k_pay_refund,
    "pay_transfer" to R.string.energy_k_pay_transfer,
    "topup" to R.string.energy_k_topup,
    "admin_adjust" to R.string.energy_k_admin_adjust,
    "risk_clawback" to R.string.energy_k_risk_clawback,
    "risk_clawback_revert" to R.string.energy_k_risk_clawback_revert,
    "move_penalty" to R.string.energy_k_move_penalty,
    "account_deleted" to R.string.energy_k_account_deleted,
)

/**
 * Only these kinds carry a memo a person wrote (a tip's or transfer's note,
 * an admin's reason); on every other kind it is the system's own wording,
 * which the title already says better.
 */
private val NOTE_KINDS = setOf("tip", "transfer", "admin_adjust")

private fun ApiEnergyEntry.note(): String? = memo?.takeIf { kind in NOTE_KINDS && it.isNotBlank() }

/** The line under the title, after the time: what the entry was about, as the web picks it. */
@Composable
private fun entryDetail(entry: ApiEnergyEntry): String? = when {
    entry.kind == "checkin" -> if (entry.streak > 0) stringResource(R.string.energy_k_streak, entry.streak) else null
    entry.ref != null && entry.ref.type != "user" -> entry.ref.label
    entry.note() != null -> entry.note()
    entry.ref != null -> entry.ref.label
    entry.kind in NOTE_KINDS -> null
    else -> entry.memo
}?.takeIf { it.isNotBlank() }

/** `/t/{id}` and `/u/{name}` are the only refs with somewhere to go in the app. */
private fun openRef(url: String?, navigator: Navigator): (() -> Unit)? {
    if (url == null) return null
    Regex("^/t/(\\d+)").find(url)?.let { match ->
        val id = match.groupValues[1].toIntOrNull() ?: return null
        // The server links a reply as `#post-N` (points/refs.go postURL).
        val post = Regex("#post-(\\d+)$").find(url)?.groupValues?.get(1)?.toIntOrNull()
        return { navigator.openTopic(id, post) }
    }
    Regex("^/u/([^/?#]+)").find(url)?.let { match ->
        val name = java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
        return { navigator.openProfile(name) }
    }
    return null
}

@Composable
private fun EntryRow(entry: ApiEnergyEntry, expanded: Boolean, navigator: Navigator, onToggle: () -> Unit) {
    val type = entry.type()
    val (ground, ink, icon) = type.disc()
    val reduceMotion = LocalReduceMotion.current
    val turn by animateFloatAsState(if (expanded) 180f else 0f, if (reduceMotion) tween(0) else tween(180), label = "chevron")
    val instant = DiscourseFormat.instant(entry.createdAt)?.atZone(BEIJING)
    val expandedLabel = stringResource(if (expanded) R.string.energy_row_expanded else R.string.energy_row_collapsed)
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .semantics { stateDescription = expandedLabel }
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(ground), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = ink, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(entryTitle(entry), style = Type.body(15, FontWeight.Medium), color = Nocturne.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(instant?.let(TIME::format), entryDetail(entry)).joinToString(" · ")
                if (sub.isNotEmpty()) {
                    Text(sub, style = Type.body(13), color = Nocturne.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatSigned(entry.amount),
                    style = Type.body(16, FontWeight.Bold),
                    color = if (entry.amount >= 0) Nocturne.accent700 else Nocturne.text,
                    maxLines = 1,
                )
                entry.balanceAfter?.let {
                    Text(stringResource(R.string.energy_balance_after, formatCount(it)), style = Type.body(12), color = Nocturne.textSecondary, maxLines = 1)
                }
            }
            Icon(Lucide.ChevronDown, null, tint = Nocturne.textTertiary, modifier = Modifier.size(18.dp).rotate(turn))
        }
        AnimatedVisibility(
            visible = expanded,
            enter = if (reduceMotion) fadeIn(tween(0)) else expandVertically() + fadeIn(),
            exit = if (reduceMotion) fadeOut(tween(0)) else shrinkVertically() + fadeOut(),
        ) {
            EntryDetail(entry, type, instant?.let(FULL_TIME::format), navigator)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.divider))
    }
}

@Composable
private fun EntryDetail(entry: ApiEnergyEntry, type: EntryType, time: String?, navigator: Navigator) {
    Column(
        Modifier
            .padding(bottom = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Nocturne.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        entry.no?.takeIf { it.isNotBlank() }?.let { DetailLine(stringResource(R.string.energy_d_no), it) }
        time?.let { DetailLine(stringResource(R.string.energy_d_time), it) }
        DetailLine(stringResource(R.string.energy_d_type), stringResource(type.labelRes))
        val ref = entry.ref
        val who = entry.counterpart?.takeIf { it.isNotBlank() }
        when {
            ref != null && !ref.label.isNullOrBlank() ->
                DetailLine(stringResource(R.string.energy_d_ref), ref.label, openRef(ref.url, navigator))
            who != null ->
                DetailLine(stringResource(R.string.energy_d_ref), "u/$who") { navigator.openProfile(who) }
            !entry.memo.isNullOrBlank() && entry.kind !in NOTE_KINDS ->
                DetailLine(stringResource(R.string.energy_d_memo), entry.memo)
        }
        entry.note()?.let {
            DetailLine(
                stringResource(if (entry.kind == "admin_adjust") R.string.energy_d_reason else R.string.energy_d_note),
                "“$it”",
            )
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = Type.body(13), color = Nocturne.textSecondary, modifier = Modifier.width(72.dp))
        Text(
            value,
            style = Type.body(13, if (onClick != null) FontWeight.Medium else FontWeight.Normal),
            color = if (onClick != null) Nocturne.accent else Nocturne.text,
            modifier = Modifier
                .weight(1f)
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        )
    }
}

// ------------------------------------------------------------ List edges

@Composable
private fun LedgerFoot(state: EnergyState, onMore: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            state.isLoadingMore -> NodelocLoader(height = 22.dp)
            state.nextBefore != null -> {
                // Scrolling to the end also pages; the button is there for the
                // reader who stops short, and after a failed page.
                SecondaryButton(stringResource(R.string.energy_load_more), onClick = onMore)
                state.total?.let {
                    Text(
                        stringResource(R.string.energy_shown, state.entries.size, it),
                        style = Type.body(12),
                        color = Nocturne.textSecondary,
                    )
                }
            }
            else -> Text(stringResource(R.string.energy_end), style = Type.body(13), color = Nocturne.textSecondary)
        }
    }
}

@Composable
private fun EmptyLedger(state: EnergyState, onReset: () -> Unit) {
    val filtered = state.isFiltered
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Nocturne.surface), contentAlignment = Alignment.Center) {
            Icon(Lucide.Zap, null, tint = Nocturne.textSecondary, modifier = Modifier.size(24.dp))
        }
        Text(
            stringResource(
                when {
                    !filtered -> R.string.energy_empty
                    state.direction == EnergyDirection.Out -> R.string.energy_empty_out
                    state.direction == EnergyDirection.In -> R.string.energy_empty_in
                    else -> R.string.energy_empty_filtered
                },
            ),
            style = Type.body(16, FontWeight.Bold),
            color = Nocturne.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            stringResource(if (filtered) R.string.energy_empty_filtered_hint else R.string.energy_empty_hint),
            style = Type.body(14),
            color = Nocturne.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (filtered) {
            SecondaryButton(stringResource(R.string.energy_show_all), modifier = Modifier.padding(top = 14.dp), onClick = onReset)
        }
    }
}
