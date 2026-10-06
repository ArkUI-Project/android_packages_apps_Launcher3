/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.folder;

import android.content.Context;
import android.content.SharedPreferences;

import com.android.launcher3.model.data.ItemInfo;

import java.util.List;

/** Local launch counts affect only the optional closed-folder preview. */
public final class FolderPreviewUsage {
    private FolderPreviewUsage() { }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("folder_preview_usage", Context.MODE_PRIVATE);
    }

    private static String key(ItemInfo item) {
        return item.getTargetComponent() == null ? null
                : item.user.getIdentifier() + ":" + item.getTargetComponent().flattenToString();
    }

    public static void record(Context context, ItemInfo item) {
        if (item == null) return;
        String key = key(item);
        if (key == null) return;
        SharedPreferences preferences = preferences(context);
        int count = preferences.getInt(key, 0);
        preferences.edit().putInt(key, count == Integer.MAX_VALUE ? count : count + 1).apply();
    }

    static void sort(Context context, List<ItemInfo> items) {
        SharedPreferences preferences = preferences(context);
        // TimSort is stable: equal counts retain the user's folder order.
        items.sort((first, second) -> Integer.compare(count(preferences, second),
                count(preferences, first)));
    }

    private static int count(SharedPreferences preferences, ItemInfo item) {
        String key = key(item);
        return key == null ? 0 : preferences.getInt(key, 0);
    }
}
