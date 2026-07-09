package app.lawnchair.allapps

import android.app.Application
import android.content.Context
import android.util.Log
import app.lawnchair.data.folder.model.FolderViewModel
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.FolderInfo

/**
 * Morrowa §10.38.4 J1: persists the order of an *App Drawer* folder's contents to Room after the
 * user reorders items inside the opened [com.android.launcher3.folder.Folder].
 *
 * Home folders persist through the launcher model; App Drawer folders (`container == NO_ID`,
 * i.e. [com.android.launcher3.folder.Folder.isInAppDrawer]) are backed by Room instead, so their
 * reorder-commit point (Folder#onDropCompleted) calls here. This entry point is only ever reached
 * from behind an `isInAppDrawer()` guard, so it never affects Home folder behavior.
 */
object DrawerFolderReorder {

    private const val TAG = "MorrowaFolder"

    // Application-scoped; a single instance is reused across reorders to avoid re-creating the
    // ViewModel (and its coroutine scope) on every drop.
    @Volatile
    private var viewModel: FolderViewModel? = null

    /**
     * Writes [folder]'s current contents order to Room via [FolderViewModel.updateFolderItems].
     * The order is read straight from [FolderInfo.getContents], which the drag/reorder path has
     * already mutated into the new visual order by this point.
     *
     * No-ops (with a warning) when the folder is an optimistic, not-yet-persisted entry
     * (`id == 0`); the canonical, id-bearing folder replaces it sub-second, so a later reorder
     * persists correctly.
     */
    @JvmStatic
    fun persistOrder(context: Context, folder: FolderInfo) {
        val id = folder.id
        val title = folder.title?.toString().orEmpty()
        if (id == 0) {
            Log.w(TAG, "Skipping drawer folder reorder persist: optimistic folder (id=0) title=$title")
            return
        }
        val apps = folder.getContents().filterIsInstance<AppInfo>()
        if (apps.isEmpty()) {
            Log.w(TAG, "Skipping drawer folder reorder persist: no AppInfo contents (id=$id title=$title)")
            return
        }
        val application = context.applicationContext as? Application ?: run {
            Log.w(TAG, "Skipping drawer folder reorder persist: no Application context (id=$id)")
            return
        }
        val vm = viewModel ?: FolderViewModel(application).also { viewModel = it }
        vm.updateFolderItems(id, title, apps)
    }
}
