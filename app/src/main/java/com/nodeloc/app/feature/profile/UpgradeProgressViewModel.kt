package com.nodeloc.app.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nodeloc.app.core.util.runCatchingCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UpgradeProgressState(
    val reputation: ApiRepSummary? = null,
    /** Names and colours; the ladder still draws, in plain pills, without them. */
    val tiers: List<ApiTierInfo> = emptyList(),
    val isLoading: Boolean = false,
    val error: Throwable? = null,
)

/**
 * The climb to the next tier.
 *
 * `/me/reputation` is the whole page: standing, the next rung, this week's
 * gain, the per-source totals and every rung's threshold. Tiers are earned by
 * reputation alone now, so there is no checklist left to draw.
 */
class UpgradeProgressViewModel : ViewModel() {
    private val growth = GrowthApi.shared

    private val _state = MutableStateFlow(UpgradeProgressState())
    val state: StateFlow<UpgradeProgressState> = _state.asStateFlow()

    private var username: String? = null

    /** Keyed on the name only to reload after an account switch; the endpoint is always `/me`. */
    fun bind(username: String) {
        if (this.username == username) return
        this.username = username
        load()
    }

    fun retry() = load()

    private fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            val tiers = async { TierDirectory.get() }
            val outcome = runCatchingCancellable { growth.reputation() }
            _state.value = UpgradeProgressState(
                reputation = outcome.getOrNull() ?: _state.value.reputation,
                tiers = tiers.await(),
                isLoading = false,
                error = outcome.exceptionOrNull(),
            )
        }
    }
}
