/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.folder;

import static com.android.app.animation.Interpolators.EMPHASIZED;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;

import com.android.launcher3.Launcher;
import com.android.launcher3.Insettable;
import com.android.launcher3.views.BaseDragLayer;

/** Redraw the selected folder above the blurred workspace while its action menu is open. */
final class FolderFocus extends View implements Insettable {
    private final Launcher mLauncher;
    private final FolderIcon mIcon;
    private final Rect mBounds = new Rect();
    private ValueAnimator mAnimator;
    private float mProgress;
    private boolean mClosing;

    FolderFocus(FolderIcon icon) {
        super(icon.getContext());
        mIcon = icon;
        mLauncher = Launcher.getLauncher(getContext());
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false);
    }

    void start() {
        mLauncher.getDragLayer().addView(this, new BaseDragLayer.LayoutParams(-1, -1));
        mLauncher.beginFolderFocusBlur(this);
        animateBlur(1, false);
    }

    void close(boolean animate) {
        if (mClosing) return;
        mClosing = true;
        if (animate && ValueAnimator.areAnimatorsEnabled()) animateBlur(0, true);
        else {
            if (mAnimator != null) mAnimator.cancel();
            mLauncher.setFolderFocusBlur(this, 0);
            mLauncher.getDragLayer().removeView(this);
        }
    }

    private void animateBlur(float end, boolean remove) {
        if (mAnimator != null) mAnimator.cancel();
        mAnimator = ValueAnimator.ofFloat(mProgress, end);
        mAnimator.setInterpolator(EMPHASIZED);
        mAnimator.setDuration(remove ? 200 : 280);
        mAnimator.addUpdateListener(animator -> {
            mProgress = (float) animator.getAnimatedValue();
            if (mProgress > 0 || remove) mLauncher.setFolderFocusBlur(this, mProgress);
            invalidate();
        });
        if (remove) mAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animator) {
                mLauncher.getDragLayer().removeView(FolderFocus.this);
            }
        });
        mAnimator.start();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (!mIcon.isAttachedToWindow()) return;
        float scale = mLauncher.getDragLayer().getDescendantRectRelativeToSelf(mIcon, mBounds);
        int save = canvas.save();
        canvas.translate(mBounds.left, mBounds.top);
        canvas.scale(scale, scale);
        mIcon.draw(canvas);
        canvas.restoreToCount(save);
    }

    @Override protected void onDetachedFromWindow() {
        if (mAnimator != null) mAnimator.cancel();
        mLauncher.setFolderFocusBlur(this, 0);
        super.onDetachedFromWindow();
    }

    @Override public void setInsets(Rect insets) { }
}
