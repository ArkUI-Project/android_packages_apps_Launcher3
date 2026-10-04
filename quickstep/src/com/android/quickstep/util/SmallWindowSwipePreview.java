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
import android.view.WindowManager;

import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.internal.arkui.SmallWindowBounds;
import com.android.internal.graphics.drawable.BackgroundBlurDrawable;
import com.android.launcher3.Utilities;
import com.android.launcher3.anim.DesktopAnimationSettings;
import com.android.quickstep.RemoteTargetGluer.RemoteTargetHandle;
import com.android.quickstep.util.SurfaceTransaction.SurfaceProperties;

import java.util.function.Consumer;

/** Reversible, two-dimensional drag targets on top of the normal overview animation. */
public final class SmallWindowSwipePreview implements TransformParams.BuilderProxy {
    public static final int TARGET_NONE = 0;
    public static final int TARGET_SPLIT = 1;
    public static final int TARGET_SMALL_WINDOW = 2;

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
    private final Matrix mNormalMatrix = new Matrix();
    private final Matrix mMatrix = new Matrix();
    private final Matrix mReleaseMatrix = new Matrix();
    private final Rect mCrop = new Rect();
    private final RectF mNormal = new RectF();
    private final RectF mCurrent = new RectF();
    private final RectF mVelocitySample = new RectF();
    private final PointF mSizeVelocity = new PointF();
    private long mVelocitySampleTime;
    private final RectF mHover = new RectF();
    private final RectF mReleaseStart = new RectF();
    private final RectF mReleaseEnd = new RectF();
    private final Targets mTargets;
    private final int[] mHostLocation = new int[2];
    private final float[] mMatrixValues = new float[9];
    private float mPointerX;
    private float mPointerY;
    private float mVisibility;
    private float mNormalRadius;
    private float mRadius;
    private float mReleaseRadius;
    private float mSplitReveal;
    private float mWindowReveal;
    private float mRequestedSplitReveal;
    private float mRequestedWindowReveal;
    private float mReleaseSplitReveal;
    private float mReleaseWindowReveal;
    private float mReleaseRecentsAlpha;
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
        final Rect display = mWindowManager.getMaximumWindowMetrics().getBounds();
        mWidth = display.width();
        mHeight = display.height();
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
        // Hide siblings with the targets' reveal, before the app reaches either circle.
        // Waiting for the small-window material to finish growing exposed the whole stack.
        return 1f - Math.max(mVisibility, Math.max(mSplitReveal, mWindowReveal));
    }

    /** The app follows the finger directly; only the target material has a settling response. */
    public void updateGesture(float x, float y, float distance) {
        if (mReleasing || mClosed) return;
        mPointerX = Utilities.boundToRange(x, 0f, mWidth);
        mPointerY = Utilities.boundToRange(y, 0f, mHeight);
        // The reference introduces the targets as the overview card approaches half-screen width.
        mVisibility = clamp((distance / mHeight - mMotion.swipeTargetStart)
                / mMotion.swipeTargetRange);
        mVisibility = mVisibility * mVisibility * (3f - 2f * mVisibility);
        float vertical = clamp((.66f - mPointerY / mHeight) / .26f);
        float horizontal = clamp((Math.abs(mPointerX / mWidth - .5f) - .04f) / .22f);
        // A straight swipe to the top must work without a pause or a sideways detour.
        horizontal = Math.max(horizontal, clamp((.20f - mPointerY / mHeight) / .10f));
        int target = mPointerX < mWidth * .5f ? TARGET_SPLIT : TARGET_SMALL_WINDOW;
        progress = vertical * horizontal * mVisibility;
        if (target == TARGET_SPLIT && !mCanSplit) progress = 0f;
        mTarget = progress > 0f ? target : TARGET_NONE;
        float reveal = clamp((progress - .30f) / .45f);
        float splitReveal = mTarget == TARGET_SPLIT ? reveal : 0f;
        float windowReveal = mTarget == TARGET_SMALL_WINDOW ? reveal : 0f;
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

        // Keep the card under the finger; only release moves it to its final corner.
        float width = mWidth * AnimatorControllerWithResistance.PHONE_MIN_WINDOW_SCALE;
        float height = width * mNormal.height() / mNormal.width();
        float left = Utilities.boundToRange(mPointerX - width / 2f, 6 * mDensity,
                mWidth - width - 6 * mDensity);
        float top = Utilities.boundToRange(mPointerY - height * .78f, mHeight * .10f,
                mHeight - height - 24 * mDensity);
        mHover.set(left, top, left + width, top + height);
        interpolate(mNormal, mHover, progress, mCurrent);
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
        mRadius = mNormalRadius + (surfaceRadius(14 * mDensity) - mNormalRadius) * progress;
        builder.setMatrix(mMatrix).setWindowCrop(mCrop).setCornerRadius(mRadius);
        mTargets.update();
    }

    /** Freeze the actual last frame before starting a release or cancellation animator. */
    public void beginRelease(boolean toSmallWindow, boolean showRecents) {
        mReleaseRecentsAlpha = getRecentsAlpha();
        mReleasing = true;
        mToSmallWindow = toSmallWindow;
        mShowRecentsOnRelease = showRecents;
        mSplitSpring.cancel();
        mWindowSpring.cancel();
        mReleaseSplitReveal = mSplitReveal;
        mReleaseWindowReveal = mWindowReveal;
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
        mSplitSpring.cancel();
        mWindowSpring.cancel();
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
        mSplitSpring.cancel();
        mWindowSpring.cancel();
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

    private static float clamp(float value) {
        return Utilities.boundToRange(value, 0f, 1f);
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
        private final RectF mSplit = new RectF(.055f * mWidth, .065f * mHeight,
                .945f * mWidth, .48f * mHeight);
        private final RectF mCircle = new RectF();
        private final RectF mEnvelope = new RectF();
        private final RectF mReleaseEnvelope = new RectF();
        private final BlurShape mLeft = new BlurShape();
        private final BlurShape mRight = new BlurShape();
        private float mReleaseEnvelopeRadius;
        private float mSplitIconX;
        private float mSplitIconY;
        private float mSplitIconAlpha;
        private float mWindowIconAlpha;

        void freeze() {
            mReleaseEnvelope.set(mRight.bounds);
            mReleaseEnvelopeRadius = mRight.radius;
        }

        void update() {
            if (mClosed) return;
            mOverlayHost.getLocationOnScreen(mHostLocation);
            float visibility = mVisibility * (mReleasing ? 1f - progress : 1f);
            float split = mReleasing ? mReleaseSplitReveal * (1f - progress) : mSplitReveal;
            float small = mReleasing ? mReleaseWindowReveal * (1f - progress) : mWindowReveal;
            float circleRadius = mWidth * .055f;
            float circleY = mHeight * .128f;
            float padding = 5 * mDensity;
            if (mCanSplit) {
                setCircle(mCircle, mWidth * .14f, circleY, circleRadius);
                interpolate(mCircle, mSplit, split, mBounds);
                float radius = circleRadius + (26 * mDensity - circleRadius) * split;
                float alpha = visibility * (1f - clamp((small - .8f) / .2f));
                mLeft.update(mBounds, radius, alpha);
                mSplitIconX = mBounds.centerX();
                mSplitIconY = circleY + (mSplit.top + .047f * mHeight - circleY) * split;
                mSplitIconAlpha = alpha;
            }

            // One continuous shape grows out of the circular entry. Its destination is the
            // hover slot, independent of the app's current rectangle, so the app moves into
            // the expanding envelope instead of acquiring an already attached border.
            float grow = clamp(small / .35f);
            float wrap = clamp((small - .25f) / .75f);
            float radius = circleRadius * (1f + .4f * grow);
            setCircle(mCircle, mWidth * .86f, circleY, radius);
            mEnvelope.set(mHover.isEmpty() ? mCircle : mHover);
            mEnvelope.inset(-padding, -padding);
            interpolate(mCircle, mEnvelope, wrap, mBounds);
            radius += (14 * mDensity + padding - radius) * wrap;
            if (mReleasing && mToSmallWindow) {
                mEnvelope.set(mCurrent);
                mEnvelope.inset(-padding, -padding);
                interpolate(mReleaseEnvelope, mEnvelope, progress, mBounds);
                radius = mReleaseEnvelopeRadius
                        + (mDestinationRadius + padding - mReleaseEnvelopeRadius) * progress;
            }
            mRight.update(mBounds, radius,
                    visibility * (1f - clamp((split - .6f) / .4f)));
            mWindowIconAlpha = mRight.alpha * (1f - clamp((small - .15f) / .3f));
            if (mReleasing && mToSmallWindow) mWindowIconAlpha = 0f;
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
            drawIcon(canvas, mRight.bounds.centerX(), mRight.bounds.top + mRight.radius,
                    false, mWindowIconAlpha);
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
