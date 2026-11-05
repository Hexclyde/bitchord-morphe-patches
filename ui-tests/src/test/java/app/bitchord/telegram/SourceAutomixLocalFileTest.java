package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DefaultDataSource;

import com.music.bitchord.playback.PlaybackService;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/**
 * A downloaded file in the queue is read straight from the device, as the host's own analysis does.
 * The stream resolver answers null until the service has built it and knows nothing about files.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SourceAutomixLocalFileTest {
    final List<Uri> resolved = Collections.synchronizedList(new ArrayList<>());
    java.util.function.Supplier<SourceAudio.AnalysisFormat> originalFormats;
    File audio;

    @Before
    public void setup() throws Exception {
        originalFormats = SourceAudio.analysisFormats;
        SourceAudio.analysisFormats = () -> SourceAutomixTest.format(48000, 120_000_000);
        PlaybackService.stopped = false;
        PlaybackService.keySuffix = "";
        PlaybackService.cached = uri -> null;
        PlaybackService.source =
                new DefaultDataSource.Factory(org.robolectric.RuntimeEnvironment.getApplication());
        PlaybackService.resolver =
                uri -> {
                    resolved.add(uri);
                    return null;
                };
        PlaybackService.dspDecoder = null;
        PlaybackService.dspFeatures = null;
        for (String name : new String[] {"jobs", "observed", "pinned", "facts", "replays"}) {
            java.lang.reflect.Field field = SourceAutomix.class.getDeclaredField(name);
            field.setAccessible(true);
            ((Map<?, ?>) field.get(null)).clear();
        }
        audio = File.createTempFile("downloaded", ".flac");
        audio.deleteOnExit();
        Files.write(audio.toPath(), new byte[2048]);
    }

    @After
    public void restore() {
        SourceAudio.analysisFormats = originalFormats;
        PlaybackService.cached = null;
        PlaybackService.source = null;
        PlaybackService.resolver = null;
    }

    private MediaItem item(Uri uri) {
        return new MediaItem.Builder().setMediaId("recording-1").setUri(uri).build();
    }

    @Test
    public void aDownloadedFileInTheQueueIsAnalysedWithoutTheStreamResolver() throws Exception {
        Uri uri = Uri.fromFile(audio);
        SourceAutomix.prepareIdentity(uri).get(5, TimeUnit.SECONDS);
        SourceAutomixTest.Callback callback = new SourceAutomixTest.Callback();
        assertTrue(SourceAutomix.request(callback, item(uri), 120000L));
        SourceAutomixTest.Analyzer analyzer = callback.f$0.trackAnalyzer;
        assertEquals(Collections.singletonList(uri), analyzer.analyzed);
        assertTrue("The resolver was asked for a local file: " + resolved, resolved.isEmpty());
    }

    @Test
    public void theEditorsReaderOpensADownloadedFileWithoutTheStreamResolver() throws Exception {
        Uri uri = Uri.fromFile(audio);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            android.media.MediaDataSource source =
                    worker.submit(() -> SourceAutomix.opener(uri).invoke())
                            .get(10, TimeUnit.SECONDS);
            assertNotNull(source);
            assertEquals(2048, source.getSize());
            source.close();
        } finally {
            worker.shutdownNow();
        }
        assertTrue("The resolver was asked for a local file: " + resolved, resolved.isEmpty());
    }

    @Test
    public void aFileThatCannotBeReadStillGoesToTheResolver() throws Exception {
        Uri uri = Uri.fromFile(new File(audio.getParentFile(), "gone-" + audio.getName()));
        java.util.function.Function<File, String> original = SourceAutomix.statReader;
        SourceAutomix.statReader = f -> "0|0";
        try {
            SourceAutomix.prepareIdentity(uri).get(5, TimeUnit.SECONDS);
            SourceAutomix.request(new SourceAutomixTest.Callback(), item(uri), 120000L);
        } finally {
            SourceAutomix.statReader = original;
        }
        assertEquals(Collections.singletonList(uri), resolved);
    }
}
