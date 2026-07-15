package app.lawnchair.allapps

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.lawnchair.allapps.edit.DrawerOrderKeys
import app.lawnchair.allapps.edit.DrawerOrderMerge
import app.lawnchair.data.AppDatabase
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

@Suppress("SYNTHETIC_PROPERTY_WITHOUT_JAVA_ORIGIN")
class LawnchairAlphabeticalAppsList<T>(
    private val context: T,
    private val appsStore: AllAppsStore<T>,
    workProfileManager: WorkProfileManager?,
    privateProfileManager: PrivateProfileManager?,
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

    // Morrowa v2 P1 (docs/Morrowa_AppDrawer_編集モードv2_要件設計.md §3.4): the persisted unified
    // App Drawer order, namespaced key -> rank. Empty until the user commits their first edit
    // session (P2); while empty the drawer renders the legacy layout unchanged. Read-only here —
    // the only writer is the edit session's commit.
    private var drawerOrder: Map<String, Int> = emptyMap()

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
        observeDrawerOrder()
    }

    // Morrowa v2 P1: single direction DB -> Flow -> display (§10.42 原則3). No in-memory writes
    // besides this observer.
    private fun observeDrawerOrder() {
        AppDatabase.INSTANCE.get(context).drawerOrderDao().getAll()
            .onEachFlow { entities ->
                drawerOrder = entities.associate { it.key to it.rank }
                onAppsUpdated()
            }
            .launchIn(context.launcher.lifecycleScope)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        context.launcher.deviceProfile.inv.removeOnChangeListener(this)
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
        } else if (drawerOrder.isEmpty()) {
            // Morrowa v2 P1: no committed edit yet -> the legacy layout, code path unchanged
            // (folders as a top block, then apps alphabetical).
            getSortedFolders().forEach { folder ->
                val folderApps = folder.getContents().mapNotNull { app ->
                    appsStore.getApp(app.componentKey)
                }
                if (folderApps.size > 1) {
                    val folderInfo = FolderInfo()
                    // Morrowa v2 P2: carry the Room id so DrawerEditOverlay's snapshot can key the
                    // folder ("folder:<id>"). Display behavior is unchanged.
                    folderInfo.id = folder.id
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
        } else {
            // Morrowa v2 P1 (§3.4): unified order — a single walk over the rank-merged sequence.
            // Folder resolution and the member-hiding rule are identical to the legacy branch;
            // only the ordering differs (DrawerOrderMerge, pure logic + unit tests).
            val folderInfos = getSortedFolders().mapNotNull { folder ->
                val folderApps = folder.getContents().mapNotNull { app ->
                    appsStore.getApp(app.componentKey)
                }
                if (folderApps.size <= 1) return@mapNotNull null
                FolderInfo().apply {
                    id = folder.id
                    title = folder.title
                    folderApps.forEach { app ->
                        add(app)
                        if (prefs.folderApps.get()) filteredList.add(app)
                    }
                }
            }
            val apps = appList.mapNotNull { it }
                .filterNot { app -> filteredList.contains(app) && prefs.folderApps.get() }

            val folderByKey = folderInfos.associateBy { DrawerOrderKeys.folder(it.id) }
            val appByKey = apps.associateBy { DrawerOrderKeys.app(it.toComponentKey().toString()) }
            // Morrowa B-3: with hideFolderApps OFF, member apps also appear top-level but are
            // never ranked by an edit commit — map them to their folder so the merge places them
            // right after it instead of dumping them at the end.
            val memberOf = if (prefs.folderApps.get()) {
                emptyMap()
            } else {
                buildMap {
                    folderInfos.forEach { folderInfo ->
                        val folderKey = DrawerOrderKeys.folder(folderInfo.id)
                        folderInfo.getContents().forEach { content ->
                            (content as? AppInfo)?.let {
                                put(DrawerOrderKeys.app(it.toComponentKey().toString()), folderKey)
                            }
                        }
                    }
                }
            }
            val merged = DrawerOrderMerge.mergedKeys(
                ranks = drawerOrder,
                folderKeys = folderInfos.map { DrawerOrderKeys.folder(it.id) },
                appKeys = apps.map { DrawerOrderKeys.app(it.toComponentKey().toString()) },
                memberOf = memberOf,
            )

            var lastSectionName: String? = null
            merged.forEach { key ->
                folderByKey[key]?.let { folderInfo ->
                    mAdapterItems.add(AdapterItem.asFolder(folderInfo))
                    position++
                    return@forEach
                }
                val info = appByKey[key] ?: return@forEach
                mAdapterItems.add(AdapterItem.asApp(info))
                // Reproduce the base fast-scroll sectioning (AlphabeticalAppsList#addAppsWithSections):
                // a new section whenever the letter changes across consecutive app items.
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

        return position
    }

    override fun onIdpChanged(modelPropertiesChanged: Boolean) {
        onAppsUpdated()
    }
}
