/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.anim;

import android.view.animation.Interpolator;

/**
 * The measured return has one continuous arrival: width settles before height, then the center.
 * Values are fractions of the initial travel, but the small undershoot is in destination-icon
 * units so returning from a larger window does not squash the icon more. Every join has a shared
 * tangent, and the final tangent is zero before the real workspace icon takes over.
 */
public final class IosHomeMotion implements Interpolator {
    private final float[] mTimes;
    private final float[] mValues;
    private final float[] mTangents;

    public static Interpolator width(float start, float end) {
        float undershoot = start > end ? excursion(start, end, end * .04f) : 0f;
        return new IosHomeMotion(
                new float[] {0f, 1f / 6f, 1f / 3f, .50f, 2f / 3f, 1f},
                new float[] {0f, .715f, .955f, 1f + undershoot,
                        1f + undershoot * .5f, 1f}, false);
    }

    public static Interpolator height(float start, float end) {
        float undershoot = start > end ? excursion(start, end, end * .02f) : 0f;
        return new IosHomeMotion(
                new float[] {0f, 1f / 6f, 1f / 3f, .50f, .65f, 1f},
                new float[] {0f, .79f, .964f, 1f, 1f + undershoot, 1f}, false);
    }

    public static Interpolator center(float start, float end, float iconSize,
            boolean horizontal) {
        float overshoot = excursion(start, end, iconSize * (horizontal ? .015f : .025f));
        return horizontal
                ? new IosHomeMotion(
                        new float[] {0f, 1f / 6f, 1f / 3f, .50f, .72f, 1f},
                        new float[] {0f, .70f, .97f, 1f, 1f + overshoot, 1f}, true)
                : new IosHomeMotion(
                        new float[] {0f, .08f, 1f / 6f, 1f / 3f, .50f, .72f, 1f},
                        new float[] {0f, .02f, .34f, .82f, .975f,
                                1f + overshoot, 1f}, true);
    }

    private static float excursion(float start, float end, float pixels) {
        // A gesture released next to its destination must not create a new large excursion.
        return Math.min(.04f, pixels / Math.max(1f, Math.abs(end - start)));
    }

    private IosHomeMotion(float[] times, float[] values, boolean startAtRest) {
        mTimes = times;
        mValues = values;
        mTangents = new float[times.length];
        for (int i = 1; i < times.length - 1; i++) {
            float before = times[i] - times[i - 1];
            float after = times[i + 1] - times[i];
            float left = (values[i] - values[i - 1]) / before;
            float right = (values[i + 1] - values[i]) / after;
            if (left * right > 0f) {
                // Shape-preserving cubic Hermite tangents: never add another reversal between
                // measured samples, including the single minimum in width and height.
                float a = 2f * after + before;
                float b = after + 2f * before;
                mTangents[i] = (a + b) / (a / left + b / right);
            }
        }
        if (!startAtRest) {
            float first = (values[1] - values[0]) / (times[1] - times[0]);
            float second = (values[2] - values[1]) / (times[2] - times[1]);
            mTangents[0] = Math.min(3f * first, Math.max(0f, (3f * first - second) / 2f));
        }
    }

    @Override
    public float getInterpolation(float input) {
        if (input <= 0f) return 0f;
        if (input >= 1f) return 1f;
        int i = 0;
        while (input > mTimes[i + 1]) i++;
        float span = mTimes[i + 1] - mTimes[i];
        float t = (input - mTimes[i]) / span;
        float t2 = t * t;
        float t3 = t2 * t;
        return (2f * t3 - 3f * t2 + 1f) * mValues[i]
                + (t3 - 2f * t2 + t) * span * mTangents[i]
                + (-2f * t3 + 3f * t2) * mValues[i + 1]
                + (t3 - t2) * span * mTangents[i + 1];
    }
}
