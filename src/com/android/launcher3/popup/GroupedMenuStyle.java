/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.popup;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;

import com.android.launcher3.R;

import java.util.ArrayList;

/** Shared connected-row geometry for app and overview menus. */
public final class GroupedMenuStyle {
    private GroupedMenuStyle() { }

    public static GradientDrawable surface(Context context, boolean first, boolean last) {
        float outer = context.getResources().getDimension(R.dimen.arkui_menu_outer_radius);
        float inner = context.getResources().getDimension(R.dimen.arkui_menu_inner_radius);
        float top = first ? outer : inner;
        float bottom = last ? outer : inner;
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(context.getColor(R.color.materialColorSurfaceBright));
        shape.setCornerRadii(new float[]{top, top, top, top, bottom, bottom, bottom, bottom});
        return shape;
    }

    /** Applies first/middle/last shapes without putting a surface behind the group. */
    public static void applyToGroup(ViewGroup group) {
        group.setBackground(null);
        ArrayList<View> rows = new ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) rows.add(child);
        }
        for (int i = 0; i < rows.size(); i++) {
            View row = rows.get(i);
            row.setElevation(0);
            row.setBackground(new RippleDrawable(
                    group.getContext().getColorStateList(R.color.arkui_app_icon_menu_ripple),
                    surface(group.getContext(), i == 0, i == rows.size() - 1), null));
        }
    }

    /** Places an action glyph in a tonal circle, preserving the original action and label. */
    public static void styleSystemIcon(View iconView) {
        Drawable glyph = iconView.getBackground();
        if (glyph == null) return;
        Context context = iconView.getContext();
        glyph = glyph.mutate();
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(context.getColor(R.color.materialColorPrimaryContainer));
        int inset = Math.round(10 * context.getResources().getDisplayMetrics().density);
        iconView.setBackgroundTintList(ColorStateList.valueOf(
                context.getColor(R.color.materialColorPrimaryContainer)));
        iconView.setBackground(circle);
        iconView.setForeground(new InsetDrawable(glyph, inset));
        iconView.setForegroundTintList(ColorStateList.valueOf(
                context.getColor(R.color.materialColorOnPrimaryContainer)));
    }
}
