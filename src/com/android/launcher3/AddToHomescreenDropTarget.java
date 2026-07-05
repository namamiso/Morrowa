package com.android.launcher3;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import com.android.launcher3.accessibility.LauncherAccessibilityDelegate;
import com.android.launcher3.allapps.ActivityAllAppsContainerView;
import com.android.launcher3.dragndrop.DragOptions;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.WorkspaceItemFactory;
import com.android.launcher3.model.data.WorkspaceItemInfo;

import app.morrowa.MorrowaPage;

/**
 * Drop target shown only while dragging an app icon out of the App Drawer (All Apps). Dropping
 * here adds the app to the Workspace page the user currently has open behind the drawer (falling
 * back to Home if that page is a Morrowa overlay page such as Habit/ToDo, which can't hold apps).
 *
 * This deliberately does NOT use {@link LauncherAccessibilityDelegate#addToWorkspace}: that
 * method defers the actual placement inside a {@code goToState(NORMAL, ..., successCallback)}
 * animation-success callback, which was silently getting cancelled by the very next
 * {@code DropTargetHandler#onDropAnimationComplete}'s own (redundant) {@code goToState(NORMAL)}
 * call fired immediately afterwards from {@link ButtonDropTarget#onDrop} -- the item never got
 * added even though the drop otherwise looked successful. It also doesn't use
 * {@code ItemInstallQueue}: that always scans screens starting from index 0, ignoring which page
 * is currently open. Instead this mirrors {@code addToWorkspace}'s DB-write/bind steps directly
 * (see {@code ModelWriter#addItemToDatabase} / {@code Launcher#inflateAndBindItemWithAnimation}),
 * targeting the resolved page explicitly and skipping the state-transition wrapper entirely.
 */
public class AddToHomescreenDropTarget extends ButtonDropTarget {

    private boolean mIsAppDrawerDrag;

    public AddToHomescreenDropTarget(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AddToHomescreenDropTarget(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        setDrawable(R.drawable.ic_add_no_shadow);
    }

    @Override
    public void onDragStart(DropTarget.DragObject dragObject, DragOptions options) {
        mIsAppDrawerDrag = dragObject.dragSource instanceof ActivityAllAppsContainerView;
        super.onDragStart(dragObject, options);
    }

    @Override
    protected void setupItemInfo(ItemInfo info) {}

    @Override
    protected boolean supportsDrop(ItemInfo info) {
        return mIsAppDrawerDrag
                && info.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPLICATION;
    }

    @Override
    public int getSupportedAccessibilityAction(ItemInfo info, View view) {
        // All Apps items already expose their own accessibility "Add to home screen" action
        // (see LauncherAccessibilityDelegate#ADD_TO_WORKSPACE); this drop target is touch-only.
        return LauncherAccessibilityDelegate.INVALID;
    }

    @Override
    public int getAccessibilityAction() {
        return LauncherAccessibilityDelegate.INVALID;
    }

    @Override
    public void onAccessibilityDrop(View view, ItemInfo item, int action) {
        // Not supported; see getSupportedAccessibilityAction().
    }

    @Override
    public void completeDrop(DragObject d) {
        ItemInfo info = d.dragInfo;
        if (!(info instanceof WorkspaceItemFactory)) {
            return;
        }
        Launcher launcher = Launcher.getLauncher(getContext());
        Workspace<?> workspace = launcher.getWorkspace();

        int targetPageIndex = resolveTargetPageIndex(workspace);
        if (targetPageIndex < 0 || targetPageIndex >= workspace.getPageCount()) {
            return;
        }
        CellLayout targetLayout = (CellLayout) workspace.getPageAt(targetPageIndex);
        int[] coordinates = new int[2];
        if (targetLayout == null
                || !targetLayout.findCellForSpan(coordinates, info.spanX, info.spanY)) {
            // MVP: no space on the resolved page. Don't fall back to scanning other pages.
            return;
        }
        int screenId = workspace.getScreenOrder().get(targetPageIndex);

        WorkspaceItemInfo workspaceItemInfo =
                ((WorkspaceItemFactory) info).makeWorkspaceItem(getContext());
        launcher.getModelWriter().addItemToDatabase(workspaceItemInfo,
                LauncherSettings.Favorites.CONTAINER_DESKTOP, screenId, coordinates[0],
                coordinates[1]);
        launcher.inflateAndBindItemWithAnimation(workspaceItemInfo);
    }

    /**
     * Returns the Workspace page index to place the dropped app on: the page currently open
     * behind the drawer, or Home if that page is a Morrowa overlay page (Habit/ToDo) that can't
     * hold apps.
     */
    private int resolveTargetPageIndex(Workspace<?> workspace) {
        int currentPageIndex = workspace.getCurrentPage();
        MorrowaPage currentMorrowaPage = workspace.getMorrowaPageForPageIndex(currentPageIndex);
        if (currentMorrowaPage != null && currentMorrowaPage.isOverlayPage()) {
            int homePageIndex = workspace.getPageIndexForMorrowaPage(MorrowaPage.HOME);
            if (homePageIndex >= 0) {
                return homePageIndex;
            }
        }
        return currentPageIndex;
    }
}
