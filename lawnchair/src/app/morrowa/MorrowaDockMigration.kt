package app.morrowa

import android.content.Context
import android.util.Log
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.Workspace
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.Executors

/**
 * Morrowa 残項目 B-2 (docs/Morrowa_残項目一覧.md): one-shot migration of legacy Dock (hotseat)
 * items into the workspace grid. Morrowa hides the hotseat entirely (PRD remove-hotseat-qsb), so
 * items a user had docked before switching to Morrowa were stranded in an invisible container.
 * This moves them into empty workspace cells — bottom rows first (the old dock area), Home screen
 * first — then forces one model reload so they appear. Runs once per install (flag below); a run
 * with no docked items also sets the flag and never looks again.
 */
object MorrowaDockMigration {

    private const val TAG = "MorrowaDockMigration"
    private const val PREFS_NAME = "morrowa_dock_migration"
    private const val KEY_DONE = "dock_migration_done"

    @JvmStatic
    fun maybeMigrate(launcher: Launcher) {
        val prefs = launcher.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return

        val hotseatContainer = launcher.hotseat?.shortcutsAndWidgets ?: return
        val items = (0 until hotseatContainer.childCount)
            .mapNotNull { hotseatContainer.getChildAt(it)?.tag as? ItemInfo }
            .filter { it.container == Favorites.CONTAINER_HOTSEAT }
        if (items.isEmpty()) {
            prefs.edit().putBoolean(KEY_DONE, true).apply()
            return
        }

        val assigned = mutableSetOf<Long>()
        var moved = 0
        for (item in items) {
            val target = findEmptyCell(launcher.workspace, assigned) ?: break
            launcher.modelWriter.moveItemInDatabase(
                item,
                Favorites.CONTAINER_DESKTOP,
                target.screenId,
                target.x,
                target.y,
            )
            assigned.add(cellKey(target.screenId, target.x, target.y))
            moved++
        }
        // Mark done even on partial migration (no free cells left) — leftover items simply stay
        // invisible, exactly the pre-migration state; never loop retrying.
        prefs.edit().putBoolean(KEY_DONE, true).apply()
        Log.i(TAG, "Migrated $moved/${items.size} dock items into the workspace grid")
        if (moved > 0) {
            // Posted: this runs from finishBindingItems — reload must not reenter the bind.
            Executors.MAIN_EXECUTOR.execute { launcher.model.forceReload() }
        }
    }

    private data class Target(val screenId: Int, val x: Int, val y: Int)

    /**
     * The first free cell, scanning screens in swipe order (Morrowa Habit/ToDo screens skipped)
     * and each screen's rows bottom-up — the old dock rows fill first. [assigned] holds cells
     * already claimed in this run, since CellLayout occupancy only updates after the reload.
     */
    private fun findEmptyCell(workspace: Workspace<*>, assigned: Set<Long>): Target? {
        val order = workspace.screenOrder
        for (i in 0 until order.size()) {
            val screenId = order.get(i)
            if (screenId == Workspace.MORROWA_HABIT_SCREEN_ID ||
                screenId == Workspace.MORROWA_TODO_SCREEN_ID
            ) {
                continue
            }
            val screen = workspace.getScreenWithId(screenId) ?: continue
            for (y in screen.countY - 1 downTo 0) {
                for (x in 0 until screen.countX) {
                    if (!screen.isOccupied(x, y) && cellKey(screenId, x, y) !in assigned) {
                        return Target(screenId, x, y)
                    }
                }
            }
        }
        return null
    }

    private fun cellKey(screenId: Int, x: Int, y: Int): Long =
        (screenId.toLong() shl 32) or (x.toLong() shl 16) or y.toLong()
}
