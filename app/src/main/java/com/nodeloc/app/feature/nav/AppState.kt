package com.nodeloc.app.feature.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.model.CurrentUser
import com.nodeloc.app.core.network.DiscourseConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Cross-screen state: who is signed in, the inbox badge, and the one login gate
 * every guest entry point raises.
 */
class AppViewModel : ViewModel() {
    private val services = ServiceLocator.get
    private val session = services.session
    private val messageCenter = services.messageCenter

    val currentUser: StateFlow<CurrentUser?> = session.currentUser
    val isSignedIn: StateFlow<Boolean> = session.isSignedIn

    val avatarUrl: StateFlow<String?> = session.currentUser
        .map { DiscourseConfig.avatarUrl(it?.avatarTemplate, 120) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val unreadTotal: StateFlow<Int> = messageCenter.state
        .map { it.unreadTotal }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    init {
        // Loaded here rather than by whichever screen happens to be looked at
        // first. It used to be asked for by the feed, so an app that came back
        // onto the chat tab — which is where it left off — had an empty list
        // and showed everybody, including the people just blocked.
        viewModelScope.launch {
            session.currentUser.collect { services.blockedUsers.load(it?.username) }
        }
    }

    /**
     * The bottom sheet a guest sees when they tap something that needs an
     * account. Raised, never two at once — chaining sheets loses the second,
     * so callers dismiss first and wait ~450ms (see [LoginGateDelayMillis]).
     */
    private val _loginGateVisible = MutableStateFlow(false)
    val loginGateVisible: StateFlow<Boolean> = _loginGateVisible.asStateFlow()

    fun showLoginGate() {
        _loginGateVisible.value = true
    }

    /**
     * For entry points that first close a menu or sheet of their own: raising
     * the gate in the same frame loses it behind the thing being dismissed.
     */
    fun showLoginGateAfterDismiss() {
        viewModelScope.launch {
            delay(LoginGateDelayMillis)
            _loginGateVisible.value = true
        }
    }

    fun dismissLoginGate() {
        _loginGateVisible.value = false
    }

    fun refreshSession() {
        viewModelScope.launch { session.refresh(force = true) }
    }

    fun refreshBadge() {
        viewModelScope.launch { messageCenter.load() }
    }

    fun signOut() {
        // Everything else the account owned goes with it, inside the repository.
        session.signOut()
    }

    companion object {
        /** Presenting a sheet on top of a dismissing one drops it. */
        const val LoginGateDelayMillis = 450L
    }
}
