package com.nodeloc.app.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.Award
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trophy
import com.composables.icons.lucide.WifiOff
import com.nodeloc.app.R
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FilterPill
import com.nodeloc.app.core.design.FilterPillRow
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.SkeletonBox
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.skeletonPulsing
import com.nodeloc.app.core.network.isOfflineError
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.feature.moderation.ModScaffold
import com.nodeloc.app.feature.nav.Navigator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class AchFilter(val labelRes: Int) {
    All(R.string.ach_filter_all),
    Unlocked(R.string.ach_filter_unlocked),
    InProgress(R.string.ach_filter_progress),
    NotStarted(R.string.ach_filter_not_started),
}

/**
 * Where an achievement stands. Off ones (closed, or admin-granted) cannot be
 * worked towards, so however far the counter got they are "not started" —
 * a bar creeping towards something nobody can reach reads as a promise.
 */
fun ApiAchItem.stage(): AchFilter = when {
    unlocked -> AchFilter.Unlocked
    !off && current > 0 -> AchFilter.InProgress
    else -> AchFilter.NotStarted
}

data class AchievementsState(
    val isOwn: Boolean = true,
    val items: List<ApiAchItem> = emptyList(),
    val maxShowcase: Int = 3,
    val total: Int = 0,
    val unlocked: Int = 0,
    val filter: AchFilter = AchFilter.All,
    val isLoading: Boolean = false,
    val error: Throwable? = null,
    val pinning: String? = null,
)

/**
 * Yours: the whole catalogue with progress. Someone else's: only what they
 * pinned — the server keeps the rest of a member's progress to the member.
 */
class AchievementsViewModel : ViewModel() {
    private val growth = GrowthApi.shared
    private val _state = MutableStateFlow(AchievementsState())
    val state: StateFlow<AchievementsState> = _state.asStateFlow()
    private var bound: String? = null
    private var didBind = false

    fun bind(username: String?) {
        if (didBind && bound == username) return
        didBind = true
        bound = username
        load()
    }

    fun retry() = load()

    fun setFilter(filter: AchFilter) {
        _state.value = _state.value.copy(filter = filter)
    }

    private fun load() {
        val username = bound
        viewModelScope.launch {
            _state.value = _state.value.copy(isOwn = username == null, isLoading = true, error = null)
            if (username == null) {
                runCatchingCancellable { growth.achievements() }
                    .onSuccess {
                        _state.value = _state.value.copy(
                            items = it.achievements,
                            maxShowcase = it.maxShowcase,
                            total = it.total,
                            unlocked = it.unlockedCount,
                            isLoading = false,
                        )
                    }
                    .onFailure { _state.value = _state.value.copy(isLoading = false, error = it) }
            } else {
                runCatchingCancellable { growth.showcase(username) }
                    .onSuccess {
                        // The showcase only lists unlocked ones and leaves the
                        // flag off, since for a showcase it goes without saying.
                        val items = it.showcase.map { item -> item.copy(unlocked = true) }
                        _state.value = _state.value.copy(
                            items = items,
                            total = items.size,
                            unlocked = items.size,
                            isLoading = false,
                        )
                    }
                    .onFailure { _state.value = _state.value.copy(isLoading = false, error = it) }
            }
        }
    }

    /** Optimistic, rolled back on failure — including the server's "three at most". */
    fun togglePin(item: ApiAchItem) {
        val current = _state.value
        if (!current.isOwn || current.pinning != null || !item.unlocked) return
        val pin = !item.pinned
        if (pin && current.items.count { it.pinned } >= current.maxShowcase) {
            ToastCenter.show(R.string.ach_pin_full, current.maxShowcase)
            return
        }
        fun flip(to: Boolean) {
            _state.value = _state.value.copy(
                items = _state.value.items.map { if (it.key == item.key) it.copy(pinned = to) else it },
            )
        }
        flip(pin)
        _state.value = _state.value.copy(pinning = item.key)
        viewModelScope.launch {
            runCatchingCancellable { growth.setPinned(item.key, pin) }
                .onFailure {
                    flip(!pin)
                    ToastCenter.showError(it)
                }
            _state.value = _state.value.copy(pinning = null)
        }
    }
}

@Composable
fun AchievementsScreen(username: String?, navigator: Navigator) {
    val viewModel: AchievementsViewModel = viewModel(key = "achievements:${username.orEmpty()}")
    val state by viewModel.state.collectAsState()
    var detail by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(username) { viewModel.bind(username) }

    ModScaffold(
        title = if (username == null) stringResource(R.string.ach_title) else stringResource(R.string.ach_title_user, username),
        onBack = navigator::back,
    ) {
        Box(Modifier.fillMaxSize().background(Nocturne.surface)) {
            when {
                state.items.isEmpty() && state.isLoading -> Column(
                    Modifier.padding(16.dp).skeletonPulsing(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SkeletonBox(Modifier.fillMaxWidth().height(140.dp), corner = 18.dp)
                    repeat(3) { SkeletonBox(Modifier.fillMaxWidth().height(150.dp), corner = 16.dp) }
                }

                state.items.isEmpty() && state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val offline = state.error?.isOfflineError() == true
                    EmptyStateView(
                        icon = if (offline) Lucide.WifiOff else Lucide.CircleAlert,
                        title = stringResource(if (offline) R.string.error_offline else R.string.ach_load_failed),
                        retryLabel = stringResource(R.string.common_retry),
                        onRetry = viewModel::retry,
                    )
                }

                else -> AchievementsBody(state, viewModel::setFilter) { detail = it.key }
            }
        }
    }

    state.items.firstOrNull { it.key == detail }?.let { item ->
        AchievementDialog(
            item = item,
            canPin = state.isOwn && item.unlocked,
            busy = state.pinning != null,
            onTogglePin = { viewModel.togglePin(item) },
            onDismiss = { detail = null },
        )
    }
}

@Composable
private fun AchievementsBody(state: AchievementsState, onFilter: (AchFilter) -> Unit, onOpen: (ApiAchItem) -> Unit) {
    val counts = state.items.groupingBy { it.stage() }.eachCount()
    val shown = state.items.filter { state.filter == AchFilter.All || it.stage() == state.filter }
    // Catalogue order within a category, as the server sends it; categories
    // in the order they first appear, which is the catalogue's own.
    val groups = shown.groupBy { it.category ?: "" }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.isOwn) {
            item(key = "filters") {
                Box(Modifier.fillMaxWidth().background(Nocturne.bg)) {
                    FilterPillRow {
                        AchFilter.entries.forEach { f ->
                            FilterPill(
                                text = stringResource(f.labelRes),
                                selected = f == state.filter,
                                count = if (f == AchFilter.All) null else counts[f] ?: 0,
                                onClick = { onFilter(f) },
                            )
                        }
                    }
                }
            }
            item(key = "summary") { SummaryCard(state) }
        } else {
            item { Spacer(Modifier.height(4.dp)) }
        }

        if (shown.isEmpty()) {
            item { EmptyStateView(Lucide.Award, stringResource(if (state.isOwn) R.string.ach_empty else R.string.me2_showcase_empty)) }
        }

        groups.forEach { (category, items) ->
            if (state.isOwn && category.isNotEmpty()) {
                item(key = "head-$category") {
                    val all = state.items.filter { it.category == category }
                    GrowthSectionHead(category, trailing = "${all.count { it.unlocked }} / ${all.size}")
                }
            }
            items(items.chunked(3), key = { row -> "row-${row.first().key}" }) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { AchievementTile(it, Modifier.weight(1f)) { onOpen(it) } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(state: AchievementsState) {
    // What the unlocks themselves paid, each repeat counted: the overview's own
    // `reputation` is the account's whole total, not this.
    val earned = state.items.filter { it.unlocked }.sumOf { it.reputation.toLong() * maxOf(it.times, 1) }
    GrowthCard(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Nocturne.warningBg),
                contentAlignment = Alignment.Center,
            ) { Icon(Lucide.Trophy, null, tint = Nocturne.warning, modifier = Modifier.size(24.dp)) }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.ach_summary, state.unlocked, state.total),
                    style = Type.heading(20, FontWeight.Bold),
                    color = Nocturne.text,
                )
                Text(
                    stringResource(R.string.ach_summary_rep, formatCount(earned)),
                    style = Type.body(13),
                    color = Nocturne.textSecondary,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..4).forEach { tier ->
                val (ground, ink) = achTierColors(tier)
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ground)
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        state.items.count { it.unlocked && it.tier == tier }.toString(),
                        style = Type.body(17, FontWeight.Bold),
                        color = ink,
                    )
                    Text(achTierLabel(tier), style = Type.body(12, FontWeight.Bold), color = ink)
                }
            }
        }
    }
}

@Composable
private fun AchievementTile(item: ApiAchItem, modifier: Modifier, onClick: () -> Unit) {
    val (ground, ink) = achTierColors(item.tier, item.unlocked)
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Nocturne.bg)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 10.dp)
            .alpha(if (item.unlocked) 1f else 0.92f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(ground),
                contentAlignment = Alignment.Center,
            ) { Icon(achievementIcon(item.icon), null, tint = ink, modifier = Modifier.size(24.dp)) }
            // The tier as a tag on the disc's edge, filled in its own ink.
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 4.dp, y = 2.dp)
                    .height(16.dp)
                    .clip(CircleShape)
                    .background(if (item.unlocked) ink else Nocturne.neutral500)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(achTierLabel(item.tier), style = Type.body(10, FontWeight.Bold), color = Nocturne.bg, maxLines = 1)
            }
            if (item.pinned) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Nocturne.accent),
                )
            }
        }
        Text(
            item.name + if (item.repeatable && item.times > 1) " ×${item.times}" else "",
            style = Type.body(14, FontWeight.SemiBold),
            color = if (item.unlocked) Nocturne.text else Nocturne.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            item.how ?: item.description.orEmpty(),
            style = Type.body(11),
            color = Nocturne.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            item.unlocked -> (item.lastAt ?: item.unlockedAt)?.let {
                Text(monthOf(it), style = Type.body(11), color = Nocturne.textTertiary, maxLines = 1)
            }
            item.off -> Text(
                stringResource(if (item.closed) R.string.ach_closed else R.string.ach_manual),
                style = Type.body(11),
                color = Nocturne.textTertiary,
                maxLines = 1,
            )
            // A one-step goal has nothing between none and done to show.
            item.target > 1 -> Column(Modifier.fillMaxWidth().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                GrowthBar(item.current.toFloat() / item.target, height = 5.dp)
                Text(
                    "${formatCount(item.current.coerceAtMost(item.target))} / ${formatCount(item.target)}",
                    style = Type.body(11),
                    color = Nocturne.textTertiary,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

/** "2024 年 1 月": an unlock is remembered by its month, not its minute. */
@Composable
private fun monthOf(raw: String): String {
    val instant = DiscourseFormat.instant(raw) ?: return ""
    val formatter = remember {
        DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "yMMM"))
    }
    return formatter.format(instant.atZone(ZoneId.systemDefault()))
}

@Composable
private fun AchievementDialog(
    item: ApiAchItem,
    canPin: Boolean,
    busy: Boolean,
    onTogglePin: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (ground, ink) = achTierColors(item.tier, item.unlocked)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nocturne.bg,
        icon = {
            Box(Modifier.size(56.dp).clip(CircleShape).background(ground), contentAlignment = Alignment.Center) {
                Icon(achievementIcon(item.icon), null, tint = ink, modifier = Modifier.size(28.dp))
            }
        },
        title = {
            Text(item.name, style = Type.heading(20, FontWeight.Bold), color = Nocturne.text, textAlign = TextAlign.Center)
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${achTierLabel(item.tier)} · ${stringResource(R.string.ach_reward, item.reputation)}",
                    style = Type.body(13, FontWeight.SemiBold),
                    color = ink,
                )
                (item.how ?: item.description)?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = Type.body(14), color = Nocturne.textSecondary, textAlign = TextAlign.Center)
                }
                if (item.unlocked) {
                    (item.lastAt ?: item.unlockedAt)?.let {
                        Text(
                            stringResource(R.string.ach_unlocked_on, DiscourseFormat.day(it)),
                            style = Type.body(12),
                            color = Nocturne.textTertiary,
                        )
                    }
                    if (item.pinned) {
                        Text(stringResource(R.string.ach_pinned), style = Type.body(12, FontWeight.SemiBold), color = Nocturne.accent)
                    }
                }
            }
        },
        confirmButton = {
            if (canPin) {
                TextButton(enabled = !busy, onClick = onTogglePin) {
                    Text(
                        stringResource(if (item.pinned) R.string.ach_unpin else R.string.ach_pin),
                        color = Nocturne.accent,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close), color = Nocturne.textSecondary) }
        },
    )
}
