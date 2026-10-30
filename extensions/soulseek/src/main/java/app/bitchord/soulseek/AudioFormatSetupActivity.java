package app.bitchord.soulseek;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Checkable;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import app.bitchord.telegram.PatchStrings;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/** Download formats use the same numbered, switch-and-drag rows as BitChord's Sources list. */
public final class AudioFormatSetupActivity extends DownloadActivity {
    private static final int MOVE_UP = 0x7e020001, MOVE_DOWN = 0x7e020002;
    private AudioFormatPreferences.Settings settings;
    private DownloadStyle style;
    private ScrollView scroll;
    private LinearLayout rows;
    private final EnumMap<AudioFormatPreferences.Format, FormatRow> rowViews =
            new EnumMap<>(AudioFormatPreferences.Format.class);
    private FormatRow held;
    private FormatDragOrder drag;
    private int pointer = -1, startScroll;
    private float downY, fingerY;
    private boolean dragging;

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        style = downloadStyle;
        settings = AudioFormatPreferences.snapshot(this);
        if (saved != null && saved.containsKey("draft")) {
            try {
                settings = AudioFormatPreferences.decode(saved.getString("draft"));
            } catch (Exception invalidDraft) {
                // Keep the saved preferences if Android supplied an invalid draft.
            }
        }
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(style.dp(20), style.dp(12), style.dp(20), style.dp(28));
        scroll.addView(body);
        TextView title =
                style.text(PatchStrings.t("Download formats"), 34, style.foreground, style.heavy);
        title.setPadding(0, style.dp(12), 0, style.dp(28));
        body.addView(title);
        TextView heading =
                style.text(
                        PatchStrings.t("FORMATS, TRIED IN THIS ORDER"),
                        11,
                        style.secondary,
                        style.semibold);
        heading.setPadding(style.dp(4), 0, 0, style.dp(8));
        body.addView(heading);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setBackground(style.shape(style.card, 16, false));
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        body.addView(rows, new LinearLayout.LayoutParams(-1, -2));
        for (AudioFormatPreferences.Format format : settings.order) {
            rowViews.put(format, new FormatRow(format));
        }
        renderOrder();
        TextView explanation =
                style.text(
                        PatchStrings.t(
                                "Enable the formats you accept. Drag a handle to choose which is"
                                        + " checked first. Keep at least one format enabled."),
                        14,
                        style.secondary,
                        style.regular);
        explanation.setLineSpacing(style.dp(3), 1);
        explanation.setPadding(style.dp(4), style.dp(12), style.dp(4), style.dp(20));
        body.addView(explanation);
        android.widget.Button save = new android.widget.Button(this);
        save.setText(PatchStrings.t("Save format choices"));
        body.addView(save, new LinearLayout.LayoutParams(-1, -2));
        save.setOnClickListener(
                v -> {
                    finishDrag(true);
                    try {
                        AudioFormatPreferences.save(this, settings);
                        finish();
                    } catch (Exception error) {
                        Toast.makeText(
                                        this,
                                        PatchStrings.t(
                                                "Could not save audio format choices. Try again."),
                                        Toast.LENGTH_LONG)
                                .show();
                    }
                });
        TextView provenance =
                style.text(
                        PatchStrings.t(
                                "Preference applies among matching artist, title and duration"
                                    + " results. Completed files must match an enabled format. A"
                                    + " filename or encoding check cannot prove that a lossless"
                                    + " file was never converted from a lossy source."),
                        13,
                        style.secondary,
                        style.regular);
        provenance.setLineSpacing(style.dp(3), 1);
        provenance.setPadding(style.dp(4), style.dp(18), style.dp(4), 0);
        body.addView(provenance);
        setContentView(scroll);
        if (saved != null) {
            int position = saved.getInt("scroll", 0);
            scroll.post(() -> scroll.scrollTo(0, position));
        }
    }

    @Override
    public void onSaveInstanceState(Bundle out) {
        finishDrag(false);
        out.putString("draft", AudioFormatPreferences.encode(settings));
        out.putInt("scroll", scroll.getScrollY());
        super.onSaveInstanceState(out);
    }

    @Override
    protected void onPause() {
        finishDrag(false);
        super.onPause();
    }

    private void renderOrder() {
        rows.removeAllViews();
        for (int index = 0; index < settings.order.size(); index++) {
            FormatRow row = rowViews.get(settings.order.get(index));
            row.setTranslationY(0);
            row.setTranslationZ(0);
            row.setAlpha(1);
            row.number.setText(String.valueOf(index + 1));
            row.enabled.setChecked(settings.enabled.contains(row.format));
            row.updateAppearance();
            row.last = index == settings.order.size() - 1;
            row.invalidate();
            rows.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void move(AudioFormatPreferences.Format format, int direction) {
        finishDrag(false);
        int index = settings.order.indexOf(format);
        if (index + direction < 0 || index + direction >= settings.order.size()) return;
        settings = settings.move(index, direction);
        renderOrder();
        FormatRow row = rowViews.get(format);
        row.handle.requestFocus();
        row.handle.announceForAccessibility(format.label + ", priority " + (index + direction + 1));
    }

    private boolean touchHandle(FormatRow row, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            finishDrag(false);
            held = row;
            pointer = event.getPointerId(0);
            downY = fingerY = event.getRawY();
            startScroll = scroll.getScrollY();
            row.handle.getParent().requestDisallowInterceptTouchEvent(true);
            row.handle.setPressed(true);
            return true;
        }
        if (held != row) return false;
        if (action == MotionEvent.ACTION_CANCEL
                || (action == MotionEvent.ACTION_POINTER_UP
                        && event.getPointerId(event.getActionIndex()) == pointer)) {
            finishDrag(false);
            return true;
        }
        int at = event.findPointerIndex(pointer);
        if (at < 0) {
            finishDrag(false);
            return true;
        }
        // getRawY(index) is newer than Android 8; translate the selected pointer ourselves.
        fingerY = event.getRawY() + event.getY(at) - event.getY(0);
        if (action == MotionEvent.ACTION_MOVE) {
            if (!dragging
                    && Math.abs(fingerY - downY)
                            > ViewConfiguration.get(this).getScaledTouchSlop()) {
                int count = rows.getChildCount();
                int[] tops = new int[count], heights = new int[count];
                for (int index = 0; index < count; index++) {
                    View item = rows.getChildAt(index);
                    tops[index] = item.getTop();
                    heights[index] = item.getHeight();
                }
                drag = new FormatDragOrder(tops, heights, settings.order.indexOf(row.format));
                dragging = true;
                row.setTranslationZ(style.dp(8));
                row.setAlpha(.96f);
                row.handle.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                scroll.postOnAnimation(autoScroll);
            }
            updateDrag();
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            if (!dragging) row.handle.performClick();
            finishDrag(true);
            return true;
        }
        return true;
    }

    private void updateDrag() {
        if (!dragging || drag == null || held == null) return;
        drag.update(fingerY - downY + scroll.getScrollY() - startScroll);
        for (int index = 0; index < settings.order.size(); index++) {
            rowViews.get(settings.order.get(index)).setTranslationY(drag.translation(index));
        }
    }

    private final Runnable autoScroll =
            new Runnable() {
                @Override
                public void run() {
                    if (!dragging || held == null) return;
                    int[] location = new int[2];
                    scroll.getLocationOnScreen(location);
                    float top = location[1] + scroll.getPaddingTop();
                    float bottom = location[1] + scroll.getHeight() - scroll.getPaddingBottom();
                    float edge = style.dp(56), speed = style.dp(12);
                    int amount = 0;
                    if (fingerY < top + edge) {
                        amount =
                                -(int)
                                        Math.ceil(
                                                speed * Math.min(1, (top + edge - fingerY) / edge));
                    } else if (fingerY > bottom - edge) {
                        amount =
                                (int)
                                        Math.ceil(
                                                speed
                                                        * Math.min(
                                                                1,
                                                                (fingerY - bottom + edge) / edge));
                    }
                    if (amount != 0) {
                        scroll.scrollBy(0, amount);
                        updateDrag();
                    }
                    scroll.postOnAnimation(this);
                }
            };

    private void finishDrag(boolean commit) {
        if (held == null) return;
        scroll.removeCallbacks(autoScroll);
        FormatRow previous = held;
        boolean moved = dragging && drag != null && drag.target() != drag.start();
        if (commit && moved) {
            List<AudioFormatPreferences.Format> order = new ArrayList<>(settings.order);
            order.add(drag.target(), order.remove(drag.start()));
            settings =
                    new AudioFormatPreferences.Settings(
                            order, settings.enabled, settings.configured);
        }
        held = null;
        dragging = false;
        drag = null;
        pointer = -1;
        previous.handle.setPressed(false);
        previous.handle.getParent().requestDisallowInterceptTouchEvent(false);
        renderOrder();
        if (commit && moved) {
            previous.handle.announceForAccessibility(
                    previous.format.label
                            + ", priority "
                            + (settings.order.indexOf(previous.format) + 1));
        }
    }

    private final class FormatRow extends LinearLayout {
        final AudioFormatPreferences.Format format;
        final TextView number;
        final LinearLayout labels;
        final FormatSwitch enabled;
        final View handle;
        private final Paint rule = new Paint();
        boolean last;

        FormatRow(AudioFormatPreferences.Format format) {
            super(AudioFormatSetupActivity.this);
            this.format = format;
            setOrientation(LinearLayout.HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(style.dp(76));
            setPadding(style.dp(12), style.dp(8), style.dp(4), style.dp(8));
            setBackground(style.shape(style.card, 16, false));
            setWillNotDraw(false);
            number = style.text("", 14, style.secondary, style.regular);
            number.setGravity(Gravity.CENTER);
            addView(number, new LayoutParams(style.dp(24), -2));
            labels = new LinearLayout(getContext());
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(style.dp(8), 0, style.dp(8), 0);
            labels.addView(style.text(format.label, 16, style.foreground, style.regular));
            labels.addView(style.text(extensions(format), 13, style.secondary, style.regular));
            addView(labels, new LayoutParams(0, -2, 1));
            enabled = new FormatSwitch(getContext());
            enabled.setContentDescription(PatchStrings.f("Accept {0}", format.label));
            enabled.setOnClickListener(
                    v -> {
                        if (held != null) return;
                        try {
                            settings = settings.withEnabled(format, !enabled.isChecked());
                            enabled.setChecked(settings.enabled.contains(format));
                            updateAppearance();
                        } catch (IllegalArgumentException lastFormat) {
                            Toast.makeText(
                                            AudioFormatSetupActivity.this,
                                            PatchStrings.t(
                                                    "Keep at least one audio format enabled."),
                                            Toast.LENGTH_SHORT)
                                    .show();
                        }
                    });
            addView(enabled, new LayoutParams(style.dp(52), style.dp(48)));
            handle = new DragHandle(getContext(), format);
            addView(handle, new LayoutParams(style.dp(48), style.dp(48)));
            handle.setOnTouchListener((view, event) -> touchHandle(this, event));
        }

        private void updateAppearance() {
            labels.setAlpha(enabled.isChecked() ? 1 : .5f);
            number.setAlpha(enabled.isChecked() ? 1 : .5f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!last) {
                rule.setColor(style.outline);
                rule.setStrokeWidth(style.dp(1));
                canvas.drawLine(style.dp(44), getHeight() - 1, getWidth(), getHeight() - 1, rule);
            }
        }
    }

    private final class DragHandle extends View {
        private final AudioFormatPreferences.Format format;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        DragHandle(Context context, AudioFormatPreferences.Format format) {
            super(context);
            this.format = format;
            setContentDescription(PatchStrings.f("Reorder {0}", format.label));
            setFocusable(true);
            setClickable(true);
            setOnClickListener(
                    v ->
                            announceForAccessibility(
                                    PatchStrings.t(
                                            "Drag to reorder, or use Move up and Move down.")));
            setOnKeyListener(
                    (view, key, event) -> {
                        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                            move(format, key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                            return true;
                        }
                        return false;
                    });
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setColor(style.secondary);
            paint.setAlpha(isPressed() ? 255 : 150);
            paint.setStrokeWidth(style.dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            float x = getWidth() / 2f, y = getHeight() / 2f;
            canvas.drawLine(
                    x - style.dp(6), y - style.dp(2), x + style.dp(6), y - style.dp(2), paint);
            canvas.drawLine(
                    x - style.dp(6), y + style.dp(2), x + style.dp(6), y + style.dp(2), paint);
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            int index = settings.order.indexOf(format);
            if (index > 0)
                info.addAction(
                        new AccessibilityNodeInfo.AccessibilityAction(
                                MOVE_UP, PatchStrings.t("Move up")));
            if (index < settings.order.size() - 1) {
                info.addAction(
                        new AccessibilityNodeInfo.AccessibilityAction(
                                MOVE_DOWN, PatchStrings.t("Move down")));
            }
        }

        @Override
        public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (action == MOVE_UP || action == MOVE_DOWN) {
                int direction = action == MOVE_UP ? -1 : 1;
                int next = settings.order.indexOf(format) + direction;
                if (next < 0 || next >= settings.order.size()) return false;
                move(format, direction);
                return true;
            }
            return super.performAccessibilityAction(action, arguments);
        }
    }

    /** Material 3's 52x32dp switch, centred in a 48dp touch target as on Sources. */
    private final class FormatSwitch extends View implements Checkable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF track = new RectF();
        private boolean checked;

        FormatSwitch(Context context) {
            super(context);
            setClickable(true);
            setFocusable(true);
        }

        @Override
        public boolean isChecked() {
            return checked;
        }

        @Override
        public void setChecked(boolean value) {
            checked = value;
            invalidate();
            sendAccessibilityEvent(
                    android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        }

        @Override
        public void toggle() {
            performClick();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float top = (getHeight() - style.dp(32)) / 2f;
            track.set(0, top, getWidth(), top + style.dp(32));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(checked ? style.foreground : style.dark ? 0xff36343b : 0xffe6e0e9);
            canvas.drawRoundRect(track, style.dp(16), style.dp(16), paint);
            if (!checked) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(style.outline);
                paint.setStrokeWidth(style.dp(2));
                track.inset(style.dp(1), style.dp(1));
                canvas.drawRoundRect(track, style.dp(15), style.dp(15), paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(checked ? style.background : style.secondary);
            canvas.drawCircle(
                    checked ? getWidth() - style.dp(16) : style.dp(16),
                    getHeight() / 2f,
                    style.dp(checked ? 12 : 8),
                    paint);
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Switch");
            info.setCheckable(true);
            info.setChecked(checked);
        }
    }

    private static String extensions(AudioFormatPreferences.Format format) {
        switch (format) {
            case FLAC:
                return ".flac";
            case ALAC:
                return ".m4a (ALAC)";
            case WAV:
                return ".wav";
            case AIFF:
                return ".aif / .aiff / .aifc";
            case DSD:
                return ".dsf / .dff";
            case OPUS:
                return ".opus / .ogg";
            case AAC:
                return ".aac / .m4a / .mp4";
            case VORBIS:
                return ".ogg / .oga / .vorbis";
            default:
                return ".mp3";
        }
    }
}
