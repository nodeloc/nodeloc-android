package com.nodeloc.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocTheme
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.ToastHost
import com.nodeloc.app.core.util.AppLocale
import com.nodeloc.app.feature.media.MiniProgramShell
import com.nodeloc.app.feature.nav.LinkDestination
import com.nodeloc.app.feature.nav.LinkRouter
import kotlinx.coroutines.launch

/**
 * Host for one directory app, in a task of its own.
 *
 * A mini program is a sibling of the app, not a page inside it, which is the
 * whole reason this is an Activity rather than another nav destination:
 * `documentLaunchMode` plus a per-app intent URI earns each app its own card in
 * Recents, so one can be left running and returned to, and closing it does not
 * unwind the feed underneath. The chrome is [MiniProgramShell], shared with the
 * in-app browser.
 */
class MiniAppActivity : ComponentActivity() {

    // Before onCreate, and before a single string is read: a screen that draws
    // in the system language and then flips to the account's is worse than one
    // that waits for the answer.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val url = intent.getStringExtra(EXTRA_URL) ?: intent.dataString
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        val name = intent.getStringExtra(EXTRA_NAME)
        val label = name ?: getString(R.string.app_name)

        // Launched cold from Recents this process may be new, and the shell
        // reads the cookie jar before its first paint.
        ServiceLocator.init(this)
        ToastCenter.attach(this)

        applyTaskDescription(label, null)
        intent.getStringExtra(EXTRA_ICON)?.let { loadTaskIcon(it, label) }

        // Registered before the shell composes, so the shell's own handler —
        // added later, and therefore consulted first — still gets the presses it
        // wants for page history. This one catches the rest.
        onBackPressedDispatcher.addCallback(this) { close() }

        setContent {
            NodelocTheme {
                Box(Modifier.fillMaxSize().background(Nocturne.bg)) {
                    MiniProgramShell(
                        url = url,
                        onClose = ::close,
                        onLinkOutsideShell = ::leaveShell,
                    )
                    ToastHost()
                }
            }
        }
    }

    /**
     * Closing is not minimising. Home and Recents leave the card parked and the
     * app where the user left it; the capsule's ✕ and a back press at the root of
     * the page history mean *done*, and take the card with them.
     */
    private fun close() {
        finishAndRemoveTask()
    }

    /**
     * A link the shell won't load itself. Anything with a native screen belongs
     * to the main task — the mini program hands it over and stays where it is,
     * rather than growing a copy of the reader inside its own card.
     */
    private fun leaveShell(url: String) {
        if (LinkRouter.resolve(url) is LinkDestination.External) {
            // mailto:/tel: and friends have no in-app answer at all.
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
            return
        }
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = url.toUri()
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }

    /**
     * The Recents card carries the app's own name and logo, so a row of parked
     * mini programs is tellable apart.
     *
     * The deprecated constructor is the deliberate one: `TaskDescription.Builder`
     * (API 33+) only takes an icon as a drawable resource id, and a directory
     * app's logo is a bitmap fetched from the site.
     */
    @Suppress("DEPRECATION")
    private fun applyTaskDescription(label: String, icon: Bitmap?) {
        val color = ContextCompat.getColor(this, R.color.nodeloc_bg)
        setTaskDescription(ActivityManager.TaskDescription(label, icon, color))
    }

    private fun loadTaskIcon(iconUrl: String, label: String) {
        lifecycleScope.launch {
            val bitmap = runCatching {
                val request = ImageRequest.Builder(this@MiniAppActivity).data(iconUrl).build()
                (SingletonImageLoader.get(this@MiniAppActivity).execute(request) as? SuccessResult)
                    ?.image
                    ?.toBitmap()
            }.getOrNull() ?: return@launch
            applyTaskDescription(label, bitmap)
        }
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_ICON = "icon"

        fun launch(context: Context, url: String, name: String?, iconUrl: String?) {
            val intent = Intent(context, MiniAppActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                // Recents keys a document task on the intent's data, so this is
                // what makes re-opening an app return to its existing card
                // instead of stacking a second one.
                data = url.toUri()
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_ICON, iconUrl)
                addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            }
            runCatching { context.startActivity(intent) }
        }
    }
}
