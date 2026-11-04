package app.bitchord.telegram;

import android.media.MediaCodec;
import android.media.MediaDataSource;
import android.net.Uri;

import com.music.bitchord.playback.PlaybackService;

import kotlin.Unit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Bounded streaming three-band waveforms. Butterworth filters and per-channel peak extraction
 * adapted from DJM-Rec WaveformAnalyzer.cpp/.h, 2f36d11b837f5d627740c77f3acc9b130c24b04f, Copyright
 * (c) 2026 P2GR, MIT. See licenses/AUTOMIX-COMPONENTS.txt. Uses actual PCM timestamps; no mono sum,
 * full-track arrays, synthetic waveforms or alternate analysis URI.
 */
final class TransitionWaveform {
    static final class Bands {
        public final float[] low, mid, high;

        Bands(int count) {
            low = new float[count];
            mid = new float[count];
            high = new float[count];
        }
    }

    static Bands read(Uri selected, double from, double to, int bins) throws IOException {
        if (selected == null
                || !Double.isFinite(from)
                || !Double.isFinite(to)
                || from < 0
                || to <= from
                || to - from > 180
                || bins < 2
                || bins > 4096) return null;
        long deadline = System.nanoTime() + 90_000_000_000L;
        SourceAutomix.enterQuiet();
        // Resolution can dispatch coroutines: open before acquiring the shared PCM permit.
        try (MediaDataSource source = SourceAutomix.opener(selected).invoke()) {
            if (source == null) return null;
            try (SourceAudio.Decoding permit = SourceAudio.decode(deadline, () -> false)) {
                Accumulator wave = new Accumulator(from, to, bins);
                Object decoded =
                        PlaybackService.bitchordWaveDecode(
                                source,
                                from,
                                to,
                                () ->
                                        Thread.currentThread().isInterrupted()
                                                || AutomixAccess.stopped()
                                                || System.nanoTime() >= deadline,
                                (buffer, info, channels, rate) -> {
                                    try {
                                        permit.check();
                                        wave.accept(buffer, info, channels, rate);
                                    } catch (IOException stopped) {
                                        throw new DecodeFailure(stopped);
                                    }
                                    return Unit.INSTANCE;
                                });
                permit.check();
                return decoded == null || !wave.complete() ? null : wave.bands;
            } catch (DecodeFailure failed) {
                throw (IOException) failed.getCause();
            }
        } finally {
            SourceAutomix.leaveQuiet();
        }
    }

    private static final class DecodeFailure extends RuntimeException {
        DecodeFailure(IOException cause) {
            super(cause);
        }
    }

    static final class Accumulator {
        final double from, to;
        final Bands bands;
        private Filter[][] filters;
        // Bounded reusable sample block; one codec buffer at a time, never a track-sized array.
        private static final int BLOCK_FRAMES = 4096;
        private short[] block;
        private int channels, rate;
        private double first = Double.POSITIVE_INFINITY, last = Double.NEGATIVE_INFINITY;
        private long accepted;

        Accumulator(double from, double to, int bins) {
            this.from = from;
            this.to = to;
            bands = new Bands(bins);
        }

        void accept(ByteBuffer input, MediaCodec.BufferInfo info, int channelCount, int sampleRate)
                throws IOException {
            if (input == null
                    || info == null
                    || info.offset < 0
                    || info.size < 0
                    || (long) info.offset + info.size > input.limit()
                    || channelCount < 1
                    || channelCount > 32
                    || sampleRate < 4000
                    || sampleRate > 768000
                    || info.size % (channelCount * 2) != 0)
                throw new IOException("Invalid waveform PCM");
            if (filters == null) {
                channels = channelCount;
                rate = sampleRate;
                filters = new Filter[channels][4];
                for (int c = 0; c < channels; c++) {
                    filters[c][0] = new Filter(250, rate, false);
                    filters[c][1] = new Filter(200, rate, true);
                    filters[c][2] = new Filter(1500, rate, false);
                    filters[c][3] = new Filter(2000, rate, true);
                }
            }
            if (channels != channelCount || rate != sampleRate)
                throw new IOException("Waveform format changed");
            ByteBuffer bytes = input.duplicate();
            bytes.limit(info.offset + info.size);
            bytes.position(info.offset);
            ShortBuffer pcm = bytes.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            int frames = info.size / (channels * 2), bins = bands.low.length;
            double start = info.presentationTimeUs / 1_000_000d;
            if (block == null) block = new short[BLOCK_FRAMES * channels];
            int bin = -1;
            double low = 0, mid = 0, high = 0;
            for (int done = 0; done < frames; ) {
                if (Thread.currentThread().isInterrupted()) throw new SourceAudio.Stopped();
                int count = Math.min(BLOCK_FRAMES, frames - done);
                pcm.get(block, 0, count * channels);
                for (int frame = 0, at = 0; frame < count; frame++) {
                    double seconds = start + (done + frame) / (double) rate;
                    int next = (int) Math.floor((seconds - from) / (to - from) * bins);
                    boolean inRange = seconds >= from && seconds < to && next >= 0 && next < bins;
                    if (next != bin || !inRange) {
                        flush(bin, low, mid, high);
                        bin = inRange ? next : -1;
                        low = mid = high = 0;
                    }
                    for (int c = 0; c < channels; c++) {
                        double sample = block[at++] / 32768d;
                        Filter[] f = filters[c];
                        double l = Math.abs(f[0].process(sample));
                        double m = Math.abs(f[2].process(f[1].process(sample)));
                        double h = Math.abs(f[3].process(sample));
                        if (l > low) low = l;
                        if (m > mid) mid = m;
                        if (h > high) high = h;
                    }
                    if (inRange) {
                        accepted++;
                        if (seconds < first) first = seconds;
                        if (seconds + 1d / rate > last) last = seconds + 1d / rate;
                    }
                }
                done += count;
            }
            flush(bin, low, mid, high);
        }

        private void flush(int bin, double low, double mid, double high) {
            if (bin < 0) return;
            bands.low[bin] = Math.max(bands.low[bin], (float) Math.min(1, low));
            bands.mid[bin] = Math.max(bands.mid[bin], (float) Math.min(1, mid));
            bands.high[bin] = Math.max(bands.high[bin], (float) Math.min(1, high));
        }

        boolean complete() {
            return accepted > 0 && first <= from + .1 && last >= to - .1;
        }
    }

    /** DJM-Rec's second-order Butterworth, direct form I; independent state per channel. */
    private static final class Filter {
        final double b0, b1, b2, a1, a2;
        double x1, x2, y1, y2;

        Filter(double cutoff, int rate, boolean high) {
            double w = 2 * Math.PI * Math.min(cutoff, rate * .45) / rate;
            double cos = Math.cos(w), alpha = Math.sin(w) / (2 * .70710678), a0 = 1 + alpha;
            b0 = (high ? 1 + cos : 1 - cos) / (2 * a0);
            b1 = (high ? -(1 + cos) : 1 - cos) / a0;
            b2 = b0;
            a1 = -2 * cos / a0;
            a2 = (1 - alpha) / a0;
        }

        double process(double x) {
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            return y;
        }
    }
}
