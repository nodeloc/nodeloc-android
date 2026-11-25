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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trophy
import com.composables.icons.lucide.WifiOff
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.Avatar
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.SkeletonBox
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.skeletonPulsing
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
    /** The viewer's own balance, for the pinned row when they are not on the board. */
    val myBalance: Long? = null,
    val isLoading: Boolean = false,
    val error: Throwable? = null,
) {
    val myRow: ApiLeaderRow? get() = me?.let { name -> rows.firstOrNull { it.username.equals(name, ignoreCase = true) } }
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
            // The board is public; the balance is not, and is only asked for
            // when there is an account to ask about.
            val balance = async { if (me != null) runCatchingCancellable { growth.balance() }.getOrNull() else null }
            runCatchingCancellable { growth.leaderboard() }
                .onSuccess { _state.value = _state.value.copy(rows = it, isLoading = false) }
                .onFailure { _state.value = _state.value.copy(isLoading = false, error = it) }
            _state.value = _state.value.copy(myBalance = balance.await())
        }
    }
}

/**
 * The energy rich list.
 *
 * One board only: the server keeps all-time balances, with no weekly or
 * monthly cut, no tier and no movement since last time — so the design's
 * period tabs, tier line and arrows have nothing to show and are left out.
 */
@Composable
fun WealthRankScreen(navigator: Navigator) {
    val viewModel: WealthRankViewModel = viewModel()
    val state by viewModel.state.collectAsState()

    ModScaffold(
        title = stringResource(R.string.rank_title),
        onBack = navigator::back,
        bottomBar = state.me?.takeIf { state.rows.isNotEmpty() }?.let { me ->
            { MyRow(me, state.myRow, state.myBalance) { navigator.openProfile(me) } }
        },
    ) {
        Text(
            stringResource(R.string.rank_subtitle),
            style = Type.body(12),
            color = Nocturne.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
        Box(Modifier.fillMaxSize().background(Nocturne.surface)) {
            when {
                state.rows.isEmpty() && state.isLoading -> Column(
                    Modifier.padding(16.dp).skeletonPulsing(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SkeletonBox(Modifier.fillMaxWidth().height(200.dp), corner = 22.dp)
                    repeat(5) { SkeletonBox(Modifier.fillMaxWidth().height(56.dp), corner = 14.dp) }
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
                    contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "podium") { Podium(state.rows.take(3), navigator) }
                    val rest = state.rows.drop(3)
                    if (rest.isNotEmpty()) {
                        item(key = "list") {
                            Column(
                                Modifier
                                    .padding(horizontal = 16.dp)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Nocturne.bg),
                            ) {
                                rest.forEachIndexed { index, row ->
                                    RankRow(row, isMe = row.username.equals(state.me, ignoreCase = true)) {
                                        navigator.openProfile(row.username)
                                    }
                                    if (index < rest.lastIndex) {
                                        Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.neutral300))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Gold, silver, bronze, as close as the palette goes. */
@Composable
private fun medal(rank: Int): Color = when (rank) {
    1 -> Nocturne.warning
    2 -> Nocturne.neutral600
    else -> Nocturne.danger
}

@Composable
private fun Podium(top: List<ApiLeaderRow>, navigator: Navigator) {
    // Second, first, third: the winner stands in the middle, highest.
    val order = listOfNotNull(top.getOrNull(1), top.getOrNull(0), top.getOrNull(2))
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Nocturne.bg)
            .padding(start = 12.dp, end = 12.dp, top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        order.forEach { row ->
            val first = row.rank == 1
            PodiumStep(
                row = row,
                avatar = if (first) 56.dp else 48.dp,
                step = when (row.rank) {
                    1 -> 92.dp
                    2 -> 64.dp
                    else -> 48.dp
                },
                modifier = Modifier.weight(1f),
                onClick = { navigator.openProfile(row.username) },
            )
        }
    }
}

@Composable
private fun PodiumStep(row: ApiLeaderRow, avatar: Dp, step: Dp, modifier: Modifier, onClick: () -> Unit) {
    val tone = medal(row.rank)
    Column(
        modifier.clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(contentAlignment = Alignment.BottomCenter) {
            Avatar(row.username, variant = row.username.hashCode(), size = avatar)
            Box(
                Modifier
                    .offset(y = 8.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(tone),
                contentAlignment = Alignment.Center,
            ) {
                Text(row.rank.toString(), style = Type.body(12, FontWeight.Bold), color = Nocturne.bg)
            }
        }
        Text(
            row.username,
            style = Type.body(14, FontWeight.SemiBold),
            color = Nocturne.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(formatCount(row.balance), style = Type.body(12), color = Nocturne.textSecondary, maxLines = 1)
        Box(
            Modifier
                .fillMaxWidth()
                .height(step)
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .background(Brush.verticalGradient(listOf(tone.copy(alpha = 0.2f), tone.copy(alpha = 0.05f)))),
        )
    }
}

@Composable
private fun RankRow(row: ApiLeaderRow, isMe: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isMe) Nocturne.selected else Nocturne.bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            row.rank.toString(),
            style = Type.body(15, FontWeight.Bold),
            color = Nocturne.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(28.dp),
        )
        Avatar(row.username, variant = row.username.hashCode(), size = 36.dp)
        Text(
            row.username,
            style = Type.body(15, FontWeight.SemiBold),
            color = Nocturne.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(formatCount(row.balance), style = Type.body(15, FontWeight.Bold), color = Nocturne.text, maxLines = 1)
    }
}

/**
 * The viewer, pinned under the list whether or not they made it onto the
 * board. The server serves the first hundred only, so below that there is
 * no rank to show — the row says so rather than guessing one.
 */
@Composable
private fun MyRow(me: String, row: ApiLeaderRow?, balance: Long?, onClick: () -> Unit) {
    val ink = Nocturne.bg
    Row(
        Modifier
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Nocturne.text)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (row != null) {
            Text(row.rank.toString(), style = Type.body(15, FontWeight.Bold), color = ink)
        }
        Avatar(me, variant = me.hashCode(), size = 34.dp)
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.rank_me, me),
                style = Type.body(15, FontWeight.SemiBold),
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row == null) {
                Text(
                    stringResource(R.string.rank_not_listed, GrowthApi.LEADERBOARD_LIMIT),
                    style = Type.body(12),
                    color = ink.copy(alpha = 0.7f),
                )
            }
        }
        (row?.balance ?: balance)?.let {
            Text(formatCount(it), style = Type.body(15, FontWeight.Bold), color = ink, maxLines = 1)
        }
    }
}
