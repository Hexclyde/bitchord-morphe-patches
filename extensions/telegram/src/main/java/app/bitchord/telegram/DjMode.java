package app.bitchord.telegram;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DJ mode: the queue is played in an order that mixes well, each next song chosen for a key that
 * sits next to the current one on the Camelot wheel and a tempo close to it. Songs you queued
 * yourself keep their place. The key and tempo of a song are learned as it plays, and BitChord's
 * own Automix analyses are used when it has them. The idea is from Prism by Yigder.
 */
final class DjMode {
    private DjMode() {}

    static final String PREFERENCES = "bitchord_dj_mode";
    static final int CAPTURE_SECONDS = 48, MIN_SECONDS = KeyTempo.MIN_SECONDS;
    static final int LOOK_AHEAD = 30;
    static final int MAX_STORED = 6_000;
    static final double MARGIN = 0.5;
    private static final String TIER_KEY = "bitchord.queueTier";

    /** What's known about a song. */
    static final class Info {
        final String camelot;
        final double bpm;
        final String source;

        Info(String camelot, double bpm, String source) {
            this.camelot = camelot;
            this.bpm = bpm;
            this.source = source;
        }

        /** "A minor · 8A · 124 BPM", in the user's language. */
        String describe() {
            List<String> parts = new ArrayList<>();
            if (camelot != null) {
                String name = keyName(camelot);
                parts.add(name == null ? camelot : name + " · " + camelot);
            }
            if (bpm > 0) parts.add(Strings.f("{0} BPM", String.valueOf(Math.round(bpm))));
            return parts.isEmpty() ? Strings.t("Nothing known yet") : String.join(" · ", parts);
        }

        String toJson() {
            return (camelot == null ? "" : camelot)
                    + "|"
                    + String.format(Locale.ROOT, "%.1f", bpm)
                    + "|"
                    + source;
        }

        static Info fromJson(String text) {
            if (text == null) return null;
            String[] parts = text.split("\\|", -1);
            if (parts.length != 3) return null;
            try {
                return new Info(
                        parts[0].isEmpty() ? null : parts[0],
                        Double.parseDouble(parts[1]),
                        parts[2]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** "A minor" for a Camelot code. */
    static String keyName(String camelot) {
        if (camelot == null || camelot.length() < 2) return null;
        boolean minor = Character.toUpperCase(camelot.charAt(camelot.length() - 1)) == 'A';
        int number;
        try {
            number = Integer.parseInt(camelot.substring(0, camelot.length() - 1));
        } catch (NumberFormatException e) {
            return null;
        }
        for (int pc = 0; pc < 12; pc++)
            if (KeyTempo.camelot(pc, minor).equals(number + (minor ? "A" : "B")))
                return KeyTempo.NOTES[pc] + " " + Strings.t(minor ? "minor" : "major");
        return null;
    }

    // ── State ────────────────────────────────────────────────────────────────

    private static volatile SharedPreferences preferences;
    private static volatile File store;
    static volatile boolean enabled;
    private static final Map<String, Info> known = new ConcurrentHashMap<>();
    private static volatile boolean loaded;
    private static volatile ExecutorService worker;
    private static volatile Handler main;
    private static volatile Object service;
    static volatile String lastArrangement = "";

    static void init(Context context) {
        if (preferences != null || context == null) return;
        Context app = context.getApplicationContext();
        preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        enabled = preferences.getBoolean("enabled", false);
        store = new File(app.getNoBackupFilesDir(), "dj-mode.json");
        worker().execute(DjMode::load);
    }

    private static ExecutorService worker() {
        ExecutorService found = worker;
        if (found == null) {
            synchronized (DjMode.class) {
                if (worker == null)
                    worker =
                            Executors.newSingleThreadExecutor(
                                    task -> {
                                        Thread thread = new Thread(task, "dj-mode");
                                        thread.setPriority(Thread.MIN_PRIORITY);
                                        thread.setDaemon(true);
                                        return thread;
                                    });
                found = worker;
            }
        }
        return found;
    }

    static void setEnabled(boolean on) {
        enabled = on;
        SharedPreferences saved = preferences;
        if (saved != null) saved.edit().putBoolean("enabled", on).apply();
        HostLog.d("DJ mode " + (on ? "on" : "off"));
        if (on) pollSoon();
    }

    /** BitChord's playback service, for its Automix analyses. */
    static void service(Object playbackService) {
        service = playbackService;
        if (enabled) pollSoon();
    }

    static int knownCount() {
        return known.size();
    }

    static Info info(String mediaId) {
        return mediaId == null ? null : known.get(mediaId);
    }

    /** The song the player is on, as last reported. */
    static String currentId() {
        return currentId;
    }

    private static volatile String currentId;

    private static void load() {
        File file = store;
        if (file == null || !file.isFile()) {
            loaded = true;
            return;
        }
        try {
            JSONObject json =
                    new JSONObject(
                            new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                Info info = Info.fromJson(json.optString(id, null));
                if (info != null) known.putIfAbsent(id, info);
            }
        } catch (Exception e) {
            HostLog.d("DJ mode couldn't read its notes: " + e);
        }
        loaded = true;
    }

    private static volatile boolean saveQueued;

    private static void saveSoon() {
        if (saveQueued) return;
        saveQueued = true;
        Handler handler = main;
        if (handler == null) main = handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> worker().execute(DjMode::save), 5_000);
    }

    private static void save() {
        saveQueued = false;
        File file = store;
        if (file == null) return;
        try {
            while (known.size() > MAX_STORED) {
                Iterator<String> some = known.keySet().iterator();
                if (!some.hasNext()) break;
                some.next();
                some.remove();
            }
            JSONObject json = new JSONObject();
            for (Map.Entry<String, Info> entry : known.entrySet())
                json.put(entry.getKey(), entry.getValue().toJson());
            File temporary = new File(file.getPath() + ".tmp");
            Files.write(temporary.toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
            if (!temporary.renameTo(file)) Files.move(temporary.toPath(), file.toPath());
        } catch (Exception e) {
            HostLog.d("DJ mode couldn't save its notes: " + e);
        }
    }

    // ── Listening to what plays ──────────────────────────────────────────────

    private static final Object captureLock = new Object();
    private static float[] capture;
    private static int captured;
    private static volatile String capturingId;
    private static Object captureSource;
    private static volatile long captureFrom;
    private static long lastTapNanos;
    private static double accumulator, phase;
    private static int accumulated;

    /** How long after a song change the capture waits, past the end of a crossfade's tail. */
    static final long SETTLE_NANOS = 6_000_000_000L;

    /** A source that goes quiet this long during a capture has stopped; another is taken. */
    static final long SOURCE_QUIET_NANOS = 2_000_000_000L;

    /** The player moved to a song: finish the last one's listening and start this one's. */
    static void playing(String mediaId) {
        currentId = mediaId;
        float[] done = null;
        int count = 0;
        String doneId = null;
        synchronized (captureLock) {
            if (capturingId != null
                    && !capturingId.equals(mediaId)
                    && captured >= MIN_SECONDS * KeyTempo.RATE) {
                done = capture;
                count = captured;
                doneId = capturingId;
                capture = null;
            }
            if (mediaId == null || mediaId.equals(capturingId)) {
                if (mediaId == null) capturingId = null;
                return;
            }
            capturingId = mediaId;
            captured = 0;
            captureSource = null;
            accumulated = 0;
            accumulator = 0;
            phase = 0;
            // The first seconds may still carry the end of the last song in a crossfade.
            captureFrom = System.nanoTime() + SETTLE_NANOS;
        }
        if (done != null) analyse(doneId, done, count);
    }

    /**
     * Each decoded block, from the audio thread: kept as mono at {@link KeyTempo#RATE} until there
     * is enough of the song. Only the sink the listener hears is taken, and one source at a time;
     * nothing is allocated here once the buffer exists.
     */
    static void tap(Object source, float[] samples, int count, int channels, int sampleRate) {
        if (!enabled || channels < 1 || sampleRate < 8_000) return;
        String id = capturingId;
        if (id == null || known.containsKey(id)) return;
        long now = System.nanoTime();
        if (now < captureFrom || !audible(source)) return;
        float[] finished = null;
        int finishedCount = 0;
        synchronized (captureLock) {
            if (!id.equals(capturingId)) return;
            if (captureSource == null) captureSource = source;
            else if (captureSource != source) {
                // Another sink: the one being captured has stopped, or this is the other player
                // of a crossfade. Only a source that went quiet is replaced.
                if (now - lastTapNanos < SOURCE_QUIET_NANOS) return;
                captureSource = source;
                captured = 0;
                accumulated = 0;
                accumulator = 0;
                phase = 0;
            }
            lastTapNanos = now;
            if (capture == null) capture = new float[CAPTURE_SECONDS * KeyTempo.RATE];
            int frames = count / channels;
            for (int f = 0; f < frames && captured < capture.length; f++) {
                float mono = 0;
                for (int c = 0; c < channels; c++) mono += samples[f * channels + c];
                accumulator += mono / channels;
                accumulated++;
                phase += KeyTempo.RATE;
                if (phase >= sampleRate) {
                    phase -= sampleRate;
                    capture[captured++] = (float) (accumulator / accumulated);
                    accumulator = 0;
                    accumulated = 0;
                }
            }
            if (captured >= capture.length) {
                finished = capture;
                finishedCount = captured;
                capture = null;
                capturingId = null;
            }
        }
        if (finished != null) analyse(id, finished, finishedCount);
    }

    private static final Map<Class<?>, Object> audibleFields = new ConcurrentHashMap<>();
    private static final Object NO_FIELD = new Object();

    /**
     * Whether a sink is the one the listener hears. BitChord's sinks know (a crossfade keeps two
     * playing); its equaliser blocks don't carry that, so they're all taken.
     */
    static boolean audible(Object source) {
        if (source == null) return true;
        Object field = audibleFields.get(source.getClass());
        if (field == null) {
            try {
                Field found = source.getClass().getDeclaredField("isAudible");
                found.setAccessible(true);
                field = found;
            } catch (Exception e) {
                field = NO_FIELD;
            }
            audibleFields.put(source.getClass(), field);
        }
        if (field == NO_FIELD) return true;
        try {
            Object function = ((Field) field).get(source);
            if (function == null) return true;
            Object answer = function.getClass().getMethod("invoke").invoke(function);
            return !Boolean.FALSE.equals(answer);
        } catch (Exception | LinkageError e) {
            return true;
        }
    }

    // ── Following the session's player ───────────────────────────────────────

    private static volatile Field playerField;
    private static final Runnable poll = DjMode::poll;

    /** Looks at what the session's player is on, now and every couple of seconds while on. */
    static void pollSoon() {
        Handler handler = main;
        if (handler == null) main = handler = new Handler(Looper.getMainLooper());
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    private static void poll() {
        if (!enabled) return;
        Object player = sessionPlayer();
        if (player instanceof Player) {
            try {
                Player p = (Player) player;
                MediaItem item = p.getCurrentMediaItem();
                String id = item == null ? null : item.mediaId;
                if (p.isPlaying() || id == null) playing(id);
            } catch (RuntimeException e) {
                // Not on its thread, or gone.
            }
        }
        Handler handler = main;
        if (handler != null && service != null) {
            handler.postDelayed(poll, 2_000);
        }
    }

    private static volatile Field crossfadeField;
    private static volatile Method transitioningMethod;

    /** True while BitChord's crossfade or Automix is moving from one song to the next. */
    static boolean transitioning() {
        Object host = service;
        if (host == null) return false;
        try {
            Field field = crossfadeField;
            if (field == null) {
                field = host.getClass().getDeclaredField("crossfade");
                field.setAccessible(true);
                crossfadeField = field;
            }
            Object crossfade = field.get(host);
            if (crossfade == null) return false;
            Method method = transitioningMethod;
            if (method == null) {
                method = crossfade.getClass().getMethod("isTransitioning");
                method.setAccessible(true);
                transitioningMethod = method;
            }
            return Boolean.TRUE.equals(method.invoke(crossfade));
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    /** The player BitChord's session is on; BitChord keeps a spare for crossfades. */
    static Object sessionPlayer() {
        Object host = service;
        if (host == null) return null;
        try {
            Field field = playerField;
            if (field == null) {
                field = host.getClass().getDeclaredField("player");
                field.setAccessible(true);
                playerField = field;
            }
            return field.get(host);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    static boolean isSessionPlayer(Player player) {
        Object session = sessionPlayer();
        return session == null || session == player;
    }

    private static void analyse(String id, float[] mono, int count) {
        worker().execute(
                        () -> {
                            if (known.containsKey(id)) return;
                            KeyTempo.Result found = KeyTempo.analyse(mono, count);
                            if (found.pitchClass < 0 && found.bpm <= 0) return;
                            Info info =
                                    new Info(
                                            found.keyConfidence >= 0.15 ? found.camelot() : null,
                                            found.tempoConfidence >= 0.2 ? found.bpm : 0,
                                            "listened");
                            known.put(id, info);
                            HostLog.d(
                                    "DJ mode: "
                                            + id
                                            + " is "
                                            + info.describe()
                                            + " ("
                                            + count / KeyTempo.RATE
                                            + " s)");
                            saveSoon();
                        });
    }

    /** Takes what BitChord's Automix has already worked out, for songs not yet listened to. */
    static void importHost() {
        Object host = service;
        if (host == null) return;
        worker().execute(
                        () -> {
                            try {
                                Object analyzer = field(host, "trackAnalyzer");
                                Object results =
                                        analyzer == null ? null : field(analyzer, "results");
                                if (!(results instanceof Map)) return;
                                int added = 0;
                                for (Object value : ((Map<?, ?>) results).values()) {
                                    String id = (String) field(value, "trackId");
                                    if (id == null || id.isEmpty() || known.containsKey(id))
                                        continue;
                                    Info info = fromHost(value);
                                    if (info == null) continue;
                                    known.put(id, info);
                                    added++;
                                }
                                if (added > 0) {
                                    HostLog.d(
                                            "DJ mode: took "
                                                    + added
                                                    + " analyses from BitChord's Automix");
                                    saveSoon();
                                }
                            } catch (Exception | LinkageError e) {
                                HostLog.d("DJ mode couldn't read BitChord's analyses: " + e);
                            }
                        });
    }

    /** One of BitChord's TrackAnalysis values: its key and tempo, when it has them. */
    static Info fromHost(Object analysis) {
        Object status = field(analysis, "status");
        Object bpm = field(analysis, "bpm");
        Object key = field(analysis, "key");
        if (!"ready".equals(status) || !(bpm instanceof Double) || (Double) bpm <= 0) return null;
        return new Info(
                KeyTempo.camelotOf(key instanceof String ? (String) key : null),
                (Double) bpm,
                "BitChord");
    }

    private static Object field(Object target, String name) {
        if (target == null) return null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass())
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                // Declared further up.
            } catch (Exception e) {
                return null;
            }
        return null;
    }

    // ── Arranging the queue ──────────────────────────────────────────────────

    /** The mixing cost from one song to another; lower is better, unknown songs cost most. */
    static double cost(Info from, Info to) {
        if (from == null || to == null) return 100;
        double cost = 0;
        if (from.camelot == null || to.camelot == null) cost += 6;
        else cost += KeyTempo.keyDistance(from.camelot, to.camelot);
        if (from.bpm <= 0 || to.bpm <= 0) cost += 3;
        else cost += KeyTempo.tempoDistance(from.bpm, to.bpm) * 12;
        return cost;
    }

    /**
     * Among the next songs in the queue, up to the first one you queued yourself, the index of the
     * one that mixes best from {@code current}; -1 when the next song is already as good. Pure.
     */
    static int pick(Info current, List<Info> upcoming, List<Boolean> userQueued) {
        if (current == null || upcoming.isEmpty() || Boolean.TRUE.equals(userQueued.get(0)))
            return -1;
        int best = -1;
        double bestCost = Double.MAX_VALUE;
        for (int i = 0; i < upcoming.size(); i++) {
            if (Boolean.TRUE.equals(userQueued.get(i))) break;
            double cost = cost(current, upcoming.get(i));
            if (cost < bestCost) {
                bestCost = cost;
                best = i;
            }
        }
        if (best <= 0) return -1;
        double nextCost = cost(current, upcoming.get(0));
        return nextCost - bestCost > MARGIN ? best : -1;
    }

    /** Called on the player's thread when a song starts: brings the best next song forward. */
    static void arrange(Player player) {
        arrange(player, 0);
    }

    /** How often a reorder waits for an Automix transition to finish before giving up. */
    static final int TRANSITION_RETRIES = 10;

    private static void arrange(Player player, int waited) {
        if (!enabled || player == null) return;
        try {
            if (inParty()) return;
            // Automix plans its mix into the next song while a transition runs; moving that song
            // now would leave the plan mixing into the wrong one.
            Handler handler = main;
            if (transitioning()) {
                if (handler != null && waited < TRANSITION_RETRIES)
                    handler.postDelayed(() -> arrange(player, waited + 1), 1_000);
                return;
            }
            int at = player.getCurrentMediaItemIndex();
            int count = player.getMediaItemCount();
            if (at < 0 || at + 2 > count) return;
            MediaItem current = player.getMediaItemAt(at);
            Info now = info(current.mediaId);
            if (now == null) {
                lastArrangement = Strings.t("The current song isn’t known yet");
                return;
            }
            List<Info> upcoming = new ArrayList<>();
            List<Boolean> user = new ArrayList<>();
            int end = Math.min(count, at + 1 + LOOK_AHEAD);
            for (int i = at + 1; i < end; i++) {
                MediaItem item = player.getMediaItemAt(i);
                upcoming.add(info(item.mediaId));
                user.add(userQueued(item));
            }
            int pick = pick(now, upcoming, user);
            if (pick < 0) {
                lastArrangement = Strings.t("The next song already fits");
                return;
            }
            int from = at + 1 + pick;
            player.moveMediaItems(from, from + 1, at + 1);
            Info chosen = upcoming.get(pick);
            lastArrangement =
                    Strings.f("Moved up a song in {0}", chosen == null ? "?" : chosen.describe());
            HostLog.d(
                    "DJ mode: moved item "
                            + from
                            + " to "
                            + (at + 1)
                            + ", "
                            + now.describe()
                            + " → "
                            + chosen.describe());
        } catch (RuntimeException e) {
            HostLog.d("DJ mode couldn't arrange the queue: " + e);
        }
    }

    /** True for a song you queued with Play next or Add to queue: it keeps its place. */
    static boolean userQueued(MediaItem item) {
        try {
            Bundle extras = item.mediaMetadata == null ? null : item.mediaMetadata.extras;
            return extras != null && "USER_QUEUE".equals(extras.getString(TIER_KEY));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** In a Listen Together party the queue belongs to everyone, so nothing is moved. */
    static boolean inParty() {
        try {
            Class<?> type = Class.forName("com.music.bitchord.data.listentogether.ListenTogether");
            Object instance = type.getDeclaredField("INSTANCE").get(null);
            for (Field field : type.getDeclaredFields()) {
                if (!field.getName().toLowerCase(Locale.ROOT).contains("state")) continue;
                field.setAccessible(true);
                Object flow = field.get(instance);
                if (flow == null) continue;
                Object state;
                try {
                    state = flow.getClass().getMethod("getValue").invoke(flow);
                } catch (NoSuchMethodException e) {
                    continue;
                }
                Object code = field(state, "code");
                if (code instanceof String && !((String) code).isEmpty()) return true;
            }
        } catch (Exception | LinkageError e) {
            // No such thing in this build.
        }
        return false;
    }
}
