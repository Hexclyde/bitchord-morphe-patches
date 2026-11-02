package app.bitchord.telegram;

import android.net.Uri;

import androidx.media3.datasource.DataSpec;

import org.json.JSONObject;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * Plays a song from the best enabled source rather than from YouTube, whenever that copy is the
 * same recording.
 *
 * <p>BitChord starts a song on YouTube's stream or on what's cached of it, then looks for something
 * better while it plays. Here the next two songs in the queue are matched and checked ahead of
 * time, and a song just chosen is given a moment for the same. When a song is opened, the check's
 * answer decides which copy it reads: a better copy goes in a cache entry of its own, the one
 * BitChord's upgrades use, so what's cached from YouTube is never mixed with it.
 *
 * <p>Once the better copy has played for a while, what's cached from YouTube of that song is
 * removed, unless it could play with no connection and the better copy can't yet.
 *
 * <p>{@link SourceFirst} connects this to BitChord.
 */
final class SourcePreparation {
    static final class WeakIdentitySnapshots<K, V> {
        private static final class Snapshot<K, V> extends WeakReference<K> {
            V value;

            Snapshot(K key, V value) {
                super(key);
                this.value = value;
            }
        }

        private final List<Snapshot<K, V>> entries = new ArrayList<>();

        synchronized V get(K key) {
            cleanup();
            if (key == null) return null;
            for (Snapshot<K, V> entry : entries) if (entry.get() == key) return entry.value;
            return null;
        }

        synchronized void put(K key, V value) {
            java.util.Objects.requireNonNull(key);
            cleanup();
            for (Snapshot<K, V> entry : entries)
                if (entry.get() == key) {
                    entry.value = value;
                    return;
                }
            entries.add(new Snapshot<>(key, value));
        }

        synchronized void cleanup() {
            entries.removeIf(entry -> entry.get() == null);
        }
    }

    /** Everything outside this class. */
    interface Host {
        default void attach(SourcePreparation engine) {}

        default void detach(SourcePreparation engine) {}

        boolean sourcesAvailable();

        boolean backgroundAllowed();

        boolean foregroundAllowed();

        /** Downloads, original-version pins, parties and anything else BitChord keeps to itself. */
        boolean leftToHost(String mediaId);

        Object target(Uri item);

        /** The best copy across the enabled sources, or null. */
        Object lookup(Object target) throws Exception;

        /** Blocking file metadata is captured only on the preparation worker. */
        default void describe(Object stream) throws Exception {}

        String url(Object stream);

        String source(Object stream);

        boolean sourceActive(String source);

        boolean manifest(Object stream);

        /** Stable file identity, never a URL alone. Null confines trust to this process. */
        default String contentIdentity(Object stream) {
            return null;
        }

        /** Fingerprint of the complete cache bytes, or null when incomplete. */
        default String cacheIdentity(Uri marked) {
            return null;
        }

        /** Rebuild this exact queue entry with the manifest MIME type on the player thread. */
        default boolean reopenManifest(Uri original, Uri marked, Object stream) {
            return false;
        }

        /** The candidate id when a stream's file can be told from others, or null. */
        String candidate(Object stream);

        /** What a stream's validity depends on, compared later; null when it's no longer valid. */
        String terms(Object stream);

        void verify(Uri marked, Object stream, BooleanSupplier cancelled, boolean background)
                throws IOException;

        void trust(Uri marked, Object stream);

        boolean rejected(Uri marked, Object stream);

        /**
         * Whether the file really is what it promises, by the format its decoder would be given.
         */
        boolean honest(Object stream);

        /** Makes the rendition's entry ready for this stream; false when it can't be. */
        boolean claim(Uri marked, Object stream);

        boolean park(String mediaId, Object stream);

        void unpark(String mediaId, Object stream);

        /** The URL of the stream parked for a song, or null. */
        String parked(String mediaId);

        Object parkedStream(String mediaId);

        /** Stops BitChord looking for yet another copy while this one is used, or lets it again. */
        void holdUpgrades(String mediaId, boolean hold);

        /** Fills the rendition from its start and says how long reading took, in milliseconds. */
        long warm(Uri marked, long bytes) throws Exception;

        /** The bitrate a stream promises, in kbps; 0 when unknown. */
        int kbps(Object stream);

        DataSpec rewrite(DataSpec spec, Uri marked) throws Exception;

        boolean complete(Uri marked);

        long length(Uri marked);

        /** Cache entries of this song with YouTube's audio, played from: never anything else. */
        List<String> youtubeEntries(String mediaId, Uri marked);

        boolean entryComplete(String key);

        boolean better(Object stream, String key, Uri marked, String seconds);

        boolean remove(String key);

        void later(Runnable task, long millis);

        /** Whether this song is still playing past {@code position}, and only one player has it. */
        boolean stillPlaying(String mediaId, Uri entry, long position);

        String read();

        void write(String saved);

        void log(String line);
    }

    static final int LOOKAHEAD = 2, KEPT = 4, COPIES = 2, CLEAN_TRIES = 3, FILL_TRIES = 2;
    static final int REMEMBERED = 500;
    static final long FIRST_WAIT_MS = 20_000, DECLINED_MS = 120_000, SLOW_MS = 60_000;
    static final long KEPT_MS = 20 * 60_000, PROOF_MS = 10_000;
    static final long WARM_BYTES = 1 << 20, STEP_BYTES = 4 << 20;

    /** A warm read under this took nothing from the network worth measuring. */
    private static final long MEASURABLE_MS = 100;

    private static final Object PLAIN = new Object();

    private final Host host;
    private final long firstWaitMs;
    private final Object lock = new Object();
    private final Deque<Work> waiting = new ArrayDeque<>();
    private final Map<String, Prepared> prepared =
            new LinkedHashMap<String, Prepared>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Prepared> eldest) {
                    return size() > KEPT;
                }
            };
    private final Map<String, Long> declined = new HashMap<>(), songsDeclined = new HashMap<>();
    private final Set<String> checking = new HashSet<>(), rejectedCopies = new HashSet<>();
    // Entry decisions include PLAIN: a finished preparation cannot rewrite an open period.
    private final Map<String, Object> entries = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, String> cacheKeys = new LinkedHashMap<>(16, .75f, true);
    private final Map<Object, String> periodEntries = new WeakHashMap<>();
    private Set<String> currentEntries = Collections.emptySet();
    private final String session = UUID.randomUUID().toString();
    private final Map<Object, Object> periods = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<String, Integer> cleanTries = new HashMap<>(), fillTries = new HashMap<>();
    private final Set<String> proving = new HashSet<>(), cleaned = new HashSet<>();
    private final Map<String, Mapped> toFill = new HashMap<>();
    private Set<String> upcoming = Collections.emptySet(), upcomingEntries = Collections.emptySet();
    private Thread worker;
    private Work running;
    private boolean closed, on = true, listening;
    private JSONObject saved;

    SourcePreparation(Host host, long firstWaitMs) {
        this.host = host;
        this.firstWaitMs = firstWaitMs;
    }

    private interface Work {
        void run() throws Exception;

        default void cancel() {}

        default boolean background() {
            return true;
        }
    }

    private static final class Prepared {
        final Object stream;
        final String url, source, terms;
        final Uri marked;
        final long at = System.nanoTime();

        Prepared(Object stream, String url, String source, String terms, Uri marked) {
            this.stream = stream;
            this.url = url;
            this.source = source;
            this.terms = terms;
            this.marked = marked;
        }
    }

    /** A song now read from its better copy. */
    private static final class Mapped {
        final Uri marked;
        final Object stream;
        final String url, source, seconds;
        String identity, terms;

        Mapped(Uri marked, Object stream, String url, String source, String seconds) {
            this.marked = marked;
            this.stream = stream;
            this.url = url;
            this.source = source;
            this.seconds = seconds;
        }
    }

    // ── Which copy a period reads ───────────────────────────────────────────

    static boolean eligible(Uri uri) {
        if (uri == null
                || !"bitchord".equals(uri.getScheme())
                || !"watch".equals(uri.getAuthority())) return false;
        String id = uri.getQueryParameter("v");
        return id != null
                && !id.isEmpty()
                && uri.getQueryParameter("q") == null
                && uri.getQueryParameter("direct_youtube") == null
                && uri.getQueryParameter("automix_opus") == null
                && uri.getQueryParameter("watch_read") == null
                && !"1".equals(uri.getQueryParameter("m"));
    }

    static final String ENTRY = "bc_entry";

    static Uri withoutEntry(Uri uri) {
        if (uri == null || uri.getQueryParameter(ENTRY) == null) return uri;
        Uri.Builder builder = uri.buildUpon().clearQuery();
        for (String key : uri.getQueryParameterNames())
            if (!ENTRY.equals(key))
                for (String value : uri.getQueryParameters(key))
                    builder.appendQueryParameter(key, value);
        return builder.build();
    }

    static String entryKey(Uri uri) {
        String entry = uri.getQueryParameter(ENTRY);
        return uri.getQueryParameter("v") + "|" + (entry == null ? "untagged" : entry);
    }

    /**
     * Preserve the first actual cache key of each plain queue entry across source-policy changes.
     */
    String cacheKey(Uri item, String computed) {
        if (!eligible(item)
                || item.getQueryParameter(ENTRY) == null
                || computed == null
                || computed.isEmpty()) return computed;
        synchronized (lock) {
            String entry = entryKey(item), known = cacheKeys.get(entry);
            if (known != null) return known;
            cacheKeys.put(entry, computed);
            trimEntries();
            return computed;
        }
    }

    /** Current decks remain protected even between period release and its replacement open. */
    void retainCurrentEntries(List<Uri> items) {
        Set<String> keep = new HashSet<>();
        for (Uri item : items)
            if (item != null && item.getQueryParameter(ENTRY) != null) keep.add(entryKey(item));
        synchronized (lock) {
            currentEntries = keep;
            if (running instanceof Job) promoteCurrentWaiter((Job) running);
            for (Work work : waiting) if (work instanceof Job) promoteCurrentWaiter((Job) work);
            trimEntries();
            lock.notifyAll();
        }
    }

    private void promoteCurrentWaiter(Job job) {
        for (String entry : job.waiters.keySet())
            if (currentEntries.contains(entry)) {
                job.foreground = true;
                return;
            }
    }

    private boolean future(Uri uri, String id) {
        String entry = entryKey(uri);
        return uri.getQueryParameter(ENTRY) != null
                && !currentEntries.isEmpty()
                && upcoming.contains(id)
                && upcomingEntries.contains(entry)
                && !currentEntries.contains(entry);
    }

    private void trimEntries() {
        // At most REMEMBERED inactive identities, plus the entries still owned by live
        // periods/decks.
        Set<String> held = new HashSet<>(currentEntries);
        held.addAll(periodEntries.values());
        trim(entries, held);
        trim(cacheKeys, held);
    }

    private static void trim(Map<String, ?> values, Set<String> held) {
        int idle = 0;
        for (String key : values.keySet()) if (!held.contains(key)) idle++;
        for (Iterator<String> oldest = values.keySet().iterator();
                idle > REMEMBERED && oldest.hasNext(); ) {
            String key = oldest.next();
            if (!held.contains(key)) {
                oldest.remove();
                idle--;
            }
        }
    }

    int rememberedKeys() {
        synchronized (lock) {
            return cacheKeys.size();
        }
    }

    static Uri marked(Uri item, String identity) {
        return item.buildUpon().appendQueryParameter("q", "hifi" + hash(identity)).build();
    }

    private static Uri forEntry(Uri original, Uri chosen) {
        return original.buildUpon()
                .appendQueryParameter("q", chosen.getQueryParameter("q"))
                .build();
    }

    /** The same seam is used by playback, analysis and the audible source label. */
    Uri renditionUri(Uri item) {
        if (!eligible(item)) return item;
        synchronized (lock) {
            Object bound = entries.get(entryKey(item));
            if (bound != null) return bound == PLAIN ? item : ((Mapped) bound).marked;
        }
        String id = item.getQueryParameter("v");
        Prepared ready = usable(id);
        if (ready == null || !ready.url.equals(host.parked(id))) return item;
        // Once analysis consumes a prepared choice, the entry is bound to that same file.
        // The open below consumes this binding rather than making another timing-dependent choice.
        Mapped choice =
                new Mapped(
                        forEntry(item, ready.marked),
                        ready.stream,
                        ready.url,
                        ready.source,
                        item.getQueryParameter("d"));
        choice.identity = host.contentIdentity(ready.stream);
        choice.terms = ready.terms;
        synchronized (lock) {
            Object existing = entries.get(entryKey(item));
            if (existing != null) return existing == PLAIN ? item : ((Mapped) existing).marked;
            entries.put(entryKey(item), choice);
            cleanTries.remove(entryKey(item));
            trimEntries();
        }
        host.holdUpgrades(id, true);
        return choice.marked;
    }

    /** A late host upgrade must not replace the file already chosen for this entry. */
    boolean preparedEntry(Uri item) {
        if (item == null) return false;
        synchronized (lock) {
            Object bound = entries.get(entryKey(item));
            return bound instanceof Mapped
                    && (eligible(item) || item.equals(((Mapped) bound).marked));
        }
    }

    boolean isPreparedUri(Uri item) {
        if (item == null) return false;
        synchronized (lock) {
            if (closed) return false;
            Object bound = entries.get(entryKey(item));
            return bound instanceof Mapped && item.equals(((Mapped) bound).marked);
        }
    }

    private static String songKey(Uri item) {
        String seconds = item.getQueryParameter("d");
        Integer parsed = null;
        try {
            if (seconds != null) parsed = Integer.valueOf(seconds);
        } catch (NumberFormatException ignored) {
            // No usable length.
        }
        return item.getQueryParameter("n") + "|" + parsed;
    }

    /** The first open of a period decides for all of them, so it reads only one file. */
    DataSpec route(Object period, DataSpec spec) throws IOException {
        Uri uri = spec.uri;
        if (!eligible(uri)) return spec;
        try {
            Object decided = periods.get(period);
            if (decided == null) {
                if (uri.getQueryParameter(ENTRY) != null)
                    synchronized (lock) {
                        decided = entries.get(entryKey(uri));
                    }
                if (decided == null) {
                    Mapped choice = decide(uri);
                    decided = choice == null ? PLAIN : choice;
                    synchronized (lock) {
                        Object bound =
                                uri.getQueryParameter(ENTRY) == null
                                        ? null
                                        : entries.get(entryKey(uri));
                        if (bound != null) decided = bound;
                        else entries.put(entryKey(uri), decided);
                    }
                }
                periods.put(period, decided);
                synchronized (lock) {
                    periodEntries.put(period, entryKey(uri));
                    trimEntries();
                }
            }
            if (decided == PLAIN) return spec;
            Mapped choice = (Mapped) decided;
            String id = uri.getQueryParameter("v");
            if (choice.stream != null) {
                String changed = null;
                if (!choice.url.equals(host.parked(id))) changed = "the pinned stream changed";
                else if (!java.util.Objects.equals(
                        choice.identity, host.contentIdentity(choice.stream)))
                    changed = "the file identity changed";
                else if (!java.util.Objects.equals(choice.terms, host.terms(choice.stream)))
                    changed = "source permissions or quality settings changed";
                if (changed != null) {
                    // Reading on could join another file to this one.
                    host.log("Source preparation: " + changed + " while it was read");
                    throw new IOException("The chosen copy is no longer available");
                }
            }
            if (choice.stream != null && host.manifest(choice.stream)) {
                if (!host.reopenManifest(uri, choice.marked, choice.stream))
                    throw new IOException("Could not rebuild this entry for its stream type");
                throw new IOException("Media source rebuilt for its stream type");
            }
            return host.rewrite(spec, choice.marked);
        } catch (IOException error) {
            throw error;
        } catch (Throwable unavailable) {
            host.log("Source preparation skipped: " + unavailable.getClass().getSimpleName());
            synchronized (lock) {
                if (periods.get(period) instanceof Mapped
                        || entries.get(entryKey(uri)) instanceof Mapped)
                    throw new IOException("The chosen copy could not be reopened", unavailable);
            }
            return spec;
        }
    }

    private Mapped decide(Uri uri) throws Exception {
        synchronized (lock) {
            if (closed || !on) return null;
        }
        String id = uri.getQueryParameter("v");
        if (host.leftToHost(id) || !host.sourcesAvailable()) return null;
        if (isDeclined(id)) return null;
        JSONObject known = knownCopy(id);
        if (known != null) {
            Uri marked =
                    uri.buildUpon().appendQueryParameter("q", known.optString("marker")).build();
            String source = known.optString("source"), fingerprint = host.cacheIdentity(marked);
            if (host.sourceActive(source)
                    && fingerprint != null
                    && fingerprint.equals(known.optString("cache"))) {
                host.holdUpgrades(id, true);
                return new Mapped(marked, null, null, source, uri.getQueryParameter("d"));
            }
        }
        if (!host.foregroundAllowed()) return null;
        Prepared ready = usable(id);
        if (ready == null) ready = wait(uri, id);
        if (ready == null) return null;
        if (!host.park(id, ready.stream)) return null;
        host.holdUpgrades(id, true);
        Mapped chosen =
                new Mapped(
                        forEntry(uri, ready.marked),
                        ready.stream,
                        ready.url,
                        ready.source,
                        uri.getQueryParameter("d"));
        chosen.identity = host.contentIdentity(ready.stream);
        chosen.terms = ready.terms;
        synchronized (lock) {
            cleanTries.remove(entryKey(uri));
        }
        host.log("Source preparation: playing a better copy from the start");
        return chosen;
    }

    private Prepared usable(String id) {
        Prepared ready;
        synchronized (lock) {
            ready = prepared.get(id);
        }
        if (ready == null) return null;
        boolean fresh = System.nanoTime() - ready.at < TimeUnit.MILLISECONDS.toNanos(KEPT_MS);
        if (fresh
                && ready.terms.equals(host.terms(ready.stream))
                && host.sourceActive(ready.source)) return ready;
        synchronized (lock) {
            prepared.remove(id);
        }
        return null;
    }

    /** Asks for a song to be prepared first and waits, within reason, off the player's thread. */
    private Prepared wait(Uri uri, String id) {
        long began = System.nanoTime(), budget = TimeUnit.MILLISECONDS.toNanos(firstWaitMs);
        boolean future;
        Job job;
        synchronized (lock) {
            future = future(uri, id);
            job = foregroundJob(uri, id, !future);
        }
        if (job == null) return null;
        try {
            while (true) {
                synchronized (lock) {
                    if (closed || !on || job.cancelled) return null;
                    if (!future(uri, id)) {
                        future = false;
                        job.foreground = true;
                    }
                }
                long remaining = budget * (future ? 3 : 1) - (System.nanoTime() - began);
                if (remaining <= 0) throw new TimeoutException();
                try {
                    return job.done.get(
                            Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)),
                            TimeUnit.NANOSECONDS);
                } catch (TimeoutException poll) {
                    // Queue changes can make a future period current while this loader waits.
                }
            }
        } catch (TimeoutException slow) {
            // It keeps going, for next time. Until it answers, nothing else offers this song a
            // copy that would only wait on the same check.
            host.log("Source preparation: too slow to start on, so this play falls back");
            return null;
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception failed) {
            return null;
        } finally {
            synchronized (lock) {
                job.waiters.computeIfPresent(
                        entryKey(uri), (entry, count) -> count == 1 ? null : count - 1);
            }
        }
    }

    private Job foregroundJob(Uri uri, String id, boolean foreground) {
        synchronized (lock) {
            if (closed) return null;
            if (running instanceof Job && ((Job) running).id.equals(id)) {
                Job job = (Job) running;
                job.foreground |= foreground;
                job.waiters.merge(entryKey(uri), 1, Integer::sum);
                return job;
            }
            if (running != null
                    && (foreground || running.background())
                    && !(running instanceof Job
                            && !foreground
                            && !((Job) running).waiters.isEmpty())) running.cancel();
            Job queued = null;
            for (Iterator<Work> it = waiting.iterator(); it.hasNext(); ) {
                Work work = it.next();
                if (work instanceof Job) {
                    Job job = (Job) work;
                    if (job.id.equals(id)) {
                        queued = job;
                        if (foreground) it.remove();
                    } else if (foreground && job.isForeground()) {
                        it.remove();
                        job.finish(null);
                    }
                }
            }
            Job job = queued != null ? queued : new Job(uri, id);
            job.foreground |= foreground;
            job.waiters.merge(entryKey(uri), 1, Integer::sum);
            if (queued == null || foreground) {
                if (foreground) waiting.addFirst(job);
                else waiting.addLast(job);
            }
            start();
            lock.notifyAll();
            return job;
        }
    }

    private void start() {
        if (worker != null) return;
        worker = new Thread(this::work, "Source preparation");
        worker.setDaemon(true);
        worker.start();
    }

    private void work() {
        while (true) {
            Work next;
            synchronized (lock) {
                try {
                    while (!closed && waiting.isEmpty()) lock.wait();
                } catch (InterruptedException stopped) {
                    return;
                }
                if (closed) return;
                next = waiting.pollFirst();
                running = next;
            }
            try {
                next.run();
            } catch (Throwable error) {
                // A method missing from the host mustn't take the app with it.
                host.log("Source preparation stopped: " + error.getClass().getSimpleName());
                next.cancel();
            } finally {
                synchronized (lock) {
                    running = null;
                    lock.notifyAll();
                }
            }
        }
    }

    // ── Preparing ───────────────────────────────────────────────────────────

    private final class Job implements Work {
        final Uri uri;
        Uri marked;
        final String id, song;
        final CompletableFuture<Prepared> done = new CompletableFuture<>();
        final Map<String, Integer> waiters = new HashMap<>();
        volatile boolean foreground, cancelled;

        Job(Uri uri, String id) {
            this.uri = uri;
            this.id = id;
            marked = marked(uri, session + "|pending|" + id);
            song = songKey(uri);
        }

        boolean isForeground() {
            return foreground;
        }

        @Override
        public boolean background() {
            return !foreground;
        }

        @Override
        public void cancel() {
            cancelled = true;
            finish(null);
        }

        void finish(Prepared result) {
            done.complete(result);
        }

        boolean stop() {
            synchronized (lock) {
                return cancelled || closed || !on;
            }
        }

        @Override
        public void run() throws Exception {
            try {
                prepare();
            } catch (Exception unavailable) {
                if (!stop()) decline(id, song, DECLINED_MS, "the sources couldn't be asked");
                host.log("Source preparation: " + unavailable.getClass().getSimpleName());
            } finally {
                finish(null);
            }
        }

        private void prepare() throws Exception {
            if (stop()) return;
            if (!foreground && !(listening() && host.backgroundAllowed())) return;
            if (host.leftToHost(id) || !host.sourcesAvailable()) return;
            for (int copy = 0; copy < COPIES; copy++) {
                if (stop()) return;
                Object stream = host.lookup(host.target(uri));
                if (stop()) return;
                if (stream == null) {
                    decline(
                            id,
                            song,
                            DECLINED_MS,
                            copy == 0
                                    ? "no usable matching file was returned"
                                    : "no verified replacement was available after the recording"
                                            + " check");
                    return;
                }
                host.describe(stream);
                String url = host.url(stream), source = host.source(stream);
                String terms = host.terms(stream);
                if (terms == null || !host.sourceActive(source)) {
                    decline(id, song, DECLINED_MS, "the source can't serve it now");
                    return;
                }
                String content = host.contentIdentity(stream);
                marked =
                        marked(
                                uri,
                                source
                                        + "|"
                                        + (content == null
                                                ? session + "|" + url + "|" + terms
                                                : content));
                if (!checked(stream, content)) {
                    if (stop()) return;
                    if (host.rejected(marked, stream)) {
                        String candidate = host.candidate(stream);
                        if (candidate != null && copy + 1 < COPIES) {
                            synchronized (lock) {
                                rejectedCopies.add(song + "\n" + candidate);
                            }
                            continue;
                        }
                        decline(
                                id,
                                song,
                                DECLINED_MS,
                                "the recording check did not verify this copy");
                    } else decline(id, song, DECLINED_MS, "it couldn't be checked");
                    return;
                }
                if (!host.honest(stream)) {
                    decline(id, song, DECLINED_MS, "the file isn't the format it promises");
                    return;
                }
                host.describe(stream);
                if (!java.util.Objects.equals(content, host.contentIdentity(stream))
                        || !terms.equals(host.terms(stream))) {
                    decline(id, null, DECLINED_MS, "the file changed while it was checked");
                    return;
                }
                if (!host.claim(marked, stream) || !host.park(id, stream)) {
                    decline(id, null, DECLINED_MS, "its cache entry is in use");
                    return;
                }
                if (!host.manifest(stream) && slow(stream)) {
                    host.unpark(id, stream);
                    decline(id, null, SLOW_MS, "it arrives too slowly to play");
                    return;
                }
                Prepared ready = new Prepared(stream, url, source, terms, marked);
                synchronized (lock) {
                    prepared.put(id, ready);
                    declined.remove(id);
                }
                if (content != null) remember("verified3", proofKey(stream, content), null);
                finish(ready);
                return;
            }
        }

        /** The check itself, unless this exact file passed it before. */
        private String proofKey(Object stream, String content) {
            return hash(
                    "prepared-v1\n"
                            + withoutEntry(uri)
                            + "\n"
                            + host.source(stream)
                            + "\n"
                            + content);
        }

        private boolean checked(Object stream, String content) {
            if (content != null && isSaved("verified3", proofKey(stream, content))) {
                host.trust(marked, stream);
                return true;
            }
            synchronized (lock) {
                checking.add(song);
            }
            try {
                host.verify(marked, stream, () -> stop(), !foreground);
                return true;
            } catch (IOException notNow) {
                return false;
            } finally {
                synchronized (lock) {
                    checking.remove(song);
                }
            }
        }

        /** Whether the start of the copy came in slower than it would be played. */
        private boolean slow(Object stream) throws Exception {
            long took = host.warm(marked, WARM_BYTES);
            int kbps = host.kbps(stream);
            if (took < MEASURABLE_MS || kbps <= 0) return false;
            return WARM_BYTES * 8 / took < kbps;
        }
    }

    void upcoming(List<Uri> items) {
        synchronized (lock) {
            if (closed || !on) return;
            Set<String> wanted = new HashSet<>();
            Set<String> wantedEntries = new HashSet<>();
            List<Uri> next = new ArrayList<>();
            for (Uri item : items.subList(0, Math.min(LOOKAHEAD, items.size()))) {
                if (!eligible(item)) continue;
                wanted.add(item.getQueryParameter("v"));
                wantedEntries.add(entryKey(item));
                next.add(item);
            }
            upcoming = wanted;
            upcomingEntries = wantedEntries;
            for (Iterator<Work> it = waiting.iterator(); it.hasNext(); ) {
                Work work = it.next();
                if (work instanceof Job && !((Job) work).isForeground()) {
                    Job job = (Job) work;
                    boolean awaited = false;
                    for (String entry : job.waiters.keySet())
                        if (wantedEntries.contains(entry) || currentEntries.contains(entry)) {
                            awaited = true;
                            break;
                        }
                    if (awaited) continue;
                    ((Job) work).finish(null);
                    it.remove();
                }
            }
            if (running instanceof Job
                    && running.background()
                    && !wanted.contains(((Job) running).id)) running.cancel();
            for (Uri item : next) {
                String id = item.getQueryParameter("v");
                if (prepared.containsKey(id)) continue;
                Long until = declined.get(id);
                if (until != null && System.nanoTime() < until) continue;
                if (running instanceof Job && ((Job) running).id.equals(id)) continue;
                boolean queued = false;
                for (Work work : waiting)
                    if (work instanceof Job && ((Job) work).id.equals(id)) {
                        queued = true;
                        break;
                    }
                if (queued) continue;
                waiting.addLast(new Job(item, id));
                start();
            }
            lock.notifyAll();
        }
    }

    void playing(boolean isPlaying) {
        synchronized (lock) {
            listening = isPlaying;
            if (isPlaying) return;
            for (Iterator<Work> it = waiting.iterator(); it.hasNext(); ) {
                Work work = it.next();
                if (work.background()) {
                    work.cancel();
                    it.remove();
                }
            }
            if (running != null && running.background()) running.cancel();
        }
    }

    private boolean listening() {
        synchronized (lock) {
            return listening;
        }
    }

    void switched(boolean enabled) {
        synchronized (lock) {
            on = enabled;
            if (enabled) return;
            for (Work work : waiting) work.cancel();
            waiting.clear();
            if (running != null) running.cancel();
            prepared.clear();
            // Open periods keep their file even when background preparation is switched off.
            for (Object choice : entries.values())
                if (choice instanceof Mapped) {
                    Mapped copy = (Mapped) choice;
                    host.holdUpgrades(copy.marked.getQueryParameter("v"), false);
                }
            toFill.clear();
        }
    }

    void failed(String id) {
        failed(id, null);
    }

    void failed(String id, Uri failedEntry) {
        synchronized (lock) {
            prepared.remove(id);
            Mapped was = null;
            for (Map.Entry<String, Object> entry : entries.entrySet()) {
                if (entry.getValue() instanceof Mapped
                        && id.equals(((Mapped) entry.getValue()).marked.getQueryParameter("v"))
                        && (failedEntry == null || entry.getKey().equals(entryKey(failedEntry)))) {
                    was = (Mapped) entry.getValue();
                    entry.setValue(PLAIN);
                }
            }
            toFill.remove(id);
            if (was == null) return;
            declined.put(id, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DECLINED_MS));
            if (was.stream != null) host.unpark(id, was.stream);
            host.holdUpgrades(id, false);
        }
        host.log("Source preparation: this copy failed to play, so it's no longer offered");
    }

    private void decline(String id, String song, long millis, String why) {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        synchronized (lock) {
            declined.put(id, until);
            if (song != null) songsDeclined.put(song, until);
        }
        host.log("Source preparation: staying with what BitChord would play: " + why);
    }

    private boolean isDeclined(String id) {
        synchronized (lock) {
            Long until = declined.get(id);
            if (until == null) return false;
            if (System.nanoTime() < until) return true;
            declined.remove(id);
            return false;
        }
    }

    /**
     * Whether a candidate from another source sits a search out: while its song is being checked,
     * after the song was turned down, or when this very copy was found to be another recording.
     */
    boolean holds(String song, String candidate) {
        if (candidate == null || !candidate.startsWith("src:")) return false;
        synchronized (lock) {
            if (rejectedCopies.contains(song + "\n" + candidate)) return true;
            if (checking.contains(song)) return true;
            Long until = songsDeclined.get(song);
            if (until == null) return false;
            if (System.nanoTime() < until) return true;
            songsDeclined.remove(song);
            return false;
        }
    }

    String statusOf(String id) {
        synchronized (lock) {
            if (prepared.containsKey(id)) return "ready";
            if (running instanceof Job && ((Job) running).id.equals(id)) return "preparing";
            for (Work work : waiting)
                if (work instanceof Job && ((Job) work).id.equals(id)) return "preparing";
            return "";
        }
    }

    // ── What's cached from YouTube ──────────────────────────────────────────

    /** The song now playing: once its better copy has proved itself, the old one can go. */
    void current(String id, Uri uri, long position, boolean isPlaying) {
        if (id == null || !isPlaying) return;
        fillLeftBehind(id);
        Mapped copy;
        synchronized (lock) {
            Object bound = uri == null ? null : entries.get(entryKey(uri));
            copy = bound instanceof Mapped ? (Mapped) bound : null;
            if (copy == null) {
                // A song upgraded mid-play is on the same terms.
                String tag = uri == null ? null : uri.getQueryParameter("q");
                if (tag == null || !tag.startsWith("hifi")) return;
                Object stream = host.parkedStream(id);
                if (stream == null) return;
                copy =
                        new Mapped(
                                uri,
                                stream,
                                host.url(stream),
                                host.source(stream),
                                uri.getQueryParameter("d"));
            }
            String entry = entryKey(copy.marked);
            if (cleaned.contains(entry) || !proving.add(entry)) return;
        }
        Mapped proved = copy;
        host.later(() -> proved(id, proved, position), PROOF_MS);
    }

    private void proved(String id, Mapped copy, long position) {
        synchronized (lock) {
            proving.remove(entryKey(copy.marked));
            if (closed || !on) return;
        }
        if (!host.stillPlaying(id, copy.marked, position)) {
            host.log("Source preparation: the old copy stays for now; still in use or not playing");
            return;
        }
        enqueue(new Clean(id, copy));
    }

    private void enqueue(Work work) {
        synchronized (lock) {
            if (closed) return;
            waiting.addLast(work);
            start();
            lock.notifyAll();
        }
    }

    /** Removes what's cached from YouTube of a song once its better copy is playing. */
    private final class Clean implements Work {
        final String id;
        final Mapped copy;

        Clean(String id, Mapped copy) {
            this.id = id;
            this.copy = copy;
        }

        @Override
        public void run() {
            int tries;
            synchronized (lock) {
                String entry = entryKey(copy.marked);
                tries = cleanTries.getOrDefault(entry, 0);
                if (tries >= CLEAN_TRIES) return;
                cleanTries.put(entry, tries + 1);
            }
            boolean whole = host.complete(copy.marked);
            if (whole
                    && copy.source != null
                    && (copy.stream == null || !host.manifest(copy.stream))) {
                String cache = host.cacheIdentity(copy.marked);
                if (cache != null)
                    try {
                        remember(
                                "known3",
                                id,
                                new JSONObject()
                                        .put("source", copy.source)
                                        .put("marker", copy.marked.getQueryParameter("q"))
                                        .put("cache", cache)
                                        .toString());
                    } catch (Exception ignored) {
                    }
            }
            List<String> old = new ArrayList<>();
            for (String key : host.youtubeEntries(id, copy.marked))
                if (copy.stream == null || host.better(copy.stream, key, copy.marked, copy.seconds))
                    old.add(key);
            int removed = 0, kept = 0, busy = 0;
            for (String key : old) {
                if (host.entryComplete(key) && !whole) {
                    // It could play with no connection: the better copy has to be able to first.
                    kept++;
                    continue;
                }
                if (host.remove(key)) removed++;
                else busy++;
            }
            if (kept > 0) {
                synchronized (lock) {
                    toFill.put(id, copy);
                }
            }
            if (removed + kept + busy > 0)
                host.log(
                        "Source preparation: removed "
                                + removed
                                + " old YouTube copies, kept "
                                + kept
                                + " until the better copy is whole, "
                                + busy
                                + " still in use");
            if (busy == 0 && kept == 0) {
                synchronized (lock) {
                    cleanTries.remove(entryKey(copy.marked));
                    if (cleaned.size() >= REMEMBERED) cleaned.clear();
                    cleaned.add(entryKey(copy.marked));
                }
            }
        }
    }

    /** A whole YouTube copy waits for the better copy to be whole. Done once nothing reads it. */
    private void fillLeftBehind(String playing) {
        synchronized (lock) {
            for (Map.Entry<String, Mapped> entry : toFill.entrySet()) {
                String id = entry.getKey();
                if (id.equals(playing) || upcoming.contains(id)) continue;
                int tries = fillTries.getOrDefault(id, 0);
                if (tries >= FILL_TRIES) continue;
                fillTries.put(id, tries + 1);
                Mapped copy = entry.getValue();
                toFill.remove(id);
                waiting.addLast(new Fill(id, copy));
                start();
                lock.notifyAll();
                return;
            }
        }
    }

    private final class Fill implements Work {
        final String id;
        final Mapped copy;
        volatile boolean cancelled;

        Fill(String id, Mapped copy) {
            this.id = id;
            this.copy = copy;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public void run() throws Exception {
            if (!listening() || !host.backgroundAllowed()) {
                synchronized (lock) {
                    toFill.put(id, copy);
                }
                return;
            }
            if (copy.stream != null) host.park(id, copy.stream);
            long length = host.length(copy.marked);
            for (long at = STEP_BYTES; !cancelled && length > 0; at += STEP_BYTES) {
                synchronized (lock) {
                    if (closed || !on) return;
                }
                host.warm(copy.marked, Math.min(at, length));
                if (at >= length) break;
            }
            if (host.complete(copy.marked)) {
                synchronized (lock) {
                    cleanTries.remove(entryKey(copy.marked));
                }
                new Clean(id, copy).run();
            } else {
                host.log("Source preparation: the better copy isn't whole yet; trying next play");
            }
        }
    }

    // ── What's kept between sessions ────────────────────────────────────────

    private JSONObject saved() {
        synchronized (lock) {
            if (saved != null) return saved;
            try {
                String text = host.read();
                saved = text == null || text.isEmpty() ? new JSONObject() : new JSONObject(text);
            } catch (Exception unreadable) {
                saved = new JSONObject();
            }
            return saved;
        }
    }

    private JSONObject knownCopy(String id) {
        synchronized (lock) {
            JSONObject known = saved().optJSONObject("known3");
            if (known == null) return null;
            try {
                JSONObject record = new JSONObject(known.optString(id));
                String marker = record.optString("marker");
                return marker.matches("hifi[a-f0-9]{32}") ? record : null;
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private boolean isSaved(String section, String key) {
        synchronized (lock) {
            JSONObject found = saved().optJSONObject(section);
            return found != null && found.has(key);
        }
    }

    private void remember(String section, String key, String value) {
        synchronized (lock) {
            try {
                JSONObject all = saved();
                JSONObject found = all.optJSONObject(section);
                if (found == null) all.put(section, found = new JSONObject());
                found.put(key, value == null ? "1" : value);
                if (found.length() > REMEMBERED) {
                    Iterator<String> oldest = found.keys();
                    oldest.next();
                    oldest.remove();
                }
                host.write(all.toString());
            } catch (Exception lost) {
                // It's checked again next time.
            }
        }
    }

    private static String hash(String text) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : Arrays.copyOf(digest, 16)) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception unavailable) {
            throw new IllegalStateException("File identity unavailable", unavailable);
        }
    }

    // ── For its tests, and its end ──────────────────────────────────────────

    boolean idle() {
        synchronized (lock) {
            return running == null && waiting.isEmpty();
        }
    }

    boolean started() {
        synchronized (lock) {
            return worker != null;
        }
    }

    boolean alive() {
        synchronized (lock) {
            return worker != null && worker.isAlive();
        }
    }

    int kept() {
        synchronized (lock) {
            return prepared.size();
        }
    }

    void close() {
        Thread thread;
        synchronized (lock) {
            closed = true;
            for (Work work : waiting) work.cancel();
            waiting.clear();
            if (running != null) running.cancel();
            thread = worker;
            lock.notifyAll();
        }
        if (thread != null) thread.interrupt();
        host.detach(this);
    }
}
