package com.nodeloc.app.feature.settings

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Gift
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.UserPlus
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import androidx.compose.ui.graphics.asImageBitmap
import com.nodeloc.app.core.design.NodelocLoader
import androidx.compose.runtime.remember
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FilterPill
import com.nodeloc.app.core.design.LocalReduceMotion
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.PillTone
import com.nodeloc.app.core.design.PrimaryButton
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.SecondaryButton
import com.nodeloc.app.core.design.StatusPill
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.pressScale
import com.nodeloc.app.core.design.rememberPressSource
import com.nodeloc.app.core.network.ApiInviteFriend
import com.nodeloc.app.core.network.ApiInviteMilestone
import com.nodeloc.app.core.network.ApiInviteOverview
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.network.PersonalApi
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.feature.feed.RowsSkeleton
import com.nodeloc.app.feature.moderation.ModScaffold
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

private enum class FriendFilter { All, Effective, Pending }

private val ApiInviteFriend.isEffective get() = stage == "verified" || stage == "leveled"

@Composable
fun InviteScreen(navigator: Navigator) {
    val viewModel: InviteViewModel = viewModel()
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { if (state.overview == null) viewModel.load() }

    // The page names itself in its own heading, as the board does; the bar
    // above it carries only the way back.
    ModScaffold("", navigator::back) {
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
    var filter by rememberSaveable { mutableStateOf(FriendFilter.All) }
    val friends = overview.friends.filter {
        when (filter) {
            FriendFilter.All -> true
            FriendFilter.Effective -> it.isEffective
            FriendFilter.Pending -> it.stage == "pending"
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().background(Nocturne.bg),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.invite_title), style = Type.heading(24, FontWeight.Bold), color = Nocturne.text)
        }
        item { Hero(overview) }
        item { LinkCard(code, navigator, welcome = overview.rewards.welcome) }
        item { Stats(overview) }
        if (overview.milestones.isNotEmpty()) item { Milestones(overview) }

        item {
            Column(Modifier.padding(top = 12.dp)) {
                Text(stringResource(R.string.invite_friends), style = Type.heading(16, FontWeight.Bold), color = Nocturne.text)
                if (overview.friends.isNotEmpty()) {
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterPill(stringResource(R.string.invite_filter_all), filter == FriendFilter.All, count = overview.friends.size) {
                            filter = FriendFilter.All
                        }
                        FilterPill(
                            stringResource(R.string.invite_filter_effective),
                            filter == FriendFilter.Effective,
                            count = overview.friends.count { it.isEffective },
                        ) { filter = FriendFilter.Effective }
                        FilterPill(
                            stringResource(R.string.invite_filter_pending),
                            filter == FriendFilter.Pending,
                            count = overview.friends.count { it.stage == "pending" },
                        ) { filter = FriendFilter.Pending }
                    }
                }
            }
        }
        if (friends.isEmpty()) {
            item {
                Text(
                    stringResource(if (overview.friends.isEmpty()) R.string.invite_friends_empty else R.string.invite_filter_empty),
                    style = Type.body(14),
                    color = Nocturne.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                )
            }
        } else {
            items(friends, key = { it.id }) { friend ->
                FriendRow(
                    friend = friend,
                    levelName = overview.rewards.levelName,
                    onOpen = { navigator.openProfile(friend.username) },
                    onRemind = { onRemind(friend) },
                )
            }
        }
        item {
            Text(
                stringResource(R.string.invite_friends_note, overview.rewards.verifyDays),
                style = Type.body(12),
                color = Nocturne.textSecondary,
            )
        }
        item { Faq(overview) }
    }
}

/** The dark explainer: the headline offer, then the three ways a friend pays out. */
@Composable
private fun Hero(overview: ApiInviteOverview) {
    val rewards = overview.rewards
    // Ink stays light on the deep ground in both themes; the hero is a fixed
    // dark panel in the design, not a surface that inverts.
    val ink = Nocturne.neutral100
    val soft = ink.copy(alpha = 0.78f)
    val mint = Nocturne.accent300
    val steps = buildList {
        if (rewards.inviter > 0) {
            add(stringResource(R.string.invite_rule_verify) to stringResource(R.string.invite_step_verify_amount, energy(rewards.inviter), energy(rewards.welcome)))
        }
        if (rewards.levelBonus > 0 && rewards.levelName.isNotBlank()) {
            add(stringResource(R.string.invite_rule_level, rewards.levelName) to stringResource(R.string.invite_step_level_amount, energy(rewards.levelBonus)))
        }
        if (rewards.second > 0) {
            add(stringResource(R.string.invite_rule_second) to stringResource(R.string.invite_step_second_amount, energy(rewards.second)))
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Nocturne.accent900)
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        Row(
            Modifier.height(26.dp).clip(CircleShape).background(mint.copy(alpha = 0.16f)).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Lucide.Gift, null, tint = mint, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.invite_reward_badge), style = Type.body(12, FontWeight.Bold), color = mint)
        }
        Text(
            if (rewards.inviter > 0) stringResource(R.string.invite_hero_headline, energy(rewards.inviter), energy(rewards.welcome))
            else stringResource(R.string.invite_hero_title),
            style = Type.heading(22, FontWeight.Bold),
            color = ink,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(stringResource(R.string.invite_hero_sub), style = Type.body(14), color = soft, modifier = Modifier.padding(top = 6.dp))
        if (steps.isNotEmpty()) {
            Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                steps.forEachIndexed { index, (label, amount) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(ink.copy(alpha = 0.06f))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(mint), contentAlignment = Alignment.Center) {
                            Text("${index + 1}", style = Type.body(14, FontWeight.Bold), color = Nocturne.accent900)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(label, style = Type.body(13), color = soft)
                            Text(amount, style = Type.body(14, FontWeight.Bold), color = ink, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkCard(code: String?, navigator: Navigator, welcome: Long) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val shareText = stringResource(R.string.invite_share_text)
    var posterOpen by remember { mutableStateOf(false) }
    OutlinedCard {
        Text(stringResource(R.string.invite_link_title), style = Type.heading(16, FontWeight.Bold), color = Nocturne.text)
        if (code == null) {
            // Personal codes start at a member level; below it the page still
            // opens, and says why there is nothing to share.
            Text(
                stringResource(R.string.invite_cannot_invite),
                style = Type.body(14),
                color = Nocturne.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@OutlinedCard
        }
        // `/i/CODE` is the short landing page; the code alone still works in
        // the signup form, so it is shown as well as linked.
        val link = "${DiscourseConfig.BASE_URL}/i/$code"
        fun copy(label: String, text: String, toast: Int) {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text)))
                ToastCenter.show(toast)
            }
        }
        Text(
            link,
            style = Type.body(15),
            color = Nocturne.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Nocturne.surface)
                .border(1.dp, Nocturne.neutral600, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp)
                .wrapContentHeight(Alignment.CenterVertically),
        )
        PrimaryButton(
            stringResource(R.string.invite_copy_link),
            block = true,
            modifier = Modifier.padding(top = 8.dp),
        ) { copy(code, link, R.string.reader_link_copied) }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.invite_code_label), style = Type.body(13), color = Nocturne.textSecondary)
            CodeChip(code) { copy(code, code, R.string.invite_code_copied) }
            Spacer(Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.invite_poster)) { posterOpen = true }
            SecondaryButton(stringResource(R.string.invite_share)) { navigator.share(context, link, shareText) }
        }
        if (posterOpen) PosterDialog(code, link, welcome) { posterOpen = false }
    }
}

/**
 * 生成海报: the poster drawn for the invitee, previewed, then handed to the
 * share sheet — which is also where saving it to the gallery lives.
 */
@Composable
private fun PosterDialog(code: String, link: String, welcome: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val me by ServiceLocator.get.session.currentUser.collectAsState()
    val inviter = me?.name?.takeIf { it.isNotBlank() } ?: me?.username ?: "nodeloc"
    var poster by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(code, inviter) {
        val avatar = runCatchingCancellable {
            InvitePoster.loadAvatar(context, DiscourseConfig.avatarUrl(me?.avatarTemplate, 240))
        }.getOrNull()
        poster = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            InvitePoster.render(context, inviter, avatar, code, link, welcome)
        }
    }
    val title = stringResource(R.string.invite_poster_from, inviter)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nocturne.bg,
        title = { Text(stringResource(R.string.invite_poster), style = Type.heading(20), color = Nocturne.text) },
        text = {
            val bitmap = poster
            if (bitmap == null) {
                Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) { NodelocLoader(height = 40.dp) }
            } else {
                androidx.compose.foundation.Image(
                    bitmap.asImageBitmap(),
                    contentDescription = title,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, Nocturne.divider, RoundedCornerShape(16.dp)),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = poster != null,
                onClick = { poster?.let { InvitePoster.share(context, it, title) } },
            ) {
                Text(stringResource(R.string.invite_poster_share), color = Nocturne.accent)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_done), color = Nocturne.textSecondary)
            }
        },
    )
}

/** The code in a dashed box, the board's "tear-off" look; tapping copies it. */
@Composable
private fun CodeChip(code: String, onCopy: () -> Unit) {
    val outline = Nocturne.neutral600
    val press = rememberPressSource()
    Row(
        Modifier
            .pressScale(press)
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .drawBehind {
                drawRoundRect(
                    color = outline,
                    cornerRadius = CornerRadius(10.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
            .clickable(interactionSource = press, indication = ripple(), onClick = onCopy)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(code, style = Type.body(15, FontWeight.Bold).copy(letterSpacing = 0.12.em), color = Nocturne.text, maxLines = 1)
        Icon(Lucide.Copy, null, tint = Nocturne.textSecondary, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun Stats(overview: ApiInviteOverview) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                stringResource(R.string.invite_stat_invited),
                overview.invited.toString(),
                stringResource(R.string.invite_stat_invited_sub),
                Modifier.weight(1f),
            )
            StatTile(
                stringResource(R.string.invite_stat_effective),
                overview.effective.toString(),
                stringResource(R.string.invite_stat_effective_sub),
                Modifier.weight(1f),
                accent = true,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                stringResource(R.string.invite_stat_total),
                "+" + energy(overview.earned),
                if (overview.earnedSecond > 0) stringResource(R.string.invite_stat_earned_sub, energy(overview.earnedSecond))
                else stringResource(R.string.invite_stat_energy),
                Modifier.weight(1f),
                accent = true,
            )
            StatTile(
                stringResource(R.string.invite_stat_pending),
                energy(overview.pending),
                stringResource(R.string.invite_stat_pending_sub),
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, sub: String, modifier: Modifier, accent: Boolean = false) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Nocturne.divider, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(label, style = Type.body(12), color = Nocturne.textSecondary)
        Text(
            value,
            style = Type.heading(24, FontWeight.Bold),
            color = if (accent) Nocturne.accent700 else Nocturne.text,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(sub, style = Type.body(12), color = Nocturne.textSecondary, maxLines = 2)
    }
}

/**
 * The track: each milestone sits at an even step along the bar, and the fill
 * moves piecewise between them — a linear scale up to 100 would leave the
 * first three rungs crowded into the opening few millimetres.
 */
@Composable
private fun Milestones(overview: ApiInviteOverview) {
    val milestones = overview.milestones.sortedBy { it.target }
    val count = overview.milestoneCount
    val next = milestones.firstOrNull { !it.reached }
    val steps = milestones.size
    val fraction = run {
        val index = milestones.indexOfFirst { it.target > count }
        if (index < 0) return@run 1f
        val fromTarget = if (index == 0) 0 else milestones[index - 1].target
        val fromAt = index.toFloat() / steps
        val toAt = (index + 1).toFloat() / steps
        val span = (milestones[index].target - fromTarget).coerceAtLeast(1)
        fromAt + (count - fromTarget).toFloat() / span * (toAt - fromAt)
    }
    val fill by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "milestoneFill")
    OutlinedCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.invite_milestones), style = Type.heading(16, FontWeight.Bold), color = Nocturne.text)
            Spacer(Modifier.width(12.dp))
            Text(
                if (next == null) stringResource(R.string.invite_milestone_all_done)
                else stringResource(R.string.invite_milestone_next, (next.target - count).coerceAtLeast(1), prize(next)),
                style = Type.body(13, FontWeight.Bold),
                color = Nocturne.accent700,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 18.dp, start = 14.dp, end = 14.dp)) {
            val width = maxWidth
            Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Nocturne.neutral400))
                Box(Modifier.fillMaxWidth(fill).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Nocturne.accent))
                milestones.forEachIndexed { index, milestone ->
                    val at = width * ((index + 1).toFloat() / steps)
                    Box(
                        Modifier
                            .offset(x = at - 14.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(if (milestone.reached) Nocturne.accent else Nocturne.bg)
                            .border(2.dp, if (milestone.reached) Nocturne.accent else Nocturne.neutral600, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (milestone.reached) {
                            Icon(Lucide.Check, null, tint = Nocturne.bg, modifier = Modifier.size(14.dp))
                        } else {
                            Text(milestone.target.toString(), style = Type.body(11, FontWeight.Bold), color = Nocturne.textSecondary)
                        }
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            val width = maxWidth
            val labelWidth = 76.dp
            Box(Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                milestones.forEachIndexed { index, milestone ->
                    val last = index == milestones.lastIndex
                    // The rail is inset by a node's radius; labels line up under
                    // their node, except the last, which keeps to the edge.
                    val center = 14.dp + (width - 28.dp) * ((index + 1).toFloat() / steps)
                    val x = if (last) width - labelWidth else (center - labelWidth / 2).coerceAtLeast(0.dp)
                    Column(
                        Modifier.offset(x = x).width(labelWidth),
                        horizontalAlignment = if (last) Alignment.End else Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.invite_milestone_people, milestone.target),
                            style = Type.body(12, FontWeight.Bold),
                            color = if (milestone.reached) Nocturne.accent700 else Nocturne.text,
                        )
                        Text(
                            prize(milestone),
                            style = Type.body(12),
                            color = Nocturne.textSecondary,
                            textAlign = if (last) TextAlign.End else TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun prize(milestone: ApiInviteMilestone): String {
    val badge = milestone.badge?.takeIf { it.isNotBlank() }
    return when {
        badge != null && milestone.reward > 0 -> stringResource(R.string.invite_prize_badge_energy, badge, energy(milestone.reward))
        badge != null -> stringResource(R.string.invite_prize_badge, badge)
        else -> stringResource(R.string.invite_prize_energy, energy(milestone.reward))
    }
}

@Composable
private fun FriendRow(
    friend: ApiInviteFriend,
    levelName: String,
    onOpen: () -> Unit,
    onRemind: () -> Unit,
) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.divider))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .clickable(onClick = onOpen)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val display = friend.name?.takeIf { it.isNotBlank() } ?: friend.username
            RemoteAvatar(DiscourseConfig.absoluteUrl(friend.avatarUrl), display, variant = (friend.id % 2).toInt(), size = 40.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        display,
                        style = Type.body(15, FontWeight.Medium),
                        color = Nocturne.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (friend.subInvites > 0) {
                        StatusPill(stringResource(R.string.invite_sub_invites, friend.subInvites), PillTone.Info)
                    }
                }
                StageLine(friend)
            }
            when {
                friend.isEffective -> Column(horizontalAlignment = Alignment.End) {
                    Text("+" + energy(friend.earned), style = Type.body(15, FontWeight.Bold), color = Nocturne.accent700)
                    val note = when {
                        friend.capped -> stringResource(R.string.invite_friend_capped)
                        friend.pending > 0 && levelName.isNotBlank() ->
                            stringResource(R.string.invite_friend_level_pending, levelName, energy(friend.pending))
                        friend.second > 0 -> stringResource(R.string.invite_friend_second, energy(friend.second))
                        else -> stringResource(R.string.invite_friend_all_paid)
                    }
                    Text(note, style = Type.body(12), color = Nocturne.textSecondary, maxLines = 1)
                }
                friend.stage == "pending" && friend.canRemind ->
                    SecondaryButton(stringResource(R.string.invite_remind_verify), compact = true, onClick = onRemind)
                friend.stage == "pending" && friend.remindedAt != null ->
                    Text(stringResource(R.string.invite_reminded), style = Type.body(12), color = Nocturne.textTertiary)
            }
        }
    }
}

/** "Joined Nov 15 · ▬▬▬ · stage": three bars for signed up, verified, levelled. */
@Composable
private fun StageLine(friend: ApiInviteFriend) {
    val (done, label, color) = when (friend.stage) {
        "leveled" -> Triple(3, stringResource(R.string.invite_stage_leveled, friend.levelName.orEmpty()), Nocturne.accent700)
        "verified" -> Triple(2, stringResource(R.string.invite_stage_verified), Nocturne.textSecondary)
        "expired" -> Triple(0, stringResource(R.string.invite_stage_expired), Nocturne.textTertiary)
        else -> Triple(1, stringResource(R.string.invite_stage_pending_long), Nocturne.warning)
    }
    Row(
        Modifier.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.invite_joined_on, monthDay(friend.joinedAt)), style = Type.body(12), color = Nocturne.textSecondary, maxLines = 1)
        Text("·", style = Type.body(12), color = Nocturne.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { index ->
                Box(
                    Modifier
                        .size(width = 18.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (index < done) Nocturne.accent else Nocturne.neutral400),
                )
            }
        }
        Text(label, style = Type.body(12, FontWeight.Medium), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The rules, each answer filled in from the live reward settings. */
@Composable
private fun Faq(overview: ApiInviteOverview) {
    val rewards = overview.rewards
    val entries = buildList {
        add(stringResource(R.string.invite_faq_q_valid) to stringResource(R.string.invite_faq_a_valid, rewards.verifyDays))
        add(
            stringResource(R.string.invite_faq_q_when) to
                if (rewards.levelBonus > 0 && rewards.levelName.isNotBlank()) stringResource(R.string.invite_faq_a_when, rewards.levelName)
                else stringResource(R.string.invite_faq_a_when_plain),
        )
        if (rewards.second > 0) {
            add(stringResource(R.string.invite_faq_q_second) to stringResource(R.string.invite_faq_a_second, energy(rewards.second)))
        }
        if (rewards.monthlyCap > 0) {
            add(stringResource(R.string.invite_faq_q_cap) to stringResource(R.string.invite_faq_a_cap, rewards.monthlyCap))
        }
    }
    var open by rememberSaveable { mutableStateOf(0) }
    val reduceMotion = LocalReduceMotion.current
    Column(Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.invite_faq_title), style = Type.heading(14, FontWeight.Bold), color = Nocturne.text)
        entries.forEachIndexed { index, (question, answer) ->
            val expanded = open == index
            val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "faqChevron")
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clickable { open = if (expanded) -1 else index }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(question, style = Type.body(14, FontWeight.Medium), color = Nocturne.text, modifier = Modifier.weight(1f))
                    Icon(
                        Lucide.ChevronDown,
                        null,
                        tint = Nocturne.textSecondary,
                        modifier = Modifier.size(18.dp).rotate(if (reduceMotion) (if (expanded) 180f else 0f) else turn),
                    )
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = if (reduceMotion) fadeIn(initialAlpha = 1f) else expandVertically() + fadeIn(),
                    exit = if (reduceMotion) fadeOut(targetAlpha = 1f) else shrinkVertically() + fadeOut(),
                ) {
                    Text(answer, style = Type.body(13), color = Nocturne.textSecondary, modifier = Modifier.padding(bottom = 12.dp))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.divider))
            }
        }
    }
}

@Composable
private fun OutlinedCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Nocturne.divider, RoundedCornerShape(16.dp))
            .padding(16.dp),
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
