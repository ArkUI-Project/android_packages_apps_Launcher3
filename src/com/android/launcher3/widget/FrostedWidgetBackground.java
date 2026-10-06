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
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewTreeObserver;

import com.android.launcher3.R;
import com.android.launcher3.graphics.FrostedSurfaceDrawable;
import com.android.launcher3.util.Themes;

/** Decorates a widget's surface while leaving provider text, imagery and actions intact. */
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
                mSurface.setColor(resolveColor(mView, mOriginal, tint));
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
        mSurface = new FrostedSurfaceDrawable(body,
                resolveColor(body, mOriginal, mOriginalTint), radius);
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
        if (drawable == null || drawable instanceof ColorDrawable) return true;
        if (drawable instanceof GradientDrawable) {
            GradientDrawable shape = (GradientDrawable) drawable;
            return shape.getColors() == null && (shape.getShape() == GradientDrawable.RECTANGLE
                    || shape.getShape() == GradientDrawable.OVAL);
        }
        // Artwork, masks and provider-specific ripple/button backgrounds retain their shape.
        return false;
    }

    private int resolveColor(View view, Drawable original, ColorStateList tint) {
        int color = 0;
        if (tint != null) {
            color = tint.getColorForState(view.getDrawableState(), tint.getDefaultColor());
        } else if (original instanceof ColorDrawable) {
            color = ((ColorDrawable) original).getColor();
        } else if (original instanceof GradientDrawable) {
            ColorStateList fill = ((GradientDrawable) original).getColor();
            if (fill != null) color = fill.getDefaultColor();
        }
        if ((color >>> 24) == 0) {
            boolean darkText = Themes.getAttrBoolean(mHost.getContext(), R.attr.isWorkspaceDarkText);
            color = mHost.getContext().getColor(darkText ? android.R.color.system_neutral1_50
                    : android.R.color.system_neutral1_900);
        }
        return color;
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
