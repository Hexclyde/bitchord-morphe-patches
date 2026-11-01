package app.bitchord.telegram;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SmartPlaylistMatcherTest {
    private static final SmartPlaylistMatcher.Best FIRST = (track, songs) -> songs.get(0);

    private static Object song(String id, String title, String artist) throws Exception {
        return MixedSongCodec.decode(
                new JSONObject().put("videoId", id).put("title", title).put("artist", artist));
    }

    private static SmartPlaylistMatcher.Row row(int at, String title, String artist) {
        return new SmartPlaylistMatcher.Row(
                at, new SmartPlaylistService.Suggestion(title, artist, ""));
    }

    @Test
    public void aCloseMatchIsAcceptedAndKeepsItsSearchIdentity() throws Exception {
        SmartPlaylistMatcher.Row row = row(0, "Blue Monday", "New Order");
        Object found = song("abcdefghijk", "Blue Monday", "New Order");
        row.apply(
                SmartPlaylistMatcher.resolve(
                        row.track, track -> Collections.singletonList(found), FIRST));
        assertEquals(SmartPlaylistMatcher.State.MATCHED, row.state());
        assertSame(found, row.selected);
        assertTrue(row.reviewed);
        assertTrue(row.confidence >= SmartPlaylistMatcher.CONFIDENT);
    }

    @Test
    public void aWeakMatchNeedsReviewEvenIfTheMatcherPicksIt() throws Exception {
        SmartPlaylistMatcher.Row row = row(0, "Blue Monday", "New Order");
        Object found = song("abcdefghijk", "Blue Tuesday Remix", "Tribute Band");
        row.apply(
                SmartPlaylistMatcher.resolve(
                        row.track, track -> Collections.singletonList(found), FIRST));
        assertEquals(SmartPlaylistMatcher.State.REVIEW, row.state());
        assertNull(row.selected);
        assertFalse(row.reviewed);
        assertTrue(row.confidence > 0 && row.confidence < SmartPlaylistMatcher.CONFIDENT);
        assertFalse(SmartPlaylistMatcher.ready(Collections.singletonList(row)));
        row.choose(found);
        assertTrue(row.reviewed);
        assertTrue(SmartPlaylistMatcher.ready(Collections.singletonList(row)));
    }

    @Test
    public void aSuggestionWithoutAnArtistAlwaysNeedsReview() throws Exception {
        SmartPlaylistMatcher.Row row = row(0, "Blue Monday", "");
        Object found = song("abcdefghijk", "Blue Monday", "New Order");
        row.apply(
                SmartPlaylistMatcher.resolve(
                        row.track, track -> Collections.singletonList(found), FIRST));
        assertEquals(SmartPlaylistMatcher.State.REVIEW, row.state());
    }

    @Test
    public void aMissingMatcherFallsBackToReview() throws Exception {
        SmartPlaylistMatcher.Row row = row(0, "Blue Monday", "New Order");
        Object found = song("abcdefghijk", "Blue Monday", "New Order");
        row.apply(
                SmartPlaylistMatcher.resolve(
                        row.track,
                        track -> Collections.singletonList(found),
                        (track, songs) -> {
                            throw new NoClassDefFoundError("Host matcher absent");
                        }));
        assertEquals(SmartPlaylistMatcher.State.REVIEW, row.state());
        assertEquals(1, row.candidates.size());
    }

    @Test
    public void aMatcherCannotIntroduceAnUnsearchedSong() throws Exception {
        SmartPlaylistMatcher.Row row = row(0, "Blue Monday", "New Order");
        Object searched = song("abcdefghijk", "Blue Tuesday", "Other");
        Object invented = song("bcdefghijkl", "Blue Monday", "New Order");
        row.apply(
                SmartPlaylistMatcher.resolve(
                        row.track,
                        track -> Collections.singletonList(searched),
                        (track, songs) -> invented));
        assertNull(row.selected);
    }

    @Test
    public void candidatesAreRankedBestFirstAndBounded() throws Exception {
        SmartPlaylistMatcher.Row row = row(0, "Blue Monday", "New Order");
        List<Object> found = new ArrayList<>();
        found.add(song("abcdefghijk", "Something Else", "Nobody"));
        found.add(song("bcdefghijkl", "Blue Monday", "New Order"));
        for (int i = 0; i < 40; i++)
            found.add(song("cdefghijkl" + (char) ('a' + i % 26), "x", "y"));
        row.apply(SmartPlaylistMatcher.resolve(row.track, track -> found, FIRST));
        assertEquals("bcdefghijkl", MixedSongCodec.text(row.candidates.get(0), "videoId"));
        assertEquals(20, row.candidates.size());
    }

    @Test
    public void searchFailuresAndEmptyResultsAreNeverFabricated() throws Exception {
        SmartPlaylistMatcher.Row failed = row(0, "One", "A");
        failed.apply(
                SmartPlaylistMatcher.resolve(
                        failed.track,
                        track -> {
                            throw new IllegalStateException("offline");
                        },
                        FIRST));
        assertEquals(SmartPlaylistMatcher.State.FAILED, failed.state());
        assertNull(failed.selected);
        SmartPlaylistMatcher.Row empty = row(1, "Two", "B");
        empty.apply(
                SmartPlaylistMatcher.resolve(empty.track, track -> Collections.emptyList(), FIRST));
        assertEquals(SmartPlaylistMatcher.State.UNMATCHED, empty.state());
        assertFalse(SmartPlaylistMatcher.ready(Arrays.asList(failed, empty)));
        SmartPlaylistMatcher.skipUnresolved(Arrays.asList(failed, empty));
        assertEquals(SmartPlaylistMatcher.State.SKIPPED, failed.state());
        assertTrue(SmartPlaylistMatcher.selection(Arrays.asList(failed, empty)).isEmpty());
        assertFalse(
                "An empty playlist cannot be saved",
                SmartPlaylistMatcher.ready(Arrays.asList(failed, empty)));
    }

    @Test
    public void cancellationPropagates() {
        assertThrows(
                InterruptedException.class,
                () ->
                        SmartPlaylistMatcher.resolve(
                                row(0, "One", "A").track,
                                track -> {
                                    throw new InterruptedException();
                                },
                                FIRST));
    }

    @Test
    public void selectionKeepsRowOrderAndOmitsSkipped() throws Exception {
        List<SmartPlaylistMatcher.Row> rows = new ArrayList<>();
        Object a = song("abcdefghijk", "Alpha", "A"), b = song("bcdefghijkl", "Beta", "B");
        for (String title : Arrays.asList("Beta", "Gamma", "Alpha"))
            rows.add(row(rows.size(), title, "X"));
        rows.get(0)
                .apply(new SmartPlaylistMatcher.Outcome(Collections.singletonList(b), b, false, 1));
        rows.get(1)
                .apply(new SmartPlaylistMatcher.Outcome(Collections.emptyList(), null, false, 0));
        rows.get(2)
                .apply(new SmartPlaylistMatcher.Outcome(Collections.singletonList(a), a, false, 1));
        assertEquals(1, SmartPlaylistMatcher.unresolved(rows));
        rows.get(1).skip();
        assertEquals(Arrays.asList(b, a), SmartPlaylistMatcher.selection(rows));
        rows.get(0).skip();
        assertEquals(Collections.singletonList(a), SmartPlaylistMatcher.selection(rows));
    }

    @Test
    public void streamingOnlySourcesStillParticipateInPlaylistMatching() throws Exception {
        com.music.bitchord.data.sources.SourceRegistry.downloadSources.clear();
        com.music.bitchord.data.sources.SourceRegistry.configs.setValue(new ArrayList<>());
        Object found = song("src:stream-only::one", "Blue Monday", "New Order");
        com.music.bitchord.data.sources.MusicSource source =
                new com.music.bitchord.data.sources.MusicSource() {
                    public String getConfigId() {
                        return "stream-only";
                    }

                    public com.music.bitchord.data.sources.SourceKind getKind() {
                        return com.music.bitchord.data.sources.SourceKind.ADDON;
                    }

                    public Object search(
                            String query,
                            int limit,
                            boolean all,
                            com.music.bitchord.data.sources.StreamRequest request,
                            kotlin.coroutines.Continuation<Object> next) {
                        assertNull(request);
                        return Collections.singletonList(found);
                    }
                };
        com.music.bitchord.data.sources.SourceRegistry.downloadSources.add(source);
        com.music.bitchord.data.sources.SourceRegistry.configs.setValue(
                Collections.singletonList(
                        new com.music.bitchord.data.sources.SourceConfig(
                                "stream-only",
                                com.music.bitchord.data.sources.SourceKind.ADDON,
                                "Source",
                                "",
                                true,
                                false,
                                false)));
        assertTrue(com.music.bitchord.data.sources.SourceRegistry.activeForDownload().isEmpty());
        assertEquals(
                Collections.singletonList(found),
                SmartPlaylistMatcher.installedSearch(row(0, "Blue Monday", "New Order").track));
        com.music.bitchord.data.sources.SourceRegistry.downloadSources.clear();
    }

    @Test
    public void downloadOnlyProviderIsNeverAskedForPlaylistPlayback() throws Exception {
        com.music.bitchord.data.sources.SourceRegistry.downloadSources.clear();
        com.music.bitchord.data.sources.MusicSource source =
                new com.music.bitchord.data.sources.MusicSource() {
                    public String getConfigId() {
                        return FlacAddon.ID;
                    }

                    public com.music.bitchord.data.sources.SourceKind getKind() {
                        return com.music.bitchord.data.sources.SourceKind.ADDON;
                    }

                    public Object search(
                            String query,
                            int limit,
                            boolean all,
                            com.music.bitchord.data.sources.StreamRequest request,
                            kotlin.coroutines.Continuation<Object> next) {
                        throw new AssertionError(
                                "A download provider must not be asked for playlist playback");
                    }
                };
        com.music.bitchord.data.sources.SourceRegistry.downloadSources.add(source);
        com.music.bitchord.data.sources.SourceRegistry.configs.setValue(
                Collections.singletonList(
                        new com.music.bitchord.data.sources.SourceConfig(
                                FlacAddon.ID,
                                com.music.bitchord.data.sources.SourceKind.ADDON,
                                "Downloads",
                                "",
                                true,
                                true,
                                false)));
        try {
            SmartPlaylistMatcher.installedSearch(row(0, "Blue Monday", "New Order").track);
        } catch (Exception absentCatalogue) {
        } finally {
            com.music.bitchord.data.sources.SourceRegistry.downloadSources.clear();
        }
    }

    @org.robolectric.annotation.Implements(value = PlaylistImport.class, isInAndroidSdk = false)
    public static class UnavailableCatalogue {
        @org.robolectric.annotation.Implementation
        protected static java.util.List<Object> search(PlaylistImport.Track track) {
            throw new IllegalStateException("Catalogue unavailable");
        }
    }

    @Test
    @Config(
            shadows = UnavailableCatalogue.class,
            instrumentedPackages = "app.bitchord.telegram.PlaylistImport")
    public void installedSearchReportsAnUnavailableCatalogue() {
        com.music.bitchord.data.sources.SourceRegistry.downloadSources.clear();
        assertThrows(
                Exception.class,
                () -> SmartPlaylistMatcher.installedSearch(row(0, "One", "A").track));
    }
}
