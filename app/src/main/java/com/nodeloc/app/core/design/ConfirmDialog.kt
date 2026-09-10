package com.nodeloc.app.core.design

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nodeloc.app.R

/**
 * "Are you sure" for the handful of actions this app cannot undo.
 *
 * Deliberately not offered for everything: a confirmation on a reversible
 * action teaches people to tap through confirmations. This exists for deleting,
 * where the app has no undo and the site's is a web page away.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nocturne.bg,
        title = { Text(title, style = Type.heading(17), color = Nocturne.text) },
        text = { Text(body, style = Type.body(13), color = Nocturne.muted(0.6f)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = Nocturne.danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), color = Nocturne.muted(0.6f))
            }
        },
    )
}
