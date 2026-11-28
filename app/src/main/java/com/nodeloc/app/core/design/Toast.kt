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
import androidx.compose.runtime.setValue
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
    /** One notice, and optionally the one thing it lets you do about it (选择收藏夹, 撤销). */
    class Notice(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()
    private var hideJob: Job? = null

    @Volatile
    private var appContext: Context? = null

    /** Called once at startup; lets non-Compose code resolve string resources. */
    fun attach(context: Context) {
        // The application context keeps the phone's language; the activities
        // are wrapped in the in-app one. Wrapped here too, or a toast says
        // "Already checked in today" on a page that is in Chinese. Activities
        // are recreated on a language change, which re-attaches.
        appContext = com.nodeloc.app.core.util.AppLocale.wrap(context.applicationContext)
    }

    fun show(text: String) = post(Notice(text))

    fun show(@StringRes resId: Int, vararg args: Any) {
        val context = appContext ?: return
        show(context.getString(resId, *args))
    }

    /**
     * A notice with an action. It stays longer than a plain one: reading it and
     * reaching for the button takes more than the usual two and a half seconds.
     */
    fun showWithAction(text: String, @StringRes actionRes: Int, action: () -> Unit) {
        val context = appContext ?: return
        post(Notice(text, context.getString(actionRes), action), millis = 5000)
    }

    fun showWithAction(@StringRes resId: Int, @StringRes actionRes: Int, action: () -> Unit) {
        val context = appContext ?: return
        showWithAction(context.getString(resId), actionRes, action)
    }

    /** The friendly line for a failed action — never the raw exception. */
    fun showError(error: Throwable) {
        val context = appContext ?: return
        show(error.friendlyMessage(context))
    }

    /** Runs the showing notice's action once and takes the notice down. */
    fun runAction(notice: Notice) {
        if (_notice.value === notice) dismiss()
        notice.action?.invoke()
    }

    fun dismiss() {
        hideJob?.cancel()
        _notice.value = null
    }

    private fun post(notice: Notice, millis: Long = 2600) {
        hideJob?.cancel()
        _notice.value = notice
        hideJob = scope.launch {
            delay(millis)
            _notice.value = null
        }
    }
}

/** Mounted once at the root; floats above everything. */
@Composable
fun ToastHost(modifier: Modifier = Modifier) {
    val notice by ToastCenter.notice.collectAsState()
    // Held while it animates out, so the text does not blank before it leaves.
    var shown by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<ToastCenter.Notice?>(null) }
    if (notice != null) shown = notice
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
            visible = notice != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            val current = shown
            Surface(
                shape = CircleShape,
                color = Nocturne.surface.copy(alpha = 0.96f),
                shadowElevation = 12.dp,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
            ) {
                androidx.compose.foundation.layout.Row(
                    Modifier
                        .clip(CircleShape)
                        .background(Nocturne.bg.copy(alpha = 0.5f))
                        .padding(start = 18.dp, end = if (current?.actionLabel != null) 6.dp else 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        current?.text.orEmpty(),
                        modifier = Modifier.padding(vertical = 11.dp),
                        style = Type.body(13, FontWeight.SemiBold),
                        color = Nocturne.text,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                    )
                    val label = current?.actionLabel
                    if (current != null && label != null) {
                        androidx.compose.material3.TextButton(onClick = { ToastCenter.runAction(current) }) {
                            Text(label, style = Type.body(13, FontWeight.Bold), color = Nocturne.accent)
                        }
                    }
                }
            }
        }
    }
}
