package app.lawnchair.allapps.edit

/**
 * Morrowa v2 P2 (docs/Morrowa_AppDrawer_編集モードv2_要件設計.md §3.2): process-scoped holder for
 * the in-progress edit session. The session lives purely in memory — the DB is written exactly
 * once, by [commitKeys]' caller, when the user taps 完了 — so there is no optimistic state and no
 * racing writer (§10.42 原則3).
 *
 * Surviving activity recreation (rotation, theme change) falls out of process scope: the overlay
 * view dies with the activity but the draft stays here, and the next edit entry resumes it as
 * long as the drawer's content (the key set) hasn't changed in between (Q3: 回転は継続、プロセス死は破棄).
 */
object DrawerEditSession {

    var model: DrawerOrderModel? = null
        private set

    private var initial: DrawerOrderModel? = null

    val isActive: Boolean get() = model != null

    /** True when the draft differs from the snapshot the session started from. */
    val isDirty: Boolean get() = model != initial

    /**
     * Starts a session from [snapshot], or resumes the existing draft when one is active and
     * still describes the same set of entries (same keys — apps installed/removed or folders
     * changed outside the session invalidate the draft).
     */
    fun startOrResume(snapshot: List<DrawerEditEntry>) {
        val current = model
        if (current != null && sameKeys(current.entries, snapshot)) return
        val m = DrawerOrderModel(snapshot)
        initial = m
        model = m
    }

    /** Applies [op] to the draft. No-op if no session is active. */
    fun update(op: (DrawerOrderModel) -> DrawerOrderModel) {
        model = model?.let(op)
    }

    /** Ends the session, discarding the draft. */
    fun clear() {
        model = null
        initial = null
    }

    /**
     * The DrawerOrder keys of the current draft in display order, for the commit write
     * (rank = index). Folders created in-session (folderId == null) can't exist until P3;
     * they are skipped defensively rather than crashing the commit.
     */
    fun commitKeys(): List<String> = model?.entries.orEmpty().mapNotNull { entry ->
        when (entry) {
            is DrawerEditEntry.App -> DrawerOrderKeys.app(entry.key)
            is DrawerEditEntry.Folder -> entry.folderId?.let { DrawerOrderKeys.folder(it) }
        }
    }

    private fun sameKeys(a: List<DrawerEditEntry>, b: List<DrawerEditEntry>): Boolean {
        fun keysOf(entries: List<DrawerEditEntry>): Set<String> = entries.map { entry ->
            when (entry) {
                is DrawerEditEntry.App -> DrawerOrderKeys.app(entry.key)
                is DrawerEditEntry.Folder -> "folder:${entry.folderId}"
            }
        }.toSet()
        return keysOf(a) == keysOf(b)
    }
}
