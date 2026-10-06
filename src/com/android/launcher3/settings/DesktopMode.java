/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.settings;

import android.content.Context;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.LauncherSettings;
import com.android.launcher3.model.data.CollectionInfo;
import com.android.launcher3.model.data.ItemInfo;

/** Standard mode keeps application launchers on the workspace and in its folders. */
public final class DesktopMode {
    private DesktopMode() {}

    public static boolean isStandard(Context context) {
        return LauncherPrefs.STANDARD_DESKTOP.get(context);
    }

    public static boolean canRemove(Context context, ItemInfo item) {
        return !isStandard(context)
                || (item.itemType != LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
                        && !(item instanceof CollectionInfo));
    }
}
