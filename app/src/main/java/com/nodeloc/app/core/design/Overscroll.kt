package com.nodeloc.app.core.design

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.withoutEventHandling
import androidx.compose.runtime.Composable

/**
 * The stretch keeps its look and gives up its taps.
 *
 * The platform effect is two halves. The visual one draws the stretch. The
 * event one claims the pointer down while an edge is charged, so the reader
 * can "catch" the bounce mid-flight — and that is the half that breaks a list.
 * At the end of a long one the charge does not reliably settle back to zero:
 * the list goes idle, nothing invalidates, and the edge stays armed. From then
 * on every tap is read as the start of a scroll — the row takes the press,
 * `clickable` sees the scroll take over and cancels — so nothing in the list
 * answers until it is scrolled away from the edge. Dragging there drags
 * against the same charge, which is what makes it feel stuck.
 *
 * Belongs on any list whose rows can be tapped. Catching the bounce is not an
 * interaction anyone reaches for; a list that ignores every tap is one they
 * notice on the first try.
 */
@Composable
fun rememberTapSafeOverscroll(): OverscrollEffect? =
    rememberOverscrollEffect()?.withoutEventHandling()
