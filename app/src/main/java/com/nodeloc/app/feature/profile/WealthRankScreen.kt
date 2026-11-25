package com.nodeloc.app.feature.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Trophy
import com.composables.icons.lucide.WifiOff
import com.composables.icons.lucide.Zap
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.Avatar
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.PrimaryButton
import com.nodeloc.app.core.design.SkeletonBox
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.pressScale
import com.nodeloc.app.core.design.rememberPressSource
import com.nodeloc.app.core.design.skeletonPulsing
import com.nodeloc.app.core.network.ApiCheckinStatus
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.network.isOfflineError
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.feature.moderation.ModScaffold
import com.nodeloc.app.feature.nav.Navigator
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WealthRankState(
    val rows: List<ApiLeaderRow> = emptyList(),
    val me: String? = null,
    /** The viewer's own balance, for the standing card when they are not on the board. */
    val myBalance: Long? = null,
    val monthIncome: Long? = null,
    val checkin: ApiCheckinStatus? = null,
    val isCheckingIn: Boolean = false,
    val isLoading: Boolean = false,
    val error: Throwable? = null,
) {
    val myRow: ApiLeaderRow? get() = me?.let { name -> rows.firstOrNull { it.username.equals(name, ignoreCase = true) } }

    /** How far the viewer is behind the next place up; null at the top or off the board. */
    val gapAbove: Long?
        get() {
            val mine = myRow ?: return null
            val above = rows.lastOrNull { it.rank < mine.rank } ?: return null
            return (above.balance - mine.balance).takeIf { it > 0 }
        }
}

class WealthRankViewModel : ViewModel() {
    private val growth = GrowthApi.shared
    private val session = ServiceLocator.get.session
    private val _state = MutableStateFlow(WealthRankState())
    val state: StateFlow<WealthRankState> = _state.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        viewModelScope.launch {
            val me = session.username
            _state.value = _state.value.copy(me = me, isLoading = true, error = null)
            // The board is public; the rest is the viewer's own and is only
            // asked for when there is an account to ask about.
            val balance = async { if (me != null) runCatchingCancellable { growth.balance() }.getOrNull() else null }
            val month = async { if (me != null) runCatchingCancellable { growth.monthSummary() }.getOrNull() else null }
            val checkin = async { if (me != null) runCatchingCancellable { growth.checkinStatus() }.getOrNull() else null }
            runCatchingCancellable { growth.leaderboard() }
                .onSuccess { _state.value = _state.value.copy(rows = it, isLoading = false) }
                .onFailure { _state.value = _state.value.copy(isLoading = false, error = it) }
            _state.value = _state.value.copy(
                myBalance = balance.await() ?: _state.value.myBalance,
                monthIncome = month.await()?.income ?: _state.value.monthIncome,
                checkin = checkin.await() ?: _state.value.checkin,
            )
        }
    }

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
                if (claimDailyCheckin(growth) != CheckinOutcome.Failed) load()
            } finally {
                _state.value = _state.value.copy(isCheckingIn = false)
            }
        }
    }
}

/**
 * The energy rich list.
 *
 * The app reads the one board the API serves: all-time balances, first
 * hundred, with no tier, no movement and no weekly or monthly cut (the web's
 * hourly rank tables are not exposed). So the design's period tabs, tier line
 * and pager have nothing to show; the hundred scroll in one list instead.
 */
@Composable
fun WealthRankScreen(navigator: Navigator) {
    val viewModel: WealthRankViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val shareLabel = stringResource(R.string.rank_share)
    val title = stringResource(R.string.rank_heading)

    ModScaffold(
        title = title,
        onBack = navigator::back,
        actions = {
            HeaderIconButton(Lucide.Share2, contentDescription = shareLabel) { navigator.share(context, "${DiscourseConfig.BASE_URL}/rank", title) }
        },
    ) {
        when {
            state.rows.isEmpty() && state.isLoading -> Column(
                Modifier.padding(16.dp).skeletonPulsing(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SkeletonBox(Modifier.fillMaxWidth().height(190.dp), corner = 16.dp)
                repeat(5) { SkeletonBox(Modifier.fillMaxWidth().height(52.dp), corner = 12.dp) }
            }

            state.rows.isEmpty() && state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val offline = state.error?.isOfflineError() == true
                EmptyStateView(
                    icon = if (offline) Lucide.WifiOff else Lucide.CircleAlert,
                    title = stringResource(if (offline) R.string.error_offline else R.string.rank_load_failed),
                    retryLabel = stringResource(R.string.common_retry),
                    onRetry = viewModel::retry,
                )
            }

            state.rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyStateView(Lucide.Trophy, stringResource(R.string.rank_empty))
            }

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            ) {
                item(key = "subtitle") {
                    Text(stringResource(R.string.rank_subtitle), style = Type.body(14), color = Nocturne.textSecondary)
                }
                // Fewer than three on the board: they stay in the list rather
                // than standing on a podium with an empty step.
                val podium = state.rows.size >= 3
                if (podium) item(key = "podium") { Podium(state.rows.take(3), navigator) }
                state.me?.let { me ->
                    item(key = "standing") { Standing(me, state) { navigator.openProfile(me) } }
                }
                val rest = if (podium) state.rows.drop(3) else state.rows
                item(key = "list-gap") { Box(Modifier.height(8.dp)) }
                items(rest, key = { "r-${it.rank}-${it.username}" }) { row ->
                    RankRow(row, isMe = row.username.equals(state.me, ignoreCase = true)) { navigator.openProfile(row.username) }
                }
                if (state.me != null) {
                    item(key = "mine") {
                        MyEnergy(state, onLedger = navigator::openEnergyHistory, onCheckin = viewModel::checkin)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Podium

/** Gold, silver, bronze rings, as close as the palette goes. */
@Composable
private fun medal(rank: Int): Color = when (rank) {
    1 -> Nocturne.accent2500
    2 -> Nocturne.neutral600
    else -> Nocturne.accent2700
}

@Composable
private fun Podium(top: List<ApiLeaderRow>, navigator: Navigator) {
    // Second, first, third: the winner stands in the middle, tallest.
    val order = listOfNotNull(top.getOrNull(1), top.getOrNull(0), top.getOrNull(2))
    Row(
        Modifier.padding(top = 20.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        order.forEach { row ->
            PodiumCard(row, Modifier.weight(1f)) { navigator.openProfile(row.username) }
        }
    }
}

/**
 * The winner's card is the design's dark one, in the deepest accent with the
 * lightest neutral for ink, so it stays a dark card in both themes.
 */
@Composable
private fun PodiumCard(row: ApiLeaderRow, modifier: Modifier, onClick: () -> Unit) {
    val first = row.rank == 1
    val ground = if (first) Nocturne.accent900 else Nocturne.surface
    val ink = if (first) Nocturne.neutral100 else Nocturne.text
    val soft = if (first) Nocturne.neutral100.copy(alpha = 0.78f) else Nocturne.textSecondary
    val ring = medal(row.rank)
    val avatar: Dp = if (first) 60.dp else 48.dp
    val press = rememberPressSource()
    Column(
        modifier
            .pressScale(press)
            .clip(RoundedCornerShape(16.dp))
            .background(ground)
            .clickable(interactionSource = press, indication = ripple(), role = Role.Button, onClick = onClick)
            .padding(start = 8.dp, end = 8.dp, top = if (first) 24.dp else 16.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .height(28.dp)
                .widthIn(min = 28.dp)
                .clip(CircleShape)
                .background(if (first) ring else ring.copy(alpha = 0.22f))
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                row.rank.toString(),
                style = Type.heading(15, FontWeight.ExtraBold),
                color = if (first) Nocturne.accent900 else Nocturne.text,
            )
        }
        Box(
            Modifier.padding(top = 8.dp).size(avatar).border(BorderStroke(3.dp, ring), CircleShape).padding(3.dp),
            contentAlignment = Alignment.Center,
        ) {
            RemoteAvatar(row.avatarUrl, row.username, variant = row.username.hashCode(), size = avatar - 6.dp)
        }
        Text(
            row.username,
            style = Type.body(15, FontWeight.Bold),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            formatCount(row.balance),
            style = Type.heading(17, FontWeight.ExtraBold),
            color = ink,
            maxLines = 1,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(stringResource(R.string.rank_energy_unit), style = Type.body(12), color = soft)
    }
}

// -------------------------------------------------------------- Standing

/**
 * "你当前第 N 名": the viewer's place and how far the next place is. The
 * server serves the first hundred only, so below that the card says so
 * rather than guessing a rank.
 */
@Composable
private fun Standing(me: String, state: WealthRankState, onProfile: () -> Unit) {
    val row = state.myRow
    val balance = row?.balance ?: state.myBalance
    val account by ServiceLocator.get.session.currentUser.collectAsState()
    Row(
        Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (Nocturne.isDark) Nocturne.accent100 else Nocturne.accent100.copy(alpha = 0.6f))
            .border(BorderStroke(1.dp, Nocturne.accent300), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Off the board there is no row to carry a picture; the signed-in
        // account always has its own.
        val ownAvatar = row?.avatarUrl
            ?: account?.avatarTemplate?.let { DiscourseConfig.avatarUrl(it, 120) }
        if (ownAvatar != null) RemoteAvatar(ownAvatar, me, size = 40.dp)
        else Avatar(me, size = 40.dp, bg = Nocturne.accent, fg = Nocturne.neutral100)
        Column(Modifier.weight(1f)) {
            Text(
                if (row != null) stringResource(R.string.rank_you_are, row.rank)
                else stringResource(R.string.rank_not_listed, GrowthApi.LEADERBOARD_LIMIT),
                style = Type.body(15, FontWeight.Bold),
                color = Nocturne.text,
            )
            val line = listOfNotNull(
                balance?.let { stringResource(R.string.rank_energy_amount, formatCount(it)) },
                state.gapAbove?.let { stringResource(R.string.rank_gap, formatCount(it)) },
            ).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, style = Type.body(13), color = Nocturne.textSecondary)
        }
        PrimaryButton(stringResource(R.string.rank_my_profile), compact = true, onClick = onProfile)
    }
}

// ------------------------------------------------------------------ Rows

@Composable
private fun RankRow(row: ApiLeaderRow, isMe: Boolean, onClick: () -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .clip(RoundedCornerShape(8.dp))
                .then(if (isMe) Modifier.background(Nocturne.selected) else Modifier)
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                row.rank.toString(),
                style = Type.heading(16, FontWeight.ExtraBold),
                color = Nocturne.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(32.dp),
            )
            RemoteAvatar(row.avatarUrl, row.username, variant = row.username.hashCode(), size = 36.dp)
            Text(
                if (isMe) stringResource(R.string.rank_me, row.username) else row.username,
                style = Type.body(15, FontWeight.Bold),
                color = Nocturne.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(formatCount(row.balance), style = Type.body(15, FontWeight.Bold), color = Nocturne.text, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.divider))
    }
}

// ------------------------------------------------------------- My energy

/** 我的能量 and 如何获得能量, with the rules as the server actually pays them (web/rank.go `earnWays`). */
@Composable
private fun MyEnergy(state: WealthRankState, onLedger: () -> Unit, onCheckin: () -> Unit) {
    Column(
        Modifier
            .padding(top = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Nocturne.surface)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Lucide.Zap, null, tint = Nocturne.textSecondary, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.rank_my_energy), style = Type.body(12, FontWeight.Bold), color = Nocturne.textSecondary)
        }
        Text(
            (state.myRow?.balance ?: state.myBalance)?.let(::formatCount) ?: "--",
            style = Type.heading(32, FontWeight.ExtraBold),
            color = Nocturne.text,
            modifier = Modifier.padding(top = 4.dp),
        )
        state.monthIncome?.let {
            Text(stringResource(R.string.rank_month_gain, formatSigned(it)), style = Type.body(13), color = Nocturne.textSecondary)
        }
        val press = rememberPressSource()
        Row(
            Modifier
                .pressScale(press)
                .heightIn(min = 44.dp)
                .clickable(interactionSource = press, indication = null, role = Role.Button, onClick = onLedger),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.rank_view_ledger), style = Type.body(13, FontWeight.Bold), color = Nocturne.accent)
            Icon(Lucide.ChevronRight, null, tint = Nocturne.accent, modifier = Modifier.size(14.dp))
        }
        state.checkin?.let { status ->
            PrimaryButton(
                when {
                    status.checkedInToday -> stringResource(R.string.energy_checked_in)
                    status.todayPoints != null -> stringResource(R.string.rank_checkin_for, status.todayPoints)
                    else -> stringResource(R.string.checkin_action)
                },
                modifier = Modifier.padding(top = 4.dp),
                block = true,
                enabled = !status.checkedInToday && !state.isCheckingIn,
                onClick = onCheckin,
            )
        }
        Box(Modifier.padding(top = 16.dp, bottom = 12.dp).fillMaxWidth().height(1.dp).background(Nocturne.divider))
        Text(stringResource(R.string.rank_how_title), style = Type.body(14, FontWeight.Bold), color = Nocturne.text)
        val checkinGain = state.checkin?.todayPoints?.let { "+$it" } ?: stringResource(R.string.rank_way_checkin_gain)
        listOf(
            stringResource(R.string.energy_k_checkin) to checkinGain,
            stringResource(R.string.energy_k_featured_reward) to "+$FEATURED_REWARD",
            stringResource(R.string.rank_way_node) to "+$OWNER_REWARD_TOPIC / +$OWNER_REWARD_REPLY",
            stringResource(R.string.rank_way_games) to stringResource(R.string.rank_way_games_gain),
        ).forEach { (label, gain) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = Type.body(14), color = Nocturne.text, modifier = Modifier.weight(1f))
                Text(gain, style = Type.body(14, FontWeight.Bold), color = Nocturne.accent700)
            }
        }
        Text(
            stringResource(R.string.rank_uses),
            style = Type.body(12),
            color = Nocturne.textSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

// The server's fixed grants (play/grants.go, play/ownerrewards.go). Invite and
// task rewards are site settings the API does not expose, so they are not listed.
private const val FEATURED_REWARD = 200
private const val OWNER_REWARD_TOPIC = 5
private const val OWNER_REWARD_REPLY = 1
