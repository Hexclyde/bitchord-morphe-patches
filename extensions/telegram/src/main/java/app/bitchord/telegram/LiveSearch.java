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
            super("Telegram search rate limit. Retry in " + seconds + " seconds.");
            retrySeconds = Math.max(1, seconds);
        }
    }

    public static final class Result {
        public final List<JSONObject> tracks;
        public final String warning;

        Result(List<JSONObject> tracks, String warning) {
            this.tracks = Collections.unmodifiableList(new ArrayList<>(tracks));
            this.warning = warning;
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
    private final Semaphore searches = new Semaphore(2);
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

    public Result search(Set<Long> selected, String query, long session) throws Exception {
        String q = query == null ? "" : query.trim();
        if (q.length() > 256) throw new IOException("Search text is too long.");
        List<Long> chats = new ArrayList<>(new TreeSet<>(selected));
        chats.remove(0L);
        if (chats.isEmpty()) return new Result(Collections.emptyList(), "");
        if (chats.size() > 100) throw new IOException("Select at most 100 music chats.");
        long currentEpoch = epoch.get();
        String key = session + "/" + currentEpoch + "/" + chats + "/" + q;
        synchronized (cache) {
            Entry entry = cache.get(key);
            if (entry != null && entry.expires > clock.millis()) return entry.result;
            cache.remove(key);
        }
        long remaining = cooldownUntil.get() - clock.millis();
        if (remaining > 0)
            throw new RateLimited((int) Math.min(Integer.MAX_VALUE, (remaining + 999) / 1000));
        CompletableFuture<Result> own = new CompletableFuture<>(),
                other = pending.putIfAbsent(key, own);
        if (other != null) {
            try {
                return other.get(timeoutMillis + 1000, TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                throw cause(e);
            }
        }
        boolean acquired = false;
        try {
            if (!(acquired = searches.tryAcquire()))
                throw new IOException("Telegram search is busy. Try again shortly.");
            Result result = fetch(chats, q);
            if (currentEpoch != epoch.get())
                throw new IOException("Music-chat selection changed. Search again.");
            if (result.warning.isEmpty())
                synchronized (cache) {
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
            pending.remove(key, own);
        }
    }

    private Result fetch(List<Long> chats, String query) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        List<Future<TdApi.FoundChatMessages>> jobs = new ArrayList<>();
        List<Long> jobChats = new ArrayList<>();
        LinkedHashMap<String, JSONObject> unique = new LinkedHashMap<>();
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
                    jobs.add(
                            requests.submit(
                                    () -> {
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
                                            return gateway.search(
                                                    new TdApi.SearchChatMessages(
                                                            chat, null, query, null, 0, 0, 100,
                                                            filter),
                                                    left);
                                        } catch (RateLimited e) {
                                            cooldownUntil.accumulateAndGet(
                                                    clock.millis() + 1000L * e.retrySeconds,
                                                    Math::max);
                                            throw e;
                                        }
                                    }));
                    jobChats.add(chat);
                }
            for (int i = 0; i < jobs.size(); i++) {
                try {
                    Future<TdApi.FoundChatMessages> job = jobs.get(i);
                    long left = deadline - System.nanoTime();
                    TdApi.FoundChatMessages found =
                            job.isDone()
                                    ? job.get()
                                    : job.get(Math.max(0, left), TimeUnit.NANOSECONDS);
                    if (found == null || found.messages == null) continue;
                    for (int n = 0; n < Math.min(100, found.messages.length); n++) {
                        TdApi.Message message = found.messages[n];
                        if (message == null || message.chatId != jobChats.get(i)) continue;
                        JSONObject row = TrackMetadata.from(message);
                        if (row != null && unique.size() < MAX_RESULTS)
                            unique.put(row.getString("id"), row);
                    }
                } catch (ExecutionException e) {
                    problem = cause(e);
                } catch (TimeoutException e) {
                    problem = e;
                }
            }
        } catch (RejectedExecutionException e) {
            problem = new IOException("Telegram search is busy. Try again shortly.", e);
        } finally {
            for (Future<?> job : jobs) if (!job.isDone()) job.cancel(true);
            requests.purge();
        }
        if (unique.isEmpty() && problem != null)
            throw problem instanceof RateLimited
                    ? problem
                    : new IOException(
                            "Telegram search unavailable or timed out. Try again shortly.",
                            problem);
        String warning =
                problem == null
                        ? ""
                        : "Some Telegram searches did not finish. Results are partial; try again or"
                                + " select fewer chats.";
        return new Result(new ArrayList<>(unique.values()), warning);
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
    }
}
