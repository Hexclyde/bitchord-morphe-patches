package app.bitchord.telegram;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.media3.common.MediaItem;

import kotlin.jvm.functions.Function5;

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
    private static volatile TransitionEdit automaticEdit;
    private static volatile String automaticKey;
    private static final Map<Object, EqState> equalizers = new ConcurrentHashMap<>();
    private static final ThreadLocal<double[]> assigning = new ThreadLocal<>();
    private static final ThreadLocal<Integer> assigningSide = new ThreadLocal<>();
    private static final DeckMeter[] meters = {new DeckMeter(), new DeckMeter()};
    private static final Map<String, Field> fields = new ConcurrentHashMap<>();

    static final class Pair {
        final String outgoingId, incomingId, outgoingTitle, incomingTitle, key;
        final double outgoingDuration, incomingDuration;
        final int index;
        MediaItem outgoing, incoming;
        private String recording;

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

        String recording() {
            if (recording == null)
                recording =
                        key(
                                rendition(outgoing, outgoingId, outgoingDuration),
                                rendition(incoming, incomingId, incomingDuration));
            return recording;
        }

        double maximumOverlap(TransitionEdit edit) {
            return Math.max(
                    .5,
                    Math.min(
                            TransitionEdit.MAX_OVERLAP,
                            Math.min(
                                    outgoingDuration - edit.outgoingTrim,
                                    (incomingDuration - edit.incomingCue) / 3)));
        }
    }

    static String rendition(MediaItem item, String id, double duration) {
        android.net.Uri uri = SourceFirst.renditionUri(item);
        if (uri != null
                && uri.isHierarchical()
                && uri.getQueryParameter(SourcePreparation.ENTRY) != null) {
            android.net.Uri.Builder clean = uri.buildUpon().clearQuery();
            for (String name : new TreeSet<>(uri.getQueryParameterNames())) {
                if (SourcePreparation.ENTRY.equals(name)) continue;
                for (String value : uri.getQueryParameters(name))
                    clean.appendQueryParameter(name, value);
            }
            uri = clean.build();
        }
        return id
                + "|"
                + (uri == null ? "unknown" : SourceAutomix.identity(uri))
                + "|"
                + Math.round(duration * 1000);
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
        TransitionStore.Entry entry = TransitionStore.get().lookup(pair);
        return entry.known && entry.error == null ? entry.edit() : null;
    }

    static void validateSave(Pair pair, TransitionEdit edit) {
        if (edit != null) {
            edit.validate();
            if (pair.outgoingDuration <= 0
                    || pair.incomingDuration <= 0
                    || edit.overlap > pair.maximumOverlap(edit) + .001
                    || edit.incomingCue + edit.overlap * 3 > pair.incomingDuration + .001
                    || edit.outgoingTrim + edit.overlap > pair.outgoingDuration + .001)
                throw new IllegalArgumentException("Transition exceeds the available audio");
        }
        try {
            Pair current = pair();
            if (current == null || !current.recording().equals(pair.recording()))
                throw new IllegalArgumentException("The queue changed; reopen this transition");
        } catch (IllegalArgumentException changed) {
            throw changed;
        } catch (Exception unavailable) {
            throw new IllegalArgumentException("Playback service is not ready", unavailable);
        }
    }

    static void save(Pair pair, TransitionEdit edit) {
        validateSave(pair, edit);
        TransitionStore.get().write(pair, edit, error -> {});
    }

    static void remove(Pair pair) {
        validateSave(pair, null);
        TransitionStore.get().write(pair, null, error -> {});
    }

    /**
     * Hold planning until the stored pair is known, and while the real song is paused for editing.
     */
    public static boolean holdPlanner(Object owner) {
        if (TransitionPreview.holding(owner)) return true;
        try {
            Pair pair = pair();
            return pair != null && !TransitionStore.get().lookup(pair).known;
        } catch (Exception unavailable) {
            return false;
        }
    }

    public static boolean previewHolding(Object owner) {
        return TransitionPreview.holding(owner);
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

    static Object pcall(Object player, String name, Class<?>[] types, Object... args)
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
        Pair pair =
                new Pair(
                        outgoing.mediaId,
                        incoming.mediaId,
                        title(outgoing),
                        title(incoming),
                        duration,
                        duration(incoming),
                        index);
        pair.outgoing = outgoing;
        pair.incoming = incoming;
        pair.recording();
        return pair;
    }

    static Object analysis(Pair pair, int side) {
        try {
            Object analyzer = field(SourceAutomix.owner(), "trackAnalyzer");
            Object results = field(analyzer, "results");
            return ((Map<?, ?>) results).get(side == 0 ? pair.outgoingId : pair.incomingId);
        } catch (Exception unavailable) {
            return null;
        }
    }

    static TransitionEdit automaticDraft(Pair pair) {
        TransitionEdit edit = pair.key.equals(automaticKey) ? automaticEdit : null;
        return edit == null || !pair.recording().equals(edit.recording)
                ? new TransitionEdit()
                : edit.copy();
    }

    /** Explicit, editable suggestion. Keeps native fallback when analysis is incomplete. */
    static TransitionEdit suggest(Pair pair, TransitionEdit original) {
        Object a = analysis(pair, 0), b = analysis(pair, 1);
        if (measured(a, "beatConfidence") < .5 || measured(b, "beatConfidence") < .5)
            throw new IllegalStateException("A measured beat grid is required");
        MixTools.Grid from = original.grid(0, measured(a, "bpm"), measured(a, "firstBeat"));
        MixTools.Grid to = original.grid(1, measured(b, "bpm"), measured(b, "firstBeat"));
        double earliest = Math.max(0, measured(b, "audibleStartTime"));
        double limit = measured(a, "contentEndTime");
        if (limit <= 0 || limit > pair.outgoingDuration) limit = pair.outgoingDuration;
        MixTools.Plan plan =
                MixTools.unmatched(
                        from,
                        to,
                        limit,
                        earliest,
                        measured(b, "introEndTime"),
                        original.bars > 0 ? original.bars : 8);
        if (plan == null) throw new IllegalStateException("No transition fits this audio");
        TransitionEdit edit = original.copy();
        edit.overlap = plan.end - plan.start;
        edit.outgoingTrim = pair.outgoingDuration - plan.end;
        edit.incomingCue = plan.cue;
        edit.bars =
                plan.bars == 2 || plan.bars == 4 || plan.bars == 8 || plan.bars == 16
                        ? plan.bars
                        : 0;
        if (edit.overlap > pair.maximumOverlap(edit) || edit.outgoingTrim > 120)
            throw new IllegalStateException("No transition fits this audio");
        edit.validate();
        return edit;
    }

    static float[] meter(int side) {
        return meters[side].reading(System.nanoTime());
    }

    static double measured(Object analysis, String name) {
        try {
            if (!"ready".equals(field(analysis, "status"))) return 0;
            Object value = field(analysis, name);
            double number = value instanceof Number ? ((Number) value).doubleValue() : 0;
            return Double.isFinite(number) ? number : 0;
        } catch (Exception unavailable) {
            return 0;
        }
    }

    static String musicalKey(Object analysis) {
        try {
            double confidence = measured(analysis, "keyConfidence");
            if (!(confidence >= TransitionAnalysis.KEY_CONFIDENCE && confidence <= 1)) return null;
            return KeyTempo.camelotOf((String) field(analysis, "key"));
        } catch (Exception unavailable) {
            return null;
        }
    }

    /** Real selected-source bands; created on the player thread and decoded on a worker. */
    static final class WaveRequest {
        final Object reader;
        final MediaItem item;
        final long duration;
        final double from, to;

        WaveRequest(Object reader, MediaItem item, double duration, double from, double to) {
            this.reader = reader;
            this.item = item;
            this.duration = Math.round(duration * 1000);
            this.from = Math.max(0, from);
            this.to = Math.min(duration, to);
        }

        float[][] read() throws Exception {
            if (item == null || to <= from || Thread.currentThread().isInterrupted()) return null;
            SourceAutomix.enterQuiet();
            try {
                Object result = ((Function5) reader).invoke(item, duration, from, to, 512);
                if (result == null || Thread.currentThread().isInterrupted()) return null;
                float[][] bands = {
                    (float[]) field(result, "low"),
                    (float[]) field(result, "mid"),
                    (float[]) field(result, "high")
                };
                int size = bands[0].length;
                if (size < 2 || size > 4096 || bands[1].length != size || bands[2].length != size)
                    return null;
                for (float[] band : bands)
                    for (float value : band) if (!Float.isFinite(value) || value < 0) return null;
                return bands;
            } finally {
                SourceAutomix.leaveQuiet();
            }
        }
    }

    static WaveRequest waveform(Pair pair, int side, double from, double to) {
        try {
            MediaItem item = side == 0 ? pair.outgoing : pair.incoming;
            android.net.Uri uri = SourceFirst.renditionUri(item);
            if (uri == null) return null;
            Function5<MediaItem, Long, Double, Double, Integer, Object> reader =
                    (selected, duration, start, end, bins) -> {
                        try {
                            return TransitionWaveform.read(uri, start, end, bins);
                        } catch (java.io.IOException failed) {
                            throw new IllegalStateException(failed);
                        }
                    };
            return new WaveRequest(
                    reader,
                    item,
                    side == 0 ? pair.outgoingDuration : pair.incomingDuration,
                    from,
                    to);
        } catch (Exception unavailable) {
            return null;
        }
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

    static double djBlend(Object plan, Pair pair, Object player, double normal) throws Exception {
        if (Boolean.TRUE.equals(field(plan, "blocked"))) return normal;
        double end = ((Number) field(plan, "transitionEnd")).doubleValue();
        double cue = ((Number) field(plan, "incomingCueTime")).doubleValue();
        double switchTime = ((Number) field(plan, "incomingHandoffTime")).doubleValue();
        double fraction = (switchTime - cue) / normal;
        if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) return normal;
        TransitionEdit bounds = new TransitionEdit();
        bounds.outgoingTrim = Math.max(0, Math.min(120, pair.outgoingDuration - end));
        bounds.incomingCue = Math.max(0, Math.min(3600, cue));
        double span =
                DjOrder.blend(
                        DjMode.info(pair.outgoingId),
                        DjMode.info(pair.incomingId),
                        normal,
                        pair.maximumOverlap(bounds));
        if (!Double.isFinite(span)
                || span <= normal
                || end - span < 0
                || cue + span > pair.incomingDuration) return normal;
        SourceAutomix.set(plan, "fadeSeconds", span);
        SourceAutomix.set(plan, "transitionStart", end - span);
        SourceAutomix.set(plan, "incomingHandoffTime", cue + span * fraction);
        double now =
                ((Number) pcall(player, "getCurrentPosition", new Class<?>[0])).doubleValue()
                        / 1000;
        SourceAutomix.set(plan, "shouldStart", now >= end - span);
        return span;
    }

    public static void apply(Object plan) {
        prepared = null;
        preparedPlan = null;
        try {
            if (Boolean.TRUE.equals(field(plan, "blocked"))) return;
            Pair pair = pair();
            if (pair == null || pair.outgoingDuration <= 0 || pair.incomingDuration <= 0) return;
            TransitionEdit nativeEdit = new TransitionEdit();
            double nativeSpan = ((Number) field(plan, "fadeSeconds")).doubleValue();
            if (SoundExtras.djApplied() && DjMode.enabled)
                nativeSpan = djBlend(plan, pair, player(), nativeSpan);
            if (nativeSpan >= .5 && nativeSpan <= pair.maximumOverlap(nativeEdit)) {
                nativeEdit.overlap = nativeSpan;
                nativeEdit.outgoingTrim =
                        Math.max(
                                0,
                                Math.min(
                                        120,
                                        pair.outgoingDuration
                                                - ((Number) field(plan, "transitionEnd"))
                                                        .doubleValue()));
                nativeEdit.incomingCue =
                        Math.max(
                                0,
                                Math.min(
                                        3600,
                                        ((Number) field(plan, "incomingCueTime")).doubleValue()));
                nativeEdit.overlap = Math.min(nativeEdit.overlap, pair.maximumOverlap(nativeEdit));
                nativeEdit.recording = pair.recording();
                automaticEdit = nativeEdit;
                automaticKey = pair.key;
            }
            TransitionEdit edit = load(pair);
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
            automaticKey = null;
            automaticEdit = null;
            controller.clear();
        }
    }

    public static void release() {
        finish();
        prepared = null;
        preparedPlan = null;
        automaticKey = null;
        automaticEdit = null;
        TransitionPreview.releaseService();
        controller.clear();
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
                assigningSide.set(side);
                try {
                    call(
                            tracks,
                            side == 0 ? "outgoing" : "incoming",
                            new Class<?>[] {float.class, float.class},
                            hz[0],
                            Math.min(2000, hz[1]));
                } finally {
                    assigning.remove();
                    assigningSide.remove();
                }
            }
            return true;
        } catch (Exception unavailable) {
            HostLog.d("Transition filters unavailable: " + unavailable.getClass().getSimpleName());
            return false;
        }
    }

    static void previewProcessor(Object processor, TransitionEdit edit, int side) {
        assigning.set(edit.eq[side]);
        assigningSide.set(side);
        try {
            assign(processor);
        } finally {
            assigning.remove();
            assigningSide.remove();
        }
    }

    static void detachProcessor(Object processor) {
        equalizers.remove(processor);
    }

    public static void assign(Object processor) {
        double[] gains = assigning.get();
        if (gains == null) {
            equalizers.remove(processor);
            return;
        }
        EqState previous = equalizers.get(processor);
        int side = assigningSide.get() == null ? -1 : assigningSide.get();
        if (previous == null || !Arrays.equals(previous.db, gains) || previous.side != side)
            equalizers.put(processor, new EqState(gains.clone(), side));
    }

    private static final class EqState {
        final double[] db;
        final int side;
        final boolean flat;
        TransitionEdit.Equalizer filter;
        int rate, channels;

        EqState(double[] db, int side) {
            this.db = db;
            this.side = side;
            flat = Arrays.stream(db).allMatch(value -> value == 0);
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
            if (rate < 1 || channels < 1 || channels > 32) return;
            state.configure(rate, channels);
            bytes.order(ByteOrder.nativeOrder());
            int count = bytes.remaining() / (2 * channels) * channels;
            DeckMeter meter = state.side < 0 ? null : meters[state.side];
            if (meter != null) meter.begin();
            float left = 0;
            for (int i = 0; i < count; i++) {
                int at = bytes.position() + i * 2;
                float value = bytes.getShort(at) / 32768f;
                if (!state.flat) value = state.filter.sample(value, i % channels);
                if (i % channels == 0) {
                    left = value;
                    if (channels == 1 && meter != null) meter.frame(value, value);
                } else if (i % channels == 1 && meter != null) meter.frame(left, value);
                if (!state.flat)
                    bytes.putShort(
                            at,
                            (short) Math.max(-32768, Math.min(32767, Math.round(value * 32768))));
            }
            if (meter != null) meter.end(System.nanoTime());
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
                    || channels > 32
                    || frames < 0
                    || frames > ((float[]) samples).length / channels) return;
            state.configure(rate, channels);
            float[] audio = (float[]) samples;
            if (!state.flat) state.filter.process(audio, frames, channels);
            if (state.side >= 0) {
                DeckMeter meter = meters[state.side];
                meter.begin();
                for (int i = 0; i < frames; i++)
                    meter.frame(audio[i * channels], audio[i * channels + (channels == 1 ? 0 : 1)]);
                meter.end(System.nanoTime());
            }
        } catch (Exception unavailable) {
            /* Unrelated merged block types have no transition processing. */
        }
    }

    static List<String> queue(Object player) throws Exception {
        int count = ((Number) pcall(player, "getMediaItemCount", new Class<?>[0])).intValue();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MediaItem item =
                    (MediaItem) pcall(player, "getMediaItemAt", new Class<?>[] {int.class}, i);
            ids.add(item.mediaId + "|" + SourceFirst.renditionUri(item));
        }
        return ids;
    }
}
