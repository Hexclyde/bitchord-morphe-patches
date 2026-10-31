package app.bitchord.telegram;

import android.net.Uri;

import org.json.*;

import java.lang.reflect.*;
import java.util.*;

/** Native, provider-neutral playlist metadata and reviewed YouTube Music matching. */
final class PlaylistImport {
    static final int LIMIT = 5000;

    static final class Track {
        final String title, artist, album, videoId, isrc;
        final long duration;

        Track(
                String title,
                String artist,
                String album,
                String videoId,
                String isrc,
                long duration) {
            this.title = title.trim();
            this.artist = artist.trim();
            this.album = album.trim();
            this.videoId = videoId;
            this.isrc = isrc;
            this.duration = duration;
            if (this.title.isEmpty()
                    || title.length() > 1000
                    || artist.length() > 1000
                    || duration < 0
                    || duration > 86400000)
                throw new IllegalArgumentException("Invalid track metadata");
        }

        Object direct() throws Exception {
            if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) return null;
            return MixedSongCodec.decode(
                    new JSONObject()
                            .put("videoId", videoId)
                            .put("title", title)
                            .put("artist", artist)
                            .put("albumName", album)
                            .put(
                                    "durationText",
                                    duration == 0
                                            ? ""
                                            : duration / 60000
                                                    + ":"
                                                    + String.format(
                                                            Locale.ROOT,
                                                            "%02d",
                                                            duration / 1000 % 60)));
        }
    }

    static final class Playlist {
        final String title;
        final List<Track> tracks = new ArrayList<>();
        int unavailable;

        Playlist(String title) {
            this.title = title.trim();
            if (this.title.isEmpty() || title.length() > 300)
                throw new IllegalArgumentException("Invalid playlist name");
        }
    }

    static String text(JSONObject object, String key) {
        return object.isNull(key) ? "" : object.optString(key, "");
    }

    static List<Playlist> parse(String raw, String filename) throws Exception {
        raw = raw.replaceFirst("^\\uFEFF", "");
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("Empty file");
        if (trimmed.startsWith("{")) return json(trimmed);
        String name =
                filename == null ? "Imported playlist" : filename.replaceFirst("\\.[^.]+$", "");
        Playlist playlist = new Playlist(name);
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv") || lower.endsWith(".tsv")) csv(raw, playlist);
        else if (lower.endsWith(".m3u") || lower.endsWith(".m3u8") || trimmed.startsWith("#EXTM3U"))
            m3u(raw, playlist);
        else
            for (String line : raw.split("\\R"))
                if (!line.trim().isEmpty() && !line.startsWith("#"))
                    playlist.tracks.add(line(line, 0));
        check(Collections.singletonList(playlist));
        return Collections.singletonList(playlist);
    }

    private static List<Playlist> json(String raw) throws Exception {
        JSONObject doc = new JSONObject(raw);
        if (!"songmirror-playlist-backup".equals(doc.optString("kind"))
                || doc.optInt("schema_version") != 1)
            throw new IllegalArgumentException("Unsupported playlist JSON");
        String provider = doc.getJSONObject("provider").getString("id");
        JSONArray lists = doc.getJSONArray("playlists");
        List<Playlist> out = new ArrayList<>();
        for (int i = 0; i < lists.length(); i++) {
            JSONObject row = lists.getJSONObject(i);
            Playlist playlist = new Playlist(row.getString("name"));
            JSONArray tracks = row.getJSONArray("tracks");
            for (int n = 0; n < tracks.length(); n++) {
                JSONObject track = tracks.getJSONObject(n);
                if (track.optBoolean("unavailable")) {
                    playlist.unavailable++;
                    continue;
                }
                String id = text(track, "id"),
                        video =
                                provider.equals("ytmusic") && id.matches("[A-Za-z0-9_-]{11}")
                                        ? id
                                        : youtube(text(track, "external_url"));
                String title = text(track, "name");
                if (title.isEmpty() && video != null) title = video;
                playlist.tracks.add(
                        new Track(
                                title,
                                text(track, "artist"),
                                text(track, "album"),
                                video,
                                text(track, "isrc"),
                                track.optLong("duration_ms", 0)));
            }
            out.add(playlist);
        }
        check(out);
        return out;
    }

    static String youtube(String url) {
        try {
            Uri uri = Uri.parse(url.trim());
            String host = uri.getHost();
            if (host == null
                    || uri.getUserInfo() != null
                    || !("https".equalsIgnoreCase(uri.getScheme())
                            || "http".equalsIgnoreCase(uri.getScheme()))) return null;
            host = host.toLowerCase(Locale.ROOT);
            String id = null;
            if (host.equals("youtu.be")) id = uri.getLastPathSegment();
            else if (Arrays.asList(
                            "youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com")
                    .contains(host)) id = uri.getQueryParameter("v");
            return id != null && id.matches("[A-Za-z0-9_-]{11}") ? id : null;
        } catch (Exception invalid) {
            return null;
        }
    }

    static Track line(String text, long duration) {
        String video = youtube(text);
        if (video != null) return new Track(video, "", "", video, "", duration);
        String[] parts = text.trim().split("\\s+[–—-]\\s+", 2);
        return parts.length == 2
                ? new Track(parts[1], parts[0], "", null, "", duration)
                : new Track(text, "", "", null, "", duration);
    }

    static long duration(String raw, boolean milliseconds) {
        if (raw.trim().isEmpty()) return 0;
        double value = 0;
        if (raw.contains(":")) {
            for (String part : raw.trim().split(":")) value = value * 60 + Double.parseDouble(part);
            value *= 1000;
        } else value = Double.parseDouble(raw.trim()) * (milliseconds ? 1 : 1000);
        if (!Double.isFinite(value) || value < 0 || value > 86400000)
            throw new IllegalArgumentException("Invalid duration");
        return Math.round(value);
    }

    private static void m3u(String raw, Playlist playlist) {
        String name = null;
        long duration = 0;
        for (String line : raw.split("\\R")) {
            line = line.trim();
            if (line.startsWith("#EXTINF:")) {
                int comma = line.indexOf(',');
                if (comma < 0) throw new IllegalArgumentException("Malformed EXTINF");
                String seconds = line.substring(8, comma);
                duration = seconds.equals("-1") ? 0 : duration(seconds, false);
                name = line.substring(comma + 1);
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                String id = youtube(line);
                Track meta = line(name == null ? line : name, duration);
                playlist.tracks.add(new Track(meta.title, meta.artist, "", id, "", duration));
                name = null;
                duration = 0;
            }
        }
    }

    static List<List<String>> cells(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (quoted) quoted = false;
                else if (cell.length() == 0) quoted = true;
                else throw new IllegalArgumentException("Malformed quoted field");
            } else if (!quoted && c == delimiter) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (!quoted && (c == '\n' || c == '\r')) {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                cell.setLength(0);
                if (row.stream().anyMatch(s -> !s.trim().isEmpty())) rows.add(row);
                row = new ArrayList<>();
            } else cell.append(c);
        }
        if (quoted) throw new IllegalArgumentException("Unclosed quoted field");
        row.add(cell.toString());
        if (row.stream().anyMatch(s -> !s.trim().isEmpty())) rows.add(row);
        return rows;
    }

    static String header(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", " ");
    }

    private static void csv(String raw, Playlist playlist) {
        String first = raw.split("\\R", 2)[0];
        char delimiter = ',';
        int largest = -1;
        for (char candidate : new char[] {',', '\t', ';', '|'}) {
            int size = cells(first, candidate).get(0).size();
            if (size > largest) {
                delimiter = candidate;
                largest = size;
            }
        }
        List<List<String>> rows = cells(raw, delimiter);
        if (rows.isEmpty()) throw new IllegalArgumentException("Empty CSV");
        Map<String, Integer> fields = new HashMap<>();
        for (int i = 0; i < rows.get(0).size(); i++) fields.put(header(rows.get(0).get(i)), i);
        String title =
                alias(
                        fields,
                        "title",
                        "name",
                        "track name",
                        "song name",
                        "track title",
                        "track",
                        "song");
        if (title == null)
            throw new IllegalArgumentException("CSV needs a title or track name column");
        String
                artist =
                        alias(
                                fields,
                                "artist",
                                "artists",
                                "artist name",
                                "track artist",
                                "performer"),
                album = alias(fields, "album", "album name", "release"),
                ms = alias(fields, "duration ms", "durationms", "duration (ms)", "length ms", "ms"),
                seconds = alias(fields, "duration", "length", "time", "track duration"),
                isrc = alias(fields, "isrc", "isrc code"),
                url = alias(fields, "url", "external url", "track uri", "uri");
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            playlist.tracks.add(
                    new Track(
                            cell(row, fields, title),
                            cell(row, fields, artist),
                            cell(row, fields, album),
                            youtube(cell(row, fields, url)),
                            cell(row, fields, isrc),
                            duration(cell(row, fields, ms == null ? seconds : ms), ms != null)));
        }
    }

    private static String alias(Map<String, Integer> fields, String... names) {
        for (String name : names) if (fields.containsKey(name)) return name;
        return null;
    }

    private static String cell(List<String> row, Map<String, Integer> fields, String key) {
        if (key == null) return "";
        int index = fields.get(key);
        return index < row.size() ? row.get(index).trim() : "";
    }

    static void check(List<Playlist> lists) {
        if (lists.isEmpty() || lists.size() > 200)
            throw new IllegalArgumentException("Invalid playlist count");
        int count = 0;
        for (Playlist playlist : lists) {
            count += playlist.tracks.size() + playlist.unavailable;
            if (count > LIMIT) throw new IllegalArgumentException("Import exceeds 5,000 tracks");
        }
    }

    static List<Object> search(Track track) throws Exception {
        Object repo =
                Class.forName("com.music.bitchord.data.YtMusicRepository")
                        .getField("INSTANCE")
                        .get(null);
        Class<?> filters = Class.forName("com.music.bitchord.data.model.SearchFilter");
        Object songs = filters.getField("SONGS").get(null);
        Method search =
                repo.getClass()
                        .getMethod(
                                "searchPage-BWLJW6A",
                                String.class,
                                filters,
                                boolean.class,
                                kotlin.coroutines.jvm.internal.ContinuationImpl.class);
        Object page =
                VideoSwitch.await(
                        step ->
                                search.invoke(
                                        repo, track.artist + " " + track.title, songs, true, step));
        if (page == null) throw new IllegalStateException("Search unavailable");
        List<Object> result = new ArrayList<>();
        for (Object row : (List<?>) MixedSongCodec.field(page, "rows")) {
            String name = row.getClass().getName();
            if (name.endsWith("$Track") || name.endsWith("$TopTrack")) {
                Object song = MixedSongCodec.field(row, "song");
                if (!MixedSongCodec.text(song, "videoId").isEmpty()) result.add(song);
            }
        }
        return result;
    }

    static Object best(Track source, List<Object> songs) throws Exception {
        Class<?> target = Class.forName("com.music.bitchord.data.sources.TrackMatcher$Target");
        Object wanted =
                target.getConstructor(
                                String.class,
                                String.class,
                                Integer.class,
                                String.class,
                                Boolean.class,
                                boolean.class)
                        .newInstance(
                                source.title,
                                source.artist,
                                source.duration == 0 ? null : (int) (source.duration / 1000),
                                source.album.isEmpty() ? null : source.album,
                                null,
                                false);
        return Class.forName("com.music.bitchord.data.sources.TrackMatcher")
                .getMethod("best", List.class, target)
                .invoke(null, songs, wanted);
    }
}
