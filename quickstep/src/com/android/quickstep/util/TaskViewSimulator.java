/*
 * Copyright (C) 2020 The Android Open Source Project
 * Modified by the ArkUI Project in 2026 for stacked cards and interruptible landscape motion.
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
package com.android.quickstep.util;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import static com.android.launcher3.states.RotationHelper.deltaRotation;
import static com.android.launcher3.touch.PagedOrientationHandler.MATRIX_POST_TRANSLATE;
import static com.android.launcher3.util.OverviewReleaseFlags.enableGridOnlyOverview;
import static com.android.quickstep.util.RecentsOrientedState.postDisplayRotation;
import static com.android.quickstep.util.RecentsOrientedState.preDisplayRotation;
import static com.android.wm.shell.Flags.enableFlexibleTwoAppSplit;
import static com.android.wm.shell.shared.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT;
import static com.android.wm.shell.shared.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT;
import static com.android.wm.shell.shared.split.SplitScreenConstants.SPLIT_POSITION_UNDEFINED;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.Utilities;
import com.android.launcher3.anim.AnimatedFloat;
import com.android.launcher3.anim.DesktopAnimationSettings;
import com.android.launcher3.anim.PendingAnimation;
import com.android.launcher3.util.TraceHelper;
import com.android.quickstep.BaseActivityInterface;
import com.android.quickstep.BaseContainerInterface;
import com.android.quickstep.DesktopFullscreenDrawParams;
import com.android.quickstep.FallbackWindowInterface;
import com.android.quickstep.FullscreenDrawParams;
import com.android.quickstep.TaskAnimationManager;
import com.android.quickstep.util.SurfaceTransaction.SurfaceProperties;
import com.android.systemui.shared.recents.model.ThumbnailData;
import com.android.systemui.shared.recents.utilities.PreviewPositionHelper;
import com.android.wm.shell.shared.split.SplitBounds;
import com.android.wm.shell.shared.split.SplitScreenConstants;

/**
 * A utility class which emulates the layout behavior of TaskView and RecentsView
 */
public class TaskViewSimulator implements TransformParams.BuilderProxy {

    private static final String TAG = "TaskViewSimulator";
    private static final boolean DEBUG = false;

    private final Rect mTmpCropRect = new Rect();
    private final RectF mTempRectF = new RectF();
    private final float[] mTempPoint = new float[2];

    private final Context mContext;
    private final BaseContainerInterface mSizeStrategy;

    @NonNull
    private RecentsOrientedState mOrientationState;
    private final boolean mIsRecentsRtl;

    private final Rect mTaskRect = new Rect();
    private final Rect mFullTaskSize = new Rect();
    private final Rect mCarouselTaskSize = new Rect();
    private PointF mPivotOverride = null;
    private final PointF mPivot = new PointF();
    private DeviceProfile mDp;
    @SplitScreenConstants.SplitPosition
    private int mSplitPosition = SPLIT_POSITION_UNDEFINED;

    private final Matrix mMatrix = new Matrix();
    private final Matrix mMatrixTmp = new Matrix();
    private float mBaseMatrixRotation;

    /** Buffer/display rotation, before adding any app-plane animation. */
    public float getBaseMatrixRotation() { return mBaseMatrixRotation; }

    // Thumbnail view properties
    private final Rect mThumbnailPosition = new Rect();
    private final ThumbnailData mThumbnailData = new ThumbnailData();
    private final PreviewPositionHelper mPositionHelper = new PreviewPositionHelper();
    private final Matrix mInversePositionMatrix = new Matrix();

    // TaskView properties
    private final FullscreenDrawParams mCurrentFullscreenParams;
    public final AnimatedFloat taskPrimaryTranslation = new AnimatedFloat();
    public final AnimatedFloat taskSecondaryTranslation = new AnimatedFloat();
    public final AnimatedFloat taskGridTranslationX = new AnimatedFloat();
    public final AnimatedFloat taskGridTranslationY = new AnimatedFloat();

    // Carousel properties
    public final AnimatedFloat carouselScale = new AnimatedFloat();
    private float mStackScale = 1f;
    private float mStackTranslationX;
    private float mStackAlpha = 1f;
    @Nullable private LandscapeLaunch mLandscapeLaunch;
    public final AnimatedFloat landscapeLaunchProgress = new AnimatedFloat();
    @Nullable private RemoteAnimationTarget mPreviewTarget;
    @Nullable private AppWindowAnimationState.Pose mGestureSeed;
    @Nullable private AppWindowAnimationState.GestureHandoff mGestureHandoff;
    private boolean mGestureSeedRead;
    private float mGestureSeedRotation;
    private float mGestureCornerRadius = -1f;
    private final RectF mSeedRect = new RectF();
    private final RectF mGestureRect = new RectF();
    private final RectF mGestureSource = new RectF();
    private final RectF mGestureBaseRect = new RectF();
    private DesktopAnimationSettings mGestureMotion;
    private boolean mRelativeGesture;
    private float mAppLaunchHandoffProgress;
    private float mGestureProgressFloor = 1f;
    private float mGestureStartProgress = Float.NaN;
    private float mGestureSeedWeight = 1f;
    @Nullable private LandscapeLaunch mInterruptedLaunch;
    private final Matrix mInterruptedMatrix = new Matrix();
    private final Rect mInterruptedCrop = new Rect();
    private final float[] mInterruptedValues = new float[9];
    private final float[] mCurrentValues = new float[9];
    private float mInterruptedProgress, mInterruptedWeight, mInterruptedRadius;

    /** Unfold the actual thumbnail transform into the app buffer's final coordinate space. */
    public void prepareLandscapeLaunch(RemoteAnimationTarget target,
            DesktopAnimationSettings motion) {
        LandscapeLaunch previous = mLandscapeLaunch != null ? mLandscapeLaunch : mInterruptedLaunch;
        float previousRadius = getCurrentCornerRadius();
        mGestureSeed = null;
        mGestureHandoff = null;
        mGestureSeedRead = true;
        mGestureCornerRadius = -1f;
        mInterruptedLaunch = null;
        mLandscapeLaunch = null;
        if (mDp == null || mSplitBounds != null || mIsDesktopTask
                || LandscapeAppAnimation.getOpeningRotation(mContext, mDp, target,
                        target.rotationChange) == 0f) return;
        mLandscapeLaunch = new LandscapeLaunch(target, motion);
        landscapeLaunchProgress.updateValue(0f);
        if (previous != null && previous.initialized) {
            // A launch interrupted by another launch starts at the last submitted surface pose.
            mLandscapeLaunch.capture(mMatrix, mTmpCropRect, previousRadius);
        } else {
            // Fixed-rotation targets carry a landscape buffer even while overview stays in
            // portrait. Using the gesture's touch rotation here loses the quarter-turn.
            setPreviewBounds(target.screenSpaceBounds, target.contentInsets);
        }
        mLayoutValid = false;
    }

    public void finishLandscapeLaunch(boolean success) {
        if (!success && mLandscapeLaunch != null && mLandscapeLaunch.initialized) {
            mInterruptedLaunch = mLandscapeLaunch;
            mInterruptedMatrix.set(mMatrix);
            mInterruptedMatrix.getValues(mInterruptedValues);
            mInterruptedCrop.set(mTmpCropRect);
            mInterruptedRadius = mLandscapeLaunch.cornerRadius;
            mInterruptedProgress = fullScreenProgress.value;
            mInterruptedWeight = 1f;
        }
        mLandscapeLaunch = null;
        mLayoutValid = false;
    }

    private static final class LandscapeLaunch {
        final Rect endCrop;
        final Rect startCrop = new Rect();
        final RectF mappedCrop = new RectF();
        final float[] values = new float[9];
        final int bufferRotation;
        final float endX, endY;
        final DesktopAnimationSettings motion;
        boolean initialized;
        float startX, startY, startScale, startAngle, startRadius, cornerRadius;

        LandscapeLaunch(RemoteAnimationTarget target, DesktopAnimationSettings settings) {
            motion = settings;
            endCrop = new Rect(0, 0, target.screenSpaceBounds.width(),
                    target.screenSpaceBounds.height());
            bufferRotation = target.windowConfiguration.getRotation();
            endX = (target.localBounds == null ? target.position.x : target.localBounds.left)
                    + endCrop.exactCenterX();
            endY = (target.localBounds == null ? target.position.y : target.localBounds.top)
                    + endCrop.exactCenterY();
        }

        void capture(Matrix matrix, Rect crop, float radius) {
            startCrop.set(crop);
            mappedCrop.set(crop);
            matrix.mapRect(mappedCrop);
            startX = mappedCrop.centerX();
            startY = mappedCrop.centerY();
            matrix.getValues(values);
            startScale = (float) Math.hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y]);
            startAngle = (float) Math.toDegrees(
                    Math.atan2(values[Matrix.MSKEW_Y], values[Matrix.MSCALE_X]));
            startRadius = radius * startScale;
            cornerRadius = radius;
            initialized = true;
        }

        void apply(Matrix matrix, Rect crop, float progress, float endRadius) {
            float p = LandscapeAppAnimation.boundProgress(progress);
            float scale = Utilities.mapRange(p, startScale, 1f);
            // Interpolate visible size once. Easing crop and scale independently multiplies
            // their rates and makes a recent-app launch accelerate differently from an icon.
            float width = Utilities.mapRange(p, startCrop.width() * startScale, endCrop.width());
            float height = Utilities.mapRange(p, startCrop.height() * startScale, endCrop.height());
            float cropX = Utilities.mapRange(p, startCrop.exactCenterX(), endCrop.exactCenterX());
            float cropY = Utilities.mapRange(p, startCrop.exactCenterY(), endCrop.exactCenterY());
            float halfWidth = width / Math.max(0.001f, scale) / 2f;
            float halfHeight = height / Math.max(0.001f, scale) / 2f;
            crop.set(Math.round(cropX - halfWidth), Math.round(cropY - halfHeight),
                    Math.round(cropX + halfWidth), Math.round(cropY + halfHeight));
            matrix.setTranslate(-crop.exactCenterX(), -crop.exactCenterY());
            matrix.postScale(scale, scale);
            matrix.postRotate(startAngle * motion.rotationProgress(1f - p));
            matrix.postTranslate(Utilities.mapRange(p, startX, endX),
                    Utilities.mapRange(p, startY, endY));
            cornerRadius = Math.min(Utilities.mapRange(p, startRadius, endRadius)
                    / Math.max(0.001f, scale), Math.min(crop.width(), crop.height()) / 2f);
        }
    }

    /** Match the extra TaskView transform without replacing gesture/launch transforms. */
    public void setStackTransform(float scale, float translationX, float alpha) {
        mStackScale = scale;
        mStackTranslationX = translationX;
        mStackAlpha = alpha;
    }

    // RecentsView properties
    public final AnimatedFloat recentsViewScale = new AnimatedFloat();
    public final AnimatedFloat fullScreenProgress = new AnimatedFloat();
    public final AnimatedFloat recentsViewSecondaryTranslation = new AnimatedFloat();
    public final AnimatedFloat recentsViewPrimaryTranslation = new AnimatedFloat();
    public final AnimatedFloat recentsViewScroll = new AnimatedFloat();

    // Cached calculations
    private boolean mLayoutValid = false;
    private int mOrientationStateId;
    private SplitBounds mSplitBounds;
    private Boolean mDrawsBelowRecents = null;
    private Boolean mDrawAboveOtherApps = null;
    private boolean mIsGridTask;
    private final boolean mIsDesktopTask;
    private boolean mIsAnimatingToCarousel = false;
    private final int mDesktopTaskIndex;

    @Nullable
    private Matrix mTaskRectTransform = null;
    @Nullable
    private Matrix mTaskRectTransformInverse = null;

    public TaskViewSimulator(Context context, BaseContainerInterface sizeStrategy,
            boolean isDesktop, int desktopTaskIndex) {
        mContext = context;
        mSizeStrategy = sizeStrategy;
        mIsDesktopTask = isDesktop;
        mDesktopTaskIndex = desktopTaskIndex;

        mOrientationState = TraceHelper.allowIpcs("TaskViewSimulator.init",
                () -> new RecentsOrientedState(context, sizeStrategy, i -> { }));
        mOrientationState.setGestureActive(true);
        mCurrentFullscreenParams = mIsDesktopTask
                ? new DesktopFullscreenDrawParams(context)
                : new FullscreenDrawParams(context);
        mOrientationStateId = mOrientationState.getStateId();
        Resources resources = context.getResources();
        mIsRecentsRtl = mOrientationState.getOrientationHandler().getRecentsRtlSetting(resources);
        carouselScale.value = 1f;
    }

    /**
     * Sets the device profile for the current state
     */
    public void setDp(DeviceProfile dp) {
        mDp = dp;
        mLayoutValid = false;
        mOrientationState.setDeviceProfile(dp);
        if (enableGridOnlyOverview()) {
            mIsGridTask = dp.getDeviceProperties().isTablet() && !mIsDesktopTask;
        }
        calculateTaskSize();
    }

    /**
     * Updates the task size.
     */
    public void calculateTaskSize() {
        if (mDp == null) {
            return;
        }

        if (mIsGridTask) {
            mSizeStrategy.calculateGridTaskSize(mContext, mDp, mFullTaskSize,
                    mOrientationState.getOrientationHandler());
            if (enableGridOnlyOverview()) {
                mSizeStrategy.calculateTaskSize(mContext, mDp, mCarouselTaskSize,
                        mOrientationState.getOrientationHandler());
            }
        } else {
            mSizeStrategy.calculateTaskSize(mContext, mDp, mFullTaskSize,
                    mOrientationState.getOrientationHandler());
            if (enableGridOnlyOverview()) {
                mCarouselTaskSize.set(mFullTaskSize);
            }
        }

        if (mSplitBounds != null) {
            // The task rect changes according to the staged split task sizes, but recents
            // fullscreen scale and pivot remains the same since the task fits into the existing
            // sized task space bounds
            mTaskRect.set(mFullTaskSize);
            mOrientationState.getOrientationHandler()
                    .setSplitTaskSwipeRect(mDp, mTaskRect, mSplitBounds, mSplitPosition);
        } else if (mIsDesktopTask) {
            // For desktop, tasks can take up only part of the screen size.
            // Full task size represents the whole screen size, but scaled down to fit in recents.
            // Task rect will represent the scaled down thumbnail position and is placed inside
            // full task size as it is on the home screen.
            PointF fullscreenTaskDimension = new PointF();
            BaseActivityInterface.getTaskDimension(mDp, fullscreenTaskDimension);
            // Calculate the scale down factor used in recents
            float scale = mFullTaskSize.width() / fullscreenTaskDimension.x;
            mTaskRect.set(mThumbnailPosition);
            mTaskRect.scale(scale);
            // Ensure the task rect is inside the full task rect
            mTaskRect.offset(mFullTaskSize.left, mFullTaskSize.top);

            Rect taskDimension = new Rect(0, 0, (int) fullscreenTaskDimension.x,
                    (int) fullscreenTaskDimension.y);
            mTmpCropRect.set(mThumbnailPosition);
            if (mTmpCropRect.setIntersect(taskDimension, mThumbnailPosition)) {
                mTmpCropRect.offset(-mThumbnailPosition.left, -mThumbnailPosition.top);
            } else {
                mTmpCropRect.setEmpty();
            }
        } else {
            mTaskRect.set(mFullTaskSize);
        }
    }

    /**
     * Sets the orientation state used for this animation
     */
    public void setOrientationState(@NonNull RecentsOrientedState orientationState) {
        mOrientationState = orientationState;
        mLayoutValid = false;
    }

    /**
     * @see com.android.quickstep.views.RecentsView#FULLSCREEN_PROGRESS
     */
    public float getFullScreenScale() {
        if (mDp == null) {
            return 1;
        }
        float scale = mOrientationState.getFullScreenScaleAndPivot(
                mIsAnimatingToCarousel ? mCarouselTaskSize : mFullTaskSize, mDp, mPivot);
        if (mPivotOverride != null) {
            mPivot.set(mPivotOverride);
        }
        return scale;
    }

    /**
     * Sets the targets which the simulator will control specifically for targets to animate when
     * in split screen
     *
     * @param splitInfo set to {@code null} when not in staged split mode
     */
    public void setPreview(RemoteAnimationTarget runningTarget, SplitBounds splitInfo) {
        mLandscapeLaunch = null;
        mPreviewTarget = runningTarget;
        mGestureHandoff = null;
        mGestureSeedRead = false;
        mGestureSeed = null;
        mGestureCornerRadius = -1f;
        mGestureProgressFloor = 1f;
        mGestureStartProgress = Float.NaN;
        mGestureSeedWeight = 1f;
        mRelativeGesture = false;
        mAppLaunchHandoffProgress = 0f;
        mGestureBaseRect.setEmpty();
        mInterruptedLaunch = null;
        mSplitBounds = splitInfo;
        if (mSplitBounds == null) {
            setPreviewBounds(
                    runningTarget.startBounds != null
                            ? runningTarget.startBounds : runningTarget.screenSpaceBounds,
                    runningTarget.contentInsets);
            mSplitPosition = SPLIT_POSITION_UNDEFINED;
        } else {
            // Always use the end bounds for split as we may be reparenting the task from fullscreen
            setPreviewBounds(runningTarget.screenSpaceBounds, runningTarget.contentInsets);
            mSplitPosition = runningTarget.taskId == splitInfo.leftTopTaskId
                    ? SPLIT_POSITION_TOP_OR_LEFT : SPLIT_POSITION_BOTTOM_OR_RIGHT;
            if (enableFlexibleTwoAppSplit()) {
                mPositionHelper.setSplitBounds(mSplitBounds, mSplitPosition);
            }
        }
        calculateTaskSize();
    }

    /** Set before the first surface update, after assigning this gesture's remote targets. */
    public void setGestureHandoff(@Nullable AppWindowAnimationState.GestureHandoff handoff) {
        mGestureHandoff = handoff;
        mGestureSeedRead = false;
    }

    public boolean hasAppLaunchHandoff() {
        return mRelativeGesture && mGestureSeed != null;
    }

    /** Settle a captured launch to the normal app/overview endpoint after the finger is lifted. */
    public void setAppLaunchHandoffProgress(float progress) {
        mAppLaunchHandoffProgress = Utilities.boundToRange(progress, 0f, 1f);
    }

    /**
     * Sets the targets which the simulator will control
     */
    public void setPreviewBounds(Rect bounds, Rect insets) {
        mThumbnailData.insets.set(insets);
        // TODO: What is this?
        mThumbnailData.windowingMode = WINDOWING_MODE_FULLSCREEN;

        mThumbnailPosition.set(bounds);
        mLayoutValid = false;
    }

    /**
     * Updates the scroll for RecentsView
     */
    public void setScroll(float scroll) {
        recentsViewScroll.value = scroll;
    }

    public void setDrawsBelowRecents(boolean drawsBelowRecents) {
        mDrawsBelowRecents = drawsBelowRecents;
    }

    public boolean getDrawsBelowRecents() {
        return mDrawsBelowRecents != null ? mDrawsBelowRecents : false;
    }

    /**
     * Sets whether the task is part of overview grid and not being focused.
     */
    public void setIsGridTask(boolean isGridTask) {
        mIsGridTask = isGridTask;
    }

    /**
     * Sets whether drawing this app above other apps during animation. It's currently used when
     * activating an app window from the exploded desktop view which will launch the desktop tile
     * and exit Overview.
     */
    public void setDrawsAboveOtherApps(boolean drawsAboveOtherApps) {
        mDrawAboveOtherApps = drawsAboveOtherApps;
    }

    /**
     * Override the pivot used to apply scale changes.
     */
    public void setPivotOverride(PointF pivotOverride) {
        mPivotOverride = pivotOverride;
        getFullScreenScale();
    }

    /**
     * Adds animation for all the components corresponding to transition from an app to carousel.
     */
    public void addAppToCarouselAnim(PendingAnimation pa, Interpolator interpolator,
            boolean isHandlingAtomicEvent) {
        pa.addFloat(fullScreenProgress, AnimatedFloat.VALUE, 1, 0, interpolator);
        if (enableGridOnlyOverview() && mDp.getDeviceProperties().isTablet() && !isHandlingAtomicEvent) {
            mIsAnimatingToCarousel = true;
            carouselScale.value = mCarouselTaskSize.width() / (float) mFullTaskSize.width();
        }
        pa.addFloat(recentsViewScale, AnimatedFloat.VALUE, getFullScreenScale(), 1,
                interpolator);
    }

    /**
     * Adds animation for all the components corresponding to transition from overview to the app.
     */
    public void addOverviewToAppAnim(PendingAnimation pa, TimeInterpolator interpolator) {
        pa.addFloat(fullScreenProgress, AnimatedFloat.VALUE, 0, 1, interpolator);
        pa.addFloat(recentsViewScale, AnimatedFloat.VALUE, 1, getFullScreenScale(), interpolator);
    }

    /**
     * Returns the current clipped/visible window bounds in the window coordinate space
     */
    public RectF getCurrentCropRect() {
        if ((mLandscapeLaunch != null && mLandscapeLaunch.initialized)
                || mGestureCornerRadius >= 0f || mInterruptedLaunch != null) {
            mTempRectF.set(mTmpCropRect);
            return mTempRectF;
        }
        // Crop rect is the inverse of thumbnail matrix
        mTempRectF.set(0, 0, mTaskRect.width(), mTaskRect.height());
        mInversePositionMatrix.mapRect(mTempRectF);
        return mTempRectF;
    }

    /**
     * Returns the current task bounds in the Launcher coordinate space.
     */
    public RectF getCurrentRect() {
        RectF result = getCurrentCropRect();
        mMatrixTmp.set(mMatrix);
        preDisplayRotation(mOrientationState.getDisplayRotation(), mDp.getDeviceProperties().getWidthPx(), mDp.getDeviceProperties().getHeightPx(),
                mMatrixTmp);
        mMatrixTmp.mapRect(result);
        return result;
    }

    public RecentsOrientedState getOrientationState() {
        return mOrientationState;
    }

    /**
     * Returns the current transform applied to the window
     */
    public Matrix getCurrentMatrix() {
        return mMatrix;
    }

    /**
     * Sets a matrix used to transform the position of tasks. If set, this matrix is applied to
     * the task rect after the task has been scaled and positioned inside the fulltask, but
     * before scaling and translation of the whole recents view is performed.
     */
    public void setTaskRectTransform(@Nullable Matrix taskRectTransform) {
        mTaskRectTransform = taskRectTransform;
        if (mTaskRectTransform != null) {
            // The inverse transform is used to adjust the corner radius of tasks. Since not all
            // tasks will have a taskRectTransform, we construct it lazily.
            if (mTaskRectTransformInverse == null) {
                mTaskRectTransformInverse = new Matrix();
            }
            mTaskRectTransform.invert(mTaskRectTransformInverse);
        }
    }

    /**
     * Calculates the crop rect for desktop tasks given the current matrix.
     */
    private void calculateDesktopTaskCropRect() {
        // The approach here is to map a rect that represents the untransformed thumbnail position
        // using the current matrix. This will give us a rect that can be intersected with
        // [mFullTaskSize]. Using the intersection, we then compute how much of the task window that
        // needs to be cropped (which will be nothing if the window is entirely within the desktop).
        mTempRectF.set(0, 0, mThumbnailPosition.width(), mThumbnailPosition.height());
        mMatrix.mapRect(mTempRectF);

        float offsetX = mTempRectF.left;
        float offsetY = mTempRectF.top;
        float scale = mThumbnailPosition.width() / mTempRectF.width();

        if (mTempRectF.intersect(mFullTaskSize.left, mFullTaskSize.top, mFullTaskSize.right,
                mFullTaskSize.bottom)) {
            mTempRectF.offset(-offsetX, -offsetY);
            mTempRectF.scale(scale);
            mTempRectF.round(mTmpCropRect);
        }
    }

    /**
     * Applies the rotation on the matrix to so that it maps from launcher coordinate space to
     * window coordinate space.
     */
    public void applyWindowToHomeRotation(Matrix matrix) {
        matrix.postTranslate(mDp.getDeviceProperties().getWindowX(), mDp.getDeviceProperties().getWindowY());
        postDisplayRotation(deltaRotation(
                        mOrientationState.getRecentsActivityRotation(),
                        mOrientationState.getDisplayRotation()),
                mDp.getDeviceProperties().getWidthPx(), mDp.getDeviceProperties().getHeightPx(), matrix);
    }

    /**
     * Applies the target to the previously set parameters
     */
    public void apply(TransformParams params) {
        apply(params, null);
    }

    /**
     * Applies the target to the previously set parameters, optionally with an overridden
     * surface transaction
     */
    public void apply(TransformParams params, @Nullable SurfaceTransaction surfaceTransaction) {
        apply(params, surfaceTransaction, false);
    }

    /** The first handed-off frame must not wait for a possibly hidden Launcher draw. */
    public void applyImmediately(TransformParams params) {
        apply(params, null, true);
    }

    /** Build the first gesture frame without exposing a new leash before Shell's atomic start. */
    public void applyToTransaction(TransformParams params, SurfaceControl.Transaction transaction) {
        apply(params, null, false, transaction);
    }

    private void apply(TransformParams params, @Nullable SurfaceTransaction surfaceTransaction,
            boolean applyImmediately) {
        apply(params, surfaceTransaction, applyImmediately, null);
    }

    private void apply(TransformParams params, @Nullable SurfaceTransaction surfaceTransaction,
            boolean applyImmediately, @Nullable SurfaceControl.Transaction firstFrame) {
        if (mDp == null || mThumbnailPosition.isEmpty()) {
            return;
        }
        if (!mGestureSeedRead) {
            mGestureSeedRead = true;
            if (mPreviewTarget != null && mSplitBounds == null && !mIsDesktopTask) {
                mRelativeGesture = mGestureHandoff != null;
                mGestureSeed = mGestureHandoff == null
                        ? AppWindowAnimationState.peek(mPreviewTarget.taskId, mDp)
                        : mGestureHandoff.getPose(mPreviewTarget.taskId, mDp);
                mGestureHandoff = null;
                if (mGestureSeed != null) {
                    mGestureMotion = DesktopAnimationSettings.read(mContext);
                    mGestureSeedRotation = Float.isFinite(mGestureSeed.appRotation)
                            ? mGestureSeed.appRotation
                            : LandscapeAppAnimation.getClosingRotation(mContext, mDp,
                                    mPreviewTarget, mOrientationState.getRecentsActivityRotation())
                                    * mGestureMotion.rotationProgress(1f - mGestureSeed.openness);
                }
            }
        }
        if (!mLayoutValid || mOrientationStateId != mOrientationState.getStateId()) {
            mLayoutValid = true;
            mOrientationStateId = mOrientationState.getStateId();

            getFullScreenScale();
            if (mLandscapeLaunch != null || mInterruptedLaunch != null) {
                mThumbnailData.rotation = (mLandscapeLaunch != null
                        ? mLandscapeLaunch : mInterruptedLaunch).bufferRotation;
            } else if (TaskAnimationManager.SHELL_TRANSITIONS_ROTATION) {
                // With shell transitions, the display is rotated early so we need to actually use
                // the rotation when the gesture starts
                mThumbnailData.rotation = mOrientationState.getTouchRotation();
            } else {
                mThumbnailData.rotation = mOrientationState.getDisplayRotation();
            }

            // mIsRecentsRtl is the inverse of TaskView RTL.
            boolean isRtlEnabled = !mIsRecentsRtl;
            mPositionHelper.updateThumbnailMatrix(
                    mThumbnailPosition, mThumbnailData, mTaskRect.width(), mTaskRect.height(),
                    mDp.getDeviceProperties().isTablet(),
                    mOrientationState.getRecentsActivityRotation(), isRtlEnabled,
                    mContext.getResources().getDisplayMetrics().densityDpi);
            mPositionHelper.getMatrix().invert(mInversePositionMatrix);
            if (DEBUG) {
                Log.d(TAG, " taskRect: " + mTaskRect);
            }
        }

        float fullScreenProgress = Utilities.boundToRange(this.fullScreenProgress.value, 0, 1);
        mCurrentFullscreenParams.setProgress(fullScreenProgress, recentsViewScale.value,
                carouselScale.value * mStackScale);

        // Apply thumbnail matrix
        float taskWidth = mTaskRect.width();
        float taskHeight = mTaskRect.height();

        mMatrix.set(mPositionHelper.getMatrix());

        // Apply TaskView matrix: taskRect, optional transform, translate
        mMatrix.postTranslate(mTaskRect.left, mTaskRect.top);
        if (mTaskRectTransform != null) {
            mMatrix.postConcat(mTaskRectTransform);

            // Calculate cropping for desktop tasks. The order is important since it uses the
            // current matrix. Therefore we calculate it here, after applying the task rect
            // transform, but before applying scaling/translation that affects the whole
            // recentsview.
            if (mIsDesktopTask) {
                calculateDesktopTaskCropRect();
            }
        }

        // TaskView scales its contents before applying dismissal/neighbor translations. Keep
        // the live surface in the same space so reflow is not scaled a second time.
        mMatrix.postScale(mStackScale, mStackScale,
                mCarouselTaskSize.centerX(), mCarouselTaskSize.centerY());
        mOrientationState.getOrientationHandler().setPrimary(mMatrix, MATRIX_POST_TRANSLATE,
                taskPrimaryTranslation.value);
        mOrientationState.getOrientationHandler().setSecondary(mMatrix, MATRIX_POST_TRANSLATE,
                taskSecondaryTranslation.value);
        mMatrix.postTranslate(taskGridTranslationX.value, taskGridTranslationY.value);

        mMatrix.postScale(carouselScale.value, carouselScale.value,
                mIsRecentsRtl ? mCarouselTaskSize.right : mCarouselTaskSize.left,
                mCarouselTaskSize.top);

        mMatrix.postTranslate(mStackTranslationX, 0f);

        mOrientationState.getOrientationHandler().setPrimary(
                mMatrix, MATRIX_POST_TRANSLATE, recentsViewScroll.value);

        // Apply RecentsView matrix
        mMatrix.postScale(recentsViewScale.value, recentsViewScale.value, mPivot.x, mPivot.y);
        mOrientationState.getOrientationHandler().setSecondary(mMatrix, MATRIX_POST_TRANSLATE,
                recentsViewSecondaryTranslation.value);
        mOrientationState.getOrientationHandler().setPrimary(mMatrix, MATRIX_POST_TRANSLATE,
                recentsViewPrimaryTranslation.value);
        applyWindowToHomeRotation(mMatrix);

        if (!mIsDesktopTask) {
            // Crop rect is the inverse of thumbnail matrix
            mTempRectF.set(0, 0, taskWidth, taskHeight);
            mInversePositionMatrix.mapRect(mTempRectF);
            mTempRectF.roundOut(mTmpCropRect);
        }

        mBaseMatrixRotation = LandscapeAppAnimation.getMatrixRotation(mMatrix, mCurrentValues);
        mGestureCornerRadius = -1f;
        if (mGestureSeed != null && (fullScreenProgress > 0f || mRelativeGesture)) {
            mSeedRect.set(mGestureSeed.rect);
            mMatrixTmp.reset();
            applyWindowToHomeRotation(mMatrixTmp);
            mMatrixTmp.mapRect(mSeedRect);
            mGestureSource.set(mTmpCropRect);
            mGestureRect.set(mGestureSource);
            mMatrix.mapRect(mGestureRect);
            final float p;
            float seedRadius = mGestureSeed.cornerRadius;
            if (mRelativeGesture) {
                // Apply finger motion relative to the captured frame. Mixing that small
                // frame towards a fullscreen rectangle while dragging made it expand again
                // before it could close, even though ownership had already changed.
                if (mGestureBaseRect.isEmpty()) mGestureBaseRect.set(mGestureRect);
                float scaleX = mGestureRect.width() / mGestureBaseRect.width();
                float scaleY = mGestureRect.height() / mGestureBaseRect.height();
                float centerX = mSeedRect.centerX()
                        + mGestureRect.centerX() - mGestureBaseRect.centerX();
                float centerY = mSeedRect.centerY()
                        + mGestureRect.centerY() - mGestureBaseRect.centerY();
                float halfWidth = mSeedRect.width() * scaleX / 2f;
                float halfHeight = mSeedRect.height() * scaleY / 2f;
                mSeedRect.set(centerX - halfWidth, centerY - halfHeight,
                        centerX + halfWidth, centerY + halfHeight);
                seedRadius *= Math.min(scaleX, scaleY);
                p = 1f - mAppLaunchHandoffProgress;
            } else {
                // A cached pose without a live handoff still converges to either endpoint.
                if (Float.isNaN(mGestureStartProgress)) {
                    mGestureStartProgress = fullScreenProgress;
                }
                mGestureProgressFloor = Math.min(mGestureProgressFloor, fullScreenProgress);
                float weight = fullScreenProgress > mGestureProgressFloor
                        ? mGestureProgressFloor * (1f - fullScreenProgress)
                                / Math.max(0.001f, 1f - mGestureProgressFloor)
                        : fullScreenProgress;
                weight /= Math.max(0.001f, mGestureStartProgress);
                mGestureSeedWeight = Math.min(mGestureSeedWeight, weight);
                p = mGestureSeedWeight;
            }
            mGestureRect.set(Utilities.mapRange(p, mGestureRect.left, mSeedRect.left),
                    Utilities.mapRange(p, mGestureRect.top, mSeedRect.top),
                    Utilities.mapRange(p, mGestureRect.right, mSeedRect.right),
                    Utilities.mapRange(p, mGestureRect.bottom, mSeedRect.bottom));
            float radius = getCurrentCornerRadius();
            float baseRotation = mBaseMatrixRotation;
            LandscapeAppAnimation.applyClosingTransform(mMatrix, mGestureSource, mGestureRect,
                    baseRotation, mGestureSeedRotation * p, 1f, mTmpCropRect, mGestureMotion,
                    baseRotation);
            float scale = mMatrix.mapRadius(1f);
            mGestureCornerRadius = Math.min(Utilities.mapRange(p, radius,
                            seedRadius / Math.max(0.001f, scale)),
                    Math.min(mTmpCropRect.width(), mTmpCropRect.height()) / 2f);
        }
        if (mLandscapeLaunch != null) {
            if (!mLandscapeLaunch.initialized) {
                mLandscapeLaunch.capture(mMatrix, mTmpCropRect, getCurrentCornerRadius());
            }
            mLandscapeLaunch.apply(mMatrix, mTmpCropRect, landscapeLaunchProgress.value,
                    com.android.systemui.shared.system.QuickStepContract.getWindowCornerRadius(
                            mContext));
        }
        if (mInterruptedLaunch != null) {
            float p = fullScreenProgress;
            float weight = p < mInterruptedProgress
                    ? p / Math.max(0.001f, mInterruptedProgress)
                    : (1f - p) / Math.max(0.001f, 1f - mInterruptedProgress);
            mInterruptedWeight = Math.min(mInterruptedWeight, weight);
            // Both matrices are uniform scale + rotation + translation. Blending their
            // affine components retains that form and allows the finger to take over either
            // toward overview or back to the app, without locking the cancelled launch pose.
            mMatrix.getValues(mCurrentValues);
            for (int i = 0; i < 9; i++) {
                mCurrentValues[i] = Utilities.mapRange(mInterruptedWeight,
                        mCurrentValues[i], mInterruptedValues[i]);
            }
            float radius = getCurrentCornerRadius();
            mMatrix.setValues(mCurrentValues);
            mTmpCropRect.set(Math.round(Utilities.mapRange(mInterruptedWeight,
                            mTmpCropRect.left, mInterruptedCrop.left)),
                    Math.round(Utilities.mapRange(mInterruptedWeight,
                            mTmpCropRect.top, mInterruptedCrop.top)),
                    Math.round(Utilities.mapRange(mInterruptedWeight,
                            mTmpCropRect.right, mInterruptedCrop.right)),
                    Math.round(Utilities.mapRange(mInterruptedWeight,
                            mTmpCropRect.bottom, mInterruptedCrop.bottom)));
            mGestureCornerRadius = Utilities.mapRange(mInterruptedWeight, radius, mInterruptedRadius);
            if (mInterruptedWeight <= 0f) {
                mInterruptedLaunch = null;
                mLayoutValid = false;
            }
        }
        params.setProgress(1f - fullScreenProgress);
        SurfaceTransaction transaction = surfaceTransaction == null
                ? params.createSurfaceParams(this) : surfaceTransaction;
        if (firstFrame != null) {
            firstFrame.merge(transaction.getTransaction());
            transaction.getTransaction().close();
        } else if (applyImmediately) {
            transaction.getTransaction().apply();
        } else {
            params.applySurfaceParams(transaction);
        }

        if (!DEBUG) {
            return;
        }
        Log.d(TAG, "progress: " + fullScreenProgress
                + " carouselScale: " + carouselScale.value
                + " recentsViewScale: " + recentsViewScale.value
                + " crop: " + mTmpCropRect
                + " radius: " + getCurrentCornerRadius()
                + " taskW: " + taskWidth + " H: " + taskHeight
                + " taskRect: " + mTaskRect
                + " taskPrimaryT: " + taskPrimaryTranslation.value
                + " taskSecondaryT: " + taskSecondaryTranslation.value
                + " taskGridTranslationX: " + taskGridTranslationX.value
                + " taskGridTranslationY: " + taskGridTranslationY.value
                + " recentsPrimaryT: " + recentsViewPrimaryTranslation.value
                + " recentsSecondaryT: " + recentsViewSecondaryTranslation.value
                + " recentsScroll: " + recentsViewScroll.value
                + " pivot: " + mPivot
        );
    }

    private TransformParams.BuilderProxy mGestureTransform;

    /** Adds a task-specific gesture transform to the same transaction as overview. */
    public void setGestureTransform(@Nullable TransformParams.BuilderProxy transform) {
        mGestureTransform = transform;
    }

    @Override
    public void onBuildTargetParams(
            SurfaceProperties builder, RemoteAnimationTarget app, TransformParams params) {
        builder.setMatrix(mMatrix)
                .setWindowCrop(mTmpCropRect)
                .setCornerRadius(getCurrentCornerRadius())
                .setAlpha(params.getTargetAlpha() * mStackAlpha);

        if (mGestureTransform != null) {
            mGestureTransform.onBuildTargetParams(builder, app, params);
        }

        if (mDrawsBelowRecents == null && mDrawAboveOtherApps == null) {
            // No reordering will be enforced.
            return;
        }

        // In shell transitions, the animation leashes are reparented to an animation container
        // so we can bump layers as needed.
        int baseLayer = app.prefixOrderIndex - mDesktopTaskIndex;
        // 1000/2000 are arbitrary numbers to give room for multiple layers.
        if (mDrawsBelowRecents != null) {
            baseLayer += mDrawsBelowRecents ? Integer.MIN_VALUE + 2000 :  Integer.MAX_VALUE - 2000;
        }
        if (mDrawAboveOtherApps != null && mDrawAboveOtherApps) {
            baseLayer += 1000;
        }
        SurfaceControl overviewOverlay;
        if (mSizeStrategy instanceof FallbackWindowInterface windowInterface
                && (overviewOverlay = windowInterface.getOverviewOverlay()) != null
                && app.taskInfo.getActivityType() != ACTIVITY_TYPE_HOME) {
            // the Overview surface will live on the overviewOverlayLayer meaning that we
            // allow taskview simulator be set above/below this layer as needed for animations.
            builder.setRelativeLayer(overviewOverlay, baseLayer);
        } else {
            builder.setLayer(baseLayer);
        }
    }

    /**
     * Returns the corner radius that should be applied to the target so that it matches the
     * TaskView
     */
    public float getCurrentCornerRadius() {
        if (mGestureCornerRadius >= 0f) return mGestureCornerRadius;
        if (mLandscapeLaunch != null && mLandscapeLaunch.initialized) {
            return mLandscapeLaunch.cornerRadius;
        }
        float visibleRadius = mCurrentFullscreenParams.getCurrentCornerRadius();
        mTempPoint[0] = visibleRadius;
        mTempPoint[1] = 0;
        mInversePositionMatrix.mapVectors(mTempPoint);

        // If this task has another transform of it, then its scale also needs to be taken into
        // consideration for the radius.
        if (mTaskRectTransform != null && mTaskRectTransformInverse != null) {
            mTaskRectTransformInverse.mapVectors(mTempPoint);
        }

        // Ideally we should use square-root. This is an optimization as one of the dimension is 0.
        return Math.max(Math.abs(mTempPoint[0]), Math.abs(mTempPoint[1]));
    }
}
