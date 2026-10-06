/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.folder;

import static com.android.app.animation.Interpolators.EMPHASIZED;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import com.android.launcher3.BubbleTextView;
import com.android.launcher3.CellLayout;
import com.android.launcher3.R;
import com.android.launcher3.anim.RoundedRectRevealOutlineProvider;
import com.android.launcher3.apppairs.AppPairIcon;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.util.Themes;
import com.android.launcher3.views.BaseDragLayer;

import java.util.ArrayList;
import java.util.List;

/** Rectangular reveal and app-by-app motion from the actual large-folder preview. */
final class LargeFolderAnimationManager implements FolderAnimationCreator {
    private final Folder mFolder;

    LargeFolderAnimationManager(Folder folder) { mFolder = folder; }

    @Override public AnimatorSet createAnimatorSet(boolean opening) {
        FolderIcon icon = mFolder.mFolderIcon;
        BaseDragLayer dragLayer = mFolder.mActivityContext.getDragLayer();
        BaseDragLayer.LayoutParams lp = (BaseDragLayer.LayoutParams) mFolder.getLayoutParams();
        mFolder.setTranslationX(0);
        mFolder.setTranslationY(0);
        // Folder.onSizeChanged decides title visibility from the measured footer width.
        mFolder.measure(View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(lp.height, View.MeasureSpec.EXACTLY));
        mFolder.layout(lp.x, lp.y, lp.x + lp.width, lp.y + lp.height);
        Rect source = new Rect();
        float scale = dragLayer.getDescendantRectRelativeToSelf(icon, source);
        Rect preview = new Rect();
        icon.getPreviewBounds(preview);
        RectF surface = new RectF(source.left + preview.left * scale,
                source.top + preview.top * scale, source.left + preview.right * scale,
                source.top + preview.bottom * scale);
        float dx = surface.left - lp.x;
        float dy = surface.top - lp.y;
        mFolder.setTranslationX(opening ? dx : 0);
        mFolder.setTranslationY(opening ? dy : 0);

        AnimatorSet set = new AnimatorSet();
        set.setInterpolator(EMPHASIZED);
        play(set, motion(mFolder, View.TRANSLATION_X, dx, 0, opening));
        play(set, motion(mFolder, View.TRANSLATION_Y, dy, 0, opening));
        GradientDrawable background = (GradientDrawable) mFolder.getBackground();
        float radius = icon.getPreviewCornerRadius() * scale;
        Animator reveal = new RoundedRectRevealOutlineProvider(radius, background.getCornerRadius(),
                new Rect(0, 0, Math.round(surface.width()), Math.round(surface.height())),
                new Rect(0, 0, lp.width, lp.height)).createRevealAnimator(mFolder, !opening);
        play(set, reveal);
        ObjectAnimator color = ObjectAnimator.ofArgb(background, "color",
                Themes.getAttrColor(icon.getContext(), opening ? R.attr.folderPreviewColor
                        : R.attr.folderBackgroundColor),
                Themes.getAttrColor(icon.getContext(), opening ? R.attr.folderBackgroundColor
                        : R.attr.folderPreviewColor));
        play(set, color);

        List<View> items = new ArrayList<>(mFolder.getItemsOnPage(mFolder.mContent.getCurrentPage()));
        // Compute final item geometry before changing root translation and item transforms.
        mFolder.setTranslationX(0);
        mFolder.setTranslationY(0);
        List<Rect> destinations = new ArrayList<>();
        for (View item : items) {
            Rect bounds = new Rect();
            dragLayer.getDescendantRectRelativeToSelf(item, bounds);
            destinations.add(bounds);
        }
        mFolder.setTranslationX(opening ? dx : 0);
        mFolder.setTranslationY(opening ? dy : 0);
        for (int i = 0; i < items.size(); i++) {
            View view = items.get(i);
            BubbleTextView label = view instanceof BubbleTextView text ? text
                    : ((AppPairIcon) view).getTitleTextView();
            int baseSize = Math.max(1, label.getIconSize());
            Rect destination = destinations.get(i);
            float iconOffset = (destination.width() - baseSize) / 2f;
            RectF start = new RectF();
            boolean inPreview = !icon.mInfo.isPrivacyLocked()
                    && icon.getLargePreviewItemBounds((ItemInfo) view.getTag(), start);
            if (!inPreview) {
                float size = 12 * icon.getResources().getDisplayMetrics().density;
                start.set(preview.centerX() - size / 2, preview.centerY() - size / 2,
                        preview.centerX() + size / 2, preview.centerY() + size / 2);
            }
            float startX = source.left + start.left * scale - surface.left;
            float startY = source.top + start.top * scale - surface.top;
            float endX = destination.left - lp.x + iconOffset;
            float endY = destination.top - lp.y + view.getPaddingTop();
            float itemDx = startX - endX;
            float itemDy = startY - endY;
            float itemScale = start.width() * scale / baseSize;
            view.setPivotX(iconOffset);
            view.setPivotY(view.getPaddingTop());
            view.setTranslationX(opening ? itemDx : 0);
            view.setTranslationY(opening ? itemDy : 0);
            view.setScaleX(opening ? itemScale : 1);
            view.setScaleY(opening ? itemScale : 1);
            play(set, motion(view, View.TRANSLATION_X, itemDx, 0, opening));
            play(set, motion(view, View.TRANSLATION_Y, itemDy, 0, opening));
            play(set, motion(view, View.SCALE_X, itemScale, 1, opening));
            play(set, motion(view, View.SCALE_Y, itemScale, 1, opening));
            if (!inPreview) {
                view.setAlpha(opening ? 0 : 1);
                play(set, motion(view, View.ALPHA, 0, 1, opening));
            }
            if (opening) label.setTextVisibility(false);
            play(set, label.createTextAlphaAnimator(opening));
        }
        mFolder.mFooter.setAlpha(opening ? 0 : 1);
        play(set, motion(mFolder.mFooter, View.ALPHA, 0, 1, opening));
        CellLayout page = mFolder.mContent.getCurrentCellLayout();
        boolean folderClip = mFolder.getClipChildren();
        boolean contentClip = mFolder.mContent.getClipChildren();
        boolean pageClip = page.getClipChildren();
        mFolder.setClipChildren(false);
        mFolder.mContent.setClipChildren(false);
        page.setClipChildren(false);
        set.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animator) {
                mFolder.setTranslationX(0);
                mFolder.setTranslationY(0);
                mFolder.mFooter.setAlpha(1);
                mFolder.setClipChildren(folderClip);
                mFolder.mContent.setClipChildren(contentClip);
                page.setClipChildren(pageClip);
                for (View item : items) {
                    item.setTranslationX(0);
                    item.setTranslationY(0);
                    item.setScaleX(1);
                    item.setScaleY(1);
                    item.setAlpha(1);
                    BubbleTextView text = item instanceof BubbleTextView bubble ? bubble
                            : ((AppPairIcon) item).getTitleTextView();
                    text.setTextVisibility(true);
                }
            }
        });
        return set;
    }

    private static ObjectAnimator motion(View view, android.util.Property<View, Float> property,
            float collapsed, float expanded, boolean opening) {
        return ObjectAnimator.ofFloat(view, property,
                opening ? collapsed : expanded, opening ? expanded : collapsed);
    }

    private static void play(AnimatorSet set, Animator animator) {
        animator.setDuration(320);
        set.play(animator);
    }
}
