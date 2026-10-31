package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.net.Uri;

import androidx.media3.common.MediaItem;

import com.music.bitchord.playback.PlaybackService;
import com.music.bitchord.playback.smart.TrackAnalysis;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.*;
import java.util.concurrent.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SourceAutomixTest {
    static class Direct extends AbstractExecutorService {
        int tasks;

        public void execute(Runnable task) {
            tasks++;
            task.run();
        }

        public void shutdown() {}

        public List<Runnable> shutdownNow() {
            return Collections.emptyList();
        }

        public boolean isShutdown() {
            return false;
        }

        public boolean isTerminated() {
            return false;
        }

        public boolean awaitTermination(long t, TimeUnit u) {
            return true;
        }
    }

    static class Tracker {
        public void release() {}
    }

    static class Thermal {
        public Object getValue() {
            return null;
        }
    }

    static class Store {
        List<String> keys = new ArrayList<>();

        public void save(String key, TrackAnalysis value) {
            keys.add(key);
        }
    }

    static class Outcome {
        public Object flags;

        Outcome(Object value) {
            flags = value;
        }
    }

    static class Analyzer {
        public Map<String, Object> results = new ConcurrentHashMap<>();
        public Map<String, Uri> sourceAnalysisUris = new ConcurrentHashMap<>();
        public Set<String> running = ConcurrentHashMap.newKeySet();
        public Direct executor = new Direct();
        public Store store = new Store();
        public Tracker tracker = new Tracker(), vocals = new Tracker();
        public Thermal thermalWatch$delegate = new Thermal();
        public int thermalStatus;
        List<Uri> analyzed = new ArrayList<>();
        boolean fail;

        public Outcome analyze(String id, Uri uri, double duration) {
            analyzed.add(uri);
            return new Outcome(fail ? null : new TrackAnalysis(id));
        }
    }

    static class Service {
        public Analyzer trackAnalyzer = new Analyzer();
    }

    static class Callback {
        public Service f$0 = new Service();
    }

    Callback callback;

    @Before
    public void setup() throws Exception {
        callback = new Callback();
        PlaybackService.stopped = false;
        PlaybackService.keySuffix = "";
        for (String name : new String[] {"jobs", "observed", "pinned"}) {
            java.lang.reflect.Field field = SourceAutomix.class.getDeclaredField(name);
            field.setAccessible(true);
            ((Map<?, ?>) field.get(null)).clear();
        }
    }

    MediaItem item(String uri) {
        return new MediaItem.Builder().setMediaId("recording-1").setUri(uri).build();
    }

    @Test
    public void pickedAddonRowsAndEveryPlayableSchemeReachTheSameAnalyzer() {
        for (String uri :
                new String[] {
                    "bitchord://source?s=telegram-native&t=12_34&mix=stereo",
                    "smb://share/audio.flac",
                    "content://music/42",
                    "file:///a.flac",
                    "https://host/audio.mp3",
                    "bitchord://watch?v=abc"
                }) {
            Callback next = new Callback();
            assertTrue(SourceAutomix.request(next, item(uri), 120000L));
            Analyzer analyzer = next.f$0.trackAnalyzer;
            assertEquals(Collections.singletonList(Uri.parse(uri)), analyzer.analyzed);
            assertEquals(
                    "recording-1", ((TrackAnalysis) analyzer.results.get("recording-1")).trackId);
            assertEquals(Uri.parse(uri), analyzer.sourceAnalysisUris.get("recording-1"));
            assertTrue(analyzer.running.isEmpty());
        }
    }

    @Test
    public void sourceUpgradeInvalidatesPriorAnalysisAndPersistenceKey() {
        MediaItem item = item("bitchord://watch?v=abc");
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        SourceAutomix.request(callback, item, 120000L);
        SourceAutomix.request(callback, item, 120000L);
        assertEquals(1, analyzer.analyzed.size());
        PlaybackService.keySuffix = "#hifi-new";
        SourceAutomix.request(callback, item, 120000L);
        assertEquals(2, analyzer.analyzed.size());
        assertEquals(2, analyzer.store.keys.size());
        assertNotEquals(analyzer.store.keys.get(0), analyzer.store.keys.get(1));
    }

    @Test
    public void codecFailureDoesNotRetryOnEveryPlaybackTick() {
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        analyzer.fail = true;
        for (int n = 0; n < 30; n++)
            assertTrue(SourceAutomix.request(callback, item("content://music/9"), 120000L));
        assertEquals(1, analyzer.analyzed.size());
        assertTrue(analyzer.results.isEmpty());
        assertTrue(analyzer.running.isEmpty());
    }

    @Test
    public void partyAndThermalGatesAvoidBackgroundWork() {
        PlaybackService.stopped = true;
        SourceAutomix.request(callback, item("content://music/9"), 120000L);
        assertEquals(0, callback.f$0.trackAnalyzer.executor.tasks);
        PlaybackService.stopped = false;
        callback.f$0.trackAnalyzer.thermalStatus = 3;
        SourceAutomix.request(callback, item("content://music/9"), 120000L);
        assertEquals(0, callback.f$0.trackAnalyzer.executor.tasks);
    }

    @Test
    public void unrelatedCallbackArmsRetainOriginalBehaviour() {
        assertFalse(SourceAutomix.request(callback, item("content://music/9"), "next-track"));
        assertFalse(SourceAutomix.request(callback, new Object(), 120000L));
    }
}
