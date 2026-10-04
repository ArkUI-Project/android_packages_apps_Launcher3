/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.anim;

import android.view.animation.Interpolator;

import com.android.launcher3.Utilities;

/**
 * One bounded timeline for app windows. The curve controls travel; a damped residual preserves
 * velocity when a gesture or another animation hands over. Evaluation does not allocate.
 */
public final class WindowMotion {
    private final Interpolator mCurve;
    private final float mDuration;
    private final float mStart;
    private final double mDamping;
    private final double mDecay;
    private final double mRoot;
    private float mEnd;
    private float mResidualStart;
    private double mDisplacement;
    private double mVelocity;

    /** Velocity is in units/ms; NaN starts on the configured curve without a handoff. */
    public WindowMotion(float start, float end, float velocity, long duration,
            Interpolator curve, float stiffness, float damping) {
        mStart = start;
        mEnd = end;
        mDuration = Math.max(1, duration);
        mCurve = curve;
        double frequency = Math.sqrt(stiffness);
        mDamping = damping;
        mDecay = damping * frequency;
        mRoot = frequency * Math.sqrt(Math.abs(1.0 - damping * (double) damping));
        if (Float.isFinite(velocity)) {
            // A custom curve may start with a vertical tangent. Keep that tangent from
            // turning the handoff correction into an off-screen excursion.
            float limit = Math.abs(velocity) + 8f * Math.abs(end - start) / mDuration;
            mVelocity = Utilities.boundToRange(velocity - baseVelocity(0f), -limit, limit) * 1000f;
        }
    }

    public float value(float elapsedMs) {
        if (elapsedMs >= mDuration) return mEnd;
        float time = Math.max(0f, elapsedMs);
        return value(time, mCurve.getInterpolation(time / mDuration));
    }

    /** Reuse one evaluated curve for every channel in the same frame. */
    public float value(float elapsedMs, float progress) {
        if (elapsedMs >= mDuration) return mEnd;
        return Utilities.mapRange(progress, mStart, mEnd) + residual(Math.max(0f, elapsedMs));
    }

    /** Retarget from the displayed frame, retaining both its position and its tangent. */
    public void retarget(float end, float elapsedMs) {
        if (end == mEnd || elapsedMs >= mDuration) return;
        float position = value(elapsedMs);
        float velocity = velocity(elapsedMs);
        mEnd = end;
        mResidualStart = elapsedMs;
        mDisplacement = position - baseValue(elapsedMs);
        // The endpoint envelope has derivative -2 / remaining at its start.
        mVelocity = (velocity - baseVelocity(elapsedMs)) * 1000f
                + 2 * mDisplacement / ((mDuration - elapsedMs) / 1000f);
    }

    private float baseValue(float time) {
        return Utilities.mapRange(mCurve.getInterpolation(time / mDuration), mStart, mEnd);
    }

    private float baseVelocity(float time) {
        float before = Math.max(0f, time - 0.5f);
        float after = Math.min(mDuration, time + 0.5f);
        return (baseValue(after) - baseValue(before)) / Math.max(0.001f, after - before);
    }

    private float velocity(float time) {
        float before = Math.max(mResidualStart, time - 0.5f);
        float after = Math.min(mDuration, time + 0.5f);
        return (value(after) - value(before)) / Math.max(0.001f, after - before);
    }

    private float residual(float time) {
        if (mDisplacement == 0 && mVelocity == 0) return 0f;
        double t = Math.max(0f, time - mResidualStart) / 1000.0;
        double offset;
        if (mDamping < 1.0) {
            offset = Math.exp(-mDecay * t) * (mDisplacement * Math.cos(mRoot * t)
                    + (mVelocity + mDecay * mDisplacement) / mRoot * Math.sin(mRoot * t));
        } else if (mDamping > 1.0) {
            double slow = -mDecay + mRoot;
            double fast = -mDecay - mRoot;
            double coefficient = (mVelocity - fast * mDisplacement) / (slow - fast);
            offset = coefficient * Math.exp(slow * t)
                    + (mDisplacement - coefficient) * Math.exp(fast * t);
        } else {
            offset = Math.exp(-mDecay * t)
                    * (mDisplacement + (mVelocity + mDecay * mDisplacement) * t);
        }
        // Reach the exact endpoint with zero residual velocity at the configured duration.
        double remaining = 1.0 - (time - mResidualStart) / (mDuration - mResidualStart);
        return (float) (offset * remaining * remaining);
    }
}
