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

interface HorizontalScrollerRegistry {
    fun publish(key: Any, bounds: RectF)
    fun remove(key: Any)
}

val LocalMorrowaScrollerRegistry =
    staticCompositionLocalOf<HorizontalScrollerRegistry?> { null }

class MorrowaWorkspacePageView(
    context: Context,
    page: MorrowaPage,
) : FrameLayout(context) {

    private val scrollerBounds = mutableMapOf<Any, RectF>()
    private val scrollerRegistry = object : HorizontalScrollerRegistry {
        override fun publish(key: Any, bounds: RectF) {
            scrollerBounds[key] = RectF(bounds)
        }

        override fun remove(key: Any) {
            scrollerBounds.remove(key)
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
}
