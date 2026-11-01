package app.bitchord.telegram;

import android.media.MediaDataSource;
import android.net.Uri;
import android.os.Process;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSpec;

import kotlin.jvm.functions.Function0;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/** All source types use the same host analyzer; this runtime never selects audible playback. */
public final class SourceAutomix {
    private SourceAutomix() {}

    private static volatile WeakReference<Object> service = new WeakReference<>(null);
    private static final ThreadLocal<Job> active = new ThreadLocal<>();
    private static final ThreadLocal<Integer> quiet = new ThreadLocal<>();
    private static final Map<Object, Map<String, Job>> jobs =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<String, DataSpec> pinned = new ConcurrentHashMap<>();
    private static final Map<String, Uri> observed = new ConcurrentHashMap<>();
    private static final Map<Object, Integer> modelUsers = new WeakHashMap<>();
    private static final long BUDGET_NANOS = 90_000_000_000L;

    static final class Job {
        final Object service, analyzer;
        final String id, rendition, storeKey;
        final Uri uri;
        final double duration;
        volatile DataSpec spec;
        volatile boolean running, done, cancelled;
        volatile int attempts;
        volatile long retryAfter;
        long deadline;

        Job(Object service, Object analyzer, String id, Uri uri, double duration, String key) {
            this.service = service;
            this.analyzer = analyzer;
            this.id = id;
            this.uri = uri;
            this.duration = duration;
            this.rendition = key;
            this.storeKey = "automix17|" + id + "|" + key + "|" + Math.round(duration * 1000);
        }
    }

    public static void service(Object value) {
        service = new WeakReference<>(value);
    }

    public static void settingChanged() {
        SoundExtras.effectsChanged();
    }

    public static void release(Object owner) {
        synchronized (jobs) {
            jobs.entrySet()
                    .removeIf(
                            entry -> {
                                boolean remove = false;
                                for (Job job : entry.getValue().values())
                                    if (job.service == owner) {
                                        job.cancelled = true;
                                        remove = true;
                                    }
                                return remove;
                            });
        }
        if (service.get() == owner) {
            TransitionRuntime.release();
            service.clear();
            pinned.clear();
            observed.clear();
        }
    }

    static Object owner() {
        return service.get();
    }

    static Uri observed(String id) {
        return observed.get(id);
    }

    public static boolean applied() {
        return false;
    }

    static boolean activeMix() {
        return applied() && AutomixAccess.enabled();
    }

    static boolean ownsResolver(Object value) {
        Object owner = service.get();
        if (owner == null || value == null) return false;
        try {
            Object target = field(owner, "streamResolver");
            return value == target || field(value, "f$0") == target || field(value, "f$0") == owner;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean quiet() {
        Integer n = quiet.get();
        return n != null && n > 0;
    }

    static void enterQuiet() {
        Integer n = quiet.get();
        quiet.set(n == null ? 1 : n + 1);
    }

    static void leaveQuiet() {
        Integer n = quiet.get();
        if (n == null || n <= 1) quiet.remove();
        else quiet.set(n - 1);
    }

    static Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(object);
    }

    static void set(Object object, String name, Object value) throws Exception {
        Field f = object.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(object, value);
    }

    static Object invoke(Object object, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method m = object.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(object, args);
    }

    static String identity(Uri uri) {
        String key = AutomixAccess.key(uri);
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            File f = new File(uri.getPath());
            key += "|" + f.length() + "|" + f.lastModified();
        }
        return key == null ? uri.toString() : key;
    }

    /** Called only for the request-analysis arm of the optimized callback. No I/O on its caller. */
    public static boolean request(Object callback, Object value, Object runtime) {
        if (!(value instanceof MediaItem) || !(runtime instanceof Long)) return false;
        MediaItem item = (MediaItem) value;
        if (item.localConfiguration == null || item.mediaId == null || item.mediaId.isEmpty())
            return true;
        try {
            Object owner = field(callback, "f$0");
            service(owner);
            Object analyzer = field(owner, "trackAnalyzer");
            if (AutomixAccess.stopped()) return true;
            Map<String, Job> state;
            synchronized (jobs) {
                state = jobs.get(analyzer);
                if (state == null) {
                    state = new ConcurrentHashMap<>();
                    jobs.put(analyzer, state);
                }
            }
            Uri uri = SourceFirst.renditionUri(item);
            if (observed.size() > 128) observed.clear();
            observed.put(item.mediaId, uri);
            String key = identity(uri);
            Job old;
            synchronized (state) {
                old = state.get(item.mediaId);
                if (old == null || !old.rendition.equals(key)) {
                    if (old != null) old.cancelled = true;
                    Job replacement =
                            new Job(
                                    owner,
                                    analyzer,
                                    item.mediaId,
                                    uri,
                                    ((Long) runtime) / 1000.0,
                                    key);
                    state.put(item.mediaId, replacement);
                    ((Map<?, ?>) field(analyzer, "results")).remove(item.mediaId);
                    old = replacement;
                    // Only current/upcoming tracks are requested; bound long-session bookkeeping.
                    if (state.size() > 128)
                        state.entrySet()
                                .removeIf(
                                        e ->
                                                !e.getValue().running
                                                        && !e.getKey().equals(item.mediaId));
                }
            }
            Job job = old;
            if (job.done || job.running || job.attempts >= 3 || System.nanoTime() < job.retryAfter)
                return true;
            if (((Number) field(analyzer, "thermalStatus")).intValue() >= 3) return true;
            ((Map<String, Uri>) field(analyzer, "sourceAnalysisUris")).put(item.mediaId, uri);
            Set<String> running = (Set<String>) field(analyzer, "running");
            if (!running.add(item.mediaId)) return true;
            job.running = true;
            Map<String, Job> current = state;
            try {
                ((ExecutorService) field(analyzer, "executor"))
                        .execute(() -> analyze(job, current, running));
            } catch (RuntimeException error) {
                job.running = false;
                running.remove(item.mediaId);
                throw error;
            }
        } catch (Throwable error) {
            // A failed analysis must never return to the source-specific YouTube path.
            HostLog.d("AutoMix analysis request unavailable: " + error.getClass().getSimpleName());
        }
        return true;
    }

    private static void analyze(Job job, Map<String, Job> current, Set<String> running) {
        Object[] models = null;
        active.set(job);
        enterQuiet();
        job.deadline = System.nanoTime() + BUDGET_NANOS;
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            if (AutomixAccess.stopped() || job.cancelled || current.get(job.id) != job) return;
            invoke(field(job.analyzer, "thermalWatch$delegate"), "getValue", new Class<?>[0]);
            if (((Number) field(job.analyzer, "thermalStatus")).intValue() >= 3) return;
            job.attempts++;
            Map<String, Object> results = (Map<String, Object>) field(job.analyzer, "results");
            AutomixAccess.restore(job.analyzer, job.storeKey);
            Object result = results.remove(job.storeKey);
            if (result == null) {
                models = useModels(job.analyzer);
                Object outcome =
                        invoke(
                                job.analyzer,
                                "analyze",
                                new Class<?>[] {String.class, Uri.class, double.class},
                                job.id,
                                job.uri,
                                job.duration);
                result = outcome == null ? null : field(outcome, "flags");
            }
            if (result == null
                    || !"com.music.bitchord.playback.smart.TrackAnalysis"
                            .equals(result.getClass().getName())
                    || ((Number) field(result, "bpm")).doubleValue() <= 0) return;
            if (current.get(job.id) != job
                    || !job.rendition.equals(identity(job.uri))
                    || AutomixAccess.stopped()) return;
            synchronized (current) {
                if (job.cancelled || current.get(job.id) != job) return;
                set(result, "trackId", job.id);
                results.put(job.id, result);
            }
            Object store = field(job.analyzer, "store");
            invoke(
                    store,
                    "save",
                    new Class<?>[] {String.class, result.getClass()},
                    job.storeKey,
                    result);
            job.done = true;
            HostLog.d("AutoMix measured selected audio; source=" + job.uri.getScheme());
        } catch (Throwable error) {
            HostLog.d(
                    "AutoMix will use a basic fade until audio can be measured: "
                            + error.getClass().getSimpleName());
        } finally {
            finishModels(models);
            job.retryAfter = System.nanoTime() + 15_000_000_000L;
            job.running = false;
            running.remove(job.id);
            active.remove();
            leaveQuiet();
            try {
                invoke(field(job.analyzer, "tracker"), "release", new Class<?>[0]);
            } catch (Exception ignored) {
            }
            try {
                invoke(field(job.analyzer, "vocals"), "release", new Class<?>[0]);
            } catch (Exception ignored) {
            }
        }
    }

    /** Called under the host model's own lock, before its native session is closed. */
    public static boolean deferModelRelease(Object model) {
        synchronized (modelUsers) {
            return modelUsers.getOrDefault(model, 0) > 0;
        }
    }

    private static Object[] useModels(Object analyzer) throws Exception {
        Object tracker = field(analyzer, "tracker"), vocals = field(analyzer, "vocals");
        Object trackerLock = field(tracker, "lock"), vocalLock = field(vocals, "lock");
        synchronized (trackerLock) {
            synchronized (modelUsers) {
                modelUsers.put(tracker, modelUsers.getOrDefault(tracker, 0) + 1);
            }
        }
        synchronized (vocalLock) {
            synchronized (modelUsers) {
                modelUsers.put(vocals, modelUsers.getOrDefault(vocals, 0) + 1);
            }
        }
        return new Object[] {tracker, vocals};
    }

    private static void finishModels(Object[] models) {
        if (models == null) return;
        for (Object model : models) {
            try {
                synchronized (field(model, "lock")) {
                    synchronized (modelUsers) {
                        int users = modelUsers.getOrDefault(model, 0) - 1;
                        if (users <= 0) modelUsers.remove(model);
                        else modelUsers.put(model, users);
                    }
                }
            } catch (Exception unavailable) {
                HostLog.d("AutoMix model ownership could not be released");
            }
        }
    }

    static DataSpec resolve(Uri uri) throws Exception {
        Object owner = service.get();
        if (owner == null) throw new java.io.IOException("Playback service is not ready");
        DataSpec resolved = AutomixAccess.resolve(owner, uri);
        return SourcePreference.guard(new DataSpec(uri), resolved);
    }

    public static String copyKey(Uri uri) {
        return identity(uri);
    }

    public static Function0<MediaDataSource> opener(Uri uri) {
        return () -> {
            enterQuiet();
            try {
                MediaDataSource cached = AutomixAccess.cached(uri);
                if (cached != null) return cached;
                Job job = active.get();
                DataSpec spec;
                if (job != null && uri.equals(job.uri)) {
                    if (job.spec == null) job.spec = resolve(uri);
                    spec = job.spec;
                    if (pinned.size() > 128) pinned.clear();
                    pinned.put(job.rendition, spec);
                } else {
                    String key = identity(uri);
                    spec = pinned.get(key);
                    if (spec == null) {
                        spec = resolve(uri);
                        if (pinned.size() > 128) pinned.clear();
                        pinned.put(key, spec);
                    }
                }
                return new SourceAudio(
                        spec,
                        job == null ? System.nanoTime() + BUDGET_NANOS : job.deadline,
                        () -> job != null && job.cancelled);
            } catch (Exception error) {
                return null;
            } finally {
                leaveQuiet();
            }
        };
    }

    /** Preserve the listener's chosen speed and pitch on both transition decks. */
    public static void speed(Player player, float ignored) {
        float base = AutomixAccess.speed();
        if (!Float.isFinite(base) || base <= 0) base = 1f;
        PlaybackParameters previous = player.getPlaybackParameters();
        float pitch = previous == null ? 1f : previous.pitch;
        player.setPlaybackParameters(new PlaybackParameters(base, pitch));
    }

    /** A tempo-neutral plan keeps cueing/filtering while disabling the rate/phase servo. */
    public static void plan(Object plan) {
        try {
            set(plan, "incomingPlaybackRate", 1d);
            set(plan, "outgoingPlaybackRate", 1d);
            set(plan, "phaseLock", false);
            TransitionRuntime.apply(plan);
        } catch (Exception error) {
            throw new IllegalStateException("AutoMix plan ABI changed", error);
        }
    }
}
