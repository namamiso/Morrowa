/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.states;

import static com.android.launcher3.logging.StatsLogManager.LAUNCHER_STATE_ALLAPPS;

import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherState;
import com.android.launcher3.views.ActivityContext;

/**
 * Morrowa: dedicated state for the App Drawer's own drag/edit mode (reorder, folder create,
 * folder insert), kept separate from Home's {@link SpringLoadedState}.
 *
 * Phase A: not yet wired to any state transition. Visually equivalent to
 * {@code LauncherState.ALL_APPS} (drawer fully open) so that entering this state has no
 * observable effect until later phases add drag-specific behavior.
 *
 * Deliberately does NOT set {@code FLAG_CLOSE_POPUPS}: this state is entered from
 * {@code Workspace#onDragStart} while a drag is already in progress. {@code FLAG_CLOSE_POPUPS}
 * would force {@code AbstractFloatingView#closeAllOpenViews} immediately, short-circuiting the
 * long-press shortcuts popup's own {@code PreDragCondition}/{@code animateClose()} sequence
 * (see {@code PopupContainerWithArrow}) that is responsible for restoring the original icon's
 * visibility once the real drag begins. Home's {@link SpringLoadedState} — entered from the same
 * call site for Workspace-origin drags — omits this flag for the same reason.
 */
public class DrawerSpringLoadedState extends LauncherState {

    private static final int STATE_FLAGS =
            FLAG_WORKSPACE_INACCESSIBLE | FLAG_HOTSEAT_INACCESSIBLE;

    public DrawerSpringLoadedState(int id) {
        super(id, LAUNCHER_STATE_ALLAPPS, STATE_FLAGS);
    }

    @Override
    public int getTransitionDuration(ActivityContext context, boolean isToState) {
        return 150;
    }

    @Override
    public float getVerticalProgress(Launcher launcher) {
        return 0f;
    }

    @Override
    public int getVisibleElements(Launcher launcher) {
        return ALL_APPS_CONTENT | FLOATING_SEARCH_BAR;
    }
}
