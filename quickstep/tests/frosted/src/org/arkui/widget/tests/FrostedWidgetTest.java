/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.arkui.widget.tests;

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.launcher3.Launcher;
import com.android.launcher3.graphics.FrostedSurfaceDrawable;
import com.android.launcher3.widget.FrostedWidgetBackground;
import com.android.quickstep.util.LiveFrostedBackdrop;

import java.util.concurrent.atomic.AtomicBoolean;

/** Device regressions for RemoteViews updates, geometry, foreground and compositor lifetime. */
public final class FrostedWidgetTest extends InstrumentationTestCase {
    private Launcher mLauncher;
    private FrameLayout mFixture;
    private AppWidgetHostView mHost;
    private FrostedWidgetBackground mController;
    private FakeBackdrop mLastBackdrop;
    private int mCreates;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        getInstrumentation().runOnMainSync(() ->
                mLauncher = Launcher.ACTIVITY_TRACKER.getCreatedContext());
        if (mLauncher == null) {
            getInstrumentation().startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setComponent(new ComponentName("com.android.launcher3",
                            "com.android.launcher3.uioverrides.QuickstepLauncher"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            getInstrumentation().runOnMainSync(() ->
                    mLauncher = Launcher.ACTIVITY_TRACKER.getCreatedContext());
        }
        assertNotNull("Native launcher activity", mLauncher);
        getInstrumentation().runOnMainSync(() -> {
            FrostedSurfaceDrawable.setBackdropFactory(owner -> {
                mCreates++;
                return mLastBackdrop = new FakeBackdrop();
            });
            mFixture = new FrameLayout(mLauncher);
            mLauncher.getDragLayer().addView(mFixture,
                    new com.android.launcher3.views.BaseDragLayer.LayoutParams(600, 420));
            mHost = new AppWidgetHostView(mLauncher);
            AppWidgetProviderInfo info = new AppWidgetProviderInfo();
            info.provider = new ComponentName(mLauncher, Launcher.class);
            info.providerInfo = new android.content.pm.ActivityInfo();
            info.providerInfo.applicationInfo = mLauncher.getApplicationInfo();
            info.providerInfo.packageName = mLauncher.getPackageName();
            info.providerInfo.name = Launcher.class.getName();
            info.providerInfo.nonLocalizedLabel = "Widget surface fixture";
            mHost.setAppWidget(-1, info);
            mFixture.addView(mHost, new FrameLayout.LayoutParams(600, 420));
            mController = new FrostedWidgetBackground(mHost);
        });
    }

    @Override
    protected void tearDown() throws Exception {
        getInstrumentation().runOnMainSync(() -> {
            if (mController != null) mController.clear();
            if (mFixture != null) mLauncher.getDragLayer().removeView(mFixture);
            FrostedSurfaceDrawable.setBackdropFactory(LiveFrostedBackdrop::new);
        });
        super.tearDown();
    }

    private FrameLayout body(int color) {
        FrameLayout body = new FrameLayout(mLauncher);
        body.setId(android.R.id.background);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(24);
        body.setBackground(background);
        mHost.addView(body, new FrameLayout.LayoutParams(600, 420));
        mController.apply();
        return body;
    }

    public void testProviderTextAndClickArePreserved() {
        getInstrumentation().runOnMainSync(() -> {
            FrameLayout body = body(Color.WHITE);
            TextView text = new TextView(mLauncher);
            text.setText("日程 • 14:30");
            text.setTextColor(Color.BLACK);
            text.setContentDescription("打开日程");
            AtomicBoolean clicked = new AtomicBoolean();
            text.setOnClickListener(v -> clicked.set(true));
            body.addView(text);
            mController.apply();
            assertEquals("日程 • 14:30", text.getText().toString());
            assertEquals(Color.BLACK, text.getCurrentTextColor());
            assertEquals("打开日程", text.getContentDescription());
            assertTrue(text.performClick());
            assertTrue(clicked.get());
        });
    }

    public void testReappliedTintUpdatesExistingSurface() {
        getInstrumentation().runOnMainSync(() -> {
            FrameLayout body = body(Color.WHITE);
            Object surface = body.getBackground();
            int creates = mCreates;
            body.setBackgroundTintList(ColorStateList.valueOf(0xff244033));
            mController.apply();
            assertSame(surface, body.getBackground());
            assertNull(body.getBackgroundTintList());
            assertEquals(0x244033,
                    ((GradientDrawable) surface).getColor().getDefaultColor() & 0xffffff);
            assertEquals(creates, mCreates);
        });
    }

    public void testReplacedProviderBackgroundReleasesOldRegion() {
        getInstrumentation().runOnMainSync(() -> {
            FrameLayout body = body(Color.WHITE);
            FakeBackdrop previous = mLastBackdrop;
            body.setBackground(new ColorDrawable(Color.BLACK));
            mController.apply();
            assertTrue(previous.closed);
            assertTrue(body.getBackground() instanceof FrostedSurfaceDrawable);
        });
    }

    public void testFrameReappliesSurfaceWithoutLayout() {
        FrameLayout[] body = new FrameLayout[1];
        FakeBackdrop[] previous = new FakeBackdrop[1];
        getInstrumentation().runOnMainSync(() -> {
            body[0] = body(Color.WHITE);
            previous[0] = mLastBackdrop;
            body[0].setBackground(new ColorDrawable(Color.BLACK));
        });
        long deadline = android.os.SystemClock.uptimeMillis() + 3000;
        AtomicBoolean restored = new AtomicBoolean();
        while (!restored.get() && android.os.SystemClock.uptimeMillis() < deadline) {
            android.os.SystemClock.sleep(40);
            getInstrumentation().runOnMainSync(() -> restored.set(
                    body[0].getBackground() instanceof FrostedSurfaceDrawable));
        }
        assertTrue("Provider update restyled on the next rendered frame", restored.get());
        assertTrue(previous[0].closed);
    }

    public void testReinflatedBodyReleasesOldRegion() {
        getInstrumentation().runOnMainSync(() -> {
            body(Color.WHITE);
            FakeBackdrop previous = mLastBackdrop;
            mHost.removeAllViews();
            FrameLayout replacement = body(Color.BLACK);
            assertTrue(previous.closed);
            assertTrue(replacement.getBackground() instanceof FrostedSurfaceDrawable);
        });
    }

    public void testClearRestoresProviderDrawableAndTint() {
        getInstrumentation().runOnMainSync(() -> {
            FrameLayout body = body(Color.WHITE);
            body.setBackgroundTintList(ColorStateList.valueOf(Color.RED));
            mController.apply();
            FakeBackdrop previous = mLastBackdrop;
            mController.clear();
            assertTrue(previous.closed);
            assertFalse(body.getBackground() instanceof FrostedSurfaceDrawable);
            assertEquals(Color.RED, body.getBackgroundTintList().getDefaultColor());
            mController.apply();
            assertTrue(body.getBackground() instanceof FrostedSurfaceDrawable);
        });
    }

    public void testArtworkBackgroundIsPreserved() {
        getInstrumentation().runOnMainSync(() -> {
            FrameLayout body = body(Color.WHITE);
            mController.clear();
            Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            android.graphics.drawable.BitmapDrawable artwork =
                    new android.graphics.drawable.BitmapDrawable(mLauncher.getResources(), bitmap);
            body.setBackground(artwork);
            mController.apply();
            assertSame(artwork, body.getBackground());
            body.setBackground(null);
            bitmap.recycle();
        });
    }

    public void testGradientArtworkIsPreserved() {
        getInstrumentation().runOnMainSync(() -> {
            FrameLayout body = body(Color.WHITE);
            mController.clear();
            GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[] {Color.BLUE, Color.GREEN});
            body.setBackground(gradient);
            mController.apply();
            assertSame(gradient, body.getBackground());
        });
    }

    public void testCircularSurfaceStaysCircularWhenResized() {
        getInstrumentation().runOnMainSync(() -> {
            FrameLayout body = body(Color.WHITE);
            FrostedSurfaceDrawable surface = (FrostedSurfaceDrawable) body.getBackground();
            surface.setShape(GradientDrawable.OVAL);
            surface.setBounds(0, 0, 600, 420);
            assertEquals(new Rect(90, 0, 510, 420), surface.getBounds());
            surface.setBounds(0, 0, 260, 540);
            assertEquals(new Rect(0, 140, 260, 400), surface.getBounds());
        });
    }

    public void testSoftwareRenderingUsesReadableFallback() {
        getInstrumentation().runOnMainSync(() -> {
            FrameLayout body = body(Color.BLACK);
            FrostedSurfaceDrawable surface = (FrostedSurfaceDrawable) body.getBackground();
            Bitmap image = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            surface.setBounds(0, 0, 96, 96);
            surface.draw(new Canvas(image));
            assertEquals(242, Color.alpha(surface.getColor().getDefaultColor()));
            assertTrue(mLastBackdrop.hides > 0);
            image.recycle();
        });
    }

    public void testInvisibleDrawableReleasesItsBlurRegion() {
        getInstrumentation().runOnMainSync(() -> {
            FrameLayout body = body(Color.WHITE);
            FrostedSurfaceDrawable surface = (FrostedSurfaceDrawable) body.getBackground();
            int hides = mLastBackdrop.hides;
            surface.setAlpha(0);
            assertTrue(mLastBackdrop.hides > hides);
            surface.setAlpha(255);
            assertFalse(mLastBackdrop.closed);
        });
    }

    public void testDetachAndReattachRecreateOnlyTheBackdrop() {
        getInstrumentation().runOnMainSync(() -> {
            FrameLayout body = body(Color.WHITE);
            Object surface = body.getBackground();
            FakeBackdrop previous = mLastBackdrop;
            mFixture.removeView(mHost);
            assertTrue(previous.closed);
            mFixture.addView(mHost);
            assertSame(surface, body.getBackground());
            assertNotSame(previous, mLastBackdrop);
            assertFalse(mLastBackdrop.closed);
        });
    }

    private static final class FakeBackdrop implements FrostedSurfaceDrawable.Backdrop {
        boolean closed;
        int hides;
        @Override public boolean isEnabled() { return !closed; }
        @Override public void draw(Canvas canvas, Rect bounds, float radius, float[] radii,
                int alpha) { }
        @Override public void hide() { hides++; }
        @Override public void close() { closed = true; }
    }
}
