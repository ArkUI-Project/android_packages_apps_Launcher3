/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.folder;

import static com.android.app.animation.Interpolators.EMPHASIZED;
import static com.android.app.animation.Interpolators.LINEAR;

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
import com.android.launcher3.Utilities;
import com.android.launcher3.anim.SpringAnimationBuilder;
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
        if (icon.usesLargePreview()) {
            icon.getPreviewBounds(preview);
        } else {
            // getPreviewBounds also includes icons protruding beyond the compact circle.
            // The closing backdrop must match the actual background that replaces it.
            icon.getCompactPreviewBounds(preview);
        }
        RectF surface = new RectF(source.left + preview.left * sourceScale,
                source.top + preview.top * sourceScale, source.left + preview.right * sourceScale,
                source.top + preview.bottom * sourceScale);
        mFolder.prepareExpansion(surface, icon.getPreviewCornerRadius() * sourceScale);
        float progress = mFolder.getExpansionProgress();
        float distance = (opening ? 1f : 0f) - progress;
        SpringAnimationBuilder spring = new SpringAnimationBuilder(icon.getContext())
                .setValues(0f, 1f).setStiffness(opening ? 520f : 650f).setDampingRatio(.82f)
                .setMinimumVisibleChange(.001f)
                .setStartVelocity(Math.abs(distance) < .001f ? 0f
                        : mFolder.getExpansionVelocity() / distance).computeParams();
        long duration = Math.max(120L, spring.getDuration());
        AnimatorSet set = new AnimatorSet();
        ValueAnimator focus = ValueAnimator.ofFloat(0f, 1f);
        focus.setDuration(duration);
        focus.setInterpolator(LINEAR);
        set.play(focus);
        List<ItemMotion> motions = new ArrayList<>();

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
            boolean fromPreview = opening && progress == 0;
            view.setTranslationX(fromPreview ? dx : previous[0]);
            view.setTranslationY(fromPreview ? dy : previous[1]);
            view.setScaleX(fromPreview ? scale : previous[2]);
            view.setScaleY(fromPreview ? scale : previous[3]);
            view.setAlpha(fromPreview ? (inPreview ? 1 : 0) : previous[4]);
            motions.add(new ItemMotion(view, destination, iconBounds, start, inPreview));
            if (opening && progress == 0) label.setTextVisibility(false);
            Animator text = label.createTextAlphaAnimator(opening);
            text.setDuration(Math.min(duration, opening ? 180 : 120));
            text.setStartDelay(opening && progress == 0 ? 70 : 0);
            text.setInterpolator(EMPHASIZED);
            set.play(text);
        }

        float[] closingTravel = {0f};
        focus.addUpdateListener(animation -> {
            float fraction = animation.getAnimatedFraction();
            float travel = fraction >= 1f ? 1f : spring.getInterpolatedValue(fraction);
            if (!opening) {
                // The background stops at the closed preview, so its icons must stop there
                // too. Spring overshoot otherwise crosses their tiny slots, then rebounds
                // just before the static FolderIcon takes over.
                closingTravel[0] = Math.max(closingTravel[0],
                        Utilities.boundToRange(travel, 0f, 1f));
                travel = closingTravel[0];
            }
            float sourceScaleNow = dragLayer.getDescendantRectRelativeToSelf(icon, source);
            surface.set(source.left + preview.left * sourceScaleNow,
                    source.top + preview.top * sourceScaleNow,
                    source.left + preview.right * sourceScaleNow,
                    source.top + preview.bottom * sourceScaleNow);
            mFolder.updateExpansionSource(surface, icon.getPreviewCornerRadius() * sourceScaleNow);
            mFolder.setExpansionProgress(progress + distance * travel);
            // All icons sample the same spring and the same current desktop frame. The target
            // can keep moving during a page swipe without restarting any of these animations.
            for (ItemMotion item : motions) {
                item.update(travel, source, sourceScaleNow, opening);
            }
        });

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
                // Closing labels must stay hidden until Folder removes this view. Restoring
                // them here can expose full-size titles over the compact preview for a frame.
                if (opening) resetContent(mFolder);
            }
        });
        return set;
    }

    static void resetContent(Folder folder) {
        folder.mFooter.setTranslationY(0);
        folder.mFooter.setAlpha(1);
        folder.getPageControls().setAlpha(1);
        for (View item : folder.getIconsInReadingOrder()) {
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

    private static void play(AnimatorSet set, View view,
            android.util.Property<View, Float> property, float target, long duration) {
        ObjectAnimator animator = ObjectAnimator.ofFloat(view, property, target);
        animator.setDuration(duration);
        animator.setInterpolator(EMPHASIZED);
        set.play(animator);
    }

    private static final class ItemMotion {
        private final View mView;
        private final Rect mDestination;
        private final Rect mIconBounds;
        private final RectF mPreview;
        private final boolean mInPreview;
        private final float mX, mY, mScaleX, mScaleY, mAlpha;

        ItemMotion(View view, Rect destination, Rect iconBounds, RectF preview,
                boolean inPreview) {
            mView = view;
            mDestination = destination;
            mIconBounds = iconBounds;
            mPreview = preview;
            mInPreview = inPreview;
            mX = view.getTranslationX();
            mY = view.getTranslationY();
            mScaleX = view.getScaleX();
            mScaleY = view.getScaleY();
            mAlpha = view.getAlpha();
        }

        void update(float progress, Rect source, float sourceScale, boolean opening) {
            float x = opening ? 0f : source.left + mPreview.centerX() * sourceScale
                    - mDestination.left - mIconBounds.exactCenterX();
            float y = opening ? 0f : source.top + mPreview.centerY() * sourceScale
                    - mDestination.top - mIconBounds.exactCenterY();
            float scale = opening ? 1f
                    : mPreview.width() * sourceScale / Math.max(1, mIconBounds.width());
            mView.setTranslationX(Utilities.mapRange(progress, mX, x));
            mView.setTranslationY(Utilities.mapRange(progress, mY, y));
            mView.setScaleX(Math.max(.01f, Utilities.mapRange(progress, mScaleX, scale)));
            mView.setScaleY(Math.max(.01f, Utilities.mapRange(progress, mScaleY, scale)));
            float alpha = opening || mInPreview ? 1f : 0f;
            mView.setAlpha(Utilities.boundToRange(Utilities.mapRange(progress, mAlpha, alpha), 0f, 1f));
        }
    }
}
