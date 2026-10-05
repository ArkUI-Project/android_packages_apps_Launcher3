/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.quickstep.util;

import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;

import android.graphics.Rect;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;

import androidx.annotation.Nullable;

import com.android.quickstep.RemoteAnimationTargets;

/** Fits the original status bar into an app's changing crop, in the same surface transaction. */
public final class SystemBarFollowAnimation {
    private SystemBarFollowAnimation() { }

    public static SurfaceTransaction.SurfaceProperties forTarget(SurfaceTransaction transaction,
            RemoteAnimationTargets targets, RemoteAnimationTarget app) {
        if (!app.leash.isValid() || getTarget(targets, app) == null) {
            return transaction.forSurface(app.leash);
        }
        return transaction.new SurfaceProperties(app.leash) {
            @Override public SurfaceTransaction.SurfaceProperties setWindowCrop(Rect crop) {
                super.setWindowCrop(crop);
                apply(targets, app, transaction.getTransaction(), crop);
                return this;
            }
        };
    }

    public static void apply(RemoteAnimationTargets targets, RemoteAnimationTarget app,
            SurfaceControl.Transaction transaction, Rect crop) {
        RemoteAnimationTarget bar = getTarget(targets, app);
        if (bar == null || !bar.leash.isValid() || crop.isEmpty() || app.rotationChange != 0) return;
        int sourceWidth = bar.screenSpaceBounds.width();
        if (sourceWidth <= 0) return;
        float scale = crop.width() / (float) sourceWidth;
        // The container remains a child of the app and inherits its current matrix/alpha. Only
        // its local transform changes: a centered icon crop otherwise cuts every edge glyph out.
        transaction.setMatrix(bar.leash, scale, 0, 0, scale)
                .setPosition(bar.leash, crop.left, crop.top);
    }

    public static @Nullable SurfaceControl getSurface(RemoteAnimationTargets targets,
            RemoteAnimationTarget app) {
        RemoteAnimationTarget bar = getTarget(targets, app);
        return bar == null ? null : bar.leash;
    }

    private static @Nullable RemoteAnimationTarget getTarget(RemoteAnimationTargets targets,
            RemoteAnimationTarget app) {
        if (targets == null || targets.nonApps == null || app == null) return null;
        for (RemoteAnimationTarget target : targets.nonApps) {
            if (target.windowType == TYPE_STATUS_BAR && target.taskId == app.taskId) return target;
        }
        return null;
    }
}
