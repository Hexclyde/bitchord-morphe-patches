package app.bitchord.telegram;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.MediaItem;

import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Applies directed-pair edits to the host planner, faders and physical audio decks. */
public final class TransitionRuntime {
    static final String PREFERENCES = "bitchord_transition_edits";
    private static WeakReference<Object> controller = new WeakReference<>(null);
    private static volatile TransitionEdit prepared, armed;
    private static volatile Object preparedPlan, armedOwner;
    private static volatile String previewKey;
    private static volatile TransitionEdit previewEdit;
    private static final Map<String, TransitionEdit> cache = new ConcurrentHashMap<>();
    private static final Map<Object, EqState> equalizers = new ConcurrentHashMap<>();
    private static final ThreadLocal<double[]> assigning = new ThreadLocal<>();
    private static final Map<String, Field> fields = new ConcurrentHashMap<>();

    static final class Pair {
        final String outgoingId, incomingId, outgoingTitle, incomingTitle, key;
        final double outgoingDuration, incomingDuration;
        final int index;

        Pair(String a, String b, String at, String bt, double ad, double bd, int index) {
            outgoingId = a;
            incomingId = b;
            outgoingTitle = at;
            incomingTitle = bt;
            outgoingDuration = ad;
            incomingDuration = bd;
            this.index = index;
            key = key(a, b);
        }

        double maximumOverlap(TransitionEdit edit) {
            return Math.max(
                    .5,
                    Math.min(
                            30,
                            Math.min(
                                    outgoingDuration - edit.outgoingTrim,
                                    (incomingDuration - edit.incomingCue) / 3)));
        }
    }

    static String key(String a, String b) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    (a.length() + ":" + a + b)
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte value : digest) out.append(String.format(Locale.ROOT, "%02x", value & 255));
            return out.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static SharedPreferences preferences() {
        Context app = SoundExtras.app();
        if (app == null) throw new IllegalStateException("Playback service is not ready");
        return app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    static TransitionEdit load(Pair pair) {
        String raw = preferences().getString(pair.key, null);
        if (raw == null) return null;
        try {
            return TransitionEdit.decode(raw);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    static void save(Pair pair, TransitionEdit edit) {
        edit.validate();
        if (pair.outgoingDuration <= 0
                || pair.incomingDuration <= 0
                || edit.overlap > pair.maximumOverlap(edit) + .001
                || edit.incomingCue + edit.overlap * 3 > pair.incomingDuration + .001
                || edit.outgoingTrim + edit.overlap > pair.outgoingDuration + .001)
            throw new IllegalArgumentException("Transition exceeds the available audio");
        SharedPreferences prefs = preferences();
        SharedPreferences.Editor write = prefs.edit();
        if (prefs.getAll().size() >= 200 && !prefs.contains(pair.key)) {
            String oldest = prefs.getAll().keySet().iterator().next();
            write.remove(oldest);
            cache.remove(oldest);
        }
        write.putString(pair.key, edit.encode()).apply();
        cache.put(pair.key, edit.copy());
    }

    static void remove(Pair pair) {
        preferences().edit().remove(pair.key).apply();
        cache.remove(pair.key);
    }

    static Object player() throws Exception {
        Object service = SourceAutomix.owner();
        if (service == null) throw new IllegalStateException("Playback service is not ready");
        return field(service, "player");
    }

    static Object call(Object target, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method method = target.getClass().getMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static Object pcall(Object player, String name, Class<?>[] types, Object... args)
            throws Exception {
        return Class.forName("androidx.media3.common.Player")
                .getMethod(name, types)
                .invoke(player, args);
    }

    static Object field(Object value, String name) throws Exception {
        String key = value.getClass().getName() + "#" + name;
        Field found = fields.get(key);
        if (found == null) {
            found = value.getClass().getDeclaredField(name);
            found.setAccessible(true);
            fields.put(key, found);
        }
        return found.get(value);
    }

    static Pair pair() throws Exception {
        Object player = player();
        int index =
                ((Number) pcall(player, "getCurrentMediaItemIndex", new Class<?>[0])).intValue();
        int next = ((Number) pcall(player, "getNextMediaItemIndex", new Class<?>[0])).intValue();
        if (index < 0 || next < 0) return null;
        MediaItem outgoing = (MediaItem) pcall(player, "getCurrentMediaItem", new Class<?>[0]);
        MediaItem incoming =
                (MediaItem) pcall(player, "getMediaItemAt", new Class<?>[] {int.class}, next);
        if (outgoing == null || incoming == null || outgoing.mediaId.equals(incoming.mediaId))
            return null;
        double duration =
                ((Number) pcall(player, "getDuration", new Class<?>[0])).longValue() / 1000d;
        return new Pair(
                outgoing.mediaId,
                incoming.mediaId,
                title(outgoing),
                title(incoming),
                duration,
                duration(incoming),
                index);
    }

    static String title(MediaItem item) {
        return item.mediaMetadata.title == null
                ? item.mediaId
                : item.mediaMetadata.title.toString();
    }

    static double duration(MediaItem item) {
        try {
            Object song =
                    Class.forName("com.music.bitchord.playback.PlayerConnectionKt")
                            .getMethod("toSong", MediaItem.class)
                            .invoke(null, item);
            String value = (String) field(song, "durationText");
            double duration = 0;
            for (String part : value.split(":"))
                duration = duration * 60 + Double.parseDouble(part);
            return duration;
        } catch (Exception unavailable) {
            return 0;
        }
    }

    public static void bind(Object value) {
        controller = new WeakReference<>(value);
    }

    public static void apply(Object plan) {
        prepared = null;
        preparedPlan = null;
        try {
            if (Boolean.TRUE.equals(field(plan, "blocked"))) return;
            Pair pair = pair();
            if (pair == null || pair.outgoingDuration <= 0 || pair.incomingDuration <= 0) return;
            TransitionEdit edit = pair.key.equals(previewKey) ? previewEdit : load(pair);
            if (edit == null) return;
            edit = edit.copy();
            edit.overlap = Math.min(edit.overlap, pair.maximumOverlap(edit));
            double start = edit.start(pair.outgoingDuration);
            if (start + edit.overlap > pair.outgoingDuration
                    || edit.incomingCue + edit.overlap > pair.incomingDuration) return;
            for (String name :
                    new String[] {
                        "transitionStart",
                        "transitionEnd",
                        "fadeSeconds",
                        "incomingCueTime",
                        "incomingHandoffTime"
                    }) {
                double value =
                        name.equals("transitionStart")
                                ? start
                                : name.equals("transitionEnd")
                                        ? start + edit.overlap
                                        : name.equals("fadeSeconds")
                                                ? edit.overlap
                                                : name.equals("incomingCueTime")
                                                        ? edit.incomingCue
                                                        : edit.incomingCue + edit.overlap * .5;
                SourceAutomix.set(plan, name, value);
            }
            double now =
                    ((Number) pcall(player(), "getCurrentPosition", new Class<?>[0])).longValue()
                            / 1000d;
            SourceAutomix.set(plan, "shouldStart", now >= start);
            SourceAutomix.set(plan, "markerVisible", true);
            SourceAutomix.set(plan, "reason", "Custom transition");
            for (String name : new String[] {"filterSweep", "vocalOverlap", "echoSeconds"})
                SourceAutomix.set(plan, name, 0d);
            SourceAutomix.set(plan, "bassSwap", false);
            Field style = plan.getClass().getDeclaredField("transitionStyle");
            style.setAccessible(true);
            style.set(plan, Enum.valueOf((Class) style.getType(), "EQUAL_POWER"));
            prepared = edit;
            preparedPlan = plan;
        } catch (Exception unavailable) {
            HostLog.d("Custom transition unavailable: " + unavailable.getClass().getSimpleName());
        }
    }

    public static void arm(Object owner, Object plan) {
        if (owner == controller.get() && plan != null && plan == preparedPlan && prepared != null) {
            armed = prepared.copy();
            armedOwner = owner;
        } else if (armedOwner == owner) finish();
    }

    public static void finish(Object owner) {
        if (armedOwner == owner) finish();
    }

    public static void finish() {
        armed = null;
        armedOwner = null;
        equalizers.clear();
    }

    public static void release(Object owner) {
        finish(owner);
        if (controller.get() == owner) {
            prepared = null;
            preparedPlan = null;
            controller.clear();
        }
    }

    public static void release() {
        finish();
        prepared = null;
        preparedPlan = null;
        controller.clear();
        cache.clear();
    }

    public static Float incoming(Object owner, float progress) {
        TransitionEdit edit = armed;
        return edit == null || armedOwner != owner ? null : (float) edit.gain(1, progress);
    }

    public static Float outgoing(Object owner, float progress) {
        TransitionEdit edit = armed;
        return edit == null || armedOwner != owner ? null : (float) edit.gain(0, progress);
    }

    public static boolean filters(Object owner, float incoming, Float outgoing) {
        TransitionEdit edit = armed;
        if (edit == null || owner != armedOwner) return false;
        try {
            Object tracks = field(owner, "tracks");
            for (int side = 0; side < 2; side++) {
                if (side == 0 && outgoing == null) continue;
                float[] hz = edit.cutoffs(side, side == 1 ? incoming : outgoing);
                assigning.set(edit.eq[side]);
                try {
                    call(
                            tracks,
                            side == 0 ? "outgoing" : "incoming",
                            new Class<?>[] {float.class, float.class},
                            hz[0],
                            Math.min(2000, hz[1]));
                } finally {
                    assigning.remove();
                }
            }
            return true;
        } catch (Exception unavailable) {
            HostLog.d("Transition filters unavailable: " + unavailable.getClass().getSimpleName());
            return false;
        }
    }

    public static void assign(Object processor) {
        double[] gains = assigning.get();
        if (gains == null || Arrays.stream(gains).allMatch(value -> value == 0)) {
            equalizers.remove(processor);
            return;
        }
        EqState previous = equalizers.get(processor);
        if (previous == null || !Arrays.equals(previous.db, gains))
            equalizers.put(processor, new EqState(gains.clone()));
    }

    private static final class EqState {
        final double[] db;
        TransitionEdit.Equalizer filter;
        int rate, channels;

        EqState(double[] db) {
            this.db = db;
        }

        void configure(int rate, int channels) {
            if (filter == null || this.rate != rate || this.channels != channels) {
                filter = new TransitionEdit.Equalizer(rate, channels, db);
                this.rate = rate;
                this.channels = channels;
            }
        }
    }

    public static void pcm16(Object processor, ByteBuffer bytes) {
        EqState state = equalizers.get(processor);
        if (state == null || bytes.isReadOnly()) return;
        try {
            int rate = ((Number) field(processor, "sampleRate")).intValue(),
                    channels = ((Number) field(processor, "channelCount")).intValue();
            if (rate < 1 || channels < 1) return;
            state.configure(rate, channels);
            bytes.order(ByteOrder.nativeOrder());
            int count = bytes.remaining() / (2 * channels) * channels;
            for (int i = 0; i < count; i++) {
                int at = bytes.position() + i * 2;
                float value = state.filter.sample(bytes.getShort(at) / 32768f, i % channels);
                bytes.putShort(
                        at, (short) Math.max(-32768, Math.min(32767, Math.round(value * 32768))));
            }
        } catch (Exception unavailable) {
            equalizers.remove(processor);
        }
    }

    public static void floats(Object chain, Object block) {
        try {
            Object processor = field(chain, "source");
            EqState state = equalizers.get(processor);
            if (state == null) return;
            Object samples = field(block, "children");
            if (!(samples instanceof float[])) return;
            int rate = ((Number) field(processor, "sampleRate")).intValue(),
                    channels = ((Number) field(block, "symbol")).intValue(),
                    frames = ((Number) field(block, "terminalBitCount")).intValue();
            if (rate < 1
                    || channels < 1
                    || frames < 0
                    || frames > ((float[]) samples).length / channels) return;
            state.configure(rate, channels);
            state.filter.process((float[]) samples, frames, channels);
        } catch (Exception unavailable) {
            /* Unrelated merged block types have no transition processing. */
        }
    }

    static final class Preview {
        final Pair pair;
        final List<String> queue;
        final int index;
        final long position;
        final boolean playing;
        final Object speed;
        final Handler handler = new Handler(Looper.getMainLooper());
        boolean stopping, closed;
        Runnable done;

        Preview(Pair pair, TransitionEdit edit, Runnable done) throws Exception {
            edit.validate();
            this.pair = pair;
            this.done = done;
            Object player = player();
            queue = queue(player);
            index = pair.index;
            position = ((Number) pcall(player, "getCurrentPosition", new Class<?>[0])).longValue();
            playing = (Boolean) pcall(player, "getPlayWhenReady", new Class<?>[0]);
            speed = pcall(player, "getPlaybackParameters", new Class<?>[0]);
            if (!AutomixAccess.enabled())
                throw new IllegalStateException("Enable Automix in Playback settings first");
            previewKey = pair.key;
            previewEdit = edit.copy();
            try {
                pcall(
                        player,
                        "seekTo",
                        new Class<?>[] {int.class, long.class},
                        index,
                        Math.round(Math.max(0, edit.start(pair.outgoingDuration) - 2) * 1000));
                pcall(player, "play", new Class<?>[0]);
            } catch (Exception failure) {
                previewKey = null;
                previewEdit = null;
                throw failure;
            }
            long limit = System.currentTimeMillis() + 90000;
            handler.postDelayed(
                    new Runnable() {
                        public void run() {
                            if (closed || stopping) return;
                            try {
                                Object current = player();
                                int at =
                                        ((Number)
                                                        pcall(
                                                                current,
                                                                "getCurrentMediaItemIndex",
                                                                new Class<?>[0]))
                                                .intValue();
                                long pos =
                                        ((Number)
                                                        pcall(
                                                                current,
                                                                "getCurrentPosition",
                                                                new Class<?>[0]))
                                                .longValue();
                                if (System.currentTimeMillis() > limit
                                        || !queue.equals(queue(current))
                                        || (at != index
                                                && pos
                                                        > (edit.incomingCue + edit.overlap + 2)
                                                                * 1000)) {
                                    stop();
                                    return;
                                }
                            } catch (Exception unavailable) {
                                stop();
                                return;
                            }
                            handler.postDelayed(this, 100);
                        }
                    },
                    100);
        }

        void stop() {
            if (stopping || closed) return;
            stopping = true;
            previewKey = null;
            previewEdit = null;
            try {
                Object player = player();
                pcall(player, "pause", new Class<?>[0]);
                Object control = controller.get();
                if (control != null) call(control, "onSkipRequested", new Class<?>[0]);
            } catch (Exception ignored) {
            }
            long limit = System.currentTimeMillis() + 2000;
            handler.post(
                    new Runnable() {
                        public void run() {
                            try {
                                Object control = controller.get();
                                if (control != null
                                        && Boolean.TRUE.equals(
                                                call(control, "isTransitioning", new Class<?>[0]))
                                        && System.currentTimeMillis() < limit) {
                                    handler.postDelayed(this, 40);
                                    return;
                                }
                                Object current = player();
                                if (queue.equals(queue(current))) {
                                    pcall(
                                            current,
                                            "seekTo",
                                            new Class<?>[] {int.class, long.class},
                                            index,
                                            position);
                                    pcall(
                                            current,
                                            "setPlaybackParameters",
                                            new Class<?>[] {
                                                Class.forName(
                                                        "androidx.media3.common.PlaybackParameters")
                                            },
                                            speed);
                                    pcall(
                                            current,
                                            "setPlayWhenReady",
                                            new Class<?>[] {boolean.class},
                                            playing);
                                }
                            } catch (Exception ignored) {
                            }
                            closed = true;
                            finish();
                            if (done != null) done.run();
                        }
                    });
        }
    }

    private static List<String> queue(Object player) throws Exception {
        int count = ((Number) pcall(player, "getMediaItemCount", new Class<?>[0])).intValue();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++)
            ids.add(
                    ((MediaItem) pcall(player, "getMediaItemAt", new Class<?>[] {int.class}, i))
                            .mediaId);
        return ids;
    }
}
