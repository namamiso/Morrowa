package app.morrowa

import android.content.Context
import android.view.View
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.Workspace
import com.android.launcher3.model.data.ItemInfo

object MorrowaTransparentItemController {
    private const val PREFS_NAME = "morrowa_transparent_items"
    private const val KEY_TRANSPARENT_ITEMS = "transparent_items"
    private const val EDIT_MODE_ALPHA = 0.28f

    @JvmStatic
    fun isSupported(itemInfo: ItemInfo?): Boolean {
        if (itemInfo == null || itemInfo.id < 0) {
            return false
        }
        if (itemInfo.container != Favorites.CONTAINER_DESKTOP) {
            return false
        }
        return itemInfo.itemType == Favorites.ITEM_TYPE_APPLICATION ||
            itemInfo.itemType == Favorites.ITEM_TYPE_FOLDER
    }

    @JvmStatic
    fun isTransparent(context: Context, itemInfo: ItemInfo): Boolean =
        getTransparentKeys(context).contains(keyForItem(itemInfo))

    @JvmStatic
    fun setTransparent(context: Context, itemInfo: ItemInfo, transparent: Boolean) {
        val current = getTransparentKeys(context).toMutableSet()
        val key = keyForItem(itemInfo)
        if (transparent) {
            current.add(key)
        } else {
            current.remove(key)
        }
        getPrefs(context)
            .edit()
            .putStringSet(KEY_TRANSPARENT_ITEMS, current)
            .apply()
    }

    @JvmStatic
    fun applyToView(view: View?, itemInfo: ItemInfo?, editMode: Boolean) {
        applyToView(view, itemInfo, editMode, getTransparentKeys(view?.context ?: return))
    }

    @JvmStatic
    fun applyToView(
        view: View?,
        itemInfo: ItemInfo?,
        editMode: Boolean,
        transparentKeys: Set<String>,
    ) {
        if (view == null || !isSupported(itemInfo)) {
            return
        }
        val transparent = transparentKeys.contains(keyForItem(itemInfo!!))
        view.alpha = when {
            !transparent -> 1f
            editMode -> EDIT_MODE_ALPHA
            else -> 0f
        }
    }

    @JvmStatic
    fun applyToWorkspace(workspace: Workspace<*>, editMode: Boolean) {
        val transparentKeys = getTransparentKeys(workspace.context)
        workspace.mapOverItems { info, view ->
            applyToView(view, info, editMode, transparentKeys)
            false
        }
    }

    @JvmStatic
    fun getTransparentKeys(context: Context): Set<String> =
        getPrefs(context).getStringSet(KEY_TRANSPARENT_ITEMS, emptySet()).orEmpty()

    private fun keyForItem(itemInfo: ItemInfo): String {
        val component = itemInfo.targetComponent
        if (component != null && itemInfo.itemType == Favorites.ITEM_TYPE_APPLICATION) {
            return "app:${itemInfo.user}:$component"
        }
        return "item:${itemInfo.id}"
    }

    private fun getPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
