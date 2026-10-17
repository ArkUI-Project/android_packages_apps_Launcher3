/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.quickstep.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class IosWindowDeformationTest {
    private static final float EPSILON = 0.000001f;

    @Test
    public void opaqueWindow_handoffNeverExposesDesktop() {
        for (int frame = 0; frame <= 100; frame++) {
            assertCompositeAlpha(1f, frame / 100f);
        }
    }

    @Test
    public void fadingWindow_handoffPreservesRequestedOpacity() {
        for (int alphaStep = 0; alphaStep <= 100; alphaStep++) {
            for (int frame = 0; frame <= 100; frame++) {
                assertCompositeAlpha(alphaStep / 100f, frame / 100f);
            }
        }
    }

    @Test
    public void finishedHandoff_snapshotIsTransparent() {
        assertEquals(0f, IosWindowDeformation.getSnapshotAlpha(1f, 0f), 0f);
        assertEquals(0f, IosWindowDeformation.getSnapshotAlpha(0f, 1f), 0f);
    }

    private static void assertCompositeAlpha(float alpha, float blend) {
        float snapshotAlpha = IosWindowDeformation.getSnapshotAlpha(alpha, blend);
        assertTrue(Float.isFinite(snapshotAlpha));
        assertTrue(snapshotAlpha >= 0f && snapshotAlpha <= 1f);
        float liveAlpha = alpha * (1f - blend);
        float compositeAlpha = liveAlpha + snapshotAlpha * (1f - liveAlpha);
        assertEquals(alpha, compositeAlpha, EPSILON);
    }
}
