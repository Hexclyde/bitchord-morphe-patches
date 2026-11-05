package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.content.ContextWrapper;
import android.net.Uri;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.datasource.DataSpec;

import com.music.bitchord.playback.PlaybackService;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SourceAutomixLookaheadTest {
    static class Service extends ContextWrapper {
        public SourceAutomixTest.Analyzer trackAnalyzer = new SourceAutomixTest.Analyzer();
        public Object player;
        public Object crossfade = new Object();

        Service() {
            super(RuntimeEnvironment.getApplication());
        }
    }

    static class Callback {
        public Service f$0;

        Callback(Service service) {
            f$0 = service;
        }
    }

    static class Gate {
        final CountDownLatch entered = new CountDownLatch(1), open = new CountDownLatch(1);
    }

    Service service;
    Callback callback;
    SourceAutomixTest.Analyzer analyzer;
    List<String> queue = new ArrayList<>();
    int index, repeat;
    boolean playing = true, shuffle;
    int[] order = {0, 1, 2, 3, 4};
    final List<String> analyzed = Collections.synchronizedList(new ArrayList<>());
    final Map<String, Gate> gates = new ConcurrentHashMap<>();
    final Set<Thread> readers = Collections.synchronizedSet(new HashSet<>());
    final List<Uri> resolved = Collections.synchronizedList(new ArrayList<>());
    ExecutorService background;
    java.util.function.Supplier<SourceAudio.AnalysisFormat> originalFormats;
    java.util.function.Function<java.io.File, String> originalStat;
    java.util.function.LongSupplier originalClock;

    final Timeline timeline =
            new Timeline() {
                public int getWindowCount() {
                    return queue.size();
                }

                public Window getWindow(
                        int index, Window window, long defaultPositionProjectionUs) {
                    return window;
                }

                public int getPeriodCount() {
                    return queue.size();
                }

                public Period getPeriod(int index, Period period, boolean setIds) {
                    return period;
                }

                public int getIndexOfPeriod(Object uid) {
                    return -1;
                }

                public Object getUidOfPeriod(int index) {
                    return index;
                }

                @Override
                public int getNextWindowIndex(int window, int mode, boolean shuffled) {
                    if (!shuffled) return super.getNextWindowIndex(window, mode, false);
                    // A shuffled queue follows its own order.
                    int at = 0;
                    while (order[at] != window) at++;
                    if (mode == Player.REPEAT_MODE_ONE) return window;
                    if (at + 1 < queue.size()) return order[at + 1];
                    return mode == Player.REPEAT_MODE_ALL ? order[0] : -1;
                }
            };

    static android.net.Uri uri(String id) {
        return Uri.parse("https://example.test/" + id);
    }

    final Map<String, Uri> overrides = new HashMap<>();

    MediaItem item(String id) {
        return new MediaItem.Builder()
                .setMediaId(id)
                .setUri(overrides.containsKey(id) ? overrides.get(id) : uri(id))
                .build();
    }

    @Before
    public void setup() throws Exception {
        originalFormats = SourceAudio.analysisFormats;
        originalStat = SourceAutomix.statReader;
        originalClock = SourceAutomix.identityClock;
        SourceAudio.analysisFormats = () -> SourceAutomixTest.format(48000, 120_000_000);
        PlaybackService.stopped = false;
        PlaybackService.keySuffix = "";
        PlaybackService.cached = u -> SourceAutomixTest.cachedAudio();
        PlaybackService.resolver =
                u -> {
                    resolved.add(u);
                    return new DataSpec(u);
                };
        PlaybackService.dspDecoder = null;
        PlaybackService.dspFeatures = null;
        for (String name : new String[] {"jobs", "observed", "pinned", "facts", "replays"}) {
            Field field = SourceAutomix.class.getDeclaredField(name);
            field.setAccessible(true);
            ((Map<?, ?>) field.get(null)).clear();
        }
        SourceAutomix.cancelSpeculation();
        Field posted = SourceAutomix.class.getDeclaredField("posted");
        posted.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) posted.get(null)).set(false);
        Field draining = SourceAutomix.class.getDeclaredField("draining");
        draining.setAccessible(true);
        ((Map<?, ?>) draining.get(null)).clear();
        service = new Service();
        callback = new Callback(service);
        analyzer = service.trackAnalyzer;
        queue.addAll(Arrays.asList("a", "b", "c", "d", "e"));
        service.player =
                Proxy.newProxyInstance(
                        Player.class.getClassLoader(),
                        new Class<?>[] {Player.class},
                        (proxy, method, args) -> {
                            readers.add(Thread.currentThread());
                            switch (method.getName()) {
                                case "getApplicationLooper":
                                    return Looper.getMainLooper();
                                case "isPlaying":
                                    return playing;
                                case "getMediaItemCount":
                                    return queue.size();
                                case "getCurrentMediaItemIndex":
                                    return index;
                                case "getNextMediaItemIndex":
                                    return timeline.getNextWindowIndex(
                                            index,
                                            repeat == Player.REPEAT_MODE_ONE ? 0 : repeat,
                                            shuffle);
                                case "getRepeatMode":
                                    return repeat;
                                case "getShuffleModeEnabled":
                                    return shuffle;
                                case "getCurrentTimeline":
                                    return timeline;
                                case "getMediaItemAt":
                                    return item(queue.get((Integer) args[0]));
                                case "getCurrentMediaItem":
                                    return item(queue.get(index));
                                case "equals":
                                    return proxy == args[0];
                                case "hashCode":
                                    return System.identityHashCode(proxy);
                                case "toString":
                                    return "Queue fixture";
                            }
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == int.class) return 0;
                            if (method.getReturnType() == long.class) return 0L;
                            return null;
                        });
        analyzer.probe =
                id -> {
                    analyzed.add(id);
                    Gate gate = gates.get(id);
                    if (gate == null) return;
                    gate.entered.countDown();
                    try {
                        assertTrue(gate.open.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        throw new AssertionError(interrupted);
                    }
                };
    }

    @After
    public void restore() {
        for (Gate gate : gates.values()) gate.open.countDown();
        if (background != null) background.shutdownNow();
        SourceAutomix.cancelSpeculation();
        SourceAudio.analysisFormats = originalFormats;
        SourceAutomix.statReader = originalStat;
        SourceAutomix.identityClock = originalClock;
        PlaybackService.resolver = null;
        PlaybackService.cached = null;
    }

    Gate gate(String id) {
        Gate gate = new Gate();
        gates.put(id, gate);
        return gate;
    }

    void background() {
        background = Executors.newSingleThreadExecutor();
        analyzer.executor.background = background;
    }

    /** What the native controller does: both requests, then the host rebuilds its read-ahead. */
    void nativePair() throws Exception {
        SourceAutomix.service(service);
        SourceAutomix.request(callback, item(queue.get(index)), 180_000L);
        int next = timeline.getNextWindowIndex(index, repeat == 1 ? 0 : repeat, shuffle);
        if (next >= 0) SourceAutomix.request(callback, item(queue.get(next)), 180_000L);
        SourceAutomix.queueChanged(service, service.player);
        idle();
    }

    static void idle() {
        ShadowLooper.idleMainLooper();
    }

    /**
     * Lets the main looper run the snapshot the finished pair posted, until the gate is reached.
     */
    void reached(Gate gate) throws Exception {
        await(() -> gate.entered.getCount() == 0);
    }

    void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < end) {
            idle();
            if (condition.getAsBoolean()) return;
            Thread.sleep(5);
        }
        fail("Timed out");
    }

    SourceAutomix.Job job(String id) {
        try {
            Field field = SourceAutomix.class.getDeclaredField("jobs");
            field.setAccessible(true);
            Map<?, ?> all = (Map<?, ?>) field.get(null);
            Map<?, ?> state = (Map<?, ?>) all.get(analyzer);
            return state == null ? null : (SourceAutomix.Job) state.get(id);
        } catch (ReflectiveOperationException unavailable) {
            throw new AssertionError(unavailable);
        }
    }

    @Test
    public void currentAndNextAreMeasuredBeforeTheExtraItem() throws Exception {
        background();
        Gate first = gate("a");
        SourceAutomix.service(service);
        SourceAutomix.request(callback, item("a"), 180_000L);
        SourceAutomix.request(callback, item("b"), 180_000L);
        SourceAutomix.queueChanged(service, service.player);
        reached(first);
        idle();
        assertNull("Nothing is requested while the pair is unfinished", job("c"));
        first.open.countDown();
        await(() -> analyzed.size() == 3);
        assertEquals(Arrays.asList("a", "b", "c"), analyzed);
    }

    @Test
    public void onlyOneOccurrenceBeyondTheNextIsMeasured() throws Exception {
        nativePair();
        await(() -> analyzed.contains("c"));
        idle();
        assertEquals(Arrays.asList("a", "b", "c"), analyzed);
        assertNull(job("d"));
        assertEquals(Collections.singleton(Thread.currentThread()), readers);
    }

    @Test
    public void aShuffledQueueFollowsTheTimelineOrder() throws Exception {
        shuffle = true;
        order = new int[] {0, 3, 1, 2, 4};
        nativePair();
        assertEquals(Arrays.asList("a", "d", "b"), analyzed);
    }

    @Test
    public void repeatOneStillLooksPastTheRealNext() throws Exception {
        repeat = Player.REPEAT_MODE_ONE;
        nativePair();
        assertEquals(Arrays.asList("a", "b", "c"), analyzed);
    }

    @Test
    public void repeatAllWrapsToTheStartOfTheQueue() throws Exception {
        repeat = Player.REPEAT_MODE_ALL;
        index = 3;
        nativePair();
        assertEquals(Arrays.asList("d", "e", "a"), analyzed);
    }

    @Test
    public void aWrapBackToCurrentOrNextIsNotAnExtra() throws Exception {
        repeat = Player.REPEAT_MODE_ALL;
        queue = new ArrayList<>(Arrays.asList("a", "b"));
        nativePair();
        assertEquals(Arrays.asList("a", "b"), analyzed);
        queue = new ArrayList<>(Arrays.asList("a", "b", "a"));
        nativePair();
        assertNull("The same recording again is not another occurrence", job("c"));
        assertEquals(Arrays.asList("a", "b"), analyzed);
    }

    @Test
    public void noExtraWhenNothingFollowsTheNext() throws Exception {
        index = 3;
        nativePair();
        assertEquals(Arrays.asList("d", "e"), analyzed);
        index = 4;
        nativePair();
        assertEquals(Arrays.asList("d", "e"), analyzed);
    }

    @Test
    public void anUnfinishedOrWaitingPairBlocksTheExtra() throws Exception {
        analyzer.thermalStatus = 3;
        nativePair();
        assertTrue(analyzed.isEmpty());
        analyzer.thermalStatus = 0;
        SourceAutomix.Job now = job("a");
        now.phase = "memory";
        SourceAutomix.queueChanged(service, service.player);
        idle();
        assertNull(job("c"));
        assertFalse(analyzed.contains("c"));
    }

    @Test
    public void aPairThatSettledWithoutAResultDoesNotStarveTheExtra() throws Exception {
        analyzer.fail = true;
        nativePair();
        assertEquals("The pair is still retrying", Arrays.asList("a", "b"), analyzed);
        for (String id : new String[] {"a", "b"}) job(id).attempts = 3;
        SourceAutomix.queueChanged(service, service.player);
        idle();
        assertTrue("A pair out of attempts no longer holds it back", analyzed.contains("c"));
    }

    @Test
    public void aSourceThatIsNotCachedWaitsWithoutResolvingOrSpendingAnAttempt() throws Exception {
        PlaybackService.cached =
                u -> u.getPath().endsWith("/c") ? null : SourceAutomixTest.cachedAudio();
        nativePair();
        SourceAutomix.Job extra = job("c");
        assertNotNull(extra);
        assertEquals(Arrays.asList("a", "b"), analyzed);
        assertTrue(resolved.isEmpty());
        assertEquals(0, extra.attempts);
        assertEquals(0L, extra.retryAfter);
        assertFalse(extra.running);
        assertFalse(analyzer.running.contains("c"));
        PlaybackService.cached = u -> SourceAutomixTest.cachedAudio();
        SourceAutomix.queueChanged(service, service.player);
        idle();
        assertEquals("Once cached, the same job measures", Arrays.asList("a", "b", "c"), analyzed);
        assertTrue(resolved.isEmpty());
    }

    @Test
    public void aLocalFileThatCannotBeReadWaitsAsWell() throws Exception {
        Uri missing = Uri.fromFile(new java.io.File("/nonexistent/lookahead-c.flac"));
        overrides.put("c", missing);
        PlaybackService.cached =
                u -> "file".equals(u.getScheme()) ? null : SourceAutomixTest.cachedAudio();
        SourceAutomix.statReader = f -> "0|0";
        SourceAutomix.identityClock = () -> 0L;
        SourceAutomix.prepareIdentity(missing).get(5, TimeUnit.SECONDS);
        nativePair();
        SourceAutomix.Job extra = job("c");
        assertNotNull(extra);
        assertEquals(Arrays.asList("a", "b"), analyzed);
        assertTrue(resolved.isEmpty());
        assertEquals(0, extra.attempts);
        assertEquals(0L, extra.retryAfter);
    }

    @Test
    public void workThePlayerNeedsNowCancelsTheExtraAndRunsFirst() throws Exception {
        nativePair();
        await(() -> analyzed.contains("c"));
        analyzed.clear();
        background();
        index = 1;
        queue.set(3, "d");
        // c is current's next once the queue moves; a new d is the extra and blocks.
        Gate extra = gate("d");
        SourceAutomix.cancelSpeculation();
        nativePair();
        reached(extra);
        int tasks = analyzer.executor.tasks;
        SourceAutomix.Job held = job("d");
        assertFalse(held.cancelled);
        SourceAutomix.request(callback, item("b"), 180_000L);
        SourceAutomix.request(callback, item("c"), 180_000L);
        assertFalse("Finished requests do not cancel the extra", held.cancelled);
        assertEquals(tasks, analyzer.executor.tasks);
        SourceAutomix.request(callback, item("z"), 180_000L);
        assertTrue("A request that needs work does", held.cancelled);
        assertFalse("It runs behind the single worker", analyzed.contains("z"));
        extra.open.countDown();
        await(() -> analyzed.contains("z"));
        assertFalse("A cancelled run completes nothing", held.done);
    }

    @Test
    public void aRequestForTheSameRecordingAdoptsTheRunningExtra() throws Exception {
        background();
        Gate extra = gate("c");
        nativePair();
        reached(extra);
        SourceAutomix.Job running = job("c");
        assertTrue(running.speculative);
        int tasks = analyzer.executor.tasks;
        index = 1;
        SourceAutomix.request(callback, item("b"), 180_000L);
        SourceAutomix.request(callback, item("c"), 180_000L);
        assertSame("One job serves both", running, job("c"));
        assertFalse(running.speculative);
        assertFalse(running.cancelled);
        assertEquals("No second task is queued", tasks, analyzer.executor.tasks);
        extra.open.countDown();
        await(() -> running.done);
        assertEquals(1, Collections.frequency(analyzed, "c"));
    }

    @Test
    public void aPauseCancelsTheExtraAndNothingStartsWhilePaused() throws Exception {
        nativePair();
        await(() -> analyzed.contains("c"));
        analyzed.clear();
        background();
        index = 1;
        Gate extra = gate("d");
        nativePair();
        reached(extra);
        SourceAutomix.Job held = job("d");
        playing = false;
        SourceAutomix.cancelLookahead(service);
        assertTrue(held.cancelled);
        SourceAutomix.queueChanged(service, service.player);
        idle();
        extra.open.countDown();
        await(() -> !analyzer.running.contains("d"));
        assertNull(analyzer.results.get("d"));
        assertSame("No new job is made while paused", held, job("d"));
    }

    @Test
    public void aCancelledExtraDoesNotBlockTheNextForegroundRequestForIt() throws Exception {
        nativePair();
        await(() -> analyzed.contains("c"));
        analyzed.clear();
        background();
        index = 1;
        Gate extra = gate("d");
        nativePair();
        reached(extra);
        SourceAutomix.cancelLookahead(service);
        analyzer.results.remove("d");
        // The queue reaches d while the cancelled run still holds the id.
        SourceAutomix.request(callback, item("d"), 180_000L);
        assertEquals("Not started twice at once", 1, Collections.frequency(analyzed, "d"));
        extra.open.countDown();
        gates.remove("d");
        await(() -> job("d") != null && job("d").done);
        assertNotNull(analyzer.results.get("d"));
        assertEquals(2, Collections.frequency(analyzed, "d"));
    }

    @Test
    public void aMovedQueueOrAReleasedOwnerEndsTheClaimWithoutPublishing() throws Exception {
        nativePair();
        await(() -> analyzed.contains("c"));
        analyzed.clear();
        background();
        index = 1;
        Gate extra = gate("d");
        nativePair();
        reached(extra);
        SourceAutomix.Job held = job("d");
        queue.remove(3);
        SourceAutomix.queueChanged(service, service.player);
        idle();
        assertTrue("The extra changed with the queue", held.cancelled);
        extra.open.countDown();
        await(() -> !analyzer.running.contains("d"));
        assertNull(analyzer.results.get("d"));
        index = 1;
        SourceAutomix.release(service);
        SourceAutomix.queueChanged(service, service.player);
        idle();
        assertNull(job("c"));
    }

    @Test
    public void aNewOwnerCancelsTheOldExtra() throws Exception {
        nativePair();
        await(() -> analyzed.contains("c"));
        Service newer = new Service();
        SourceAutomix.Job extra = job("c");
        assertNotNull(extra);
        SourceAutomix.service(newer);
        assertFalse("A finished measurement stays", extra.cancelled);
        assertNotNull(analyzer.results.get("c"));
    }

    @Test
    public void onlyOneWorkerRunsAndTheExtraAddsOneTask() throws Exception {
        nativePair();
        await(() -> analyzed.contains("c"));
        assertEquals("Current, next and one extra", 3, analyzer.executor.tasks);
        assertEquals(Collections.singleton(Thread.currentThread()), readers);
    }

    @Test
    public void aNativePairDoneAgainDoesNotRestartTheExtra() throws Exception {
        nativePair();
        await(() -> analyzed.contains("c"));
        int tasks = analyzer.executor.tasks;
        for (int tick = 0; tick < 5; tick++) nativePair();
        assertEquals(tasks, analyzer.executor.tasks);
        assertEquals(Arrays.asList("a", "b", "c"), analyzed);
    }

    @Test
    public void aSameIdItemWithAnotherSourceDoesNotReuseTheOldJobsReadiness() throws Exception {
        PlaybackService.cached =
                u -> u.getPath().endsWith("/c") ? null : SourceAutomixTest.cachedAudio();
        nativePair();
        assertEquals("The extra waits for its source", Arrays.asList("a", "b"), analyzed);
        analyzed.clear();
        PlaybackService.cached = u -> SourceAutomixTest.cachedAudio();
        // The queue entry for b now points at another quality; no native request has come yet.
        overrides.put("b", Uri.parse("https://example.test/b-lossless"));
        SourceAutomix.queueChanged(service, service.player);
        idle();
        assertTrue("b's old finished job is not its pair readiness", analyzed.isEmpty());
        assertTrue(job("c") == null || job("c").cancelled);
        nativePair();
        assertEquals(Arrays.asList("b", "c"), analyzed);
    }

    @Test
    public void aPairJobReplacedWhileTheExtraRunsEndsTheExtraWithoutPublishing() throws Exception {
        background();
        Gate extra = gate("c");
        nativePair();
        reached(extra);
        SourceAutomix.Job held = job("c");
        SourceAutomix.Job oldNext = job("b");
        overrides.put("b", Uri.parse("https://example.test/b-lossless"));
        SourceAutomix.request(callback, item("b"), 180_000L);
        assertNotSame("The next recording got a new job", oldNext, job("b"));
        extra.open.countDown();
        await(() -> !analyzer.running.contains("c"));
        assertTrue(held.cancelled);
        assertFalse("Nothing from the old pair is completed", held.done);
        if (analyzer.results.get("c") != null) {
            SourceAutomix.Job later = job("c");
            assertNotSame("Only a new job may have produced it", held, later);
            assertTrue(later.done);
            assertEquals(2, Collections.frequency(analyzed, "c"));
        }
    }

    @Test
    public void aForeignOwnerCannotStartOrCancelTheExtra() throws Exception {
        background();
        Gate extra = gate("c");
        nativePair();
        reached(extra);
        SourceAutomix.Job held = job("c");
        Service foreign = new Service();
        SourceAutomix.cancelLookahead(foreign);
        SourceAutomix.cancelLookahead(null);
        SourceAutomix.queueChanged(foreign, foreign.player);
        SourceAutomix.queueChanged(null, null);
        idle();
        assertFalse(held.cancelled);
        extra.open.countDown();
        await(() -> held.done);
        assertNotNull(analyzer.results.get("c"));
    }

    @Test
    public void aQueueChangeBeforeAnyOwnerIsKnownStartsNothing() throws Exception {
        SourceAutomix.release(service);
        SourceAutomix.queueChanged(service, service.player);
        idle();
        assertTrue(analyzed.isEmpty());
    }

    @Test
    public void openingTheEditorCancelsTheExtraEvenWhenBothSelectedAreDone() throws Exception {
        background();
        Gate extra = gate("c");
        nativePair();
        reached(extra);
        SourceAutomix.Job held = job("c");
        TransitionRuntime.Pair pair =
                new TransitionRuntime.Pair("a", "b", "A", "B", 180, 180, 0, 1);
        pair.outgoing = item("a");
        pair.incoming = item("b");
        assertTrue(job("a").done && job("b").done);
        SourceAutomix.requestEditor(pair, false);
        assertTrue("The editor's work does not wait behind the extra", held.cancelled);
        extra.open.countDown();
        await(() -> !analyzer.running.contains("c"));
        assertNull(analyzer.results.get("c"));
    }

    public static class Media extends android.content.ContentProvider {
        static final java.util.concurrent.atomic.AtomicInteger opens =
                new java.util.concurrent.atomic.AtomicInteger();
        static volatile java.io.File file;
        static volatile boolean refuse;
        static volatile String style = "file";

        public boolean onCreate() {
            return true;
        }

        public android.database.Cursor query(
                Uri uri, String[] projection, String selection, String[] args, String order) {
            return null;
        }

        public String getType(Uri uri) {
            return "audio/flac";
        }

        public Uri insert(Uri uri, android.content.ContentValues values) {
            return null;
        }

        public int delete(Uri uri, String selection, String[] args) {
            return 0;
        }

        public int update(Uri uri, android.content.ContentValues v, String s, String[] a) {
            return 0;
        }

        @Override
        public android.os.ParcelFileDescriptor openFile(Uri uri, String mode)
                throws java.io.FileNotFoundException {
            opens.incrementAndGet();
            if (refuse) throw new SecurityException("revoked");
            try {
                if (style.equals("pipe")) return android.os.ParcelFileDescriptor.createPipe()[0];
            } catch (java.io.IOException unavailable) {
                throw new java.io.FileNotFoundException();
            }
            return android.os.ParcelFileDescriptor.open(
                    file, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
        }

        @Override
        public android.content.res.AssetFileDescriptor openAssetFile(Uri uri, String mode)
                throws java.io.FileNotFoundException {
            if (!style.equals("partial")) return super.openAssetFile(uri, mode);
            // Promises more than the file holds.
            return new android.content.res.AssetFileDescriptor(openFile(uri, mode), 0, 4096);
        }
    }

    boolean refuseContent;
    String style = "file";

    private void contentExtra(Uri extra, String authority) throws Exception {
        java.io.File audio = java.io.File.createTempFile("lookahead", ".flac");
        audio.deleteOnExit();
        java.nio.file.Files.write(audio.toPath(), new byte[2048]);
        Media.file = audio;
        Media.refuse = refuseContent;
        Media.style = style;
        Media.opens.set(0);
        org.robolectric.Robolectric.buildContentProvider(Media.class).create(authority);
        overrides.put("c", extra);
        PlaybackService.cached =
                u -> "content".equals(u.getScheme()) ? null : SourceAutomixTest.cachedAudio();
        nativePair();
    }

    @Test
    public void aTrustedLibraryAudioItemIsOpenedWithoutResolving() throws Exception {
        contentExtra(Uri.parse("content://media/external/audio/media/42"), "media");
        SourceAutomix.Job extra = job("c");
        assertNotNull(extra);
        assertEquals("The provider was probed on the worker", 1, Media.opens.get());
        assertNotNull(extra.spec);
        assertEquals(extra.uri, extra.spec.uri);
        assertTrue(resolved.isEmpty());
        assertEquals(1, extra.attempts);
    }

    @Test
    public void aTrustedDocumentIsOpenedWithoutResolving() throws Exception {
        contentExtra(
                Uri.parse(
                        "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fc.flac"),
                "com.android.externalstorage.documents");
        assertNotNull(job("c").spec);
        assertTrue(resolved.isEmpty());
    }

    @Test
    public void aRevokedContentItemWaitsWithoutAnAttempt() throws Exception {
        refuseContent = true;
        contentExtra(Uri.parse("content://media/external/audio/media/43"), "media");
        SourceAutomix.Job extra = job("c");
        assertNotNull(extra);
        assertNull(extra.spec);
        assertEquals(0, extra.attempts);
        assertEquals(0L, extra.retryAfter);
        assertTrue(resolved.isEmpty());
    }

    @Test
    public void cloudAndPickerContentIsNeverOpenedAhead() throws Exception {
        for (String text :
                new String[] {
                    "content://com.google.android.apps.photos.contentprovider/0/1/x/2",
                    "content://media/picker/0/com.android.providers.media.photopicker/media/9",
                    "content://media/external/video/media/9",
                    "content://media/external/audio/media/abc",
                    "content://com.android.providers.downloads.documents/root/downloads"
                }) {
            Uri content = Uri.parse(text);
            assertFalse(text, SourceAutomix.trustedContent(content));
        }
        contentExtra(
                Uri.parse("content://com.google.android.apps.photos.contentprovider/0/1/x/2"),
                "com.google.android.apps.photos.contentprovider");
        assertEquals("The provider is not even asked", 0, Media.opens.get());
        assertEquals(0, job("c").attempts);
        assertTrue(resolved.isEmpty());
    }

    static class GatedStore extends SourceAutomixTest.Store {
        final Gate gate = new Gate();

        @Override
        public void save(String key, com.music.bitchord.playback.smart.TrackAnalysis value) {
            if (key.contains("|c|")) {
                gate.entered.countDown();
                try {
                    assertTrue(gate.open.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    throw new AssertionError(interrupted);
                }
            }
            super.save(key, value);
        }
    }

    @Test
    public void anExtraCancelledWhileSavingKeepsItsAncestryForTheNextSource() throws Exception {
        background();
        GatedStore store = new GatedStore();
        analyzer.store = store;
        nativePair();
        reached(store.gate);
        Object oldResult = analyzer.results.get("c");
        assertNotNull("The old source's result is already in the map", oldResult);
        SourceAutomix.Job oldJob = job("c");
        SourceAutomix.cancelLookahead(service);
        overrides.put("c", Uri.parse("https://example.test/c-lossless"));
        SourceAutomix.request(callback, item("c"), 180_000L);
        SourceAutomix.Job fresh = job("c");
        assertNotSame(oldJob, fresh);
        assertFalse("The old plain result is not adopted for another source", fresh.adopted);
        assertFalse(fresh.done);
        assertTrue(fresh.superseded);
        store.gate.open.countDown();
        await(() -> fresh.done);
        assertNotSame(oldResult, analyzer.results.get("c"));
        assertEquals(2, Collections.frequency(analyzed, "c"));
    }

    @Test
    public void aPipeOrAPartialContentFileWaitsWithoutAnAttempt() throws Exception {
        for (String shape : new String[] {"pipe", "partial"}) {
            setup();
            style = shape;
            contentExtra(Uri.parse("content://media/external/audio/media/44"), "media");
            SourceAutomix.Job extra = job("c");
            assertNotNull(shape, extra);
            assertEquals(shape, 1, Media.opens.get());
            assertNull(shape, extra.spec);
            assertEquals(shape, 0, extra.attempts);
            assertTrue(resolved.isEmpty());
        }
    }

    @Test
    public void aVisibleEditorHoldsTheExtraBackUntilItsOwnScreenCloses() throws Exception {
        Object first = new Object(), second = new Object();
        SourceAutomix.editor(first, true);
        nativePair();
        assertEquals("Only the pair is measured", Arrays.asList("a", "b"), analyzed);
        // A native read-ahead callback after the editor opened does not restart it.
        SourceAutomix.queueChanged(service, service.player);
        nativePair();
        idle();
        assertEquals(Arrays.asList("a", "b"), analyzed);
        // A rotation: the new screen shows before the old one reports closed.
        SourceAutomix.editor(second, true);
        SourceAutomix.editor(first, false);
        nativePair();
        assertEquals("The old screen cannot clear the new one", Arrays.asList("a", "b"), analyzed);
        SourceAutomix.editor(second, false);
        idle();
        assertEquals(Arrays.asList("a", "b", "c"), analyzed);
    }

    @Test
    public void openingTheEditorAgainStopsARunningExtraAndANativeCallbackDoesNotRestartIt()
            throws Exception {
        background();
        Gate extra = gate("c");
        nativePair();
        reached(extra);
        SourceAutomix.Job held = job("c");
        Object screen = new Object();
        SourceAutomix.editor(screen, true);
        assertTrue(held.cancelled);
        extra.open.countDown();
        await(() -> !analyzer.running.contains("c"));
        SourceAutomix.queueChanged(service, service.player);
        idle();
        assertEquals(1, Collections.frequency(analyzed, "c"));
        SourceAutomix.editor(screen, false);
        await(() -> Collections.frequency(analyzed, "c") == 2);
    }

    @Test
    public void aLostEditorScreenCannotHoldTheExtraBackForever() throws Exception {
        Object screen = new Object();
        SourceAutomix.editor(screen, true);
        java.lang.ref.WeakReference<Object> weak = new java.lang.ref.WeakReference<>(screen);
        screen = null;
        for (int n = 0; n < 20 && weak.get() != null; n++) {
            System.gc();
            Thread.sleep(20);
        }
        Assume.assumeTrue(weak.get() == null);
        nativePair();
        assertEquals(Arrays.asList("a", "b", "c"), analyzed);
    }

    private void holdPreview(Object controller) throws Exception {
        Field unsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafe.setAccessible(true);
        Object session =
                ((sun.misc.Unsafe) unsafe.get(null))
                        .allocateInstance(TransitionPreview.Session.class);
        Field field = TransitionPreview.Session.class.getDeclaredField("controller");
        field.setAccessible(true);
        field.set(session, controller);
        Field held = TransitionPreview.class.getDeclaredField("held");
        held.setAccessible(true);
        held.set(null, session);
    }

    private static void releasePreview() throws Exception {
        Field held = TransitionPreview.class.getDeclaredField("held");
        held.setAccessible(true);
        held.set(null, null);
    }

    @Test
    public void aPreviewHeldByAnotherControllerBlocksNothing() throws Exception {
        try {
            holdPreview(new Object());
            nativePair();
            assertEquals(Arrays.asList("a", "b", "c"), analyzed);
        } finally {
            releasePreview();
        }
    }

    @Test
    public void aPreviewHeldByTheAudibleControllerBlocksTheExtra() throws Exception {
        try {
            holdPreview(service.crossfade);
            nativePair();
            assertEquals(
                    "The pair is measured, the extra waits", Arrays.asList("a", "b"), analyzed);
        } finally {
            releasePreview();
        }
        SourceAutomix.queueChanged(service, service.player);
        idle();
        assertEquals(Arrays.asList("a", "b", "c"), analyzed);
    }
}
