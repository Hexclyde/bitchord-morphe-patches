package app.bitchord.telegram;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;

import java.text.NumberFormat;

/** Native, pair-specific timeline and automation controls. */
public final class TransitionEditorActivity extends Activity {
    private TelegramStyle style;
    private TransitionRuntime.Pair pair;
    private TransitionEdit edit;
    private Timeline timeline;
    private TextView status, preview;
    private LinearLayout controls;
    private int side, point = 1;
    private TransitionRuntime.Preview audition;
    private boolean wide;

    @Override
    public void onCreate(Bundle saved) {
        boolean dark = TelegramStyle.isDark(this);
        setTheme(
                dark
                        ? android.R.style.Theme_Material_NoActionBar
                        : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(saved);
        Strings.refresh(this);
        style = new TelegramStyle(this);
        wide =
                getResources().getConfiguration().orientation
                        == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout root = column();
        root.setTag("transition-editor-root");
        root.setPadding(style.dp(16), style.dp(8), style.dp(16), style.dp(8));
        root.setBackgroundColor(style.background);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        root.setOnApplyWindowInsetsListener(
                (view, insets) -> {
                    int left, top, right, bottom;
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        android.graphics.Insets safe =
                                insets.getInsets(
                                        WindowInsets.Type.systemBars()
                                                | WindowInsets.Type.displayCutout()
                                                | WindowInsets.Type.ime());
                        left = safe.left;
                        top = safe.top;
                        right = safe.right;
                        bottom = safe.bottom;
                    } else {
                        left = insets.getSystemWindowInsetLeft();
                        top = insets.getSystemWindowInsetTop();
                        right = insets.getSystemWindowInsetRight();
                        bottom = insets.getSystemWindowInsetBottom();
                    }
                    view.setPadding(
                            style.dp(16) + left,
                            style.dp(8) + top,
                            style.dp(16) + right,
                            style.dp(8) + bottom);
                    return insets;
                });
        root.requestApplyInsets();
        LinearLayout header = row();
        header.addView(
                style.backButton(this::finish),
                new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        LinearLayout.LayoutParams headingSpace = new LinearLayout.LayoutParams(0, -2, 1);
        headingSpace.leftMargin = style.dp(10);
        header.addView(label(Strings.t("Transition editor"), 22, true), headingSpace);
        root.addView(header);
        status = label("", 13, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (!wide) root.addView(status);
        else status.setVisibility(View.GONE);
        try {
            pair = TransitionRuntime.pair();
        } catch (Exception unavailable) {
            pair = null;
        }
        if (pair == null || pair.outgoingDuration <= 0 || pair.incomingDuration <= 0) {
            if (wide) root.addView(status);
            message(
                    pair == null
                            ? Strings.t("Add two tracks to the queue first.")
                            : Strings.t(
                                    "Track durations are not available yet. Start playback or"
                                            + " choose another source."));
            setContentView(root);
            return;
        }
        TransitionEdit savedEdit = TransitionRuntime.load(pair);
        edit = savedEdit == null ? new TransitionEdit() : savedEdit;
        normalize();
        LinearLayout tracks = column();
        tracks.setPadding(
                style.dp(wide ? 12 : 4),
                style.dp(wide ? 0 : 8),
                style.dp(4),
                style.dp(wide ? 0 : 12));
        for (int deck = 0; deck < 2; deck++) {
            TextView track =
                    label(
                            deck == 0 ? pair.outgoingTitle : "→ " + pair.incomingTitle,
                            deck == 0 ? 16 : 14,
                            deck == 0);
            track.setSingleLine(true);
            track.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tracks.addView(track);
        }
        timeline = new Timeline();
        timeline.setTag("transition-timeline");
        timeline.setMinimumHeight(style.dp(212));
        timeline.setBackground(style.shape(style.card, 16, false));
        controls = column();
        if (wide) {
            header.addView(tracks, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout panes = row();
            panes.setGravity(Gravity.TOP);
            ScrollView graphScroll = new ScrollView(this);
            graphScroll.addView(timeline, new ScrollView.LayoutParams(-1, style.dp(212)));
            LinearLayout.LayoutParams graphSpace = new LinearLayout.LayoutParams(0, -1, 1);
            graphSpace.rightMargin = style.dp(12);
            panes.addView(graphScroll, graphSpace);
            LinearLayout right = column();
            right.addView(status);
            right.addView(controls);
            ScrollView controlScroll = new ScrollView(this);
            controlScroll.addView(right);
            panes.addView(controlScroll, new LinearLayout.LayoutParams(0, -1, 1));
            root.addView(panes, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            root.addView(tracks);
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            LinearLayout content = column();
            content.addView(timeline, new LinearLayout.LayoutParams(-1, style.dp(212)));
            content.addView(controls);
            scroll.addView(content);
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        buildControls();
        LinearLayout footer = row();
        footer.setPadding(0, style.dp(12), 0, 0);
        preview = action(Strings.t("Preview"), this::preview);
        preview.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(style.dark ? 0x228e8e93 : 0x22000000),
                        style.shape(style.background, 28, true),
                        null));
        preview.setTag("transition-preview");
        LinearLayout.LayoutParams previewSpace = new LinearLayout.LayoutParams(0, -2, 1);
        previewSpace.rightMargin = style.dp(10);
        footer.addView(preview, previewSpace);
        TextView save =
                style.button(
                        Strings.t("Save"),
                        true,
                        () -> {
                            try {
                                normalize();
                                TransitionRuntime.save(pair, edit);
                                message(Strings.t("Saved"));
                            } catch (Exception unavailable) {
                                message(Strings.t("Could not save this transition."));
                            }
                        });
        save.setMinHeight(style.dp(48));
        save.setPadding(style.dp(16), style.dp(10), style.dp(16), style.dp(10));
        save.setTag("transition-save");
        TextView automatic =
                action(
                        Strings.t("Automatic"),
                        () -> {
                            stopPreview();
                            TransitionRuntime.remove(pair);
                            edit = new TransitionEdit();
                            normalize();
                            buildControls();
                            timeline.invalidate();
                            message(Strings.t("Automatic"));
                        });
        automatic.setBackground(null);
        automatic.setTextColor(style.secondary);
        automatic.setTextSize(14);
        automatic.setTag("transition-automatic");
        if (wide) {
            LinearLayout.LayoutParams automaticSpace = new LinearLayout.LayoutParams(0, -2, 1);
            automaticSpace.rightMargin = style.dp(10);
            footer.addView(automatic, automaticSpace);
        }
        footer.addView(save, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(footer);
        if (!wide) root.addView(automatic);
        setContentView(root);
        if (saved != null)
            try {
                edit = TransitionEdit.decode(saved.getString("draft"));
                normalize();
                buildControls();
            } catch (Exception ignored) {
            }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        if (edit != null) out.putString("draft", edit.encode());
        super.onSaveInstanceState(out);
    }

    @Override
    protected void onStop() {
        stopPreview();
        super.onStop();
    }

    private void normalize() {
        edit.outgoingTrim = Math.max(0, Math.min(edit.outgoingTrim, pair.outgoingDuration - .5));
        edit.incomingCue = Math.max(0, Math.min(edit.incomingCue, pair.incomingDuration - 1.5));
        edit.overlap = Math.max(.5, Math.min(edit.overlap, pair.maximumOverlap(edit)));
        edit.validate();
    }

    private void message(String text) {
        status.setText(text);
        if (wide) status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void changed() {
        normalize();
        timeline.invalidate();
        message("");
    }

    private void buildControls() {
        controls.removeAllViews();
        LinearLayout timing = group(controls);
        slider(
                timing,
                Strings.t("Overlap"),
                .5,
                pair.maximumOverlap(edit),
                edit.overlap,
                " s",
                value -> {
                    edit.overlap = value;
                    changed();
                });
        slider(
                timing,
                Strings.t("Outgoing") + " · " + Strings.t("End"),
                0,
                Math.min(120, pair.outgoingDuration - .5),
                edit.outgoingTrim,
                " s",
                value -> {
                    edit.outgoingTrim = value;
                    changed();
                });
        slider(
                timing,
                Strings.t("Incoming") + " · " + Strings.t("Start"),
                0,
                Math.min(3600, pair.incomingDuration - 1.5),
                edit.incomingCue,
                " s",
                value -> {
                    edit.incomingCue = value;
                    changed();
                });
        LinearLayout envelope = group(controls);
        choose(
                envelope,
                Strings.t("Curve"),
                new String[] {
                    Strings.t("Linear"),
                    Strings.t("Equal power"),
                    Strings.t("Smooth"),
                    Strings.t("Custom")
                },
                edit.curve,
                value -> {
                    edit.curve = value;
                    changed();
                });
        choose(
                envelope,
                Strings.t("Volume"),
                new String[] {Strings.t("Outgoing"), Strings.t("Incoming")},
                side,
                value -> {
                    side = value;
                    buildControls();
                    timeline.invalidate();
                });
        choose(
                envelope,
                Strings.t("Point"),
                new String[] {"1", "2", "3", "4"},
                point,
                value -> {
                    point = value;
                    buildControls();
                    timeline.invalidate();
                });
        if (point > 0 && point < 3) {
            final double offset = side == 0 ? edit.start(pair.outgoingDuration) : edit.incomingCue;
            slider(
                    envelope,
                    Strings.t("Time"),
                    offset + (edit.times[side][point - 1] + TransitionEdit.MIN_GAP) * edit.overlap,
                    offset + (edit.times[side][point + 1] - TransitionEdit.MIN_GAP) * edit.overlap,
                    offset + edit.times[side][point] * edit.overlap,
                    " s",
                    value -> {
                        edit.times[side][point] = (value - offset) / edit.overlap;
                        edit.curve = TransitionEdit.CUSTOM;
                        changed();
                    });
        }
        slider(
                envelope,
                Strings.t("Volume"),
                0,
                100,
                edit.levels[side][point] * 100,
                "%",
                value -> {
                    edit.levels[side][point] = value / 100;
                    edit.curve = TransitionEdit.CUSTOM;
                    changed();
                });
        LinearLayout effects = group(controls);
        TextView eqTitle = label(Strings.t("Equaliser"), 16, true);
        eqTitle.setPadding(style.dp(4), style.dp(12), style.dp(4), style.dp(8));
        effects.addView(eqTitle);
        String[] names = {Strings.t("Low"), Strings.t("Mid"), Strings.t("High")};
        for (int band = 0; band < 3; band++) {
            final int selected = band;
            slider(
                    effects,
                    names[band],
                    -12,
                    12,
                    edit.eq[side][band],
                    " dB",
                    value -> {
                        edit.eq[side][selected] = value;
                        changed();
                    });
        }
        choose(
                effects,
                Strings.t("Filter"),
                new String[] {Strings.t("None"), Strings.t("Low-pass"), Strings.t("High-pass")},
                edit.filter[side],
                value -> {
                    edit.filter[side] = value;
                    if (value == 2) edit.cutoff[side] = Math.min(edit.cutoff[side], 2000);
                    changed();
                    buildControls();
                });
        if (edit.filter[side] != 0)
            slider(
                    effects,
                    Strings.t("Cutoff"),
                    20,
                    edit.filter[side] == 2 ? 2000 : 20000,
                    edit.cutoff[side],
                    " Hz",
                    value -> {
                        edit.cutoff[side] = value;
                        changed();
                    });
    }

    interface Value {
        void set(double value);
    }

    interface Choice {
        void set(int value);
    }

    private void slider(
            LinearLayout parent,
            String title,
            double min,
            double max,
            double initial,
            String unit,
            Value change) {
        max = Math.max(min, max);
        final double upper = max;
        final double[] current = {initial};
        LinearLayout group = column();
        group.setPadding(style.dp(4), style.dp(6), style.dp(4), style.dp(6));
        TextView caption = label(title + " · " + number(initial) + unit, 15, true);
        caption.setMinHeight(style.dp(48));
        caption.setGravity(Gravity.CENTER_VERTICAL);
        caption.setClickable(true);
        caption.setFocusable(true);
        SeekBar bar = new SeekBar(this);
        bar.setMax(1000);
        bar.setMinHeight(style.dp(48));
        bar.setPadding(style.dp(8), 0, style.dp(8), 0);
        bar.setThumbTintList(ColorStateList.valueOf(style.foreground));
        bar.setProgressTintList(ColorStateList.valueOf(style.foreground));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(style.outline));
        bar.setContentDescription(title);
        bar.setProgress(
                upper == min ? 0 : (int) Math.round((initial - min) * 1000 / (upper - min)));
        group.addView(caption);
        group.addView(bar, new LinearLayout.LayoutParams(-1, style.dp(48)));
        parent.addView(group);
        bar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    public void onStartTrackingTouch(SeekBar b) {}

                    public void onStopTrackingTouch(SeekBar b) {
                        buildControls();
                    }

                    public void onProgressChanged(SeekBar b, int value, boolean user) {
                        if (user) {
                            double actual = min + (upper - min) * value / 1000;
                            current[0] = actual;
                            change.set(actual);
                            caption.setText(title + " · " + number(actual) + unit);
                        }
                    }
                });
        caption.setOnClickListener(
                v -> {
                    PlaylistModal modal = new PlaylistModal(this, title, "");
                    EditText value = modal.input(title);
                    value.setInputType(
                            InputType.TYPE_CLASS_NUMBER
                                    | InputType.TYPE_NUMBER_FLAG_DECIMAL
                                    | InputType.TYPE_NUMBER_FLAG_SIGNED);
                    value.setText(number(current[0]));
                    value.setSelectAllOnFocus(true);
                    modal.action(
                            Strings.t("Save"),
                            true,
                            () -> {
                                try {
                                    String text = value.getText().toString().trim();
                                    java.text.ParsePosition at = new java.text.ParsePosition(0);
                                    Number parsed =
                                            NumberFormat.getNumberInstance().parse(text, at);
                                    if (parsed == null || at.getIndex() != text.length())
                                        throw new IllegalArgumentException();
                                    double actual = parsed.doubleValue();
                                    if (!Double.isFinite(actual) || actual < min || actual > upper)
                                        throw new IllegalArgumentException();
                                    change.set(actual);
                                    modal.dismiss();
                                    buildControls();
                                } catch (Exception invalid) {
                                    value.setError(number(min) + " – " + number(upper));
                                }
                            });
                    modal.action(Strings.t("Cancel"), false, modal::dismiss);
                    modal.show();
                });
    }

    private void choose(
            LinearLayout parent, String title, String[] values, int selected, Choice choice) {
        TextView action =
                action(
                        title + " · " + values[selected],
                        () -> {
                            AlertDialog dialog =
                                    new AlertDialog.Builder(this)
                                            .setTitle(title)
                                            .setSingleChoiceItems(
                                                    values,
                                                    selected,
                                                    (d, index) -> {
                                                        choice.set(index);
                                                        d.dismiss();
                                                        buildControls();
                                                    })
                                            .setNegativeButton(Strings.t("Cancel"), null)
                                            .create();
                            dialog.setOnShowListener(d -> style.tuneDialog(dialog));
                            dialog.show();
                        });
        action.setBackground(null);
        action.setTextSize(15);
        action.setTypeface(style.medium);
        action.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        action.setPadding(style.dp(4), style.dp(8), style.dp(4), style.dp(8));
        action.setMinHeight(style.dp(48));
        parent.addView(action);
    }

    private void preview() {
        if (audition != null) {
            stopPreview();
            return;
        }
        try {
            normalize();
            audition =
                    new TransitionRuntime.Preview(
                            pair,
                            edit,
                            () -> {
                                audition = null;
                                if (!isFinishing()) {
                                    preview.setText(Strings.t("Preview"));
                                    message(Strings.t("Preview finished"));
                                }
                            });
            preview.setText(Strings.t("Stop"));
            message(Strings.t("Preview"));
        } catch (Exception unavailable) {
            message(
                    AutomixAccess.enabled()
                            ? Strings.t("Could not preview this transition.")
                            : Strings.t("Enable Automix in Playback settings first."));
        }
    }

    private void stopPreview() {
        if (audition != null) audition.stop();
    }

    private static String number(double value) {
        NumberFormat n = NumberFormat.getNumberInstance();
        n.setMaximumFractionDigits(3);
        return n.format(value);
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private LinearLayout group(LinearLayout parent) {
        LinearLayout group = column();
        group.setBackground(style.shape(style.card, 16, false));
        group.setPadding(style.dp(12), style.dp(4), style.dp(12), style.dp(4));
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
        space.topMargin = wide && parent.getChildCount() == 0 ? 0 : style.dp(12);
        parent.addView(group, space);
        return group;
    }

    private LinearLayout row() {
        LinearLayout v = new LinearLayout(this);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private TextView label(String text, int size, boolean bold) {
        return style.text(
                text,
                size,
                bold ? style.foreground : style.secondary,
                bold ? style.semibold : style.regular);
    }

    private TextView action(String title, Runnable run) {
        TextView action = style.button(title, false, run);
        action.setMinHeight(style.dp(48));
        action.setPadding(style.dp(16), style.dp(10), style.dp(16), style.dp(10));
        return action;
    }

    private final class Timeline extends View {
        final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path curve = new Path();
        int dragging = -1, deck;

        Timeline() {
            super(TransitionEditorActivity.this);
            setContentDescription(Strings.t("Volume"));
            setFocusable(false);
        }

        float x(double time) {
            return style.dp(24) + (getWidth() - style.dp(48)) * (float) time;
        }

        float y(int deck, double level) {
            return style.dp(80 + deck * 100) - style.dp(48) * (float) level;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            ink.setTypeface(style.regular);
            ink.setTextSize(style.dp(12));
            for (int deck = 0; deck < 2; deck++) {
                ink.setColor(style.secondary);
                canvas.drawText(
                        deck == 0 ? Strings.t("Outgoing") : Strings.t("Incoming"),
                        style.dp(24),
                        style.dp(20 + deck * 100),
                        ink);
                ink.setColor(style.outline);
                ink.setStrokeWidth(style.dp(1));
                for (int i = 0; i < 5; i++) {
                    float tick = x(i / 4d);
                    canvas.drawLine(tick, y(deck, 0), tick, y(deck, 1), ink);
                }
                int color = deck == 0 ? style.foreground : (style.dark ? 0xff30d158 : 0xff15803d);
                ink.setColor(color);
                ink.setStrokeWidth(style.dp(2));
                ink.setStyle(Paint.Style.STROKE);
                curve.reset();
                for (int i = 0; i <= 120; i++) {
                    float x = x(i / 120d), y = y(deck, edit.gain(deck, i / 120d));
                    if (i == 0) curve.moveTo(x, y);
                    else curve.lineTo(x, y);
                }
                canvas.drawPath(curve, ink);
                ink.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 4; i++) {
                    canvas.drawCircle(
                            x(edit.times[deck][i]),
                            y(deck, edit.levels[deck][i]),
                            style.dp(i == point && deck == side ? 7 : 5),
                            ink);
                }
                ink.setColor(style.secondary);
                canvas.drawText(
                        number(deck == 0 ? edit.start(pair.outgoingDuration) : edit.incomingCue)
                                + " s",
                        style.dp(24),
                        style.dp(98 + deck * 100),
                        ink);
                String end =
                        number(
                                        (deck == 0
                                                        ? edit.start(pair.outgoingDuration)
                                                        : edit.incomingCue)
                                                + edit.overlap)
                                + " s";
                canvas.drawText(
                        end,
                        getWidth() - style.dp(24) - ink.measureText(end),
                        style.dp(98 + deck * 100),
                        ink);
            }
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                float best = style.dp(24);
                for (int d = 0; d < 2; d++)
                    for (int i = 0; i < 4; i++) {
                        float distance =
                                (float)
                                        Math.hypot(
                                                event.getX() - x(edit.times[d][i]),
                                                event.getY() - y(d, edit.levels[d][i]));
                        if (distance < best) {
                            best = distance;
                            dragging = i;
                            deck = d;
                        }
                    }
                if (dragging < 0) return false;
                getParent().requestDisallowInterceptTouchEvent(true);
                side = deck;
                point = dragging;
                return true;
            }
            if (event.getAction() == android.view.MotionEvent.ACTION_MOVE && dragging >= 0) {
                edit.curve = TransitionEdit.CUSTOM;
                double at = (event.getX() - style.dp(24)) / (getWidth() - style.dp(48));
                if (dragging > 0 && dragging < 3)
                    edit.times[deck][dragging] =
                            Math.max(
                                    edit.times[deck][dragging - 1] + TransitionEdit.MIN_GAP,
                                    Math.min(
                                            edit.times[deck][dragging + 1] - TransitionEdit.MIN_GAP,
                                            at));
                edit.levels[deck][dragging] =
                        Math.max(
                                0,
                                Math.min(
                                        1,
                                        (style.dp(80 + deck * 100) - event.getY()) / style.dp(48)));
                changed();
                return true;
            }
            if (event.getAction() == android.view.MotionEvent.ACTION_UP
                    || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                boolean handled = dragging >= 0;
                dragging = -1;
                getParent().requestDisallowInterceptTouchEvent(false);
                if (handled) {
                    performClick();
                    buildControls();
                }
                return handled;
            }
            return dragging >= 0;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
