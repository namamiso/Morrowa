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

    init {
        isClickable = true
        isLongClickable = false

        val app = context.applicationContext as Application
        val composeView = ComposeView(context).also { view ->
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            view.setContent {
                when (page) {
                    MorrowaPage.HABIT -> HabitScreen(viewModel = HabitViewModel(app))
                    MorrowaPage.TODO -> ToDoScreen(viewModel = ToDoViewModel(app))
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
