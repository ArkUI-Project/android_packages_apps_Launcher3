/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.quickstep.util;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.RemoteAnimationTarget;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.internal.arkui.SmallWindowBounds;
import com.android.internal.graphics.drawable.BackgroundBlurDrawable;
import com.android.launcher3.Utilities;
import com.android.launcher3.anim.DesktopAnimationSettings;
import com.android.quickstep.RemoteTargetGluer.RemoteTargetHandle;
import com.android.quickstep.util.SurfaceTransaction.SurfaceProperties;
import com.android.quickstep.views.RecentsView;

import java.util.function.Consumer;

/** Reversible, two-dimensional drag targets on top of the normal overview animation. */
public final class SmallWindowSwipePreview implements TransformParams.BuilderProxy {
    public static final int TARGET_NONE = 0;
    public static final int TARGET_SPLIT = 1;
    public static final int TARGET_SMALL_WINDOW = 2;

    // Display-relative entry geometry. Hit testing uses these resting regions, never the
    // animated blur bounds: moving an indicator must not move the goal underneath the finger.
    private static final float ENTRY_LEFT = .14f;
    private static final float ENTRY_RIGHT = .86f;
    private static final float ENTRY_TOP = .14f;
    private static final float ENTRY_REACH_X = .43f;
    private static final float ENTRY_REACH_Y = .20f;
    private static final float ENTRY_EXIT_DISTANCE = 1.12f;

    private final RemoteTargetHandle mHandle;
    private final RemoteAnimationTarget mTask;
    private final View mOverlayHost;
    private final boolean mCanSplit;
    private final float mWidth;
    private final float mHeight;
    private final float mDensity;
    private final float mDestinationRadius;
    private final Rect mDestination;
    private final WindowManager mWindowManager;
    private final Consumer<Boolean> mBlurListener;
    private final Runnable mOnVisualUpdate;
    private final DesktopAnimationSettings mMotion;
    private final boolean mWasBelowRecents;
    private final SpringAnimation mSplitSpring;
    private final SpringAnimation mWindowSpring;
    private final SpringAnimation mFollowXSpring;
    private final SpringAnimation mFollowYSpring;
    private final SpringAnimation mFollowWidthSpring;
    private final Matrix mNormalMatrix = new Matrix();
    private final Matrix mMatrix = new Matrix();
    private final Matrix mReleaseMatrix = new Matrix();
    private final Rect mCrop = new Rect();
    private final RectF mNormal = new RectF();
    private final RectF mCurrent = new RectF();
    private final RectF mSafeDisplay = new RectF();
    private final RectF mVelocitySample = new RectF();
    private final PointF mSizeVelocity = new PointF();
    private long mVelocitySampleTime;
    private final RectF mFollowOrigin = new RectF();
    private final RectF mReleaseStart = new RectF();
    private final RectF mReleaseEnd = new RectF();
    private final RectF mRecentsBounds = new RectF();
    private final RectF mReleaseRecentsBounds = new RectF();
    private final Targets mTargets;
    private final int[] mHostLocation = new int[2];
    private final float[] mMatrixValues = new float[9];
    private float mPointerX;
    private float mPointerY;
    private float mFollowProgress;
    private float mFollowOriginX;
    private float mFollowOriginY;
    private float mFollowOriginScale;
    private float mDragScale = 1f;
    private float mMaterialX;
    private float mMaterialY;
    private float mMaterialWidth;
    private float mRequestedMaterialX;
    private float mRequestedMaterialY;
    private float mRequestedMaterialWidth;
    private boolean mMaterialPositionInitialized;
    private float mVisibility;
    private float mNormalRadius;
    private float mRadius;
    private float mReleaseRadius;
    private float mSplitReveal;
    private float mWindowReveal;
    private float mRequestedSplitReveal;
    private float mRequestedWindowReveal;
    private float mSplitProximity;
    private float mWindowProximity;
    private float mReleaseRecentsAlpha;
    private float mReleaseRecentsRetreat;
    private boolean mBlurEnabled;
    private boolean mClosed;
    private boolean mTransformDetached;
    private ValueAnimator mTargetFade;
    private boolean mReleasing;
    private boolean mToSmallWindow;
    private boolean mShowRecentsOnRelease;
    private int mTarget;
    private Bitmap mSnapshot;
    public float progress;

    public SmallWindowSwipePreview(Context context, RemoteTargetHandle handle,
            RemoteAnimationTarget task, View overlayHost, boolean canSplit,
            Runnable onVisualUpdate) {
        mHandle = handle;
        mMotion = DesktopAnimationSettings.read(context);
        mTask = task;
        mOverlayHost = overlayHost;
        mCanSplit = canSplit;
        mWindowManager = context.getSystemService(WindowManager.class);
        final WindowMetrics metrics = mWindowManager.getMaximumWindowMetrics();
        final Rect display = metrics.getBounds();
        mWidth = display.width();
        mHeight = display.height();
        final Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        mSafeDisplay.set(insets.left, insets.top,
                mWidth - insets.right, mHeight - insets.bottom);
        mDensity = context.getResources().getDisplayMetrics().density;
        mDestination = SmallWindowBounds.getDestination(context, true, false);
        mDestinationRadius = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.pinned_window_corner_radius)
                * mDestination.width() / mWidth;
        mOnVisualUpdate = onVisualUpdate;
        mWasBelowRecents = handle.getTaskViewSimulator().getDrawsBelowRecents();
        mPointerX = mWidth / 2f;
        mPointerY = mHeight;
        mTargets = new Targets();
        mSplitSpring = createRevealSpring(true);
        mWindowSpring = createRevealSpring(false);
        mFollowXSpring = createFollowSpring(0);
        mFollowYSpring = createFollowSpring(1);
        mFollowWidthSpring = createFollowSpring(2);
        overlayHost.getLocationOnScreen(mHostLocation);
        mTargets.setBounds(0, 0, overlayHost.getWidth(), overlayHost.getHeight());
        overlayHost.getOverlay().add(mTargets);
        // The app stays sharp in front of the backdrop throughout the morph.
        handle.getTaskViewSimulator().setDrawsBelowRecents(false);
        handle.getTaskViewSimulator().setGestureTransform(this);
        mBlurListener = enabled -> {
            if (mClosed) return;
            mBlurEnabled = enabled;
            mTargets.update();
        };
        mWindowManager.addCrossWindowBlurEnabledListener(context.getMainExecutor(), mBlurListener);
    }

    public Rect getDestination() {
        return new Rect(mDestination);
    }

    public int getTarget() {
        return mTarget;
    }

    public boolean hasActiveTransform() {
        return mFollowProgress > 0f && !mCurrent.isEmpty();
    }

    public RectF getCurrentBounds() {
        return new RectF(mCurrent);
    }

    public PointF getSizeVelocity() {
        // A held finger releases from rest; a duplicate transaction in one frame should not
        // erase the last measured resize velocity.
        return SystemClock.uptimeMillis() - mVelocitySampleTime < 100
                ? new PointF(mSizeVelocity.x, mSizeVelocity.y) : new PointF();
    }

    public float getVisibleCornerRadius() {
        mMatrix.getValues(mMatrixValues);
        return mRadius * (float) Math.hypot(mMatrixValues[Matrix.MSCALE_X],
                mMatrixValues[Matrix.MSKEW_Y]);
    }

    public void setSnapshot(Bitmap snapshot) {
        mSnapshot = snapshot;
    }

    public Bitmap getSnapshot() {
        return mSnapshot;
    }

    /** Keep the same inset crop when the live task is replaced with its scaled snapshot. */
    public RectF getSnapshotCrop() {
        final Rect bounds = mTask.startBounds != null
                ? mTask.startBounds : mTask.screenSpaceBounds;
        if (bounds.isEmpty() || mCrop.isEmpty()) return new RectF(0f, 0f, 1f, 1f);
        RectF crop = new RectF(clamp((float) mCrop.left / bounds.width()),
                clamp((float) mCrop.top / bounds.height()),
                clamp((float) mCrop.right / bounds.width()),
                clamp((float) mCrop.bottom / bounds.height()));
        return crop.isEmpty() ? new RectF(0f, 0f, 1f, 1f) : crop;
    }

    /** Replace the live app only in the frame containing the initialized split staging view. */
    public void handoffToSplit() {
        if (mClosed) return;
        if (mTask.leash.isValid()) {
            SurfaceTransactionApplier applier = new SurfaceTransactionApplier(mOverlayHost);
            if (mHandle.getTransformParams().getTargetSet() != null) {
                mHandle.getTransformParams().getTargetSet().addReleaseCheck(applier);
            }
            SurfaceTransaction transaction = new SurfaceTransaction();
            transaction.forSurface(mTask.leash).setAlpha(0f);
            applier.scheduleApply(transaction);
        }
        close();
    }

    public float getRecentsAlpha() {
        if (mReleasing) {
            return mReleaseRecentsAlpha + ((mShowRecentsOnRelease ? 1f : 0f)
                    - mReleaseRecentsAlpha) * progress;
        }
        // Cards travel out of the viewport as a target unfolds. Keep them visible through
        // that movement, and bring them back immediately when the finger leaves the region.
        return 1f - clamp((getRecentsRetreat() - .85f) / .15f);
    }

    private float getRecentsRetreat() {
        if (mReleasing) {
            return mReleaseRecentsRetreat + ((mShowRecentsOnRelease ? 0f : 1f)
                    - mReleaseRecentsRetreat) * progress;
        }
        return Math.max(mSplitReveal, mWindowReveal);
    }

    /** Move every overview card in the same coordinate space as the live foreground app. */
    public void updateRecentsView(RecentsView<?, ?> recents) {
        updateRecentsBounds();
        recents.setSmallWindowPreviewTransform(mNormal, mRecentsBounds,
                mFollowProgress * (mReleasing ? 1f - progress : 1f), getRecentsRetreat());
        recents.setSmallWindowPreviewAlpha(true, getRecentsAlpha());
    }

    private void updateRecentsBounds() {
        if (mReleasing) {
            interpolate(mReleaseRecentsBounds, mReleaseEnd, progress, mRecentsBounds);
        } else if (!mCurrent.isEmpty() && mMaterialPositionInitialized) {
            float width = Math.max(1f, mMaterialWidth);
            float height = width * mCurrent.height() / mCurrent.width();
            mRecentsBounds.set(mMaterialX - width / 2f, mMaterialY,
                    mMaterialX + width / 2f, mMaterialY + height);
            interpolate(mCurrent, mRecentsBounds, mFollowProgress, mRecentsBounds);
            // Springs may overshoot the held app, but its wrapping material must remain visible.
            constrainPreviewBounds(mRecentsBounds, .025f * mWidth, false);
        } else {
            mRecentsBounds.set(mCurrent);
        }
    }

    /** The app follows the finger directly; the target material and adjacent cards trail it. */
    public void updateGesture(float x, float y, float distance) {
        if (mReleasing || mClosed) return;
        mPointerX = x;
        mPointerY = y;
        // The reference introduces the targets as the overview card approaches half-screen width.
        mVisibility = clamp((distance / mHeight - mMotion.swipeTargetStart)
                / mMotion.swipeTargetRange);
        mVisibility = mVisibility * mVisibility * (3f - 2f * mVisibility);
        // Once picked up, keep the same drag origin until release. Fading or changing targets
        // must not blend the app back to overview, or reset its origin under a moving finger.
        mFollowProgress = Math.max(mFollowProgress, mVisibility);
        mDragScale = Math.max(AnimatorControllerWithResistance.PHONE_MIN_WINDOW_SCALE,
                1f - Math.max(0f, distance) / mHeight
                        * AnimatorControllerWithResistance.PHONE_WINDOW_SHRINK_RATE);
        float splitDistance = mCanSplit ? entryDistance(ENTRY_LEFT) : Float.MAX_VALUE;
        float windowDistance = entryDistance(ENTRY_RIGHT);
        mSplitProximity = mCanSplit ? smoothstep((1.5f - splitDistance) / .5f) : 0f;
        mWindowProximity = smoothstep((1.5f - windowDistance) / .5f);
        int nearest = splitDistance < windowDistance ? TARGET_SPLIT : TARGET_SMALL_WINDOW;
        float nearestDistance = Math.min(splitDistance, windowDistance);
        float selectedDistance = mTarget == TARGET_SPLIT ? splitDistance : windowDistance;
        if (mVisibility < .5f) {
            mTarget = TARGET_NONE;
        } else if (nearestDistance <= 1f) {
            mTarget = nearest;
        } else if (mTarget == TARGET_NONE || selectedDistance > ENTRY_EXIT_DISTANCE) {
            mTarget = TARGET_NONE;
        }
        // Progress remains the release contract with AbsSwipeUpHandler. Entry/exit is determined
        // by the finger, so a fast crossing does not have to wait for the material spring.
        progress = mTarget == TARGET_NONE ? 0f : 1f;
        float splitReveal = mTarget == TARGET_SPLIT ? 1f : 0f;
        float windowReveal = mTarget == TARGET_SMALL_WINDOW ? 1f : 0f;
        if (splitReveal != mRequestedSplitReveal) {
            mRequestedSplitReveal = splitReveal;
            mSplitSpring.animateToFinalPosition(splitReveal);
        }
        if (windowReveal != mRequestedWindowReveal) {
            mRequestedWindowReveal = windowReveal;
            mWindowSpring.animateToFinalPosition(windowReveal);
        }
        mTargets.update();
    }

    private float entryDistance(float centerX) {
        float dx = (mPointerX / mWidth - centerX) / ENTRY_REACH_X;
        // The regions remain open toward the top edge, including a straight swipe without
        // pausing in overview. Around the middle, the nearer entry wins (right on an exact tie).
        float dy = Math.max(0f, mPointerY / mHeight - ENTRY_TOP) / ENTRY_REACH_Y;
        return (float) Math.hypot(dx, dy);
    }

    private SpringAnimation createRevealSpring(boolean split) {
        SpringAnimation animation = new SpringAnimation(new FloatValueHolder(0f));
        animation.setSpring(new SpringForce(0f).setStiffness(700f)
                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        animation.setMinimumVisibleChange(.001f);
        animation.addUpdateListener((anim, value, velocity) -> {
            if (mClosed || mReleasing) return;
            if (split) mSplitReveal = clamp(value);
            else mWindowReveal = clamp(value);
            mTargets.update();
            mOnVisualUpdate.run();
        });
        return animation;
    }

    private SpringAnimation createFollowSpring(int axis) {
        SpringAnimation animation = new SpringAnimation(new FloatValueHolder(0f));
        animation.setSpring(new SpringForce(0f)
                .setStiffness(axis == 2 ? mMotion.sizeStiffness : mMotion.positionStiffness)
                .setDampingRatio(axis == 2 ? mMotion.sizeDamping : mMotion.positionDamping));
        if (axis == 2) animation.setMinValue(1f);
        animation.setMinimumVisibleChange(.5f);
        animation.addUpdateListener((anim, value, velocity) -> {
            if (mClosed || mReleasing || mTargetFade != null) return;
            if (axis == 0) mMaterialX = value;
            else if (axis == 1) mMaterialY = value;
            else mMaterialWidth = value;
            mTargets.update();
            mOnVisualUpdate.run();
        });
        return animation;
    }

    private void updateMaterialPosition() {
        float x = mCurrent.centerX();
        float y = mCurrent.top;
        float width = mCurrent.width();
        if (!mMaterialPositionInitialized) {
            mMaterialPositionInitialized = true;
            mRequestedMaterialX = mMaterialX = x;
            mRequestedMaterialY = mMaterialY = y;
            mRequestedMaterialWidth = mMaterialWidth = width;
            mFollowXSpring.setStartValue(x);
            mFollowYSpring.setStartValue(y);
            mFollowWidthSpring.setStartValue(width);
        }
        if (x != mRequestedMaterialX) {
            mRequestedMaterialX = x;
            mFollowXSpring.animateToFinalPosition(x);
        }
        if (y != mRequestedMaterialY) {
            mRequestedMaterialY = y;
            mFollowYSpring.animateToFinalPosition(y);
        }
        if (width != mRequestedMaterialWidth) {
            mRequestedMaterialWidth = width;
            mFollowWidthSpring.animateToFinalPosition(width);
        }
    }

    private void stopTargetSprings() {
        mSplitSpring.cancel();
        mWindowSpring.cancel();
        mFollowXSpring.cancel();
        mFollowYSpring.cancel();
        mFollowWidthSpring.cancel();
    }

    @Override
    public void onBuildTargetParams(SurfaceProperties builder, RemoteAnimationTarget app,
            TransformParams params) {
        if (app.taskId != mTask.taskId || app.leash != mTask.leash || mReleasing || mClosed) return;
        final TaskViewSimulator simulator = mHandle.getTaskViewSimulator();
        mNormalMatrix.set(simulator.getCurrentMatrix());
        mNormal.set(simulator.getCurrentCropRect());
        mNormal.roundOut(mCrop);
        mNormalMatrix.mapRect(mNormal);
        mNormalRadius = simulator.getCurrentCornerRadius();
        if (mNormal.isEmpty()) return;

        mCurrent.set(mNormal);
        if (mFollowProgress > 0f) {
            if (mFollowOrigin.isEmpty()) {
                // Capture the displayed card instead of centering it on an off-center touch.
                // The first free-drag frame must coincide with the normal overview frame.
                mFollowOrigin.set(mNormal);
                mFollowOriginX = mPointerX;
                mFollowOriginY = mPointerY;
                mFollowOriginScale = mDragScale;
            }
            // Position and size depend only on the gesture, not target reveal, springs, or
            // background recents scrolling. Preserve the vertical grab point while resizing;
            // keep horizontal shrinking centered so a vertical swipe cannot skew the card.
            float scale = mDragScale / mFollowOriginScale;
            float width = mFollowOrigin.width() * scale;
            float height = mFollowOrigin.height() * scale;
            float centerX = mFollowOrigin.centerX() + mPointerX - mFollowOriginX;
            float left = centerX - width / 2f;
            float top = mPointerY - (mFollowOriginY - mFollowOrigin.top) * scale;
            mCurrent.set(left, top, left + width, top + height);
            // Keep the whole card and its eventual wrapping frame inside the usable display.
            // Only compress motion near an edge; entering a target never changes this position.
            constrainPreviewBounds(mCurrent, .025f * mWidth, true);
        }
        updateMaterialPosition();
        long now = SystemClock.uptimeMillis();
        long dt = now - mVelocitySampleTime;
        if (dt >= 4) {
            if (!mVelocitySample.isEmpty() && dt < 100) {
                mSizeVelocity.set((mCurrent.width() - mVelocitySample.width()) / dt,
                        (mCurrent.height() - mVelocitySample.height()) / dt);
            } else {
                mSizeVelocity.set(0f, 0f);
            }
            mVelocitySample.set(mCurrent);
            mVelocitySampleTime = now;
        }
        transform(mNormalMatrix, mNormal, mCurrent);
        mRadius = mNormalRadius
                + (surfaceRadius(14 * mDensity) - mNormalRadius) * mFollowProgress;
        builder.setMatrix(mMatrix).setWindowCrop(mCrop).setCornerRadius(mRadius);
        mTargets.update();
        mOnVisualUpdate.run();
    }

    /** Freeze the actual last frame before starting a release or cancellation animator. */
    public void beginRelease(boolean toSmallWindow, boolean showRecents) {
        mReleaseRecentsAlpha = getRecentsAlpha();
        mReleaseRecentsRetreat = getRecentsRetreat();
        updateRecentsBounds();
        mReleaseRecentsBounds.set(mRecentsBounds);
        mReleasing = true;
        mToSmallWindow = toSmallWindow;
        mShowRecentsOnRelease = showRecents;
        stopTargetSprings();
        mTargets.freeze();
        mReleaseStart.set(mCurrent);
        if (toSmallWindow) mReleaseEnd.set(mDestination);
        else mReleaseEnd.set(mNormal);
        mReleaseMatrix.set(mMatrix);
        mReleaseRadius = mRadius;
        progress = 0f;
    }

    public void apply() {
        if (!mReleasing || mClosed || !mTask.leash.isValid() || mReleaseStart.isEmpty()) return;
        interpolate(mReleaseStart, mReleaseEnd, progress, mCurrent);
        transform(mReleaseMatrix, mReleaseStart, mCurrent);
        float endRadius = mToSmallWindow ? surfaceRadius(mDestinationRadius) : mNormalRadius;
        mRadius = mReleaseRadius + (endRadius - mReleaseRadius) * progress;
        final SurfaceTransaction transaction = new SurfaceTransaction();
        transaction.forSurface(mTask.leash).setMatrix(mMatrix).setWindowCrop(mCrop)
                .setAlpha(1f).setCornerRadius(mRadius);
        mHandle.getTransformParams().applySurfaceParams(transaction);
        mTargets.update();
    }

    public void close() {
        if (mClosed) return;
        mClosed = true;
        if (mTargetFade != null) mTargetFade.cancel();
        mSnapshot = null;
        stopTargetSprings();
        mWindowManager.removeCrossWindowBlurEnabledListener(mBlurListener);
        detachTransform();
        mTargets.close();
        mOverlayHost.getOverlay().remove(mTargets);
    }

    private void detachTransform() {
        if (mTransformDetached) return;
        mTransformDetached = true;
        mHandle.getTaskViewSimulator().setGestureTransform(null);
        mHandle.getTaskViewSimulator().setDrawsBelowRecents(mWasBelowRecents);
    }

    /** Release the window immediately; only the two target indicators finish fading out. */
    public void finishToHome() {
        if (mClosed || mTargetFade != null) return;
        stopTargetSprings();
        mTargets.freeze();
        detachTransform();
        mTargetFade = ValueAnimator.ofFloat(mVisibility, 0f);
        mTargetFade.setDuration(mMotion.swipeTargetFade);
        mTargetFade.setInterpolator(mMotion.interpolator);
        mTargetFade.addUpdateListener(animation -> {
            mVisibility = (float) animation.getAnimatedValue();
            mTargets.update();
        });
        mTargetFade.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                mTargetFade = null;
                close();
            }
        });
        mTargetFade.start();
    }

    private void transform(Matrix source, RectF from, RectF to) {
        mMatrix.set(source);
        mMatrix.postScale(to.width() / from.width(), to.height() / from.height(),
                from.left, from.top);
        mMatrix.postTranslate(to.left - from.left, to.top - from.top);
    }

    private float surfaceRadius(float visibleRadius) {
        mMatrix.getValues(mMatrixValues);
        float scale = (float) Math.hypot(mMatrixValues[Matrix.MSCALE_X],
                mMatrixValues[Matrix.MSKEW_Y]);
        return visibleRadius / Math.max(.01f, scale);
    }

    private void constrainPreviewBounds(RectF bounds, float padding, boolean soften) {
        // Introduce system-bar and frame clearance with pickup, so the fullscreen starting
        // frame does not jump. Oversized early frames use their available centered clearance.
        float minX = Math.min((mSafeDisplay.left + padding) * mFollowProgress,
                Math.max(0f, (mWidth - bounds.width()) / 2f));
        float minY = Math.min((mSafeDisplay.top + padding) * mFollowProgress,
                Math.max(0f, (mHeight - bounds.height()) / 2f));
        float maxX = Math.max(minX, mWidth - bounds.width()
                - (mWidth - mSafeDisplay.right + padding) * mFollowProgress);
        float maxY = Math.max(minY, mHeight - bounds.height()
                - (mHeight - mSafeDisplay.bottom + padding) * mFollowProgress);
        float x = soften ? constrainDragPosition(bounds.left, minX, maxX)
                : Utilities.boundToRange(bounds.left, minX, maxX);
        float y = soften ? constrainDragPosition(bounds.top, minY, maxY)
                : Utilities.boundToRange(bounds.top, minY, maxY);
        bounds.offset(x - bounds.left, y - bounds.top);
    }

    private float constrainDragPosition(float position, float min, float max) {
        float reach = Math.min(32f * mDensity, (max - min) / 4f);
        if (reach <= 0f) return min;
        // The derivative is one at the inner boundary and remains positive outside it.
        // Unlike clamping or snapping, reversing the finger immediately moves the card back.
        if (position < min + reach) {
            return min + reach * reach / (min + 2f * reach - position);
        }
        if (position > max - reach) {
            return max - reach * reach / (position - max + 2f * reach);
        }
        return position;
    }

    private static float clamp(float value) {
        return Utilities.boundToRange(value, 0f, 1f);
    }

    private static float smoothstep(float value) {
        float p = clamp(value);
        return p * p * (3f - 2f * p);
    }

    private static void interpolate(RectF from, RectF to, float p, RectF out) {
        out.set(from.left + (to.left - from.left) * p,
                from.top + (to.top - from.top) * p,
                from.right + (to.right - from.right) * p,
                from.bottom + (to.bottom - from.bottom) * p);
    }

    private final class Targets extends Drawable {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mBounds = new RectF();
        private final RectF mSplit = new RectF();
        private final RectF mCircle = new RectF();
        private final RectF mEnvelope = new RectF();
        private final RectF mReleaseSplit = new RectF();
        private final RectF mReleaseEnvelope = new RectF();
        private final BlurShape mLeft = new BlurShape();
        private final BlurShape mRight = new BlurShape();
        private float mReleaseEnvelopeRadius;
        private float mReleaseSplitRadius;
        private float mReleaseLeftAlpha;
        private float mReleaseRightAlpha;
        private float mReleaseSplitIconAlpha;
        private float mReleaseWindowIconAlpha;
        private float mReleaseVisibility;
        private float mSplitIconX;
        private float mSplitIconY;
        private float mWindowIconX;
        private float mWindowIconY;
        private float mSplitIconAlpha;
        private float mWindowIconAlpha;

        void freeze() {
            mReleaseSplit.set(mLeft.bounds);
            mReleaseEnvelope.set(mRight.bounds);
            mReleaseSplitRadius = mLeft.radius;
            mReleaseEnvelopeRadius = mRight.radius;
            mReleaseLeftAlpha = mLeft.alpha;
            mReleaseRightAlpha = mRight.alpha;
            mReleaseSplitIconAlpha = mSplitIconAlpha;
            mReleaseWindowIconAlpha = mWindowIconAlpha;
            mReleaseVisibility = mVisibility;
        }

        void update() {
            if (mClosed) return;
            mOverlayHost.getLocationOnScreen(mHostLocation);
            if (mReleasing || mTargetFade != null) {
                updateRelease();
                return;
            }
            float visibility = mVisibility;
            float split = mSplitReveal;
            float small = mWindowReveal;
            float circleRadius = mWidth * .06f;
            float circleY = mHeight * .115f;
            float padding = .025f * mWidth;
            if (mCanSplit) {
                float entryRadius = circleRadius * (1f + .4f * mSplitProximity)
                        * (1f - .4f * small);
                setFollowingCircle(mWidth * .11f, circleY, entryRadius,
                        mSplitProximity, visibility);
                // The top stage keeps its screen-relative size while its material follows
                // the held card. This continues during a hold and reverses on withdrawal.
                float splitWidth = .89f * mWidth;
                float splitHeight = .44f * mHeight;
                float splitLeft = .055f * mWidth + (mMaterialX - mWidth / 2f) * .08f;
                float splitTop = .045f * mHeight + (mMaterialY - .12f * mHeight) * .18f;
                mSplit.set(splitLeft, splitTop,
                        splitLeft + splitWidth, splitTop + splitHeight);
                constrainPreviewBounds(mSplit, 0f, false);
                interpolate(mCircle, mSplit, split, mBounds);
                float radius = entryRadius + (.11f * mWidth - entryRadius) * split;
                float alpha = visibility * (1f - smoothstep(small * 2f));
                mLeft.update(mBounds, radius, alpha);
                mSplitIconX = mBounds.centerX();
                mSplitIconY = mCircle.centerY()
                        + (mSplit.top + .047f * mHeight - mCircle.centerY()) * split;
                mSplitIconAlpha = alpha;
            }

            // The circle starts following before it unfolds. Its spring trails the live app,
            // so fast motion can pull the app ahead and the blur gradually catches and wraps it.
            float wrap = smoothstep(small);
            float radius = circleRadius * (1f + .4f * mWindowProximity)
                    * (1f - .4f * split);
            setFollowingCircle(mWidth * .89f, circleY, radius,
                    mWindowProximity, visibility);
            updateRecentsBounds();
            mEnvelope.set(mRecentsBounds.isEmpty() ? mCircle : mRecentsBounds);
            mEnvelope.inset(-padding, -padding);
            constrainPreviewBounds(mEnvelope, 0f, false);
            interpolate(mCircle, mEnvelope, wrap, mBounds);
            radius += (14 * mDensity + padding - radius) * wrap;
            mRight.update(mBounds, radius,
                    visibility * (1f - smoothstep(split * 2f)));
            mWindowIconAlpha = mRight.alpha * (1f - clamp((small - .15f) / .3f));
            mWindowIconX = mCircle.centerX();
            mWindowIconY = mCircle.centerY();
            invalidateSelf();
        }

        private void setFollowingCircle(float x, float y, float radius,
                float proximity, float visibility) {
            float dx = mMaterialX - x;
            float dy = mMaterialY - radius * .5f - y;
            float followX = visibility * (.10f + .16f * proximity);
            float followY = visibility * .65f;
            setCircle(mCircle, x + dx * followX, y + dy * followY, radius);
            constrainPreviewBounds(mCircle, 0f, false);
        }

        private void updateRelease() {
            float fade = mReleasing ? 1f - progress
                    : clamp(mVisibility / Math.max(.001f, mReleaseVisibility));
            // Preserve the last material pose at handoff instead of snapping it back to an entry.
            mLeft.update(mReleaseSplit, mReleaseSplitRadius, mReleaseLeftAlpha * fade);
            mBounds.set(mReleaseEnvelope);
            float radius = mReleaseEnvelopeRadius;
            if (mReleasing && mToSmallWindow) {
                float padding = .025f * mWidth;
                mEnvelope.set(mCurrent);
                mEnvelope.inset(-padding, -padding);
                interpolate(mReleaseEnvelope, mEnvelope, progress, mBounds);
                radius += (mDestinationRadius + padding - radius) * progress;
            }
            mRight.update(mBounds, radius, mReleaseRightAlpha * fade);
            mSplitIconAlpha = mReleaseSplitIconAlpha * fade;
            mWindowIconAlpha = mReleasing && mToSmallWindow
                    ? 0f : mReleaseWindowIconAlpha * fade;
            invalidateSelf();
        }

        private void setCircle(RectF out, float x, float y, float radius) {
            out.set(x - radius, y - radius, x + radius, y + radius);
        }

        @Override
        public void draw(Canvas canvas) {
            if (mClosed) return;
            canvas.save();
            canvas.translate(-mHostLocation[0], -mHostLocation[1]);
            if (mCanSplit) {
                mLeft.draw(canvas);
                drawIcon(canvas, mSplitIconX, mSplitIconY, true, mSplitIconAlpha);
            }
            mRight.draw(canvas);
            drawIcon(canvas, mWindowIconX, mWindowIconY, false, mWindowIconAlpha);
            canvas.restore();
        }

        void close() {
            mLeft.close();
            mRight.close();
            mOverlayHost.invalidate();
        }

        /** SurfaceFlinger blurs the live backdrop; no screenshot or fixed wallpaper colors. */
        private final class BlurShape {
            final RectF bounds = new RectF();
            final Rect roundedBounds = new Rect();
            final BackgroundBlurDrawable blur = mOverlayHost.getViewRootImpl() == null ? null
                    : mOverlayHost.getViewRootImpl().createBackgroundBlurDrawable();
            float radius;
            float alpha;

            BlurShape() {
                if (blur != null) {
                    blur.setAlpha(0);
                    blur.setBlurRadius(0);
                    blur.setVisible(false, false);
                }
            }

            void update(RectF rect, float cornerRadius, float visibility) {
                bounds.set(rect);
                radius = cornerRadius;
                alpha = clamp(visibility);
                if (blur == null) return;
                // Update the region before pre-draw so its geometry and rendered frame agree.
                blur.setVisible(alpha > 0f && mBlurEnabled, false);
                blur.setAlpha(Math.round(255 * alpha));
                blur.setBlurRadius(mBlurEnabled ? Math.round(32 * mDensity) : 0);
                blur.setColor((Math.round(32 * alpha) << 24) | 0x00FFFFFF);
                blur.setCornerRadius(radius);
                bounds.roundOut(roundedBounds);
                blur.setBounds(roundedBounds);
            }

            void draw(Canvas canvas) {
                if (alpha <= 0f) return;
                if (blur != null && mBlurEnabled && canvas.isHardwareAccelerated()) {
                    blur.draw(canvas);
                } else {
                    mPaint.setColor(0xFFFFFFFF);
                    mPaint.setAlpha(Math.round(44 * alpha));
                    mPaint.setStyle(Paint.Style.FILL);
                    canvas.drawRoundRect(bounds, radius, radius, mPaint);
                }
                mPaint.setColor(0xFFFFFFFF);
                mPaint.setAlpha(Math.round(55 * alpha));
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(.65f * mDensity);
                canvas.drawRoundRect(bounds, radius, radius, mPaint);
                mPaint.setStyle(Paint.Style.FILL);
            }

            void close() {
                if (blur == null) return;
                blur.setAlpha(0);
                blur.setBlurRadius(0);
                blur.setVisible(false, false);
            }
        }

        private void drawIcon(Canvas canvas, float x, float y, boolean split, float alpha) {
            mPaint.setColor(0xFFFFFFFF);
            mPaint.setAlpha((int) (255 * alpha));
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(1.3f * mDensity);
            float w = 5 * mDensity;
            float h = 6.5f * mDensity;
            mBounds.set(x - w, y - h, x + w, y + h);
            canvas.drawRoundRect(mBounds, 2 * mDensity, 2 * mDensity, mPaint);
            if (split) {
                canvas.drawLine(x - w, y, x + w, y, mPaint);
            } else {
                mBounds.offset(-3 * mDensity, 3 * mDensity);
                canvas.drawRoundRect(mBounds, 2 * mDensity, 2 * mDensity, mPaint);
            }
            mPaint.setStyle(Paint.Style.FILL);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
