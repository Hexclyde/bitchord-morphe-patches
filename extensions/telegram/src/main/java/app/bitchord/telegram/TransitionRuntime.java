package app.bitchord.telegram;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;

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
    private static volatile boolean automaticEffects;
    private static Pair preparedPair, armedPair;
    private static Player tempoOutgoing, tempoIncoming;
    private static TransitionEdit tempoEdit;
    private static boolean tempoStarted;
    private static final Map<Player, PlaybackParameters> tempoOwned = new IdentityHashMap<>();
    private static final Map<Object, EqState> equalizers = new ConcurrentHashMap<>();
    private static final ThreadLocal<double[]> assigning = new ThreadLocal<>();
    private static final ThreadLocal<Integer> assigningSide = new ThreadLocal<>();
    private static final DeckMeter[] meters = {new DeckMeter(), new DeckMeter()};
    private static final Map<String, Field> fields = new ConcurrentHashMap<>();

    static final class Pair {
        final String outgoingId, incomingId, outgoingTitle, incomingTitle, key;
        final double outgoingDuration, incomingDuration;
        final int index, nextIndex;
        MediaItem outgoing, incoming;
        private String recording;

        Pair(String a, String b, String at, String bt, double ad, double bd, int index) {
            this(a, b, at, bt, ad, bd, index, index + 1);
        }

        Pair(
                String a,
                String b,
                String at,
                String bt,
                double ad,
                double bd,
                int index,
                int nextIndex) {
            outgoingId = a;
            incomingId = b;
            outgoingTitle = at;
            incomingTitle = bt;
            outgoingDuration = ad;
            incomingDuration = bd;
            this.index = index;
            this.nextIndex = nextIndex;
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
                                    (outgoingDuration - edit.outgoingTrim) / edit.overlapRate[0],
                                    (incomingDuration - edit.incomingCue)
                                            / (3 * edit.overlapRate[1]))));
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

    static void validateDraftBounds(Pair pair, TransitionEdit edit) {
        if (edit != null) {
            edit.validate();
            if (pair.outgoingDuration <= 0
                    || pair.incomingDuration <= 0
                    || edit.overlap > pair.maximumOverlap(edit) + .001
                    || edit.incomingCue + edit.sourceSpan(1) * 3 > pair.incomingDuration + .001
                    || edit.outgoingTrim + edit.sourceSpan(0) > pair.outgoingDuration + .001)
                throw new IllegalArgumentException("Transition exceeds the available audio");
        }
    }

    static void validateSave(Pair pair, TransitionEdit edit) {
        validateDraftBounds(pair, edit);
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
        } catch (SourceAutomix.IdentityPending waiting) {
            // A local file's fingerprint is read off the main thread; planning resumes with it.
            SourceAutomix.prepareIdentity(waiting.uri);
            return true;
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
        // Repeat-all on a one-item queue wraps to the same slot; there is no pair to edit.
        if (index < 0 || next < 0 || next == index) return null;
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
                        index,
                        next);
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

    /** The native automatic mix for a pair, or the reason it cannot be previewed faithfully. */
    static final class AutomaticMix {
        final TransitionEdit edit;
        final String reason;

        AutomaticMix(TransitionEdit edit, String reason) {
            this.edit = edit;
            this.reason = reason;
        }
    }

    /** The timing native AutoMix chose for exactly this pair and recording, or null. */
    static TransitionEdit automaticPlan(Pair pair) {
        TransitionEdit edit = automaticEdit;
        String key = automaticKey;
        return pair == null
                        || edit == null
                        || !pair.key.equals(key)
                        || !pair.recording().equals(edit.recording)
                ? null
                : edit.copy();
    }

    static AutomaticMix before(Pair pair) {
        TransitionEdit edit = automaticPlan(pair);
        if (edit == null)
            return new AutomaticMix(null, Strings.t("Automatic mix is not ready yet."));
        if (automaticEffects)
            return new AutomaticMix(
                    null, Strings.t("Before isn't available: Automix adds effects to this blend."));
        return new AutomaticMix(edit, null);
    }

    /** Anything beyond the plain equal-power blend the private audition decks reproduce. */
    private static boolean hasEffects(Object plan) {
        try {
            for (String name : new String[] {"filterSweep", "vocalOverlap", "echoSeconds"}) {
                Object value = field(plan, name);
                if (!(value instanceof Number) || ((Number) value).doubleValue() != 0) return true;
            }
            if (!Boolean.FALSE.equals(field(plan, "bassSwap"))) return true;
            try {
                if (!Boolean.FALSE.equals(field(plan, "phaseLock"))) return true;
            } catch (NoSuchFieldException older) {
                // Plans without phase information cannot promise an unchanged preview rate.
                return true;
            }
            if (!"EQUAL_POWER".equals(String.valueOf(field(plan, "transitionStyle")))) return true;
            try {
                Object render = field(plan, "render");
                if (render != null && !Boolean.FALSE.equals(field(render, "advanced"))) return true;
            } catch (NoSuchFieldException plain) {
                /* A plan without a render stage has nothing more to add. */
            }
            return false;
        } catch (Exception unreadable) {
            return true;
        }
    }

    /**
     * The native timing when it is known; otherwise a neutral placeholder that callers must not
     * present as native. See {@link #automaticPlan}.
     */
    static TransitionEdit automaticDraft(Pair pair) {
        TransitionEdit edit = automaticPlan(pair);
        return edit == null ? new TransitionEdit() : edit;
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
        edit.overlap = (plan.end - plan.start) / edit.overlapRate[0];
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
        if (controller.get() != value) released.clear();
        controller = new WeakReference<>(value);
    }

    static double djBlend(Object plan, Pair pair, Object player, double normal) throws Exception {
        if (Boolean.TRUE.equals(field(plan, "blocked"))) return normal;
        double end = ((Number) field(plan, "transitionEnd")).doubleValue();
        double cue = ((Number) field(plan, "incomingCueTime")).doubleValue();
        double switchTime = ((Number) field(plan, "incomingHandoffTime")).doubleValue();
        double outRate = ((Number) field(plan, "outgoingPlaybackRate")).doubleValue();
        double inRate = ((Number) field(plan, "incomingPlaybackRate")).doubleValue();
        if (!(outRate >= .5 && outRate <= 2 && inRate >= .5 && inRate <= 2)) return normal;
        // The span is outgoing source seconds; the incoming clock runs at inRate / outRate.
        double ratio = inRate / outRate;
        double fraction = (switchTime - cue) / (normal * ratio);
        if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) return normal;
        TransitionEdit bounds = new TransitionEdit();
        bounds.outgoingTrim = Math.max(0, Math.min(120, pair.outgoingDuration - end));
        bounds.incomingCue = Math.max(0, Math.min(3600, cue));
        bounds.overlapRate[0] = outRate;
        bounds.overlapRate[1] = inRate;
        double span =
                DjOrder.blend(
                        DjMode.info(pair.outgoingId),
                        DjMode.info(pair.incomingId),
                        normal,
                        pair.maximumOverlap(bounds) * outRate);
        if (!Double.isFinite(span)
                || span <= normal
                || end - span < 0
                || cue + span * ratio > pair.incomingDuration) return normal;
        SourceAutomix.set(plan, "fadeSeconds", span);
        SourceAutomix.set(plan, "transitionStart", end - span);
        SourceAutomix.set(plan, "incomingHandoffTime", cue + span * ratio * fraction);
        double now =
                ((Number) pcall(player, "getCurrentPosition", new Class<?>[0])).doubleValue()
                        / 1000;
        SourceAutomix.set(plan, "shouldStart", now >= end - span);
        return span;
    }

    public static void apply(Object plan) {
        prepared = null;
        preparedPlan = null;
        preparedPair = null;
        automaticEdit = null;
        automaticKey = null;
        try {
            if (Boolean.TRUE.equals(field(plan, "blocked"))) return;
            automaticEffects = hasEffects(plan);
            Pair pair = pair();
            if (pair == null || pair.outgoingDuration <= 0 || pair.incomingDuration <= 0) return;
            TransitionEdit nativeEdit = new TransitionEdit();
            double nativeSpan = ((Number) field(plan, "fadeSeconds")).doubleValue();
            if (SoundExtras.djApplied() && DjMode.enabled)
                nativeSpan = djBlend(plan, pair, player(), nativeSpan);
            double outgoingRate = ((Number) field(plan, "outgoingPlaybackRate")).doubleValue();
            double incomingRate = ((Number) field(plan, "incomingPlaybackRate")).doubleValue();
            if (!Double.isFinite(outgoingRate)
                    || !Double.isFinite(incomingRate)
                    || outgoingRate < .5
                    || outgoingRate > 2
                    || incomingRate < .5
                    || incomingRate > 2) return;
            nativeEdit.overlapRate[0] = outgoingRate;
            nativeEdit.overlapRate[1] = incomingRate;
            double nativeOverlap = nativeSpan / outgoingRate;
            if (nativeOverlap >= .5 && nativeOverlap <= pair.maximumOverlap(nativeEdit)) {
                nativeEdit.overlap = nativeOverlap;
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
            if (edit.automaticTiming) {
                TransitionEdit timing = nativeEdit.copy();
                timing.copyTempoFrom(edit);
                timing.automaticTiming = true;
                edit = timing;
            } else edit = edit.copy();
            // Rates the listener never chose follow the fresh native plan, not an older one.
            if (!edit.tempoExplicit)
                System.arraycopy(nativeEdit.overlapRate, 0, edit.overlapRate, 0, 2);
            edit.overlap = Math.min(edit.overlap, pair.maximumOverlap(edit));
            double start = edit.start(pair.outgoingDuration);
            if (start + edit.sourceSpan(0) > pair.outgoingDuration
                    || edit.incomingCue + edit.sourceSpan(1) > pair.incomingDuration) return;
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
                                        ? start + edit.sourceSpan(0)
                                        : name.equals("fadeSeconds")
                                                ? edit.sourceSpan(0)
                                                : name.equals("incomingCueTime")
                                                        ? edit.incomingCue
                                                        : edit.incomingAt(.5);
                SourceAutomix.set(plan, name, value);
            }
            double now =
                    ((Number) pcall(player(), "getCurrentPosition", new Class<?>[0])).longValue()
                            / 1000d;
            SourceAutomix.set(plan, "shouldStart", now >= start);
            SourceAutomix.set(plan, "incomingPlaybackRate", edit.overlapRate[1]);
            SourceAutomix.set(plan, "outgoingPlaybackRate", edit.overlapRate[0]);
            if (edit.tempoExplicit) {
                try {
                    SourceAutomix.set(plan, "phaseLock", false);
                } catch (NoSuchFieldException older) {
                    // A plan without phase information has no servo to disable.
                }
            }
            if (!edit.automaticTiming) {
                SourceAutomix.set(plan, "markerVisible", true);
                SourceAutomix.set(plan, "reason", "Custom transition");
                for (String name : new String[] {"filterSweep", "vocalOverlap", "echoSeconds"})
                    SourceAutomix.set(plan, name, 0d);
                SourceAutomix.set(plan, "bassSwap", false);
                Field style = plan.getClass().getDeclaredField("transitionStyle");
                style.setAccessible(true);
                style.set(plan, Enum.valueOf((Class) style.getType(), "EQUAL_POWER"));
            }
            prepared = edit;
            preparedPair = pair;
            preparedPlan = plan;
        } catch (Exception unavailable) {
            HostLog.d("Custom transition unavailable: " + unavailable.getClass().getSimpleName());
        }
    }

    public static void arm(Object owner, Object plan) {
        if (owner == controller.get() && plan != null && plan == preparedPlan && prepared != null) {
            finish();
            released.clear();
            armedPair = preparedPair;
            armed = prepared.copy();
            armedOwner = owner;
            // Only rates the listener chose are ours; native rates stay with the host.
            if (armed.tempoExplicit) {
                try {
                    tempoOutgoing = (Player) player();
                    tempoEdit = armed;
                } catch (Exception unavailable) {
                    finish();
                }
            }
        } else {
            if (armedOwner == owner) finish();
            released.clear();
        }
    }

    /** What the host's own deck fields say; null when the controller does not expose them. */
    private static Player nativeDeck(Object owner, String name) {
        if (owner == null) return null;
        try {
            Object value = field(owner, name);
            return value instanceof Player ? (Player) value : null;
        } catch (Exception unavailable) {
            return null;
        }
    }

    /** A deck whose explicit rate ended; only speed facts are kept, weakly, for two decks. */
    private static final class Released {
        final WeakReference<Player> deck;
        final float base, foreign;

        Released(Player deck, float base, float foreign) {
            this.deck = new WeakReference<>(deck);
            this.base = base;
            this.foreign = foreign;
        }
    }

    private static final List<Released> released = new ArrayList<>(2);

    private static Released releasedFor(Player deck) {
        for (Iterator<Released> it = released.iterator(); it.hasNext(); ) {
            Released mark = it.next();
            Player marked = mark.deck.get();
            if (marked == null) it.remove();
            else if (marked == deck) return mark;
        }
        return null;
    }

    private static void markReleased(Player deck, float base, float foreign) {
        Released old = releasedFor(deck);
        if (old != null) released.remove(old);
        while (released.size() >= 2) released.remove(0);
        released.add(new Released(deck, base, foreign));
    }

    private static float baseSpeed() {
        float base = AutomixAccess.speed();
        return Float.isFinite(base) && base > 0 ? base : 1f;
    }

    /**
     * The speed a deck gets. Native setters pass an absolute value that already includes the
     * listener's speed, so that value is used once; an explicit rate multiplies the listener's
     * speed once; any other deck gets the listener's speed.
     */
    static float playbackRate(Player deck, float base, float requested) {
        Object owner = controller.get();
        Player outgoing = nativeDeck(owner, "outgoing");
        Player incoming = nativeDeck(owner, "incoming");
        Player easing = nativeDeck(owner, "easing");
        TransitionEdit edit = tempoEdit;
        if (edit != null && owner != null && armedOwner == owner) {
            if (tempoIncoming == null && deck != tempoOutgoing && deck == incoming)
                tempoIncoming = deck;
            if (deck == tempoOutgoing)
                return tempoStarted ? base * (float) edit.overlapRate[0] : base;
            if (deck == tempoIncoming) return base * (float) edit.overlapRate[1];
        }
        Released mark = releasedFor(deck);
        if (mark != null) {
            // Native cleanup can ask for the base before it sets easing, so any native ref counts.
            if (deck == outgoing || deck == incoming || deck == easing) {
                if (mark.base != base) {
                    markReleased(deck, base, Float.NaN);
                    return base;
                }
                float expected = Float.isNaN(mark.foreign) ? base : mark.foreign;
                PlaybackParameters now = deck.getPlaybackParameters();
                // A speed chosen after finish stays; speed alone decides, never pitch.
                if (now != null && now.speed != expected) {
                    markReleased(deck, base, now.speed);
                    return now.speed;
                }
                return expected;
            }
            released.remove(mark);
        }
        if (deck == null || (deck != outgoing && deck != incoming && deck != easing)) return base;
        if (!Float.isFinite(requested) || requested <= 0) return base;
        return Math.max(base * .5f, Math.min(base * 2f, requested));
    }

    static void tempoAssigned(Player deck, PlaybackParameters parameters) {
        if (tempoEdit != null && (deck == tempoOutgoing || deck == tempoIncoming))
            tempoOwned.put(deck, parameters);
    }

    /** Start the explicit overlap rates before either deck becomes audible in the blend. */
    public static void beginTempo(Object owner) {
        if (armedOwner != owner || tempoEdit == null || armedPair == null) return;
        if (tempoIncoming == null) tempoIncoming = nativeDeck(owner, "incoming");
        MediaItem outgoing = tempoOutgoing == null ? null : tempoOutgoing.getCurrentMediaItem();
        MediaItem incoming = tempoIncoming == null ? null : tempoIncoming.getCurrentMediaItem();
        if (outgoing == null
                || incoming == null
                || !armedPair.outgoingId.equals(outgoing.mediaId)
                || !armedPair.incomingId.equals(incoming.mediaId)) {
            finish();
            return;
        }
        tempoStarted = true;
        SourceAutomix.speed(tempoOutgoing, baseSpeed());
        SourceAutomix.speed(tempoIncoming, baseSpeed());
    }

    public static void finish(Object owner) {
        if (armedOwner == owner) finish();
    }

    public static void finish() {
        for (Map.Entry<Player, PlaybackParameters> entry : tempoOwned.entrySet()) {
            Player deck = entry.getKey();
            try {
                PlaybackParameters current = deck.getPlaybackParameters();
                float base = baseSpeed();
                if (current == null) continue;
                // Speed alone decides ownership; a pitch change stays with the listener.
                if (current.speed == entry.getValue().speed) {
                    deck.setPlaybackParameters(new PlaybackParameters(base, current.pitch));
                    markReleased(deck, base, Float.NaN);
                } else markReleased(deck, base, current.speed);
            } catch (RuntimeException unavailable) {
                HostLog.d("Transition deck is no longer available");
            }
        }
        tempoOwned.clear();
        tempoOutgoing = tempoIncoming = null;
        tempoEdit = null;
        tempoStarted = false;
        armedPair = null;
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
            released.clear();
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
        released.clear();
        controller.clear();
    }

    public static Float incoming(Object owner, float progress) {
        TransitionEdit edit = armed;
        return edit == null || edit.automaticTiming || armedOwner != owner
                ? null
                : (float) edit.gain(1, progress);
    }

    public static Float outgoing(Object owner, float progress) {
        TransitionEdit edit = armed;
        return edit == null || edit.automaticTiming || armedOwner != owner
                ? null
                : (float) edit.gain(0, progress);
    }

    public static boolean filters(Object owner, float incoming, Float outgoing) {
        TransitionEdit edit = armed;
        if (edit == null || edit.automaticTiming || owner != armedOwner) return false;
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
