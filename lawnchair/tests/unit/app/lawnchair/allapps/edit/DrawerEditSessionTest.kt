package app.lawnchair.allapps.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Morrowa v2 P2: tests for the process-scoped edit session holder — start/resume semantics (Q3:
 * 回転は継続), dirty tracking for the discard confirmation, and the commit key mapping.
 */
class DrawerEditSessionTest {

    private val snapshot = listOf(
        DrawerEditEntry.App("a"),
        DrawerEditEntry.Folder(1, "F1", listOf("x", "y")),
        DrawerEditEntry.App("b"),
    )

    @Before
    fun reset() {
        DrawerEditSession.clear()
    }

    @Test
    fun `start creates a clean session`() {
        DrawerEditSession.startOrResume(snapshot)
        assertTrue(DrawerEditSession.isActive)
        assertFalse(DrawerEditSession.isDirty)
        assertEquals(snapshot, DrawerEditSession.model!!.entries)
    }

    @Test
    fun `update marks the session dirty`() {
        DrawerEditSession.startOrResume(snapshot)
        DrawerEditSession.update { it.move(0, 2) }
        assertTrue(DrawerEditSession.isDirty)
    }

    @Test
    fun `startOrResume with the same key set keeps the draft`() {
        DrawerEditSession.startOrResume(snapshot)
        DrawerEditSession.update { it.move(0, 2) }
        val draft = DrawerEditSession.model!!.entries
        // Same entries, e.g. after rotation the snapshot is rebuilt from the (unchanged) drawer.
        DrawerEditSession.startOrResume(snapshot)
        assertEquals(draft, DrawerEditSession.model!!.entries)
        assertTrue(DrawerEditSession.isDirty)
    }

    @Test
    fun `startOrResume with a changed key set discards the draft`() {
        DrawerEditSession.startOrResume(snapshot)
        DrawerEditSession.update { it.move(0, 2) }
        val changed = snapshot + DrawerEditEntry.App("newly-installed")
        DrawerEditSession.startOrResume(changed)
        assertEquals(changed, DrawerEditSession.model!!.entries)
        assertFalse(DrawerEditSession.isDirty)
    }

    @Test
    fun `clear ends the session`() {
        DrawerEditSession.startOrResume(snapshot)
        DrawerEditSession.clear()
        assertFalse(DrawerEditSession.isActive)
        assertNull(DrawerEditSession.model)
    }

    @Test
    fun `plan for a pure reorder has no folder changes`() {
        DrawerEditSession.startOrResume(snapshot)
        DrawerEditSession.update { it.move(2, 0) }
        val plan = DrawerEditSession.buildCommitPlan()!!
        assertEquals(emptyList<DrawerEditEntry.Folder>(), plan.newFolders)
        assertEquals(emptyList<Int>(), plan.deletedFolderIds)
        assertEquals(emptyList<DrawerEditEntry.Folder>(), plan.updatedFolders)
        assertEquals(
            listOf(DrawerEditEntry.App("b"), DrawerEditEntry.App("a"), DrawerEditEntry.Folder(1, "F1", listOf("x", "y"))),
            plan.orderedEntries,
        )
    }

    @Test
    fun `plan detects a session-created folder`() {
        DrawerEditSession.startOrResume(snapshot)
        DrawerEditSession.update { it.groupIntoFolder(listOf("a", "b"), "New") }
        val plan = DrawerEditSession.buildCommitPlan()!!
        assertEquals(listOf(DrawerEditEntry.Folder(null, "New", listOf("a", "b"))), plan.newFolders)
        assertEquals(emptyList<Int>(), plan.deletedFolderIds)
        assertEquals(emptyList<DrawerEditEntry.Folder>(), plan.updatedFolders)
    }

    @Test
    fun `plan detects a disbanded folder`() {
        DrawerEditSession.startOrResume(snapshot)
        DrawerEditSession.update { it.disband(1) }
        val plan = DrawerEditSession.buildCommitPlan()!!
        assertEquals(listOf(1), plan.deletedFolderIds)
        assertEquals(emptyList<DrawerEditEntry.Folder>(), plan.newFolders)
    }

    @Test
    fun `plan detects changed membership of an existing folder`() {
        DrawerEditSession.startOrResume(snapshot)
        DrawerEditSession.update { it.addToFolder(listOf("a"), 1) }
        val plan = DrawerEditSession.buildCommitPlan()!!
        assertEquals(listOf(DrawerEditEntry.Folder(1, "F1", listOf("x", "y", "a"))), plan.updatedFolders)
        assertEquals(emptyList<Int>(), plan.deletedFolderIds)
    }

    @Test
    fun `plan leaves untouched folders out of updatedFolders`() {
        DrawerEditSession.startOrResume(snapshot)
        DrawerEditSession.update { it.move(0, 2) }
        assertEquals(emptyList<DrawerEditEntry.Folder>(), DrawerEditSession.buildCommitPlan()!!.updatedFolders)
    }

    @Test
    fun `no session yields no plan and update is a no-op`() {
        DrawerEditSession.update { it.move(0, 1) }
        assertFalse(DrawerEditSession.isActive)
        assertNull(DrawerEditSession.buildCommitPlan())
    }
}
