package app.bitchord.soulseek;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;

import app.bitchord.telegram.PatchStrings;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Native download screens use BitChord's theme preference, SF Pro fonts and grouped surfaces. */
final class DownloadStyle {
    final Activity activity;
    final boolean dark;
    final int background, card, foreground, secondary, outline, field;
    final Typeface regular, medium, semibold, heavy;
    private final Map<View, Boolean> styled = Collections.synchronizedMap(new WeakHashMap<>());

    DownloadStyle(Activity activity) {
        this.activity = activity;
        dark = isDark(activity);
        background = dark ? Color.BLACK : Color.WHITE;
        foreground = dark ? Color.WHITE : Color.BLACK;
        card = dark ? 0xff1c1c1e : 0xfff2f2f7;
        secondary = dark ? 0xff8e8e93 : 0xff6e6e73;
        outline = dark ? 0xff2c2c2e : 0xffe5e5ea;
        field = outline;
        regular = font("regular", Typeface.NORMAL);
        medium = font("medium", Typeface.NORMAL);
        semibold = font("semibold", Typeface.BOLD);
        heavy = font("heavy", Typeface.BOLD);
    }

    static boolean isDark(Activity activity) {
        try {
            Object flow =
                    Class.forName("com.music.bitchord.data.settings.AppSettings")
                            .getField("themeMode")
                            .get(null);
            Object value =
                    Class.forName("kotlinx.coroutines.flow.StateFlow")
                            .getMethod("getValue")
                            .invoke(flow);
            if ("DARK".equals(value.toString())) return true;
            if ("LIGHT".equals(value.toString())) return false;
        } catch (Exception ignored) {
            // System mode is also BitChord's fallback when its settings are unavailable.
        }
        return (activity.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private Typeface font(String weight, int fallback) {
        try {
            int id =
                    activity.getResources()
                            .getIdentifier(
                                    "sf_pro_display_" + weight, "font", activity.getPackageName());
            if (id != 0) return activity.getResources().getFont(id);
        } catch (Exception ignored) {
        }
        return Typeface.create("sans-serif", fallback);
    }

    GradientDrawable shape(int color, int radius, boolean border) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(dp(radius));
        if (border) result.setStroke(dp(1), outline);
        return result;
    }

    TextView text(String value, int size, int color, Typeface face) {
        TextView result = new TextView(activity);
        result.setText(value);
        result.setTextSize(size);
        result.setTextColor(color);
        result.setTypeface(face);
        result.setFontFeatureSettings("kern");
        markCustom(result);
        return result;
    }

    android.app.Dialog settingsPanel(
            String title,
            String message,
            String[] icons,
            String[] labels,
            java.util.function.IntConsumer selected) {
        android.app.Dialog dialog = newPanelDialog();
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) {
                View rule = new View(activity);
                rule.setBackgroundColor(outline);
                LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, dp(1));
                spacing.setMargins(dp(36), 0, 0, 0);
                actions.addView(rule, spacing);
            }
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(58));
            row.setPadding(0, dp(12), 0, dp(12));
            ImageView icon = new ImageView(activity);
            icon.setImageDrawable(viewIcon(icons[i]));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
            TextView label = text(labels[i], 15, foreground, medium);
            label.setPadding(dp(14), 0, dp(4), 0);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            row.setContentDescription(labels[i]);
            row.setTag("download-setting:" + i);
            row.setBackground(
                    new RippleDrawable(
                            ColorStateList.valueOf(dark ? 0x22ffffff : 0x17000000),
                            shape(Color.TRANSPARENT, 12, false),
                            null));
            row.setClickable(true);
            row.setFocusable(true);
            row.setAccessibilityDelegate(
                    new View.AccessibilityDelegate() {
                        @Override
                        public void onInitializeAccessibilityNodeInfo(
                                View host, android.view.accessibility.AccessibilityNodeInfo info) {
                            super.onInitializeAccessibilityNodeInfo(host, info);
                            info.setClassName(Button.class.getName());
                        }
                    });
            final int index = i;
            row.setOnClickListener(
                    v -> {
                        dialog.dismiss();
                        selected.accept(index);
                    });
            actions.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        return panel(dialog, title, message, actions, "download-settings-panel");
    }

    android.app.Dialog detailsPanel(String title, String message, View content) {
        return panel(newPanelDialog(), title, message, content, "download-details-panel");
    }

    private android.app.Dialog newPanelDialog() {
        return new android.app.Dialog(
                activity,
                dark
                        ? android.R.style.Theme_Material_Dialog_NoActionBar
                        : android.R.style.Theme_Material_Light_Dialog_NoActionBar);
    }

    private android.app.Dialog panel(
            android.app.Dialog dialog, String title, String message, View content, String tag) {
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setTag(tag);
        panel.setPadding(dp(20), dp(22), dp(20), dp(12));
        TextView heading = text(title, 22, foreground, semibold);
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        panel.addView(heading);
        TextView description = text(message, 14, secondary, regular);
        description.setPadding(0, dp(6), 0, dp(14));
        panel.addView(description);
        panel.addView(content);
        TextView done = text(PatchStrings.t("Done"), 16, foreground, semibold);
        done.setGravity(Gravity.CENTER);
        done.setMinHeight(dp(48));
        done.setBackground(shape(field, 24, false));
        done.setTag("download-settings-done");
        done.setFocusable(true);
        done.setOnClickListener(v -> dialog.dismiss());
        done.setAccessibilityDelegate(
                new View.AccessibilityDelegate() {
                    @Override
                    public void onInitializeAccessibilityNodeInfo(
                            View host, android.view.accessibility.AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setClassName(Button.class.getName());
                    }
                });
        LinearLayout.LayoutParams doneSpace = new LinearLayout.LayoutParams(-1, -2);
        doneSpace.topMargin = dp(14);
        panel.addView(done, doneSpace);
        android.widget.ScrollView scroll =
                new android.widget.ScrollView(activity) {
                    @Override
                    protected void onMeasure(int width, int height) {
                        int max =
                                activity.getResources().getDisplayMetrics().heightPixels * 85 / 100;
                        super.onMeasure(
                                width,
                                View.MeasureSpec.makeMeasureSpec(
                                        Math.min(max, View.MeasureSpec.getSize(height)),
                                        View.MeasureSpec.AT_MOST));
                    }
                };
        scroll.addView(panel);
        dialog.setContentView(scroll);
        dialog.setCanceledOnTouchOutside(true);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            boolean blur =
                    android.os.Build.VERSION.SDK_INT >= 31
                            && activity.getWindowManager().isCrossWindowBlurEnabled();
            window.setBackgroundDrawable(
                    shape(blur ? (dark ? 0xe61c1c1e : 0xf2f2f2f7) : card, 22, false));
            android.view.WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.flags |= android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            window.setAttributes(attributes);
            window.setDimAmount(.28f);
            if (blur) window.setBackgroundBlurRadius(dp(24));
        }
        dialog.show();
        if (window != null)
            window.setLayout(
                    Math.min(
                            dp(360),
                            activity.getResources().getDisplayMetrics().widthPixels - dp(40)),
                    -2);
        return dialog;
    }

    /** Custom controls may opt out of the generic first-layout styling pass. */
    void markCustom(View view) {
        styled.put(view, true);
    }

    void card(LinearLayout view) {
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(16), dp(12), dp(16), dp(12));
        view.setBackground(shape(card, 16, false));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(12), 0, dp(12));
        view.setLayoutParams(params);
        markCustom(view);
    }

    void button(Button button) {
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTypeface(semibold);
        button.setTextColor(colors(foreground, secondary));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(50));
        button.setMinimumHeight(dp(50));
        button.setMinWidth(dp(48));
        button.setMinimumWidth(dp(48));
        button.setPadding(dp(18), dp(12), dp(18), dp(12));
        button.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(dark ? 0x228e8e93 : 0x22000000),
                        shape(field, 28, false),
                        null));
        button.setBackgroundTintList(null);
        button.setElevation(0);
        button.setStateListAnimator(null);
        if (button.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) button.getLayoutParams();
            params.topMargin = dp(6);
            params.bottomMargin = dp(4);
            button.setLayoutParams(params);
        }
        markCustom(button);
    }

    void navigation(Button button) {
        button(button);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setTypeface(medium);
        button.setMinimumHeight(dp(56));
        button.setMinHeight(dp(56));
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        button.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(dark ? 0x228e8e93 : 0x22000000),
                        new ColorDrawable(Color.TRANSPARENT),
                        null));
        button.setCompoundDrawablePadding(dp(12));
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(
                null,
                null,
                new Drawable() {
                    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

                    public void draw(Canvas canvas) {
                        paint.setColor(secondary);
                        paint.setStrokeWidth(dp(1.7f));
                        paint.setStrokeCap(Paint.Cap.ROUND);
                        float x = getBounds().exactCenterX(), y = getBounds().exactCenterY();
                        float direction =
                                button.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1 : 1;
                        canvas.drawLine(
                                x - direction * dp(3), y - dp(5), x + direction * dp(2), y, paint);
                        canvas.drawLine(
                                x + direction * dp(2), y, x - direction * dp(3), y + dp(5), paint);
                    }

                    public int getIntrinsicWidth() {
                        return dp(20);
                    }

                    public int getIntrinsicHeight() {
                        return dp(20);
                    }

                    public void setAlpha(int alpha) {
                        paint.setAlpha(alpha);
                    }

                    public void setColorFilter(ColorFilter filter) {
                        paint.setColorFilter(filter);
                    }

                    public int getOpacity() {
                        return PixelFormat.TRANSLUCENT;
                    }
                },
                null);
        if (button.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams space = (LinearLayout.LayoutParams) button.getLayoutParams();
            space.topMargin = 0;
            space.bottomMargin = 0;
            button.setLayoutParams(space);
        }
        markCustom(button);
    }

    void textAction(Button button) {
        button(button);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setTextSize(14);
        button.setTypeface(medium);
        button.setTextColor(colors(secondary, secondary));
        button.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(dark ? 0x228e8e93 : 0x22000000),
                        new ColorDrawable(Color.TRANSPARENT),
                        null));
        markCustom(button);
    }

    void inline(Button button, boolean first) {
        button(button);
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(0, -2, 1);
        space.topMargin = dp(8);
        space.bottomMargin = dp(8);
        if (first) space.rightMargin = dp(8);
        button.setLayoutParams(space);
    }

    void field(EditText input) {
        input.setTextSize(16);
        input.setTypeface(regular);
        input.setTextColor(colors(foreground, secondary));
        input.setHintTextColor(secondary);
        input.setMinHeight(dp(52));
        input.setPadding(dp(16), dp(14), dp(16), dp(14));
        input.setBackground(shape(field, 14, false));
        input.setBackgroundTintList(null);
        if (input.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) input.getLayoutParams();
            params.topMargin = dp(8);
            params.bottomMargin = dp(8);
            input.setLayoutParams(params);
        }
        markCustom(input);
    }

    private ColorStateList colors(int enabled, int disabled) {
        return new ColorStateList(
                new int[][] {new int[] {-android.R.attr.state_enabled}, new int[0]},
                new int[] {disabled, enabled});
    }

    /**
     * Material Icons Rounded Settings, the same Icons.Rounded.Settings used by BitChord. Path from
     * google/material-design-icons (Apache-2.0), action/settings/materialiconsround/24px.svg.
     */
    ImageButton settingsButton() {
        ImageButton button = new ImageButton(activity);
        button.setImageTintList(ColorStateList.valueOf(foreground));
        button.setPadding(0, 0, 0, 0);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setImageDrawable(
                new Drawable() {
                    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    final Path path = settingsPath();

                    {
                        paint.setColor(foreground);
                    }

                    @Override
                    public void draw(Canvas canvas) {
                        canvas.save();
                        float scale = Math.min(getBounds().width(), getBounds().height()) / 24f;
                        canvas.translate(
                                getBounds().exactCenterX() - 12 * scale,
                                getBounds().exactCenterY() - 12 * scale);
                        canvas.scale(scale, scale);
                        canvas.drawPath(path, paint);
                        canvas.restore();
                    }

                    @Override
                    public int getIntrinsicWidth() {
                        return dp(24);
                    }

                    @Override
                    public int getIntrinsicHeight() {
                        return dp(24);
                    }

                    @Override
                    public void setAlpha(int alpha) {
                        paint.setAlpha(alpha);
                        invalidateSelf();
                    }

                    @Override
                    public void setColorFilter(ColorFilter filter) {
                        paint.setColorFilter(filter);
                        invalidateSelf();
                    }

                    @Override
                    public int getOpacity() {
                        return PixelFormat.TRANSLUCENT;
                    }
                });
        button.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(dark ? 0x228e8e93 : 0x22000000),
                        shape(Color.TRANSPARENT, 24, false),
                        null));
        button.setBackgroundTintList(null);
        markCustom(button);
        return button;
    }

    private static Path settingsPath() {
        Path path = new Path();
        path.moveTo(19.5f, 12f);
        path.cubicTo(19.5f, 11.77f, 19.49f, 11.55f, 19.47f, 11.32f);
        path.lineTo(21.33f, 9.91f);
        path.cubicTo(21.73f, 9.61f, 21.84f, 9.05f, 21.59f, 8.61f);
        path.lineTo(19.72f, 5.38f);
        path.cubicTo(19.47f, 4.94f, 18.93f, 4.76f, 18.47f, 4.96f);
        path.lineTo(16.32f, 5.87f);
        path.cubicTo(15.95f, 5.61f, 15.56f, 5.38f, 15.15f, 5.19f);
        path.lineTo(14.86f, 2.88f);
        path.cubicTo(14.8f, 2.38f, 14.37f, 2f, 13.87f, 2f);
        path.lineTo(10.14f, 2f);
        path.cubicTo(9.63f, 2f, 9.2f, 2.38f, 9.14f, 2.88f);
        path.lineTo(8.85f, 5.19f);
        path.cubicTo(8.44f, 5.38f, 8.05f, 5.61f, 7.68f, 5.87f);
        path.lineTo(5.53f, 4.96f);
        path.cubicTo(5.07f, 4.76f, 4.53f, 4.94f, 4.28f, 5.38f);
        path.lineTo(2.41f, 8.62f);
        path.cubicTo(2.16f, 9.06f, 2.27f, 9.61f, 2.67f, 9.92f);
        path.lineTo(4.53f, 11.33f);
        path.cubicTo(4.51f, 11.55f, 4.5f, 11.77f, 4.5f, 12f);
        path.cubicTo(4.5f, 12.23f, 4.51f, 12.45f, 4.53f, 12.68f);
        path.lineTo(2.67f, 14.09f);
        path.cubicTo(2.27f, 14.39f, 2.16f, 14.95f, 2.41f, 15.39f);
        path.lineTo(4.28f, 18.62f);
        path.cubicTo(4.53f, 19.06f, 5.07f, 19.24f, 5.53f, 19.04f);
        path.lineTo(7.68f, 18.13f);
        path.cubicTo(8.05f, 18.39f, 8.44f, 18.62f, 8.85f, 18.81f);
        path.lineTo(9.14f, 21.12f);
        path.cubicTo(9.2f, 21.62f, 9.63f, 22f, 10.13f, 22f);
        path.lineTo(13.86f, 22f);
        path.cubicTo(14.36f, 22f, 14.79f, 21.62f, 14.85f, 21.12f);
        path.lineTo(15.14f, 18.81f);
        path.cubicTo(15.55f, 18.62f, 15.94f, 18.39f, 16.31f, 18.13f);
        path.lineTo(18.46f, 19.04f);
        path.cubicTo(18.92f, 19.24f, 19.46f, 19.06f, 19.71f, 18.62f);
        path.lineTo(21.58f, 15.39f);
        path.cubicTo(21.83f, 14.95f, 21.72f, 14.4f, 21.32f, 14.09f);
        path.lineTo(19.46f, 12.68f);
        path.cubicTo(19.49f, 12.45f, 19.5f, 12.23f, 19.5f, 12f);
        path.close();
        path.moveTo(12.04f, 15.5f);
        path.cubicTo(10.11f, 15.5f, 8.54f, 13.93f, 8.54f, 12f);
        path.cubicTo(8.54f, 10.07f, 10.11f, 8.5f, 12.04f, 8.5f);
        path.cubicTo(13.97f, 8.5f, 15.54f, 10.07f, 15.54f, 12f);
        path.cubicTo(15.54f, 13.93f, 13.97f, 15.5f, 12.04f, 15.5f);
        path.close();
        return path;
    }

    CompoundButton switchButton() {
        CompoundButton toggle =
                new CompoundButton(activity) {
                    @Override
                    public CharSequence getAccessibilityClassName() {
                        return "android.widget.Switch";
                    }
                };
        toggle.setButtonDrawable((Drawable) null);
        toggle.setCompoundDrawablesRelativeWithIntrinsicBounds(
                null, null, new ToggleDrawable(), null);
        toggle.setPadding(0, dp(10), 0, dp(10));
        toggle.setMinHeight(dp(48));
        toggle.setMinWidth(dp(64));
        toggle.setBackground(null);
        toggle.setClickable(true);
        toggle.setFocusable(true);
        markCustom(toggle);
        return toggle;
    }

    Drawable switchDrawable() {
        return new ToggleDrawable();
    }

    /** Native filter and admission symbols. */
    Drawable explorerIcon(String kind, int tint) {
        return new Drawable() {
            final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(Canvas canvas) {
                canvas.save();
                canvas.translate(getBounds().left, getBounds().top);
                canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
                ink.setColor(tint);
                ink.setStrokeWidth(1.7f);
                ink.setStrokeCap(Paint.Cap.ROUND);
                ink.setStrokeJoin(Paint.Join.ROUND);
                if (kind.equals("lossless")) {
                    ink.setStyle(Paint.Style.STROKE);
                    canvas.drawLine(4, 9, 4, 15, ink);
                    canvas.drawLine(9, 5, 9, 19, ink);
                    canvas.drawLine(14, 2, 14, 22, ink);
                    canvas.drawLine(19, 7, 19, 17, ink);
                } else if (kind.equals("search")) {
                    ink.setStyle(Paint.Style.STROKE);
                    canvas.drawCircle(10, 10, 6, ink);
                    canvas.drawLine(15, 15, 21, 21, ink);
                } else if (kind.equals("upload")) {
                    ink.setStyle(Paint.Style.STROKE);
                    canvas.drawLine(12, 3, 12, 17, ink);
                    canvas.drawLine(7, 8, 12, 3, ink);
                    canvas.drawLine(17, 8, 12, 3, ink);
                    canvas.drawLine(4, 17, 4, 21, ink);
                    canvas.drawLine(4, 21, 20, 21, ink);
                    canvas.drawLine(20, 17, 20, 21, ink);
                } else if (kind.equals("clock")) {
                    ink.setStyle(Paint.Style.STROKE);
                    canvas.drawCircle(12, 12, 9, ink);
                    canvas.drawLine(12, 6, 12, 12, ink);
                    canvas.drawLine(12, 12, 17, 15, ink);
                } else if (kind.equals("preview")) {
                    ink.setStyle(Paint.Style.FILL);
                    Path play = new Path();
                    play.moveTo(8, 4);
                    play.lineTo(21, 12);
                    play.lineTo(8, 20);
                    play.close();
                    canvas.drawPath(play, ink);
                } else if (kind.equals("check")) {
                    ink.setStyle(Paint.Style.STROKE);
                    Path check = new Path();
                    check.moveTo(5, 12);
                    check.lineTo(10, 17);
                    check.lineTo(20, 6);
                    canvas.drawPath(check, ink);
                } else if (kind.equals("available")) {
                    ink.setStyle(Paint.Style.FILL);
                    ink.setAlpha(35);
                    canvas.drawCircle(12, 12, 9, ink);
                    ink.setAlpha(75);
                    canvas.drawCircle(12, 12, 6.5f, ink);
                    ink.setAlpha(255);
                    canvas.drawCircle(12, 12, 4.5f, ink);
                } else if (kind.equals("later")) {
                    ink.setStyle(Paint.Style.STROKE);
                    canvas.drawLine(3, 5, 16, 5, ink);
                    canvas.drawLine(3, 11, 13, 11, ink);
                    canvas.drawLine(3, 17, 10, 17, ink);
                    canvas.drawLine(18, 13, 18, 21, ink);
                    canvas.drawLine(14, 17, 22, 17, ink);
                } else {
                    ink.setStyle(Paint.Style.STROKE);
                    canvas.drawLine(12, 2, 12, 15, ink);
                    canvas.drawLine(7, 10, 12, 15, ink);
                    canvas.drawLine(17, 10, 12, 15, ink);
                    canvas.drawLine(4, 17, 4, 21, ink);
                    canvas.drawLine(4, 21, 20, 21, ink);
                    canvas.drawLine(20, 17, 20, 21, ink);
                }
                canvas.restore();
            }

            @Override
            public int getIntrinsicWidth() {
                return dp(22);
            }

            @Override
            public int getIntrinsicHeight() {
                return dp(22);
            }

            @Override
            public void setAlpha(int alpha) {
                ink.setAlpha(alpha);
            }

            @Override
            public void setColorFilter(ColorFilter filter) {
                ink.setColorFilter(filter);
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }

    Drawable viewIcon(String mode) {
        return new Drawable() {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(Canvas canvas) {
                canvas.save();
                canvas.translate(getBounds().left, getBounds().top);
                canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
                paint.setColor(foreground);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                if (mode.equals("pause")) {
                    canvas.drawLine(8, 5, 8, 19, paint);
                    canvas.drawLine(16, 5, 16, 19, paint);
                } else if (mode.equals("minus")) {
                    canvas.drawLine(5, 12, 19, 12, paint);
                } else if (mode.equals("trash")) {
                    canvas.drawLine(4, 6, 20, 6, paint);
                    canvas.drawLine(9, 3, 15, 3, paint);
                    canvas.drawRect(6, 7, 18, 21, paint);
                    canvas.drawLine(10, 10, 10, 18, paint);
                    canvas.drawLine(14, 10, 14, 18, paint);
                } else if (mode.equals("folder")) {
                    canvas.drawRoundRect(3, 6, 21, 20, 2, 2, paint);
                    canvas.drawLine(4, 6, 4, 3, paint);
                    canvas.drawLine(4, 3, 10, 3, paint);
                    canvas.drawLine(10, 3, 13, 6, paint);
                } else if (mode.equals("transfers")) {
                    canvas.drawLine(4, 7, 20, 7, paint);
                    canvas.drawLine(16, 3, 20, 7, paint);
                    canvas.drawLine(16, 11, 20, 7, paint);
                    canvas.drawLine(20, 17, 4, 17, paint);
                    canvas.drawLine(8, 13, 4, 17, paint);
                    canvas.drawLine(8, 21, 4, 17, paint);
                } else if (mode.equals("link")) {
                    canvas.save();
                    canvas.rotate(-40, 12, 12);
                    canvas.drawRoundRect(3, 8, 13, 16, 4, 4, paint);
                    canvas.drawRoundRect(11, 8, 21, 16, 4, 4, paint);
                    canvas.restore();
                } else if (mode.equals("share")) {
                    canvas.drawLine(6, 12, 18, 5, paint);
                    canvas.drawLine(6, 12, 18, 19, paint);
                    canvas.drawCircle(5, 12, 3, paint);
                    canvas.drawCircle(19, 4, 3, paint);
                    canvas.drawCircle(19, 20, 3, paint);
                } else if (mode.equals("slots")) {
                    for (int y = 5; y <= 19; y += 7) {
                        canvas.drawLine(3, y, 21, y, paint);
                        canvas.drawCircle(y == 12 ? 16 : 8, y, 2, paint);
                    }
                } else if (mode.equals("refresh")) {
                    canvas.drawArc(4, 4, 20, 20, 40, 285, false, paint);
                    canvas.drawLine(20, 3, 20, 9, paint);
                    canvas.drawLine(14, 9, 20, 9, paint);
                } else if (mode.equals("more")) {
                    paint.setStyle(Paint.Style.FILL);
                    for (int y : new int[] {5, 12, 19}) canvas.drawCircle(12, y, 1.6f, paint);
                } else if (mode.equals("sort")) {
                    canvas.drawLine(3, 7, 21, 7, paint);
                    canvas.drawLine(3, 17, 21, 17, paint);
                    canvas.drawLine(8, 4, 8, 10, paint);
                    canvas.drawLine(16, 14, 16, 20, paint);
                } else if (mode.equals("down")) {
                    canvas.drawLine(6, 9, 12, 15, paint);
                    canvas.drawLine(12, 15, 18, 9, paint);
                } else if (mode.equals("info")) {
                    canvas.drawCircle(12, 12, 9, paint);
                    canvas.drawPoint(12, 7, paint);
                    canvas.drawLine(12, 11, 12, 17, paint);
                } else if (mode.equals("music")) {
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(10, 17, 4, paint);
                    canvas.drawRoundRect(12, 3, 14, 17, 1, 1, paint);
                    canvas.drawRoundRect(13, 3, 18, 7, 1, 1, paint);
                } else if (mode.equals("close")) {
                    canvas.drawLine(6, 6, 18, 18, paint);
                    canvas.drawLine(18, 6, 6, 18, paint);
                } else if (mode.equals("search")) {
                    canvas.drawCircle(10.5f, 10.5f, 7, paint);
                    canvas.drawLine(16, 16, 21, 21, paint);
                } else if (mode.equals("grid")) {
                    for (int x : new int[] {3, 14})
                        for (int y : new int[] {3, 14})
                            canvas.drawRoundRect(x, y, x + 7, y + 7, 1, 1, paint);
                } else if (mode.equals("list")) {
                    for (int y : new int[] {6, 12, 18}) {
                        canvas.drawPoint(3, y, paint);
                        canvas.drawLine(8, y, 21, y, paint);
                    }
                } else if (mode.equals("detailed")) {
                    Path path = new Path();
                    path.moveTo(14, 2);
                    path.lineTo(6, 2);
                    path.quadTo(4, 2, 4, 4);
                    path.lineTo(4, 20);
                    path.quadTo(4, 22, 6, 22);
                    path.lineTo(18, 22);
                    path.quadTo(20, 22, 20, 20);
                    path.lineTo(20, 8);
                    path.close();
                    canvas.drawPath(path, paint);
                    canvas.drawLine(14, 2, 14, 8, paint);
                    canvas.drawLine(14, 8, 20, 8, paint);
                    canvas.drawLine(8, 13, 16, 13, paint);
                    canvas.drawLine(8, 17, 16, 17, paint);
                } else {
                    canvas.drawRoundRect(3, 3, 21, 21, 2, 2, paint);
                    canvas.drawLine(3, 9, 21, 9, paint);
                    if (mode.equals("compact")) canvas.drawLine(3, 15, 21, 15, paint);
                    else canvas.drawLine(9, 9, 9, 21, paint);
                }
                canvas.restore();
            }

            @Override
            public int getIntrinsicWidth() {
                return dp(24);
            }

            @Override
            public int getIntrinsicHeight() {
                return dp(24);
            }

            @Override
            public void setAlpha(int alpha) {
                paint.setAlpha(alpha);
            }

            @Override
            public void setColorFilter(ColorFilter filter) {
                paint.setColorFilter(filter);
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }

    ImageButton backButton(Runnable action) {
        ImageButton result = new ImageButton(activity);
        result.setImageTintList(ColorStateList.valueOf(foreground));
        result.setPadding(0, 0, 0, 0);
        result.setScaleType(ImageView.ScaleType.CENTER);
        result.setImageDrawable(
                new Drawable() {
                    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

                    {
                        paint.setColor(foreground);
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(2);
                        paint.setStrokeCap(Paint.Cap.ROUND);
                        paint.setStrokeJoin(Paint.Join.ROUND);
                    }

                    @Override
                    public void draw(Canvas canvas) {
                        canvas.save();
                        canvas.translate(getBounds().exactCenterX(), getBounds().exactCenterY());
                        float scale = Math.min(getBounds().width(), getBounds().height()) / 24f;
                        canvas.scale(PatchStrings.rtl() ? -scale : scale, scale);
                        Path arrow = new Path();
                        arrow.moveTo(7, 0);
                        arrow.lineTo(-7, 0);
                        arrow.moveTo(0, -7);
                        arrow.lineTo(-7, 0);
                        arrow.lineTo(0, 7);
                        canvas.drawPath(arrow, paint);
                        canvas.restore();
                    }

                    @Override
                    public int getIntrinsicWidth() {
                        return dp(24);
                    }

                    @Override
                    public int getIntrinsicHeight() {
                        return dp(24);
                    }

                    @Override
                    public void setAlpha(int alpha) {
                        paint.setAlpha(alpha);
                        invalidateSelf();
                    }

                    @Override
                    public void setColorFilter(ColorFilter filter) {
                        paint.setColorFilter(filter);
                        invalidateSelf();
                    }

                    @Override
                    public int getOpacity() {
                        return PixelFormat.TRANSLUCENT;
                    }
                });
        result.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(dark ? 0x228e8e93 : 0x22000000),
                        shape(dark ? 0xff0d0d0f : 0xfff7f7f9, 30, true),
                        null));
        result.setBackgroundTintList(null);
        result.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        result.setContentDescription(PatchStrings.t("Back"));
        result.setOnClickListener(view -> action.run());
        markCustom(result);
        return result;
    }

    /** Styles new views only, so live status updates cannot reset user focus or typed values. */
    void applyTree(View view) {
        if (!styled.containsKey(view)) {
            styled.put(view, true);
            if (view instanceof EditText) field((EditText) view);
            else if (view instanceof CompoundButton) {
                CompoundButton toggle = (CompoundButton) view;
                toggle.setTextSize(16);
                toggle.setTypeface(medium);
                toggle.setTextColor(colors(foreground, secondary));
                toggle.setButtonTintList(colors(foreground, secondary));
                toggle.setMinHeight(dp(48));
                toggle.setPadding(dp(4), dp(8), dp(4), dp(8));
                if (!(view instanceof RadioButton) && !(view instanceof CheckBox)) {
                    toggle.setButtonDrawable((Drawable) null);
                    toggle.setCompoundDrawablePadding(dp(12));
                    toggle.setCompoundDrawablesRelativeWithIntrinsicBounds(
                            null, null, new ToggleDrawable(), null);
                }
            } else if (view instanceof Button) {
                Button button = (Button) view;
                if ("Back".contentEquals(button.getText())
                        || PatchStrings.t("Back").contentEquals(button.getText()))
                    button.setVisibility(View.GONE);
                else button(button);
            } else if (view instanceof TextView) {
                TextView text = (TextView) view;
                float size =
                        text.getTextSize()
                                / activity.getResources().getDisplayMetrics().scaledDensity;
                boolean title = size >= 24;
                text.setTypeface(title ? heavy : size >= 16 ? medium : regular);
                text.setTextColor(colors(title || size >= 16 ? foreground : secondary, secondary));
                text.setFontFeatureSettings("kern");
                text.setLineSpacing(dp(3), 1);
                if (title) {
                    text.setTextSize(34);
                    text.setPadding(0, dp(22), 0, dp(20));
                } else text.setPadding(0, dp(8), 0, dp(8));
            } else if (view instanceof Spinner) {
                view.setBackground(
                        new LayerDrawable(
                                new Drawable[] {shape(card, 14, false), new ChevronDrawable()}));
                view.setPadding(dp(12), dp(8), dp(40), dp(8));
                view.setMinimumHeight(dp(52));
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) applyTree(group.getChildAt(i));
        }
    }

    private final class ChevronDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override
        public void draw(Canvas canvas) {
            paint.setColor(secondary);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            float x = getBounds().right - dp(24), y = getBounds().exactCenterY();
            Path path = new Path();
            path.moveTo(x - dp(4), y - dp(2));
            path.lineTo(x, y + dp(2));
            path.lineTo(x + dp(4), y - dp(2));
            canvas.drawPath(path, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** BitChord 1.9 capsule switch shared with Patches & tweaks. */
    private final class ToggleDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        protected boolean onStateChange(int[] state) {
            invalidateSelf();
            return true;
        }

        @Override
        public void draw(Canvas canvas) {
            boolean checked = false, enabled = false, pressed = false;
            for (int value : getState()) {
                if (value == android.R.attr.state_checked) checked = true;
                if (value == android.R.attr.state_enabled) enabled = true;
                if (value == android.R.attr.state_pressed) pressed = true;
            }
            canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            float scale = Math.min(getBounds().width() / 64f, getBounds().height() / 28f);
            canvas.scale(scale, scale);
            paint.setColor(
                    checked ? (dark ? 0xff30d158 : 0xff34c759) : (dark ? 0x5c8e8e93 : 0x388e8e93));
            if (!enabled) paint.setAlpha(paint.getAlpha() * 100 / 255);
            canvas.drawRoundRect(0, 0, 64, 28, 14, 14, paint);
            paint.setColor(0xffffffff);
            paint.setAlpha(enabled ? 255 : 100);
            float width = pressed ? 40 * 1.18f : 40;
            float x = checked ? 62 - width : 2;
            canvas.drawRoundRect(x, 2, x + width, 26, 12, 12, paint);
            canvas.restore();
        }

        @Override
        public int getIntrinsicWidth() {
            return dp(64);
        }

        @Override
        public int getIntrinsicHeight() {
            return dp(28);
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(ColorFilter filter) {}

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
