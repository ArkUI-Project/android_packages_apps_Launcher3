/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Toast;

import com.android.launcher3.celllayout.CellLayoutLayoutParams;
import com.android.launcher3.folder.FolderIcon;
import com.android.launcher3.model.data.FolderInfo;
import com.android.launcher3.views.BaseDragLayer;

/** A corner-driven folder editor. Occupancy and the database commit at each accepted grid change. */
public final class FolderResizeFrame extends AbstractFloatingView
        implements ViewTreeObserver.OnPreDrawListener, Insettable {
    private final Launcher mLauncher;
    private final FolderIcon mIcon;
    private final CellLayout mLayout;
    private final View mGrip;
    private final float mDensity;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mIconBounds = new Rect();
    private final Rect mLocalBounds = new Rect();
    private final RectF mFrameBounds = new RectF();
    private final RectF mHitBounds = new RectF();
    private boolean mDragging;
    private float mDownX;
    private float mDownY;
    private int mRejectedSpanX;
    private int mRejectedSpanY;
    private float mScale = 1;

    private FolderResizeFrame(FolderIcon icon, CellLayout layout) {
        super(icon.getContext(), null);
        mIcon = icon;
        mLayout = layout;
        mLauncher = Launcher.getLauncher(getContext());
        mDensity = getResources().getDisplayMetrics().density;
        mIsOpen = true;
        setWillNotDraw(false);
        mPaint.setColor(Color.WHITE);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mGrip = new View(getContext());
        mGrip.setId(R.id.folder_resize_handle);
        mGrip.setContentDescription(getContext().getString(R.string.folder_resize_handle));
        mGrip.setFocusable(true);
        mGrip.setOnClickListener(view -> {
            if (mIcon.mInfo.isPrivacyLocked()) {
                AbstractFloatingView.closeOpenViews(mLauncher, false, TYPE_ACTION_POPUP);
                if (isOpen()) close(false);
                mIcon.authenticatePrivacy(() -> {
                    FolderResizeFrame frame = showForFolder(mIcon);
                    if (frame != null) frame.advanceSize();
                });
            } else advanceSize();
        });
        addView(mGrip, new LayoutParams(Math.round(48 * mDensity), Math.round(48 * mDensity)));
    }

    private void advanceSize() {
        int next = mIcon.mInfo.spanX == 2 && mIcon.mInfo.spanY == 2 ? 1
                : mIcon.mInfo.spanX == 1 && mIcon.mInfo.spanY == 2 ? 2
                : mIcon.mInfo.spanX == 2 ? 3 : 0;
        resize(next == 1 || next == 3 ? 1 : 2, next == 2 || next == 3 ? 1 : 2);
    }

    public static FolderResizeFrame showForFolder(FolderIcon icon) {
        if (!(icon.getParent() instanceof ShortcutAndWidgetContainer container)
                || !(container.getParent() instanceof CellLayout layout)
                || icon.mInfo.container != LauncherSettings.Favorites.CONTAINER_DESKTOP) return null;
        Launcher launcher = Launcher.getLauncher(icon.getContext());
        AbstractFloatingView.closeOpenViews(launcher, false, TYPE_WIDGET_RESIZE_FRAME);
        FolderResizeFrame frame = new FolderResizeFrame(icon, layout);
        icon.setResizeEditing(true);
        launcher.getDragLayer().addView(frame, new BaseDragLayer.LayoutParams(-1, -1));
        frame.getViewTreeObserver().addOnPreDrawListener(frame);
        frame.post(frame::updateBounds);
        return frame;
    }

    public View getResizeHandle() { return mGrip; }

    public static boolean resizeFolder(FolderIcon icon, int spanX, int spanY) {
        if (!(icon.getParent() instanceof ShortcutAndWidgetContainer container)
                || !(container.getParent() instanceof CellLayout layout)) return false;
        icon.setResizeEditing(true);
        boolean accepted = new FolderResizeFrame(icon, layout).resize(spanX, spanY);
        icon.setResizeEditing(false);
        return accepted;
    }

    private void updateBounds() {
        if (!mIcon.isAttachedToWindow()) { close(false); return; }
        mScale = mLauncher.getDragLayer().getDescendantRectRelativeToSelf(mIcon, mIconBounds);
        mIcon.getPreviewBounds(mLocalBounds);
        RectF next = new RectF(mIconBounds.left + mLocalBounds.left * mScale,
                mIconBounds.top + mLocalBounds.top * mScale,
                mIconBounds.left + mLocalBounds.right * mScale,
                mIconBounds.top + mLocalBounds.bottom * mScale);
        if (next.equals(mFrameBounds)) return;
        mFrameBounds.set(next);
        float touch = 24 * mDensity;
        mHitBounds.set(next.right - touch, next.bottom - touch,
                next.right + touch, next.bottom + touch);
        mGrip.setTranslationX(mHitBounds.left);
        mGrip.setTranslationY(mHitBounds.top);
        invalidate();
    }

    @Override public boolean onPreDraw() {
        updateBounds();
        return true;
    }

    @Override public boolean onControllerInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return mDragging;
        updateBounds();
        if (mHitBounds.contains(event.getX(), event.getY())) {
            mDragging = true;
            mDownX = event.getX();
            mDownY = event.getY();
            mRejectedSpanX = mRejectedSpanY = 0;
            return true;
        }
        if (!mIconBounds.contains((int) event.getX(), (int) event.getY())) close(true);
        return false;
    }

    @Override public boolean onControllerTouchEvent(MotionEvent event) {
        if (!mDragging) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float dx = (event.getX() - mDownX) / mScale;
            float dy = (event.getY() - mDownY) / mScale;
            DeviceProfile profile = mLauncher.getDeviceProfile();
            float stepX = mLayout.getCellWidth()
                    + profile.getWorkspaceIconProfile().getCellLayoutBorderSpacePx().x;
            // Small elastic feedback, then a discrete switch. A fresh anchor after each switch
            // gives the return gesture hysteresis instead of oscillating at one threshold.
            float threshold = stepX * .5f;
            int oldX = mIcon.mInfo.spanX;
            int oldY = mIcon.mInfo.spanY;
            int spanX = nextSpan(oldX, dx, threshold);
            int spanY = nextSpan(oldY, dy, threshold);
            if ((spanX != oldX || spanY != oldY) && resize(spanX, spanY)) {
                if (spanX != oldX) mDownX = event.getX();
                if (spanY != oldY) mDownY = event.getY();
                performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            } else {
                mIcon.setResizeResistance(resistance(dx, oldX), resistance(dy, oldY));
            }
            updateBounds();
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            mDragging = false;
            mIcon.finishInteractiveResize();
        }
        return true;
    }

    private int nextSpan(int current, float delta, float threshold) {
        return delta > threshold ? Math.min(2, current + 1)
                : delta < -threshold ? Math.max(1, current - 1) : current;
    }

    private float resistance(float delta, int span) {
        if (span == 1 && delta < 0 || span == 2 && delta > 0) return 0;
        float limit = 6 * mDensity;
        return limit * delta / (Math.abs(delta) + limit * 2);
    }

    private boolean resize(int spanX, int spanY) {
        FolderInfo info = mIcon.mInfo;
        if (spanX == info.spanX && spanY == info.spanY) return true;
        if (spanX == mRejectedSpanX && spanY == mRejectedSpanY) return false;
        CellLayoutLayoutParams lp = (CellLayoutLayoutParams) mIcon.getLayoutParams();
        int oldX = lp.getCellX();
        int oldY = lp.getCellY();
        int oldSpanX = lp.cellHSpan;
        int oldSpanY = lp.cellVSpan;
        int x = Math.min(oldX, mLayout.getCountX() - spanX);
        int y = Math.min(oldY, mLayout.getCountY() - spanY);
        if (x < 0 || y < 0) return false;
        int[] direction = {spanX >= oldSpanX ? 1 : -1, spanY >= oldSpanY ? 1 : -1};
        mIcon.prepareResize();
        lp.setTmpCellX(x);
        lp.setTmpCellY(y);
        lp.cellHSpan = spanX;
        lp.cellVSpan = spanY;
        if (!mLayout.createAreaForResize(x, y, spanX, spanY, mIcon, direction, true)) {
            lp.setTmpCellX(oldX);
            lp.setTmpCellY(oldY);
            lp.cellHSpan = oldSpanX;
            lp.cellVSpan = oldSpanY;
            mLayout.revertTempState();
            mIcon.cancelPreparedResize();
            mRejectedSpanX = spanX;
            mRejectedSpanY = spanY;
            performHapticFeedback(HapticFeedbackConstants.REJECT);
            if (!mDragging) Toast.makeText(getContext(), R.string.folder_resize_no_space,
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        lp.setCellX(x);
        lp.setCellY(y);
        lp.useTmpCoords = false;
        // createAreaForResize commits the folder and any displaced neighbors together.
        mIcon.requestLayout();
        mRejectedSpanX = mRejectedSpanY = 0;
        mIcon.invalidate();
        mGrip.announceForAccessibility(getContext().getString(R.string.folder_resized, spanX, spanY));
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        float radius = mIcon.getPreviewCornerRadius() * mScale;
        mPaint.setAlpha(100);
        mPaint.setStrokeWidth(mDensity);
        canvas.drawRoundRect(mFrameBounds, radius, radius, mPaint);
        mPaint.setAlpha(255);
        mPaint.setStrokeWidth(3 * mDensity);
        RectF corner = new RectF(mFrameBounds.right - radius * 2,
                mFrameBounds.bottom - radius * 2, mFrameBounds.right, mFrameBounds.bottom);
        canvas.drawArc(corner, 0, 90, false, mPaint);
    }

    @Override protected void handleClose(boolean animate) {
        mIsOpen = false;
        mDragging = false;
        getViewTreeObserver().removeOnPreDrawListener(this);
        mIcon.finishInteractiveResize();
        mIcon.setResizeEditing(false);
        mLauncher.getDragLayer().removeView(this);
    }

    @Override protected boolean isOfType(int type) { return (type & TYPE_WIDGET_RESIZE_FRAME) != 0; }

    @Override public void setInsets(Rect insets) { }
}
