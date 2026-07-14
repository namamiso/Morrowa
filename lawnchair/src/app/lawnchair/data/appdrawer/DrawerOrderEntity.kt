package app.lawnchair.data.appdrawer

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Morrowa v2 (docs/Morrowa_AppDrawer_編集モードv2_要件設計.md §R4): unified App Drawer order for
 * the main list. A single ordered sequence holding both apps and App Drawer folders, so a folder
 * can live anywhere among the apps. [key] is namespaced — see
 * [app.lawnchair.allapps.edit.DrawerOrderKeys]. Empty until the user commits their first edit
 * session; the read path falls back to the legacy layout while empty (see
 * [app.lawnchair.allapps.edit.DrawerOrderMerge]).
 */
@Entity(tableName = "DrawerOrder")
data class DrawerOrderEntity(
    @PrimaryKey val key: String,
    val rank: Int,
)
