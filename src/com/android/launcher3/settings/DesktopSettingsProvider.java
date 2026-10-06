/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.settings;

import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
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
import com.android.launcher3.LauncherFiles;
import com.android.launcher3.anim.DesktopAnimationSettings;
import com.android.launcher3.states.RotationHelper;
import com.android.launcher3.util.DisplayController;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Narrow bridge to the existing, backed-up Launcher preferences used by system Settings. */
public class DesktopSettingsProvider extends ContentProvider {
    private static final Map<String, Boolean> DEFAULTS = Map.ofEntries(
            Map.entry("pref_workspace_lock", false),
            Map.entry("pref_standard_desktop", false),
            Map.entry("pref_add_icon_to_home", true),
            Map.entry("pref_allowRotation", false),
            Map.entry("pref_enable_minus_one", true),
            Map.entry("pref_drawer_open_keyboard", false),
            Map.entry("pref_allapps_themed_icons", false),
            Map.entry("pref_desktop_show_labels", true),
            Map.entry("pref_drawer_show_labels", true),
            Map.entry("pref_sleep_gesture", false),
            Map.entry("pref_landscape_app_animation", true),
            Map.entry("pref_stacked_recents", false));

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
        if (!"get_preferences".equals(method) && !"set_preference".equals(method) && !reset) {
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
        if (write && !validBoolean && !validNumber) {
            throw new IllegalArgumentException("Unknown preference or invalid value");
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            final SharedPreferences preferences = getContext().getSharedPreferences(
                    LauncherFiles.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE);
            // Commit on the Binder thread, never on the launcher UI thread. The normal
            // SharedPreferences listeners still update rotation, labels, themes and gestures.
            if (write || reset) {
                SharedPreferences.Editor editor = preferences.edit();
                if (reset) {
                    for (DesktopAnimationSettings.Parameter item
                            : DesktopAnimationSettings.PARAMETERS) {
                        editor.remove(item.key());
                    }
                } else if (validNumber) {
                    editor.putInt(key, (int) value);
                } else {
                    editor.putBoolean(key, (boolean) value);
                }
                if (!editor.commit()) return null;
            }
            return MAIN_EXECUTOR.submit(() -> readPreferences(preferences)).get(3, TimeUnit.SECONDS);
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
        available.putBoolean("pref_allowRotation", !info.isTablet(info.realBounds)
                && (!Flags.oneGridSpecs() || info.isRotationAllowed()));
        final LauncherApps apps = context.getSystemService(LauncherApps.class);
        available.putBoolean("pref_enable_minus_one", apps != null && apps.isPackageEnabled(
                "com.google.android.googlequicksearchbox", Process.myUserHandle()));
        available.putBoolean("pref_icon_badging", BuildConfig.NOTIFICATION_DOTS_ENABLED);
        available.putBoolean("pref_suggestions", apps != null && apps.isPackageEnabled(
                "com.google.android.as", Process.myUserHandle())
                && new Intent("android.settings.ACTION_CONTENT_SUGGESTIONS_SETTINGS")
                        .resolveActivity(context.getPackageManager()) != null);
        final Bundle result = new Bundle();
        result.putBundle("values", values);
        result.putBundle("available", available);
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
