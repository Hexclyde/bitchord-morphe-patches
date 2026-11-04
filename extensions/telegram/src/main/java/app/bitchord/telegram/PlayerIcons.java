package app.bitchord.telegram;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.view.View;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The player patches call into this from BitChord's layouts: the Audio/Video pair joins the drag
 * strip, and the Automix Editor icon sits as an unscaled sibling of the title row (portrait) or of
 * the artwork square (landscape).
 */
public final class PlayerIcons {
    private PlayerIcons() {}

    static final int PAIR_DP = 48, EDITOR_DP = 48;

    // The handle sits 6 dp down and is 5 dp tall; the pair starts under it.
    static final int HANDLE_DP = 6 + 5;

    // The icon's height less the native gap above the title and the stats' own bottom padding.
    static final int PORTRAIT_LIFT_DP = EDITOR_DP - 20 - 8, LANDSCAPE_LIFT_DP = EDITOR_DP - 8;
    private static final float CAPTION_LINE_SP = 16, CAPTION_PAD_DP = 1, MIN_TARGET_DP = 48;
    private static final int PAIR_KEY = 0x50494331, ABOVE_KEY = 0x50494333, SQUARE_KEY = 0x50494334;
    private static final Visible pair = new Visible(), editor = new Visible();
    private static Compose compose;
    private static boolean composeFailed, squareWarned;

    static void forTest() {
        compose = null;
        composeFailed = false;
        squareWarned = false;
        pair.set(false);
        editor.set(false);
    }

    // Snapshot state, so reading it while composing recomposes the layout when it changes.
    static final class Visible {
        private Object state;
        private Method read, write;
        private volatile boolean plain;

        private synchronized void bind() {
            if (state != null || read != null) return;
            try {
                Class<?> type =
                        Class.forName("androidx.compose.runtime.ParcelableSnapshotMutableIntState");
                state = type.getConstructor(int.class).newInstance(0);
                read = type.getMethod("getIntValue");
                write = type.getMethod("setIntValue", int.class);
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                state = null;
                read = write = null;
            }
        }

        boolean get() {
            bind();
            if (state == null) return plain;
            try {
                return ((Integer) read.invoke(state)) != 0;
            } catch (ReflectiveOperationException unavailable) {
                return plain;
            }
        }

        void set(boolean on) {
            plain = on;
            bind();
            if (state == null) return;
            try {
                if (on != (((Integer) read.invoke(state)) != 0)) write.invoke(state, on ? 1 : 0);
            } catch (ReflectiveOperationException ignored) {
                // The layouts then keep the size they had; the icons still draw and answer.
            }
        }
    }

    static void pairShown(boolean on) {
        pair.set(on);
    }

    static void editorShown(boolean on) {
        editor.set(on);
    }

    static boolean pairShown() {
        return pair.get();
    }

    static boolean editorShown() {
        return editor.get();
    }

    public static float strip(float nativeDp) {
        return strip(nativeDp, Resources.getSystem().getConfiguration().fontScale);
    }

    static float strip(float nativeDp, float fontScale) {
        if (!pair.get()) return nativeDp;
        // The caption's touch target is at least 48 dp, centred on its line; the pair ends above
        // it.
        float line = CAPTION_LINE_SP * fontScale + CAPTION_PAD_DP;
        float target = Math.max(MIN_TARGET_DP, line);
        return Math.max(nativeDp, (float) Math.ceil(HANDLE_DP + PAIR_DP + (target + line) / 2));
    }

    // The caption's target is at least 48 dp, centred on its 16 sp line and 2 dp padding.
    static float captionGap(float fontScale) {
        float line = CAPTION_LINE_SP * fontScale + 4;
        return (float) Math.ceil((Math.max(MIN_TARGET_DP, line) - line) / 2);
    }

    public static void modes(Object composer) {
        place(composer, PAIR_KEY, null);
    }

    public static void editorAbove(Object title, Object composer) {
        place(composer, ABOVE_KEY, c -> editor.get() ? c.above(title) : c.hidden());
    }

    public static void editorOnSquare(Object square, Object composer) {
        place(
                composer,
                SQUARE_KEY,
                c -> {
                    if (!editor.get()) return c.hidden();
                    Object placed = c.onSquare(square);
                    if (placed == null && !squareWarned) {
                        squareWarned = true;
                        HostLog.d("Player icons: the landscape artwork is not a plain square");
                    }
                    return placed;
                });
    }

    public static Object liftPortrait(Object modifier) {
        return lift(modifier, PORTRAIT_LIFT_DP);
    }

    public static Object liftLandscape(Object modifier) {
        return lift(modifier, LANDSCAPE_LIFT_DP);
    }

    private static Object lift(Object modifier, int dp) {
        if (composeFailed || !editor.get()) return modifier;
        try {
            Compose c = compose();
            return c == null ? modifier : c.padding.invoke(null, modifier, 0f, 0f, 0f, dp * 1f);
        } catch (InvocationTargetException thrown) {
            if (thrown.getCause() instanceof RuntimeException)
                HostLog.d("Player icon spacing skipped once: " + thrown.getCause());
            else failed(thrown);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            failed(unavailable);
        } catch (RuntimeException skipped) {
            HostLog.d("Player icon spacing skipped once: " + skipped);
        }
        return modifier;
    }

    private interface Where {
        Object modifier(Compose c) throws ReflectiveOperationException;
    }

    private static void place(Object composer, int key, Where where) {
        if (composeFailed) return;
        try {
            Compose c = compose();
            if (c == null) return;
            // Its own group, so the icon coming and going never shifts what the host composes.
            c.startGroup.invoke(composer, key);
            try {
                Ink ink = c.ink(composer);
                Object modifier =
                        where == null ? c.modifier(c.topCenter, HANDLE_DP, 0) : where.modifier(c);
                if (modifier == null) return;
                modifier = c.blended(modifier, ink);
                Function1<Context, View> factory =
                        where == null ? VideoSwitch.HEADER_FACTORY : EDITOR_FACTORY;
                Function1<View, Unit> update =
                        where == null
                                ? VideoSwitch.headerUpdate(ink)
                                : view -> {
                                    EditorEntry entry = (EditorEntry) view;
                                    entry.ink(ink);
                                    entry.refresh();
                                    return Unit.INSTANCE;
                                };
                c.androidView.invoke(null, factory, modifier, update, composer, 0, 0);
            } finally {
                c.endGroup.invoke(composer);
            }
        } catch (InvocationTargetException thrown) {
            if (thrown.getCause() instanceof RuntimeException)
                HostLog.d("Player icon skipped once: " + thrown.getCause());
            else failed(thrown);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            failed(unavailable);
        } catch (RuntimeException skipped) {
            HostLog.d("Player icon skipped once: " + skipped);
        }
    }

    private static void failed(Throwable cause) {
        // A different Compose ABI will not start matching; stop trying on every recomposition.
        composeFailed = true;
        HostLog.d("Player icons unavailable: " + cause);
    }

    static Ink ink(Object composer) {
        try {
            Compose c = compose();
            return c == null ? Ink.WHITE : c.ink(composer);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return Ink.WHITE;
        }
    }

    static Object blended(Object modifier, Ink ink) {
        try {
            Compose c = compose();
            return c == null ? modifier : c.blended(modifier, ink);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
            return modifier;
        }
    }

    private static final Function1<Context, View> EDITOR_FACTORY = EditorEntry::new;

    private static synchronized Compose compose() throws ReflectiveOperationException {
        if (compose == null) compose = new Compose(table());
        return compose;
    }

    // The patch replaces this body with the host symbols it resolved, one key=reference per line.
    static String symbols() {
        return "";
    }

    private static volatile String testSymbols;

    private static String table() {
        String forTest = testSymbols;
        return forTest != null ? forTest : symbols();
    }

    static void forTest(String symbols) {
        testSymbols = symbols;
        forTest();
    }

    // The player's own numbers: tint or white at 0.75 (0.60 under chrome), lit at 1, tile 0.62
    // (0.47).
    static final class Ink {
        static final Ink WHITE = new Ink(0xffffffff, false);

        final int rgb;
        final boolean chrome;

        Ink(int rgb, boolean chrome) {
            this.rgb = rgb;
            this.chrome = chrome;
        }

        int glyph(boolean lit) {
            return alpha(rgb, lit ? 1f : chrome ? 0.60f : 0.75f);
        }

        int tile() {
            return alpha(rgb, chrome ? 0.47f : 0.62f);
        }

        static int alpha(int color, float alpha) {
            return (Math.round(alpha * 255) << 24) | (color & 0xffffff);
        }
    }

    // SRC_OUT keeps the tile only where the glyph, drawn first in the same layer, is not.
    static void litTile(Canvas canvas, float cx, float cy, float density, int color, Paint paint) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OUT));
        float half = 35 * density / 2;
        canvas.drawRoundRect(
                new RectF(cx - half, cy - half, cx + half, cy + half),
                7 * density,
                7 * density,
                paint);
        paint.setXfermode(null);
    }

    static final class EditorEntry extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Runnable tick = this::refresh;
        private final float density;
        private Ink ink = Ink.WHITE;

        EditorEntry(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            setTag("player-automix-editor");
            setContentDescription(Strings.t("Automix Editor"));
            setTooltipText(Strings.t("Automix Editor"));
            setClickable(true);
            setFocusable(true);
            android.util.TypedValue ripple = new android.util.TypedValue();
            if (context.getTheme()
                    .resolveAttribute(
                            android.R.attr.selectableItemBackgroundBorderless, ripple, true))
                setBackgroundResource(ripple.resourceId);
            setOnClickListener(
                    view ->
                            context.startActivity(
                                    new Intent(context, TransitionEditorActivity.class)
                                            .addFlags(
                                                    context instanceof android.app.Activity
                                                            ? 0
                                                            : Intent.FLAG_ACTIVITY_NEW_TASK)));
            refresh();
        }

        void ink(Ink next) {
            ink = next;
            invalidate();
        }

        void refresh() {
            removeCallbacks(tick);
            boolean show = SourceAutomix.applied() && VideoSwitch.mainSurface();
            setVisibility(show ? VISIBLE : GONE);
            editorShown(show);
            if (show) TransitionWaveCache.prefetch();
            if (isAttachedToWindow() && getWindowVisibility() == VISIBLE) postDelayed(tick, 300);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            post(tick);
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(tick);
            editorShown(false);
            super.onDetachedFromWindow();
        }

        @Override
        protected void onMeasure(int width, int height) {
            // Hidden, it reserves nothing; the host sizes its slot from what this reports.
            int side = getVisibility() == GONE ? 0 : Math.round(EDITOR_DP * density);
            setMeasuredDimension(resolveSize(side, width), resolveSize(side, height));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            paint.setColor(ink.glyph(false));
            paint.setStrokeWidth(1.8f * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            for (int i = -3; i <= 3; i++) {
                float extent = (i % 2 == 0 ? 7 : 4) * density;
                canvas.drawLine(
                        cx + i * 3 * density,
                        cy - extent,
                        cx + i * 3 * density,
                        cy + extent,
                        paint);
            }
        }
    }

    static final class Compose {
        final Method androidView, padding, then, startGroup, endGroup, consume, blend;
        final Method offset, wrapWidth, size;
        final Object companion, topCenter, bottomCenter, centerH;
        final Constructor<?> childData, zIndex;
        final Field tintField, chromeField;
        final Field[] square = new Field[4];
        private boolean inkFailed;

        Compose(String table) throws ReflectiveOperationException {
            Map<String, String> refs = new HashMap<>();
            for (String line : table.split("\n"))
                if (line.indexOf('=') > 0)
                    refs.put(
                            line.substring(0, line.indexOf('=')),
                            line.substring(line.indexOf('=') + 1));
            if (!refs.containsKey("androidView")) throw new NoSuchMethodException("symbols");
            androidView = method(need(refs, "androidView"));
            padding = method(need(refs, "padding"));
            Class<?> modifierType = padding.getParameterTypes()[0];
            then = modifierType.getMethod("then", modifierType);
            companion = field(need(refs, "companion")).get(null);
            topCenter = field(need(refs, "topCenter")).get(null);
            bottomCenter = field(need(refs, "bottomCenter")).get(null);
            childData = constructor(need(refs, "childData"));
            zIndex = constructor(need(refs, "zIndex"));
            offset = method(need(refs, "offset"));
            wrapWidth = method(need(refs, "wrapWidth"));
            size = method(need(refs, "size"));
            centerH = field(need(refs, "centerH")).get(null);
            String[] sides = {"minWidth", "minHeight", "maxWidth", "maxHeight"};
            for (int i = 0; i < 4; i++) square[i] = field(need(refs, sides[i]));
            startGroup = method(need(refs, "startGroup"));
            endGroup = method(need(refs, "endGroup"));
            boolean tint = refs.containsKey("tint"), chrome = refs.containsKey("chrome");
            if (tint && chrome) throw new NoSuchFieldException("tint and chrome");
            tintField = tint ? field(refs.get("tint")) : null;
            chromeField = chrome ? field(refs.get("chrome")) : null;
            consume = tint || chrome ? method(need(refs, "consume")) : null;
            // The blend is the host's own helper for its chrome local; both are bound or neither.
            blend = chrome ? method(need(refs, "blend")) : null;
        }

        private static String need(Map<String, String> refs, String key)
                throws NoSuchMethodException {
            String ref = refs.get(key);
            if (ref == null) throw new NoSuchMethodException(key);
            return ref;
        }

        private static Class<?> type(String descriptor) throws ClassNotFoundException {
            switch (descriptor.charAt(0)) {
                case 'Z':
                    return boolean.class;
                case 'I':
                    return int.class;
                case 'J':
                    return long.class;
                case 'F':
                    return float.class;
                case 'V':
                    return void.class;
                case 'L':
                    return Class.forName(
                            descriptor.substring(1, descriptor.length() - 1).replace('/', '.'));
                default:
                    throw new ClassNotFoundException(descriptor);
            }
        }

        private static List<Class<?>> parameters(String list) throws ClassNotFoundException {
            List<Class<?>> found = new ArrayList<>();
            for (int at = 0; at < list.length(); ) {
                int end = list.charAt(at) == 'L' ? list.indexOf(';', at) : at;
                found.add(type(list.substring(at, end + 1)));
                at = end + 1;
            }
            return found;
        }

        private static Method method(String ref) throws ReflectiveOperationException {
            String owner = ref.substring(0, ref.indexOf(';') + 1);
            String name = ref.substring(ref.indexOf("->") + 2, ref.indexOf('('));
            List<Class<?>> params =
                    parameters(ref.substring(ref.indexOf('(') + 1, ref.indexOf(')')));
            Method found = type(owner).getDeclaredMethod(name, params.toArray(new Class<?>[0]));
            found.setAccessible(true);
            return found;
        }

        private static Constructor<?> constructor(String ref) throws ReflectiveOperationException {
            String owner = ref.substring(0, ref.indexOf(';') + 1);
            List<Class<?>> params =
                    parameters(ref.substring(ref.indexOf('(') + 1, ref.indexOf(')')));
            Constructor<?> found =
                    type(owner).getDeclaredConstructor(params.toArray(new Class<?>[0]));
            found.setAccessible(true);
            return found;
        }

        private static Field field(String ref) throws ReflectiveOperationException {
            String owner = ref.substring(0, ref.indexOf(';') + 1);
            Field found =
                    type(owner)
                            .getDeclaredField(
                                    ref.substring(ref.indexOf("->") + 2, ref.indexOf(':')));
            found.setAccessible(true);
            return found;
        }

        Object modifier(Object alignment, float top, float bottom)
                throws ReflectiveOperationException {
            Object placed = then.invoke(companion, childData.newInstance(alignment, false));
            return top == 0 && bottom == 0
                    ? placed
                    : padding.invoke(null, placed, 0f, top, 0f, bottom);
        }

        Object hidden() throws ReflectiveOperationException {
            return size.invoke(null, companion, 0f);
        }

        Object above(Object title) throws ReflectiveOperationException {
            Object raised = offset.invoke(null, title, 0f, -EDITOR_DP * 1f);
            return target(raised);
        }

        Object onSquare(Object square) throws ReflectiveOperationException {
            if (square == null || !square.getClass().equals(this.square[0].getDeclaringClass()))
                return null;
            float[] side = new float[4];
            for (int i = 0; i < 4; i++) side[i] = this.square[i].getFloat(square);
            for (float value : side) if (value != side[0]) return null;
            if (side[0] <= EDITOR_DP) return null;
            // Emitted ahead of the artwork, so it is stacked above it to draw and to take touches.
            Object lowered = padding.invoke(null, square, 0f, side[0] - EDITOR_DP, 0f, 0f);
            return then.invoke(target(lowered), zIndex.newInstance(1f));
        }

        // The node may be wide, but the view inside it, and so every touch it takes, is 48 dp.
        private Object target(Object base) throws ReflectiveOperationException {
            Object centred = wrapWidth.invoke(null, base, centerH, false);
            return size.invoke(null, centred, EDITOR_DP * 1f);
        }

        Object blended(Object modifier, Ink ink) throws ReflectiveOperationException {
            return blend != null && ink.chrome ? blend.invoke(null, modifier, true) : modifier;
        }

        // An ink that cannot be read costs the colour, never the control.
        Ink ink(Object composer) {
            if (consume == null || inkFailed) return Ink.WHITE;
            try {
                if (tintField != null) {
                    Object color = consume.invoke(composer, tintField.get(null));
                    long packed = color.getClass().getField("value").getLong(color);
                    return (packed & 63L) == 0 ? new Ink((int) (packed >>> 32), false) : Ink.WHITE;
                }
                return new Ink(
                        0xffffffff,
                        Boolean.TRUE.equals(consume.invoke(composer, chromeField.get(null))));
            } catch (ReflectiveOperationException | RuntimeException failed) {
                inkFailed = true;
                HostLog.d("Player icons: the player ink cannot be read: " + failed);
                return Ink.WHITE;
            }
        }
    }
}
