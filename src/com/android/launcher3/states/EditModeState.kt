/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.launcher3.states

import android.content.Context
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState
import com.android.launcher3.LauncherUiState
import com.android.launcher3.logging.StatsLogManager
import com.android.launcher3.views.ActivityContext

/** Definition for Edit Mode state used for home gardening multi-select */
class EditModeState(id: Int) : LauncherState(id, StatsLogManager.LAUNCHER_STATE_HOME, STATE_FLAGS) {

    companion object {
        const val DEPTH_15_PERCENT = 0.15f

        private val STATE_FLAGS =
            (FLAG_MULTI_PAGE or
                FLAG_DISABLE_RESTORE or
                FLAG_WORKSPACE_ICONS_CAN_BE_DRAGGED or FLAG_HAS_SYS_UI_SCRIM)
    }

    override fun getTransitionDuration(context: ActivityContext, isToState: Boolean) = 260

    override fun <T> getDepthUnchecked(context: T): Float where T : Context?, T : ActivityContext? {
        return DEPTH_15_PERCENT
    }

    override fun getWorkspaceScaleAndTranslation(launcher: Launcher): ScaleAndTranslation {
        // Keep the first row below Done, with the dock area reserved for editing actions.
        return ScaleAndTranslation(0.84f, 0f, launcher.resources.displayMetrics.density * 10f)
    }

    override fun getHotseatScaleAndTranslation(launcher: Launcher): ScaleAndTranslation {
        return ScaleAndTranslation(0.84f, 0f, -launcher.resources.displayMetrics.density * 36f)
    }

    override fun getWorkspaceBackgroundAlpha(launcher: Launcher): Float {
        return 0f
    }

    override fun getVisibleElements(launcherUiState: LauncherUiState) = WORKSPACE_PAGE_INDICATOR

    override fun onLeavingState(launcher: Launcher?, toState: LauncherState?) {
        // cleanup any changes to workspace
    }
}
