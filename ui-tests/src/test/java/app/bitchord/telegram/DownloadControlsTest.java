package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.content.Context;

import com.music.bitchord.data.model.Song;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class DownloadControlsTest {
    Context app;
    int cancelled, enqueued, deleted;
    boolean deleteResult;
    DownloadControls.Host previous;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(DownloadControls.PREFS, 0).edit().clear().commit();
        previous = DownloadControls.host;
        DownloadControls.host =
                new DownloadControls.Host() {
                    public void cancel(String id) {
                        cancelled++;
                    }

                    public void enqueue(Context context, Object song, String from) {
                        assertEquals("src:provider::exact", MixedSongCodec.text(song, "videoId"));
                        assertEquals("test collection", from);
                        enqueued++;
                    }

                    public boolean delete(Context context, String id) {
                        deleted++;
                        return deleteResult;
                    }
                };
    }

    @After
    public void cleanup() {
        DownloadControls.host = previous;
    }

    void remember() {
        DownloadControls.remember(
                app, new Song("src:provider::exact", "Track", "Artist", "3:00"), "test collection");
    }

    @Test
    public void pausePersistsAndResumeKeepsExactSourceAndCollection() throws Exception {
        remember();
        DownloadControls.pause(app, "src:provider::exact");
        assertEquals(1, cancelled);
        assertEquals(0, enqueued);
        assertEquals(1, DownloadControls.paused(app).size());
        DownloadControls.retry(app, "src:provider::exact");
        assertEquals(1, enqueued);
        assertTrue(DownloadControls.paused(app).isEmpty());
        assertEquals(0, deleted);
    }

    @Test
    public void removingHistoryNeverDeletesFileAndNewAdmissionUnhidesIt() throws Exception {
        remember();
        DownloadControls.remove(app, "src:provider::exact");
        assertTrue(DownloadControls.hidden(app, "src:provider::exact"));
        assertEquals(0, deleted);
        remember();
        assertFalse(DownloadControls.hidden(app, "src:provider::exact"));
    }

    @Test
    public void deletionOnlyRemovesEntryAfterFileDeletionSucceeds() throws Exception {
        remember();
        assertFalse(DownloadControls.delete(app, "src:provider::exact"));
        assertFalse(DownloadControls.hidden(app, "src:provider::exact"));
        deleteResult = true;
        assertTrue(DownloadControls.delete(app, "src:provider::exact"));
        assertTrue(DownloadControls.hidden(app, "src:provider::exact"));
        assertEquals(2, deleted);
    }
}
