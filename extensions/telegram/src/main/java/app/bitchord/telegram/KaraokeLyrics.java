package app.bitchord.telegram;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Karaoke on the lyrics screen. The Karaoke patch calls {@link #portrait} and {@link #landscape}
 * from inside BitChord's lyrics layouts, where one native capsule is added as the last child of the
 * box that holds the lyrics. It reads and writes the same values as the Karaoke settings page.
 */
public final class KaraokeLyrics {
    private KaraokeLyrics() {}

    /** What the settings page starts the removal level at; Reset goes back to it. */
    static final int DEFAULT_LEVEL = SoundExtras.SING_DEFAULT;

    /**
     * The host's translate button is a bare 34 dp disc. The capsule paints a 34 dp disc standing on
     * the bottom of its 48 dp target, directly above the button and 48 dp from its centre, leaving
     * a 14 dp gap. The 48 dp target is 7 dp wider than the button each side, so portrait shifts it
     * 7 dp toward the end to share the button's axis.
     */
    static final int CENTRE_SEPARATION_DP = 48, TRANSLATE_DP = 34;

    static final int PORTRAIT_END_DP = 0, PORTRAIT_BOTTOM_DP = CENTRE_SEPARATION_DP;

    static final int PORTRAIT_SHIFT_DP = (KaraokeCapsule.BASE_DP - TRANSLATE_DP) / 2;

    private static final int GROUP_KEY = 0x4B415241;

    private static volatile WeakReference<Object> haze = new WeakReference<>(null);
    private static Compose compose;
    private static boolean composeFailed;

    static void forTest() {
        haze = new WeakReference<>(null);
        compose = null;
        composeFailed = false;
    }

    // ── What the Karaoke settings already hold ───────────────────────────────

    static boolean applied() {
        return SoundExtras.singApplied();
    }

    /** True while the separation model is the method in use. */
    static boolean modelOn() {
        return SoundExtras.singEnabled && SoundExtras.singMethod == SeparationModel.METHOD;
    }

    /** Karaoke's model is chosen and switched on; the level stays what it was. */
    static void activate() {
        if (SoundExtras.singMethod != SeparationModel.METHOD)
            SoundExtras.setSingMethod(SeparationModel.METHOD);
        if (!SoundExtras.singEnabled) SoundExtras.setSingEnabled(true);
    }

    static int level() {
        return SoundExtras.singLevel;
    }

    static void setLevel(int level) {
        int clamped = Math.max(0, Math.min(100, level));
        if (clamped != SoundExtras.singLevel) SoundExtras.setSingLevel(clamped);
    }

    static boolean ready() {
        return modelOn() && "Model ready".equals(SeparationModel.status());
    }

    /** The big number on the open capsule. */
    static String percent() {
        return Strings.f("{0}%", level());
    }

    /** A short line under the number when the model is not simply doing its job; none when off. */
    static String note() {
        if (!modelOn()) return "";
        String status = SeparationModel.status();
        if ("Model loading".equals(status)) return Strings.t("Loading");
        if (!"Model ready".equals(status)) return Strings.t("Filter");
        return level() == 0 ? Strings.t("Original vocals") : "";
    }

    /** What a screen reader says: the model's own status, then how much is removed. */
    static String spoken() {
        if (!modelOn()) return Strings.t("Off");
        String amount =
                level() == 0 ? Strings.t("Original vocals") : Strings.f("{0}% quieter", level());
        return Strings.t(SeparationModel.status()) + ", " + amount;
    }

    // ── Hooks ────────────────────────────────────────────────────────────────

    /** Called as BitChord's one frosted-glass state is created. */
    public static void haze(Object state) {
        haze = new WeakReference<>(state);
    }

    /**
     * Portrait lyrics: a child of the box holding the lyrics, above the translation button, while
     * the lyrics are on screen. It stays whether or not the host's own lyric controls are shown.
     */
    public static void portrait(Object composer, boolean lyricsVisible) {
        embed(composer, false, lyricsVisible);
    }

    /** The same, held back while the host is picking lyric lines to share. */
    public static void portrait(Object composer, boolean lyricsVisible, Object picker) {
        embed(composer, false, lyricsVisible && !picking(picker));
    }

    private static boolean picking(Object picker) {
        try {
            Compose c = compose();
            return c != null && c.picking != null && (Boolean) c.picking.invoke(picker);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Landscape lyrics: a child of the translation slot below the lyrics. The slot is 34 dp and
     * centred in the row whatever the status line makes of its height, so the capsule is moved from
     * there.
     */
    public static void landscape(Object composer) {
        embed(composer, true, true);
    }

    /**
     * The translation slot's offset for a capsule this tall, packed as the host's IntOffset. The
     * slot centres the capsule on the button, so only the rise is left: the target's bottom ends 48
     * - 17 dp above the slot's centre.
     */
    static long landscapeOffset(float density, int measuredHeightPx) {
        int y =
                -Math.round((CENTRE_SEPARATION_DP - TRANSLATE_DP / 2f) * density)
                        - measuredHeightPx / 2;
        return y & 0xffffffffL;
    }

    /** The portrait target's shift toward the end, packed as the host's IntOffset. */
    static long portraitOffset(float density) {
        return (long) Math.round(PORTRAIT_SHIFT_DP * density) << 32;
    }

    private static void embed(Object composer, boolean landscape, boolean show) {
        if (!applied() || composeFailed) return;
        try {
            Compose c = compose();
            if (c == null || landscape && !c.hasSlot()) return;
            // Its own group, so the capsule coming and going never shifts what the host composes.
            c.startGroup.invoke(composer, GROUP_KEY);
            try {
                if (!show) return;
                final int tint = c.tint(composer);
                final Object frost = c.frost(haze.get(), tint, c.visible());
                final boolean blurred = frost != null;
                KaraokeCapsule[] control = new KaraokeCapsule[1];
                Object modifier =
                        landscape
                                ? c.slotModifier(frost, control)
                                : c.modifier(PORTRAIT_END_DP, PORTRAIT_BOTTOM_DP, frost, control);
                Function1<View, Unit> update =
                        view -> {
                            KaraokeCapsule capsule = (KaraokeCapsule) view;
                            control[0] = capsule;
                            capsule.tint(tint);
                            capsule.blurred(blurred);
                            return Unit.INSTANCE;
                        };
                c.androidView.invoke(null, FACTORY, modifier, update, composer, 0, 0);
            } finally {
                c.endGroup.invoke(composer);
            }
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            // A different Compose ABI will not start matching; stop trying on every recomposition.
            composeFailed = true;
            HostLog.d("Karaoke control unavailable on the lyrics: " + unavailable);
        } catch (RuntimeException failed) {
            HostLog.d("Karaoke control skipped once: " + failed);
        }
    }

    private static final Function1<Context, View> FACTORY =
            context -> {
                KaraokeCapsule capsule = new KaraokeCapsule(context);
                capsule.listener(KaraokeLyrics::visible);
                // A new capsule starts with its microphone showing.
                new Handler(Looper.getMainLooper()).post(() -> visible(true));
                return capsule;
            };

    /** Drops the host blur when the microphone dims to its resting state. */
    private static void visible(boolean visible) {
        Compose c = compose;
        if (c != null) c.visible(visible);
    }

    private static synchronized Compose compose() throws ReflectiveOperationException {
        if (compose == null) compose = new Compose(table());
        return compose;
    }

    /**
     * The host symbols the capsule binds to, one {@code key=reference} per line. The patch replaces
     * this body with what it resolved in the host's own code; nothing here names a class the host's
     * build may have moved.
     */
    static String symbols() {
        return "";
    }

    private static volatile String testSymbols;

    private static String table() {
        String forTest = testSymbols;
        return forTest != null ? forTest : symbols();
    }

    /** Installs another symbol table, or none, and forgets what was bound. */
    static void forTest(String symbols) {
        testSymbols = symbols;
        forTest();
    }

    /**
     * The parts of Compose and BitChord's UI that the capsule needs, bound from {@link #symbols}.
     */
    static final class Compose {
        final Method androidView, then, padding, hazeEffect, frostStyle;
        final Method drawContent, drawContext, contextCanvas, nativeCanvas;
        final Constructor<?> drawElement;
        final Method startGroup, endGroup, consumeLocal, picking, requiredSize, offsetBy;
        final Constructor<?> intOffset;
        final Class<?> modifierType;
        final Object companion, alignment;
        final Constructor<?> childData;
        final Field tintField;
        private final Object visibleState;
        private final Method readVisible, writeVisible;

        Compose(String table) throws ReflectiveOperationException {
            Map<String, String> refs = new HashMap<>();
            for (String line : table.split("\n"))
                if (line.indexOf('=') > 0)
                    refs.put(
                            line.substring(0, line.indexOf('=')),
                            line.substring(line.indexOf('=') + 1));
            if (!refs.containsKey("androidView")) throw new NoSuchMethodException("symbols");
            androidView = method(refs.get("androidView"));
            padding = method(refs.get("padding"));
            modifierType = padding.getParameterTypes()[0];
            then = modifierType.getMethod("then", modifierType);
            companion = field(refs.get("companion")).get(null);
            alignment = field(refs.get("alignment")).get(null);
            childData = constructor(refs.get("childData"));
            picking = refs.containsKey("picking") ? method(refs.get("picking")) : null;
            startGroup = method(refs.get("startGroup"));
            endGroup = method(refs.get("endGroup"));
            if (refs.containsKey("requiredSize")) {
                requiredSize = method(refs.get("requiredSize"));
                offsetBy = method(refs.get("offsetBy"));
                intOffset = constructor(refs.get("intOffset"));
            } else {
                requiredSize = offsetBy = null;
                intOffset = null;
            }
            tintField = refs.containsKey("tint") ? field(refs.get("tint")) : null;
            consumeLocal = refs.containsKey("consume") ? method(refs.get("consume")) : null;
            if (refs.containsKey("hazeEffect")) {
                hazeEffect = method(refs.get("hazeEffect"));
                frostStyle = method(refs.get("frost"));
                drawElement = constructor(refs.get("drawElement"));
                drawContent = method(refs.get("drawContent"));
                drawContext = method(refs.get("drawContext"));
                contextCanvas = method(refs.get("contextCanvas"));
                nativeCanvas = method(refs.get("nativeCanvas"));
            } else {
                hazeEffect =
                        frostStyle =
                                drawContent = drawContext = contextCanvas = nativeCanvas = null;
                drawElement = null;
            }
            Class<?> intState =
                    Class.forName("androidx.compose.runtime.ParcelableSnapshotMutableIntState");
            visibleState = intState.getConstructor(int.class).newInstance(1);
            readVisible = intState.getMethod("getIntValue");
            writeVisible = intState.getMethod("setIntValue", int.class);
        }

        // ── Reading a descriptor ──

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

        /** Read during composition, so a change recomposes the lyrics' box and drops the blur. */
        boolean visible() throws ReflectiveOperationException {
            return ((Integer) readVisible.invoke(visibleState)) != 0;
        }

        void visible(boolean on) {
            try {
                if (on != (((Integer) readVisible.invoke(visibleState)) != 0))
                    writeVisible.invoke(visibleState, on ? 1 : 0);
            } catch (ReflectiveOperationException ignored) {
                // The blur then stays as it was; the capsule still draws and answers.
            }
        }

        /**
         * The player's own tint where this BitChord has one, else the white its lyrics use. A tint
         * that cannot be read costs the colour, never the control.
         */
        int tint(Object composer) {
            if (tintField == null || consumeLocal == null || tintFailed) return 0xffffffff;
            try {
                Object color = consumeLocal.invoke(composer, tintField.get(null));
                long packed = color.getClass().getField("value").getLong(color);
                return (packed & 63L) == 0 ? (int) (packed >>> 32) : 0xffffffff;
            } catch (ReflectiveOperationException | RuntimeException failed) {
                tintFailed = true;
                HostLog.d("Karaoke control: the player tint cannot be read: " + failed);
                return 0xffffffff;
            }
        }

        private boolean tintFailed, drawingFailed;

        /** BitChord's frosted-glass modifier for the capsule, or null without it. */
        Object frost(Object state, int tint, boolean shown) throws ReflectiveOperationException {
            if (state == null || hazeEffect == null || !shown || drawingFailed) return null;
            // The helper keeps the colour it is given as the glass's own background and as its
            // no-blur fallback, so that colour must already be nearly clear; the float is the
            // strength of the tint laid over the blur.
            long packed = (KaraokeCapsule.withAlpha(tint, 0.04f) & 0xffffffffL) << 32;
            Object style = frostStyle.invoke(null, 0.06f, packed);
            Function1<Object, Unit> none = scope -> Unit.INSTANCE;
            return hazeEffect.invoke(null, companion, state, style, none);
        }

        boolean hasSlot() {
            return requiredSize != null;
        }

        /**
         * Inside the 34 dp translation slot: the target is 48 dp wide and as tall as the capsule
         * wants (an unspecified maximum would inherit the slot's 34 dp), centred in the slot, then
         * raised above the button. The offset reads the height measured for this frame.
         */
        Object slotModifier(Object frost, KaraokeCapsule[] control)
                throws ReflectiveOperationException {
            float base = KaraokeCapsule.BASE_DP;
            // Every bound is given, so the mask of arguments left to their defaults is empty.
            Object sized =
                    requiredSize.invoke(
                            null, companion, base, base, base, Float.POSITIVE_INFINITY, 0);
            Object placed =
                    shifted(
                            sized,
                            capsule ->
                                    landscapeOffset(
                                            capsule.getResources().getDisplayMetrics().density,
                                            capsule.getMeasuredHeight()),
                            control);
            return withFrost(placed, frost, control);
        }

        /** The host's end-relative offset, read in the placement phase from the live capsule. */
        private Object shifted(
                Object base, Function1<KaraokeCapsule, Long> offset, KaraokeCapsule[] control)
                throws ReflectiveOperationException {
            Function1<Object, Object> moved =
                    density -> {
                        KaraokeCapsule capsule = control[0];
                        if (capsule == null) return zero();
                        try {
                            return intOffset.newInstance(offset.invoke(capsule));
                        } catch (ReflectiveOperationException failed) {
                            throw new IllegalStateException(failed);
                        }
                    };
            return offsetBy.invoke(null, base, moved);
        }

        private Object zero() {
            try {
                return intOffset.newInstance(0L);
            } catch (ReflectiveOperationException failed) {
                throw new IllegalStateException(failed);
            }
        }

        Object modifier(int endDp, int bottomDp, Object frost, KaraokeCapsule[] control)
                throws ReflectiveOperationException {
            Object aligned = then.invoke(companion, childData.newInstance(alignment, false));
            Object[] values = {aligned, 0f, 0f, endDp * 1f, bottomDp * 1f, 0};
            // The host's padding takes a mask of arguments left to their defaults; none are.
            Object spaced =
                    padding.invoke(
                            null,
                            padding.getParameterCount() == 6
                                    ? values
                                    : java.util.Arrays.copyOf(values, 5));
            // Without the offset symbols the target stays 7 dp short of the button's axis.
            Object placed =
                    offsetBy == null
                            ? spaced
                            : shifted(
                                    spaced,
                                    capsule ->
                                            portraitOffset(
                                                    capsule.getResources()
                                                            .getDisplayMetrics()
                                                            .density),
                                    control);
            return withFrost(placed, frost, control);
        }

        private Object withFrost(Object spaced, Object frost, KaraokeCapsule[] control)
                throws ReflectiveOperationException {
            if (frost == null || drawElement == null) return spaced;
            Function1<Object, Unit> drawing =
                    scope -> {
                        try {
                            android.graphics.Canvas canvas =
                                    (android.graphics.Canvas)
                                            nativeCanvas.invoke(
                                                    null,
                                                    contextCanvas.invoke(
                                                            drawContext.invoke(scope)));
                            int saved = canvas.save();
                            try {
                                if (control[0] != null) control[0].clipVisual(canvas);
                                drawContent.invoke(scope);
                            } finally {
                                canvas.restoreToCount(saved);
                            }
                            return Unit.INSTANCE;
                        } catch (ReflectiveOperationException
                                | RuntimeException
                                | LinkageError failure) {
                            if (!drawingFailed) HostLog.d("Karaoke blur unavailable: " + failure);
                            drawingFailed = true;
                            visible(false);
                            return Unit.INSTANCE;
                        }
                    };
            // A drawing boundary leaves the full native activation target available.
            Object clipped = then.invoke(spaced, drawElement.newInstance(drawing));
            return then.invoke(clipped, frost);
        }
    }
}
