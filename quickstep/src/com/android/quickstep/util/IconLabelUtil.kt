/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.quickstep.util

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.os.UserHandle
import com.android.launcher3.Utilities

object IconLabelUtil {
    private fun appTwinLabel(context: Context, info: ActivityInfo, userId: Int): String? {
        if (!Utilities.ATLEAST_V || userId == UserHandle.myUserId()) return null
        val user = UserHandle.of(userId)
        val launcher = context.getSystemService(LauncherApps::class.java) ?: return null
        return try {
            if (launcher.getLauncherUserInfo(user)?.userType !=
                "org.arkui.usertype.profile.APP_TWIN") return null
            launcher.resolveActivity(Intent().setComponent(info.componentName), user)?.label?.toString()
        } catch (_: RuntimeException) {
            // A profile can disappear while its recent-task icon is being loaded.
            null
        }
    }

    @JvmStatic
    fun getActivityLabel(context: Context, info: ActivityInfo, userId: Int): String =
        appTwinLabel(context, info, userId) ?: Utilities.trim(info.loadLabel(context.packageManager))

    @JvmStatic
    @JvmOverloads
    fun getBadgedContentDescription(
        context: Context,
        info: ActivityInfo,
        userId: Int,
        taskDescription: ActivityManager.TaskDescription? = null,
    ): String {
        appTwinLabel(context, info, userId)?.let { return it }
        val packageManager = context.packageManager
        var taskLabel = taskDescription?.let { Utilities.trim(it.label) }
        if (taskLabel.isNullOrEmpty()) {
            taskLabel = Utilities.trim(info.loadLabel(packageManager))
        }

        val applicationLabel = Utilities.trim(info.applicationInfo.loadLabel(packageManager))
        val badgedApplicationLabel =
            if (userId != UserHandle.myUserId())
                packageManager
                    .getUserBadgedLabel(applicationLabel, UserHandle.of(userId))
                    .toString()
            else applicationLabel
        return if (applicationLabel == taskLabel) badgedApplicationLabel
        else "$badgedApplicationLabel $taskLabel"
    }
}
