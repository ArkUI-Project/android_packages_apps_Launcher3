/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.folder;

import static com.android.app.animation.Interpolators.EMPHASIZED;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;

import com.android.launcher3.BubbleTextView;
import com.android.launcher3.CellLayout;
import com.android.launcher3.apppairs.AppPairIcon;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.views.BaseDragLayer;

import java.util.ArrayList;
import java.util.List;

/** Expand the actual preview icons over the desktop while its blur comes into focus. */
final class FullScreenFolderAnimationManager implements FolderAnimationCreator {
    private final Folder mFolder;

    FullScreenFolderAnimationManager(Folder folder) { mFolder = folder; }

    @Override public AnimatorSet createAnimatorSet(boolean opening) {
        FolderIcon icon = mFolder.mFolderIcon;
        BaseDragLayer dragLayer = mFolder.mActivityContext.getDragLayer();
        BaseDragLayer.LayoutParams lp = (BaseDragLayer.LayoutParams) mFolder.getLayoutParams();
        List<View> items = new ArrayList<>(mFolder.getItemsOnPage(mFolder.mContent.getCurrentPage()));
        List<float[]> current = new ArrayList<>();
        for (View item : items) {
            current.add(new float[]{item.getTranslationX(), item.getTranslationY(),
                    item.getScaleX(), item.getScaleY(), item.getAlpha()});
            item.setTranslationX(0);
            item.setTranslationY(0);
            item.setScaleX(1);
            item.setScaleY(1);
        }
        mFolder.setTranslationX(0);
        mFolder.setTranslationY(0);
        mFolder.measure(View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(lp.height, View.MeasureSpec.EXACTLY));
        mFolder.layout(lp.x, lp.y, lp.x + lp.width, lp.y + lp.height);

        Rect source = new Rect();
        float sourceScale = dragLayer.getDescendantRectRelativeToSelf(icon, source);
        Rect preview = new Rect();
        icon.getPreviewBounds(preview);
        RectF surface = new RectF(source.left + preview.left * sourceScale,
                source.top + preview.top * sourceScale, source.left + preview.right * sourceScale,
                source.top + preview.bottom * sourceScale);
        mFolder.prepareExpansion(surface, icon.getPreviewCornerRadius() * sourceScale);
        float progress = mFolder.getExpansionProgress();
        long duration = Math.max(120, Math.round((opening ? 360 : 260)
                * (opening ? 1 - progress : progress)));
        AnimatorSet set = new AnimatorSet();
        set.setInterpolator(EMPHASIZED);
        ValueAnimator focus = ValueAnimator.ofFloat(progress, opening ? 1 : 0);
        focus.setDuration(duration);
        focus.addUpdateListener(animation -> mFolder.setExpansionProgress(
                (float) animation.getAnimatedValue()));
        set.play(focus);

        for (int i = 0; i < items.size(); i++) {
            View view = items.get(i);
            BubbleTextView label = view instanceof BubbleTextView text ? text
                    : ((AppPairIcon) view).getTitleTextView();
            Rect destination = new Rect();
            dragLayer.getDescendantRectRelativeToSelf(view, destination);
            Rect iconBounds = new Rect();
            if (view instanceof BubbleTextView text) {
                text.getIconBounds(iconBounds);
            } else {
                ((AppPairIcon) view).getWorkspaceVisualDragBounds(iconBounds);
            }
            RectF start = new RectF();
            boolean inPreview = icon.getExpandedPreviewItemBounds((ItemInfo) view.getTag(), start);
            if (!inPreview) {
                float size = 12 * icon.getResources().getDisplayMetrics().density;
                start.set(preview.centerX() - size / 2, preview.centerY() - size / 2,
                        preview.centerX() + size / 2, preview.centerY() + size / 2);
            }
            float dx = source.left + start.centerX() * sourceScale
                    - destination.left - iconBounds.exactCenterX();
            float dy = source.top + start.centerY() * sourceScale
                    - destination.top - iconBounds.exactCenterY();
            float scale = start.width() * sourceScale / Math.max(1, iconBounds.width());
            view.setPivotX(iconBounds.exactCenterX());
            view.setPivotY(iconBounds.exactCenterY());
            float[] previous = current.get(i);
            view.setTranslationX(opening ? dx * (1 - progress) : previous[0]);
            view.setTranslationY(opening ? dy * (1 - progress) : previous[1]);
            view.setScaleX(opening ? scale + (1 - scale) * progress : previous[2]);
            view.setScaleY(opening ? scale + (1 - scale) * progress : previous[3]);
            view.setAlpha(opening ? (inPreview ? 1 : progress) : previous[4]);
            play(set, view, View.TRANSLATION_X, opening ? 0 : dx, duration);
            play(set, view, View.TRANSLATION_Y, opening ? 0 : dy, duration);
            play(set, view, View.SCALE_X, opening ? 1 : scale, duration);
            play(set, view, View.SCALE_Y, opening ? 1 : scale, duration);
            if (!inPreview) play(set, view, View.ALPHA, opening ? 1 : 0, duration);
            if (opening && progress == 0) label.setTextVisibility(false);
            Animator text = label.createTextAlphaAnimator(opening);
            text.setDuration(Math.min(duration, opening ? 180 : 120));
            text.setStartDelay(opening && progress == 0 ? 70 : 0);
            set.play(text);
        }

        if (opening && progress == 0) {
            mFolder.mFooter.setAlpha(0);
            mFolder.mFooter.setTranslationY(8 * icon.getResources().getDisplayMetrics().density);
            mFolder.getPageControls().setAlpha(0);
        }
        play(set, mFolder.mFooter, View.ALPHA, opening ? 1 : 0, Math.min(duration, 220));
        play(set, mFolder.mFooter, View.TRANSLATION_Y,
                opening ? 0 : 8 * icon.getResources().getDisplayMetrics().density, duration);
        play(set, mFolder.getPageControls(), View.ALPHA, opening ? 1 : 0,
                Math.min(duration, 220));

        CellLayout page = mFolder.mContent.getCurrentCellLayout();
        List<ViewGroup> groups = List.of(mFolder, mFolder.mContent, page,
                page.getShortcutsAndWidgets());
        List<boolean[]> clips = new ArrayList<>();
        for (ViewGroup group : groups) {
            clips.add(new boolean[]{group.getClipChildren(), group.getClipToPadding()});
            group.setClipChildren(false);
            group.setClipToPadding(false);
        }
        set.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override public void onAnimationCancel(Animator animation) { mCancelled = true; }

            @Override public void onAnimationEnd(Animator animation) {
                for (int i = 0; i < groups.size(); i++) {
                    groups.get(i).setClipChildren(clips.get(i)[0]);
                    groups.get(i).setClipToPadding(clips.get(i)[1]);
                }
                if (mCancelled) return;
                mFolder.setExpansionProgress(opening ? 1 : 0);
                mFolder.mFooter.setTranslationY(0);
                mFolder.mFooter.setAlpha(1);
                mFolder.getPageControls().setAlpha(1);
                for (View item : items) {
                    item.setTranslationX(0);
                    item.setTranslationY(0);
                    item.setScaleX(1);
                    item.setScaleY(1);
                    item.setAlpha(1);
                    BubbleTextView label = item instanceof BubbleTextView text ? text
                            : ((AppPairIcon) item).getTitleTextView();
                    label.setTextVisibility(true);
                }
            }
        });
        return set;
    }

    private static void play(AnimatorSet set, View view,
            android.util.Property<View, Float> property, float target, long duration) {
        ObjectAnimator animator = ObjectAnimator.ofFloat(view, property, target);
        animator.setDuration(duration);
        set.play(animator);
    }
}
