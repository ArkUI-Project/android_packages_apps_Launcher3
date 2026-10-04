/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.quickstep.util;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.util.RotationUtils.deltaRotation;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.RemoteAnimationTarget;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.RemoveAnimationSettingsTracker;
import com.android.launcher3.Utilities;
import com.android.launcher3.anim.DesktopAnimationSettings;

/** Rotation of the app plane, independent of the display's fixed-rotation transform. */
public final class LandscapeAppAnimation {
    // Match the reference's short expansion and longer, gently settling tail.
    public static final Interpolator OPEN_INTERPOLATOR = new PathInterpolator(0.22f, 0.52f, 0.08f, 1f);

    private LandscapeAppAnimation() { }

    private static boolean isEligible(Context context, DeviceProfile profile,
            RemoteAnimationTarget target) {
        return target != null && target.taskInfo != null && !target.isTranslucent
                && !target.screenSpaceBounds.isEmpty()
                && target.windowConfiguration.getWindowingMode() == WINDOWING_MODE_FULLSCREEN
                && !profile.getDeviceProperties().isLandscape()
                && !profile.getDeviceProperties().isTablet()
                && (target.taskInfo.configuration.orientation == ORIENTATION_LANDSCAPE
                        || isFixedLandscape(target)
                        || target.screenSpaceBounds.width() > target.screenSpaceBounds.height()
                        || (target.startBounds != null
                                && target.startBounds.width() > target.startBounds.height()))
                && LauncherPrefs.get(context).get(LauncherPrefs.LANDSCAPE_APP_ANIMATION)
                && ValueAnimator.areAnimatorsEnabled()
                && !RemoveAnimationSettingsTracker.INSTANCE.get(context).isRemoveAnimationEnabled();
    }

    /** Rotation in the app leash's coordinate space; both landscape directions are supported. */
    public static int getOpeningCoordinateRotation(DeviceProfile profile,
            RemoteAnimationTarget target, int displayRotationChange) {
        if (displayRotationChange != 0 || target == null
                || profile.getDeviceProperties().isLandscape()
                || target.screenSpaceBounds.width() <= target.screenSpaceBounds.height()) {
            return displayRotationChange;
        }
        // A fixed-rotation app buffer can be landscape even when the display transition has
        // no rotation change. Layout the opening crop in that buffer's coordinate space.
        final int delta = deltaRotation(profile.getDeviceProperties().getRotationHint(),
                target.windowConfiguration.getRotation());
        return Math.floorMod(resolveLandscapeDelta(target, delta), 4);
    }

    public static float getOpeningRotation(Context context, DeviceProfile profile,
            RemoteAnimationTarget target, int rotationChange) {
        if (!isEligible(context, profile, target)) {
            return 0f;
        }
        // Fixed-rotation launches can have rotationChange == 0: Shell rotates the parent
        // leash, while the task configuration already describes the destination orientation.
        int delta = rotationChange;
        if ((delta & 1) == 0) {
            delta = deltaRotation(profile.getDeviceProperties().getRotationHint(),
                    target.windowConfiguration.getRotation());
        }
        return quarterTurn(resolveLandscapeDelta(target, delta));
    }

    public static float getClosingRotation(Context context, DeviceProfile profile,
            RemoteAnimationTarget target, int homeRotation) {
        if (!isEligible(context, profile, target)) {
            return 0f;
        }
        int delta = deltaRotation(homeRotation, target.windowConfiguration.getRotation());
        // Shell may already report the destination rotation on the closing task.
        if ((delta & 1) == 0 && (target.rotationChange & 1) != 0) {
            delta = -target.rotationChange;
        }
        return quarterTurn(resolveLandscapeDelta(target, delta));
    }

    private static boolean isFixedLandscape(RemoteAnimationTarget target) {
        final ActivityInfo info = target.taskInfo.topActivityInfo;
        if (info == null) {
            return false;
        }
        return info.screenOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                || info.screenOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                || info.screenOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                || info.screenOrientation == ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE;
    }

    private static int resolveLandscapeDelta(RemoteAnimationTarget target, int delta) {
        if ((delta & 1) != 0) {
            return delta;
        }
        final ActivityInfo info = target.taskInfo.topActivityInfo;
        return info != null
                && info.screenOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                ? 3 : 1;
    }

    /** Fallback targets are always in home coordinates, even during a landscape gesture. */
    public static RectF getDefaultHomeTarget(DeviceProfile profile) {
        final float halfIconSize = profile.getWorkspaceIconProfile().getIconSizePx() / 2f;
        final float x = profile.getDeviceProperties().getAvailableWidthPx() / 2f;
        final float y = profile.getDeviceProperties().getAvailableHeightPx()
                - profile.hotseatBarSizePx;
        return new RectF(x - halfIconSize, y - halfIconSize,
                x + halfIconSize, y + halfIconSize);
    }

    private static float quarterTurn(int delta) {
        return switch (Math.floorMod(delta, 4)) {
            case 1 -> -90f;
            case 3 -> 90f;
            default -> 0f;
        };
    }

    public static float boundProgress(float progress) {
        return Utilities.boundToRange(progress, 0f, 1f);
    }

    public static float normalizeRotation(float degrees) {
        return ((degrees + 180f) % 360f + 360f) % 360f - 180f;
    }

    /** Rotates after placement, around the visible crop's center rather than the screen origin. */
    public static void applyRotation(Matrix matrix, Rect crop, float degrees, RectF scratch) {
        scratch.set(crop);
        matrix.mapRect(scratch);
        matrix.postRotate(degrees, scratch.centerX(), scratch.centerY());
    }

    public static float getMatrixRotation(Matrix matrix) {
        return getMatrixRotation(matrix, new float[9]);
    }

    public static float getMatrixRotation(Matrix matrix, float[] values) {
        matrix.getValues(values);
        return (float) Math.toDegrees(Math.atan2(values[Matrix.MSKEW_Y], values[Matrix.MSCALE_X]));
    }

    /** Preserve the gesture's starting transform and crop uniformly while folding into an icon. */
    public static void applyClosingTransform(Matrix matrix, RectF source, RectF destination,
            float startRotation, float rotation, float progress, Rect crop,
            DesktopAnimationSettings motion, float coordinateRotation) {
        // Crop axes follow the buffer/display orientation, never a partially completed app
        // rotation. Rounding that animated angle to a quarter-turn flips the crop at 45°.
        final boolean swapAxes = (Math.round(coordinateRotation / 90f) & 1) != 0;
        final float width = swapAxes ? destination.height() : destination.width();
        final float height = swapAxes ? destination.width() : destination.height();
        final float scale = Math.max(width / source.width(), height / source.height());
        final float halfWidth = Math.min(source.width(), width / scale) / 2f;
        final float halfHeight = Math.min(source.height(), height / scale) / 2f;
        crop.set(Math.round(source.centerX() - halfWidth),
                Math.round(source.centerY() - halfHeight),
                Math.round(source.centerX() + halfWidth),
                Math.round(source.centerY() + halfHeight));
        matrix.setTranslate(-source.centerX(), -source.centerY());
        matrix.postScale(scale, scale);
        matrix.postRotate(startRotation + rotation * motion.rotationProgress(progress));
        matrix.postTranslate(destination.centerX(), destination.centerY());
    }
}
