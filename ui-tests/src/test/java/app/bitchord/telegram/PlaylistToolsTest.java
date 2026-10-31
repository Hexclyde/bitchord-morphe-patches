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
public class PlaylistToolsTest {
    @Before
    public void setup() throws Exception {
        java.lang.reflect.Field app = SoundExtras.class.getDeclaredField("app");
        app.setAccessible(true);
        app.set(null, RuntimeEnvironment.getApplication());
        GuestPlaylists.preferences().edit().clear().commit();
    }

    @Test
    public void strictPlaylistLinksChooseThePlaylistEvenWithATrack() {
        assertEquals(
                "PLabc_12",
                GuestPlaylists.playlistId(
                        "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabc_12"));
        for (String url :
                Arrays.asList(
                        "https://youtube.com.evil.test/playlist?list=PLabc",
                        "javascript://youtube.com/playlist?list=PLabc",
                        "https://a@youtube.com/playlist?list=PLabc",
                        "https://youtube.com/playlist?list=PLabc&list=PLxyz",
                        "https://youtube.com/playlist?list=RDabc",
                        "https://youtube.com/watch?v=dQw4w9WgXcQ"))
            assertThrows(url, IllegalArgumentException.class, () -> GuestPlaylists.playlistId(url));
    }

    @Test
    public void bookmarksDeduplicateRenameAndPersistWithoutAccount() throws Exception {
        GuestPlaylists.bookmark("PLabc", "First");
        GuestPlaylists.bookmark("PLdef", "Other");
        GuestPlaylists.bookmark("PLabc", "Renamed");
        JSONArray stored = GuestPlaylists.bookmarks();
        assertEquals(2, stored.length());
        assertEquals("Renamed", stored.getJSONObject(0).getString("title"));
        GuestPlaylists.forget("PLabc");
        assertEquals("PLdef", GuestPlaylists.bookmarks().getJSONObject(0).getString("id"));
    }

    static final class Page {
        final List<Object> songs;
        final String continuation;

        Page(List<Object> songs, String continuation) {
            this.songs = songs;
            this.continuation = continuation;
        }
    }

    Object song(String id) throws Exception {
        return MixedSongCodec.decode(new JSONObject().put("videoId", id).put("title", id));
    }

    @Test
    public void paginationKeepsOrderAndDeduplicatesAcrossPages() throws Exception {
        Object a = song("abcdefghijk"), b = song("bcdefghijkl");
        List<Object> rows =
                GuestPlaylists.collect(
                        new Page(Arrays.asList(a), "next"),
                        token -> new Page(Arrays.asList(a, b), null));
        assertEquals(2, rows.size());
        assertEquals("bcdefghijkl", MixedSongCodec.text(rows.get(1), "videoId"));
    }

    @Test
    public void failedOrRepeatedContinuationNeverProducesPartialCopy() throws Exception {
        Page page = new Page(Arrays.asList(song("abcdefghijk")), "same");
        assertThrows(
                IllegalStateException.class, () -> GuestPlaylists.collect(page, token -> null));
        assertThrows(
                IllegalStateException.class, () -> GuestPlaylists.collect(page, token -> page));
    }

    JSONObject export() throws Exception {
        JSONObject track =
                new JSONObject()
                        .put("id", "abcdefghijk")
                        .put("name", "Song")
                        .put("artist", "Artist")
                        .put("duration_ms", 123000)
                        .put("unavailable", false);
        return new JSONObject()
                .put("kind", "songmirror-playlist-backup")
                .put("schema_version", 1)
                .put("provider", new JSONObject().put("id", "ytmusic"))
                .put(
                        "playlists",
                        new JSONArray()
                                .put(
                                        new JSONObject()
                                                .put("name", "Copy")
                                                .put(
                                                        "tracks",
                                                        new JSONArray()
                                                                .put(track)
                                                                .put(
                                                                        new JSONObject(
                                                                                track.toString()))
                                                                .put(
                                                                        new JSONObject()
                                                                                .put(
                                                                                        "unavailable",
                                                                                        true)))));
    }

    @Test
    public void portableJsonPreservesIdentityAndSkipsUnavailable() throws Exception {
        PlaylistImport.Playlist imported =
                PlaylistImport.parse(export().toString(), "export.json").get(0);
        assertEquals("Copy", imported.title);
        assertEquals(2, imported.tracks.size());
        assertEquals(1, imported.unavailable);
        assertEquals("2:03", MixedSongCodec.text(imported.tracks.get(0).direct(), "durationText"));
    }

    @Test
    public void otherProviderIdsNeedMatchingAndCannotBeRelabelledAsYoutube() throws Exception {
        JSONObject doc = export();
        doc.getJSONObject("provider").put("id", "spotify");
        assertNull(
                PlaylistImport.parse(doc.toString(), "export.json").get(0).tracks.get(0).direct());
        JSONObject future = export().put("schema_version", 2);
        assertThrows(
                IllegalArgumentException.class,
                () -> PlaylistImport.parse(future.toString(), "export.json"));
    }

    @Test
    public void csvQuotingExplicitMillisecondUnitsAndUnicodeArePreserved() throws Exception {
        PlaylistImport.Playlist list =
                PlaylistImport.parse(
                                "Track Name,Artist Name,Duration_ms\n\"Hello, world\",Bjōrk,1500\n",
                                "music.csv")
                        .get(0);
        assertEquals("Hello, world", list.tracks.get(0).title);
        assertEquals("Bjōrk", list.tracks.get(0).artist);
        assertEquals(1500, list.tracks.get(0).duration);
        assertThrows(IllegalArgumentException.class, () -> PlaylistImport.cells("\"unclosed", ','));
    }

    @Test
    public void m3uUsesExtinfAndNeverExecutesOrFetchesListedUrls() throws Exception {
        PlaylistImport.Playlist list =
                PlaylistImport.parse(
                                "#EXTM3U\n"
                                        + "#EXTINF:123,Artist - Song\n"
                                        + "https://youtu.be/abcdefghijk\n",
                                "music.m3u")
                        .get(0);
        assertEquals("Song", list.tracks.get(0).title);
        assertEquals("Artist", list.tracks.get(0).artist);
        assertEquals(123000, list.tracks.get(0).duration);
        assertEquals("abcdefghijk", list.tracks.get(0).videoId);
        assertNull(PlaylistImport.youtube("https://youtube.com.evil/watch?v=abcdefghijk"));
    }

    @Test
    public void confidenceUsesTitleArtistAndDurationWithoutCallingAService() throws Exception {
        PlaylistImport.Track wanted =
                new PlaylistImport.Track("Song", "Artist", "", null, "", 123000);
        Object exact =
                MixedSongCodec.decode(
                        new JSONObject()
                                .put("videoId", "abcdefghijk")
                                .put("title", "Song")
                                .put("artist", "Artist")
                                .put("durationText", "2:03"));
        assertEquals(1, ImportConfidence.score(wanted, exact), 1e-8);
        assertEquals(0, ImportConfidence.similarity("", ""), 0);
    }
}
