package app.bitchord.telegram;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * Everything the user already has, read without changing anything: device and offline playlists,
 * liked songs, the queue, downloads, listening statistics and files on the device. Each part is
 * optional. A BitChord that doesn't have one just contributes less, and the songs keep the source
 * identity they were saved with.
 */
final class LocalPlaylistLibrary {
    private LocalPlaylistLibrary() {}

    static final int MAX_SONGS = LocalPlaylistEngine.MAX_ENTRIES;
    private static final int MAX_PLAYLISTS = 2000;
    private static final int MAX_HISTORY_FILES = 36;
    private static final long MAX_HISTORY_BYTES = 8L * 1024 * 1024;
    private static final int MAX_MEDIA_ROWS = MAX_SONGS;
    private static final int MAX_TAG_FILES = 300;
    private static final long TAG_BUDGET_NANOS = 6_000_000_000L;
    private static final long HOST_TIMEOUT_MS = 15_000;
    private static final long QUEUE_TIMEOUT_MS = 2_000;
    private static final int YEAR_MIN = 1000;

    /** A playlist's name and rows. {@code liked} marks BitChord's own liked songs list. */
    static final class Shelf {
        final String title;
        final List<?> songs;
        final boolean liked;

        Shelf(String title, List<?> songs, boolean liked) {
            this.title = title;
            this.songs = songs;
            this.liked = liked;
        }
    }

    /** One song's added-up row from the listening statistics. */
    static final class Listened {
        final String id;
        String title = "", artist = "", album, albumId, artistId, art;
        int plays;
        long last;

        Listened(String id) {
            this.id = id;
        }
    }

    /** Year and genre as a device's media library has them. Null when it doesn't. */
    static final class MediaTags {
        final Integer year;
        final String genre;

        MediaTags(Integer year, String genre) {
            this.year = year;
            this.genre = genre;
        }
    }

    /** The parts of the library, so reading and combining can be tested separately. */
    interface Host {
        List<Shelf> playlists() throws Exception;

        List<Shelf> offlinePlaylists() throws Exception;

        List<Object> queue() throws Exception;

        List<Object> downloads() throws Exception;

        Map<String, Listened> listening() throws Exception;

        Map<String, String> likes() throws Exception;

        List<Object> localFiles() throws Exception;

        Map<String, MediaTags> mediaTags() throws Exception;
    }

    static final class Library {
        final List<LocalPlaylistEngine.Entry> entries = new ArrayList<>();
        int readable, unreadable;
        boolean listeningKnown, storesReadable;

        /** BitChord's own stores couldn't be read, as opposed to simply holding nothing yet. */
        boolean unavailable() {
            return entries.isEmpty() && !storesReadable;
        }
    }

    // ── Combining ───────────────────────────────────────────────────────────

    private static final class Row {
        Object song;
        String id, uri, title = "", artist = "", album = "";
        final Set<String> playlists = new LinkedHashSet<>();
        Boolean explicit;
        boolean favorite, disliked;
        Long added, duration;
        int plays;

        LocalPlaylistEngine.Entry entry(boolean playsKnown, Map<String, MediaTags> media) {
            String identity;
            try {
                identity = MixedSongCodec.key(song);
            } catch (RuntimeException noIdentity) {
                return null;
            }
            LocalPlaylistEngine.Entry entry =
                    new LocalPlaylistEngine.Entry(song, identity, title, artist, album)
                            .explicit(explicit)
                            .favorite(favorite ? Boolean.TRUE : null)
                            .disliked(disliked)
                            .added(added)
                            .plays(playsKnown ? Integer.valueOf(plays) : null);
            entry.location = uri;
            entry.durationSeconds = duration;
            entry.playlists.addAll(playlists);
            MediaTags tags = uri == null ? null : media.get(uri);
            if (tags == null && uri != null) tags = media.get(mediaId(uri));
            if (tags != null) {
                entry.year = tags.year;
                entry.genre = tags.genre;
            }
            return entry;
        }
    }

    /** Rows that share an id or a file location are one song, whichever part they came from. */
    private static final class Merge {
        final Map<String, Row> byKey = new HashMap<>();
        final Set<Row> rows = new LinkedHashSet<>();

        Row add(Object song) {
            String id = MixedSongCodec.text(song, "videoId"),
                    uri = MixedSongCodec.text(song, "localUri");
            if (id.isEmpty() && uri.isEmpty()) return null;
            Row byId = id.isEmpty() ? null : byKey.get("id:" + id);
            Row byUri = uri.isEmpty() ? null : byKey.get("uri:" + uri);
            Row row = byId != null ? byId : byUri;
            if (row == null) {
                if (rows.size() >= MAX_SONGS) return null;
                row = new Row();
                row.song = song;
                rows.add(row);
            } else if (byId != null && byUri != null && byId != byUri) absorb(byId, byUri);
            else if (row.uri != null && uri.isEmpty() && id.equals(row.id)) {
                // A stream row outlives its downloaded copy, and plays from it while it exists.
                row.song = song;
            }
            if (row.id == null && !id.isEmpty()) row.id = id;
            if (row.uri == null && !uri.isEmpty()) row.uri = uri;
            if (!id.isEmpty()) byKey.put("id:" + id, row);
            if (!uri.isEmpty()) byKey.put("uri:" + uri, row);
            describe(row, song);
            return row;
        }

        private void absorb(Row keep, Row gone) {
            keep.playlists.addAll(gone.playlists);
            keep.favorite |= gone.favorite;
            keep.disliked |= gone.disliked;
            keep.plays += gone.plays;
            if (keep.uri == null) keep.uri = gone.uri;
            if (keep.album.isEmpty()) keep.album = gone.album;
            if (keep.artist.isEmpty()) keep.artist = gone.artist;
            if (keep.added == null) keep.added = gone.added;
            if (keep.explicit == null) keep.explicit = gone.explicit;
            byKey.replaceAll((key, row) -> row == gone ? keep : row);
            rows.remove(gone);
        }

        private void describe(Row row, Object song) {
            if (row.title.isEmpty()) row.title = MixedSongCodec.text(song, "title");
            if (row.artist.isEmpty()) row.artist = knownArtist(MixedSongCodec.text(song, "artist"));
            if (row.album.isEmpty()) row.album = MixedSongCodec.text(song, "albumName");
            if (row.explicit == null) {
                String explicit = MixedSongCodec.text(song, "isExplicit");
                if (!explicit.isEmpty()) row.explicit = Boolean.valueOf(explicit);
            }
            if (row.added == null)
                row.added = number(MixedSongCodec.text(song, "localDateAddedSeconds"));
            if (row.duration == null)
                row.duration = seconds(MixedSongCodec.text(song, "durationText"));
        }
    }

    /** Media libraries write "no artist" as a name. It isn't one. */
    static String knownArtist(String artist) {
        String folded = artist == null ? "" : artist.trim().toLowerCase(Locale.ROOT);
        return folded.equals("<unknown>")
                        || folded.equals("unknown")
                        || folded.equals("unknown artist")
                ? ""
                : artist.trim();
    }

    private static Long number(String value) {
        if (value.isEmpty()) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException unknown) {
            return null;
        }
    }

    static Long seconds(String durationText) {
        if (durationText == null || !durationText.matches("\\d{1,4}(?::\\d{2}){1,2}")) return null;
        long total = 0;
        for (String part : durationText.split(":")) total = total * 60 + Long.parseLong(part);
        return total;
    }

    private interface Part<T> {
        T read() throws Exception;
    }

    private static <T> T part(String name, Part<T> part, T missing, Library library)
            throws InterruptedException {
        try {
            T value = part.read();
            library.readable++;
            return value;
        } catch (InterruptedException cancelled) {
            throw cancelled;
        } catch (Exception | LinkageError unavailable) {
            library.unreadable++;
            HostLog.d("Local playlists couldn't read " + name + ": " + unavailable);
            return missing;
        }
    }

    /** Reads each part once, in an order that keeps an already saved row's identity. */
    static Library collect(Host host, BooleanSupplier cancelled) throws InterruptedException {
        Library library = new Library();
        Merge merge = new Merge();
        List<Shelf> shelves = new ArrayList<>();
        int failed = library.unreadable;
        shelves.addAll(part("playlists", host::playlists, Collections.emptyList(), library));
        library.storesReadable = library.unreadable == failed;
        LocalPlaylistEngine.check(cancelled);
        shelves.addAll(
                part(
                        "offline playlists",
                        host::offlinePlaylists,
                        Collections.emptyList(),
                        library));
        LocalPlaylistEngine.check(cancelled);
        int count = 0;
        for (Shelf shelf : shelves) {
            if (count++ >= MAX_PLAYLISTS) break;
            LocalPlaylistEngine.check(cancelled);
            for (Object song : shelf.songs) {
                Row row = merge.add(song);
                if (row == null) continue;
                if (shelf.liked) row.favorite = true;
                else if (shelf.title != null) row.playlists.add(shelf.title);
            }
        }
        for (Object song : part("queue", host::queue, Collections.emptyList(), library))
            merge.add(song);
        LocalPlaylistEngine.check(cancelled);
        failed = library.unreadable;
        for (Object song : part("downloads", host::downloads, Collections.emptyList(), library))
            merge.add(song);
        library.storesReadable |= library.unreadable == failed;
        LocalPlaylistEngine.check(cancelled);
        for (Object song : part("local files", host::localFiles, Collections.emptyList(), library))
            merge.add(song);
        LocalPlaylistEngine.check(cancelled);
        Map<String, Listened> listening = part("listening", host::listening, null, library);
        library.listeningKnown = listening != null;
        if (listening != null)
            for (Listened track : listening.values()) {
                Row row = merge.byKey.get("id:" + track.id);
                if (row == null && listeningOnly(track)) row = addListened(merge, track);
                if (row != null) row.plays = saturated((long) row.plays + track.plays);
            }
        LocalPlaylistEngine.check(cancelled);
        Map<String, String> likes = part("likes", host::likes, Collections.emptyMap(), library);
        for (Map.Entry<String, String> like : likes.entrySet()) {
            Row row = merge.byKey.get("id:" + like.getKey());
            if (row == null) continue;
            if ("LIKE".equals(like.getValue())) row.favorite = true;
            else if ("DISLIKE".equals(like.getValue())) row.disliked = true;
        }
        Map<String, MediaTags> media =
                part("media tags", host::mediaTags, Collections.emptyMap(), library);
        LocalPlaylistEngine.check(cancelled);
        for (Row row : merge.rows) {
            if (row.title.isEmpty()) continue;
            LocalPlaylistEngine.Entry entry = row.entry(library.listeningKnown, media);
            if (entry != null) library.entries.add(entry);
        }
        return library;
    }

    private static int saturated(long value) {
        return (int) Math.min(Integer.MAX_VALUE, value);
    }

    /** Only a source song or a catalogue song can be played again from just its statistics row. */
    private static boolean listeningOnly(Listened track) {
        return !track.title.trim().isEmpty() && SmartDownloads.eligibleId(track.id);
    }

    private static Row addListened(Merge merge, Listened track) {
        try {
            JSONObject json =
                    new JSONObject()
                            .put("videoId", track.id)
                            .put("title", track.title)
                            .put("artist", track.artist);
            if (track.art != null) json.put("thumbnailUrl", track.art);
            if (track.album != null) json.put("albumName", track.album);
            if (track.albumId != null) json.put("albumId", track.albumId);
            if (track.artistId != null) json.put("artistId", track.artistId);
            return merge.add(MixedSongCodec.decode(json));
        } catch (Exception | LinkageError unusable) {
            return null;
        }
    }

    // ── Listening statistics ────────────────────────────────────────────────

    /** Added-up rows of BitChord's monthly statistics files, newest files first and bounded. */
    static Map<String, Listened> readListening(File directory, BooleanSupplier cancelled)
            throws Exception {
        Map<String, Listened> tracks = new HashMap<>();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return tracks;
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        File parent = directory.getCanonicalFile();
        int read = 0;
        for (File file : files) {
            if (read >= MAX_HISTORY_FILES) break;
            LocalPlaylistEngine.check(cancelled);
            if (!file.isFile()
                    || file.length() > MAX_HISTORY_BYTES
                    || !parent.equals(file.getCanonicalFile().getParentFile())) continue;
            read++;
            try {
                addListening(
                        tracks,
                        new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            } catch (Exception damaged) {
                HostLog.d("Local playlists skipped a statistics file: " + file.getName());
            }
        }
        return tracks;
    }

    static void addListening(Map<String, Listened> tracks, String json) throws Exception {
        JSONArray list = new JSONObject(json).optJSONArray("tracks");
        if (list == null) return;
        for (int i = 0; i < list.length(); i++) {
            JSONObject row = list.optJSONObject(i);
            if (row == null) continue;
            String id = row.optString("id");
            if (id.isEmpty() || id.length() > 8192) continue;
            Listened track = tracks.get(id);
            if (track == null) tracks.put(id, track = new Listened(id));
            track.plays = saturated((long) track.plays + Math.max(0, row.optInt("plays")));
            long last = row.optLong("last");
            if (last >= track.last) {
                track.last = last;
                track.title = row.optString("title", track.title);
                track.artist = row.optString("artist", track.artist);
                track.album = text(row, "album", track.album);
                track.albumId = text(row, "albumId", track.albumId);
                track.artistId = text(row, "artistId", track.artistId);
                track.art = text(row, "art", track.art);
            } else {
                // Files are read newest first; an older one only fills in what's still missing.
                if (track.title.isEmpty()) track.title = row.optString("title", "");
                if (track.artist.isEmpty()) track.artist = row.optString("artist", "");
                if (track.album == null) track.album = text(row, "album", null);
                if (track.albumId == null) track.albumId = text(row, "albumId", null);
                if (track.artistId == null) track.artistId = text(row, "artistId", null);
                if (track.art == null) track.art = text(row, "art", null);
            }
        }
    }

    private static String text(JSONObject row, String name, String fallback) {
        String value = row.isNull(name) ? "" : row.optString(name, "");
        return value.isEmpty() ? fallback : value;
    }

    // ── Reading BitChord ────────────────────────────────────────────────────

    /** The real BitChord. The class names are parameters so fixtures can stand in for them. */
    static final class Reflected implements Host {
        static final String OFFLINE = "com.music.bitchord.data.OfflinePlaylists",
                LIKES = "com.music.bitchord.data.LikeState",
                DOWNLOADS = "com.music.bitchord.download.Downloads",
                LOCAL_MEDIA = "com.music.bitchord.data.LocalMediaRepository";

        private final Context app;
        private final BooleanSupplier cancelled;
        private final String offline, likes, downloads, localMedia;

        Reflected(Context app, BooleanSupplier cancelled) {
            this(app, cancelled, OFFLINE, LIKES, DOWNLOADS, LOCAL_MEDIA);
        }

        Reflected(
                Context app,
                BooleanSupplier cancelled,
                String offline,
                String likes,
                String downloads,
                String localMedia) {
            this.app = app;
            this.cancelled = cancelled;
            this.offline = offline;
            this.likes = likes;
            this.downloads = downloads;
            this.localMedia = localMedia;
        }

        @Override
        public List<Shelf> playlists() throws Exception {
            List<Shelf> shelves = new ArrayList<>();
            for (Object playlist : MixedPlaylists.nativePlaylists())
                shelves.add(
                        new Shelf(
                                MixedSongCodec.text(playlist, "title"),
                                MixedPlaylists.songs(playlist),
                                false));
            return shelves;
        }

        @Override
        public List<Shelf> offlinePlaylists() throws Exception {
            Class<?> type = Class.forName(offline);
            List<Shelf> shelves = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            addOffline(shelves, seen, flow(type, "_liked"), true);
            addOffline(shelves, seen, flow(type, "_playlists"), false);
            return shelves;
        }

        private void addOffline(List<Shelf> out, Set<String> seen, Object value, boolean liked) {
            if (value == null) return;
            if (!(value instanceof List)) {
                addPlaylist(out, seen, value, liked);
                return;
            }
            List<?> list = (List<?>) value;
            if (!list.isEmpty() && !hasSongs(list.get(0))) {
                if (liked) out.add(new Shelf(null, new ArrayList<>(list), true));
                return;
            }
            for (Object playlist : list) addPlaylist(out, seen, playlist, liked);
        }

        private void addPlaylist(
                List<Shelf> out, Set<String> seen, Object playlist, boolean liked) {
            if (!hasSongs(playlist)) return;
            String id = MixedSongCodec.text(playlist, "id");
            if (!id.isEmpty() && !seen.add(id)) return;
            boolean isLiked = liked;
            try {
                isLiked |=
                        Boolean.TRUE.equals(
                                playlist.getClass().getMethod("isLiked").invoke(playlist));
            } catch (ReflectiveOperationException absent) {
                // Its title is still usable, and the likes list is read separately.
            }
            try {
                out.add(
                        new Shelf(
                                MixedSongCodec.text(playlist, "title"),
                                new ArrayList<>((List<?>) MixedSongCodec.field(playlist, "songs")),
                                isLiked));
            } catch (Exception unreadable) {
                HostLog.d("Local playlists skipped an offline playlist");
            }
        }

        private static boolean hasSongs(Object value) {
            try {
                return MixedSongCodec.field(value, "songs") instanceof List;
            } catch (Exception no) {
                return false;
            }
        }

        @Override
        public List<Object> queue() throws Exception {
            FutureTask<List<Object>> read = new FutureTask<>(GuestPlaylists::queue);
            // Media3 only answers on the thread it runs on.
            new Handler(Looper.getMainLooper()).post(read);
            try {
                return finish(read, QUEUE_TIMEOUT_MS, cancelled);
            } catch (IllegalStateException nothingPlaying) {
                return Collections.emptyList();
            }
        }

        @Override
        public List<Object> downloads() throws Exception {
            Class<?> type = Class.forName(downloads);
            Object saved = flow(type, "_saved"), metadata;
            metadata = flow(type, "_savedMetadata");
            if (!(saved instanceof Map) || !(metadata instanceof Map))
                throw new ReflectiveOperationException("Unknown downloads shape");
            List<Object> songs = new ArrayList<>();
            for (Map.Entry<?, ?> item : ((Map<?, ?>) saved).entrySet()) {
                if (!(item.getKey() instanceof String) || !(item.getValue() instanceof String))
                    continue;
                Object song =
                        savedSong(
                                (String) item.getKey(),
                                (String) item.getValue(),
                                ((Map<?, ?>) metadata).get(item.getKey()));
                if (song != null) songs.add(song);
            }
            return songs;
        }

        @Override
        public Map<String, Listened> listening() throws Exception {
            return readListening(new File(app.getFilesDir(), "listening"), cancelled);
        }

        @Override
        public Map<String, String> likes() throws Exception {
            Object value = flow(Class.forName(likes), "_overrides");
            if (!(value instanceof Map)) throw new ReflectiveOperationException("Unknown likes");
            Map<String, String> statuses = new HashMap<>();
            for (Map.Entry<?, ?> like : ((Map<?, ?>) value).entrySet())
                if (like.getKey() instanceof String && like.getValue() != null)
                    statuses.put((String) like.getKey(), String.valueOf(like.getValue()));
            return statuses;
        }

        @Override
        public List<Object> localFiles() throws Exception {
            Class<?> type = Class.forName(localMedia);
            if (!Boolean.TRUE.equals(
                    type.getMethod("hasStoragePermission", Context.class).invoke(null, app)))
                return Collections.emptyList();
            Method read =
                    type.getMethod(
                            "getLocalMusic",
                            Context.class,
                            kotlin.coroutines.jvm.internal.ContinuationImpl.class);
            FutureTask<Object> task =
                    new FutureTask<>(() -> VideoSwitch.await(step -> read.invoke(null, app, step)));
            Thread thread = new Thread(task, "bitchord-local-playlist");
            thread.setDaemon(true);
            thread.start();
            Object files = finish(task, HOST_TIMEOUT_MS, cancelled);
            return files instanceof List
                    ? new ArrayList<>((List<?>) files)
                    : Collections.emptyList();
        }

        @Override
        public Map<String, MediaTags> mediaTags() throws Exception {
            try {
                return readMediaTags(app, cancelled);
            } catch (SecurityException notAllowed) {
                return Collections.emptyMap();
            }
        }
    }

    private static Object flow(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        Object flow = field.get(null);
        if (flow == null) return null;
        Method read = flow.getClass().getMethod("getValue");
        read.setAccessible(true);
        return read.invoke(flow);
    }

    /** A saved download as BitChord describes it. Without a description there's no song to name. */
    static Object savedSong(String id, String uri, Object metadata) throws Exception {
        if (metadata == null || uri == null || uri.isEmpty()) return null;
        String title = MixedSongCodec.text(metadata, "title");
        if (title.trim().isEmpty()) return null;
        if (missingFile(uri)) return null;
        String videoId = MixedSongCodec.text(metadata, "videoId");
        JSONObject json =
                new JSONObject()
                        .put("videoId", videoId.isEmpty() ? id : videoId)
                        .put("title", title)
                        .put("artist", MixedSongCodec.text(metadata, "artist"))
                        .put("localUri", uri);
        copy(json, metadata, "thumbnailUrl", "thumbnailUrl");
        copy(json, metadata, "durationText", "durationText");
        copy(json, metadata, "albumName", "albumName");
        copy(json, metadata, "downloadFormat", "downloadFormat");
        String added = MixedSongCodec.text(metadata, "dateAddedSeconds");
        if (added.matches("\\d{1,18}")) json.put("localDateAddedSeconds", Long.parseLong(added));
        return MixedSongCodec.decode(json);
    }

    private static boolean missingFile(String uri) {
        if (!uri.startsWith("file:")) return false;
        String path = Uri.parse(uri).getPath();
        return path == null || !new File(path).exists();
    }

    private static void copy(JSONObject json, Object metadata, String from, String to)
            throws Exception {
        String value = MixedSongCodec.text(metadata, from);
        if (!value.isEmpty()) json.put(to, value);
    }

    private static <T> T finish(Future<T> task, long millis, BooleanSupplier cancelled)
            throws Exception {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (true) {
            try {
                LocalPlaylistEngine.check(cancelled);
                return task.get(50, TimeUnit.MILLISECONDS);
            } catch (TimeoutException waiting) {
                if (System.nanoTime() - deadline > 0) {
                    task.cancel(true);
                    throw new TimeoutException("Local library read took too long");
                }
            } catch (InterruptedException stop) {
                task.cancel(true);
                throw stop;
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof Exception) throw (Exception) failed.getCause();
                if (failed.getCause() instanceof LinkageError)
                    throw (LinkageError) failed.getCause();
                throw failed;
            }
        }
    }

    // ── Device media library ────────────────────────────────────────────────

    /** The digits of a media library address, which stay the same however it's written. */
    static String mediaId(String uri) {
        int at = uri == null ? -1 : uri.indexOf("/audio/media/");
        if (at < 0) return "";
        int from = at + 13, to = from;
        while (to < uri.length() && Character.isDigit(uri.charAt(to))) to++;
        return to == from ? "" : "media:" + uri.substring(from, to);
    }

    /**
     * Year and genre, which BitChord's Song doesn't carry. Missing, zero and placeholder values
     * stay unknown. The media library has no tempo column; {@link #addTempo} covers that.
     */
    static Map<String, MediaTags> readMediaTags(Context app, BooleanSupplier cancelled)
            throws Exception {
        Map<String, MediaTags> tags = new HashMap<>();
        boolean withGenre = Build.VERSION.SDK_INT >= 30;
        String[] columns =
                withGenre
                        ? new String[] {
                            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.YEAR, "genre"
                        }
                        : new String[] {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.YEAR};
        try (Cursor rows =
                app.getContentResolver()
                        .query(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                columns,
                                null,
                                null,
                                null)) {
            if (rows == null) return tags;
            int count = 0;
            while (rows.moveToNext() && count++ < MAX_MEDIA_ROWS) {
                if (count % 256 == 0) LocalPlaylistEngine.check(cancelled);
                MediaTags row =
                        new MediaTags(
                                knownYear(rows.isNull(1) ? 0 : rows.getInt(1)),
                                withGenre && !rows.isNull(2)
                                        ? knownGenre(rows.getString(2))
                                        : null);
                if (row.year != null || row.genre != null)
                    tags.put("media:" + rows.getLong(0), row);
            }
        }
        return tags;
    }

    static Integer knownYear(int year) {
        return year >= YEAR_MIN && year <= Calendar.getInstance().get(Calendar.YEAR) + 1
                ? Integer.valueOf(year)
                : null;
    }

    static String knownGenre(String genre) {
        String clean = genre == null ? "" : genre.trim();
        String lower = clean.toLowerCase(Locale.ROOT);
        return clean.isEmpty()
                        || lower.equals("<unknown>")
                        || lower.equals("unknown")
                        || lower.equals("other")
                        || lower.equals("music")
                ? null
                : clean;
    }

    // ── Tempo, only for requests that care ──────────────────────────────────

    interface Measured {
        Double bpm(String id);
    }

    interface Opener {
        InputStream open(String location) throws Exception;
    }

    /**
     * Adds known tempos in place: analyses already on the device first, then tags of local files.
     * Bounded in files and time, and nothing is analysed here. What can't be read stays unknown.
     */
    static void addTempo(
            List<LocalPlaylistEngine.Entry> entries,
            Measured measured,
            Opener opener,
            BooleanSupplier cancelled)
            throws InterruptedException {
        long deadline = System.nanoTime() + TAG_BUDGET_NANOS;
        int opened = 0;
        for (LocalPlaylistEngine.Entry entry : entries) {
            LocalPlaylistEngine.check(cancelled);
            String id = MixedSongCodec.text(entry.song, "videoId");
            Double known = id.isEmpty() ? null : plausible(measured, id);
            if (known != null) {
                entry.bpm(known, LocalPlaylistEngine.Tempo.MEASURED);
                continue;
            }
            if (entry.location == null
                    || opened >= MAX_TAG_FILES
                    || System.nanoTime() - deadline > 0) continue;
            opened++;
            try (InputStream input = opener.open(entry.location)) {
                Double tagged = input == null ? null : LocalPlaylistTags.bpm(input);
                if (tagged != null) entry.bpm(tagged, LocalPlaylistEngine.Tempo.TAGGED);
            } catch (Exception | LinkageError unreadable) {
                // A file that moved or lost its permission just has no known tempo.
            }
        }
    }

    private static Double plausible(Measured measured, String id) {
        try {
            Double bpm = measured.bpm(id);
            return bpm == null ? null : LocalPlaylistTags.plausible(bpm);
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    /** What DJ mode has already worked out, including what it took from Automix. */
    static Double djModeBpm(String id) {
        DjMode.Info info = DjMode.info(id);
        return info == null || info.bpm <= 0 ? null : info.bpm;
    }
}
