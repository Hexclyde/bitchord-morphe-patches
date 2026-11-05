package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.media.MediaCodec;
import android.media.MediaDataSource;

import com.music.bitchord.playback.PlaybackService;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class DspAnalysisTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @After
    public void clearPorts() {
        PlaybackService.dspDecoder = null;
        PlaybackService.dspFeatures = null;
    }

    static float[] tone(int rate, double seconds, double hz) {
        float[] values = new float[(int) (rate * seconds)];
        for (int i = 0; i < values.length; i++)
            values[i] = (float) (.5 * Math.sin(2 * Math.PI * hz * i / rate));
        return values;
    }

    static float[] convert(float[] input, int rate, int... chunks) throws IOException {
        List<Float> output = new ArrayList<>();
        DspAnalysis.Resampler stream =
                new DspAnalysis.Resampler(
                        rate,
                        (int) (input.length * (long) DspAnalysis.RATE / rate + 10),
                        output::add);
        for (int at = 0, n = 0; at < input.length; n++) {
            int end = Math.min(input.length, at + chunks[n % chunks.length]);
            stream.accept(java.util.Arrays.copyOfRange(input, at, end));
            at = end;
        }
        stream.finish();
        float[] result = new float[output.size()];
        for (int i = 0; i < result.length; i++) result[i] = output.get(i);
        return result;
    }

    static double rms(float[] values) {
        double square = 0;
        for (int i = 1024; i < values.length - 1024; i++) square += values[i] * values[i];
        return Math.sqrt(square / (values.length - 2048));
    }

    @Test
    public void resamplingKeepsOneTimelineAcrossEveryCodecChunk() throws Exception {
        float[] input = tone(48000, 3.25, 997);
        for (int i = 0; i < input.length; i++)
            input[i] += (float) (.1 * Math.sin(2 * Math.PI * 13000 * i / 48000));
        float[] contiguous = convert(input, 48000, input.length);
        float[] fragmented = convert(input, 48000, 1, 257, 480, 4096, 65536, 7);
        assertEquals(35831, fragmented.length);
        assertArrayEquals(
                "Codec packet boundaries cannot reset phase or filtering",
                contiguous,
                fragmented,
                0);
    }

    @Test
    public void lowPassRejectsAnAliasedToneWhileKeepingAudibleTimingAndLevel() throws Exception {
        for (int rate : new int[] {44100, 48000, 96000, 192000}) {
            float[] pass = convert(tone(rate, 1, 1000), rate, 397, 1024, 7);
            float[] stop = convert(tone(rate, 1, 10000), rate, 257, 16384);
            assertEquals(11025, pass.length);
            assertEquals(
                    "The pass-band keeps its original level at " + rate,
                    Math.sqrt(.125),
                    rms(pass),
                    .001);
            assertTrue("10 kHz must not fold into the measured band at " + rate, rms(stop) < .001);
        }
    }

    @Test
    public void anImpulseDoesNotAcquireAChunkBoundaryOffset() throws Exception {
        int rate = 48000, inputAt = 56789;
        float[] input = new float[rate * 2];
        input[inputAt] = 1;
        float[] output = convert(input, rate, 17, 2048, 3, 511);
        int peak = 0;
        for (int i = 0; i < output.length; i++)
            if (Math.abs(output[i]) > Math.abs(output[peak])) peak = i;
        assertEquals(inputAt * 11025d / rate, peak, 1);
    }

    @Test
    public void constantAudioKeepsUnityAtBothEdges() throws Exception {
        float[] input = new float[44100];
        java.util.Arrays.fill(input, .375f);
        float[] output = convert(input, 44100, 1, 213, 1024);
        assertEquals(.375, output[0], .00001);
        assertEquals(.375, output[output.length - 1], .00001);
    }

    @Test
    public void invalidSamplesRatesAndDurationsNeverProduceAnAnalysis() throws Exception {
        for (int rate : new int[] {-1, 0, 7999, 44101, 384001})
            assertEquals(-1, DspAnalysis.bytes(rate, 10));
        for (double seconds : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY, 901})
            assertEquals(-1, DspAnalysis.bytes(48000, seconds));
        for (float value : new float[] {Float.NaN, Float.POSITIVE_INFINITY, 2}) {
            DspAnalysis.Resampler converter = new DspAnalysis.Resampler(48000, 11025, v -> {});
            assertThrows(IOException.class, () -> converter.accept(new float[] {value}));
        }
    }

    @Test
    public void durationLimitAndCompletionCannotBeBypassedWithSmallChunks() throws Exception {
        DspAnalysis.Resampler converter = new DspAnalysis.Resampler(11025, 100, v -> {});
        assertThrows(
                IOException.class,
                () -> {
                    for (int i = 0; i < 1000; i++) converter.accept(new float[] {.2f});
                });
        DspAnalysis.Resampler done = new DspAnalysis.Resampler(48000, 100, v -> {});
        done.accept(new float[] {.2f});
        done.finish();
        assertThrows(IOException.class, done::finish);
        assertThrows(IOException.class, () -> done.accept(new float[] {.2f}));
    }

    static MediaDataSource source(AtomicInteger closed) {
        return new MediaDataSource() {
            public int readAt(long at, byte[] buffer, int offset, int size) {
                return -1;
            }

            public long getSize() {
                return 0;
            }

            public void close() {
                closed.incrementAndGet();
            }
        };
    }

    static void decoder(int chunks, int timestampGap) {
        PlaybackService.dspDecoder =
                (source, end, abort, consumer) -> {
                    ByteBuffer pcm = ByteBuffer.allocate(1920).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < 960; i++) pcm.putShort((short) 4000);
                    for (int i = 0; i < chunks; i++) {
                        if (abort.invoke()) return null;
                        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                        info.set(0, 1920, i * 10_000L + (i > 4 ? timestampGap : 0), 0);
                        consumer.invoke(pcm, info, 2, 48000);
                    }
                    return new Object();
                };
    }

    @Test
    public void streamingPublishesOnlyCompleteMeasuredAudioAndDeletesItsScratch() throws Exception {
        AtomicInteger closed = new AtomicInteger(), features = new AtomicInteger();
        decoder(200, 0);
        PlaybackService.dspFeatures =
                (samples, seconds) -> {
                    features.incrementAndGet();
                    assertEquals(22050, samples.length);
                    assertEquals(2, seconds, .00001);
                    assertEquals(4000 / 32768f, samples[11025], .00001);
                    return new Object();
                };
        File directory = files.newFolder();
        assertNotNull(
                DspAnalysis.analyze(
                        "id",
                        directory,
                        () -> source(closed),
                        48000,
                        2,
                        () -> false,
                        () -> 64L << 20));
        assertEquals(1, closed.get());
        assertEquals(1, features.get());
        assertArrayEquals(new String[0], directory.list());
    }

    @Test
    public void cancellationDecodeFailureAndTimestampGapsDoNotPublishPartialFeatures()
            throws Exception {
        for (String failure : new String[] {"cancel", "short", "gap", "rate", "size", "throw"}) {
            AtomicInteger closed = new AtomicInteger(), features = new AtomicInteger();
            AtomicBoolean stopped = new AtomicBoolean();
            decoder(failure.equals("short") ? 20 : 200, failure.equals("gap") ? 500_000 : 0);
            PlaybackService.DspDecoder original = PlaybackService.dspDecoder;
            PlaybackService.dspDecoder =
                    (source, end, abort, chunks) -> {
                        if (failure.equals("cancel")) stopped.set(true);
                        if (failure.equals("throw"))
                            throw new IllegalStateException("codec failed");
                        if (failure.equals("rate") || failure.equals("size")) {
                            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                            info.set(
                                    0,
                                    failure.equals("size") ? DspAnalysis.CHUNK_BYTES + 1 : 1920,
                                    0,
                                    0);
                            chunks.invoke(
                                    ByteBuffer.allocate(1920),
                                    info,
                                    2,
                                    failure.equals("rate") ? 96000 : 48000);
                        }
                        return original.decode(source, end, abort, chunks);
                    };
            PlaybackService.dspFeatures =
                    (samples, seconds) -> {
                        features.incrementAndGet();
                        return new Object();
                    };
            File directory = files.newFolder();
            assertThrows(
                    failure,
                    Exception.class,
                    () ->
                            DspAnalysis.analyze(
                                    "id",
                                    directory,
                                    () -> source(closed),
                                    48000,
                                    2,
                                    stopped::get,
                                    () -> 64L << 20));
            assertEquals(failure, 1, closed.get());
            assertEquals(failure, 0, features.get());
            assertArrayEquals(failure, new String[0], directory.list());
        }
    }

    @Test
    public void admissionIsCheckedBeforeSourceOrStorageAndBeforeTheWholeArray() throws Exception {
        AtomicInteger opened = new AtomicInteger(), availableCalls = new AtomicInteger();
        decoder(200, 0);
        File directory = files.newFolder();
        assertThrows(
                DspAnalysis.Deferred.class,
                () ->
                        DspAnalysis.analyze(
                                "id",
                                directory,
                                () -> {
                                    opened.incrementAndGet();
                                    return source(new AtomicInteger());
                                },
                                48000,
                                2,
                                () -> false,
                                () -> 1));
        assertEquals(0, opened.get());
        assertArrayEquals(new String[0], directory.list());
        PlaybackService.dspFeatures =
                (samples, seconds) -> {
                    fail("Headroom was withdrawn");
                    return null;
                };
        assertThrows(
                DspAnalysis.Deferred.class,
                () ->
                        DspAnalysis.analyze(
                                "id",
                                directory,
                                () -> source(new AtomicInteger()),
                                48000,
                                2,
                                () -> false,
                                () -> availableCalls.incrementAndGet() == 1 ? 64L << 20 : 1));
        assertEquals(2, availableCalls.get());
        assertArrayEquals(new String[0], directory.list());
    }

    @Test
    public void admissionIsTheLargerOfTheDecodeAndMeasurementPeaks() {
        // 48 kHz: 147 phases of 283 taps is a 166,404 byte filter table.
        long decode = (24L << 20) + 166_404;
        assertEquals(
                "A short track is bound by the decode workspace",
                decode,
                DspAnalysis.bytes(48000, 2));
        assertEquals(
                "A long track is bound by Java mono plus the native input copy",
                2_654_269L * 8 + (16L << 20),
                DspAnalysis.bytes(48000, 240));
        assertEquals(9_930_769L * 8 + (16L << 20), DspAnalysis.bytes(48000, 900));
        for (double seconds : new double[] {2, 30, 240, 900}) {
            long frames = (long) Math.ceil((seconds + .75) * DspAnalysis.RATE);
            assertEquals(
                    Math.max(decode, DspAnalysis.measurement(frames)),
                    DspAnalysis.bytes(48000, seconds));
        }
        assertEquals(176_400L + (16L << 20), DspAnalysis.measurement(22_050));
    }

    @Test
    public void thePhaseSplitKeepsTheSameFormatAndDurationRefusals() {
        for (int rate : new int[] {-1, 0, 7999, 44101, 384001})
            assertEquals(rate + " Hz", -1, DspAnalysis.bytes(rate, 10));
        for (double seconds : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY, 900.5})
            assertEquals(seconds + " s", -1, DspAnalysis.bytes(48000, seconds));
        assertTrue(DspAnalysis.bytes(48000, 900) > 0);
        assertTrue(DspAnalysis.bytes(11025, 900) > 0);
        assertTrue(DspAnalysis.bytes(384000, 900) > 0);
    }

    @Test
    public void theSourceIsClosedBeforeMeasuredFeaturesAndOnlyTheMeasuredPcmIsCharged()
            throws Exception {
        AtomicInteger closed = new AtomicInteger(), features = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        decoder(200, 0);
        PlaybackService.dspFeatures =
                (samples, seconds) -> {
                    assertEquals("The source is closed before measurement", 1, closed.get());
                    assertEquals(22050, samples.length);
                    features.incrementAndGet();
                    return new Object();
                };
        // The source's 8 MiB cache needs no second allowance after close.
        long oldRequired = 30_319L * 8 + (24L << 20) + 166_404;
        File directory = files.newFolder();
        assertNotNull(
                DspAnalysis.analyze(
                        "id",
                        directory,
                        () -> source(closed),
                        48000,
                        2,
                        () -> false,
                        () -> calls.incrementAndGet() == 1 ? 64L << 20 : oldRequired - (8L << 20)));
        assertEquals(2, calls.get());
        assertEquals(1, features.get());
        assertArrayEquals(new String[0], directory.list());
    }

    @Test
    public void oneByteBelowTheMeasurementPeakIsWaitingForMemoryAfterDecoding() throws Exception {
        AtomicInteger closed = new AtomicInteger(), features = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        decoder(200, 0);
        PlaybackService.dspFeatures =
                (samples, seconds) -> {
                    features.incrementAndGet();
                    return new Object();
                };
        long measurement = DspAnalysis.measurement(22_050);
        File directory = files.newFolder();
        DspAnalysis.Deferred deferred =
                assertThrows(
                        DspAnalysis.Deferred.class,
                        () ->
                                DspAnalysis.analyze(
                                        "id",
                                        directory,
                                        () -> source(closed),
                                        48000,
                                        2,
                                        () -> false,
                                        () ->
                                                calls.incrementAndGet() == 1
                                                        ? 64L << 20
                                                        : measurement - 1));
        assertTrue(deferred.decoded);
        assertEquals(measurement, deferred.required);
        assertEquals(measurement - 1, deferred.available);
        assertEquals(1, closed.get());
        assertEquals(0, features.get());
        assertArrayEquals(new String[0], directory.list());
    }

    @Test
    public void stopRequestedWhileTheHeadroomIsRecheckedCannotMeasureOrPublish() throws Exception {
        AtomicInteger closed = new AtomicInteger(), features = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean stopped = new AtomicBoolean();
        decoder(200, 0);
        PlaybackService.dspFeatures =
                (samples, seconds) -> {
                    features.incrementAndGet();
                    return new Object();
                };
        File directory = files.newFolder();
        IOException failure =
                assertThrows(
                        IOException.class,
                        () ->
                                DspAnalysis.analyze(
                                        "id",
                                        directory,
                                        () -> source(closed),
                                        48000,
                                        2,
                                        stopped::get,
                                        () -> {
                                            if (calls.incrementAndGet() == 2) stopped.set(true);
                                            return 64L << 20;
                                        }));
        assertFalse(failure instanceof DspAnalysis.Deferred);
        assertEquals(2, calls.get());
        assertEquals(1, closed.get());
        assertEquals(0, features.get());
        assertArrayEquals(new String[0], directory.list());
    }

    @Test
    public void aFailedMeasurementClosesTheSourceAndDeletesItsScratch() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        decoder(200, 0);
        OutOfMemoryError failure = new OutOfMemoryError("Measurement allocation failed");
        PlaybackService.dspFeatures =
                (samples, seconds) -> {
                    throw failure;
                };
        File directory = files.newFolder();
        assertSame(
                failure,
                assertThrows(
                        OutOfMemoryError.class,
                        () ->
                                DspAnalysis.analyze(
                                        "id",
                                        directory,
                                        () -> source(closed),
                                        48000,
                                        2,
                                        () -> false,
                                        () -> 64L << 20)));
        assertEquals(1, closed.get());
        assertArrayEquals(new String[0], directory.list());
    }
}
