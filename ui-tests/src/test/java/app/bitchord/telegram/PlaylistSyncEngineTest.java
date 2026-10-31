package app.bitchord.telegram;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class PlaylistSyncEngineTest {
    static final String SPOT1 = "abcdefghijklmnopqrstuv",
            SPOT2 = "bcdefghijklmnopqrstuvw",
            YT1 = "abcdefghijk",
            YT2 = "bcdefghijkl";
    final android.content.Context app = RuntimeEnvironment.getApplication();
    PlaylistSyncEngine.Job job;
    Spotify remoteSpotify;
    YouTube remoteYoutube;
    PlaylistSyncEngine engine;

    Object song(String id) throws Exception {
        return MixedSongCodec.decode(
                new JSONObject()
                        .put("videoId", id)
                        .put("title", "Song " + id)
                        .put("artist", "Artist")
                        .put("durationText", "2:03"));
    }

    @Before
    public void setup() throws Exception {
        app.getSharedPreferences("bitchord_playlist_sync", 0).edit().clear().commit();
        job = new PlaylistSyncEngine.Job();
        job.name = "Test pair";
        job.spotifyId = "1234567890123456789012";
        job.youtubeId = "PL-test";
        job.links.put(SPOT1, YT1);
        job.links.put(SPOT2, YT2);
        remoteSpotify = new Spotify();
        remoteYoutube = new YouTube();
        remoteSpotify.ids.add(SPOT1);
        remoteYoutube.ids.add(YT2);
        engine = new PlaylistSyncEngine(app, remoteSpotify, remoteYoutube);
        engine.save(job);
    }

    final class Spotify implements PlaylistSyncEngine.SpotifyPort {
        String account = "spotify-account";
        int revision, addCalls, removeCalls;
        List<String> ids = new ArrayList<>();

        public String account() {
            return account;
        }

        public SpotifyPlaylistApi.Playlist read(String id) {
            List<SpotifyPlaylistApi.Track> tracks = new ArrayList<>();
            for (String track : ids)
                tracks.add(
                        new SpotifyPlaylistApi.Track(
                                track,
                                "spotify:track:" + track,
                                "entry-" + track,
                                new PlaylistImport.Track(
                                        "Song " + track, "Artist", "", null, "", 123000)));
            return new SpotifyPlaylistApi.Playlist(
                    id, "Source", String.valueOf(revision), account, tracks, true);
        }

        public List<SpotifyPlaylistApi.Track> search(PlaylistImport.Track source) {
            throw new AssertionError("Known mappings must not be searched again");
        }

        public void assertRevision(String id, String expected) {
            assertEquals(expected, String.valueOf(revision));
        }

        public String add(String id, List<String> tracks, String expected) {
            assertRevision(id, expected);
            addCalls++;
            ids.addAll(tracks);
            return String.valueOf(++revision);
        }

        public String remove(String id, List<String> tracks, String expected) {
            assertRevision(id, expected);
            removeCalls++;
            ids.removeAll(tracks);
            return String.valueOf(++revision);
        }
    }

    final class YouTube implements PlaylistSyncEngine.YouTubePort {
        int revision, addCalls, removeCalls;
        boolean failAdd, ignoreAdd;
        List<String> ids = new ArrayList<>();

        public String account() {
            return "youtube-account";
        }

        public YouTubePlaylistApi.Playlist read(String id) throws Exception {
            List<YouTubePlaylistApi.Track> tracks = new ArrayList<>();
            for (String track : ids)
                tracks.add(new YouTubePlaylistApi.Track(track, "slot-" + track, song(track)));
            return new YouTubePlaylistApi.Playlist(
                    id, "Destination", String.valueOf(revision), account(), tracks);
        }

        public void assertUnchanged(YouTubePlaylistApi.Playlist expected) {
            assertEquals(expected.revision, String.valueOf(revision));
        }

        public void add(YouTubePlaylistApi.Playlist expected, List<String> tracks) {
            assertUnchanged(expected);
            addCalls++;
            if (failAdd)
                throw new IllegalStateException("Connection lost after another provider wrote");
            if (!ignoreAdd) {
                ids.addAll(tracks);
                revision++;
            }
        }

        public void remove(
                YouTubePlaylistApi.Playlist expected, List<YouTubePlaylistApi.Track> tracks) {
            assertUnchanged(expected);
            removeCalls++;
            for (YouTubePlaylistApi.Track track : tracks) ids.remove(track.id);
            revision++;
        }
    }

    @Test
    public void verifiedTwoWayUnionPersistsOneSharedBaseline() throws Exception {
        PlaylistSyncEngine.Preview preview = engine.preview(job, (done, total) -> {});
        assertEquals(1, preview.plan.changes.get("spotify").add.size());
        assertEquals(1, preview.plan.changes.get("youtube").add.size());
        assertEquals("synced", engine.apply(preview));
        assertEquals(2, remoteSpotify.ids.size());
        assertEquals(2, remoteYoutube.ids.size());
        assertNotNull(engine.jobs().get(0).baseline);
        assertFalse(engine.prefs.contains("journal." + job.id));
    }

    @Test
    public void partialWriteKeepsJournalAndRepreviewDoesNotRepeatSuccessfulAdds() throws Exception {
        remoteYoutube.failAdd = true;
        PlaylistSyncEngine.Preview preview = engine.preview(job, (done, total) -> {});
        assertThrows(IllegalStateException.class, () -> engine.apply(preview));
        assertNull(job.baseline);
        assertEquals("needs_verification", engine.jobs().get(0).phase);
        assertTrue(engine.prefs.contains("journal." + job.id));
        assertEquals(1, remoteSpotify.addCalls);
        remoteYoutube.failAdd = false;
        job = engine.jobs().get(0);
        PlaylistSyncEngine.Preview resumed = engine.preview(job, (done, total) -> {});
        assertTrue(resumed.plan.changes.get("spotify").add.isEmpty());
        engine.apply(resumed);
        assertEquals(1, remoteSpotify.addCalls);
        assertEquals("synced", engine.jobs().get(0).phase);
    }

    @Test
    public void successResponseWithoutWrittenTracksNeverAdvancesTheBaseline() throws Exception {
        remoteYoutube.ignoreAdd = true;
        PlaylistSyncEngine.Preview preview = engine.preview(job, (done, total) -> {});
        assertThrows(IllegalStateException.class, () -> engine.apply(preview));
        assertNull(job.baseline);
        assertEquals(0, remoteSpotify.removeCalls);
        assertEquals(0, remoteYoutube.removeCalls);
    }

    @Test
    public void accountSwitchBlocksEveryWrite() throws Exception {
        PlaylistSyncEngine.Preview preview = engine.preview(job, (done, total) -> {});
        remoteSpotify.account = "another-account";
        assertThrows(IllegalStateException.class, () -> engine.apply(preview));
        assertEquals(0, remoteSpotify.addCalls);
        assertEquals(0, remoteYoutube.addCalls);
    }

    @Test
    public void codecRetainsLimitsAndNeverSerializesCredentials() throws Exception {
        job.maxAdds = 200;
        job.maxRemovals = 30;
        String json = PlaylistSyncEngine.encode(job).toString();
        PlaylistSyncEngine.Job restored = PlaylistSyncEngine.decode(new JSONObject(json));
        assertEquals(200, restored.maxAdds);
        assertEquals(30, restored.maxRemovals);
        assertEquals(job.links, restored.links);
        assertFalse(json.contains("access_token"));
        assertFalse(json.contains("refresh_token"));
    }
}
