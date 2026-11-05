package app.bitchord.telegram;

import static org.junit.Assert.*;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class TransitionWaveCacheTest {
    private final AtomicInteger reads = new AtomicInteger();

    @Before
    public void setup() throws Exception {
        var field = SoundExtras.class.getDeclaredField("app");
        field.setAccessible(true);
        field.set(null, RuntimeEnvironment.getApplication());
        TransitionWaveCache.clearMemory();
        File dir = new File(RuntimeEnvironment.getApplication().getCacheDir(), "automix_waveforms");
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
    }

    private float[][] measured() {
        reads.incrementAndGet();
        return new float[][] {{.1f, .2f}, {.3f, .4f}, {.5f, .6f}};
    }

    @Test
    public void coveringPeaksSurviveMemoryEvictionAndNeverCrossRecordingIdentity()
            throws Exception {
        String a = TransitionRuntime.key("wave-v1", "selected-flac");
        assertNotNull(TransitionWaveCache.load(a, 0, 12, this::measured));
        assertNotNull(TransitionWaveCache.load(a, 2, 9, this::measured));
        assertEquals(1, reads.get());
        TransitionWaveCache.clearMemory();
        var fromDisk = TransitionWaveCache.load(a, 3, 8, this::measured);
        assertEquals(1, reads.get());
        assertArrayEquals(new float[] {.3f, .4f}, fromDisk.bands[1], 0);
        String b = TransitionRuntime.key("wave-v1", "different-master");
        TransitionWaveCache.load(b, 0, 12, this::measured);
        assertEquals(2, reads.get());
        assertNull(TransitionWaveCache.peek(a, 0, 13));
    }

    @Test
    public void concurrentPreviewAndPrefetchShareOneDecode() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1),
                release = new CountDownLatch(1),
                otherStarted = new CountDownLatch(1);
        try {
            String id = TransitionRuntime.key("wave-v1", "same-recording");
            var first =
                    workers.submit(
                            () ->
                                    TransitionWaveCache.load(
                                            id,
                                            0,
                                            12,
                                            () -> {
                                                entered.countDown();
                                                assertTrue(release.await(5, TimeUnit.SECONDS));
                                                return measured();
                                            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second =
                    workers.submit(
                            () -> {
                                otherStarted.countDown();
                                return TransitionWaveCache.load(id, 0, 12, this::measured);
                            });
            assertTrue(otherStarted.await(5, TimeUnit.SECONDS));
            release.countDown();
            assertNotNull(first.get(5, TimeUnit.SECONDS));
            assertNotNull(second.get(5, TimeUnit.SECONDS));
            assertEquals(1, reads.get());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    public void corruptOrInvalidDataIsDecodedAgainInsteadOfBeingShown() throws Exception {
        String id = TransitionRuntime.key("wave-v1", "recording");
        TransitionWaveCache.load(id, 0, 12, this::measured);
        TransitionWaveCache.clearMemory();
        File dir = new File(RuntimeEnvironment.getApplication().getCacheDir(), "automix_waveforms");
        for (File f : dir.listFiles())
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(new byte[] {1, 2, 3});
            }
        assertNotNull(TransitionWaveCache.load(id, 0, 12, this::measured));
        assertEquals(2, reads.get());
        assertNull(
                TransitionWaveCache.load(
                        TransitionRuntime.key("bad", "recording"),
                        0,
                        12,
                        () -> new float[][] {{Float.NaN, 0}, {0, 0}, {0, 0}}));
    }

    @Test
    public void aWiderDecodeInFlightIsSharedByANarrowerWindow() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            String id = TransitionRuntime.key("wave-v1", "covering");
            var wide =
                    workers.submit(
                            () ->
                                    TransitionWaveCache.load(
                                            id,
                                            0,
                                            20,
                                            () -> {
                                                entered.countDown();
                                                assertTrue(release.await(5, TimeUnit.SECONDS));
                                                return measured();
                                            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var narrow = workers.submit(() -> TransitionWaveCache.load(id, 4, 12, this::measured));
            Thread.sleep(100);
            release.countDown();
            assertNotNull(wide.get(5, TimeUnit.SECONDS));
            assertEquals(0, narrow.get(5, TimeUnit.SECONDS).from, 0);
            assertEquals(1, reads.get());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    private String completedWithoutPeaks(String name) throws Exception {
        String id = TransitionRuntime.key("wave-v1", name);
        Class<?> type = Class.forName(TransitionWaveCache.class.getName() + "$Work");
        var constructor =
                type.getDeclaredConstructor(
                        String.class, double.class, double.class, Callable.class);
        constructor.setAccessible(true);
        FutureTask<?> finished =
                (FutureTask<?>) constructor.newInstance(id, 0d, 12d, (Callable<Object>) () -> null);
        finished.run();
        var member = TransitionWaveCache.class.getDeclaredField("running");
        member.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> running = (java.util.Map<String, Object>) member.get(null);
        // Completion precedes the owner's removal from the shared-work registry.
        running.put(id + "-" + TransitionRuntime.key("0.0", "12.0"), finished);
        return id;
    }

    @Test
    public void aWaiterDecodesForItselfWhenTheSharedDecodeEndsWithoutPeaks() throws Exception {
        String id = completedWithoutPeaks("abandoned");
        assertNotNull(
                "A live waiter is not left without peaks by someone else's failed decode",
                TransitionWaveCache.load(id, 0, 12, this::measured));
        assertEquals(1, reads.get());
    }

    @Test
    public void aWaiterWhoseOwnDecodeAlsoFailsStopsAfterOneAttempt() throws Exception {
        String id = completedWithoutPeaks("unreadable");
        AtomicInteger waiterReads = new AtomicInteger();
        assertNull(
                TransitionWaveCache.load(
                        id,
                        0,
                        12,
                        () -> {
                            waiterReads.incrementAndGet();
                            return null;
                        }));
        assertEquals("No runaway retry", 1, waiterReads.get());
    }

    @Test
    public void aVisibleWindowCancelsSpeculativeWorkButKeepsACoveringDecode() throws Exception {
        String id = TransitionRuntime.key("wave-v1", "visible");
        Class<?> type = Class.forName(TransitionWaveCache.class.getName() + "$Work");
        var constructor =
                type.getDeclaredConstructor(
                        String.class, double.class, double.class, Callable.class);
        constructor.setAccessible(true);
        var flag = type.getDeclaredField("background");
        flag.setAccessible(true);
        var member = TransitionWaveCache.class.getDeclaredField("running");
        member.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> running = (java.util.Map<String, Object>) member.get(null);
        FutureTask<?> far =
                (FutureTask<?>)
                        constructor.newInstance(id, 40d, 52d, (Callable<Object>) () -> null);
        FutureTask<?> covering =
                (FutureTask<?>) constructor.newInstance(id, 0d, 20d, (Callable<Object>) () -> null);
        FutureTask<?> foreground =
                (FutureTask<?>)
                        constructor.newInstance(id, 60d, 72d, (Callable<Object>) () -> null);
        flag.setBoolean(far, true);
        flag.setBoolean(covering, true);
        running.put("far", far);
        running.put("covering", covering);
        running.put("foreground", foreground);
        try {
            TransitionWaveCache.yieldTo(id, 4, 12);
            assertTrue("Obsolete speculative work stops", far.isCancelled());
            assertFalse("A decode that covers the window is shared", covering.isCancelled());
            assertFalse("Visible work is never cancelled", foreground.isCancelled());
        } finally {
            running.remove("far");
            running.remove("covering");
            running.remove("foreground");
        }
    }

    @Test
    public void aFailedDecodeDoesNotPoisonTheCacheForALaterLoad() throws Exception {
        String id = TransitionRuntime.key("wave-v1", "flaky");
        assertNull(TransitionWaveCache.load(id, 0, 12, () -> null));
        assertNotNull(TransitionWaveCache.load(id, 0, 12, this::measured));
        assertEquals(1, reads.get());
    }
}
