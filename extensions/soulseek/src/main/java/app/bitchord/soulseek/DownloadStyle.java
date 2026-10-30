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
                        canvas.scale(scale, scale);
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
        result.setContentDescription("Back");
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
                if ("Back".contentEquals(button.getText())) button.setVisibility(View.GONE);
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
