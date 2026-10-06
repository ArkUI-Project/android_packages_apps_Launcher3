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

package com.android.launcher3.graphics;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import androidx.core.graphics.ColorUtils;

/** A tonal Material surface with a live, independently rendered blurred backdrop. */
public class FrostedSurfaceDrawable extends GradientDrawable
        implements View.OnAttachStateChangeListener {

    /** Quickstep supplies the platform compositor; SDK-only launcher variants use tonal fill. */
    public interface Backdrop {
        boolean isEnabled();
        void draw(Canvas canvas, Rect bounds, float radius, float[] radii, int alpha);
        void hide();
        void close();
    }

    public interface BackdropFactory {
        Backdrop create(View owner);
    }

    private static BackdropFactory sBackdropFactory;

    private final View mOwner;
    private final float mDensity;
    private Backdrop mBackdrop;
    private int mSurfaceColor;
    private boolean mBlurred;
    private float[] mRadii;
    private boolean mDisposed;

    public static void setBackdropFactory(BackdropFactory factory) {
        sBackdropFactory = factory;
    }

    public FrostedSurfaceDrawable(View owner, int surfaceColor, float radius) {
        mOwner = owner;
        mDensity = owner.getResources().getDisplayMetrics().density;
        setCornerRadius(radius);
        setColor(surfaceColor);
        owner.addOnAttachStateChangeListener(this);
        if (owner.isAttachedToWindow()) attachBackdrop();
    }

    @Override
    public void setColor(int color) {
        mSurfaceColor = color;
        updateFill();
    }

    private void updateFill() {
        boolean dark = ColorUtils.calculateLuminance(mSurfaceColor | 0xff000000) < .35;
        int alpha = mBlurred ? (dark ? 184 : 156) : 242;
        super.setColor(ColorUtils.setAlphaComponent(mSurfaceColor, alpha));
        super.setStroke(Math.max(1, Math.round(mDensity * .5f)),
                ColorUtils.setAlphaComponent(Color.WHITE, dark ? 28 : 72));
    }

    @Override
    public void setCornerRadii(float[] radii) {
        mRadii = radii == null ? null : radii.clone();
        super.setCornerRadii(radii);
    }

    @Override
    public void setCornerRadius(float radius) {
        mRadii = null;
        super.setCornerRadius(radius);
    }

    @Override
    public void setBounds(int left, int top, int right, int bottom) {
        // Circular clock faces keep their shape even when resized to a rectangular grid area.
        if (getShape() == OVAL) {
            int size = Math.min(right - left, bottom - top);
            left += (right - left - size) / 2;
            top += (bottom - top - size) / 2;
            right = left + size;
            bottom = top + size;
        }
        super.setBounds(left, top, right, bottom);
    }

    @Override
    public void draw(Canvas canvas) {
        if (!isVisible() || getAlpha() == 0) {
            if (mBackdrop != null) mBackdrop.hide();
            return;
        }
        boolean blurred = mBackdrop != null && mBackdrop.isEnabled()
                && canvas.isHardwareAccelerated();
        if (blurred != mBlurred) {
            mBlurred = blurred;
            updateFill();
        }
        if (blurred) {
            float radius = getShape() == OVAL ? getBounds().width() / 2f : getCornerRadius();
            mBackdrop.draw(canvas, getBounds(), radius, mRadii, getAlpha());
        } else if (mBackdrop != null) {
            mBackdrop.hide();
        }
        super.draw(canvas);
    }

    @Override
    public void setAlpha(int alpha) {
        if (alpha == 0 && mBackdrop != null) mBackdrop.hide();
        super.setAlpha(alpha);
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        if (!visible && mBackdrop != null) mBackdrop.hide();
        return super.setVisible(visible, restart);
    }

    private void attachBackdrop() {
        if (!mDisposed && mBackdrop == null && sBackdropFactory != null) {
            mBackdrop = sBackdropFactory.create(mOwner);
            mOwner.invalidate();
        }
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        attachBackdrop();
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        if (mBackdrop != null) {
            mBackdrop.close();
            mBackdrop = null;
        }
    }

    /** Release listeners when a RemoteViews update replaces this surface. */
    public void dispose() {
        mDisposed = true;
        onViewDetachedFromWindow(mOwner);
        mOwner.removeOnAttachStateChangeListener(this);
    }
}
