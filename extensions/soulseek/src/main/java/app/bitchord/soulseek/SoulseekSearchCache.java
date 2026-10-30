package app.bitchord.soulseek;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Short-lived initial batches. No credentials, sockets, or persisted discovery state. */
final class SoulseekSearchCache {
    static final int MAX_QUERIES = 16, MAX_ROWS = 200;
    static final int MAX_TOTAL_ROWS = 1200;
    static final long MAX_METADATA_BYTES = 8L * 1024 * 1024;
    static final long TTL_NANOS = 30_000_000_000L;

    interface Clock {
        long now();
    }

    private final Clock clock;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private String scope;

    SoulseekSearchCache() {
        this(System::nanoTime);
    }

    SoulseekSearchCache(Clock clock) {
        this.clock = clock;
    }

    synchronized List<SoulseekTrack> get(String sessionAndPolicy, String query) {
        selectScope(sessionAndPolicy);
        expire();
        Entry entry = entries.get(key(query));
        return entry == null ? null : new ArrayList<>(entry.rows);
    }

    synchronized void put(String sessionAndPolicy, String query, List<SoulseekTrack> rows) {
        selectScope(sessionAndPolicy);
        expire();
        // An empty result should be retryable as peers come online.
        if (rows.isEmpty()) {
            entries.remove(key(query));
            return;
        }
        List<SoulseekTrack> bounded =
                Collections.unmodifiableList(
                        new ArrayList<>(rows.subList(0, Math.min(rows.size(), MAX_ROWS))));
        entries.put(key(query), new Entry(clock.now(), bounded));
        while (overBudget()) entries.remove(entries.keySet().iterator().next());
    }

    synchronized void clear() {
        scope = null;
        entries.clear();
    }

    private void selectScope(String next) {
        if (!next.equals(scope)) {
            entries.clear();
            scope = next;
        }
    }

    private void expire() {
        long now = clock.now();
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            long elapsed = now - iterator.next().getValue().at;
            if (elapsed < 0 || elapsed >= TTL_NANOS) iterator.remove();
        }
    }

    private boolean overBudget() {
        int rows = 0;
        long bytes = 0;
        for (Entry entry : entries.values()) {
            rows += entry.rows.size();
            bytes += entry.bytes;
        }
        return entries.size() > MAX_QUERIES || rows > MAX_TOTAL_ROWS || bytes > MAX_METADATA_BYTES;
    }

    private static String key(String query) {
        // Normalize only the cache key; actual search terms go to Soulseek unchanged.
        return Normalizer.normalize(query.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static final class Entry {
        final long at;
        final List<SoulseekTrack> rows;
        final long bytes;

        Entry(long at, List<SoulseekTrack> rows) {
            this.at = at;
            this.rows = rows;
            long estimated = 0;
            for (SoulseekTrack track : rows)
                estimated +=
                        256L
                                + 2L
                                        * (track.id.length()
                                                + track.peer.length()
                                                + track.path.length()
                                                + track.format.length()
                                                + track.advertisedExtension.length()
                                                + track.title.length()
                                                + track.artist.length()
                                                + track.mime.length());
            bytes = estimated;
        }
    }
}
