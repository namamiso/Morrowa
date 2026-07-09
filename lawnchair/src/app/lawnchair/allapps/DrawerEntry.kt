package app.lawnchair.allapps

import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.FolderInfo

/**
 * Morrowa §10.38.1 G-1: one element of the unified App Drawer order -- either an app or an App
 * Drawer folder. [key] is the namespaced key stored in `DrawerOrder`
 * ([app.lawnchair.data.appdrawer.DrawerOrderEntity]): `"app:<componentKey>"` / `"folder:<id>"`.
 * The read walk in [LawnchairAlphabeticalAppsList.addAppsWithSections] and the reorder handoff with
 * [app.lawnchair.allapps.views.SearchContainerView] both speak this type. In task G only [App]
 * entries actually move (folder drag arrives in task H), but the reorder machinery is entry-based
 * from the start so H is a pure extension.
 */
sealed class DrawerEntry {
    abstract fun key(): String

    class App(val info: AppInfo) : DrawerEntry() {
        override fun key(): String = "app:" + info.toComponentKey().toString()
    }

    class Folder(val info: FolderInfo) : DrawerEntry() {
        override fun key(): String = "folder:" + info.id
    }
}
