/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.quickstep.util;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.SurfaceControl;
import android.view.View;
import android.window.ScreenCaptureInternal;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.function.ObjIntConsumer;

/**
 * A short-lived visual tail when Shell hands the live window to a newer transition. Captures once,
 * asynchronously, and draws the remaining original motion in Launcher's non-interactive overlay.
 * It never holds up the next app launch and never writes to the old app leash after cancellation.
 */
public final class WindowAnimationSnapshot extends Drawable
        implements View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
    private static final int MAX_CAPTURE_EDGE = 1080;
    private static final int MAX_RUNNING = 3;
    private static final ArrayList<WindowAnimationSnapshot> sRunning = new ArrayList<>();

    private final View mHost;
    private final int mHostWidth, mHostHeight;
    private final Rect mCaptureBounds;
    private final RectF mDrawBounds;
    private final Matrix mMatrix = new Matrix();
    private final Path mClip = new Path();
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private AppWindowAnimationState.Session mSession;
    private @Nullable Bitmap mBitmap;
    private @Nullable ValueAnimator mAnimator;
    // Native capture keeps a weak reference to the consumer; retain it until the reply.
    private @Nullable ObjIntConsumer<ScreenCaptureInternal.ScreenshotHardwareBuffer> mCaptureReply;
    private @Nullable ScreenCaptureInternal.ScreenCaptureListener mCaptureListener;
    private boolean mClosed;
    private final Runnable mTimeout = this::close;

    public WindowAnimationSnapshot(View host, SurfaceControl leash, Rect captureBounds,
            AppWindowAnimationState.Session session) {
        mHost = host;
        mHostWidth = host.getWidth();
        mHostHeight = host.getHeight();
        mCaptureBounds = new Rect(captureBounds);
        mDrawBounds = new RectF(captureBounds);
        mSession = session;
        setBounds(0, 0, mHostWidth, mHostHeight);
        if (!ValueAnimator.areAnimatorsEnabled() || !host.isAttachedToWindow()
                || !leash.isValid() || captureBounds.isEmpty()) {
            mClosed = true;
            return;
        }
        mCaptureReply = (buffer, status) -> MAIN_EXECUTOR.execute(() -> {
            mCaptureReply = null;
            mCaptureListener = null;
            if (buffer == null) return;
            try {
                if (!mClosed && status == 0 && !buffer.containsSecureLayers()) {
                    mBitmap = buffer.asBitmap();
                }
            } finally {
                if (buffer.getHardwareBuffer() != null) buffer.getHardwareBuffer().close();
            }
        });
        mCaptureListener = new ScreenCaptureInternal.ScreenCaptureListener(mCaptureReply);
        float scale = Math.min(1f, (float) MAX_CAPTURE_EDGE
                / Math.max(captureBounds.width(), captureBounds.height()));
        try {
            int status = ScreenCaptureInternal.captureLayers(
                    new ScreenCaptureInternal.LayerCaptureArgs.Builder(leash)
                            .setSourceCrop(mCaptureBounds).setFrameScale(scale).build(),
                    mCaptureListener);
            if (status != 0) close();
        } catch (IllegalArgumentException | SecurityException e) {
            // The live task may have disappeared while Shell was handing it over.
            close();
        }
    }

    public void continueAnimation(RectFSpringAnim source,
            RectFSpringAnim.OnUpdateListener update) {
        continueAnimation(source.createContinuation(update), 0L);
    }

    public void continueAnimation(@Nullable ValueAnimator tail, long playTime) {
        if (mClosed || mBitmap == null || !mSession.isActive()
                || !mHost.isAttachedToWindow() || !mHost.isShown()
                || mHost.getWidth() != mHostWidth || mHost.getHeight() != mHostHeight
                || tail == null || playTime >= tail.getDuration()) {
            close();
            return;
        }
        AppWindowAnimationState.Session continuation = mSession.continueWith(this::close);
        if (continuation == null) {
            close();
            return;
        }
        mSession = continuation;
        mAnimator = tail;
        while (sRunning.size() >= MAX_RUNNING) sRunning.get(0).close();
        sRunning.add(this);
        mHost.addOnAttachStateChangeListener(this);
        mHost.addOnLayoutChangeListener(this);
        mHost.getOverlay().add(this);
        // A backgrounded Launcher may stop producing animation frames. Do not retain its
        // bitmap until the next time the user returns to Home.
        mHost.postDelayed(mTimeout, Math.round((tail.getDuration() - playTime)
                * Math.max(1f, ValueAnimator.getDurationScale())) + 100L);
        tail.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                close();
            }
        });
        tail.setCurrentPlayTime(playTime);
        tail.start();
    }

    /** The matrix and crop use the same app coordinates as the live surface transaction. */
    public void update(Matrix appToHome, Rect crop, float cornerRadius, float alpha,
            RectF homeRect, float openness, float homeCornerRadius) {
        if (!isContinuing()) return;
        mMatrix.set(appToHome);
        mClip.rewind();
        mClip.addRoundRect(crop.left, crop.top, crop.right, crop.bottom,
                cornerRadius, cornerRadius, Path.Direction.CW);
        mPaint.setAlpha(Math.round(255f * LandscapeAppAnimation.boundProgress(alpha)));
        mSession.record(homeRect, openness, homeCornerRadius);
        invalidateSelf();
    }

    public boolean isContinuing() {
        return !mClosed && mAnimator != null;
    }

    public void onSourceFinished() {
        if (!isContinuing()) close();
    }

    private void close() {
        if (mClosed) return;
        mClosed = true;
        mHost.removeCallbacks(mTimeout);
        if (mAnimator != null) {
            mAnimator.cancel();
            mAnimator = null;
            mSession.finish();
        }
        mHost.getOverlay().remove(this);
        mHost.removeOnAttachStateChangeListener(this);
        mHost.removeOnLayoutChangeListener(this);
        sRunning.remove(this);
        // Dropping the hardware bitmap releases our reference without recycling a buffer
        // which RenderThread may still be reading from the preceding frame.
        mBitmap = null;
    }

    @Override
    public void draw(Canvas canvas) {
        if (mBitmap == null || !isContinuing()) return;
        int save = canvas.save();
        canvas.concat(mMatrix);
        canvas.clipPath(mClip);
        canvas.drawBitmap(mBitmap, null, mDrawBounds, mPaint);
        canvas.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) { mPaint.setAlpha(alpha); }

    @Override
    public void setColorFilter(@Nullable ColorFilter filter) { mPaint.setColorFilter(filter); }

    @Override
    public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    @Override
    public void onViewAttachedToWindow(View view) { }

    @Override
    public void onViewDetachedFromWindow(View view) { close(); }

    @Override
    public void onLayoutChange(View view, int left, int top, int right, int bottom,
            int oldLeft, int oldTop, int oldRight, int oldBottom) {
        if (right - left != mHostWidth || bottom - top != mHostHeight) close();
    }
}
