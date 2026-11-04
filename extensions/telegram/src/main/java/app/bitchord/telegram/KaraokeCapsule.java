package app.bitchord.telegram;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;

/**
 * The Karaoke control on BitChord's lyrics: a frosted capsule holding a microphone, which opens
 * into a vertical slider for how much of the lead vocal is removed. Closing it fades the microphone
 * out; the microphone's place keeps its touch target and its description.
 */
final class KaraokeCapsule extends FrameLayout {
    static final int BASE_DP = 76, TRACK_DP = 44, ICON_DP = 34, EXPANDED_DP = 320;
    static final long AUTO_CLOSE_MS = 3_000, OPEN_MS = 400, FADE_MS = 280, GLITTER_MS = 600;
    private static final int ACTION_RESET = 0x4B000001,
            ACTION_FINER = 0x4B000002,
            ACTION_COARSER = 0x4B000003;
    private final float dp, sp;
    private final Mic mic;
    private final Track track;
    private final Off off;
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Runnable closeLater = () -> close(true);
    private final Runnable status = this::statusChanged;
    private final AccessibilityManager accessibility;

    private float open, shown = 1, glitter;
    private int tint = Color.WHITE;
    private boolean blurred, dragging, focusRevealed, pendingFade;
    private ValueAnimator timeline, shimmer;
    private Listener listener;
    private Runnable subscription;
    private int available = Integer.MAX_VALUE;

    /** Told when the microphone starts or stops being drawn, so the host blur can follow. */
    interface Listener {
        void visible(boolean visible);
    }

    KaraokeCapsule(Context context) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        dp = density;
        sp =
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP, 1f, context.getResources().getDisplayMetrics());
        accessibility =
                (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        setTag("karaoke-capsule");
        setClipChildren(false);
        setWillNotDraw(false);
        mic = new Mic(context);
        track = new Track(context);
        off = new Off(context);
        addView(track);
        addView(off);
        addView(mic);
        track.setVisibility(INVISIBLE);
        off.setVisibility(INVISIBLE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        refreshDescriptions();
    }

    // ── What the host sets ───────────────────────────────────────────────────

    void tint(int argb) {
        if (tint == argb) return;
        tint = argb;
        invalidateAll();
    }

    void blurred(boolean behind) {
        if (blurred == behind) return;
        blurred = behind;
        invalidate();
    }

    void listener(Listener changed) {
        listener = changed;
    }

    // ── Reading it ───────────────────────────────────────────────────────────

    boolean expanded() {
        return open > 0.5f;
    }

    boolean iconShown() {
        return shown > 0.01f;
    }

    float iconAlpha() {
        return shown;
    }

    float openFraction() {
        return open;
    }

    /** True while the glitter animation runs, which is only with the slider open and drawn. */
    boolean shimmering() {
        return shimmer != null;
    }

    View microphone() {
        return mic;
    }

    View slider() {
        return track;
    }

    View offButton() {
        return off;
    }

    // ── Opening, closing and fading ──────────────────────────────────────────

    /** The microphone was pressed: switch the separation model on if it is off, then toggle. */
    void press() {
        if (!KaraokeLyrics.modelOn()) KaraokeLyrics.activate();
        if (open > 0.5f && !pendingFade) close(true);
        else open();
    }

    void open() {
        cancelCloseLater();
        focusRevealed = false;
        pendingFade = false;
        announceVisible(true);
        go(1, 1, false);
        refreshDescriptions();
    }

    /** Hides the slider and then the microphone. Karaoke itself stays as it was. */
    void close(boolean fade) {
        cancelCloseLater();
        if (!fade && open <= 0.01f) return;
        pendingFade = fade;
        go(0, fade ? 0 : 1, fade);
        refreshDescriptions();
    }

    /** Brings the microphone back without opening the slider, for focus. */
    void reveal() {
        if (shown >= 1 || pendingFade && open > 0.01f) return;
        focusRevealed = true;
        announceVisible(true);
        go(open, 1, false);
    }

    private void fadeAfterFocus() {
        if (focusRevealed && open <= 0.01f) {
            focusRevealed = false;
            pendingFade = true;
            go(0, 0, true);
        }
    }

    private boolean animating() {
        return ValueAnimator.areAnimatorsEnabled();
    }

    private void go(float openTo, float shownTo, boolean staged) {
        if (timeline != null) timeline.cancel();
        final float openFrom = open, shownFrom = shown;
        if (!animating() || !isAttachedToWindow()) {
            apply(openTo, shownTo);
            return;
        }
        final long openMs = openTo != openFrom ? OPEN_MS : 0;
        final long fadeMs = shownTo != shownFrom ? FADE_MS : 0;
        // Closing waits for most of the collapse before the microphone fades.
        final long delay = staged ? openMs * 7 / 10 : 0;
        final long total = Math.max(openMs, delay + fadeMs);
        if (total == 0) {
            apply(openTo, shownTo);
            return;
        }
        PathInterpolator ease = new PathInterpolator(0.2f, 0.8f, 0.2f, 1f);
        ValueAnimator run = ValueAnimator.ofFloat(0, total);
        run.setDuration(total);
        run.addUpdateListener(
                a -> {
                    float elapsed = (Float) a.getAnimatedValue();
                    float opening = openMs == 0 ? 1 : Math.min(1, elapsed / openMs);
                    float fading = fadeMs == 0 ? 1 : clamp((elapsed - delay) / fadeMs, 0, 1);
                    apply(
                            openFrom + (openTo - openFrom) * ease.getInterpolation(opening),
                            shownFrom + (shownTo - shownFrom) * fading);
                });
        run.addListener(
                new android.animation.AnimatorListenerAdapter() {
                    boolean cancelled;

                    @Override
                    public void onAnimationCancel(android.animation.Animator a) {
                        cancelled = true;
                    }

                    @Override
                    public void onAnimationEnd(android.animation.Animator a) {
                        if (timeline == a) timeline = null;
                        if (!cancelled) apply(openTo, shownTo);
                    }
                });
        timeline = run;
        run.start();
    }

    private void apply(float openNow, float shownNow) {
        boolean wasShown = shown > 0.01f;
        boolean sized = openNow != open;
        open = openNow;
        shown = shownNow;
        boolean sliderVisible = open > 0.01f;
        int visibility = sliderVisible ? VISIBLE : INVISIBLE;
        if (track.getVisibility() != visibility) {
            track.setVisibility(visibility);
            off.setVisibility(visibility);
        }
        float sections = clamp((open - 0.35f) / 0.65f, 0, 1);
        track.setAlpha(sections);
        off.setAlpha(sections);
        if (sized) requestLayout();
        invalidateAll();
        boolean nowShown = shown > 0.01f;
        if (wasShown != nowShown) announceVisible(nowShown);
        if (open >= 0.999f) {
            pendingFade = false;
            startGlitter();
            scheduleClose();
        } else stopGlitter();
        if (open <= 0.001f && shown <= 0.001f) pendingFade = false;
    }

    private void announceVisible(boolean visible) {
        Listener told = listener;
        if (told != null) told.visible(visible);
    }

    private void scheduleClose() {
        cancelCloseLater();
        if (dragging || open < 0.999f || holdsOpen()) return;
        postDelayed(closeLater, AUTO_CLOSE_MS);
    }

    private void cancelCloseLater() {
        removeCallbacks(closeLater);
    }

    /** Touch exploration, a screen-reader or keyboard focus on the control keeps it open. */
    private boolean holdsOpen() {
        if (accessibility != null
                && accessibility.isEnabled()
                && accessibility.isTouchExplorationEnabled()) return true;
        return track.hasFocus()
                || off.hasFocus()
                || mic.hasFocus()
                || mic.isAccessibilityFocused()
                || track.isAccessibilityFocused()
                || off.isAccessibilityFocused();
    }

    void touched() {
        if (open >= 0.999f) scheduleClose();
    }

    // ── Glitter: only while the slider is open and fully drawn ───────────────

    private void startGlitter() {
        if (shimmer != null || !animating() || !isAttachedToWindow()) return;
        if (!KaraokeLyrics.modelOn() || KaraokeLyrics.level() == 0) return;
        ValueAnimator run = ValueAnimator.ofFloat(0, 1);
        run.setDuration(GLITTER_MS * 2);
        run.setRepeatCount(ValueAnimator.INFINITE);
        run.setInterpolator(null);
        run.addUpdateListener(
                a -> {
                    glitter = (Float) a.getAnimatedValue();
                    track.invalidate();
                });
        shimmer = run;
        run.start();
    }

    private void stopGlitter() {
        if (shimmer == null) return;
        shimmer.cancel();
        shimmer = null;
        track.invalidate();
    }

    // ── Measuring ────────────────────────────────────────────────────────────

    private int base() {
        return Math.round(BASE_DP * dp);
    }

    private int expandedHeight() {
        int wanted = Math.round(EXPANDED_DP * dp);
        return Math.max(base(), Math.min(wanted, available));
    }

    private float fontScale() {
        return clamp(getResources().getConfiguration().fontScale, 1f, 1.6f);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int limit =
                MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
                        ? Integer.MAX_VALUE
                        : MeasureSpec.getSize(heightSpec);
        available = Math.min(limit, windowRoom());
        int width = base();
        int collapsed = base();
        int height = Math.round(collapsed + (expandedHeight() - collapsed) * open);
        setMeasuredDimension(width, height);
        Geometry g = geometry(height);
        mic.measure(exactly(width), exactly(base()));
        off.measure(exactly(g.offWidth), exactly(g.offHeight));
        track.measure(exactly(width), exactly(Math.max(0, g.trackBottom - g.trackTop)));
    }

    private static int exactly(int size) {
        return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
    }

    /** Room left on screen for the capsule once the bars and cutouts are out. */
    private int windowRoom() {
        WindowInsets insets = getRootWindowInsets();
        View root = getRootView();
        if (insets == null || root == null || root.getHeight() == 0) return Integer.MAX_VALUE;
        int top, bottom;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars =
                    insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            top = bars.top;
            bottom = bars.bottom;
        } else {
            top = insets.getSystemWindowInsetTop();
            bottom = insets.getSystemWindowInsetBottom();
        }
        return Math.max(base(), root.getHeight() - top - bottom - Math.round(16 * dp));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left, height = bottom - top;
        mic.layout(0, height - base(), width, height);
        Geometry g = geometry(height);
        off.layout(
                (width - g.offWidth) / 2,
                g.offTop,
                (width + g.offWidth) / 2,
                g.offTop + g.offHeight);
        track.layout(0, g.trackTop, width, g.trackBottom);
    }

    /** Where the parts sit for a capsule this tall. */
    private Geometry geometry(int height) {
        Geometry g = new Geometry();
        int room = height - base();
        g.offHeight = Math.round(48 * dp);
        g.offWidth = Math.round(64 * dp);
        g.offTop = Math.round(6 * dp);
        g.readoutHeight = Math.round(26 * dp * fontScale());
        int stacked = g.offTop + g.offHeight + g.readoutHeight;
        int gap = Math.round(12 * dp);
        int minTrack = Math.round(48 * dp);
        if (room - stacked - gap < minTrack) {
            // Short screens give up the Off row's margin first, then the status line.
            g.offTop = 0;
            g.readoutHeight = Math.round(20 * dp * fontScale());
            stacked = g.offHeight + g.readoutHeight;
        }
        g.readoutTop = g.offTop + g.offHeight;
        g.trackTop = g.readoutTop + g.readoutHeight;
        g.trackBottom = Math.max(g.trackTop, height - base() - gap);
        return g;
    }

    private static final class Geometry {
        int offTop, offWidth, offHeight, readoutTop, readoutHeight, trackTop, trackBottom;
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    @Override
    protected void dispatchDraw(Canvas canvas) {
        drawGlass(canvas);
        drawReadout(canvas);
        super.dispatchDraw(canvas);
    }

    private void drawGlass(Canvas canvas) {
        if (shown <= 0.001f) return;
        float inset = 0.5f * dp, radius = getWidth() / 2f;
        box.set(inset, inset, getWidth() - inset, getHeight() - inset);
        glass.setStyle(Paint.Style.FILL);
        glass.setShader(null);
        // The host blur supplies the frost; without it the capsule is a little denser.
        glass.setColor(withAlpha(tint, (blurred ? 0.05f : 0.16f) * shown));
        canvas.drawRoundRect(box, radius, radius, glass);
        glass.setStyle(Paint.Style.STROKE);
        glass.setStrokeWidth(dp);
        glass.setColor(withAlpha(tint, 0.15f * shown));
        canvas.drawRoundRect(box, radius, radius, glass);
        box.inset(dp, dp);
        glass.setStrokeWidth(dp);
        glass.setShader(
                new LinearGradient(
                        0,
                        box.top,
                        0,
                        box.top + box.height() * 0.5f,
                        withAlpha(tint, 0.20f * shown),
                        withAlpha(tint, 0f),
                        Shader.TileMode.CLAMP));
        canvas.drawRoundRect(box, radius - dp, radius - dp, glass);
        glass.setShader(null);
    }

    private void drawReadout(Canvas canvas) {
        if (open <= 0.35f || shown <= 0.01f) return;
        Geometry g = geometry(getHeight());
        float alpha = clamp((open - 0.35f) / 0.65f, 0, 1) * shown;
        String value = KaraokeLyrics.percent();
        String note = KaraokeLyrics.note();
        float maxWidth = getWidth() - 12 * dp;
        text.setColor(withAlpha(tint, 0.95f * alpha));
        float size = 14 * sp;
        text.setTextSize(fit(value, size, maxWidth));
        float centre = g.readoutTop + g.readoutHeight * (note.isEmpty() ? 0.62f : 0.46f);
        canvas.drawText(value, getWidth() / 2f, centre, text);
        if (!note.isEmpty()) {
            text.setColor(withAlpha(tint, 0.7f * alpha));
            text.setTextSize(fit(note, 10 * sp, maxWidth));
            canvas.drawText(note, getWidth() / 2f, g.readoutTop + g.readoutHeight * 0.88f, text);
        }
    }

    /** The largest size up to {@code size} at which {@code value} still fits. */
    private float fit(String value, float size, float width) {
        text.setTextSize(size);
        float needed = text.measureText(value);
        if (needed <= width || needed == 0) return size;
        return Math.max(7 * sp, size * width / needed);
    }

    private void invalidateAll() {
        invalidate();
        mic.invalidate();
        track.invalidate();
        off.invalidate();
    }

    // ── Model and level changes ──────────────────────────────────────────────

    private void statusChanged() {
        if (!isAttachedToWindow()) return;
        refreshDescriptions();
        invalidateAll();
        if (track.getVisibility() == VISIBLE) {
            track.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            if (open >= 0.999f && KaraokeLyrics.modelOn() && KaraokeLyrics.level() > 0)
                startGlitter();
        }
    }

    private void refreshDescriptions() {
        mic.refresh();
        track.refresh();
        off.refresh();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (subscription != null) SeparationModel.unwatch(subscription);
        final WeakReference<KaraokeCapsule> self = new WeakReference<>(this);
        final Runnable[] own = new Runnable[1];
        own[0] =
                () -> {
                    KaraokeCapsule view = self.get();
                    // A capsule that was collected stops hearing; one that is gone ignores it.
                    if (view == null) SeparationModel.unwatch(own[0]);
                    else view.post(view.status);
                };
        subscription = own[0];
        SeparationModel.watch(subscription);
        refreshDescriptions();
    }

    @Override
    protected void onDetachedFromWindow() {
        // Only this capsule's own subscription goes; another capsule's stays.
        if (subscription != null) SeparationModel.unwatch(subscription);
        subscription = null;
        removeCallbacks(status);
        cancelCloseLater();
        if (timeline != null) timeline.cancel();
        timeline = null;
        stopGlitter();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        requestLayout();
        invalidateAll();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) {
            refreshDescriptions();
            invalidateAll();
        } else stopGlitter();
        if (hasWindowFocus && open >= 0.999f) startGlitter();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) stopGlitter();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE
                && event.getAction() == KeyEvent.ACTION_DOWN
                && open > 0.01f) {
            close(true);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static int withAlpha(int color, float alpha) {
        int a = Math.round(clamp(alpha, 0, 1) * (Color.alpha(color)));
        return (color & 0x00ffffff) | (a << 24);
    }

    private static void note(Path path) {
        path.moveTo(17, 22);
        path.lineTo(17, 4);
        path.lineTo(32, 8);
        path.lineTo(32, 14);
        path.lineTo(17, 10);
    }

    // ── The microphone ───────────────────────────────────────────────────────

    private final class Mic extends View {
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path head = new Path(), handle = new Path(), stand = new Path();
        private final Path noteStem = new Path(), cord = new Path(), switchPath = new Path();

        Mic(Context context) {
            super(context);
            setTag("karaoke-microphone");
            setClickable(true);
            setFocusable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setStrokeJoin(Paint.Join.ROUND);
            ink.setStrokeWidth(5);
            note(noteStem);
            handle.moveTo(45, 35);
            handle.lineTo(20, 60);
            handle.arcTo(
                    new RectF(25 - 7.0711f, 65 - 7.0711f, 25 + 7.0711f, 65 + 7.0711f), -135, -180);
            handle.lineTo(55, 45);
            switchPath.moveTo(34, 49);
            switchPath.lineTo(39, 44);
            switchPath.lineTo(44, 49);
            switchPath.lineTo(39, 54);
            switchPath.close();
            cord.moveTo(23, 67);
            cord.cubicTo(5, 80, 15, 95, 28, 92);
            cord.cubicTo(38, 90, 38, 100, 38, 100);
            setOnClickListener(v -> press());
            setOnFocusChangeListener(
                    (v, focused) -> {
                        if (focused) reveal();
                        else {
                            fadeAfterFocus();
                            touched();
                        }
                    });
            TypedValue ripple = new TypedValue();
            if (context.getTheme()
                    .resolveAttribute(
                            android.R.attr.selectableItemBackgroundBorderless, ripple, true))
                setBackgroundResource(ripple.resourceId);
        }

        void refresh() {
            setContentDescription(Strings.t("Karaoke"));
            String state = KaraokeLyrics.spoken();
            if (Build.VERSION.SDK_INT >= 30) setStateDescription(state);
            else setContentDescription(Strings.t("Karaoke") + ", " + state);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(base(), base());
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Button");
            info.addAction(
                    new AccessibilityNodeInfo.AccessibilityAction(
                            AccessibilityNodeInfo.ACTION_CLICK,
                            open > 0.5f
                                    ? Strings.t("Close Karaoke controls")
                                    : Strings.t("Open Karaoke controls")));
        }

        @Override
        public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) reveal();
            else if (action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) {
                post(KaraokeCapsule.this::fadeAfterFocus);
                touched();
            }
            return super.performAccessibilityAction(action, arguments);
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.getRepeatCount() == 0) press();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (shown <= 0.001f) return;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float size = ICON_DP * dp * (1f + 0.1f * open);
            if (open > 0.01f) {
                ink.setStyle(Paint.Style.FILL);
                ink.setColor(withAlpha(tint, 0.05f * open * shown));
                canvas.drawCircle(cx, cy, getWidth() / 2f - dp, ink);
                ink.setStyle(Paint.Style.STROKE);
            }
            ink.setColor(withAlpha(tint, 0.9f * shown));
            canvas.save();
            canvas.translate(cx - size / 2, cy - size / 2);
            float scale = size / 100f;
            canvas.scale(scale, scale);
            ink.setStrokeWidth(5);
            drawNote(canvas, 8, 20);
            drawNote(canvas, 65, 55);
            canvas.save();
            canvas.translate(8, 0);
            canvas.drawCircle(60, 30, 14, ink);
            canvas.drawLine(50, 20, 70, 40, ink);
            canvas.drawPath(handle, ink);
            canvas.drawPath(switchPath, ink);
            canvas.drawLine(42, 58, 42, 92, ink);
            canvas.drawPath(cord, ink);
            canvas.restore();
            canvas.restore();
            drawState(canvas, cx + size * 0.34f, cy - size * 0.38f);
        }

        private void drawNote(Canvas canvas, float x, float y) {
            canvas.save();
            canvas.translate(x, y);
            canvas.drawCircle(12, 22, 5, ink);
            canvas.drawPath(noteStem, ink);
            canvas.restore();
        }

        /** A dot beside the microphone while Karaoke is on: solid once the model is ready. */
        private void drawState(Canvas canvas, float x, float y) {
            if (!KaraokeLyrics.modelOn() || open > 0.5f) return;
            float radius = 3.5f * dp;
            boolean ready = KaraokeLyrics.ready();
            ink.setStrokeWidth(1.6f * dp);
            ink.setStyle(ready ? Paint.Style.FILL : Paint.Style.STROKE);
            ink.setColor(withAlpha(tint, 0.9f * shown));
            canvas.drawCircle(x, y, radius, ink);
            ink.setStyle(Paint.Style.STROKE);
        }
    }

    // ── The Off button ───────────────────────────────────────────────────────

    private final class Off extends View {
        private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();

        Off(Context context) {
            super(context);
            setTag("karaoke-off");
            setClickable(true);
            setFocusable(true);
            pill.setTextAlign(Paint.Align.CENTER);
            pill.setFakeBoldText(true);
            setOnClickListener(
                    v -> {
                        KaraokeLyrics.deactivate();
                        close(true);
                    });
            setOnFocusChangeListener((v, focused) -> touched());
            TypedValue ripple = new TypedValue();
            if (context.getTheme()
                    .resolveAttribute(
                            android.R.attr.selectableItemBackgroundBorderless, ripple, true))
                setBackgroundResource(ripple.resourceId);
        }

        void refresh() {
            setContentDescription(Strings.t("Turn Karaoke off"));
            setEnabled(true);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(Math.round(64 * dp), Math.round(48 * dp));
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Button");
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (shown <= 0.001f) return;
            float w = getWidth(), h = getHeight();
            float ph = Math.min(h, 30 * dp), pw = Math.min(w, 52 * dp);
            bounds.set((w - pw) / 2, (h - ph) / 2, (w + pw) / 2, (h + ph) / 2);
            boolean on = KaraokeLyrics.modelOn();
            pill.setStyle(Paint.Style.FILL);
            pill.setColor(withAlpha(tint, (on ? 0.14f : 0.05f) * shown));
            canvas.drawRoundRect(bounds, ph / 2, ph / 2, pill);
            pill.setStyle(Paint.Style.STROKE);
            pill.setStrokeWidth(dp);
            pill.setColor(withAlpha(tint, 0.25f * shown));
            canvas.drawRoundRect(bounds, ph / 2, ph / 2, pill);
            pill.setStyle(Paint.Style.FILL);
            pill.setColor(withAlpha(tint, (on ? 0.95f : 0.6f) * shown));
            String label = Strings.t("Off");
            float size = 13 * sp;
            pill.setTextSize(size);
            float width = pill.measureText(label);
            if (width > pw - 8 * dp)
                pill.setTextSize(Math.max(8 * sp, size * (pw - 8 * dp) / width));
            Paint.FontMetrics metrics = pill.getFontMetrics();
            canvas.drawText(
                    label,
                    bounds.centerX(),
                    bounds.centerY() - (metrics.ascent + metrics.descent) / 2,
                    pill);
        }
    }

    // ── The slider ───────────────────────────────────────────────────────────

    private final class Track extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF slot = new RectF(), fill = new RectF();
        private final Path clip = new Path(), noteStem = new Path();

        Track(Context context) {
            super(context);
            setTag("karaoke-slider");
            setClickable(true);
            setFocusable(true);
            setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
            note(noteStem);
            setOnFocusChangeListener((v, focused) -> touched());
        }

        void refresh() {
            String label = Strings.t("Karaoke vocal removal");
            String state = KaraokeLyrics.spoken();
            if (Build.VERSION.SDK_INT >= 30) {
                setContentDescription(label);
                setStateDescription(state);
            } else setContentDescription(label + ", " + state);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(base(), getSuggestedMinimumHeight());
        }

        private void slot() {
            float width = TRACK_DP * dp;
            slot.set((getWidth() - width) / 2, 0, (getWidth() + width) / 2, getHeight());
        }

        private void level(float y) {
            slot();
            float top = slot.top, bottom = slot.bottom;
            if (bottom <= top) return;
            int value = Math.round(clamp((bottom - y) / (bottom - top), 0, 1) * 100);
            change(value);
        }

        /** Moves the shared level; a user's movement of the slider also turns Karaoke on. */
        private void change(int value) {
            if (value == KaraokeLyrics.level() && KaraokeLyrics.modelOn()) return;
            if (!KaraokeLyrics.modelOn()) KaraokeLyrics.activate();
            KaraokeLyrics.setLevel(value);
            refresh();
            mic.refresh();
            invalidateAll();
            if (value > 0 && open >= 0.999f) startGlitter();
            else if (value == 0) stopGlitter();
            touched();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // The lyrics scroll must not take over while the slider is dragged.
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    dragging = true;
                    cancelCloseLater();
                    level(event.getY());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) level(event.getY());
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging) level(event.getY());
                    performClick();
                // fall through
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    scheduleClose();
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            int level = KaraokeLyrics.level();
            int target;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_PLUS:
                    target = level + 1;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_MINUS:
                    target = level - 1;
                    break;
                case KeyEvent.KEYCODE_PAGE_UP:
                    target = level + 10;
                    break;
                case KeyEvent.KEYCODE_PAGE_DOWN:
                    target = level - 10;
                    break;
                case KeyEvent.KEYCODE_MOVE_HOME:
                    target = 0;
                    break;
                case KeyEvent.KEYCODE_MOVE_END:
                    target = 100;
                    break;
                default:
                    return super.onKeyDown(keyCode, event);
            }
            change((int) clamp(target, 0, 100));
            touched();
            return true;
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.SeekBar");
            info.setRangeInfo(
                    AccessibilityNodeInfo.RangeInfo.obtain(
                            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
                            0,
                            100,
                            KaraokeLyrics.level()));
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            info.addAction(
                    new AccessibilityNodeInfo.AccessibilityAction(
                            ACTION_RESET, Strings.f("Reset to {0}%", KaraokeLyrics.DEFAULT_LEVEL)));
            info.addAction(
                    new AccessibilityNodeInfo.AccessibilityAction(
                            ACTION_FINER, Strings.t("Decrease by 1%")));
            info.addAction(
                    new AccessibilityNodeInfo.AccessibilityAction(
                            ACTION_COARSER, Strings.t("Increase by 1%")));
        }

        @Override
        public boolean performAccessibilityAction(int action, Bundle arguments) {
            int level = KaraokeLyrics.level();
            touched();
            if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()) {
                if (arguments == null
                        || !arguments.containsKey(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) return false;
                float value =
                        arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);
                change(Math.round(clamp(value, 0, 100)));
                return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                change(Math.min(100, level + 5));
                return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                change(Math.max(0, level - 5));
                return true;
            }
            if (action == ACTION_COARSER) {
                change(Math.min(100, level + 1));
                return true;
            }
            if (action == ACTION_FINER) {
                change(Math.max(0, level - 1));
                return true;
            }
            if (action == ACTION_RESET) {
                change(KaraokeLyrics.DEFAULT_LEVEL);
                return true;
            }
            return super.performAccessibilityAction(action, arguments);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (shown <= 0.001f) return;
            float radius = TRACK_DP * dp / 2;
            // The touch area is the full capsule width; the slot is what is drawn.
            slot();
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            paint.setColor(withAlpha(Color.BLACK, 0.25f * shown));
            canvas.drawRoundRect(slot, radius, radius, paint);
            paint.setShader(
                    new LinearGradient(
                            0,
                            slot.top,
                            0,
                            slot.top + 14 * dp,
                            Color.argb(Math.round(110 * shown), 0, 0, 0),
                            Color.TRANSPARENT,
                            Shader.TileMode.CLAMP));
            canvas.drawRoundRect(slot, radius, radius, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp);
            paint.setColor(withAlpha(tint, 0.06f * shown));
            canvas.drawRoundRect(slot, radius, radius, paint);

            float fraction = KaraokeLyrics.level() / 100f;
            float filled = slot.height() * fraction;
            if (filled <= 0) return;
            fill.set(slot.left, slot.bottom - filled, slot.right, slot.bottom);
            float corner = Math.min(radius, filled / 2);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(
                    new LinearGradient(
                            0,
                            fill.top,
                            0,
                            fill.bottom,
                            withAlpha(tint, 0.15f * shown),
                            withAlpha(tint, 0.35f * shown),
                            Shader.TileMode.CLAMP));
            canvas.drawRoundRect(fill, corner, corner, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * dp);
            paint.setColor(withAlpha(tint, 0.5f * shown));
            RectF glow = new RectF(fill);
            glow.inset(dp, dp);
            if (glow.height() > 2 * dp) canvas.drawRoundRect(glow, corner, corner, paint);
            paint.setStrokeWidth(dp);
            paint.setColor(withAlpha(tint, 0.8f * shown));
            canvas.drawLine(
                    fill.left + corner * 0.6f,
                    fill.top + dp,
                    fill.right - corner * 0.6f,
                    fill.top + dp,
                    paint);
            if (shimmer != null) drawGlitter(canvas, corner);
        }

        /** Light rising through the fill, and notes drifting up with it. */
        private void drawGlitter(Canvas canvas, float corner) {
            canvas.save();
            clip.reset();
            clip.addRoundRect(fill, corner, corner, Path.Direction.CW);
            canvas.clipPath(clip);
            float band = 60 * dp;
            float travel = (1 - glitter) * band * 2;
            paint.setStyle(Paint.Style.FILL);
            for (float y = fill.bottom + band - travel; y > fill.top - band; y -= band * 2) {
                paint.setShader(
                        new LinearGradient(
                                0,
                                y - band,
                                0,
                                y + band,
                                new int[] {
                                    Color.TRANSPARENT,
                                    withAlpha(tint, 0.10f * shown),
                                    withAlpha(tint, 0.55f * shown),
                                    withAlpha(tint, 0.10f * shown),
                                    Color.TRANSPARENT
                                },
                                new float[] {0, 0.3f, 0.5f, 0.7f, 1},
                                Shader.TileMode.CLAMP));
                canvas.drawRect(fill.left, y - band, fill.right, y + band, paint);
            }
            paint.setShader(null);
            float span = 120 * dp;
            float[] xs = {0.28f, 0.62f, 0.45f};
            float[] phases = {0f, 0.37f, 0.71f};
            float[] alphas = {0.8f, 0.5f, 0.9f};
            for (int i = 0; i < xs.length; i++) {
                float p = (glitter * 2 + phases[i]) % 1f;
                float y = fill.bottom + 8 * dp - p * (fill.height() + span * 0.25f);
                float x = fill.left + fill.width() * xs[i];
                float size = (10 + 3 * i) * dp;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(5);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setColor(withAlpha(tint, alphas[i] * shown));
                canvas.save();
                canvas.translate(x - size / 2, y - size / 2);
                float scale = size / 50f;
                canvas.scale(scale, scale);
                canvas.drawCircle(12, 22, 5, paint);
                canvas.drawPath(noteStem, paint);
                canvas.restore();
            }
            canvas.restore();
        }
    }
}
