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
        Map<String, Double> measuredDurations = new HashMap<>();
        boolean fail;
        java.util.function.Consumer<String> probe;

        public Outcome analyze(String id, Uri uri, double duration) {
            if (probe != null) probe.accept(id);
            synchronized (analyzed) {
                analyzed.add(uri);
                measuredDurations.put(id, duration);
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
        return format(rate, durationUs, 2);
    }

    static SourceAudio.AnalysisFormat format(int rate, long durationUs, int channels) {
        return new SourceAudio.AnalysisFormat() {
            public android.media.MediaFormat read(android.media.MediaDataSource source) {
                android.media.MediaFormat format =
                        android.media.MediaFormat.createAudioFormat("audio/flac", rate, channels);
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
        SourceAutomix.forgetWake();
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
        originalStat = SourceAutomix.statReader;
        originalClock = SourceAutomix.identityClock;
        for (String name : new String[] {"jobs", "observed", "pinned", "facts", "replays"}) {
            java.lang.reflect.Field field = SourceAutomix.class.getDeclaredField(name);
            field.setAccessible(true);
            ((Map<?, ?>) field.get(null)).clear();
        }
    }

    java.util.function.Function<java.io.File, String> originalStat;
    java.util.function.LongSupplier originalClock;

    @After
    public void restoreFormats() throws Exception {
        DspAnalysis.discardAll();
        SourceAutomix.forgetWake();
        SourceAutomix.leasedModels = true;
        SourceAutomix.statReader = originalStat;
        SourceAutomix.identityClock = originalClock;
        PlaybackService.restorer = null;
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
        SourceAutomix.leasedModels = false;
        SteadyPlayback.enabled = true;
        SteadyPlayback.heapBytes = SourceAutomix.heapMaximum;
        dspControl = new DspControl();
        SteadyPlayback.loadControl(dspControl);
        assertTrue(SteadyPlayback.allowLoading(dspControl, new DspParameters()));
    }

    @Test
    public void editorRequestsAnIncomingTrackWithoutDurationMetadata() throws Exception {
        SourceAutomix.heapMaximum = () -> 512L << 20;
        SourceAutomix.heapUsed = () -> 32L << 20;
        SourceAudio.analysisFormats = () -> format(48000, 2_000_000);
        TransitionRuntime.Pair pair =
                new TransitionRuntime.Pair("recording-1", "next", "A", "B", 2, 0, 0);
        pair.outgoing = item("content://music/current");
        pair.incoming =
                new MediaItem.Builder().setMediaId("next").setUri("content://music/next").build();
        SourceAutomix.service(callback.f$0);
        SourceAutomix.requestEditor(pair, false);
        assertEquals(
                "The source header can supply the missing next-track duration",
                "ready",
                SourceAutomix.analysisPhase("next"));
        assertTrue(callback.f$0.trackAnalyzer.results.containsKey("next"));
        assertEquals(2, callback.f$0.trackAnalyzer.measuredDurations.get("next"), .001);
    }

    @Test
    public void boundedAnalysisUsesTheSelectedFilesLengthNotARoundedQueueLabel() {
        guardedLowMemory();
        SourceAudio.analysisFormats = () -> format(48000, 2_000_000);
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
                    assertEquals(2d, seconds, .0001);
                    return new Object();
                };
        SourceAutomix.request(callback, item("content://music/rounded-length"), 3000L);
        assertEquals(
                "A complete two-second file is not a truncated three-second recording",
                "ready",
                SourceAutomix.analysisPhase("recording-1"));
        assertTrue(callback.f$0.trackAnalyzer.results.containsKey("recording-1"));
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
            SourceAutomix.Job waiting = all.get(callback.f$0.trackAnalyzer).get("recording-1");
            waiting.retryAfter = 0;
            // Kept audio expired: every try is a full read, which is what the bound counts.
            DspAnalysis.discard(waiting.storeKey);
        }
        assertEquals("Completed source reads are limited to three attempts", 3, decoded.get());
        assertTrue(callback.f$0.trackAnalyzer.results.isEmpty());
        assertTrue(callback.f$0.trackAnalyzer.store.keys.isEmpty());
    }

    SourceAutomix.Job job(String id) throws Exception {
        java.lang.reflect.Field jobs = SourceAutomix.class.getDeclaredField("jobs");
        jobs.setAccessible(true);
        Map<Object, Map<String, SourceAutomix.Job>> all =
                (Map<Object, Map<String, SourceAutomix.Job>>) jobs.get(null);
        return all.get(callback.f$0.trackAnalyzer).get(id);
    }

    @Test
    public void spentFullReadsShowTheLimitUntilARealRetryFindsHeadroom() throws Exception {
        guardedLowMemory();
        SourceAudio.analysisFormats = () -> format(48000, 2_000_000);
        DspAnalysisTest.decoder(200, 0);
        PlaybackService.DspDecoder decoder = PlaybackService.dspDecoder;
        java.util.concurrent.atomic.AtomicInteger decoded =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger opened =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger measured =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean pressure =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        PlaybackService.dspDecoder =
                (source, end, abort, chunks) -> {
                    decoded.incrementAndGet();
                    Object result = decoder.decode(source, end, abort, chunks);
                    if (pressure.get()) SourceAutomix.heapUsed = () -> 170L << 20;
                    return result;
                };
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
        MediaItem selected = item("content://music/low-memory");
        TransitionRuntime.Pair pair =
                new TransitionRuntime.Pair("recording-1", "recording-2", "A", "B", 2, 2, 0);
        pair.outgoing = selected;
        String waiting = Strings.t("Waiting for memory");
        String limit = Strings.t("Not enough memory to analyze this track. Tap Retry.");
        for (int i = 0; i < 3; i++) {
            SourceAutomix.heapUsed = () -> 38L << 20;
            SourceAutomix.request(callback, selected, 2000L);
            assertEquals(i < 2 ? "memory" : "memory_limit", job("recording-1").phase);
            assertEquals(i < 2 ? waiting : limit, TransitionAnalysis.read(pair, 0).state());
            job("recording-1").retryAfter = 0;
            DspAnalysis.discard(job("recording-1").storeKey);
        }
        assertEquals(3, decoded.get());
        int reads = opened.get();

        pressure.set(false);
        SourceAutomix.heapUsed = () -> 38L << 20;
        assertTrue(SourceAutomix.request(callback, selected, 2000L));
        assertEquals("An ordinary request starts no fourth read", 3, decoded.get());
        assertEquals("An ordinary request reopens nothing", reads, opened.get());
        assertEquals(0, measured.get());
        assertEquals("memory_limit", job("recording-1").phase);
        assertEquals(limit, TransitionAnalysis.read(pair, 0).state());
        assertTrue(callback.f$0.trackAnalyzer.results.isEmpty());

        SourceAutomix.requestEditor(pair, true);
        assertEquals(4, decoded.get());
        assertEquals("The editor's Retry measures the recovered heap", 1, measured.get());
        assertEquals("ready", job("recording-1").phase);
        assertEquals(1, job("recording-1").attempts);
        assertNotNull(callback.f$0.trackAnalyzer.results.get("recording-1"));
        assertEquals(1, callback.f$0.trackAnalyzer.store.keys.size());
    }

    @Test
    public void pressureBeforeAnyReadKeepsWaitingAndSpendsNoAttempt() throws Exception {
        guardedLowMemory();
        SourceAudio.analysisFormats = () -> format(48000, 2_000_000);
        SourceAutomix.heapUsed = () -> 170L << 20;
        DspAnalysisTest.decoder(200, 0);
        java.util.concurrent.atomic.AtomicInteger decoded =
                new java.util.concurrent.atomic.AtomicInteger();
        PlaybackService.DspDecoder decoder = PlaybackService.dspDecoder;
        PlaybackService.dspDecoder =
                (source, end, abort, chunks) -> {
                    decoded.incrementAndGet();
                    return decoder.decode(source, end, abort, chunks);
                };
        MediaItem selected = item("content://music/low-memory");
        TransitionRuntime.Pair pair =
                new TransitionRuntime.Pair("recording-1", "recording-2", "A", "B", 2, 2, 0);
        for (int i = 0; i < 6; i++) {
            SourceAutomix.request(callback, selected, 2000L);
            SourceAutomix.Job job = job("recording-1");
            assertEquals("memory", job.phase);
            assertEquals(0, job.attempts);
            assertEquals(Strings.t("Waiting for memory"), TransitionAnalysis.read(pair, 0).state());
            job.retryAfter = 0;
        }
        assertEquals(0, decoded.get());
    }

    @Test
    public void headroomBetweenTheMeasurementAndTheWholeDecodePeakStillPublishes()
            throws Exception {
        guardedLowMemory();
        SourceAudio.analysisFormats = () -> format(48000, 2_000_000);
        DspAnalysisTest.decoder(200, 0);
        PlaybackService.DspDecoder decoder = PlaybackService.dspDecoder;
        PlaybackService.dspDecoder =
                (source, end, abort, chunks) -> {
                    Object result = decoder.decode(source, end, abort, chunks);
                    // 17 MiB remain: above the 22,050 measured frames, below the decode peak.
                    SourceAutomix.heapUsed = () -> 119L << 20;
                    return result;
                };
        java.util.concurrent.atomic.AtomicInteger measured =
                new java.util.concurrent.atomic.AtomicInteger();
        PlaybackService.dspFeatures =
                (samples, seconds) -> {
                    measured.incrementAndGet();
                    return new Object();
                };
        SourceAutomix.request(callback, item("content://music/low-memory"), 2000L);
        assertEquals(1, measured.get());
        assertEquals("ready", job("recording-1").phase);
        assertEquals(1, job("recording-1").attempts);
        assertNotNull(callback.f$0.trackAnalyzer.results.get("recording-1"));
    }

    @Test
    public void failedMeasurementCannotPublishAndReleasesTheTemporaryMemoryLease()
            throws Exception {
        guardedLowMemory();
        SourceAudio.analysisFormats = () -> format(48000, 2_000_000);
        DspAnalysisTest.decoder(200, 0);
        PlaybackService.dspFeatures =
                (samples, seconds) -> {
                    throw new OutOfMemoryError("Measurement failed");
                };
        SourceAutomix.request(callback, item("content://music/low-memory"), 2000L);
        assertEquals("failed", job("recording-1").phase);
        assertTrue(callback.f$0.trackAnalyzer.results.isEmpty());
        assertTrue(callback.f$0.trackAnalyzer.store.keys.isEmpty());
        assertTrue(callback.f$0.trackAnalyzer.running.isEmpty());
        java.lang.reflect.Field leases = SteadyPlayback.class.getDeclaredField("analysisLeases");
        leases.setAccessible(true);
        assertTrue(((java.util.List<?>) leases.get(null)).isEmpty());
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
        assertEquals(
                "Stereo is the baseline the 20 bytes per frame region was sized for",
                SourceAudio.analysisBytes(48000, 330),
                SourceAudio.analysisBytes(48000, 330, 2));
        assertEquals(
                "Mono is never reserved below stereo",
                SourceAudio.analysisBytes(48000, 330),
                SourceAudio.analysisBytes(48000, 330, 1));
        assertTrue(
                "5.1 chunks and flattened copies are three times stereo's",
                SourceAudio.analysisBytes(48000, 330, 6) - SourceAudio.analysisBytes(48000, 330)
                        >= 48000L * 330 * 4 * 4);
        for (int invalid : new int[] {0, -1, 9, 64})
            assertEquals(
                    "Channel count " + invalid + " is rejected, not clamped",
                    -1,
                    SourceAudio.analysisBytes(48000, 330, invalid));
        assertTrue(SourceAudio.analysisBytes(48000, 330, 8) > 0);
        SourceAudio.analysisFormats = () -> format(48000, 330_000_000);
        assertNotNull(SourceAudio.analysisLimit(cachedAudio(), 330, 158_050_215L));
        assertNull(SourceAudio.analysisLimit(cachedAudio(), 330, 158_050_216L));
        SourceAudio.analysisFormats = () -> format(48000, 330_000_000, 6);
        assertNotNull(
                "A 5.1 source needs more than the stereo bound",
                SourceAudio.analysisLimit(cachedAudio(), 330, 158_050_216L));
        assertNull(
                SourceAudio.analysisLimit(
                        cachedAudio(), 330, SourceAudio.analysisBytes(48000, 330, 6)));
        SourceAudio.analysisFormats = () -> format(48000, 330_000_000, 9);
        assertNotNull(
                "An impossible channel count is not estimated downward",
                SourceAudio.analysisLimit(cachedAudio(), 330, 1L << 40));
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

    @Test
    public void cachedAnalysisIsRestoredWhilePreparationHoldsThePriority() throws Exception {
        PlaybackPriority.reset();
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        TrackAnalysis stored = cached("recording-1", 120);
        PlaybackService.restorer =
                (target, key) -> {
                    if (key.equals("recording-1"))
                        nativePublish(((Analyzer) target).results, "recording-1", stored);
                };
        try (AutoCloseable lease = PlaybackPriority.nextPreparation("next")) {
            SourceAutomix.request(callback, item("content://music/42"), 120000L);
        } finally {
            PlaybackService.restorer = null;
            PlaybackPriority.reset();
        }
        assertEquals("ready", job("recording-1").phase);
        assertSame(stored, analyzer.results.get("recording-1"));
        assertTrue(analyzer.analyzed.isEmpty());
    }

    @Test
    public void aMeasurementThatPlaybackHoldsBackReportsWaiting() throws Exception {
        PlaybackPriority.reset();
        try (AutoCloseable lease = PlaybackPriority.nextPreparation("next")) {
            SourceAutomix.request(callback, item("content://music/42"), 120000L);
            SourceAutomix.Job job = job("recording-1");
            assertEquals("waiting", job.phase);
            assertEquals(0, job.attempts);
            TransitionRuntime.Pair pair =
                    new TransitionRuntime.Pair("recording-1", "recording-2", "A", "B", 2, 2, 0);
            assertEquals(
                    Strings.t("Waiting for playback"), TransitionAnalysis.read(pair, 0).state());
            assertTrue(callback.f$0.trackAnalyzer.analyzed.isEmpty());
        } finally {
            PlaybackPriority.reset();
        }
    }

    @Test
    public void renewedPreparationCannotStarveTheCurrentAnalysis() throws Exception {
        PlaybackPriority.reset();
        java.util.concurrent.atomic.AtomicLong now =
                new java.util.concurrent.atomic.AtomicLong(1_000_000_000_000L);
        java.util.function.LongSupplier original = PlaybackPriority.clock;
        PlaybackPriority.clock = now::get;
        try {
            AutoCloseable lease = PlaybackPriority.nextPreparation("next");
            SourceAutomix.request(callback, item("content://music/42"), 120000L);
            assertEquals("waiting", job("recording-1").phase);
            for (int i = 0; i < 4; i++) {
                now.addAndGet(10_000_000_000L);
                lease.close();
                lease = PlaybackPriority.nextPreparation("next" + i);
            }
            job("recording-1").retryAfter = 0;
            SourceAutomix.request(callback, item("content://music/42"), 120000L);
            assertEquals("ready", job("recording-1").phase);
            lease.close();
        } finally {
            PlaybackPriority.clock = original;
            PlaybackPriority.reset();
        }
    }

    @Test
    public void everyAnalysisPathLeavesNoPriorityLease() throws Exception {
        PlaybackPriority.reset();
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        SourceAutomix.request(callback, item("content://music/42"), 120000L);
        assertEquals("ready", job("recording-1").phase);
        assertEquals(0, PlaybackPriority.leaseCount());
        analyzer.fail = true;
        analyzer.results.clear();
        job("recording-1").retryAfter = 0;
        SourceAutomix.request(callback, item("content://music/43"), 120000L);
        assertEquals(0, PlaybackPriority.leaseCount());
    }

    @Test
    public void aMemoryWaitStartsShortAndGrowsToACap() {
        long previous = 0;
        for (int i = 1; i < 20; i++) {
            long delay = SourceAutomix.memoryDelayNanos(i);
            assertTrue(delay >= previous);
            assertTrue(delay <= 60_000_000_000L);
            previous = delay;
        }
        assertTrue(SourceAutomix.memoryDelayNanos(1) <= 4_000_000_000L);
    }

    @Test
    public void releasedMemoryWakesOnlyWaitsThatNowFit() throws Exception {
        guardedLowMemory();
        SourceAudio.analysisFormats = () -> format(48000, 2_000_000);
        SourceAutomix.heapUsed = () -> 170L << 20;
        DspAnalysisTest.decoder(200, 0);
        SourceAutomix.request(callback, item("content://music/low-memory"), 2000L);
        SourceAutomix.Job job = job("recording-1");
        assertEquals("memory", job.phase);
        assertTrue(job.retryAfter - System.nanoTime() <= 4_000_000_000L);
        long held = job.retryAfter;
        job.leasedRetry = false;
        SourceAutomix.memoryAvailable();
        assertEquals("Still short: no wake", held, job.retryAfter);
        SourceAutomix.heapUsed = () -> 38L << 20;
        SourceAutomix.memoryAvailable();
        assertTrue("Fits now: woken", job.retryAfter == 0 || "ready".equals(job.phase));
    }

    private void idleMain() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    private void releaseSomeMemory() {
        try (SourceAudio.JavaMemory grant =
                SourceAudio.reserveJava(192L << 20, 38L << 20, 1 << 20)) {
            assertNotNull(grant);
        }
    }

    private SourceAutomix.Job memoryWaitWithKnownFormat(
            java.util.concurrent.atomic.AtomicInteger measured) throws Exception {
        guardedLowMemory();
        SourceAudio.analysisFormats = () -> format(48000, 2_000_000);
        DspAnalysisTest.decoder(200, 0);
        PlaybackService.dspFeatures =
                (samples, seconds) -> {
                    measured.incrementAndGet();
                    return new Object();
                };
        SourceAutomix.heapUsed = () -> 170L << 20;
        SourceAutomix.request(callback, item("content://music/low-memory"), 2000L);
        SourceAutomix.Job job = job("recording-1");
        assertEquals("memory", job.phase);
        assertNotNull("The format probe ran before the claim failed", job.format);
        SourceAutomix.heapUsed = () -> 38L << 20;
        return job;
    }

    @Test
    public void aReleaseReevaluatesTheLeasedAllowanceInsteadOfSittingOutTheBackoff()
            throws Exception {
        java.util.concurrent.atomic.AtomicInteger measured =
                new java.util.concurrent.atomic.AtomicInteger();
        SourceAutomix.Job job = memoryWaitWithKnownFormat(measured);
        assertEquals("Nothing runs before a release", 0, measured.get());
        releaseSomeMemory();
        idleMain();
        assertEquals("The wait is cut short", 0, job.retryAfter);
        SourceAutomix.request(callback, item("content://music/low-memory"), 2000L);
        assertEquals("ready", job.phase);
        assertEquals(1, measured.get());
    }

    @Test
    public void manyReleasesScheduleOneWakeAndTheCallbackDoesNoWork() throws Exception {
        java.util.concurrent.atomic.AtomicInteger measured =
                new java.util.concurrent.atomic.AtomicInteger();
        SourceAutomix.Job job = memoryWaitWithKnownFormat(measured);
        long held = job.retryAfter;
        for (int i = 0; i < 20; i++) releaseSomeMemory();
        assertEquals("The callback only schedules", held, job.retryAfter);
        assertEquals("memory", job.phase);
        idleMain();
        assertEquals("One wake, however many releases", 0, job.retryAfter);
        SourceAutomix.request(callback, item("content://music/low-memory"), 2000L);
        assertEquals(1, measured.get());
        for (int i = 0; i < 5; i++) {
            releaseSomeMemory();
            idleMain();
        }
        assertEquals("A finished job is not woken again", 1, measured.get());
        assertEquals("ready", job.phase);
    }

    @Test
    public void aWaitThatCannotFitEvenLeasedIsWokenAtMostOncePerEpisode() throws Exception {
        java.util.concurrent.atomic.AtomicInteger measured =
                new java.util.concurrent.atomic.AtomicInteger();
        SourceAutomix.Job job = memoryWaitWithKnownFormat(measured);
        SourceAutomix.heapUsed = () -> 170L << 20;
        releaseSomeMemory();
        idleMain();
        assertEquals("The one leased re-check", 0, job.retryAfter);
        SourceAutomix.request(callback, item("content://music/low-memory"), 2000L);
        assertEquals("memory", job.phase);
        long held = job.retryAfter;
        assertTrue(held > 0);
        for (int i = 0; i < 10; i++) {
            releaseSomeMemory();
            idleMain();
        }
        assertEquals("Releases without room do not wake it", held, job.retryAfter);
        assertEquals(0, measured.get());
    }

    @Test
    public void modelAnalysisUsesTheLeasedAllowanceWhenOnlyThatFits() throws Exception {
        SourceAutomix.heapMaximum = () -> 300L << 20;
        SourceAutomix.heapUsed = () -> 100L << 20;
        SteadyPlayback.enabled = true;
        SteadyPlayback.heapBytes = SourceAutomix.heapMaximum;
        dspControl = new DspControl();
        SteadyPlayback.loadControl(dspControl);
        assertTrue(SteadyPlayback.allowLoading(dspControl, new DspParameters()));
        SourceAudio.analysisFormats = () -> format(48000, 2_000_000);
        long modelBytes = SourceAudio.analysisBytes(48000, 2);
        assertTrue(modelBytes > SourceAudio.analysisAvailable(300L << 20, 100L << 20));
        try (SourceAudio.DspMemory probe = SourceAudio.dspMemory(300L << 20, 100L << 20)) {
            assertTrue(modelBytes < probe.available());
        }
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        PlaybackService.dspFeatures =
                (samples, seconds) -> {
                    fail("The bounded fallback must not run when the model fits leased");
                    return null;
                };
        SourceAutomix.request(callback, item("content://music/low-memory"), 2000L);
        assertEquals("ready", job("recording-1").phase);
        assertEquals("The model analyzer measured it", 1, analyzer.analyzed.size());
        assertEquals(0, PlaybackPriority.leaseCount());
    }

    @Test
    public void withoutTheCeilingHooksModelAnalysisKeepsTheBoundedFallback() throws Exception {
        SourceAutomix.heapMaximum = () -> 300L << 20;
        SourceAutomix.heapUsed = () -> 100L << 20;
        SteadyPlayback.enabled = false;
        SourceAudio.analysisFormats = () -> format(48000, 2_000_000);
        DspAnalysisTest.decoder(200, 0);
        java.util.concurrent.atomic.AtomicInteger measured =
                new java.util.concurrent.atomic.AtomicInteger();
        PlaybackService.dspFeatures =
                (samples, seconds) -> {
                    measured.incrementAndGet();
                    return new Object();
                };
        SourceAutomix.request(callback, item("content://music/low-memory"), 2000L);
        assertTrue(callback.f$0.trackAnalyzer.analyzed.isEmpty());
        assertEquals(1, measured.get());
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
            if (uri.startsWith("file:"))
                try {
                    SourceAutomix.prepareIdentity(Uri.parse(uri)).get(5, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
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

    private static TrackAnalysis cached(String id, double seconds) {
        TrackAnalysis result = new TrackAnalysis(id);
        result.duration = seconds;
        return result;
    }

    @Test
    public void readyNativeResultForTheSameUriAndDurationIsKeptWithoutADecode() throws Exception {
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        TrackAnalysis cachedResult = cached("recording-1", 120);
        analyzer.results.put("recording-1", cachedResult);
        analyzer.sourceAnalysisUris.put("recording-1", Uri.parse("content://music/42"));
        assertTrue(SourceAutomix.request(callback, item("content://music/42"), 120000L));
        assertSame(cachedResult, analyzer.results.get("recording-1"));
        assertTrue(analyzer.analyzed.isEmpty());
        assertEquals(0, analyzer.executor.tasks);
        assertTrue(analyzer.store.keys.isEmpty());
        assertEquals("ready", job("recording-1").phase);
        assertTrue(job("recording-1").done);
        assertEquals("ready", SourceAutomix.analysisPhase("recording-1"));
    }

    @Test
    public void readyNativeResultWithoutAnAnalysisUriEntryIsKeptForWatchAndLocalRows()
            throws Exception {
        for (String uri :
                new String[] {"bitchord://watch?v=abc", "file:///music/a.flac", "content://m/1"}) {
            Callback next = new Callback();
            Analyzer analyzer = next.f$0.trackAnalyzer;
            TrackAnalysis native_ = cached("recording-1", 120);
            analyzer.results.put("recording-1", native_);
            assertTrue(SourceAutomix.request(next, item(uri), 120000L));
            assertSame(uri, native_, analyzer.results.get("recording-1"));
            assertTrue(uri, analyzer.analyzed.isEmpty());
            assertEquals(uri, 0, analyzer.executor.tasks);
            assertTrue(uri, analyzer.store.keys.isEmpty());
            assertNull(
                    "Unverified data is not relabelled",
                    analyzer.sourceAnalysisUris.get("recording-1"));
        }
    }

    @Test
    public void plainIdNativeCacheRestoresOnTheWorkerWithoutADecode() throws Exception {
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        TrackAnalysis stored = cached("recording-1", 120);
        java.util.List<String> keys = new ArrayList<>();
        PlaybackService.restorer =
                (target, key) -> {
                    keys.add(key);
                    if (key.equals("recording-1"))
                        nativePublish(((Analyzer) target).results, "recording-1", stored);
                };
        try {
            SourceAutomix.request(callback, item("content://music/42"), 120000L);
        } finally {
            PlaybackService.restorer = null;
        }
        assertTrue(keys.contains("recording-1"));
        assertSame(stored, analyzer.results.get("recording-1"));
        assertTrue(analyzer.analyzed.isEmpty());
        assertTrue(analyzer.store.keys.isEmpty());
        assertEquals("ready", job("recording-1").phase);
    }

    @Test
    public void aConflictingPlainIdCacheIsRemovedOnlyWhileItIsTheSameObject() throws Exception {
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        TrackAnalysis stale = cached("recording-1", 400);
        PlaybackService.restorer =
                (target, key) -> {
                    if (key.equals("recording-1"))
                        nativePublish(((Analyzer) target).results, "recording-1", stale);
                };
        try {
            SourceAutomix.request(callback, item("content://music/42"), 120000L);
        } finally {
            PlaybackService.restorer = null;
        }
        assertEquals(1, analyzer.analyzed.size());
        assertNotSame(stale, analyzer.results.get("recording-1"));
    }

    @Test
    public void aResultOwnedByAnotherTrackIdIsNotReused() throws Exception {
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        TrackAnalysis foreign = cached("another-track", 120);
        analyzer.results.put("recording-1", foreign);
        SourceAutomix.request(callback, item("content://music/42"), 120000L);
        assertEquals(1, analyzer.analyzed.size());
        assertNotSame(foreign, analyzer.results.get("recording-1"));
    }

    @Test
    public void readyNativeResultWithAnotherDurationIsReplacedNotTrusted() throws Exception {
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        TrackAnalysis stale = cached("recording-1", 300);
        analyzer.results.put("recording-1", stale);
        analyzer.sourceAnalysisUris.put("recording-1", Uri.parse("content://music/42"));
        assertTrue(SourceAutomix.request(callback, item("content://music/42"), 120000L));
        assertEquals(1, analyzer.analyzed.size());
        assertNotSame(stale, analyzer.results.get("recording-1"));
        assertEquals(
                Uri.parse("content://music/42"), analyzer.sourceAnalysisUris.get("recording-1"));
    }

    @Test
    public void coldNativeRestoreIsAdoptedWithoutBindingTheSourceUri() throws Exception {
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        TrackAnalysis stored = cached("recording-1", 120);
        PlaybackService.restorer =
                (target, key) -> {
                    if (key.equals("recording-1"))
                        nativePublish(((Analyzer) target).results, "recording-1", stored);
                };
        SourceAutomix.request(callback, item("content://music/42"), 120000L);
        assertSame(stored, analyzer.results.get("recording-1"));
        assertNull(analyzer.sourceAnalysisUris.get("recording-1"));
        assertTrue(analyzer.store.keys.isEmpty());
        assertTrue(job("recording-1").adopted);
    }

    @Test
    public void nativePublicationDelegatesUnlessTheWorkerCapturesThatMapAndId() throws Exception {
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        Map<Object, Object> plain = new ConcurrentHashMap<>();
        TrackAnalysis first = cached("x", 120), second = cached("x", 120);
        assertNull(SourceAutomix.restored(plain, "x", first));
        assertSame(first, SourceAutomix.restored(plain, "x", second));
        assertSame(first, plain.get("x"));
        Map<Object, Object> other = new ConcurrentHashMap<>();
        TrackAnalysis stored = cached("recording-1", 120), sibling = cached("sibling", 120);
        PlaybackService.restorer =
                (target, key) -> {
                    if (!key.equals("recording-1")) return;
                    nativePublish(other, "recording-1", stored);
                    nativePublish(((Analyzer) target).results, "sibling", sibling);
                    assertNull(
                            "The captured entry stays private",
                            ((Analyzer) target).results.get("recording-1"));
                };
        SourceAutomix.request(callback, item("content://music/42"), 120000L);
        assertSame("Another map is untouched by the capture", stored, other.get("recording-1"));
        assertSame("Another id is published at once", sibling, analyzer.results.get("sibling"));
    }

    private static void waitFor(CountDownLatch latch) throws Exception {
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    private static java.io.File tiny(String text) throws Exception {
        java.io.File file = java.io.File.createTempFile("identity", ".flac");
        file.deleteOnExit();
        java.nio.file.Files.write(file.toPath(), text.getBytes());
        return file;
    }

    @Test
    public void localFileIdentityIsUnavailableBeforeTheWorkerAndFullAndStableAfter()
            throws Exception {
        java.io.File file = tiny("abc");
        Uri uri = Uri.fromFile(file);
        Thread test = Thread.currentThread();
        List<Thread> readers = Collections.synchronizedList(new ArrayList<>());
        SourceAutomix.statReader =
                f -> {
                    readers.add(Thread.currentThread());
                    return f.length() + "|" + f.lastModified();
                };
        // Asking starts a read on the worker; hold the worker so the unseen state is observable.
        CountDownLatch held = new CountDownLatch(1), gate = new CountDownLatch(1);
        identityWorker()
                .execute(
                        () -> {
                            held.countDown();
                            try {
                                gate.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException interrupted) {
                                throw new AssertionError(interrupted);
                            }
                        });
        try {
            waitFor(held);
            assertNull(SourceAutomix.cachedIdentity(uri));
            try {
                SourceAutomix.identity(uri);
                fail("A file that was never read has no identity on the main thread");
            } catch (SourceAutomix.IdentityPending pending) {
                assertEquals(uri, pending.uri);
            }
            assertTrue("Nothing was read while the worker was held", readers.isEmpty());
        } finally {
            gate.countDown();
        }
        String full = SourceAutomix.prepareIdentity(uri).get(5, TimeUnit.SECONDS);
        assertEquals(PlaybackService.bitchordAutomixKey(uri) + "|3|" + file.lastModified(), full);
        assertEquals(full, SourceAutomix.cachedIdentity(uri));
        assertEquals(full, SourceAutomix.identity(uri));
        assertFalse(readers.contains(test));
        assertEquals(
                "content://music/42",
                SourceAutomix.cachedIdentity(Uri.parse("content://music/42")));
    }

    @Test
    public void editorAndRequestNeverReadTheFileOnTheMainThread() throws Exception {
        java.io.File file = tiny("abc");
        Uri uri = Uri.fromFile(file);
        Thread test = Thread.currentThread();
        List<Thread> readers = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch read = new CountDownLatch(1);
        SourceAutomix.statReader =
                f -> {
                    readers.add(Thread.currentThread());
                    read.countDown();
                    return f.length() + "|" + f.lastModified();
                };
        TransitionRuntime.Pair pair =
                new TransitionRuntime.Pair("recording-1", "recording-2", "A", "B", 120, 120, 0);
        pair.outgoing = item(uri.toString());
        SourceAutomix.service(callback.f$0);
        SourceAutomix.requestEditor(pair, true);
        SourceAutomix.request(callback, item(uri.toString()), 120000L);
        waitFor(read);
        drainIdentityWorker();
        assertFalse(readers.contains(test));
        assertEquals(1, callback.f$0.trackAnalyzer.analyzed.size());
    }

    @Test
    public void aChangedLocalFileInvalidatesTheAnalysisItOwned() throws Exception {
        java.io.File file = tiny("abc");
        Uri uri = Uri.fromFile(file);
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        SourceAutomix.prepareIdentity(uri).get(5, TimeUnit.SECONDS);
        SourceAutomix.request(callback, item(uri.toString()), 120000L);
        SourceAutomix.prepareIdentity(uri).get(5, TimeUnit.SECONDS);
        assertEquals(1, analyzer.analyzed.size());
        Object first = analyzer.results.get("recording-1");
        java.nio.file.Files.write(file.toPath(), "abcdef".getBytes());
        file.setLastModified(file.lastModified() + 5000);
        SourceAutomix.prepareIdentity(uri).get(5, TimeUnit.SECONDS);
        SourceAutomix.request(callback, item(uri.toString()), 120000L);
        assertEquals("The changed file is measured again", 2, analyzer.analyzed.size());
        assertNotSame(first, analyzer.results.get("recording-1"));
    }

    @Test
    public void aFileChangeSeenByTheWorkerReplaysTheWaitingRequest() throws Exception {
        java.io.File file = tiny("abc");
        Uri uri = Uri.fromFile(file);
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong();
        SourceAutomix.identityClock = now::get;
        SourceAutomix.prepareIdentity(uri).get(5, TimeUnit.SECONDS);
        SourceAutomix.request(callback, item(uri.toString()), 120000L);
        assertEquals(1, analyzer.analyzed.size());
        java.nio.file.Files.write(file.toPath(), "abcdef".getBytes());
        file.setLastModified(file.lastModified() + 5000);
        now.set(600);
        SourceAutomix.request(callback, item(uri.toString()), 120000L);
        drainIdentityWorker();
        assertEquals(
                "The snapshot was refreshed and the request replayed", 2, analyzer.analyzed.size());
    }

    @Test
    public void aRequestHoldingTheOldFactCannotReplaceTheFreshJob() throws Exception {
        java.io.File file = tiny("abc");
        Uri uri = Uri.fromFile(file);
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong();
        CountDownLatch statEntered = new CountDownLatch(1), statGate = new CountDownLatch(1);
        CountDownLatch clockEntered = new CountDownLatch(1), clockGate = new CountDownLatch(1);
        SourceAutomix.identityClock = now::get;
        SourceAutomix.prepareIdentity(uri).get(5, TimeUnit.SECONDS);
        SourceAutomix.request(callback, item(uri.toString()), 120000L);
        assertEquals(1, analyzer.analyzed.size());
        java.nio.file.Files.write(file.toPath(), "abcdef".getBytes());
        file.setLastModified(file.lastModified() + 5000);
        now.set(600);
        SourceAutomix.statReader =
                f -> {
                    statEntered.countDown();
                    try {
                        assertTrue(statGate.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        throw new AssertionError(interrupted);
                    }
                    return f.length() + "|" + f.lastModified();
                };
        // The first request leaves a replay waiting on a read that has not finished.
        SourceAutomix.request(callback, item(uri.toString()), 120000L);
        waitFor(statEntered);
        SourceAutomix.identityClock =
                () -> {
                    if (Thread.currentThread().getName().equals("late-request")) {
                        clockEntered.countDown();
                        try {
                            assertTrue(clockGate.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            throw new AssertionError(interrupted);
                        }
                    }
                    return now.get();
                };
        // This request has read the old fact when the read below publishes the new one.
        Thread second =
                new Thread(
                        () -> SourceAutomix.request(callback, item(uri.toString()), 120000L),
                        "late-request");
        second.start();
        waitFor(clockEntered);
        statGate.countDown();
        drainIdentityWorker();
        assertEquals("The replay measured the changed file", 2, analyzer.analyzed.size());
        clockGate.countDown();
        second.join(5000);
        drainIdentityWorker();
        assertEquals("The old fact did not start another job", 2, analyzer.analyzed.size());
    }

    @Test
    public void pendingFileFactsAreSharedAndAReleasedOwnerLosesItsReplay() throws Exception {
        java.io.File file = tiny("abc");
        Uri uri = Uri.fromFile(file);
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        CountDownLatch entered = new CountDownLatch(1), gate = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger reads =
                new java.util.concurrent.atomic.AtomicInteger();
        SourceAutomix.statReader =
                f -> {
                    reads.incrementAndGet();
                    entered.countDown();
                    try {
                        assertTrue(gate.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        throw new AssertionError(interrupted);
                    }
                    return f.length() + "|" + f.lastModified();
                };
        try {
            CompletableFuture<String> first = SourceAutomix.prepareIdentity(uri);
            waitFor(entered);
            assertSame("Callers share one pending read", first, SourceAutomix.prepareIdentity(uri));
            for (int n = 0; n < 20; n++)
                SourceAutomix.request(callback, item(uri.toString()), 120000L);
            SourceAutomix.release(callback.f$0);
            gate.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertEquals(1, reads.get());
            assertTrue("A released owner is not asked again", analyzer.analyzed.isEmpty());
            assertEquals(0, analyzer.executor.tasks);
        } finally {
            gate.countDown();
        }
    }

    @Test
    public void snapshotsAndPendingFilesStayBounded() throws Exception {
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int n = 0; n < 300; n++)
            futures.add(SourceAutomix.prepareIdentity(Uri.parse("file:///bounded/" + n + ".flac")));
        for (CompletableFuture<String> future : futures)
            try {
                future.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException refused) {
            }
        java.lang.reflect.Field field = SourceAutomix.class.getDeclaredField("facts");
        field.setAccessible(true);
        assertTrue(((Map<?, ?>) field.get(null)).size() <= 128);
        assertTrue(identityWorker().getQueue().size() <= 128);
    }

    private static ThreadPoolExecutor identityWorker() throws Exception {
        java.lang.reflect.Field field = SourceAutomix.class.getDeclaredField("identityWorker");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(null);
    }

    /**
     * The worker is one thread, so a task queued now runs after every read and replay before it.
     */
    private static void drainIdentityWorker() throws Exception {
        identityWorker().submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    @Test
    public void aSnapshotIsServedWhileItRefreshesFromHalfASecondAndExpiresAtOneSecond()
            throws Exception {
        java.io.File file = tiny("abc");
        Uri uri = Uri.fromFile(file);
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicInteger reads =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean block =
                new java.util.concurrent.atomic.AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1), gate = new CountDownLatch(1);
        SourceAutomix.identityClock = now::get;
        SourceAutomix.statReader =
                f -> {
                    reads.incrementAndGet();
                    if (block.get()) {
                        entered.countDown();
                        try {
                            assertTrue(gate.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            throw new AssertionError(interrupted);
                        }
                    }
                    return f.length() + "|" + f.lastModified();
                };
        try {
            String full = SourceAutomix.prepareIdentity(uri).get(5, TimeUnit.SECONDS);
            assertEquals(
                    PlaybackService.bitchordAutomixKey(uri) + "|3|" + file.lastModified(), full);
            now.set(499);
            assertEquals(full, SourceAutomix.cachedIdentity(uri));
            drainIdentityWorker();
            assertEquals("No refresh before half a second", 1, reads.get());
            block.set(true);
            now.set(500);
            assertEquals(
                    "The valid snapshot is still served", full, SourceAutomix.cachedIdentity(uri));
            waitFor(entered);
            assertEquals(full, SourceAutomix.cachedIdentity(uri));
            now.set(1000);
            assertNull("A snapshot a second old is not served", SourceAutomix.cachedIdentity(uri));
            assertEquals("Callers join the pending read", 2, reads.get());
            gate.countDown();
            drainIdentityWorker();
            assertEquals(full, SourceAutomix.cachedIdentity(uri));
        } finally {
            gate.countDown();
        }
    }

    @Test
    public void theMainThreadNeverReadsAFileEvenInsideAnActiveAnalysis() throws Exception {
        java.io.File file = tiny("abc");
        Uri unseen = Uri.fromFile(file);
        Thread test = Thread.currentThread();
        List<Thread> readers = Collections.synchronizedList(new ArrayList<>());
        SourceAutomix.statReader =
                f -> {
                    readers.add(Thread.currentThread());
                    return f.length() + "|" + f.lastModified();
                };
        java.util.concurrent.atomic.AtomicInteger pending =
                new java.util.concurrent.atomic.AtomicInteger();
        callback.f$0.trackAnalyzer.probe =
                id -> {
                    try {
                        SourceAutomix.identity(unseen);
                    } catch (SourceAutomix.IdentityPending expected) {
                        pending.incrementAndGet();
                    }
                };
        SourceAutomix.request(callback, item("content://music/42"), 120000L);
        drainIdentityWorker();
        assertEquals("The analysis job does not allow a read on the main thread", 1, pending.get());
        assertFalse(readers.contains(test));
    }

    @Test
    public void aWorkerReadsTheFileBeforeItsIdentityIsUsed() throws Exception {
        java.io.File file = tiny("abc");
        Uri uri = Uri.fromFile(file);
        java.util.concurrent.atomic.AtomicReference<String> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = new Thread(() -> seen.set(SourceAutomix.identity(uri)));
        worker.start();
        worker.join(5000);
        assertEquals(
                PlaybackService.bitchordAutomixKey(uri) + "|3|" + file.lastModified(), seen.get());
    }

    @Test
    public void aReleasedOwnersReplayNeverRunsOrTakesTheServiceBack() throws Exception {
        java.io.File file = tiny("abc");
        Uri uri = Uri.fromFile(file);
        Callback older = new Callback(), newer = new Callback();
        CountDownLatch entered = new CountDownLatch(1), gate = new CountDownLatch(1);
        SourceAutomix.statReader =
                f -> {
                    entered.countDown();
                    try {
                        assertTrue(gate.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        throw new AssertionError(interrupted);
                    }
                    return f.length() + "|" + f.lastModified();
                };
        try {
            SourceAutomix.request(older, item(uri.toString()), 120000L);
            waitFor(entered);
            SourceAutomix.service(newer.f$0);
            gate.countDown();
            drainIdentityWorker();
            assertSame(newer.f$0, SourceAutomix.owner());
            assertTrue(older.f$0.trackAnalyzer.analyzed.isEmpty());
            assertEquals(0, older.f$0.trackAnalyzer.executor.tasks);
        } finally {
            gate.countDown();
        }
    }

    @Test
    public void adoptedNativeResultIsStillInvalidatedWhenTheRenditionChanges() throws Exception {
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        TrackAnalysis cachedResult = cached("recording-1", 120);
        analyzer.results.put("recording-1", cachedResult);
        analyzer.sourceAnalysisUris.put("recording-1", Uri.parse("content://music/42"));
        SourceAutomix.request(callback, item("content://music/42"), 120000L);
        assertTrue(analyzer.analyzed.isEmpty());
        PlaybackService.keySuffix = "#hifi-new";
        SourceAutomix.request(callback, item("content://music/42"), 120000L);
        assertEquals(1, analyzer.analyzed.size());
        assertNotSame(cachedResult, analyzer.results.get("recording-1"));
    }

    @Test
    public void editorRequestDoesNotDiscardAReadyNativeResultItCanProve() throws Exception {
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        TrackAnalysis cachedResult = cached("recording-1", 120);
        analyzer.results.put("recording-1", cachedResult);
        analyzer.sourceAnalysisUris.put("recording-1", Uri.parse("content://music/42"));
        TransitionRuntime.Pair pair =
                new TransitionRuntime.Pair("recording-1", "recording-2", "A", "B", 120, 120, 0);
        pair.outgoing = item("content://music/42");
        SourceAutomix.service(callback.f$0);
        SourceAutomix.requestEditor(pair, true);
        assertSame(cachedResult, analyzer.results.get("recording-1"));
        assertTrue(analyzer.analyzed.isEmpty());
    }

    /** Native plain-id restore publishes through SourceAutomix.restored when it exists. */
    private static Object nativePublish(Map<?, ?> results, String id, Object value) {
        try {
            java.lang.reflect.Method hook =
                    SourceAutomix.class.getDeclaredMethod(
                            "restored", Map.class, Object.class, Object.class);
            hook.setAccessible(true);
            return hook.invoke(null, results, id, value);
        } catch (NoSuchMethodException absent) {
            return ((Map<Object, Object>) results).putIfAbsent(id, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    public void nativeCanonicalMappedSourceResultStaysTheSameObjectWithoutADecodeOrRelabel()
            throws Exception {
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        TrackAnalysis mapped = cached("recording-1", 120);
        Uri canonical = Uri.parse("bitchord://watch?v=canonical");
        analyzer.results.put("recording-1", mapped);
        analyzer.sourceAnalysisUris.put("recording-1", canonical);
        String audible = "bitchord://source?s=telegram-native&t=12_34&mix=stereo";
        for (int n = 0; n < 2; n++)
            assertTrue(SourceAutomix.request(callback, item(audible), 120000L));
        assertSame(mapped, analyzer.results.get("recording-1"));
        assertTrue(analyzer.analyzed.isEmpty());
        assertEquals(0, analyzer.executor.tasks);
        assertTrue(analyzer.store.keys.isEmpty());
        assertEquals(canonical, analyzer.sourceAnalysisUris.get("recording-1"));
        assertEquals("ready", SourceAutomix.analysisPhase("recording-1"));
    }

    @Test
    public void changedAdoptedRenditionDoesNotResurrectTheSamePlainIdCache() throws Exception {
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        TrackAnalysis old = cached("recording-1", 120);
        analyzer.results.put("recording-1", old);
        SourceAutomix.request(callback, item("content://music/42"), 120000L);
        assertTrue(job("recording-1").done);
        assertTrue(analyzer.analyzed.isEmpty());
        PlaybackService.restorer =
                (target, key) -> {
                    if (key.equals("recording-1"))
                        nativePublish(((Analyzer) target).results, "recording-1", old);
                };
        try {
            PlaybackService.keySuffix = "#hifi-new";
            SourceAutomix.request(callback, item("content://music/42"), 120000L);
        } finally {
            PlaybackService.restorer = null;
        }
        assertEquals("The changed rendition is measured again", 1, analyzer.analyzed.size());
        assertNotSame(old, analyzer.results.get("recording-1"));
    }

    @Test
    public void gatedNativeRestoreAfterReleaseCannotPublish() throws Exception {
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        ExecutorService background = Executors.newSingleThreadExecutor();
        analyzer.executor.background = background;
        CountDownLatch entered = new CountDownLatch(1), gate = new CountDownLatch(1);
        TrackAnalysis stale = cached("recording-1", 120);
        PlaybackService.restorer =
                (target, key) -> {
                    if (!key.equals("recording-1")) return;
                    entered.countDown();
                    try {
                        assertTrue(gate.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        throw new AssertionError(interrupted);
                    }
                    nativePublish(((Analyzer) target).results, "recording-1", stale);
                };
        try {
            SourceAutomix.request(callback, item("content://music/42"), 120000L);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            SourceAutomix.release(callback.f$0);
            gate.countDown();
            background.shutdown();
            assertTrue(background.awaitTermination(5, TimeUnit.SECONDS));
            assertNull(
                    "A released service never receives a late restore",
                    analyzer.results.get("recording-1"));
            assertTrue(analyzer.analyzed.isEmpty());
            assertTrue(analyzer.running.isEmpty());
        } finally {
            gate.countDown();
            PlaybackService.restorer = null;
            background.shutdownNow();
            assertTrue(background.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void newerNativeResultDuringAGatedRestoreWins() throws Exception {
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        ExecutorService background = Executors.newSingleThreadExecutor();
        analyzer.executor.background = background;
        CountDownLatch entered = new CountDownLatch(1), gate = new CountDownLatch(1);
        TrackAnalysis stale = cached("recording-1", 120), newer = cached("recording-1", 120);
        PlaybackService.restorer =
                (target, key) -> {
                    if (!key.equals("recording-1")) return;
                    entered.countDown();
                    try {
                        assertTrue(gate.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        throw new AssertionError(interrupted);
                    }
                    nativePublish(((Analyzer) target).results, "recording-1", stale);
                };
        try {
            SourceAutomix.request(callback, item("content://music/42"), 120000L);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            analyzer.results.put("recording-1", newer);
            gate.countDown();
            background.shutdown();
            assertTrue(background.awaitTermination(5, TimeUnit.SECONDS));
            assertSame(newer, analyzer.results.get("recording-1"));
            assertTrue(analyzer.analyzed.isEmpty());
            assertTrue(analyzer.store.keys.isEmpty());
        } finally {
            gate.countDown();
            PlaybackService.restorer = null;
            background.shutdownNow();
            assertTrue(background.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void aNativeResultPutBetweenTheJobChangeAndTheWorkerIsNotAdoptedForAChangedRendition()
            throws Exception {
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        analyzer.results.put("recording-1", cached("recording-1", 120));
        SourceAutomix.request(callback, item("content://music/42"), 120000L);
        assertTrue(job("recording-1").done);
        ExecutorService background = Executors.newSingleThreadExecutor();
        analyzer.executor.background = background;
        CountDownLatch held = new CountDownLatch(1), gate = new CountDownLatch(1);
        background.execute(
                () -> {
                    held.countDown();
                    try {
                        gate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        throw new AssertionError(interrupted);
                    }
                });
        try {
            waitFor(held);
            PlaybackService.keySuffix = "#hifi-new";
            SourceAutomix.request(callback, item("content://music/42"), 120000L);
            TrackAnalysis stale = cached("recording-1", 120);
            analyzer.results.put("recording-1", stale);
            gate.countDown();
            background.shutdown();
            assertTrue(background.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals("The changed rendition is measured", 1, analyzer.analyzed.size());
            assertNotSame(stale, analyzer.results.get("recording-1"));
            assertEquals(
                    Uri.parse("content://music/42"),
                    analyzer.sourceAnalysisUris.get("recording-1"));
        } finally {
            gate.countDown();
            background.shutdownNow();
            assertTrue(background.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void theSameUriWithAnotherRuntimeIsMeasuredAgainNotServedFromTheOldNativeResult()
            throws Exception {
        Analyzer analyzer = callback.f$0.trackAnalyzer;
        TrackAnalysis native120 = cached("recording-1", 120);
        analyzer.results.put("recording-1", native120);
        SourceAutomix.request(callback, item("content://music/42"), 120000L);
        assertTrue(job("recording-1").done);
        assertTrue(analyzer.analyzed.isEmpty());
        SourceAutomix.request(callback, item("content://music/42"), 300000L);
        assertEquals("The longer recording is decoded", 1, analyzer.analyzed.size());
        assertEquals(300, job("recording-1").duration, .001);
        assertNotSame(native120, analyzer.results.get("recording-1"));
    }

    @Test
    public void anOldOwnerPublishesNothingAfterTheServiceSwitchesDuringAnalysis() throws Exception {
        Analyzer older = callback.f$0.trackAnalyzer;
        Callback newer = new Callback();
        older.probe = id -> SourceAutomix.service(newer.f$0);
        SourceAutomix.request(callback, item("content://music/42"), 120000L);
        assertEquals(1, older.analyzed.size());
        assertTrue(older.results.isEmpty());
        assertTrue(older.sourceAnalysisUris.isEmpty());
        assertTrue(older.store.keys.isEmpty());
        assertSame(newer.f$0, SourceAutomix.owner());
    }

    @Test
    public void aFileSnapshotIsStampedWhenTheReadFinishesNotWhenItStarts() throws Exception {
        java.io.File file = tiny("abc");
        Uri uri = Uri.fromFile(file);
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong();
        CountDownLatch entered = new CountDownLatch(1), gate = new CountDownLatch(1);
        SourceAutomix.identityClock = now::get;
        SourceAutomix.statReader =
                f -> {
                    entered.countDown();
                    try {
                        assertTrue(gate.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        throw new AssertionError(interrupted);
                    }
                    return f.length() + "|" + f.lastModified();
                };
        try {
            CompletableFuture<String> read = SourceAutomix.prepareIdentity(uri);
            waitFor(entered);
            now.set(2000);
            gate.countDown();
            String full = read.get(5, TimeUnit.SECONDS);
            assertEquals(
                    PlaybackService.bitchordAutomixKey(uri) + "|3|" + file.lastModified(), full);
            assertEquals(
                    "The snapshot is fresh when the read arrives",
                    full,
                    SourceAutomix.cachedIdentity(uri));
            drainIdentityWorker();
        } finally {
            gate.countDown();
        }
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
