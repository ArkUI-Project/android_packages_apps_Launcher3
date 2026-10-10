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
        if (!opening) {
            // The card bends while shrinking quickly and straightens before the small size
            // undershoot. Keeping a half-sine over the whole return pinches the landing icon.
            float phase = Utilities.boundToRange(time < .12f ? time / .12f
                    : (.50f - time) / .38f, 0f, 1f);
            return phase * phase * (3f - 2f * phase);
        }
        float phase = Utilities.boundToRange(time / .75f, 0f, 1f);
        float envelope = (float) Math.sin(Math.PI * phase);
        return envelope * Math.min(1f, 2f * (1f - openness));
    }

    public Matrix matrix(RectF bounds, float amount, boolean opening) {
        float l = bounds.left, t = bounds.top, r = bounds.right, b = bounds.bottom;
        float inset = bounds.width() * (opening ? .15f : .14f) * amount;
        float skew = bounds.width() * (opening ? .045f : .006f) * amount;
        float lift = bounds.height() * (opening ? .025f : .003f) * amount;
        mSource[0] = l; mSource[1] = t;
        mSource[2] = r; mSource[3] = t;
        mSource[4] = r; mSource[5] = b;
        mSource[6] = l; mSource[7] = b;
        // On return, taper about the center width rather than continually reducing it.
        // The size trajectory owns compression, including for circular adaptive icons.
        float topInset = opening ? 0f : -inset / 2f;
        float bottomInset = opening ? inset : inset / 2f;
        mDestination[0] = l + topInset + skew; mDestination[1] = t;
        mDestination[2] = r - topInset + skew; mDestination[3] = t + lift;
        mDestination[4] = r - bottomInset - skew; mDestination[5] = b - lift;
        mDestination[6] = l + bottomInset - skew; mDestination[7] = b;
        mMatrix.setPolyToPoly(mSource, 0, mDestination, 0, 4);
        return mMatrix;
    }
}
