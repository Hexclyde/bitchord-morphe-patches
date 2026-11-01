package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.content.Context;

import com.music.bitchord.data.model.Song;
import com.music.bitchord.data.settings.*;
import com.music.bitchord.data.sources.*;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SmartDownloadsTest {
    Context app;

    static final class Catalogue implements MusicSource {
        final String id;
        final List<Song> songs;
        DownloadQuality requested;
        boolean fail;

        Catalogue(String id, Song... songs) {
            this.id = id;
            this.songs = Arrays.asList(songs);
        }

        public String getConfigId() {
            return id;
        }

        public SourceKind getKind() {
            return SourceKind.ADDON;
        }

        public Object search(
                String q,
                int limit,
                boolean all,
                StreamRequest request,
                kotlin.coroutines.Continuation<Object> step) {
            requested = request.quality;
            if (fail) throw new IllegalStateException();
            return songs;
        }
    }

    @Before
    public void prepare() {
        app = RuntimeEnvironment.getApplication();
        SourceRegistry.downloadSources.clear();
        SourceRegistry.configs.setValue(new ArrayList<>());
        AppSettings.downloadQuality.setValue(DownloadQuality.LOSSLESS);
        app.getSharedPreferences("bitchord_download_explorer", 0).edit().clear().commit();
    }

    void add(Catalogue source, boolean enabled, boolean allowed) {
        ArrayList<SourceConfig> next = new ArrayList<>(SourceRegistry.configs.getValue());
        next.add(
                new SourceConfig(
                        source.id, SourceKind.ADDON, source.id, "", enabled, allowed, false));
        SourceRegistry.configs.setValue(next);
        SourceRegistry.downloadSources.add(source);
    }

    Song song(String id, String quality) throws Exception {
        return (Song)
                MixedSongCodec.decode(
                        new org.json.JSONObject()
                                .put("videoId", id)
                                .put("title", "Mask Off")
                                .put("artist", "Ganja White Night, Mr. Bill")
                                .put("sourceQuality", quality));
    }

    SmartDownloads.Track track() {
        SmartDownloads.Track t = new SmartDownloads.Track("src:old::recording");
        t.title = "Mask Off";
        t.artist = "Ganja White Night, Mr. Bill";
        return t;
    }

    @Test
    public void bestEligibleQualityAcrossSourcesUsesDownloadPreference() throws Exception {
        Catalogue mp3 = new Catalogue("compressed", song("src:compressed::one", "HIGH"));
        Catalogue flac = new Catalogue("lossless", song("src:lossless::one", "LOSSLESS"));
        Catalogue blocked = new Catalogue("blocked", song("src:blocked::one", "LOSSLESS"));
        add(mp3, true, true);
        add(flac, true, true);
        add(blocked, true, false);
        assertEquals(
                "src:lossless::one",
                MixedSongCodec.text(SmartDownloadSources.resolve(track()), "videoId"));
        assertEquals(DownloadQuality.LOSSLESS, mp3.requested);
        assertNull(blocked.requested);
        AppSettings.downloadQuality.setValue(DownloadQuality.HIGH);
        assertEquals(
                "src:compressed::one",
                MixedSongCodec.text(SmartDownloadSources.resolve(track()), "videoId"));
        assertEquals(DownloadQuality.HIGH, flac.requested);
    }

    @Test
    public void unavailableAndWrongRecordingDoNotBecomeUnrelatedDownloads() throws Exception {
        Catalogue unavailable = new Catalogue("broken", song("src:broken::one", "LOSSLESS"));
        unavailable.fail = true;
        add(unavailable, true, true);
        Catalogue wrong =
                new Catalogue(
                        "wrong",
                        new Song("src:wrong::one", "Mask Off live", "Someone else", "3:00"));
        add(wrong, true, true);
        assertNull(SmartDownloadSources.resolve(track()));
        SmartDownloads.Track youtube = track();
        youtube = new SmartDownloads.Track("abcdefghijk");
        youtube.title = "Mask Off";
        youtube.artist = "Ganja White Night, Mr. Bill";
        assertNull(SmartDownloadSources.resolve(youtube));
    }

    @Test
    public void exactAdmissionIsPinnedAndRechecksSourcePermissions() throws Exception {
        Catalogue source = new Catalogue("selected", song("src:selected::one", "LOSSLESS"));
        add(source, true, true);
        Song song = source.songs.get(0);
        SmartDownloadSources.prepare(app, song);
        assertTrue(DownloadSelection.forbidFallback(song));
        SourceRegistry.setEnabled("selected", false);
        try {
            SmartDownloadSources.prepare(app, song);
            fail("disabled source accepted");
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void differentProviderFormatsCannotShareAnExistingFilename() throws Exception {
        Song flac = song("src:flacdownloader-native::recording_flac", "LOSSLESS");
        Song mp3 = song("src:flacdownloader-native::recording_mp3_320", "HIGH");
        DownloadSelection.remember(app, flac, null);
        DownloadSelection.remember(app, mp3, null);
        assertNotEquals(
                DownloadSelection.selectedFileName(flac, "Mask Off.mp3"),
                DownloadSelection.selectedFileName(mp3, "Mask Off.mp3"));
    }

    void smartEnabled() throws Exception {
        java.lang.reflect.Field preferences = SmartDownloads.class.getDeclaredField("preferences");
        preferences.setAccessible(true);
        preferences.set(null, null);
        app.getSharedPreferences(SmartDownloads.PREFERENCES, 0).edit().clear().commit();
        SmartDownloads.init(app);
        SmartDownloads.setEnabled(true);
    }

    @Test
    public void scheduledDownloadsDoNotUseTheForegroundQueueAndFailuresRemainRetryable()
            throws Exception {
        smartEnabled();
        Catalogue source = new Catalogue("selected", song("src:selected::one", "LOSSLESS"));
        add(source, true, true);
        com.music.bitchord.download.Downloads.scheduledCalls = 0;
        int foregroundBefore = com.music.bitchord.download.Downloads.queued.size();
        com.music.bitchord.download.Downloads.scheduledMode = 1;
        assertTrue(
                SmartDownloads.enqueue(
                                app,
                                Collections.singletonList(source.songs.get(0)),
                                Collections.singletonList("history-id"),
                                new java.util.concurrent.atomic.AtomicBoolean())
                        .isEmpty());
        assertTrue(SmartDownloads.queued().isEmpty());
        com.music.bitchord.download.Downloads.scheduledMode = 0;
        assertEquals(
                Collections.singletonList("history-id"),
                SmartDownloads.enqueue(
                        app,
                        Collections.singletonList(source.songs.get(0)),
                        Collections.singletonList("history-id"),
                        new java.util.concurrent.atomic.AtomicBoolean()));
        assertTrue(SmartDownloads.queued().contains("history-id"));
        assertEquals(foregroundBefore, com.music.bitchord.download.Downloads.queued.size());
        assertEquals(2, com.music.bitchord.download.Downloads.scheduledCalls);
        assertFalse(
                ((kotlinx.coroutines.Job) com.music.bitchord.download.Downloads.scheduledContext)
                        .isActive());
        SmartDownloads.setEnabled(false);
    }

    @Test
    public void jobCancellationCancelsOnlyItsOwnCoroutineAndDoesNotRememberUnfinishedSongs()
            throws Exception {
        smartEnabled();
        Catalogue source = new Catalogue("selected", song("src:selected::one", "LOSSLESS"));
        add(source, true, true);
        com.music.bitchord.download.Downloads.scheduledMode = 2;
        com.music.bitchord.download.Downloads.scheduledCalls = 0;
        java.util.concurrent.atomic.AtomicBoolean stopped =
                new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.FutureTask<List<String>> task =
                new java.util.concurrent.FutureTask<>(
                        () ->
                                SmartDownloads.enqueue(
                                        app,
                                        new ArrayList<Object>(source.songs),
                                        Collections.singletonList("cancelled-history"),
                                        stopped));
        Thread thread = new Thread(task);
        thread.start();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (com.music.bitchord.download.Downloads.scheduledCalls == 0
                && System.nanoTime() < deadline) Thread.sleep(10);
        stopped.set(true);
        assertTrue(task.get(5, java.util.concurrent.TimeUnit.SECONDS).isEmpty());
        assertFalse(
                ((kotlinx.coroutines.Job) com.music.bitchord.download.Downloads.scheduledContext)
                        .isActive());
        assertFalse(SmartDownloads.queued().contains("cancelled-history"));
        SmartDownloads.setEnabled(false);
    }

    @Test
    public void automaticSourceRankingNeverPinsAnUnreportedOrLowerRequestedTier() throws Exception {
        assertEquals(-1, SmartDownloadSources.rank(song("src:x::low", "LOW"), "HIGH"));
        assertEquals(-1, SmartDownloadSources.rank(song("src:x::high", "HIGH"), "LOSSLESS"));
        assertEquals(-1, SmartDownloadSources.rank(song("src:x::unknown", ""), "LOSSLESS"));
        assertTrue(SmartDownloadSources.rank(song("src:x::flac", "FLAC"), "LOSSLESS") > 0);
    }
}
