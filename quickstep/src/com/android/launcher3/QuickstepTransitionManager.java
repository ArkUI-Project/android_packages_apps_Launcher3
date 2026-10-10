/*
 * Modified by the ArkUI Project in 2026 for optional landscape app transitions.
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.launcher3;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.role.RoleManager.ROLE_HOME;
import static android.provider.Settings.Secure.LAUNCHER_TASKBAR_EDUCATION_SHOWING;
import static android.view.RemoteAnimationTarget.MODE_CLOSING;
import static android.view.RemoteAnimationTarget.MODE_OPENING;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.window.StartingWindowInfo.STARTING_WINDOW_TYPE_NONE;
import static android.window.StartingWindowInfo.STARTING_WINDOW_TYPE_SPLASH_SCREEN;
import static android.window.TransitionFilter.CONTAINER_ORDER_TOP;

import static com.android.app.animation.Interpolators.ACCELERATE_1_5;
import static com.android.app.animation.Interpolators.AGGRESSIVE_EASE;
import static com.android.app.animation.Interpolators.DECELERATE_1_5;
import static com.android.app.animation.Interpolators.DECELERATE_1_7;
import static com.android.app.animation.Interpolators.EXAGGERATED_EASE;
import static com.android.app.animation.Interpolators.LINEAR;
import static com.android.internal.util.LatencyTracker.ACTION_DESKTOP_MODE_EXIT_MODE_ON_LAST_WINDOW_CLOSE;
import static com.android.launcher3.BaseActivity.EVENT_DESTROYED;
import static com.android.launcher3.BaseActivity.INVISIBLE_ALL;
import static com.android.launcher3.BaseActivity.INVISIBLE_BY_APP_TRANSITIONS;
import static com.android.launcher3.BaseActivity.INVISIBLE_BY_PENDING_FLAGS;
import static com.android.launcher3.BaseActivity.PENDING_INVISIBLE_BY_WALLPAPER_ANIMATION;
import static com.android.launcher3.Flags.refactorTaskbarUiState;
import static com.android.launcher3.Flags.syncAppLaunchWithTaskbarStash;
import static com.android.launcher3.LauncherAnimUtils.SCALE_PROPERTY;
import static com.android.launcher3.LauncherState.ALL_APPS;
import static com.android.launcher3.LauncherState.BACKGROUND_APP;
import static com.android.launcher3.LauncherState.NORMAL;
import static com.android.launcher3.LauncherState.OVERVIEW;
import static com.android.launcher3.Utilities.mapBoundToRange;
import static com.android.launcher3.config.FeatureFlags.SEPARATE_RECENTS_ACTIVITY;
import static com.android.launcher3.testing.shared.TestProtocol.WALLPAPER_OPEN_ANIMATION_FINISHED_MESSAGE;
import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.MultiPropertyFactory.MULTI_PROPERTY_VALUE;
import static com.android.launcher3.util.window.RefreshRateTracker.getSingleFrameMs;
import static com.android.launcher3.views.FloatingIconView.SHAPE_PROGRESS_DURATION;
import static com.android.quickstep.TaskViewUtils.findTaskViewToLaunch;
import static com.android.quickstep.util.AnimUtils.clampToDuration;
import static com.android.quickstep.util.AnimUtils.completeRunnableListCallback;
import static com.android.quickstep.util.FloatingIconViewHelper.getFloatingIconView;
import static com.android.systemui.shared.system.QuickStepContract.getWindowCornerRadius;
import static com.android.systemui.shared.system.QuickStepContract.supportsRoundedCornersOnWindows;
import static com.android.wm.shell.Flags.enableDynamicInsetsForAppLaunch;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.app.WindowConfiguration;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.RemoteException;
import android.view.IRemoteAnimationRunner;
import android.window.IRemoteTransitionFinishedCallback;
import android.window.TransitionInfo;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationDefinition;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.window.DesktopModeFlags;
import android.window.IRemoteTransition;
import android.window.RemoteTransitionStub;
import android.window.RemoteTransition;
import android.window.TransitionFilter;
import android.window.WindowAnimationState;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.android.app.animation.Animations;
import com.android.app.animation.Interpolators;
import com.android.internal.jank.Cuj;
import com.android.internal.util.LatencyTracker;
import com.android.launcher3.DeviceProfile.OnDeviceProfileChangeListener;
import com.android.launcher3.LauncherAnimationRunner.RemoteAnimationFactory;
import com.android.launcher3.anim.AnimationSuccessListener;
import com.android.launcher3.anim.DesktopAnimationSettings;
import com.android.launcher3.anim.IosWindowShape;
import com.android.launcher3.anim.WindowMotion;
import com.android.launcher3.anim.AnimatorListeners;
import com.android.launcher3.compat.AccessibilityManagerCompat;
import com.android.launcher3.dragndrop.DragLayer;
import com.android.launcher3.icons.FastBitmapDrawable;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.shortcuts.DeepShortcutView;
import com.android.launcher3.taskbar.TaskbarInteractor;
import com.android.launcher3.taskbar.customization.TaskbarFeatureEvaluator;
import com.android.launcher3.testing.shared.ResourceUtils;
import com.android.launcher3.touch.PagedOrientationHandler;
import com.android.launcher3.uioverrides.QuickstepLauncher;
import com.android.launcher3.util.ActivityOptionsWrapper;
import com.android.launcher3.util.RunnableList;
import com.android.launcher3.util.StableViewInfo;
import com.android.launcher3.views.FloatingIconView;
import com.android.launcher3.widget.LauncherAppWidgetHostView;
import com.android.quickstep.LauncherBackAnimationController;
import com.android.quickstep.RemoteAnimationTargets;
import com.android.quickstep.SystemUiProxy;
import com.android.quickstep.TaskViewUtils;
import com.android.quickstep.util.AlreadyStartedBackAnimState;
import com.android.quickstep.util.AnimatorBackState;
import com.android.quickstep.util.BackAnimState;
import com.android.quickstep.util.CrossDisplayMoveTransition;
import com.android.quickstep.util.LandscapeAppAnimation;
import com.android.quickstep.util.AppWindowAnimationState;
import com.android.quickstep.util.WindowAnimationSnapshot;
import com.android.quickstep.util.IosWindowDeformation;
import com.android.quickstep.util.MultiValueUpdateListener;
import com.android.quickstep.util.RectFSpringAnim;
import com.android.quickstep.util.RectFSpringAnim.DefaultSpringConfig;
import com.android.quickstep.util.RectFSpringAnim.TaskbarHotseatSpringConfig;
import com.android.quickstep.util.ScalingWorkspaceRevealAnim;
import com.android.quickstep.util.SurfaceTransaction;
import com.android.quickstep.util.SurfaceTransaction.SurfaceProperties;
import com.android.quickstep.util.SystemBarFollowAnimation;
import com.android.quickstep.util.SurfaceTransactionApplier;
import com.android.quickstep.util.TaskCornerRadius;
import com.android.quickstep.util.TaskRestartedDuringLaunchListener;
import com.android.quickstep.util.WorkspaceRevealAnim;
import com.android.quickstep.views.FloatingWidgetView;
import com.android.quickstep.views.RecentsView;
import com.android.systemui.animation.ActivityTransitionAnimator;
import com.android.systemui.animation.DelegateTransitionAnimatorController;
import com.android.systemui.animation.LaunchableView;
import com.android.systemui.animation.RemoteAnimationDelegate;
import com.android.systemui.animation.RemoteAnimationRunnerCompat;
import com.android.systemui.animation.RemoteTransitionDelegate;
import com.android.systemui.shared.system.InteractionJankMonitorWrapper;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus;
import com.android.wm.shell.startingsurface.IStartingWindowListener;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;

/**
 * Manages the opening and closing app transitions from Launcher
 */
public class QuickstepTransitionManager implements OnDeviceProfileChangeListener {
    private static final String TAG = "QuickstepTransitionManager";

    private static final boolean ENABLE_SHELL_STARTING_SURFACE =
            SystemProperties.getBoolean("persist.debug.shell_starting_surface", true);

    /** Duration of status bar animations. */
    public static final int STATUS_BAR_TRANSITION_DURATION = 120;

    /**
     * Since our animations decelerate heavily when finishing, we want to start status bar
     * animations x ms before the ending.
     */
    public static final int STATUS_BAR_TRANSITION_PRE_DELAY = 96;

    public static final long APP_LAUNCH_DURATION = 500;

    public static final int ANIMATION_NAV_FADE_IN_DURATION = 266;
    public static final int ANIMATION_NAV_FADE_OUT_DURATION = 133;
    public static final long ANIMATION_DELAY_NAV_FADE_IN =
            APP_LAUNCH_DURATION - ANIMATION_NAV_FADE_IN_DURATION;
    public static final Interpolator NAV_FADE_IN_INTERPOLATOR =
            new PathInterpolator(0f, 0f, 0f, 1f);
    public static final Interpolator NAV_FADE_OUT_INTERPOLATOR =
            new PathInterpolator(0.2f, 0f, 1f, 1f);

    public static final int RECENTS_LAUNCH_DURATION = 336;
    private static final int LAUNCHER_RESUME_START_DELAY = 100;
    private static final int CLOSING_TRANSITION_DURATION_MS = 250;
    public static final int SPLIT_LAUNCH_DURATION = 370;
    public static final int SPLIT_DIVIDER_ANIM_DURATION = 100;

    public static final int CONTENT_ALPHA_DURATION = 217;
    public static final int TRANSIENT_TASKBAR_TRANSITION_DURATION = 417;
    public static final int PINNED_TASKBAR_TRANSITION_DURATION = 600;
    public static final int TASKBAR_TO_APP_DURATION = 600;
    // TODO(b/236145847): Tune TASKBAR_TO_HOME_DURATION to 383 after conflict with unlock animation
    // is solved.
    private static final int TASKBAR_TO_HOME_DURATION_FAST = 300;
    private static final int TASKBAR_TO_HOME_DURATION_SLOW = 1000;
    protected static final int CONTENT_SCALE_DURATION = 350;

    private static final int MAX_NUM_TASKS = 5;

    // Cross-fade duration between App Widget and App when launching from widget.
    private static final int WIDGET_CROSSFADE_DURATION_MILLIS = 125;

    protected final QuickstepLauncher mLauncher;
    protected final DragLayer mDragLayer;

    protected final Handler mHandler;

    private final float mClosingWindowTransY;
    private final float mClosingFreeformWindowTransY;
    private final float mMaxShadowRadius;

    private final StartingWindowListener mStartingWindowListener =
            new StartingWindowListener(this);

    // TODO(b/397690719): Investigate the memory leak from TaskStackChangeListeners#mImpl
    // This is a temporary fix of memory leak b/397690719. We track registered
    // {@link TaskRestartedDuringLaunchListener}, and remove them on activity destroy.
    private final List<TaskRestartedDuringLaunchListener> mRegisteredTaskStackChangeListener =
            new ArrayList<>();

    private DeviceProfile mDeviceProfile;

    // Strong refs to runners which are cleared when the launcher activity is destroyed
    private RemoteAnimationFactory mWallpaperOpenRunner;
    private final ArrayList<RemoteAnimationFactory> mAppLaunchRunners = new ArrayList<>();

    private RemoteAnimationFactory mWallpaperOpenTransitionRunner;
    private RemoteTransition mLauncherOpenTransition;
    private RemoteTransition mMoveDisplayTransition;

    private final RemoteAnimationCoordinateTransfer mCoordinateTransfer;
    private final LatencyTracker mLatencyTracker;

    private LauncherBackAnimationController mBackAnimationController;
    private final AnimatorListenerAdapter mForceInvisibleListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationStart(Animator animation) {
            mLauncher.addForceInvisibleFlag(INVISIBLE_BY_APP_TRANSITIONS);
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            mLauncher.clearForceInvisibleFlag(INVISIBLE_BY_APP_TRANSITIONS);
        }
    };

    // Pairs of window starting type and starting window background color for starting tasks
    // Will never be larger than MAX_NUM_TASKS
    private LinkedHashMap<Integer, Pair<Integer, Integer>> mTaskStartParams;

    private final Interpolator mOpeningXInterpolator;
    private final Interpolator mOpeningInterpolator;

    private final SystemUiProxy mSystemUiProxy;

    public QuickstepTransitionManager(QuickstepLauncher launcher) {
        mLauncher = launcher;
        mDragLayer = mLauncher.getDragLayer();
        mHandler = new Handler(Looper.getMainLooper());
        mDeviceProfile = mLauncher.getDeviceProfile();
        mBackAnimationController = new LauncherBackAnimationController(mLauncher, this);

        Resources res = mLauncher.getResources();
        mClosingWindowTransY = res.getDimensionPixelSize(R.dimen.closing_window_trans_y);
        mClosingFreeformWindowTransY =
                res.getDimensionPixelSize(R.dimen.closing_freeform_window_trans_y);
        mMaxShadowRadius = res.getDimensionPixelSize(R.dimen.max_shadow_radius);

        mLauncher.addOnDeviceProfileChangeListener(this);
        mSystemUiProxy = SystemUiProxy.INSTANCE.get(mLauncher);

        if (ENABLE_SHELL_STARTING_SURFACE) {
            mTaskStartParams = new LinkedHashMap<>(MAX_NUM_TASKS) {
                @Override
                protected boolean removeEldestEntry(Entry<Integer, Pair<Integer, Integer>> entry) {
                    return size() > MAX_NUM_TASKS;
                }
            };

            mSystemUiProxy.setStartingWindowListener(mStartingWindowListener);
        }

        mOpeningXInterpolator = AnimationUtils.loadInterpolator(
                launcher, R.interpolator.app_open_x);
        mOpeningInterpolator = AnimationUtils.loadInterpolator(
                launcher, R.interpolator.emphasized_interpolator);
        mCoordinateTransfer = new RemoteAnimationCoordinateTransfer(mLauncher);
        mLatencyTracker = LatencyTracker.getInstance(launcher);
    }

    @Override
    public void onDeviceProfileChanged(DeviceProfile dp) {
        mDeviceProfile = dp;
    }

    private void startCrossDisplayMoveAnimation(TransitionInfo info, SurfaceControl.Transaction t,
            IRemoteTransitionFinishedCallback finishCallback) {
        mHandler.post(() -> {
            new CrossDisplayMoveTransition(mLauncher, APP_LAUNCH_DURATION,
                    CLOSING_TRANSITION_DURATION_MS)
                    .startCrossDisplayMoveAnimation(info, t, finishCallback);
        });
    }

    /**
     * A {@link RemoteTransitionStub} that handles cross display move animations.
     */
    private class MoveDisplayChangeRunner extends RemoteTransitionStub {
        @Override
        public void startAnimation(IBinder token, TransitionInfo info, SurfaceControl.Transaction t,
                IRemoteTransitionFinishedCallback finishCallback) throws RemoteException {
            startCrossDisplayMoveAnimation(info, t, finishCallback);
        }
    }

    /**
     * @return ActivityOptions with remote animations that controls how the window of the opening
     * targets are displayed.
     */
    public ActivityOptionsWrapper getActivityLaunchOptions(View v, ItemInfo itemInfo) {
        boolean fromRecents = isLaunchingFromRecents(v, null /* targets */);
        RunnableList onEndCallback = new RunnableList();

        // Handle the case where an already visible task is launched which results in no transition
        TaskRestartedDuringLaunchListener restartedListener =
                new TaskRestartedDuringLaunchListener();
        restartedListener.register(onEndCallback::executeAllAndDestroy);
        mRegisteredTaskStackChangeListener.add(restartedListener);
        onEndCallback.add(new Runnable() {
            @Override
            public void run() {
                restartedListener.unregister();
                mRegisteredTaskStackChangeListener.remove(restartedListener);
            }
        });

        RemoteAnimationRunnerCompat appLaunchRunner = createAppLaunchRunner(
                v, onEndCallback);
        IRemoteTransition appLaunchRemoteTransition = createAppLaunchRemoteTransition(
                appLaunchRunner);

        // Note that this duration is a guess as we do not know if the animation will be a
        // recents launch or not for sure until we know the opening app targets.
        final DesktopAnimationSettings motion = DesktopAnimationSettings.read(mLauncher);
        long duration = fromRecents ? motion.recentsDuration
                : v instanceof LauncherAppWidgetHostView ? APP_LAUNCH_DURATION : motion.openDuration;

        long statusBarTransitionDelay = Math.max(0, duration - STATUS_BAR_TRANSITION_DURATION
                - STATUS_BAR_TRANSITION_PRE_DELAY);
      ActivityOptions options = ActivityOptions.makeRemoteAnimation(
              new RemoteAnimationAdapter(appLaunchRunner, duration, statusBarTransitionDelay),
              new RemoteTransition(appLaunchRemoteTransition, mLauncher.getIApplicationThread(),
                    "QuickstepLaunch"));
        IRemoteCallback endCallback = completeRunnableListCallback(onEndCallback, mLauncher);
        options.setOnAnimationAbortListener(endCallback);
        options.setOnAnimationFinishedListener(endCallback);
        options.setLaunchCookie(StableViewInfo.toLaunchCookie(itemInfo));

        // Prepare taskbar for animation synchronization. This needs to happen here before any
        // app transition is created.
        TaskbarInteractor taskbarInteractor = mLauncher.getTaskbarInteractor();
        if (syncAppLaunchWithTaskbarStash()
                && mLauncher.getStateManager().getState() == NORMAL
                && taskbarInteractor != null) {
            taskbarInteractor.setIgnoreInAppFlagForSync(true);
            mLauncher.addEventCallback(EVENT_DESTROYED, onEndCallback::executeAllAndDestroy);
            onEndCallback.add(() -> {
                taskbarInteractor.setIgnoreInAppFlagForSync(false);
            });
        }

        return new ActivityOptionsWrapper(options, onEndCallback);
    }

    /**
     * Selects the appropriate type of launch runner for the given view, builds it, and returns it.
     * Each requested launch retains its factory until that request finishes, including when a
     * second icon is tapped before the first request receives its animation targets.
     */
    private RemoteAnimationRunnerCompat createAppLaunchRunner(View v, RunnableList onEndCallback) {
        ItemInfo tag = (ItemInfo) v.getTag();
        ContainerAnimationRunner containerRunner = null;
        if (tag != null && tag.shouldUseBackgroundAnimation()) {
            containerRunner = ContainerAnimationRunner.fromView(
                    v, true /* forLaunch */, mLauncher, mStartingWindowListener, onEndCallback,
                    null /* windowState */);
        }

        RemoteAnimationFactory runner = containerRunner != null
                ? containerRunner : new AppLaunchAnimationRunner(v, onEndCallback);
        mAppLaunchRunners.add(runner);
        onEndCallback.add(() -> mAppLaunchRunners.remove(runner));
        return new LauncherAnimationRunner(
                mHandler, runner, true /* startAtFrontOfQueue */);
    }

    /**
     * Creates a remote transition for app launches.
     *
     * The app launch runner picks an animation based on the view type (e.g. icon, widget).
     *
     * However, for cross-display moves, we need to pick a different animation based on the
     * (dynamic) TransitionInfo content. This is controlled by the
     * enableCrossDisplaysAppLaunchTransition flag.
     *
     * If the flag is enabled, the returned transition will:
     * 1. Check if the transition is a cross-display move, and if so, use the cross-display
     *    animation.
     * 2. Otherwise, use the default app launch animation from the runner.
     *
     * If the flag is disabled, this will always return the default app launch animation.
     */
    private IRemoteTransition createAppLaunchRemoteTransition(
            RemoteAnimationRunnerCompat appLaunchRunner) {
        IRemoteTransition defaultAppLaunchTransition = appLaunchRunner.toRemoteTransition();
        if (!com.android.window.flags.Flags.enableCrossDisplaysAppLaunchTransition()) {
            return defaultAppLaunchTransition;
        }

        IRemoteTransition crossDisplayMoveTransition = new MoveDisplayChangeRunner();
        return new RemoteTransitionDelegate(
                (info) -> {
                    if (CrossDisplayMoveTransition.isCrossDisplayMove(info)) {
                        Log.d(TAG, "Handling launch as a cross display move transition");
                        return crossDisplayMoveTransition;
                    } else {
                        return defaultAppLaunchTransition;
                    }
                });
    }

    /**
     * Whether the launch is a recents app transition and we should do a launch animation
     * from the recents view. Note that if the remote animation targets are not provided, this
     * may not always be correct as we may resolve the opening app to a task when the animation
     * starts.
     *
     * @param v       the view to launch from
     * @param targets apps that are opening/closing
     * @return true if the app is launching from recents, false if it most likely is not
     */
    protected boolean isLaunchingFromRecents(@NonNull View v,
            @Nullable RemoteAnimationTarget[] targets) {
        return mLauncher.getStateManager().getState().isRecentsViewVisible
                && findTaskViewToLaunch(mLauncher.getOverviewPanel(), v, targets) != null;
    }

    /**
     * Composes the animations for a launch from the recents list.
     *
     * @param anim            the animator set to add to
     * @param v               the launching view
     * @param appTargets      the apps that are opening/closing
     * @param launcherClosing true if the launcher app is closing
     */
    protected void composeRecentsLaunchAnimator(@NonNull AnimatorSet anim, @NonNull View v,
            @NonNull RemoteAnimationTarget[] appTargets,
            @NonNull RemoteAnimationTarget[] wallpaperTargets,
            @NonNull RemoteAnimationTarget[] nonAppTargets, boolean launcherClosing) {
        TaskViewUtils.composeRecentsLaunchAnimator(anim, v, appTargets, wallpaperTargets,
                nonAppTargets, launcherClosing, mLauncher.getStateManager(),
                mLauncher.getOverviewPanel(), mLauncher.getDepthController(),
                /* transitionInfo= */ null, /* appearedTaskId= */ INVALID_TASK_ID);
    }

    private boolean areAllTargetsTranslucent(@NonNull RemoteAnimationTarget[] targets) {
        boolean isAllOpeningTargetTrs = true;
        for (int i = 0; i < targets.length; i++) {
            RemoteAnimationTarget target = targets[i];
            if (target.mode == MODE_OPENING) {
                isAllOpeningTargetTrs &= target.isTranslucent;
            }
            if (!isAllOpeningTargetTrs) break;
        }
        return isAllOpeningTargetTrs;
    }

    /**
     * Compose the animations for a launch from the app icon.
     *
     * @param anim            the animation to add to
     * @param v               the launching view with the icon
     * @param appTargets      the list of opening/closing apps
     * @param launcherClosing true if launcher is closing
     */
    private void composeIconLaunchAnimator(@NonNull AnimatorSet anim, @NonNull View v,
            @NonNull RemoteAnimationTarget[] appTargets,
            @NonNull RemoteAnimationTarget[] wallpaperTargets,
            @NonNull RemoteAnimationTarget[] nonAppTargets,
            boolean launcherClosing, LauncherAnimationRunner.AnimationResult result) {
        // Set the state animation first so that any state listeners are called
        // before our internal listeners.
        mLauncher.getStateManager().setCurrentAnimation(anim);

        // Note: the targetBounds are relative to the launcher
        int startDelay = getSingleFrameMs(mLauncher);
        Animator windowAnimator = getOpeningWindowAnimators(
                v, appTargets, wallpaperTargets, nonAppTargets, launcherClosing, anim::cancel,
                result);
        windowAnimator.setStartDelay(startDelay);
        anim.play(windowAnimator);
        if (launcherClosing) {
            // Delay animation by a frame to avoid jank.
            Pair<AnimatorSet, Runnable> launcherContentAnimator =
                    getLauncherContentAnimator(true /* isAppOpening */, startDelay, false);
            anim.play(launcherContentAnimator.first);
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    launcherContentAnimator.second.run();
                }
            });
        }
    }

    private void composeWidgetLaunchAnimator(
            @NonNull AnimatorSet anim,
            @NonNull LauncherAppWidgetHostView v,
            @NonNull RemoteAnimationTarget[] appTargets,
            @NonNull RemoteAnimationTarget[] wallpaperTargets,
            @NonNull RemoteAnimationTarget[] nonAppTargets,
            boolean launcherClosing) {
        mLauncher.getStateManager().setCurrentAnimation(anim);
        anim.play(getOpeningWindowAnimatorsForWidget(
                v, appTargets, wallpaperTargets, nonAppTargets, launcherClosing));
    }

    /**
     * Return the window bounds of the opening target.
     * In multiwindow mode, we need to get the final size of the opening app window target to help
     * figure out where the floating view should animate to.
     */
    private Rect getWindowTargetBounds(@NonNull RemoteAnimationTarget[] appTargets,
            int rotationChange) {
        RemoteAnimationTarget target = null;
        for (RemoteAnimationTarget t : appTargets) {
            if (t.mode != MODE_OPENING) continue;
            target = t;
            break;
        }
        final int widthPx = mDeviceProfile.getDeviceProperties().getWidthPx();
        final int heightPx = mDeviceProfile.getDeviceProperties().getHeightPx();
        if (target == null) return new Rect(0, 0, widthPx, heightPx);
        final Rect bounds = new Rect(target.screenSpaceBounds);
        if (target.localBounds != null) {
            bounds.set(target.localBounds);
        } else {
            bounds.offsetTo(target.position.x, target.position.y);
        }
        if (rotationChange != 0) {
            if ((rotationChange % 2) == 1) {
                // undoing rotation, so our "original" parent size is actually flipped
                Utilities.rotateBounds(bounds, heightPx, widthPx,
                        4 - rotationChange);
            } else {
                Utilities.rotateBounds(bounds, widthPx, heightPx,
                        4 - rotationChange);
            }
        }
        return bounds;
    }

    /** Dump debug logs to bug report. */
    public void dump(@NonNull String prefix, @NonNull PrintWriter printWriter) {}

    /**
     * Content is everything on screen except the background and the floating view (if any).
     *
     * @param isAppOpening     True when this is called when an app is opening.
     *                         False when this is called when an app is closing.
     * @param startDelay       Start delay duration.
     * @param skipAllAppsScale True if we want to avoid scaling All Apps
     */
    private Pair<AnimatorSet, Runnable> getLauncherContentAnimator(boolean isAppOpening,
            int startDelay, boolean skipAllAppsScale) {
        final DesktopAnimationSettings motion = DesktopAnimationSettings.read(mLauncher);
        AnimatorSet launcherAnimator = new AnimatorSet();
        final boolean[] cancelled = {false};
        launcherAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled[0] = true;
            }
        });
        Runnable endListener;

        float[] alphas = isAppOpening
                ? new float[]{1, 0}
                : new float[]{0, 1};

        float[] scales = isAppOpening
                ? new float[]{1, motion.workspaceScale}
                : new float[]{motion.workspaceScale, 1};

        // Pause expensive view updates as they can lead to layer thrashing and skipped frames.
        mLauncher.pauseExpensiveViewUpdates();

        if (mLauncher.isInState(ALL_APPS)) {
            // All Apps in portrait mode is full screen, so we only animate AllAppsContainerView.
            final View appsView = mLauncher.getAppsView();
            final float startAlpha = appsView.getAlpha();
            final float startScale = SCALE_PROPERTY.get(appsView);
            Animations.Companion.setOngoingAnimation(appsView, launcherAnimator);
            alphas[0] = isAppOpening || startAlpha < 1f ? startAlpha : alphas[0];
            scales[0] = isAppOpening || startScale != 1f ? startScale : scales[0];
            if (mDeviceProfile.getDeviceProperties().isTablet()) {

                // AllApps should not fade at all in tablets.
                alphas = new float[]{1, 1};
            }
            appsView.setAlpha(alphas[0]);

            ObjectAnimator alpha = ObjectAnimator.ofFloat(appsView, View.ALPHA, alphas);
            alpha.setDuration(isAppOpening ? motion.openDuration : motion.homeDuration);
            alpha.setInterpolator(isAppOpening ? motion.interpolator : motion.homeInterpolator);
            appsView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            alpha.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (appsView.getTag(com.android.app.animation.R.id.ongoing_animation)
                            == launcherAnimator) {
                        appsView.setLayerType(View.LAYER_TYPE_NONE, null);
                    }
                }
            });

            if (!skipAllAppsScale) {
                SCALE_PROPERTY.set(appsView, scales[0]);
                ObjectAnimator scale = ObjectAnimator.ofFloat(appsView, SCALE_PROPERTY, scales);
                scale.setInterpolator(isAppOpening ? motion.interpolator : motion.homeInterpolator);
                scale.setDuration(isAppOpening ? motion.openDuration : motion.homeDuration);
                launcherAnimator.play(scale);
            }

            launcherAnimator.play(alpha);

            endListener = () -> {
                if (appsView.getTag(com.android.app.animation.R.id.ongoing_animation)
                        == launcherAnimator) {
                    if (!cancelled[0]) {
                        appsView.setAlpha(1f);
                        SCALE_PROPERTY.set(appsView, 1f);
                    }
                    appsView.setLayerType(View.LAYER_TYPE_NONE, null);
                    appsView.setTag(com.android.app.animation.R.id.ongoing_animation, null);
                }
                mLauncher.resumeExpensiveViewUpdates();
            };
        } else if (mLauncher.isInState(OVERVIEW)) {
            endListener = composeViewContentAnimator(launcherAnimator, alphas, scales);
        } else {
            List<View> viewsToAnimate = new ArrayList<>();
            viewsToAnimate.add(mLauncher.getWorkspace());

            Hotseat hotseat = mLauncher.getHotseat();
            mLauncher.getWorkspace().setPivotToScaleWithSelf(hotseat);
            // Do not scale hotseat as a whole when taskbar is present, and scale QSB only if it's
            // not inline.
            if (mDeviceProfile.isTaskbarPresent) {
                if (!mDeviceProfile.isQsbInline) {
                    viewsToAnimate.add(hotseat.getQsb());
                }
            } else {
                viewsToAnimate.add(hotseat);
            }

            viewsToAnimate.forEach(view -> {
                view.setLayerType(View.LAYER_TYPE_HARDWARE, null);

                // Start the animation from the current value, instead of assuming the views are
                // in their resting state, so interrupted animations merge seamlessly.
                // TODO(b/367591368): ideally these animations would be refactored to be
                //  controlled centrally so each instances doesn't need to care about this
                //  coordination.
                boolean continuingScale = view.getTag(
                        com.android.app.animation.R.id.ongoing_animation) instanceof Animator;
                float startScale = motion.isIos && !isAppOpening && !continuingScale
                        ? motion.workspaceScale : view.getScaleX();
                float[] scale = new float[]{startScale, scales[1]};

                // Cancel any ongoing animations. This is necessary to avoid a conflict between
                // e.g. the unfinished animation triggered when closing an app back to Home and
                // this animation caused by a launch.
                Animations.Companion.cancelOngoingAnimation(view);
                // Make sure to cache the current animation, so it can be properly interrupted.
                Animations.Companion.setOngoingAnimation(view, launcherAnimator);

                ObjectAnimator scaleAnim = ObjectAnimator.ofFloat(view, SCALE_PROPERTY, scale)
                        .setDuration(isAppOpening ? motion.openDuration : motion.homeDuration);
                scaleAnim.setInterpolator(isAppOpening ? motion.workspaceOpenInterpolator
                        : motion.workspaceHomeInterpolator);
                launcherAnimator.play(scaleAnim);
            });

            endListener = () -> {
                viewsToAnimate.forEach(view -> {
                    if (view.getTag(com.android.app.animation.R.id.ongoing_animation)
                            != launcherAnimator) return;
                    if (!cancelled[0]) {
                        SCALE_PROPERTY.set(view, 1f);
                    }
                    view.setLayerType(View.LAYER_TYPE_NONE, null);

                    // Reset the cached animation.
                    // setOngoingAnimation(null) cancels the tagged animator. An old end
                    // callback must only detach its own tag, never cancel a newer launch.
                    view.setTag(com.android.app.animation.R.id.ongoing_animation, null);
                });
                mLauncher.resumeExpensiveViewUpdates();
            };
        }

        launcherAnimator.setStartDelay(startDelay);
        return new Pair<>(launcherAnimator, endListener);
    }

    /**
     * Compose recents view alpha and translation Y animation when launcher opens/closes apps.
     *
     * @param anim   the animator set to add to
     * @param alphas the alphas to animate to over time
     * @param scales the scale values to animator to over time
     * @return listener to run when the animation ends
     */
    protected Runnable composeViewContentAnimator(@NonNull AnimatorSet anim,
            float[] alphas, float[] scales) {
        RecentsView overview = mLauncher.getOverviewPanel();
        ObjectAnimator alpha = ObjectAnimator.ofFloat(overview,
                RecentsView.CONTENT_ALPHA, alphas);
        alpha.setDuration(CONTENT_ALPHA_DURATION);
        alpha.setInterpolator(LINEAR);
        anim.play(alpha);
        overview.setFreezeViewVisibility(true);

        ObjectAnimator scaleAnim = ObjectAnimator.ofFloat(overview, SCALE_PROPERTY, scales);
        scaleAnim.setInterpolator(AGGRESSIVE_EASE);
        scaleAnim.setDuration(CONTENT_SCALE_DURATION);
        anim.play(scaleAnim);

        return () -> {
            overview.setFreezeViewVisibility(false);
            SCALE_PROPERTY.set(overview, 1f);
            mLauncher.getStateManager().reapplyState();
            mLauncher.resumeExpensiveViewUpdates();
        };
    }

    private boolean shouldCropToInset(RemoteAnimationTarget target) {
        return enableDynamicInsetsForAppLaunch()
                && mDeviceProfile.isTaskbarPresent
                && mDeviceProfile.isTaskbarPresentInApps
                && target != null && !target.willShowImeOnTarget
                && !isTransientTaskbar();
    }

    /**
     * @return Animator that controls the window of the opening targets from app icons.
     */
    private Animator getOpeningWindowAnimators(View v,
            RemoteAnimationTarget[] appTargets,
            RemoteAnimationTarget[] wallpaperTargets,
            RemoteAnimationTarget[] nonAppTargets,
            boolean launcherClosing, Runnable onInterrupt,
            LauncherAnimationRunner.AnimationResult result) {
        RemoteAnimationTargets openingTargets = new RemoteAnimationTargets(appTargets,
                wallpaperTargets, nonAppTargets, MODE_OPENING);
        final RemoteAnimationTarget target = openingTargets.getFirstAppTarget();
        final DesktopAnimationSettings motion = DesktopAnimationSettings.read(mLauncher);
        final AppWindowAnimationState.Session motionState = AppWindowAnimationState.beginOpening(
                target, mDeviceProfile);
        final AppWindowAnimationState.Pose previous = motionState.previous;
        final int displayRotationChange = getRotationChange(appTargets);
        final int rotationChange = LandscapeAppAnimation.getOpeningCoordinateRotation(
                mDeviceProfile, target, displayRotationChange);
        Rect windowTargetBounds = getWindowTargetBounds(appTargets, rotationChange);
        final int[] bottomInsetPos = new int[]{
                mSystemUiProxy.getHomeVisibilityState().getNavbarInsetPosition()};
        final float landscapeRotation = openingTargets.apps.length == 1
                ? LandscapeAppAnimation.getOpeningRotation(mLauncher, mDeviceProfile, target,
                        rotationChange) : 0f;
        final Interpolator openingInterpolator = motion.interpolator;
        final Interpolator openingXInterpolator = motion.interpolator;
        final boolean cropToInset = shouldCropToInset(target);
        if (cropToInset) {
            // Animate to above the taskbar.
            windowTargetBounds.bottom = Math.min(bottomInsetPos[0],
                    windowTargetBounds.bottom);
        }
        boolean appTargetsAreTranslucent = areAllTargetsTranslucent(appTargets);

        RectF launcherIconBounds = new RectF();
        FloatingIconView floatingView = getFloatingIconView(mLauncher, v,
                (mLauncher.getTaskbarInteractor() == null || !isTransientTaskbar())
                        ? null
                        : mLauncher.getTaskbarInteractor().findMatchingAsyncView(v),
                null /* fadeOutView */, !appTargetsAreTranslucent, launcherIconBounds,
                true /* isOpening */);
        Rect crop = new Rect();
        Matrix matrix = new Matrix();

        SurfaceTransactionApplier surfaceApplier =
                new SurfaceTransactionApplier(floatingView);
        openingTargets.addReleaseCheck(surfaceApplier);
        motionState.setGestureTargets(openingTargets, () -> {
            // Keep the remote transition alive while recents prepares its replacement leash.
            // The gesture takes over the live window immediately, without a fullscreen gap.
            Runnable finish = result.deferFinish(() -> {
                // Shell cancels the old remote before delivering recents. The gesture now
                // owns these leashes and releases them after the atomic first-frame ack,
                // cancellation, or its watchdog; releasing here can blank the retained root.
            });
            motionState.setGestureRelease(() -> {
                openingTargets.release();
                finish.run();
            });
            surfaceApplier.cancelPendingTransactions();
            if (!appTargetsAreTranslucent) {
                // Cancelling the source timeline must not start blur recovery under the finger.
                mLauncher.getDepthController().holdAppTransition(motionState);
            }
            onInterrupt.run();
        }, () -> mLauncher.getDepthController().endAppTransition(motionState));
        RemoteAnimationTarget navBarTarget = openingTargets.getNavBarRemoteAnimationTarget();

        int[] dragLayerBounds = new int[2];
        mDragLayer.getLocationOnScreen(dragLayerBounds);
        final Rect fullWindowCrop = new Rect(0, 0,
                windowTargetBounds.width(), windowTargetBounds.height());
        if (rotationChange != 0) {
            Utilities.rotateBounds(fullWindowCrop, mDeviceProfile.getDeviceProperties().getWidthPx(),
                    mDeviceProfile.getDeviceProperties().getHeightPx(), rotationChange);
        }
        final RectF openingStartRect = previous == null
                ? new RectF(launcherIconBounds) : new RectF(previous.rect);
        final RectF openingEndRect = new RectF(windowTargetBounds);
        openingEndRect.offset(-dragLayerBounds[0], -dragLayerBounds[1]);
        final boolean directGeometry = landscapeRotation != 0f || previous != null
                || (openingTargets.apps.length == 1 && target != null && !target.isTranslucent
                        && !cropToInset && rotationChange == 0
                        && target.windowConfiguration.getWindowingMode()
                                == android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN);
        final boolean hasFollowingStatusBar =
                SystemBarFollowAnimation.getSurface(openingTargets, target) != null;
        final boolean hasPreviousCropAnchor = previous != null
                && Float.isFinite(previous.verticalCropAnchor);
        // A quick reopen can arrive before WM releases the old bar owner. Preserve its source
        // slice even when that new transition cannot borrow the original status bar yet.
        final boolean anchorStatusBar = directGeometry && !cropToInset
                && openingTargets.apps.length == 1 && !appTargetsAreTranslucent
                && target != null && target.windowConfiguration.getWindowingMode()
                        == android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
                && rotationChange == 0
                && landscapeRotation == 0f && fullWindowCrop.height() > fullWindowCrop.width()
                && (hasFollowingStatusBar || hasPreviousCropAnchor);
        final float startCropAnchor = previous == null ? 0f
                : Float.isFinite(previous.verticalCropAnchor) ? previous.verticalCropAnchor : .5f;
        final float endCropAnchor = hasFollowingStatusBar ? 0f : startCropAnchor;
        final WindowMotion openX = new WindowMotion(openingStartRect.centerX(),
                openingEndRect.centerX(), previous == null ? Float.NaN : previous.velocity.x,
                motion.openDuration, motion.interpolator,
                motion.positionStiffness, motion.positionDamping);
        final WindowMotion openY = new WindowMotion(openingStartRect.centerY(),
                openingEndRect.centerY(), previous == null ? Float.NaN : previous.velocity.y,
                motion.openDuration, motion.interpolator,
                motion.positionStiffness, motion.positionDamping);
        final WindowMotion openWidth = new WindowMotion(openingStartRect.width(),
                openingEndRect.width(), previous == null ? Float.NaN : previous.sizeVelocity.x,
                motion.openDuration, motion.interpolator, motion.sizeStiffness, motion.sizeDamping);
        final WindowMotion openHeight = new WindowMotion(openingStartRect.height(),
                openingEndRect.height(), previous == null ? Float.NaN : previous.sizeVelocity.y,
                motion.openDuration, motion.openingHeightInterpolator,
                motion.sizeStiffness, motion.sizeDamping);
        final WindowAnimationSnapshot snapshot = directGeometry && !cropToInset
                && !appTargetsAreTranslucent && openingTargets.apps.length == 1
                && target != null && target.taskId >= 0
                ? new WindowAnimationSnapshot(mDragLayer, target.leash, fullWindowCrop, motionState,
                        SystemBarFollowAnimation.getSurface(openingTargets, target))
                : null;
        final IosWindowDeformation deformation = motion.isIos && snapshot != null
                && rotationChange == 0 && landscapeRotation == 0f
                ? new IosWindowDeformation(mDragLayer, target.leash, fullWindowCrop,
                        motion.openDuration) : null;
        final Matrix windowToHome = new Matrix();
        windowToHome.setRotate(-90f * rotationChange);
        if (rotationChange == 1) {
            windowToHome.postTranslate(0f, mDeviceProfile.getDeviceProperties().getWidthPx());
        } else if (rotationChange == 2) {
            windowToHome.postTranslate(mDeviceProfile.getDeviceProperties().getWidthPx(),
                    mDeviceProfile.getDeviceProperties().getHeightPx());
        } else if (rotationChange == 3) {
            windowToHome.postTranslate(mDeviceProfile.getDeviceProperties().getHeightPx(), 0f);
        }
        windowToHome.invert(windowToHome);
        windowToHome.postTranslate(-dragLayerBounds[0], -dragLayerBounds[1]);
        final Matrix snapshotMatrix = new Matrix();
        final boolean[] snapshotOnly = {false};
        final float[] lastOpeningProgress = {0f};
        final Runnable[] continueOpening = {null};

        final boolean hasSplashScreen;
        if (ENABLE_SHELL_STARTING_SURFACE) {
            int taskId = openingTargets.getFirstAppTargetTaskId();
            Pair<Integer, Integer> defaultParams = Pair.create(STARTING_WINDOW_TYPE_NONE, 0);
            Pair<Integer, Integer> taskParams =
                    mTaskStartParams.getOrDefault(taskId, defaultParams);
            mTaskStartParams.remove(taskId);
            hasSplashScreen = taskParams.first == STARTING_WINDOW_TYPE_SPLASH_SCREEN;
        } else {
            hasSplashScreen = false;
        }

        AnimOpenProperties prop = new AnimOpenProperties(mLauncher.getResources(), mDeviceProfile,
                windowTargetBounds, launcherIconBounds, v, dragLayerBounds[0], dragLayerBounds[1],
                hasSplashScreen, floatingView.isDifferentFromAppIcon());
        int left = prop.cropCenterXStart - prop.cropWidthStart / 2;
        int top = prop.cropCenterYStart - prop.cropHeightStart / 2;
        int right = left + prop.cropWidthStart;
        int bottom = top + prop.cropHeightStart;
        // Set the crop here so we can calculate the corner radius below.
        crop.set(left, top, right, bottom);

        RectF floatingIconBounds = new RectF();
        RectF tmpRectF = new RectF();
        Point tmpPos = new Point();
        Rect closingCrop = new Rect();
        final float finalWindowRadius = getWindowCornerRadius(mLauncher);
        final float cardWindowRadius = TaskCornerRadius.get(mLauncher);

        AnimatorSet animatorSet = new AnimatorSet();
        ValueAnimator appAnimator = ValueAnimator.ofFloat(0, 1);
        appAnimator.setDuration(motion.openDuration);
        appAnimator.setInterpolator(LINEAR);
        floatingView.setFastFinishRunnable(onInterrupt);
        AnimatorListenerAdapter windowListener = new AnimatorListenerAdapter() {
            private boolean mFinished;

            @Override
            public void onAnimationCancel(Animator animation) {
                if (mFinished) return;
                surfaceApplier.cancelPendingTransactions();
                if (deformation != null) deformation.close();
                if (!motionState.isGestureOwned()
                        && continueOpening[0] != null && lastOpeningProgress[0] > 0f) {
                    continueOpening[0].run();
                }
                motionState.cancel();
            }

            @Override
            public void onAnimationStart(Animator animation) {
                if (mFinished) return;
                floatingView.onAnimationStart(animation);
                if (!appTargetsAreTranslucent && !motionState.isGestureOwned()) {
                    mLauncher.getDepthController().beginAppTransition(appAnimator, true);
                }
                if (shouldShowEduOnAppLaunch()) {
                    // LAUNCHER_TASKBAR_EDUCATION_SHOWING is set to true here, when the education
                    // flow is about to start, to avoid a race condition with other components
                    // that would show something else to the user as soon as the app is opened.
                    Settings.Secure.putInt(mLauncher.getContentResolver(),
                            LAUNCHER_TASKBAR_EDUCATION_SHOWING, 1);
                }
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mFinished) return;
                mFinished = true;
                floatingView.onAnimationEnd(animation);
                if (!motionState.isGestureOwned()) motionState.finish();
                if (snapshot != null) snapshot.onSourceFinished();
                if (deformation != null) deformation.close();
                mLauncher.getDepthController().endAppTransition(appAnimator);
                if (v instanceof BubbleTextView) {
                    ((BubbleTextView) v).setStayPressed(false);
                }
                TaskbarInteractor taskbarInteractor = mLauncher.getTaskbarInteractor();
                if (taskbarInteractor != null) {
                    taskbarInteractor.showEduOnAppLaunch();
                }
                if (!motionState.isGestureOwned()) openingTargets.release();
            }

            private boolean shouldShowEduOnAppLaunch() {
                if (refactorTaskbarUiState()) {
                    final boolean ret = newShouldShowEduOnAppLaunch();
                    if (BuildConfig.IS_STUDIO_BUILD && ret != legacyShouldShowEduOnAppLaunch()) {
                        throw new IllegalStateException("shouldShowEduOnAppLaunch() doesn't match");
                    }
                    return ret;
                } else {
                    return legacyShouldShowEduOnAppLaunch();
                }
            }

            private boolean legacyShouldShowEduOnAppLaunch() {
                return mLauncher.getTaskbarInteractor() != null
                        && mLauncher.getTaskbarInteractor().shouldShowEduOnAppLaunch();
            }

            private boolean newShouldShowEduOnAppLaunch() {
                return mLauncher.getTaskbarUiState().getShouldShowEduOnAppLaunchRef().getValue();
            }
        };
        appAnimator.addListener(windowListener);
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                // A gesture may catch the launch during its one-frame start delay. The
                // child has not started then, so AnimatorSet.cancel() will not clean it up.
                if (!appAnimator.isStarted()) {
                    windowListener.onAnimationCancel(appAnimator);
                    windowListener.onAnimationEnd(appAnimator);
                }
            }
        });

        final float initialWindowRadius = supportsRoundedCornersOnWindows(mLauncher.getResources())
                ? Math.max(crop.width(), crop.height()) / 2f
                : 0f;
        final float finalShadowRadius = appTargetsAreTranslucent ? 0 : mMaxShadowRadius;

        MultiValueUpdateListener listener = new MultiValueUpdateListener() {
            FloatProp mDx = new FloatProp(0, prop.dX, openingXInterpolator);
            FloatProp mDy = new FloatProp(0, prop.dY, openingInterpolator);

            FloatProp mIconScaleToFitScreen = new FloatProp(prop.initialAppIconScale,
                    prop.finalAppIconScale, openingInterpolator);
            FloatProp mIconAlpha = new FloatProp(
                    previous != null ? 1f - motion.windowAlpha(1f - previous.openness)
                            : landscapeRotation != 0f ? 1f : prop.iconAlphaStart, 0f,
                    clampToDuration(LINEAR, 0, motion.iconFadeDuration, motion.openDuration));

            FloatProp mWindowRadius = new FloatProp(initialWindowRadius,
                    getWindowCornerRadius(mLauncher), openingInterpolator);
            FloatProp mShadowRadius = new FloatProp(0, finalShadowRadius,
                    openingInterpolator);

            FloatProp mCropRectCenterX = new FloatProp(prop.cropCenterXStart, prop.cropCenterXEnd,
                    openingInterpolator);
            FloatProp mCropRectCenterY = new FloatProp(prop.cropCenterYStart, prop.cropCenterYEnd,
                    openingInterpolator);
            FloatProp mCropRectWidth = new FloatProp(prop.cropWidthStart, prop.cropWidthEnd,
                    openingInterpolator);
            FloatProp mCropRectHeight = new FloatProp(prop.cropHeightStart, prop.cropHeightEnd,
                    openingInterpolator);

            FloatProp mNavFadeOut = new FloatProp(1f, 0f, clampToDuration(
                    NAV_FADE_OUT_INTERPOLATOR, 0, ANIMATION_NAV_FADE_OUT_DURATION,
                    motion.openDuration));
            FloatProp mNavFadeIn = new FloatProp(0f, 1f, clampToDuration(
                    NAV_FADE_IN_INTERPOLATOR,
                    Math.max(0, motion.openDuration - ANIMATION_NAV_FADE_IN_DURATION),
                    Math.min(ANIMATION_NAV_FADE_IN_DURATION, motion.openDuration), motion.openDuration));

            @Override
            public void onUpdate(float percent, boolean initOnly) {
                final boolean drawSnapshot = snapshotOnly[0];
                if (drawSnapshot ? snapshot == null || !snapshot.isContinuing()
                        : !motionState.isActive()) return;
                if (!drawSnapshot && !initOnly) lastOpeningProgress[0] = percent;
                if (cropToInset && bottomInsetPos[0] != mSystemUiProxy.getHomeVisibilityState()
                        .getNavbarInsetPosition()) {
                    final RemoteAnimationTarget target = openingTargets.getFirstAppTarget();
                    bottomInsetPos[0] = mSystemUiProxy.getHomeVisibilityState()
                            .getNavbarInsetPosition();
                    final Rect bounds = target != null
                            ? target.screenSpaceBounds : windowTargetBounds;
                    // Animate to above the taskbar.
                    int bottomLevel = Math.min(bottomInsetPos[0], bounds.bottom);
                    windowTargetBounds.bottom = bottomLevel;
                    final int endHeight = bottomLevel - bounds.top;

                    AnimOpenProperties prop = new AnimOpenProperties(mLauncher.getResources(),
                            mDeviceProfile, windowTargetBounds, launcherIconBounds, v,
                            dragLayerBounds[0], dragLayerBounds[1], hasSplashScreen,
                            floatingView.isDifferentFromAppIcon());
                    mCropRectCenterY = new FloatProp(prop.cropCenterYStart, prop.cropCenterYEnd,
                            openingInterpolator);
                    mCropRectHeight = new FloatProp(prop.cropHeightStart, prop.cropHeightEnd,
                            openingInterpolator);
                    mDy = new FloatProp(0, prop.dY, openingInterpolator);
                    mIconScaleToFitScreen = new FloatProp(prop.initialAppIconScale,
                            prop.finalAppIconScale, openingInterpolator);
                    float interpolatedPercent = openingInterpolator.getInterpolation(percent);
                    mCropRectHeight.value = Utilities.mapRange(interpolatedPercent,
                            prop.cropHeightStart, prop.cropHeightEnd);
                    mCropRectCenterY.value = Utilities.mapRange(interpolatedPercent,
                            prop.cropCenterYStart, prop.cropCenterYEnd);
                    mDy.value = Utilities.mapRange(interpolatedPercent, 0, prop.dY);
                    mIconScaleToFitScreen.value = Utilities.mapRange(interpolatedPercent,
                            prop.initialAppIconScale, prop.finalAppIconScale);
                }

                // Calculate the size of the scaled icon.
                float iconWidth = launcherIconBounds.width() * mIconScaleToFitScreen.value;
                float iconHeight = launcherIconBounds.height() * mIconScaleToFitScreen.value;

                int left = (int) (mCropRectCenterX.value - mCropRectWidth.value / 2);
                int top = (int) (mCropRectCenterY.value - mCropRectHeight.value / 2);
                int right = (int) (left + mCropRectWidth.value);
                int bottom = (int) (top + mCropRectHeight.value);
                crop.set(left, top, right, bottom);

                final int windowCropWidth = crop.width();
                final int windowCropHeight = crop.height();
                if (rotationChange != 0) {
                    Utilities.rotateBounds(crop, mDeviceProfile.getDeviceProperties().getWidthPx(),
                            mDeviceProfile.getDeviceProperties().getHeightPx(), rotationChange);
                }

                // Scale the size of the icon to match the size of the window crop.
                float scaleX = iconWidth / windowCropWidth;
                float scaleY = iconHeight / windowCropHeight;
                float scale = Math.min(1f, Math.max(scaleX, scaleY));

                float scaledCropWidth = windowCropWidth * scale;
                float scaledCropHeight = windowCropHeight * scale;
                float offsetX = (scaledCropWidth - iconWidth) / 2;
                float offsetY = (scaledCropHeight - iconHeight) / 2;

                // Calculate the window position to match the icon position.
                tmpRectF.set(launcherIconBounds);
                tmpRectF.offset(dragLayerBounds[0], dragLayerBounds[1]);
                tmpRectF.offset(mDx.value, mDy.value);
                Utilities.scaleRectFAboutCenter(tmpRectF, mIconScaleToFitScreen.value);
                float windowTransX0 = tmpRectF.left - offsetX - crop.left * scale;
                float windowTransY0 = tmpRectF.top - offsetY - crop.top * scale;

                // Calculate the icon position.
                floatingIconBounds.set(launcherIconBounds);
                floatingIconBounds.offset(mDx.value, mDy.value);
                Utilities.scaleRectFAboutCenter(floatingIconBounds, mIconScaleToFitScreen.value);
                floatingIconBounds.left -= offsetX;
                floatingIconBounds.top -= offsetY;
                floatingIconBounds.right += offsetX;
                floatingIconBounds.bottom += offsetY;

                final float expansion = openingInterpolator.getInterpolation(percent);
                final float verticalCropAnchor = anchorStatusBar
                        ? Utilities.mapRange(expansion, startCropAnchor, endCropAnchor) : Float.NaN;
                float openness = Utilities.mapRange(expansion,
                        previous == null ? 0f : previous.openness, 1f);
                float windowRadius = mWindowRadius.value;
                if (directGeometry) {
                    // Interpolate the visible rectangle once. Multiplying an independently
                    // eased icon scale by an eased crop made early landscape frames collapse
                    // into a capsule and hid most of the quarter-turn behind the icon fade.
                    float elapsed = percent * motion.openDuration;
                    float centerX = openX.value(elapsed, expansion);
                    float centerY = openY.value(elapsed, expansion);
                    float visibleWidth = Utilities.boundToRange(
                            openWidth.value(elapsed, expansion), launcherIconBounds.width(),
                            openingEndRect.width());
                    float visibleHeight = Utilities.boundToRange(
                            openHeight.value(elapsed), launcherIconBounds.height(),
                            openingEndRect.height());
                    floatingIconBounds.set(centerX - visibleWidth / 2f,
                            centerY - visibleHeight / 2f, centerX + visibleWidth / 2f,
                            centerY + visibleHeight / 2f);
                    if (previous != null) {
                        float travel = openingEndRect.height() - openingStartRect.height();
                        float sizeProgress = Math.abs(travel) < 1f ? expansion
                                : (visibleHeight - openingStartRect.height()) / travel;
                        openness = LandscapeAppAnimation.boundProgress(Utilities.mapRange(
                                sizeProgress, previous.openness, 1f));
                    }
                    boolean swap = (rotationChange & 1) != 0;
                    float width = swap ? floatingIconBounds.height() : floatingIconBounds.width();
                    float height = swap ? floatingIconBounds.width() : floatingIconBounds.height();
                    scale = Math.max(width / fullWindowCrop.width(), height / fullWindowCrop.height());
                    float halfWidth = width / scale / 2f;
                    float halfHeight = height / scale / 2f;
                    crop.set(Math.round(fullWindowCrop.exactCenterX() - halfWidth),
                            Math.round(fullWindowCrop.exactCenterY() - halfHeight),
                            Math.round(fullWindowCrop.exactCenterX() + halfWidth),
                            Math.round(fullWindowCrop.exactCenterY() + halfHeight));
                    if (anchorStatusBar) {
                        // Preserve the app's existing top inset. A resumed centered slice
                        // reaches the top smoothly instead of changing contents on its first frame.
                        crop.offset(0, Math.round(Math.max(0, fullWindowCrop.height() - crop.height())
                                * verticalCropAnchor) - crop.top);
                    }
                    // A physical display can have square corners while the floating app
                    // still needs card corners. Flatten only as it reaches the screen edges.
                    float remainingInset = Math.max(
                            openingEndRect.width() - visibleWidth,
                            openingEndRect.height() - visibleHeight) / 2f;
                    float cardRadius = Math.max(finalWindowRadius,
                            Math.min(cardWindowRadius, remainingInset));
                    float screenRadius = previous != null
                            ? Utilities.mapRange(expansion, previous.cornerRadius, cardRadius)
                            : Utilities.mapRange(motion.cornerProgress(1f - openness),
                                    cardRadius, launcherIconBounds.width() / 2f);
                    windowRadius = Math.min(screenRadius / scale,
                            Math.min(crop.width(), crop.height()) / 2f);
                }

                SurfaceTransaction transaction = drawSnapshot ? null : new SurfaceTransaction();

                final float landscapeProgress = motion.rotationProgress(1f - openness);
                if (!drawSnapshot) {
                    mLauncher.getDepthController().setAppTransitionProgress(appAnimator,
                            percent);
                    motionState.record(floatingIconBounds, openness, windowRadius * scale,
                            verticalCropAnchor);
                }

                for (int i = appTargets.length - 1; i >= 0; i--) {
                    RemoteAnimationTarget target = appTargets[i];
                    if (drawSnapshot && target != openingTargets.getFirstAppTarget()) continue;
                    SurfaceProperties builder = drawSnapshot ? null
                            : SystemBarFollowAnimation.forTarget(transaction, openingTargets, target);

                    if (target.mode == MODE_OPENING) {
                        matrix.setScale(scale, scale);
                        if (directGeometry) {
                            float centerX = floatingIconBounds.centerX() + dragLayerBounds[0];
                            float centerY = floatingIconBounds.centerY() + dragLayerBounds[1];
                            float homeX = centerX;
                            if (rotationChange == 1) {
                                centerX = centerY;
                                centerY = mDeviceProfile.getDeviceProperties().getWidthPx() - homeX;
                            } else if (rotationChange == 3) {
                                centerX = mDeviceProfile.getDeviceProperties().getHeightPx() - centerY;
                                centerY = homeX;
                            } else if (rotationChange == 2) {
                                centerX = mDeviceProfile.getDeviceProperties().getWidthPx() - centerX;
                                centerY = mDeviceProfile.getDeviceProperties().getHeightPx() - centerY;
                            }
                            matrix.setTranslate(-crop.exactCenterX(), -crop.exactCenterY());
                            matrix.postScale(scale, scale);
                            matrix.postTranslate(centerX, centerY);
                        } else if (rotationChange == 1) {
                            matrix.postTranslate(windowTransY0,
                                    mDeviceProfile.getDeviceProperties().getWidthPx() - (windowTransX0 + scaledCropWidth));
                        } else if (rotationChange == 2) {
                            matrix.postTranslate(
                                    mDeviceProfile.getDeviceProperties().getWidthPx() - (windowTransX0 + scaledCropWidth),
                                    mDeviceProfile.getDeviceProperties().getHeightPx() - (windowTransY0 + scaledCropHeight));
                        } else if (rotationChange == 3) {
                            matrix.postTranslate(
                                    mDeviceProfile.getDeviceProperties().getHeightPx() - (windowTransY0 + scaledCropHeight),
                                    windowTransX0);
                        } else {
                            matrix.postTranslate(windowTransX0, windowTransY0);
                        }

                        if (landscapeRotation != 0f) {
                            LandscapeAppAnimation.applyRotation(matrix, crop,
                                    landscapeRotation * landscapeProgress, tmpRectF);
                        }

                        if (drawSnapshot) {
                            snapshotMatrix.setConcat(windowToHome, matrix);
                            snapshot.update(snapshotMatrix, crop, windowRadius,
                                    1f - mIconAlpha.value, floatingIconBounds, openness,
                                    windowRadius * scale, verticalCropAnchor);
                        } else {
                            floatingView.update(initOnly && previous == null ? 1f : mIconAlpha.value,
                                    floatingIconBounds, percent, 0f,
                                    windowRadius * scale, true /* isOpening */);
                            if (landscapeRotation != 0f) {
                                floatingView.setAppRotation(
                                        -landscapeRotation * (1f - landscapeProgress),
                                        floatingIconBounds);
                            }
                            floatingView.setAppDeformation(deformation == null ? 0f
                                    : IosWindowShape.amount(percent, openness, true),
                                    floatingIconBounds);
                            float deformationAlpha = 0f;
                            if (deformation != null) {
                                snapshotMatrix.setConcat(windowToHome, matrix);
                                deformationAlpha = deformation.update(snapshotMatrix, crop,
                                        windowRadius, 1f - mIconAlpha.value, floatingIconBounds,
                                        percent, openness, true);
                            }
                            builder.setMatrix(matrix)
                                    .setWindowCrop(crop)
                                    .setAlpha((1f - mIconAlpha.value) * (1f - deformationAlpha))
                                    .setCornerRadius(windowRadius)
                                    .setShadowRadius(mShadowRadius.value);
                        }
                    } else if (target.mode == MODE_CLOSING) {
                        if (target.localBounds != null) {
                            tmpPos.set(target.localBounds.left, target.localBounds.top);
                        } else {
                            tmpPos.set(target.position.x, target.position.y);
                        }
                        closingCrop.set(target.screenSpaceBounds);
                        closingCrop.offsetTo(0, 0);

                        if ((displayRotationChange % 2) == 1) {
                            int tmp = closingCrop.right;
                            closingCrop.right = closingCrop.bottom;
                            closingCrop.bottom = tmp;
                            tmp = tmpPos.x;
                            tmpPos.x = tmpPos.y;
                            tmpPos.y = tmp;
                        }
                        matrix.setTranslate(tmpPos.x, tmpPos.y);
                        builder.setMatrix(matrix)
                                .setWindowCrop(closingCrop)
                                .setAlpha(1f);
                    }
                }

                if (drawSnapshot) return;
                if (navBarTarget != null) {
                    SurfaceProperties navBuilder =
                            transaction.forSurface(navBarTarget.leash);
                    if (landscapeRotation != 0f) {
                        // The system restores the bar after the rotated app reaches fullscreen.
                        navBuilder.setAlpha(0f);
                    } else if (mNavFadeIn.value > mNavFadeIn.getStartValue()) {
                        if (directGeometry) {
                            matrix.setTranslate(-crop.exactCenterX(), -crop.exactCenterY());
                            matrix.postScale(scale, scale);
                            matrix.postTranslate(floatingIconBounds.centerX() + dragLayerBounds[0],
                                    floatingIconBounds.centerY() + dragLayerBounds[1]);
                        } else {
                            matrix.setScale(scale, scale);
                            matrix.postTranslate(windowTransX0, windowTransY0);
                        }
                        navBuilder.setMatrix(matrix)
                                .setWindowCrop(crop)
                                .setAlpha(mNavFadeIn.value);
                    } else {
                        navBuilder.setAlpha(mNavFadeOut.value);
                    }
                }
                if (initOnly) {
                    // Shell has already exposed the new leash. Do not wait for another
                    // Launcher draw to restore the interrupted window's geometry.
                    transaction.getTransaction().apply();
                } else {
                    surfaceApplier.scheduleApply(transaction);
                }
            }
        };
        if (snapshot != null) {
            continueOpening[0] = () -> {
                ValueAnimator tail = ValueAnimator.ofFloat(0f, 1f);
                tail.setDuration(motion.openDuration);
                tail.setInterpolator(LINEAR);
                tail.addUpdateListener(listener);
                snapshotOnly[0] = true;
                snapshot.continueAnimation(tail,
                        Math.round(lastOpeningProgress[0] * motion.openDuration));
            };
        }
        appAnimator.addUpdateListener(listener);
        // Initialize the leash as well as the icon before the one-frame start delay. In a
        // reopen, leaving the new leash at its default transform flashes a fullscreen frame
        // between the interrupted return pose and the first launch animation pulse.
        listener.onUpdate(0, true /* initOnly */);

        // If app targets are translucent, do not animate the background as it causes a visible
        // flicker when it resets itself at the end of its animation.
        if (appTargetsAreTranslucent || !launcherClosing) {
            animatorSet.play(appAnimator);
        } else {
            animatorSet.playTogether(appAnimator,
                    getBackgroundAnimator(motion.openDuration, motion.interpolator));
        }
        return animatorSet;
    }

    private boolean isTransientTaskbar() {
        return TaskbarFeatureEvaluator.INSTANCE.get(mLauncher).isTransient();
    }

    private Animator getOpeningWindowAnimatorsForWidget(LauncherAppWidgetHostView v,
            RemoteAnimationTarget[] appTargets,
            RemoteAnimationTarget[] wallpaperTargets,
            RemoteAnimationTarget[] nonAppTargets, boolean launcherClosing) {
        Rect windowTargetBounds = getWindowTargetBounds(appTargets, getRotationChange(appTargets));
        boolean appTargetsAreTranslucent = areAllTargetsTranslucent(appTargets);

        final RectF widgetBackgroundBounds = new RectF();
        final Rect appWindowCrop = new Rect();
        final Matrix matrix = new Matrix();
        RemoteAnimationTargets openingTargets = new RemoteAnimationTargets(appTargets,
                wallpaperTargets, nonAppTargets, MODE_OPENING);

        RemoteAnimationTarget openingTarget = openingTargets.getFirstAppTarget();
        int fallbackBackgroundColor = 0;
        if (openingTarget != null && ENABLE_SHELL_STARTING_SURFACE) {
            fallbackBackgroundColor = mTaskStartParams.containsKey(openingTarget.taskId)
                    ? mTaskStartParams.get(openingTarget.taskId).second : 0;
            mTaskStartParams.remove(openingTarget.taskId);
        }
        if (fallbackBackgroundColor == 0) {
            fallbackBackgroundColor =
                    FloatingWidgetView.getDefaultBackgroundColor(mLauncher, openingTarget);
        }

        final float finalWindowRadius = getWindowCornerRadius(mLauncher);
        final FloatingWidgetView floatingView = FloatingWidgetView.getFloatingWidgetView(mLauncher,
                v, widgetBackgroundBounds,
                new Size(windowTargetBounds.width(), windowTargetBounds.height()),
                finalWindowRadius, appTargetsAreTranslucent, fallbackBackgroundColor);
        final float initialWindowRadius = supportsRoundedCornersOnWindows(mLauncher.getResources())
                ? floatingView.getInitialCornerRadius() : 0;

        SurfaceTransactionApplier surfaceApplier = new SurfaceTransactionApplier(floatingView);
        openingTargets.addReleaseCheck(surfaceApplier);

        RemoteAnimationTarget navBarTarget = openingTargets.getNavBarRemoteAnimationTarget();

        AnimatorSet animatorSet = new AnimatorSet();
        ValueAnimator appAnimator = ValueAnimator.ofFloat(0, 1);
        appAnimator.setDuration(APP_LAUNCH_DURATION);
        appAnimator.setInterpolator(LINEAR);
        appAnimator.addListener(floatingView);
        appAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                openingTargets.release();
            }
        });
        floatingView.setFastFinishRunnable(animatorSet::end);

        appAnimator.addUpdateListener(new MultiValueUpdateListener() {
            float mAppWindowScale = 1;
            final FloatProp mWidgetForegroundAlpha = new FloatProp(1, 0, clampToDuration(
                    LINEAR, 0, WIDGET_CROSSFADE_DURATION_MILLIS / 2, APP_LAUNCH_DURATION));

            final FloatProp mWidgetFallbackBackgroundAlpha = new FloatProp(0, 1,
                    clampToDuration(LINEAR, 0, 75, APP_LAUNCH_DURATION));
            final FloatProp mPreviewAlpha = new FloatProp(0, 1, clampToDuration(
                    LINEAR,
                    WIDGET_CROSSFADE_DURATION_MILLIS / 2 /* delay */,
                    WIDGET_CROSSFADE_DURATION_MILLIS / 2 /* duration */,
                    APP_LAUNCH_DURATION));
            final FloatProp mWindowRadius = new FloatProp(initialWindowRadius, finalWindowRadius,
                    mOpeningInterpolator);
            final FloatProp mCornerRadiusProgress = new FloatProp(0, 1, mOpeningInterpolator);

            // Window & widget background positioning bounds
            final FloatProp mDx = new FloatProp(widgetBackgroundBounds.centerX(),
                    windowTargetBounds.centerX(), mOpeningXInterpolator);
            final FloatProp mDy = new FloatProp(widgetBackgroundBounds.centerY(),
                    windowTargetBounds.centerY(), mOpeningInterpolator);
            final FloatProp mWidth = new FloatProp(widgetBackgroundBounds.width(),
                    windowTargetBounds.width(), mOpeningInterpolator);
            final FloatProp mHeight = new FloatProp(widgetBackgroundBounds.height(),
                    windowTargetBounds.height(), mOpeningInterpolator);

            final FloatProp mNavFadeOut = new FloatProp(1f, 0f, clampToDuration(
                    NAV_FADE_OUT_INTERPOLATOR, 0, ANIMATION_NAV_FADE_OUT_DURATION,
                    APP_LAUNCH_DURATION));
            final FloatProp mNavFadeIn = new FloatProp(0f, 1f, clampToDuration(
                    NAV_FADE_IN_INTERPOLATOR, ANIMATION_DELAY_NAV_FADE_IN,
                    ANIMATION_NAV_FADE_IN_DURATION, APP_LAUNCH_DURATION));

            @Override
            public void onUpdate(float percent, boolean initOnly) {
                widgetBackgroundBounds.set(mDx.value - mWidth.value / 2f,
                        mDy.value - mHeight.value / 2f, mDx.value + mWidth.value / 2f,
                        mDy.value + mHeight.value / 2f);
                // Set app window scaling factor to match widget background width
                mAppWindowScale = widgetBackgroundBounds.width() / windowTargetBounds.width();
                // Crop scaled app window to match widget
                appWindowCrop.set(0 /* left */, 0 /* top */,
                        windowTargetBounds.width() /* right */,
                        Math.round(widgetBackgroundBounds.height() / mAppWindowScale) /* bottom */);
                matrix.setTranslate(widgetBackgroundBounds.left, widgetBackgroundBounds.top);
                matrix.postScale(mAppWindowScale, mAppWindowScale, widgetBackgroundBounds.left,
                        widgetBackgroundBounds.top);

                SurfaceTransaction transaction = new SurfaceTransaction();
                float floatingViewAlpha = appTargetsAreTranslucent ? 1 - mPreviewAlpha.value : 1;
                for (int i = appTargets.length - 1; i >= 0; i--) {
                    RemoteAnimationTarget target = appTargets[i];
                    SurfaceProperties builder = SystemBarFollowAnimation.forTarget(
                            transaction, openingTargets, target);
                    if (target.mode == MODE_OPENING) {
                        floatingView.update(widgetBackgroundBounds, floatingViewAlpha,
                                mWidgetForegroundAlpha.value, mWidgetFallbackBackgroundAlpha.value,
                                mCornerRadiusProgress.value);
                        builder.setMatrix(matrix)
                                .setWindowCrop(appWindowCrop)
                                .setAlpha(mPreviewAlpha.value)
                                .setCornerRadius(mWindowRadius.value / mAppWindowScale);
                    }
                }

                if (navBarTarget != null) {
                    SurfaceProperties navBuilder = transaction.forSurface(navBarTarget.leash);
                    if (mNavFadeIn.value > mNavFadeIn.getStartValue()) {
                        navBuilder.setMatrix(matrix)
                                .setWindowCrop(appWindowCrop)
                                .setAlpha(mNavFadeIn.value);
                    } else {
                        navBuilder.setAlpha(mNavFadeOut.value);
                    }
                }
                surfaceApplier.scheduleApply(transaction);
            }
        });

        // If app targets are translucent, do not animate the background as it causes a visible
        // flicker when it resets itself at the end of its animation.
        if (appTargetsAreTranslucent || !launcherClosing) {
            animatorSet.play(appAnimator);
        } else {
            animatorSet.playTogether(appAnimator, getBackgroundAnimator());
        }
        return animatorSet;
    }

    /** Returns animator that controls depth/blur of the background during app/widget opening. */
    private Animator getBackgroundAnimator() {
        return getBackgroundAnimator(APP_LAUNCH_DURATION, mOpeningInterpolator);
    }

    private Animator getBackgroundAnimator(long duration, Interpolator interpolator) {
        if (Flags.allAppsBlur()) {
            // Don't animate/blur the background for this launch, regardless of the launcher state.
            // We have too many performance issues with the blur.
            return new AnimatorSet();
        }

        ObjectAnimator backgroundRadiusAnim = ObjectAnimator.ofFloat(
                        mLauncher.getDepthController().stateDepth, MULTI_PROPERTY_VALUE,
                        BACKGROUND_APP.getDepth(mLauncher))
                .setDuration(duration);
        backgroundRadiusAnim.setInterpolator(interpolator);

        // The depth controller owns the blur surface. A second, unconfigured effect layer
        // contributes no blur and needlessly allocates/removes a SurfaceControl on every launch.
        return backgroundRadiusAnim;
    }

    /**
     * Registers remote animations used when closing apps to home screen.
     */
    public void registerRemoteAnimations() {
        if (SEPARATE_RECENTS_ACTIVITY.get()) {
            return;
        }
        RemoteAnimationDefinition definition = new RemoteAnimationDefinition();
        addRemoteAnimations(definition);
        mLauncher.registerRemoteAnimations(definition);
    }

    /**
     * Adds remote animations to a {@link RemoteAnimationDefinition}. May be overridden to add
     * additional animations.
     */
    private void addRemoteAnimations(RemoteAnimationDefinition definition) {
        mWallpaperOpenRunner = new WallpaperOpenLauncherAnimationRunner();
        definition.addRemoteAnimation(WindowManager.TRANSIT_OLD_WALLPAPER_OPEN,
                WindowConfiguration.ACTIVITY_TYPE_STANDARD,
                new RemoteAnimationAdapter(
                        new LauncherAnimationRunner(mHandler, mWallpaperOpenRunner,
                                false /* startAtFrontOfQueue */),
                        CLOSING_TRANSITION_DURATION_MS, 0 /* statusBarTransitionDelay */));
    }

    /**
     * Registers remote animations used when closing apps to home screen.
     */
    public void registerRemoteTransitions() {
        SystemUiProxy.INSTANCE.get(mLauncher).shareTransactionQueue();
        if (SEPARATE_RECENTS_ACTIVITY.get()) {
            return;
        }

        mWallpaperOpenTransitionRunner = new WallpaperOpenLauncherAnimationRunner();
        mLauncherOpenTransition = new RemoteTransition(
                new LauncherAnimationRunner(mHandler, mWallpaperOpenTransitionRunner,
                        false /* startAtFrontOfQueue */).toRemoteTransition(),
                mLauncher.getIApplicationThread(), "QuickstepLaunchHome");

        TransitionFilter homeCheck = new TransitionFilter();
        // No need to handle the transition that also dismisses keyguard.
        homeCheck.mNotFlags = TRANSIT_FLAG_KEYGUARD_GOING_AWAY;

        homeCheck.mRequirements =
                new TransitionFilter.Requirement[]{new TransitionFilter.Requirement(),
                        new TransitionFilter.Requirement(),
                        new TransitionFilter.Requirement()};

        homeCheck.mRequirements[0].mActivityType = ACTIVITY_TYPE_HOME;
        homeCheck.mRequirements[0].mTopActivity = mLauncher.getComponentName();
        homeCheck.mRequirements[0].mModes = new int[]{TRANSIT_OPEN, TRANSIT_TO_FRONT};
        if (!com.android.window.flags.Flags.polishCloseWallpaperIncludesOpenChange()) {
            homeCheck.mRequirements[0].mOrder = CONTAINER_ORDER_TOP;
        }

        homeCheck.mRequirements[1].mActivityType = ACTIVITY_TYPE_STANDARD;
        homeCheck.mRequirements[1].mModes = new int[]{TRANSIT_CLOSE, TRANSIT_TO_BACK};

        homeCheck.mRequirements[2].mNot = true;
        homeCheck.mRequirements[2].mCustomAnimation = true;
        homeCheck.mRequirements[2].mMustBeTask = true;
        homeCheck.mRequirements[2].mMustBeIndependent = true;

        SystemUiProxy.INSTANCE.get(mLauncher)
                .registerRemoteTransition(mLauncherOpenTransition, homeCheck);
        if (mBackAnimationController != null) {
            mBackAnimationController.registerComponentCallbacks();
            if (isHomeRoleHeld()) {
                mBackAnimationController.registerBackCallbacks(mHandler);
            }
        }

        /*
         * For cross-display moves, moving to the default display is handled by the LaunchOptions
         * transition setup, which forwards to AppLaunchRemoteAnimationRunner. However, if the
         * launch happens via a different means (e.g. desktop mode), we also need to handle the
         * cross-display move via a remote transition.
         */
        if (com.android.window.flags.Flags.enableCrossDisplaysAppLaunchTransition()) {
            mMoveDisplayTransition = new RemoteTransition(new MoveDisplayChangeRunner(),
                    mLauncher.getIApplicationThread(), "QuickstepDisplayMove");
            TransitionFilter changeCheck = new TransitionFilter();
            changeCheck.mRequirements = new TransitionFilter.Requirement[]{
                    new TransitionFilter.Requirement()};
            changeCheck.mRequirements[0].mModes = new int[]{TRANSIT_CHANGE};
            changeCheck.mRequirements[0].mMustBeTask = true;
            // (TRANSIT_CHANGE is never independent.)
            changeCheck.mRequirements[0].mMustBeIndependent = false;
            changeCheck.mRequirements[0].mActivityType = ACTIVITY_TYPE_STANDARD;
            changeCheck.mRequirements[0].mIsCrossDisplayMove = true;
            SystemUiProxy.INSTANCE.get(mLauncher)
                    .registerRemoteTransition(mMoveDisplayTransition, changeCheck);
        }
    }

    public void onActivityDestroyed() {
        unregisterRemoteAnimations();
        unregisterRemoteTransitions();
        mLauncher.removeOnDeviceProfileChangeListener(this);
        SystemUiProxy.INSTANCE.get(mLauncher).setStartingWindowListener(null);
        if (BuildConfig.IS_STUDIO_BUILD && !mRegisteredTaskStackChangeListener.isEmpty()) {
            Log.e(TAG, "IllegalState: Failed to run onEndCallback created from"
                    + " getActivityLaunchOptions()");
        }
        mRegisteredTaskStackChangeListener.forEach(TaskRestartedDuringLaunchListener::unregister);
        mRegisteredTaskStackChangeListener.clear();
    }

    /**
     * Called when the overview-target changes. Updates the back callback registration state.
     */
    public void onOverviewTargetChange() {
        if (isHomeRoleHeld()) {
            mBackAnimationController.registerBackCallbacks(mHandler);
        } else {
            mBackAnimationController.unregisterBackCallbacks();
        }
    }

    private boolean isHomeRoleHeld() {
        RoleManager roleManager = mLauncher.getSystemService(RoleManager.class);
        return roleManager == null || roleManager.isRoleHeld(ROLE_HOME);
    }

    private void unregisterRemoteAnimations() {
        if (SEPARATE_RECENTS_ACTIVITY.get()) {
            return;
        }
        mLauncher.unregisterRemoteAnimations();

        // Also clear strong references to the runners registered with the remote animation
        // definition so we don't have to wait for the system gc
        mWallpaperOpenRunner = null;
        mAppLaunchRunners.clear();
    }

    protected void unregisterRemoteTransitions() {
        SystemUiProxy.INSTANCE.get(mLauncher).unshareTransactionQueue();
        if (SEPARATE_RECENTS_ACTIVITY.get()) {
            return;
        }
        if (mLauncherOpenTransition == null) return;
        SystemUiProxy.INSTANCE.get(mLauncher).unregisterRemoteTransition(
                mLauncherOpenTransition);
        mLauncherOpenTransition = null;
        mWallpaperOpenTransitionRunner = null;
        if (mMoveDisplayTransition != null) {
            SystemUiProxy.INSTANCE.get(mLauncher)
                    .unregisterRemoteTransition(mMoveDisplayTransition);
            mMoveDisplayTransition = null;
        }
        if (mBackAnimationController != null) {
            mBackAnimationController.unregisterBackCallbacks();
            mBackAnimationController.unregisterComponentCallbacks();
            mBackAnimationController = null;
        }
    }

    private boolean launcherIsATargetWithMode(RemoteAnimationTarget[] targets, int mode) {
        for (RemoteAnimationTarget target : targets) {
            if (target.mode == mode && target.taskInfo != null
                    // Compare component name instead of task-id because transitions will promote
                    // the target up to the root task while getTaskId returns the leaf.
                    && target.taskInfo.topActivity != null
                    && target.taskInfo.topActivity.equals(mLauncher.getComponentName())) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldPlayFallbackClosingAnimation(RemoteAnimationTarget[] targets) {
        int numTargets = 0;
        for (RemoteAnimationTarget target : targets) {
            if (target.mode == MODE_CLOSING) {
                numTargets++;
                if (numTargets > 1 || target.windowConfiguration.getWindowingMode()
                        == WINDOWING_MODE_MULTI_WINDOW) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int getRotationChange(RemoteAnimationTarget[] appTargets) {
        int rotationChange = 0;
        for (RemoteAnimationTarget target : appTargets) {
            if (Math.abs(target.rotationChange) > Math.abs(rotationChange)) {
                rotationChange = target.rotationChange;
            }
        }
        return Math.floorMod(rotationChange, 4);
    }

    /**
     * Returns view on launcher that corresponds to the closing app in the list of app targets
     */
    public @Nullable View findLauncherView(RemoteAnimationTarget[] appTargets) {
        for (RemoteAnimationTarget appTarget : appTargets) {
            if (appTarget.mode == MODE_CLOSING) {
                View launcherView = findLauncherView(appTarget);
                if (launcherView != null) {
                    return launcherView;
                }
            }
        }
        return null;
    }

    /**
     * Returns view on launcher that corresponds to the {@param runningTaskTarget}.
     */
    private @Nullable View findLauncherView(RemoteAnimationTarget runningTaskTarget) {
        if (runningTaskTarget == null || runningTaskTarget.taskInfo == null) {
            return null;
        }

        final ComponentName[] taskInfoActivities = new ComponentName[]{
                runningTaskTarget.taskInfo.baseActivity,
                runningTaskTarget.taskInfo.origActivity,
                runningTaskTarget.taskInfo.realActivity,
                runningTaskTarget.taskInfo.topActivity};

        String packageName = null;
        for (ComponentName component : taskInfoActivities) {
            if (component != null && component.getPackageName() != null) {
                packageName = component.getPackageName();
                break;
            }
        }

        if (packageName == null) {
            return null;
        }

        // Find the associated item info for the launch cookie (if available), note that predicted
        // apps actually have an id of -1, so use another default id here
        final List<IBinder> launchCookies = runningTaskTarget.taskInfo.launchCookies == null
                ? Collections.EMPTY_LIST
                : runningTaskTarget.taskInfo.launchCookies;

        return mLauncher.getFirstVisibleElementForAppClose(
                StableViewInfo.fromLaunchCookies(launchCookies), packageName,
                UserHandle.of(runningTaskTarget.taskInfo.userId));
    }

    private @NonNull RectF getDefaultWindowTargetRect() {
        return LandscapeAppAnimation.getDefaultHomeTarget(mLauncher.getDeviceProfile());
    }

    /**
     * Closing animator that animates the window into its final location on the workspace.
     */
    protected RectFSpringAnim getClosingWindowAnimators(AnimatorSet animation,
            RemoteAnimationTarget[] targets, View launcherView, PointF velocityPxPerS,
            RectF closingWindowStartRectF, float startWindowCornerRadius) {
        return getClosingWindowAnimators(animation, targets, new RemoteAnimationTarget[0],
                launcherView, velocityPxPerS, closingWindowStartRectF, startWindowCornerRadius);
    }

    private RectFSpringAnim getClosingWindowAnimators(AnimatorSet animation,
            RemoteAnimationTarget[] targets, RemoteAnimationTarget[] nonAppTargets,
            View launcherView, PointF velocityPxPerS, RectF closingWindowStartRectF,
            float startWindowCornerRadius) {
        FloatingIconView floatingIconView = null;
        FloatingWidgetView floatingWidget = null;
        RectF targetRect = new RectF();

        RemoteAnimationTarget runningTaskTarget = null;
        boolean isTransluscent = false;
        for (RemoteAnimationTarget target : targets) {
            if (target.mode == MODE_CLOSING) {
                runningTaskTarget = target;
                isTransluscent = runningTaskTarget.isTranslucent;
                break;
            }
        }

        // Get floating view and target rect.
        boolean isInHotseat = false;
        if (launcherView instanceof LauncherAppWidgetHostView) {
            Size windowSize = new Size(mDeviceProfile.getDeviceProperties().getWidthPx(),
                    mDeviceProfile.getDeviceProperties().getHeightPx());
            int fallbackBackgroundColor =
                    FloatingWidgetView.getDefaultBackgroundColor(mLauncher, runningTaskTarget);
            floatingWidget = FloatingWidgetView.getFloatingWidgetView(mLauncher,
                    (LauncherAppWidgetHostView) launcherView, targetRect, windowSize,
                    getWindowCornerRadius(mLauncher), isTransluscent, fallbackBackgroundColor);
        } else if (launcherView != null && !RemoveAnimationSettingsTracker.INSTANCE.get(
                mLauncher).isRemoveAnimationEnabled()) {
            floatingIconView = getFloatingIconView(mLauncher, launcherView, null,
                    mLauncher.getTaskbarInteractor() == null
                            ? null
                            : mLauncher.getTaskbarInteractor().findMatchingAsyncView(launcherView),
                    true /* hideOriginal */, targetRect, false /* isOpening */);
        } else {
            targetRect.set(getDefaultWindowTargetRect());
        }

        final float landscapeRotation = floatingWidget != null ? 0f
                : LandscapeAppAnimation.getClosingRotation(mLauncher, mDeviceProfile,
                        runningTaskTarget, mDeviceProfile.getDeviceProperties().getRotationHint());
        final DesktopAnimationSettings motion = DesktopAnimationSettings.read(mLauncher);
        final AppWindowAnimationState.Session motionState = AppWindowAnimationState.begin(
                floatingWidget == null && runningTaskTarget != null
                        ? runningTaskTarget.taskId : -1, mDeviceProfile);
        final float startOpenness = motionState.previous == null
                ? 1f : motionState.previous.openness;
        if (motionState.previous != null) {
            closingWindowStartRectF.set(motionState.previous.rect);
            startWindowCornerRadius = motionState.previous.cornerRadius;
            velocityPxPerS = new PointF(motionState.previous.velocity.x,
                    motionState.previous.velocity.y);
        }
        final PointF startVelocity = velocityPxPerS;
        boolean useTaskbarHotseatParams = mDeviceProfile.isTaskbarPresent && isInHotseat;
        RectFSpringAnim anim = new RectFSpringAnim(useTaskbarHotseatParams
                ? new TaskbarHotseatSpringConfig(mLauncher, closingWindowStartRectF, targetRect)
                : new DefaultSpringConfig(mLauncher, mDeviceProfile, closingWindowStartRectF,
                        targetRect));
        if (floatingWidget == null && !useTaskbarHotseatParams) anim.setMotionSettings(motion);
        if (motionState.previous != null) {
            anim.setInitialSizeVelocity(motionState.previous.sizeVelocity.x,
                    motionState.previous.sizeVelocity.y);
        }

        // Hook up floating views to the closing window animators.
        // note the coordinate of closingWindowStartRect is based on launcher
        Rect closingWindowStartRect = new Rect();
        closingWindowStartRectF.round(closingWindowStartRect);
        Rect closingWindowOriginalRect =
                new Rect(0, 0, mDeviceProfile.getDeviceProperties().getWidthPx(), mDeviceProfile.getDeviceProperties().getHeightPx());
        if (floatingIconView != null) {
            anim.addAnimatorListener(floatingIconView);
            floatingIconView.setOnTargetChangeListener(anim::onTargetPositionChanged);
            floatingIconView.setFastFinishRunnable(animation::cancel);
            FloatingIconView finalFloatingIconView = floatingIconView;

            // We want the window alpha to be 0 once this threshold is met, so that the
            // FloatingIconView can be seen morphing into the icon shape.
            final float windowAlphaThreshold = 1f - SHAPE_PROGRESS_DURATION;

            SpringAnimRunner runner = new SpringAnimRunner(targets, nonAppTargets, targetRect,
                    closingWindowStartRect, closingWindowOriginalRect, startWindowCornerRadius,
                    landscapeRotation, motion, motionState, startOpenness, true, anim) {
                @Override
                public void onUpdate(RectF currentRectF, float progress) {
                    // We want the icon alpha to be 1 once this threshold is met, so that it can be
                    // seen morphing into the icon shape. But before the threshold, we want to limit
                    // the alpha to reduce the blur effect behind the window.
                    float closeProgress = 1f - startOpenness * (1f - progress);
                    float iconAlpha = 1f - motion.windowAlpha(closeProgress);
                    finalFloatingIconView.update(iconAlpha, currentRectF, progress,
                            windowAlphaThreshold, getCornerRadius(progress), false);
                    finalFloatingIconView.setAppDeformation(motion.isIos
                                    && landscapeRotation == 0f
                                    ? IosWindowShape.amount(anim.getTimelineProgress(),
                                            1f - closeProgress, false) : 0f,
                            currentRectF);
                    if (landscapeRotation != 0f) {
                        finalFloatingIconView.setAppRotation(-landscapeRotation
                                * (1f - motion.rotationProgress(closeProgress)),
                                currentRectF);
                    }

                    super.onUpdate(currentRectF, progress);
                }
            };
            anim.addOnUpdateListener(runner);
            anim.addAnimatorListener(runner);
        } else if (floatingWidget != null) {
            anim.addAnimatorListener(floatingWidget);
            floatingWidget.setOnTargetChangeListener(anim::onTargetPositionChanged);
            floatingWidget.setFastFinishRunnable(anim::end);

            final float floatingWidgetAlpha = isTransluscent ? 0 : 1;
            FloatingWidgetView finalFloatingWidget = floatingWidget;
            SpringAnimRunner runner = new SpringAnimRunner(targets, nonAppTargets, targetRect,
                    closingWindowStartRect, closingWindowOriginalRect, startWindowCornerRadius,
                    0f, motion, motionState, startOpenness, false, anim) {
                @Override
                public void onUpdate(RectF currentRectF, float progress) {
                    final float fallbackBackgroundAlpha =
                            1 - mapBoundToRange(progress, 0.8f, 1, 0, 1, EXAGGERATED_EASE);
                    final float foregroundAlpha =
                            mapBoundToRange(progress, 0.5f, 1, 0, 1, EXAGGERATED_EASE);
                    finalFloatingWidget.update(currentRectF, floatingWidgetAlpha, foregroundAlpha,
                            fallbackBackgroundAlpha, 1 - progress);

                    super.onUpdate(currentRectF, progress);
                }
            };
            anim.addOnUpdateListener(runner);
            anim.addAnimatorListener(runner);
        } else {
            // If no floating icon or widget is present, animate the to the default window
            // target rect.
            SpringAnimRunner runner = new SpringAnimRunner(
                    targets, nonAppTargets, targetRect, closingWindowStartRect, closingWindowOriginalRect,
                    startWindowCornerRadius, landscapeRotation, motion, motionState,
                    startOpenness, true, anim);
            anim.addOnUpdateListener(runner);
            anim.addAnimatorListener(runner);
        }

        // Use a fixed velocity to start the animation.
        animation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                anim.start(mLauncher, mDeviceProfile, startVelocity);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                anim.cancel();
            }
        });
        return anim;
    }

    /**
     * Closing window animator that moves the window down and offscreen.
     */
    private Animator getFallbackClosingWindowAnimators(RemoteAnimationTarget[] appTargets,
            RemoteAnimationTarget[] nonAppTargets) {
        RemoteAnimationTargets targets = new RemoteAnimationTargets(appTargets,
                new RemoteAnimationTarget[0], nonAppTargets, MODE_CLOSING);
        final int rotationChange = getRotationChange(appTargets);
        SurfaceTransactionApplier surfaceApplier = new SurfaceTransactionApplier(mDragLayer);
        Matrix matrix = new Matrix();
        Point tmpPos = new Point();
        Rect tmpRect = new Rect();
        ValueAnimator closingAnimator = ValueAnimator.ofFloat(0, 1);
        int duration = CLOSING_TRANSITION_DURATION_MS;
        float windowCornerRadius = getWindowCornerRadius(mLauncher);
        float startShadowRadius = areAllTargetsTranslucent(appTargets) ? 0 : mMaxShadowRadius;
        closingAnimator.setDuration(duration);
        boolean isFreeform = isFreeformAnimation(appTargets);
        float translateY = isFreeform ? mClosingFreeformWindowTransY : mClosingWindowTransY;
        float endScale = isFreeform ? 0.95f : 1f;
        Interpolator alphaInterpolator = isFreeform
                ? clampToDuration(LINEAR, 0, 100, duration)
                : clampToDuration(LINEAR, 25, 125, duration);
        closingAnimator.addUpdateListener(new MultiValueUpdateListener() {
            FloatProp mDy = new FloatProp(0, translateY, DECELERATE_1_7);
            FloatProp mScale = new FloatProp(1f, endScale, DECELERATE_1_7);
            FloatProp mAlpha = new FloatProp(1f, 0f, alphaInterpolator);
            FloatProp mShadowRadius = new FloatProp(startShadowRadius, 0, DECELERATE_1_7);

            @Override
            public void onUpdate(float percent, boolean initOnly) {
                SurfaceTransaction transaction = new SurfaceTransaction();
                for (int i = appTargets.length - 1; i >= 0; i--) {
                    RemoteAnimationTarget target = appTargets[i];
                    SurfaceProperties builder = SystemBarFollowAnimation.forTarget(
                            transaction, targets, target);

                    if (target.screenSpaceBounds != null) {
                        tmpPos.set(target.screenSpaceBounds.left, target.screenSpaceBounds.top);
                    } else {
                        tmpPos.set(target.position.x, target.position.y);
                    }

                    final Rect crop = new Rect(target.localBounds);
                    crop.offsetTo(0, 0);
                    if (target.mode == MODE_CLOSING) {
                        tmpRect.set(target.screenSpaceBounds);
                        if ((rotationChange % 2) != 0) {
                            final int right = crop.right;
                            crop.right = crop.bottom;
                            crop.bottom = right;
                        }
                        matrix.setScale(mScale.value, mScale.value,
                                tmpRect.centerX(),
                                tmpRect.centerY());
                        matrix.postTranslate(0, mDy.value);
                        matrix.postTranslate(tmpPos.x, tmpPos.y);
                        builder.setMatrix(matrix)
                                .setWindowCrop(crop)
                                .setAlpha(mAlpha.value)
                                .setCornerRadius(windowCornerRadius)
                                .setShadowRadius(mShadowRadius.value);
                    } else if (target.mode == MODE_OPENING) {
                        matrix.setTranslate(tmpPos.x, tmpPos.y);
                        builder.setMatrix(matrix)
                                .setWindowCrop(crop)
                                .setAlpha(1f);
                    }
                }
                surfaceApplier.scheduleApply(transaction);
            }
        });

        return closingAnimator;
    }

    private boolean isFreeformAnimation(RemoteAnimationTarget[] appTargets) {
        return DesktopModeStatus.canEnterDesktopMode(mLauncher.getApplicationContext())
                && DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_EXIT_TRANSITIONS_BUGFIX.isTrue()
                && Arrays.stream(appTargets)
                        .anyMatch(app -> app.taskInfo != null && app.taskInfo.isFreeform());
    }

    private void addCujInstrumentation(Animator anim, int cuj) {
        anim.addListener(getCujAnimationSuccessListener(cuj, /* cujPreStartCallback= */null));
    }

    private void addCujInstrumentation(Animator anim, int cuj, Runnable cujPreStartCallback) {
        anim.addListener(getCujAnimationSuccessListener(cuj, cujPreStartCallback));
    }

    private void addCujInstrumentation(RectFSpringAnim anim, int cuj) {
        anim.addAnimatorListener(
                getCujAnimationSuccessListener(cuj, /* cujPreStartCallback= */null));
    }

    private AnimationSuccessListener getCujAnimationSuccessListener(
            int cuj, Runnable cujPreStartCallback) {
        return new AnimationSuccessListener() {
            @Override
            public void onAnimationStart(Animator animation) {
                mDragLayer.getViewTreeObserver().addOnDrawListener(
                        new ViewTreeObserver.OnDrawListener() {
                            boolean mHandled = false;

                            @Override
                            public void onDraw() {
                                if (mHandled) {
                                    return;
                                }
                                mHandled = true;
                                if (cujPreStartCallback != null) {
                                    cujPreStartCallback.run();
                                }
                                InteractionJankMonitorWrapper.begin(mDragLayer, cuj);

                                mDragLayer.post(() ->
                                        mDragLayer.getViewTreeObserver().removeOnDrawListener(
                                                this));
                            }
                        });
                super.onAnimationStart(animation);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                super.onAnimationCancel(animation);
                InteractionJankMonitorWrapper.cancel(cuj);
            }

            @Override
            public void onAnimationSuccess(Animator animator) {
                InteractionJankMonitorWrapper.end(cuj);
            }
        };
    }

    /**
     * Creates the {@link RectFSpringAnim} and {@link AnimatorSet} required to animate
     * the transition.
     */
    @NonNull
    public BackAnimState createWallpaperOpenAnimations(
            RemoteAnimationTarget[] appTargets,
            RemoteAnimationTarget[] wallpapers,
            RemoteAnimationTarget[] nonAppTargets,
            RectF startRect,
            float startWindowCornerRadius,
            boolean fromPredictiveBack) {
        View launcherView = findLauncherView(appTargets);
        if (launcherView != null
                && launcherView.getTag() instanceof ItemInfo info
                && info.shouldUseBackgroundAnimation()) {
            // Try to create a return animation
            RunnableList onEndCallback = new RunnableList();
            WindowAnimationState windowState = new WindowAnimationState();
            windowState.bounds = startRect;
            windowState.bottomLeftRadius = windowState.bottomRightRadius =
                    windowState.topLeftRadius = windowState.topRightRadius =
                            startWindowCornerRadius;
            ContainerAnimationRunner runner = ContainerAnimationRunner.fromView(
                    launcherView, false /* forLaunch */, mLauncher, mStartingWindowListener,
                    onEndCallback, windowState);
            if (runner != null) {
                runner.startAnimation(TRANSIT_CLOSE,
                        appTargets, wallpapers, nonAppTargets,
                        new IRemoteAnimationFinishedCallback.Stub() {
                            @Override
                            public void onAnimationFinished() {
                                onEndCallback.executeAllAndDestroy();
                            }
                        });
                return new AlreadyStartedBackAnimState(onEndCallback);
            }
        }

        AnimatorSet anim = new AnimatorSet();
        RectFSpringAnim rectFSpringAnim = null;

        final boolean launcherIsForceInvisibleOrOpening = mLauncher.isForceInvisible()
                || launcherIsATargetWithMode(appTargets, MODE_OPENING);

        boolean playFallBackAnimation = mLauncher.getWorkspace().isOverlayShown()
                || shouldPlayFallbackClosingAnimation(appTargets);

        boolean playWorkspaceReveal = true;
        boolean skipAllAppsScale = false;
        if (!playFallBackAnimation) {
            rectFSpringAnim = getClosingWindowAnimators(
                    anim, appTargets, nonAppTargets, launcherView, new PointF(), startRect,
                    startWindowCornerRadius);
            if (mLauncher.isInState(LauncherState.ALL_APPS)) {
                // Skip scaling all apps, otherwise FloatingIconView will get wrong
                // layout bounds.
                skipAllAppsScale = true;
            } else {
                anim.play(
                        new ScalingWorkspaceRevealAnim(mLauncher, rectFSpringAnim,
                                rectFSpringAnim.getTargetRect(),
                                !fromPredictiveBack /* playAlphaReveal */,
                                true /* playBlur */).getAnimators());

                // We play StaggeredWorkspaceAnim as a part of the closing window animation.
                playWorkspaceReveal = false;
            }
        } else {
            anim.play(getFallbackClosingWindowAnimators(appTargets, nonAppTargets));
        }

        AnimatorListenerAdapter endListener = new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                AccessibilityManagerCompat.sendTestProtocolEventToTest(
                        mLauncher, WALLPAPER_OPEN_ANIMATION_FINISHED_MESSAGE);
            }
        };
        if (rectFSpringAnim != null) {
            rectFSpringAnim.addAnimatorListener(endListener);
        } else {
            anim.addListener(endListener);
        }

        // Normally, we run the launcher content animation when we are transitioning
        // home, but if home is already visible, then we don't want to animate the
        // contents of launcher unless we know that we are animating home as a result
        // of the home button press with quickstep, which will result in launcher being
        // started on touch down, prior to the animation home (and won't be in the
        // targets list because it is already visible). In that case, we force
        // invisibility on touch down, and only reset it after the animation to home
        // is initialized.
        if (launcherIsForceInvisibleOrOpening) {
            if (rectFSpringAnim != null && anim.getChildAnimations().isEmpty()) {
                addCujInstrumentation(rectFSpringAnim, Cuj.CUJ_LAUNCHER_APP_CLOSE_TO_HOME);
            } else {
                if (isFreeformAnimation(appTargets)) {
                    addCujInstrumentation(
                            anim,
                            Cuj.CUJ_DESKTOP_MODE_EXIT_MODE_ON_LAST_WINDOW_CLOSE,
                            /* cujPreStartCallback= */ () -> {
                                mLatencyTracker.onActionEnd(
                                        ACTION_DESKTOP_MODE_EXIT_MODE_ON_LAST_WINDOW_CLOSE);
                            });
                }
                addCujInstrumentation(anim, playFallBackAnimation
                        ? Cuj.CUJ_LAUNCHER_APP_CLOSE_TO_HOME_FALLBACK
                        : Cuj.CUJ_LAUNCHER_APP_CLOSE_TO_HOME);
            }

            // Only register the content animation for cancellation when state changes
            mLauncher.getStateManager().setCurrentAnimation(anim);

            if (mLauncher.isInState(LauncherState.ALL_APPS)) {
                Pair<AnimatorSet, Runnable> contentAnimator =
                        getLauncherContentAnimator(false, LAUNCHER_RESUME_START_DELAY,
                                skipAllAppsScale);
                anim.play(contentAnimator.first);
                anim.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        contentAnimator.second.run();
                    }
                });
            } else if (playWorkspaceReveal) {
                anim.play(new WorkspaceRevealAnim(mLauncher, false).getAnimators());
            }
        }

        return new AnimatorBackState(rectFSpringAnim, anim);
    }

    /** Get animation duration for taskbar for going to home. */
    public static int getTaskbarToHomeDuration(
            boolean isPersistentTaskbarAndNotInDesktopMode) {
        return getTaskbarToHomeDuration(false, isPersistentTaskbarAndNotInDesktopMode);
    }

    /**
     * Get animation duration for taskbar for going to home.
     *
     * @param shouldOverrideToFastAnimation should overwrite scaling reveal home animation duration
     */
    public static int getTaskbarToHomeDuration(boolean shouldOverrideToFastAnimation,
            boolean isPersistentTaskbarAndNotInDesktopMode) {
        if (isPersistentTaskbarAndNotInDesktopMode) {
            return PINNED_TASKBAR_TRANSITION_DURATION;
        } else if (!shouldOverrideToFastAnimation) {
            return TASKBAR_TO_HOME_DURATION_SLOW;
        } else {
            return TASKBAR_TO_HOME_DURATION_FAST;
        }
    }

    /**
     * Remote animation runner for animation from the app to Launcher, including recents.
     */
    protected class WallpaperOpenLauncherAnimationRunner implements RemoteAnimationFactory {

        @Override
        public void onAnimationStart(int transit,
                RemoteAnimationTarget[] appTargets,
                RemoteAnimationTarget[] wallpaperTargets,
                RemoteAnimationTarget[] nonAppTargets,
                LauncherAnimationRunner.AnimationResult result) {
            if (mLauncher.isDestroyed()) {
                AnimatorSet anim = new AnimatorSet();
                anim.play(getFallbackClosingWindowAnimators(appTargets, nonAppTargets));
                result.setAnimation(anim, mLauncher.getApplicationContext());
                return;
            }

            if (mLauncher.hasSomeInvisibleFlag(PENDING_INVISIBLE_BY_WALLPAPER_ANIMATION)) {
                mLauncher.addForceInvisibleFlag(INVISIBLE_BY_PENDING_FLAGS);
                mLauncher.getStateManager().moveToRestState();
            }

            // Start from the closing task, not the opening launcher's already-rotated bounds.
            final RectF resolveRectF = new RectF(0, 0,
                    mDeviceProfile.getDeviceProperties().getWidthPx(),
                    mDeviceProfile.getDeviceProperties().getHeightPx());
            for (RemoteAnimationTarget t : appTargets) {
                if (t.mode == MODE_CLOSING) {
                    transferRectToTargetCoordinate(
                            t, new RectF(t.screenSpaceBounds), true, resolveRectF);
                    break;
                }
            }

            BackAnimState bankAnimState = createWallpaperOpenAnimations(
                    appTargets, wallpaperTargets, nonAppTargets, resolveRectF,
                    QuickStepContract.getWindowCornerRadius(mLauncher),
                    false /* fromPredictiveBack */);

            TaskViewUtils.createSplitAuxiliarySurfacesAnimator(nonAppTargets, false, null);
            mLauncher.clearForceInvisibleFlag(INVISIBLE_ALL);
            bankAnimState.applyToAnimationResult(result, mLauncher);
        }
    }

    /**
     * Remote animation runner for animation to launch an app.
     */
    private class AppLaunchAnimationRunner implements RemoteAnimationFactory {

        private final View mV;
        private final RunnableList mOnEndCallback;

        AppLaunchAnimationRunner(View v, RunnableList onEndCallback) {
            mV = v;
            mOnEndCallback = onEndCallback;
        }

        @Override
        public void onAnimationStart(int transit,
                RemoteAnimationTarget[] appTargets,
                RemoteAnimationTarget[] wallpaperTargets,
                RemoteAnimationTarget[] nonAppTargets,
                LauncherAnimationRunner.AnimationResult result) {
            AnimatorSet anim = new AnimatorSet();
            boolean launcherClosing =
                    launcherIsATargetWithMode(appTargets, MODE_CLOSING);

            final boolean launchingFromWidget = mV instanceof LauncherAppWidgetHostView;
            final boolean launchingFromRecents = isLaunchingFromRecents(mV, appTargets);
            final boolean skipFirstFrame;
            if (launchingFromWidget) {
                composeWidgetLaunchAnimator(anim, (LauncherAppWidgetHostView) mV, appTargets,
                        wallpaperTargets, nonAppTargets, launcherClosing);
                addCujInstrumentation(anim, Cuj.CUJ_LAUNCHER_APP_LAUNCH_FROM_WIDGET);
                skipFirstFrame = true;
            } else if (launchingFromRecents) {
                composeRecentsLaunchAnimator(anim, mV, appTargets, wallpaperTargets, nonAppTargets,
                        launcherClosing);
                addCujInstrumentation(
                        anim, Cuj.CUJ_LAUNCHER_APP_LAUNCH_FROM_RECENTS);
                skipFirstFrame = true;
            } else {
                composeIconLaunchAnimator(anim, mV, appTargets, wallpaperTargets, nonAppTargets,
                        launcherClosing, result);
                addCujInstrumentation(anim, Cuj.CUJ_LAUNCHER_APP_LAUNCH_FROM_ICON);
                skipFirstFrame = false;
            }

            if (launcherClosing) {
                anim.addListener(mForceInvisibleListener);
            }

            // Syncs the app launch animation and taskbar stash animation (if exists).
            if (syncAppLaunchWithTaskbarStash()) {
                TaskbarInteractor taskbarInteractor = mLauncher.getTaskbarInteractor();
                if (taskbarInteractor != null) {
                    taskbarInteractor.setIgnoreInAppFlagForSync(false);

                    if (launcherClosing) {
                        taskbarInteractor.createAnimToAppAndPlay(anim);
                    }
                }
            }

            result.setAnimation(anim, mLauncher, mOnEndCallback::executeAllAndDestroy,
                    skipFirstFrame);
            AppWindowAnimationState.dispatchPendingGestures();
        }

        @Override
        public void onAnimationCancelled() {
            mOnEndCallback.executeAllAndDestroy();
        }
    }

    /** Remote animation runner to launch an app using System UI's animation library. */
    private static class ContainerAnimationRunner implements RemoteAnimationFactory {

        /** The delegate runner that handles the actual animation. */
        private final RemoteAnimationDelegate<IRemoteAnimationFinishedCallback> mDelegate;

        private ContainerAnimationRunner(
                RemoteAnimationDelegate<IRemoteAnimationFinishedCallback> delegate) {
            mDelegate = delegate;
        }

        @Nullable
        static ContainerAnimationRunner fromView(
                View v,
                boolean forLaunch,
                Launcher launcher,
                StartingWindowListener startingWindowListener,
                RunnableList onEndCallback,
                @Nullable WindowAnimationState windowState) {
            // First the controller is created. This is used by the runner to animate the
            // origin/target view.
            ActivityTransitionAnimator.Controller controller =
                    buildController(v, forLaunch, windowState);
            if (controller == null) {
                return null;
            }

            // The callback is used to make sure that we use the right color to fade between view
            // and the window.
            ActivityTransitionAnimator.Callback callback = task -> {
                final int backgroundColor =
                        startingWindowListener.mBackgroundColor == Color.TRANSPARENT
                                ? launcher.getScrimView().getBackgroundColor()
                                : startingWindowListener.mBackgroundColor;
                return ColorUtils.setAlphaComponent(backgroundColor, 255);
            };

            ActivityTransitionAnimator.Listener listener =
                    new ActivityTransitionAnimator.Listener() {
                        @Override
                        public void onTransitionAnimationEnd() {
                            onEndCallback.executeAllAndDestroy();
                        }
                    };

            return new ContainerAnimationRunner(
                    new ActivityTransitionAnimator.LegacyAnimationDelegate(
                            MAIN_EXECUTOR, controller, callback, listener));
        }

        /**
         * Constructs a {@link ActivityTransitionAnimator.Controller} that can be used by a
         * {@link ContainerAnimationRunner} to animate a view into an opening window or from a
         * closing one.
         */
        @Nullable
        private static ActivityTransitionAnimator.Controller buildController(
                View v, boolean isLaunching, @Nullable WindowAnimationState windowState) {
            View viewToUse = findLaunchableViewWithBackground(v);
            if (viewToUse == null) {
                return null;
            }

            // The CUJ is logged by the click handler, so we don't log it inside the animation
            // library. TODO: figure out return CUJ.
            ActivityTransitionAnimator.Controller controllerDelegate =
                    ActivityTransitionAnimator.Controller.fromView(viewToUse, null /* cujType */);

            if (controllerDelegate == null) {
                return null;
            }

            // This wrapper allows us to override the default value, telling the controller that the
            // current window is below the animating window as well as information about the return
            // animation.
            return new DelegateTransitionAnimatorController(controllerDelegate) {
                @Override
                public boolean isLaunching() {
                    return isLaunching;
                }

                @Override
                public boolean isBelowAnimatingWindow() {
                    return true;
                }

                @Nullable
                @Override
                public WindowAnimationState getWindowAnimatorState() {
                    return windowState;
                }
            };
        }

        /**
         * Finds the closest parent of [view] (inclusive) that implements {@link LaunchableView} and
         * has a background drawable.
         */
        @Nullable
        private static <T extends View & LaunchableView> T findLaunchableViewWithBackground(
                View view) {
            View current = view;
            while (current.getBackground() == null || !(current instanceof LaunchableView)) {
                if (current.getParent() instanceof View v) {
                    current = v;
                } else {
                    return null;
                }
            }
            return (T) current;
        }

        @Override
        public void onAnimationStart(int transit, RemoteAnimationTarget[] appTargets,
                RemoteAnimationTarget[] wallpaperTargets, RemoteAnimationTarget[] nonAppTargets,
                LauncherAnimationRunner.AnimationResult result) {
            startAnimation(
                    transit, appTargets, wallpaperTargets, nonAppTargets, result);
        }

        public void startAnimation(int transit, RemoteAnimationTarget[] appTargets,
                RemoteAnimationTarget[] wallpaperTargets, RemoteAnimationTarget[] nonAppTargets,
                IRemoteAnimationFinishedCallback result) {
            mDelegate.onAnimationStart(
                    transit, appTargets, wallpaperTargets, nonAppTargets, result);
        }

        @Override
        public void onAnimationCancelled() {
            mDelegate.onAnimationCancelled();
        }
    }

    /**
     * Class that holds all the variables for the app open animation.
     */
    static class AnimOpenProperties {

        public final int cropCenterXStart;
        public final int cropCenterYStart;
        public final int cropWidthStart;
        public final int cropHeightStart;

        public final int cropCenterXEnd;
        public final int cropCenterYEnd;
        public final int cropWidthEnd;
        public final int cropHeightEnd;

        public final float dX;
        public final float dY;

        public final float initialAppIconScale;
        public final float finalAppIconScale;

        public final float iconAlphaStart;

        AnimOpenProperties(Resources r, DeviceProfile dp, Rect windowTargetBounds,
                RectF launcherIconBounds, View view, int dragLayerLeft, int dragLayerTop,
                boolean hasSplashScreen, boolean hasDifferentAppIcon) {
            // Scale the app icon to take up the entire screen. This simplifies the math when
            // animating the app window position / scale.
            float smallestSize = Math.min(windowTargetBounds.height(), windowTargetBounds.width());
            float maxScaleX = smallestSize / launcherIconBounds.width();
            float maxScaleY = smallestSize / launcherIconBounds.height();
            float iconStartScale = 1f;
            if (view instanceof BubbleTextView && !(view.getParent() instanceof DeepShortcutView)) {
                Drawable dr = ((BubbleTextView) view).getIcon();
                if (dr instanceof FastBitmapDrawable) {
                    iconStartScale = ((FastBitmapDrawable) dr).getAnimatedScale();
                }
            }

            initialAppIconScale = iconStartScale;
            finalAppIconScale = Math.max(maxScaleX, maxScaleY);

            // Animate the app icon to the center of the window bounds in screen coordinates.
            float centerX = windowTargetBounds.centerX() - dragLayerLeft;
            float centerY = windowTargetBounds.centerY() - dragLayerTop;

            dX = centerX - launcherIconBounds.centerX();
            dY = centerY - launcherIconBounds.centerY();

            iconAlphaStart = hasSplashScreen && !hasDifferentAppIcon ? 0 : 1f;

            final int windowIconSize = ResourceUtils.getDimenByName("starting_surface_icon_size",
                    r, 108);

            cropCenterXStart = windowTargetBounds.centerX();
            cropCenterYStart = windowTargetBounds.centerY();

            cropWidthStart = windowIconSize;
            cropHeightStart = windowIconSize;

            cropWidthEnd = windowTargetBounds.width();
            cropHeightEnd = windowTargetBounds.height();

            cropCenterXEnd = windowTargetBounds.centerX();
            cropCenterYEnd = windowTargetBounds.centerY();
        }
    }

    private static class StartingWindowListener extends IStartingWindowListener.Stub {
        private final WeakReference<QuickstepTransitionManager> mTransitionManagerRef;
        private int mBackgroundColor;

        private StartingWindowListener(QuickstepTransitionManager transitionManager) {
            mTransitionManagerRef = new WeakReference<>(transitionManager);
        }

        @Override
        public void onTaskLaunching(int taskId, int supportedType, int color) {
            QuickstepTransitionManager transitionManager = mTransitionManagerRef.get();
            if (transitionManager != null) {
                transitionManager.mTaskStartParams.put(taskId, Pair.create(supportedType, color));
            }
            mBackgroundColor = color;
        }
    }

    /**
     * Transfer the rectangle to another coordinate if needed.
     *
     * @param toLauncher which one is the anchor of this transfer, if true then transfer from
     *                   animation target to launcher, false transfer from launcher to animation
     *                   target.
     */
    public void transferRectToTargetCoordinate(RemoteAnimationTarget target, RectF currentRect,
            boolean toLauncher, RectF resultRect) {
        mCoordinateTransfer.transferRectToTargetCoordinate(
                target, currentRect, toLauncher, resultRect);
    }

    private static class RemoteAnimationCoordinateTransfer {
        private final QuickstepLauncher mLauncher;
        private final Rect mDisplayRect = new Rect();
        private final Rect mTmpResult = new Rect();

        RemoteAnimationCoordinateTransfer(QuickstepLauncher launcher) {
            mLauncher = launcher;
        }

        void transferRectToTargetCoordinate(RemoteAnimationTarget target, RectF currentRect,
                boolean toLauncher, RectF resultRect) {
            final int taskRotation = target.windowConfiguration.getRotation();
            final DeviceProfile profile = mLauncher.getDeviceProfile();
            final int rotation = profile.getDeviceProperties().getRotationHint();
            final int widthPx = profile.getDeviceProperties().getWidthPx();
            final int heightPx = profile.getDeviceProperties().getHeightPx();

            final int rotationDelta = toLauncher
                    ? android.util.RotationUtils.deltaRotation(taskRotation, rotation)
                    : android.util.RotationUtils.deltaRotation(rotation, taskRotation);
            if (rotationDelta != ROTATION_0) {
                // Get original display size when task is on top but with different rotation
                if (rotationDelta % 2 != 0 && toLauncher && (rotation == ROTATION_0
                        || rotation == ROTATION_180)) {
                    mDisplayRect.set(0, 0, heightPx, widthPx);
                } else {
                    mDisplayRect.set(0, 0, widthPx, heightPx);
                }
                currentRect.round(mTmpResult);
                android.util.RotationUtils.rotateBounds(mTmpResult, mDisplayRect, rotationDelta);
                resultRect.set(mTmpResult);
            } else {
                resultRect.set(currentRect);
            }
        }
    }

    /**
     * RectFSpringAnim update listener to be used for app to home animation.
     */
    private class SpringAnimRunner extends AnimatorListenerAdapter
            implements RectFSpringAnim.OnUpdateListener {
        private final RemoteAnimationTarget[] mAppTargets;
        private final RemoteAnimationTargets mTargets;
        private final Matrix mMatrix = new Matrix();
        private final Point mTmpPos = new Point();
        private final RectF mCurrentRectF = new RectF();
        private final float mStartRadius;
        private final float mEndRadius;
        private final SurfaceTransactionApplier mSurfaceApplier;
        private final Rect mWindowStartBounds = new Rect();
        private final Rect mWindowOriginalBounds = new Rect();
        private final float mLandscapeRotation;
        private final float mVerticalCropAnchor;
        private final DesktopAnimationSettings mMotion;
        private final AppWindowAnimationState.Session mMotionState;
        private final float mStartOpenness;
        private final boolean mUseMotion;
        private final RectFSpringAnim mWindowAnimation;
        private final @Nullable WindowAnimationSnapshot mSnapshot;
        private final @Nullable IosWindowDeformation mDeformation;
        private final @Nullable RemoteAnimationTarget mSnapshotTarget;
        private final Matrix mWindowToHome = new Matrix();
        private final Matrix mSnapshotMatrix = new Matrix();

        private final Rect mTmpRect = new Rect();

        /**
         * Constructor for SpringAnimRunner
         *
         * @param appTargets                the list of opening/closing apps
         * @param targetRect                target rectangle
         * @param closingWindowStartRect    start position of the window when the spring animation
         *                                  is started. In the predictive back to home case this
         *                                  will be smaller than closingWindowOriginalRect because
         *                                  the window is already scaled by the user gesture
         * @param closingWindowOriginalRect Original unscaled window rect
         * @param startWindowCornerRadius   corner radius of window at the start position
         */
        SpringAnimRunner(RemoteAnimationTarget[] appTargets, RemoteAnimationTarget[] nonAppTargets,
                RectF targetRect,
                Rect closingWindowStartRect, Rect closingWindowOriginalRect,
                float startWindowCornerRadius, float landscapeRotation,
                DesktopAnimationSettings motion, AppWindowAnimationState.Session motionState,
                float startOpenness, boolean useMotion, RectFSpringAnim windowAnimation) {
            mAppTargets = appTargets;
            mTargets = new RemoteAnimationTargets(appTargets, new RemoteAnimationTarget[0],
                    nonAppTargets, MODE_CLOSING);
            mMotion = motion;
            mMotionState = motionState;
            mStartOpenness = startOpenness;
            mUseMotion = useMotion;
            mWindowAnimation = windowAnimation;
            mLandscapeRotation = landscapeRotation;
            mStartRadius = startWindowCornerRadius;
            mEndRadius = Math.max(1, targetRect.width()) / 2f;
            mSurfaceApplier = new SurfaceTransactionApplier(mDragLayer);
            mWindowStartBounds.set(closingWindowStartRect);
            mWindowOriginalBounds.set(closingWindowOriginalRect);

            // transfer the coordinate based on animation target.
            RemoteAnimationTarget closingTarget = null;
            if (mAppTargets != null) {
                for (RemoteAnimationTarget t : mAppTargets) {
                    if (t.mode == MODE_CLOSING) {
                        closingTarget = t;
                        final RectF transferRect = new RectF(mWindowStartBounds);
                        final RectF result = new RectF();
                        transferRectToTargetCoordinate(t, transferRect, false, result);
                        result.round(mWindowStartBounds);

                        transferRect.set(closingWindowOriginalRect);
                        transferRectToTargetCoordinate(t, transferRect, false, result);
                        result.round(mWindowOriginalBounds);
                        break;
                    }
                }
            }
            mSnapshotTarget = closingTarget;
            final float previousCropAnchor = motionState.previous == null ? Float.NaN
                    : motionState.previous.verticalCropAnchor;
            mVerticalCropAnchor = useMotion && closingTarget != null
                    && !closingTarget.isTranslucent
                    && closingTarget.windowConfiguration.getWindowingMode()
                            == android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
                    && closingTarget.rotationChange == 0 && landscapeRotation == 0f
                    && mWindowOriginalBounds.height() > mWindowOriginalBounds.width()
                    && (SystemBarFollowAnimation.getSurface(mTargets, closingTarget) != null
                            || Float.isFinite(previousCropAnchor))
                    ? (Float.isFinite(previousCropAnchor) ? previousCropAnchor : 0f)
                    : Float.NaN;
            if (useMotion && closingTarget != null && closingTarget.taskId >= 0
                    && !closingTarget.isTranslucent) {
                int homeRotation = mDeviceProfile.getDeviceProperties().getRotationHint();
                int delta = android.util.RotationUtils.deltaRotation(
                        closingTarget.windowConfiguration.getRotation(), homeRotation);
                int width = mDeviceProfile.getDeviceProperties().getWidthPx();
                int height = mDeviceProfile.getDeviceProperties().getHeightPx();
                if ((delta & 1) != 0 && (homeRotation == ROTATION_0
                        || homeRotation == ROTATION_180)) {
                    int tmp = width;
                    width = height;
                    height = tmp;
                }
                mWindowToHome.setRotate(-90f * delta);
                if (delta == 1) mWindowToHome.postTranslate(0f, width);
                else if (delta == 2) mWindowToHome.postTranslate(width, height);
                else if (delta == 3) mWindowToHome.postTranslate(height, 0f);
                mSnapshot = new WindowAnimationSnapshot(mDragLayer, closingTarget.leash,
                        new Rect(0, 0, mWindowOriginalBounds.width(),
                                mWindowOriginalBounds.height()), motionState,
                        SystemBarFollowAnimation.getSurface(mTargets, closingTarget));
                windowAnimation.setOnCancelContinuation(() -> mSnapshot.continueAnimation(
                        windowAnimation, (rect, progress) -> updateWindow(rect, progress, true)));
            } else {
                mSnapshot = null;
            }
            mDeformation = motion.isIos && mSnapshot != null && landscapeRotation == 0f
                    && closingTarget.rotationChange == 0
                    ? new IosWindowDeformation(mDragLayer, closingTarget.leash,
                            new Rect(0, 0, mWindowOriginalBounds.width(),
                                    mWindowOriginalBounds.height()), motion.homeDuration) : null;
        }

        public float getCornerRadius(float progress) {
            return Utilities.mapRange(mUseMotion ? mMotion.cornerProgress(progress) : progress,
                    mStartRadius, mEndRadius);
        }

        @Override
        public void onUpdate(RectF currentRectF, float progress) {
            updateWindow(currentRectF, progress, false);
        }

        private void updateWindow(RectF currentRectF, float progress, boolean snapshotOnly) {
            if (!snapshotOnly && !mMotionState.isActive()) return;
            final float landscapeProgress = LandscapeAppAnimation.boundProgress(progress);
            final float closeProgress = 1f - mStartOpenness * (1f - landscapeProgress);
            if (!snapshotOnly) {
                mMotionState.record(currentRectF, 1f - closeProgress, getCornerRadius(progress),
                        mVerticalCropAnchor);
                mLauncher.getDepthController().setAppTransitionProgress(this,
                        mWindowAnimation.getTimelineProgress());
            }
            SurfaceTransaction transaction = snapshotOnly ? null : new SurfaceTransaction();
            for (int i = mAppTargets.length - 1; i >= 0; i--) {
                RemoteAnimationTarget target = mAppTargets[i];
                if (snapshotOnly && target != mSnapshotTarget) continue;
                SurfaceProperties builder = snapshotOnly ? null
                        : SystemBarFollowAnimation.forTarget(transaction, mTargets, target);

                if (target.localBounds != null) {
                    mTmpPos.set(target.localBounds.left, target.localBounds.top);
                } else {
                    mTmpPos.set(target.position.x, target.position.y);
                }

                if (target.mode == MODE_CLOSING) {
                    transferRectToTargetCoordinate(target, currentRectF, false, mCurrentRectF);

                    // Scale the target window to match the currentRectF.
                    float scale = Math.max(mCurrentRectF.width()
                                    / Math.max(1, mWindowOriginalBounds.width()),
                            mCurrentRectF.height() / Math.max(1, mWindowOriginalBounds.height()));
                    final float halfWidth = mCurrentRectF.width() / scale / 2f;
                    final float halfHeight = mCurrentRectF.height() / scale / 2f;
                    final float centerX = mWindowOriginalBounds.width() / 2f;
                    final float centerY = mWindowOriginalBounds.height() / 2f;
                    mTmpRect.set(Math.round(centerX - halfWidth), Math.round(centerY - halfHeight),
                            Math.round(centerX + halfWidth), Math.round(centerY + halfHeight));

                    // Match size and position of currentRect.
                    mMatrix.setTranslate(-centerX, -centerY);
                    mMatrix.postScale(scale, scale);
                    mMatrix.postTranslate(mCurrentRectF.centerX(), mCurrentRectF.centerY());
                    if (!mUseMotion) {
                        // Widget backgrounds retain their existing edge-anchored crop.
                        boolean portrait = mWindowStartBounds.height() > mWindowStartBounds.width();
                        scale = Math.min(1f, portrait
                                ? mCurrentRectF.width() / mWindowOriginalBounds.width()
                                : mCurrentRectF.height() / mWindowOriginalBounds.height());
                        mTmpRect.set(0, 0, portrait ? mWindowOriginalBounds.width()
                                        : (int) (mCurrentRectF.width() / scale),
                                portrait ? (int) (mCurrentRectF.height() / scale)
                                        : mWindowOriginalBounds.height());
                        mMatrix.setScale(scale, scale);
                        mMatrix.postTranslate(mCurrentRectF.left, mCurrentRectF.top);
                    }
                    if (mLandscapeRotation != 0f) {
                        mMatrix.postRotate(mLandscapeRotation
                                        * mMotion.rotationProgress(closeProgress),
                                mCurrentRectF.centerX(), mCurrentRectF.centerY());
                    }
                    AppWindowAnimationState.applyVerticalCropAnchor(mMatrix, mTmpRect,
                            mWindowOriginalBounds.height(), mVerticalCropAnchor);

                    if (snapshotOnly) {
                        mSnapshotMatrix.setConcat(mWindowToHome, mMatrix);
                        mSnapshot.update(mSnapshotMatrix, mTmpRect,
                                getCornerRadius(progress) / scale, getWindowAlpha(closeProgress),
                                currentRectF, 1f - closeProgress, getCornerRadius(progress),
                                mVerticalCropAnchor);
                    } else {
                        float deformationAlpha = 0f;
                        if (mDeformation != null) {
                            mSnapshotMatrix.setConcat(mWindowToHome, mMatrix);
                            deformationAlpha = mDeformation.update(mSnapshotMatrix, mTmpRect,
                                    getCornerRadius(progress) / scale,
                                    getWindowAlpha(closeProgress), currentRectF,
                                    mWindowAnimation.getTimelineProgress(),
                                    1f - closeProgress, false);
                        }
                        builder.setMatrix(mMatrix)
                                .setWindowCrop(mTmpRect)
                                .setAlpha(getWindowAlpha(closeProgress) * (1f - deformationAlpha))
                                .setCornerRadius(getCornerRadius(progress) / scale);
                    }
                } else if (target.mode == MODE_OPENING) {
                    mMatrix.setTranslate(mTmpPos.x, mTmpPos.y);
                    builder.setMatrix(mMatrix)
                            .setAlpha(1f);
                }
            }
            if (!snapshotOnly) mSurfaceApplier.scheduleApply(transaction);
        }

        @Override
        public void onAnimationStart(Animator animation) {
            mLauncher.getDepthController().beginAppTransition(this, false);
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            mMotionState.finish();
            mLauncher.getDepthController().endAppTransition(this);
            if (mSnapshot != null) mSnapshot.onSourceFinished();
            if (mDeformation != null) mDeformation.close();
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            if (mDeformation != null) mDeformation.close();
            mMotionState.cancel();
        }

        @Override
        public void onCancel() {
            if (mDeformation != null) mDeformation.close();
            mMotionState.cancel();
            mLauncher.getDepthController().endAppTransition(this);
        }

        protected float getWindowAlpha(float progress) {
            if (mUseMotion) return mMotion.windowAlpha(progress);
            if (progress <= 0f) return 1f;
            if (progress >= 0.85f) return 0f;
            return Utilities.mapToRange(progress, 0f, 0.85f, 1f, 0f, ACCELERATE_1_5);
        }
    }
}
