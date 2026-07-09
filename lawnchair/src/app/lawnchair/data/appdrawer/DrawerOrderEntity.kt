package app.lawnchair.data.appdrawer

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Morrowa §10.38.1 G-1: unified App Drawer order for the main list. A single ordered sequence that
 * holds both apps and App Drawer folders, so a folder can live anywhere among the apps (要望A)
 * rather than being pinned to a top block. [key] is a namespaced string -- `"app:<componentKey>"`
 * for an app, `"folder:<folderId>"` for a folder -- which structurally rules out a componentKey and
 * a folder id colliding on the same primary key. Replaces the app-only [DrawerAppOrderEntity] +
 * `drawerListOrder` pref pair (both left in place but no longer read/written; see §10.38.5 risk 1).
 */
@Entity(tableName = "DrawerOrder")
data class DrawerOrderEntity(
    @PrimaryKey val key: String,
    val rank: Int,
)
