/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.folder;

import android.view.WindowManager;
import android.view.View;

import com.android.launcher3.Launcher;
import com.android.launcher3.AbstractFloatingView;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Secure authenticated contents, then release screenshot protection after a clean frame. */
public final class FolderPrivacy {
    private static final Set<Launcher> sSecured = Collections.newSetFromMap(new WeakHashMap<>());

    private FolderPrivacy() { }

    static void secure(Launcher launcher) {
        if ((launcher.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) == 0) {
            sSecured.add(launcher);
            launcher.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    static void releaseAfterCleanFrame(Launcher launcher) {
        if (!sSecured.contains(launcher)) return;
        launcher.getDragLayer().getViewTreeObserver().registerFrameCommitCallback(() ->
                launcher.getDragLayer().post(() -> {
                    // A closing surface still paints private contents until it is detached.
                    for (int i = 0; i < launcher.getDragLayer().getChildCount(); i++) {
                        View surface = launcher.getDragLayer().getChildAt(i);
                        if (surface instanceof Folder folder && folder.mInfo.isPrivacyLocked()
                                || surface instanceof FolderEditorSheet editor && editor.isProtected()) return;
                    }
                    if (sSecured.remove(launcher)) {
                        launcher.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                    }
                }));
        launcher.getDragLayer().invalidate();
    }

    public static void closeOnPause(Launcher launcher) {
        Folder folder = Folder.getOpen(launcher);
        if (folder != null && folder.mInfo.isPrivacyLocked()) folder.close(false);
        FolderEditorSheet editor = AbstractFloatingView.getOpenView(launcher,
                AbstractFloatingView.TYPE_FOLDER_EDITOR);
        if (editor != null && editor.isProtected()) editor.close(false);
    }
}
