package com.nodeloc.app.core.design

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nodeloc.app.core.network.friendlyMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App-wide transient notice. Exists because of a hard rule: an action the user
 * took must never fail silently — a like, bookmark or reply that didn't land
 * says so. One toast at a time; a new message replaces the current one and
 * restarts the clock.
 */
object ToastCenter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private var hideJob: Job? = null

    @Volatile
    private var appContext: Context? = null

    /** Called once at startup; lets non-Compose code resolve string resources. */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun show(text: String) {
        hideJob?.cancel()
        _message.value = text
        hideJob = scope.launch {
            delay(2600)
            _message.value = null
        }
    }

    fun show(@StringRes resId: Int, vararg args: Any) {
        val context = appContext ?: return
        show(context.getString(resId, *args))
    }

    /** The friendly line for a failed action — never the raw exception. */
    fun showError(error: Throwable) {
        val context = appContext ?: return
        show(error.friendlyMessage(context))
    }
}

/** Mounted once at the root; floats above everything. */
@Composable
fun ToastHost(modifier: Modifier = Modifier) {
    val message by ToastCenter.message.collectAsState()
    // Along the bottom edge. At the top it landed under the notch or the
    // camera cut-out on the phones that have one, which is every phone this
    // app is used on — and a message nobody can read is not a message.
    Box(
        modifier
            .fillMaxSize()
            // The keyboard when it is up, the navigation bar when it is not,
            // and clear of the tab bar either way.
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(bottom = 72.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = message != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Surface(
                shape = CircleShape,
                color = Nocturne.surface.copy(alpha = 0.96f),
                shadowElevation = 12.dp,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
            ) {
                Text(
                    message.orEmpty(),
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Nocturne.bg.copy(alpha = 0.5f))
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                    style = Type.body(13, FontWeight.SemiBold),
                    color = Nocturne.text,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
