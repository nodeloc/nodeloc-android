package com.nodeloc.app.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ArrowDownLeft
import com.composables.icons.lucide.ArrowUpRight
import com.composables.icons.lucide.CalendarCheck
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Coins
import com.composables.icons.lucide.Gift
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.Wallet
import com.composables.icons.lucide.WifiOff
import com.composables.icons.lucide.Zap
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FilterPill
import com.nodeloc.app.core.design.FilterPillRow
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.OnReachedEnd
import com.nodeloc.app.core.design.SkeletonBox
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.skeletonPulsing
import com.nodeloc.app.core.network.ApiCheckinStatus
import com.nodeloc.app.core.network.DiscourseError
import com.nodeloc.app.core.network.isOfflineError
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.core.util.rethrowIfCancellation
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.feature.moderation.ModScaffold
import com.nodeloc.app.feature.nav.Navigator
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The ledger's filter chips. Direction and kind group are two separate
 * server filters that intersect, so each chip sets exactly one of them;
 * the groups are the server's own (points/history.go `KindGroups`).
 */
enum class EnergyFilter(val labelRes: Int, val direction: String? = null, val group: String? = null) {
    All(R.string.energy_f_all),
    Income(R.string.energy_f_in, direction = "in"),
    Expense(R.string.energy_f_out, direction = "out"),
    Checkin(R.string.energy_f_checkin, group = "checkin"),
    Tip(R.string.energy_f_tip, group = "tip"),
    Games(R.string.energy_f_games, group = "games"),
    Reward(R.string.energy_f_reward, group = "reward"),
    Spend(R.string.energy_f_spend, group = "spend"),
    Other(R.string.energy_f_other, group = "other"),
}

data class EnergyState(
    val balance: Long? = null,
    val income: Long? = null,
    val expense: Long? = null,
    /** Null when the account is outside the board's first page, which is all the server serves. */
    val rank: Int? = null,
    val checkin: ApiCheckinStatus? = null,
    val isCheckingIn: Boolean = false,
    val filter: EnergyFilter = EnergyFilter.All,
    val entries: List<ApiEnergyEntry> = emptyList(),
    val nextBefore: Long? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: Throwable? = null,
)

class EnergyViewModel : ViewModel() {
    private val growth = GrowthApi.shared
    private val services = ServiceLocator.get
    private val _state = MutableStateFlow(EnergyState())
    val state: StateFlow<EnergyState> = _state.asStateFlow()
    private var listJob: Job? = null

    init {
        loadHeader()
        loadList()
    }

    fun retry() {
        loadHeader()
        loadList()
    }

    fun setFilter(filter: EnergyFilter) {
        if (filter == _state.value.filter) return
        _state.value = _state.value.copy(filter = filter, entries = emptyList(), nextBefore = null)
        loadList()
    }

    private fun loadHeader() {
        viewModelScope.launch {
            val balance = async { runCatchingCancellable { growth.balance() }.getOrNull() }
            val month = async { runCatchingCancellable { growth.monthSummary() }.getOrNull() }
            val checkin = async { runCatchingCancellable { growth.checkinStatus() }.getOrNull() }
            val board = async { runCatchingCancellable { growth.leaderboard() }.getOrNull() }
            val me = services.session.username
            val m = month.await()
            _state.value = _state.value.copy(
                balance = balance.await() ?: _state.value.balance,
                income = m?.income ?: _state.value.income,
                expense = m?.expense ?: _state.value.expense,
                checkin = checkin.await() ?: _state.value.checkin,
                rank = board.await()?.firstOrNull { it.username.equals(me, ignoreCase = true) }?.rank,
            )
        }
    }

    /** A filter change replaces the list; the job it supersedes must not write into it. */
    private fun loadList() {
        listJob?.cancel()
        val filter = _state.value.filter
        listJob = viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            runCatchingCancellable { growth.history(filter.direction, filter.group, before = null) }
                .onSuccess {
                    _state.value = _state.value.copy(entries = it.entries, nextBefore = it.nextBefore, isLoading = false)
                }
                .onFailure { _state.value = _state.value.copy(isLoading = false, error = it) }
        }
    }

    fun loadMore() {
        val current = _state.value
        val before = current.nextBefore ?: return
        if (current.isLoading || current.isLoadingMore) return
        val filter = current.filter
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoadingMore = true)
            runCatchingCancellable { growth.history(filter.direction, filter.group, before) }
                .onSuccess {
                    if (_state.value.filter != filter) return@onSuccess
                    _state.value = _state.value.copy(
                        entries = _state.value.entries + it.entries,
                        nextBefore = it.nextBefore,
                    )
                }
                .onFailure { ToastCenter.showError(it) }
            _state.value = _state.value.copy(isLoadingMore = false)
        }
    }

    /**
     * Same guard as the profile's: the endpoint rate-limits every attempt and
     * only forgives a successful one, so a known-claimed day is never sent.
     */
    fun checkin() {
        val current = _state.value
        if (current.isCheckingIn) return
        if (current.checkin?.checkedInToday == true) {
            ToastCenter.show(R.string.checkin_done)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isCheckingIn = true)
            try {
                val result = growth.checkin()
                services.preferences.setCheckinDate(result.day ?: LocalDate.now().toString())
                result.points?.let { ToastCenter.show(R.string.checkin_success, it) } ?: ToastCenter.show(R.string.checkin_done)
                loadHeader()
                loadList()
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                // Already claimed answers 4xx: the day is spent, which is what
                // the strip should now say rather than an error.
                if (error is DiscourseError.BadResponse && error.code in 400..499 && error.code != 401 && error.code != 429) {
                    services.preferences.setCheckinDate(LocalDate.now().toString())
                    ToastCenter.show(R.string.checkin_done)
                    loadHeader()
                } else {
                    ToastCenter.showError(error)
                }
            } finally {
                _state.value = _state.value.copy(isCheckingIn = false)
            }
        }
    }
}

@Composable
fun EnergyScreen(navigator: Navigator) {
    val viewModel: EnergyViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    listState.OnReachedEnd { viewModel.loadMore() }

    ModScaffold(title = stringResource(R.string.energy_title), onBack = navigator::back) {
        FilterPillRow {
            EnergyFilter.entries.forEach { f ->
                FilterPill(stringResource(f.labelRes), selected = f == state.filter) { viewModel.setFilter(f) }
            }
        }
        Box(Modifier.fillMaxSize().background(Nocturne.surface)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "balance") {
                    BalanceCard(state, onRank = navigator::openWealthRank, onCheckin = viewModel::checkin)
                }
                when {
                    state.entries.isEmpty() && state.isLoading -> item {
                        Column(Modifier.padding(horizontal = 16.dp).skeletonPulsing(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(5) { SkeletonBox(Modifier.fillMaxWidth().height(58.dp), corner = 14.dp) }
                        }
                    }
                    state.entries.isEmpty() && state.error != null -> item {
                        val offline = state.error?.isOfflineError() == true
                        EmptyStateView(
                            icon = if (offline) Lucide.WifiOff else Lucide.CircleAlert,
                            title = stringResource(if (offline) R.string.error_offline else R.string.energy_load_failed),
                            retryLabel = stringResource(R.string.common_retry),
                            onRetry = viewModel::retry,
                        )
                    }
                    state.entries.isEmpty() -> item { EmptyStateView(Lucide.Zap, stringResource(R.string.energy_empty)) }
                    else -> {
                        // Days as the ledger counts them: Beijing time, which is
                        // also how the server's monthly totals are cut.
                        val days = state.entries.groupBy { dayOf(it.createdAt) }
                        days.forEach { (day, entries) ->
                            item(key = "day-$day") {
                                GrowthSectionHead(dayLabel(day))
                                Column(
                                    Modifier
                                        .padding(top = 8.dp)
                                        .padding(horizontal = 16.dp)
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(Nocturne.bg),
                                ) {
                                    entries.forEachIndexed { index, entry ->
                                        EntryRow(entry, navigator)
                                        if (index < entries.lastIndex) {
                                            Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.neutral300))
                                        }
                                    }
                                }
                            }
                        }
                        item(key = "foot") {
                            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                                if (state.isLoadingMore) {
                                    NodelocLoader(height = 22.dp)
                                } else if (state.nextBefore == null) {
                                    Text(stringResource(R.string.energy_end), style = Type.body(12), color = Nocturne.textTertiary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BalanceCard(state: EnergyState, onRank: () -> Unit, onCheckin: () -> Unit) {
    GrowthCard(radius = 22.dp, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Nocturne.warningBg),
                contentAlignment = Alignment.Center,
            ) { Icon(Lucide.Zap, null, tint = Nocturne.warning, modifier = Modifier.size(24.dp)) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.energy_balance), style = Type.body(13), color = Nocturne.textSecondary)
                Text(
                    state.balance?.let(::formatCount) ?: "--",
                    style = Type.heading(30, FontWeight.Bold),
                    color = Nocturne.text,
                    maxLines = 1,
                )
            }
            // The design's 去兑换 has nowhere to go: the site has no exchange,
            // so the button is left out rather than pointing at nothing.
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Nocturne.surface)
                .padding(vertical = 10.dp),
        ) {
            Figure(state.income?.let(::formatSigned), stringResource(R.string.energy_month_income), Nocturne.accent, Modifier.weight(1f))
            Figure(state.expense?.let { formatSigned(-it) }, stringResource(R.string.energy_month_expense), Nocturne.danger, Modifier.weight(1f))
            Figure(
                state.rank?.toString(),
                stringResource(R.string.energy_rank),
                Nocturne.text,
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onRank),
            )
        }
        state.checkin?.let { status ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Nocturne.accent100.copy(alpha = if (Nocturne.isDark) 0.12f else 0.6f))
                    .clickable(enabled = !state.isCheckingIn, role = Role.Button, onClick = onCheckin)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Lucide.CalendarCheck, null, tint = Nocturne.accent, modifier = Modifier.size(18.dp))
                Text(
                    buildString {
                        append(stringResource(if (status.checkedInToday) R.string.energy_checked_in else R.string.energy_not_checked_in))
                        if (status.streak > 0) append(" · ").append(stringResource(R.string.energy_streak, status.streak))
                    },
                    style = Type.body(14, FontWeight.Medium),
                    color = Nocturne.text,
                    modifier = Modifier.weight(1f),
                )
                if (status.checkedInToday) {
                    status.todayPoints?.let {
                        Text(formatSigned(it.toLong()), style = Type.body(13, FontWeight.SemiBold), color = Nocturne.accent)
                    }
                } else {
                    Text(
                        stringResource(R.string.checkin_action),
                        style = Type.body(13, FontWeight.SemiBold),
                        color = Nocturne.accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun Figure(value: String?, label: String, ink: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value ?: "--", style = Type.body(19, FontWeight.Bold), color = if (value == null) Nocturne.textTertiary else ink, maxLines = 1)
        Text(label, style = Type.body(12), color = Nocturne.textSecondary, maxLines = 1)
    }
}

private val BEIJING: ZoneId = ZoneId.of("Asia/Shanghai")

private fun dayOf(raw: String?): LocalDate? = DiscourseFormat.instant(raw)?.atZone(BEIJING)?.toLocalDate()

@Composable
private fun dayLabel(day: LocalDate?): String {
    if (day == null) return ""
    val today = LocalDate.now(BEIJING)
    return when (day) {
        today -> stringResource(R.string.energy_today)
        today.minusDays(1) -> stringResource(R.string.energy_yesterday)
        else -> {
            val skeleton = if (day.year == today.year) "MMMdEEE" else "yMMMdEEE"
            val formatter = remember(skeleton) {
                DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton))
            }
            formatter.format(day)
        }
    }
}

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** What kind of entry this is, for the disc: ground, ink and glyph. */
@Composable
private fun entryStyle(entry: ApiEnergyEntry): Triple<Color, Color, ImageVector> {
    val kind = entry.kind
    return when {
        kind == "checkin" -> Triple(Nocturne.accent100, Nocturne.accent700, Lucide.CalendarCheck)
        kind == "tip" || kind == "tip_revoke" -> Triple(Nocturne.warningBg, Nocturne.warning, Lucide.Heart)
        kind.startsWith("red_envelope") || kind.startsWith("lottery") -> Triple(Nocturne.dangerBg, Nocturne.danger, Lucide.Gift)
        kind == "transfer" -> if (entry.amount >= 0) {
            Triple(Nocturne.infoBg, Nocturne.info, Lucide.ArrowDownLeft)
        } else {
            Triple(Nocturne.infoBg, Nocturne.info, Lucide.ArrowUpRight)
        }
        kind.endsWith("_reward") || kind.startsWith("invite_") || kind == "moderator_stipend" || kind == "app_award" ->
            Triple(Nocturne.warningBg, Nocturne.warning, Lucide.Star)
        kind in setOf("content_unlock", "pay", "pay_refund", "pay_transfer", "app_spend", "topup") ->
            Triple(Nocturne.neutral300, Nocturne.text, Lucide.Wallet)
        else -> Triple(Nocturne.neutral300, Nocturne.text, Lucide.Coins)
    }
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

@Composable
private fun entrySubtitle(entry: ApiEnergyEntry): String {
    val time = DiscourseFormat.instant(entry.createdAt)?.atZone(BEIJING)?.let(TIME::format).orEmpty()
    val note = entry.memo?.takeIf { entry.kind in NOTE_KINDS && it.isNotBlank() }?.let { "“$it”" }
    val detail = when {
        entry.kind == "checkin" && entry.streak > 0 -> stringResource(R.string.energy_k_streak, entry.streak)
        entry.ref != null && entry.ref.type != "user" -> entry.ref.label
        note != null -> note
        else -> entry.ref?.label
    }
    return listOfNotNull(detail?.takeIf { it.isNotBlank() }, time.takeIf { it.isNotEmpty() }).joinToString(" · ")
}

/** `/t/{id}` and `/u/{name}` are the only refs with somewhere to go in the app. */
private fun openRef(url: String?, navigator: Navigator): (() -> Unit)? {
    if (url == null) return null
    Regex("^/t/(\\d+)").find(url)?.let { match ->
        val id = match.groupValues[1].toIntOrNull() ?: return null
        val post = Regex("#p(\\d+)$").find(url)?.groupValues?.get(1)?.toIntOrNull()
        return { navigator.openTopic(id, post) }
    }
    Regex("^/u/([^/?#]+)").find(url)?.let { match ->
        val name = java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
        return { navigator.openProfile(name) }
    }
    return null
}

@Composable
private fun EntryRow(entry: ApiEnergyEntry, navigator: Navigator) {
    val (ground, ink, icon) = entryStyle(entry)
    val open = openRef(entry.ref?.url, navigator)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = open != null, onClick = { open?.invoke() })
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(ground), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = ink, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(entryTitle(entry), style = Type.body(15), color = Nocturne.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = entrySubtitle(entry)
            if (sub.isNotEmpty()) {
                Text(sub, style = Type.body(12), color = Nocturne.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            formatSigned(entry.amount),
            style = Type.body(16, FontWeight.Bold),
            color = if (entry.amount >= 0) Nocturne.accent else Nocturne.danger,
            maxLines = 1,
        )
    }
}
