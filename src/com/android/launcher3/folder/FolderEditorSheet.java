/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.folder;

import static com.android.app.animation.Interpolators.EMPHASIZED;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.FolderResizeFrame;
import com.android.launcher3.Insettable;
import com.android.launcher3.Launcher;
import com.android.launcher3.R;
import com.android.launcher3.model.data.FolderInfo;
import com.android.launcher3.util.SystemUiController;
import com.android.launcher3.views.AbstractSlideInView;
import com.android.launcher3.views.BaseDragLayer;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

/** A cancellable folder draft with a live preview, using native Material 3 Expressive controls. */
public final class FolderEditorSheet extends AbstractSlideInView<Launcher> implements Insettable {
    private final FolderIcon mOwner;
    private final FolderInfo mDraft = new FolderInfo();
    private final Rect mInsets = new Rect();
    private final Context mMaterialContext;
    private final LargeFolderPreview mPreview;
    private final TextInputEditText mName;
    private final MaterialSwitch mSmartSort;
    private final ScrollView mScroll;
    private final List<MaterialButton> mSizes = new ArrayList<>();
    private int mKeyboardInset;

    private FolderEditorSheet(FolderIcon owner) {
        super(owner.getContext(), null, 0);
        mOwner = owner;
        mDraft.copyFrom(owner.mInfo);
        // This sheet is shown only after authenticating a protected folder.
        mDraft.options &= ~FolderInfo.FLAG_PRIVACY_LOCKED;
        mMaterialContext = new ContextThemeWrapper(getContext(), R.style.ArkuiFolderEditorTheme);
        setId(R.id.folder_editor_sheet);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setAccessibilityPaneTitle(getContext().getString(R.string.folder_action_edit));
        setLayoutParams(new BaseDragLayer.LayoutParams(-1, -1));
        setFilterTouchesWhenObscured(true);

        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), 0);
        mContent = content;
        GradientDrawable background = rounded(color(R.color.materialColorSurfaceContainerLow), 32);
        background.setCornerRadii(new float[]{dp(32), dp(32), dp(32), dp(32), 0, 0, 0, 0});
        setContentBackgroundWithParent(background, content);
        addView(content);

        View handle = new View(getContext());
        handle.setBackground(rounded(color(R.color.materialColorOutlineVariant), 8));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(48), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(handle, handleParams);

        LinearLayout header = new LinearLayout(getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(header, new LinearLayout.LayoutParams(-1, dp(72)));
        MaterialButton cancel = headerButton(R.id.folder_editor_cancel, R.string.folder_editor_cancel, false);
        header.addView(cancel, new LinearLayout.LayoutParams(dp(48), dp(48)));
        cancel.setOnClickListener(view -> close(true));
        TextView title = text(R.string.folder_action_edit, 24, R.color.materialColorOnSurface);
        title.setGravity(Gravity.CENTER);
        title.setAccessibilityHeading(true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        MaterialButton save = headerButton(R.id.folder_editor_save, R.string.folder_editor_save, true);
        header.addView(save, new LinearLayout.LayoutParams(dp(48), dp(48)));
        save.setOnClickListener(view -> save());

        mScroll = new ScrollView(getContext());
        mScroll.setFillViewport(true);
        mScroll.setClipToPadding(false);
        content.addView(mScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = new LinearLayout(getContext());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(8), 0, dp(24));
        mScroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        TextInputLayout nameBox = new TextInputLayout(mMaterialContext);
        nameBox.setHint(getContext().getString(R.string.folder_editor_name));
        nameBox.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        nameBox.setBoxCornerRadii(dp(24), dp(24), dp(24), dp(24));
        mName = new TextInputEditText(mMaterialContext);
        mName.setBackground(null);
        mName.setId(R.id.folder_editor_name);
        mName.setSingleLine(true);
        mName.setMinHeight(dp(72));
        mName.setPaddingRelative(dp(20), dp(18), dp(20), dp(18));
        mName.setGravity(Gravity.CENTER_VERTICAL);
        mName.setText(owner.mInfo.title);
        mName.setTextSize(20);
        nameBox.addView(mName, new LinearLayout.LayoutParams(-1, -2));
        body.addView(nameBox, spaced(-1, -2, 0, 12));

        FrameLayout previewCard = new FrameLayout(getContext());
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{color(R.color.materialColorPrimaryContainer),
                        color(R.color.materialColorSecondaryContainer)});
        gradient.setCornerRadius(dp(32));
        previewCard.setBackground(gradient);
        previewCard.setClipToOutline(true);
        previewCard.setContentDescription(getContext().getString(R.string.folder_editor_preview));
        mPreview = new LargeFolderPreview(owner, mDraft);
        mPreview.setEditing(true);
        previewCard.addView(mPreview, new FrameLayout.LayoutParams(-1, -1));
        body.addView(previewCard, spaced(-1, dp(200), 0, 20));

        TextView sizes = text(R.string.folder_editor_size, 16, R.color.materialColorOnSurfaceVariant);
        sizes.setAccessibilityHeading(true);
        body.addView(sizes, spaced(-1, -2, 8, 12));
        HorizontalScrollView sizeScroll = new HorizontalScrollView(getContext());
        sizeScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout sizeRow = new LinearLayout(getContext());
        sizeScroll.addView(sizeRow);
        addSize(sizeRow, R.id.folder_size_1x1, 1, 1);
        addSize(sizeRow, R.id.folder_size_2x2, 2, 2);
        addSize(sizeRow, R.id.folder_size_2x1, 2, 1);
        addSize(sizeRow, R.id.folder_size_1x2, 1, 2);
        body.addView(sizeScroll, spaced(-1, -2, 0, 24));

        LinearLayout smartRow = new LinearLayout(getContext());
        smartRow.setGravity(Gravity.CENTER_VERTICAL);
        smartRow.setPadding(dp(16), dp(16), dp(12), dp(16));
        smartRow.setBackground(rounded(color(R.color.materialColorSurfaceContainer), 28));
        LinearLayout smartText = new LinearLayout(getContext());
        smartText.setOrientation(LinearLayout.VERTICAL);
        smartText.addView(text(R.string.folder_editor_smart_sort, 17, R.color.materialColorOnSurface));
        smartText.addView(text(R.string.folder_editor_smart_sort_summary, 13,
                R.color.materialColorOnSurfaceVariant));
        smartRow.addView(smartText, new LinearLayout.LayoutParams(0, -2, 1));
        mSmartSort = new MaterialSwitch(mMaterialContext);
        mSmartSort.setId(R.id.folder_editor_smart_sort);
        mSmartSort.setContentDescription(getContext().getString(R.string.folder_editor_smart_sort));
        mSmartSort.setChecked(mDraft.hasOption(FolderInfo.FLAG_SMART_PREVIEW));
        mSmartSort.setOnCheckedChangeListener((button, checked) -> {
            mDraft.setOption(FolderInfo.FLAG_SMART_PREVIEW, checked, null);
            mPreview.refresh();
        });
        smartRow.addView(mSmartSort);
        smartRow.setOnClickListener(view -> mSmartSort.toggle());
        body.addView(smartRow, new LinearLayout.LayoutParams(-1, -2));
        updateSizes();
        setOnApplyWindowInsetsListener((view, insets) -> {
            mKeyboardInset = insets.getInsets(WindowInsets.Type.ime()).bottom;
            requestLayout();
            return insets;
        });
    }

    public static void show(FolderIcon owner) {
        Launcher launcher = Launcher.getLauncher(owner.getContext());
        AbstractFloatingView.closeOpenViews(launcher, false, TYPE_FOLDER_EDITOR | TYPE_WIDGET_RESIZE_FRAME);
        if (owner.mInfo.isPrivacyLocked()) FolderPrivacy.secure(launcher);
        FolderEditorSheet sheet = new FolderEditorSheet(owner);
        sheet.attachToContainer();
        sheet.mIsOpen = true;
        sheet.requestApplyInsets();
        launcher.getSystemUiController().updateUiState(SystemUiController.UI_STATE_WIDGET_BOTTOM_SHEET,
                ColorUtils.calculateLuminance(sheet.color(R.color.materialColorSurfaceContainerLow)) > .5
                        ? SystemUiController.FLAG_LIGHT_NAV : SystemUiController.FLAG_DARK_NAV);
        sheet.setUpOpenAnimation(360).getAnimationPlayer().setInterpolator(EMPHASIZED);
        sheet.mOpenCloseAnimation.start();
    }

    public boolean isProtected() { return mOwner.mInfo.isPrivacyLocked(); }

    private void addSize(LinearLayout parent, int id, int x, int y) {
        MaterialButton button = new MaterialButton(mMaterialContext);
        button.setId(id);
        button.setText(getContext().getString(R.string.folder_editor_size_option, x, y));
        button.setTag(new int[]{x, y});
        button.setTextSize(14);
        button.setIcon(new SizeGlyph(x, y));
        button.setIconSize(dp(48));
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_TOP);
        button.setIconPadding(dp(8));
        button.setCornerRadius(dp(24));
        button.setPadding(dp(8), dp(12), dp(8), dp(8));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setOnClickListener(view -> {
            mPreview.prepareResize();
            mDraft.spanX = x;
            mDraft.spanY = y;
            mPreview.requestLayout();
            updateSizes();
        });
        mSizes.add(button);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(80), dp(104));
        params.setMarginEnd(dp(8));
        parent.addView(button, params);
    }

    private void updateSizes() {
        for (MaterialButton button : mSizes) {
            int[] size = (int[]) button.getTag();
            boolean selected = size[0] == mDraft.spanX && size[1] == mDraft.spanY;
            button.setSelected(selected);
            button.setStrokeWidth(selected ? dp(2) : 0);
            button.setStrokeColor(ColorStateList.valueOf(color(R.color.materialColorPrimary)));
            button.setBackgroundTintList(ColorStateList.valueOf(color(selected
                    ? R.color.materialColorPrimaryContainer : R.color.materialColorSurfaceContainerHighest)));
            int foreground = color(selected ? R.color.materialColorOnPrimaryContainer
                    : R.color.materialColorOnSurfaceVariant);
            button.setIconTint(ColorStateList.valueOf(foreground));
            button.setTextColor(foreground);
        }
    }

    private MaterialButton headerButton(int id, int description, boolean save) {
        MaterialButton button = new MaterialButton(mMaterialContext);
        button.setId(id);
        button.setContentDescription(getContext().getString(description));
        button.setIcon(new ActionGlyph(save));
        button.setIconSize(dp(24));
        button.setIconPadding(0);
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        button.setText("");
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setCornerRadius(dp(24));
        button.setBackgroundTintList(ColorStateList.valueOf(color(save
                ? R.color.materialColorPrimary : R.color.materialColorSurfaceContainerHighest)));
        button.setIconTint(ColorStateList.valueOf(color(save
                ? R.color.materialColorOnPrimary : R.color.materialColorOnSurface)));
        return button;
    }

    private void save() {
        if (!mOwner.isAttachedToWindow()) { close(true); return; }
        if (!FolderResizeFrame.resizeFolder(mOwner, mDraft.spanX, mDraft.spanY)) return;
        mOwner.mInfo.setOption(FolderInfo.FLAG_SMART_PREVIEW, mSmartSort.isChecked(),
                mActivityContext.getModelWriter());
        String title = mName.getText() == null ? "" : mName.getText().toString().trim();
        mOwner.mInfo.setTitle(title, mActivityContext.getModelWriter());
        mOwner.onTitleChanged(title);
        mOwner.refreshFolderPresentation();
        close(true);
    }

    @Override protected int getScrimColor(Context context) { return 0x66000000; }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        int bottom = Math.max(mInsets.bottom, mKeyboardInset);
        mContent.setPadding(dp(16), dp(12), dp(16), bottom);
        int contentHeight = Math.max(dp(160), height - mInsets.top - dp(20));
        mContent.measure(MeasureSpec.makeMeasureSpec(width - mInsets.left - mInsets.right,
                        MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY));
        setMeasuredDimension(width, height);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        mContent.layout(mInsets.left, getHeight() - mContent.getMeasuredHeight(),
                getWidth() - mInsets.right, getHeight());
        setTranslationShift(mTranslationShift);
    }

    @Override public boolean onControllerInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mNoIntercept = getPopupContainer().isEventOverView(mScroll, event);
        }
        return super.onControllerInterceptTouchEvent(event);
    }

    @Override protected void handleClose(boolean animate) {
        getContext().getSystemService(InputMethodManager.class)
                .hideSoftInputFromWindow(getWindowToken(), 0);
        handleClose(animate, 240);
    }

    @Override protected void onCloseComplete() {
        super.onCloseComplete();
        mActivityContext.getSystemUiController().updateUiState(
                SystemUiController.UI_STATE_WIDGET_BOTTOM_SHEET, 0);
        if (isProtected()) FolderPrivacy.releaseAfterCleanFrame(mActivityContext);
    }

    @Override protected boolean isOfType(int type) { return (type & TYPE_FOLDER_EDITOR) != 0; }

    @Override public void setInsets(Rect insets) { mInsets.set(insets); requestLayout(); }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int color(int resource) { return getContext().getColor(resource); }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }
    private TextView text(int string, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(string);
        view.setTextSize(size);
        view.setTextColor(color(color));
        return view;
    }
    private LinearLayout.LayoutParams spaced(int width, int height, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.topMargin = dp(top);
        params.bottomMargin = dp(bottom);
        return params;
    }

    private abstract static class Glyph extends Drawable {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ColorStateList tint;
        Glyph() { paint.setColor(Color.WHITE); }
        @Override public void setTintList(ColorStateList colors) {
            tint = colors;
            onStateChange(getState());
        }
        @Override protected boolean onStateChange(int[] state) {
            if (tint != null) paint.setColor(tint.getColorForState(state, tint.getDefaultColor()));
            invalidateSelf();
            return true;
        }
        @Override public boolean isStateful() { return tint != null && tint.isStateful(); }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static final class ActionGlyph extends Glyph {
        private final boolean save;
        ActionGlyph(boolean save) { this.save = save; }
        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            int restore = canvas.save();
            canvas.translate(bounds.left, bounds.top);
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f);
            paint.setStrokeWidth(2.4f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            if (save) {
                canvas.drawLine(4, 12, 10, 18, paint);
                canvas.drawLine(10, 18, 21, 5, paint);
            } else {
                canvas.drawLine(6, 6, 18, 18, paint);
                canvas.drawLine(18, 6, 6, 18, paint);
            }
            canvas.restoreToCount(restore);
        }
    }

    private static final class SizeGlyph extends Glyph {
        private final int columns;
        private final int rows;
        SizeGlyph(int x, int y) { columns = x == 2 ? 3 : y == 1 ? 2 : 1; rows = y == 2 ? 3 : x == 1 ? 2 : 1; }
        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float cell = Math.min(bounds.width() / 4f, bounds.height() / 4f);
            float left = bounds.centerX() - columns * cell / 2;
            float top = bounds.centerY() - rows * cell / 2;
            for (int row = 0; row < rows; row++) for (int col = 0; col < columns; col++) {
                float x = left + col * cell;
                float y = top + row * cell;
                canvas.drawRoundRect(x, y, x + cell * .8f, y + cell * .8f, cell * .24f, cell * .24f, paint);
            }
        }
    }
}
