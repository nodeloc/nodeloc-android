package com.nodeloc.app.core.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Drag the list downwards and the keyboard goes with it — and only that.
 *
 * The platform's own `imeNestedScroll` is bidirectional by design: the gesture
 * that lowers the keyboard also raises it, so a drag *up* summons one nobody
 * asked for, dragged into view a notch at a time and following the finger
 * rather than appearing. Nothing else in the app opens a keyboard by being
 * scrolled at, and a list is not a handle for one.
 *
 * There is no one-way variant to ask for, so this is the dismissing half
 * written out. It consumes nothing — the scroll it listens to still scrolls.
 *
 * Apply with `Modifier.nestedScroll(rememberDismissKeyboardOnPullDown())`.
 */
@Composable
fun rememberDismissKeyboardOnPullDown(): NestedScrollConnection {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    return remember(keyboard, focusManager) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // UserInput only: a fling settling, or a programmatic scroll to
                // a just-posted reply, is not someone asking for the keyboard
                // to go away.
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    keyboard?.hide()
                    focusManager.clearFocus()
                }
                return Offset.Zero
            }
        }
    }
}
