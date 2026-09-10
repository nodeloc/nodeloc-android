package com.nodeloc.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.edit
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocTheme
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.ToastHost
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.update.UpdatePrompt
import com.nodeloc.app.core.util.AppLocale
import com.nodeloc.app.feature.nav.NodelocApp
import com.nodeloc.app.feature.nav.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // Before onCreate, and before a single string is read: a screen that draws
    // in the system language and then flips to the account's is worse than one
    // that waits for the answer.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val services = ServiceLocator.init(this)

        // The account's language arrives after the window is already up, and on
        // the very first sign-in it arrives while the user is looking at it.
        // The collector's first emission is the language this activity was
        // built with, so a start in the right language costs nothing.
        val builtWith = AppLocale.tag.value
        lifecycleScope.launch {
            AppLocale.tag.collect { if (it != builtWith) recreate() }
        }
        // Errors reach the user as friendly text; status codes never do.
        ToastCenter.attach(this)
        services.session.bootstrap()
        services.pushRepository.ensureChannels()
        // Scheduled work does not survive a force-stop, and enabling push was
        // the only thing that ever scheduled it.
        lifecycleScope.launch { services.pushRepository.rescheduleIfEnabled() }

        // Quiet unless there is something to say: a check that finds nothing
        // is not news, and this one runs whether or not anybody asked.
        services.updates.check()

        // Only a fresh start. On a restore the system redelivers the original
        // VIEW intent, and the back stack it restores already contains the
        // screen it opened — so this pushed a second copy on top of it.
        if (savedInstanceState == null) handleIntent(intent)

        // The welcome screen is a first-launch thing only; every later start
        // goes straight to the feed, signed in or not.
        val launchPrefs = getSharedPreferences("nodeloc.install", MODE_PRIVATE)
        val showWelcome = !launchPrefs.getBoolean(KEY_SEEN_WELCOME, false)
        if (showWelcome) launchPrefs.edit { putBoolean(KEY_SEEN_WELCOME, true) }

        setContent {
            // The stored choice, not the system's: settings offers 浅色/深色 as
            // well as 跟随系统, and until this was read back the first two
            // saved a value nothing ever looked at.
            val colorMode by services.preferences.colorMode.collectAsState(initial = "system")
            val darkTheme = when (colorMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            NodelocTheme(darkTheme = darkTheme, reduceMotion = animationsDisabled()) {
                Box(Modifier.fillMaxSize().background(Nocturne.bg)) {
                    NodelocApp(startDestination = if (showWelcome) Route.Welcome else Route.Home)
                    UpdatePrompt()
                    ToastHost()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // The in-app badge supersedes anything sitting in the shade.
        ServiceLocator.get.pushRepository.clearDeliveredNotifications()
    }

    /**
     * Two kinds of incoming link: the User API Key redirect (`nodeloc://auth`),
     * and a tapped notification or shared nodeloc.com URL.
     */
    private fun handleIntent(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (data.scheme == "nodeloc" && data.host == "auth") {
            lifecycleScope.launch {
                val services = ServiceLocator.get
                runCatching { services.authService.handleAuthRedirect(data) }
                    .onSuccess { services.session.onSignedIn() }
                    .onFailure { ToastCenter.showError(it) }
            }
            return
        }
        if (data.host?.endsWith("nodeloc.com") == true) {
            _pendingLink.value = data.toString()
        }
    }

    /**
     * "Reduce motion" on Android is the animator duration scale; zero means the
     * brand loader must stand still at full brightness rather than animate.
     */
    private fun animationsDisabled(): Boolean =
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    companion object {
        private const val KEY_SEEN_WELCOME = "seen_welcome"

        /**
         * Links that arrive as intents, drained by the nav host.
         *
         * A flow rather than a plain field because the second one matters as
         * much as the first: a notification tapped while the app is already
         * running, or a site link handed back by a mini program, both arrive
         * through `onNewIntent` long after the graph was composed.
         */
        private val _pendingLink = MutableStateFlow<String?>(null)
        val pendingLink: StateFlow<String?> = _pendingLink.asStateFlow()

        fun consumePendingLink() {
            _pendingLink.value = null
        }
    }
}
