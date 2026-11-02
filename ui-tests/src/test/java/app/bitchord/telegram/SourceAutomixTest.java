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
        ExecutorService background;

        public void execute(Runnable task) {
            tasks++;
            if (background == null) task.run();
            else background.execute(task);
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
        final Object lock = new Object();
        Runnable onRelease, onAttempt;

        public void release() {
            if (SourceAutomix.deferModelRelease(this)) {
                if (onAttempt != null) onAttempt.run();
                return;
            }
            synchronized (lock) {
                try {
                    if (SourceAutomix.deferModelRelease(this)) return;
                    if (onRelease != null) onRelease.run();
                } finally {
                    if (onAttempt != null) onAttempt.run();
                }
            }
        }
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
        java.util.function.Consumer<String> probe;

        public Outcome analyze(String id, Uri uri, double duration) {
            if (probe != null) probe.accept(id);
            synchronized (analyzed) {
                analyzed.add(uri);
            }
            return new Outcome(fail ? null : new TrackAnalysis(id));
        }
    }

    static class Service extends android.content.ContextWrapper {
        public Analyzer trackAnalyzer = new Analyzer();

        Service() {
            super(org.robolectric.RuntimeEnvironment.getApplication());
        }
    }

    static class Callback {
        public Service f$0 = new Service();
    }

    Callback callback;
    java.util.function.Supplier<SourceAudio.AnalysisFormat> originalFormats;
    java.util.function.LongSupplier originalMaximum, originalUsed;
    boolean originalSteady;
    java.util.function.LongSupplier originalSteadyHeap;
    Map<String, Map<?, ?>> originalControlMaps = new HashMap<>();
    Object originalGate;
    DspControl dspControl;

    static SourceAudio.AnalysisFormat format(int rate, long durationUs) {
        return new SourceAudio.AnalysisFormat() {
            public android.media.MediaFormat read(android.media.MediaDataSource source) {
                android.media.MediaFormat format =
                        android.media.MediaFormat.createAudioFormat("audio/flac", rate, 2);
                format.setLong(android.media.MediaFormat.KEY_DURATION, durationUs);
                return format;
            }

            public void close() {}
        };
    }

    static android.media.MediaDataSource cachedAudio() {
        return new android.media.MediaDataSource() {
            public int readAt(long at, byte[] bytes, int offset, int length) {
                if (at > 0) return -1;
                bytes[offset] = 0;
                return 1;
            }

            public long getSize() {
                return 1;
            }

            public void close() {}
        };
    }

    @Before
    public void setup() throws Exception {
        originalFormats = SourceAudio.analysisFormats;
        originalMaximum = SourceAutomix.heapMaximum;
        originalUsed = SourceAutomix.heapUsed;
        originalSteady = SteadyPlayback.enabled;
        originalSteadyHeap = SteadyPlayback.heapBytes;
        for (String name : new String[] {"controls", "observedControls"}) {
            java.lang.reflect.Field member = SteadyPlayback.class.getDeclaredField(name);
            member.setAccessible(true);
            originalControlMaps.put(name, new IdentityHashMap<>((Map<?, ?>) member.get(null)));
        }
        try {
            java.lang.reflect.Field member = SteadyPlayback.class.getDeclaredField("gateObserved");
            member.setAccessible(true);
            originalGate = member.get(null);
        } catch (NoSuchFieldException older) {
            originalGate = null;
        }
        SourceAudio.analysisFormats = () -> format(48000, 120_000_000);
        callback = new Callback();
        PlaybackService.stopped = false;
        PlaybackService.keySuffix = "";
        PlaybackService.cached = uri -> cachedAudio();
        PlaybackService.resolver = null;
        PlaybackService.dspDecoder = null;
        PlaybackService.dspFeatures = null;
        HostLog.observer = null;
        for (String name : new String[] {"jobs", "observed", "pinned"}) {
            java.lang.reflect.Field field = SourceAutomix.class.getDeclaredField(name);
            field.setAccessible(true);
            ((Map<?, ?>) field.get(null)).clear();
        }
    }

    @After
    public void restoreFormats() throws Exception {
        SourceAudio.analysisFormats = originalFormats;
        SourceAutomix.heapMaximum = originalMaximum;
        SourceAutomix.heapUsed = originalUsed;
        PlaybackService.dspDecoder = null;
        PlaybackService.dspFeatures = null;
        HostLog.observer = null;
        SteadyPlayback.enabled = originalSteady;
        SteadyPlayback.heapBytes = originalSteadyHeap;
        for (String name : originalControlMaps.keySet()) {
            java.lang.reflect.Field member = SteadyPlayback.class.getDeclaredField(name);
            member.setAccessible(true);
            Map<Object, Object> state = (Map<Object, Object>) member.get(null);
            state.clear();
            state.putAll(originalControlMaps.get(name));
        }
        if (originalGate != null) {
            java.lang.reflect.Field member = SteadyPlayback.class.getDeclaredField("gateObserved");
            member.setAccessible(true);
            member.set(null, originalGate);
        }
    }

    static class DspAllocator {
        int allocatedCount, availableCount, individualAllocationSize = 65536;
    }

    static class DspControl {
        int targetBufferBytesOverwrite = 8 << 20;
        Map<Object, Object> loadingStates = new HashMap<>();
        DspAllocator allocator = new DspAllocator();

        public void updateAllocator() {}
    }

    static class DspParameters {
        long bufferedDurationUs = 3_000_000;
    }

    void guardedLowMemory() {
        SourceAutomix.heapMaximum = () -> 192L << 20;
        SourceAutomix.heapUsed = () -> 38L << 20;
        SteadyPlayback.enabled = true;
        SteadyPlayback.heapBytes = SourceAutomix.heapMaximum;
        dspControl = new DspControl();
        SteadyPlayback.loadControl(dspControl);
        assertTrue(SteadyPlayback.allowLoading(dspControl, new DspParameters()));
    }

    @Test
    public void boundedAnalysisRemainsAvailableWithReadAheadDisabled() throws Exception {
        guardedLowMemory();
        SteadyPlayback.setEnabled(false);
        assertTrue(SteadyPlayback.allowLoading(dspControl, new DspParameters()));
        SourceAutomix.heapMaximum = () -> 256L << 20;
        SourceAutomix.heapUsed = () -> 120L << 20;
        SteadyPlayback.heapBytes = SourceAutomix.heapMaximum;
        SourceAudio.analysisFormats = () -> format(48000, 183_000_000);
        java.util.concurrent.atomic.AtomicInteger decoded =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<String> messages = new java.util.ArrayList<>();
        HostLog.observer = messages::add;
        PlaybackService.dspDecoder =
                (source, end, abort, chunks) -> {
                    decoded.incrementAndGet();
                    assertFalse(SteadyPlayback.enabled);
                    assertEquals(8 << 20, dspControl.targetBufferBytesOverwrite);
                    dspControl.allocator.allocatedCount = 1024;
                    assertFalse(
                            "The live analysis lease must stop further native allocation",
                            SteadyPlayback.allowLoading(dspControl, new DspParameters()));
                    dspControl.allocator.allocatedCount = 0;
                    return null;
                };
        SourceAutomix.request(callback, item("content://music/phone-budget"), 183000L);
        assertEquals(String.join("\n", messages), 1, decoded.get());
        assertFalse(SteadyPlayback.enabled);
        assertEquals(8 << 20, dspControl.targetBufferBytesOverwrite);
        java.lang.reflect.Field leases = SteadyPlayback.class.getDeclaredField("analysisLeases");
        leases.setAccessible(true);
        assertTrue(((java.util.List<?>) leases.get(null)).isEmpty());
    }

    @Test
    public void lowMemoryUsesMeasuredDspInsteadOfSilentlyLosingEverySmartTransition() {
        guardedLowMemory();
        SourceAudio.analysisFormats = () -> format(48000, 2_000_000);
        java.util.concurrent.atomic.AtomicInteger measured =
                new java.util.concurrent.atomic.AtomicInteger();
        PlaybackService.dspDecoder =
                (source, end, abort, chunks) -> {
                    java.nio.ByteBuffer pcm =
                            java.nio.ByteBuffer.allocate(1920)
                                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < 960; i++) pcm.putShort((short) 4000);
                    for (int i = 0; i < 200; i++) {
                        android.media.MediaCodec.BufferInfo info =
                                new android.media.MediaCodec.BufferInfo();
                        info.set(0, 1920, i * 10_000L, 0);
                        chunks.invoke(pcm, info, 2, 48000);
                    }
                    return new Object();
                };
        PlaybackService.dspFeatures =
                (samples, seconds) -> {
                    measured.incrementAndGet();
                    assertEquals(22050, samples.length);
                    assertEquals(2d, seconds, .0001);
                    assertEquals(4000 / 32768f, samples[11025], .00001);
                    return new Object();
                };
        SourceAutomix.request(callback, item("content://music/low-memory"), 2000L);
        assertEquals("The same selected audio must still be measured", 1, measured.get());
        assertTrue(callback.f$0.trackAnalyzer.analyzed.isEmpty());
        assertNotNull(callback.f$0.trackAnalyzer.results.get("recording-1"));
        assertEquals(1, callback.f$0.trackAnalyzer.store.keys.size());
    }

    @Test
    public void boundedDspCannotPublishAfterTheSelectedRenditionChangesOrPlaybackStops() {
        for (String change : new String[] {"rendition", "stop", "release"}) {
            guardedLowMemory();
            SourceAudio.analysisFormats = () -> format(48000, 2_000_000);
            Callback next = new Callback();
            DspAnalysisTest.decoder(200, 0);
            PlaybackService.dspFeatures =
                    (samples, seconds) -> {
                        if (change.equals("rendition")) PlaybackService.keySuffix = "#changed";
                        if (change.equals("stop")) PlaybackService.stopped = true;
                        if (change.equals("release")) SourceAutomix.release(next.f$0);
                        return new Object();
                    };
            SourceAutomix.request(next, item("content://music/low-memory"), 2000L);
            assertTrue(change, next.f$0.trackAnalyzer.results.isEmpty());
            assertTrue(change, next.f$0.trackAnalyzer.store.keys.isEmpty());
            assertTrue(change, next.f$0.trackAnalyzer.running.isEmpty());
            PlaybackService.keySuffix = "";
            PlaybackService.stopped = false;
        }
    }

    @Test
    public void waitingForMemoryDoesNotExhaustTheThreeActualDecodeAttempts() throws Exception {
        guardedLowMemory();
        SourceAudio.analysisFormats = () -> format(48000, 2_000_000);
        SourceAutomix.heapUsed = () -> 170L << 20;
        DspAnalysisTest.decoder(200, 0);
        java.util.concurrent.atomic.AtomicInteger measured =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger opened =
                new java.util.concurrent.atomic.AtomicInteger();
        PlaybackService.cached =
                uri -> {
                    opened.incrementAndGet();
                    return cachedAudio();
                };
        PlaybackService.dspFeatures =
                (samples, seconds) -> {
                    measured.incrementAndGet();
                    return new Object();
                };
        java.lang.reflect.Field jobs = SourceAutomix.class.getDeclaredField("jobs");
        jobs.setAccessible(true);
        int firstOpens = 0;
        for (int i = 0; i < 4; i++) {
            SourceAutomix.request(callback, item("content://music/low-memory"), 2000L);
            if (i == 0) firstOpens = opened.get();
            else
                assertEquals(
                        "Waiting does not reopen the selected source", firstOpens, opened.get());
            Map<Object, Map<String, SourceAutomix.Job>> all =
                    (Map<Object, Map<String, SourceAutomix.Job>>) jobs.get(null);
            SourceAutomix.Job job = all.get(callback.f$0.trackAnalyzer).get("recording-1");
            job.retryAfter = 0;
        }
        assertEquals(0, measured.get());
        SourceAutomix.heapUsed = () -> 38L << 20;
        SourceAutomix.request(callback, item("content://music/low-memory"), 2000L);
        assertEquals(
                "A later safe allowance must still permit real measurement", 1, measured.get());
        assertNotNull(callback.f$0.trackAnalyzer.results.get("recording-1"));
    }

    @Test
    public void withdrawnHeadroomAfterAFullDecodeStillConsumesABoundedAttempt() throws Exception {
        guardedLowMemory();
        SourceAudio.analysisFormats = () -> format(48000, 2_000_000);
        DspAnalysisTest.decoder(200, 0);
        PlaybackService.DspDecoder decoder = PlaybackService.dspDecoder;
        java.util.concurrent.atomic.AtomicInteger decoded =
                new java.util.concurrent.atomic.AtomicInteger();
        PlaybackService.dspDecoder =
                (source, end, abort, chunks) -> {
                    decoded.incrementAndGet();
                    Object result = decoder.decode(source, end, abort, chunks);
                    SourceAutomix.heapUsed = () -> 170L << 20;
                    return result;
                };
        PlaybackService.dspFeatures =
                (samples, seconds) -> {
                    fail("The whole array was not admitted");
                    return null;
                };
        java.lang.reflect.Field jobs = SourceAutomix.class.getDeclaredField("jobs");
        jobs.setAccessible(true);
        for (int i = 0; i < 5; i++) {
            SourceAutomix.heapUsed = () -> 38L << 20;
            SourceAutomix.request(callback, item("content://music/low-memory"), 2000L);
            Map<Object, Map<String, SourceAutomix.Job>> all =
                    (Map<Object, Map<String, SourceAutomix.Job>>) jobs.get(null);
            all.get(callback.f$0.trackAnalyzer).get("recording-1").retryAfter = 0;
        }
        assertEquals("Completed source reads are limited to three attempts", 3, decoded.get());
        assertTrue(callback.f$0.trackAnalyzer.results.isEmpty());
        assertTrue(callback.f$0.trackAnalyzer.store.keys.isEmpty());
    }

    @Test
    public void diagnosticsDescribeTheBudgetAndOwnFailureSiteWithoutSourceSecrets() {
        guardedLowMemory();
        SourceAudio.analysisFormats = () -> format(48000, 2_000_000);
        SourceAutomix.heapUsed = () -> 170L << 20;
        List<String> messages = new ArrayList<>();
        HostLog.observer = messages::add;
        SourceAutomix.request(callback, item("content://music/low-memory"), 2000L);
        String waiting = String.join("\n", messages);
        assertTrue(waiting, waiting.contains("neededKiB="));
        assertTrue(waiting, waiting.contains("availableKiB="));
        assertTrue(waiting, waiting.contains("decoded=false"));
        assertTrue(waiting, waiting.contains("stage=before-decode"));
        messages.clear();
        SourceAutomix.heapUsed = () -> 38L << 20;
        PlaybackService.dspDecoder =
                (source, end, abort, chunks) -> {
                    throw new IllegalStateException(
                            "https://private.test/audio?api_key=secret-marker");
                };
        SourceAutomix.request(new Callback(), item("content://music/low-memory"), 2000L);
        String failure = String.join("\n", messages);
        assertTrue(failure, failure.contains("stage=bounded-DSP"));
        assertTrue(failure, failure.contains("error=IllegalStateException"));
        assertTrue(failure, failure.matches("(?s).*site=decode:[0-9]+.*"));
        assertFalse(failure, failure.contains("secret-marker"));
        assertFalse(failure, failure.contains("private.test"));
    }

    @Test
    public void aHighRateLongTrackDefersAnalysisWithoutChangingPlayback() {
        SourceAudio.analysisFormats = () -> format(192000, 3_600_000_000L);
        SourceAutomix.request(callback, item("content://music/too-large"), 3_600_000L);
        assertTrue(
                "The PCM working set exceeds even the component JVM's heap",
                callback.f$0.trackAnalyzer.analyzed.isEmpty());
        assertTrue(callback.f$0.trackAnalyzer.results.isEmpty());
    }

    @Test
    public void analysisWorkingSetUsesTheActualRateAndHasAnExactHeadroomBoundary() {
        // 330 s at 48 kHz: two full mono buffers + 11.025 kHz DSP + 16 MiB margin.
        assertEquals(158_050_216L, SourceAudio.analysisBytes(48000, 330));
        assertTrue(SourceAudio.analysisBytes(96000, 330) > (192L << 20));
        SourceAudio.analysisFormats = () -> format(48000, 330_000_000);
        assertNotNull(SourceAudio.analysisLimit(cachedAudio(), 330, 158_050_215L));
        assertNull(SourceAudio.analysisLimit(cachedAudio(), 330, 158_050_216L));
        SourceAudio.analysisFormats = () -> format(96000, 330_000_000);
        assertNotNull(
                "A short queue label cannot hide a long high-rate container",
                SourceAudio.analysisLimit(cachedAudio(), 60, 192L << 20));
        assertNull(
                "Ample headroom retains analysis",
                SourceAudio.analysisLimit(cachedAudio(), 330, 512L << 20));
        assertNotNull(
                "The larger requested duration is also protected",
                SourceAudio.analysisLimit(cachedAudio(), 3600, 192L << 20));
    }

    @Test
    public void analysisLeavesRoomForPlaybackBuffersThatHaveNotFilledYet() {
        SourceAudio.analysisFormats = () -> format(48000, 214_000_000);
        long lowHeap = SourceAudio.analysisAvailable(192L << 20, 38L << 20);
        assertNotNull(
                "The selected file fits raw headroom but not alongside growing player buffers",
                SourceAudio.analysisLimit(cachedAudio(), 214, lowHeap));
        long largerHeap = SourceAudio.analysisAvailable(512L << 20, 38L << 20);
        assertNull(SourceAudio.analysisLimit(cachedAudio(), 214, largerHeap));
        assertEquals(0, SourceAudio.analysisAvailable(192L << 20, 193L << 20));
        assertEquals(0, SourceAudio.analysisAvailable(-1, 0));
        assertEquals(0, SourceAudio.analysisAvailable(192L << 20, -1));
    }

    @Test
    public void unknownNonfiniteAndOverflowingAnalysisSizesAreNotAdmitted() {
        for (double seconds :
                new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE})
            assertEquals(-1, SourceAudio.analysisBytes(48000, seconds));
        assertEquals(-1, SourceAudio.analysisBytes(0, 330));
        assertEquals(-1, SourceAudio.analysisBytes(-1, 330));
        assertEquals(-1, SourceAudio.analysisBytes(Integer.MAX_VALUE, Long.MAX_VALUE));
        SourceAudio.analysisFormats = () -> format(48000, 0);
        assertNotNull(SourceAudio.analysisLimit(cachedAudio(), 0, 512L << 20));
        assertNotNull(SourceAudio.analysisLimit(cachedAudio(), Double.NaN, 512L << 20));
        SourceAudio.analysisFormats = () -> format(0, 330_000_000);
        assertNotNull(SourceAudio.analysisLimit(cachedAudio(), 330, 512L << 20));
    }

    @Test
    public void metadataProbeReleasesTheExtractorOnSuccessAndEveryReadFailure() {
        java.util.concurrent.atomic.AtomicInteger closed =
                new java.util.concurrent.atomic.AtomicInteger();
        for (String outcome :
                new String[] {"success", "no-audio", "set-source", "select-format", "release"}) {
            closed.set(0);
            SourceAudio.analysisFormats =
                    () ->
                            new SourceAudio.AnalysisFormat() {
                                public android.media.MediaFormat read(
                                        android.media.MediaDataSource source)
                                        throws java.io.IOException {
                                    if (outcome.equals("set-source"))
                                        throw new java.io.IOException("setDataSource failed");
                                    if (outcome.equals("select-format"))
                                        throw new IllegalStateException("getTrackFormat failed");
                                    if (outcome.equals("no-audio")) return null;
                                    return android.media.MediaFormat.createAudioFormat(
                                            "audio/flac", 48000, 2);
                                }

                                public void close() {
                                    closed.incrementAndGet();
                                    if (outcome.equals("release"))
                                        throw new IllegalStateException("release failed");
                                }
                            };
            String reason = SourceAudio.analysisLimit(cachedAudio(), 120, 512L << 20);
            if (outcome.equals("success")) assertNull(reason);
            else assertNotNull(outcome, reason);
            assertEquals("Extractor release for " + outcome, 1, closed.get());
        }
        SourceAudio.analysisFormats =
                () -> {
                    throw new IllegalStateException("constructor failed");
                };
        assertNotNull(SourceAudio.analysisLimit(cachedAudio(), 120, 512L << 20));
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
    public void parallelRequestsNeverReleaseSessionsWhileAnotherAnalysisUsesThem()
            throws Exception {
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        ExecutorService parallel = Executors.newFixedThreadPool(2);
        analyzer.executor.background = parallel;
        CountDownLatch firstEntered = new CountDownLatch(1), secondEntered = new CountDownLatch(1);
        CountDownLatch finishFirst = new CountDownLatch(1), firstReleased = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger users =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger unsafeRelease =
                new java.util.concurrent.atomic.AtomicInteger();
        analyzer.probe =
                id -> {
                    users.incrementAndGet();
                    try {
                        if (id.equals("first")) {
                            firstEntered.countDown();
                            assertTrue(finishFirst.await(3, TimeUnit.SECONDS));
                        } else {
                            secondEntered.countDown();
                            assertTrue(firstReleased.await(3, TimeUnit.SECONDS));
                        }
                    } catch (InterruptedException interrupted) {
                        throw new AssertionError(interrupted);
                    } finally {
                        users.decrementAndGet();
                    }
                };
        analyzer.tracker.onRelease =
                () -> {
                    if (users.get() != 0) unsafeRelease.incrementAndGet();
                };
        analyzer.tracker.onAttempt = firstReleased::countDown;
        try {
            SourceAutomix.request(
                    callback,
                    new MediaItem.Builder().setMediaId("first").setUri("content://music/1").build(),
                    120000L);
            assertTrue(firstEntered.await(3, TimeUnit.SECONDS));
            SourceAutomix.request(
                    callback,
                    new MediaItem.Builder()
                            .setMediaId("second")
                            .setUri("content://music/2")
                            .build(),
                    120000L);
            secondEntered.await(150, TimeUnit.MILLISECONDS);
            finishFirst.countDown();
            parallel.shutdown();
            assertTrue(parallel.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(2, analyzer.analyzed.size());
            assertEquals(
                    "An ONNX session cannot be released while another job is using it",
                    0,
                    unsafeRelease.get());
        } finally {
            finishFirst.countDown();
            firstReleased.countDown();
            parallel.shutdownNow();
        }
    }

    @Test
    public void serviceShutdownDefersNativeClosureWithoutBlockingTheUi() throws Exception {
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        ExecutorService background = Executors.newSingleThreadExecutor();
        analyzer.executor.background = background;
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger closed =
                new java.util.concurrent.atomic.AtomicInteger();
        analyzer.tracker.onRelease = closed::incrementAndGet;
        analyzer.vocals.onRelease = closed::incrementAndGet;
        analyzer.probe =
                id -> {
                    synchronized (analyzer.tracker.lock) {
                        entered.countDown();
                        try {
                            assertTrue(finish.await(3, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            throw new AssertionError(interrupted);
                        }
                    }
                };
        try {
            SourceAutomix.request(callback, item("content://music/8"), 120000L);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            long started = System.nanoTime();
            SourceAutomix.release(callback.f$0);
            analyzer.tracker.release();
            analyzer.vocals.release();
            assertTrue(
                    "Shutdown must not wait for native inference on the UI thread",
                    System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(500));
            assertEquals("In-flight native sessions remain alive", 0, closed.get());
            finish.countDown();
            background.shutdown();
            assertTrue(background.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(2, closed.get());
            assertFalse(SourceAutomix.deferModelRelease(analyzer.tracker));
            assertTrue("Cancelled results must not be published", analyzer.results.isEmpty());
        } finally {
            finish.countDown();
            background.shutdownNow();
        }
    }

    @Test
    public void unrelatedCallbackArmsRetainOriginalBehaviour() {
        assertFalse(SourceAutomix.request(callback, item("content://music/9"), "next-track"));
        assertFalse(SourceAutomix.request(callback, new Object(), 120000L));
    }

    @Test
    public void serviceShutdownCancelsAnAnalysisWaitingForDecodeAdmission() throws Exception {
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        ExecutorService background = Executors.newSingleThreadExecutor();
        analyzer.executor.background = background;
        CountDownLatch analyzed = new CountDownLatch(1);
        analyzer.probe = id -> analyzed.countDown();
        try (SourceAudio.Decoding permit =
                SourceAudio.decode(System.nanoTime() + TimeUnit.SECONDS.toNanos(10), () -> false)) {
            SourceAutomix.request(callback, item("content://music/wait"), 120000L);
            assertFalse(
                    "The held working set keeps this job queued",
                    analyzed.await(200, TimeUnit.MILLISECONDS));
            SourceAutomix.release(callback.f$0);
            background.shutdown();
            assertTrue(
                    "Cancellation does not wait for the occupied permit",
                    background.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(analyzer.analyzed.isEmpty());
            assertTrue(analyzer.running.isEmpty());
        } finally {
            background.shutdownNow();
            assertTrue(background.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void completeCachedAnalysisStillStopsWhenItsJobIsCancelled() throws Exception {
        java.util.concurrent.atomic.AtomicInteger reads =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger closes =
                new java.util.concurrent.atomic.AtomicInteger();
        PlaybackService.cached =
                uri ->
                        new android.media.MediaDataSource() {
                            public int readAt(long at, byte[] bytes, int offset, int size) {
                                reads.incrementAndGet();
                                bytes[offset] = 1;
                                return 1;
                            }

                            public long getSize() {
                                return 1;
                            }

                            public void close() {
                                closes.incrementAndGet();
                            }
                        };
        callback.f$0.trackAnalyzer.probe =
                id -> {
                    try (android.media.MediaDataSource source =
                            SourceAutomix.opener(Uri.parse("content://music/cached")).invoke()) {
                        assertNotNull(source);
                        assertEquals(1, source.readAt(0, new byte[1], 0, 1));
                        SourceAutomix.release(callback.f$0);
                        try {
                            source.readAt(0, new byte[1], 0, 1);
                            fail("The cached decoder must stop");
                        } catch (SourceAudio.Stopped expected) {
                        }
                    } catch (java.io.IOException failure) {
                        throw new AssertionError(failure);
                    }
                };
        try {
            SourceAutomix.request(callback, item("content://music/cached"), 120000L);
            assertEquals(1, reads.get());
            assertEquals("Preflight, metadata and decoder readers close", 3, closes.get());
            assertTrue(callback.f$0.trackAnalyzer.results.isEmpty());
        } finally {
            PlaybackService.cached = null;
        }
    }
}
