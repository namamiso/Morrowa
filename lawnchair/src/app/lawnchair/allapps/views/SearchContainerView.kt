package app.lawnchair.allapps.views

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.core.view.OneShotPreDrawListener
import androidx.recyclerview.widget.RecyclerView
import app.lawnchair.allapps.LawnchairAlphabeticalAppsList
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.search.LawnchairSearchUiDelegate
import com.android.launcher3.Alarm
import com.android.launcher3.BubbleTextView
import com.android.launcher3.DropTarget
import com.android.launcher3.OnAlarmListener
import com.android.launcher3.Utilities
import com.android.launcher3.allapps.ActivityAllAppsContainerView
import com.android.launcher3.allapps.AllAppsRecyclerView
import com.android.launcher3.allapps.LauncherAllAppsContainerView
import com.android.launcher3.dragndrop.DragController
import com.android.launcher3.dragndrop.DraggableView
import com.android.launcher3.dragndrop.DragLayer
import com.android.launcher3.dragndrop.DragOptions
import com.android.launcher3.dragndrop.DragView
import com.android.launcher3.icons.IconNormalizer
import com.android.launcher3.model.data.AppInfo
import kotlin.math.min

/**
 * Morrowa: this is the concrete class instantiated for `R.id.apps_view` (see
 * `res/layout/all_apps.xml`), so it implements [DropTarget] here rather than on the AOSP-derived
 * [ActivityAllAppsContainerView] / [LauncherAllAppsContainerView] base classes. Dropping an app
 * dragged from the App Drawer onto another app here reorders the main list's manual order
 * (Phase D2/D3, see docs/Morrowa_AppDrawer_編集モード_実装計画.md §10, esp. §10.10/§10.11 for why
 * position resolution happens in [acceptDrop] rather than [onDrop], and why every accepted drop
 * must clear [DropTarget.DragObject.deferDragViewCleanupPostAnimation]). Registered in
 * `Launcher#setupViews` between Workspace and the drop target bar so bar buttons (Uninstall /
 * Add to home screen) still take priority.
 */
class SearchContainerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LauncherAllAppsContainerView(context, attrs, defStyleAttr), DropTarget, DragController.DragListener {

    // Cached between acceptDrop() and the immediately-following onDrop() call -- DragController
    // invokes them back-to-back synchronously within DragController#drop (dragndrop/
    // DragController.java:526-568), the same pattern Workspace uses for mDropToLayout.
    private var resolvedInsertIndex: Int = -1
    // Morrowa §10.19.2 R4: whether the hover state was in the folder-creation zone at the moment
    // of drop (mirroring Workspace#mCreateUserFolderOnDrop, Workspace.java:2184 -- drop position
    // is never re-evaluated for folder creation, only what was already decided during hover).
    private var resolvedCreateFolder: Boolean = false

    // Morrowa §10.19.2 R2: live reorder preview throttle + last-resolved-slot tracking, mirroring
    // Workspace#mReorderAlarm/REORDER_TIMEOUT and mLastReorderX/Y (Workspace.java:271,2839-2842).
    private val reorderAlarm = Alarm()
    private var lastTargetSlotKey: String? = null

    // Morrowa §10.19.2 R1: hides the dragged app's own icon view for the duration of an
    // App-Drawer-origin drag (mirroring Workspace#startDrag's child.setVisibility(INVISIBLE),
    // Workspace.java:1933-1934). Registered/unregistered per-drag in onDragStart/onDragEnd since
    // there's no public accessor for "the personal tab's RecyclerView" independent of whichever
    // tab is currently active.
    private var draggedVisibilityListener: RecyclerView.OnChildAttachStateChangeListener? = null
    private var draggedVisibilityRecyclerView: AllAppsRecyclerView? = null

    override fun createSearchUiDelegate() = LawnchairSearchUiDelegate(this)

    override fun onDragStart(dragObject: DropTarget.DragObject, options: DragOptions) {
        if (dragObject.dragSource !is ActivityAllAppsContainerView<*>) return
        val draggedApp = dragObject.dragInfo as? AppInfo ?: return
        if (isSearching || !isPersonalTab) return
        val mainList = getPersonalAppList() as? LawnchairAlphabeticalAppsList<*> ?: return
        val recyclerView = activeRecyclerView ?: return

        mainList.draggedComponentKey = draggedApp.toComponentKey().toString()
        applyDraggedVisibility(recyclerView, mainList)

        val listener = object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                applyDraggedVisibilityToChild(view, recyclerView, mainList)
            }

            override fun onChildViewDetachedFromWindow(view: View) {}
        }
        recyclerView.addOnChildAttachStateChangeListener(listener)
        draggedVisibilityListener = listener
        draggedVisibilityRecyclerView = recyclerView
    }

    override fun onDragEnd() {
        val recyclerView = draggedVisibilityRecyclerView
        draggedVisibilityListener?.let { recyclerView?.removeOnChildAttachStateChangeListener(it) }
        draggedVisibilityListener = null
        draggedVisibilityRecyclerView = null

        val mainList = getPersonalAppList() as? LawnchairAlphabeticalAppsList<*> ?: return
        if (mainList.draggedComponentKey == null) return
        mainList.draggedComponentKey = null
        recyclerView?.let { applyDraggedVisibility(it, mainList) }
    }

    private fun applyDraggedVisibility(recyclerView: AllAppsRecyclerView, mainList: LawnchairAlphabeticalAppsList<*>) {
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i) ?: continue
            applyDraggedVisibilityToChild(child, recyclerView, mainList)
        }
    }

    private fun applyDraggedVisibilityToChild(
        child: View,
        recyclerView: AllAppsRecyclerView,
        mainList: LawnchairAlphabeticalAppsList<*>,
    ) {
        val position = recyclerView.getChildAdapterPosition(child)
        if (position == RecyclerView.NO_POSITION) return
        val app = mainList.getAppAtAdapterPosition(position)
        val isDragged = app != null && mainList.draggedComponentKey == app.toComponentKey().toString()
        child.visibility = if (isDragged) View.INVISIBLE else View.VISIBLE
    }

    override fun isDropEnabled(): Boolean = true

    override fun acceptDrop(dragObject: DropTarget.DragObject): Boolean {
        resolvedInsertIndex = -1
        resolvedCreateFolder = false
        val mainList = eligibleMainList(dragObject) ?: return false
        val movedApp = dragObject.dragInfo as AppInfo
        val recyclerView = activeRecyclerView ?: return false
        val slot = resolveTargetSlot(dragObject, recyclerView, mainList) ?: return false
        // Hovering over the dragged app's own (hidden) slot is Home's "hasntMoved" case
        // (Workspace.java:2179-2182) -- nothing to do.
        if (slot.app.toComponentKey() == movedApp.toComponentKey()) return false

        // Morrowa §10.19.2 R2: only commit what the hover was already previewing. A drop landing
        // in the folder-creation zone (R4 not yet implemented) or outside any icon's reorder
        // radius is rejected rather than silently reordering somewhere the user never saw
        // previewed (mirrors Workspace#willCreateUserFolder's considerTimeout/
        // mCreateUserFolderOnDrop guard against re-deciding at drop time, Workspace.java:2184).
        if (classifyZone(slot) != HoverZone.REORDER) return false

        val targetIndex = mainList.getOrderedApps()
            .indexOfFirst { it.toComponentKey() == slot.app.toComponentKey() }
        if (targetIndex < 0) return false

        resolvedInsertIndex = targetIndex
        return true
    }

    override fun onDrop(dragObject: DropTarget.DragObject, options: DragOptions) {
        reorderAlarm.cancelAlarm()

        // Contract (DragController#dispatchDropComplete): once acceptDrop() returns true, this
        // drop target owns DragView cleanup -- one of these two outcomes must always happen
        // (§10.10/§10.12's ghost-DragView invariant):
        //  - bail out below: clear deferDragViewCleanupPostAnimation=false for an immediate
        //    removal (matches Folder#onDrop's d.dragView.hasDrawn()==false fallback), or
        //  - fall through: leave the default deferDragViewCleanupPostAnimation=true and animate
        //    the DragView into the target's new position (Folder#animateViewIntoPosition
        //    equivalent, §10.17.1), which removes it itself when the animation ends
        //    (DragLayer#playDropAnimation -> clearAnimatedView -> DragController#onDeferredEndDrag).
        val movedApp = dragObject.dragInfo as? AppInfo
        val mainList = getPersonalAppList() as? LawnchairAlphabeticalAppsList<*>
        val insertIndex = resolvedInsertIndex
        val recyclerView = activeRecyclerView
        val dragView = dragObject.dragView
        if (movedApp == null || mainList == null || insertIndex < 0 || recyclerView == null || dragView == null) {
            dragObject.deferDragViewCleanupPostAnimation = false
            return
        }

        mainList.reorderApp(movedApp, insertIndex)

        // The DiffUtil move triggered by reorderApp() above lays out on the next frame; wait for
        // it so the target's real, final position can be read.
        OneShotPreDrawListener.add(recyclerView) {
            animateDropLanding(dragView, movedApp, mainList, recyclerView)
        }
    }

    /**
     * Morrowa §10.17.1: animates [dragView] into the moved app's landing position, mirroring
     * DragLayer#animateViewIntoPosition(DragView, View, int, View)
     * (`src/com/android/launcher3/dragndrop/DragLayer.java:250-306`) which is CellLayoutLayoutParams-
     * bound and can't be called directly on a RecyclerView child. Falls back to an immediate
     * removal if the target view can't be resolved (e.g. it scrolled out of the bound range),
     * matching Folder#onDrop's `d.dragView.hasDrawn()` fallback (`folder/Folder.java:1610-1622`).
     */
    private fun animateDropLanding(
        dragView: DragView<*>,
        movedApp: AppInfo,
        mainList: LawnchairAlphabeticalAppsList<*>,
        recyclerView: AllAppsRecyclerView,
    ) {
        val newIndex = mainList.getOrderedApps()
            .indexOfFirst { it.toComponentKey() == movedApp.toComponentKey() }
        val targetView = if (newIndex >= 0) {
            recyclerView.findViewHolderForAdapterPosition(newIndex)?.itemView as? BubbleTextView
        } else {
            null
        }
        if (targetView == null) {
            dragView.remove()
            return
        }

        val dragLayer = mActivityContext.dragLayer
        val destRect = Rect()
        targetView.getWorkspaceVisualDragBounds(destRect)

        val loc = floatArrayOf(0f, 0f)
        val scale = dragLayer.getDescendantCoordRelativeToSelf(targetView, loc)

        var toScale = scale
        toScale *= destRect.width().toFloat() / (dragView.measuredWidth - dragView.blurSizeOutline)
        val scaleShiftX = dragView.measuredWidth * (1 - toScale) / 2
        val scaleShiftY = dragView.measuredHeight * (1 - toScale) / 2
        val toX = (loc[0] + scale * destRect.left - toScale * dragView.blurSizeOutline / 2 - scaleShiftX).toInt()
        val toY = (loc[1] + scale * destRect.top - toScale * dragView.blurSizeOutline / 2 - scaleShiftY).toInt()

        targetView.visibility = View.INVISIBLE
        dragLayer.animateViewIntoPosition(
            dragView,
            toX,
            toY,
            1f,
            toScale,
            toScale,
            { targetView.visibility = View.VISIBLE },
            DragLayer.ANIMATION_END_DISAPPEAR,
            -1,
            null,
        )
    }

    override fun onDragEnter(dragObject: DropTarget.DragObject) {
        // Mirrors Workspace#onDragEnter's mPrevTargetRank = -1 (Folder.java:1154 equivalent
        // pattern) -- forces the next onDragOver to treat whatever it resolves as "changed".
        lastTargetSlotKey = null
        reorderAlarm.cancelAlarm()
    }

    override fun onDragOver(dragObject: DropTarget.DragObject) {
        val mainList = eligibleMainList(dragObject) ?: run {
            (getPersonalAppList() as? LawnchairAlphabeticalAppsList<*>)?.cancelPendingReorder()
            lastTargetSlotKey = null
            return
        }
        val movedApp = dragObject.dragInfo as AppInfo
        val recyclerView = activeRecyclerView ?: return
        mainList.beginPendingReorder()

        val slot = resolveTargetSlot(dragObject, recyclerView, mainList) ?: return
        // §10.19.2 R2 animation guard (mirrors Workspace#willCreateUserFolder's useTmpCoords
        // check, Workspace.java:2172-2176): don't resolve a new target while the slot we'd
        // resolve to is still mid-move from the previous preview update.
        if (isSlotAnimating(recyclerView, slot)) return

        val slotKey = slot.app.toComponentKey().toString()
        if (slotKey == lastTargetSlotKey) return
        lastTargetSlotKey = slotKey
        reorderAlarm.cancelAlarm()

        // Hovering the dragged app's own (hidden) slot: nothing to preview, matches Home's
        // "still over the empty cell you came from" (Workspace.java:2179-2182 hasntMoved).
        if (slotKey == movedApp.toComponentKey().toString()) return

        when (classifyZone(slot)) {
            HoverZone.FOLDER -> {
                // §10.19.2 R4 not yet implemented: dead zone placeholder so deep overlap never
                // fires a reorder preview (this was the source of symptom 3's instability).
            }
            HoverZone.REORDER -> {
                val targetIndex = mainList.getOrderedApps().indexOfFirst { it.toComponentKey().toString() == slotKey }
                if (targetIndex < 0) return
                reorderAlarm.setOnAlarmListener(OnAlarmListener {
                    mainList.previewReorder(movedApp, targetIndex)
                })
                reorderAlarm.setAlarm(REORDER_PREVIEW_DELAY_MS)
            }
            HoverZone.NONE -> Unit
        }
    }

    override fun onDragExit(dragObject: DropTarget.DragObject) {
        reorderAlarm.cancelAlarm()
        // dragComplete is true when this onDragExit fires as part of a drop landing on this same
        // target (DragController#drop sets it before the "about to accept/drop" onDragExit call,
        // dragndrop/DragController.java:538,553) -- in that case leave the preview in place for
        // onDrop() to commit. Only a genuine exit (moving to another drop target, or cancelling)
        // should revert it.
        if (!dragObject.dragComplete) {
            (getPersonalAppList() as? LawnchairAlphabeticalAppsList<*>)?.cancelPendingReorder()
        }
    }

    override fun prepareAccessibilityDrop() {}

    override fun getHitRectRelativeToDragLayer(outRect: Rect) {
        mActivityContext.dragLayer.getDescendantRectRelativeToSelf(this, outRect)
    }

    /** Returns the main app list if [dragObject] is eligible for App Drawer reordering, else
     * null: must originate from the App Drawer, carry an [AppInfo], and land on the personal
     * tab's A-Z list (not search, not Work/Private space, manual-folder mode only). */
    private fun eligibleMainList(dragObject: DropTarget.DragObject): LawnchairAlphabeticalAppsList<*>? {
        if (dragObject.dragSource !is ActivityAllAppsContainerView<*>) return null
        if (dragObject.dragInfo !is AppInfo) return null
        if (isSearching) return null
        if (!isPersonalTab) return null
        if (!PreferenceManager.getInstance(context).drawerList.get()) return null
        return getPersonalAppList() as? LawnchairAlphabeticalAppsList<*>
    }

    /** A resolved nearest-icon hover target: the app it belongs to, its current child view (for
     * size/animation-state queries), and its distance from the drag's visual center. */
    private class TargetSlot(val app: AppInfo, val childView: View, val distance: Float)

    /** Mirrors Workspace's three-zone distance model (Workspace.java:2807-2842,
     * CellLayout#getFolderCreationRadius/getReorderRadius, CellLayout.java:950-981). */
    private enum class HoverZone { FOLDER, REORDER, NONE }

    /**
     * Resolves the nearest icon slot to the drag's visual center (matching Folder#getTargetRank,
     * `src/com/android/launcher3/folder/Folder.java:1172-1176`, and Workspace#findNearestArea)
     * -- unlike the original resolveTargetApp, this INCLUDES the dragged app's own slot as a
     * valid (if inert) target, matching Home's model where the dragged item's original position
     * is simply "the empty cell", not an excluded position (§10.19.2 R2).
     */
    private fun resolveTargetSlot(
        dragObject: DropTarget.DragObject,
        recyclerView: AllAppsRecyclerView,
        mainList: LawnchairAlphabeticalAppsList<*>,
    ): TargetSlot? {
        // dragObject.x/y (and thus getVisualCenter, derived from them) are already relative to
        // this view: DragController#findDropTarget maps raw touch coordinates into getDropView()
        // (defaults to `this`) local space before invoking acceptDrop/onDrop/onDragOver.
        val coord = dragObject.getVisualCenter(FloatArray(2))
        Utilities.mapCoordInSelfToDescendant(recyclerView, this, coord)
        val cx = coord[0]
        val cy = coord[1]

        var nearest: TargetSlot? = null
        var nearestDistSq = Float.MAX_VALUE
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i) ?: continue
            val position = recyclerView.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val app = mainList.getAppAtAdapterPosition(position) ?: continue
            val (iconCx, iconCy) = iconVisualCenter(child)
            val dx = iconCx - cx
            val dy = iconCy - cy
            val distSq = dx * dx + dy * dy
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq
                nearest = TargetSlot(app, child, kotlin.math.sqrt(distSq))
            }
        }
        return nearest
    }

    /**
     * Returns [child]'s icon glyph's visual center, in the same coordinate space as
     * [resolveTargetSlot]'s `cx`/`cy` (the RecyclerView's local space). For an all-apps cell,
     * the icon glyph sits in the upper portion of the view with the label below
     * (`BubbleTextView#getIconBounds`, `src/com/android/launcher3/BubbleTextView.java:953-964`,
     * `outBounds.offset((getWidth() - iconSize) / 2, getPaddingTop())` for the vertical layout),
     * so it is NOT the same point as the raw view's geometric center. Using the raw view center
     * here made the effective reorder-trigger sensitivity feel worse than Home's, since the user
     * naturally aims at the icon glyph itself rather than the icon+label cell's midpoint.
     * Falls back to the raw view center for non-[DraggableView] children (dividers, etc).
     */
    private fun iconVisualCenter(child: View): Pair<Float, Float> {
        if (child is DraggableView) {
            val bounds = Rect()
            child.getWorkspaceVisualDragBounds(bounds)
            if (!bounds.isEmpty) {
                return (child.left + bounds.exactCenterX()) to (child.top + bounds.exactCenterY())
            }
        }
        return (child.left + child.width / 2f) to (child.top + child.height / 2f)
    }

    /** True while [slot]'s child view is still animating into its final layout position (a
     * DiffUtil move from the previous preview update), so target resolution should be skipped
     * for this frame (§10.19.2 R2, mirroring Workspace#willCreateUserFolder's useTmpCoords
     * check, Workspace.java:2172-2176). */
    private fun isSlotAnimating(recyclerView: AllAppsRecyclerView, slot: TargetSlot): Boolean {
        if (slot.childView.translationX != 0f || slot.childView.translationY != 0f) return true
        return recyclerView.itemAnimator?.isRunning == true
    }

    /**
     * Classifies [slot] into Home's three-zone distance model: within [HoverZone.FOLDER]'s
     * radius of the target's visual center, within the larger [HoverZone.REORDER] radius, or
     * [HoverZone.NONE] (too far to affect anything).
     *
     * Radii mirror CellLayout#getReorderRadius/getFolderCreationRadius
     * (`src/com/android/launcher3/CellLayout.java:950-981`) exactly, not just approximately:
     * Home's reorder radius is measured from the cell center to the nearest edge of the cell
     * rect **expanded outward by half the border/gutter spacing on each side**
     * (`cellBoundsWithSpacing.inset(-mBorderSpace.x / 2, -mBorderSpace.y / 2)`,
     * `CellLayout.java:517`) -- i.e. it reaches partway into the gap between adjacent cells, not
     * just to the icon's own edge. The original version of this method used the target child's
     * bare measured width/height with no gutter contribution, which under-shot Home's actual
     * (larger) reorder radius and made reordering require unnecessarily precise aim.
     */
    private fun classifyZone(slot: TargetSlot): HoverZone {
        val cellW = slot.childView.width.toFloat()
        val cellH = slot.childView.height.toFloat()
        if (cellW <= 0f || cellH <= 0f) return HoverZone.NONE

        val allAppsProfile = mActivityContext.deviceProfile.allAppsProfile
        val borderSpace = allAppsProfile.borderSpacePx
        val iconSizePx = allAppsProfile.iconSizePx.toFloat()
        val iconVisibleRadius = IconNormalizer.ICON_VISIBLE_AREA_FACTOR * iconSizePx / 2f
        val reorderRadius = min(cellW / 2f + borderSpace.x / 2f, cellH / 2f + borderSpace.y / 2f)
        val folderRadius = (reorderRadius + iconVisibleRadius) / 2f

        return when {
            slot.distance <= folderRadius -> HoverZone.FOLDER
            slot.distance <= reorderRadius -> HoverZone.REORDER
            else -> HoverZone.NONE
        }
    }

    private companion object {
        /** Matches Workspace#REORDER_TIMEOUT (src/com/android/launcher3/Workspace.java:271) --
         * longer than Folder#REORDER_DELAY (250ms) since the drawer's continuous list has more
         * candidate targets per unit of finger movement than a folder's fixed grid. */
        const val REORDER_PREVIEW_DELAY_MS = 650L
    }
}
