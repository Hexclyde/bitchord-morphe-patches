package app.bitchord.telegram;

import android.content.Context;
import android.net.Uri;
import android.os.Looper;

import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Smart playlists from what's already on this device. Runs only when asked, needs no account, key
 * or connection, searches no source, uploads nothing and changes nothing: the caller reviews the
 * songs and saves them through {@link MixedPlaylists}.
 *
 * <p>The approach follows PixelMusic's local playlist generator (see {@link LocalPlaylistEngine}).
 */
public final class LocalPlaylist {
    private LocalPlaylist() {}

    static final int MAX_DESCRIPTION = LocalPlaylistEngine.MAX_DESCRIPTION;
    static final int MAX_TRACKS = 200;

    /** Why a list came back empty or short. Each one tells the user what they can do about it. */
    enum Problem {
        NO_DESCRIPTION,
        LONG_DESCRIPTION,
        EMPTY_LIBRARY,
        LIBRARY_UNAVAILABLE,
        NO_MATCH,
        NEEDS_TAGS,
        NO_SIMILAR
    }

    /** A chosen song with the evidence that put it there. */
    static final class Song {
        final Object song;
        final Set<LocalPlaylistEngine.Why> why;
        final Double bpm;

        Song(LocalPlaylistEngine.Pick pick) {
            song = pick.entry.song;
            why = Collections.unmodifiableSet(pick.why);
            bpm = pick.entry.bpm;
        }
    }

    static final class Result {
        final List<Song> picks;
        final List<Object> songs;

        /** Songs nothing contradicts and nothing supports. Only ever offered, never included. */
        final List<Object> unverified;

        final int available, matched, contradicted, disliked, explicit;

        /** Parts of the library this BitChord couldn't show. The rest was still used. */
        final int unreadableParts;

        final Problem problem;

        private Result(
                LocalPlaylistEngine.Ranking ranking,
                int available,
                int unreadableParts,
                Problem problem) {
            List<Song> chosen = new ArrayList<>();
            List<Object> objects = new ArrayList<>(), open = new ArrayList<>();
            if (ranking != null) {
                for (LocalPlaylistEngine.Pick pick : ranking.picks) {
                    chosen.add(new Song(pick));
                    objects.add(pick.entry.song);
                }
                for (LocalPlaylistEngine.Entry entry : ranking.unverified) open.add(entry.song);
            }
            picks = Collections.unmodifiableList(chosen);
            songs = Collections.unmodifiableList(objects);
            unverified = Collections.unmodifiableList(open);
            this.available = available;
            matched = ranking == null ? 0 : ranking.matched;
            contradicted = ranking == null ? 0 : ranking.contradicted;
            disliked = ranking == null ? 0 : ranking.disliked;
            explicit = ranking == null ? 0 : ranking.explicit;
            this.unreadableParts = unreadableParts;
            this.problem = problem;
        }

        /** What to tell the user, or null when there's nothing to explain. */
        String message() {
            return problem == null ? null : message(problem);
        }

        static String message(Problem problem) {
            switch (problem) {
                case NO_DESCRIPTION:
                    return Strings.t("Describe the playlist you want.");
                case LONG_DESCRIPTION:
                    return Strings.t("Describe the playlist in 1,000 characters or fewer.");
                case EMPTY_LIBRARY:
                    return Strings.t(
                            "Nothing is saved on this device yet. Save songs to a playlist, play"
                                    + " some music or allow access to your files, then try again.");
                case LIBRARY_UNAVAILABLE:
                    return Strings.t("This version of BitChord doesn't expose its library.");
                case NO_MATCH:
                    return Strings.t(
                            "No saved songs match that. Try an artist, genre or mood from your"
                                    + " library.");
                case NEEDS_TAGS:
                    return Strings.t(
                            "Your saved songs don't have the tags this needs. You can still review"
                                    + " songs without tags.");
                case NO_SIMILAR:
                    return Strings.t(
                            "Your library doesn't have enough tags or playlists to find similar"
                                    + " artists.");
                default:
                    return null;
            }
        }

        static String reason(LocalPlaylistEngine.Why why, Double bpm) {
            switch (why) {
                case ARTIST:
                    return Strings.t("Matches the artist");
                case TITLE:
                    return Strings.t("Matches the title");
                case ALBUM:
                    return Strings.t("Matches the album");
                case PLAYLIST:
                    return Strings.t("From a matching playlist");
                case GENRE:
                    return Strings.t("Matches the genre");
                case ARTIST_GENRE:
                    return Strings.t("This artist's other songs match the genre");
                case YEAR:
                    return Strings.t("Matches the year");
                case TEMPO_TAGGED:
                    return Strings.f("Tagged {0} BPM", String.valueOf(Math.round(bpm)));
                case TEMPO_MEASURED:
                    return Strings.f("Measured {0} BPM", String.valueOf(Math.round(bpm)));
                case ENERGY_ESTIMATE:
                    return Strings.t("Its genre suggests this energy");
                case SIMILAR_GENRE:
                    return Strings.t("Similar genre");
                case SIMILAR_PLAYLIST:
                    return Strings.t("Shares playlists with this artist");
                case LIKED:
                    return Strings.t("Liked");
                case PLAYED:
                    return Strings.t("Played often");
                case UNPLAYED:
                    return Strings.t("Not played yet");
                case RECENT:
                    return Strings.t("Recently added");
                default:
                    return null;
            }
        }
    }

    /**
     * The chosen songs, best first: real BitChord songs from this device's own library. Empty when
     * nothing fits; {@link #run} says why. Call away from the main thread.
     *
     * @throws InterruptedException when {@code cancelled} says so or the thread is interrupted
     */
    public static List<Object> generate(
            Context app,
            String description,
            int requestedCount,
            boolean familiar,
            boolean avoidExplicit,
            BooleanSupplier cancelled)
            throws Exception {
        return new ArrayList<>(
                run(app, description, requestedCount, familiar, avoidExplicit, cancelled).songs);
    }

    /** {@link #generate} with its reasons, counts and any problem. Same threading and limits. */
    static Result run(
            Context app,
            String description,
            int requestedCount,
            boolean familiar,
            boolean avoidExplicit,
            BooleanSupplier cancelled)
            throws Exception {
        if (app == null) throw new IllegalArgumentException("Missing context");
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Local playlists can't run on the main thread");
        Problem invalid = validate(description);
        if (invalid != null) return new Result(null, 0, 0, invalid);
        Context context = app.getApplicationContext();
        return from(
                LocalPlaylistLibrary.collect(
                        new LocalPlaylistLibrary.Reflected(context, cancelled), cancelled),
                description,
                requestedCount,
                familiar,
                avoidExplicit,
                cancelled,
                entries ->
                        LocalPlaylistLibrary.addTempo(
                                entries,
                                LocalPlaylistLibrary::djModeBpm,
                                location ->
                                        context.getContentResolver()
                                                .openInputStream(Uri.parse(location)),
                                cancelled));
    }

    interface Tempos {
        void add(List<LocalPlaylistEngine.Entry> entries) throws InterruptedException;
    }

    static Problem validate(String description) {
        if (description == null || description.trim().isEmpty()) return Problem.NO_DESCRIPTION;
        return description.length() > MAX_DESCRIPTION ? Problem.LONG_DESCRIPTION : null;
    }

    /** Ranks an already read library. Tempos are only looked up for requests that care. */
    static Result from(
            LocalPlaylistLibrary.Library library,
            String description,
            int requestedCount,
            boolean familiar,
            boolean avoidExplicit,
            BooleanSupplier cancelled,
            Tempos tempos)
            throws InterruptedException {
        Problem invalid = validate(description);
        if (invalid != null) return new Result(null, 0, library.unreadable, invalid);
        int count = Math.max(1, Math.min(MAX_TRACKS, requestedCount));
        if (library.entries.isEmpty())
            return new Result(
                    null,
                    0,
                    library.unreadable,
                    library.unavailable() ? Problem.LIBRARY_UNAVAILABLE : Problem.EMPTY_LIBRARY);
        LocalPlaylistEngine.Index index = LocalPlaylistEngine.index(library.entries, cancelled);
        LocalPlaylistEngine.Query query = LocalPlaylistEngine.parse(description, index);
        if (query.wantsTempo() && tempos != null) {
            tempos.add(library.entries);
            LocalPlaylistEngine.check(cancelled);
        }
        LocalPlaylistEngine.Ranking ranking =
                LocalPlaylistEngine.rank(query, index, count, familiar, avoidExplicit, cancelled);
        Problem problem = null;
        if (ranking.picks.isEmpty()) {
            if (ranking.similarUnsupported) problem = Problem.NO_SIMILAR;
            else if (!ranking.unverified.isEmpty()) problem = Problem.NEEDS_TAGS;
            else problem = Problem.NO_MATCH;
        }
        return new Result(ranking, index.size(), library.unreadable, problem);
    }
}
