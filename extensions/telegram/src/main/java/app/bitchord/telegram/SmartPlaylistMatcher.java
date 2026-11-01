package app.bitchord.telegram;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Turns suggested metadata into real catalogue songs through installed search. Song identity only
 * ever comes from a search result, and anything short of a confident match waits for review.
 */
final class SmartPlaylistMatcher {
    static final double CONFIDENT = .85;
    private static final int MAX_CANDIDATES = 20;

    private SmartPlaylistMatcher() {}

    enum State {
        SEARCHING,
        MATCHED,
        REVIEW,
        UNMATCHED,
        FAILED,
        SKIPPED
    }

    interface Search {
        List<Object> find(PlaylistImport.Track track) throws Exception;
    }

    interface Best {
        Object choose(PlaylistImport.Track track, List<Object> candidates) throws Exception;
    }

    /** Immutable result of one search, built off the UI thread. */
    static final class Outcome {
        final List<Object> candidates;
        final Object selected;
        final boolean failed;
        final double confidence;

        Outcome(List<Object> candidates, Object selected, boolean failed, double confidence) {
            this.candidates = Collections.unmodifiableList(candidates);
            this.selected = selected;
            this.failed = failed;
            this.confidence = confidence;
        }
    }

    /** UI-thread state for one suggestion; its list position is its fixed playlist position. */
    static final class Row {
        final int position;
        final PlaylistImport.Track track;
        List<Object> candidates = Collections.emptyList();
        Object selected;

        /** The chosen song's score, or the best candidate's while it still awaits review. */
        double confidence;

        boolean searched, failed, reviewed, skipped;

        Row(int position, SmartPlaylistService.Suggestion suggestion) {
            this.position = position;
            track =
                    new PlaylistImport.Track(
                            suggestion.title, suggestion.artist, suggestion.album, null, "", 0);
        }

        void apply(Outcome outcome) {
            candidates = outcome.candidates;
            selected = outcome.selected;
            failed = outcome.failed;
            confidence = outcome.confidence;
            reviewed = outcome.selected != null;
            searched = true;
        }

        void choose(Object song) {
            selected = song;
            confidence = ImportConfidence.score(track, song);
            skipped = false;
            reviewed = true;
        }

        void skip() {
            selected = null;
            skipped = true;
            reviewed = true;
        }

        State state() {
            if (skipped) return State.SKIPPED;
            if (!searched) return State.SEARCHING;
            if (selected != null) return State.MATCHED;
            if (failed) return State.FAILED;
            return candidates.isEmpty() ? State.UNMATCHED : State.REVIEW;
        }
    }

    static Outcome resolve(PlaylistImport.Track track, Search search, Best best)
            throws InterruptedException {
        List<Object> candidates = new ArrayList<>();
        try {
            for (Object song : search.find(track)) {
                if (candidates.size() >= MAX_CANDIDATES) break;
                candidates.add(song);
            }
        } catch (InterruptedException cancelled) {
            throw cancelled;
        } catch (Exception | LinkageError unavailable) {
            return new Outcome(candidates, null, true, 0);
        }
        // Stable, so equally scored candidates keep the search's own order.
        candidates.sort(
                (a, b) ->
                        Double.compare(
                                ImportConfidence.score(track, b),
                                ImportConfidence.score(track, a)));
        Object selected = confident(track, candidates, best);
        Object shown = selected != null || candidates.isEmpty() ? selected : candidates.get(0);
        return new Outcome(
                candidates,
                selected,
                false,
                shown == null ? 0 : ImportConfidence.score(track, shown));
    }

    private static Object confident(
            PlaylistImport.Track track, List<Object> candidates, Best best) {
        if (candidates.isEmpty() || track.artist.isEmpty()) return null;
        try {
            Object chosen = best.choose(track, candidates);
            return candidates.contains(chosen) && ImportConfidence.score(track, chosen) >= CONFIDENT
                    ? chosen
                    : null;
        } catch (Exception | LinkageError unavailable) {
            return null;
        }
    }

    static int unresolved(List<Row> rows) {
        int count = 0;
        for (Row row : rows) if (!row.reviewed) count++;
        return count;
    }

    static boolean ready(List<Row> rows) {
        return unresolved(rows) == 0 && !selection(rows).isEmpty();
    }

    static List<Object> selection(List<Row> rows) {
        List<Object> songs = new ArrayList<>();
        for (Row row : rows) if (row.selected != null && !row.skipped) songs.add(row.selected);
        return songs;
    }

    static void skipUnresolved(List<Row> rows) {
        for (Row row : rows) if (row.searched && !row.reviewed) row.skip();
    }

    /** Searches the catalogue, then each enabled source that offers its own search. */
    static List<Object> installedSearch(PlaylistImport.Track track) throws Exception {
        LinkedHashMap<String, Object> found = new LinkedHashMap<>();
        Exception failure = null;
        try {
            for (Object song : PlaylistImport.search(track))
                found.putIfAbsent(MixedSongCodec.key(song), song);
        } catch (InterruptedException cancelled) {
            throw cancelled;
        } catch (Exception unavailable) {
            failure = unavailable;
        } catch (LinkageError unavailable) {
            failure = new IllegalStateException("Search unavailable");
        }
        try {
            for (Object song : sourceSearch(track))
                found.putIfAbsent(MixedSongCodec.key(song), song);
        } catch (InterruptedException cancelled) {
            throw cancelled;
        } catch (Exception | LinkageError absent) {
            // Sources are optional; the catalogue result above still stands.
        }
        // An empty answer only means "no match" if the catalogue could actually be asked.
        if (found.isEmpty() && failure != null) throw failure;
        return new ArrayList<>(found.values());
    }

    private static List<Object> sourceSearch(PlaylistImport.Track track) throws Exception {
        Class<?> registry = Class.forName("com.music.bitchord.data.sources.SourceRegistry");
        Class<?> contract = Class.forName("com.music.bitchord.data.sources.MusicSource");
        Method search = null;
        for (Method candidate : contract.getMethods())
            if (candidate.getName().equals("search") && candidate.getParameterCount() == 5)
                search = candidate;
        if (search == null) return Collections.emptyList();
        String query = track.artist + " " + track.title;
        List<Object> songs = new ArrayList<>();
        Method method = search;
        for (Object source : (List<?>) SmartDownloadSources.call(registry, "active")) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (FlacAddon.ID.equals(contract.getMethod("getConfigId").invoke(source))) continue;
            try {
                Object result =
                        VideoSwitch.await(
                                step -> method.invoke(source, query, 12, true, null, step));
                if (result instanceof List)
                    for (Object song : (List<?>) result)
                        if (!MixedSongCodec.text(song, "videoId").isEmpty()) songs.add(song);
            } catch (InterruptedException cancelled) {
                throw cancelled;
            } catch (Exception unavailable) {
                // One unreachable source leaves the others usable.
            }
        }
        return songs;
    }
}
