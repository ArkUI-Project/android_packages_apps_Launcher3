/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.settings;

import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static com.android.launcher3.LauncherPrefs.DESKTOP_ICON_SIZE;
import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import com.android.launcher3.BuildConfig;
import com.android.launcher3.Flags;
import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.LauncherFiles;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.anim.DesktopAnimationSettings;
import com.android.launcher3.graphics.ThemeManager;
import com.android.launcher3.shapes.IconShapeModel;
import com.android.launcher3.shapes.ShapesProvider;
import com.android.launcher3.states.RotationHelper;
import com.android.launcher3.util.DisplayController;
import com.android.launcher3.views.AskAiView;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Narrow bridge to the existing, backed-up Launcher preferences used by system Settings. */
public class DesktopSettingsProvider extends ContentProvider {
    private static final String ICON_SHAPE = ThemeManager.PREF_ICON_SHAPE.getSharedPrefKey();
    private static final String ICON_SIZE = DESKTOP_ICON_SIZE.getSharedPrefKey();
    private static final String ICON_LABELS = "pref_desktop_show_labels";
    private static final String GRID = "pref_desktop_grid";
    private static final Map<String, Boolean> DEFAULTS = Map.ofEntries(
            Map.entry("pref_workspace_lock", false),
            Map.entry("pref_standard_desktop", true),
            Map.entry("pref_add_icon_to_home", true),
            Map.entry("pref_allowRotation", false),
            Map.entry("pref_enable_minus_one", true),
            Map.entry("pref_drawer_open_keyboard", false),
            Map.entry("pref_allapps_themed_icons", false),
            Map.entry("pref_desktop_show_labels", true),
            Map.entry("pref_show_ask_ai", true),
            Map.entry("pref_drawer_show_labels", true),
            Map.entry("pref_sleep_gesture", false),
            Map.entry("pref_landscape_app_animation", true),
            Map.entry("pref_stacked_recents", true));

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String key, Bundle extras) {
        // ContentProvider.call does not enforce the manifest read/write permissions for us.
        getContext().enforceCallingOrSelfPermission(WRITE_SECURE_SETTINGS,
                "Changing home screen preferences requires system Settings access");
        final boolean reset = "reset_animation_preferences".equals(method);
        final boolean writeIcons = "set_icon_preferences".equals(method);
        if (!"get_preferences".equals(method) && !"set_preference".equals(method)
                && !writeIcons && !reset) {
            throw new IllegalArgumentException("Unknown desktop settings method");
        }
        final boolean write = "set_preference".equals(method);
        final DesktopAnimationSettings.Parameter parameter = write
                ? DesktopAnimationSettings.find(key) : null;
        final Object value = extras == null ? null : extras.get("value");
        final boolean validBoolean = key != null && DEFAULTS.containsKey(key)
                && value instanceof Boolean;
        final boolean validNumber = parameter != null && value instanceof Integer
                && (int) value >= parameter.min() && (int) value <= parameter.max();
        final boolean validShape = ICON_SHAPE.equals(key) && isValidShape(value);
        final boolean validSize = ICON_SIZE.equals(key) && isValidSize(value);
        final boolean validGrid = GRID.equals(key) && value instanceof String;
        if (write && !validBoolean && !validNumber && !validShape && !validSize && !validGrid) {
            throw new IllegalArgumentException("Unknown preference or invalid value");
        }
        if (writeIcons && (extras == null || !isValidSize(extras.get(ICON_SIZE))
                || !isValidShape(extras.get(ICON_SHAPE))
                || !(extras.get(ICON_LABELS) instanceof Boolean))) {
            throw new IllegalArgumentException("Invalid icon preferences");
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            final SharedPreferences preferences = getContext().getSharedPreferences(
                    LauncherFiles.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE);
            // Commit on the Binder thread, never on the launcher UI thread. The normal
            // SharedPreferences listeners still update rotation, labels, themes and gestures.
            if ((write && !validGrid) || writeIcons || reset) {
                SharedPreferences.Editor editor = preferences.edit();
                if (writeIcons) {
                    editor.putInt(ICON_SIZE, extras.getInt(ICON_SIZE));
                    editor.putString(ICON_SHAPE, extras.getString(ICON_SHAPE));
                    editor.putBoolean(ICON_LABELS, extras.getBoolean(ICON_LABELS));
                } else if (reset) {
                    for (DesktopAnimationSettings.Parameter item
                            : DesktopAnimationSettings.PARAMETERS) {
                        editor.remove(item.key());
                    }
                } else if (validNumber || validSize) {
                    editor.putInt(key, (int) value);
                } else if (validShape) {
                    editor.putString(key, (String) value);
                } else {
                    editor.putBoolean(key, (boolean) value);
                }
                if (!editor.commit()) return null;
            }
            return MAIN_EXECUTOR.submit(() -> {
                if (write && validGrid) {
                    final InvariantDeviceProfile idp = InvariantDeviceProfile.INSTANCE.get(getContext());
                    if (idp.getGridOptionFromName(getContext(), (String) value) == null) {
                        throw new IllegalArgumentException("Unsupported desktop grid");
                    }
                    // Reuse normal grid migration instead of creating a fresh workspace.
                    idp.setCurrentGrid((String) value);
                }
                return readPreferences(preferences);
            }).get(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            throw new IllegalStateException("Unable to read desktop preferences", e.getCause());
        } catch (TimeoutException e) {
            return null;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static boolean isValidSize(Object value) {
        return value instanceof Integer && (int) value >= 80 && (int) value <= 120;
    }

    private static boolean isValidShape(Object value) {
        return value instanceof String && ("".equals(value)
                || Arrays.stream(ShapesProvider.INSTANCE.getIconShapes())
                        .anyMatch(shape -> shape.getKey().equals(value)));
    }

    private Bundle readPreferences(SharedPreferences preferences) {
        final Context context = getContext();
        final DisplayController.Info info = DisplayController.INSTANCE.get(context).getInfo();
        final Bundle values = new Bundle();
        for (Map.Entry<String, Boolean> entry : DEFAULTS.entrySet()) {
            final boolean defaultValue = "pref_allowRotation".equals(entry.getKey())
                    ? RotationHelper.getAllowRotationDefaultValue(info) : entry.getValue();
            values.putBoolean(entry.getKey(), preferences.getBoolean(entry.getKey(), defaultValue));
        }
        final Bundle available = new Bundle();
        for (String key : DEFAULTS.keySet()) {
            available.putBoolean(key, true);
        }
        final IconShapeModel[] shapes = ShapesProvider.INSTANCE.getIconShapes();
        final String[] shapeKeys = new String[shapes.length + 1];
        final String[] shapeLabels = new String[shapes.length + 1];
        final String[] shapePaths = new String[shapes.length + 1];
        shapeKeys[0] = "";
        shapeLabels[0] = context.getString(R.string.arkui_icon_shape_system);
        final int maskId = context.getResources().getIdentifier(
                "config_icon_mask", "string", "android");
        shapePaths[0] = maskId == 0 ? "M50 0A50 50 0 1 1 50 100A50 50 0 1 1 50 0"
                : context.getResources().getString(maskId);
        for (int i = 0; i < shapes.length; i++) {
            shapeKeys[i + 1] = shapes[i].getKey();
            shapeLabels[i + 1] = context.getString(shapes[i].getTitleId());
            shapePaths[i + 1] = shapes[i].getPathString();
        }
        values.putString(ICON_SHAPE, ThemeManager.PREF_ICON_SHAPE.get(context));
        available.putBoolean(ICON_SHAPE, shapes.length > 1);
        final Bundle iconShapes = new Bundle();
        iconShapes.putStringArray("values", shapeKeys);
        iconShapes.putStringArray("labels", shapeLabels);
        iconShapes.putStringArray("paths", shapePaths);
        iconShapes.putString("default", shapes.length == 0 ? "" : ShapesProvider.CIRCLE_KEY);
        final int iconSize = Math.max(80, Math.min(120, preferences.getInt(ICON_SIZE, 115)));
        values.putInt(ICON_SIZE, iconSize);
        available.putBoolean(ICON_SIZE, true);
        final Bundle sizeSpec = new Bundle();
        sizeSpec.putInt("min", 80);
        sizeSpec.putInt("max", 120);
        sizeSpec.putInt("step", 5);
        sizeSpec.putInt("default", 115);
        final InvariantDeviceProfile idp = InvariantDeviceProfile.INSTANCE.get(context);
        final List<InvariantDeviceProfile.GridOption> grids = idp.parseAllGridOptions(context);
        grids.sort(Comparator.comparingInt((InvariantDeviceProfile.GridOption grid) -> grid.numColumns)
                .thenComparingInt(grid -> grid.numRows));
        final String[] gridNames = new String[grids.size()];
        final String[] gridLabels = new String[grids.size()];
        for (int i = 0; i < grids.size(); i++) {
            final InvariantDeviceProfile.GridOption grid = grids.get(i);
            gridNames[i] = grid.name;
            gridLabels[i] = String.format(Locale.getDefault(), "%d × %d", grid.numColumns, grid.numRows);
        }
        final Bundle gridOptions = new Bundle();
        gridOptions.putStringArray("values", gridNames);
        gridOptions.putStringArray("labels", gridLabels);
        values.putString(GRID, LauncherPrefs.get(context).get(LauncherPrefs.GRID_NAME));
        available.putBoolean(GRID, grids.size() > 1);
        sizeSpec.putFloat("base_size_dp", idp.iconSize[0] / idp.desktopIconScale);
        available.putBoolean("pref_allowRotation", !info.isTablet(info.realBounds)
                && (!Flags.oneGridSpecs() || info.isRotationAllowed()));
        final LauncherApps apps = context.getSystemService(LauncherApps.class);
        available.putBoolean("pref_enable_minus_one", apps != null && apps.isPackageEnabled(
                "com.google.android.googlequicksearchbox", Process.myUserHandle()));
        available.putBoolean("pref_icon_badging", BuildConfig.NOTIFICATION_DOTS_ENABLED);
        available.putBoolean("pref_show_ask_ai",
                AskAiView.isAssistantAvailable(context));
        available.putBoolean("pref_suggestions", apps != null && apps.isPackageEnabled(
                "com.google.android.as", Process.myUserHandle())
                && new Intent("android.settings.ACTION_CONTENT_SUGGESTIONS_SETTINGS")
                        .resolveActivity(context.getPackageManager()) != null);
        final Bundle result = new Bundle();
        result.putBundle("values", values);
        result.putBundle("available", available);
        result.putBundle("icon_shapes", iconShapes);
        result.putBundle("icon_size", sizeSpec);
        result.putBundle("grid_options", gridOptions);
        result.putBundle("motion", DesktopAnimationSettings.describe(preferences));
        return result;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
