/*
 * Modified by the ArkUI Project in 2026 for landscape app return animations.
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.android.app.animation.Interpolators.ACCELERATE_1_5;
import static com.android.app.animation.Interpolators.LINEAR;
import static com.android.launcher3.PagedView.INVALID_PAGE;

import android.animation.Animator;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Matrix.ScaleToFit;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.RemoteAnimationTarget;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.Utilities;
import com.android.launcher3.anim.AnimatedFloat;
import com.android.launcher3.anim.AnimationSuccessListener;
import com.android.launcher3.anim.AnimatorPlaybackController;
import com.android.launcher3.anim.PendingAnimation;
import com.android.launcher3.logging.FileLog;
import com.android.launcher3.touch.PagedOrientationHandler;
import com.android.launcher3.views.ClipIconView;
import com.android.quickstep.RemoteTargetGluer.RemoteTargetHandle;
import com.android.quickstep.orientation.RecentsPagedOrientationHandler;
import com.android.quickstep.util.AnimatorControllerWithResistance;
import com.android.quickstep.util.LandscapeAppAnimation;
import com.android.quickstep.util.AppWindowAnimationState;
import com.android.quickstep.util.WindowAnimationSnapshot;
import com.android.launcher3.anim.DesktopAnimationSettings;
import com.android.quickstep.util.RectFSpringAnim;
import com.android.quickstep.util.RectFSpringAnim.DefaultSpringConfig;
import com.android.quickstep.util.RectFSpringAnim.TaskbarHotseatSpringConfig;
import com.android.quickstep.util.SurfaceTransaction.SurfaceProperties;
import com.android.quickstep.util.SystemBarFollowAnimation;
import com.android.quickstep.util.TaskViewSimulator;
import com.android.quickstep.util.TransformParams;
import com.android.quickstep.util.TransformParams.BuilderProxy;
import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.TaskView;
import com.android.wm.shell.shared.GroupedTaskInfo;

import java.util.Arrays;
import java.util.function.Consumer;

public abstract class SwipeUpAnimationLogic implements
        RecentsAnimationCallbacks.RecentsAnimationListener {

    protected static final Rect TEMP_RECT = new Rect();
    protected final RemoteTargetGluer mTargetGluer;

    protected DeviceProfile mDp;

    protected final Context mContext;
    protected final GestureState mGestureState;

    protected RemoteTargetHandle[] mRemoteTargetHandles;

    // Shift in the range of [0, 1].
    // 0 => preview snapShot is completely visible, and hotseat is completely translated down
    // 1 => preview snapShot is completely aligned with the recents view and hotseat is completely
    // visible.
    protected final AnimatedFloat mCurrentShift = new AnimatedFloat(this::onCurrentShiftUpdated);
    protected float mCurrentDisplacement;

    // The distance needed to drag to reach the task size in recents.
    protected int mTransitionDragLength;
    // How much further we can drag past recents, as a factor of mTransitionDragLength.
    protected float mDragLengthFactor = 1;

    protected boolean mIsSwipeForSplit;

    public SwipeUpAnimationLogic(Context context, GestureState gestureState,
            RotationTouchHelper rotationTouchHelper) {
        mContext = context;
        mGestureState = gestureState;
        updateIsGestureForSplit(TopTaskTracker.INSTANCE.get(context)
                .getRunningSplitTaskIds().length);

        GroupedTaskInfo groupedTaskInfo = null;
        if (mGestureState.getRunningTask() != null) {
            groupedTaskInfo =
                    mGestureState.getRunningTask().getPlaceholderGroupedTaskInfo(
                            /* splitTaskIds = */ null);
        }
        mTargetGluer = new RemoteTargetGluer(mContext, mGestureState.getContainerInterface(),
                groupedTaskInfo);
        mRemoteTargetHandles = mTargetGluer.getRemoteTargetHandles();
        runActionOnRemoteHandles(remoteTargetHandle ->
                remoteTargetHandle.getTaskViewSimulator().getOrientationState().update(
                        rotationTouchHelper.getCurrentActiveRotation(),
                        rotationTouchHelper.getDisplayRotation()
                ));
    }

    protected void initTransitionEndpoints(DeviceProfile dp) {
        mDp = dp;
        mTransitionDragLength = mGestureState.getContainerInterface()
                .getSwipeUpDestinationAndLength(dp, mContext, TEMP_RECT,
                        mRemoteTargetHandles[0].getTaskViewSimulator().getOrientationState()
                                .getOrientationHandler());
        mDragLengthFactor = (float) dp.getDeviceProperties().getHeightPx() / mTransitionDragLength;

        for (RemoteTargetHandle remoteHandle : mRemoteTargetHandles) {
            PendingAnimation pendingAnimation = new PendingAnimation(mTransitionDragLength * 2);
            TaskViewSimulator taskViewSimulator = remoteHandle.getTaskViewSimulator();
            taskViewSimulator.setDp(dp);
            taskViewSimulator.addAppToCarouselAnim(pendingAnimation, LINEAR,
                    mGestureState.isHandlingAtomicEvent());
            AnimatorPlaybackController playbackController =
                    pendingAnimation.createPlaybackController();

            remoteHandle.setPlaybackController(AnimatorControllerWithResistance.createForRecents(
                    playbackController, mContext, taskViewSimulator.getOrientationState(),
                    mDp, taskViewSimulator.recentsViewScale, AnimatedFloat.VALUE,
                    taskViewSimulator.recentsViewSecondaryTranslation, AnimatedFloat.VALUE
            ));
        }
    }

    @UiThread
    public void updateDisplacement(float displacement) {
        // We are moving in the negative x/y direction
        displacement = overrideDisplacementForTransientTaskbar(-displacement);
        mCurrentDisplacement = displacement;

        float shift;
        if (displacement > mTransitionDragLength * mDragLengthFactor && mTransitionDragLength > 0) {
            shift = mDragLengthFactor;
        } else {
            float translation = Math.max(displacement, 0);
            shift = mTransitionDragLength == 0 ? 0 : translation / mTransitionDragLength;
        }

        mCurrentShift.updateValue(shift);
    }

    /**
     * When Transient Taskbar is enabled, subclasses can override the displacement to keep the app
     * window at the bottom of the screen while taskbar is being swiped in.
     * @param displacement The distance the user has swiped up from the bottom of the screen. This
     *                     value will be positive unless the user swipe downwards.
     * @return the overridden displacement.
     */
    protected float overrideDisplacementForTransientTaskbar(float displacement) {
        return displacement;
    }

    /**
     * Called when the value of {@link #mCurrentShift} changes
     */
    @UiThread
    public abstract void onCurrentShiftUpdated();

    protected RecentsPagedOrientationHandler getOrientationHandler() {
        // OrientationHandler should be independent of remote target, can directly take one
        return mRemoteTargetHandles[0].getTaskViewSimulator()
                .getOrientationState().getOrientationHandler();
    }

    protected abstract class HomeAnimationFactory {
        protected float mSwipeVelocity;
        protected float mLandscapeRotation;
        protected final DesktopAnimationSettings mMotion = DesktopAnimationSettings.read(mContext);
        @Nullable private RectF mStartWindow;
        private float mStartWindowRadius;
        @Nullable private PointF mStartSizeVelocity;

        public void setStartWindow(RectF rect, float visibleRadius, @Nullable PointF sizeVelocity) {
            mStartWindow = new RectF(rect);
            mStartWindowRadius = visibleRadius;
            mStartSizeVelocity = sizeVelocity;
        }

        public void beginAppTransition(Object owner) { }

        public void setAppTransitionProgress(Object owner, float progress) { }

        public void endAppTransition(Object owner) { }

        public @Nullable View getAnimationHost() { return null; }

        /**
         * Returns true if we know the home animation involves an item in the hotseat.
         */
        public boolean isInHotseat() {
            return false;
        }

        public @NonNull RectF getWindowTargetRect() {
            return LandscapeAppAnimation.getDefaultHomeTarget(mDp);
        }

        /** Widgets, PiP and split-screen supply their own geometry. */
        public boolean supportsLandscapeAnimation() {
            return false;
        }

        /** Returns the corner radius of the window at the end of the animation. */
        public float getEndRadius(RectF cropRectF) {
            return cropRectF.width() / 2f;
        }

        public abstract @NonNull AnimatorPlaybackController createActivityAnimationToHome();

        public void setSwipeVelocity(float velocity) {
            mSwipeVelocity = velocity;
        }

        public void playAtomicAnimation(float velocity) {
            // No-op
        }

        public void setAnimation(RectFSpringAnim anim) { }

        public void update(RectF currentRect, float progress, float radius, int overlayAlpha) { }

        public void onCancel() { }

        /**
         * @param progress The progress of the animation to the home screen.
         * @return The current alpha to set on the animating app window.
         */
        protected float getWindowAlpha(float progress) {
            if (supportsLandscapeAnimation() && getTargetTaskView() == null) {
                return mMotion.windowAlpha(progress);
            }
            // Alpha interpolates between [1, 0] between progress values [start, end]
            final float start = 0f;
            final float end = 0.85f;

            if (progress <= start) {
                return 1f;
            }
            if (progress >= end) {
                return 0f;
            }
            return Utilities.mapToRange(progress, start, end, 1, 0, ACCELERATE_1_5);
        }

        /**
         * Sets a {@link com.android.launcher3.views.ClipIconView.TaskViewArtist} that should be
         * used draw a {@link TaskView} during this home animation.
         */
        public void setTaskViewArtist(ClipIconView.TaskViewArtist taskViewArtist) { }

        public boolean isAnimationReady() {
            return true;
        }

        public boolean isAnimatingIntoIcon() {
            return false;
        }

        @Nullable
        public TaskView getTargetTaskView() {
            return null;
        }

        public boolean isRtl() {
            return Utilities.isRtl(mContext.getResources());
        }

        public boolean isPortrait() {
            return !mDp.getDeviceProperties().isLandscape() && !mDp.isSeascape();
        }
    }

    /**
     * Update with start progress for window animation to home.
     * @param outMatrix {@link Matrix} to map a rect in Launcher space to window space.
     * @param startProgress The progress of {@link #mCurrentShift} to start thw window from.
     * @return {@link RectF} represents the bounds as starting point in window space.
     */
    protected RectF[] updateProgressForStartRect(Matrix[] outMatrix, float startProgress) {
        mCurrentShift.updateValue(startProgress);
        RectF[] startRects = new RectF[mRemoteTargetHandles.length];
        for (int i = 0, mRemoteTargetHandlesLength = mRemoteTargetHandles.length;
                i < mRemoteTargetHandlesLength; i++) {
            RemoteTargetHandle remoteHandle = mRemoteTargetHandles[i];
            TaskViewSimulator tvs = remoteHandle.getTaskViewSimulator();
            tvs.apply(remoteHandle.getTransformParams().setProgress(startProgress));

            startRects[i] = new RectF(tvs.getCurrentCropRect());
            outMatrix[i] = new Matrix();
            tvs.applyWindowToHomeRotation(outMatrix[i]);
            tvs.getCurrentMatrix().mapRect(startRects[i]);
        }
        return startRects;
    }

    /** Helper to avoid writing some for-loops to iterate over {@link #mRemoteTargetHandles} */
    protected void runActionOnRemoteHandles(Consumer<RemoteTargetHandle> consumer) {
        for (RemoteTargetHandle handle : mRemoteTargetHandles) {
            consumer.accept(handle);
        }
    }

    /** @return only the TaskViewSimulators from {@link #mRemoteTargetHandles} */
    protected TaskViewSimulator[] getRemoteTaskViewSimulators() {
        return Arrays.stream(mRemoteTargetHandles)
                .map(remoteTargetHandle -> remoteTargetHandle.getTaskViewSimulator())
                .toArray(TaskViewSimulator[]::new);
    }

    /**
     * Creates an animation that transforms the current app window into the home app.
     * @param startProgress The progress of {@link #mCurrentShift} to start the window from.
     * @param homeAnimationFactory The home animation factory.
     */
    protected RectFSpringAnim[] createWindowAnimationToHome(float startProgress,
            HomeAnimationFactory homeAnimationFactory) {
        // TODO(b/195473584) compute separate end targets for different staged split
        final RectF targetRect = homeAnimationFactory.getWindowTargetRect();
        RectFSpringAnim[] out = new RectFSpringAnim[mRemoteTargetHandles.length];
        Matrix[] homeToWindowPositionMap = new Matrix[mRemoteTargetHandles.length];
        RectF[] startRects = updateProgressForStartRect(homeToWindowPositionMap, startProgress);
        for (int i = 0, mRemoteTargetHandlesLength = mRemoteTargetHandles.length;
                i < mRemoteTargetHandlesLength; i++) {
            RemoteTargetHandle remoteHandle = mRemoteTargetHandles[i];
            out[i] = getWindowAnimationToHomeInternal(
                    homeAnimationFactory,
                    targetRect,
                    remoteHandle.getTransformParams(),
                    remoteHandle.getTaskViewSimulator(),
                    startRects[i],
                    homeToWindowPositionMap[i]);
        }
        return out;
    }

    protected void updateIsGestureForSplit(int targetCount) {
        mIsSwipeForSplit = targetCount > 1;
    }

    private RectFSpringAnim getWindowAnimationToHomeInternal(
            HomeAnimationFactory homeAnimationFactory,
            RectF targetRect,
            TransformParams transformParams,
            TaskViewSimulator taskViewSimulator,
            RectF startRect,
            Matrix homeToWindowPositionMap) {
        RectF cropRectF = new RectF(taskViewSimulator.getCurrentCropRect());
        // Move the startRect to Launcher space as floatingIconView runs in Launcher
        Matrix windowToHomePositionMap = new Matrix();

        TaskView targetTaskView = homeAnimationFactory.getTargetTaskView();
        RemoteAnimationTargets targets = transformParams.getTargetSet();
        if (!mIsSwipeForSplit && targetTaskView == null
                && homeAnimationFactory.supportsLandscapeAnimation()
                && targets != null && targets.apps.length == 1 && !cropRectF.isEmpty()
                && LandscapeAppAnimation.getClosingRotation(mContext, mDp,
                        targets.getFirstAppTarget(), taskViewSimulator.getOrientationState()
                                .getRecentsActivityRotation()) != 0f) {
            // Remove only the app-plane tilt from the spring's geometry. Using the rotated
            // bounding box here enlarges the first return frame when catching an opening app.
            Matrix untilted = new Matrix(taskViewSimulator.getCurrentMatrix());
            RectF mapped = new RectF(cropRectF);
            untilted.mapRect(mapped);
            untilted.postRotate(taskViewSimulator.getBaseMatrixRotation()
                            - LandscapeAppAnimation.getMatrixRotation(untilted),
                    mapped.centerX(), mapped.centerY());
            untilted.mapRect(startRect, cropRectF);
        }
        if (targetTaskView == null) {
            // If the start rect ends up overshooting too much to the left/right offscreen, bring it
            // back to fullscreen. This can happen when the recentsScroll value isn't aligned with
            // the pageScroll value for a given taskView, see b/228829958#comment12
            mRemoteTargetHandles[0].getTaskViewSimulator()
                    .getOrientationState()
                    .getOrientationHandler()
                    .fixBoundsForHomeAnimStartRect(startRect, mDp);

        }
        homeToWindowPositionMap.invert(windowToHomePositionMap);
        if (homeAnimationFactory.mStartWindow != null && targetTaskView == null) {
            startRect.set(homeAnimationFactory.mStartWindow);
        }
        windowToHomePositionMap.mapRect(startRect);
        RectF invariantStartRect = new RectF(startRect);

        if (targetTaskView != null) {
            Rect thumbnailBounds = new Rect();
            targetTaskView.getThumbnailBounds(thumbnailBounds, /* relativeToDragLayer= */ true);

            invariantStartRect = new RectF(thumbnailBounds);
            invariantStartRect.offsetTo(startRect.left, thumbnailBounds.top);
            startRect = new RectF(thumbnailBounds);
        }

        boolean useTaskbarHotseatParams = mDp.isTaskbarPresent
                && homeAnimationFactory.isInHotseat();
        RectFSpringAnim anim = new RectFSpringAnim(useTaskbarHotseatParams
                ? new TaskbarHotseatSpringConfig(mContext, startRect, targetRect)
                : new DefaultSpringConfig(mContext, mDp, startRect, targetRect));
        if (!useTaskbarHotseatParams && !mIsSwipeForSplit && targetTaskView == null
                && homeAnimationFactory.supportsLandscapeAnimation()) {
            anim.setMotionSettings(homeAnimationFactory.mMotion);
            if (homeAnimationFactory.mStartSizeVelocity != null) {
                float[] values = new float[9];
                windowToHomePositionMap.getValues(values);
                boolean swapped = Math.abs(values[Matrix.MSKEW_X])
                        > Math.abs(values[Matrix.MSCALE_X]);
                PointF velocity = homeAnimationFactory.mStartSizeVelocity;
                anim.setInitialSizeVelocity(swapped ? velocity.y : velocity.x,
                        swapped ? velocity.x : velocity.y);
            }
        }
        homeAnimationFactory.setAnimation(anim);

        SpringAnimationRunner runner = new SpringAnimationRunner(
                homeAnimationFactory,
                cropRectF,
                homeToWindowPositionMap,
                transformParams,
                taskViewSimulator,
                invariantStartRect, anim);
        anim.addAnimatorListener(runner);
        anim.addOnUpdateListener(runner);
        return anim;
    }

    protected class SpringAnimationRunner extends AnimationSuccessListener
            implements RectFSpringAnim.OnUpdateListener, BuilderProxy {

        private static final String TAG = "SpringAnimationRunner";

        final Rect mCropRect = new Rect();
        final Matrix mMatrix = new Matrix();

        final RectF mWindowCurrentRect = new RectF();
        final Matrix mHomeToWindowPositionMap;
        private final TransformParams mLocalTransformParams;
        final HomeAnimationFactory mAnimationFactory;

        final AnimatorPlaybackController mHomeAnim;
        final RectF mCropRectF;
        final float mVerticalCropAnchor;
        final int mCropSourceHeight;

        final float mStartRadius;
        final float mEndRadius;
        final float mLandscapeRotation;
        final float mStartMatrixRotation;
        final float mBaseMatrixRotation;
        final float mFullLandscapeRotation;
        final float mStartAppRotation;
        final DesktopAnimationSettings mMotion;
        @Nullable final AppWindowAnimationState.Session mMotionState;
        @Nullable final WindowAnimationSnapshot mSnapshot;
        private final Matrix mWindowToHome = new Matrix();
        private final Matrix mSnapshotMatrix = new Matrix();

        final RectF mRunningTaskViewStartRectF;
        private final RectFSpringAnim mWindowAnimation;
        @Nullable
        final TaskView mTargetTaskView;
        final float mRunningTaskViewScrollOffset;
        final float mTaskViewWidth;
        final float mTaskViewHeight;
        final boolean mIsPortrait;
        final Rect mThumbnailStartBounds = new Rect();

        // Store the mTargetTaskView view properties onAnimationStart so that we can reset them
        // when cleaning up.
        float mTaskViewAlpha;
        float mTaskViewTranslationX;
        float mTaskViewTranslationY;
        float mTaskViewScaleX;
        float mTaskViewScaleY;

        SpringAnimationRunner(
                HomeAnimationFactory factory,
                RectF cropRectF,
                Matrix homeToWindowPositionMap,
                TransformParams transformParams,
                TaskViewSimulator taskViewSimulator,
                RectF invariantStartRect, RectFSpringAnim windowAnimation) {
            mAnimationFactory = factory;
            mWindowAnimation = windowAnimation;
            mMotion = factory.mMotion;
            mHomeAnim = factory.createActivityAnimationToHome();
            mCropRectF = cropRectF;
            mHomeToWindowPositionMap = homeToWindowPositionMap;
            mLocalTransformParams = transformParams;

            cropRectF.roundOut(mCropRect);

            // End on a "round-enough" radius so that the shape reveal doesn't have to do too much
            // rounding at the end of the animation.
            mStartRadius = factory.mStartWindow == null
                    ? taskViewSimulator.getCurrentCornerRadius()
                    : factory.mStartWindowRadius * cropRectF.width()
                            / Math.max(1f, invariantStartRect.width());
            mEndRadius = factory.getEndRadius(cropRectF);

            mRunningTaskViewStartRectF = invariantStartRect;
            mTargetTaskView = factory.getTargetTaskView();
            final RemoteAnimationTargets targets = transformParams.getTargetSet();
            mVerticalCropAnchor = taskViewSimulator.getCurrentVerticalCropAnchor();
            mCropSourceHeight = targets != null && targets.apps.length == 1
                    ? targets.getFirstAppTarget().screenSpaceBounds.height() : 0;
            mMotionState = !mIsSwipeForSplit && mTargetTaskView == null
                    && factory.supportsLandscapeAnimation() && targets != null
                    && targets.apps.length == 1
                    ? AppWindowAnimationState.begin(targets.getFirstAppTargetTaskId(), mDp) : null;
            mFullLandscapeRotation = !mIsSwipeForSplit && mTargetTaskView == null
                    && factory.supportsLandscapeAnimation()
                    && targets != null && targets.apps.length == 1
                    && !cropRectF.isEmpty()
                    ? LandscapeAppAnimation.getClosingRotation(mContext, mDp,
                            targets.getFirstAppTarget(), taskViewSimulator.getOrientationState()
                                    .getRecentsActivityRotation()) : 0f;
            mStartMatrixRotation = LandscapeAppAnimation.getMatrixRotation(
                    taskViewSimulator.getCurrentMatrix());
            mBaseMatrixRotation = taskViewSimulator.getBaseMatrixRotation();
            mStartAppRotation = LandscapeAppAnimation.normalizeRotation(
                    mStartMatrixRotation - mBaseMatrixRotation);
            mLandscapeRotation = mFullLandscapeRotation == 0f ? 0f
                    : mFullLandscapeRotation - mStartAppRotation;
            factory.mLandscapeRotation = mLandscapeRotation;
            mTaskViewWidth = mTargetTaskView == null ? 0 : mTargetTaskView.getWidth();
            mTaskViewHeight = mTargetTaskView == null ? 0 : mTargetTaskView.getHeight();
            mIsPortrait = factory.isPortrait();
            // Use the running task's start position to determine how much it needs to be offset
            // to end up offscreen.
            mRunningTaskViewScrollOffset = factory.isRtl()
                    ? (Math.min(0, -invariantStartRect.right))
                    : (Math.max(0, mDp.getDeviceProperties().getWidthPx() - invariantStartRect.left));
            View host = factory.getAnimationHost();
            if (mMotionState != null && host != null && targets != null
                    && !targets.getFirstAppTarget().isTranslucent) {
                homeToWindowPositionMap.invert(mWindowToHome);
                mSnapshot = new WindowAnimationSnapshot(host, targets.getFirstAppTarget().leash,
                        mCropRect, mMotionState, SystemBarFollowAnimation.getSurface(targets,
                                targets.getFirstAppTarget()));
                windowAnimation.setOnCancelContinuation(() -> mSnapshot.continueAnimation(
                        windowAnimation, this::updateSnapshot));
            } else {
                mSnapshot = null;
            }
        }

        private float updateHomeWindowGeometry(RectF currentRect, float progress) {
            float radius = Utilities.mapRange(mMotionState != null
                    ? mMotion.cornerProgress(progress) : progress, mStartRadius, mEndRadius);
            mHomeToWindowPositionMap.mapRect(mWindowCurrentRect, currentRect);
            mMatrix.setRectToRect(mCropRectF, mWindowCurrentRect, ScaleToFit.FILL);
            if (mMotionState != null && !mWindowCurrentRect.isEmpty()) {
                LandscapeAppAnimation.applyClosingTransform(mMatrix, mCropRectF,
                        mWindowCurrentRect, mStartMatrixRotation, mLandscapeRotation,
                        progress, mCropRect, mMotion, mBaseMatrixRotation);
                AppWindowAnimationState.applyVerticalCropAnchor(mMatrix, mCropRect,
                        mCropSourceHeight, mVerticalCropAnchor);
                radius = Math.min(radius, Math.min(mCropRect.width(), mCropRect.height()) / 2f);
            }
            return radius;
        }

        private float getOpenness(float progress) {
            if (mFullLandscapeRotation == 0f) return 1f - progress;
            float rotationFraction = LandscapeAppAnimation.boundProgress(
                    (mStartAppRotation + mLandscapeRotation * mMotion.rotationProgress(progress))
                            / mFullLandscapeRotation);
            return 1f - (float) Math.pow(rotationFraction, 1f / mMotion.rotationResponse);
        }

        private void updateSnapshot(RectF currentRect, float progress) {
            float radius = updateHomeWindowGeometry(currentRect, progress);
            mSnapshotMatrix.setConcat(mWindowToHome, mMatrix);
            mSnapshot.update(mSnapshotMatrix, mCropRect, radius,
                    mAnimationFactory.getWindowAlpha(progress), currentRect,
                    getOpenness(progress), mSnapshotMatrix.mapRadius(radius), mVerticalCropAnchor);
        }

        @Override
        public void onUpdate(RectF currentRect, float progress) {
            if (mMotionState != null && !mMotionState.isActive()) return;
            float cornerRadius = Utilities.mapRange(mMotionState != null
                    ? mMotion.cornerProgress(progress) : progress,
                    mStartRadius, mEndRadius);
            float alpha = mAnimationFactory.getWindowAlpha(progress);

            mHomeAnim.setPlayFraction(progress);
            if (mTargetTaskView == null) {
                cornerRadius = updateHomeWindowGeometry(currentRect, progress);
                mAnimationFactory.setAppTransitionProgress(this,
                        mWindowAnimation.getTimelineProgress());
                mLocalTransformParams
                        .setTargetAlpha(alpha)
                        .setCornerRadius(cornerRadius);
            } else {
                mHomeToWindowPositionMap.mapRect(mWindowCurrentRect, mRunningTaskViewStartRectF);
                mWindowCurrentRect.offset(mRunningTaskViewScrollOffset * progress, 0f);
                mMatrix.setRectToRect(mCropRectF, mWindowCurrentRect, ScaleToFit.FILL);
                mLocalTransformParams.setCornerRadius(mStartRadius);
            }

            mLocalTransformParams.applySurfaceParams(
                    mLocalTransformParams.createSurfaceParams(this));
            if (mMotionState != null) {
                mMotionState.record(currentRect, getOpenness(progress),
                        mMatrix.mapRadius(cornerRadius), mVerticalCropAnchor);
            }

            mAnimationFactory.update(
                    currentRect,
                    progress,
                    mMatrix.mapRadius(cornerRadius),
                    mTargetTaskView == null ? 0 : (int) (alpha * 255));

            if (mTargetTaskView == null) {
                return;
            }
            if (mAnimationFactory.isAnimatingIntoIcon() && mAnimationFactory.isAnimationReady()) {
                mTargetTaskView.setAlpha(0f);
                return;
            }
            mTargetTaskView.setAlpha(mAnimationFactory.isAnimatingIntoIcon() ? 1f : alpha);
            float startWidth = mThumbnailStartBounds.width();
            float startHeight =  mThumbnailStartBounds.height();
            float currentWidth = currentRect.width();
            float currentHeight = currentRect.height();
            float scale;

            boolean isStartWidthValid = Float.compare(startWidth, 0f) > 0;
            boolean isStartHeightValid = Float.compare(startHeight, 0f) > 0;
            if (isStartWidthValid && isStartHeightValid) {
                scale = Math.min(currentWidth, currentHeight) / Math.min(startWidth, startHeight);
            } else {
                FileLog.e(TAG, "TaskView starting bounds are invalid: " + mThumbnailStartBounds);
                if (isStartWidthValid) {
                    scale = currentWidth / startWidth;
                } else if (isStartHeightValid) {
                    scale = currentHeight / startHeight;
                } else {
                    scale = 1f;
                }
            }

            if (Float.isNaN(scale)) {
                FileLog.e(TAG, "Scale is NaN: starting dimensions=[" + startWidth + ", "
                        + startHeight + "], current dimensions=[" + currentWidth + ", "
                        + currentHeight + "]");
            }

            mTargetTaskView.setScaleX(scale);
            mTargetTaskView.setScaleY(scale);
            mTargetTaskView.setTranslationX(
                    currentRect.centerX() - mThumbnailStartBounds.centerX());
            mTargetTaskView.setTranslationY(
                    currentRect.centerY() - mThumbnailStartBounds.centerY());
        }

        @Override
        public void onBuildTargetParams(SurfaceProperties builder, RemoteAnimationTarget app,
                TransformParams params) {
            builder.setMatrix(mMatrix)
                    .setWindowCrop(mCropRect)
                    .setCornerRadius(params.getCornerRadius());
        }

        @Override
        public void onCancel() {
            if (mMotionState != null) mMotionState.cancel();
            cleanUp();
            mAnimationFactory.onCancel();
        }

        @Override
        public void onAnimationStart(Animator animation) {
            if (mTargetTaskView == null) {
                mAnimationFactory.beginAppTransition(this);
            }
            setUp();
            mHomeAnim.dispatchOnStart();
            if (mTargetTaskView == null) {
                return;
            }
            Rect thumbnailBounds = new Rect();
            // Use bounds relative to mTargetTaskView since it will be scaled afterwards
            mTargetTaskView.getThumbnailBounds(thumbnailBounds);
            mAnimationFactory.setTaskViewArtist(new ClipIconView.TaskViewArtist(
                    mTargetTaskView::draw,
                    0f,
                    -thumbnailBounds.top,
                    Math.min(mTaskViewHeight, mTaskViewWidth),
                    mIsPortrait));
        }

        private void setUp() {
            if (mTargetTaskView == null) {
                return;
            }
            RecentsView recentsView = mTargetTaskView.getRecentsView();
            if (recentsView != null) {
                recentsView.setOffsetMidpointIndexOverride(
                        recentsView.indexOfChild(mTargetTaskView));
            }
            mTargetTaskView.getThumbnailBounds(
                    mThumbnailStartBounds, /* relativeToDragLayer= */ true);
            mTaskViewAlpha = mTargetTaskView.getAlpha();
            if (mAnimationFactory.isAnimatingIntoIcon()) {
                return;
            }
            mTaskViewTranslationX = mTargetTaskView.getTranslationX();
            mTaskViewTranslationY = mTargetTaskView.getTranslationY();
            mTaskViewScaleX = mTargetTaskView.getScaleX();
            mTaskViewScaleY = mTargetTaskView.getScaleY();
        }

        private void cleanUp() {
            mAnimationFactory.endAppTransition(this);
            if (mTargetTaskView == null) {
                return;
            }
            RecentsView recentsView = mTargetTaskView.getRecentsView();
            if (recentsView != null) {
                recentsView.setOffsetMidpointIndexOverride(INVALID_PAGE);
            }
            mTargetTaskView.setAlpha(mTaskViewAlpha);
            if (!mAnimationFactory.isAnimatingIntoIcon()) {
                mTargetTaskView.setTranslationX(mTaskViewTranslationX);
                mTargetTaskView.setTranslationY(mTaskViewTranslationY);
                mTargetTaskView.setScaleX(mTaskViewScaleX);
                mTargetTaskView.setScaleY(mTaskViewScaleY);
                return;
            }
            mAnimationFactory.setTaskViewArtist(null);
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            super.onAnimationEnd(animation);
            if (mSnapshot != null) mSnapshot.onSourceFinished();
        }

        @Override
        public void onAnimationSuccess(Animator animator) {
            if (mMotionState != null && !mMotionState.isActive()) {
                cleanUp();
                return;
            }
            if (mMotionState != null) mMotionState.finish();
            cleanUp();
            mHomeAnim.getAnimationPlayer().end();
        }
    }

    public interface RunningWindowAnim {
        void end();

        void cancel();

        static RunningWindowAnim wrap(Animator animator) {
            return new RunningWindowAnim() {
                @Override
                public void end() {
                    animator.end();
                }

                @Override
                public void cancel() {
                    animator.cancel();
                }
            };
        }

        static RunningWindowAnim wrap(RectFSpringAnim rectFSpringAnim) {
            return new RunningWindowAnim() {
                @Override
                public void end() {
                    rectFSpringAnim.end();
                }

                @Override
                public void cancel() {
                    rectFSpringAnim.cancel();
                }
            };
        }
    }
}
