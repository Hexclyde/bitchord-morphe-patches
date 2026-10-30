package app.bitchord.telegram;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Checkable;

/**
 * BitChord 1.9's IosSwitch geometry: a 64×28dp track and white 40×24dp capsule,
 * within a 48dp touch target. The enabled colour stays green in either theme.
 */
final class TelegramSwitch extends View implements Checkable {
    interface Listener {
        void changed(TelegramSwitch view, boolean checked);
    }

    private final TelegramStyle style;
    private final int uncheckedTrack;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private boolean checked;
    private float position; // 0 = off, 1 = on
    private ValueAnimator animator;
    private Listener listener;

    TelegramSwitch(Context context, TelegramStyle style) {
        super(context);
        this.style = style;
        uncheckedTrack = style.dark ? 0x5c8e8e93 : 0x388e8e93;
        setClickable(true);
        setFocusable(true);
        setOnClickListener(v -> toggle());
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(style.dp(64), style.dp(48));
    }

    @Override
    public boolean isChecked() {
        return checked;
    }

    @Override
    public void setChecked(boolean value) {
        set(value, false, false);
    }

    @Override
    public void toggle() {
        set(!checked, true, true);
    }

    private void set(boolean value, boolean animate, boolean notify) {
        if (value == checked && animator == null) {
            position = value ? 1 : 0;
            invalidate();
            return;
        }
        checked = value;
        if (animator != null) animator.cancel();
        if (animate && isAttachedToWindow()) {
            animator = ValueAnimator.ofFloat(position, value ? 1 : 0);
            animator.setDuration(150);
            animator.addUpdateListener(
                    a -> {
                        position = (float) a.getAnimatedValue();
                        invalidate();
                    });
            animator.start();
        } else {
            animator = null;
            position = value ? 1 : 0;
            invalidate();
        }
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
        if (notify && listener != null) listener.changed(this, value);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float left = (getWidth() - style.dp(64)) / 2f;
        float top = (getHeight() - style.dp(28)) / 2f;
        float radius = style.dp(14);
        track.set(left, top, left + style.dp(64), top + style.dp(28));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(blend(uncheckedTrack, style.dark ? 0xff30d158 : 0xff34c759, position));
        canvas.drawRoundRect(track, radius, radius, paint);
        float width = style.dp(40) * (isPressed() ? 1.18f : 1f);
        float x = left + style.dp(2) + (style.dp(60) - width) * position;
        track.set(x, top + style.dp(2), x + width, top + style.dp(26));
        paint.setColor(0xffffffff);
        canvas.drawRoundRect(track, style.dp(12), style.dp(12), paint);
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Switch");
        info.setCheckable(true);
        info.setChecked(checked);
    }

    private static int blend(int from, int to, float t) {
        int a =
                (int)
                        (((from >>> 24) & 0xff)
                                + (((to >>> 24) & 0xff) - ((from >>> 24) & 0xff)) * t);
        int r = (int) (((from >> 16) & 0xff) + (((to >> 16) & 0xff) - ((from >> 16) & 0xff)) * t);
        int g = (int) (((from >> 8) & 0xff) + (((to >> 8) & 0xff) - ((from >> 8) & 0xff)) * t);
        int b = (int) ((from & 0xff) + ((to & 0xff) - (from & 0xff)) * t);
        return a << 24 | r << 16 | g << 8 | b;
    }
}
