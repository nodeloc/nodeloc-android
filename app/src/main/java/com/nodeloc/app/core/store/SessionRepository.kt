package com.nodeloc.app.core.store

import com.nodeloc.app.R
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.model.CurrentUser
import com.nodeloc.app.core.network.AuthService
import com.nodeloc.app.core.network.DiscourseClient
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.network.DiscourseError
import com.nodeloc.app.core.network.clearWebViewSession
import com.nodeloc.app.core.util.AppLocale
import com.nodeloc.app.core.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Who is signed in, app-wide.
 *
 * `session/current.json` is fetched **once** per sign-in and shared: the tab
 * bar avatar, the reader's avatar slot, the composer's energy balance and the
 * inbox badge all read from here rather than each firing their own request.
 */
class SessionRepository(
    private val client: DiscourseClient,
    private val authService: AuthService,
    private val preferences: AppPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var accountScoped: List<AccountScoped> = emptyList()

    // Written from whichever thread signs out — main from settings, IO from an
    // involuntary one — and read from IO in onSignedIn.
    @Volatile
    private var purgeJob: Job? = null

    // One language lookup per sign-in. Cleared on the way out so the next
    // account is asked about afresh.
    @Volatile
    private var languageAdopted = false

    /**
     * Wired after construction rather than injected: every repository holding
     * per-account state is built *after* this one, so a constructor argument
     * would put a cycle into a graph that deliberately has none.
     */
    fun registerAccountScoped(vararg targets: AccountScoped) {
        accountScoped = targets.toList()
    }

    private val _currentUser = MutableStateFlow<CurrentUser?>(null)
    val currentUser: StateFlow<CurrentUser?> = _currentUser.asStateFlow()

    private val _isSignedIn = MutableStateFlow(false)
    val isSignedIn: StateFlow<Boolean> = _isSignedIn.asStateFlow()

    val username: String? get() = _currentUser.value?.username ?: client.auth.username

    val avatarUrl: String? get() = DiscourseConfig.avatarUrl(_currentUser.value?.avatarTemplate, 120)

    fun bootstrap() {
        val restored = authService.restore()
        _isSignedIn.value = restored
        if (!restored) return
        scope.launch {
            // A restored cookie session carries a CSRF token from last launch,
            // and Discourse rotates it — every write would 403 until it's
            // refreshed, so do that before anything else touches the network.
            if (client.auth.hasSession) authService.refreshCsrfToken()
            refresh(force = true)
        }
    }

    /**
     * Reloads the current user. A 404 here is Discourse's way of saying "not
     * signed in", so it clears the session rather than surfacing an error.
     */
    suspend fun refresh(force: Boolean = false): CurrentUser? {
        if (!client.auth.isAuthenticated) {
            _currentUser.value = null
            _isSignedIn.value = false
            return null
        }
        return mutex.withLock {
            if (!force && _currentUser.value != null) return@withLock _currentUser.value
            try {
                val user = client.currentUser().currentUser
                _currentUser.value = user
                _isSignedIn.value = true
                preferences.applyAccountReadingMode(user.userOption?.communityViewMode)
                adoptAccountLanguage(user.username)
                user
            } catch (error: DiscourseError) {
                if (error.isNotFound || error.isUnauthorized) {
                    // The cookie rotated out from under us, or the key was
                    // revoked. Either way the session is gone, and it has to go
                    // the same way a deliberate sign-out does — otherwise the
                    // next account inherits this one's caches.
                    signOut(involuntary = true)
                }
                null
            }
        }
    }

    /** Called after any successful sign-in path. */
    suspend fun onSignedIn() {
        // A purge still in flight would otherwise land on the new account's
        // DataStore, wiping settings it has only just read.
        purgeJob?.join()

        // Signing in as somebody else without signing out first is reachable:
        // the OAuth redirect can arrive at any moment. Purge before fetching,
        // not after — refresh() writes the account's reading mode, and a purge
        // behind it would delete what it had just applied.
        // Conservative on purpose: an identity we cannot name yet is treated as
        // a different one. The alternative — assuming it is the same account —
        // hands the next user the previous one's preferences and caches.
        val previous = _currentUser.value?.username
        if (previous != null && previous != client.auth.username) purgeAccountState()

        _isSignedIn.value = true
        refresh(force = true)
        // The website sign-in path never sees a username — the provider hands
        // back a cookie, not a name — so this is where it learns one.
        _currentUser.value?.username?.let(authService::rememberUsername)
        joinPendingInterestNodes()
    }

    /**
     * Switches the app to the language the site shows this member.
     *
     * Not from `session/current.json`: Discourse only serialises
     * `effective_locale` when content localisation is on, and it is off here.
     * Not from the profile's own `locale` column either — see
     * [DiscourseClient.effectiveLocale] for why that one cannot be read alone.
     * One request, once per sign-in rather than once per refresh.
     *
     * A failed request leaves whatever language is in force alone, and lets the
     * next refresh try again; the site answering with a language we ship no
     * strings for hands the phone back its say.
     */
    private fun adoptAccountLanguage(username: String) {
        if (username.isEmpty() || languageAdopted) return
        languageAdopted = true
        scope.launch {
            val locale = runCatchingCancellable { client.effectiveLocale() }.getOrNull()
                ?: run {
                    languageAdopted = false
                    return@launch
                }
            AppLocale.applyFromAccount(locale)
        }
    }

    /**
     * The server rejected this session, from wherever the call was made.
     *
     * Launched rather than run inline: the caller is a request that is about to
     * unwind, and signing out fans out across five repositories and raises a
     * toast.
     */
    fun onSessionRejectedByServer() {
        scope.launch { signOut(involuntary = true) }
    }

    /**
     * The only way out of an account.
     *
     * The credential and the flags go synchronously so the UI flips this frame;
     * the disk, the WebView and the caches follow on the IO scope. Everything
     * keyed to the account has to be included — this used to clear three fields
     * and leave the next account reading the previous one's preferences, node
     * memberships, chat transcripts and push watermark.
     */
    fun signOut(involuntary: Boolean = false) {
        authService.clearCredentials()
        _currentUser.value = null
        _isSignedIn.value = false
        // The account borrowed the language; the phone gets it back.
        languageAdopted = false
        AppLocale.followSystem()
        purgeJob = scope.launch { purgeAccountState() }
        if (involuntary) ToastCenter.show(R.string.session_expired)
    }

    /**
     * Runs after the credential is already gone, so nothing here can reach the
     * network. Each step is isolated: one repository failing must not leave the
     * next one holding the account that just left.
     */
    private suspend fun purgeAccountState() {
        runCatching { preferences.clearAccountScoped() }
        accountScoped.forEach { runCatching { it.resetForSignOut() } }
        clearWebViewSession()
    }

    /**
     * Nodes picked during signup are joined here, because a fresh account may
     * need email activation before it can join anything — the choice is parked
     * in DataStore and applied the next time a sign-in succeeds.
     */
    private suspend fun joinPendingInterestNodes() {
        val pending = runCatching { preferences.pendingInterestNodes.first() }.getOrDefault(emptySet())
        if (pending.isEmpty()) return
        pending.forEach { id -> runCatching { client.joinNode(id) } }
        preferences.clearPendingInterestNodes()
    }
}
