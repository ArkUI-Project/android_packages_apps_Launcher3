/*
 * Copyright (C) 2026 The ArkUI Project
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

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import com.android.internal.graphics.drawable.BackgroundBlurDrawable;
import com.android.launcher3.graphics.FrostedSurfaceDrawable;

import java.util.function.Consumer;

/** SurfaceFlinger follows each card's RenderNode through desktop and drag animations. */
public final class LiveFrostedBackdrop implements FrostedSurfaceDrawable.Backdrop {
    private final View mOwner;
    private final WindowManager mWindowManager;
    private final BackgroundBlurDrawable mBlur;
    private final int mRadius;
    private final Consumer<Boolean> mListener;
    private final ViewTreeObserver mObserver;
    private final ViewTreeObserver.OnPreDrawListener mPreDrawListener;
    private boolean mEnabled;
    private boolean mClosed;
    private boolean mDrawn;
    private int mDrawableAlpha;
    private int mCompositeAlpha;

    public LiveFrostedBackdrop(View owner) {
        mOwner = owner;
        mWindowManager = owner.getContext().getSystemService(WindowManager.class);
        mRadius = Math.round(30 * owner.getResources().getDisplayMetrics().density);
        mBlur = owner.getViewRootImpl() == null ? null
                : owner.getViewRootImpl().createBackgroundBlurDrawable();
        if (mBlur != null) {
            mBlur.setColor(Color.TRANSPARENT);
            hide();
        }
        mEnabled = mWindowManager.isCrossWindowBlurEnabled();
        mListener = enabled -> {
            if (mClosed) return;
            mEnabled = enabled;
            if (!enabled) hide();
            mOwner.invalidate();
        };
        mWindowManager.addCrossWindowBlurEnabledListener(owner.getContext().getMainExecutor(),
                mListener);
        mObserver = owner.getViewTreeObserver();
        mPreDrawListener = () -> {
            if (mDrawn && !mClosed) {
                int alpha = compositeAlpha();
                if (mCompositeAlpha != alpha) {
                    mCompositeAlpha = alpha;
                    if (alpha == 0) {
                        hideRegion();
                    } else {
                        showRegion(alpha);
                        mBlur.setBounds(mBlur.getBounds());
                    }
                    // Ancestor fades normally update RenderNodes without rerecording backgrounds.
                    mOwner.invalidate();
                }
            }
            return true;
        };
        mObserver.addOnPreDrawListener(mPreDrawListener);
    }

    @Override
    public boolean isEnabled() {
        return !mClosed && mEnabled && mBlur != null;
    }

    @Override
    public void draw(Canvas canvas, Rect bounds, float radius, float[] radii, int alpha) {
        mDrawn = true;
        mDrawableAlpha = alpha;
        mCompositeAlpha = compositeAlpha();
        if (mCompositeAlpha == 0) {
            hideRegion();
            return;
        }
        showRegion(mCompositeAlpha);
        if (radii == null) {
            mBlur.setCornerRadius(radius);
        } else {
            mBlur.setCornerRadius(radii[0], radii[2], radii[6], radii[4]);
        }
        // Rebuild the path after visibility/alpha changes, including hide -> show transitions.
        mBlur.setBounds(bounds);
        mBlur.draw(canvas);
    }

    private int compositeAlpha() {
        if (!mEnabled || !mOwner.isShown() || mOwner.getWindowVisibility() != View.VISIBLE) return 0;
        float alpha = mDrawableAlpha / 255f;
        View view = mOwner;
        while (true) {
            alpha *= view.getAlpha();
            if (!(view.getParent() instanceof View)) break;
            view = (View) view.getParent();
        }
        return Math.round(255 * alpha);
    }

    private void showRegion(int alpha) {
        mBlur.setVisible(true, false);
        mBlur.setAlpha(alpha);
        mBlur.setBlurRadius(mRadius);
    }

    @Override
    public void hide() {
        mDrawn = false;
        mCompositeAlpha = 0;
        hideRegion();
    }

    private void hideRegion() {
        if (mBlur == null) return;
        mBlur.setBlurRadius(0);
        mBlur.setAlpha(0);
        mBlur.setVisible(false, false);
    }

    @Override
    public void close() {
        if (mClosed) return;
        mClosed = true;
        hide();
        if (mObserver.isAlive()) mObserver.removeOnPreDrawListener(mPreDrawListener);
        mWindowManager.removeCrossWindowBlurEnabledListener(mListener);
    }
}
