/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.anim;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import com.android.launcher3.LauncherFiles;
import com.android.launcher3.Utilities;

import java.util.HashMap;
import java.util.Map;

/** One validated schema for the Settings bridge and immutable, per-animation motion snapshots. */
public final class DesktopAnimationSettings {
    public static final String PREFIX = "pref_motion_";
    private static SharedPreferences sPreferences;
    private static DesktopAnimationSettings sSnapshot;
    // SharedPreferences keeps a weak listener reference; keep the listener for the process lifetime.
    private static final SharedPreferences.OnSharedPreferenceChangeListener sListener =
            (preferences, key) -> {
                if (key == null || key.startsWith(PREFIX)) {
                    synchronized (DesktopAnimationSettings.class) {
                        sSnapshot = null;
                    }
                }
            };

    public record Parameter(String name, int defaultValue, int min, int max, int step,
            String unit) {
        public String key() { return PREFIX + name; }

        int read(Map<String, ?> values) {
            Object value = values.get(key());
            return Utilities.boundToRange(value instanceof Integer ? (int) value : defaultValue,
                    min, max);
        }
    }

    public static final Parameter[] PARAMETERS = {
            new Parameter("open_duration", 450, 200, 1000, 10, "ms"),
            new Parameter("recents_duration", 420, 200, 1000, 10, "ms"),
            new Parameter("home_duration", 400, 200, 1000, 10, "ms"),
            new Parameter("curve_x1", 26, 0, 100, 1, "%"),
            new Parameter("curve_y1", 100, 0, 100, 1, "%"),
            new Parameter("curve_x2", 32, 0, 100, 1, "%"),
            new Parameter("curve_y2", 100, 0, 100, 1, "%"),
            new Parameter("height_curve_x1", 33, 0, 100, 1, "%"),
            new Parameter("height_curve_y1", 75, 0, 100, 1, "%"),
            new Parameter("height_curve_x2", 20, 0, 100, 1, "%"),
            new Parameter("height_curve_y2", 100, 0, 100, 1, "%"),
            new Parameter("home_curve_x1", 34, 0, 100, 1, "%"),
            new Parameter("home_curve_y1", 49, 0, 100, 1, "%"),
            new Parameter("home_curve_x2", 26, 0, 100, 1, "%"),
            new Parameter("home_curve_y2", 100, 0, 100, 1, "%"),
            new Parameter("size_curve_x1", 33, 0, 100, 1, "%"),
            new Parameter("size_curve_y1", 75, 0, 100, 1, "%"),
            new Parameter("size_curve_x2", 20, 0, 100, 1, "%"),
            new Parameter("size_curve_y2", 100, 0, 100, 1, "%"),
            new Parameter("home_height_duration", 85, 50, 100, 1, "%"),
            new Parameter("position_stiffness", 650, 100, 1500, 10, ""),
            new Parameter("position_damping", 95, 50, 150, 1, "%"),
            new Parameter("size_stiffness", 650, 100, 1500, 10, ""),
            new Parameter("size_damping", 100, 50, 150, 1, "%"),
            new Parameter("rotation_response", 100, 50, 200, 1, "%"),
            new Parameter("corner_start", 65, 0, 90, 1, "%"),
            new Parameter("fade_start", 85, 50, 95, 1, "%"),
            new Parameter("icon_fade_duration", 50, 10, 150, 5, "ms"),
            new Parameter("workspace_scale", 90, 75, 100, 1, "%"),
            new Parameter("blur_radius", 24, 0, 60, 1, "dp"),
            new Parameter("blur_enter_duration", 120, 30, 300, 10, "ms"),
            new Parameter("blur_exit_start", 5, 0, 70, 1, "%"),
            new Parameter("blur_recovery", 180, 0, 500, 10, "ms"),
            new Parameter("swipe_target_start", 32, 20, 60, 1, "%"),
            new Parameter("swipe_target_range", 10, 5, 25, 1, "%"),
            new Parameter("swipe_target_fade", 100, 30, 300, 10, "ms"),
            new Parameter("stack_spacing", 28, 10, 60, 1, "%"),
            new Parameter("stack_scale_step", 25, 0, 100, 1, "‰"),
    };

    public final long openDuration, recentsDuration, homeDuration, iconFadeDuration, blurRecovery;
    public final long homeHeightDuration, blurEnterDuration, swipeTargetFade;
    public final float positionStiffness, positionDamping, sizeStiffness, sizeDamping;
    public final float rotationResponse, cornerStart, fadeStart, workspaceScale;
    public final float stackSpacing, stackScaleStep;
    public final float blurExitStart, swipeTargetStart, swipeTargetRange;
    public final int blurRadius;
    public final Interpolator interpolator;
    public final Interpolator openingHeightInterpolator, homeInterpolator, homeSizeInterpolator;
    private static final Map<String, Parameter> PARAMETERS_BY_KEY = createParameterIndex();

    private static Map<String, Parameter> createParameterIndex() {
        Map<String, Parameter> parameters = new HashMap<>();
        for (Parameter parameter : PARAMETERS) parameters.put(parameter.key(), parameter);
        return Map.copyOf(parameters);
    }

    private DesktopAnimationSettings(Map<String, ?> prefs) {
        openDuration = value(prefs, "open_duration");
        recentsDuration = value(prefs, "recents_duration");
        homeDuration = value(prefs, "home_duration");
        homeHeightDuration = Math.max(1, homeDuration * value(prefs, "home_height_duration") / 100);
        iconFadeDuration = value(prefs, "icon_fade_duration");
        blurRecovery = value(prefs, "blur_recovery");
        blurEnterDuration = value(prefs, "blur_enter_duration");
        blurExitStart = value(prefs, "blur_exit_start") / 100f;
        swipeTargetStart = value(prefs, "swipe_target_start") / 100f;
        swipeTargetRange = value(prefs, "swipe_target_range") / 100f;
        swipeTargetFade = value(prefs, "swipe_target_fade");
        positionStiffness = value(prefs, "position_stiffness");
        positionDamping = value(prefs, "position_damping") / 100f;
        sizeStiffness = value(prefs, "size_stiffness");
        sizeDamping = value(prefs, "size_damping") / 100f;
        rotationResponse = value(prefs, "rotation_response") / 100f;
        cornerStart = value(prefs, "corner_start") / 100f;
        fadeStart = value(prefs, "fade_start") / 100f;
        workspaceScale = value(prefs, "workspace_scale") / 100f;
        blurRadius = value(prefs, "blur_radius");
        stackSpacing = value(prefs, "stack_spacing") / 100f;
        stackScaleStep = value(prefs, "stack_scale_step") / 1000f;
        // The reference expands width/position ahead of height, then returns position behind
        // size. Keep separate clocks so changing shape never changes the blur or travel phase.
        interpolator = curve(prefs, "curve_");
        openingHeightInterpolator = curve(prefs, "height_curve_");
        homeInterpolator = curve(prefs, "home_curve_");
        homeSizeInterpolator = curve(prefs, "size_curve_");
    }

    private static Interpolator curve(Map<String, ?> prefs, String prefix) {
        return new PathInterpolator(value(prefs, prefix + "x1") / 100f,
                value(prefs, prefix + "y1") / 100f, value(prefs, prefix + "x2") / 100f,
                value(prefs, prefix + "y2") / 100f);
    }

    /** Raw timeline progress, independent of the window's eased size and handoff velocity. */
    public float blurProgress(float time, boolean opening, long duration) {
        float p = opening ? time * duration / blurEnterDuration
                : (time - blurExitStart) / (1f - blurExitStart);
        p = Utilities.boundToRange(p, 0f, 1f);
        // The backdrop clears while the icon is still travelling. A symmetric fade keeps it
        // heavily blurred into that final tail, well after the reference has regained detail.
        return opening ? p * p * (3f - 2f * p) : 1f - (1f - p) * (1f - p) * (1f - p);
    }

    public static synchronized DesktopAnimationSettings read(Context context) {
        if (sPreferences == null) {
            sPreferences = preferences(context.getApplicationContext());
            sPreferences.registerOnSharedPreferenceChangeListener(sListener);
        }
        if (sSnapshot == null) sSnapshot = new DesktopAnimationSettings(sPreferences.getAll());
        return sSnapshot;
    }

    public static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(LauncherFiles.SHARED_PREFERENCES_KEY,
                Context.MODE_PRIVATE);
    }

    public static Parameter find(String key) {
        // Reads and resets have no preference key. Map.copyOf rejects null lookups.
        return key == null ? null : PARAMETERS_BY_KEY.get(key);
    }

    private static int value(Map<String, ?> prefs, String name) {
        return find(PREFIX + name).read(prefs);
    }

    public static Bundle describe(SharedPreferences prefs) {
        Bundle result = new Bundle();
        Map<String, ?> values = prefs.getAll();
        for (Parameter parameter : PARAMETERS) {
            Bundle spec = new Bundle();
            spec.putInt("value", parameter.read(values));
            spec.putInt("default", parameter.defaultValue());
            spec.putInt("min", parameter.min());
            spec.putInt("max", parameter.max());
            spec.putInt("step", parameter.step());
            spec.putString("unit", parameter.unit());
            result.putBundle(parameter.key(), spec);
        }
        return result;
    }

    public float rotationProgress(float progress) {
        float bounded = Utilities.boundToRange(progress, 0f, 1f);
        return rotationResponse == 1f ? bounded : (float) Math.pow(bounded, rotationResponse);
    }

    public float cornerProgress(float progress) {
        float p = Utilities.boundToRange((progress - cornerStart) / (1f - cornerStart), 0f, 1f);
        return p * p * (3f - 2f * p);
    }

    public float windowAlpha(float progress) {
        float p = Utilities.boundToRange((progress - fadeStart) / (1f - fadeStart), 0f, 1f);
        return 1f - p * p * (3f - 2f * p);
    }
}
