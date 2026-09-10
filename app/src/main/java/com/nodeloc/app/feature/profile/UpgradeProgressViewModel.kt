package com.nodeloc.app.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.model.UpgradeProgress
import com.nodeloc.app.core.util.runCatchingCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UpgradeProgressState(
    val progress: UpgradeProgress? = null,
    val isLoading: Boolean = false,
    val error: Throwable? = null,
)

/** One read, no paging, no writes — the whole report arrives in one call. */
class UpgradeProgressViewModel : ViewModel() {
    private val client = ServiceLocator.get.client

    private val _state = MutableStateFlow(UpgradeProgressState())
    val state: StateFlow<UpgradeProgressState> = _state.asStateFlow()

    private var username: String? = null

    fun bind(username: String) {
        if (this.username == username) return
        this.username = username
        load()
    }

    fun retry() = load()

    private fun load() {
        val target = username ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            val outcome = runCatchingCancellable { client.upgradeProgress(target) }
            _state.value = UpgradeProgressState(
                progress = outcome.getOrNull(),
                isLoading = false,
                error = outcome.exceptionOrNull(),
            )
        }
    }
}
