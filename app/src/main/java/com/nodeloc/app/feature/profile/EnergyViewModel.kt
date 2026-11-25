package com.nodeloc.app.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.network.ApiCheckinStatus
import com.nodeloc.app.core.network.DiscourseError
import com.nodeloc.app.core.util.rethrowIfCancellation
import com.nodeloc.app.core.util.runCatchingCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

enum class CheckinOutcome { Claimed, AlreadyClaimed, Failed }

/**
 * One 签到 attempt, shared by the ledger and the rich list, with its toast.
 * Callers guard first on a known-claimed day: the endpoint rate-limits every
 * attempt and only forgives a successful one.
 */
suspend fun claimDailyCheckin(growth: GrowthApi = GrowthApi.shared): CheckinOutcome {
    val preferences = ServiceLocator.get.preferences
    return try {
        val result = growth.checkin()
        preferences.setCheckinDate(result.day ?: LocalDate.now().toString())
        result.points?.let { ToastCenter.show(R.string.checkin_success, it) } ?: ToastCenter.show(R.string.checkin_done)
        CheckinOutcome.Claimed
    } catch (error: Throwable) {
        error.rethrowIfCancellation()
        // Already claimed answers 4xx: the day is spent, which is what the
        // page should now say rather than an error.
        if (error is DiscourseError.BadResponse && error.code in 400..499 && error.code != 401 && error.code != 429) {
            preferences.setCheckinDate(LocalDate.now().toString())
            ToastCenter.show(R.string.checkin_done)
            CheckinOutcome.AlreadyClaimed
        } else {
            ToastCenter.showError(error)
            CheckinOutcome.Failed
        }
    }
}

/** 全部 / 收入 / 支出: the ledger's direction filter. */
enum class EnergyDirection(val labelRes: Int, val key: String?) {
    All(R.string.energy_f_all, null),
    In(R.string.energy_f_in, "in"),
    Out(R.string.energy_f_out, "out"),
}

/**
 * The kind chips: the server's own groups (points/history.go `KindGroups`),
 * in its order, with 其他 last. The design's 赞同 chip has no ledger kind
 * behind it — likes earn reputation, not energy — so it is not offered.
 */
enum class EnergyGroup(val labelRes: Int, val key: String?) {
    All(R.string.energy_f_all, null),
    Checkin(R.string.energy_f_checkin, "checkin"),
    Tip(R.string.energy_f_tip, "tip"),
    Games(R.string.energy_f_games, "games"),
    Reward(R.string.energy_f_reward, "reward"),
    Spend(R.string.energy_f_spend, "spend"),
    Other(R.string.energy_f_other, "other"),
}

data class EnergyState(
    val balance: Long? = null,
    val transferRule: ApiTransferRule? = null,
    val income: Long? = null,
    val expense: Long? = null,
    /** Null when the account is outside the board's first page, which is all the server serves. */
    val rank: Int? = null,
    /** Whether the board answered at all, so a missing [rank] can say "outside the top" rather than "--". */
    val rankLoaded: Boolean = false,
    val checkin: ApiCheckinStatus? = null,
    val isCheckingIn: Boolean = false,
    val direction: EnergyDirection = EnergyDirection.All,
    val group: EnergyGroup = EnergyGroup.All,
    val month: YearMonth? = null,
    val entries: List<ApiEnergyEntry> = emptyList(),
    val total: Long? = null,
    val nextBefore: Long? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: Throwable? = null,
    val transfer: TransferState = TransferState(),
) {
    val isFiltered: Boolean get() = direction != EnergyDirection.All || group != EnergyGroup.All || month != null
}

data class TransferState(
    val isSending: Boolean = false,
    /** The last refusal, shown in the sheet so the reader can fix it there. */
    val error: Throwable? = null,
    /** Bumped on success so the sheet knows to close. */
    val doneCount: Int = 0,
)

class EnergyViewModel : ViewModel() {
    private val growth = GrowthApi.shared
    private val services = ServiceLocator.get
    private val _state = MutableStateFlow(EnergyState())
    val state: StateFlow<EnergyState> = _state.asStateFlow()
    private var listJob: Job? = null

    /** The key of the transfer last attempted, kept while its terms stay the same; see [transfer]. */
    private var pendingTransfer: Pair<String, String>? = null

    val me: String? get() = services.session.username

    init {
        loadHeader()
        loadList()
    }

    fun retry() {
        loadHeader()
        loadList()
    }

    fun setDirection(direction: EnergyDirection) {
        if (direction == _state.value.direction) return
        _state.value = _state.value.copy(direction = direction)
        reload()
    }

    fun setGroup(group: EnergyGroup) {
        if (group == _state.value.group) return
        _state.value = _state.value.copy(group = group)
        reload()
    }

    fun setMonth(month: YearMonth?) {
        if (month == _state.value.month) return
        _state.value = _state.value.copy(month = month)
        reload()
    }

    fun resetFilters() {
        _state.value = _state.value.copy(direction = EnergyDirection.All, group = EnergyGroup.All, month = null)
        reload()
    }

    private fun reload() {
        _state.value = _state.value.copy(entries = emptyList(), nextBefore = null, total = null)
        loadList()
    }

    private fun loadHeader() {
        viewModelScope.launch {
            val info = async { runCatchingCancellable { growth.pointsInfo() }.getOrNull() }
            val month = async { runCatchingCancellable { growth.monthSummary() }.getOrNull() }
            val checkin = async { runCatchingCancellable { growth.checkinStatus() }.getOrNull() }
            val board = async { runCatchingCancellable { growth.leaderboard() }.getOrNull() }
            val me = services.session.username
            val p = info.await()
            val m = month.await()
            val rows = board.await()
            _state.value = _state.value.copy(
                balance = p?.balance ?: _state.value.balance,
                transferRule = p?.transfer ?: _state.value.transferRule,
                income = m?.income ?: _state.value.income,
                expense = m?.expense ?: _state.value.expense,
                checkin = checkin.await() ?: _state.value.checkin,
                rank = rows?.firstOrNull { it.username.equals(me, ignoreCase = true) }?.rank ?: _state.value.rank.takeIf { rows == null },
                rankLoaded = rows != null || _state.value.rankLoaded,
            )
        }
    }

    private fun currentFilters() = Triple(_state.value.direction, _state.value.group, _state.value.month)

    /** A filter change replaces the list; the job it supersedes must not write into it. */
    private fun loadList() {
        listJob?.cancel()
        val (direction, group, month) = currentFilters()
        listJob = viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            runCatchingCancellable { growth.history(direction.key, group.key, before = null, limit = PAGE, month = month?.toString()) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        entries = it.entries,
                        nextBefore = it.nextBefore,
                        total = it.total,
                        isLoading = false,
                    )
                }
                .onFailure { _state.value = _state.value.copy(isLoading = false, error = it) }
        }
    }

    fun loadMore() {
        val current = _state.value
        val before = current.nextBefore ?: return
        if (current.isLoading || current.isLoadingMore) return
        val filters = currentFilters()
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoadingMore = true)
            val (direction, group, month) = filters
            runCatchingCancellable { growth.history(direction.key, group.key, before, limit = PAGE, month = month?.toString()) }
                .onSuccess {
                    if (currentFilters() != filters) return@onSuccess
                    _state.value = _state.value.copy(
                        entries = (_state.value.entries + it.entries).distinctBy { e -> e.id },
                        nextBefore = it.nextBefore,
                        total = it.total ?: _state.value.total,
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
                when (claimDailyCheckin(growth)) {
                    CheckinOutcome.Claimed -> { loadHeader(); loadList() }
                    CheckinOutcome.AlreadyClaimed -> loadHeader()
                    CheckinOutcome.Failed -> Unit
                }
            } finally {
                _state.value = _state.value.copy(isCheckingIn = false)
            }
        }
    }

    fun clearTransferError() {
        if (_state.value.transfer.error != null) {
            _state.value = _state.value.copy(transfer = _state.value.transfer.copy(error = null))
        }
    }

    /**
     * Sends a transfer the reader has already confirmed.
     *
     * The server pays once per Idempotency-Key. A timeout can hide a transfer
     * that went through, so trying the very same terms again reuses the key —
     * the server then answers with the original result instead of paying
     * twice. Changing anything is a new transfer and gets a new key.
     */
    fun transfer(to: String, amount: Long, memo: String) {
        if (_state.value.transfer.isSending) return
        val terms = "$to|$amount|$memo"
        val key = pendingTransfer?.takeIf { it.first == terms }?.second ?: UUID.randomUUID().toString()
        pendingTransfer = terms to key
        viewModelScope.launch {
            _state.value = _state.value.copy(transfer = _state.value.transfer.copy(isSending = true, error = null))
            try {
                val result = growth.transfer(to, amount, memo, key)
                pendingTransfer = null
                _state.value = _state.value.copy(
                    balance = result.balance ?: _state.value.balance,
                    transfer = TransferState(doneCount = _state.value.transfer.doneCount + 1),
                )
                ToastCenter.show(R.string.energy_tf_done, formatCount(amount), to)
                loadHeader()
                loadList()
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                _state.value = _state.value.copy(transfer = _state.value.transfer.copy(isSending = false, error = error))
            }
        }
    }

    private companion object {
        const val PAGE = 20
    }
}
