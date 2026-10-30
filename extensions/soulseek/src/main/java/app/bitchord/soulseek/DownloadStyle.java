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
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
        toggle.setPadding(0, dp(8), 0, dp(8));
        toggle.setMinHeight(dp(48));
        toggle.setMinWidth(dp(52));
        toggle.setBackground(null);
        toggle.setFocusable(true);
        markCustom(toggle);
        return toggle;
    }

    ImageButton backButton(Runnable action) {
        ImageButton result = new ImageButton(activity);
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
                if (!(view instanceof RadioButton)) {
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

    /** The same monochrome pill and thumb proportions used by BitChord's source switches. */
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
            boolean checked = false, enabled = false;
            for (int value : getState()) {
                if (value == android.R.attr.state_checked) checked = true;
                if (value == android.R.attr.state_enabled) enabled = true;
            }
            canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            float scale = Math.min(getBounds().width() / 52f, getBounds().height() / 32f);
            canvas.scale(scale, scale);
            paint.setColor(checked ? foreground : outline);
            paint.setAlpha(enabled ? 255 : 100);
            canvas.drawRoundRect(0, 0, 52, 32, 16, 16, paint);
            paint.setColor(checked ? background : secondary);
            canvas.drawCircle(checked ? 36 : 16, 16, 12, paint);
            canvas.restore();
        }

        @Override
        public int getIntrinsicWidth() {
            return dp(52);
        }

        @Override
        public int getIntrinsicHeight() {
            return dp(32);
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
