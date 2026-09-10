package com.nodeloc.app.core.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.util.runCatchingCancellable
import kotlinx.coroutines.launch

/**
 * Stop seeing someone.
 *
 * Discourse calls it ignoring, and it is the strong one: their topics leave
 * the lists, their replies collapse, their messages stop arriving. The word
 * on the button is "block" because that is what everyone else calls it.
 *
 * Asked before it happens, because it is not obvious from the outside that it
 * can be taken back, and the settings list that takes it back is two screens
 * away. The failure is worth showing rather than swallowing: the server has
 * two rules of its own — staff cannot be ignored, and a site decides through
 * `ignore_allowed_groups` who may ignore at all — and both come back as a
 * sentence saying so.
 */
@Composable
fun BlockUserDialog(username: String, onDismiss: () -> Unit, onBlocked: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    ConfirmDialog(
        title = stringRes(R.string.block_user_title),
        body = stringRes(R.string.block_user_message, username),
        confirmLabel = stringRes(R.string.block_user_confirm),
        onDismiss = onDismiss,
        onConfirm = {
            onDismiss()
            // On the lists before the round trip, and taken back off if
            // the server refuses: blocking somebody and watching them stay is
            // the whole complaint this answers.
            ServiceLocator.get.blockedUsers.add(username)
            scope.launch {
                runCatchingCancellable { ServiceLocator.get.client.ignoreUser(username) }
                    .onSuccess {
                        ToastCenter.show(R.string.block_user_done)
                        onBlocked()
                    }
                    .onFailure {
                        ServiceLocator.get.blockedUsers.remove(username)
                        ToastCenter.showError(it)
                    }
            }
        },
    )
}

@Composable
private fun stringRes(id: Int, vararg args: Any): String =
    androidx.compose.ui.res.stringResource(id, *args)
