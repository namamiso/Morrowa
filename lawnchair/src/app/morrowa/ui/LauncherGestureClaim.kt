package app.morrowa.ui

import android.graphics.RectF
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import app.morrowa.LocalMorrowaScrollerRegistry

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

/**
 * Publishes this vertically scrollable list's bounds and live edge state to
 * the Morrowa page registry, so the launcher's vertical swipe controllers
 * (all-apps swipe, notification pull) can decline gestures the list still
 * wants: mid-list both directions scroll the list; at the top a downward
 * swipe falls through to the launcher, at the bottom an upward one does; a
 * list too short to scroll never blocks anything.
 */
fun Modifier.morrowaVerticalScrollRegion(
    listState: LazyListState,
    enabled: Boolean = true,
): Modifier = composed {
    val registry = LocalMorrowaScrollerRegistry.current ?: return@composed Modifier
    val regionKey = remember { Any() }
    val lastBounds = remember { arrayOf<RectF?>(null) }

    // Snapshot reads: any edge-state change recomposes this modifier and the
    // SideEffect republishes, since onGloballyPositioned alone only fires on
    // layout changes.
    val canScrollBackward = listState.canScrollBackward
    val canScrollForward = listState.canScrollForward
    SideEffect {
        if (enabled) {
            lastBounds[0]?.let {
                registry.publishVertical(regionKey, it, canScrollBackward, canScrollForward)
            }
        }
    }
    DisposableEffect(enabled) {
        if (!enabled) {
            registry.removeVertical(regionKey)
        }
        onDispose {
            registry.removeVertical(regionKey)
        }
    }

    Modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInRoot()
        lastBounds[0] = RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
        if (enabled) {
            registry.publishVertical(
                regionKey,
                lastBounds[0]!!,
                listState.canScrollBackward,
                listState.canScrollForward,
            )
        } else {
            registry.removeVertical(regionKey)
        }
    }
}
