package app.bitchord.telegram;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.compose.runtime.ComposerImpl;
import androidx.compose.runtime.NeverEqualPolicy;
import androidx.compose.runtime.ParcelableSnapshotMutableState;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.jvm.internal.ContinuationImpl;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Measured audio/video controls using the host's version lookup and queue-preserving swap. */
public final class VideoSwitch {
    private VideoSwitch() {}

    static final String PREFERENCES = "bitchord_video_switch";
    private static final String SONG = "com.music.bitchord.data.model.Song";
    private static final String CONNECTION = "com.music.bitchord.playback.PlayerConnectionKt";
    private static final String REPOSITORY = "com.music.bitchord.data.YtMusicRepository";

    /** The fields a version takes from the song it replaces, so the queue stays as it was. */
    private static final String[] QUEUE_FIELDS = {
        "queueTier",
        "queueEntryId",
        "radioName",
        "playbackSource",
        "playbackSourceType",
        "playbackSourceId"
    };

    static volatile boolean enabled = false;
    static volatile boolean preferredVideo;
    private static volatile String attempted;
    private static volatile String seenEntry, seenId;
    private static Object eventListener;
    private static final Runnable ENFORCE = VideoSwitch::enforcePreference;

    private static volatile SharedPreferences preferences;
    private static volatile WeakReference<Object> controller = new WeakReference<>(null);
    private static WeakReference<Pill> pill = new WeakReference<>(null);
    private static Method androidView;
    private static Object headerModifier;
    static final Function1<Context, View> HEADER_FACTORY = Header::new;
    static final Function1<Context, View> LANDSCAPE_FACTORY = context -> new Header(context, true);
    private static Handler main;

    /** Songs with no other version, so the pill doesn't ask again. */
    private static final Set<String> missing = Collections.synchronizedSet(new HashSet<>());

    /** The video id being switched from while a switch runs, or null. */
    private static volatile String switching;

    private static volatile boolean lastLookupCached;

    private static final long VERSION_WAIT_MS = 30_000;
    private static LyricsSwitch lyricsSwitch;

    /** The chosen provider stays the song's while a version has no result from it. */
    private static String keptEntry;

    private static Object keptProvider;

    /** A lookup made with a length that is off by more than the providers' own tolerance. */
    private static final long LENGTH_RELOAD_MS = 2_000;

    private static final int LENGTH_RELOADS = 2;
    private static WeakReference<Object> lookupModel = new WeakReference<>(null);
    private static String reloadId;
    private static int reloads;

    private static final class LyricsSwitch {
        final String from, target, entry;
        final boolean video, host;
        final long deadline;
        WeakReference<Object> model = new WeakReference<>(null);
        Object provider;
        List<?> key, oldKey;
        long generation;
        boolean retryScheduled;

        LyricsSwitch(Object source, Object replacement, long now) {
            this(
                    string(source, "videoId"),
                    string(replacement, "videoId"),
                    string(source, "queueEntryId"),
                    isVideo(replacement),
                    false,
                    now);
        }

        LyricsSwitch(
                String from, String target, String entry, boolean video, boolean host, long now) {
            this.from = from;
            this.target = target;
            this.entry = entry;
            this.video = video;
            this.host = host;
            deadline = now + VERSION_WAIT_MS;
        }

        boolean belongs(Object playing, long now) {
            // BitChord's own swaps follow its settings, not the switch's saved preference.
            return enabled
                    && (host || video == preferredVideo)
                    && now < deadline
                    && entry != null
                    && entry.equals(string(playing, "queueEntryId"))
                    && (target.equals(string(playing, "videoId"))
                            || from.equals(string(playing, "videoId")));
        }
    }

    static void pendingLyricsSwitch(Object from, Object target, long now) {
        lyricsSwitch = null;
        String entry = string(from, "queueEntryId");
        if (enabled
                && entry != null
                && entry.equals(string(target, "queueEntryId"))
                && string(from, "videoId") != null
                && string(target, "videoId") != null
                && !Objects.equals(string(from, "videoId"), string(target, "videoId")))
            lyricsSwitch = new LyricsSwitch(from, target, now);
    }

    /** Carries the lyrics provider across a version change that BitChord made itself. */
    private static void hostLyricsSwitch(Object playing, String fromId, long now) {
        String target = string(playing, "videoId");
        String entry = string(playing, "queueEntryId");
        LyricsSwitch pending = lyricsSwitch;
        if (!enabled
                || entry == null
                || !youTubeId(fromId)
                || !youTubeId(target)
                || fromId.equals(target)
                || (pending != null && pending.belongs(playing, now))) return;
        lyricsSwitch = new LyricsSwitch(fromId, target, entry, isVideo(playing), true, now);
    }

    /**
     * A new version of the same queue entry that BitChord swapped in (its song menu or music-only
     * preference) is not undone by the saved preference, and keeps the chosen lyrics provider.
     */
    static void noteCurrent(Object playing, long now) {
        String id = string(playing, "videoId");
        if (id == null) return;
        String entry = string(playing, "queueEntryId");
        String beforeEntry = seenEntry, beforeId = seenId;
        seenEntry = entry;
        seenId = id;
        if (entry == null || !entry.equals(beforeEntry)) {
            keptProvider = null;
            reloadId = null;
        }
        if (entry == null
                || !entry.equals(beforeEntry)
                || !youTubeId(beforeId)
                || !youTubeId(id)
                || beforeId.equals(id)) return;
        attempted = id + "|" + preferredVideo;
        hostLyricsSwitch(playing, beforeId, now);
    }

    /** The native lookup still clears its results, jobs and old timed lyrics. */
    public static void lyricsBeforeReset(Object model, Object nextKey) {
        boolean onMain =
                Looper.getMainLooper() != null && Looper.myLooper() == Looper.getMainLooper();
        beforeLyrics(
                model,
                nextKey,
                onMain ? current() : null,
                SystemClock.elapsedRealtime(),
                onMain && !inParty());
    }

    public static void lyricsReady(Object model) {
        boolean onMain =
                Looper.getMainLooper() != null && Looper.myLooper() == Looper.getMainLooper();
        LyricsSwitch pending = lyricsSwitch;
        boolean waiting =
                afterLyrics(
                        model,
                        onMain ? current() : null,
                        SystemClock.elapsedRealtime(),
                        onMain && !inParty());
        if (waiting && pending != null && main != null && !pending.retryScheduled) {
            pending.retryScheduled = true;
            main.postDelayed(
                    () -> {
                        pending.retryScheduled = false;
                        if (lyricsSwitch != pending) return;
                        Object owner = pending.model.get();
                        if (owner == null) lyricsSwitch = null;
                        else lyricsReady(owner);
                    },
                    100);
        }
    }

    public static void lyricsModel(Object model) {
        if (Looper.getMainLooper() != null && Looper.myLooper() == Looper.getMainLooper())
            lookupModel = new WeakReference<>(model);
    }

    /** The length BitChord is about to look lyrics up with, before it compares the lookup key. */
    public static void lyricsLength(long durationMs) {
        boolean onMain =
                Looper.getMainLooper() != null && Looper.myLooper() == Looper.getMainLooper();
        reloadLyrics(
                lookupModel.get(),
                durationMs,
                onMain ? current() : null,
                SystemClock.elapsedRealtime(),
                onMain && !inParty());
    }

    /**
     * The native lookup key ignores the length, so a lookup made before the version's length landed
     * is never repeated. A real change gets one fresh native lookup, with the provider kept.
     */
    static boolean reloadLyrics(
            Object model, long durationMs, Object playing, long now, boolean onMain) {
        if (!enabled || !onMain || model == null || playing == null || durationMs <= 0)
            return false;
        try {
            Object request = read(model, "currentLyricsRequest");
            String id = string(playing, "videoId");
            String entry = string(playing, "queueEntryId");
            Object shown = read(model, "lyricsFor");
            if (request == null
                    || shown == null
                    || entry == null
                    || id == null
                    || lyricsSwitch != null
                    || !id.equals(string(request, "videoId"))
                    || Math.abs(
                                    durationMs
                                            - request.getClass()
                                                    .getField("durationMs")
                                                    .getLong(request))
                            <= LENGTH_RELOAD_MS) return false;
            String again = entry + "|" + id;
            if (!again.equals(reloadId)) {
                reloadId = again;
                reloads = 0;
            }
            if (reloads >= LENGTH_RELOADS) return false;
            reloads++;
            if (read(model, "selectedLyricsSource") != null) {
                LyricsSwitch reload = new LyricsSwitch(id, id, entry, isVideo(playing), true, now);
                reload.oldKey = (List<?>) shown;
                lyricsSwitch = reload;
            }
            // The native lookup sees a new key and runs its usual reset.
            model.getClass().getField("lyricsFor").set(model, null);
            return true;
        } catch (Exception | LinkageError unavailable) {
            HostLog.d("Lyrics length reload unavailable: " + unavailable);
            return false;
        }
    }

    static void beforeLyrics(
            Object model, Object nextKey, Object playing, long now, boolean onMain) {
        if (onMain) noteCurrent(playing, now);
        LyricsSwitch pending = lyricsSwitch;
        if (pending == null) return;
        try {
            if (!onMain || !pending.belongs(playing, now) || !(nextKey instanceof List))
                throw new IllegalStateException();
            List<?> old =
                    pending.oldKey != null ? pending.oldKey : (List<?>) read(model, "lyricsFor");
            List<?> next = (List<?>) nextKey;
            Object provider = read(model, "selectedLyricsSource");
            if (provider == null && pending.entry.equals(keptEntry)) provider = keptProvider;
            if (old == null
                    || old.size() != 4
                    || next.size() != 4
                    || provider == null
                    || !pending.from.equals(old.get(0))
                    || !pending.target.equals(next.get(0))
                    || !old.subList(1, 4).equals(next.subList(1, 4))
                    || !pending.from.equals(string(read(model, "currentLyricsRequest"), "videoId")))
                throw new IllegalStateException();
            pending.key =
                    java.util.Arrays.asList(
                            next.get(0),
                            new HashSet<>((Set<?>) next.get(1)),
                            new ArrayList<>((List<?>) next.get(2)),
                            next.get(3));
            pending.model = new WeakReference<>(model);
            pending.provider = provider;
            pending.generation = ((Number) read(model, "lyricsGeneration")).longValue() + 1;
        } catch (Exception | LinkageError unavailable) {
            lyricsSwitch = null;
        }
    }

    static boolean afterLyrics(Object model, Object playing, long now, boolean onMain) {
        LyricsSwitch pending = lyricsSwitch;
        if (pending == null) return false;
        try {
            if (!onMain
                    || !pending.belongs(playing, now)
                    || pending.model.get() != model
                    || pending.provider == null
                    || pending.key == null
                    || !pending.key.equals(read(model, "lyricsFor"))
                    || pending.generation != ((Number) read(model, "lyricsGeneration")).longValue()
                    || read(model, "selectedLyricsSource") != null
                    || !pending.target.equals(
                            string(read(model, "currentLyricsRequest"), "videoId"))) {
                lyricsSwitch = null;
                return false;
            }
            if (!pending.target.equals(string(playing, "videoId"))) return true;
            lyricsSwitch = null;
            // This bridge invokes the provider chooser captured by BitChord's player UI.
            model.getClass()
                    .getMethod("bitchordSelectLyricsProvider", Object.class)
                    .invoke(model, pending.provider);
            // The chooser ignores a provider with no result for this version; keep it for the next.
            keptEntry = pending.entry;
            keptProvider = read(model, "selectedLyricsSource") == null ? pending.provider : null;
        } catch (Exception | LinkageError unavailable) {
            if (lyricsSwitch == pending) lyricsSwitch = null;
            HostLog.d("Version lyrics selection unavailable: " + unavailable);
        }
        return false;
    }

    private static Object read(Object owner, String name) throws ReflectiveOperationException {
        return owner.getClass().getField(name).get(owner);
    }

    static void init(Context context) {
        if (preferences != null || context == null) return;
        preferences =
                context.getApplicationContext()
                        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        enabled = preferences.getBoolean("enabled", false);
        preferredVideo = preferences.getBoolean("preferredVideo", false);
        if (main == null) main = new Handler(Looper.getMainLooper());
        main.post(ENFORCE);
    }

    static void setEnabled(boolean on) {
        enabled = on;
        if (!on) {
            lyricsSwitch = null;
            keptProvider = null;
            reloadId = null;
        }
        SharedPreferences saved = preferences;
        if (saved != null) saved.edit().putBoolean("enabled", on).apply();
        attempted = null;
        if (main != null) main.post(ENFORCE);
        Pill shown = pill.get();
        if (shown != null) shown.refresh();
    }

    // ── Hooks ───────────────────────────────────────────────────────────────

    /** BitChord's player connection, each time the app's screen reads the player's state. */
    public static void controller(Object mediaController) {
        if (mediaController != null && controller.get() != mediaController) {
            lyricsSwitch = null;
            Object previous = controller.get();
            controller = new WeakReference<>(mediaController);
            if (main == null) main = new Handler(Looper.getMainLooper());
            main.post(() -> observe(previous, mediaController));
        }
    }

    /** A saved YouTube preference, independent of the current song's available versions. */
    static void setPreferredVideo(boolean video) {
        preferredVideo = video;
        if (lyricsSwitch != null && !lyricsSwitch.host && lyricsSwitch.video != video)
            lyricsSwitch = null;
        attempted = null;
        SharedPreferences saved = preferences;
        if (saved != null) saved.edit().putBoolean("preferredVideo", video).apply();
        if (main != null) main.post(ENFORCE);
        refreshPill();
    }

    private static void observe(Object previous, Object current) {
        try {
            Class<?> listener = Class.forName("androidx.media3.common.Player$Listener");
            if (previous != null && eventListener != null)
                controllerType()
                        .getMethod("removeListener", listener)
                        .invoke(previous, eventListener);
            eventListener =
                    Proxy.newProxyInstance(
                            listener.getClassLoader(),
                            new Class<?>[] {listener},
                            (proxy, method, args) -> {
                                if (method.getName().equals("onMediaItemTransition"))
                                    trackChanged();
                                if (method.getName().equals("onMediaItemTransition")
                                        || method.getName().equals("onEvents")) {
                                    main.removeCallbacks(ENFORCE);
                                    main.post(ENFORCE);
                                }
                                if (method.getName().equals("equals")) return proxy == args[0];
                                if (method.getName().equals("hashCode"))
                                    return System.identityHashCode(proxy);
                                if (method.getName().equals("toString"))
                                    return "BitChord version preference";
                                return null;
                            });
            controllerType().getMethod("addListener", listener).invoke(current, eventListener);
        } catch (Exception | LinkageError unavailable) {
            HostLog.d("Version preference listener unavailable: " + unavailable);
        }
        main.post(ENFORCE);
    }

    /** Each queue entry gets a fresh attempt, even when the same recording plays again. */
    static void trackChanged() {
        Fullscreen.reset();
        attempted = null;
        missing.clear();
        Object playing = current();
        if (playing != null) noteCurrent(playing, SystemClock.elapsedRealtime());
        LyricsSwitch pending = lyricsSwitch;
        if (pending != null && !pending.belongs(playing, SystemClock.elapsedRealtime()))
            lyricsSwitch = null;
        if (main != null) main.post(ENFORCE);
    }

    /**
     * Whether the player's fullscreen video shows, kept apart from the screen's orientation: some
     * windows ignore an orientation request, so the request itself decides nothing. What the window
     * did after it decides, and a rotation hands the choice back to the host.
     */
    public static final class Fullscreen {
        private Fullscreen() {}

        static final int AUTO = 0, ON = 1, OFF = 2;
        static final long SETTLE_MS = 600;

        static int mode = AUTO;
        private static boolean sideways, asked;
        private static long changes;
        private static ParcelableSnapshotMutableState revision;
        private static WeakReference<Activity> owner = new WeakReference<>(null);
        private static WeakReference<Object> orientation = new WeakReference<>(null);

        /** The host's own landscape choice, replaced by what the window really did. */
        public static boolean show(boolean landscape) {
            if (revision == null)
                revision = new ParcelableSnapshotMutableState(0, NeverEqualPolicy.INSTANCE);
            revision.getValue();
            if (landscape != sideways) {
                sideways = landscape;
                set(AUTO, true);
            }
            return mode == AUTO ? landscape : mode == ON;
        }

        /** The host asked its window to turn; it may not. */
        public static void requested(Activity activity, boolean landscape) {
            requested(null, activity, landscape);
        }

        public static void requested(Object controller, Activity activity, boolean landscape) {
            orientation = new WeakReference<>(controller);
            if (owner.get() != activity) {
                set(AUTO, false);
                owner = new WeakReference<>(activity);
            }
            Handler handler = new Handler(Looper.getMainLooper());
            handler.removeCallbacks(SETTLE);
            ask(landscape);
            handler.postDelayed(SETTLE, SETTLE_MS);
        }

        static final Runnable SETTLE = Fullscreen::settle;

        static void ask(boolean landscape) {
            asked = landscape;
        }

        /** Where the window did not follow the request, fullscreen follows it alone. */
        static void settle() {
            set(asked == sideways ? AUTO : asked ? ON : OFF, true);
        }

        static void reset() {
            set(AUTO, true);
        }

        /** Back leaves a fullscreen the window never turned into. */
        static final Function0<Unit> LEAVE =
                () -> {
                    Object controller = orientation.get();
                    if (mode == ON && controller != null) portrait(controller);
                    reset();
                    return Unit.INSTANCE;
                };

        /** The frame yields to native drawers before leaving a held fullscreen. */
        public static void bind(Function0<Boolean> overlayOpen, ComposerImpl composer) {
            if (revision == null)
                revision = new ParcelableSnapshotMutableState(0, NeverEqualPolicy.INSTANCE);
            revision.getValue();
            boolean overlay = Boolean.TRUE.equals(overlayOpen.invoke());
            hostBack(mode == ON && !overlay, LEAVE, composer, 0, 0);
        }

        // Replaced by the patch with the controller's native portrait request.
        public static void portrait(Object controller) {}

        // Replaced by the patch with BitChord's own back handler composable.
        public static void hostBack(
                boolean enabled,
                Function0<Unit> onBack,
                ComposerImpl composer,
                int changed,
                int flags) {}

        private static void set(int next, boolean notify) {
            if (mode == next) return;
            mode = next;
            if (notify && revision != null) revision.setValue(++changes);
        }
    }

    static boolean needsPreferredVersion(Object song) {
        return enabled && switchable(song) && isVideo(song) != preferredVideo;
    }

    /** Reads BitChord's music-only preference once; false when this BitChord has none. */
    static boolean hostMusicOnly() {
        try {
            Class<?> type = Class.forName("com.music.bitchord.playback.MusicOnlyPlayback");
            return Boolean.TRUE.equals(
                    type.getMethod("isActive").invoke(type.getField("INSTANCE").get(null)));
        } catch (ReflectiveOperationException | LinkageError absent) {
            return false;
        }
    }

    static volatile java.util.function.BooleanSupplier nativeMusicOnly = VideoSwitch::hostMusicOnly;

    /** The version the player switch shows: BitChord's own choice while it converts videos. */
    static boolean shownVideo(Object song) {
        return switchable(song) && nativeMusicOnly.getAsBoolean() ? isVideo(song) : preferredVideo;
    }

    /**
     * Whether the saved preference may switch [song] now. A tap on the switch always may; the
     * automatic pass leaves the song to BitChord's music-only preference while that is on.
     */
    static boolean claim(Object song, boolean manual) {
        if (!needsPreferredVersion(song) || inParty() || switching != null) return false;
        if (!manual && nativeMusicOnly.getAsBoolean()) return false;
        String key = string(song, "videoId") + "|" + preferredVideo;
        if (key.equals(attempted) || missing.contains(key)) return false;
        attempted = key;
        return true;
    }

    private static void enforcePreference() {
        enforce(false);
    }

    private static void enforce(boolean manual) {
        Context context = SoundExtras.app();
        Object song = current();
        if (song != null) noteCurrent(song, SystemClock.elapsedRealtime());
        if (context == null || !claim(song, manual)) return;
        switchTo(context, preferredVideo);
    }

    // ── What's playing ──────────────────────────────────────────────────────

    /** The song playing now, as BitChord's Song, or null. */
    static Object current() {
        Object mediaController = controller.get();
        if (mediaController == null) return null;
        try {
            Object item = controllerType().getMethod("getCurrentMediaItem").invoke(mediaController);
            if (item == null) return null;
            return connection("toSong").invoke(null, item);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /** Whether the song can switch: a YouTube Music song, not a file, addon or Telegram track. */
    static boolean switchable(Object song) {
        if (song == null) return false;
        String id = string(song, "videoId");
        if (!youTubeId(id)) return false;
        return string(song, "localUri") == null && string(song, "localPath") == null;
    }

    static boolean youTubeId(String id) {
        if (id == null || id.length() != 11) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_') || c > 'z') return false;
        }
        return true;
    }

    static boolean isVideo(Object song) {
        return song != null && bool(song, "isVideo");
    }

    /** True in a Listen Together party, where BitChord doesn't swap versions. */
    static boolean inParty() {
        try {
            Class<?> type = Class.forName("com.music.bitchord.data.listentogether.ListenTogether");
            Field field = type.getDeclaredField("state");
            field.setAccessible(true);
            Object state = flowValue(field.get(null));
            if (state == null) return false;
            Method method = state.getClass().getMethod("getInParty");
            return Boolean.TRUE.equals(method.invoke(state));
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    // ── Switching ───────────────────────────────────────────────────────────

    /** Switches the song playing now to its video (true) or its song (false). */
    static void switchTo(Context context, boolean video) {
        Object now = current();
        if (!switchable(now) || isVideo(now) == video || switching != null) return;
        if (inParty()) {
            toast(context, Strings.t("Versions can’t be switched in a Listen Together party."));
            return;
        }
        String from = string(now, "videoId");
        switching = from;
        refreshPill();
        long tapped = SystemClock.elapsedRealtime();
        new Thread(
                        () -> {
                            Object version = null;
                            long[] stages = {tapped, 0, 0};
                            try {
                                YouTubePolicy.playbackGate();
                                stages[1] = SystemClock.elapsedRealtime();
                                version = find(now, video);
                            } catch (Exception | LinkageError e) {
                                HostLog.d("Couldn't find the other version: " + e);
                            }
                            stages[2] = SystemClock.elapsedRealtime();
                            Object found = version;
                            main.post(() -> finish(context, now, found, video, stages));
                        },
                        "bitchord-video-switch")
                .start();
    }

    private static void finish(
            Context context, Object now, Object version, boolean video, long[] stages) {
        String from = string(now, "videoId");
        try {
            Object playing = current();
            if (playing == null || !from.equals(string(playing, "videoId"))) return;
            String entry = string(now, "queueEntryId");
            if (entry != null && !entry.equals(string(playing, "queueEntryId"))) return;
            if (!enabled || video != preferredVideo || inParty()) return;
            if (version == null || from.equals(string(version, "videoId"))) {
                missing.add(from + "|" + video);
                toast(
                        context,
                        video
                                ? Strings.t("There’s no video for this song.")
                                : Strings.t("There’s no song version of this video."));
                return;
            }
            Object replacement = asReplacement(version, now, video);
            Object mediaController = controller.get();
            if (mediaController == null) return;
            Class<?> songType = Class.forName(SONG);
            pendingLyricsSwitch(now, replacement, SystemClock.elapsedRealtime());
            Class.forName(CONNECTION)
                    .getMethod("swapToVersion", controllerType(), songType)
                    .invoke(null, mediaController, replacement);
            HostLog.d("Requested the " + (video ? "video" : "song") + " version of " + from);
            HostLog.d(timing(video, stages, lastLookupCached, SystemClock.elapsedRealtime()));
        } catch (Exception | LinkageError e) {
            lyricsSwitch = null;
            HostLog.d("Version switch failed: " + e);
            toast(context, Strings.t("Couldn’t switch versions."));
        } finally {
            switching = null;
            refreshPill();
            if (main != null) main.post(ENFORCE);
        }
    }

    /** Where a switch spent its time, in milliseconds; no ids, titles or sources. */
    static String timing(boolean video, long[] stages, boolean cached, long dispatched) {
        return "Switch timing to="
                + (video ? "video" : "song")
                + " gate="
                + (stages[1] - stages[0])
                + " lookup="
                + (stages[2] - stages[1])
                + " cache="
                + (cached ? "hit" : "miss")
                + " main="
                + (dispatched - stages[2])
                + " total="
                + (dispatched - stages[0]);
    }

    private static Class<?> controllerType() throws ClassNotFoundException {
        return Class.forName("androidx.media3.session.MediaController");
    }

    /** The other version: BitChord's cached one, or one looked up on YouTube Music. */
    static Object find(Object song, boolean video) throws Exception {
        Class<?> repository = Class.forName(REPOSITORY);
        String id = string(song, "videoId");
        Method cached =
                repository.getMethod(
                        video ? "cachedVideoVersion" : "cachedAudioVersion", String.class);
        Object version = cached.invoke(null, id);
        lastLookupCached = version != null;
        if (version != null) return version;
        Object instance = repository.getField("INSTANCE").get(null);
        Method resolve =
                repository.getMethod(
                        video ? "resolveVideo" : "resolveAudio",
                        Class.forName(SONG),
                        ContinuationImpl.class);
        return await(continuation -> resolve.invoke(instance, song, continuation));
    }

    /**
     * The version as the queue's entry: the song's place, radio and source are kept, as BitChord's
     * menu does. Made from a fresh copy so BitChord's cache isn't changed.
     */
    static Object asReplacement(Object version, Object replaced, boolean video) throws Exception {
        Class<?> connection = Class.forName(CONNECTION);
        Class<?> songType = Class.forName(SONG);
        Object item = connection.getMethod("toMediaItem", songType).invoke(null, version);
        Object copy = connection("toSong").invoke(null, item);
        for (String name : QUEUE_FIELDS) {
            Field field = songType.getDeclaredField(name);
            field.setAccessible(true);
            field.set(copy, field.get(replaced));
        }
        if (!video) {
            // A song stays analysable by Automix and its stats; the video's origin flag blocks
            // both.
            Field origin = songType.getDeclaredField("isVideoOrigin");
            origin.setAccessible(true);
            origin.setBoolean(copy, false);
        }
        return copy;
    }

    // ── Calling BitChord's suspending lookups ──────────────────────────────

    interface Suspending {
        Object call(ContinuationImpl continuation) throws Exception;
    }

    /** Runs one of BitChord's suspending functions to the end, on this (background) thread. */
    static Object await(Suspending body) throws Exception {
        return await(body, VERSION_WAIT_MS, null);
    }

    /**
     * As {@link #await(Suspending)} with its own deadline. [stale] is polled while waiting; once it
     * is true the wait ends with a CancellationException and the result is dropped.
     */
    static Object await(Suspending body, long millis, java.util.function.BooleanSupplier stale)
            throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Object> result = new AtomicReference<>();
        Continuation<Object> completion =
                new Continuation<Object>() {
                    @Override
                    public CoroutineContext getContext() {
                        return EmptyCoroutineContext.INSTANCE;
                    }

                    @Override
                    public void resumeWith(Object value) {
                        result.set(value);
                        done.countDown();
                    }
                };
        Object returned = body.call(new Step(completion));
        if (returned != kotlin.coroutines.intrinsics.CoroutineSingletons.COROUTINE_SUSPENDED) {
            return lookupResult(returned);
        }
        long end = System.nanoTime() + millis * 1_000_000L;
        while (!done.await(Math.min(100L, millis), TimeUnit.MILLISECONDS)) {
            if (stale != null && stale.getAsBoolean())
                throw new java.util.concurrent.CancellationException("stale lookup");
            if (System.nanoTime() - end > 0) return null;
        }
        Object value = result.get();
        return lookupResult(value);
    }

    private static Object lookupResult(Object value) throws ReflectiveOperationException {
        if (value != null && value.getClass().getName().equals("kotlin.Result")) {
            Field payload = value.getClass().getDeclaredField("value");
            payload.setAccessible(true);
            value = payload.get(value);
        }
        return failed(value) ? null : value;
    }

    private static boolean failed(Object value) {
        return value != null && value.getClass().getName().equals("kotlin.Result$Failure");
    }

    /** The continuation BitChord's lookups resume; it passes their result straight on. */
    static final class Step extends ContinuationImpl {
        Step(Continuation<Object> completion) {
            super(completion);
        }

        @Override
        protected Object invokeSuspend(Object result) {
            return result;
        }
    }

    // ── The pill ────────────────────────────────────────────────────────────

    /** The landscape pair: a real child of the host's main pane Column, above its caption. */
    public static void header(Object composer) {
        try {
            initHeader();
            PlayerIcons.Ink ink = PlayerIcons.ink(composer);
            androidView.invoke(
                    null,
                    LANDSCAPE_FACTORY,
                    PlayerIcons.blended(headerModifier, ink),
                    headerUpdate(ink),
                    composer,
                    0,
                    0);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot embed player mode controls", failure);
        }
    }

    static Function1<View, Unit> headerUpdate(PlayerIcons.Ink ink) {
        return view -> {
            Header row = (Header) view;
            row.control.ink(ink);
            row.refresh();
            return Unit.INSTANCE;
        };
    }

    private static synchronized void initHeader() throws ReflectiveOperationException {
        if (androidView != null) return;
        Class<?> modifier = Class.forName("androidx.compose.ui.Modifier");
        Object fill =
                Class.forName("androidx.compose.foundation.layout.SizeKt")
                        .getField("FillWholeMaxWidth")
                        .get(null);
        Object layer =
                Class.forName("androidx.compose.ui.ZIndexElement")
                        .getConstructor(float.class)
                        .newInstance(1f);
        Object bounds = modifier.getMethod("then", modifier).invoke(fill, layer);
        Method found = null;
        for (Method method :
                Class.forName("androidx.compose.ui.viewinterop.AndroidView_androidKt").getMethods())
            if (method.getName().equals("AndroidView") && method.getParameterCount() == 6)
                found = method;
        if (found == null) throw new NoSuchMethodException("Player AndroidView ABI");
        headerModifier = bounds;
        androidView = found;
    }

    static final class Header extends FrameLayout {
        final Pill control;
        private final boolean captionBelow;

        Header(Context context) {
            this(context, false);
        }

        /** In landscape the caption follows the pair in the same column. */
        Header(Context context, boolean captionBelow) {
            super(context);
            this.captionBelow = captionBelow;
            setTag("player-mode-header");
            control = new Pill(context);
            addView(control, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            pill = new WeakReference<>(control);
            refresh();
        }

        void refresh() {
            control.refresh();
            gap();
        }

        /** Keeps the caption's enlarged touch target below the pair, and nothing when hidden. */
        void gap() {
            float density = getResources().getDisplayMetrics().density;
            float fontScale = getResources().getConfiguration().fontScale;
            int below =
                    captionBelow && control.getVisibility() == VISIBLE
                            ? (int) Math.ceil(PlayerIcons.captionGap(fontScale) * density)
                            : 0;
            if (below != getPaddingBottom()) setPadding(0, 0, 0, below);
        }
    }

    private static void refreshPill() {
        Pill shown = pill.get();
        if (shown != null) shown.refresh();
    }

    static boolean mainSurface() {
        try {
            Field field =
                    Class.forName("com.music.bitchord.data.settings.AppSettings")
                            .getDeclaredField("lastPlayerScreen");
            field.setAccessible(true);
            Object flow = field.get(null);
            Method read = flow.getClass().getMethod("getValue");
            read.setAccessible(true);
            Object screen = read.invoke(flow);
            return "MAIN".equals(String.valueOf(screen));
        } catch (ReflectiveOperationException unavailable) {
            return false;
        }
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context.getApplicationContext(), text, Toast.LENGTH_SHORT).show();
    }

    /** Two 52 × 48 dp targets; only icons are painted, while each exposes its own label. */
    static final class Pill extends LinearLayout {
        private final float density;
        private final Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG);
        private PlayerIcons.Ink ink = PlayerIcons.Ink.WHITE;
        private final Runnable tick = this::refresh;
        final ModeIcon audio, video;
        private boolean busy, chosenVideo;

        Pill(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            setOrientation(HORIZONTAL);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            audio = new ModeIcon(context, false);
            video = new ModeIcon(context, true);
            addView(
                    audio,
                    new LinearLayout.LayoutParams((int) (52 * density), (int) (48 * density)));
            addView(
                    video,
                    new LinearLayout.LayoutParams((int) (52 * density), (int) (48 * density)));
            setVisibility(GONE);
        }

        void tint(int color) {
            ink(new PlayerIcons.Ink(color, false));
        }

        void ink(PlayerIcons.Ink next) {
            ink = next;
            audio.invalidate();
            video.invalidate();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            post(tick);
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(tick);
            PlayerIcons.pairShown(false);
            super.onDetachedFromWindow();
        }

        void refresh() {
            removeCallbacks(tick);
            Object now = enabled ? current() : null;
            boolean show = mainSurface() && switchable(now);
            setVisibility(show ? VISIBLE : GONE);
            PlayerIcons.pairShown(show);
            if (getParent() instanceof Header) ((Header) getParent()).gap();
            busy = switching != null;
            boolean allowed = !busy && !inParty();
            audio.setEnabled(allowed);
            video.setEnabled(allowed);
            chosenVideo = shownVideo(now);
            audio.setSelected(!chosenVideo);
            video.setSelected(chosenVideo);
            String id = string(now, "videoId");
            audio.setAlpha(!allowed || missing.contains(id + "|false") ? 0.4f : 1f);
            video.setAlpha(!allowed || missing.contains(id + "|true") ? 0.4f : 1f);
            if (isAttachedToWindow() && getWindowVisibility() == VISIBLE) postDelayed(tick, 300);
        }

        private void choose(boolean wantVideo) {
            if (busy) return;
            if (inParty()) {
                toast(
                        getContext(),
                        Strings.t("Versions can’t be switched in a Listen Together party."));
                return;
            }
            setPreferredVideo(wantVideo);
            enforce(true);
        }

        final class ModeIcon extends View {
            final boolean isVideo;
            final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Path path = new Path();
            final RectF bounds = new RectF();

            ModeIcon(Context context, boolean isVideo) {
                super(context);
                this.isVideo = isVideo;
                String label = Strings.t(isVideo ? "Video" : "Audio");
                setContentDescription(label);
                setTooltipText(label);
                setTag(isVideo ? "player-mode-video" : "player-mode-audio");
                setFocusable(true);
                setClickable(true);
                icon.setStyle(Paint.Style.STROKE);
                icon.setStrokeCap(Paint.Cap.ROUND);
                icon.setStrokeJoin(Paint.Join.ROUND);
                icon.setStrokeWidth(2 * density);
                setOnClickListener(view -> choose(isVideo));
            }

            @Override
            public void onInitializeAccessibilityNodeInfo(
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                info.setClassName("android.widget.RadioButton");
                info.setCheckable(true);
                info.setChecked(chosenVideo == isVideo);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                boolean lit = isSelected();
                // Lit, as the player's own glyphs are: the glyph, then the tile cut by it.
                int layer = lit ? canvas.saveLayer(0, 0, getWidth(), getHeight(), null) : 0;
                icon.setColor(ink.glyph(lit));
                glyph(canvas);
                if (lit) {
                    PlayerIcons.litTile(
                            canvas, getWidth() / 2f, getHeight() / 2f, density, ink.tile(), tile);
                    canvas.restoreToCount(layer);
                }
            }

            private void glyph(Canvas canvas) {
                float cx = getWidth() / 2f, cy = getHeight() / 2f;
                if (isVideo) {
                    float wd = 9 * density, ht = 6.5f * density;
                    bounds.set(cx - wd, cy - ht, cx + wd, cy + ht);
                    canvas.drawRoundRect(bounds, 2 * density, 2 * density, icon);
                    path.reset();
                    path.moveTo(cx - 2.5f * density, cy - 3.5f * density);
                    path.lineTo(cx + 3.5f * density, cy);
                    path.lineTo(cx - 2.5f * density, cy + 3.5f * density);
                    path.close();
                    icon.setStyle(Paint.Style.FILL);
                    canvas.drawPath(path, icon);
                    icon.setStyle(Paint.Style.STROKE);
                } else {
                    float size = 8 * density;
                    bounds.set(cx - size, cy - size, cx + size, cy + size);
                    path.reset();
                    path.addArc(bounds, 180, 180);
                    canvas.drawPath(path, icon);
                    canvas.drawLine(cx - size, cy, cx - size, cy + size * 0.55f, icon);
                    canvas.drawLine(cx + size, cy, cx + size, cy + size * 0.55f, icon);
                    icon.setStyle(Paint.Style.FILL_AND_STROKE);
                    bounds.set(
                            cx - size - density,
                            cy + size * 0.05f,
                            cx - size * 0.45f,
                            cy + size * 0.9f);
                    canvas.drawRoundRect(bounds, density, density, icon);
                    bounds.set(
                            cx + size * 0.45f,
                            cy + size * 0.05f,
                            cx + size + density,
                            cy + size * 0.9f);
                    canvas.drawRoundRect(bounds, density, density, icon);
                    icon.setStyle(Paint.Style.STROKE);
                }
            }
        }
    }

    // ── Reflection helpers ──────────────────────────────────────────────────

    private static Method connection(String name) throws Exception {
        return Class.forName(CONNECTION)
                .getMethod(name, Class.forName("androidx.media3.common.MediaItem"));
    }

    private static Object flowValue(Object flow) throws Exception {
        if (flow == null) return null;
        for (Class<?> type = flow.getClass(); type != null; type = type.getSuperclass())
            for (Field field : type.getDeclaredFields())
                if (field.getName().equals("$$delegate_0")) {
                    field.setAccessible(true);
                    return flowValue(field.get(flow));
                }
        return flow.getClass().getMethod("getValue").invoke(flow);
    }

    static String string(Object song, String name) {
        try {
            Field field = song.getClass().getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(song);
            return value instanceof String ? (String) value : null;
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    static boolean bool(Object song, String name) {
        try {
            Field field = song.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getBoolean(song);
        } catch (Exception | LinkageError e) {
            return false;
        }
    }
}
