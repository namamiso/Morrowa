package app.morrowa

import android.app.Application
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.morrowa.ui.HabitScreen
import app.morrowa.ui.ToDoScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.abs

class MorrowaOverlayView(
    context: Context,
    private val controller: MorrowaPageController,
) : FrameLayout(context) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minimumFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val habitViewModel = HabitViewModel(context.applicationContext as Application)
    private val todoViewModel = ToDoViewModel(context.applicationContext as Application)
    private val habitView: ComposeView = ComposeView(context).also { composeView ->
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        composeView.setContent {
            HabitScreen(viewModel = habitViewModel)
        }
    }
    private val todoView: ComposeView = ComposeView(context).also { composeView ->
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        composeView.setContent {
            ToDoScreen(viewModel = todoViewModel)
        }
    }
    private var pageCollectionJob: Job? = null

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = isOverlayPage()

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (!isOverlayPage() || e1 == null) {
                    return false
                }

                val deltaX = e2.x - e1.x
                val deltaY = e2.y - e1.y
                if (
                    abs(deltaX) <= abs(deltaY) ||
                    abs(deltaX) <= touchSlop ||
                    abs(velocityX) <= minimumFlingVelocity
                ) {
                    return false
                }

                if (velocityX > 0) {
                    controller.movePrevious()
                } else {
                    controller.moveNext()
                }
                return true
            }
        },
    )

    init {
        visibility = GONE
        addView(habitView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(todoView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        render(controller.currentPage.value)
    }

    fun bind(scope: CoroutineScope) {
        if (pageCollectionJob?.isActive == true) {
            return
        }
        pageCollectionJob = controller.currentPage
            .onEach(::render)
            .launchIn(scope)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isOverlayPage()) {
            return false
        }
        return gestureDetector.onTouchEvent(event)
    }

    private fun render(page: MorrowaPage) {
        visibility = when (page) {
            MorrowaPage.HABIT,
            MorrowaPage.TODO,
            -> VISIBLE
            MorrowaPage.HOME,
            MorrowaPage.WIDGET_BLANK,
            -> GONE
        }

        habitView.visibility = if (page == MorrowaPage.HABIT) VISIBLE else GONE
        todoView.visibility = if (page == MorrowaPage.TODO) VISIBLE else GONE
    }

    private fun isOverlayPage(): Boolean = controller.currentPage.value.isOverlayPage
}
