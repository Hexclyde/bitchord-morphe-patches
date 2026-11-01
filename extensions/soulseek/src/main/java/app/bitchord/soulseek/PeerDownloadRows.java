package app.bitchord.soulseek;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Keeps transfer identities, focus and row views stable across progress snapshots. */
final class PeerDownloadRows {
    private final DownloadStyle style;
    private final Map<LinearLayout, LinkedHashMap<String, Row>> sections = new IdentityHashMap<>();
    private final Map<LinearLayout, Row> owners = new IdentityHashMap<>();

    private static final class Row {
        final LinearLayout view;
        Row actions;
        int next;
        boolean seen;

        Row(LinearLayout view) {
            this.view = view;
        }
    }

    PeerDownloadRows(DownloadStyle style) {
        this.style = style;
    }

    /** Draws hairlines between a section's rows; removing a row can never orphan a divider. */
    void group(LinearLayout section) {
        style.card(section);
        section.setPadding(0, style.dp(2), 0, style.dp(2));
        GradientDrawable line = new GradientDrawable();
        line.setColor(style.outline);
        line.setSize(0, Math.max(1, style.dp(.5f)));
        section.setDividerDrawable(line);
        section.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        section.setDividerPadding(style.dp(16));
    }

    void begin(LinearLayout section) {
        for (Row row : sections.computeIfAbsent(section, key -> new LinkedHashMap<>()).values())
            row.seen = false;
    }

    LinearLayout row(LinearLayout section, String key) {
        Map<String, Row> rows = sections.get(section);
        Row row = rows.get(key);
        if (row == null) {
            LinearLayout view = new LinearLayout(section.getContext());
            view.setOrientation(LinearLayout.VERTICAL);
            view.setPadding(style.dp(16), style.dp(10), style.dp(16), style.dp(10));
            view.setTag("peer-row:" + key);
            style.markCustom(view);
            row = new Row(view);
            rows.put(key, row);
            owners.put(view, row);
            section.addView(view, new LinearLayout.LayoutParams(-1, -2));
        }
        row.seen = true;
        row.next = 0;
        return row.view;
    }

    TextView text(LinearLayout parent, String value, int size) {
        Row row = owners.get(parent);
        if (row == null) return null;
        TextView view;
        View next = row.next < parent.getChildCount() ? parent.getChildAt(row.next) : null;
        if (next instanceof TextView && !(next instanceof Button)) view = (TextView) next;
        else {
            view = style.text("", size, style.foreground, style.regular);
            parent.addView(view, row.next, new LinearLayout.LayoutParams(-1, -2));
        }
        row.next++;
        if (!value.contentEquals(view.getText())) view.setText(value);
        view.setTextSize(size);
        int color = size >= 15 ? style.foreground : style.secondary;
        if (view.getCurrentTextColor() != color) view.setTextColor(color);
        view.setTypeface(size >= 16 ? style.medium : style.regular);
        return view;
    }

    boolean button(LinearLayout parent, String label, Runnable action) {
        return action(parent, label, label, false, action) != null;
    }

    /** A labelled pill; descriptions name the transfer so repeated labels stay distinguishable. */
    Button action(
            LinearLayout parent,
            String label,
            String description,
            boolean primary,
            Runnable action) {
        Row row = owners.get(parent);
        if (row == null) return null;
        Button view;
        View next = row.next < parent.getChildCount() ? parent.getChildAt(row.next) : null;
        if (next instanceof Button) view = (Button) next;
        else {
            view = new Button(parent.getContext());
            boolean inline = parent.getOrientation() == LinearLayout.HORIZONTAL;
            LinearLayout.LayoutParams place =
                    inline
                            ? new LinearLayout.LayoutParams(0, -2, 1)
                            : new LinearLayout.LayoutParams(-1, -2);
            if (inline && row.next > 0) place.setMarginStart(style.dp(8));
            parent.addView(view, row.next, place);
            style.button(view);
        }
        row.next++;
        if (!label.contentEquals(view.getText())) view.setText(label);
        if (!description.equals(String.valueOf(view.getContentDescription())))
            view.setContentDescription(description);
        tone(view, primary);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    /** Applies the page's single emphasis; only state changes touch the drawable. */
    void tone(Button view, boolean primary) {
        if (Boolean.valueOf(primary).equals(view.getTag())) return;
        view.setTag(primary);
        view.setTextColor(primary ? style.background : style.foreground);
        view.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(style.dark ? 0x228e8e93 : 0x22000000),
                        style.shape(primary ? style.foreground : style.field, 28, false),
                        null));
    }

    /** The row's reusable action strip; hold at most one primary pair plus overflow. */
    LinearLayout actions(LinearLayout parent) {
        Row row = owners.get(parent);
        if (row == null) return null;
        // A row losing its progress bar keeps its strip, and so a focused control inside it.
        if (row.actions != null && row.actions.view.getParent() == parent)
            while (parent.indexOfChild(row.actions.view) > row.next) parent.removeViewAt(row.next);
        View next = row.next < parent.getChildCount() ? parent.getChildAt(row.next) : null;
        if (row.actions == null || next != row.actions.view) {
            forgetActions(row);
            LinearLayout strip = new LinearLayout(parent.getContext());
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            style.markCustom(strip);
            parent.addView(strip, row.next, new LinearLayout.LayoutParams(-1, -2));
            row.actions = new Row(strip);
            owners.put(strip, row.actions);
        }
        row.next++;
        row.actions.next = 0;
        return row.actions.view;
    }

    /** A 48dp overflow control, keeping secondary actions out of the row until requested. */
    ImageButton overflow(LinearLayout parent, String description, Runnable action) {
        Row row = owners.get(parent);
        if (row == null) return null;
        ImageButton view;
        View next = row.next < parent.getChildCount() ? parent.getChildAt(row.next) : null;
        if (next instanceof ImageButton) view = (ImageButton) next;
        else {
            view = new ImageButton(parent.getContext());
            view.setImageDrawable(new MoreIcon(style));
            view.setBackground(
                    new RippleDrawable(
                            ColorStateList.valueOf(style.dark ? 0x228e8e93 : 0x22000000),
                            null,
                            style.shape(style.foreground, 24, false)));
            view.setScaleType(ImageButton.ScaleType.CENTER);
            view.setPadding(0, 0, 0, 0);
            style.markCustom(view);
            LinearLayout.LayoutParams place =
                    new LinearLayout.LayoutParams(style.dp(48), style.dp(48));
            place.setMarginStart(style.dp(4));
            parent.addView(view, row.next, place);
        }
        row.next++;
        if (!description.equals(String.valueOf(view.getContentDescription())))
            view.setContentDescription(description);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    /** Thin determinate progress; adjacent text already carries the spoken value. */
    ProgressBar progress(LinearLayout parent, long done, long total) {
        Row row = owners.get(parent);
        if (row == null) return null;
        ProgressBar view;
        View next = row.next < parent.getChildCount() ? parent.getChildAt(row.next) : null;
        if (next instanceof ProgressBar) view = (ProgressBar) next;
        else {
            view =
                    new ProgressBar(
                            parent.getContext(), null, android.R.attr.progressBarStyleHorizontal);
            view.setMax(1000);
            view.setProgressTintList(ColorStateList.valueOf(0xff2ed15a));
            view.setProgressBackgroundTintList(ColorStateList.valueOf(style.outline));
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            style.markCustom(view);
            LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(-1, style.dp(4));
            place.topMargin = style.dp(8);
            place.bottomMargin = style.dp(4);
            parent.addView(view, row.next, place);
        }
        row.next++;
        int value = total <= 0 ? 0 : (int) Math.max(0, Math.min(1000, done * 1000 / total));
        if (view.getProgress() != value) view.setProgress(value);
        return view;
    }

    void end(LinearLayout section) {
        Iterator<Row> iterator = sections.get(section).values().iterator();
        while (iterator.hasNext()) {
            Row row = iterator.next();
            if (!row.seen) {
                section.removeView(row.view);
                owners.remove(row.view);
                if (row.actions != null) owners.remove(row.actions.view);
                iterator.remove();
                continue;
            }
            trim(row);
            if (row.actions != null) {
                if (row.actions.view.getParent() != row.view) forgetActions(row);
                else trim(row.actions);
            }
        }
    }

    private static void trim(Row row) {
        while (row.view.getChildCount() > row.next)
            row.view.removeViewAt(row.view.getChildCount() - 1);
    }

    private void forgetActions(Row row) {
        if (row.actions == null) return;
        owners.remove(row.actions.view);
        ViewGroup parent = (ViewGroup) row.actions.view.getParent();
        if (parent != null) parent.removeView(row.actions.view);
        row.actions = null;
    }

    /** Peer keys arrive over the network; a malformed value must never break rendering. */
    static String shortKey(String key) {
        String clean = key == null ? "" : key.replaceAll("[\\p{Cntrl}\\p{Cf}\\s]", "");
        if (clean.length() <= 12) return clean.isEmpty() ? "…" : clean;
        return clean.substring(0, 12) + "…";
    }

    /** One three-dot glyph from the same drawn icon family as the other download controls. */
    private static final class MoreIcon extends Drawable {
        private final DownloadStyle style;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        MoreIcon(DownloadStyle style) {
            this.style = style;
            paint.setColor(style.foreground);
        }

        @Override
        public void draw(Canvas canvas) {
            float x = getBounds().exactCenterX(), y = getBounds().exactCenterY();
            float radius = style.dp(1.75f), gap = style.dp(5.5f);
            for (int i = -1; i <= 1; i++) canvas.drawCircle(x, y + i * gap, radius, paint);
        }

        @Override
        public int getIntrinsicWidth() {
            return style.dp(24);
        }

        @Override
        public int getIntrinsicHeight() {
            return style.dp(24);
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

    /** Holds the first visible view steady on screen when rows above it change height. */
    static final class ScrollAnchor {
        private final ScrollView scroll;
        private View anchor;
        private int offset;
        private boolean armed;
        private final ViewTreeObserver.OnPreDrawListener restore =
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        disarm();
                        View target = anchor;
                        anchor = null;
                        if (target == null || !within(target)) return true;
                        int shift = top(target) - scroll.getScrollY() - offset;
                        if (shift == 0) return true;
                        scroll.scrollBy(0, shift);
                        return true;
                    }
                };

        ScrollAnchor(ScrollView scroll) {
            this.scroll = scroll;
        }

        /** Captures the anchor before a snapshot is applied; a pending capture is kept. */
        void capture() {
            if (armed || scroll.getScrollY() == 0 || scroll.getChildCount() == 0) return;
            View found = firstVisible(scroll.getChildAt(0), scroll.getScrollY());
            if (found == null) return;
            anchor = found;
            offset = top(found) - scroll.getScrollY();
            armed = true;
            scroll.getViewTreeObserver().addOnPreDrawListener(restore);
        }

        void release() {
            disarm();
            anchor = null;
        }

        private void disarm() {
            if (!armed) return;
            armed = false;
            ViewTreeObserver observer = scroll.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(restore);
        }

        private boolean within(View view) {
            for (Object at = view; at != null; at = ((View) at).getParent()) {
                if (at == scroll) return true;
                if (!(at instanceof View)) return false;
            }
            return false;
        }

        private int top(View view) {
            int y = 0;
            for (View at = view; at != scroll.getChildAt(0); at = (View) at.getParent())
                y += at.getTop();
            return y;
        }

        private View firstVisible(View view, int y) {
            if (!(view instanceof LinearLayout) || isRow(view)) return view;
            LinearLayout group = (LinearLayout) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.GONE && child.getBottom() > y)
                    return firstVisible(child, y - child.getTop());
            }
            return view == scroll.getChildAt(0) ? null : view;
        }

        private static boolean isRow(View view) {
            Object tag = view.getTag();
            return tag instanceof String && ((String) tag).startsWith("peer-row:");
        }
    }

    /**
     * Loads one snapshot at a time off the UI thread. Requests during a load collapse into one
     * follow-up, and a result reaches the page only while resumed and still the latest load.
     */
    static final class Refresher<T> {
        interface Source<T> {
            T load() throws Exception;
        }

        interface Target<T> {
            void apply(T snapshot);

            void failed();
        }

        private final Executor background;
        private final Handler ui;
        private final long interval;
        private final Source<T> source;
        private final Target<T> target;
        private final Runnable tick = this::request;
        private boolean resumed, loading, again;
        private int generation;

        Refresher(
                Executor background,
                Handler ui,
                long interval,
                Source<T> source,
                Target<T> target) {
            this.background = background;
            this.ui = ui;
            this.interval = interval;
            this.source = source;
            this.target = target;
        }

        void resume() {
            if (resumed) return;
            resumed = true;
            request();
        }

        void pause() {
            resumed = false;
            loading = false;
            again = false;
            generation++;
            ui.removeCallbacks(tick);
        }

        void request() {
            if (!resumed) return;
            if (loading) {
                again = true;
                return;
            }
            ui.removeCallbacks(tick);
            loading = true;
            final int current = ++generation;
            try {
                background.execute(
                        () -> {
                            T value = null;
                            boolean ok = false;
                            try {
                                value = source.load();
                                ok = true;
                            } catch (Exception | LinkageError unavailable) {
                                // The page keeps its last snapshot and reports the failure.
                            }
                            final T loaded = value;
                            final boolean success = ok;
                            ui.post(() -> finish(current, loaded, success));
                        });
            } catch (RejectedExecutionException closed) {
                loading = false;
            }
        }

        private void finish(int token, T value, boolean success) {
            if (token != generation || !resumed) return;
            loading = false;
            if (success) target.apply(value);
            else target.failed();
            if (!resumed) return;
            if (again) {
                again = false;
                request();
            } else ui.postDelayed(tick, interval);
        }
    }
}
