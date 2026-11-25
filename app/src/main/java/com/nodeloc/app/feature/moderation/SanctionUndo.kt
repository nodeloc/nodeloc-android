package com.nodeloc.app.feature.moderation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nodeloc.app.R
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The "撤销" snackbar after a moderation write that has an inverse.
 *
 * An offer outlives whatever made it: the sheet that deleted a reply is gone
 * by the time the snackbar shows, so the undo runs in a scope of its own
 * rather than in the sheet's or a screen's.
 */
object ModUndo {
    class Offer(
        val id: Long,
        @StringRes val messageRes: Int,
        val args: List<Any>,
        val undo: suspend () -> Unit,
        val afterUndo: () -> Unit,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _offer = MutableStateFlow<Offer?>(null)
    val offer: StateFlow<Offer?> = _offer.asStateFlow()
    private var nextId = 0L

    fun offer(@StringRes messageRes: Int, vararg args: Any, afterUndo: () -> Unit = {}, undo: suspend () -> Unit) {
        _offer.value = Offer(++nextId, messageRes, args.toList(), undo, afterUndo)
    }

    internal fun consume(id: Long) {
        if (_offer.value?.id == id) _offer.value = null
    }

    internal fun run(offer: Offer) {
        scope.launch {
            runCatchingCancellable { offer.undo() }
                .onSuccess {
                    ToastCenter.show(R.string.mod_sheet_undone)
                    offer.afterUndo()
                }
                .onFailure { ToastCenter.showError(it) }
        }
    }
}

/**
 * Shows [ModUndo] offers along the bottom edge of [content]. Mount it once per
 * screen that starts moderation writes; the reader mounts its own.
 */
@Composable
fun ModUndoHost(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    val host = remember { SnackbarHostState() }
    val offer by ModUndo.offer.collectAsState()
    val resources = androidx.compose.ui.platform.LocalResources.current
    val undoLabel = stringResource(R.string.common_undo)
    LaunchedEffect(offer?.id) {
        val current = offer ?: return@LaunchedEffect
        ModUndo.consume(current.id)
        val message = resources.getString(current.messageRes, *current.args.toTypedArray())
        if (host.showSnackbar(message, actionLabel = undoLabel, withDismissAction = false) == SnackbarResult.ActionPerformed) {
            ModUndo.run(current)
        }
    }
    Box(modifier.fillMaxSize()) {
        content()
        SnackbarHost(host, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp)) { data ->
            Snackbar(
                modifier = Modifier.padding(horizontal = 16.dp),
                containerColor = Nocturne.text,
                contentColor = Nocturne.bg,
                action = {
                    data.visuals.actionLabel?.let { label ->
                        TextButton(onClick = { data.performAction() }) {
                            Text(label, style = Type.body(14), color = if (Nocturne.isDark) Nocturne.accent800 else Nocturne.accent300)
                        }
                    }
                },
            ) { Text(data.visuals.message, style = Type.body(14)) }
        }
    }
}
