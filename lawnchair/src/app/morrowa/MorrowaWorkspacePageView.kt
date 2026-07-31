package app.morrowa

import android.app.Application
import android.content.Context
import android.graphics.RectF
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.morrowa.ui.HabitScreen
import app.morrowa.ui.ToDoScreen
import com.android.launcher3.CellLayout
import com.android.launcher3.Launcher
import com.android.launcher3.Utilities

interface HorizontalScrollerRegistry {
    fun publish(key: Any, bounds: RectF)
    fun remove(key: Any)

    /**
     * Registers a vertically scrollable region with its live edge state.
     * [canScrollBackward]/[canScrollForward] mirror LazyListState: backward =
     * content can still scroll toward the top, forward = toward the bottom.
     */
    fun publishVertical(key: Any, bounds: RectF, canScrollBackward: Boolean, canScrollForward: Boolean)
    fun removeVertical(key: Any)
}

val LocalMorrowaScrollerRegistry =
    staticCompositionLocalOf<HorizontalScrollerRegistry?> { null }

class MorrowaWorkspacePageView(
    context: Context,
    page: MorrowaPage,
) : FrameLayout(context) {

    private class VerticalRegion(
        val bounds: RectF,
        val canScrollBackward: Boolean,
        val canScrollForward: Boolean,
    )

    private val scrollerBounds = mutableMapOf<Any, RectF>()
    private val verticalRegions = mutableMapOf<Any, VerticalRegion>()
    private val scrollerRegistry = object : HorizontalScrollerRegistry {
        override fun publish(key: Any, bounds: RectF) {
            scrollerBounds[key] = RectF(bounds)
        }

        override fun remove(key: Any) {
            scrollerBounds.remove(key)
        }

        override fun publishVertical(
            key: Any,
            bounds: RectF,
            canScrollBackward: Boolean,
            canScrollForward: Boolean,
        ) {
            verticalRegions[key] =
                VerticalRegion(RectF(bounds), canScrollBackward, canScrollForward)
        }

        override fun removeVertical(key: Any) {
            verticalRegions.remove(key)
        }
    }

    private val app = context.applicationContext as Application
    private val habitViewModel =
        if (page == MorrowaPage.HABIT) HabitViewModel(app) else null
    private val todoViewModel =
        if (page == MorrowaPage.TODO) ToDoViewModel(app) else null

    init {
        isClickable = true
        isLongClickable = false

        val composeView = ComposeView(context).also { view ->
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            view.setContent {
                CompositionLocalProvider(LocalMorrowaScrollerRegistry provides scrollerRegistry) {
                    when (page) {
                        MorrowaPage.HABIT -> HabitScreen(viewModel = requireNotNull(habitViewModel))
                        MorrowaPage.TODO -> ToDoScreen(viewModel = requireNotNull(todoViewModel))
                        MorrowaPage.HOME,
                        MorrowaPage.WIDGET_BLANK,
                        -> Unit
                    }
                }
            }
        }
        addView(
            composeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    fun isPointOverHorizontalScroller(x: Float, y: Float): Boolean =
        scrollerBounds.values.any { bounds -> bounds.contains(x, y) }

    /**
     * True when a gesture starting at (x, y) — local coordinates — should be
     * left to the list under the finger: the point is over a registered
     * vertical region that still has scroll room in the swipe's direction.
     * swipeUp means the finger moves up, i.e. the list scrolls forward.
     */
    fun blocksVerticalSwipe(x: Float, y: Float, swipeUp: Boolean): Boolean =
        verticalRegions.values.any { region ->
            region.bounds.contains(x, y) &&
                if (swipeUp) region.canScrollForward else region.canScrollBackward
        }

    companion object {
        /**
         * DragLayer-coordinate entry point for the launcher's vertical touch
         * controllers (all-apps swipe, notification pull). Returns true when
         * the controller should decline the gesture because a Morrowa list
         * under the finger can still scroll in that direction.
         */
        @JvmStatic
        fun blocksVerticalSwipe(
            launcher: Launcher,
            dragLayerX: Float,
            dragLayerY: Float,
            swipeUp: Boolean,
        ): Boolean {
            val workspace = launcher.workspace ?: return false
            val page = workspace.getChildAt(workspace.nextPage) as? CellLayout ?: return false
            val container = page.shortcutsAndWidgets ?: return false
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i) as? MorrowaWorkspacePageView ?: continue
                val coord = floatArrayOf(dragLayerX, dragLayerY)
                Utilities.mapCoordInSelfToDescendant(child, launcher.dragLayer, coord)
                if (coord[0] < 0f || coord[1] < 0f ||
                    coord[0] > child.width || coord[1] > child.height
                ) {
                    return false
                }
                return child.blocksVerticalSwipe(coord[0], coord[1], swipeUp)
            }
            return false
        }
    }
}
