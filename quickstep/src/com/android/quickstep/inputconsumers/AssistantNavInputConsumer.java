/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.quickstep.inputconsumers;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;

import android.content.Context;
import android.os.UserHandle;
import android.os.Bundle;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.android.launcher3.util.DisplayController;
import com.android.quickstep.InputConsumer;
import com.android.quickstep.NavHandle;
import com.android.quickstep.SystemUiProxy;
import com.android.systemui.shared.system.InputMonitorCompat;

import org.arkui.assistant.protocol.AssistantInvocation;

/** Recognizes only stationary handle gestures; swipes remain owned by the delegate. */
public final class AssistantNavInputConsumer extends DelegateInputConsumer {
    // Input consumers are recreated for every DOWN, so retain the preceding tap per display.
    private static final SparseArray<MotionEvent> sLastTaps = new SparseArray<>();
    private final Context mContext;
    private final NavHandle mHandle;
    private final int mUserId = UserHandle.myUserId();
    private final int mTouchSlop;
    private final int mDoubleTapSlop;
    private final float mCenter;
    private final float mHalfWidth;
    private final Runnable mLongPress = () -> trigger(AssistantInvocation.LONG_PRESS);
    private MotionEvent mDown;
    private boolean mCandidate;

    public AssistantNavInputConsumer(Context context, InputConsumer delegate,
            InputMonitorCompat monitor, NavHandle handle, int displayId) {
        super(displayId, delegate, monitor);
        mContext = context;
        mHandle = handle;
        ViewConfiguration config = ViewConfiguration.get(context);
        mTouchSlop = config.getScaledTouchSlop();
        mDoubleTapSlop = config.getScaledDoubleTapSlop();
        mCenter = DisplayController.INSTANCE.get(context).getInfo().currentSize.x / 2f;
        mHalfWidth = Math.max(handle.getNavHandleWidth(context) / 2f,
                24 * context.getResources().getDisplayMetrics().density);
    }

    public static boolean isEnabled(Context context) {
        int user = UserHandle.myUserId();
        return (AssistantInvocation.action(context, AssistantInvocation.DOUBLE_TAP, user) != 0
                || AssistantInvocation.action(context, AssistantInvocation.LONG_PRESS, user) != 0)
                && AssistantInvocation.isAvailable(context, user);
    }

    @Override public int getType() { return TYPE_NAV_HANDLE_LONG_PRESS | mDelegate.getType(); }
    @Override protected String getDelegatorName() { return "AssistantNavInputConsumer"; }

    @Override public void onMotionEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                mDown = MotionEvent.obtain(event);
                mCandidate = Math.abs(event.getRawX() - mCenter) <= mHalfWidth;
                if (mCandidate && AssistantInvocation.action(mContext,
                        AssistantInvocation.LONG_PRESS, mUserId) != 0) {
                    mHandle.animateNavBarLongPress(true, true, ViewConfiguration.getLongPressTimeout());
                    MAIN_EXECUTOR.getHandler().postDelayed(mLongPress,
                            ViewConfiguration.getLongPressTimeout());
                } else if (!mCandidate) clearLastTap();
            }
            case MotionEvent.ACTION_MOVE -> {
                if (mDown != null && (distanceSquared(mDown, event) > mTouchSlop * mTouchSlop
                        || !mDelegate.allowInterceptByParent())) cancel();
            }
            case MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> cancel();
            case MotionEvent.ACTION_UP -> {
                if (mCandidate && mState == STATE_INACTIVE && mDown != null
                        && distanceSquared(mDown, event) <= mTouchSlop * mTouchSlop
                        && event.getEventTime() - mDown.getEventTime() < ViewConfiguration.getLongPressTimeout()
                        && AssistantInvocation.action(mContext, AssistantInvocation.DOUBLE_TAP, mUserId) != 0) {
                    MotionEvent last = sLastTaps.get(getDisplayId());
                    if (last != null && mDown.getEventTime() - last.getEventTime() <= ViewConfiguration.getDoubleTapTimeout()
                            && distanceSquared(last, event) <= mDoubleTapSlop * mDoubleTapSlop) {
                        trigger(AssistantInvocation.DOUBLE_TAP);
                        clearLastTap();
                    } else {
                        clearLastTap();
                        sLastTaps.put(getDisplayId(), MotionEvent.obtain(event));
                    }
                }
                finishTouch();
            }
        }
        if (mState != STATE_ACTIVE) mDelegate.onMotionEvent(event);
    }

    private void trigger(String entry) {
        if (!mCandidate || mDown == null || !mDelegate.allowInterceptByParent()) return;
        if (AssistantInvocation.action(mContext, entry, mUserId) == 0
                || !AssistantInvocation.isAvailable(mContext, mUserId)) return;
        Bundle args = new Bundle();
        args.putString(AssistantInvocation.EXTRA_ENTRY, entry);
        setActive(mDown);
        cancel();
        SystemUiProxy.INSTANCE.get(mContext).startAssistant(args);
    }

    private static float distanceSquared(MotionEvent a, MotionEvent b) {
        float x = a.getRawX() - b.getRawX(), y = a.getRawY() - b.getRawY();
        return x * x + y * y;
    }

    private void clearLastTap() {
        MotionEvent last = sLastTaps.get(getDisplayId());
        if (last != null) { last.recycle(); sLastTaps.remove(getDisplayId()); }
    }

    private void finishTouch() {
        MAIN_EXECUTOR.getHandler().removeCallbacks(mLongPress);
        mHandle.animateNavBarLongPress(false, true, 140);
        mCandidate = false;
        if (mDown != null) { mDown.recycle(); mDown = null; }
    }

    private void cancel() { clearLastTap(); finishTouch(); }

    @Override public void onConsumerAboutToBeSwitched() {
        finishTouch();
        super.onConsumerAboutToBeSwitched();
    }
}
