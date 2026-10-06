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

package com.android.launcher3.qsb;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import com.android.launcher3.graphics.FrostedSurfaceDrawable;

/** A pill-shaped desktop search surface; the child buttons keep their own ripples. */
public final class FrostedSearchView extends LinearLayout {
    private final FrostedSurfaceDrawable mSurface;

    public FrostedSearchView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mSurface = new FrostedSurfaceDrawable(this,
                com.android.launcher3.util.Themes.getAttrColor(context,
                        com.android.launcher3.R.attr.folderBackgroundColor),
                28 * getResources().getDisplayMetrics().density);
        setBackground(mSurface);
        setClipToOutline(true);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        mSurface.setCornerRadius(height / 2f);
    }
}
