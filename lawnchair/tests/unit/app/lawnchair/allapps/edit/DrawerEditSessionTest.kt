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
    fun `commitKeys maps entries to namespaced keys in display order`() {
        DrawerEditSession.startOrResume(snapshot)
        DrawerEditSession.update { it.move(2, 0) }
        assertEquals(
            listOf("app:b", "app:a", "folder:1"),
            DrawerEditSession.commitKeys(),
        )
    }

    @Test
    fun `commitKeys skips session-created folders without an id`() {
        DrawerEditSession.startOrResume(
            listOf(DrawerEditEntry.App("a"), DrawerEditEntry.Folder(null, "New", listOf("p", "q"))),
        )
        assertEquals(listOf("app:a"), DrawerEditSession.commitKeys())
    }

    @Test
    fun `no session is a no-op`() {
        DrawerEditSession.update { it.move(0, 1) }
        assertFalse(DrawerEditSession.isActive)
        assertEquals(emptyList<String>(), DrawerEditSession.commitKeys())
    }
}
