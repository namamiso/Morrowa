package app.lawnchair.allapps.edit

/**
 * Morrowa App Drawer 編集モード v2 (docs/Morrowa_AppDrawer_編集モードv2_要件設計.md §3.3):
 * one element of the unified drawer order held by an edit session — an app or an App Drawer
 * folder. Pure Kotlin: keys are opaque strings (componentKey serializations); folder membership
 * lives here too, so the whole edit session is testable without Android.
 */
sealed interface DrawerEditEntry {
    data class App(val key: String) : DrawerEditEntry

    /**
     * [folderId] is the Room id for a pre-existing folder, or null for a folder created in this
     * session (the commit layer assigns the real id). [members] are app keys in display order.
     */
    data class Folder(
        val folderId: Int?,
        val name: String,
        val members: List<String>,
    ) : DrawerEditEntry
}

/**
 * Morrowa v2 §3.3: the pure, immutable edit-session model of the App Drawer's unified order.
 * Every operation returns a new model; **invalid input is a no-op** (returns `this`) rather than
 * an exception, so a UI-side index bug can never corrupt the session. The UI/state layer
 * ([app.lawnchair.allapps.edit] ViewModel, P2) is a thin shell over these operations, and the
 * commit layer diffs the final model against the session's initial snapshot.
 *
 * Invariant: an app key appears at most once across top-level [DrawerEditEntry.App] entries and
 * all folder [DrawerEditEntry.Folder.members] combined.
 */
data class DrawerOrderModel(val entries: List<DrawerEditEntry>) {

    /** Every app key in the model: top-level apps and folder members. */
    fun allAppKeys(): List<String> = entries.flatMap { entry ->
        when (entry) {
            is DrawerEditEntry.App -> listOf(entry.key)
            is DrawerEditEntry.Folder -> entry.members
        }
    }

    /**
     * Moves the entry at [fromIndex] to [toIndex] (indices in [entries]; [toIndex] is the position
     * in the list *after* removal, matching ItemTouchHelper's onMove contract). [toIndex] is
     * clamped; an out-of-range [fromIndex] is a no-op.
     */
    fun move(fromIndex: Int, toIndex: Int): DrawerOrderModel {
        if (fromIndex !in entries.indices) return this
        val result = entries.toMutableList()
        val moved = result.removeAt(fromIndex)
        result.add(toIndex.coerceIn(0, result.size), moved)
        return DrawerOrderModel(result)
    }

    /**
     * Groups the top-level apps whose key is in [keys] into a new folder named [name] (v2 R3).
     * The folder is created at the position of the first selected entry; members are stored in
     * display order. Requires at least 2 matching top-level apps — otherwise a no-op (folders of
     * 0-1 apps fall below the display gate and auto-disband anyway). Keys that are unknown, not
     * top-level (already folder members), or folders are ignored. The new folder has
     * `folderId = null` until commit.
     */
    fun groupIntoFolder(keys: Collection<String>, name: String): DrawerOrderModel {
        val keySet = keys.toSet()
        val selected = entries.filterIsInstance<DrawerEditEntry.App>().filter { it.key in keySet }
        if (selected.size < 2) return this
        val selectedSet = selected.toSet()
        // Position of the first selected entry, counted in surviving (non-selected) entries ==
        // the insert index after the members are removed from the top level.
        val insertAt = entries.asSequence().takeWhile { it !in selectedSet }.count()
        val result = entries.filterNot { it in selectedSet }.toMutableList()
        result.add(
            insertAt.coerceIn(0, result.size),
            DrawerEditEntry.Folder(folderId = null, name = name, members = selected.map { it.key }),
        )
        return DrawerOrderModel(result)
    }

    /**
     * Appends the top-level apps whose key is in [keys] to the folder at [folderIndex] (v2 R3
     * 「フォルダへ追加」), removing them from the top level. No-op if [folderIndex] isn't a folder
     * or no key matches a top-level app.
     */
    fun addToFolder(keys: Collection<String>, folderIndex: Int): DrawerOrderModel {
        val folder = folderAt(folderIndex) ?: return this
        val keySet = keys.toSet()
        val selected = entries.filterIsInstance<DrawerEditEntry.App>().filter { it.key in keySet }
        if (selected.isEmpty()) return this
        val selectedSet = selected.toSet()
        val result = entries.filterNot { it in selectedSet }.toMutableList()
        val newFolderIndex = result.indexOf(folder)
        result[newFolderIndex] = folder.copy(members = folder.members + selected.map { it.key })
        return DrawerOrderModel(result)
    }

    /**
     * Dissolves the folder at [folderIndex], expanding its members (in member order) at the
     * folder's position (v2 R3 「解除」). No-op if [folderIndex] isn't a folder.
     */
    fun disband(folderIndex: Int): DrawerOrderModel {
        val folder = folderAt(folderIndex) ?: return this
        val result = entries.toMutableList()
        result.removeAt(folderIndex)
        result.addAll(folderIndex, folder.members.map { DrawerEditEntry.App(it) })
        return DrawerOrderModel(result)
    }

    /**
     * Reorders the folder at [folderIndex]'s members: moves the member at [fromIndex] to
     * [toIndex] (member indices; [toIndex] clamped, invalid [fromIndex] is a no-op).
     */
    fun moveInFolder(folderIndex: Int, fromIndex: Int, toIndex: Int): DrawerOrderModel {
        val folder = folderAt(folderIndex) ?: return this
        if (fromIndex !in folder.members.indices) return this
        val members = folder.members.toMutableList()
        val moved = members.removeAt(fromIndex)
        members.add(toIndex.coerceIn(0, members.size), moved)
        return replaceFolder(folderIndex, folder.copy(members = members))
    }

    /**
     * Removes member [key] from the folder at [folderIndex]; the app reappears as a top-level
     * entry **immediately after the folder** (v2 R3). If the folder is left with fewer than 2
     * members it auto-disbands: the remaining member (if any) takes the folder's slot, still
     * followed by the removed app. No-op if [folderIndex] isn't a folder or [key] isn't a member.
     */
    fun removeFromFolder(folderIndex: Int, key: String): DrawerOrderModel {
        val folder = folderAt(folderIndex) ?: return this
        if (key !in folder.members) return this
        val remaining = folder.members - key
        val result = entries.toMutableList()
        result.removeAt(folderIndex)
        val replacement = if (remaining.size >= 2) {
            listOf(folder.copy(members = remaining), DrawerEditEntry.App(key))
        } else {
            remaining.map { DrawerEditEntry.App(it) } + DrawerEditEntry.App(key)
        }
        result.addAll(folderIndex, replacement)
        return DrawerOrderModel(result)
    }

    /** Renames the folder at [folderIndex] to [name]. No-op if it isn't a folder. */
    fun renameFolder(folderIndex: Int, name: String): DrawerOrderModel {
        val folder = folderAt(folderIndex) ?: return this
        return replaceFolder(folderIndex, folder.copy(name = name))
    }

    private fun folderAt(index: Int): DrawerEditEntry.Folder? =
        entries.getOrNull(index) as? DrawerEditEntry.Folder

    private fun replaceFolder(index: Int, folder: DrawerEditEntry.Folder): DrawerOrderModel {
        val result = entries.toMutableList()
        result[index] = folder
        return DrawerOrderModel(result)
    }
}
