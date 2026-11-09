/*
 * The streaming resampler adapts BitChord's native/analyzer/resampler.cpp,
 * ported from Orchard (https://github.com/SFG5453/Orchard).
 *
 * Copyright (C) 2026 SFG545 (original Orchard implementation)
 * Copyright (C) 2026 Kushagra Singh (BitChord adaptation)
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package app.bitchord.telegram;

import android.media.MediaCodec;
import android.media.MediaDataSource;

import com.music.bitchord.playback.PlaybackService;

import kotlin.Unit;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Whole-track DSP with bounded PCM storage; no model sessions are opened. */
final class DspAnalysis {
    private DspAnalysis() {}

    static final int RATE = 11_025, MAX_SECONDS = 900, CHUNK_BYTES = 1 << 20;
    private static final int IO_BYTES = 64 << 10;

    interface Samples {
        void sample(float value) throws IOException;
    }

    static final class Deferred extends IOException {
        final boolean decoded;
        final long required, available;

        Deferred(long required, long available, boolean decoded) {
            super("DSP analysis is waiting for memory");
            this.required = required;
            this.available = available;
            this.decoded = decoded;
        }
    }

    /** The larger of the separate decode and measurement working sets. */
    static long bytes(int rate, double seconds) {
        if (rate < 8_000
                || rate > 384_000
                || !Double.isFinite(seconds)
                || seconds <= 0
                || seconds > MAX_SECONDS) return -1;
        int phases = RATE / gcd(rate, RATE);
        if (phases > 1024) return -1;
        long taps = 2L * ((long) Math.ceil(32d * Math.max(1d, rate / (double) RATE)) + 1) + 1;
        long filters = phases * taps * 4;
        if (filters > (2L << 20)) return -1;
        long decode = (24L << 20) + filters;
        return Math.max(decode, measurement((long) Math.ceil((seconds + .75) * RATE)));
    }

    /** The Java mono array and the native analyzer's input copy, with its working remainder. */
    static long measurement(long frames) {
        return frames * 8 + (16L << 20);
    }

    static Object analyze(
            String id,
            File directory,
            Supplier<MediaDataSource> open,
            int rate,
            double seconds,
            BooleanSupplier stopped,
            LongSupplier available)
            throws IOException {
        return analyze(id, directory, open, rate, seconds, stopped, available, count -> {});
    }

    /**
     * {@code allocated} is told, once and right after it succeeds, how many Java heap bytes the
     * measured mono array took: the one allocation here that a memory grant covers exactly. The
     * native analyzer's copy and the working remainder are not Java heap and are never reported.
     */
    static Object analyze(
            String id,
            File directory,
            Supplier<MediaDataSource> open,
            int rate,
            double seconds,
            BooleanSupplier stopped,
            LongSupplier available,
            LongConsumer allocated)
            throws IOException {
        long required = bytes(rate, seconds);
        check(stopped);
        if (required < 0) return null;
        long availableBytes = available.getAsLong();
        if (required > availableBytes) throw new Deferred(required, availableBytes, false);
        File scratch = File.createTempFile("automix-dsp-", ".pcm", directory);
        try {
            long frames = decode(scratch, open, rate, seconds, stopped);
            check(stopped);
            if (frames <= 0 || Math.abs(frames / (double) RATE - seconds) > .75)
                throw new IOException("The whole selected audio was not decoded");
            // The source is closed and the resampler dropped: only the measured PCM remains.
            long measured = measurement(frames);
            availableBytes = available.getAsLong();
            if (measured > availableBytes) throw new Deferred(measured, availableBytes, true);
            check(stopped);
            float[] mono = read(scratch, (int) frames, stopped, allocated);
            check(stopped);
            double measuredSeconds = frames / (double) RATE;
            Object features = PlaybackService.bitchordDspFeatures(mono, measuredSeconds);
            check(stopped);
            return features == null
                    ? null
                    : PlaybackService.bitchordDspResult(features, id, measuredSeconds);
        } finally {
            scratch.delete();
        }
    }

    private static long decode(
            File file,
            Supplier<MediaDataSource> open,
            int rate,
            double seconds,
            BooleanSupplier stopped)
            throws IOException {
        try (MediaDataSource source = open.get();
                FileOutputStream output = new FileOutputStream(file)) {
            if (source == null) throw new IOException("The selected audio cannot be reopened");
            ByteBuffer pending = ByteBuffer.allocate(IO_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            Resampler resampler =
                    new Resampler(
                            rate,
                            (int) Math.ceil((seconds + .75) * RATE),
                            value -> {
                                if (pending.remaining() < 4) {
                                    output.write(pending.array(), 0, pending.position());
                                    pending.clear();
                                }
                                pending.putFloat(value);
                            });
            long[] input = {0};
            Object decoded =
                    PlaybackService.bitchordDspDecode(
                            source,
                            seconds,
                            () -> stopped.getAsBoolean() || Thread.currentThread().isInterrupted(),
                            (buffer, info, channels, sampleRate) -> {
                                try {
                                    check(stopped);
                                    validate(buffer, info, channels, sampleRate, rate, input[0]);
                                    float[] mono =
                                            PlaybackService.bitchordDspMono(buffer, info, channels);
                                    if (mono == null || mono.length != info.size / (channels * 2))
                                        throw new IOException(
                                                "The selected audio returned an invalid PCM chunk");
                                    resampler.accept(mono);
                                    input[0] += mono.length;
                                    return Unit.INSTANCE;
                                } catch (IOException failed) {
                                    throw new StreamFailure(failed);
                                }
                            });
            if (decoded == null) throw new IOException("The selected audio decode did not finish");
            check(stopped);
            resampler.finish();
            output.write(pending.array(), 0, pending.position());
            return resampler.written;
        } catch (StreamFailure failed) {
            throw (IOException) failed.getCause();
        }
    }

    private static void validate(
            ByteBuffer buffer,
            MediaCodec.BufferInfo info,
            int channels,
            int rate,
            int expectedRate,
            long received)
            throws IOException {
        if (buffer == null
                || info == null
                || rate != expectedRate
                || channels < 1
                || channels > 8
                || info.size < 0
                || info.size > CHUNK_BYTES
                || info.offset < 0
                || info.offset > buffer.capacity() - info.size
                || info.size % (channels * 2) != 0)
            throw new IOException("The selected audio PCM format changed or exceeded its limit");
        double expected = received * 1_000_000d / rate;
        if (Math.abs(info.presentationTimeUs - expected) > 100_000)
            throw new IOException("The selected audio timestamps are not continuous");
    }

    private static float[] read(
            File file, int frames, BooleanSupplier stopped, LongConsumer allocated)
            throws IOException {
        if (frames <= 0 || frames > RATE * (MAX_SECONDS + 1) || file.length() != frames * 4L)
            throw new IOException("The measured audio exceeded its storage limit");
        float[] samples = new float[frames];
        allocated.accept(frames * 4L);
        ByteBuffer bytes = ByteBuffer.allocate(IO_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        try (FileInputStream input = new FileInputStream(file)) {
            int at = 0;
            while (at < frames) {
                check(stopped);
                int count = Math.min(bytes.capacity(), (frames - at) * 4), read = 0;
                while (read < count) {
                    int next = input.read(bytes.array(), read, count - read);
                    if (next < 0) throw new IOException("The measured audio was truncated");
                    read += next;
                }
                bytes.clear();
                bytes.asFloatBuffer().get(samples, at, count / 4);
                at += count / 4;
            }
        }
        return samples;
    }

    private static void check(BooleanSupplier stopped) throws IOException {
        if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new IOException("DSP analysis stopped");
    }

    private static final class StreamFailure extends RuntimeException {
        StreamFailure(IOException cause) {
            super(cause);
        }
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    /**
     * Streaming adaptation of BitChord's Blackman polyphase resampler by Kushagra Singh, ported
     * from Orchard by SFG545 (AGPL-3.0-or-later). One phase spans every codec chunk.
     */
    static final class Resampler {
        final int up, down, reach, taps, mask, limit;
        final float[] weights, history;
        final Samples sink;
        long received, written;
        float last;
        boolean finished;

        Resampler(int rate, int limit, Samples sink) throws IOException {
            if (bytes(rate, 1) < 0 || limit <= 0 || limit > RATE * (MAX_SECONDS + 1))
                throw new IOException("Unsupported DSP resampling size or rate");
            int divisor = gcd(rate, RATE);
            up = RATE / divisor;
            down = rate / divisor;
            double cutoff = .5 * Math.min(1d, RATE / (double) rate);
            double half = 32 / (2 * cutoff);
            reach = (int) Math.ceil(half) + 1;
            taps = 2 * reach + 1;
            int size = 1;
            while (size < taps + 2) size <<= 1;
            mask = size - 1;
            history = new float[size * 2];
            weights = new float[up * taps];
            for (int phase = 0; phase < up; phase++) {
                double fraction = phase / (double) up, sum = 0;
                for (int k = 0; k < taps; k++) {
                    double offset = Math.abs(fraction - (k - reach));
                    double value = 0;
                    if (offset < half) {
                        double x = 2 * cutoff * offset;
                        double sinc = x < 1e-12 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x);
                        double position = (offset + half) / (2 * half);
                        double window =
                                .42
                                        - .5 * Math.cos(2 * Math.PI * position)
                                        + .08 * Math.cos(4 * Math.PI * position);
                        value = 2 * cutoff * sinc * window;
                    }
                    weights[phase * taps + k] = (float) value;
                    sum += value;
                }
                for (int k = 0; k < taps; k++) weights[phase * taps + k] /= sum;
            }
            this.limit = limit;
            this.sink = sink;
        }

        void accept(float[] chunk) throws IOException {
            if (finished || chunk == null)
                throw new IOException("DSP samples arrived after completion");
            for (float value : chunk) {
                if (!Float.isFinite(value) || Math.abs(value) > 1.01f)
                    throw new IOException("The selected audio contains invalid PCM");
                if (received == 0) Arrays.fill(history, value);
                int slot = (int) received & mask;
                history[slot] = history[slot + mask + 1] = last = value;
                received++;
                while (written * down / up + reach < received) emit();
            }
        }

        private void emit() throws IOException {
            if (written >= limit) throw new IOException("DSP audio exceeded its duration limit");
            long position = written * down;
            int first = (int) (position / up - reach) & mask;
            int row = (int) (position % up) * taps;
            float sum = 0;
            for (int k = 0; k < taps; k++) sum += weights[row + k] * history[first + k];
            sink.sample(sum);
            written++;
        }

        void finish() throws IOException {
            if (finished) throw new IOException("DSP audio was completed twice");
            finished = true;
            long count = received * up / down;
            long padding = received;
            while (written < count) {
                while (padding <= written * down / up + reach) {
                    int slot = (int) padding & mask;
                    history[slot] = history[slot + mask + 1] = last;
                    padding++;
                }
                emit();
            }
        }
    }
}
