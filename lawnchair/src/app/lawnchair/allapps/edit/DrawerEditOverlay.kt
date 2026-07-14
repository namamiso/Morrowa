package app.lawnchair.allapps.edit

import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import app.lawnchair.LawnchairLauncher
import app.lawnchair.data.AppDatabase
import app.lawnchair.data.appdrawer.DrawerOrderEntity
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.R
import com.android.launcher3.allapps.BaseAllAppsAdapter
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.Themes
import com.android.launcher3.views.BaseDragLayer
import kotlinx.coroutines.launch

/**
 * Morrowa v2 P2 (docs/Morrowa_AppDrawer_編集モードv2_要件設計.md §3.1): the App Drawer edit-mode
 * surface — a Morrowa-owned full-screen floating view layered over ALL_APPS in the DragLayer.
 * It owns its RecyclerView + [ItemTouchHelper]; while it is open, the AOSP AllApps view, its
 * adapter, the DragController and the Launcher state machine are never touched (§10.42 原則1/2).
 * Reordering mutates only [DrawerEditSession]'s in-memory draft; 完了 commits the whole order to
 * the DrawerOrder table in one transaction and the drawer re-renders through its read-only flow.
 */
class DrawerEditOverlay(
    private val launcher: LawnchairLauncher,
) : AbstractFloatingView(launcher, null) {

    private val adapter = EditAdapter()
    private lateinit var recyclerView: RecyclerView

    init {
        orientation = VERTICAL
        setBackgroundColor(Themes.getAttrColor(launcher, android.R.attr.colorBackground))
        buildHeader()
        buildGrid()
        val insets: Rect = launcher.deviceProfile.insets
        setPadding(insets.left, insets.top, insets.right, insets.bottom)
    }

    private fun buildHeader() {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val header = LinearLayout(launcher).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val textColor = Themes.getAttrColor(launcher, android.R.attr.textColorPrimary)

        val cancel = TextView(launcher).apply {
            text = resources.getText(R.string.morrowa_drawer_edit_cancel)
            setTextColor(textColor)
            textSize = 16f
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            setOnClickListener { attemptCancel() }
        }
        val title = TextView(launcher).apply {
            text = resources.getText(R.string.morrowa_drawer_edit_title)
            setTextColor(textColor)
            textSize = 18f
            gravity = Gravity.CENTER
        }
        val done = TextView(launcher).apply {
            text = resources.getText(R.string.morrowa_drawer_edit_done)
            setTextColor(textColor)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            setOnClickListener { commitAndClose() }
        }
        header.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun buildGrid() {
        val columns = launcher.deviceProfile.numShownAllAppsColumns.takeIf { it > 0 } ?: DEFAULT_COLUMNS
        recyclerView = RecyclerView(launcher).apply {
            layoutManager = GridLayoutManager(launcher, columns)
            adapter = this@DrawerEditOverlay.adapter
            clipToPadding = false
        }
        ItemTouchHelper(TouchCallback()).attachToRecyclerView(recyclerView)
        addView(recyclerView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    // ---- open/close ----

    private fun markOpen() {
        mIsOpen = true
    }

    private fun attemptCancel() {
        if (!DrawerEditSession.isDirty) {
            DrawerEditSession.clear()
            close(true)
            return
        }
        AlertDialog.Builder(launcher)
            .setTitle(R.string.morrowa_drawer_edit_discard_title)
            .setMessage(R.string.morrowa_drawer_edit_discard_message)
            .setNegativeButton(R.string.morrowa_drawer_edit_cancel, null)
            .setPositiveButton(R.string.morrowa_drawer_edit_discard) { _, _ ->
                DrawerEditSession.clear()
                close(true)
            }
            .show()
    }

    private fun commitAndClose() {
        val keys = DrawerEditSession.commitKeys()
        if (keys.isNotEmpty()) {
            val entities = keys.mapIndexed { index, key -> DrawerOrderEntity(key = key, rank = index) }
            launcher.lifecycleScope.launch {
                AppDatabase.INSTANCE.get(launcher).drawerOrderDao().replaceAll(entities)
            }
        }
        DrawerEditSession.clear()
        close(true)
    }

    override fun onBackInvoked() {
        attemptCancel()
    }

    override fun handleClose(animate: Boolean) {
        // Session is deliberately NOT cleared here: a close forced from outside (state change,
        // activity recreation) keeps the draft, and the next edit entry resumes it (Q3). The
        // explicit 完了/キャンセル paths clear it before closing.
        launcher.dragLayer.removeView(this)
    }

    override fun isOfType(type: Int): Boolean = type and TYPE_COMPOSE_VIEW != 0

    override fun onControllerInterceptTouchEvent(ev: MotionEvent?): Boolean = false

    // ---- data ----

    private fun entries(): List<DrawerEditEntry> = DrawerEditSession.model?.entries.orEmpty()

    private fun resolveApp(key: String): AppInfo? = runCatching {
        launcher.appsView.appsStore.getApp(ComponentKey.fromString(key))
    }.getOrNull()

    // ---- adapter ----

    private inner class EditAdapter : RecyclerView.Adapter<CellHolder>() {

        init {
            setHasStableIds(true)
        }

        override fun getItemCount(): Int = entries().size

        override fun getItemId(position: Int): Long = when (val entry = entries()[position]) {
            is DrawerEditEntry.App -> DrawerOrderKeys.app(entry.key).hashCode().toLong()
            is DrawerEditEntry.Folder -> ("folder:${entry.folderId}:${entry.name}").hashCode().toLong()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CellHolder {
            val density = parent.resources.displayMetrics.density
            val iconSize = launcher.deviceProfile.allAppsProfile.iconSizePx
            val cell = LinearLayout(parent.context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val padV = (8 * density).toInt()
                setPadding(0, padV, 0, padV)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            val iconFrame = FrameLayout(parent.context)
            val iconView = ImageView(parent.context)
            val folderView = FolderPreviewView(parent.context)
            iconFrame.addView(iconView, FrameLayout.LayoutParams(iconSize, iconSize))
            iconFrame.addView(folderView, FrameLayout.LayoutParams(iconSize, iconSize))
            cell.addView(iconFrame, LinearLayout.LayoutParams(iconSize, iconSize))
            val label = TextView(parent.context).apply {
                setTextColor(Themes.getAttrColor(launcher, android.R.attr.textColorPrimary))
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                val padTop = (4 * density).toInt()
                setPadding((4 * density).toInt(), padTop, (4 * density).toInt(), 0)
            }
            cell.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return CellHolder(cell, iconView, folderView, label)
        }

        override fun onBindViewHolder(holder: CellHolder, position: Int) {
            when (val entry = entries()[position]) {
                is DrawerEditEntry.App -> {
                    val info = resolveApp(entry.key)
                    holder.iconView.visibility = VISIBLE
                    holder.folderView.visibility = GONE
                    holder.iconView.setImageDrawable(info?.bitmap?.newIcon(launcher))
                    holder.label.text = info?.title ?: ""
                }
                is DrawerEditEntry.Folder -> {
                    holder.iconView.visibility = GONE
                    holder.folderView.visibility = VISIBLE
                    holder.folderView.setMembers(
                        entry.members.take(4).mapNotNull { key -> resolveApp(key)?.bitmap?.newIcon(launcher) },
                    )
                    holder.label.text = entry.name
                }
            }
        }
    }

    private class CellHolder(
        root: View,
        val iconView: ImageView,
        val folderView: FolderPreviewView,
        val label: TextView,
    ) : RecyclerView.ViewHolder(root)

    // ---- drag & drop (ItemTouchHelper: platform-standard drag, §10.42 原則2) ----

    private inner class TouchCallback : ItemTouchHelper.Callback() {

        override fun isLongPressDragEnabled(): Boolean = true

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int = makeMovementFlags(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0,
        )

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            DrawerEditSession.update { it.move(from, to) }
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                viewHolder?.itemView?.animate()?.scaleX(DRAG_SCALE)?.scaleY(DRAG_SCALE)?.setDuration(100)?.start()
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
        }
    }

    /** Simple 2x2 mini-icon folder preview — deliberately NOT the AOSP FolderIcon (§10.42 原則1). */
    private class FolderPreviewView(context: android.content.Context) : View(context) {

        private var members: List<Drawable> = emptyList()
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33808080
        }
        private val bgRect = RectF()

        fun setMembers(drawables: List<Drawable>) {
            members = drawables
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            bgRect.set(0f, 0f, w, h)
            canvas.drawRoundRect(bgRect, w / 4f, h / 4f, bgPaint)
            val inset = (w * 0.12f).toInt()
            val mini = ((w - inset * 3) / 2).toInt()
            members.forEachIndexed { index, drawable ->
                val col = index % 2
                val row = index / 2
                val left = inset + col * (mini + inset)
                val top = inset + row * (mini + inset)
                drawable.setBounds(left, top, left + mini, top + mini)
                drawable.draw(canvas)
            }
        }
    }

    companion object {
        private const val DEFAULT_COLUMNS = 4
        private const val DRAG_SCALE = 1.1f

        /**
         * Opens the edit overlay over the current ALL_APPS. The session snapshot is taken
         * synchronously from the personal list's current adapter items — the same sequence the
         * user is looking at — so there is no async seed and no race (§10.42 原則3).
         */
        @JvmStatic
        fun show(launcher: LawnchairLauncher) {
            val snapshot = buildSnapshot(launcher)
            if (snapshot.isEmpty()) {
                Toast.makeText(launcher, R.string.all_apps_loading_message, Toast.LENGTH_SHORT).show()
                return
            }
            DrawerEditSession.startOrResume(snapshot)
            val overlay = DrawerEditOverlay(launcher)
            launcher.dragLayer.addView(
                overlay,
                BaseDragLayer.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            overlay.markOpen()
        }

        private fun buildSnapshot(launcher: LawnchairLauncher): List<DrawerEditEntry> {
            val items = launcher.appsView.personalAppList.adapterItems
            return items.mapNotNull { item ->
                when (item.viewType) {
                    BaseAllAppsAdapter.VIEW_TYPE_ICON -> item.itemInfo?.let { info ->
                        DrawerEditEntry.App(info.toComponentKey().toString())
                    }
                    BaseAllAppsAdapter.VIEW_TYPE_FOLDER -> item.folderInfo?.let { folder ->
                        DrawerEditEntry.Folder(
                            folderId = folder.id.takeIf { it > 0 },
                            name = folder.title?.toString().orEmpty(),
                            members = folder.getContents().mapNotNull { content ->
                                (content as? AppInfo)?.toComponentKey()?.toString()
                            },
                        )
                    }
                    else -> null
                }
            }
        }
    }
}
