package app.bitchord.telegram;

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
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.*;

/** Mirrors BitChord Theme.kt and loads its existing SF Pro Display fonts. */
final class TelegramStyle {
    final Activity activity;
    final boolean dark;
    final int background, card, foreground, secondary, outline, field;
    final Typeface regular, medium, semibold, heavy;

    TelegramStyle(Activity activity) {
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
            return (activity.getResources().getConfiguration().uiMode
                            & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
        } catch (Exception e) {
            return true;
        }
    }

    int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    Typeface font(String weight, int fallback) {
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
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (border) d.setStroke(dp(1), outline);
        return d;
    }

    TextView text(String value, int size, int color, Typeface face) {
        TextView t = new TextView(activity);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setFontFeatureSettings("kern");
        return t;
    }

    ImageButton backButton(Runnable action) {
        ImageButton b = new ImageButton(activity);
        b.setPadding(0, 0, 0, 0);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setImageDrawable(
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
                        // Draw around the drawable's geometric center; text baselines and font
                        // scaling do not apply.
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
        b.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(dark ? 0x228e8e93 : 0x22000000),
                        shape(dark ? 0xff0d0d0f : 0xfff7f7f9, 30, true),
                        null));
        b.setBackgroundTintList(null);
        b.setContentDescription("Back");
        b.setOnClickListener(v -> action.run());
        return b;
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

    TextView button(String value, boolean primary, Runnable action) {
        TextView b = text(value, 16, primary ? background : foreground, semibold);
        b.setGravity(android.view.Gravity.CENTER);
        b.setMinHeight(dp(50));
        b.setPadding(dp(18), dp(12), dp(18), dp(12));
        b.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(dark ? 0x228e8e93 : 0x22000000),
                        shape(primary ? foreground : field, 28, false),
                        null));
        b.setOnClickListener(v -> action.run());
        b.setClickable(true);
        b.setFocusable(true);
        b.setAccessibilityDelegate(
                new View.AccessibilityDelegate() {
                    @Override
                    public void onInitializeAccessibilityNodeInfo(
                            View host, android.view.accessibility.AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setClassName(Button.class.getName());
                    }
                });
        return b;
    }

    void tuneDialog(android.app.AlertDialog dialog) {
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawable(shape(card, 18, false));
        for (int which : new int[] {-1, -2}) {
            Button b = dialog.getButton(which);
            if (b != null) {
                b.setTextColor(foreground);
                b.setTypeface(semibold);
            }
        }
    }
}
