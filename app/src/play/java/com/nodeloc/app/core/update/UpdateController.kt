package com.nodeloc.app.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nodeloc.app.core.network.DiscourseClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The store build's updater, which updates nothing.
 *
 * Play delivers the new version itself, and an app that fetches an APK and
 * calls the installer around it violates Device and Network Abuse — so this
 * flavour has neither the code nor the REQUEST_INSTALL_PACKAGES permission that
 * would let it. [available] never fills, which is what keeps the shared
 * `UpdatePrompt` from ever drawing.
 *
 * What is left is the settings row: a person who taps "check for updates"
 * asked a real question, and the honest answer is the store page, where the
 * button they want actually lives.
 *
 * Takes the client it never reads so that both flavours are constructed by the
 * same line in ServiceLocator.
 */
@Suppress("UNUSED_PARAMETER")
class UpdateController(
    private val context: Context,
    client: DiscourseClient,
) {
    val available: StateFlow<AvailableUpdate?> = MutableStateFlow(null)
    val stage: StateFlow<UpdateStage> = MutableStateFlow(UpdateStage.Idle)

    /** Silent at launch; only a deliberate tap opens anything. */
    fun check(announce: Boolean = false) {
        if (announce) openStoreListing()
    }

    fun dismiss() = Unit

    fun downloadAndInstall() = openStoreListing()

    /**
     * `market://` first so the Play app opens on its own listing rather than
     * bouncing through a browser; the https address is the fallback for a
     * device that has no store app, where it is also the only thing that works.
     */
    private fun openStoreListing() {
        val id = context.packageName
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$id"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(market) }
            .onFailure { runCatching { context.startActivity(web) } }
    }
}
