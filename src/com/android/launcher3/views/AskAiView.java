/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.views;

import static com.android.launcher3.LauncherPrefs.SHOW_ASK_AI;
import static com.android.launcher3.LauncherState.NORMAL;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.DeviceProfile.OnDeviceProfileChangeListener;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherPrefChangeListener;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.LauncherState;
import com.android.launcher3.R;
import com.android.launcher3.graphics.FrostedSurfaceDrawable;
import com.android.launcher3.statemanager.StateManager.StateListener;

/** The hotseat's native Ask AI button, sharing the desktop's transforms and visibility. */
public final class AskAiView extends FrameLayout implements LauncherPrefChangeListener,
        StateListener<LauncherState>, OnDeviceProfileChangeListener {
    private static final String ASSISTANT_PACKAGE = "org.arkui.assistant";

    private final ActivityContext mActivity;
    private final @Nullable Launcher mLauncher;
    private final LauncherPrefs mPrefs;
    private View mSurfaceView;
    private ImageView mIcon;
    private TextView mLabel;
    private FrostedSurfaceDrawable mSurface;
    private Runnable mPresentationChangedListener;
    private final BroadcastReceiver mPackageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getData() != null
                    && ASSISTANT_PACKAGE.equals(intent.getData().getSchemeSpecificPart())) {
                refreshPresentation();
            }
        }
    };

    public AskAiView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mActivity = ActivityContext.lookupContext(context);
        mLauncher = mActivity instanceof Launcher ? (Launcher) mActivity : null;
        mPrefs = LauncherPrefs.get(context);
        setOnClickListener(view -> openAssistant());
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mSurfaceView = findViewById(R.id.arkui_ask_ai_surface);
        mIcon = findViewById(R.id.arkui_ask_ai_icon);
        mLabel = findViewById(R.id.arkui_ask_ai_label);
        mSurface = new FrostedSurfaceDrawable(mSurfaceView,
                getContext().getColor(R.color.arkui_ask_ai_surface), 0);
        mSurfaceView.setBackground(mSurface);
        mSurfaceView.setClipToOutline(true);
        mSurfaceView.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) ->
                mSurface.setCornerRadius((bottom - top) / 2f));
        updateTheme();
        refreshPresentation();
    }

    private void updateTheme() {
        int foreground = getContext().getColor(R.color.arkui_ask_ai_foreground);
        mSurface.setColor(getContext().getColor(R.color.arkui_ask_ai_surface));
        mIcon.setImageTintList(ColorStateList.valueOf(foreground));
        mLabel.setTextColor(foreground);
        mLabel.setText(R.string.arkui_ask_ai);
        setContentDescription(getContext().getString(R.string.arkui_ask_ai_description));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(Float.MAX_VALUE);
        mSurfaceView.setForeground(new RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(foreground, 32)), null, mask));
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateTheme();
        refreshPresentation();
    }

    private static Intent assistantIntent() {
        return new Intent(Intent.ACTION_ASSIST)
                .setClassName(ASSISTANT_PACKAGE, ASSISTANT_PACKAGE + ".AssistantActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    public static boolean isAssistantAvailable(Context context) {
        return assistantIntent().resolveActivity(context.getPackageManager()) != null;
    }

    private void openAssistant() {
        if (mLauncher == null || !isEnabled() || !mLauncher.isInState(NORMAL)
                || mLauncher.getStateManager().isInTransition()
                || AbstractFloatingView.getTopOpenView(mLauncher) != null) {
            return;
        }
        try {
            mLauncher.startActivity(assistantIntent());
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(getContext(), R.string.arkui_ask_ai_unavailable,
                    Toast.LENGTH_SHORT).show();
            refreshPresentation();
        }
    }

    public void setOnPresentationChangedListener(Runnable listener) {
        mPresentationChangedListener = listener;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Wallpaper/grid previews use a rendering context, with no Launcher activity to observe.
        if (mLauncher == null) return;
        mPrefs.addListener(this, SHOW_ASK_AI);
        mLauncher.getStateManager().addStateListener(this);
        mLauncher.addOnDeviceProfileChangeListener(this);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        getContext().registerReceiver(mPackageReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        refreshPresentation();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mLauncher != null) {
            mPrefs.removeListener(this, SHOW_ASK_AI);
            mLauncher.getStateManager().removeStateListener(this);
            mLauncher.removeOnDeviceProfileChangeListener(this);
            getContext().unregisterReceiver(mPackageReceiver);
        }
        super.onDetachedFromWindow();
    }

    @Override
    public void onPrefChanged(String key) {
        if (key == null || SHOW_ASK_AI.getSharedPrefKey().equals(key)) {
            refreshPresentation();
        }
    }

    private void refreshPresentation() {
        updatePresentation(mLauncher == null ? NORMAL : mLauncher.getStateManager().getState(),
                mLauncher != null && mLauncher.getStateManager().isInTransition());
    }

    private void updatePresentation(LauncherState state, boolean transitioning) {
        // Keep drawing during an outgoing transition so the hotseat owns the fade/scale.
        boolean onHome = state == NORMAL || (transitioning && getVisibility() == VISIBLE);
        boolean show = onHome && mPrefs.get(SHOW_ASK_AI)
                && !mActivity.getDeviceProfile().isVerticalBarLayout()
                && isAssistantAvailable(getContext());
        setVisibility(show ? VISIBLE : GONE);
        setEnabled(show && mLauncher != null && !transitioning && state == NORMAL);
        if (mPresentationChangedListener != null) mPresentationChangedListener.run();
    }

    @Override
    public void onStateTransitionStart(LauncherState toState) {
        updatePresentation(toState, true);
    }

    @Override
    public void onStateTransitionComplete(LauncherState finalState) {
        updatePresentation(finalState, false);
    }

    @Override
    public void onDeviceProfileChanged(DeviceProfile dp) {
        refreshPresentation();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(Button.class.getName());
    }
}
