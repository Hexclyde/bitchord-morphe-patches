package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.media.MediaCodec;
import android.media.MediaDataSource;

import com.music.bitchord.playback.PlaybackService;
import com.music.bitchord.playback.smart.AudioDecoder;

import kotlin.Pair;
import kotlin.Unit;
import kotlin.jvm.functions.Function4;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** The shared decoder bridge, over a stand-in for the library that asks the real input first. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SourceAudioDecoderTest {
    SourceAudio.Backend originalDecoder;
    final AtomicInteger closes = new AtomicInteger();

    static final class Frame {
        final int bytes, channels, rate, offset;
        final long time;

        Frame(ByteBuffer buffer, MediaCodec.BufferInfo info, int channels, int rate) {
            this.bytes = info.size;
            this.offset = info.offset;
            this.time = info.presentationTimeUs;
            this.channels = channels;
            this.rate = rate;
        }
    }

    @Before
    public void setup() {
        originalDecoder = SourceAudio.decoder;
        SyntheticDecoder.reset();
        SyntheticDecoder.install();
        AudioDecoder.seekStall = null;
        AudioDecoder.platform = false;
        AudioDecoder.seeksEntered.set(0);
        PlaybackService.stopped = false;
        closes.set(0);
    }

    @After
    public void restore() {
        CountDownLatch stall = AudioDecoder.seekStall;
        AudioDecoder.seekStall = null;
        if (stall != null) stall.countDown();
        SourceAudio.decoder = originalDecoder;
        SyntheticDecoder.reset();
        PlaybackService.stopped = false;
    }

    MediaDataSource file(int seconds, boolean walks) {
        byte[] bytes = AudioDecoder.file(91, 0, seconds, 0, walks);
        return new MediaDataSource() {
            public int readAt(long at, byte[] into, int offset, int length) {
                if (at >= bytes.length) return -1;
                int count = (int) Math.min(length, bytes.length - at);
                System.arraycopy(bytes, (int) at, into, offset, count);
                return count;
            }

            public long getSize() {
                return bytes.length;
            }

            public void close() {
                closes.incrementAndGet();
            }
        };
    }

    Function4<ByteBuffer, MediaCodec.BufferInfo, Integer, Integer, Unit> into(List<Frame> frames) {
        return (buffer, info, channels, rate) -> {
            frames.add(new Frame(buffer, info, channels, rate));
            return Unit.INSTANCE;
        };
    }

    void assertBalanced() {
        assertEquals(
                "Every opened file is closed once",
                SyntheticDecoder.opened.get(),
                SyntheticDecoder.closed.get());
        assertEquals(
                "No handle is used out of turn or closed twice", 0, SyntheticDecoder.misuse.get());
    }

    @Test
    public void framesAre16BitInTheFilesChannelsWithTheirRealTimes() throws Exception {
        List<Frame> frames = new ArrayList<>();
        Pair<Double, Double> timing =
                SourceAudio.decodeRaw(file(60, true), 10, 14, () -> false, into(frames));
        assertNotNull(timing);
        assertEquals(44100.0, timing.getFirst(), 0);
        assertEquals("The first frame's own time, not the request", 10.0, timing.getSecond(), 0);
        assertEquals(4, frames.size());
        for (int i = 0; i < frames.size(); i++) {
            Frame frame = frames.get(i);
            assertEquals(AudioDecoder.RATE * 2, frame.bytes);
            assertEquals(0, frame.offset);
            assertEquals(1, frame.channels);
            assertEquals(AudioDecoder.RATE, frame.rate);
            assertEquals((10 + i) * 1_000_000L, frame.time);
        }
        assertEquals("The source is closed as the platform extractor's was", 1, closes.get());
        assertBalanced();
    }

    @Test
    public void rangesThatCannotBeDecodedAreRefusedBeforeAnythingOpens() {
        List<Frame> frames = new ArrayList<>();
        double[][] ranges = {{5, 5}, {6, 5}, {-1, 4}, {Double.NaN, 4}, {1, Double.NaN}, {1, 1e12}};
        for (double[] range : ranges)
            assertNull(
                    range[0] + ".." + range[1],
                    SourceAudio.decodeRaw(
                            file(30, false), range[0], range[1], () -> false, into(frames)));
        assertNull(SourceAudio.decodeRaw(null, 0, 4, () -> false, into(frames)));
        assertNull(SourceAudio.decodeRaw(file(30, false), 0, 4, null, into(frames)));
        assertEquals(0, SyntheticDecoder.opened.get());
        assertTrue(frames.isEmpty());
    }

    @Test
    public void framesWhollyBeforeTheStartAreSkippedAndTheStraddlingOneKeepsItsRealTime()
            throws Exception {
        SyntheticDecoder.seekFromStart = true;
        List<Frame> frames = new ArrayList<>();
        Pair<Double, Double> timing =
                SourceAudio.decodeRaw(file(60, false), 2.5, 6, () -> false, into(frames));
        assertNotNull(timing);
        assertEquals(
                "The first frame that reaches the start, as decoded", 2.0, timing.getSecond(), 0);
        assertEquals(4, frames.size());
        for (int i = 0; i < frames.size(); i++)
            assertEquals((2 + i) * 1_000_000L, frames.get(i).time);
        assertBalanced();
    }

    @Test
    public void aRangePastTheEndOrClippedByItIsHonest() throws Exception {
        List<Frame> frames = new ArrayList<>();
        assertNull(SourceAudio.decodeRaw(file(30, false), 40, 50, () -> false, into(frames)));
        assertTrue(frames.isEmpty());
        Pair<Double, Double> timing =
                SourceAudio.decodeRaw(file(30, false), 27, 100, () -> false, into(frames));
        assertNotNull(timing);
        assertEquals("Only what the file has", 3, frames.size());
        assertBalanced();
    }

    @Test
    public void aFrameWithoutATimeOrOutOfOrderOrMalformedIsNotMeasured() throws Exception {
        for (int mode = 0; mode < 7; mode++) {
            SyntheticDecoder.reset();
            if (mode == 0) SyntheticDecoder.silentFrame = 1;
            if (mode == 1) SyntheticDecoder.backwardFrame = 2;
            if (mode == 2) SyntheticDecoder.malformedFrame = 0;
            if (mode == 3) SyntheticDecoder.sentinelFrame = 0;
            if (mode == 4) SyntheticDecoder.repeatFrame = 1;
            if (mode == 5) SyntheticDecoder.hugeFrame = 1;
            if (mode == 6) SyntheticDecoder.lateFrame = 0;
            List<Frame> frames = new ArrayList<>();
            assertNull(
                    "mode " + mode,
                    SourceAudio.decodeRaw(file(30, false), 0, 10, () -> false, into(frames)));
            assertBalanced();
        }
    }

    @Test
    public void nothingIsDeliveredOnceTheWorkIsStopped() throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        List<Frame> frames = new ArrayList<>();
        Function4<ByteBuffer, MediaCodec.BufferInfo, Integer, Integer, Unit> tap =
                (buffer, info, channels, rate) -> {
                    frames.add(new Frame(buffer, info, channels, rate));
                    if (frames.size() == 2) stop.set(true);
                    return Unit.INSTANCE;
                };
        assertNull(SourceAudio.decodeRaw(file(30, false), 0, 20, stop::get, tap));
        assertEquals(2, frames.size());
        assertBalanced();
        assertNull(
                "Stopped before it began",
                SourceAudio.decodeRaw(file(30, false), 0, 20, () -> true, tap));
        assertEquals(2, frames.size());
    }

    @Test
    public void aStalledSeekEndsWhenStoppedAndFreesThePermitAndTheHandle() throws Exception {
        CountDownLatch stall = new CountDownLatch(1);
        AudioDecoder.seekStall = stall;
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Pair<Double, Double>> decoded =
                    executor.submit(
                            () ->
                                    SourceAudio.decodeRaw(
                                            file(60, true),
                                            10,
                                            14,
                                            stop::get,
                                            into(new ArrayList<>())));
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (AudioDecoder.seeksEntered.get() == 0 && System.nanoTime() < limit)
                Thread.sleep(5);
            assertEquals(1, AudioDecoder.seeksEntered.get());
            Future<Boolean> waiter =
                    executor.submit(
                            () -> {
                                try (SourceAudio.Decoding permit =
                                        SourceAudio.decode(
                                                System.nanoTime() + TimeUnit.SECONDS.toNanos(10),
                                                () -> false)) {
                                    return true;
                                }
                            });
            try {
                waiter.get(200, TimeUnit.MILLISECONDS);
                fail("The decoder holds the permit while it works");
            } catch (TimeoutException expected) {
            }
            stop.set(true);
            assertNull(decoded.get(3, TimeUnit.SECONDS));
            assertTrue(waiter.get(3, TimeUnit.SECONDS));
            assertBalanced();
        } finally {
            stall.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void anOuterLeaseStopsTheDecodeAndKeepsTheSamePermit() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        List<Frame> frames = new ArrayList<>();
        Function4<ByteBuffer, MediaCodec.BufferInfo, Integer, Integer, Unit> tap =
                (buffer, info, channels, rate) -> {
                    frames.add(new Frame(buffer, info, channels, rate));
                    cancelled.set(true);
                    return Unit.INSTANCE;
                };
        ExecutorService other = Executors.newSingleThreadExecutor();
        try (SourceAudio.Decoding outer =
                SourceAudio.decode(
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(30), cancelled::get)) {
            assertNull(SourceAudio.decodeRaw(file(30, false), 0, 10, () -> false, tap));
            assertEquals("The job's cancellation applies to the decode", 1, frames.size());
            Future<Boolean> admitted =
                    other.submit(
                            () -> {
                                try (SourceAudio.Decoding permit =
                                        SourceAudio.decode(
                                                System.nanoTime() + 300_000_000L, () -> false)) {
                                    return true;
                                } catch (SourceAudio.Stopped waited) {
                                    return false;
                                }
                            });
            assertFalse(
                    "Decoding did not give up the outer permit", admitted.get(3, TimeUnit.SECONDS));
        } finally {
            other.shutdownNow();
        }
        assertBalanced();
        try (SourceAudio.Decoding again =
                SourceAudio.decode(System.nanoTime() + TimeUnit.SECONDS.toNanos(5), () -> false)) {
            assertNotNull(again);
        }
    }

    @Test
    public void aDecodeWithoutAJobNeverConsultsTheOptionalHostPort() throws Exception {
        PlaybackService.stopped = true;
        List<Frame> frames = new ArrayList<>();
        assertNotNull(SourceAudio.decodeRaw(file(30, false), 0, 3, () -> false, into(frames)));
        assertEquals(3, frames.size());
        assertNotNull(SourceAudio.containerDurationSeconds(file(30, false)));
    }

    @Test
    public void aMissingLibraryFailsClosedWithoutFallingBackToThePlatform() throws Exception {
        SourceAudio.decoder =
                new SyntheticDecoder() {
                    @Override
                    public long open(Object input) throws IOException {
                        throw new IOException("The audio decoder is not available");
                    }
                };
        List<Frame> frames = new ArrayList<>();
        assertNull(SourceAudio.decodeRaw(file(30, false), 0, 3, () -> false, into(frames)));
        assertNull(SourceAudio.containerDurationSeconds(file(30, false)));
        assertTrue(frames.isEmpty());
        try (MediaDataSource source = file(30, false)) {
            SourceAudio.analysisInfo(source, 30);
            fail("A file the library cannot open has no format");
        } catch (IOException expected) {
        }
        SourceAudio.decoder =
                new SyntheticDecoder() {
                    @Override
                    public long open(Object input) {
                        throw new UnsatisfiedLinkError("nativeOpen");
                    }
                };
        assertNull(SourceAudio.decodeRaw(file(30, false), 0, 3, () -> false, into(frames)));
        assertNull(SourceAudio.containerDurationSeconds(file(30, false)));
        assertEquals(0, SyntheticDecoder.opened.get());
    }

    @Test
    public void aLengthGuessedFromTheBitrateIsNotALength() throws Exception {
        assertEquals(30.0, SourceAudio.containerDurationSeconds(file(30, false)), 0);
        SyntheticDecoder.durationMethod = 2;
        assertNull(SourceAudio.containerDurationSeconds(file(30, false)));
        try (MediaDataSource source = file(30, false)) {
            SourceAudio.AnalysisInfo info = SourceAudio.analysisInfo(source, 50);
            assertEquals("Only the requested length is trusted", 50.0, info.seconds, 0);
            assertEquals(AudioDecoder.RATE, info.sampleRate);
        }
        SyntheticDecoder.durationMethod = 0;
        try (MediaDataSource source = file(30, false)) {
            assertEquals(30.0, SourceAudio.analysisInfo(source, 10).seconds, 0);
        }
        assertBalanced();
    }

    @Test
    public void onlyDurationsReadFromTimestampsOrTheStreamAreExact() throws Exception {
        for (long method : new long[] {SourceAudio.Info.FROM_PTS, SourceAudio.Info.FROM_STREAM}) {
            SyntheticDecoder.durationMethod = method;
            assertEquals(30.0, SourceAudio.containerDurationSeconds(file(30, false)), 0);
        }
        for (long method : new long[] {2, 3, 7, -1}) {
            SyntheticDecoder.durationMethod = method;
            assertNull("method " + method, SourceAudio.containerDurationSeconds(file(30, false)));
        }
    }

    @Test
    public void onlyTheSelectedAudiosOwnLengthIsExactAndAnAmbiguousOneIsUnknown() throws Exception {
        SyntheticDecoder.durationOverride = 10_000_000L;
        assertEquals(10.0, SourceAudio.containerDurationSeconds(file(30, false)), 0);
        for (long unknown : new long[] {-1, 0}) {
            SyntheticDecoder.durationOverride = unknown;
            assertNull("length " + unknown, SourceAudio.containerDurationSeconds(file(30, false)));
            try (MediaDataSource source = file(30, false)) {
                assertEquals(
                        "Only the requested length is trusted",
                        20.0,
                        SourceAudio.analysisInfo(source, 20).seconds,
                        0);
            }
        }
        SyntheticDecoder.durationOverride = 10_000_000L;
        SyntheticDecoder.durationMethod = 2;
        assertNull(
                "A bitrate guess stays unknown even when copied into the stream",
                SourceAudio.containerDurationSeconds(file(30, false)));
        assertBalanced();
    }

    @Test
    public void aFailureAfterTakingThePermitReleasesItAndLeavesTheOuterLease() throws Exception {
        Throwable[] failures = {
            new IllegalStateException("callback"),
            new UnsatisfiedLinkError("callback"),
            new AssertionError("callback")
        };
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            for (Throwable failure : failures) {
                AtomicInteger checks = new AtomicInteger();
                java.util.function.BooleanSupplier fails =
                        () -> {
                            // Passes while waiting, then fails once the permit is held.
                            if (checks.incrementAndGet() == 1) return false;
                            if (failure instanceof Error) throw (Error) failure;
                            throw (RuntimeException) failure;
                        };
                AtomicBoolean cancelled = new AtomicBoolean();
                try (SourceAudio.Decoding outer =
                        SourceAudio.decode(
                                System.nanoTime() + TimeUnit.SECONDS.toNanos(30), cancelled::get)) {
                    try {
                        SourceAudio.decode(System.nanoTime() + TimeUnit.SECONDS.toNanos(30), fails);
                        fail("The failure is reported as it was");
                    } catch (RuntimeException | Error thrown) {
                        assertSame(failure, thrown);
                    }
                    assertEquals(2, checks.get());
                    cancelled.set(true);
                    try {
                        SourceAudio.checkLeased();
                        fail("The outer lease is still the one in force");
                    } catch (SourceAudio.Stopped expected) {
                        assertEquals(SourceAudio.StopReason.CANCELLED, expected.reason);
                    }
                }
                SourceAudio.checkLeased();
                assertTrue(
                        "The permit is free again",
                        other.submit(
                                        () -> {
                                            try (SourceAudio.Decoding permit =
                                                    SourceAudio.decode(
                                                            System.nanoTime()
                                                                    + TimeUnit.SECONDS.toNanos(3),
                                                            () -> false)) {
                                                return true;
                                            }
                                        })
                                .get(5, TimeUnit.SECONDS));
            }
        } finally {
            other.shutdownNow();
        }
    }

    @Test
    public void aDecodeWhoseAbortFailsAfterTheLockStillFreesThePermit() throws Exception {
        for (boolean error : new boolean[] {false, true}) {
            AtomicInteger calls = new AtomicInteger();
            assertNull(
                    SourceAudio.decodeRaw(
                            file(30, false),
                            0,
                            3,
                            () -> {
                                if (calls.incrementAndGet() == 1) return false;
                                if (error) throw new UnsatisfiedLinkError("abort");
                                throw new IllegalStateException("abort");
                            },
                            into(new ArrayList<>())));
            assertEquals(0, SyntheticDecoder.opened.get());
            ExecutorService other = Executors.newSingleThreadExecutor();
            try {
                assertTrue(
                        other.submit(
                                        () -> {
                                            try (SourceAudio.Decoding permit =
                                                    SourceAudio.decode(
                                                            System.nanoTime()
                                                                    + TimeUnit.SECONDS.toNanos(3),
                                                            () -> false)) {
                                                return true;
                                            }
                                        })
                                .get(5, TimeUnit.SECONDS));
            } finally {
                other.shutdownNow();
            }
        }
    }

    @Test
    public void framesOffThePlaybackClockAreNeverDecodedOrTimed() throws Exception {
        SyntheticDecoder.unqualified = true;
        List<Frame> frames = new ArrayList<>();
        assertNull(SourceAudio.decodeRaw(file(30, false), 0, 5, () -> false, into(frames)));
        assertTrue("No audio is delivered", frames.isEmpty());
        assertEquals(1, SyntheticDecoder.opened.get());
        assertBalanced();
        assertNull(SourceAudio.containerDurationSeconds(file(30, false)));
        try (MediaDataSource source = file(30, false)) {
            SourceAudio.AnalysisInfo info = SourceAudio.analysisInfo(source, 12);
            assertEquals("The rate is read, the length is not promoted", 12.0, info.seconds, 0);
            assertEquals(AudioDecoder.RATE, info.sampleRate);
        }
        assertBalanced();
        try (SourceAudio.Decoding again =
                SourceAudio.decode(System.nanoTime() + TimeUnit.SECONDS.toNanos(2), () -> false)) {
            assertNotNull(again);
        }
        SyntheticDecoder.unqualified = false;
        assertNotNull(SourceAudio.decodeRaw(file(30, false), 0, 3, () -> false, into(frames)));
        assertEquals(3, frames.size());
        assertEquals(30.0, SourceAudio.containerDurationSeconds(file(30, false)), 0);
    }

    @Test
    public void aFormatReportWithoutTheClockFlagFailsClosed() throws Exception {
        SyntheticDecoder.shortInfo = true;
        List<Frame> frames = new ArrayList<>();
        assertNull(SourceAudio.decodeRaw(file(30, false), 0, 5, () -> false, into(frames)));
        assertNull(SourceAudio.containerDurationSeconds(file(30, false)));
        assertTrue(frames.isEmpty());
        assertBalanced();
    }

    @Test
    public void aFileTheLibraryCannotReadHasNoAnswer() throws Exception {
        MediaDataSource unknown =
                new MediaDataSource() {
                    public int readAt(long at, byte[] into, int offset, int length) {
                        return at >= 10 ? -1 : Math.min(length, 10 - (int) at);
                    }

                    public long getSize() {
                        return 10;
                    }

                    public void close() {}
                };
        assertNull(SourceAudio.decodeRaw(unknown, 0, 5, () -> false, into(new ArrayList<>())));
        assertNull(SourceAudio.containerDurationSeconds(unknown));
        assertEquals(0, SyntheticDecoder.opened.get());
    }

    @Test
    public void everyReadAndSeekChecksForAStopBeforeTouchingTheSource() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        MediaDataSource counted =
                new MediaDataSource() {
                    public int readAt(long at, byte[] into, int offset, int length) {
                        reads.incrementAndGet();
                        return -1;
                    }

                    public long getSize() {
                        reads.incrementAndGet();
                        return 100;
                    }

                    public void close() {}
                };
        SourceAudio.Input stopped = new SourceAudio.Input(counted, () -> true);
        for (int call = 0; call < 3; call++)
            try {
                if (call == 0) stopped.read(new byte[8], 0, 8);
                else stopped.seek(0, call == 1 ? 0 : 0x10000);
                fail("A stopped input does no work");
            } catch (SourceAudio.Stopped expected) {
            }
        assertTrue(stopped.stopped());
        assertEquals(0, reads.get());
        Thread.currentThread().interrupt();
        try {
            SourceAudio.Input input = new SourceAudio.Input(counted, () -> false);
            assertTrue("An interrupt stops it too", input.stopped());
            try {
                input.read(new byte[8], 0, 8);
                fail();
            } catch (SourceAudio.Stopped expected) {
                assertEquals(SourceAudio.StopReason.INTERRUPTED, expected.reason);
            }
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, reads.get());
        MediaDataSource generous =
                new MediaDataSource() {
                    public int readAt(long at, byte[] into, int offset, int length) {
                        return length + 1;
                    }

                    public long getSize() {
                        return 100;
                    }

                    public void close() {}
                };
        try {
            new SourceAudio.Input(generous, () -> false).read(new byte[8], 0, 8);
            fail("A source cannot return more than was asked");
        } catch (IOException expected) {
        }
    }

    @Test
    public void anIdleInputStopsButOneThatKeepsMovingDoesNot() throws Exception {
        SourceAudio.Input input = new SourceAudio.Input(file(1, false), () -> false, 150_000_000L);
        for (int i = 0; i < 6; i++) {
            Thread.sleep(60);
            assertEquals(0, input.seek(0, 0));
        }
        assertFalse("Progress renews the limit", input.stopped());
        Thread.sleep(250);
        assertTrue(input.stopped());
        try {
            input.read(new byte[8], 0, 8);
            fail("Nothing completed for longer than the limit");
        } catch (SourceAudio.Stopped idle) {
            assertEquals(SourceAudio.StopReason.DEADLINE, idle.reason);
        }
    }

    @Test
    public void aSlowStreamThatKeepsDeliveringIsNotCutOff() throws Exception {
        long idle = SourceAudio.idleNanos;
        SourceAudio.idleNanos = 200_000_000L;
        try {
            MediaDataSource slow = file(30, false);
            MediaDataSource trickle =
                    new MediaDataSource() {
                        public int readAt(long at, byte[] into, int offset, int length)
                                throws IOException {
                            try {
                                Thread.sleep(40);
                            } catch (InterruptedException interrupted) {
                                throw new IOException(interrupted);
                            }
                            return slow.readAt(at, into, offset, length);
                        }

                        public long getSize() throws IOException {
                            return slow.getSize();
                        }

                        public void close() throws IOException {
                            slow.close();
                        }
                    };
            List<Frame> frames = new ArrayList<>();
            assertNotNull(SourceAudio.decodeRaw(trickle, 0, 3, () -> false, into(frames)));
            assertEquals(3, frames.size());
            assertBalanced();
        } finally {
            SourceAudio.idleNanos = idle;
        }
    }

    @Test
    public void aStuckSeekWithoutAJobEndsAtTheIdleLimitAndFreesEverything() throws Exception {
        long idle = SourceAudio.idleNanos;
        SourceAudio.idleNanos = 300_000_000L;
        CountDownLatch stall = new CountDownLatch(1);
        AudioDecoder.seekStall = stall;
        try {
            long began = System.nanoTime();
            assertNull(
                    SourceAudio.decodeRaw(
                            file(60, true), 10, 14, () -> false, into(new ArrayList<>())));
            assertTrue(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(5));
            assertEquals(1, AudioDecoder.seeksEntered.get());
            assertBalanced();
            try (SourceAudio.Decoding again =
                    SourceAudio.decode(
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(2), () -> false)) {
                assertNotNull(again);
            }
        } finally {
            stall.countDown();
            SourceAudio.idleNanos = idle;
        }
    }

    @Test
    public void aProbeWithoutAJobIsLimitedAndFreesThePermitAndTheHandle() throws Exception {
        long probe = SourceAudio.probeNanos;
        SourceAudio.probeNanos = 300_000_000L;
        CountDownLatch stall = new CountDownLatch(1);
        SyntheticDecoder.infoStall = stall;
        try {
            long began = System.nanoTime();
            assertNull(SourceAudio.containerDurationSeconds(file(30, false)));
            assertTrue(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(5));
            assertBalanced();
            assertEquals(1, SyntheticDecoder.opened.get());
            try (SourceAudio.Decoding again =
                    SourceAudio.decode(
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(2), () -> false)) {
                assertNotNull(again);
            }
        } finally {
            stall.countDown();
            SourceAudio.probeNanos = probe;
        }
    }

    @Test
    public void anInterruptEndsAProbeAndADecodeWithoutAJob() throws Exception {
        CountDownLatch stall = new CountDownLatch(1);
        SyntheticDecoder.infoStall = stall;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Double> probing =
                    executor.submit(() -> SourceAudio.containerDurationSeconds(file(30, false)));
            Thread.sleep(200);
            probing.cancel(true);
            executor.shutdown();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            assertBalanced();
        } finally {
            stall.countDown();
            executor.shutdownNow();
        }
        Thread.currentThread().interrupt();
        try {
            assertNull(
                    SourceAudio.decodeRaw(
                            file(30, false), 0, 3, () -> false, into(new ArrayList<>())));
            assertNull(SourceAudio.containerDurationSeconds(file(30, false)));
        } finally {
            Thread.interrupted();
        }
        try (SourceAudio.Decoding again =
                SourceAudio.decode(System.nanoTime() + TimeUnit.SECONDS.toNanos(2), () -> false)) {
            assertNotNull(again);
        }
        assertBalanced();
    }

    @Test
    public void aHandleBelongsToTheThreadThatOpenedIt() throws Exception {
        SourceAudio.Backend backend = SourceAudio.decoder;
        MediaDataSource source = file(30, false);
        long handle = backend.open(new SourceAudio.Input(source, () -> false));
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            Future<?> misused = other.submit(() -> backend.info(handle));
            try {
                misused.get(3, TimeUnit.SECONDS);
                fail("Another thread may not use the handle");
            } catch (ExecutionException refused) {
                assertTrue(refused.getCause() instanceof IllegalStateException);
            }
        } finally {
            other.shutdownNow();
            backend.close(handle);
        }
        assertEquals(1, SyntheticDecoder.misuse.getAndSet(0));
        assertBalanced();
    }

    @Test
    public void theInputSeeksReadsAndReportsLikeTheLibraryExpects() throws Exception {
        SourceAudio.Input input = new SourceAudio.Input(file(1, false), () -> false);
        long size = input.seek(0, 0x10000);
        assertEquals(AudioDecoder.HEADER + AudioDecoder.BYTES_PER_SECOND, size);
        assertEquals(10, input.seek(10, 0));
        byte[] bytes = new byte[4];
        assertEquals(4, input.read(bytes, 0, 4));
        assertEquals(14, input.seek(0, 1));
        assertEquals(size - 8, input.seek(-8, 2 | 0x20000));
        assertEquals(8, input.read(new byte[16], 0, 16));
        assertEquals(-1, input.read(new byte[16], 0, 16));
        try {
            input.seek(-1, 0);
            fail();
        } catch (IOException expected) {
        }
        assertFalse(input.stopped());
        assertTrue(
                new SourceAudio.Input(
                                file(1, false),
                                () -> {
                                    throw new IllegalStateException();
                                })
                        .stopped());
    }
}
