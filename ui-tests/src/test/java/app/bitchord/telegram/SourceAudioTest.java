package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.net.Uri;

import androidx.media3.datasource.*;

import com.music.bitchord.playback.PlaybackService;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SourceAudioTest {
    List<Memory> opened;
    byte[] audio = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    String changed;
    long transferred;
    int chunk = Integer.MAX_VALUE;
    boolean unknownLength;

    class Memory implements DataSource {
        int position;
        boolean closed;
        Uri uri;

        public void addTransferListener(TransferListener ignored) {}

        public long open(DataSpec spec) {
            position = (int) spec.position;
            uri = changed == null ? spec.uri : Uri.parse(changed);
            return unknownLength ? androidx.media3.common.C.LENGTH_UNSET : audio.length - position;
        }

        public int read(byte[] b, int o, int n) {
            if (position == audio.length) return -1;
            int count = Math.min(Math.min(n, chunk), audio.length - position);
            System.arraycopy(audio, position, b, o, count);
            position += count;
            transferred += count;
            return count;
        }

        public Uri getUri() {
            return uri;
        }

        public Map<String, List<String>> getResponseHeaders() {
            return Collections.emptyMap();
        }

        public void close() {
            closed = true;
        }
    }

    @Before
    public void setup() {
        opened = new ArrayList<>();
        PlaybackService.source =
                () -> {
                    Memory next = new Memory();
                    opened.add(next);
                    return next;
                };
    }

    SourceAudio open(String uri) throws Exception {
        return new SourceAudio(new DataSpec(Uri.parse(uri)), System.nanoTime() + 120_000_000_000L);
    }

    @Test
    public void nativeAndNetworkSchemesUseIdenticalRandomAccess() throws Exception {
        for (String scheme :
                new String[] {
                    "bctg://audio/1",
                    "smb://share/a.flac",
                    "content://music/42",
                    "https://host/a.flac"
                })
            try (SourceAudio input = open(scheme)) {
                byte[] b = new byte[3];
                assertEquals(10, input.getSize());
                assertEquals(3, input.readAt(5, b, 0, 3));
                assertArrayEquals(new byte[] {5, 6, 7}, b);
                assertEquals(3, input.readAt(0, b, 0, 3));
                assertArrayEquals(new byte[] {0, 1, 2}, b);
                assertEquals(-1, input.readAt(10, b, 0, 3));
            }
        assertTrue(opened.stream().allMatch(s -> s.closed));
    }

    @Test
    public void independentReadersNeverShareSeekPositions() throws Exception {
        try (SourceAudio a = open("bctg://audio/1");
                SourceAudio b = open("bctg://audio/1")) {
            byte[] out = new byte[1];
            a.readAt(2, out, 0, 1);
            assertEquals(2, out[0]);
            b.readAt(7, out, 0, 1);
            assertEquals(7, out[0]);
            a.readAt(3, out, 0, 1);
            assertEquals(3, out[0]);
        }
    }

    @Test
    public void changedRenditionIsRefusedBeforeMixingBytes() throws Exception {
        file(4 * 1024 * 1024);
        try (SourceAudio input = open("https://host/a.flac")) {
            changed = "https://host/different.mp3";
            try {
                input.readAt(3 * 1024 * 1024, new byte[1], 0, 1);
                fail();
            } catch (IOException expected) {
            }
        }
    }

    @Test
    public void closedAndExpiredAnalysisCannotKeepFetching() throws Exception {
        SourceAudio input = open("https://host/a");
        input.close();
        try {
            input.readAt(0, new byte[1], 0, 1);
            fail();
        } catch (IOException expected) {
        }
        int count = opened.size();
        try {
            new SourceAudio(new DataSpec(Uri.parse("https://host/a")), 0);
            fail();
        } catch (IOException expected) {
        }
        assertEquals(count, opened.size());
    }

    static byte at(long position) {
        return (byte) (position * 31 ^ position >>> 8);
    }

    void file(int bytes) {
        audio = new byte[bytes];
        for (int i = 0; i < bytes; i++) audio[i] = at(i);
    }

    static void assertRead(SourceAudio input, long position, int length) throws Exception {
        byte[] out = new byte[length];
        int got = 0;
        while (got < length) {
            int n = input.readAt(position + got, out, got, length - got);
            assertTrue("A read that isn't at the end returns bytes", n > 0);
            got += n;
        }
        for (int i = 0; i < length; i++) assertEquals(at(position + i), out[i]);
    }

    /** What libwebm does through the 2 KB native cache when a seek can't use cues. */
    @Test
    public void matroskaWalkReadsThroughInsteadOfOneRequestPerRead() throws Exception {
        file(4 * 1024 * 1024);
        try (SourceAudio input = open("https://host/a.webm")) {
            for (long position = 64; position < 2 * 1024 * 1024; position += 2100)
                assertRead(input, position, 2048);
        }
        assertTrue("Requests for a 2 MB walk: " + opened.size(), opened.size() <= 2);
        assertTrue("Only what the walk needed: " + transferred, transferred <= 2200 * 1024);
    }

    @Test
    public void overlappingAndBacktrackingReadsStayLocal() throws Exception {
        file(1024 * 1024);
        try (SourceAudio input = open("https://host/a.flac")) {
            assertRead(input, 300_000, 4096);
            int before = opened.size();
            long moved = transferred;
            for (int i = 0; i < 50; i++) {
                assertRead(input, 300_000 + i * 16, 2048);
                assertRead(input, 300_000 + 1024, 512);
            }
            assertEquals("No request for bytes it just read", before, opened.size());
            assertEquals(moved, transferred);
        }
    }

    @Test
    public void aDistantSeekOpensThereWithoutReadingTheFileBetween() throws Exception {
        file(32 * 1024 * 1024);
        try (SourceAudio input = open("https://host/hires.flac")) {
            assertRead(input, 0, 2048);
            assertRead(input, 30 * 1024 * 1024, 16 * 1024);
        }
        assertEquals(2, opened.size());
        assertTrue("A 16 KB read of a 32 MB file: " + transferred, transferred < 512 * 1024);
    }

    @Test
    public void endOfFileRangesAndShortUpstreamReadsStayExact() throws Exception {
        file(200_001);
        chunk = 7;
        try (SourceAudio input = open("https://host/a.flac")) {
            assertEquals(200_001, input.getSize());
            assertRead(input, 199_990, 11);
            assertRead(input, 65_530, 20);
            byte[] out = new byte[64];
            assertEquals(1, input.readAt(200_000, out, 0, 64));
            assertEquals(at(200_000), out[0]);
            assertEquals(-1, input.readAt(200_001, out, 0, 64));
            assertEquals(0, input.readAt(5, out, 0, 0));
            assertEquals(-1, input.readAt(-1, out, 0, 64));
        }
    }

    @Test
    public void anUnknownLengthIsLearnedAtTheEndOfTheFile() throws Exception {
        file(70_000);
        unknownLength = true;
        try (SourceAudio input = open("https://host/a.flac")) {
            assertEquals(-1, input.getSize());
            assertRead(input, 60_000, 10_000);
            byte[] out = new byte[16];
            assertEquals(-1, input.readAt(70_000, out, 0, 16));
        }
    }

    @Test
    public void readsHoldABoundedAmountHoweverLongTheFile() throws Exception {
        file(24 * 1024 * 1024);
        try (SourceAudio input = open("https://host/hires.flac")) {
            for (long position = 0; position < audio.length; position += 2100)
                assertRead(input, position, Math.min(2048, (int) (audio.length - position)));
            assertTrue(
                    "Held blocks: " + input.heldBlocks(),
                    input.heldBlocks() * (long) SourceAudio.BLOCK <= 8 * 1024 * 1024);
            assertRead(input, 0, 2048);
        }
    }

    @Test
    public void nearlyFullHeapKeepsOnlyAReadThroughWindow() throws Exception {
        file(24 * 1024 * 1024);
        java.util.function.LongSupplier original = SourceAudio.heapFree;
        SourceAudio.heapFree = () -> 8L << 20;
        try (SourceAudio input = open("https://host/hires.flac")) {
            for (long position = 0; position < audio.length; position += 2100)
                assertRead(input, position, Math.min(2048, (int) (audio.length - position)));
            assertTrue(
                    "Held blocks: " + input.heldBlocks(),
                    input.heldBlocks() <= SourceAudio.PRESSURE_BLOCKS);
            assertRead(input, 0, 2048);
        } finally {
            SourceAudio.heapFree = original;
        }
    }

    @Test
    public void eachExtractorMayCloseItsViewWithoutClosingTheFile() throws Exception {
        file(4 * 1024 * 1024);
        try (SourceAudio input = open("https://host/a.webm")) {
            byte[] out = new byte[2048];
            android.media.MediaDataSource first = input.view();
            assertEquals(2048, first.readAt(70_000, out, 0, 2048));
            long moved = transferred;
            int requests = opened.size();
            first.close();
            try {
                first.readAt(70_000, out, 0, 2048);
                fail("A released extractor has nothing more to read");
            } catch (IOException expected) {
            }
            android.media.MediaDataSource next = input.view();
            assertEquals(4 * 1024 * 1024, next.getSize());
            assertEquals(2048, next.readAt(70_000, out, 0, 2048));
            assertEquals(at(70_000), out[0]);
            assertEquals("The next extractor reads what the last one read", moved, transferred);
            assertEquals(requests, opened.size());
        }
        assertTrue(opened.stream().allMatch(s -> s.closed));
    }

    @Test
    public void idlingLetsGoOfTheConnectionAndKeepsWhatWasRead() throws Exception {
        file(4 * 1024 * 1024);
        try (SourceAudio input = open("https://host/a.flac")) {
            assertRead(input, 0, 2048);
            input.idle();
            assertTrue(opened.get(0).closed);
            assertEquals(1, opened.size());
            assertRead(input, 100, 2048);
            assertEquals("Held bytes need no connection", 1, opened.size());
            assertRead(input, 200_000, 2048);
            assertEquals(2, opened.size());
        }
    }

    @Test
    public void aFailedBlockIsNotKeptAndTheNextReadIsExact() throws Exception {
        file(1024 * 1024);
        List<Memory> sources = opened;
        PlaybackService.source =
                () ->
                        new Memory() {
                            {
                                sources.add(this);
                            }

                            @Override
                            public int read(byte[] b, int o, int n) {
                                if (sources.size() == 1 && position >= 1000)
                                    throw new IllegalStateException("Connection lost");
                                return super.read(b, o, n);
                            }
                        };
        chunk = 500;
        try (SourceAudio input = open("https://host/a.flac")) {
            byte[] out = new byte[2048];
            try {
                input.readAt(0, out, 0, 2048);
                fail();
            } catch (IllegalStateException expected) {
            }
            assertTrue(opened.get(0).closed);
            assertRead(input, 0, 2048);
            assertEquals(2, opened.size());
        }
    }

    @Test
    public void aSwallowedReadFailureIsReportedOnce() throws Exception {
        file(1024 * 1024);
        boolean[] cancelled = {false};
        try (SourceAudio input =
                new SourceAudio(
                        new DataSpec(Uri.parse("https://host/a.flac")),
                        System.nanoTime() + 120_000_000_000L,
                        () -> cancelled[0])) {
            input.rethrowFailure();
            cancelled[0] = true;
            try {
                input.readAt(0, new byte[16], 0, 16);
                fail();
            } catch (SourceAudio.Stopped expected) {
            }
            try {
                input.rethrowFailure();
                fail("The decoder only saw the file end; the verifier must see why");
            } catch (SourceAudio.Stopped expected) {
            }
            input.rethrowFailure();
        }
    }

    @Test
    public void cancellationAndTheDeadlineStopAWalkPartWay() throws Exception {
        file(4 * 1024 * 1024);
        boolean[] cancelled = {false};
        try (SourceAudio input =
                new SourceAudio(
                        new DataSpec(Uri.parse("https://host/a.webm")),
                        System.nanoTime() + 120_000_000_000L,
                        () -> cancelled[0])) {
            assertRead(input, 0, 2048);
            long moved = transferred;
            cancelled[0] = true;
            try {
                input.readAt(2_000_000, new byte[2048], 0, 2048);
                fail();
            } catch (SourceAudio.Stopped expected) {
            }
            assertEquals("Nothing more is fetched", moved, transferred);
        }
        try (SourceAudio input =
                new SourceAudio(
                        new DataSpec(Uri.parse("https://host/a.webm")),
                        System.nanoTime() + 200_000_000L)) {
            assertRead(input, 0, 2048);
            int requests = opened.size();
            Thread.sleep(250);
            try {
                input.readAt(0, new byte[16], 0, 16);
                fail("Held bytes don't outlive the deadline either");
            } catch (SourceAudio.Stopped expected) {
            }
            assertEquals(requests, opened.size());
        }
        Thread.currentThread().interrupt();
        try {
            open("https://host/a.webm");
            fail();
        } catch (SourceAudio.Stopped expected) {
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void aSilentUpstreamCannotReadAsTheEndOfTheFile() throws Exception {
        file(1024 * 1024);
        PlaybackService.source =
                () ->
                        new Memory() {
                            @Override
                            public int read(byte[] b, int o, int n) {
                                return 0;
                            }
                        };
        try (SourceAudio input = open("https://host/a.flac")) {
            try {
                input.readAt(0, new byte[16], 0, 16);
                fail();
            } catch (IOException expected) {
                assertFalse(expected instanceof SourceAudio.Stopped);
            }
        }
    }

    private static String stopReason(IOException failure) throws Exception {
        try {
            return String.valueOf(failure.getClass().getDeclaredField("reason").get(failure));
        } catch (NoSuchFieldException missing) {
            return "";
        }
    }

    @Test
    public void stopReasonsPreserveCancellationShortCircuiting() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        SourceAudio closed =
                new SourceAudio(
                        new DataSpec(Uri.parse("https://host/a")),
                        System.nanoTime() + 120_000_000_000L,
                        () -> {
                            calls.incrementAndGet();
                            return false;
                        });
        closed.close();
        calls.set(0);
        try {
            closed.readAt(0, new byte[1], 0, 1);
            fail();
        } catch (IOException stopped) {
            assertEquals("CLOSED", stopReason(stopped));
        }
        assertEquals(0, calls.get());
        try {
            new SourceAudio(
                    new DataSpec(Uri.parse("https://host/a")),
                    0,
                    () -> {
                        calls.incrementAndGet();
                        return false;
                    });
            fail();
        } catch (IOException stopped) {
            assertEquals("DEADLINE", stopReason(stopped));
        }
        assertEquals(1, calls.get());
        calls.set(0);
        try {
            new SourceAudio(
                    new DataSpec(Uri.parse("https://host/a")),
                    Long.MAX_VALUE,
                    () -> {
                        calls.incrementAndGet();
                        return true;
                    });
            fail();
        } catch (IOException stopped) {
            assertEquals("CANCELLED", stopReason(stopped));
        }
        assertEquals(1, calls.get());
    }

    @Test
    public void interruptionAndHostStopDoNotEvaluateCancellation() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        try {
            Thread.currentThread().interrupt();
            try {
                new SourceAudio(
                        new DataSpec(Uri.parse("https://host/a")),
                        Long.MAX_VALUE,
                        () -> {
                            calls.incrementAndGet();
                            return false;
                        });
                fail();
            } catch (IOException stopped) {
                assertEquals("INTERRUPTED", stopReason(stopped));
            }
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, calls.get());
        boolean previous = PlaybackService.stopped;
        PlaybackService.stopped = true;
        try {
            new SourceAudio(
                    new DataSpec(Uri.parse("https://host/a")),
                    Long.MAX_VALUE,
                    () -> {
                        calls.incrementAndGet();
                        return false;
                    });
            fail();
        } catch (IOException stopped) {
            assertEquals("PLAYBACK_STOPPED", stopReason(stopped));
        } finally {
            PlaybackService.stopped = previous;
        }
        assertEquals(0, calls.get());
    }

    @Test
    public void readerReleaseAndByteBudgetHaveSeparateReasons() throws Exception {
        try (SourceAudio input = open("https://host/a")) {
            android.media.MediaDataSource view = input.view();
            view.close();
            try {
                view.readAt(0, new byte[1], 0, 1);
                fail();
            } catch (IOException stopped) {
                assertEquals("READER_RELEASED", stopReason(stopped));
            }
            java.lang.reflect.Field spent = SourceAudio.class.getDeclaredField("spent");
            spent.setAccessible(true);
            spent.setLong(input, 256L * 1024 * 1024 + 1);
            try {
                input.readAt(0, new byte[1], 0, 1);
                fail();
            } catch (IOException stopped) {
                assertEquals("BYTE_LIMIT", stopReason(stopped));
            }
        }
    }
}
