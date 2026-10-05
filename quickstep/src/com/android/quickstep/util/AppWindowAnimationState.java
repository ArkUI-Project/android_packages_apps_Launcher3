/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.quickstep.util;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import android.app.ActivityManager.RunningTaskInfo;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.RemoteAnimationTarget;

import androidx.annotation.Nullable;

import com.android.launcher3.DeviceProfile;
import com.android.quickstep.RemoteAnimationTargets;

import java.util.function.Consumer;

/** UI-thread handoff of window poses, with independent ownership for each task. */
public final class AppWindowAnimationState {
    private static final SparseArray<Session> sSessions = new SparseArray<>();
    private static final SparseArray<Consumer<GestureHandoff>> sPendingGestures = new SparseArray<>();
    private static long sNextSessionId;

    public static final class Pose {
        public final RectF rect = new RectF();
        public final PointF velocity = new PointF(); // pixels per millisecond
        public final PointF sizeVelocity = new PointF(); // width/height per millisecond
        public float openness;
        public float cornerRadius;
        public float appRotation = Float.NaN;
        // Position within the vertical crop slack: 0 keeps the app's top inset visible.
        public float verticalCropAnchor = Float.NaN;
        private long time;

        Pose() { }

        Pose(Pose other) {
            rect.set(other.rect);
            velocity.set(other.velocity);
            sizeVelocity.set(other.sizeVelocity);
            openness = other.openness;
            cornerRadius = other.cornerRadius;
            appRotation = other.appRotation;
            verticalCropAnchor = other.verticalCropAnchor;
            time = other.time;
        }
    }

    /** A gesture owns the live launch until Shell supplies its replacement targets. */
    public static final class GestureHandoff {
        private final int mTaskId, mWidth, mHeight, mRotation;
        private final long mSessionId;
        private final Pose mPose;
        private final Session mSource;
        private final Matrix mWindowToHome = new Matrix();
        private final Matrix mCurrentMatrix = new Matrix();
        private final RectF mCurrentRect = new RectF();
        @Nullable private final RunningTaskInfo mTaskInfo;

        private GestureHandoff(Session source) {
            mTaskId = source.mTaskId;
            mWidth = source.mWidth;
            mHeight = source.mHeight;
            mRotation = source.mRotation;
            mSessionId = source.mSessionId;
            mSource = source;
            mPose = new Pose(source.mPose);
            mTaskInfo = source.mOpeningTaskInfo;
        }

        public @Nullable RunningTaskInfo getTaskInfo() {
            return mTaskInfo;
        }

        /** Claim only this launch, even if Shell replaced it with its visual continuation. */
        public void takeOver() {
            Session owner = sSessions.get(mTaskId);
            if (owner == null || owner.mSessionId != mSessionId) return;
            if (owner.mGestureOwned) {
                owner.mGestureOwner = this;
                return;
            }
            if (owner.mOpeningTargets != null && owner.mOnGestureTakeover != null) {
                // Stop the timeline, not the remote transition. The existing leash remains
                // under the finger until Shell has supplied its replacement.
                owner.mGestureOwned = true;
                owner.mGestureOwner = this;
                Runnable takeOver = owner.mOnGestureTakeover;
                owner.mOnGestureTakeover = null;
                takeOver.run();
                return;
            }
            sSessions.remove(mTaskId);
            owner.cancel();
            Runnable stop = owner.mStopContinuation != null
                    ? owner.mStopContinuation : owner.mOnGestureTakeover;
            if (stop != null) stop.run();
        }

        public @Nullable RemoteAnimationTargets getOpeningTargets() {
            return mSource.mGestureOwner == this && sSessions.get(mTaskId) == mSource
                    ? mSource.mOpeningTargets : null;
        }

        /** Store the displayed bridge frame, rather than the original DOWN sample. */
        public void record(TaskViewSimulator simulator) {
            mCurrentMatrix.set(simulator.getCurrentMatrix());
            float rotation = LandscapeAppAnimation.normalizeRotation(
                    LandscapeAppAnimation.getMatrixRotation(mCurrentMatrix)
                            - simulator.getBaseMatrixRotation());
            mCurrentRect.set(simulator.getCurrentCropRect());
            mCurrentMatrix.mapRect(mCurrentRect);
            mCurrentMatrix.postRotate(-rotation, mCurrentRect.centerX(), mCurrentRect.centerY());
            mCurrentRect.set(simulator.getCurrentCropRect());
            mCurrentMatrix.mapRect(mCurrentRect);
            mWindowToHome.reset();
            simulator.applyWindowToHomeRotation(mWindowToHome);
            mWindowToHome.invert(mWindowToHome);
            mWindowToHome.mapRect(mCurrentRect);
            if (mCurrentRect.isEmpty()) return;
            final float cropAnchor = simulator.getCurrentVerticalCropAnchor();
            if (mCurrentRect.equals(mPose.rect) && rotation == mPose.appRotation
                    && Float.compare(cropAnchor, mPose.verticalCropAnchor) == 0) return;
            long now = SystemClock.uptimeMillis();
            long dt = now - mPose.time;
            if (dt > 0 && dt < 100) {
                mPose.velocity.set((mCurrentRect.centerX() - mPose.rect.centerX()) / dt,
                        (mCurrentRect.centerY() - mPose.rect.centerY()) / dt);
                mPose.sizeVelocity.set((mCurrentRect.width() - mPose.rect.width()) / dt,
                        (mCurrentRect.height() - mPose.rect.height()) / dt);
            } else if (dt >= 100) {
                mPose.velocity.set(0f, 0f);
                mPose.sizeVelocity.set(0f, 0f);
            }
            mPose.rect.set(mCurrentRect);
            mPose.cornerRadius = simulator.getCurrentCornerRadius()
                    * mCurrentMatrix.mapRadius(1f);
            mPose.appRotation = rotation;
            mPose.verticalCropAnchor = cropAnchor;
            mPose.time = now;
            if (mSource.mGestureOwner == this && sSessions.get(mTaskId) == mSource) {
                mSource.copyPose(mPose);
            }
        }

        public void releaseTargets() {
            if (mSource.mGestureOwner == this) mSource.releaseGesture();
        }

        public void finish() {
            if (mSource.mGestureOwner == this) mSource.finishGesture();
        }

        public @Nullable Pose getPose(int taskId, DeviceProfile profile) {
            return taskId >= 0 && (taskId == mTaskId || (mTaskInfo != null
                    && (taskId == mTaskInfo.taskId || taskId == mTaskInfo.parentTaskId)))
                    && profile.getDeviceProperties().getWidthPx() == mWidth
                    && profile.getDeviceProperties().getHeightPx() == mHeight
                    && profile.getDeviceProperties().getRotationHint() == mRotation
                    ? new Pose(mPose) : null;
        }
    }

    public static final class Session {
        public final @Nullable Pose previous;
        private final Pose mPose = new Pose();
        private final int mTaskId, mWidth, mHeight, mRotation;
        private final long mSessionId;
        @Nullable private RunningTaskInfo mOpeningTaskInfo;
        @Nullable private Runnable mOnGestureTakeover;
        @Nullable private RemoteAnimationTargets mOpeningTargets;
        @Nullable private Runnable mReleaseGesture;
        @Nullable private Runnable mEndGesture;
        @Nullable private GestureHandoff mGestureOwner;
        private boolean mGestureOwned;
        private boolean mCancelled;
        private boolean mInitialContinuationFrame;
        private @Nullable Runnable mStopContinuation;

        Session(int taskId, DeviceProfile profile) {
            mTaskId = taskId;
            mSessionId = ++sNextSessionId;
            mWidth = profile.getDeviceProperties().getWidthPx();
            mHeight = profile.getDeviceProperties().getHeightPx();
            mRotation = profile.getDeviceProperties().getRotationHint();
            // Keep separate tasks alive while their window animations overlap. Retain a
            // cancelled pose only briefly, so reopening the same task can catch that frame.
            for (int i = sSessions.size() - 1; i >= 0; i--) {
                Session session = sSessions.valueAt(i);
                if (session.mCancelled && !session.mGestureOwned
                        && SystemClock.uptimeMillis() - session.mPose.time >= 250) {
                    sSessions.removeAt(i);
                }
            }
            Session old = sSessions.get(taskId);
            previous = old != null && taskId >= 0 && old.mTaskId == taskId
                    && old.mWidth == mWidth && old.mHeight == mHeight && old.mRotation == mRotation
                    && !old.mPose.rect.isEmpty()
                    && SystemClock.uptimeMillis() - old.mPose.time < 250
                    ? new Pose(old.mPose) : null;
            if (previous != null) {
                copyPose(previous);
                mInitialContinuationFrame = true;
            }
            if (taskId >= 0) sSessions.put(taskId, this);
            if (old != null && old.mStopContinuation != null) old.mStopContinuation.run();
            if (old != null && old.mGestureOwned) old.finishGesture();
        }

        private Session(Session source, Runnable stopContinuation) {
            mTaskId = source.mTaskId;
            mSessionId = source.mSessionId;
            mWidth = source.mWidth;
            mHeight = source.mHeight;
            mRotation = source.mRotation;
            previous = new Pose(source.mPose);
            copyPose(source.mPose);
            mStopContinuation = stopContinuation;
            mInitialContinuationFrame = true;
            sSessions.put(mTaskId, this);
        }

        private void copyPose(Pose pose) {
            mPose.rect.set(pose.rect);
            mPose.velocity.set(pose.velocity);
            mPose.sizeVelocity.set(pose.sizeVelocity);
            mPose.openness = pose.openness;
            mPose.cornerRadius = pose.cornerRadius;
            mPose.appRotation = pose.appRotation;
            mPose.verticalCropAnchor = pose.verticalCropAnchor;
            mPose.time = pose.time;
        }

        /** Transfer the visual tail without keeping the old remote transition alive. */
        public @Nullable Session continueWith(Runnable stopContinuation) {
            return mTaskId >= 0 && isActive() ? new Session(this, stopContinuation) : null;
        }

        public void record(RectF rect, float openness, float cornerRadius) {
            record(rect, openness, cornerRadius, Float.NaN);
        }

        public void record(RectF rect, float openness, float cornerRadius,
                float verticalCropAnchor) {
            if (!isActive() || rect.isEmpty()) return;
            long now = SystemClock.uptimeMillis();
            long dt = now - mPose.time;
            // The snapshot first redraws the captured pose at the same play time. That
            // repeated frame must not erase the velocity needed by an immediate reopen.
            if (!mPose.rect.isEmpty() && dt > 0 && dt < 100
                    && !(mInitialContinuationFrame && mPose.rect.equals(rect))) {
                mPose.velocity.set((rect.centerX() - mPose.rect.centerX()) / dt,
                        (rect.centerY() - mPose.rect.centerY()) / dt);
                mPose.sizeVelocity.set((rect.width() - mPose.rect.width()) / dt,
                        (rect.height() - mPose.rect.height()) / dt);
            }
            mInitialContinuationFrame = false;
            mPose.rect.set(rect);
            mPose.openness = LandscapeAppAnimation.boundProgress(openness);
            mPose.cornerRadius = cornerRadius;
            mPose.appRotation = Float.NaN;
            mPose.verticalCropAnchor = verticalCropAnchor;
            mPose.time = now;
        }

        public void cancel() {
            mCancelled = true;
        }

        public boolean isActive() {
            return (mTaskId < 0 || sSessions.get(mTaskId) == this)
                    && !mCancelled && !mGestureOwned;
        }

        public boolean isGestureOwned() { return mGestureOwned; }

        public void setGestureTargets(RemoteAnimationTargets targets, Runnable takeOver,
                Runnable endGesture) {
            if (mOpeningTaskInfo == null || targets.apps.length != 1) return;
            mOpeningTargets = targets;
            mOnGestureTakeover = takeOver;
            mEndGesture = endGesture;
        }

        public void setGestureRelease(Runnable release) {
            if (mOpeningTargets == null) release.run();
            else mReleaseGesture = release;
        }

        /** Called on replacement, cancellation, or the gesture handler's cleanup. */
        public void releaseGesture() {
            mOpeningTargets = null;
            if (sSessions.get(mTaskId) == this) sSessions.remove(mTaskId);
            Runnable release = mReleaseGesture;
            mReleaseGesture = null;
            if (release != null) release.run();
        }

        private void finishGesture() {
            releaseGesture();
            mGestureOwner = null;
            Runnable end = mEndGesture;
            mEndGesture = null;
            if (mGestureOwned && end != null) end.run();
        }

        public void finish() {
            mStopContinuation = null;
            mOnGestureTakeover = null;
            mEndGesture = null;
            mOpeningTargets = null;
            if (sSessions.get(mTaskId) == this && !mCancelled) sSessions.remove(mTaskId);
        }
    }

    public static Session begin(int taskId, DeviceProfile profile) {
        return new Session(taskId, profile);
    }

    /** Register the actual opening task before the foreground-task cache catches up. */
    public static Session beginOpening(@Nullable RemoteAnimationTarget target,
            DeviceProfile profile) {
        Session session = begin(target == null ? -1 : target.taskId, profile);
        if (target != null && target.taskInfo != null && !target.isTranslucent
                && target.taskInfo.getActivityType() == ACTIVITY_TYPE_STANDARD
                && target.taskInfo.getWindowingMode() == WINDOWING_MODE_FULLSCREEN) {
            session.mOpeningTaskInfo = target.taskInfo;
        }
        return session;
    }

    /** Covers DOWN arriving before the remote launch callback has initialized its first frame. */
    public static Runnable waitForOpening(int taskId, Consumer<GestureHandoff> consumer) {
        if (taskId < 0) return () -> { };
        sPendingGestures.put(taskId, consumer);
        return () -> {
            if (sPendingGestures.get(taskId) == consumer) sPendingGestures.remove(taskId);
        };
    }

    /** Invoked after the launch animator is fully built and started, before the next UI message. */
    public static void dispatchPendingGestures() {
        for (int i = sPendingGestures.size() - 1; i >= 0; i--) {
            int taskId = sPendingGestures.keyAt(i);
            Session session = sSessions.get(taskId);
            if (session == null || session.mOpeningTargets == null
                    || !session.isActive() || session.mPose.rect.isEmpty()) continue;
            Consumer<GestureHandoff> consumer = sPendingGestures.valueAt(i);
            sPendingGestures.removeAt(i);
            consumer.accept(new GestureHandoff(session));
        }
    }

    /**
     * Sample at input-consumer selection, before a resumed Launcher can consume the swipe as a
     * Home gesture. This is read-only until the app gesture handler accepts the handoff.
     */
    public static @Nullable GestureHandoff captureOpeningForGesture(int displayId) {
        Session newest = null;
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < sSessions.size(); i++) {
            Session session = sSessions.valueAt(i);
            if (session.mOpeningTaskInfo == null || session.mStopContinuation != null
                    || !session.isActive() || session.mPose.rect.isEmpty()
                    || session.mOpeningTaskInfo.displayId != displayId
                    || now - session.mPose.time >= 250) continue;
            if (newest == null || session.mSessionId > newest.mSessionId) newest = session;
        }
        return newest == null ? null : new GestureHandoff(newest);
    }

    /**
     * Take ownership before preparing Launcher UI can cancel the launch. Reading the pose only
     * after Shell supplies recents targets lets the old snapshot expand to fullscreen meanwhile.
     * Other tasks keep their independent visual tails.
     */
    public static @Nullable GestureHandoff takeForGesture(int taskId) {
        Session owner = sSessions.get(taskId);
        if (owner == null || taskId < 0 || owner.mPose.rect.isEmpty()
                || SystemClock.uptimeMillis() - owner.mPose.time >= 250) return null;
        GestureHandoff handoff = new GestureHandoff(owner);
        handoff.takeOver();
        return handoff;
    }

    /** Read-only seed for the gesture that takes over from a launcher-owned animation. */
    public static @Nullable Pose peek(int taskId, DeviceProfile profile) {
        Session owner = sSessions.get(taskId);
        return owner != null && owner.mTaskId == taskId && taskId >= 0
                && owner.mWidth == profile.getDeviceProperties().getWidthPx()
                && owner.mHeight == profile.getDeviceProperties().getHeightPx()
                && owner.mRotation == profile.getDeviceProperties().getRotationHint()
                && !owner.mPose.rect.isEmpty()
                && SystemClock.uptimeMillis() - owner.mPose.time < 250
                ? new Pose(owner.mPose) : null;
    }

    public static float getVerticalCropAnchor(float top, float cropHeight, float sourceHeight,
            float fallback) {
        float slack = sourceHeight - cropHeight;
        return slack <= 1f ? fallback : Math.max(0f, Math.min(1f, top / slack));
    }

    /** Move the source slice while retaining the same mapped card rectangle. */
    public static void applyVerticalCropAnchor(Matrix matrix, Rect crop, float sourceHeight,
            float anchor) {
        if (!Float.isFinite(anchor)) return;
        int top = Math.round(Math.max(0f, sourceHeight - crop.height()) * anchor);
        int offset = top - crop.top;
        crop.offset(0, offset);
        matrix.preTranslate(0f, -offset);
    }

    private AppWindowAnimationState() { }
}
