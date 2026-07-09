package app.lawnchair.allapps.views

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.core.view.OneShotPreDrawListener
import androidx.recyclerview.widget.DefaultItemAnimator
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
 * dragged from the App Drawer onto another app here either reorders the main list's manual order
 * (shallow overlap, Phase D2/D3) or creates a new folder from the two apps (deep overlap, R4 --
 * see docs/Morrowa_AppDrawer_編集モード_実装計画.md §10, esp. §10.10/§10.11 for why position
 * resolution happens in [acceptDrop] rather than [onDrop] and why every accepted drop must clear
 * [DropTarget.DragObject.deferDragViewCleanupPostAnimation], and §10.30/§10.32 for the folder
 * hover state machine). Registered in `Launcher#setupViews` between Workspace and the drop target
 * bar so bar buttons (Uninstall / Add to home screen) still take priority.
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

    // Morrowa §10.27 (R4) / §10.30.3 (修正2): the app currently hovered in the folder-creation
    // zone, decided during hover and read at drop time -- the drop coordinate is never
    // re-evaluated for folder creation (mirrors Workspace#mCreateUserFolderOnDrop,
    // Workspace.java:2184). Non-null == "a folder will be created on drop". Cleared only on folder
    // exit (hysteresis, §10.30.3), a genuine drag exit, or after the folder is created -- never on
    // a mere resolved-slot/zone change, which was the feedback loop that made folder creation fail
    // to fire (§10.30.1 事実2).
    private var hoverFolderTargetApp: AppInfo? = null
    // The child view currently enlarged to signal folder-creation hover, so its scale can be
    // reverted regardless of which slot we move to next.
    private var scaledFolderTargetView: View? = null

    // Morrowa §10.19.2 R2: live reorder preview throttle + last-resolved (slot+zone) tracking,
    // mirroring Workspace#mReorderAlarm/REORDER_TIMEOUT and mLastReorderX/Y
    // (Workspace.java:271,2839-2842). Used ONLY to gate reorder-alarm re-arming (§10.30.3); the
    // folder enter/exit decision is made every frame, independent of this signature.
    private val reorderAlarm = Alarm()
    private var lastSlotSignature: String? = null

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

        // Morrowa §10.30.6 (修正5): the All Apps RecyclerView runs with itemAnimator == null
        // (ActivityAllAppsContainerView.AdapterHolder#setup, ":1780-1781") so ordinary updates
        // (search keystrokes, app updates) don't flicker. That also means reorder previews would
        // teleport. Enable a short move animation for the duration of an App-Drawer drag only, and
        // restore null in onDragEnd so normal drawer updates are unaffected.
        recyclerView.itemAnimator = DefaultItemAnimator().apply {
            moveDuration = REORDER_ANIMATION_DURATION_MS
            supportsChangeAnimations = false
        }

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
        // Morrowa §10.30.6: restore the no-animation default now the drag is over.
        recyclerView?.itemAnimator = null
        draggedVisibilityRecyclerView = null
        clearFolderHover()

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
        val mainList = eligibleMainList(dragObject) ?: return false
        val movedApp = dragObject.dragInfo as AppInfo

        // Folder path (§10.27 R4): the hover already decided this (mCreateUserFolderOnDrop
        // analogue). Accept without re-evaluating the drop coordinate.
        val folderApp = hoverFolderTargetApp
        if (folderApp != null && folderApp.toComponentKey() != movedApp.toComponentKey()) {
            return true
        }

        val recyclerView = activeRecyclerView ?: return false
        val slot = resolveTargetSlot(dragObject, recyclerView, mainList) ?: return false
        // Hovering over the dragged app's own (hidden) slot is Home's "hasntMoved" case
        // (Workspace.java:2179-2182) -- nothing to do.
        if (slot.app.toComponentKey() == movedApp.toComponentKey()) return false

        // Morrowa §10.19.2 R2: only commit what the hover was already previewing. A drop landing
        // outside any icon's reorder radius is rejected rather than silently reordering somewhere
        // the user never saw previewed (mirrors Workspace#willCreateUserFolder's considerTimeout/
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
        // drop target owns DragView cleanup -- one of these outcomes must always happen
        // (§10.10/§10.12's ghost-DragView invariant): either bail out with
        // deferDragViewCleanupPostAnimation=false for an immediate removal, or animate the DragView
        // into a final position with the default defer=true (the animation removes it on end).
        val movedApp = dragObject.dragInfo as? AppInfo
        val mainList = getPersonalAppList() as? LawnchairAlphabeticalAppsList<*>
        val recyclerView = activeRecyclerView
        val dragView = dragObject.dragView

        // Folder path (§10.27 R4): create a folder from the hovered target + the dragged app.
        val folderApp = hoverFolderTargetApp
        if (folderApp != null && movedApp != null && mainList != null && recyclerView != null && dragView != null &&
            folderApp.toComponentKey() != movedApp.toComponentKey()
        ) {
            createFolderAndAnimateDrop(dragView, movedApp, folderApp, mainList, recyclerView)
            return
        }

        val insertIndex = resolvedInsertIndex
        if (movedApp == null || mainList == null || insertIndex < 0 || recyclerView == null || dragView == null) {
            dragObject.deferDragViewCleanupPostAnimation = false
            return
        }

        // Morrowa §10.30.6 (修正5, WYSIWYG): if a live preview was showing, commit exactly that
        // order rather than recomputing from the release coordinate, so the committed layout
        // matches what the user saw. Only a fast drop with no preview uses the point reorder.
        if (mainList.hasPendingReorder()) {
            mainList.commitPendingOrder()
        } else {
            mainList.reorderApp(movedApp, insertIndex)
        }

        // The DiffUtil move triggered above lays out on the next frame; wait for it so the moved
        // app's real, final position can be read.
        OneShotPreDrawListener.add(recyclerView) {
            val targetView = findChildViewForApp(recyclerView, mainList, movedApp)
            animateDragViewOnto(dragView, targetView)
        }
    }

    /**
     * Morrowa §10.27 (R4): creates a new folder from [targetApp] (dropped onto) + [movedApp]
     * (dragged), then lands [dragView] onto the target's current on-screen position for visual
     * continuity until reloadGrid regroups the two into a folder cell. Any live reorder preview is
     * discarded first (§10.30.2 修正1) -- reloadGrid rebuilds the list so no visible revert occurs.
     */
    private fun createFolderAndAnimateDrop(
        dragView: DragView<*>,
        movedApp: AppInfo,
        targetApp: AppInfo,
        mainList: LawnchairAlphabeticalAppsList<*>,
        recyclerView: AllAppsRecyclerView,
    ) {
        mainList.cancelPendingReorder()
        // Resolve the target's live view before clearing hover state (which may animate its scale).
        val targetView = findChildViewForApp(recyclerView, mainList, targetApp)
        clearFolderHover()

        val title = resources.getString(com.android.launcher3.R.string.my_folder_label)
        mainList.createFolder(title, listOf(targetApp, movedApp))

        animateDragViewOnto(dragView, targetView)
    }

    /**
     * Morrowa §10.17.1 / §10.27: animates [dragView] into [targetView]'s position, mirroring
     * DragLayer#animateViewIntoPosition(DragView, View, int, View)
     * (`src/com/android/launcher3/dragndrop/DragLayer.java:250-306`) which is CellLayoutLayoutParams-
     * bound and can't be called on a RecyclerView child. Shared by the reorder-landing and
     * folder-creation drop paths. Falls back to an immediate removal if [targetView] is null (e.g.
     * it scrolled out of the bound range), matching Folder#onDrop's `d.dragView.hasDrawn()`
     * fallback (`folder/Folder.java:1610-1622`).
     */
    private fun animateDragViewOnto(dragView: DragView<*>, targetView: BubbleTextView?) {
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
        // Mirrors Workspace#onDragEnter's mPrevTargetRank = -1 -- forces the next onDragOver to
        // treat whatever it resolves as "changed".
        lastSlotSignature = null
        clearFolderHover()
        reorderAlarm.cancelAlarm()
    }

    override fun onDragOver(dragObject: DropTarget.DragObject) {
        val mainList = eligibleMainList(dragObject) ?: run {
            (getPersonalAppList() as? LawnchairAlphabeticalAppsList<*>)?.cancelPendingReorder()
            clearFolderHover()
            lastSlotSignature = null
            return
        }
        val movedApp = dragObject.dragInfo as AppInfo
        val recyclerView = activeRecyclerView ?: return
        mainList.beginPendingReorder()

        val slot = resolveTargetSlot(dragObject, recyclerView, mainList) ?: return

        // Morrowa §10.30.3 (修正2): the folder-hold decision is evaluated every frame, outside the
        // signature gate, with hysteresis. While a folder target is held, stay in folder mode
        // (touching nothing -- no reorder alarm, no preview revert; Home's mDragMode gating,
        // Workspace.java:2840) as long as the finger stays on the same app within the (larger)
        // exit radius. Only a different app, or leaving the exit radius, drops folder mode.
        val heldApp = hoverFolderTargetApp
        if (heldApp != null) {
            val sameApp = slot.app.toComponentKey() == heldApp.toComponentKey()
            if (sameApp && isWithinFolderExitRadius(slot)) return
            // Exit folder mode and fall through to normal zone handling; reset the signature so the
            // reorder branch below re-arms on this slot rather than seeing it as unchanged.
            clearFolderHover()
            lastSlotSignature = null
        }

        val slotKey = slot.app.toComponentKey().toString()
        // Hovering the dragged app's own (hidden) slot: nothing to preview, matches Home's "still
        // over the empty cell you came from" (Workspace.java:2179-2182 hasntMoved).
        if (slotKey == movedApp.toComponentKey().toString()) {
            lastSlotSignature = "$slotKey:SELF"
            return
        }

        val zone = classifyZone(slot)
        val signature = "$slotKey:$zone"
        when (zone) {
            HoverZone.FOLDER -> {
                // §10.30.4 (修正3): guard folder ENTER only while the target is still mid-move from
                // a previous preview (Home's useTmpCoords guard applies only to willCreateUserFolder,
                // Workspace.java:2172-2176). Reorder resolution is NOT blocked by this.
                if (isSlotAnimating(slot)) return
                reorderAlarm.cancelAlarm()
                applyFolderHoverScale(slot.childView)
                hoverFolderTargetApp = slot.app
                lastSlotSignature = signature
            }
            HoverZone.REORDER -> {
                if (signature == lastSlotSignature) return
                lastSlotSignature = signature
                reorderAlarm.cancelAlarm()
                val targetIndex = mainList.getOrderedApps().indexOfFirst { it.toComponentKey().toString() == slotKey }
                if (targetIndex < 0) return
                reorderAlarm.setOnAlarmListener(OnAlarmListener {
                    mainList.previewReorder(movedApp, targetIndex)
                })
                reorderAlarm.setAlarm(REORDER_PREVIEW_DELAY_MS)
            }
            HoverZone.NONE -> {
                lastSlotSignature = signature
            }
        }
    }

    override fun onDragExit(dragObject: DropTarget.DragObject) {
        reorderAlarm.cancelAlarm()
        // dragComplete is true when this onDragExit fires as part of a drop landing on this same
        // target (DragController#drop sets it before the "about to accept/drop" onDragExit call,
        // dragndrop/DragController.java:538,553) -- in that case leave the preview and folder-hover
        // state in place for acceptDrop()/onDrop() to consume. Only a genuine exit (moving to
        // another drop target, or cancelling) reverts them.
        if (!dragObject.dragComplete) {
            (getPersonalAppList() as? LawnchairAlphabeticalAppsList<*>)?.cancelPendingReorder()
            clearFolderHover()
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

    /** Home-style zone radii for a slot: [folderEnterRadius] < [folderExitRadius] (hysteresis) and
     * the outer [reorderRadius]. See [computeZoneRadii]. */
    private class ZoneRadii(val folderEnterRadius: Float, val folderExitRadius: Float, val reorderRadius: Float)

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

    /** Finds the currently-attached [BubbleTextView] rendering [app], or null if it isn't on
     * screen. Scans live children (rather than mapping an ordered-index to an adapter position) so
     * it stays correct when folder rows offset the adapter positions. */
    private fun findChildViewForApp(
        recyclerView: AllAppsRecyclerView,
        mainList: LawnchairAlphabeticalAppsList<*>,
        app: AppInfo,
    ): BubbleTextView? {
        val key = app.toComponentKey().toString()
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i) ?: continue
            val position = recyclerView.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val a = mainList.getAppAtAdapterPosition(position) ?: continue
            if (a.toComponentKey().toString() == key) return child as? BubbleTextView
        }
        return null
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
     * DiffUtil move from the previous preview update). Morrowa §10.30.4 (修正3): scoped to the
     * per-child translation only and applied to the folder ENTER decision alone (Home's
     * useTmpCoords guard, Workspace.java:2172-2176) -- the previous whole-RecyclerView
     * `itemAnimator.isRunning` check was removed since, with the drag-time animator enabled
     * (§10.30.6), it would freeze every hover decision for the whole 150ms of any move. */
    private fun isSlotAnimating(slot: TargetSlot): Boolean {
        return slot.childView.translationX != 0f || slot.childView.translationY != 0f
    }

    /**
     * Home's three-zone radii for [slot] (CellLayout#getReorderRadius/getFolderCreationRadius,
     * `src/com/android/launcher3/CellLayout.java:950-981`). The reorder radius is measured from the
     * cell center to the nearest edge of the cell rect **expanded outward by half the border/gutter
     * spacing** (`cellBoundsWithSpacing.inset(-mBorderSpace.x/2, ...)`, `CellLayout.java:517`), so
     * it reaches partway into the gap between adjacent cells. Morrowa §10.30.5 (修正4): the
     * folder-creation radius is based on the icon glyph's own visible radius (not a midpoint of the
     * reorder radius), so folder mode is only entered when the drag's visual center is essentially
     * on top of the target's icon image -- clamped so a reorder band always remains even at large
     * icon sizes. The exit radius adds hysteresis (§10.30.3). Null if the child has no size yet.
     */
    private fun computeZoneRadii(slot: TargetSlot): ZoneRadii? {
        val cellW = slot.childView.width.toFloat()
        val cellH = slot.childView.height.toFloat()
        if (cellW <= 0f || cellH <= 0f) return null

        val allAppsProfile = mActivityContext.deviceProfile.allAppsProfile
        val borderSpace = allAppsProfile.borderSpacePx
        val iconSizePx = allAppsProfile.iconSizePx.toFloat()
        val iconVisibleRadius = IconNormalizer.ICON_VISIBLE_AREA_FACTOR * iconSizePx / 2f
        val reorderRadius = min(cellW / 2f + borderSpace.x / 2f, cellH / 2f + borderSpace.y / 2f)
        val folderEnterRadius = min(iconVisibleRadius * FOLDER_ENTER_FACTOR, reorderRadius * FOLDER_ENTER_CLAMP_FACTOR)
        val folderExitRadius = folderEnterRadius * FOLDER_EXIT_HYSTERESIS
        return ZoneRadii(folderEnterRadius, folderExitRadius, reorderRadius)
    }

    private fun classifyZone(slot: TargetSlot): HoverZone {
        val radii = computeZoneRadii(slot) ?: return HoverZone.NONE
        return when {
            slot.distance <= radii.folderEnterRadius -> HoverZone.FOLDER
            slot.distance <= radii.reorderRadius -> HoverZone.REORDER
            else -> HoverZone.NONE
        }
    }

    /** Morrowa §10.30.3 (修正2): whether [slot] is still within the folder-mode *exit* radius (the
     * enter radius grown by [FOLDER_EXIT_HYSTERESIS]) -- used to hold folder mode against finger
     * jitter near the boundary. */
    private fun isWithinFolderExitRadius(slot: TargetSlot): Boolean {
        val radii = computeZoneRadii(slot) ?: return false
        return slot.distance <= radii.folderExitRadius
    }

    /** Morrowa §10.27 (R4): enlarge [view] to signal it is the folder-creation target. */
    private fun applyFolderHoverScale(view: View) {
        if (scaledFolderTargetView === view) return
        clearFolderHoverScale()
        scaledFolderTargetView = view
        view.animate().scaleX(FOLDER_HOVER_SCALE).scaleY(FOLDER_HOVER_SCALE)
            .setDuration(FOLDER_HOVER_SCALE_DURATION_MS).start()
    }

    private fun clearFolderHoverScale() {
        val view = scaledFolderTargetView ?: return
        scaledFolderTargetView = null
        view.animate().scaleX(1f).scaleY(1f).setDuration(FOLDER_HOVER_SCALE_DURATION_MS).start()
    }

    /** Clears all folder-hover state: reverts the target's scale and forgets the held target. */
    private fun clearFolderHover() {
        clearFolderHoverScale()
        hoverFolderTargetApp = null
    }

    private companion object {
        /** Matches Workspace#REORDER_TIMEOUT (src/com/android/launcher3/Workspace.java:271) --
         * longer than Folder#REORDER_DELAY (250ms) since the drawer's continuous list has more
         * candidate targets per unit of finger movement than a folder's fixed grid. */
        const val REORDER_PREVIEW_DELAY_MS = 650L

        /** Morrowa §10.30.6: drag-time RecyclerView move-animation duration, matching Home's
         * CellLayout#REORDER_ANIMATION_DURATION (src/com/android/launcher3/CellLayout.java:206). */
        const val REORDER_ANIMATION_DURATION_MS = 150L

        // Morrowa §10.30.5 (修正4): folder-zone radius tuning. Enter radius ≈ the icon glyph's own
        // visible radius, clamped so a reorder band always survives; exit radius adds hysteresis.
        const val FOLDER_ENTER_FACTOR = 1.0f
        const val FOLDER_ENTER_CLAMP_FACTOR = 0.8f
        const val FOLDER_EXIT_HYSTERESIS = 1.3f

        /** Morrowa §10.27 (R4): folder-hover target enlargement. */
        const val FOLDER_HOVER_SCALE = 1.15f
        const val FOLDER_HOVER_SCALE_DURATION_MS = 150L
    }
}
