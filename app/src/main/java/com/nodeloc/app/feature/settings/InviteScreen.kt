package com.nodeloc.app.feature.settings

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Share
import com.composables.icons.lucide.Trophy
import com.composables.icons.lucide.UserPlus
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.PillTone
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.StatusPill
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.network.ApiInviteFriend
import com.nodeloc.app.core.network.ApiInviteOverview
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.network.PersonalApi
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.feature.feed.RowsSkeleton
import com.nodeloc.app.feature.moderation.ModScaffold
import com.nodeloc.app.feature.moderation.ModSectionLabel
import com.nodeloc.app.feature.nav.Navigator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.NumberFormat

data class InviteState(
    val loading: Boolean = true,
    val overview: ApiInviteOverview? = null,
    /** Null when this account's level cannot invite yet — the page still opens and says so. */
    val code: String? = null,
    val error: Throwable? = null,
)

class InviteViewModel : ViewModel() {
    private val api = PersonalApi(ServiceLocator.get.client.api)

    private val _state = MutableStateFlow(InviteState())
    val state: StateFlow<InviteState> = _state.asStateFlow()

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatchingCancellable { api.inviteOverview() }
                .onSuccess { _state.value = InviteState(loading = false, overview = it.overview, code = it.code) }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it) }
        }
    }

    /**
     * The button goes on the tap. The server allows one reminder a day, so a
     * second tap while the first is in flight would only earn a 429.
     */
    fun remind(friend: ApiInviteFriend) {
        setRemindable(friend.id, false)
        viewModelScope.launch {
            runCatchingCancellable { api.remindFriend(friend.id) }
                .onSuccess { ToastCenter.show(R.string.invite_remind_sent) }
                .onFailure {
                    setRemindable(friend.id, true)
                    ToastCenter.showError(it)
                }
        }
    }

    private fun setRemindable(id: Long, value: Boolean) {
        val overview = _state.value.overview ?: return
        _state.value = _state.value.copy(
            overview = overview.copy(friends = overview.friends.map { if (it.id == id) it.copy(canRemind = value) else it }),
        )
    }
}

@Composable
fun InviteScreen(navigator: Navigator) {
    val viewModel: InviteViewModel = viewModel()
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { if (state.overview == null) viewModel.load() }

    ModScaffold(stringResource(R.string.invite_title), navigator::back) {
        val overview = state.overview
        when {
            overview == null && state.loading -> RowsSkeleton(rows = 6)
            overview == null -> EmptyStateView(
                icon = Lucide.UserPlus,
                title = stringResource(R.string.invite_load_failed),
                retryLabel = stringResource(R.string.common_retry),
                onRetry = viewModel::load,
            )
            else -> InviteBody(overview, state.code, navigator, viewModel::remind)
        }
    }
}

@Composable
private fun InviteBody(
    overview: ApiInviteOverview,
    code: String?,
    navigator: Navigator,
    onRemind: (ApiInviteFriend) -> Unit,
) {
    val rules = rewardRules(overview)
    LazyColumn(
        Modifier.fillMaxSize().background(Nocturne.surface),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Hero(overview, code, navigator) }
        item { Stats(overview) }

        if (rules.isNotEmpty()) {
            item { ModSectionLabel(stringResource(R.string.invite_rules), Modifier.padding(horizontal = 4.dp)) }
            item {
                InviteCard {
                    rules.forEachIndexed { index, (label, amount) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                Modifier.size(26.dp).clip(CircleShape).background(Nocturne.accent100),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${index + 1}", style = Type.body(13, FontWeight.Bold), color = Nocturne.accent)
                            }
                            Text(label, style = Type.body(14), color = Nocturne.text, modifier = Modifier.weight(1f))
                            Text(amount, style = Type.body(13, FontWeight.Bold), color = Nocturne.accent)
                        }
                    }
                }
            }
        }

        if (overview.milestones.isNotEmpty()) {
            item { ModSectionLabel(stringResource(R.string.invite_milestones), Modifier.padding(horizontal = 4.dp)) }
            item { Milestone(overview) }
        }

        item {
            ModSectionLabel(stringResource(R.string.invite_friends), Modifier.padding(horizontal = 4.dp)) {
                if (overview.friends.isNotEmpty()) {
                    Text(
                        stringResource(R.string.invite_friends_all, overview.friends.size),
                        style = Type.body(13, FontWeight.SemiBold),
                        color = Nocturne.accent,
                    )
                }
            }
        }
        if (overview.friends.isEmpty()) {
            item {
                InviteCard {
                    Text(
                        stringResource(R.string.invite_friends_empty),
                        style = Type.body(14),
                        color = Nocturne.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    )
                }
            }
        } else {
            itemsIndexed(overview.friends, key = { _, friend -> friend.id }) { index, friend ->
                FriendRow(
                    friend = friend,
                    first = index == 0,
                    last = index == overview.friends.lastIndex,
                    onOpen = { navigator.openProfile(friend.username) },
                    onRemind = { onRemind(friend) },
                )
            }
        }
    }
}

@Composable
private fun Hero(overview: ApiInviteOverview, code: String?, navigator: Navigator) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Dark mode keeps the hero deep rather than bright: white type on the
    // dark theme's luminous accent would not read.
    val ground = if (Nocturne.isDark) listOf(Nocturne.accent900, Nocturne.accent800) else listOf(Nocturne.accent, Nocturne.accent400)
    val ink = Nocturne.neutral100
    val shareText = stringResource(R.string.invite_share_text)
    val rewards = overview.rewards
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(ground))
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(stringResource(R.string.invite_hero_title), style = Type.heading(20, FontWeight.Bold), color = ink)
            if (rewards.inviter > 0 || rewards.welcome > 0) {
                Text(
                    stringResource(R.string.invite_hero_rewards, energy(rewards.inviter), energy(rewards.welcome)),
                    style = Type.body(14, FontWeight.Medium),
                    color = ink.copy(alpha = 0.85f),
                )
            }
        }
        if (code == null) {
            // Personal codes start at a member level; below it the page still
            // opens, and says why there is nothing to share.
            Text(
                stringResource(R.string.invite_cannot_invite),
                style = Type.body(14),
                color = ink,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ink.copy(alpha = 0.16f))
                    .padding(14.dp),
            )
            return@Column
        }
        // `/i/CODE` is the short landing page; the code alone still works in
        // the signup form, so it is shown as well as linked.
        val link = "${DiscourseConfig.BASE_URL}/i/$code"
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ink.copy(alpha = 0.16f))
                .padding(start = 14.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.invite_my_code), style = Type.body(12), color = ink.copy(alpha = 0.8f))
                Text(
                    code,
                    style = Type.heading(22, FontWeight.Bold).copy(letterSpacing = 0.14.em),
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier
                    .height(40.dp)
                    .clip(CircleShape)
                    .background(ink)
                    .clickable {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(code, link)))
                            ToastCenter.show(R.string.reader_link_copied)
                        }
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Lucide.Copy, null, tint = Nocturne.accent, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.invite_copy_link), style = Type.body(14, FontWeight.Bold), color = Nocturne.accent)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(CircleShape)
                .background(ink.copy(alpha = 0.14f))
                .clickable { navigator.share(context, link, shareText) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Lucide.Share, null, tint = ink, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.invite_share), style = Type.body(13, FontWeight.SemiBold), color = ink)
        }
    }
}

@Composable
private fun Stats(overview: ApiInviteOverview) {
    InviteCard {
        Row(Modifier.fillMaxWidth()) {
            Stat(overview.invited.toString(), stringResource(R.string.invite_stat_invited), Modifier.weight(1f))
            Stat(overview.effective.toString(), stringResource(R.string.invite_stat_effective), Modifier.weight(1f), accent = true)
            Stat("+" + energy(overview.earned), stringResource(R.string.invite_stat_earned), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier, accent: Boolean = false) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = Type.heading(19, FontWeight.Bold), color = if (accent) Nocturne.accent else Nocturne.text)
        Text(label, style = Type.body(12), color = Nocturne.textSecondary)
    }
}

@Composable
private fun rewardRules(overview: ApiInviteOverview): List<Pair<String, String>> {
    val rewards = overview.rewards
    return buildList {
        if (rewards.inviter > 0) {
            add(stringResource(R.string.invite_rule_verify) to "+" + energy(rewards.inviter))
        }
        if (rewards.levelBonus > 0 && rewards.levelName.isNotBlank()) {
            add(stringResource(R.string.invite_rule_level, rewards.levelName) to stringResource(R.string.invite_rule_level_amount, energy(rewards.levelBonus)))
        }
        if (rewards.second > 0) {
            add(stringResource(R.string.invite_rule_second) to stringResource(R.string.invite_rule_second_amount, energy(rewards.second)))
        }
    }
}

@Composable
private fun Milestone(overview: ApiInviteOverview) {
    val count = overview.milestoneCount
    val next = overview.milestones.firstOrNull { !it.reached }
    val cap = overview.rewards.monthlyCap
    InviteCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (next == null) Nocturne.accent100 else Nocturne.neutral400),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.Trophy, null, tint = if (next == null) Nocturne.accent else Nocturne.textSecondary, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                if (next == null) {
                    Text(stringResource(R.string.invite_milestone_all_done), style = Type.body(15, FontWeight.SemiBold), color = Nocturne.text)
                } else {
                    val left = (next.target - count).coerceAtLeast(1)
                    val badge = next.badge?.takeIf { it.isNotBlank() }
                    Text(
                        if (badge != null) stringResource(R.string.invite_milestone_badge, left, badge)
                        else stringResource(R.string.invite_milestone_reward, left, energy(next.reward)),
                        style = Type.body(15, FontWeight.SemiBold),
                        color = Nocturne.text,
                    )
                    val prize = when {
                        badge != null && next.reward > 0 -> stringResource(R.string.invite_milestone_prize_both, energy(next.reward))
                        badge != null -> stringResource(R.string.invite_milestone_prize_badge)
                        else -> null
                    }
                    prize?.let { Text(it, style = Type.body(12), color = Nocturne.textSecondary) }
                }
            }
        }
        val target = next?.target ?: overview.milestones.maxOf { it.target }
        val fraction = if (target > 0) (count.toFloat() / target).coerceIn(0f, 1f) else 1f
        Box(
            Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Nocturne.neutral400),
        ) {
            Box(Modifier.fillMaxWidth(fraction).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Nocturne.accent))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("$count / $target", style = Type.body(12), color = Nocturne.textTertiary, modifier = Modifier.weight(1f))
            if (cap > 0) {
                Text(stringResource(R.string.invite_monthly_cap, cap), style = Type.body(12), color = Nocturne.textTertiary)
            }
        }
    }
}

@Composable
private fun FriendRow(
    friend: ApiInviteFriend,
    first: Boolean,
    last: Boolean,
    onOpen: () -> Unit,
    onRemind: () -> Unit,
) {
    // One card made of rows: each row rounds only the corners it owns, so the
    // list can stay lazy without losing the card's outline.
    val shape = RoundedCornerShape(
        topStart = if (first) 18.dp else 0.dp,
        topEnd = if (first) 18.dp else 0.dp,
        bottomStart = if (last) 18.dp else 0.dp,
        bottomEnd = if (last) 18.dp else 0.dp,
    )
    Column(Modifier.padding(horizontal = 16.dp).clip(shape).background(Nocturne.bg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val display = friend.name?.takeIf { it.isNotBlank() } ?: friend.username
            RemoteAvatar(DiscourseConfig.absoluteUrl(friend.avatarUrl), display, variant = (friend.id % 2).toInt(), size = 36.dp)
            Column(Modifier.weight(1f)) {
                Text(display, style = Type.body(15, FontWeight.SemiBold), color = Nocturne.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.invite_joined_on, monthDay(friend.joinedAt)),
                    style = Type.body(12),
                    color = Nocturne.textSecondary,
                )
            }
            when (friend.stage) {
                "leveled" -> StatusPill(stringResource(R.string.invite_stage_leveled, friend.levelName.orEmpty()), PillTone.Accent)
                "verified" -> StatusPill(stringResource(R.string.invite_stage_verified), PillTone.Info)
                "expired" -> StatusPill(stringResource(R.string.invite_stage_expired), PillTone.Neutral)
                else -> {
                    StatusPill(stringResource(R.string.invite_stage_pending), PillTone.Warning)
                    if (friend.canRemind) {
                        Text(
                            stringResource(R.string.invite_remind),
                            style = Type.body(13, FontWeight.SemiBold),
                            color = Nocturne.accent,
                            modifier = Modifier.clip(CircleShape).clickable(onClick = onRemind).padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.neutral300))
    }
}

@Composable
private fun InviteCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Nocturne.bg)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        content = content,
    )
}

private fun energy(value: Long): String = NumberFormat.getIntegerInstance().format(value)

/** "11月16日" / "Nov 16": the locale's own month-day order. */
internal fun monthDay(raw: String?): String {
    val instant = DiscourseFormat.instant(raw) ?: return ""
    val locale = java.util.Locale.getDefault()
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd")
    return runCatching {
        java.time.format.DateTimeFormatter.ofPattern(pattern, locale)
            .withZone(java.time.ZoneId.systemDefault())
            .format(instant)
    }.getOrElse { DiscourseFormat.day(raw) }
}
