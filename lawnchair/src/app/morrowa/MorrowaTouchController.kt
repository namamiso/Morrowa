package app.morrowa

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import com.android.launcher3.util.TouchController
import kotlin.math.abs

class MorrowaTouchController(
    context: Context,
    private val pageController: MorrowaPageController,
) : TouchController {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minimumFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity

    private var velocityTracker: VelocityTracker? = null
    private var startX = 0f
    private var startY = 0f
    private var intercepting = false

    override fun onControllerInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (pageController.currentPage.value.isOverlayPage) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                intercepting = false
                startX = ev.x
                startY = ev.y
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                if (intercepting) return true
                val dx = abs(ev.x - startX)
                val dy = abs(ev.y - startY)
                if (dx >= touchSlop) {
                    if (dx > dy) {
                        intercepting = true
                    } else {
                        // 縦スワイプ確定 — このジェスチャーは介入しない
                        velocityTracker?.recycle()
                        velocityTracker = null
                    }
                }
                return intercepting
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                intercepting = false
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return false
    }

    override fun onControllerTouchEvent(ev: MotionEvent): Boolean {
        velocityTracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> {
                velocityTracker?.computeCurrentVelocity(1000)
                val vx = velocityTracker?.xVelocity ?: 0f
                val dx = ev.x - startX
                if (abs(dx) > touchSlop && abs(vx) > minimumFlingVelocity) {
                    if (vx > 0) pageController.movePrevious() else pageController.moveNext()
                }
                velocityTracker?.recycle()
                velocityTracker = null
                intercepting = false
            }
            MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
                intercepting = false
            }
        }
        return true
    }
}
