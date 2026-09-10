package com.nodeloc.app.core.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type

/**
 * The "there is a new version" dialog, shown wherever the user happens to be.
 *
 * A mandatory update takes away the dismiss button and the tap-outside, which
 * is the only difference between the two: there is still no way to install
 * anything without the user agreeing on the system's own screen.
 */
@Composable
fun UpdatePrompt() {
    val updates = ServiceLocator.get.updates
    val update by updates.available.collectAsState()
    val stage by updates.stage.collectAsState()
    val release = update ?: return
    val busy = stage == UpdateStage.Downloading

    AlertDialog(
        onDismissRequest = { if (!release.mandatory && !busy) updates.dismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !release.mandatory && !busy,
            dismissOnClickOutside = !release.mandatory && !busy,
        ),
        containerColor = Nocturne.bg,
        title = {
            Text(
                stringResource(R.string.update_available_title, release.manifest.versionName),
                style = Type.heading(17),
                color = Nocturne.text,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                release.manifest.notes?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = Type.body(13), color = Nocturne.muted(0.6f))
                }
                if (release.mandatory) {
                    Text(
                        stringResource(R.string.update_available_required),
                        style = Type.body(13),
                        color = Nocturne.danger,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = updates::downloadAndInstall, enabled = !busy) {
                Text(
                    stringResource(if (busy) R.string.update_downloading else R.string.update_install),
                    color = if (busy) Nocturne.muted(0.4f) else Nocturne.accent,
                )
            }
        },
        dismissButton = {
            if (!release.mandatory) {
                TextButton(onClick = updates::dismiss, enabled = !busy) {
                    Text(stringResource(R.string.update_later), color = Nocturne.muted(0.6f))
                }
            }
        },
    )
}
