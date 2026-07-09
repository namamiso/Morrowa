package app.lawnchair.allapps

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.lawnchair.data.AppDatabase
import app.lawnchair.data.appdrawer.DrawerOrderEntity
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
import com.android.launcher3.allapps.BaseAllAppsAdapter.VIEW_TYPE_FOLDER
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

    // Morrowa: legacy app-only manual order (table `DrawerAppOrder`). Kept observed in G1 solely as
    // a comparator fallback so a user who reordered pre-upgrade keeps their app order on the very
    // first render, before the runtime seed (§10.38.1 G-1) populates the unified `DrawerOrder`. No
    // longer written (§10.38.5 risk 1).
    private var drawerAppOrder: Map<String, Int> = emptyMap()

    // Morrowa §10.38.1 G-1: the unified App Drawer order (table `DrawerOrder`), keyed by namespaced
    // key ("app:<componentKey>" / "folder:<id>") -> rank. Single source of truth for both folder
    // and app placement in the main list. Empty until seeded/first user action.
    private var drawerOrder: Map<String, Int> = emptyMap()

    // Morrowa §10.38.1 G-1: guards the one-shot runtime seed so it runs at most once per session
    // (and never once DrawerOrder already holds rows).
    private var drawerOrderSeeded: Boolean = false

    // Morrowa D3(i): in-memory-only order shown live while a reorder drag is in progress (see
    // SearchContainerView#onDragOver). Not persisted until commitPendingReorder(); reverted by
    // cancelPendingReorder() if the drag ends without a drop here. Null when no drag is active.
    // §10.38.1 G-2: the full unified entry sequence (folders + apps). Only App entries move in task
    // G, but the preview holds the whole list so folder drag (task H) is a pure extension.
    private var pendingOrder: MutableList<DrawerEntry>? = null

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
            observeDrawerOrder()
            observeDrawerAppOrder()
        }
    }

    // Morrowa §10.38.1 G-1: observe the unified DrawerOrder (folders + apps). Replaces the app-only
    // observeDrawerAppOrder as the display driver; observeDrawerAppOrder is kept only as a
    // first-render comparator fallback (see [drawerAppOrder]).
    private fun observeDrawerOrder() {
        AppDatabase.INSTANCE.get(context).drawerOrderDao().getAll()
            .onEachFlow { entities ->
                drawerOrder = entities.associate { it.key to it.rank }
                if (drawerOrder.isNotEmpty()) drawerOrderSeeded = true
                onAppsUpdated()
            }
            .launchIn(context.launcher.lifecycleScope)
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

        // §10.38.1 G-2: keep mApps' app-to-app order equal to the unified list's app-to-app order so
        // DiffUtil and fast scroll stay consistent. Live preview wins; then the persisted app ranks
        // (from DrawerOrder "app:" keys, with the legacy DrawerAppOrder as a pre-seed fallback).
        pendingOrder?.let { pending ->
            // Rank apps by their order among the App entries in the live preview (folders are
            // ignored here -- they only create gaps, and this comparator only orders apps vs apps).
            val rank = pending.filterIsInstance<DrawerEntry.App>().withIndex().associate { (index, entry) ->
                entry.info.toComponentKey().toString() to index
            }
            return rankComparator(rank, alphabetical)
        }

        // Manual order only applies in manual drawer-folder mode (§10.3 decisions 3-4).
        if (!prefs.drawerList.get()) return alphabetical
        val appRanks = currentAppRanks()
        if (appRanks.isEmpty()) return alphabetical
        return rankComparator(appRanks, alphabetical)
    }

    /**
     * Morrowa §10.38.1 G-2: the persisted app ordering, componentKey -> rank. Prefers the unified
     * DrawerOrder ("app:" entries); falls back to the legacy DrawerAppOrder only until the runtime
     * seed populates DrawerOrder, so a pre-upgrade manual order survives the very first render.
     */
    private fun currentAppRanks(): Map<String, Int> {
        val fromUnified = drawerOrder.asSequence()
            .filter { it.key.startsWith(APP_PREFIX) }
            .associate { it.key.removePrefix(APP_PREFIX) to it.value }
        return if (fromUnified.isNotEmpty()) fromUnified else drawerAppOrder
    }

    /** Ranked-first, then [fallback] (alphabetical), keyed by componentKey. */
    private fun rankComparator(rank: Map<String, Int>, fallback: Comparator<AppInfo>): Comparator<AppInfo> =
        Comparator { a, b ->
            val rankA = rank[a.toComponentKey().toString()]
            val rankB = rank[b.toComponentKey().toString()]
            when {
                rankA != null && rankB != null -> rankA.compareTo(rankB)
                rankA != null -> -1
                rankB != null -> 1
                else -> fallback.compare(a, b)
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
     * Morrowa §10.27 (R4): creates a new App Drawer folder titled [title] containing [apps]
     * (typically the dragged app + the app it was dropped onto). Thin delegate to
     * [FolderViewModel.createFolderWithApps], which writes folder + members atomically and calls
     * reloadGrid so the folder appears in place (§10.32.3). The member apps are hidden from the
     * main list automatically on reload when `pref_hideFolderApps` is set (see
     * [addAppsWithSections]), so no manual-order bookkeeping is needed here.
     */
    fun createFolder(title: String, apps: List<AppInfo>) {
        if (!isMainList) return
        viewModel.createFolderWithApps(title, apps)
        // Morrowa §10.35 F-A: optimistic local reflection -- show the folder the instant the drop
        // lands, without waiting on the folders flow emission / toItemInfo resolution / reloadGrid
        // (Home's createUserFolderIfNecessary shows the FolderIcon immediately and persists in the
        // background). The next canonical observeFolders emission replaces folderList wholesale, so
        // this optimistic entry (id left 0 -- getSortedFolders sorts unknown ids last) is naturally
        // superseded once the DB round-trips.
        val optimistic = FolderInfo().apply {
            this.title = title
            apps.forEach { add(it) }
        }
        folderList.add(optimistic)
        updateAdapterItems()
    }

    /**
     * Morrowa §10.30.6 (修正5, WYSIWYG commit): persists the current live preview order exactly as
     * shown (no drop-position recomputation), the drawer analogue of Home committing "the layout
     * the user already sees" on drop rather than re-deriving it from the release coordinate. Used
     * by [app.lawnchair.allapps.views.SearchContainerView.onDrop] when a reorder preview was
     * active. No-op if this isn't the main list or no preview is in progress.
     */
    fun commitPendingOrder() {
        if (!isMainList) return
        val pending = pendingOrder ?: return
        pendingOrder = null
        persistEntries(pending)
        onAppsUpdated()
    }

    /**
     * Moves [moved] to app-space [insertIndex] (clamped to the valid range) within the main list's
     * manual order, seeding from the current display order (or the live preview order, if a reorder
     * drag is in progress) first. Persists the whole unified sequence to
     * [app.lawnchair.data.appdrawer.service.DrawerOrderDao] and refreshes the list. No-op if this
     * isn't the main list.
     */
    fun reorderApp(moved: AppInfo, insertIndex: Int) {
        if (!isMainList) return
        val reordered = movedAppTo(pendingOrder ?: currentDisplayEntries(), moved, insertIndex)
        pendingOrder = null
        persistEntries(reordered)
        onAppsUpdated()
    }

    /**
     * Morrowa §10.38.1 G-2: persists [entries] (the whole displayed folder+app sequence) into the
     * unified DrawerOrder at rank 0..N, so every reorder renumbers the entire list and folder rank
     * never needs separate bookkeeping. Reflected optimistically in [drawerOrder] so the
     * immediately-following onAppsUpdated() already sees the new ranks.
     */
    private fun persistEntries(entries: List<DrawerEntry>) {
        val entities = entries.mapIndexed { index, entry ->
            DrawerOrderEntity(key = entry.key(), rank = index)
        }
        drawerOrder = entities.associate { it.key to it.rank }
        drawerOrderSeeded = true
        context.launcher.lifecycleScope.launch {
            AppDatabase.INSTANCE.get(context).drawerOrderDao().replaceAll(entities)
        }
    }

    /**
     * Morrowa §10.38.1 G-2: the current unified display sequence, read straight off the adapter
     * items (folders carry their canonical [FolderInfo], apps their [AppInfo]). Seed for the live
     * preview and the point-reorder commit.
     */
    private fun currentDisplayEntries(): List<DrawerEntry> = mAdapterItems.mapNotNull { item ->
        when (item.viewType) {
            VIEW_TYPE_FOLDER -> DrawerEntry.Folder(item.folderInfo)
            VIEW_TYPE_ICON -> item.itemInfo?.let { DrawerEntry.App(it) }
            else -> null
        }
    }

    /**
     * Morrowa D3(i): starts a live (in-memory only, not persisted) reorder preview seeded from
     * the current display order, if one isn't already in progress. Call [previewReorder] as the
     * drag moves and either [reorderApp] (to commit) or [cancelPendingReorder] (to revert) when
     * the drag ends.
     */
    fun beginPendingReorder() {
        if (!isMainList || pendingOrder != null) return
        pendingOrder = currentDisplayEntries().toMutableList()
    }

    /** Morrowa §10.30.6: true while a live reorder preview is in progress (a moved app is being
     * shown at a not-yet-persisted position). Lets [app.lawnchair.allapps.views.SearchContainerView]
     * choose between committing the preview as-is ([commitPendingOrder]) and a fresh point reorder. */
    fun hasPendingReorder(): Boolean = pendingOrder != null

    /**
     * Morrowa D3(i): live-updates the in-memory preview order (no DB write) and refreshes the
     * list so icons visibly shift out of the way, matching Folder#realTimeReorder's real-time
     * feedback. No-op if [beginPendingReorder] hasn't been called.
     */
    fun previewReorder(moved: AppInfo, insertIndex: Int) {
        val current = pendingOrder ?: return
        pendingOrder = movedAppTo(current, moved, insertIndex).toMutableList()
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

    /**
     * Morrowa §10.38.1 G-2: moves [moved]'s App entry to app-space [appInsertIndex] within [order]
     * (the full folder+app entry list), keeping every folder entry in place. [appInsertIndex] counts
     * apps only -- matching how [app.lawnchair.allapps.views.SearchContainerView] resolves a drop
     * target against [getOrderedApps] -- and is translated to an entry-space position (insert just
     * before the app currently at that app-index, or after the last app when past the end). This
     * preserves the pre-G behaviour of [movedTo] in app-space while carrying folders unchanged.
     */
    private fun movedAppTo(order: List<DrawerEntry>, moved: AppInfo, appInsertIndex: Int): List<DrawerEntry> {
        val movedKey = "$APP_PREFIX${moved.toComponentKey()}"
        val without = order.filterNot { it.key() == movedKey }
        val remainingApps = without.filterIsInstance<DrawerEntry.App>()
        val entryIndex = if (appInsertIndex >= remainingApps.size) {
            val lastAppIdx = without.indexOfLast { it is DrawerEntry.App }
            if (lastAppIdx == -1) without.size else lastAppIdx + 1
        } else {
            without.indexOf(remainingApps[appInsertIndex.coerceAtLeast(0)])
        }
        val result = without.toMutableList()
        result.add(entryIndex.coerceIn(0, result.size), DrawerEntry.App(moved))
        return result
    }

    private fun observeFolders() {
        viewModel.foldersLiveData.observe(context as LifecycleOwner) { folders ->
            // Morrowa §10.35 F-B: pins down D2 (emission never arrives after a drag-create) vs D1'
            // (emission arrives but a folder's contents are unresolved). Kept permanently -- cheap.
            Log.d(
                FOLDER_TAG,
                "observeFolders fired: ${folders.size} folders " +
                    folders.joinToString { "(id=${it.id}, '${it.title}', contents=${it.getContents().size})" },
            )
            folderList = folders.toMutableList()
            updateAdapterItems()
        }
    }

    /**
     * Morrowa §10.38.1 G-1: resolves the App Drawer folders that should be shown -- each folder's
     * stored contents re-resolved against the live [AllAppsStore] and passed through the existing
     * `size > 1` display gate. Carries the canonical [FolderInfo.id] onto the display FolderInfo
     * (fact g fix) so AdapterItem identity can key off id, not just title. Unordered (see
     * [orderedFolderEntries]).
     */
    private fun resolveFolderEntries(): List<DrawerEntry.Folder> = folderList.mapNotNull { folder ->
        val contents = folder.getContents()
        val folderApps = contents.mapNotNull { app -> appsStore.getApp(app.componentKey) }
        // Morrowa §10.35 F-B: reveals whether the display gate (resolved size > 1) is what drops a
        // freshly-created folder (D1'), separately from whether it was emitted (D2).
        Log.d(
            FOLDER_TAG,
            "resolveFolderEntries folder (id=${folder.id}, '${folder.title}'): " +
                "contents=${contents.size} resolved=${folderApps.size} shown=${folderApps.size > 1}",
        )
        if (folderApps.size <= 1) return@mapNotNull null
        val folderInfo = FolderInfo().apply {
            id = folder.id
            title = folder.title
            folderApps.forEach { add(it) }
        }
        DrawerEntry.Folder(folderInfo)
    }

    /**
     * Morrowa §10.38.1 G-1: [resolveFolderEntries] in display order -- by unified DrawerOrder folder
     * rank when present, else by the legacy `drawerListOrder` pref (the pre-G folder order, kept
     * only as a pre-seed fallback so upgrade appearance is unchanged; the pref is otherwise no
     * longer read/written, §10.38.5 risk 1).
     */
    private fun orderedFolderEntries(): List<DrawerEntry.Folder> {
        val legacy = FolderOrderUtils.stringToIntList(prefs.drawerListOrder.get())
        return resolveFolderEntries().sortedWith(
            compareBy(
                { drawerOrder[it.key()] ?: Int.MAX_VALUE },
                { legacy.indexOf(it.info.id).takeIf { i -> i != -1 } ?: Int.MAX_VALUE },
            ),
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
            // Morrowa §10.38.1 G-1/G-2: single rank-merge walk over the unified folder+app order.
            val entries = buildOrderedEntries(appList.mapNotNull { it })
            var lastSectionName: String? = null
            entries.forEach { entry ->
                when (entry) {
                    is DrawerEntry.Folder -> {
                        mAdapterItems.add(AdapterItem.asFolder(entry.info))
                        position++
                    }
                    is DrawerEntry.App -> {
                        val info = entry.info
                        mAdapterItems.add(AdapterItem.asApp(info))
                        // Reproduce base addAppsWithSections' fast-scroll sectioning
                        // (AlphabeticalAppsList.java:526-537): a new FastScrollSectionInfo whenever
                        // the section letter changes across consecutive app items.
                        val sectionName = info.sectionName
                        if (sectionName != lastSectionName) {
                            lastSectionName = sectionName
                            fastScrollerSections.add(
                                AlphabeticalAppsList.FastScrollSectionInfo(sectionName, position),
                            )
                        }
                        position++
                    }
                }
            }
            maybeSeedDrawerOrder(entries)
        }

        return position
    }

    /**
     * Morrowa §10.38.1 G-1/G-2 read path: builds the unified display sequence of folders + apps.
     * Folders resolve through the `size > 1` gate ([resolveFolderEntries]); folder-member apps are
     * dropped from the app list when `pref_hideFolderApps` is on. The sequence is a rank-merge of
     * both by their DrawerOrder rank (folders before apps on a tie, unranked apps kept in comparator
     * order at the end). Before the runtime seed lands -- DrawerOrder empty, or not every displayed
     * folder is ranked yet (e.g. folders emitted after the seed ran) -- it falls back to the classic
     * "folders as a top block, then apps" layout, which is exactly the pre-G appearance and what the
     * seed snapshots.
     */
    private fun buildOrderedEntries(appList: List<AppInfo>): List<DrawerEntry> {
        val folderEntries = orderedFolderEntries()
        val hideMembers = prefs.folderApps.get()
        val memberKeys = if (hideMembers) {
            folderEntries.flatMap { fe ->
                fe.info.getContents().mapNotNull { (it as? AppInfo)?.toComponentKey()?.toString() }
            }.toSet()
        } else {
            emptySet()
        }
        val appEntries = appList
            .filterNot { hideMembers && memberKeys.contains(it.toComponentKey().toString()) }
            .map { DrawerEntry.App(it) }

        val allFoldersRanked = folderEntries.isNotEmpty() &&
            folderEntries.all { drawerOrder.containsKey(it.key()) }
        if (drawerOrder.isEmpty() || !allFoldersRanked) {
            return folderEntries + appEntries
        }
        return (folderEntries + appEntries).sortedWith(
            compareBy(
                { drawerOrder[it.key()] ?: Int.MAX_VALUE },
                { if (it is DrawerEntry.Folder) 0 else 1 },
            ),
        )
    }

    /**
     * Morrowa §10.38.1 G-1 runtime data migration: if the unified DrawerOrder is still empty but
     * there is a pre-G order to preserve (any folders, or a legacy app manual order), snapshot the
     * current display sequence [entries] (folders top block, then apps) at rank 0..N. One-shot per
     * session ([drawerOrderSeeded]); the resulting appearance is identical -- only later reorders
     * and folder creation start interleaving. No-op for a genuinely fresh user (no folders, no
     * reorders): DrawerOrder stays empty and the list is plain alphabetical.
     */
    private fun maybeSeedDrawerOrder(entries: List<DrawerEntry>) {
        if (!isMainList || drawerOrderSeeded) return
        if (drawerOrder.isNotEmpty()) {
            drawerOrderSeeded = true
            return
        }
        val hasLegacyOrder = folderList.isNotEmpty() || drawerAppOrder.isNotEmpty()
        if (!hasLegacyOrder || entries.isEmpty()) return
        drawerOrderSeeded = true
        val seed = entries.mapIndexed { index, entry -> DrawerOrderEntity(key = entry.key(), rank = index) }
        drawerOrder = seed.associate { it.key to it.rank }
        context.launcher.lifecycleScope.launch {
            AppDatabase.INSTANCE.get(context).drawerOrderDao().replaceAll(seed)
        }
    }

    override fun onIdpChanged(modelPropertiesChanged: Boolean) {
        onAppsUpdated()
    }

    /**
     * Morrowa §10.35 F-C (要望2): only the App Drawer's live drag/reorder wants DiffUtil to detect
     * moves (so [androidx.recyclerview.widget.DefaultItemAnimator] plays a symmetric slide instead
     * of the direction-asymmetric remove+insert that detectMoves=false produces, §10.34.2). Scoped
     * to an in-progress drag to keep the cost off the normal search / app-update paths, matching the
     * drag-only ItemAnimator (§10.30.6).
     */
    override fun shouldDetectMoves(): Boolean = draggedComponentKey != null || pendingOrder != null

    private companion object {
        private const val FOLDER_TAG = "MorrowaFolder"

        // Morrowa §10.38.1 G-1: DrawerOrder key namespace for apps (see [DrawerEntry.App.key]).
        private const val APP_PREFIX = "app:"
    }
}
