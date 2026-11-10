package app.bitchord.telegram;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which source answers a music search. Telegram is asked first whether or not the saved index is
 * on, empty or still building; the index answers only when Telegram can't, or when Telegram has
 * nothing and the index does.
 */
final class SearchPlan {
    private SearchPlan() {}

    /** Asks Telegram. */
    interface Remote {
        LiveSearch.Result search(String query) throws Exception;
    }

    /** Asks the saved index; null when it has nothing to say for these chats. */
    interface Saved {
        List<JSONObject> search(String query, boolean complete) throws Exception;
    }

    /** At most this many shorter queries are tried when Telegram finds nothing for the full one. */
    static final int ALTERNATES = 2;

    /**
     * Shorter queries for a search Telegram answered with nothing. Telegram matches whole words in
     * the post's own text, so the full "artist title" can miss a file whose artist sits in the
     * album's caption; the title words alone find it, and the caller keeps only rows that carry
     * every word.
     */
    static List<String> broaden(String query) {
        List<String> out = new ArrayList<>();
        String relaxed = TelegramIndex.withoutContractions(query);
        if (relaxed != null) out.add(relaxed);
        List<String> words = TrackNames.words(relaxed == null ? query : relaxed);
        if (words.size() >= 3) {
            int half = words.size() / 2;
            for (String part :
                    new String[] {
                        String.join(" ", words.subList(half, words.size())),
                        String.join(" ", words.subList(0, half))
                    }) if (!out.contains(part) && !part.equals(query)) out.add(part);
        }
        return out.size() > ALTERNATES ? new ArrayList<>(out.subList(0, ALTERNATES)) : out;
    }

    /** Foreground: Telegram first. Background: a finished index first, and never a partial one. */
    static LiveSearch.Result run(
            String query,
            boolean background,
            java.util.Collection<Long> chats,
            Remote remote,
            Saved saved,
            Misses misses)
            throws Exception {
        SearchReport scope = SearchReport.scoped(chats);
        if (background) {
            List<JSONObject> done = saved.search(query, true);
            if (done != null) return answer(done, scope, SearchReport.Origin.INDEX_BACKGROUND, "");
            if (misses != null && misses.known(query))
                return answer(
                        Collections.<JSONObject>emptyList(),
                        scope.with(Collections.singletonMap("known-miss", 1)),
                        SearchReport.Origin.LIVE,
                        "");
            LiveSearch.Result found = remote.search(query);
            if (found.tracks.isEmpty() && found.warning.isEmpty() && misses != null)
                misses.add(query);
            return found;
        }
        LiveSearch.Result found;
        try {
            found = remote.search(query);
        } catch (Exception e) {
            List<JSONObject> rows = saved.search(query, false);
            if (rows == null) throw e;
            return answer(rows, scope, SearchReport.Origin.INDEX_FALLBACK, SearchReport.causeOf(e));
        }
        if (!found.tracks.isEmpty()) return found;
        List<String> wanted = TrackNames.words(query);
        SearchReport counted = found.report;
        String failure = "";
        for (String shorter : broaden(query)) {
            LiveSearch.Result again;
            try {
                again = remote.search(shorter);
            } catch (Exception e) {
                failure = SearchReport.causeOf(e);
                counted = counted.with(Collections.singletonMap("search-" + failure, 1));
                break;
            }
            counted = counted.plus(again.report);
            List<JSONObject> kept = new ArrayList<>();
            int dropped = 0;
            for (JSONObject row : again.tracks)
                if (TelegramIndex.matches(row, wanted)) kept.add(row);
                else dropped++;
            if (!kept.isEmpty()) {
                Map<String, Integer> why = new LinkedHashMap<>();
                if (dropped > 0) why.put("missing-query-words", dropped);
                return new LiveSearch.Result(
                        kept,
                        again.warning,
                        counted.answeredBy(SearchReport.Origin.LIVE_BROADENED, "", kept.size())
                                .with(why));
            }
            counted = counted.with(Collections.singletonMap("missing-query-words", dropped));
        }
        List<JSONObject> rows = saved.search(query, false);
        if (rows != null && !rows.isEmpty())
            return answer(rows, counted, SearchReport.Origin.INDEX_AFTER_EMPTY, "");
        // A shorter search that failed is not an answer of "nothing": say so and don't cache it.
        String warning = failure.isEmpty() ? found.warning : LiveSearch.PARTIAL;
        return new LiveSearch.Result(found.tracks, warning, counted);
    }

    private static LiveSearch.Result answer(
            List<JSONObject> rows, SearchReport base, SearchReport.Origin origin, String why) {
        return new LiveSearch.Result(rows, "", base.answeredBy(origin, why, rows.size()));
    }

    /**
     * Searches Telegram answered with nothing, remembered briefly so a queue of songs doesn't ask
     * again for the same one. A catalogue or chat change drops them all, so a song posted since is
     * found.
     */
    static final class Misses {
        private static final int MAX = 64;
        private final LiveSearch.Clock clock;
        private final long ttlMillis;
        private final LinkedHashMap<String, Long> entries = new LinkedHashMap<>();
        private String scope = "";

        Misses(LiveSearch.Clock clock, long ttlMillis) {
            this.clock = clock;
            this.ttlMillis = ttlMillis;
        }

        /** Names the chats and catalogue revision the misses belong to; a new scope drops them. */
        synchronized void scope(long revision, Set<Long> chats) {
            String now = revision + "/" + new TreeSet<>(chats);
            if (!now.equals(scope)) entries.clear();
            scope = now;
        }

        synchronized boolean known(String query) {
            Long until = entries.get(key(query));
            if (until == null) return false;
            if (until > clock.millis()) return true;
            entries.remove(key(query));
            return false;
        }

        synchronized void add(String query) {
            entries.put(key(query), clock.millis() + ttlMillis);
            while (entries.size() > MAX) entries.remove(entries.keySet().iterator().next());
        }

        synchronized void clear() {
            entries.clear();
        }

        private static String key(String query) {
            return String.join(" ", TrackNames.words(query));
        }
    }
}
