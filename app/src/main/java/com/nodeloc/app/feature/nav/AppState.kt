package com.nodeloc.app.feature.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.model.CurrentUser
import com.nodeloc.app.core.network.DiscourseConfig
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    /** The guest gate; see [LoginGate]. Kept here for the screens that already hold the app model. */
    val loginGate: StateFlow<LoginPromptContext?> = LoginGate.request

    fun showLoginGate(context: LoginPromptContext = LoginPromptContext.Generic) = LoginGate.show(context)

    /**
     * For entry points that first close a menu or sheet of their own: raising
     * the gate in the same frame loses it behind the thing being dismissed.
     */
    fun showLoginGateAfterDismiss(context: LoginPromptContext = LoginPromptContext.Generic) {
        viewModelScope.launch {
            delay(LoginGateDelayMillis)
            LoginGate.show(context)
        }
    }

    fun dismissLoginGate() = LoginGate.dismiss()

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
