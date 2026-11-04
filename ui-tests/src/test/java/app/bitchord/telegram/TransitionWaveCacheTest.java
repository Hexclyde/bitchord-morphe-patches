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

    @Test
    public void aFailedDecodeDoesNotPoisonTheCacheForALaterLoad() throws Exception {
        String id = TransitionRuntime.key("wave-v1", "flaky");
        assertNull(TransitionWaveCache.load(id, 0, 12, () -> null));
        assertNotNull(TransitionWaveCache.load(id, 0, 12, this::measured));
        assertEquals(1, reads.get());
    }
}
