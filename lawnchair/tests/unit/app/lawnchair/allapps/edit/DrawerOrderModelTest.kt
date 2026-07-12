package app.lawnchair.allapps.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Morrowa App Drawer 編集モード v2 P0 (docs/Morrowa_AppDrawer_編集モードv2_要件設計.md §3.3/§4):
 * unit tests for the pure edit-session model. Written before the UI layers (P2+) — these encode
 * the v2 R2/R3 semantics, including the no-throw/no-op policy for invalid input.
 */
class DrawerOrderModelTest {

    private fun app(key: String) = DrawerEditEntry.App(key)
    private fun folder(id: Int?, name: String, vararg members: String) =
        DrawerEditEntry.Folder(id, name, members.toList())

    /** a, b, F1(x, y), c */
    private fun model() = DrawerOrderModel(
        listOf(app("a"), app("b"), folder(1, "F1", "x", "y"), app("c")),
    )

    // ---- move ----

    @Test
    fun `move forward`() {
        val m = model().move(0, 2)
        assertEquals(listOf(app("b"), folder(1, "F1", "x", "y"), app("a"), app("c")), m.entries)
    }

    @Test
    fun `move backward`() {
        val m = model().move(3, 0)
        assertEquals(listOf(app("c"), app("a"), app("b"), folder(1, "F1", "x", "y")), m.entries)
    }

    @Test
    fun `move folder entry`() {
        val m = model().move(2, 0)
        assertEquals(listOf(folder(1, "F1", "x", "y"), app("a"), app("b"), app("c")), m.entries)
    }

    @Test
    fun `move clamps toIndex`() {
        val m = model().move(0, 99)
        assertEquals(listOf(app("b"), folder(1, "F1", "x", "y"), app("c"), app("a")), m.entries)
        val m2 = model().move(3, -5)
        assertEquals(listOf(app("c"), app("a"), app("b"), folder(1, "F1", "x", "y")), m2.entries)
    }

    @Test
    fun `move with invalid fromIndex is a no-op`() {
        val base = model()
        assertSame(base, base.move(-1, 0))
        assertSame(base, base.move(4, 0))
    }

    @Test
    fun `move to same index is identity`() {
        assertEquals(model().entries, model().move(1, 1).entries)
    }

    // ---- groupIntoFolder ----

    @Test
    fun `group creates folder at first selected position with members in display order`() {
        // Select in reverse order — member order must follow display order (a, c), and the
        // folder lands at a's slot (index 0).
        val m = model().groupIntoFolder(listOf("c", "a"), "New")
        assertEquals(
            listOf(folder(null, "New", "a", "c"), app("b"), folder(1, "F1", "x", "y")),
            m.entries,
        )
    }

    @Test
    fun `group counts surviving entries for the insert position`() {
        // Select b and c: folder goes to b's slot (index 1), a and F1 survive around it.
        val m = model().groupIntoFolder(listOf("b", "c"), "New")
        assertEquals(
            listOf(app("a"), folder(null, "New", "b", "c"), folder(1, "F1", "x", "y")),
            m.entries,
        )
    }

    @Test
    fun `group ignores unknown keys and folder members`() {
        // "x" is a member of F1 (not top-level), "zzz" is unknown — both ignored.
        val m = model().groupIntoFolder(listOf("a", "c", "x", "zzz"), "New")
        assertEquals(
            listOf(folder(null, "New", "a", "c"), app("b"), folder(1, "F1", "x", "y")),
            m.entries,
        )
    }

    @Test
    fun `group with fewer than 2 matching apps is a no-op`() {
        val base = model()
        assertSame(base, base.groupIntoFolder(listOf("a"), "New"))
        assertSame(base, base.groupIntoFolder(listOf("x", "zzz"), "New"))
        assertSame(base, base.groupIntoFolder(emptyList(), "New"))
    }

    @Test
    fun `grouped folder keeps the given name and has no id yet`() {
        val created = model().groupIntoFolder(listOf("a", "b"), "しごと")
            .entries.first() as DrawerEditEntry.Folder
        assertEquals("しごと", created.name)
        assertEquals(null, created.folderId)
    }

    // ---- addToFolder ----

    @Test
    fun `addToFolder appends members in display order and removes them from top level`() {
        val m = model().addToFolder(listOf("c", "a"), 2)
        assertEquals(listOf(app("b"), folder(1, "F1", "x", "y", "a", "c")), m.entries)
    }

    @Test
    fun `addToFolder keeps folder slot stable relative to survivors`() {
        // Removing "a" (before the folder) shifts the folder's index; the updated folder must
        // land at its new position, not the stale index.
        val m = model().addToFolder(listOf("a"), 2)
        assertEquals(listOf(app("b"), folder(1, "F1", "x", "y", "a"), app("c")), m.entries)
    }

    @Test
    fun `addToFolder on a non-folder index is a no-op`() {
        val base = model()
        assertSame(base, base.addToFolder(listOf("a"), 0))
        assertSame(base, base.addToFolder(listOf("a"), 99))
    }

    @Test
    fun `addToFolder with no matching top-level apps is a no-op`() {
        val base = model()
        assertSame(base, base.addToFolder(listOf("x", "zzz"), 2))
    }

    // ---- disband ----

    @Test
    fun `disband expands members at the folder position in member order`() {
        val m = model().disband(2)
        assertEquals(listOf(app("a"), app("b"), app("x"), app("y"), app("c")), m.entries)
    }

    @Test
    fun `disband on a non-folder index is a no-op`() {
        val base = model()
        assertSame(base, base.disband(0))
        assertSame(base, base.disband(-1))
    }

    // ---- moveInFolder ----

    @Test
    fun `moveInFolder reorders members`() {
        val m = model().moveInFolder(2, 0, 1)
        assertEquals(folder(1, "F1", "y", "x"), m.entries[2])
    }

    @Test
    fun `moveInFolder clamps toIndex and rejects invalid fromIndex`() {
        val m = model().moveInFolder(2, 0, 99)
        assertEquals(folder(1, "F1", "y", "x"), m.entries[2])
        val base = model()
        assertSame(base, base.moveInFolder(2, 5, 0))
        assertSame(base, base.moveInFolder(0, 0, 1))
    }

    // ---- removeFromFolder ----

    @Test
    fun `removed member reappears right after the folder`() {
        val big = DrawerOrderModel(
            listOf(app("a"), folder(1, "F1", "x", "y", "z"), app("c")),
        ).removeFromFolder(1, "y")
        assertEquals(
            listOf(app("a"), folder(1, "F1", "x", "z"), app("y"), app("c")),
            big.entries,
        )
    }

    @Test
    fun `folder left with one member auto-disbands, remaining member takes the slot`() {
        val m = model().removeFromFolder(2, "y")
        assertEquals(listOf(app("a"), app("b"), app("x"), app("y"), app("c")), m.entries)
    }

    @Test
    fun `removeFromFolder with unknown member or non-folder index is a no-op`() {
        val base = model()
        assertSame(base, base.removeFromFolder(2, "zzz"))
        assertSame(base, base.removeFromFolder(0, "a"))
    }

    // ---- renameFolder ----

    @Test
    fun `renameFolder changes only the name`() {
        val base = model()
        val m = base.renameFolder(2, "ツール")
        assertEquals(folder(1, "ツール", "x", "y"), m.entries[2])
        // Other entries are carried over untouched (same instances).
        assertSame(base.entries[0], m.entries[0])
        assertSame(base.entries[3], m.entries[3])
    }

    @Test
    fun `renameFolder on a non-folder index is a no-op`() {
        val base = model()
        assertSame(base, base.renameFolder(0, "X"))
    }

    // ---- invariants ----

    @Test
    fun `group then disband restores all keys`() {
        val base = model()
        val roundTrip = base.groupIntoFolder(listOf("a", "b"), "T").disband(0)
        assertEquals(base.allAppKeys().sorted(), roundTrip.allAppKeys().sorted())
    }

    @Test
    fun `no operation duplicates or loses keys`() {
        val base = model()
        val mutated = base
            .move(0, 3)
            .groupIntoFolder(listOf("b", "c"), "T")
            .addToFolder(listOf("a"), base.entries.size - 3) // arbitrary valid-ish index
            .disband(0)
            .removeFromFolder(0, "x")
        assertEquals(base.allAppKeys().sorted(), mutated.allAppKeys().sorted())
    }

    @Test
    fun `allAppKeys covers top level and members`() {
        assertEquals(listOf("a", "b", "x", "y", "c"), model().allAppKeys())
    }
}
