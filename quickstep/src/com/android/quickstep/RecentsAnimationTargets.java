/*
 * Modified by the ArkUI Project in 2026 for atomic launch-to-gesture handoff.
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.quickstep;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.view.RemoteAnimationTarget.MODE_CLOSING;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.wm.shell.shared.ShellSharedConstants.KEY_EXTRA_RECENTS_START_ACK;
import static com.android.wm.shell.shared.ShellSharedConstants.KEY_EXTRA_RECENTS_START_CALLBACK;
import static com.android.wm.shell.shared.ShellSharedConstants.KEY_EXTRA_RECENTS_START_HANDOFF;
import static com.android.wm.shell.shared.ShellSharedConstants.KEY_EXTRA_RECENTS_START_TRANSACTION;

import android.app.WindowConfiguration;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.RemoteCallback;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;

import androidx.annotation.Nullable;

import com.android.quickstep.util.AppWindowAnimationState.GestureHandoff;
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Extension of {@link RemoteAnimationTargets} with additional information about swipe
 * up animation
 */
public class RecentsAnimationTargets extends RemoteAnimationTargets {

    public final Rect homeContentInsets;
    private RemoteCallback mStartCallback;
    private boolean mStartHandoffPending;
    private GestureHandoff mStartGestureHandoff;
    private final ArrayList<Consumer<Boolean>> mStartWaiters = new ArrayList<>();

    public RecentsAnimationTargets(RemoteAnimationTarget[] apps,
            RemoteAnimationTarget[] wallpapers, RemoteAnimationTarget[] nonApps,
            Rect homeContentInsets, Bundle extras) {
        super(apps, wallpapers, nonApps, MODE_CLOSING, extras);
        this.homeContentInsets = homeContentInsets;
        mStartCallback = extras.getParcelable(KEY_EXTRA_RECENTS_START_CALLBACK, RemoteCallback.class);
        mStartHandoffPending = mStartCallback != null;
    }

    public boolean hasStartHandoff() {
        return mStartHandoffPending;
    }

    /** A new gesture can reuse these targets while the first gesture's start is still in flight. */
    public @Nullable GestureHandoff retainStartHandoff(@Nullable GestureHandoff handoff) {
        if (!mStartHandoffPending) return handoff;
        if (mStartGestureHandoff == null && handoff != null) {
            mStartGestureHandoff = handoff;
            RemoteAnimationTargets opening = handoff.getOpeningTargets();
            if (opening != null) {
                ReleaseCheck check = new ReleaseCheck();
                check.setCanRelease(false);
                opening.addReleaseCheck(check);
                mStartWaiters.add(applied -> check.setCanRelease(true));
            }
        }
        return handoff != null ? handoff : mStartGestureHandoff;
    }

    /** Shell merges this frame after the old finish and new hierarchy, then acknowledges apply. */
    public void applyStartHandoff(@Nullable SurfaceControl.Transaction frame,
            Consumer<Boolean> onApplied) {
        try {
            if (!mStartHandoffPending) return;
            mStartWaiters.add(onApplied);
            RemoteCallback callback = mStartCallback;
            mStartCallback = null;
            // Another handler may already have sent the frame. All handlers wait for the same
            // ack; only the current one will resume and apply its latest finger position.
            if (callback == null) return;
            Bundle result = new Bundle();
            result.putParcelable(KEY_EXTRA_RECENTS_START_TRANSACTION, frame);
            result.putParcelable(KEY_EXTRA_RECENTS_START_ACK, new RemoteCallback(response ->
                    MAIN_EXECUTOR.execute(() -> onStartHandoffApplied(response != null
                            && response.getBoolean(KEY_EXTRA_RECENTS_START_HANDOFF)))));
            callback.sendResult(result);
        } finally {
            if (frame != null) frame.close();
        }
    }

    /** The gesture listener may have been removed while Shell was preparing the transition. */
    public void finishStartHandoff() {
        if (mStartCallback != null) {
            // Cancel instead of exposing untransformed replacement leashes.
            applyStartHandoff(null, applied -> { });
        }
    }

    private void onStartHandoffApplied(boolean applied) {
        if (!mStartHandoffPending) return;
        mStartHandoffPending = false;
        mStartCallback = null;
        mStartGestureHandoff = null;
        ArrayList<Consumer<Boolean>> waiters = new ArrayList<>(mStartWaiters);
        mStartWaiters.clear();
        for (Consumer<Boolean> waiter : waiters) waiter.accept(applied);
    }

    @Override
    public void release() {
        onStartHandoffApplied(false);
        super.release();
    }

    public boolean hasTargets() {
        return unfilteredApps.length != 0;
    }

    /**
     * Check if target apps contain desktop tasks which have windowing mode set to {@link
     * WindowConfiguration#WINDOWING_MODE_FREEFORM}
     *
     * @return {@code true} if at least one target app is a desktop task
     */
    // TODO: b/362720309 - Remove this function once multi-desks is fully launched.
    public boolean hasDesktopTasks(Context context) {
        if (!DesktopModeStatus.canEnterDesktopMode(context)) {
            return false;
        }
        for (RemoteAnimationTarget target : apps) {
            if (target.windowConfiguration.getWindowingMode() == WINDOWING_MODE_FREEFORM) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void dump(String prefix, PrintWriter pw) {
        super.dump(prefix, pw);
        prefix += '\t';
        pw.println(prefix + "RecentsAnimationTargets:");

        pw.println(prefix + "\thomeContentInsets=" + homeContentInsets);
    }
}
