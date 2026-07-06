package app.lawnchair.allapps.views

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import app.lawnchair.allapps.LawnchairAlphabeticalAppsList
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.search.LawnchairSearchUiDelegate
import com.android.launcher3.DropTarget
import com.android.launcher3.Utilities
import com.android.launcher3.allapps.ActivityAllAppsContainerView
import com.android.launcher3.allapps.AllAppsRecyclerView
import com.android.launcher3.allapps.LauncherAllAppsContainerView
import com.android.launcher3.dragndrop.DragOptions
import com.android.launcher3.model.data.AppInfo

/**
 * Morrowa: this is the concrete class instantiated for `R.id.apps_view` (see
 * `res/layout/all_apps.xml`), so it implements [DropTarget] here rather than on the AOSP-derived
 * [ActivityAllAppsContainerView] / [LauncherAllAppsContainerView] base classes. Dropping an app
 * dragged from the App Drawer onto another app here reorders the main list's manual order
 * (Phase D2, see docs/Morrowa_AppDrawer_編集モード_実装計画.md §10, esp. §10.10/§10.11 for why
 * position resolution happens in [acceptDrop] rather than [onDrop], and why every accepted drop
 * must clear [DropTarget.DragObject.deferDragViewCleanupPostAnimation]). Registered in
 * `Launcher#setupViews` between Workspace and the drop target bar so bar buttons (Uninstall /
 * Add to home screen) still take priority.
 */
class SearchContainerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LauncherAllAppsContainerView(context, attrs, defStyleAttr), DropTarget {

    // Cached between acceptDrop() and the immediately-following onDrop() call -- DragController
    // invokes them back-to-back synchronously within DragController#drop (dragndrop/
    // DragController.java:526-568), the same pattern Workspace uses for mDropToLayout.
    private var resolvedInsertIndex: Int = -1

    override fun createSearchUiDelegate() = LawnchairSearchUiDelegate(this)

    override fun isDropEnabled(): Boolean = true

    override fun acceptDrop(dragObject: DropTarget.DragObject): Boolean {
        resolvedInsertIndex = -1
        if (dragObject.dragSource !is ActivityAllAppsContainerView<*>) return false
        val movedApp = dragObject.dragInfo as? AppInfo ?: return false
        if (isSearching) return false
        if (!isPersonalTab) return false
        if (!PreferenceManager.getInstance(context).drawerList.get()) return false

        val mainList = getPersonalAppList() as? LawnchairAlphabeticalAppsList<*> ?: return false
        val recyclerView = activeRecyclerView ?: return false
        val targetApp = resolveTargetApp(dragObject, recyclerView, mainList, movedApp) ?: return false

        val targetIndex = mainList.getOrderedApps()
            .indexOfFirst { it.toComponentKey() == targetApp.toComponentKey() }
        if (targetIndex < 0) return false

        resolvedInsertIndex = targetIndex
        return true
    }

    override fun onDrop(dragObject: DropTarget.DragObject, options: DragOptions) {
        // Contract (DragController#dispatchDropComplete): once acceptDrop() returns true, this
        // drop target owns DragView cleanup. Landing animation (Folder#animateViewIntoPosition
        // equivalent) is a D3 refinement; clearing immediately is always correct and never
        // leaves a "ghost" DragView behind.
        dragObject.deferDragViewCleanupPostAnimation = false

        val movedApp = dragObject.dragInfo as? AppInfo ?: return
        val mainList = getPersonalAppList() as? LawnchairAlphabeticalAppsList<*> ?: return
        val insertIndex = resolvedInsertIndex
        if (insertIndex < 0) return
        mainList.reorderApp(movedApp, insertIndex)
    }

    override fun onDragEnter(dragObject: DropTarget.DragObject) {}

    override fun onDragOver(dragObject: DropTarget.DragObject) {}

    override fun onDragExit(dragObject: DropTarget.DragObject) {}

    override fun prepareAccessibilityDrop() {}

    override fun getHitRectRelativeToDragLayer(outRect: Rect) {
        mActivityContext.dragLayer.getDescendantRectRelativeToSelf(this, outRect)
    }

    /**
     * Resolves the app being hovered/dropped on, using the DragView's visual center (matching
     * Folder#getTargetRank, `src/com/android/launcher3/folder/Folder.java:1172-1176`) rather
     * than the raw touch point, with a nearest-icon fallback (Folder#findNearestArea
     * equivalent) for when the center falls in the spacing between icons.
     */
    private fun resolveTargetApp(
        dragObject: DropTarget.DragObject,
        recyclerView: AllAppsRecyclerView,
        mainList: LawnchairAlphabeticalAppsList<*>,
        movedApp: AppInfo,
    ): AppInfo? {
        // dragObject.x/y (and thus getVisualCenter, derived from them) are already relative to
        // this view: DragController#findDropTarget maps raw touch coordinates into getDropView()
        // (defaults to `this`) local space before invoking acceptDrop/onDrop.
        val coord = dragObject.getVisualCenter(FloatArray(2))
        Utilities.mapCoordInSelfToDescendant(recyclerView, this, coord)
        val cx = coord[0]
        val cy = coord[1]

        fun appAt(child: View): AppInfo? {
            val position = recyclerView.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) return null
            val app = mainList.getAppAtAdapterPosition(position) ?: return null
            return app.takeIf { it.toComponentKey() != movedApp.toComponentKey() }
        }

        recyclerView.findChildViewUnder(cx, cy)?.let { child -> appAt(child)?.let { return it } }

        var nearest: AppInfo? = null
        var nearestDistSq = Float.MAX_VALUE
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i) ?: continue
            val app = appAt(child) ?: continue
            val dx = (child.left + child.width / 2f) - cx
            val dy = (child.top + child.height / 2f) - cy
            val distSq = dx * dx + dy * dy
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq
                nearest = app
            }
        }
        return nearest
    }
}
