package app.bitchord.telegram;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DataSpec;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plays a song from somewhere other than YouTube Music while YouTube Music is off. A saved copy
 * comes first, then a file in the device's music library that is the same recording, then the best
 * copy any enabled source has. Source and quality settings are never written.
 */
public final class SourceFallback {
    private SourceFallback() {}

    /**
     * How long a song with no copy anywhere is remembered, so BitChord's own retries stay quick.
     */
    static final long NONE_MS = 30_000L;

    static final long WAIT_MS = 20_000L;
    static volatile long waitMs = WAIT_MS;
    private static final long RETRY_MIN_MS = 500L;

    /** The call mask BitChord's own substitute lookup passes: everything after the request. */
    private static final int BEST_DEFAULTS = 1016;

    static final long LIBRARY_WAIT_MS = 8_000L;
    static final long LIBRARY_LOAD_MS = 15_000L;
    private static final long LIBRARY_MS = 300_000L;
    private static final int MAX_REJECTS = 3;
    private static final int MAX_ENTRIES = 64;
    private static final int TOLERANCE_SECONDS = 3;

    private static final String SOURCES = "com.music.bitchord.data.sources.";
    private static final String LOCAL_MEDIA = "com.music.bitchord.data.LocalMediaRepository";

    /** One row of the device's music library, as BitChord lists it. */
    static final class Row {
        final String title, artist, uri;
        final int seconds;

        Row(String title, String artist, int seconds, String uri) {
            this.title = title;
            this.artist = artist;
            this.seconds = seconds;
            this.uri = uri;
        }
    }

    /** What the fallback needs from BitChord. Tests answer these directly. */
    interface Host {
        /** BitChord's own playback list, with the FLAC and source-first wrappers applied. */
        ArrayList<Object> ranked();

        /** Every enabled, complete source in rank order, whatever the quality setting says. */
        List<Object> enabled();

        Object instance(String configId);

        String configId(Object source);

        /** The best enabled copy of the song, or null. Ends early once [stale] is true. */
        Object lookup(Object target, List<Object> sources, BooleanSupplier stale) throws Exception;

        Object module(Object stream) throws Exception;

        /** A saved download that is still on the phone, or null. */
        String saved(String mediaId);

        /** True when the music library may be read without asking. */
        boolean storage();

        /** Reads the music library. Called off the main thread only. */
        List<Row> library() throws Exception;

        /** True when the file can be opened for reading. Called off the main thread only. */
        boolean readable(String uri);

        /** Calls [changed] whenever the music library changes. Called once. */
        void watch(Runnable changed);

        long now();
    }

    /**
     * No copy of the song exists in any enabled place. A FileNotFoundException because BitChord's
     * retry policy treats that as final, so Steady playback never retries it while offline.
     */
    static final class NoAudio extends java.io.FileNotFoundException {
        final String mediaId;

        NoAudio(String mediaId) {
            super(Strings.t("No audio track found"));
            this.mediaId = mediaId;
        }
    }

    /** A file on the phone has the song; the player swaps its item for the file. */
    static final class LocalMatch extends java.io.FileNotFoundException {
        final String mediaId, uri;

        LocalMatch(String mediaId, String uri) {
            super("The song is a file on this phone");
            this.mediaId = mediaId;
            this.uri = uri;
        }
    }

    private static final Object lock = new Object();
    private static volatile Host host = new Reflected();
    private static volatile int generation;
    private static final Map<String, Long> none = lru();
    private static final Map<String, Long> noFile = lru();
    private static final Map<String, Object[]> hits = lru();
    private static final Map<String, Set<String>> rejected = lru();
    private static Object[] snapshot;
    private static FutureTask<Object[]> loading;
    private static int libraryEpoch;
    private static boolean watching;
    private static boolean logged;

    private static <V> Map<String, V> lru() {
        return new LinkedHashMap<String, V>(16, .75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > MAX_ENTRIES;
            }
        };
    }

    // ── Hooks ───────────────────────────────────────────────────────────────

    /**
     * Replaces BitChord's playback source list where it ranks YouTube against the others. While
     * YouTube Music is off the list holds every enabled source, then YouTube's own entry last, so
     * every other source ranks above it. Does no I/O.
     */
    public static ArrayList<Object> playbackSources() {
        Host h = host;
        ArrayList<Object> ranked = h.ranked();
        try {
            if (!YouTubePolicy.ready() || !YouTubePolicy.playbackBlocked()) return ranked;
            String youtube = YouTubePolicy.youtubeId();
            ArrayList<Object> out = alternates(h, ranked, youtube);
            Object entry = youtube == null ? null : h.instance(youtube);
            if (entry != null) out.add(entry);
            return out;
        } catch (Throwable e) {
            if (!logged) {
                logged = true;
                HostLog.d("Source fallback list failed: " + e.getClass().getSimpleName());
            }
            return ranked;
        }
    }

    /** Enabled sources other than YouTube: BitChord's own order first, then the rest. */
    private static ArrayList<Object> alternates(Host h, ArrayList<Object> ranked, String youtube) {
        ArrayList<Object> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Object> rest = new ArrayList<>(ranked);
        rest.addAll(h.enabled());
        for (Object source : rest) {
            String id = h.configId(source);
            if (id == null || id.equals(youtube) || !seen.add(id)) continue;
            out.add(source);
        }
        return out;
    }

    /**
     * Called first in BitChord's resolver for a YouTube song. Returns null to let BitChord decide
     * as usual. While YouTube Music is off it returns the best copy from another source, or throws:
     * {@link LocalMatch} when a file on the phone has the song, {@link NoAudio} when nothing does.
     * Runs on a loader thread.
     */
    public static Object resolve(String videoId, Object target) throws IOException {
        if (!YouTubePolicy.playbackBlocked()) return null;
        Host h = host;
        int started = generation;
        BooleanSupplier stale = () -> generation != started;
        try {
            if (recentlyNone(videoId)) throw new NoAudio(videoId);
            String title = text(target, "title"), artist = text(target, "artist");
            int seconds = target == null ? 0 : seconds(field(target, "durationSec"));
            String file = find(videoId, title, artist, seconds);
            if (file != null) {
                YouTubePolicy.recheck();
                throw new LocalMatch(videoId, file);
            }
            if (stale.getAsBoolean()) throw new InterruptedIOException("Source settings changed");
            String youtube = YouTubePolicy.youtubeId();
            Object stream = null;
            if (!alternates(h, h.ranked(), youtube).isEmpty() && target != null)
                stream = h.lookup(target, alternates(h, h.ranked(), youtube), stale);
            if (stale.getAsBoolean()) throw new InterruptedIOException("Source settings changed");
            if (stream != null) return h.module(stream);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Lookup interrupted");
        } catch (CancellationException e) {
            throw new InterruptedIOException("Source settings changed");
        } catch (IOException e) {
            throw e;
        } catch (Exception | LinkageError e) {
            HostLog.d("Source fallback lookup failed: " + e.getClass().getSimpleName());
        }
        synchronized (lock) {
            if (generation == started) none.put(videoId, h.now());
        }
        throw new NoAudio(videoId);
    }

    /**
     * Called above the cache while YouTube Music is off. With no other source enabled the native
     * path would ask YouTube for the address before any refusal, so a song that only a file can
     * serve is decided here: a saved or library file ends as {@link LocalMatch}, nothing ends as
     * {@link NoAudio}. Verified cache from another source and direct files open as usual. Runs on a
     * loader thread; the main thread is never made to wait.
     */
    static void beforeOpen(DataSpec spec, String origin) throws IOException {
        Uri uri = spec.uri;
        if (uri == null
                || !"bitchord".equals(uri.getScheme())
                || !"watch".equals(uri.getAuthority())
                || "OTHER".equals(origin)
                || Looper.myLooper() == Looper.getMainLooper()
                || hasAlternates()) return;
        String id = query(uri, "v"), length = query(uri, "d");
        if (id == null || id.isEmpty()) return;
        try {
            String file =
                    find(
                            id,
                            query(uri, "n"),
                            query(uri, "a"),
                            length == null ? 0 : seconds(length));
            if (file == null) throw new NoAudio(id);
            YouTubePolicy.recheck();
            throw new LocalMatch(id, file);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Lookup interrupted");
        }
    }

    /** Which place in the queue this is: the entry id BitChord gave it, or none. */
    static String entry(MediaItem item) {
        if (item == null) return "";
        String id =
                item.mediaMetadata == null || item.mediaMetadata.extras == null
                        ? null
                        : item.mediaMetadata.extras.getString("bitchord.queueEntryId");
        Uri uri = uri(item);
        if ((id == null || id.isEmpty()) && uri != null) id = query(uri, "bc_entry");
        return id == null ? "" : id;
    }

    // ── Reading the outcome ─────────────────────────────────────────────────

    /** The {@link NoAudio} behind a player error, or null. */
    static NoAudio noAudio(Throwable error) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause())
            if (error instanceof NoAudio) return (NoAudio) error;
        return null;
    }

    static LocalMatch localMatch(Throwable error) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause())
            if (error instanceof LocalMatch) return (LocalMatch) error;
        return null;
    }

    /** The song's id as BitChord queues it: the watch address's `v`, else the item's own id. */
    static String mediaId(MediaItem item) {
        Uri uri = uri(item);
        String v = uri == null || !"bitchord".equals(uri.getScheme()) ? null : query(uri, "v");
        return v != null && !v.isEmpty() ? v : item == null ? null : item.mediaId;
    }

    private static Uri uri(MediaItem item) {
        return item == null || item.localConfiguration == null ? null : item.localConfiguration.uri;
    }

    private static String query(Uri uri, String name) {
        try {
            return uri.getQueryParameter(name);
        } catch (RuntimeException opaque) {
            return null;
        }
    }

    // ── Copies on the phone ─────────────────────────────────────────────────

    /** A saved file or already verified library file for this item, or null. Never blocks. */
    static String cached(MediaItem item) {
        String id = mediaId(item);
        if (id == null) return null;
        Host h = host;
        String saved = usable(id, h.saved(id));
        if (saved != null) return saved;
        if (rejects(id) >= MAX_REJECTS || !h.storage()) return null;
        synchronized (lock) {
            Object[] hit = hits.get(id);
            return hit == null
                            || h.now() - (Long) hit[1] > LIBRARY_MS
                            || skipped(id, (String) hit[0])
                    ? null
                    : (String) hit[0];
        }
    }

    /**
     * Looks for the song on a background thread and calls [found] if a file has it, else [missed]
     * (which may be null). Reads nothing when the library may not be read, and never asks for
     * permission.
     */
    static void warm(MediaItem item, Runnable found, Runnable missed) {
        String id = mediaId(item);
        Uri uri = uri(item);
        if (id == null || uri == null) return;
        String title = query(uri, "n"), artist = query(uri, "a"), length = query(uri, "d");
        Thread thread =
                new Thread(
                        () -> {
                            try {
                                String file =
                                        find(
                                                id,
                                                title,
                                                artist,
                                                length == null ? 0 : seconds(length));
                                Runnable done = file != null ? found : missed;
                                if (done != null) done.run();
                            } catch (InterruptedException ignored) {
                                // Nothing asked for the result any more.
                            }
                        },
                        "bitchord-local-match");
        thread.setDaemon(true);
        thread.start();
    }

    /** True when at least one source other than YouTube Music is enabled. */
    static boolean hasAlternates() {
        try {
            Host h = host;
            return !alternates(h, h.ranked(), YouTubePolicy.youtubeId()).isEmpty();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * A file for the song: the saved download of this id, else a library file that is the same
     * recording. Waits for the library to be read, so only background threads may call it.
     */
    static String find(String id, String title, String artist, int seconds)
            throws InterruptedException {
        Host h = host;
        String saved = usable(id, h.saved(id));
        if (saved != null) return saved;
        if (rejects(id) >= MAX_REJECTS || !h.storage()) return null;
        int started = generation;
        synchronized (lock) {
            Object[] hit = hits.get(id);
            if (hit != null
                    && h.now() - (Long) hit[1] <= LIBRARY_MS
                    && !skipped(id, (String) hit[0])) return (String) hit[0];
            Long absent = noFile.get(id);
            if (absent != null && h.now() - absent <= NONE_MS) return null;
        }
        Object[] library = library(LIBRARY_WAIT_MS);
        if (library == null) return null;
        @SuppressWarnings("unchecked")
        List<Row> rows = (List<Row>) library[0];
        String match = match(rows, title, artist, seconds, h::readable, skippedUris(id));
        synchronized (lock) {
            if (generation == started)
                if (match == null) noFile.put(id, h.now());
                else hits.put(id, new Object[] {match, h.now()});
        }
        return match;
    }

    private static String usable(String id, String uri) {
        return uri == null || uri.isEmpty() || skipped(id, uri) ? null : uri;
    }

    /** Records that the player could not read this file, so it is not offered again. */
    static void reject(String mediaId, String uri) {
        synchronized (lock) {
            Set<String> set = rejected.get(mediaId);
            if (set == null) rejected.put(mediaId, set = new HashSet<>());
            set.add(uri);
            hits.remove(mediaId);
        }
    }

    private static int rejects(String id) {
        synchronized (lock) {
            Set<String> set = rejected.get(id);
            return set == null ? 0 : set.size();
        }
    }

    private static boolean skipped(String id, String uri) {
        synchronized (lock) {
            Set<String> set = rejected.get(id);
            return set != null && set.contains(uri);
        }
    }

    private static Set<String> skippedUris(String id) {
        synchronized (lock) {
            Set<String> set = rejected.get(id);
            return set == null ? new HashSet<>() : new HashSet<>(set);
        }
    }

    private static boolean recentlyNone(String id) {
        synchronized (lock) {
            Long at = none.get(id);
            return at != null && host.now() - at <= NONE_MS;
        }
    }

    // ── The music library ───────────────────────────────────────────────────

    /** The library rows with the time they were read, shared by every caller that asks at once. */
    private static Object[] library(long wait) throws InterruptedException {
        FutureTask<Object[]> task;
        Host h = host;
        synchronized (lock) {
            if (snapshot != null && h.now() - (Long) snapshot[1] <= LIBRARY_MS) return snapshot;
            if (loading == null) {
                int epoch = libraryEpoch;
                if (!watching) {
                    watching = true;
                    h.watch(SourceFallback::libraryChanged);
                }
                FutureTask<Object[]> started =
                        new FutureTask<Object[]>(
                                () -> {
                                    Object[] read = new Object[] {h.library(), h.now()};
                                    synchronized (lock) {
                                        if (epoch == libraryEpoch) snapshot = read;
                                    }
                                    return read;
                                }) {
                            @Override
                            protected void done() {
                                synchronized (lock) {
                                    if (loading == this) loading = null;
                                }
                            }
                        };
                loading = started;
                Thread thread = new Thread(started, "bitchord-local-library");
                thread.setDaemon(true);
                thread.start();
            }
            task = loading;
        }
        try {
            return task.get(wait, TimeUnit.MILLISECONDS);
        } catch (TimeoutException slow) {
            HostLog.d("Music library read took too long");
            return null;
        } catch (ExecutionException failed) {
            HostLog.d("Music library read failed: " + failed.getCause());
            return null;
        }
    }

    /** The music library changed, so what was read or found is out of date. */
    static void libraryChanged() {
        synchronized (lock) {
            libraryEpoch++;
            snapshot = null;
            loading = null;
            hits.clear();
            none.clear();
            noFile.clear();
            generation++;
        }
    }

    /** The source settings changed: lookups in flight stop and "nothing found" is forgotten. */
    static void forget() {
        synchronized (lock) {
            none.clear();
            noFile.clear();
            generation++;
        }
    }

    // ── Matching ────────────────────────────────────────────────────────────

    /**
     * The first readable file that is the same recording, closest in length first. The title must
     * match word for word apart from credits and video labels, and both must carry the same version
     * words (live, remix, demo and so on). The artists must share a name, and the lengths must
     * agree within a few seconds, so a missing tag never matches.
     */
    static String match(
            List<Row> rows,
            String title,
            String artist,
            int seconds,
            Predicate<String> readable,
            Set<String> skip) {
        String[] wanted = titleKey(title);
        Set<String> names = artists(artist);
        if (wanted == null || names.isEmpty() || seconds <= 0) return null;
        List<Row> close = new ArrayList<>();
        for (Row row : rows) {
            if (row.seconds <= 0
                    || Math.abs(row.seconds - seconds) > TOLERANCE_SECONDS
                    || row.uri == null
                    || skip.contains(row.uri)) continue;
            String[] key = titleKey(row.title);
            if (key == null || !key[0].equals(wanted[0]) || !key[1].equals(wanted[1])) continue;
            Set<String> other = artists(row.artist);
            other.retainAll(names);
            if (!other.isEmpty()) close.add(row);
        }
        close.sort((a, b) -> Math.abs(a.seconds - seconds) - Math.abs(b.seconds - seconds));
        for (Row row : close) if (readable.test(row.uri)) return row.uri;
        return null;
    }

    private static final Set<String> VERSIONS =
            new HashSet<>(
                    Arrays.asList(
                            "live",
                            "remix",
                            "remixed",
                            "rmx",
                            "demo",
                            "acoustic",
                            "instrumental",
                            "karaoke",
                            "cover",
                            "slowed",
                            "reverb",
                            "sped",
                            "speed",
                            "nightcore",
                            "edit",
                            "mix",
                            "version",
                            "ver",
                            "remaster",
                            "remastered",
                            "mono",
                            "radio",
                            "extended",
                            "unplugged",
                            "session",
                            "sessions",
                            "acapella",
                            "cappella",
                            "bootleg",
                            "mashup",
                            "tribute",
                            "orchestral",
                            "piano",
                            "rework",
                            "flip",
                            "vip",
                            "dub",
                            "8d",
                            "boosted",
                            "reprise",
                            "stripped",
                            "rehearsal",
                            "outtake",
                            "alternate",
                            "alt",
                            "remake",
                            "lofi",
                            "chopped",
                            "screwed",
                            "stems",
                            "commentary"));
    private static final Set<String> LABELS =
            new HashSet<>(
                    Arrays.asList(
                            "official",
                            "video",
                            "audio",
                            "music",
                            "lyrics",
                            "lyric",
                            "visualizer",
                            "visualiser",
                            "hd",
                            "hq",
                            "4k",
                            "1080p",
                            "mv"));
    private static final Pattern CREDIT =
            Pattern.compile("[(\\[{]\\s*(feat|ft|featuring|with)\\b[^)\\]}]*[)\\]}]");
    private static final Pattern TRAILING = Pattern.compile("\\b(feat|ft|featuring)\\b.*$");
    private static final Pattern GROUP = Pattern.compile("[(\\[{]([^)\\]}]*)[)\\]}]");

    /** The words of a title and the sorted version words in it, or null when it has no words. */
    static String[] titleKey(String title) {
        if (title == null) return null;
        String s = fold(title);
        s = CREDIT.matcher(s).replaceAll(" ");
        s = TRAILING.matcher(s).replaceAll(" ");
        StringBuffer plain = new StringBuffer();
        Matcher group = GROUP.matcher(s);
        while (group.find()) {
            StringBuilder kept = new StringBuilder(" ");
            for (String word : words(group.group(1)))
                if (!LABELS.contains(word)) kept.append(word).append(' ');
            group.appendReplacement(plain, Matcher.quoteReplacement(kept.toString()));
        }
        group.appendTail(plain);
        List<String> core = new ArrayList<>();
        Set<String> versions = new TreeSet<>();
        for (String word : words(plain.toString()))
            if (VERSIONS.contains(word)) versions.add(word);
            else core.add(word);
        return core.isEmpty() ? null : new String[] {String.join(" ", core), versions.toString()};
    }

    /** The separate artist names in a credit; empty when none is given. */
    static Set<String> artists(String credit) {
        Set<String> names = new HashSet<>();
        if (credit == null) return names;
        String s = fold(credit).replaceAll("\\s-\\stopic$", "");
        s =
                s.replaceAll("\\s(feat|ft|featuring|with|and|x)\\.?\\s", ",")
                        .replaceAll("[;/+&,]", ",");
        for (String part : s.split(",")) {
            String name = String.join(" ", words(part));
            if (!name.isEmpty() && !name.equals("unknown") && !name.equals("various artists"))
                names.add(name);
        }
        return names;
    }

    private static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        for (String word : text.split("[^\\p{L}\\p{N}]+")) if (!word.isEmpty()) words.add(word);
        return words;
    }

    /** Seconds in a length written 3:41 or 1:02:03, or a plain count of seconds. */
    static int seconds(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null) return 0;
        int total = 0;
        try {
            for (String part : value.toString().trim().split(":"))
                total = total * 60 + Integer.parseInt(part.trim());
        } catch (NumberFormatException unreadable) {
            return 0;
        }
        return total;
    }

    private static Object field(Object value, String name) {
        try {
            Field field = value.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(value);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }

    private static String text(Object value, String name) {
        Object found = value == null ? null : field(value, name);
        return found == null ? "" : found.toString();
    }

    // ── Test seams ──────────────────────────────────────────────────────────

    static Host reflected() {
        return new Reflected();
    }

    static void use(Host replacement) {
        host = replacement == null ? new Reflected() : replacement;
    }

    static void reset() {
        synchronized (lock) {
            generation++;
            libraryEpoch++;
            none.clear();
            noFile.clear();
            hits.clear();
            rejected.clear();
            snapshot = null;
            loading = null;
            watching = false;
            logged = false;
            waitMs = WAIT_MS;
        }
        host = new Reflected();
    }

    // ── BitChord ────────────────────────────────────────────────────────────

    /** BitChord's own classes, reached by name so nothing links against them. */
    private static final class Reflected implements Host {
        private Method ranked, active, lookup;
        private Field instances;
        private Constructor<?> module;

        @Override
        public ArrayList<Object> ranked() {
            try {
                if (ranked == null)
                    ranked =
                            Class.forName(SOURCES + "SourceRegistry")
                                    .getMethod("activeForPlayback");
                @SuppressWarnings("unchecked")
                ArrayList<Object> list = (ArrayList<Object>) ranked.invoke(null);
                return list;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Playback source list changed", e);
            }
        }

        @Override
        public List<Object> enabled() {
            try {
                if (active == null)
                    active = Class.forName(SOURCES + "SourceRegistry").getMethod("active");
                ArrayList<?> list = (ArrayList<?>) active.invoke(null);
                // The download-only FLAC add-on never plays; the class is shared by every build.
                return FlacAddon.playbackSources(list);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Source list changed", e);
            }
        }

        @Override
        public Object instance(String configId) {
            try {
                if (instances == null) {
                    instances =
                            Class.forName(SOURCES + "SourceRegistry").getDeclaredField("instances");
                    instances.setAccessible(true);
                }
                Object map = instances.get(null);
                return map instanceof Map ? ((Map<?, ?>) map).get(configId) : null;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Source instances changed", e);
            }
        }

        @Override
        public String configId(Object source) {
            try {
                return (String)
                        Class.forName(SOURCES + "MusicSource")
                                .getMethod("getConfigId")
                                .invoke(source);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Source identity changed", e);
            }
        }

        @Override
        public Object lookup(Object target, List<Object> sources, BooleanSupplier stale)
                throws Exception {
            Object resolver =
                    Class.forName(SOURCES + "SourceResolver").getField("INSTANCE").get(null);
            if (lookup == null)
                for (Method method : resolver.getClass().getMethods())
                    if (method.getName().equals("substituteForYouTube")) lookup = method;
            if (lookup == null) throw new NoSuchMethodException("substituteForYouTube");
            Method found = lookup;
            long end = System.nanoTime() + waitMs * 1_000_000L;
            Object first =
                    VideoSwitch.await(step -> found.invoke(resolver, target, step), waitMs, stale);
            long left = (end - System.nanoTime()) / 1_000_000L;
            if (first != null || left < RETRY_MIN_MS || stale.getAsBoolean()) return first;
            // Some providers have audio at Best but none at Lossless. Both asks share one deadline.
            Class<?> type = Class.forName(SOURCES + "SourceResolver");
            Object asked = type.getMethod("requestForNow").invoke(null);
            if (!asked.getClass().getName().endsWith("$Lossless")) return null;
            Object best =
                    Class.forName(SOURCES + "StreamRequest$Best").getField("INSTANCE").get(null);
            Method across = null;
            for (Method method : type.getMethods())
                if (method.getName().equals("bestAcross$app_prodRelease$default")
                        && method.getParameterTypes().length == 11) across = method;
            if (across == null) throw new NoSuchMethodException("bestAcross");
            Method call = across;
            return VideoSwitch.await(
                    step ->
                            call.invoke(
                                    null,
                                    resolver,
                                    sources,
                                    target,
                                    best,
                                    false,
                                    0,
                                    null,
                                    null,
                                    null,
                                    step,
                                    BEST_DEFAULTS),
                    left,
                    stale);
        }

        @Override
        public Object module(Object stream) throws Exception {
            if (module == null)
                module =
                        Class.forName("com.music.bitchord.playback.PlaybackService$Resolved$Module")
                                .getConstructor(Class.forName(SOURCES + "SourceStream"));
            return module.newInstance(stream);
        }

        @Override
        public String saved(String mediaId) {
            try {
                return (String)
                        Class.forName("com.music.bitchord.download.Downloads")
                                .getMethod("verifiedSavedUri", String.class)
                                .invoke(null, mediaId);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }

        @Override
        public boolean storage() {
            Context app = YouTubePolicy.application();
            if (app == null) return false;
            try {
                return Boolean.TRUE.equals(
                        Class.forName(LOCAL_MEDIA)
                                .getMethod("hasStoragePermission", Context.class)
                                .invoke(null, app));
            } catch (ReflectiveOperationException | RuntimeException e) {
                return false;
            }
        }

        @Override
        public List<Row> library() throws Exception {
            Context app = YouTubePolicy.application();
            List<Row> rows = new ArrayList<>();
            if (app == null) return rows;
            Method read =
                    Class.forName(LOCAL_MEDIA)
                            .getMethod(
                                    "getLocalMusic",
                                    Context.class,
                                    kotlin.coroutines.jvm.internal.ContinuationImpl.class);
            Object files =
                    VideoSwitch.await(step -> read.invoke(null, app, step), LIBRARY_LOAD_MS, null);
            if (!(files instanceof List)) throw new IOException("Music library unavailable");
            for (Object song : (List<?>) files) {
                String uri = MixedSongCodec.text(song, "localUri");
                if (uri.isEmpty()) continue;
                rows.add(
                        new Row(
                                MixedSongCodec.text(song, "title"),
                                MixedSongCodec.text(song, "artist"),
                                seconds(MixedSongCodec.text(song, "durationText")),
                                uri));
            }
            return rows;
        }

        @Override
        public boolean readable(String uri) {
            Context app = YouTubePolicy.application();
            try {
                Uri parsed = Uri.parse(uri);
                if ("file".equals(parsed.getScheme())) {
                    File file = new File(parsed.getPath());
                    return file.isFile() && file.canRead();
                }
                if (app == null || !"content".equals(parsed.getScheme())) return false;
                try (ParcelFileDescriptor opened =
                        app.getContentResolver().openFileDescriptor(parsed, "r")) {
                    return opened != null;
                }
            } catch (IOException | RuntimeException unreadable) {
                return false;
            }
        }

        @Override
        public void watch(Runnable changed) {
            Context app = YouTubePolicy.application();
            if (app == null) return;
            try {
                app.getContentResolver()
                        .registerContentObserver(
                                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                true,
                                new ContentObserver(new Handler(Looper.getMainLooper())) {
                                    @Override
                                    public void onChange(boolean selfChange) {
                                        changed.run();
                                    }
                                });
            } catch (RuntimeException unavailable) {
                HostLog.d("Music library changes are not watched");
            }
        }

        @Override
        public long now() {
            return System.nanoTime() / 1_000_000L;
        }
    }
}
