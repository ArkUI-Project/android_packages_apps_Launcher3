/*
 * Modified by the ArkUI Project in 2026 for the swipe-up home backdrop.
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.quickstep;

import static com.android.app.animation.Interpolators.LINEAR;
import static com.android.launcher3.LauncherAnimUtils.HOTSEAT_SCALE_PROPERTY_FACTORY;
import static com.android.launcher3.LauncherAnimUtils.SCALE_INDEX_WORKSPACE_STATE;
import static com.android.launcher3.LauncherAnimUtils.WORKSPACE_SCALE_PROPERTY_FACTORY;
import static com.android.launcher3.LauncherState.ALL_APPS;
import static com.android.launcher3.LauncherState.BACKGROUND_APP;
import static com.android.launcher3.LauncherState.NORMAL;
import static com.android.launcher3.LauncherState.OVERVIEW;
import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.MultiPropertyFactory.MULTI_PROPERTY_VALUE;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.content.Context;
import android.graphics.Rect;
import android.view.RemoteAnimationTarget;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import com.android.launcher3.CellLayout;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherAnimUtils;
import com.android.launcher3.LauncherInitListener;
import com.android.launcher3.LauncherState;
import com.android.launcher3.anim.DesktopAnimationSettings;
import com.android.launcher3.anim.PendingAnimation;
import com.android.launcher3.statehandlers.DepthController;
import com.android.launcher3.statemanager.StateManager;
import com.android.launcher3.taskbar.TaskbarInteractor;
import com.android.launcher3.uioverrides.QuickstepLauncher;
import com.android.launcher3.util.DisplayController;
import com.android.launcher3.util.NavigationMode;
import com.android.launcher3.views.ScrimColors;
import com.android.quickstep.GestureState.GestureEndTarget;
import com.android.quickstep.orientation.RecentsPagedOrientationHandler;
import com.android.quickstep.util.AnimatorControllerWithResistance;
import com.android.quickstep.util.LayoutUtils;
import com.android.quickstep.views.RecentsView;
import com.android.systemui.plugins.shared.LauncherOverlayManager;
import com.android.wm.shell.shared.desktopmode.DesktopState;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * {@link BaseActivityInterface} for the in-launcher recents.
 */
public final class LauncherActivityInterface extends
        BaseActivityInterface<LauncherState, QuickstepLauncher> {

    public static final LauncherActivityInterface INSTANCE = new LauncherActivityInterface();

    private LauncherActivityInterface() {
        super(true, OVERVIEW, BACKGROUND_APP);
    }

    @Override
    public int getSwipeUpDestinationAndLength(DeviceProfile dp, Context context, Rect outRect,
            RecentsPagedOrientationHandler orientationHandler) {
        calculateTaskSize(context, dp, outRect, orientationHandler);
        if (!dp.getDeviceProperties().isTablet()
                && dp.getDeviceProperties().getHeightPx() > dp.getDeviceProperties().getWidthPx()
                && orientationHandler.isLayoutNaturalToLauncher()
                && DisplayController.getNavigationMode(context) == NavigationMode.NO_BUTTON) {
            float overviewScale = Math.min(
                    (float) outRect.width() / dp.getDeviceProperties().getWidthPx(),
                    (float) outRect.height() / dp.getDeviceProperties().getHeightPx());
            // The initial shrink and the overswipe continue at the same display-relative rate.
            return Math.max(1, Math.round(dp.getDeviceProperties().getHeightPx()
                    * (1f - overviewScale)
                    / AnimatorControllerWithResistance.PHONE_WINDOW_SHRINK_RATE));
        }
        if (dp.isVerticalBarLayout()
                && DisplayController.getNavigationMode(context) != NavigationMode.NO_BUTTON) {
            return dp.isSeascape() ? outRect.left : (dp.getDeviceProperties().getWidthPx() - outRect.right);
        } else {
            return LayoutUtils.getShelfTrackingDistance(context, dp, orientationHandler, this);
        }
    }

    @Override
    public void onSwipeUpToHomeComplete() {
        QuickstepLauncher launcher = getCreatedContainer();
        if (launcher == null) {
            return;
        }
        // When going to home, the state animator we use has SKIP_OVERVIEW because we assume that
        // setRecentsAttachedToAppWindow() will handle animating Overview instead. Thus, at the end
        // of the animation, we should ensure recents is at the correct position for NORMAL state.
        // For example, when doing a long swipe to home, RecentsView may be scaled down. This is
        // relatively expensive, so do it on the next frame instead of critical path.
        MAIN_EXECUTOR.getHandler().post(launcher.getStateManager()::reapplyState);

        launcher.getRootView().setForceHideBackArrow(false);
        notifyRecentsOfOrientation();
    }

    @Override
    public void onAssistantVisibilityChanged(float visibility) {
        QuickstepLauncher launcher = getCreatedContainer();
        if (launcher == null) {
            return;
        }
        launcher.onAssistantVisibilityChanged(visibility);
    }

    @Override
    public AnimationFactory prepareRecentsUI(
            boolean activityVisible, Consumer<AnimatorControllerWithResistance> callback) {
        notifyRecentsOfOrientation();
        DefaultAnimationFactory factory = new DefaultAnimationFactory(callback) {
            @Override
            protected void createBackgroundToOverviewAnim(QuickstepLauncher activity,
                    PendingAnimation pa) {
                super.createBackgroundToOverviewAnim(activity, pa);

                // Animate the blur and wallpaper zoom
                float fromDepthRatio = BACKGROUND_APP.getDepth(activity);
                float toDepthRatio = OVERVIEW.getDepth(activity);
                boolean portraitPhone = !activity.getDeviceProfile().getDeviceProperties().isTablet()
                        && activity.getDeviceProfile().getDeviceProperties().getHeightPx()
                                > activity.getDeviceProfile().getDeviceProperties().getWidthPx();
                if (portraitPhone) fromDepthRatio = 0f;
                pa.addFloat(getDepthController().stateDepth,
                        new LauncherAnimUtils.ClampedProperty<>(
                                MULTI_PROPERTY_VALUE, fromDepthRatio, toDepthRatio),
                        fromDepthRatio, toDepthRatio,
                        portraitPhone ? progress -> Math.min(1f, progress / .6f) : LINEAR);
                if (portraitPhone) {
                    final var workspace = activity.getWorkspace();
                    final var hotseat = activity.getHotseat();
                    final var pageAlpha = NORMAL.getWorkspacePageAlphaProvider(activity);
                    final float scale = DesktopAnimationSettings.read(activity).workspaceScale;
                    workspace.setPivotToScaleWithSelf(hotseat);
                    // BACKGROUND_APP normally hides every home icon. A vertical swipe should
                    // reveal the blurred home immediately; pausing reveals sibling task cards
                    // independently, through the recents attach animation.
                    pa.addOnFrameCallback(() -> {
                        if (!activity.isInState(BACKGROUND_APP)) return;
                        WORKSPACE_SCALE_PROPERTY_FACTORY.get(SCALE_INDEX_WORKSPACE_STATE)
                                .setValue(workspace, scale);
                        HOTSEAT_SCALE_PROPERTY_FACTORY.get(SCALE_INDEX_WORKSPACE_STATE)
                                .setValue(hotseat, scale);
                        workspace.setTranslationX(0f);
                        workspace.setTranslationY(0f);
                        hotseat.setTranslationY(0f);
                        workspace.setAlpha(1f);
                        hotseat.setAlpha(1f);
                        hotseat.setVisibility(View.VISIBLE);
                        for (int i = 0; i < workspace.getChildCount(); i++) {
                            ((CellLayout) workspace.getPageAt(i)).getShortcutsAndWidgets()
                                    .setAlpha(pageAlpha.getPageAlpha(i));
                        }
                    });
                }
            }
        };

        QuickstepLauncher launcher = factory.initBackgroundStateUI();
        // Since all apps is not visible, we can safely reset the scroll position.
        // This ensures then the next swipe up to all-apps starts from scroll 0.
        launcher.getAppsView().reset(false /* animate */, true /* clearScrim */);
        return factory;
    }

    @Override
    public LauncherInitListener createActivityInitListener(Predicate<Boolean> onInitListener) {
        return new LauncherInitListener((activity, alreadyOnHome) ->
                onInitListener.test(alreadyOnHome));
    }

    @Override
    public void setOnDeferredActivityLaunchCallback(Runnable r) {
        QuickstepLauncher launcher = getCreatedContainer();
        if (launcher == null) {
            return;
        }
        launcher.setOnDeferredActivityLaunchCallback(r);
    }

    @Nullable
    @Override
    public QuickstepLauncher getCreatedContainer() {
        return QuickstepLauncher.ACTIVITY_TRACKER.getCreatedContext();
    }

    @Nullable
    @Override
    public DepthController getDepthController() {
        QuickstepLauncher launcher = getCreatedContainer();
        if (launcher == null) {
            return null;
        }
        return launcher.getDepthController();
    }

    @Nullable
    @Override
    public TaskbarInteractor getTaskbarInteractor() {
        QuickstepLauncher launcher = getCreatedContainer();
        if (launcher == null) {
            return null;
        }
        return launcher.getTaskbarInteractor();
    }

    @Nullable
    @Override
    public RecentsView getVisibleRecentsView() {
        QuickstepLauncher launcher = getVisibleLauncher();
        RecentsView recentsView =
                launcher != null && launcher.getStateManager().getState().isRecentsViewVisible
                        ? launcher.getOverviewPanel() : null;
        if (recentsView == null || (!launcher.hasBeenResumed()
                && recentsView.getRunningTaskViewId() == -1)) {
            // If live tile has ended, return null.
            return null;
        }
        return recentsView;
    }

    @Nullable
    @UiThread
    private QuickstepLauncher getVisibleLauncher() {
        QuickstepLauncher launcher = getCreatedContainer();
        if (launcher == null) {
            return null;
        }
        if (launcher.isStarted() && (isInLiveTileMode() || launcher.hasBeenResumed())) {
            return launcher;
        }
        if (isInMinusOne()) {
            return launcher;
        }

        return null;
    }

    @Override
    public boolean switchToRecentsIfVisible(Animator.AnimatorListener animatorListener) {
        QuickstepLauncher launcher = getVisibleLauncher();
        if (launcher == null) {
            return false;
        }
        if (DesktopState.getInstance(launcher.asContext()).getShouldShowHomeBehindDesktop()
                && !launcher.hasWindowFocus()) {
            // Home is always shown behind desktop, but it is currently not the top task, so treat
            // it as if it is not visible.
            return false;
        }
        if (isInLiveTileMode()) {
            RecentsView recentsView = getVisibleRecentsView();
            if (recentsView == null) {
                return false;
            }
        }

        closeOverlay();
        launcher.getStateManager().goToState(OVERVIEW,
                launcher.getStateManager().shouldAnimateStateChange(),
                animatorListener);
        return true;
    }


    @Override
    public void onExitOverview(Runnable exitRunnable) {
        final StateManager<LauncherState, Launcher> stateManager =
                getCreatedContainer().getStateManager();
        stateManager.addStateListener(
                new StateManager.StateListener<LauncherState>() {
                    @Override
                    public void onStateTransitionComplete(LauncherState toState) {
                        // Are we going from Recents to Workspace?
                        if (toState == LauncherState.NORMAL || toState == LauncherState.ALL_APPS) {
                            exitRunnable.run();
                            notifyRecentsOfOrientation();
                            stateManager.removeStateListener(this);
                        }
                    }
                });
    }

    private void notifyRecentsOfOrientation() {
        // reset layout on swipe to home
        ((RecentsView) getCreatedContainer().getOverviewPanel()).reapplyActiveRotation();
    }

    @Override
    public Rect getOverviewWindowBounds(Rect homeBounds, RemoteAnimationTarget target) {
        return homeBounds;
    }

    @Override
    public boolean isInLiveTileMode() {
        QuickstepLauncher launcher = getCreatedContainer();

        return launcher != null
                && launcher.getStateManager().getState() == OVERVIEW
                && launcher.isStarted()
                && TopTaskTracker.INSTANCE.get(launcher).getCachedTopTask(false,
                launcher.getDisplayId()).isHomeTask();
    }

    private boolean isInMinusOne() {
        QuickstepLauncher launcher = getCreatedContainer();

        return launcher != null
                && launcher.getStateManager().getState() == NORMAL
                && !launcher.isStarted()
                && TopTaskTracker.INSTANCE.get(launcher).getCachedTopTask(false,
                launcher.getDisplayId()).isHomeTask();
    }

    @Override
    public void onLaunchTaskFailed() {
        QuickstepLauncher launcher = getCreatedContainer();
        if (launcher == null) {
            return;
        }
        launcher.getStateManager().goToState(OVERVIEW);
    }

    @Override
    public void closeOverlay() {
        super.closeOverlay();
        QuickstepLauncher launcher = getCreatedContainer();
        if (launcher == null) {
            return;
        }
        LauncherOverlayManager om = launcher.getOverlayManager();
        if (!SystemUiProxy.INSTANCE.get(launcher).getHomeVisibilityState().isHomeVisible()) {
            om.hideOverlay(false /* animate */);
        } else {
            om.hideOverlay(150);
        }
    }

    @Override
    public @Nullable Animator getParallelAnimationToGestureEndTarget(GestureEndTarget endTarget,
            long duration, RecentsAnimationCallbacks callbacks) {
        TaskbarInteractor interactor = getTaskbarInteractor();
        Animator superAnimator = super.getParallelAnimationToGestureEndTarget(
                endTarget, duration, callbacks);
        if (interactor == null || callbacks == null) {
            return superAnimator;
        }
        Animator taskbarAnimator = interactor.getParallelAnimationToGestureEndTarget(endTarget,
                duration, callbacks);
        if (superAnimator == null) {
            return taskbarAnimator;
        } else {
            AnimatorSet animatorSet = new AnimatorSet();
            animatorSet.playTogether(superAnimator, taskbarAnimator);
            return animatorSet;
        }
    }

    @Override
    protected ScrimColors getOverviewScrimColorForState(QuickstepLauncher activity,
            LauncherState state) {
        return state.getWorkspaceScrimColor(activity);
    }

    @Override
    public LauncherState stateFromGestureEndTarget(@NonNull GestureEndTarget endTarget) {
        return switch (endTarget) {
            case RECENTS -> OVERVIEW;
            case NEW_TASK, LAST_TASK -> BACKGROUND_APP;
            case ALL_APPS -> ALL_APPS;
            default -> NORMAL;
        };
    }

    @Override
    public boolean isLauncherOverlayShowing() {
        Launcher launcher = Launcher.ACTIVITY_TRACKER.getCreatedContext();

        return launcher != null && launcher.getWorkspace().isOverlayShown();
    }
}
