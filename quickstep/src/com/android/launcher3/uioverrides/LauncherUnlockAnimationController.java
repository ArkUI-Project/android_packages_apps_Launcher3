/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.uioverrides;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.MultiTranslateDelegate.INDEX_UNLOCK_ANIM;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.os.Looper;
import android.os.RemoteException;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;

import com.android.launcher3.CellLayout;
import com.android.launcher3.Reorderable;
import com.android.launcher3.Workspace;
import com.android.launcher3.util.TouchController;
import com.android.launcher3.widget.NavigableAppWidgetHostView;
import com.android.quickstep.SystemUiProxy;
import com.android.systemui.shared.system.smartspace.ILauncherUnlockAnimationController;
import com.android.systemui.shared.system.smartspace.SmartspaceState;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Flies workspace rows into place from the top down after SystemUI authorizes an unlock. */
final class LauncherUnlockAnimationController extends ILauncherUnlockAnimationController.Stub
        implements TouchController {
    private static final PathInterpolator EASE = new PathInterpolator(.22f, 1f, .36f, 1f);
    private final QuickstepLauncher mLauncher;
    private final SystemUiProxy mSystemUiProxy;
    private final IdentityHashMap<View, Item> mItems = new IdentityHashMap<>();
    private final ArrayList<ClipState> mClips = new ArrayList<>();
    private ValueAnimator mAnimator;
    private float mProgress = 1f;
    private boolean mActive;
    private boolean mDestroyed;
    private final Runnable mRestoreTimeout = this::finish;
    private final Runnable mOnSystemUiStateChanged = this::onSystemUiStateChanged;
    private final ViewTreeObserver.OnPreDrawListener mOnPreDraw = () -> {
        if (mActive) collectItems();
        return true;
    };

    LauncherUnlockAnimationController(QuickstepLauncher launcher) {
        mLauncher = launcher;
        mSystemUiProxy = SystemUiProxy.INSTANCE.get(launcher);
        mSystemUiProxy.addOnStateChangeListener(mOnSystemUiStateChanged);
    }

    private void onSystemUiStateChanged() {
        if (!mSystemUiProxy.isActive()) finish();
    }

    @Override
    public void prepareForUnlock(boolean animateSmartspace, Rect bounds, int selectedPage)
            throws RemoteException {
        // This AIDL call is synchronous: hide the content before SystemUI exposes our window.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            prepare();
            return;
        }
        Future<?> preparation = MAIN_EXECUTOR.submit(this::prepare);
        try {
            preparation.get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            preparation.cancel(false);
            MAIN_EXECUTOR.execute(this::finish);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RemoteException("Unable to prepare Launcher unlock animation");
        }
    }

    private void prepare() {
        if (mDestroyed) return;
        finish();
        mActive = true;
        mProgress = 0f;
        collectItems();
        mLauncher.getDragLayer().getViewTreeObserver().addOnPreDrawListener(mOnPreDraw);
        // Recover if SystemUI prepares us but never starts updating the gesture or animation.
        MAIN_EXECUTOR.getHandler().postDelayed(mRestoreTimeout, 2000);
    }

    @Override
    public void setUnlockAmount(float amount, boolean forceIfAnimating) {
        MAIN_EXECUTOR.execute(() -> {
            if (mDestroyed || (mAnimator != null && !forceIfAnimating)) return;
            if (amount >= 1f) {
                finish();
                return;
            }
            if (!mActive) prepare();
            // A finger can remain still indefinitely. Never time out a live swipe preview.
            MAIN_EXECUTOR.getHandler().removeCallbacks(mRestoreTimeout);
            cancelAnimator();
            applyProgress(amount);
        });
    }

    @Override
    public void playUnlockAnimation(boolean unlocked, long duration, long startDelay) {
        MAIN_EXECUTOR.execute(() -> {
            if (mDestroyed) return;
            if (!mActive) prepare();
            cancelAnimator();
            MAIN_EXECUTOR.getHandler().removeCallbacks(mRestoreTimeout);
            ValueAnimator animator = ValueAnimator.ofFloat(mProgress, unlocked ? 1f : 0f);
            mAnimator = animator;
            animator.setDuration(Math.max(250, Math.min(1000, duration)));
            animator.setStartDelay(Math.max(0, Math.min(300, startDelay)));
            animator.setInterpolator(input -> input);
            animator.addUpdateListener(value -> applyProgress((float) value.getAnimatedValue()));
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (mAnimator != animation) return;
                    mAnimator = null;
                    finish();
                }
            });
            animator.start();
        });
    }

    private void applyProgress(float progress) {
        mProgress = Math.max(0f, Math.min(1f, progress));
        collectItems();
        for (Item item : mItems.values()) item.apply(mProgress);
    }

    private void collectItems() {
        Workspace<?> workspace = mLauncher.getWorkspace();
        collectPage(workspace.getPageAt(workspace.getCurrentPage()));
        if (workspace.getNextPage() != workspace.getCurrentPage()) {
            collectPage(workspace.getPageAt(workspace.getNextPage()));
        }
        collectPage(mLauncher.getHotseat());
    }

    private void collectPage(View page) {
        if (!(page instanceof CellLayout cell)) return;
        ViewGroup children = cell.getShortcutsAndWidgets();
        if (mClips.stream().noneMatch(clip -> clip.group == cell)) {
            mClips.add(new ClipState(cell));
            mClips.add(new ClipState(children));
        }
        for (int i = 0; i < children.getChildCount(); i++) {
            View view = children.getChildAt(i);
            if (view.getVisibility() != View.VISIBLE || view.getWidth() == 0
                    || mItems.containsKey(view)) continue;
            Item item = new Item(view);
            mItems.put(view, item);
            item.apply(mProgress);
        }
    }

    private void cancelAnimator() {
        ValueAnimator animator = mAnimator;
        mAnimator = null;
        if (animator != null) animator.cancel();
    }

    void finish() {
        cancelAnimator();
        MAIN_EXECUTOR.getHandler().removeCallbacks(mRestoreTimeout);
        if (!mActive) return;
        mActive = false;
        mProgress = 1f;
        ViewTreeObserver observer = mLauncher.getDragLayer().getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnPreDrawListener(mOnPreDraw);
        for (Item item : mItems.values()) item.restore();
        mItems.clear();
        for (ClipState clip : mClips) clip.restore();
        mClips.clear();
    }

    void destroy() {
        mDestroyed = true;
        finish();
        mSystemUiProxy.removeOnStateChangeListener(mOnSystemUiStateChanged);
        mSystemUiProxy.clearLauncherUnlockAnimationController(this);
    }

    @Override
    public boolean onControllerInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) finish();
        return false;
    }

    @Override
    public boolean onControllerTouchEvent(MotionEvent event) { return false; }

    @Override
    public void setSmartspaceSelectedPage(int selectedPage) { }

    @Override
    public void setSmartspaceVisibility(int visibility) { }

    @Override
    public void dispatchSmartspaceStateToSysui() {
        MAIN_EXECUTOR.execute(() -> {
            if (!mDestroyed) {
                // This workspace has no shared lockscreen Smartspace element.
                mSystemUiProxy.notifySysuiSmartspaceStateUpdated(new SmartspaceState());
            }
        });
    }

    private final class Item {
        final View view;
        final Reorderable reorderable;
        final float alpha, scaleX, scaleY, translationX, translationY;
        final float delay, offsetX, offsetY, enlargement;

        Item(View target) {
            view = target;
            reorderable = target instanceof Reorderable item ? item : null;
            alpha = target.getAlpha();
            scaleX = reorderable == null ? target.getScaleX() : reorderable.getReorderBounceScale();
            scaleY = reorderable == null ? target.getScaleY() : scaleX;
            translationX = target.getTranslationX();
            translationY = target.getTranslationY();
            int[] location = new int[2];
            target.getLocationOnScreen(location);
            float width = Math.max(1, mLauncher.getDragLayer().getWidth());
            float height = Math.max(1, mLauncher.getDragLayer().getHeight());
            float centerX = location[0] + target.getWidth() / 2f;
            float centerY = location[1] + target.getHeight() / 2f;
            delay = Math.max(0f, Math.min(.24f, centerY / height * .24f));
            // Position is the dominant motion: lower rows travel farther upward, and side
            // columns converge into their own slots. Scale only gives a little depth.
            offsetX = (centerX - width / 2f) * .35f;
            offsetY = Math.max(height * .08f, centerY - height * .1f) * .5f;
            enlargement = target instanceof NavigableAppWidgetHostView ? .04f : .08f;
        }

        void apply(float progress) {
            float local = Math.max(0f, Math.min(1f, (progress - delay) / (1f - delay)));
            float remaining = 1f - EASE.getInterpolation(local);
            float visible = Math.min(1f, local / .18f);
            view.setAlpha(alpha * visible * visible * (3f - 2f * visible));
            if (reorderable != null) {
                reorderable.setReorderBounceScale(scaleX * (1f + enlargement * remaining));
                reorderable.getTranslateDelegate().setTranslation(INDEX_UNLOCK_ANIM,
                        offsetX * remaining, offsetY * remaining);
            } else {
                view.setScaleX(scaleX * (1f + enlargement * remaining));
                view.setScaleY(scaleY * (1f + enlargement * remaining));
                view.setTranslationX(translationX + offsetX * remaining);
                view.setTranslationY(translationY + offsetY * remaining);
            }
        }

        void restore() {
            view.setAlpha(alpha);
            if (reorderable != null) {
                reorderable.setReorderBounceScale(scaleX);
                reorderable.getTranslateDelegate().setTranslation(INDEX_UNLOCK_ANIM, 0f, 0f);
            } else {
                view.setScaleX(scaleX);
                view.setScaleY(scaleY);
                view.setTranslationX(translationX);
                view.setTranslationY(translationY);
            }
        }
    }

    private static final class ClipState {
        final ViewGroup group;
        final boolean children, padding;

        ClipState(ViewGroup view) {
            group = view;
            children = view.getClipChildren();
            padding = view.getClipToPadding();
            view.setClipChildren(false);
            view.setClipToPadding(false);
        }

        void restore() {
            group.setClipChildren(children);
            group.setClipToPadding(padding);
        }
    }
}
