/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.folder;

import static com.android.app.animation.Interpolators.EMPHASIZED;
import static com.android.launcher3.BubbleTextView.DISPLAY_WORKSPACE;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherSettings;
import com.android.launcher3.R;
import com.android.launcher3.apppairs.AppPairIcon;
import com.android.launcher3.graphics.FrostedSurfaceDrawable;
import com.android.launcher3.model.data.AppPairInfo;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.FolderInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.touch.ItemClickHandler;
import com.android.launcher3.util.Themes;
import com.android.launcher3.util.CancellableTask;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/** Live app targets; size changes retarget the current background and each icon's geometry. */
final class LargeFolderPreview extends FrameLayout {
    private final FolderIcon mOwner;
    private final FolderInfo mInfo;
    private final boolean mUpdatesOwner;
    private final float mDensity;
    private final int mIconSize;
    private final FrostedSurfaceDrawable mSurface;
    private final Drawable mLock;
    private final RectF mBounds = new RectF();
    private final RectF mTargetBounds = new RectF();
    private final RectF mFromBounds = new RectF();
    private final List<RectF> mRects = new ArrayList<>();
    private final List<RectF> mTargets = new ArrayList<>();
    private final List<RectF> mStarts = new ArrayList<>();
    private final List<ItemInfo> mItems = new ArrayList<>();
    private final List<View> mIcons = new ArrayList<>();
    private final List<CancellableTask> mIconRequests = new ArrayList<>();
    private final Set<ItemInfo> mDroppingItems = new HashSet<>();
    private final View mMore;
    private final RectF mMoreBounds = new RectF();
    private final RectF mVisibleMoreBounds = new RectF();
    private final Paint mOverflowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mClip = new Path();
    private ValueAnimator mAnimator;
    private float mRadius;
    private float mTargetRadius;
    private float mStartRadius;
    private int mFullCount;
    private boolean mEditing;
    private boolean mInteractive;
    private boolean mAwaitingResizeLayout;

    LargeFolderPreview(FolderIcon owner) {
        this(owner, owner.mInfo);
    }

    LargeFolderPreview(FolderIcon owner, FolderInfo info) {
        super(owner.getContext());
        mOwner = owner;
        mInfo = info;
        mUpdatesOwner = info == owner.mInfo;
        mDensity = getResources().getDisplayMetrics().density;
        mIconSize = owner.mActivity.getDeviceProfile().getWorkspaceIconProfile().getIconSizePx();
        setId(R.id.large_folder_preview);
        setClipChildren(false);
        setClipToPadding(false);
        setFilterTouchesWhenObscured(true);
        mSurface = new FrostedSurfaceDrawable(this,
                Themes.getAttrColor(getContext(), R.attr.folderPreviewColor), 28 * mDensity);
        mSurface.setCallback(this);
        mLock = getContext().getDrawable(R.drawable.ic_protected_locked).mutate();
        mLock.setTint(Themes.getAttrColor(getContext(), android.R.attr.textColorPrimary));
        mOverflowPaint.setColor(Color.WHITE);
        mOverflowPaint.setAlpha(24);
        mMore = new View(getContext());
        mMore.setId(R.id.folder_preview_more);
        mMore.setPivotX(0);
        mMore.setPivotY(0);
        mMore.setOnClickListener(view -> mOwner.getFolder().animateOpen());
        mMore.setOnLongClickListener(view -> mOwner.performLongClick());
        mMore.setFilterTouchesWhenObscured(true);
        addView(mMore, new LayoutParams(mIconSize, mIconSize));
        refresh();
    }

    void refresh() {
        for (CancellableTask request : mIconRequests) request.cancel();
        mIconRequests.clear();
        List<ItemInfo> items = new ArrayList<>(mInfo.getContents());
        items.sort(Folder.ITEM_POS_COMPARATOR);
        if (mInfo.hasOption(FolderInfo.FLAG_SMART_PREVIEW)) {
            FolderPreviewUsage.sort(getContext(), items);
        }
        if (!mItems.equals(items)) {
            for (View icon : mIcons) removeView(icon);
            mItems.clear();
            mItems.addAll(items);
            mIcons.clear();
            mRects.clear();
            mTargets.clear();
            mStarts.clear();
            // At most eight direct targets plus four icons in the overflow tile are drawn.
            for (int i = 0; i < Math.min(12, items.size()); i++) {
                ItemInfo item = items.get(i);
                View icon;
                if (item instanceof AppPairInfo pair) {
                    AppPairIcon pairIcon = AppPairIcon.inflateIcon(R.layout.app_pair_icon,
                            mOwner.mActivity, this, pair, DISPLAY_WORKSPACE);
                    pairIcon.setTextVisible(false);
                    icon = pairIcon;
                } else {
                    ImageView image = new ImageView(getContext());
                    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    if (item instanceof WorkspaceItemInfo app) {
                        image.setImageDrawable(app.bitmap.newIcon(getContext()));
                    }
                    image.setTag(item);
                    icon = image;
                }
                icon.setId(R.id.folder_preview_app);
                icon.setPivotX(0);
                icon.setPivotY(0);
                icon.setFilterTouchesWhenObscured(true);
                final int index = i;
                icon.setOnClickListener(view -> {
                    if (mInfo.isPrivacyLocked() || mEditing || !mUpdatesOwner) return;
                    if (!mInfo.isLarge() || index >= mFullCount) mOwner.getFolder().animateOpen();
                    else ItemClickHandler.INSTANCE.onClick(view);
                });
                icon.setOnLongClickListener(view -> mOwner.performLongClick());
                addView(icon, 0, new LayoutParams(mIconSize, mIconSize));
                mIcons.add(icon);
                mRects.add(new RectF());
                mTargets.add(new RectF());
                mStarts.add(new RectF());
            }
        }
        for (View icon : mIcons) {
            if (icon instanceof ImageView image && icon.getTag() instanceof WorkspaceItemInfo app) {
                image.setImageDrawable(app.bitmap.newIcon(getContext()));
                if (app.getMatchingLookupFlag().isVisuallyLessThan(
                        LauncherSettings.Favorites.DESKTOP_ICON_FLAG)) {
                    mIconRequests.add(LauncherAppState.getInstance(getContext()).getIconCache()
                            .updateIconInBackground(updated -> {
                                if (image.getTag() == updated) {
                                    image.setImageDrawable(app.bitmap.newIcon(getContext()));
                                }
                            }, app, LauncherSettings.Favorites.DESKTOP_ICON_FLAG));
                }
            }
            if (mInfo.isPrivacyLocked()) {
                icon.setVisibility(GONE);
                icon.setContentDescription(null);
                icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
        }
        if (mAwaitingResizeLayout) requestLayout();
        else retarget(false);
    }

    void setEditing(boolean editing) {
        mEditing = editing;
        mInteractive = false;
        if (!mAwaitingResizeLayout) retarget(true);
    }

    void setShown(boolean shown) {
        mSurface.setVisible(shown, false);
        setVisibility(shown ? VISIBLE : INVISIBLE);
    }

    void prepareResize() {
        // Preserve a frame which may already be in flight, rather than restarting at the old size.
        if (mAnimator != null) mAnimator.cancel();
        mAnimator = null;
        mInteractive = false;
        mAwaitingResizeLayout = true;
    }

    void cancelResize() {
        mAwaitingResizeLayout = false;
        retarget(true);
    }

    void setResizeResistance(float dx, float dy) {
        if (mAwaitingResizeLayout || mAnimator != null && mAnimator.isRunning()) return;
        setInteractiveSize(mTargetBounds.width() + dx, mTargetBounds.height() + dy);
    }

    private void setInteractiveSize(float width, float height) {
        mInteractive = true;
        RectF previous = new RectF(mBounds);
        mBounds.right = mBounds.left + Math.max(1, width);
        mBounds.bottom = mBounds.top + Math.max(1, height);
        // Keep the last painted arrangement until layout supplies the next grid. Replacing
        // it with the new targets here would erase the starting positions of the reflow.
        for (RectF rect : mRects) rebase(rect, previous);
        if (!mVisibleMoreBounds.isEmpty()) rebase(mVisibleMoreBounds, previous);
        updateSurface();
    }

    private void rebase(RectF rect, RectF previous) {
        float size = rect.width();
        float x = mBounds.left + (rect.centerX() - previous.left)
                / Math.max(1, previous.width()) * mBounds.width() - size / 2;
        float y = mBounds.top + (rect.centerY() - previous.top)
                / Math.max(1, previous.height()) * mBounds.height() - size / 2;
        placeInside(rect, x, y, size);
    }

    void finishInteractiveResize() {
        if (mAwaitingResizeLayout || !mInteractive) {
            mInteractive = false;
            return;
        }
        mInteractive = false;
        retarget(true);
    }

    void calculateSurfaceBounds(int width, int height, RectF out) {
        android.graphics.Point gap = mOwner.mActivity.getDeviceProfile()
                .getWorkspaceIconProfile().getCellLayoutBorderSpacePx();
        float step = (width + gap.x) / (float) mInfo.spanX;
        float rowHeight = (height + gap.y) / (float) mInfo.spanY - gap.y;
        float label = mOwner.getFolderName().shouldShowLabel()
                ? mOwner.getFolderName().getLineHeight() + 8 * mDensity : 0;
        float top = Math.max(4 * mDensity, (rowHeight - (step - gap.x) - label) / 2);
        out.set(4 * mDensity, top, width - 4 * mDensity,
                top + step * mInfo.spanY - gap.x - 8 * mDensity);
    }

    private void calculateTargets() {
        if (!mInfo.isLarge()) {
            Rect compact = new Rect();
            mOwner.getCompactPreviewBounds(compact);
            mTargetBounds.set(compact);
            if (!mUpdatesOwner) mTargetBounds.offsetTo((getWidth() - compact.width()) / 2f,
                    (getHeight() - compact.height()) / 2f);
            mTargetRadius = compact.width() / 2f;
        } else {
            if (mUpdatesOwner) calculateSurfaceBounds(getWidth(), getHeight(), mTargetBounds);
            else {
                float gap = mOwner.mActivity.getDeviceProfile().getWorkspaceIconProfile()
                        .getCellLayoutBorderSpacePx().x;
                float step = (mOwner.getWidth() + gap) / mOwner.mInfo.spanX;
                float width = step * mInfo.spanX - gap - 8 * mDensity;
                float height = step * mInfo.spanY - gap - 8 * mDensity;
                float x = (getWidth() - width) / 2;
                float y = (getHeight() - height) / 2;
                mTargetBounds.set(x, y, x + width, y + height);
            }
            mTargetRadius = Math.min(28 * mDensity,
                    Math.min(mTargetBounds.width(), mTargetBounds.height()) / 3);
        }
        calculateLayoutTargets(mTargetBounds);
    }

    private void calculateLayoutTargets(RectF bounds) {
        int capacity = mInfo.isLarge()
                ? (mInfo.spanX == 2 ? 3 : 1) * (mInfo.spanY == 2 ? 3 : 1) : 4;
        boolean overflow = mInfo.isLarge() && mItems.size() > capacity;
        mFullCount = overflow ? capacity - 1 : Math.min(capacity, mItems.size());
        int columns = mInfo.spanX == 2 ? 3 : 1;
        int rows = mInfo.spanY == 2 ? 3 : 1;
        float padding = 8 * mDensity;
        float cellW = (bounds.width() - 2 * padding) / columns;
        float cellH = (bounds.height() - 2 * padding) / rows;
        float size = mIconSize * .78f;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        Rect compact = new Rect();
        if (!mInfo.isLarge()) mOwner.getCompactPreviewBounds(compact);
        mMoreBounds.setEmpty();
        for (int i = 0; i < mTargets.size(); i++) {
            RectF rect = mTargets.get(i);
            if (!mInfo.isLarge()) {
                mOwner.getCompactItemBounds(i, rect);
                float cx = (rect.centerX() - compact.left) / Math.max(1, compact.width());
                float cy = (rect.centerY() - compact.top) / Math.max(1, compact.height());
                float compactSize = rect.width() * Math.min(
                        bounds.width() / Math.max(1, compact.width()),
                        bounds.height() / Math.max(1, compact.height()));
                float x = bounds.left + cx * bounds.width() - compactSize / 2;
                float y = bounds.top + cy * bounds.height() - compactSize / 2;
                rect.set(x, y, x + compactSize, y + compactSize);
            } else {
                int slot = Math.min(i, capacity - 1);
                int col = slot % columns;
                if (rtl) col = columns - col - 1;
                float x = bounds.left + padding + col * cellW + (cellW - size) / 2;
                float y = bounds.top + padding + (slot / columns) * cellH + (cellH - size) / 2;
                rect.set(x, y, x + size, y + size);
                if (i >= mFullCount && overflow) {
                    if (i == mFullCount) mMoreBounds.set(rect);
                    int mini = i - mFullCount;
                    float miniSize = size * .43f;
                    int miniCol = mini % 2;
                    if (rtl) miniCol = 1 - miniCol;
                    x += miniCol * size * .54f;
                    y += (mini / 2) * size * .54f;
                    rect.set(x, y, x + miniSize, y + miniSize);
                }
            }
            boolean shown = i < mFullCount || (overflow && i < mFullCount + 4);
            boolean locked = mInfo.isPrivacyLocked();
            View icon = mIcons.get(i);
            icon.setVisibility(shown && !locked ? VISIBLE : GONE);
            icon.setContentDescription(!locked && i < mFullCount ? mItems.get(i).title : null);
            icon.setImportantForAccessibility(!locked && i < mFullCount
                    ? IMPORTANT_FOR_ACCESSIBILITY_YES : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        mMore.setVisibility(overflow && !mInfo.isPrivacyLocked() && !mEditing && mUpdatesOwner ? VISIBLE : GONE);
        mMore.setContentDescription(overflow && !mInfo.isPrivacyLocked()
                ? getContext().getString(R.string.folder_more_apps, mItems.size() - mFullCount) : null);
    }

    private void retarget(boolean animate) {
        if (getWidth() == 0) return;
        mAwaitingResizeLayout = false;
        calculateTargets();
        if (mAnimator != null) mAnimator.cancel();
        if (!animate || mBounds.isEmpty() || !ValueAnimator.areAnimatorsEnabled()) {
            if (!mInteractive) mBounds.set(mTargetBounds);
            mRadius = mTargetRadius;
            for (int i = 0; i < mRects.size(); i++) mRects.get(i).set(mTargets.get(i));
            mVisibleMoreBounds.set(mMoreBounds);
            updateSurface();
            return;
        }
        mFromBounds.set(mBounds);
        mStartRadius = mRadius;
        for (int i = 0; i < mRects.size(); i++) mStarts.get(i).set(mRects.get(i));
        mAnimator = ValueAnimator.ofFloat(0, 1);
        mAnimator.setDuration(280);
        mAnimator.setInterpolator(EMPHASIZED);
        mAnimator.addUpdateListener(animator -> {
            float f = (float) animator.getAnimatedValue();
            if (!mInteractive) interpolate(mBounds, mFromBounds, mTargetBounds, f);
            mRadius = mStartRadius + (mTargetRadius - mStartRadius) * f;
            updateIconGeometry(f);
            updateSurface();
        });
        mAnimator.start();
    }

    private void updateIconGeometry(float fraction) {
        float fromW = Math.max(1, mFromBounds.width());
        float fromH = Math.max(1, mFromBounds.height());
        float toW = Math.max(1, mTargetBounds.width());
        float toH = Math.max(1, mTargetBounds.height());
        for (int i = 0; i < mRects.size(); i++) {
            RectF start = mStarts.get(i);
            RectF end = mTargets.get(i);
            float cx = (start.centerX() - mFromBounds.left) / fromW;
            float cy = (start.centerY() - mFromBounds.top) / fromH;
            cx += ((end.centerX() - mTargetBounds.left) / toW - cx) * fraction;
            cy += ((end.centerY() - mTargetBounds.top) / toH - cy) * fraction;
            float size = start.width() + (end.width() - start.width()) * fraction;
            float x = mBounds.left + cx * mBounds.width() - size / 2;
            float y = mBounds.top + cy * mBounds.height() - size / 2;
            placeInside(mRects.get(i), x, y, size);
        }
        if (mMoreBounds.isEmpty()) mVisibleMoreBounds.setEmpty();
        else {
            float size = mMoreBounds.width();
            float x = mBounds.left + (mMoreBounds.centerX() - mTargetBounds.left) / toW
                    * mBounds.width() - size / 2;
            float y = mBounds.top + (mMoreBounds.centerY() - mTargetBounds.top) / toH
                    * mBounds.height() - size / 2;
            placeInside(mVisibleMoreBounds, x, y, size);
        }
    }

    private void placeInside(RectF rect, float x, float y, float size) {
        float inset = mInfo.isLarge() ? 8 * mDensity : 0;
        x = Math.max(mBounds.left + inset, Math.min(x, mBounds.right - inset - size));
        y = Math.max(mBounds.top + inset, Math.min(y, mBounds.bottom - inset - size));
        rect.set(x, y, x + size, y + size);
    }

    private void updateSurface() {
        for (int i = 0; i < mIcons.size(); i++) {
            View icon = mIcons.get(i);
            position(icon, mRects.get(i));
            boolean dropping = mDroppingItems.contains(mItems.get(i));
            icon.setAlpha(dropping ? 0 : 1);
            icon.setClickable(!dropping);
        }
        position(mMore, mVisibleMoreBounds);
        if (mUpdatesOwner) mOwner.alignLabelToPreview(mBounds);
        invalidate();
    }

    private void position(View view, RectF rect) {
        view.setTranslationX(rect.left);
        view.setTranslationY(rect.top);
        view.setScaleX(Math.max(.001f, rect.width() / mIconSize));
        view.setScaleY(Math.max(.001f, rect.height() / mIconSize));
    }

    private static void interpolate(RectF out, RectF from, RectF to, float f) {
        out.set(from.left + (to.left - from.left) * f, from.top + (to.top - from.top) * f,
                from.right + (to.right - from.right) * f, from.bottom + (to.bottom - from.bottom) * f);
    }

    void getSurfaceBounds(Rect out) { mBounds.roundOut(out); }

    float getCornerRadius() { return mRadius; }

    boolean getItemBounds(int index, RectF out) {
        if (index < 0 || index >= mRects.size() || mIcons.get(index).getVisibility() != VISIBLE) {
            return false;
        }
        out.set(mRects.get(index));
        return !out.isEmpty();
    }

    boolean getItemBounds(ItemInfo item, RectF out) {
        return getItemBounds(mItems.indexOf(item), out);
    }

    void getOverflowBounds(RectF out) { out.set(mVisibleMoreBounds); }

    void setItemDropping(ItemInfo item, boolean dropping) {
        if (dropping) mDroppingItems.add(item);
        else mDroppingItems.remove(item);
        updateSurface();
    }

    View findDirectTarget(Predicate<ItemInfo> matcher) {
        if (!mInfo.isLarge() || mInfo.isPrivacyLocked() || mEditing) return null;
        for (int i = 0; i < mFullCount && i < mIcons.size(); i++) {
            if (mIcons.get(i).getVisibility() == VISIBLE
                    && !mDroppingItems.contains(mItems.get(i)) && matcher.test(mItems.get(i))) {
                return mIcons.get(i);
            }
        }
        return null;
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).layout(0, 0, mIconSize, mIconSize);
        if (changed || mAwaitingResizeLayout) retarget(!mBounds.isEmpty());
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        mSurface.setBounds(Math.round(mBounds.left), Math.round(mBounds.top),
                Math.round(mBounds.right), Math.round(mBounds.bottom));
        mSurface.setCornerRadius(mRadius);
        mSurface.draw(canvas);
        int save = canvas.save();
        if (mInfo.isLarge()) {
            mClip.reset();
            mClip.addRoundRect(mBounds, mRadius, mRadius, Path.Direction.CW);
            canvas.clipPath(mClip);
        }
        if (!mInfo.isPrivacyLocked() && !mVisibleMoreBounds.isEmpty()) {
            float size = mVisibleMoreBounds.width();
            float mini = size * .43f;
            for (int i = 0; i < 4; i++) {
                float x = mVisibleMoreBounds.left + (i % 2) * size * .54f;
                float y = mVisibleMoreBounds.top + (i / 2) * size * .54f;
                canvas.drawCircle(x + mini / 2, y + mini / 2, mini / 2, mOverflowPaint);
            }
        }
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
        if (mInfo.isPrivacyLocked()) {
            int size = Math.round(Math.min(32 * mDensity, mBounds.width() * .55f));
            int x = Math.round(mBounds.centerX() - size / 2f);
            int y = Math.round(mBounds.centerY() - size / 2f);
            mLock.setBounds(x, y, x + size, y + size);
            mLock.draw(canvas);
        }
    }

    @Override protected void onDetachedFromWindow() {
        if (mAnimator != null) mAnimator.cancel();
        for (CancellableTask request : mIconRequests) request.cancel();
        mIconRequests.clear();
        mDroppingItems.clear();
        super.onDetachedFromWindow();
    }

    @Override protected boolean verifyDrawable(Drawable drawable) {
        return drawable == mSurface || super.verifyDrawable(drawable);
    }
}
