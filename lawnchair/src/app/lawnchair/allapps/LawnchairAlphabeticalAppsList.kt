package app.lawnchair.allapps

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.lawnchair.data.AppDatabase
import app.lawnchair.data.appdrawer.DrawerAppOrderEntity
import app.lawnchair.data.folder.model.FolderOrderUtils
import app.lawnchair.data.folder.model.FolderViewModel
import app.lawnchair.launcher
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.util.categorizeAppsWithSystemAndGoogle
import com.android.launcher3.InvariantDeviceProfile.OnIDPChangeListener
import com.android.launcher3.allapps.AllAppsStore
import com.android.launcher3.allapps.AlphabeticalAppsList
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem
import com.android.launcher3.allapps.BaseAllAppsAdapter.VIEW_TYPE_ICON
import com.android.launcher3.allapps.PrivateProfileManager
import com.android.launcher3.allapps.WorkProfileManager
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.views.ActivityContext
import com.patrykmichalik.opto.core.onEach
import java.util.function.Predicate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach as onEachFlow
import kotlinx.coroutines.launch

@Suppress("SYNTHETIC_PROPERTY_WITHOUT_JAVA_ORIGIN")
class LawnchairAlphabeticalAppsList<T>(
    private val context: T,
    private val appsStore: AllAppsStore<T>,
    workProfileManager: WorkProfileManager?,
    privateProfileManager: PrivateProfileManager?,
    private val isMainList: Boolean,
) : AlphabeticalAppsList<T>(context, appsStore, workProfileManager, privateProfileManager),
    OnIDPChangeListener,
    DefaultLifecycleObserver
    where T : Context, T : ActivityContext {

    private var hiddenApps: Set<String> = setOf()
    private val prefs2 = PreferenceManager2.getInstance(context)
    private val prefs = PreferenceManager.getInstance(context)

    private val viewModel = FolderViewModel(
        (context as? ComponentActivity)?.application ?: context.launcher.application,
    )
    private var folderList = mutableListOf<FolderInfo>()
    private val filteredList = mutableListOf<AppInfo>()

    // Morrowa: persisted manual order for the main app list only (see §10.3 of
    // docs/Morrowa_AppDrawer_編集モード_実装計画.md). Empty until the user first reorders.
    private var drawerAppOrder: Map<String, Int> = emptyMap()

    // Morrowa D3(i): in-memory-only order shown live while a reorder drag is in progress (see
    // SearchContainerView#onDragOver). Not persisted until commitPendingReorder(); reverted by
    // cancelPendingReorder() if the drag ends without a drop here. Null when no drag is active.
    private var pendingOrder: MutableList<AppInfo>? = null

    // Morrowa §10.19.2 R1: component key of the app currently being dragged out of this list, if
    // any. Read by SearchContainerView's OnChildAttachStateChangeListener to hide that one icon
    // view for the duration of the drag (mirroring Workspace#startDrag's
    // child.setVisibility(INVISIBLE), src/com/android/launcher3/Workspace.java:1933-1934) so
    // only the DragView is visible, and the reorder preview reads as "a gap moves, neighbors
    // shift" instead of the real icon also sliding around underneath the DragView. This is a
    // plain visibility toggle applied directly to attached views -- it deliberately does NOT go
    // through onAppsUpdated()/DiffUtil, since the adapter's item order/identity hasn't changed.
    // Set/cleared by SearchContainerView's DragListener callbacks.
    var draggedComponentKey: String? = null

    init {
        context.launcher.deviceProfile.inv.addOnChangeListener(this)
        (context as? LifecycleOwner)?.lifecycle?.addObserver(this)
        try {
            prefs2.hiddenApps.onEach(launchIn = context.launcher.lifecycleScope) {
                hiddenApps = it
                onAppsUpdated()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to initialize hidden apps", t)
        }
        observeFolders()
        if (isMainList) {
            observeDrawerAppOrder()
        }
    }

    private fun observeDrawerAppOrder() {
        AppDatabase.INSTANCE.get(context).drawerAppOrderDao().getAll()
            .onEachFlow { entities ->
                drawerAppOrder = entities.associate { it.componentKey to it.rank }
                onAppsUpdated()
            }
            .launchIn(context.launcher.lifecycleScope)
    }

    override fun getAppSortComparator(): Comparator<AppInfo> {
        val alphabetical = super.getAppSortComparator()
        if (!isMainList) return alphabetical

        pendingOrder?.let { pending ->
            val rank = pending.withIndex().associate { (index, app) ->
                app.toComponentKey().toString() to index
            }
            return Comparator { a, b ->
                val rankA = rank[a.toComponentKey().toString()]
                val rankB = rank[b.toComponentKey().toString()]
                when {
                    rankA != null && rankB != null -> rankA.compareTo(rankB)
                    rankA != null -> -1
                    rankB != null -> 1
                    else -> alphabetical.compare(a, b)
                }
            }
        }

        // Manual order only applies in manual drawer-folder mode, once the user has reordered
        // at least once (§10.3 decisions 3-4).
        if (drawerAppOrder.isEmpty() || !prefs.drawerList.get()) {
            return alphabetical
        }
        return Comparator { a, b ->
            val rankA = drawerAppOrder[a.toComponentKey().toString()]
            val rankB = drawerAppOrder[b.toComponentKey().toString()]
            when {
                rankA != null && rankB != null -> rankA.compareTo(rankB)
                rankA != null -> -1
                rankB != null -> 1
                else -> alphabetical.compare(a, b)
            }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        context.launcher.deviceProfile.inv.removeOnChangeListener(this)
    }

    /** Returns the app icon rendered at [position] in this list's current adapter items, or null
     * if that position isn't an app icon (e.g. a folder or divider). Used by
     * [app.lawnchair.allapps.views.SearchContainerView]'s drag-to-reorder drop handling. */
    fun getAppAtAdapterPosition(position: Int): AppInfo? {
        val item = mAdapterItems.getOrNull(position) ?: return null
        return if (item.viewType == VIEW_TYPE_ICON) item.itemInfo else null
    }

    /**
     * Returns the current apps-only order (App Drawer folders excluded), i.e. what
     * [app.lawnchair.allapps.views.SearchContainerView] resolves drop-target indices against.
     */
    fun getOrderedApps(): List<AppInfo> = mAdapterItems
        .asSequence()
        .filter { it.viewType == VIEW_TYPE_ICON && it.itemInfo != null }
        .map { it.itemInfo }
        .distinctBy { it.toComponentKey().toString() }
        .toList()

    /**
     * Moves [moved] to [insertIndex] (clamped to the valid range) within the main list's manual
     * order, seeding the order from the current display order (or the live preview order, if a
     * reorder drag is in progress) first if this is the first manual reorder. Persists to
     * [app.lawnchair.data.appdrawer.service.DrawerAppOrderDao] and refreshes the list. No-op if
     * this isn't the main list.
     */
    fun reorderApp(moved: AppInfo, insertIndex: Int) {
        if (!isMainList) return
        val reordered = movedTo(pendingOrder ?: getOrderedApps(), moved, insertIndex)
        pendingOrder = null

        val entities = reordered.mapIndexed { index, app ->
            DrawerAppOrderEntity(componentKey = app.toComponentKey().toString(), rank = index)
        }
        drawerAppOrder = entities.associate { it.componentKey to it.rank }
        context.launcher.lifecycleScope.launch {
            AppDatabase.INSTANCE.get(context).drawerAppOrderDao().replaceAll(entities)
        }
        onAppsUpdated()
    }

    /**
     * Morrowa D3(i): starts a live (in-memory only, not persisted) reorder preview seeded from
     * the current display order, if one isn't already in progress. Call [previewReorder] as the
     * drag moves and either [reorderApp] (to commit) or [cancelPendingReorder] (to revert) when
     * the drag ends.
     */
    fun beginPendingReorder() {
        if (!isMainList || pendingOrder != null) return
        pendingOrder = getOrderedApps().toMutableList()
    }

    /**
     * Morrowa D3(i): live-updates the in-memory preview order (no DB write) and refreshes the
     * list so icons visibly shift out of the way, matching Folder#realTimeReorder's real-time
     * feedback. No-op if [beginPendingReorder] hasn't been called.
     */
    fun previewReorder(moved: AppInfo, insertIndex: Int) {
        val current = pendingOrder ?: return
        pendingOrder = movedTo(current, moved, insertIndex).toMutableList()
        onAppsUpdated()
    }

    /**
     * Morrowa D3(i): discards the live preview (e.g. the drag left the drawer, or ended on a
     * different drop target such as the Uninstall/Add-to-home-screen bar) and reverts to the
     * persisted order.
     */
    fun cancelPendingReorder() {
        if (pendingOrder == null) return
        pendingOrder = null
        onAppsUpdated()
    }

    private fun movedTo(order: List<AppInfo>, moved: AppInfo, insertIndex: Int): List<AppInfo> {
        val movedKey = moved.toComponentKey().toString()
        val result = order.filterNot { it.toComponentKey().toString() == movedKey }.toMutableList()
        result.add(insertIndex.coerceIn(0, result.size), moved)
        return result
    }

    private fun observeFolders() {
        viewModel.foldersLiveData.observe(context as LifecycleOwner) { folders ->
            folderList = folders.toMutableList()
            updateAdapterItems()
        }
    }

    private fun getSortedFolders(): List<FolderInfo> {
        val folderOrder = FolderOrderUtils.stringToIntList(prefs.drawerListOrder.get())
        return folderList.sortedWith(
            compareBy { folder ->
                folderOrder.indexOf(folder.id).takeIf { it != -1 } ?: Int.MAX_VALUE
            },
        )
    }

    override fun updateItemFilter(itemFilter: Predicate<ItemInfo>?) {
        mItemFilter = Predicate { info ->
            require(info is AppInfo) { "`info` must be an instance of `AppInfo`." }
            val componentKey = info.toComponentKey().toString()
            (itemFilter?.test(info) != false) && !hiddenApps.contains(componentKey)
        }
        onAppsUpdated()
    }

    override fun addAppsWithSections(appList: List<AppInfo?>?, startPosition: Int): Int {
        if (appList.isNullOrEmpty()) return startPosition
        val drawerListDefault = prefs.drawerList.get()
        filteredList.clear()
        var position = startPosition

        // Show app drawer folders only on main profile, to prevent state complexity
        if (isWorkOrPrivateSpace(appList)) return super.addAppsWithSections(appList, position)

        if (!drawerListDefault) {
            val validApps = appList.mapNotNull { it }
            val finalCategorizedApps = categorizeAppsWithSystemAndGoogle(validApps, context)

            finalCategorizedApps.forEach { (category, apps) ->
                if (apps.size == 1) {
                    mAdapterItems.add(AdapterItem.asApp(apps.first()))
                } else {
                    val folderInfo = FolderInfo().apply {
                        title = category
                        apps.forEach { add(it) }
                    }
                    mAdapterItems.add(AdapterItem.asFolder(folderInfo))
                }
                position++
            }
        } else {
            getSortedFolders().forEach { folder ->
                val folderApps = folder.getContents().mapNotNull { app ->
                    appsStore.getApp(app.componentKey)
                }
                if (folderApps.size > 1) {
                    val folderInfo = FolderInfo()
                    folderInfo.title = folder.title
                    mAdapterItems.add(AdapterItem.asFolder(folderInfo))
                    folderApps.forEach { app ->
                        folderInfo.add(app)
                        if (prefs.folderApps.get()) filteredList.add(app)
                    }
                    position++
                }
            }
            val remainingApps = appList.filterNot { app -> filteredList.contains(app) && prefs.folderApps.get() }
            position = super.addAppsWithSections(remainingApps, position)
        }

        return position
    }

    override fun onIdpChanged(modelPropertiesChanged: Boolean) {
        onAppsUpdated()
    }
}
