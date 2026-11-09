package app.bitchord.telegram;

import android.content.Context;
import android.media.MediaDataSource;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSpec;

import kotlin.jvm.functions.Function0;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;

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
    private static final ThreadLocal<Capture> captured = new ThreadLocal<>();
    // At most one queue item beyond the next one is measured ahead; `generation` ends its claim.
    private static volatile Job lookahead;
    private static volatile Want want;
    private static final AtomicLong generation = new AtomicLong();
    private static final AtomicBoolean posted = new AtomicBoolean();
    // Cancelled speculative jobs whose worker has not yet released the native running id.
    private static final Map<String, Job> draining = new ConcurrentHashMap<>();
    private static final int SNAPSHOTS = 128;
    // A snapshot is served until FRESH and refreshed in the background from REFRESH on.
    private static final long REFRESH = 500, FRESH = 1000;
    // Length and modified time per local file; guarded by `facts`, as are pendingFacts and replays.
    private static final Map<String, Fact> facts =
            new LinkedHashMap<String, Fact>(16, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Fact> eldest) {
                    return size() > SNAPSHOTS;
                }
            };
    private static final Map<String, CompletableFuture<String>> pendingFacts = new HashMap<>();
    private static final Map<String, Replay> replays =
            new LinkedHashMap<String, Replay>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Replay> eldest) {
                    return size() > SNAPSHOTS;
                }
            };
    private static final ThreadPoolExecutor identityWorker = identityWorker();

    /**
     * Reads "length|modified" for a local file; replaceable so tests can watch the calling thread.
     */
    static Function<File, String> statReader = f -> f.length() + "|" + f.lastModified();

    /** Monotonic milliseconds; replaceable so tests need not wait out the freshness window. */
    static LongSupplier identityClock = SystemClock::elapsedRealtime;

    private static final long BUDGET_NANOS = 90_000_000_000L;
    private static final int ATTEMPTS = 3;
    static LongSupplier heapMaximum = () -> Runtime.getRuntime().maxMemory();
    static LongSupplier heapUsed =
            () -> Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

    static final class Job {
        final Object service, analyzer;
        final String id, rendition, storeKey;
        final Uri uri;
        final double duration;
        volatile DataSpec spec;
        SourceAudio.AnalysisInfo format;
        boolean cachedOnly;
        volatile boolean running, done, cancelled;
        // Measured ahead of need; a foreground request for the same id and rendition adopts it.
        volatile boolean speculative;
        volatile long token;
        // The queue item's own location, before any source choice rewrote it.
        volatile Uri source;
        // Run once when the worker has released the running id of a cancelled speculative job.
        final AtomicReference<Runnable> resume = new AtomicReference<>();
        // The previous job of this recording owned another rendition: no plain-id cache applies.
        volatile boolean superseded;
        // Ready through a native result, not through a measurement of this exact source.
        volatile boolean adopted;
        volatile int attempts;
        volatile long retryAfter;
        // Stopped for playback priority, not for a fault; and memory waits in a row, for backoff.
        volatile boolean yielded;
        volatile int deferrals;
        // Bytes the last memory wait lacked: a release wakes the job only when this now fits.
        volatile long memoryNeeded;
        // The first wait of an episode may ask once whether the leased allowance now admits it.
        volatile boolean leasedRetry;
        // Set by that one wake: the next try checks the claim itself, not the unleased estimate.
        volatile boolean skipPreview;
        volatile String phase = "queued";
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

    /** The current and next ids a speculative job was started behind, under one generation. */
    private static final class Want {
        final long token;
        final Job current, next;

        Want(long token, Job current, Job next) {
            this.token = token;
            this.current = current;
            this.next = next;
        }
    }

    /** A speculative job that stepped aside before using an attempt of its own. */
    private static final class Skipped extends RuntimeException {
        Skipped() {
            super(null, null, false, false);
        }
    }

    /** A native cold load kept private until the worker has validated it. */
    private static final class Capture {
        final Map<?, ?> results;
        final String id;
        volatile Object value;

        Capture(Map<?, ?> results, String id) {
            this.results = results;
            this.id = id;
        }
    }

    private static final class Replay {
        final String uri;
        final Runnable run;

        Replay(String uri, Runnable run) {
            this.uri = uri;
            this.run = run;
        }
    }

    /** A local file's length and modified time have not been read off the main thread yet. */
    static final class IdentityPending extends IllegalStateException {
        final Uri uri;

        IdentityPending(Uri uri) {
            super("File identity is not captured yet");
            this.uri = uri;
        }
    }

    /** Length and modified time of a local file, with the monotonic time they were read. */
    private static final class Fact {
        final String value;
        final long at;

        Fact(String value, long at) {
            this.value = value;
            this.at = at;
        }
    }

    private static ThreadPoolExecutor identityWorker() {
        ThreadPoolExecutor pool =
                new ThreadPoolExecutor(
                        1,
                        1,
                        5,
                        TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(SNAPSHOTS),
                        task -> {
                            Thread thread = new Thread(task, "bitchord-identity");
                            thread.setDaemon(true);
                            thread.setPriority(Thread.MIN_PRIORITY);
                            return thread;
                        });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    public static void service(Object value) {
        Object previous = service.get();
        service = new WeakReference<>(value);
        if (previous != value) {
            cancelSpeculation();
            // Observed renditions belong to the owner that saw them.
            observed.clear();
            synchronized (facts) {
                replays.clear();
            }
        }
    }

    /**
     * The native cold load publishes through here. Outside a worker capture this is the plain
     * putIfAbsent; during one the value stays private until the worker has checked it.
     */
    public static Object restored(Map<Object, Object> results, Object id, Object value) {
        Capture capture = captured.get();
        if (capture != null && capture.results == results && capture.id.equals(id)) {
            capture.value = value;
            return null;
        }
        return results.putIfAbsent(id, value);
    }

    public static void settingChanged() {
        SoundExtras.effectsChanged();
    }

    public static String mixLabel(String original) {
        return AutomixAccess.mixLabel(original);
    }

    public static void release(Object owner) {
        Job spec = lookahead;
        if (spec != null && spec.service == owner) {
            generation.incrementAndGet();
            lookahead = null;
            want = null;
        }
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
            synchronized (facts) {
                replays.clear();
            }
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

    private static boolean isFile(Uri uri) {
        return uri != null && "file".equalsIgnoreCase(uri.getScheme());
    }

    private static boolean onMain() {
        Looper main = Looper.getMainLooper();
        return main != null && main.getThread() == Thread.currentThread();
    }

    private static String baseKey(Uri uri) {
        String base = AutomixAccess.key(uri);
        return base == null ? uri.toString() : base;
    }

    /**
     * Never reads a file. Other schemes give the normalized key. A local file gives the full
     * key|length|modified snapshot while it is under a second old, asking a worker for a new one
     * from half a second on, and null when it is missing or older.
     */
    static String cachedIdentity(Uri uri) {
        return identityFor(uri, null, null);
    }

    private static String identityFor(Uri uri, String mediaId, Runnable replay) {
        if (uri == null) return null;
        String base = baseKey(uri);
        if (!isFile(uri)) return base;
        Fact known;
        synchronized (facts) {
            known = facts.get(uri.toString());
        }
        long age = known == null ? Long.MAX_VALUE : identityClock.getAsLong() - known.at;
        if (age >= REFRESH) prepare(uri, mediaId, replay);
        return age >= FRESH ? null : base + "|" + known.value;
    }

    /** The key of the newest captured fact for a file; no file access. */
    private static String newestKey(Uri uri, String key) {
        if (!isFile(uri)) return key;
        Fact known;
        synchronized (facts) {
            known = facts.get(uri.toString());
        }
        return known == null ? key : baseKey(uri) + "|" + known.value;
    }

    private static String statIdentity(Uri uri) {
        String fresh = statReader.apply(new File(uri.getPath()));
        long at = identityClock.getAsLong();
        synchronized (facts) {
            facts.put(uri.toString(), new Fact(fresh, at));
        }
        return baseKey(uri) + "|" + fresh;
    }

    /**
     * Reads the file facts on the metadata worker. Calls for one file share a future; at most
     * SNAPSHOTS files are pending, and a refused one fails its future.
     */
    static CompletableFuture<String> prepareIdentity(Uri uri) {
        return prepare(uri, null, null);
    }

    private static CompletableFuture<String> prepare(Uri uri, String mediaId, Runnable replay) {
        if (uri == null) {
            CompletableFuture<String> none = new CompletableFuture<>();
            none.completeExceptionally(new IllegalArgumentException("No uri"));
            return none;
        }
        if (!isFile(uri)) return CompletableFuture.completedFuture(baseKey(uri));
        String key = uri.toString();
        CompletableFuture<String> future;
        boolean start = false;
        synchronized (facts) {
            future = pendingFacts.get(key);
            if (future == null) {
                future = new CompletableFuture<>();
                if (pendingFacts.size() >= SNAPSHOTS) {
                    future.completeExceptionally(new RejectedExecutionException("Too many files"));
                    return future;
                }
                pendingFacts.put(key, future);
                start = true;
            }
            // One replay per recording: a new request replaces the one waiting.
            if (replay != null) replays.put(mediaId, new Replay(key, replay));
        }
        if (start) {
            CompletableFuture<String> job = future;
            try {
                identityWorker.execute(() -> readFacts(uri, key, job));
            } catch (RejectedExecutionException refused) {
                synchronized (facts) {
                    pendingFacts.remove(key);
                    dropReplays(key);
                }
                job.completeExceptionally(refused);
            }
        }
        return future;
    }

    private static void dropReplays(String key) {
        for (Iterator<Replay> it = replays.values().iterator(); it.hasNext(); )
            if (it.next().uri.equals(key)) it.remove();
    }

    private static void readFacts(Uri uri, String key, CompletableFuture<String> future) {
        try {
            String fresh = statReader.apply(new File(uri.getPath()));
            // Stamped when the read has finished, so a slow read is not already aged.
            long at = identityClock.getAsLong();
            List<Runnable> again = new ArrayList<>();
            synchronized (facts) {
                Fact previous = facts.put(key, new Fact(fresh, at));
                // A first capture, a changed file or an expired snapshot re-runs the waiting
                // requests; a snapshot that was still valid and is unchanged needs no replay.
                boolean changed =
                        previous == null
                                || !previous.value.equals(fresh)
                                || at - previous.at >= FRESH;
                for (Iterator<Replay> it = replays.values().iterator(); it.hasNext(); ) {
                    Replay replay = it.next();
                    if (!replay.uri.equals(key)) continue;
                    it.remove();
                    if (changed) again.add(replay.run);
                }
            }
            // The full fingerprint is published before any replay, which may analyse inline.
            future.complete(baseKey(uri) + "|" + fresh);
            for (Runnable replay : again) {
                try {
                    replay.run();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable error) {
            synchronized (facts) {
                dropReplays(key);
            }
            future.completeExceptionally(error);
        } finally {
            synchronized (facts) {
                pendingFacts.remove(key);
            }
        }
    }

    /**
     * The main thread gets a snapshot or IdentityPending and never reads the file, whatever job it
     * is inside; any other thread reads the file and refreshes the snapshot.
     */
    static String identity(Uri uri) {
        if (!isFile(uri)) return baseKey(uri);
        if (!onMain()) return statIdentity(uri);
        String known = cachedIdentity(uri);
        if (known == null) throw new IdentityPending(uri);
        return known;
    }

    /** The job's rendition against the file as it is now; read on the analysis worker. */
    private static boolean renditionCurrent(Job job) {
        return job.rendition.equals(identity(job.uri));
    }

    /** Called only for the request-analysis arm of the optimized callback. No I/O on its caller. */
    public static boolean request(Object callback, Object value, Object runtime) {
        if (!(value instanceof MediaItem) || !(runtime instanceof Long)) return false;
        try {
            return enqueue(field(callback, "f$0"), (MediaItem) value, (Long) runtime, false);
        } catch (Throwable unavailable) {
            HostLog.d(
                    "AutoMix analysis request unavailable: "
                            + unavailable.getClass().getSimpleName());
            return true;
        } finally {
            // The native pair is requested in one call; the snapshot runs after both.
            post();
        }
    }

    /** The host rebuilt its read-ahead for the queue. Called on the player's thread. */
    public static void queueChanged(Object owner, Object player) {
        // The first native request names the owner; another service or a stray player is ignored.
        if (owner == null || owner != service.get() || !(player instanceof Player)) return;
        try {
            if (TransitionRuntime.pcall(player, "getApplicationLooper", new Class<?>[0])
                    != Looper.myLooper()) return;
        } catch (Exception unavailable) {
            return;
        }
        try {
            playbackNeeds(player);
        } catch (Throwable unavailable) {
            // Priority is advisory: without it analysis only waits less.
        }
        post();
    }

    /**
     * How long the current song needs the device, from the player's own state: buffering, or
     * playing with almost nothing buffered ahead. Zero means no urgency. Pure, for tests.
     */
    static long urgentMillis(int state, boolean playWhenReady, long bufferedAheadMs) {
        if (!playWhenReady) return 0;
        // Player.STATE_BUFFERING is 2 and STATE_READY is 3.
        if (state == 2) return 5_000;
        return state == 3 && bufferedAheadMs >= 0 && bufferedAheadMs < 3_000 ? 4_000 : 0;
    }

    /** Called on the player's thread, where the current song's state is known. */
    private static void playbackNeeds(Object player) throws Exception {
        Class<?>[] none = new Class<?>[0];
        boolean wanted =
                Boolean.TRUE.equals(TransitionRuntime.pcall(player, "getPlayWhenReady", none));
        int state = number(TransitionRuntime.pcall(player, "getPlaybackState", none));
        long ahead =
                ((Number) TransitionRuntime.pcall(player, "getBufferedPosition", none)).longValue()
                        - ((Number) TransitionRuntime.pcall(player, "getCurrentPosition", none))
                                .longValue();
        long millis = urgentMillis(state, wanted, ahead);
        if (millis > 0) PlaybackPriority.playbackUrgent(millis);
    }

    /** The host dropped its read-ahead (pause, stop, new queue): only speculation is cancelled. */
    public static void cancelLookahead(Object owner) {
        Job spec = lookahead;
        if (spec != null && spec.service == owner) cancelSpeculation();
    }

    /** Whether playback, an upgrade or next-song preparation currently outranks this analysis. */
    private static boolean yieldsToPlayback(Job job) {
        boolean yields = !allowed(job);
        if (yields) job.yielded = true;
        return yields;
    }

    /** Current and next analysis (no token) is essential and cannot be starved indefinitely. */
    private static boolean allowed(Job job) {
        return PlaybackPriority.analysisAllowed(job.id, job.token == 0);
    }

    /** Asks again shortly; used when priority, not a fault, stopped an analysis. */
    private static void postLater() {
        Looper main = Looper.getMainLooper();
        if (main != null) new Handler(main).postDelayed(SourceAutomix::post, 1_500);
    }

    /** A memory wait grows 15 s, 30 s, 60 s... up to five minutes, so it isn't retried blindly. */
    static long retryDelayNanos(int deferrals) {
        return Math.min(300_000_000_000L, 15_000_000_000L << Math.min(Math.max(deferrals, 0), 5));
    }

    /** A memory wait retries after 2 s, then 4, 8, 16, 32 and at most every minute. */
    static long memoryDelayNanos(int deferrals) {
        return Math.min(60_000_000_000L, 2_000_000_000L << Math.min(Math.max(deferrals - 1, 0), 5));
    }

    /**
     * Called when decode or measurement memory is released; wakes memory waits that now fit, so a
     * job does not sit out its backoff once the heap has room.
     */
    public static void memoryAvailable() {
        long maximum = heapMaximum.getAsLong(), used = heapUsed.getAsLong();
        long room =
                Math.max(
                        SourceAudio.dspAvailable(maximum, used),
                        SourceAudio.analysisAvailable(maximum, used));
        boolean woke = false;
        synchronized (jobs) {
            for (Map<String, Job> byId : jobs.values())
                for (Job job : byId.values())
                    if ("memory".equals(job.phase) && !job.running && job.retryAfter != 0) {
                        boolean fits = room >= job.memoryNeeded;
                        if (!fits && !job.leasedRetry) continue;
                        if (!fits) {
                            job.leasedRetry = false;
                            job.skipPreview = true;
                        }
                        job.retryAfter = 0;
                        woke = true;
                    }
        }
        if (woke) post();
    }

    // Lets tests pin the bounded fallback; the leased model path is otherwise always allowed.
    static boolean leasedModels = true;

    private static final AtomicBoolean wakePending = new AtomicBoolean(),
            listening = new AtomicBoolean();

    /** Clears a wake whose post died with its looper. */
    static void forgetWake() {
        wakePending.set(false);
    }

    // Stable identity so the memory ledger registers it once; it only schedules work.
    private static final Runnable memoryWake =
            () -> {
                if (!wakePending.compareAndSet(false, true)) return;
                Looper main = Looper.getMainLooper();
                if (main == null
                        || !new Handler(main)
                                .post(
                                        () -> {
                                            wakePending.set(false);
                                            memoryAvailable();
                                        })) wakePending.set(false);
            };

    private static void post() {
        if (!posted.compareAndSet(false, true)) return;
        Looper main = Looper.getMainLooper();
        if (main == null || !new Handler(main).post(SourceAutomix::snapshot)) posted.set(false);
    }

    /** Ends the speculative claim. A finished measurement stays; unfinished work is cancelled. */
    static void cancelSpeculation() {
        generation.incrementAndGet();
        want = null;
        Job spec = lookahead;
        lookahead = null;
        if (spec == null || spec.done) return;
        // The job stays in its map: it may already have published a result for its source, and
        // the next request for the id must replace it, never start from nothing.
        spec.cancelled = true;
        if (spec.running) {
            draining.put(spec.id, spec);
            if (!spec.running) draining.remove(spec.id, spec);
        }
    }

    // The Transition editor screen on show, held weakly so a lost screen cannot block work.
    private static final AtomicReference<WeakReference<Object>> editorScreen =
            new AtomicReference<>();

    /** The editor reports its visibility; only the screen on show can report it gone. */
    public static void editor(Object screen, boolean visible) {
        if (screen == null) return;
        if (visible) {
            editorScreen.set(new WeakReference<>(screen));
            cancelSpeculation();
            return;
        }
        WeakReference<Object> shown = editorScreen.get();
        if (shown != null && shown.get() == screen) editorScreen.compareAndSet(shown, null);
        post();
    }

    private static boolean editorVisible() {
        WeakReference<Object> shown = editorScreen.get();
        return shown != null && shown.get() != null;
    }

    /** Whether the player's preview deck is using audio; the crossfade controller names it. */
    private static boolean previewHeld(Object service) {
        try {
            Object controller = TransitionRuntime.field(service, "crossfade");
            return controller != null && TransitionPreview.holding(controller);
        } catch (Exception missing) {
            return false;
        }
    }

    private static boolean specValid(Job job) {
        return lookahead == job
                && !job.cancelled
                && job.token == generation.get()
                && !editorVisible();
    }

    /** A measured, or finally given up, outcome; memory and thermal waits are not settled. */
    private static boolean settled(Job job) {
        if (job == null || job.cancelled) return false;
        if (job.done) return true;
        return !job.running
                && job.attempts >= ATTEMPTS
                && ("unavailable".equals(job.phase) || "failed".equals(job.phase));
    }

    /** Runs on the application looper, after the native current and next requests. */
    private static void snapshot() {
        posted.set(false);
        try {
            startLookahead();
        } catch (Throwable unavailable) {
            cancelSpeculation();
            HostLog.d("AutoMix lookahead skipped: " + unavailable.getClass().getSimpleName());
        }
    }

    private static int number(Object value) {
        return ((Number) value).intValue();
    }

    private static void startLookahead() throws Exception {
        Object owner = service.get();
        if (owner == null
                || !AutomixAccess.enabled()
                || AutomixAccess.stopped()
                || editorVisible()) {
            cancelSpeculation();
            return;
        }
        Object player = TransitionRuntime.player();
        Class<?>[] none = new Class<?>[0];
        // The queue is read only on the thread that owns the player.
        if (TransitionRuntime.pcall(player, "getApplicationLooper", none) != Looper.myLooper()
                || !Boolean.TRUE.equals(TransitionRuntime.pcall(player, "isPlaying", none))) {
            cancelSpeculation();
            return;
        }
        int count = number(TransitionRuntime.pcall(player, "getMediaItemCount", none));
        int now = number(TransitionRuntime.pcall(player, "getCurrentMediaItemIndex", none));
        int next = number(TransitionRuntime.pcall(player, "getNextMediaItemIndex", none));
        if (now < 0 || next < 0 || now >= count || next >= count) {
            cancelSpeculation();
            return;
        }
        // Repeat-one does not change what follows the next item.
        int repeat = number(TransitionRuntime.pcall(player, "getRepeatMode", none));
        boolean shuffle =
                Boolean.TRUE.equals(TransitionRuntime.pcall(player, "getShuffleModeEnabled", none));
        Object timeline = TransitionRuntime.pcall(player, "getCurrentTimeline", none);
        int later =
                number(
                        Class.forName("androidx.media3.common.Timeline")
                                .getMethod(
                                        "getNextWindowIndex", int.class, int.class, boolean.class)
                                .invoke(timeline, next, repeat == 1 ? 0 : repeat, shuffle));
        if (later < 0 || later >= count || later == now || later == next) {
            cancelSpeculation();
            return;
        }
        Class<?>[] index = {int.class};
        MediaItem first = (MediaItem) TransitionRuntime.pcall(player, "getMediaItemAt", index, now);
        MediaItem second =
                (MediaItem) TransitionRuntime.pcall(player, "getMediaItemAt", index, next);
        MediaItem item =
                (MediaItem) TransitionRuntime.pcall(player, "getMediaItemAt", index, later);
        if (item == null
                || first == null
                || second == null
                || item.localConfiguration == null
                || item.mediaId == null
                || item.mediaId.isEmpty()
                || item.mediaId.equals(first.mediaId)
                || item.mediaId.equals(second.mediaId)) {
            cancelSpeculation();
            return;
        }
        Object analyzer = field(owner, "trackAnalyzer");
        Map<String, Job> state;
        synchronized (jobs) {
            state = jobs.get(analyzer);
        }
        Job here = state == null ? null : state.get(first.mediaId);
        Job after = state == null ? null : state.get(second.mediaId);
        // The jobs must be the ones for these queue items as they are now, not an older source.
        if (previewHeld(owner)
                || first.localConfiguration == null
                || second.localConfiguration == null
                || !settled(here)
                || !settled(after)
                || !first.localConfiguration.uri.equals(here.source)
                || !second.localConfiguration.uri.equals(after.source)) {
            cancelSpeculation();
            return;
        }
        Job held = lookahead;
        if (held != null && (held.id.equals(first.mediaId) || held.id.equals(second.mediaId))) {
            // The queue reached it: it is foreground work now.
            held.speculative = false;
            lookahead = null;
            want = null;
            held = null;
        }
        Want wanted = want;
        if (held != null
                && !(held.id.equals(item.mediaId)
                        && held.service == owner
                        && wanted != null
                        && wanted.token == held.token
                        && wanted.current == here
                        && wanted.next == after)) {
            cancelSpeculation();
            held = null;
        }
        double seconds = TransitionRuntime.duration(item);
        if (!(seconds > 0) || !SourceFirst.lookaheadReady(item.localConfiguration.uri, false)) {
            if (held != null) cancelSpeculation();
            return;
        }
        long token = held == null ? generation.incrementAndGet() : held.token;
        want = new Want(token, here, after);
        enqueue(owner, item, Math.round(seconds * 1000), false, false, token);
    }

    /**
     * Opening the editor requests both selected recordings even while normal playback is paused.
     */
    static void requestEditor(TransitionRuntime.Pair pair, boolean retry) {
        if (pair == null || !AutomixAccess.enabled()) return;
        Object owner = owner();
        if (owner == null) return;
        // The editor's analysis and waveform come before any measurement made ahead.
        cancelSpeculation();
        for (int side = 0; side < 2; side++) {
            double seconds = side == 0 ? pair.outgoingDuration : pair.incomingDuration;
            // A queued item can lack a duration; the worker reads its audio header.
            enqueue(
                    owner,
                    side == 0 ? pair.outgoing : pair.incoming,
                    Double.isFinite(seconds) && seconds > 0 ? Math.round(seconds * 1000) : 0,
                    retry);
        }
    }

    static String analysisPhase(String id) {
        if (!AutomixAccess.enabled()) return "disabled";
        try {
            Object analyzer = field(owner(), "trackAnalyzer");
            synchronized (jobs) {
                Map<String, Job> state = jobs.get(analyzer);
                Job job = state == null ? null : state.get(id);
                return job == null ? "waiting" : job.phase;
            }
        } catch (Exception unavailable) {
            return "unavailable";
        }
    }

    /** A measured native result the planner and the editor can read. */
    private static boolean usable(Object result) {
        try {
            Object bpm = field(result, "bpm");
            return "ready".equals(field(result, "status"))
                    && bpm instanceof Number
                    && Double.isFinite(((Number) bpm).doubleValue())
                    && ((Number) bpm).doubleValue() > 0;
        } catch (Exception unreadable) {
            return false;
        }
    }

    /**
     * Evidence that a native result belongs to another recording. A missing or different
     * analysis-URI entry is normal (watch, local and remapped source rows) and is not evidence.
     */
    private static boolean conflicts(Object result, String id, double seconds) {
        try {
            Object owner = field(result, "trackId");
            if (owner instanceof String && !((String) owner).isEmpty() && !owner.equals(id))
                return true;
        } catch (Exception absent) {
        }
        try {
            Object length = field(result, "duration");
            if (length instanceof Number
                    && seconds > 0
                    && ((Number) length).doubleValue() > 0
                    && Math.abs(((Number) length).doubleValue() - seconds) > 1) return true;
        } catch (Exception absent) {
        }
        return false;
    }

    private static boolean enqueue(Object owner, MediaItem item, long runtime, boolean retry) {
        return enqueue(owner, item, runtime, retry, true);
    }

    private static boolean enqueue(
            Object owner, MediaItem item, long runtime, boolean retry, boolean refresh) {
        if (listening.compareAndSet(false, true)) SourceAudio.onMemoryAvailable(memoryWake);
        return enqueue(owner, item, runtime, retry, refresh, 0);
    }

    /** A token other than zero marks a speculative request made under that generation. */
    private static boolean enqueue(
            Object owner,
            MediaItem item,
            long runtime,
            boolean retry,
            boolean refresh,
            long token) {
        if (item == null) return true;
        if (item.localConfiguration == null || item.mediaId == null || item.mediaId.isEmpty())
            return true;
        try {
            // A replay from an older owner must not take the service back.
            if (refresh) service(owner);
            else if (service.get() != owner) return true;
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
            // No file access here: a local file waits for the identity worker, which asks again.
            String key =
                    identityFor(
                            uri,
                            item.mediaId,
                            refresh
                                    ? () -> enqueue(owner, item, runtime, retry, false)
                                    : token != 0
                                            ? () ->
                                                    enqueue(
                                                            owner, item, runtime, false, false,
                                                            token)
                                            : null);
            if (key == null) return true;
            Job old;
            synchronized (state) {
                // A worker may have published a newer fact since this key was taken.
                key = newestKey(uri, key);
                old = state.get(item.mediaId);
                if (token != 0
                        && (token != generation.get()
                                || (old != null && old != lookahead && !old.cancelled)))
                    return true;
                double seconds = runtime / 1000.0;
                // A runtime more than a second off for the same source is another recording.
                boolean longer =
                        old != null
                                && old.duration > 0
                                && seconds > 0
                                && Math.abs(old.duration - seconds) > 1;
                if (old == null || !old.rendition.equals(key) || longer || old.cancelled) {
                    if (old != null) old.cancelled = true;
                    Job replacement =
                            new Job(owner, analyzer, item.mediaId, uri, runtime / 1000.0, key);
                    replacement.superseded = old != null;
                    replacement.source = item.localConfiguration.uri;
                    if (token != 0) {
                        replacement.speculative = true;
                        replacement.token = token;
                        lookahead = replacement;
                    } else if (old != null && old == lookahead) {
                        lookahead = null;
                    }
                    state.put(item.mediaId, replacement);
                    Map<String, Object> results = (Map<String, Object>) field(analyzer, "results");
                    Object cached = results.get(item.mediaId);
                    if (cached != null) {
                        if (old != null || conflicts(cached, item.mediaId, replacement.duration))
                            results.remove(item.mediaId, cached);
                        else if (usable(cached)) {
                            replacement.done = true;
                            replacement.adopted = true;
                            replacement.phase = "ready";
                        }
                    }
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
            if (token == 0 && job.speculative) {
                job.speculative = false;
                if (lookahead == job) lookahead = null;
            }
            if (retry && !job.running && !job.done) {
                job.attempts = 0;
                job.retryAfter = 0;
                job.deferrals = 0;
            }
            if (job.done
                    || job.running
                    || job.attempts >= ATTEMPTS
                    || System.nanoTime() < job.retryAfter) return true;
            if (((Number) field(analyzer, "thermalStatus")).intValue() >= 3) {
                job.phase = "thermal";
                return true;
            }
            Set<String> running = (Set<String>) field(analyzer, "running");
            if (token != 0 && !specValid(job)) return true;
            // Work the player needs now ends any measurement made ahead of it.
            if (token == 0 && lookahead != null && lookahead != job) cancelSpeculation();
            if (!running.add(item.mediaId)) {
                Job leaving = token == 0 ? draining.get(item.mediaId) : null;
                if (leaving != null) {
                    Runnable again = () -> enqueue(owner, item, runtime, retry, false);
                    leaving.resume.set(again);
                    if (!running.contains(item.mediaId)
                            && leaving.resume.compareAndSet(again, null)) again.run();
                }
                return true;
            }
            job.running = true;
            job.phase = "queued";
            Map<String, Job> current = state;
            try {
                ((ExecutorService) field(analyzer, "executor"))
                        .execute(() -> analyze(job, current, running));
            } catch (RuntimeException error) {
                job.running = false;
                job.phase = "unavailable";
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
        SourceAudio.Decoding decoding = null;
        String stage = "starting";
        boolean counted = false, stoodAside = false;
        active.set(job);
        enterQuiet();
        job.deadline = System.nanoTime() + BUDGET_NANOS;
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            if (AutomixAccess.stopped() || job.cancelled || current.get(job.id) != job) return;
            // Before any attempt, cache read or source open: speculation needs a current claim.
            checkLookahead(job, current);
            invoke(field(job.analyzer, "thermalWatch$delegate"), "getValue", new Class<?>[0]);
            if (((Number) field(job.analyzer, "thermalStatus")).intValue() >= 3) {
                job.phase = "thermal";
                return;
            }
            job.yielded = false;
            job.attempts++;
            counted = true;
            Map<String, Object> results = (Map<String, Object>) field(job.analyzer, "results");
            stage = "restore";
            AutomixAccess.restore(job.analyzer, job.storeKey);
            Object result = results.remove(job.storeKey);
            boolean restored = result != null, measuredDsp = false;
            if (result == null) {
                // A plain-id native cache is read on this worker and stays private until checked;
                // its provenance stays unverified. A changed rendition never revives it.
                stage = "native-restore";
                // After a proven change, an unbound native result is not this recording's.
                Object found = job.superseded ? null : results.get(job.id);
                if (found == null && !job.superseded) {
                    Capture capture = new Capture(results, job.id);
                    Capture outer = captured.get();
                    captured.set(capture);
                    try {
                        AutomixAccess.restore(job.analyzer, job.id);
                    } finally {
                        if (outer == null) captured.remove();
                        else captured.set(outer);
                    }
                    if (AutomixAccess.stopped() || job.cancelled || current.get(job.id) != job)
                        return;
                    Object candidate = capture.value;
                    if (candidate != null
                            && usable(candidate)
                            && !conflicts(candidate, job.id, job.duration)
                            && renditionCurrent(job)) {
                        synchronized (current) {
                            if (job.cancelled
                                    || current.get(job.id) != job
                                    || service.get() != job.service) return;
                            // A native result that landed meanwhile wins.
                            found = results.putIfAbsent(job.id, candidate);
                            if (found == null) found = candidate;
                        }
                    } else {
                        found = results.get(job.id);
                    }
                }
                if (found != null && conflicts(found, job.id, job.duration)) {
                    results.remove(job.id, found);
                    found = null;
                }
                if (found != null && usable(found)) {
                    if (!renditionCurrent(job)) return;
                    synchronized (current) {
                        if (job.cancelled
                                || current.get(job.id) != job
                                || service.get() != job.service) return;
                        if (results.get(job.id) != found) return;
                        job.done = true;
                        job.adopted = true;
                        job.phase = "ready";
                    }
                    HostLog.d("AutoMix reused a native analysis; source=" + job.uri.getScheme());
                    return;
                }
            }
            if (result == null) {
                // Cached results above cost no decode, so only a real measurement yields.
                if (yieldsToPlayback(job)) throw new Skipped();
                stage = "memory-preview";
                boolean leasedTry = job.skipPreview;
                job.skipPreview = false;
                if (job.format != null && !leasedTry) {
                    long maximum = heapMaximum.getAsLong(), used = heapUsed.getAsLong();
                    long full =
                            SourceAudio.analysisBytes(
                                    job.format.sampleRate, job.format.seconds, job.format.channels);
                    long bounded = DspAnalysis.bytes(job.format.sampleRate, job.format.seconds);
                    long available = SourceAudio.dspAvailable(maximum, used);
                    if (full > SourceAudio.analysisAvailable(maximum, used)
                            && bounded >= 0
                            && bounded > available)
                        throw new DspAnalysis.Deferred(bounded, available, false);
                }
                // Resolve before owning the PCM permit: the host resolver may dispatch coroutines.
                stage = "source-resolution";
                job.phase = "reading";
                try (MediaDataSource cached = AutomixAccess.cached(job.uri)) {
                    job.cachedOnly = cached != null;
                }
                if (!job.cachedOnly) {
                    // A local file is read in place, as the host's own analysis does; only a
                    // stream goes through the resolver. Ahead of time, nothing else is opened.
                    if (job.speculative) checkLookahead(job, current);
                    if (localReadable(job.uri, job.service)) job.spec = new DataSpec(job.uri);
                    else if (job.speculative) throw new Skipped();
                    else job.spec = resolve(job.uri);
                }
                checkLookahead(job, current);
                stage = "decode-admission";
                decoding =
                        SourceAudio.decode(
                                job.deadline, () -> job.cancelled || current.get(job.id) != job);
                String memoryLimit;
                Function0<MediaDataSource> input = opener(job.uri);
                stage = "model-preflight";
                try (MediaDataSource source = input.invoke()) {
                    long available =
                            SourceAudio.analysisAvailable(
                                    heapMaximum.getAsLong(), heapUsed.getAsLong());
                    memoryLimit = SourceAudio.analysisLimit(source, job.duration, available);
                    if (memoryLimit == null)
                        job.format = SourceAudio.analysisInfo(source, job.duration);
                }
                if (memoryLimit != null) {
                    stage = "format-probe";
                    SourceAudio.AnalysisInfo format;
                    try (MediaDataSource source = input.invoke()) {
                        format = SourceAudio.analysisInfo(source, job.duration);
                    }
                    job.format = format;
                    stage = "bounded-DSP";
                    try (SourceAudio.DspMemory memory =
                            SourceAudio.dspMemory(heapMaximum.getAsLong(), heapUsed.getAsLong())) {
                        // The pinned ceiling can admit the model where the unpinned one could not.
                        long modelBytes =
                                SourceAudio.analysisBytes(
                                        format.sampleRate, format.seconds, format.channels);
                        boolean leasedModel = false;
                        if (leasedModels
                                && modelBytes > 0
                                && modelBytes
                                        <= memory.available(
                                                heapMaximum.getAsLong(), heapUsed.getAsLong())) {
                            try (MediaDataSource source = input.invoke()) {
                                leasedModel =
                                        SourceAudio.analysisLimit(
                                                        source,
                                                        job.duration,
                                                        memory.available(
                                                                heapMaximum.getAsLong(),
                                                                heapUsed.getAsLong()))
                                                == null;
                            }
                            leasedModel =
                                    leasedModel
                                            && memory.claim(
                                                    modelBytes,
                                                    heapMaximum.getAsLong(),
                                                    heapUsed.getAsLong());
                        }
                        if (leasedModel) {
                            stage = "model-analysis";
                            job.phase = "analyzing";
                            models = useModels(job.analyzer);
                            Object outcome =
                                    invoke(
                                            job.analyzer,
                                            "analyze",
                                            new Class<?>[] {String.class, Uri.class, double.class},
                                            job.id,
                                            job.uri,
                                            job.format.seconds);
                            result = outcome == null ? null : field(outcome, "flags");
                            if (result == null) {
                                job.phase = "unavailable";
                                return;
                            }
                        } else {
                            long required = DspAnalysis.bytes(format.sampleRate, format.seconds);
                            if (required > 0
                                    && !memory.claim(
                                            required,
                                            heapMaximum.getAsLong(),
                                            heapUsed.getAsLong()))
                                throw new DspAnalysis.Deferred(
                                        required,
                                        memory.available(
                                                heapMaximum.getAsLong(), heapUsed.getAsLong()),
                                        false);
                            result =
                                    DspAnalysis.analyze(
                                            job.id,
                                            ((Context) job.service).getCacheDir(),
                                            input::invoke,
                                            format.sampleRate,
                                            format.seconds,
                                            () ->
                                                    job.cancelled
                                                            || current.get(job.id) != job
                                                            || yieldsToPlayback(job)
                                                            || memory.cancelled()
                                                            || AutomixAccess.stopped()
                                                            || System.nanoTime() >= job.deadline,
                                            () ->
                                                    memory.available(
                                                            heapMaximum.getAsLong(),
                                                            heapUsed.getAsLong()),
                                            count -> {
                                                memory.materialized(count);
                                                job.phase = "analyzing";
                                            },
                                            job.storeKey);
                        }
                    }
                    if (result == null) {
                        job.phase = "unavailable";
                        HostLog.d("AutoMix analysis deferred: " + memoryLimit);
                        return;
                    }
                    measuredDsp = models == null;
                } else {
                    stage = "model-analysis";
                    job.phase = "analyzing";
                    decoding.check();
                    checkLookahead(job, current);
                    long required =
                            SourceAudio.analysisBytes(
                                    job.format.sampleRate, job.format.seconds, job.format.channels);
                    try (SourceAudio.JavaMemory allocation =
                            SourceAudio.reserveJava(
                                    heapMaximum.getAsLong(), heapUsed.getAsLong(), required)) {
                        if (allocation == null)
                            throw new DspAnalysis.Deferred(
                                    required,
                                    SourceAudio.analysisAvailable(
                                            heapMaximum.getAsLong(), heapUsed.getAsLong()),
                                    false);
                        models = useModels(job.analyzer);
                        Object outcome =
                                invoke(
                                        job.analyzer,
                                        "analyze",
                                        new Class<?>[] {String.class, Uri.class, double.class},
                                        job.id,
                                        job.uri,
                                        job.format.seconds);
                        result = outcome == null ? null : field(outcome, "flags");
                    }
                }
            }
            stage = "result";
            if (result == null
                    || !"com.music.bitchord.playback.smart.TrackAnalysis"
                            .equals(result.getClass().getName())
                    || ((Number) field(result, "bpm")).doubleValue() <= 0) {
                job.phase = "unavailable";
                return;
            }
            checkLookahead(job, current);
            if (current.get(job.id) != job
                    || !renditionCurrent(job)
                    || service.get() != job.service
                    || AutomixAccess.stopped()) return;
            synchronized (current) {
                if (job.cancelled || current.get(job.id) != job || service.get() != job.service)
                    return;
                set(result, "trackId", job.id);
                results.put(job.id, result);
                // Only a result measured or restored for this exact source binds its URI.
                ((Map<String, Uri>) field(job.analyzer, "sourceAnalysisUris")).put(job.id, job.uri);
            }
            Object store = field(job.analyzer, "store");
            if (job.cancelled || current.get(job.id) != job || service.get() != job.service) return;
            stage = "save";
            invoke(
                    store,
                    "save",
                    new Class<?>[] {String.class, result.getClass()},
                    job.storeKey,
                    result);
            job.done = true;
            job.deferrals = 0;
            job.phase = "ready";
            HostLog.d(
                    (restored
                                    ? "AutoMix restored selected-audio analysis; source="
                                    : measuredDsp
                                            ? "AutoMix measured the selected audio with DSP;"
                                                    + " source="
                                            : "AutoMix measured selected audio; source=")
                            + job.uri.getScheme());
        } catch (Skipped aside) {
            // Not an attempt: nothing was measured, and the next snapshot may try again.
            if (counted) job.attempts = Math.max(0, job.attempts - 1);
            job.phase = job.yielded ? "waiting" : "queued";
            stoodAside = true;
            if (job.yielded) postLater();
        } catch (DspAnalysis.Deferred waitingForMemory) {
            job.deferrals++;
            job.memoryNeeded = waitingForMemory.required;
            if (job.deferrals == 1) job.leasedRetry = true;
            // Only a completed read uses an attempt; once spent, only a manual retry measures.
            job.phase =
                    waitingForMemory.decoded && job.attempts >= ATTEMPTS
                            ? "memory_limit"
                            : "memory";
            if (!waitingForMemory.decoded) job.attempts = Math.max(0, job.attempts - 1);
            HostLog.d(
                    "AutoMix analysis is waiting for memory: stage="
                            + (waitingForMemory.decoded ? "after-decode" : "before-decode")
                            + ", neededKiB="
                            + waitingForMemory.required / 1024
                            + ", availableKiB="
                            + Math.max(0, waitingForMemory.available) / 1024
                            + ", decoded="
                            + waitingForMemory.decoded
                            + ", "
                            + SourceAudio.memoryDiagnostics(
                                    heapMaximum.getAsLong(), heapUsed.getAsLong()));
        } catch (Throwable error) {
            if (error instanceof SourceAudio.Stopped
                            && ((SourceAudio.Stopped) error).reason
                                    == SourceAudio.StopReason.PRIORITY
                    || job.yielded && !allowed(job)) {
                if (counted) job.attempts = Math.max(0, job.attempts - 1);
                job.phase = "waiting";
                stoodAside = true;
                postLater();
                HostLog.d("AutoMix analysis yielded to playback");
            } else if (decoding != null && decoding.memoryStopped
                    || error instanceof SourceAudio.Stopped
                            && ((SourceAudio.Stopped) error).reason
                                    == SourceAudio.StopReason.MEMORY) {
                job.phase = "memory";
                job.deferrals++;
                if (counted) job.attempts = Math.max(0, job.attempts - 1);
                HostLog.d("AutoMix analysis is waiting for playback memory");
            } else {
                job.phase = "failed";
                HostLog.d(
                        "AutoMix will use a basic fade until audio can be measured: stage="
                                + stage
                                + ", error="
                                + error.getClass().getSimpleName()
                                + dspFailureSite(error));
            }
        } finally {
            try {
                finishModels(models);
                if (!stoodAside) {
                    boolean memory = "memory".equals(job.phase);
                    long delay = memory ? memoryDelayNanos(job.deferrals) : retryDelayNanos(0);
                    job.retryAfter = System.nanoTime() + delay;
                    // One wake per wait, not a loop: a release can wake it sooner.
                    Looper main = Looper.getMainLooper();
                    if (memory && main != null)
                        new Handler(main).postDelayed(SourceAutomix::post, delay / 1_000_000L + 50);
                    PlaybackPriority.analysisFinished(job.id);
                    if (job.done || job.cancelled || current.get(job.id) != job)
                        DspAnalysis.discard(job.storeKey);
                }
                job.running = false;
                running.remove(job.id);
                draining.remove(job.id, job);
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
            } finally {
                if (decoding != null) decoding.close();
            }
            // The id is free: a finished job may start the next lookahead, and a request that
            // arrived while a cancelled one still held the id asks again.
            if (!stoodAside) post();
            Runnable again = job.resume.getAndSet(null);
            if (again != null) again.run();
        }
    }

    /** Library audio and documents on this device; any other authority is never opened ahead. */
    static boolean trustedContent(Uri uri) {
        String authority = uri.getAuthority();
        List<String> parts = uri.getPathSegments();
        if ("media".equals(authority))
            return parts.size() == 4
                    && ("internal".equals(parts.get(0))
                            || "external".equals(parts.get(0))
                            || "external_primary".equals(parts.get(0)))
                    && "audio".equals(parts.get(1))
                    && "media".equals(parts.get(2))
                    && parts.get(3).matches("[0-9]{1,18}");
        if ("com.android.externalstorage.documents".equals(authority)
                || "com.android.providers.downloads.documents".equals(authority))
            return parts.size() >= 2
                    && "document".equals(parts.get(parts.size() - 2))
                    && !parts.get(parts.size() - 1).isEmpty()
                    && (parts.size() == 2 || (parts.size() == 4 && "tree".equals(parts.get(0))));
        return false;
    }

    /** On the worker: a local file or a trusted, openable content item; nothing is resolved. */
    private static boolean localReadable(Uri uri, Object owner) {
        if (isFile(uri)) return new File(uri.getPath()).canRead();
        if (!"content".equals(uri.getScheme())
                || !trustedContent(uri)
                || !(owner instanceof Context)) return false;
        try (android.content.res.AssetFileDescriptor opened =
                ((Context) owner).getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (opened == null) return false;
            // A regular local file reports its size; pipes and virtual files report -1.
            long size = opened.getParcelFileDescriptor().getStatSize();
            long start = opened.getStartOffset(), declared = opened.getDeclaredLength();
            return size > 0
                    && start >= 0
                    && start < size
                    && (declared == android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH
                            || (declared > 0 && start + declared <= size));
        } catch (Exception unreadable) {
            // Revoked, missing or refused: wait without prompting.
            return false;
        }
    }

    /** Stops a speculative job at its next checkpoint once its claim no longer holds. */
    private static void checkLookahead(Job job, Map<String, Job> current) {
        if (!job.speculative) return;
        Want claim = want;
        if (!specValid(job)
                || current.get(job.id) != job
                || service.get() != job.service
                || AutomixAccess.stopped()
                || claim == null
                || claim.token != job.token
                || current.get(claim.current.id) != claim.current
                || current.get(claim.next.id) != claim.next
                || !settled(claim.current)
                || !settled(claim.next)
                || previewHeld(job.service)
                || !SourceFirst.lookaheadReady(job.source, true)) throw new Skipped();
    }

    private static String dspFailureSite(Throwable error) {
        for (int depth = 0; error != null && depth < 4; depth++, error = error.getCause())
            for (StackTraceElement frame : error.getStackTrace())
                if ((frame.getClassName().equals(DspAnalysis.class.getName())
                                || frame.getClassName()
                                        .startsWith(DspAnalysis.class.getName() + "$"))
                        && frame.getMethodName().matches("[A-Za-z_$][A-Za-z0-9_$]*"))
                    return ", site="
                            + frame.getMethodName()
                            + ":"
                            + Math.max(0, frame.getLineNumber());
        return "";
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
                Job job = active.get();
                if (job != null && !uri.equals(job.uri)) return null;
                long deadline = job == null ? System.nanoTime() + BUDGET_NANOS : job.deadline;
                java.util.function.BooleanSupplier cancelled = () -> job != null && job.cancelled;
                MediaDataSource cached = AutomixAccess.cached(uri);
                if (cached != null) return SourceAudio.bounded(cached, deadline, cancelled);
                DataSpec spec;
                if (job != null && uri.equals(job.uri)) {
                    // A disappearing complete cache is retried later, never resolved under
                    // admission.
                    if (job.cachedOnly || job.spec == null) return null;
                    spec = job.spec;
                    if (pinned.size() > 128) pinned.clear();
                    pinned.put(job.rendition, spec);
                } else {
                    String key = identity(uri);
                    spec = pinned.get(key);
                    if (spec == null) {
                        spec = localReadable(uri, service.get()) ? new DataSpec(uri) : resolve(uri);
                        if (pinned.size() > 128) pinned.clear();
                        pinned.put(key, spec);
                    }
                }
                return SourceAudio.background(spec, deadline, cancelled);
            } catch (Exception error) {
                return null;
            } finally {
                leaveQuiet();
            }
        };
    }

    /** Apply the deck's native or explicit rate once, preserving its pitch. */
    public static void speed(Player player, float requested) {
        float base = AutomixAccess.speed();
        if (!Float.isFinite(base) || base <= 0) base = 1f;
        PlaybackParameters previous = player.getPlaybackParameters();
        float pitch = previous == null ? 1f : previous.pitch;
        PlaybackParameters parameters =
                new PlaybackParameters(
                        TransitionRuntime.playbackRate(player, base, requested), pitch);
        player.setPlaybackParameters(parameters);
        TransitionRuntime.tempoAssigned(player, parameters);
    }

    /** The native plan keeps its tempo rates and phase lock unless a stored edit chose them. */
    public static void plan(Object plan) {
        TransitionRuntime.apply(plan);
    }
}
