package app.morrowa.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Claims the whole gesture for this element from the launcher's touch
 * controllers (all-apps swipe, notification pull, workspace paging) the moment
 * the pointer lands, via requestDisallowInterceptTouchEvent up to the
 * DragLayer. Launcher3's idiom for scrollable children — see
 * LauncherAppWidgetHostView. Only use on elements whose sole purpose is a
 * drag (e.g. reorder handles); everything the finger does until lift-off is
 * kept away from the launcher.
 */
fun Modifier.claimGestureFromLauncher(): Modifier = composed {
    val hostView = LocalView.current
    pointerInput(hostView) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            hostView.parent?.requestDisallowInterceptTouchEvent(true)
        }
    }
}
