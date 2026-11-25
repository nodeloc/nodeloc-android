package com.nodeloc.app.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nodeloc.app.core.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class UpgradeProgressState(
    val reputation: ApiRepSummary? = null,
    /** Names and colours; the ladder still draws, in plain pills, without them. */
    val tiers: List<ApiTierInfo> = emptyList(),
    val isLoading: Boolean = false,
    val error: Throwable? = null,
    val permissions: PermMatrix? = null,
    val permissionsLoading: Boolean = false,
    val permissionsFailed: Boolean = false,
)

/**
 * The permission matrix, kept for the session.
 *
 * The page is computed from settings that change rarely, and it is a whole
 * HTML page to fetch. Keyed on the account, since the reader's own column is
 * marked in it; a failure is not kept, so the retry refetches.
 */
object PermissionsCache {
    private class NoMarkup : IllegalStateException("permissions markup not found")

    private val lock = Mutex()
    private var cached: Pair<String, PermMatrix>? = null

    suspend fun get(account: String): PermMatrix = lock.withLock {
        cached?.takeIf { it.first == account }?.second ?: run {
            val html = GrowthApi.shared.permissionsPage()
            val matrix = withContext(Dispatchers.Default) { PermissionsHtml.parse(html) } ?: throw NoMarkup()
            cached = account to matrix
            matrix
        }
    }
}

/**
 * The climb to the next tier.
 *
 * `/me/reputation` is the hero: standing, the next rung, this week's gain and
 * every rung's threshold. The permission matrix under it is a separate read
 * that fails on its own: the hero stays when the table does not come.
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
        loadPermissions()
    }

    fun retry() {
        load()
        if (_state.value.permissions == null) loadPermissions()
    }

    fun retryPermissions() = loadPermissions()

    private fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            val tiers = async { TierDirectory.get() }
            val outcome = runCatchingCancellable { growth.reputation() }
            _state.value = _state.value.copy(
                reputation = outcome.getOrNull() ?: _state.value.reputation,
                tiers = tiers.await(),
                isLoading = false,
                error = outcome.exceptionOrNull(),
            )
        }
    }

    private fun loadPermissions() {
        val account = username ?: return
        if (_state.value.permissionsLoading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(permissionsLoading = true, permissionsFailed = false)
            val outcome = runCatchingCancellable { PermissionsCache.get(account) }
            _state.value = _state.value.copy(
                permissions = outcome.getOrNull() ?: _state.value.permissions,
                permissionsLoading = false,
                permissionsFailed = outcome.isFailure,
            )
        }
    }
}
