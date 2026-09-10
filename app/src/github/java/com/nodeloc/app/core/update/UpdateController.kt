package com.nodeloc.app.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.nodeloc.app.R
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.network.DiscourseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The sideloaded build's updater: a manifest on the site, an APK, the system
 * installer.
 *
 * The Play build carries a different class of the same name that does none of
 * this. An app distributed through the store and updating itself around it is
 * a Device and Network Abuse violation, so the two cannot share one
 * implementation with a flag — the permission itself has to be absent.
 */
class UpdateController(
    private val context: Context,
    client: DiscourseClient,
) {
    private val repository = UpdateRepository(context, client)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _available = MutableStateFlow<AvailableUpdate?>(null)
    val available: StateFlow<AvailableUpdate?> = _available.asStateFlow()

    private val _stage = MutableStateFlow(UpdateStage.Idle)
    val stage: StateFlow<UpdateStage> = _stage.asStateFlow()

    /**
     * The launch check, which stays quiet about everything except a release.
     *
     * @param announce true when a person asked, and therefore deserves an
     *   answer either way — "already up to date" is only worth saying to
     *   someone who just pressed a button.
     */
    fun check(announce: Boolean = false) {
        if (_stage.value != UpdateStage.Idle) return
        _stage.value = UpdateStage.Checking
        scope.launch {
            val update = repository.check()
            _stage.value = UpdateStage.Idle
            if (update != null) {
                _available.value = update
            } else if (announce) {
                ToastCenter.show(R.string.update_up_to_date)
            }
        }
    }

    fun dismiss() {
        _available.value = null
    }

    /**
     * Downloads and hands the APK to the system installer.
     *
     * Nothing here installs anything: outside the Play Store — and outside a
     * device-owner or system app — Android has no silent install, so the last
     * step is always a screen the user taps. Sending them to grant "install
     * unknown apps" first, when they have not, is the difference between that
     * screen appearing and the button doing nothing at all.
     */
    fun downloadAndInstall() {
        val update = _available.value ?: return
        if (_stage.value != UpdateStage.Idle) return
        if (!canInstall()) {
            requestInstallPermission()
            return
        }
        _stage.value = UpdateStage.Downloading
        scope.launch {
            val file = repository.download(update)
            _stage.value = UpdateStage.Idle
            if (file == null) {
                ToastCenter.show(R.string.update_download_failed)
                return@launch
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    repository.installIntentUri(file),
                    "application/vnd.android.package-archive",
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
                .onFailure { ToastCenter.show(R.string.update_install_failed) }
        }
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    private fun requestInstallPermission() {
        ToastCenter.show(R.string.update_needs_install_permission)
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
