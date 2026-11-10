package app.bitchord.telegram;

import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Server-side search across the selected chats. */
public final class LiveSearch implements AutoCloseable {
    public interface Gateway {
        TdApi.FoundChatMessages search(TdApi.SearchChatMessages request, long timeoutMillis)
                throws Exception;
    }

    public interface Clock {
        long millis();
    }

    public static final class RateLimited extends IOException {
        public final int retrySeconds;

        public RateLimited(int seconds) {
            super(Strings.f("Telegram search rate limit. Retry in {0} seconds.", seconds));
            retrySeconds = Math.max(1, seconds);
        }
    }

    public static final class Result {
        public final List<JSONObject> tracks;
        public final String warning;
        final SearchReport report;

        Result(List<JSONObject> tracks, String warning) {
            this(tracks, warning, SearchReport.NONE);
        }

        Result(List<JSONObject> tracks, String warning, SearchReport report) {
            this.tracks = Collections.unmodifiableList(new ArrayList<>(tracks));
            this.warning = warning;
            this.report = report;
        }
    }

    private static final int MAX_RESULTS = 200, MAX_QUERIES = 8;
    private final Gateway gateway;
    private final Clock clock;
    private final long timeoutMillis;
    private final ThreadPoolExecutor requests =
            new ThreadPoolExecutor(
                    4,
                    4,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<Runnable>(256),
                    r -> {
                        Thread t = new Thread(r, "Telegram search");
                        t.setDaemon(true);
                        return t;
                    });
    // A background catalogue/legend lookup cannot occupy the interactive request queue.
    private final ThreadPoolExecutor backgroundRequests =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<Runnable>(200),
                    r -> {
                        Thread thread = new Thread(r, "Telegram background search");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final Semaphore backgroundSearch = new Semaphore(1);
    private static final int SLOTS = 3;
    private final Semaphore searches = new Semaphore(SLOTS);
    // BitChord's own lookups count here; background searches only run while there are none.
    private final java.util.concurrent.atomic.AtomicInteger foreground =
            new java.util.concurrent.atomic.AtomicInteger();
    static final String PARTIAL =
            "Some Telegram searches did not finish. Results are partial; try again or select"
                    + " fewer chats.";
    static final String BUSY = "Telegram search is busy. Try again shortly.";

    /** How long a lookup for playback waits for a free search before giving up. */
    static final long WAIT_MILLIS = 10000;

    /** Called with the wait in seconds when Telegram starts a new search pause. */
    volatile java.util.function.IntConsumer onRateLimited;

    private final AtomicLong cooldownUntil = new AtomicLong(), epoch = new AtomicLong();
    private final ConcurrentHashMap<String, CompletableFuture<Result>> pending =
            new ConcurrentHashMap<>();

    private static final class Entry {
        final long expires;
        final Result result;

        Entry(long expires, Result result) {
            this.expires = expires;
            this.result = result;
        }
    }

    private final AtomicLong missRevision = new AtomicLong();

    private final LinkedHashMap<String, Entry> cache = new LinkedHashMap<>(16, 0.75f, true);

    public LiveSearch(Gateway gateway) {
        this(gateway, () -> System.nanoTime() / 1000000L, 12000);
    }

    LiveSearch(Gateway gateway, Clock clock, long timeoutMillis) {
        this.gateway = gateway;
        this.clock = clock;
        this.timeoutMillis = timeoutMillis;
    }

    public void clear() {
        epoch.incrementAndGet();
        synchronized (cache) {
            cache.clear();
        }
    }

    /** New catalogue rows invalidate empty answers without cancelling active searches. */
    void clearMisses() {
        synchronized (cache) {
            missRevision.incrementAndGet();
            cache.entrySet().removeIf(e -> e.getValue().result.tracks.isEmpty());
        }
    }

    public Result search(Set<Long> selected, String query, long session) throws Exception {
        return search(selected, query, session, false);
    }

    /**
     * Searches the selected chats. A background search (format legend, name suggestions) never
     * waits and steps aside while BitChord is looking something up; anything else waits for a free
     * search for up to {@link #WAIT_MILLIS}.
     */
    public Result search(Set<Long> selected, String query, long session, boolean background)
            throws Exception {
        try (PerformanceTrace.Span trace = PerformanceTrace.begin(PerformanceTrace.Stage.SEARCH)) {
            try {
                return searchTraced(selected, query, session, background, trace);
            } catch (InterruptedException e) {
                trace.end(PerformanceTrace.Outcome.CANCELLED);
                throw e;
            } catch (Exception e) {
                trace.end(
                        e instanceof TimeoutException
                                ? PerformanceTrace.Outcome.TIMEOUT
                                : PerformanceTrace.Outcome.FAILED);
                throw e;
            }
        }
    }

    private Result searchTraced(
            Set<Long> selected,
            String query,
            long session,
            boolean background,
            PerformanceTrace.Span trace)
            throws Exception {
        String q = query == null ? "" : query.trim();
        if (q.length() > 256) throw new IOException("Search text is too long.");
        List<Long> chats = new ArrayList<>(new TreeSet<>(selected));
        chats.remove(0L);
        if (chats.isEmpty()) return new Result(Collections.emptyList(), "");
        if (chats.size() > 100) throw new IOException("Select at most 100 music chats.");
        long currentEpoch = epoch.get(), currentMissRevision = missRevision.get();
        String key = session + "/" + currentEpoch + "/" + chats + "/" + q;
        synchronized (cache) {
            Entry entry = cache.get(key);
            if (entry != null && entry.expires > clock.millis())
                return new Result(
                        entry.result.tracks, entry.result.warning, entry.result.report.fromCache());
            cache.remove(key);
        }
        long remaining = cooldownUntil.get() - clock.millis();
        if (remaining > 0)
            throw new RateLimited((int) Math.min(Integer.MAX_VALUE, (remaining + 999) / 1000));
        CompletableFuture<Result> own = new CompletableFuture<>(),
                other = pending.putIfAbsent(key, own);
        if (other != null) {
            try {
                try (PerformanceTrace.Span wait =
                        PerformanceTrace.begin(PerformanceTrace.Stage.SEARCH_SLOT, trace)) {
                    return other.get(timeoutMillis + 1000, TimeUnit.MILLISECONDS);
                }
            } catch (ExecutionException e) {
                throw cause(e);
            }
        }
        boolean acquired = false, counted = false, backgroundAcquired = false;
        try {
            PerformanceTrace.Span slot =
                    PerformanceTrace.begin(PerformanceTrace.Stage.SEARCH_SLOT, trace);
            try {
                if (background) {
                    if (foreground.get() > 0
                            || !(backgroundAcquired = backgroundSearch.tryAcquire())
                            || !(acquired = searches.tryAcquire())) throw new IOException(BUSY);
                } else {
                    foreground.incrementAndGet();
                    counted = true;
                    if (!(acquired =
                            searches.tryAcquire(
                                    Math.min(timeoutMillis, WAIT_MILLIS), TimeUnit.MILLISECONDS))) {
                        HostLog.d(
                                "Telegram search for '" + q + "' waited too long for a free slot");
                        throw new IOException(BUSY);
                    }
                }
            } catch (Exception unavailable) {
                slot.end(
                        unavailable instanceof InterruptedException
                                ? PerformanceTrace.Outcome.CANCELLED
                                : PerformanceTrace.Outcome.DEFERRED);
                throw unavailable;
            } finally {
                slot.close();
            }
            Result result = fetch(chats, q, background, trace);
            if (currentEpoch != epoch.get())
                throw new IOException("Music-chat selection changed. Search again.");
            if (result.warning.isEmpty())
                synchronized (cache) {
                    if (!result.tracks.isEmpty() || currentMissRevision == missRevision.get())
                        cache.put(key, new Entry(clock.millis() + 30000, result));
                    while (cache.size() > MAX_QUERIES)
                        cache.remove(cache.keySet().iterator().next());
                }
            own.complete(result);
            return result;
        } catch (Exception e) {
            own.completeExceptionally(e);
            throw e;
        } finally {
            if (acquired) searches.release();
            if (backgroundAcquired) backgroundSearch.release();
            if (counted) foreground.decrementAndGet();
            pending.remove(key, own);
        }
    }

    private Result fetch(
            List<Long> chats, String query, boolean background, PerformanceTrace.Span trace)
            throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        List<Future<TdApi.FoundChatMessages>> jobs = new ArrayList<>();
        Map<Future<TdApi.FoundChatMessages>, Long> jobChats = new HashMap<>();
        CompletionService<TdApi.FoundChatMessages> completed =
                new ExecutorCompletionService<>(background ? backgroundRequests : requests);
        LinkedHashMap<String, JSONObject> unique = new LinkedHashMap<>();
        SearchReport.Builder report = new SearchReport.Builder(chats);
        Exception problem = null;
        try {
            // Search audio across every selected chat, then files: documents are filtered to
            // supported music formats.
            for (int kind = 0; kind < 2; kind++)
                for (long chat : chats) {
                    final TdApi.SearchMessagesFilter filter =
                            kind == 0
                                    ? new TdApi.SearchMessagesFilterAudio()
                                    : new TdApi.SearchMessagesFilterDocument();
                    final long queuedAt = System.nanoTime();
                    Future<TdApi.FoundChatMessages> submitted =
                            completed.submit(
                                    () -> {
                                        if (background && foreground.get() > 0)
                                            throw new IOException(BUSY);
                                        if (cooldownUntil.get() > clock.millis())
                                            throw new RateLimited(
                                                    (int)
                                                            Math.max(
                                                                    1,
                                                                    (cooldownUntil.get()
                                                                                    - clock.millis()
                                                                                    + 999)
                                                                            / 1000));
                                        long left =
                                                TimeUnit.NANOSECONDS.toMillis(
                                                        deadline - System.nanoTime());
                                        if (left <= 0)
                                            throw new TimeoutException(
                                                    "Telegram search timed out.");
                                        try {
                                            try (PerformanceTrace.Span request =
                                                    PerformanceTrace.begin(
                                                            PerformanceTrace.Stage.SEARCH_CHAT,
                                                            trace)) {
                                                request.progress(0, System.nanoTime() - queuedAt);
                                                try {
                                                    return pages(chat, query, filter, deadline);
                                                } catch (InterruptedException e) {
                                                    request.end(PerformanceTrace.Outcome.CANCELLED);
                                                    throw e;
                                                } catch (Exception e) {
                                                    request.end(
                                                            e instanceof TimeoutException
                                                                    ? PerformanceTrace.Outcome
                                                                            .TIMEOUT
                                                                    : PerformanceTrace.Outcome
                                                                            .FAILED);
                                                    throw e;
                                                }
                                            }
                                        } catch (RateLimited e) {
                                            rateLimited(e.retrySeconds);
                                            throw e;
                                        }
                                    });
                    jobs.add(submitted);
                    jobChats.put(submitted, chat);
                }
            for (int i = 0; i < jobs.size(); i++) {
                try {
                    Future<TdApi.FoundChatMessages> job =
                            completed.poll(
                                    Math.max(0, deadline - System.nanoTime()),
                                    TimeUnit.NANOSECONDS);
                    if (job == null) throw new TimeoutException("Telegram search timed out.");
                    TdApi.FoundChatMessages found = job.get();
                    if (found == null || found.messages == null) continue;
                    if (found.totalCount < 0) problem = new IOException("Search page unavailable");
                    for (int n = 0; n < Math.min(MAX_RESULTS, found.messages.length); n++) {
                        TdApi.Message message = found.messages[n];
                        if (message == null || message.chatId != jobChats.get(job)) continue;
                        String why = TrackMetadata.rejection(message);
                        JSONObject row = why.isEmpty() ? TrackMetadata.from(message) : null;
                        if (row == null) {
                            report.rejected(why.isEmpty() ? "unreadable" : why);
                        } else if (unique.size() < MAX_RESULTS) {
                            if (unique.put(row.getString("id"), row) == null) report.accepted();
                            else report.rejected("duplicate");
                        } else report.rejected("over-limit");
                    }
                    if (unique.size() >= MAX_RESULTS) break;
                } catch (ExecutionException e) {
                    problem = cause(e);
                    report.unfinished(SearchReport.causeOf(problem));
                } catch (TimeoutException e) {
                    problem = e;
                    report.unfinished(SearchReport.causeOf(e));
                }
            }
        } catch (InterruptedException cancelled) {
            trace.end(PerformanceTrace.Outcome.CANCELLED);
            throw cancelled;
        } catch (RejectedExecutionException e) {
            problem = new IOException(BUSY, e);
        } finally {
            for (Future<?> job : jobs) if (!job.isDone()) job.cancel(true);
            requests.purge();
            backgroundRequests.purge();
            trace.end(
                    problem == null
                            ? PerformanceTrace.Outcome.COMPLETE
                            : PerformanceTrace.Outcome.FAILED);
        }
        if (unique.isEmpty() && problem != null)
            throw problem instanceof RateLimited
                    ? problem
                    : new IOException(
                            "Telegram search unavailable or timed out. Try again shortly.",
                            problem);
        String warning = problem == null ? "" : PARTIAL;
        return new Result(
                new ArrayList<>(unique.values()), warning, report.build(SearchReport.Origin.LIVE));
    }

    private TdApi.FoundChatMessages pages(
            long chat, String query, TdApi.SearchMessagesFilter filter, long deadline)
            throws Exception {
        List<TdApi.Message> messages = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        long from = 0;
        for (int page = 0; page < 3 && messages.size() < MAX_RESULTS; page++) {
            long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (left <= 0 || Thread.currentThread().isInterrupted()) {
                if (!messages.isEmpty())
                    return new TdApi.FoundChatMessages(
                            -1, messages.toArray(new TdApi.Message[0]), 0);
                throw new TimeoutException("Telegram search timed out.");
            }
            TdApi.FoundChatMessages found;
            try {
                found =
                        gateway.search(
                                new TdApi.SearchChatMessages(
                                        chat, null, query, null, from, 0, 100, filter),
                                left);
            } catch (Exception unavailable) {
                if (unavailable instanceof RateLimited)
                    rateLimited(((RateLimited) unavailable).retrySeconds);
                if (messages.isEmpty()) throw unavailable;
                return new TdApi.FoundChatMessages(-1, messages.toArray(new TdApi.Message[0]), 0);
            }
            if (found == null || found.messages == null) break;
            for (TdApi.Message message : found.messages)
                if (message != null
                        && message.chatId == chat
                        && seen.add(message.id)
                        && messages.size() < MAX_RESULTS) messages.add(message);
            if (found.nextFromMessageId == 0 || found.nextFromMessageId == from) break;
            from = found.nextFromMessageId;
        }
        return new TdApi.FoundChatMessages(
                messages.size(), messages.toArray(new TdApi.Message[0]), 0);
    }

    /**
     * One page of a chat's music for the index: no query, up to 100 messages older than {@code
     * fromMessageId}. Shares the rate-limit pause with searches.
     */
    TdApi.FoundChatMessages page(long chat, int kind, long fromMessageId) throws Exception {
        long remaining = cooldownUntil.get() - clock.millis();
        if (remaining > 0)
            throw new RateLimited((int) Math.min(Integer.MAX_VALUE, (remaining + 999) / 1000));
        try {
            return gateway.search(
                    new TdApi.SearchChatMessages(
                            chat, null, "", null, fromMessageId, 0, 100, filter(kind)),
                    timeoutMillis);
        } catch (RateLimited e) {
            rateLimited(e.retrySeconds);
            throw e;
        }
    }

    /** Metadata lookups share the search pause without taking a search slot. */
    <T> T metadata(java.util.concurrent.Callable<T> request) throws Exception {
        long remaining = cooldownUntil.get() - clock.millis();
        if (remaining > 0)
            throw new RateLimited((int) Math.min(Integer.MAX_VALUE, (remaining + 999) / 1000));
        try {
            return request.call();
        } catch (RateLimited limited) {
            rateLimited(limited.retrySeconds);
            throw limited;
        }
    }

    /** The index's two passes: music sent as audio, then everything sent as a file. */
    static TdApi.SearchMessagesFilter filter(int kind) {
        return kind == 0
                ? new TdApi.SearchMessagesFilterAudio()
                : new TdApi.SearchMessagesFilterDocument();
    }

    /** No search running or waiting, so the index can fetch a page without getting in the way. */
    boolean idle() {
        return foreground.get() == 0 && searches.availablePermits() == SLOTS;
    }

    /** Starts or extends the pause; tells the listener once when a new pause begins. */
    private void rateLimited(int seconds) {
        long now = clock.millis();
        long before = cooldownUntil.getAndAccumulate(now + 1000L * seconds, Math::max);
        if (before > now) return;
        HostLog.d(
                "Telegram asked to pause searches for "
                        + seconds
                        + " s (flood wait); not searching until then");
        java.util.function.IntConsumer listener = onRateLimited;
        if (listener != null)
            try {
                listener.accept(seconds);
            } catch (RuntimeException ignored) {
                // A failed notice mustn't hide the rate limit from the search.
            }
    }

    /** "Telegram API rate limited. Searching again in 45 s." */
    static String rateLimitNotice(int seconds) {
        String wait =
                seconds < 90
                        ? Strings.f("{0} s", seconds)
                        : Strings.f("{0} min", (seconds + 30) / 60);
        return Strings.f("Telegram API rate limited. Searching again in {0}.", wait);
    }

    static Exception cause(ExecutionException e) {
        Throwable cause = e.getCause();
        return cause instanceof Exception
                ? (Exception) cause
                : new IOException("Telegram search failed.");
    }

    @Override
    public void close() {
        clear();
        requests.shutdownNow();
        backgroundRequests.shutdownNow();
    }
}
