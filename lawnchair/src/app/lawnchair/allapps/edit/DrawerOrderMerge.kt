package app.lawnchair.allapps.edit

/**
 * Morrowa v2 (docs/Morrowa_AppDrawer_編集モードv2_要件設計.md §3.4): the namespaced keys stored in
 * the `DrawerOrder` table — `"app:<componentKey>"` / `"folder:<folderId>"`. The namespace
 * structurally rules out a componentKey and a folder id colliding on the same primary key.
 */
object DrawerOrderKeys {
    const val APP_PREFIX = "app:"
    const val FOLDER_PREFIX = "folder:"

    fun app(componentKey: String): String = APP_PREFIX + componentKey
    fun folder(folderId: Int): String = FOLDER_PREFIX + folderId
}

/**
 * Morrowa v2 §3.4 read path, pure logic: merges the persisted unified order with the current
 * display candidates. Android-free so the merge rules are unit-testable (P1).
 */
object DrawerOrderMerge {

    /**
     * Returns the display order of entry keys.
     *
     * - [ranks] empty (user has never committed an edit): the legacy layout — [folderKeys] as a
     *   top block, then [appKeys] (alphabetical), exactly the pre-v2 appearance.
     * - otherwise: ranked keys by rank (stable: ties keep input order), then unranked folders,
     *   then unranked apps, appended at the end in their input order — the v2 R2
     *   "new entries go to the end" rule. Ranks for keys that no longer exist (uninstalled app,
     *   deleted folder) are ignored.
     */
    fun mergedKeys(
        ranks: Map<String, Int>,
        folderKeys: List<String>,
        appKeys: List<String>,
    ): List<String> {
        if (ranks.isEmpty()) return folderKeys + appKeys
        val all = folderKeys + appKeys
        val ranked = all.filter { it in ranks }.sortedBy { ranks[it] }
        val unrankedFolders = folderKeys.filter { it !in ranks }
        val unrankedApps = appKeys.filter { it !in ranks }
        return ranked + unrankedFolders + unrankedApps
    }
}
