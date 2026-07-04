package com.android.launcher3;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import com.android.launcher3.accessibility.LauncherAccessibilityDelegate;
import com.android.launcher3.allapps.ActivityAllAppsContainerView;
import com.android.launcher3.dragndrop.DragOptions;
import com.android.launcher3.model.data.ItemInfo;

/**
 * Drop target shown only while dragging an app icon out of the App Drawer (All Apps). Dropping
 * here adds the app to the current Workspace, reusing the same placement path as
 * {@link LauncherAccessibilityDelegate#addToWorkspace} (already used by the widget sheet's
 * "tap to add" button and by the All Apps accessibility "Add to home screen" action).
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
        Launcher launcher = Launcher.getLauncher(getContext());
        launcher.getAccessibilityDelegate().addToWorkspace(d.dragInfo, /* accessibility= */ false,
                null);
    }
}
