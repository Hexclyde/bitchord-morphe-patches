package app.bitchord.telegram;

import java.util.LinkedHashMap;
import java.util.Map;

/** TDLib counters are estimates, not HTTP payload accounting. One counter per file/session. */
final class TelegramProgress {
    private final Map<Integer, Entry> files = new LinkedHashMap<>();

    static final class Entry {
        long session, counter, estimatedNewBytes, resets, version, lastReport;
        String identity;
        PerformanceTrace.Span span;
    }

    synchronized void observe(int file, long session, String identity, long downloaded) {
        if (downloaded < 0) return;
        Entry entry = files.get(file);
        if (entry == null) {
            if (files.size() >= 256) {
                Entry old = files.remove(files.keySet().iterator().next());
                if (old.span != null) old.span.close();
            }
            entry = new Entry();
            files.put(file, entry);
            entry.session = session;
            entry.identity = identity;
            entry.counter = downloaded;
        } else if (entry.session != session
                || !java.util.Objects.equals(entry.identity, identity)
                || downloaded < entry.counter) {
            entry.resets++;
            entry.session = session;
            entry.identity = identity;
            entry.counter = downloaded;
            entry.estimatedNewBytes = 0;
            if (entry.span != null) entry.span.end(PerformanceTrace.Outcome.SUPERSEDED);
            entry.span = null;
        } else {
            entry.estimatedNewBytes += downloaded - entry.counter;
            entry.counter = downloaded;
        }
        entry.version++;
        notifyAll();
        long now = System.nanoTime();
        if (now - entry.lastReport >= 1_000_000_000L) {
            if (entry.span == null || entry.span.ended || entry.span.id == 0)
                entry.span = PerformanceTrace.begin(PerformanceTrace.Stage.TDLIB_PROGRESS_ESTIMATE);
            entry.span.measure("counter_resets", entry.resets);
            entry.span.progress(entry.estimatedNewBytes, 0);
            entry.lastReport = now;
        }
    }

    synchronized long version(int file) {
        Entry entry = files.get(file);
        return entry == null ? 0 : entry.version;
    }

    synchronized long estimated(int file) {
        Entry entry = files.get(file);
        return entry == null ? 0 : entry.estimatedNewBytes;
    }

    synchronized void await(int file, long observed, long millis) throws InterruptedException {
        if (version(file) == observed) wait(Math.max(1, Math.min(100, millis)));
    }
}
