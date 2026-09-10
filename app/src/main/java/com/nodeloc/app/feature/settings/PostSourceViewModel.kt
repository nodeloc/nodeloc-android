package com.nodeloc.app.feature.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.util.DeviceInfo
import com.nodeloc.app.core.util.runCatchingCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The rungs, and what each one would print on this handset.
 *
 * The sample is the point of the screen. "手机型号" is a category; "来自 iQOO
 * Z11i" is the thing that will appear under everything you write, and only one
 * of the two is enough to decide on.
 */
enum class PostSourceLevel(val value: Int, @StringRes val labelRes: Int) {
    Off(0, R.string.post_source_level_off),
    Client(1, R.string.post_source_level_client),
    Platform(2, R.string.post_source_level_platform),
    Brand(3, R.string.post_source_level_brand),
    Model(4, R.string.post_source_level_model),
    ;

    /**
     * Mirrors the server's ladder, including its fallbacks — a phone whose ROM
     * hides its marketing name shows its code here too, rather than promising
     * a name it will not get.
     */
    fun sample(): String? = when (this) {
        Off -> null
        Client -> APP_NAME
        Platform -> "Android"
        Brand -> DeviceInfo.displayBrand.ifBlank { "Android" }
        Model -> DeviceInfo.marketingName
            ?: DeviceInfo.model.ifBlank { DeviceInfo.displayBrand.ifBlank { "Android" } }
    }

    companion object {
        private const val APP_NAME = "NodeLoc App"

        fun of(value: Int): PostSourceLevel = entries.firstOrNull { it.value == value } ?: Off
    }
}

data class PostSourceState(
    val level: Int = 0,
    val isSaving: Boolean = false,
)

class PostSourceViewModel : ViewModel() {
    private val services = ServiceLocator.get
    private val client = services.client
    private val preferences = services.preferences

    private val _state = MutableStateFlow(PostSourceState())
    val state: StateFlow<PostSourceState> = _state.asStateFlow()

    /**
     * The device's copy first, then the server's.
     *
     * The stored copy is what the composer reads, so it is the one that has to
     * be right; asking the server on open is how it gets corrected after a
     * reinstall or a change made from the web.
     */
    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(level = preferences.currentPostSourceLevel())
            val remote = runCatchingCancellable { client.postSourcePreference() }.getOrNull() ?: return@launch
            preferences.setPostSourceLevel(remote.level)
            _state.value = _state.value.copy(level = remote.level)
        }
    }

    fun select(level: Int) {
        if (level == _state.value.level || _state.value.isSaving) return
        val previous = _state.value.level
        // Optimistic, and rolled back on failure like every other write here.
        _state.value = _state.value.copy(level = level, isSaving = true)
        viewModelScope.launch {
            val outcome = runCatchingCancellable { client.setPostSourcePreference(level) }
            outcome
                .onSuccess { preferences.setPostSourceLevel(it.level) }
                .onFailure {
                    ToastCenter.showError(it)
                    _state.value = _state.value.copy(level = previous)
                }
            _state.value = _state.value.copy(isSaving = false)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            runCatchingCancellable { client.clearPostSources() }
                .onSuccess { ToastCenter.show(R.string.post_source_cleared) }
                .onFailure { ToastCenter.showError(it) }
        }
    }
}
