package app.bitchord.telegram;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SpotifyPlaylistApiTest {
    static final String PLAYLIST = "1234567890123456789012", TRACK = "abcdefghijklmnopqrstuv";

    JSONObject token() throws Exception {
        return new JSONObject()
                .put("access", "test-access")
                .put("account", "stable-account")
                .put("owner", "owner");
    }

    JSONObject meta(String revision) throws Exception {
        return new JSONObject()
                .put("name", "QA")
                .put("snapshot_id", revision)
                .put("owner", new JSONObject().put("id", "owner"));
    }

    JSONObject track() throws Exception {
        return new JSONObject()
                .put("id", TRACK)
                .put("uri", "spotify:track:" + TRACK)
                .put("type", "track")
                .put("name", "Song")
                .put("duration_ms", 123000)
                .put("artists", new JSONArray().put(new JSONObject().put("name", "Artist")))
                .put("album", new JSONObject().put("name", "Album"));
    }

    JSONObject page(boolean more) throws Exception {
        return new JSONObject()
                .put("offset", 0)
                .put("total", 1)
                .put("next", more ? "https://api.spotify.com/v1/next" : JSONObject.NULL)
                .put(
                        "items",
                        new JSONArray()
                                .put(
                                        new JSONObject()
                                                .put("added_at", "time")
                                                .put("item", track())));
    }

    @Test
    public void completeReadChecksTheSnapshotBeforeAndAfter() throws Exception {
        List<String> calls = new ArrayList<>();
        SpotifyPlaylistApi api =
                new SpotifyPlaylistApi(
                        force -> token(),
                        (method, url, token, type, body) -> {
                            calls.add(url);
                            return new SyncHttp.Response(
                                    200, url.contains("/items?") ? page(false) : meta("same"), 0);
                        });
        SpotifyPlaylistApi.Playlist playlist = api.read(PLAYLIST);
        assertEquals(1, playlist.tracks.size());
        assertEquals("time", playlist.tracks.get(0).occurrence);
        assertEquals(3, calls.size());
        assertEquals("stable-account", playlist.account);
    }

    @Test
    public void changedSnapshotAndUnavailableRowsNeverBecomeAnEmptyPlaylist() throws Exception {
        int[] metadata = {0};
        SpotifyPlaylistApi changed =
                new SpotifyPlaylistApi(
                        force -> token(),
                        (method, url, token, type, body) ->
                                new SyncHttp.Response(
                                        200,
                                        url.contains("/items?")
                                                ? page(false)
                                                : meta(metadata[0]++ == 0 ? "before" : "after"),
                                        0));
        assertThrows(IllegalStateException.class, () -> changed.read(PLAYLIST));
        JSONObject broken = page(false);
        broken.getJSONArray("items").getJSONObject(0).put("item", JSONObject.NULL);
        SpotifyPlaylistApi unavailable =
                new SpotifyPlaylistApi(
                        force -> token(),
                        (method, url, token, type, body) ->
                                new SyncHttp.Response(
                                        200, url.contains("/items?") ? broken : meta("same"), 0));
        assertThrows(IllegalStateException.class, () -> unavailable.read(PLAYLIST));
    }

    @Test
    public void removalsUseCurrentItemsSchemaAndReadSnapshot() throws Exception {
        List<String> methods = new ArrayList<>();
        List<JSONObject> bodies = new ArrayList<>();
        SpotifyPlaylistApi api =
                new SpotifyPlaylistApi(
                        force -> token(),
                        (method, url, token, type, body) -> {
                            methods.add(method);
                            if (body != null) bodies.add(new JSONObject(body));
                            assertTrue(url.endsWith("/items") || url.endsWith(PLAYLIST));
                            return new SyncHttp.Response(
                                    200,
                                    method.equals("GET")
                                            ? meta("before")
                                            : new JSONObject().put("snapshot_id", "after"),
                                    0);
                        });
        assertEquals("after", api.remove(PLAYLIST, Arrays.asList(TRACK), "before"));
        assertEquals(Arrays.asList("GET", "DELETE"), methods);
        assertEquals("before", bodies.get(0).getString("snapshot_id"));
        assertTrue(bodies.get(0).has("items"));
        assertFalse(bodies.get(0).has("tracks"));
    }

    @Test
    public void aRateLimitedOrUnknownWriteIsNeverRetried() throws Exception {
        int[] calls = {0};
        SpotifyPlaylistApi api =
                new SpotifyPlaylistApi(
                        force -> token(),
                        (method, url, token, type, body) -> {
                            calls[0]++;
                            return new SyncHttp.Response(429, new JSONObject(), 20);
                        });
        assertThrows(
                IllegalStateException.class,
                () -> api.request("POST", "/me/playlists", new JSONObject()));
        assertEquals(1, calls[0]);
    }

    @Test
    public void sourceUrlsCannotChangeTheApiHost() {
        assertEquals(
                PLAYLIST,
                PlaylistSyncEngine.spotifyId("https://open.spotify.com/playlist/" + PLAYLIST));
        assertEquals(PLAYLIST, PlaylistSyncEngine.spotifyId("spotify:playlist:" + PLAYLIST));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PlaylistSyncEngine.spotifyId(
                                "https://open.spotify.com.evil/playlist/" + PLAYLIST));
    }
}
