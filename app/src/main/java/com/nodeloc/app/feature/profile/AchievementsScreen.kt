package com.nodeloc.app.feature.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import com.composables.icons.lucide.Share
import com.composables.icons.lucide.WifiOff
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.SkeletonBox
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.animatedColor
import com.nodeloc.app.core.design.pressScale
import com.nodeloc.app.core.design.rememberPressSource
import com.nodeloc.app.core.design.skeletonPulsing
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.network.isOfflineError
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.feature.moderation.ModScaffold
import com.nodeloc.app.feature.nav.Navigator
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The chip row's two filters that are not a catalogue category. */
const val ACH_FILTER_ALL = ""
const val ACH_FILTER_REPEAT = "repeat"

/** How far along a locked one is, for "离得最近的排前面". */
private fun ApiAchItem.fraction(): Float =
    if (target > 0) (current.toFloat() / target).coerceIn(0f, 1f) else 0f

data class AchievementsState(
    val isOwn: Boolean = true,
    val items: List<ApiAchItem> = emptyList(),
    /** Pinned keys in the order the server shows them on the profile. */
    val showcase: List<String> = emptyList(),
    val maxShowcase: Int = 3,
    val total: Int = 0,
    val unlocked: Int = 0,
    /** [ACH_FILTER_ALL], [ACH_FILTER_REPEAT], or a category key. */
    val filter: String = ACH_FILTER_ALL,
    val reputation: ApiRepSummary? = null,
    val tiers: List<ApiTierInfo> = emptyList(),
    val tasks: ApiTaskOverview? = null,
    val recent: List<ApiRepLedgerItem> = emptyList(),
    val recentNext: String? = null,
    val sheetOpen: Boolean = false,
    val sheetItems: List<ApiRepLedgerItem> = emptyList(),
    val sheetNext: String? = null,
    val sheetLoading: Boolean = false,
    val isLoading: Boolean = false,
    val error: Throwable? = null,
    /** The hero, tasks and ledger are still on their way after the catalogue. */
    val decorating: Boolean = false,
    val pinning: String? = null,
)

/**
 * Yours: the whole catalogue with progress, plus the tier, tasks and
 * reputation around it. Someone else's: only what they pinned — the server
 * keeps the rest of a member's progress to the member.
 */
class AchievementsViewModel : ViewModel() {
    private val growth = GrowthApi.shared
    private val repApi = ReputationApi.shared
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

    fun setFilter(filter: String) {
        _state.value = _state.value.copy(filter = filter)
    }

    private fun load() {
        val username = bound
        viewModelScope.launch {
            _state.value = _state.value.copy(isOwn = username == null, isLoading = true, error = null)
            if (username == null) {
                // The overview goes first and alone: fetching it is what records
                // anything newly earned, and the reputation, sources and ledger
                // only count that unlock once it has been recorded.
                runCatchingCancellable { growth.achievements() }
                    .onSuccess {
                        _state.value = _state.value.copy(
                            items = it.achievements,
                            showcase = it.showcase,
                            maxShowcase = it.maxShowcase,
                            total = it.total,
                            unlocked = it.unlockedCount,
                            isLoading = false,
                            decorating = true,
                        )
                    }
                    .onFailure {
                        _state.value = _state.value.copy(isLoading = false, error = it)
                        return@launch
                    }
                // The rest decorates the page; any one failing leaves its section out.
                val rep = async { runCatchingCancellable { growth.reputation() }.onFailure { android.util.Log.w("Achievements", "reputation failed", it) }.getOrNull() }
                val tasks = async { runCatchingCancellable { repApi.tasks() }.onFailure { android.util.Log.w("Achievements", "tasks failed", it) }.getOrNull() }
                val ledger = async { runCatchingCancellable { repApi.ledger(null, RECENT) }.getOrNull() }
                val tiers = async { TierDirectory.get() }
                val page = ledger.await()
                _state.value = _state.value.copy(
                    reputation = rep.await() ?: _state.value.reputation,
                    tasks = tasks.await() ?: _state.value.tasks,
                    recent = page?.items ?: _state.value.recent,
                    recentNext = if (page != null) page.next else _state.value.recentNext,
                    tiers = tiers.await(),
                    decorating = false,
                )
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

    fun openLedger() {
        val current = _state.value
        _state.value = current.copy(sheetOpen = true, sheetItems = current.recent, sheetNext = current.recentNext)
        if (current.recent.isEmpty()) loadLedger()
    }

    fun closeLedger() {
        _state.value = _state.value.copy(sheetOpen = false)
    }

    fun loadLedger() {
        val current = _state.value
        if (current.sheetLoading || !current.sheetOpen) return
        val before = current.sheetNext
        if (before == null && current.sheetItems.isNotEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(sheetLoading = true)
            runCatchingCancellable { repApi.ledger(before) }
                .onSuccess {
                    val known = _state.value.sheetItems.map { item -> item.id }.toSet()
                    _state.value = _state.value.copy(
                        sheetItems = _state.value.sheetItems + it.items.filter { item -> item.id !in known },
                        sheetNext = it.next,
                    )
                }
                .onFailure { ToastCenter.showError(it) }
            _state.value = _state.value.copy(sheetLoading = false)
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
        val previousShowcase = current.showcase
        fun flip(to: Boolean, showcase: List<String>) {
            _state.value = _state.value.copy(
                items = _state.value.items.map { if (it.key == item.key) it.copy(pinned = to) else it },
                showcase = showcase,
            )
        }
        flip(pin, if (pin) previousShowcase + item.key else previousShowcase - item.key)
        _state.value = _state.value.copy(pinning = item.key)
        viewModelScope.launch {
            runCatchingCancellable { growth.setPinned(item.key, pin) }
                .onFailure {
                    flip(!pin, previousShowcase)
                    ToastCenter.showError(it)
                }
            _state.value = _state.value.copy(pinning = null)
        }
    }

    private companion object {
        /** The design's "最近的声望变化" shows five. */
        const val RECENT = 5
    }
}

@Composable
fun AchievementsScreen(username: String?, navigator: Navigator) {
    val viewModel: AchievementsViewModel = viewModel(key = "achievements:${username.orEmpty()}")
    val state by viewModel.state.collectAsState()
    var detail by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(username) { viewModel.bind(username) }

    val context = LocalContext.current
    val shareName = username ?: ServiceLocator.get.session.username
    val shareTitle = shareName?.let { stringResource(R.string.ach3_share_title, it) }
    // The board's top bar is bare on your own page: the hero names it. On
    // someone else's the name is the only thing saying whose medals these are.
    ModScaffold(
        title = if (username == null) "" else stringResource(R.string.ach_title_user, username),
        onBack = navigator::back,
        actions = {
            if (shareName != null) {
                HeaderIconButton(Lucide.Share, stringResource(R.string.common_share)) {
                    // The profile is where pinned medals are public; this page itself is sign-in only.
                    navigator.share(context, "${DiscourseConfig.BASE_URL}/u/$shareName", shareTitle)
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(Nocturne.bg)) {
            when {
                state.items.isEmpty() && state.isLoading -> Column(
                    Modifier.padding(16.dp).skeletonPulsing(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SkeletonBox(Modifier.fillMaxWidth().height(220.dp), corner = 24.dp)
                    SkeletonBox(Modifier.fillMaxWidth().height(150.dp), corner = 16.dp)
                    repeat(2) { SkeletonBox(Modifier.fillMaxWidth().height(120.dp), corner = 16.dp) }
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

                state.isOwn -> OwnBody(state, navigator, viewModel) { detail = it.key }
                else -> VisitorBody(state) { detail = it.key }
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

    if (state.sheetOpen) {
        LedgerSheet(
            items = state.sheetItems,
            taskTitles = state.tasks?.tasks.orEmpty().associateBy { it.key },
            loading = state.sheetLoading,
            hasMore = state.sheetNext != null,
            navigator = navigator,
            onMore = viewModel::loadLedger,
            onDismiss = viewModel::closeLedger,
        )
    }
}

@Composable
private fun OwnBody(state: AchievementsState, navigator: Navigator, viewModel: AchievementsViewModel, onOpen: (ApiAchItem) -> Unit) {
    val context = LocalContext.current
    // Categories in the catalogue's own order: the order they first appear.
    val categories = state.items.mapNotNull { item -> item.categoryKey?.let { it to (item.category ?: it) } }.distinctBy { it.first }
    val shown = state.items.filter {
        when (state.filter) {
            ACH_FILTER_ALL -> true
            ACH_FILTER_REPEAT -> it.repeatable
            else -> it.categoryKey == state.filter
        }
    }
    val got = shown.filter { it.unlocked }
    // Nearest first; ones nobody can work towards (closed, admin-granted)
    // last. A stable sort keeps catalogue order among equals.
    val todo = shown.filter { !it.unlocked }.sortedWith(compareBy<ApiAchItem> { it.off }.thenByDescending { it.fraction() })
    val taskTitles = state.tasks?.tasks.orEmpty().associateBy { it.key }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // The hero and tasks come after the catalogue. Their slots hold a
        // placeholder until then: a lazy list stays on the item it was showing,
        // so sections inserted above the catalogue later would open the page
        // scrolled past its own top.
        val rep = state.reputation
        when {
            rep != null -> item(key = "hero") { GrowthHero(rep, state.tiers, Modifier.padding(horizontal = 16.dp)) }
            state.decorating -> item(key = "hero") {
                SkeletonBox(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(320.dp).skeletonPulsing(), corner = 24.dp)
            }
        }
        val tasks = state.tasks
        when {
            tasks != null -> if (tasks.tasks.isNotEmpty()) item(key = "tasks") { TasksSection(tasks, navigator) }
            state.decorating -> item(key = "tasks") {
                SkeletonBox(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(180.dp).skeletonPulsing(), corner = 16.dp)
            }
        }
        item(key = "achievements") {
            Column {
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.me2_sc_achievements), style = Type.heading(20, FontWeight.Bold), color = Nocturne.text)
                    Text(
                        stringResource(R.string.ach_unlocked_of, state.unlocked, state.total),
                        style = Type.body(13),
                        color = Nocturne.textSecondary,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
                CategoryChips(categories, state.filter, viewModel::setFilter)
                if (got.isEmpty() && todo.isEmpty()) {
                    EmptyStateView(Lucide.Award, stringResource(R.string.ach_empty))
                }
                if (got.isNotEmpty()) {
                    SubHead(stringResource(R.string.ach_got_head, got.size), Modifier.padding(top = 16.dp))
                    BadgeGrid(got, Modifier.padding(top = 10.dp), onOpen)
                }
                if (todo.isNotEmpty()) {
                    SubHead(stringResource(R.string.ach_todo_head, todo.size), Modifier.padding(top = 20.dp))
                    LockedList(todo, Modifier.padding(top = 10.dp), onOpen)
                }
            }
        }
        item(key = "showcase") { ShowcaseCard(state, onOpen) }
        item(key = "recent") {
            RecentLedgerCard(
                items = state.recent,
                taskTitles = taskTitles,
                hasMore = state.recentNext != null,
                navigator = navigator,
                onAll = viewModel::openLedger,
            )
        }
        state.reputation?.let { rep ->
            item(key = "sources") {
                SourcesCard(rep) {
                    val me = ServiceLocator.get.session.username
                    if (me != null) navigator.openUpgradeProgress(me) else navigator.openUrl(context, "/permissions")
                }
            }
            // Only a 青铜 who has not finished the walkthrough still has this road to 白银.
            if (rep.tier == 0 && rep.onboardingDoneAt == null) {
                item(key = "onboarding") {
                    Box(Modifier.padding(horizontal = 20.dp)) {
                        LinkLine(stringResource(R.string.ach_onboarding), Nocturne.textSecondary, { navigator.openUrl(context, "/onboarding") }, height = 44.dp)
                    }
                }
            }
        }
    }
}

/** Someone else's page: their pinned achievements and nothing else. */
@Composable
private fun VisitorBody(state: AchievementsState, onOpen: (ApiAchItem) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp)) {
        if (state.items.isEmpty()) {
            item { EmptyStateView(Lucide.Award, stringResource(R.string.me2_showcase_empty)) }
        } else {
            item { BadgeGrid(state.items, Modifier, onOpen) }
        }
    }
}

@Composable
private fun SubHead(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = Type.body(12, FontWeight.Bold),
        color = Nocturne.textSecondary,
        modifier = modifier.padding(horizontal = 16.dp),
    )
}

/** The design's quiet chip row: ink when chosen, bare text otherwise. */
@Composable
private fun CategoryChips(categories: List<Pair<String, String>>, filter: String, onFilter: (String) -> Unit) {
    val entries = listOf(ACH_FILTER_ALL to stringResource(R.string.ach_filter_all)) +
        categories +
        (ACH_FILTER_REPEAT to stringResource(R.string.ach_filter_repeat))
    Row(
        Modifier
            .padding(top = 10.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        entries.forEach { (key, label) ->
            val chosen = key == filter
            val ground by animatedColor(if (chosen) Nocturne.text else Nocturne.bg, "achChip")
            val ink by animatedColor(if (chosen) Nocturne.bg else Nocturne.textSecondary, "achChipInk")
            val press = rememberPressSource()
            Box(
                Modifier
                    .pressScale(press)
                    .height(36.dp)
                    .clip(CircleShape)
                    .background(ground)
                    .semantics { selected = chosen }
                    .clickable(interactionSource = press, indication = ripple(), role = Role.Tab) { onFilter(key) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Type.body(13, FontWeight.Bold), color = ink, maxLines = 1)
            }
        }
    }
}

@Composable
private fun BadgeGrid(items: List<ApiAchItem>, modifier: Modifier, onOpen: (ApiAchItem) -> Unit) {
    Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { BadgeCard(it, Modifier.weight(1f)) { onOpen(it) } }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AchDisc(item: ApiAchItem, size: Int, iconSize: Int) =
    AchMedal(item.tier, item.unlocked, achievementIcon(item.icon), size.dp, iconSize.dp)

@Composable
private fun BadgeCard(item: ApiAchItem, modifier: Modifier, onClick: () -> Unit) {
    val press = rememberPressSource()
    Box(
        modifier
            .pressScale(press)
            .clip(RoundedCornerShape(16.dp))
            .border(BorderStroke(1.dp, Nocturne.divider), RoundedCornerShape(16.dp))
            .background(Nocturne.bg)
            .clickable(interactionSource = press, indication = ripple(), role = Role.Button, onClick = onClick),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box {
                AchDisc(item, 52, 24)
                // How many times a repeatable one has been earned, as a tag on the disc.
                if (item.repeatable && item.times > 1) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 6.dp, y = 4.dp)
                            .height(20.dp)
                            .clip(CircleShape)
                            .background(Nocturne.bg)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(Nocturne.text)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("×${item.times}", style = Type.body(11, FontWeight.Bold), color = Nocturne.bg, maxLines = 1)
                    }
                }
            }
            Text(
                item.name,
                style = Type.body(14, FontWeight.Bold),
                color = Nocturne.text,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (item.unlocked) {
                val raw = if (item.repeatable) item.lastAt ?: item.unlockedAt else item.unlockedAt ?: item.lastAt
                raw?.let {
                    val month = monthOf(it)
                    Text(
                        if (item.repeatable) stringResource(R.string.ach_recent_at, month) else month,
                        style = Type.body(12),
                        color = Nocturne.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
        if (item.pinned) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Nocturne.accent),
            )
        }
    }
}

@Composable
private fun LockedList(items: List<ApiAchItem>, modifier: Modifier, onOpen: (ApiAchItem) -> Unit) {
    Column(
        modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(BorderStroke(1.dp, Nocturne.divider), RoundedCornerShape(16.dp))
            .background(Nocturne.bg),
    ) {
        items.forEachIndexed { index, item ->
            LockedRow(item) { onOpen(item) }
            if (index < items.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.divider))
        }
    }
}

@Composable
private fun LockedRow(item: ApiAchItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            // Nobody can work towards a retired or admin-granted one; the board greys the whole row.
            .alpha(if (item.off) 0.62f else 1f)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AchDisc(item, 40, 18)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    item.name,
                    style = Type.body(15, FontWeight.Bold),
                    color = Nocturne.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    item.tierName?.takeIf { it.isNotBlank() } ?: achTierLabel(item.tier),
                    style = Type.body(11, FontWeight.Bold),
                    color = Nocturne.textSecondary,
                    maxLines = 1,
                )
                if (item.repeatable) {
                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Nocturne.neutral400).padding(horizontal = 6.dp)) {
                        Text(stringResource(R.string.ach_filter_repeat), style = Type.body(11, FontWeight.Bold), color = Nocturne.textSecondary, maxLines = 1)
                    }
                }
            }
            (item.how ?: item.description)?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = Type.body(13), color = Nocturne.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                item.off -> Text(
                    stringResource(if (item.closed) R.string.ach3_closed else R.string.ach3_manual),
                    style = Type.body(12),
                    color = Nocturne.textSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                // A repeatable one's counter restarts each time, and a one-step
                // goal has nothing between none and done to show.
                !item.repeatable && item.target > 1 -> Row(
                    Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GrowthBar(item.fraction(), Modifier.weight(1f).widthIn(max = 240.dp), height = 4.dp)
                    Text(
                        "${formatCount(item.current.coerceAtMost(item.target))} / ${formatCount(item.target)}",
                        style = Type.body(12),
                        color = Nocturne.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (item.repeatable) stringResource(R.string.ach_rep_each, item.reputation) else "+${item.reputation}",
                style = Type.body(15, FontWeight.Bold),
                color = Nocturne.text,
                maxLines = 1,
            )
            Text(stringResource(R.string.ach_rep_unit), style = Type.body(11), color = Nocturne.textSecondary, maxLines = 1)
        }
    }
}

@Composable
private fun ShowcaseCard(state: AchievementsState, onOpen: (ApiAchItem) -> Unit) {
    val byKey = state.items.associateBy { it.key }
    val pinned = state.showcase.mapNotNull { byKey[it] }.ifEmpty { state.items.filter { it.pinned } }
    AchOutlinedCard {
        AchCardHead(stringResource(R.string.ach_showcase), stringResource(R.string.ach_showcase_count, pinned.size, state.maxShowcase))
        if (pinned.isEmpty()) {
            Text(
                stringResource(R.string.ach_showcase_none),
                style = Type.body(13),
                color = Nocturne.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pinned.take(state.maxShowcase).forEach { item ->
                    val press = rememberPressSource()
                    Column(
                        Modifier
                            .weight(1f)
                            .pressScale(press)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(interactionSource = press, indication = ripple(), role = Role.Button) { onOpen(item) }
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AchDisc(item, 52, 24)
                        Text(item.name, style = Type.body(13, FontWeight.Medium), color = Nocturne.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat((state.maxShowcase - pinned.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
            }
        }
        Text(
            stringResource(R.string.ach_showcase_hint),
            style = Type.body(12),
            color = Nocturne.textSecondary,
            modifier = Modifier.padding(top = 10.dp),
        )
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
    val ink = if (item.unlocked) Nocturne.text else Nocturne.textSecondary
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nocturne.bg,
        icon = { AchMedal(item.tier, item.unlocked, achievementIcon(item.icon), 64.dp, 28.dp) },
        title = {
            Text(item.name, style = Type.heading(20, FontWeight.Bold), color = Nocturne.text, textAlign = TextAlign.Center)
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${achTierLabel(item.tier)} · ${
                        if (item.repeatable) stringResource(R.string.ach_rep_each_full, item.reputation)
                        else stringResource(R.string.ach_reward, item.reputation)
                    }",
                    style = Type.body(13, FontWeight.SemiBold),
                    color = if (item.unlocked) ink else Nocturne.textSecondary,
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
                } else if (!item.off && !item.repeatable && item.target > 1) {
                    Text(
                        "${formatCount(item.current.coerceAtMost(item.target))} / ${formatCount(item.target)}",
                        style = Type.body(12),
                        color = Nocturne.textTertiary,
                    )
                } else if (item.off) {
                    Text(
                        stringResource(if (item.closed) R.string.ach_closed else R.string.ach_manual),
                        style = Type.body(12),
                        color = Nocturne.textTertiary,
                    )
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

