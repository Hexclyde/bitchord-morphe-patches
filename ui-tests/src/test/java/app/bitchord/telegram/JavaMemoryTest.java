package app.bitchord.telegram;

import static org.junit.Assert.*;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class JavaMemoryTest {
    static final long MB = 1L << 20;
    final List<SourceAudio.JavaMemory> held = new ArrayList<>();

    SourceAudio.JavaMemory reserve(long maximum, long used, long bytes) {
        SourceAudio.JavaMemory loan = SourceAudio.reserveJava(maximum, used, bytes);
        if (loan != null) held.add(loan);
        return loan;
    }

    @After
    public void release() {
        for (SourceAudio.JavaMemory loan : held) loan.close();
        held.clear();
    }

    @Test
    public void simultaneousModelAndAnalysisCannotSpendTheSameHeadroom() {
        assertNotNull(reserve(192 * MB, 80 * MB, 64 * MB));
        assertNull(reserve(192 * MB, 80 * MB, 32 * MB));
        assertEquals(0, SourceAudio.analysisAvailable(192 * MB, 80 * MB));
    }

    @Test
    public void closingOneLoanPreservesOtherClaimsAndIsIdempotent() {
        SourceAudio.JavaMemory first = reserve(192 * MB, 80 * MB, 32 * MB);
        SourceAudio.JavaMemory second = reserve(192 * MB, 80 * MB, 32 * MB);
        assertNotNull(first);
        assertNotNull(second);
        first.close();
        first.close();
        assertNull(reserve(192 * MB, 80 * MB, 64 * MB));
        second.close();
        assertNotNull(reserve(192 * MB, 80 * MB, 64 * MB));
    }

    @Test
    public void failedAllocationReleasesItsReservation() {
        try (SourceAudio.JavaMemory ignored = reserve(192 * MB, 80 * MB, 64 * MB)) {
            throw new IllegalStateException("allocation failed");
        } catch (IllegalStateException expected) {
            assertNotNull(reserve(192 * MB, 80 * MB, 64 * MB));
        }
    }

    @Test
    public void invalidAndOverflowingClaimsNeverEnterTheLedger() {
        for (long[] values :
                new long[][] {
                    {0, 0, 1},
                    {192 * MB, -1, 1},
                    {192 * MB, 192 * MB, 1},
                    {192 * MB, 80 * MB, 0},
                    {192 * MB, 80 * MB, -1},
                    {192 * MB, 80 * MB, Long.MAX_VALUE}
                }) assertNull(reserve(values[0], values[1], values[2]));
        assertNotNull(reserve(192 * MB, 80 * MB, 64 * MB));
    }

    @Test
    public void constrainedAndRoomyHeapsAdmitWhatTheirHeadroomAllows() {
        // Margin is the eighth of the heap, clamped to 8..24 MiB: 24 MiB on both.
        assertNull(reserve(192 * MB, 38 * MB, 130 * MB + 1));
        assertNotNull(reserve(192 * MB, 38 * MB, 130 * MB));
        assertNull(reserve(192 * MB, 38 * MB, 1));
        release();
        assertNull(reserve(512 * MB, 38 * MB, 450 * MB + 1));
        assertNotNull(reserve(512 * MB, 38 * MB, 450 * MB));
    }

    @Test
    public void concurrentGrantsShareOneHeapAndReturnItOnClose() {
        SourceAudio.JavaMemory a = reserve(512 * MB, 38 * MB, 150 * MB);
        SourceAudio.JavaMemory b = reserve(512 * MB, 38 * MB, 150 * MB);
        SourceAudio.JavaMemory c = reserve(512 * MB, 38 * MB, 150 * MB);
        assertNotNull(a);
        assertNotNull(b);
        assertNotNull(c);
        assertNull(reserve(512 * MB, 38 * MB, 1));
        // Memory that landed in the heap moves from pending into used and is counted once.
        a.materialized(150 * MB);
        assertNull(reserve(512 * MB, 188 * MB, 1));
        a.close();
        SourceAudio.JavaMemory again = reserve(512 * MB, 38 * MB, 150 * MB);
        assertNotNull(again);
        // The same two grants leave a constrained heap with nothing.
        again.close();
        b.close();
        c.close();
        assertNotNull(reserve(192 * MB, 38 * MB, 65 * MB));
        assertNotNull(reserve(192 * MB, 38 * MB, 65 * MB));
        assertNull(reserve(192 * MB, 38 * MB, 1));
    }

    @Test
    public void analysisHeadroomShrinksByPromisesAndRecoversOnClose() {
        long before = SourceAudio.analysisAvailable(512 * MB, 38 * MB);
        assertEquals(218 * MB, before);
        SourceAudio.JavaMemory loan = reserve(512 * MB, 38 * MB, 100 * MB);
        assertEquals(before - 100 * MB, SourceAudio.analysisAvailable(512 * MB, 38 * MB));
        loan.close();
        assertEquals(before, SourceAudio.analysisAvailable(512 * MB, 38 * MB));
        assertEquals(13 * MB, SourceAudio.analysisAvailable(192 * MB, 38 * MB));
    }

    @Test
    public void concurrentReservationsHaveOneWinner() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1),
                decided = new CountDownLatch(2),
                finish = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int n = 0; n < 2; n++)
                tasks.add(
                        workers.submit(
                                () -> {
                                    try {
                                        assertTrue(start.await(5, TimeUnit.SECONDS));
                                        try (SourceAudio.JavaMemory loan =
                                                SourceAudio.reserveJava(
                                                        192 * MB, 80 * MB, 64 * MB)) {
                                            if (loan != null) accepted.incrementAndGet();
                                            decided.countDown();
                                            assertTrue(finish.await(5, TimeUnit.SECONDS));
                                        }
                                    } catch (InterruptedException error) {
                                        Thread.currentThread().interrupt();
                                        throw new AssertionError(error);
                                    }
                                }));
            start.countDown();
            assertTrue(decided.await(5, TimeUnit.SECONDS));
            assertEquals(1, accepted.get());
            finish.countDown();
            for (Future<?> task : tasks) task.get(5, TimeUnit.SECONDS);
            assertNotNull(reserve(192 * MB, 80 * MB, 64 * MB));
        } finally {
            finish.countDown();
            workers.shutdownNow();
        }
    }
}
