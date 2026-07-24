package app.morrowa

import android.app.Application
import android.content.Context
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.morrowa.ui.HabitScreen
import app.morrowa.ui.ToDoScreen

class MorrowaWorkspacePageView(
    context: Context,
    page: MorrowaPage,
) : FrameLayout(context) {

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
                when (page) {
                    MorrowaPage.HABIT -> HabitScreen(viewModel = requireNotNull(habitViewModel))
                    MorrowaPage.TODO -> ToDoScreen(viewModel = requireNotNull(todoViewModel))
                    MorrowaPage.HOME,
                    MorrowaPage.WIDGET_BLANK,
                    -> Unit
                }
            }
        }
        addView(
            composeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }
}
