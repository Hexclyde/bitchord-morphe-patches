package app.bitchord.telegram;

import org.json.*;

import java.net.URLEncoder;
import java.util.*;

/** Official playlist API; complete reads and scoped writes use a separate PKCE grant. */
final class SpotifyPlaylistApi implements PlaylistSyncEngine.SpotifyPort {
    private static final String API = "https://api.spotify.com/v1";

    interface Tokens {
        org.json.JSONObject token(boolean force) throws Exception;
    }

    final Tokens auth;
    final SyncHttp.Transport http;

    SpotifyPlaylistApi(SpotifySyncAuth auth) {
        this(auth::token, SyncHttp.NETWORK);
    }

    SpotifyPlaylistApi(Tokens auth, SyncHttp.Transport http) {
        this.auth = auth;
        this.http = http;
    }

    public String account() throws Exception {
        return auth.token(false).getString("account");
    }

    JSONObject request(String method, String path, JSONObject body) throws Exception {
        if (!path.startsWith("/") || path.startsWith("//"))
            throw new IllegalArgumentException("Invalid Spotify API path");
        JSONObject grant = auth.token(false);
        SyncHttp.Response response =
                http.request(
                        method,
                        API + path,
                        grant.getString("access"),
                        "application/json",
                        body == null ? null : body.toString());
        if (response.status == 401) {
            grant = auth.token(true);
            response =
                    http.request(
                            method,
                            API + path,
                            grant.getString("access"),
                            "application/json",
                            body == null ? null : body.toString());
        }
        if (response.status == 429)
            throw new IllegalStateException(
                    "Spotify rate limit; retry after "
                            + Math.max(1, response.retryAfter)
                            + " seconds");
        if (response.status < 200 || response.status >= 300)
            throw new IllegalStateException(
                    "Spotify request failed (HTTP " + response.status + ")");
        return response.json;
    }

    static String id(String id) {
        if (id == null || !id.matches("[A-Za-z0-9]{10,80}"))
            throw new IllegalArgumentException("Invalid Spotify playlist ID");
        return id;
    }

    static final class Playlist {
        final String id, title, revision, account;
        final List<Track> tracks;
        final boolean writable;

        Playlist(
                String id,
                String title,
                String revision,
                String account,
                List<Track> tracks,
                boolean writable) {
            this.id = id;
            this.title = title;
            this.revision = revision;
            this.account = account;
            this.tracks = tracks;
            this.writable = writable;
        }
    }

    static final class Track {
        final String id, uri, occurrence;
        final PlaylistImport.Track metadata;

        Track(String id, String uri, String occurrence, PlaylistImport.Track metadata) {
            this.id = id;
            this.uri = uri;
            this.occurrence = occurrence;
            this.metadata = metadata;
        }

        Object song() throws Exception {
            return MixedSongCodec.decode(
                    new JSONObject()
                            .put("videoId", id)
                            .put("title", metadata.title)
                            .put("artist", metadata.artist)
                            .put("albumName", metadata.album)
                            .put(
                                    "durationText",
                                    metadata.duration / 60000
                                            + ":"
                                            + String.format(
                                                    Locale.ROOT,
                                                    "%02d",
                                                    metadata.duration / 1000 % 60)));
        }
    }

    static Track track(JSONObject value, String occurrence) throws Exception {
        if (!"track".equals(value.optString("type", "track"))
                || value.optBoolean("is_local")
                || value.isNull("id"))
            throw new IllegalStateException("Playlist contains an unsupported or unavailable item");
        String id = value.getString("id"), uri = value.getString("uri");
        if (!id.matches("[A-Za-z0-9]{10,80}") || !uri.equals("spotify:track:" + id))
            throw new IllegalStateException("Invalid Spotify track identity");
        JSONArray artists = value.getJSONArray("artists");
        StringBuilder artist = new StringBuilder();
        for (int i = 0; i < artists.length(); i++) {
            if (i > 0) artist.append(", ");
            artist.append(artists.getJSONObject(i).getString("name"));
        }
        JSONObject album = value.optJSONObject("album"), ids = value.optJSONObject("external_ids");
        PlaylistImport.Track metadata =
                new PlaylistImport.Track(
                        value.getString("name"),
                        artist.toString(),
                        album == null ? "" : album.optString("name", ""),
                        null,
                        ids == null ? "" : ids.optString("isrc", ""),
                        value.getLong("duration_ms"));
        return new Track(id, uri, occurrence, metadata);
    }

    List<Playlist> playlists() throws Exception {
        JSONObject token = auth.token(false);
        List<Playlist> result = new ArrayList<>();
        int offset = 0;
        while (true) {
            JSONObject page = request("GET", "/me/playlists?limit=50&offset=" + offset, null);
            JSONArray items = page.getJSONArray("items");
            if (items.length() == 0 && !page.isNull("next"))
                throw new IllegalStateException("Spotify playlist pagination stalled");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                JSONObject owner = item.optJSONObject("owner");
                boolean writable =
                        item.optBoolean("collaborative")
                                || owner != null
                                        && token.getString("owner").equals(owner.optString("id"));
                if (writable)
                    result.add(
                            new Playlist(
                                    item.getString("id"),
                                    item.getString("name"),
                                    item.optString("snapshot_id", ""),
                                    token.getString("account"),
                                    Collections.emptyList(),
                                    true));
            }
            if (page.isNull("next")) return result;
            offset += items.length();
            if (offset > 10000) throw new IllegalStateException("Spotify playlist limit exceeded");
        }
    }

    public Playlist read(String playlist) throws Exception {
        id(playlist);
        JSONObject grant = auth.token(false),
                before = request("GET", "/playlists/" + playlist, null);
        JSONObject owner = before.optJSONObject("owner");
        boolean writable =
                before.optBoolean("collaborative")
                        || owner != null && grant.getString("owner").equals(owner.optString("id"));
        if (!writable)
            throw new IllegalStateException("Choose an owned or collaborative Spotify playlist");
        String revision = before.getString("snapshot_id");
        List<Track> tracks = new ArrayList<>();
        int offset = 0, expected = -1;
        while (true) {
            JSONObject page =
                    request(
                            "GET",
                            "/playlists/" + playlist + "/items?limit=50&offset=" + offset,
                            null);
            int total = page.getInt("total");
            if (expected < 0) expected = total;
            if (total != expected || page.getInt("offset") != offset)
                throw new IllegalStateException("Spotify playlist changed while reading");
            JSONArray items = page.getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject wrapper = items.getJSONObject(i);
                if (wrapper.optBoolean("is_local"))
                    throw new IllegalStateException("Spotify local files cannot be synchronized");
                JSONObject value = wrapper.optJSONObject("item");
                if (value == null) value = wrapper.optJSONObject("track");
                if (value == null)
                    throw new IllegalStateException(
                            "Spotify playlist contains an unavailable item");
                tracks.add(track(value, wrapper.optString("added_at", "")));
            }
            offset += items.length();
            if (offset > PlaylistImport.LIMIT)
                throw new IllegalStateException("Playlist exceeds 5,000 tracks");
            if (page.isNull("next")) {
                if (offset != expected)
                    throw new IllegalStateException("Spotify playlist read is incomplete");
                break;
            }
            if (items.length() == 0)
                throw new IllegalStateException("Spotify playlist pagination stalled");
        }
        JSONObject after = request("GET", "/playlists/" + playlist, null);
        if (!revision.equals(after.getString("snapshot_id")))
            throw new IllegalStateException("Spotify playlist changed while reading");
        return new Playlist(
                playlist,
                before.getString("name"),
                revision,
                grant.getString("account"),
                tracks,
                true);
    }

    public List<Track> search(PlaylistImport.Track source) throws Exception {
        String query =
                source.isrc.isEmpty()
                        ? "track:" + source.title + " artist:" + source.artist
                        : "isrc:" + source.isrc.replace("-", "");
        JSONObject result =
                request(
                        "GET",
                        "/search?type=track&limit=10&q=" + URLEncoder.encode(query, "UTF-8"),
                        null);
        JSONArray items = result.getJSONObject("tracks").getJSONArray("items");
        List<Track> candidates = new ArrayList<>();
        for (int i = 0; i < items.length(); i++)
            try {
                candidates.add(track(items.getJSONObject(i), ""));
            } catch (IllegalStateException unavailable) {
            }
        if (candidates.isEmpty() && !source.isrc.isEmpty()) {
            PlaylistImport.Track without =
                    new PlaylistImport.Track(
                            source.title, source.artist, source.album, null, "", source.duration);
            return search(without);
        }
        return candidates;
    }

    String create(String title) throws Exception {
        if (title.trim().isEmpty() || title.length() > 300)
            throw new IllegalArgumentException("Invalid playlist title");
        return request(
                        "POST",
                        "/me/playlists",
                        new JSONObject().put("name", title.trim()).put("public", false))
                .getString("id");
    }

    public String add(String playlist, List<String> ids, String expected) throws Exception {
        id(playlist);
        String revision = expected;
        for (int at = 0; at < ids.size(); at += 100) {
            assertRevision(playlist, revision);
            JSONArray uris = new JSONArray();
            for (String track : ids.subList(at, Math.min(ids.size(), at + 100)))
                uris.put("spotify:track:" + id(track));
            revision =
                    request(
                                    "POST",
                                    "/playlists/" + playlist + "/items",
                                    new JSONObject().put("uris", uris))
                            .getString("snapshot_id");
        }
        return revision;
    }

    public String remove(String playlist, List<String> ids, String expected) throws Exception {
        id(playlist);
        String revision = expected;
        for (int at = 0; at < ids.size(); at += 100) {
            assertRevision(playlist, revision);
            JSONArray items = new JSONArray();
            for (String track : ids.subList(at, Math.min(ids.size(), at + 100)))
                items.put(new JSONObject().put("uri", "spotify:track:" + id(track)));
            revision =
                    request(
                                    "DELETE",
                                    "/playlists/" + playlist + "/items",
                                    new JSONObject()
                                            .put("items", items)
                                            .put("snapshot_id", revision))
                            .getString("snapshot_id");
        }
        return revision;
    }

    void rename(String playlist, String title, String expected) throws Exception {
        assertRevision(playlist, expected);
        request("PUT", "/playlists/" + id(playlist), new JSONObject().put("name", title));
    }

    public void assertRevision(String playlist, String expected) throws Exception {
        String current =
                request("GET", "/playlists/" + id(playlist), null).getString("snapshot_id");
        if (!Objects.equals(current, expected))
            throw new IllegalStateException("Spotify playlist changed; preview again");
    }
}
