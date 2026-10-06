/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.model.tasks;

import android.content.Context;
import android.util.Pair;

import androidx.annotation.NonNull;

import com.android.launcher3.LauncherModel.ModelUpdateTask;
import com.android.launcher3.LauncherSettings;
import com.android.launcher3.lineage.trust.HiddenAppsFilter;
import com.android.launcher3.model.AllAppsList;
import com.android.launcher3.model.BgDataModel;
import com.android.launcher3.model.ModelTaskController;
import com.android.launcher3.model.WorkspaceItemSpaceFinder;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.ItemInfoWithIcon;
import com.android.launcher3.pm.UserCache;
import com.android.launcher3.settings.DesktopMode;
import com.android.launcher3.util.ComponentKey;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Adds only missing, visible launchers without moving existing icons, folders or widgets. */
public final class SyncStandardDesktopTask implements ModelUpdateTask {
    private final WorkspaceItemSpaceFinder mSpaceFinder;

    public SyncStandardDesktopTask(WorkspaceItemSpaceFinder spaceFinder) {
        mSpaceFinder = spaceFinder;
    }

    @Override
    public void execute(@NonNull ModelTaskController controller, @NonNull BgDataModel dataModel,
            @NonNull AllAppsList apps) {
        Context context = controller.getContext();
        if (!DesktopMode.isStandard(context)) return;

        Set<ComponentKey> present = new HashSet<>();
        synchronized (dataModel) {
            for (ItemInfo item : dataModel.itemsIdMap) {
                if (item.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
                        && item.getTargetComponent() != null) {
                    present.add(new ComponentKey(item.getTargetComponent(), item.user));
                }
            }
        }

        // AllApps is filtered already; re-check the privacy filter at execution time as well.
        HiddenAppsFilter filter = new HiddenAppsFilter(context);
        UserCache users = UserCache.INSTANCE.get(context);
        List<AppInfo> sortedApps = new ArrayList<>(apps.data);
        Collator collator = Collator.getInstance();
        sortedApps.sort((a, b) -> collator.compare(a.title.toString(), b.title.toString()));
        List<Pair<ItemInfo, Object>> missing = new ArrayList<>();
        for (AppInfo app : sortedApps) {
            if (app.componentName == null || !filter.shouldShowApp(app.componentName)
                    || users.getUserInfo(app.user).isPrivate()
                    || (app.runtimeStatusFlags & ItemInfoWithIcon.FLAG_NOT_PINNABLE) != 0
                    || app.isArchived()) {
                continue;
            }
            if (present.add(new ComponentKey(app.componentName, app.user))) {
                missing.add(Pair.create(app, null));
            }
        }
        new AddWorkspaceItemsTask(missing, mSpaceFinder).execute(controller, dataModel, apps);
    }
}
