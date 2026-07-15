package app.lawnchair.allapps.edit

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Morrowa v2 P1 (docs/Morrowa_AppDrawer_編集モードv2_要件設計.md §3.4/§4): unit tests for the pure
 * read-path merge. Encodes the two display modes (legacy layout while DrawerOrder is empty; the
 * unified rank order once an edit was committed) and the "new entries at the end" rule.
 */
class DrawerOrderMergeTest {

    private val folders = listOf("folder:1", "folder:2")
    private val apps = listOf("app:a", "app:b", "app:c")

    @Test
    fun `empty ranks yields legacy layout - folders then apps`() {
        assertEquals(
            listOf("folder:1", "folder:2", "app:a", "app:b", "app:c"),
            DrawerOrderMerge.mergedKeys(emptyMap(), folders, apps),
        )
    }

    @Test
    fun `ranked keys follow rank order, folders can sit between apps`() {
        val ranks = mapOf(
            "app:b" to 0,
            "folder:2" to 1,
            "app:a" to 2,
            "folder:1" to 3,
            "app:c" to 4,
        )
        assertEquals(
            listOf("app:b", "folder:2", "app:a", "folder:1", "app:c"),
            DrawerOrderMerge.mergedKeys(ranks, folders, apps),
        )
    }

    @Test
    fun `unranked entries go to the end - folders first, each group in input order`() {
        // Only a and c were ranked; folder:1/folder:2/app:b are new since the last edit.
        val ranks = mapOf("app:c" to 0, "app:a" to 1)
        assertEquals(
            listOf("app:c", "app:a", "folder:1", "folder:2", "app:b"),
            DrawerOrderMerge.mergedKeys(ranks, folders, apps),
        )
    }

    @Test
    fun `ranks for entries that no longer exist are ignored`() {
        val ranks = mapOf(
            "app:gone" to 0,
            "app:b" to 1,
            "folder:99" to 2,
            "app:a" to 3,
        )
        assertEquals(
            listOf("app:b", "app:a", "folder:1", "folder:2", "app:c"),
            DrawerOrderMerge.mergedKeys(ranks, folders, apps),
        )
    }

    @Test
    fun `duplicate rank values keep input order (stable sort)`() {
        val ranks = mapOf("folder:1" to 0, "app:a" to 0, "app:b" to 0)
        assertEquals(
            listOf("folder:1", "app:a", "app:b", "folder:2", "app:c"),
            DrawerOrderMerge.mergedKeys(ranks, folders, apps),
        )
    }

    @Test
    fun `empty candidates yield empty result`() {
        assertEquals(
            emptyList<String>(),
            DrawerOrderMerge.mergedKeys(mapOf("app:a" to 0), emptyList(), emptyList()),
        )
    }

    // ---- memberOf (B-3: hideFolderApps=false — member apps shown top-level too) ----

    @Test
    fun `unranked member apps land right after their folder, in input order`() {
        val ranks = mapOf("app:a" to 0, "folder:1" to 1, "app:c" to 2)
        val memberOf = mapOf("app:x" to "folder:1", "app:y" to "folder:1")
        assertEquals(
            listOf("app:a", "folder:1", "app:x", "app:y", "app:c", "folder:2"),
            DrawerOrderMerge.mergedKeys(ranks, folders, listOf("app:a", "app:c", "app:x", "app:y"), memberOf),
        )
    }

    @Test
    fun `member of a missing folder falls back to the end`() {
        val ranks = mapOf("app:a" to 0)
        val memberOf = mapOf("app:x" to "folder:99")
        assertEquals(
            listOf("app:a", "folder:1", "folder:2", "app:x"),
            DrawerOrderMerge.mergedKeys(ranks, folders, listOf("app:a", "app:x"), memberOf),
        )
    }

    @Test
    fun `ranked member apps keep their own rank`() {
        // A pre-B-3 commit may have ranked a member duplicate; its explicit rank wins.
        val ranks = mapOf("folder:1" to 0, "app:x" to 1, "app:a" to 2)
        val memberOf = mapOf("app:x" to "folder:1")
        assertEquals(
            listOf("folder:1", "app:x", "app:a", "folder:2"),
            DrawerOrderMerge.mergedKeys(ranks, folders, listOf("app:a", "app:x"), memberOf),
        )
    }

    @Test
    fun `key helpers namespace correctly`() {
        assertEquals("app:com.example/.Main", DrawerOrderKeys.app("com.example/.Main"))
        assertEquals("folder:7", DrawerOrderKeys.folder(7))
    }
}
