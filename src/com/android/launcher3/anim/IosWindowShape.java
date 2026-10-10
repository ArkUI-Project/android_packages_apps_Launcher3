/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.anim;

import android.graphics.Matrix;
import android.graphics.RectF;

import com.android.launcher3.Utilities;

/** Shared perspective envelope for the app card and the icon receiving it. */
public final class IosWindowShape {
    private final float[] mSource = new float[8];
    private final float[] mDestination = new float[8];
    private final Matrix mMatrix = new Matrix();

    public static float amount(float time, float openness, boolean opening) {
        float phase = Utilities.boundToRange(opening ? time / .75f : time, 0f, 1f);
        float envelope = (float) Math.sin(Math.PI * phase);
        return opening ? envelope * Math.min(1f, 2f * (1f - openness)) : envelope;
    }

    public Matrix matrix(RectF bounds, float amount) {
        float l = bounds.left, t = bounds.top, r = bounds.right, b = bounds.bottom;
        float inset = bounds.width() * .15f * amount;
        float skew = bounds.width() * .045f * amount;
        float lift = bounds.height() * .025f * amount;
        mSource[0] = l; mSource[1] = t;
        mSource[2] = r; mSource[3] = t;
        mSource[4] = r; mSource[5] = b;
        mSource[6] = l; mSource[7] = b;
        mDestination[0] = l + skew; mDestination[1] = t;
        mDestination[2] = r + skew; mDestination[3] = t + lift;
        mDestination[4] = r - inset - skew; mDestination[5] = b - lift;
        mDestination[6] = l + inset - skew; mDestination[7] = b;
        mMatrix.setPolyToPoly(mSource, 0, mDestination, 0, 4);
        return mMatrix;
    }
}
