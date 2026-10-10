/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.views;

import static com.android.launcher3.LauncherSettings.Favorites.CONTAINER_DESKTOP;
import static com.android.launcher3.LauncherState.EDIT_MODE;
import static com.android.launcher3.LauncherState.NORMAL;

import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.BubbleTextView;
import com.android.launcher3.DropTarget;
import com.android.launcher3.Insettable;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherState;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.anim.PendingAnimation;
import com.android.launcher3.dragndrop.DragController;
import com.android.launcher3.dragndrop.DragOptions;
import com.android.launcher3.graphics.FrostedSurfaceDrawable;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.LauncherAppWidgetInfo;
import com.android.launcher3.settings.DesktopMode;
import com.android.launcher3.states.StateAnimationConfig;
import com.android.launcher3.statemanager.StateManager.StateHandler;
import com.android.launcher3.util.IntSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Controls the editable workspace; its transparent chrome leaves page swipes and dragging intact. */
public final class HomeEditController implements StateHandler<LauncherState>, DragController.DragListener {
    private final Launcher mLauncher;
    private final Set<Integer> mSelected = new HashSet<>();
    private final Map<View, Badge> mBadges = new HashMap<>();
    private final Set<View> mSeen = new HashSet<>();
    private final Rect mBounds = new Rect();
    private final float[] mPoint = new float[2];
    private EditChrome mChrome;
    private boolean mDragging;
    private float mInitialSpan;
    private boolean mConsumePinch;
    private final ViewTreeObserver.OnPreDrawListener mPreDraw = () -> { updateBadges(); return true; };

    public HomeEditController(Launcher launcher) {
        mLauncher = launcher;
        launcher.getDragController().addDragListener(this);
    }

    public boolean enter() {
        if (!mLauncher.isInState(NORMAL) || mLauncher.getDragController().isDragging()
                || AbstractFloatingView.getTopOpenView(mLauncher) != null) return false;
        mLauncher.getStateManager().goToState(EDIT_MODE);
        return true;
    }

    public boolean isConsumingPinch() { return mConsumePinch; }

    /** Observed at the drag layer so a gesture can begin over an icon as well as empty space. */
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mInitialSpan = 0;
            mConsumePinch = false;
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            mInitialSpan = 0;
            if (event.getPointerCount() == 2 && mLauncher.isInState(NORMAL)
                    && !mLauncher.getDragController().isDragging()
                    && AbstractFloatingView.getTopOpenView(mLauncher) == null
                    && event.isFromSource(android.view.InputDevice.SOURCE_TOUCHSCREEN)) {
                mLauncher.getDragLayer().getDescendantRectRelativeToSelf(mLauncher.getWorkspace(), mBounds);
                if (mBounds.contains((int) event.getX(0), (int) event.getY(0))
                        && mBounds.contains((int) event.getX(1), (int) event.getY(1))) {
                    mInitialSpan = span(event);
                    // Cancel the icon/page's first finger immediately, before it can drag or scroll.
                    mConsumePinch = mInitialSpan > dp(40);
                }
            }
        } else if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() == 2
                && mInitialSpan > 0 && mConsumePinch) {
            float span = span(event);
            if (span < mInitialSpan * .78f && mInitialSpan - span > dp(40)) {
                enter();
                mInitialSpan = 0;
            }
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            mInitialSpan = 0;
        }
        boolean consumed = mConsumePinch;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mInitialSpan = 0;
            mConsumePinch = false;
        }
        return consumed;
    }

    private static float span(MotionEvent event) {
        return (float) Math.hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1));
    }

    public void toggleSelection(View source) {
        if (mChrome == null || !mBadges.containsKey(source) || !Utilities.isWorkspaceEditAllowed(mLauncher)
                || !(source.getTag() instanceof ItemInfo info)
                || info.container != CONTAINER_DESKTOP || info instanceof LauncherAppWidgetInfo) return;
        if (!mSelected.add(info.id)) mSelected.remove(info.id);
        updateSelection();
    }

    @Override public void setState(LauncherState state) {
        if (state == EDIT_MODE) {
            if (mChrome != null) return;
            mChrome = new EditChrome();
            mLauncher.getDragLayer().addView(mChrome, new BaseDragLayer.LayoutParams(-1, -1));
            mChrome.setInsets(mLauncher.getDeviceProfile().getInsets());
            mLauncher.getDragLayer().getViewTreeObserver().addOnPreDrawListener(mPreDraw);
            updateSelection();
        } else {
            if (mChrome == null) return;
            mLauncher.getDragLayer().getViewTreeObserver().removeOnPreDrawListener(mPreDraw);
            mLauncher.getDragLayer().removeView(mChrome);
            mChrome = null;
            mBadges.clear();
            mSelected.clear();
        }
    }

    @Override public void setStateWithAnimation(LauncherState state, StateAnimationConfig config,
            PendingAnimation animation) {
        boolean entering = state == EDIT_MODE && mChrome == null;
        setState(state);
        if (entering) {
            mChrome.setAlpha(0);
            animation.setViewAlpha(mChrome, 1, com.android.app.animation.Interpolators.DECELERATE_2);
        }
    }

    private void updateSelection() {
        if (mChrome == null) return;
        mChrome.remove.setVisibility(mSelected.isEmpty() ? View.GONE : View.VISIBLE);
        mChrome.remove.setText(mLauncher.getString(R.string.arkui_home_edit_remove_count, mSelected.size()));
        for (Badge badge : mBadges.values()) badge.refresh();
    }

    private void updateBadges() {
        if (mChrome == null) return;
        int blockingPopups = AbstractFloatingView.TYPE_ALL & ~AbstractFloatingView.TYPE_SNACKBAR
                & ~AbstractFloatingView.TYPE_WIDGET_RESIZE_FRAME;
        mChrome.setVisibility(mDragging
                || AbstractFloatingView.getTopOpenViewWithType(mLauncher, blockingPopups) != null
                ? View.INVISIBLE : View.VISIBLE);
        if (mChrome.getVisibility() != View.VISIBLE) return;
        mSeen.clear();
        if (Utilities.isWorkspaceEditAllowed(mLauncher)) {
            mLauncher.getWorkspace().mapOverItems((info, source) -> {
                if (info.container != CONTAINER_DESKTOP || info.id == ItemInfo.NO_ID
                        || !DesktopMode.canRemove(mLauncher, info)) return false;
                mSeen.add(source);
                Badge badge = mBadges.get(source);
                if (badge == null) {
                    badge = new Badge(source, info);
                    mBadges.put(source, badge);
                    mChrome.addView(badge, 0, new FrameLayout.LayoutParams(dp(48), dp(48)));
                }
                if (source instanceof BubbleTextView bubble) {
                    bubble.getIconBounds(mBounds);
                } else {
                    mBounds.set(0, source.getPaddingTop(), source.getWidth(), source.getHeight());
                }
                mPoint[0] = mBounds.right - dp(4);
                mPoint[1] = mBounds.top + dp(4);
                mLauncher.getDragLayer().getDescendantCoordRelativeToSelf(source, mPoint);
                badge.setTranslationX(mPoint[0] - dp(24));
                badge.setTranslationY(mPoint[1] - dp(24));
                badge.setVisibility(source.isShown() && mLauncher.getWorkspace().isVisible(
                        mLauncher.getWorkspace().getScreenWithId(info.screenId))
                        && mPoint[0] > 0 && mPoint[0] < mChrome.getWidth()
                        && mPoint[1] > 0 && mPoint[1] < mChrome.getHeight() ? View.VISIBLE : View.INVISIBLE);
                return false;
            });
        }
        boolean removed = mBadges.entrySet().removeIf(entry -> {
            if (mSeen.contains(entry.getKey())) return false;
            mChrome.removeView(entry.getValue());
            mSelected.remove(entry.getValue().info.id);
            return true;
        });
        if (removed) updateSelection();
    }

    private void removeItems(ArrayList<View> sources) {
        if (sources.isEmpty() || mDragging || !Utilities.isWorkspaceEditAllowed(mLauncher)) return;
        AbstractFloatingView.closeOpenViews(mLauncher, false, AbstractFloatingView.TYPE_SNACKBAR);
        IntSet pages = new IntSet();
        mLauncher.getModelWriter().prepareToUndoDelete();
        for (View source : sources) {
            if (!source.isAttachedToWindow() || !(source.getTag() instanceof ItemInfo info)
                    || info.container != CONTAINER_DESKTOP || info.id == ItemInfo.NO_ID
                    || !DesktopMode.canRemove(mLauncher, info)) continue;
            pages.add(info.screenId);
            mLauncher.removeItem(source, info, true, "removed in workspace edit mode");
        }
        if (pages.isEmpty()) {
            mLauncher.getModelWriter().abortDelete();
            return;
        }
        mLauncher.getWorkspace().stripEmptyScreens();
        mSelected.clear();
        updateSelection();
        Snackbar.show(mLauncher, R.string.item_removed, R.string.undo,
                mLauncher.getModelWriter()::commitDelete, () -> {
                    mLauncher.setPagesToBindSynchronously(pages);
                    mLauncher.getModelWriter().abortDelete();
                });
    }

    private void runAction(View source, int action) {
        mLauncher.getStateManager().goToState(NORMAL, false);
        // The action button has detached; use the still-attached workspace as the launch anchor.
        source = mLauncher.getWorkspace();
        switch (action) {
            case 0 -> OptionsPopupView.startWallpaperPicker(source);
            case 1 -> {
                Bundle args = new Bundle();
                args.putBoolean("effects", true);
                Intent intent = new Intent().setClassName("com.android.settings",
                        "com.android.settings.Settings$ArkuiDepthWallpaperSettingsActivity")
                        .putExtra(":settings:show_fragment_args", args);
                mLauncher.startActivitySafely(source, intent, OptionsPopupView.placeholderInfo(intent));
            }
            case 2 -> mLauncher.openWidgetPicker();
            case 3 -> OptionsPopupView.startSettings(source);
        }
    }

    @Override public void onDragStart(DropTarget.DragObject object, DragOptions options) {
        mDragging = true;
        if (mChrome != null) mChrome.setVisibility(View.INVISIBLE);
    }
    @Override public void onDragEnd() {
        mDragging = false;
        if (mChrome != null) { mChrome.setVisibility(View.VISIBLE); updateBadges(); }
    }
    public void destroy() {
        setState(NORMAL);
        mLauncher.getDragController().removeDragListener(this);
    }

    private int dp(float value) { return Math.round(value * mLauncher.getResources().getDisplayMetrics().density); }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private TextView button(int label, int color) {
        TextView view = new TextView(mLauncher);
        view.setText(label);
        view.setTextColor(Color.WHITE);
        view.setTextSize(16);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(24), 0, dp(24), 0);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40ffffff), rounded(color, 28), null));
        view.setMinHeight(dp(48));
        return view;
    }

    private final class EditChrome extends FrameLayout implements Insettable {
        final TextView done;
        final TextView remove;
        final LinearLayout actions;

        EditChrome() {
            super(mLauncher);
            setId(R.id.arkui_home_edit);
            setClipChildren(false);
            setAccessibilityPaneTitle(mLauncher.getString(R.string.edit_home_screen));
            done = button(R.string.arkui_home_edit_done, 0xff3478f6);
            done.setId(R.id.arkui_home_edit_done);
            done.setOnClickListener(v -> mLauncher.getStateManager().goToState(NORMAL));
            addView(done, new FrameLayout.LayoutParams(-2, dp(48), Gravity.TOP | Gravity.END));
            remove = button(R.string.remove_drop_target_label, 0xffbc353b);
            remove.setId(R.id.arkui_home_edit_remove);
            remove.setOnClickListener(v -> {
                ArrayList<View> sources = new ArrayList<>();
                for (var entry : mBadges.entrySet()) {
                    if (mSelected.contains(entry.getValue().info.id)) sources.add(entry.getKey());
                }
                removeItems(sources);
            });
            addView(remove, new FrameLayout.LayoutParams(-2, dp(48), Gravity.TOP | Gravity.START));
            actions = new LinearLayout(mLauncher);
            actions.setGravity(Gravity.CENTER);
            int[] labels = {R.string.arkui_home_edit_wallpaper, R.string.arkui_home_edit_effects,
                    R.string.arkui_home_edit_widgets, R.string.arkui_home_edit_settings};
            int[] icons = {R.drawable.arkui_ic_edit_wallpaper, R.drawable.arkui_ic_edit_effects,
                    R.drawable.ic_widget, R.drawable.ic_setting};
            for (int i = 0; i < labels.length; i++) {
                final int action = i;
                LinearLayout column = new LinearLayout(mLauncher);
                column.setOrientation(LinearLayout.VERTICAL);
                column.setGravity(Gravity.CENTER);
                column.setPadding(0, dp(8), 0, dp(8));
                column.setContentDescription(mLauncher.getString(labels[i]));
                column.setOnClickListener(v -> runAction(v, action));
                if (i == 2) column.setEnabled(Utilities.isWorkspaceEditAllowed(mLauncher));
                ImageView icon = new ImageView(mLauncher);
                icon.setImageResource(icons[i]);
                icon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
                icon.setPadding(dp(18), dp(18), dp(18), dp(18));
                FrostedSurfaceDrawable glass = new FrostedSurfaceDrawable(icon, 0xff484d60, dp(32));
                glass.setStroke(dp(1), 0x40ffffff);
                icon.setBackground(glass);
                icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                column.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));
                TextView label = new TextView(mLauncher);
                label.setText(labels[i]);
                label.setTextColor(Color.WHITE);
                label.setTextSize(13);
                label.setGravity(Gravity.CENTER);
                label.setShadowLayer(dp(3), 0, dp(1), 0xb0000000);
                label.setPadding(0, dp(12), 0, 0);
                label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                column.addView(label, new LinearLayout.LayoutParams(-1, -2));
                actions.addView(column, new LinearLayout.LayoutParams(0, -2, 1));
            }
            addView(actions, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        }

        @Override public void setInsets(Rect insets) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) done.getLayoutParams();
            params.topMargin = insets.top + dp(16);
            params.setMarginEnd(insets.right + dp(24));
            done.setLayoutParams(params);
            params = (FrameLayout.LayoutParams) remove.getLayoutParams();
            params.topMargin = insets.top + dp(16);
            params.setMarginStart(insets.left + dp(24));
            remove.setLayoutParams(params);
            params = (FrameLayout.LayoutParams) actions.getLayoutParams();
            params.bottomMargin = insets.bottom + dp(20);
            params.leftMargin = insets.left + dp(16);
            params.rightMargin = insets.right + dp(16);
            actions.setLayoutParams(params);
        }
    }

    private final class Badge extends View {
        final View source;
        final ItemInfo info;
        final boolean widget;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path check = new Path();
        boolean selected;

        Badge(View source, ItemInfo info) {
            super(mLauncher);
            this.source = source;
            this.info = info;
            widget = info instanceof LauncherAppWidgetInfo;
            setOnClickListener(v -> {
                if (widget) {
                    ArrayList<View> items = new ArrayList<>();
                    items.add(source);
                    removeItems(items);
                } else toggleSelection(source);
            });
            // The accessible touch target overlaps the icon; keep its long-press drag available.
            setOnLongClickListener(v -> source.performLongClick());
            refresh();
        }

        void refresh() {
            selected = mSelected.contains(info.id);
            CharSequence title = info.title;
            if (android.text.TextUtils.isEmpty(title)) title = source.getContentDescription();
            if (android.text.TextUtils.isEmpty(title)) title = mLauncher.getString(R.string.arkui_home_edit_widgets);
            setContentDescription(mLauncher.getString(widget ? R.string.arkui_home_edit_remove_item
                    : selected ? R.string.arkui_home_edit_deselect_item : R.string.arkui_home_edit_select_item,
                    title));
            setSelected(selected);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float x = getWidth() / 2f, y = getHeight() / 2f, radius = dp(11);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(widget ? 0xffef5148 : selected ? 0xff3478f6 : 0x40666a78);
            canvas.drawCircle(x, y, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.5f));
            paint.setColor(Color.WHITE);
            canvas.drawCircle(x, y, radius, paint);
            if (widget) {
                paint.setStrokeWidth(dp(2));
                canvas.drawLine(x - dp(5), y, x + dp(5), y, paint);
            } else if (selected) {
                check.reset();
                check.moveTo(x - dp(5), y);
                check.lineTo(x - dp(1), y + dp(4));
                check.lineTo(x + dp(6), y - dp(4));
                paint.setStrokeWidth(dp(2));
                canvas.drawPath(check, paint);
            }
        }
    }
}
