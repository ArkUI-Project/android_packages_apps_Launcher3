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

package com.android.launcher3.widget;

import android.appwidget.AppWidgetHostView;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewTreeObserver;

import com.android.launcher3.graphics.FrostedSurfaceDrawable;

/** Applies the host's system surface color without changing provider imagery or actions. */
public final class FrostedWidgetBackground implements View.OnAttachStateChangeListener {
    private final AppWidgetHostView mHost;
    private View mView;
    private Drawable mOriginal;
    private ColorStateList mOriginalTint;
    private FrostedSurfaceDrawable mSurface;
    private ViewTreeObserver mObserver;
    private final ViewTreeObserver.OnPreDrawListener mPreDrawListener = () -> {
        // Async RemoteViews reapply can replace a background without requesting layout.
        apply();
        return true;
    };

    public FrostedWidgetBackground(AppWidgetHostView host) {
        mHost = host;
        host.addOnAttachStateChangeListener(this);
        if (host.isAttachedToWindow()) onViewAttachedToWindow(host);
    }

    /** Select a provider's dark-text layout, when supplied, before RemoteViews are inflated. */
    public void prepareForUpdate() {
        boolean dark = (mHost.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        mHost.setOnLightBackground(!dark);
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        mObserver = view.getViewTreeObserver();
        mObserver.addOnPreDrawListener(mPreDrawListener);
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        if (mObserver != null && mObserver.isAlive()) {
            mObserver.removeOnPreDrawListener(mPreDrawListener);
        }
        mObserver = null;
    }

    public void apply() {
        if (mHost.getChildCount() == 0 || mHost.getAppWidgetInfo() == null) {
            clear();
            return;
        }
        if (mSurface != null && mView.getBackground() == mSurface
                && isDescendant(mView)) {
            // Reapplied RemoteViews may set a new tint without reinflating the body.
            ColorStateList tint = mView.getBackgroundTintList();
            if (tint != null) {
                mOriginalTint = tint;
                // Preserve the provider tint for restoration without tinting the themed surface.
                mView.setBackgroundTintList(null);
            }
            return;
        }
        clear();
        View body = RoundedCornerEnforcement.findBackground(mHost);
        if (body == null || body == mHost || !canDecorate(body.getBackground())) return;

        mView = body;
        mOriginal = body.getBackground();
        mOriginalTint = body.getBackgroundTintList();
        float radius = 28 * body.getResources().getDisplayMetrics().density;
        if (mOriginal instanceof GradientDrawable) {
            radius = Math.max(radius, ((GradientDrawable) mOriginal).getCornerRadius());
        }
        mSurface = FrostedSurfaceDrawable.forSystemTheme(body, mHost.getContext(), radius);
        if (mOriginal instanceof GradientDrawable
                && ((GradientDrawable) mOriginal).getShape() == GradientDrawable.OVAL) {
            mSurface.setShape(GradientDrawable.OVAL);
        }
        body.setBackgroundTintList(null);
        body.setBackground(mSurface);
    }

    private boolean isDescendant(View view) {
        while (view != mHost && view.getParent() instanceof View) {
            view = (View) view.getParent();
        }
        return view == mHost;
    }

    private static boolean canDecorate(Drawable drawable) {
        // Native search surfaces already own a themed backdrop and its attachment lifecycle.
        if (drawable instanceof FrostedSurfaceDrawable) return false;
        if (drawable == null || drawable instanceof ColorDrawable) return true;
        if (drawable instanceof GradientDrawable) {
            GradientDrawable shape = (GradientDrawable) drawable;
            return shape.getColors() == null && (shape.getShape() == GradientDrawable.RECTANGLE
                    || shape.getShape() == GradientDrawable.OVAL);
        }
        // Artwork, masks and provider-specific ripple/button backgrounds retain their shape.
        return false;
    }

    public void clear() {
        if (mSurface == null) return;
        mSurface.dispose();
        if (mView.getBackground() == mSurface) {
            mView.setBackground(mOriginal);
            mView.setBackgroundTintList(mOriginalTint);
        }
        mSurface = null;
        mView = null;
        mOriginal = null;
        mOriginalTint = null;
    }
}
