/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.quickstep.util;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;

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

import com.android.launcher3.Utilities;
import com.android.launcher3.anim.IosWindowShape;

import java.util.function.ObjIntConsumer;

/**
 * A hardware bitmap card for the brief perspective portion of an iOS transition. SurfaceControl
 * matrices only support affine transforms; the Canvas homography also deforms content and corners.
 * Capture is asynchronous and bounded to one image. Until it arrives the live app stays visible.
 */
public final class IosWindowDeformation extends Drawable
        implements View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
    private final View mHost;
    private final int mWidth, mHeight;
    private final RectF mSourceBounds;
    private final Matrix mAppToHome = new Matrix();
    private final Matrix mDeformation = new Matrix();
    private final IosWindowShape mShape = new IosWindowShape();
    private final Path mClip = new Path();
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap mBitmap;
    private boolean mClosed;
    private boolean mAttached;
    private float mBlend;
    private float mFirstFrame = -1f;
    private final Runnable mTimeout = this::close;
    // Native ScreenCapture holds a weak callback; retain both until its reply.
    private ObjIntConsumer<ScreenCaptureInternal.ScreenshotHardwareBuffer> mReply;
    private ScreenCaptureInternal.ScreenCaptureListener mListener;

    public IosWindowDeformation(View host, SurfaceControl leash, Rect source, long duration) {
        mHost = host;
        mWidth = host.getWidth();
        mHeight = host.getHeight();
        mSourceBounds = new RectF(source);
        setBounds(0, 0, mWidth, mHeight);
        if (!ValueAnimator.areAnimatorsEnabled() || !host.isAttachedToWindow()
                || !leash.isValid() || source.isEmpty()) {
            mClosed = true;
            return;
        }
        mReply = (buffer, status) -> MAIN_EXECUTOR.execute(() -> {
            mReply = null;
            mListener = null;
            if (buffer == null) return;
            try {
                if (!mClosed && status == 0 && !buffer.containsSecureLayers()) {
                    mBitmap = buffer.asBitmap();
                }
            } finally {
                if (buffer.getHardwareBuffer() != null) buffer.getHardwareBuffer().close();
            }
        });
        mListener = new ScreenCaptureInternal.ScreenCaptureListener(mReply);
        try {
            int status = ScreenCaptureInternal.captureLayers(
                    new ScreenCaptureInternal.LayerCaptureArgs.Builder(leash)
                            .setSourceCrop(source)
                            .setFrameScale(Math.min(1f, 1440f
                                    / Math.max(source.width(), source.height())))
                            .build(), mListener);
            if (status != 0) close();
        } catch (IllegalArgumentException | SecurityException e) {
            close();
        }
        if (mClosed) return;
        host.postDelayed(mTimeout,
                Math.round(duration * Math.max(1f, ValueAnimator.getDurationScale())) + 200);
        host.addOnAttachStateChangeListener(this);
        host.addOnLayoutChangeListener(this);
    }

    /** Returns the fraction of the live surface replaced by the perspective card. */
    public float update(Matrix appToHome, Rect crop, float radius, float alpha,
            RectF homeRect, float time, float openness, boolean opening) {
        if (mClosed || mBitmap == null || !mHost.isShown()) return 0f;
        // A late capture must never replace a nearly full-screen, already live app.
        if (opening && time >= .75f) {
            mBlend = 0f;
            invalidateSelf();
            return 0f;
        }
        if (!mAttached) {
            mAttached = true;
            mHost.getOverlay().add(this);
            mFirstFrame = time;
        }
        float enter = Utilities.boundToRange((time - mFirstFrame) / .06f, 0f, 1f);
        float exit = opening ? Utilities.boundToRange((.75f - time) / .15f, 0f, 1f) : 1f;
        // Swap at identical geometry, then bend the card. Cross-fading an already bent
        // snapshot with the rectangular live surface produces two visible window edges.
        mBlend = exit;
        mAppToHome.set(appToHome);
        mDeformation.set(mShape.matrix(homeRect,
                IosWindowShape.amount(time, openness, opening) * enter, opening));
        mClip.rewind();
        mClip.addRoundRect(crop.left, crop.top, crop.right, crop.bottom,
                radius, radius, Path.Direction.CW);
        mPaint.setAlpha(Math.round(255f * Utilities.boundToRange(alpha * mBlend, 0f, 1f)));
        invalidateSelf();
        return mBlend;
    }

    public void close() {
        if (mClosed) return;
        mClosed = true;
        mHost.removeCallbacks(mTimeout);
        mHost.getOverlay().remove(this);
        mHost.removeOnAttachStateChangeListener(this);
        mHost.removeOnLayoutChangeListener(this);
        mBitmap = null;
    }

    @Override
    public void draw(Canvas canvas) {
        if (mClosed || mBitmap == null || mBlend == 0f) return;
        int save = canvas.save();
        canvas.concat(mDeformation);
        canvas.concat(mAppToHome);
        canvas.clipPath(mClip);
        canvas.drawBitmap(mBitmap, null, mSourceBounds, mPaint);
        canvas.restoreToCount(save);
    }

    @Override public void setAlpha(int alpha) { mPaint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { mPaint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public void onViewAttachedToWindow(View view) { }
    @Override public void onViewDetachedFromWindow(View view) { close(); }
    @Override public void onLayoutChange(View view, int l, int t, int r, int b,
            int oldL, int oldT, int oldR, int oldB) {
        if (r - l != mWidth || b - t != mHeight) close();
    }
}
