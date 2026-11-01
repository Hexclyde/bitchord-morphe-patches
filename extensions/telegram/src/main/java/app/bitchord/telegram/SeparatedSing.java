package app.bitchord.telegram;

import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Causal stereo separation with a fixed lookahead and asynchronous magnitude estimates.
 *
 * <p>The lookahead is a real delay of {@link #latencySamples} frames. Nothing is written for the
 * first frames that go in, and {@link #end} and {@link #drain} bring out the same number when the
 * input stops, so the frames that come out are exactly the frames that went in.
 */
final class SeparatedSing {
    static final int FFT = 4096, HOP = 1024, BINS = FFT / 2 + 1, FRAMES = 960, MASK_FRAMES = 128;
    static final int MODEL_RATE = 44100, MAX_RATE = 192000;
    static final double LOOKAHEAD_SECONDS = 1.5;

    interface Model {
        float[] infer(float[] magnitude) throws Exception;

        /** Memory ran out on the worker. The engine has already closed itself. */
        default void failed() {}

        /** False while no estimate should be asked for. The delay and the filter carry on. */
        default boolean available() {
            return true;
        }

        /** The worker's copy of the history. One worker can share one between engines. */
        default float[] scratch(int length) {
            return new float[length];
        }
    }

    interface Fallback {
        void process(float[] samples, int count, float level);
    }

    private final int rate, delay, pendingCount, outputSize;
    private final Model model;
    private final Executor executor;
    private Fallback fallback;
    private Object fallbackSource;
    private final float[][] input = new float[2][FFT];
    private final double[] real = new double[FFT], imaginary = new double[FFT], window = window();
    private final float[][] spectra;
    private final long[] frameStarts;
    private final float[] outputLeft, outputRight, outputWeight;
    private final int[] outputEpoch;
    private final float[] history = new float[2 * BINS * FRAMES];
    private final float[][] magnitudes = new float[2][BINS], previous = new float[2][BINS];
    private final ReentrantLock historyLock = new ReentrantLock();
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile int epoch = 1;
    private volatile Mask latest, outgoing;
    private volatile int track;
    private int inputPosition, hopPosition, head, count, historyPosition, historyCount;
    private long samples, lastRequest, trackStart = -1;
    private double nextHistory, historyEnd;
    private float wet;
    private boolean usingMask, ended, ranLastBlock, historyStale;
    private volatile boolean closed, resetRequested;
    private int tail, lastDrainInput;
    private float[] snapshotBuffer;

    private static final class Mask {
        final float[] values;
        final double end;
        final int epoch, track;

        Mask(float[] values, double end, int epoch, int track) {
            this.values = values;
            this.end = end;
            this.epoch = epoch;
            this.track = track;
        }
    }

    SeparatedSing(int rate, Model model, Executor executor, Fallback fallback) {
        if (rate < 8000 || rate > MAX_RATE)
            throw new IllegalArgumentException("Unsupported separation rate");
        this.rate = rate;
        this.model = model;
        this.executor = executor;
        this.fallback = fallback;
        int hops = (int) Math.ceil(LOOKAHEAD_SECONDS * rate / HOP);
        delay = hops * HOP + FFT;
        pendingCount = hops + 2;
        outputSize = delay + FFT * 2;
        spectra = new float[pendingCount][4 * FFT];
        frameStarts = new long[pendingCount];
        outputLeft = new float[outputSize];
        outputRight = new float[outputSize];
        outputWeight = new float[outputSize];
        outputEpoch = new int[outputSize];
    }

    int sampleRate() {
        return rate;
    }

    int latencySamples() {
        return delay;
    }

    boolean ready() {
        Mask mask = latest;
        return mask != null && mask.epoch == epoch;
    }

    /** Frames the current stream has put in so far, tail included. */
    long inputFrames() {
        return samples;
    }

    /** Whether the last block went through the delay rather than straight to the filter. */
    boolean ranLastBlock() {
        return ranLastBlock;
    }

    /** Whether the filter it falls back on is already this one. */
    boolean fallsBackOn(Object source) {
        return fallbackSource == source;
    }

    void fallback(Object source, Fallback current) {
        fallbackSource = source;
        fallback = current;
    }

    /** Drops everything held. For a seek, or audio that went past without passing through here. */
    void reset() {
        resetRequested = true;
        ranLastBlock = false;
        latest = null;
        outgoing = null;
    }

    private void resetState() {
        resetRequested = false;
        epoch++;
        track++;
        latest = null;
        outgoing = null;
        trackStart = -1;
        inputPosition = hopPosition = head = count = historyPosition = historyCount = 0;
        samples = lastRequest = 0;
        nextHistory = historyEnd = 0;
        wet = 0;
        usingMask = false;
        ended = false;
        historyStale = false;
        tail = 0;
        for (float[] channel : input) Arrays.fill(channel, 0);
        for (float[] channel : previous) Arrays.fill(channel, 0);
        for (float[] channel : magnitudes) Arrays.fill(channel, 0);
    }

    /**
     * The next frame in starts another song, in the same stream. What's in the delay plays on as it
     * is, under the estimates made for it, while new estimates only look at the new song.
     */
    void newTrack() {
        if (closed || samples == 0) return;
        // One more look at the whole of the song that's ending: what's in the delay needs it.
        if (!busy.get() && model != null && model.available()) request();
        track++;
        trackStart = samples;
        outgoing = latest;
        latest = null;
        historyStale = true;
    }

    void close() {
        closed = true;
        epoch++;
        latest = null;
        outgoing = null;
    }

    /**
     * Puts a block through the delay, in place. Returns how many frames at its start are not to be
     * played, because they stand for time before the stream began.
     */
    int process(float[] pcm, int length, float level) {
        ranLastBlock = false;
        if (level <= 0) {
            reset();
            return 0;
        }
        if (length <= 0) return 0;
        if (resetRequested || ended) resetState();
        if (closed || model == null) {
            if (fallback != null) fallback.process(pcm, length, level);
            return 0;
        }
        ranLastBlock = true;
        return run(pcm, Math.min(length, pcm.length) & ~1, level);
    }

    private int run(float[] pcm, int bounded, float level) {
        int skipped = 0;
        for (int offset = 0; offset < bounded; offset += 2) {
            input[0][inputPosition] = pcm[offset];
            input[1][inputPosition] = pcm[offset + 1];
            inputPosition = (inputPosition + 1) % FFT;
            samples++;
            if (++hopPosition == HOP) {
                hopPosition = 0;
                frame(level);
            }
            int index = (int) ((samples - 1) % outputSize);
            if (samples <= delay) {
                // Before the stream's first frame, however much of a window has reached here.
                skipped++;
                outputEpoch[index] = 0;
                pcm[offset] = 0;
                pcm[offset + 1] = 0;
            } else if (outputEpoch[index] == epoch && outputWeight[index] > 1e-8f) {
                pcm[offset] = finite(outputLeft[index] / outputWeight[index]);
                pcm[offset + 1] = finite(outputRight[index] / outputWeight[index]);
                outputEpoch[index] = 0;
            } else {
                pcm[offset] = 0;
                pcm[offset + 1] = 0;
            }
        }
        if (!usingMask && fallback != null) fallback.process(pcm, bounded, level);
        return skipped;
    }

    /** No more input: what the delay holds is to come out through {@link #drain}. */
    void end() {
        if (ended || closed || resetRequested || samples == 0) return;
        ended = true;
        tail = delay;
    }

    boolean ended() {
        return ended;
    }

    boolean hasTail() {
        return tail > 0 && !closed && !resetRequested;
    }

    /**
     * Up to {@code maxFrames} more of the tail, written from the start of {@code output}. Returns
     * the frames written, which can be none while {@link #hasTail} stays true for a stream shorter
     * than the delay.
     */
    int drain(float[] output, int maxFrames, float level) {
        if (!hasTail()) return 0;
        if (level <= 0) {
            reset();
            return 0;
        }
        int frames = Math.min(tail, Math.min(maxFrames, output.length / 2));
        Arrays.fill(output, 0, frames * 2, 0);
        int skipped = run(output, frames * 2, level);
        tail -= frames;
        lastDrainInput = frames;
        int written = frames - skipped;
        if (skipped > 0) System.arraycopy(output, skipped * 2, output, 0, written * 2);
        return written;
    }

    int drain(float[] output, float level) {
        return drain(output, output.length / 2, level);
    }

    /** How many frames of input the last {@link #drain} stood for. */
    int lastDrainInput() {
        return lastDrainInput;
    }

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0;
    }

    private void frame(float level) {
        int slot = (head + count) % pendingCount;
        frameStarts[slot] = samples - FFT;
        for (int channel = 0; channel < 2; channel++) {
            Arrays.fill(imaginary, 0);
            for (int i = 0; i < FFT; i++)
                real[i] = input[channel][(inputPosition + i) % FFT] * window[i];
            Fingerprint.fft(real, imaginary);
            for (int i = 0; i < FFT; i++) {
                spectra[slot][channel * 2 * FFT + i] = (float) real[i];
                spectra[slot][channel * 2 * FFT + FFT + i] = (float) imaginary[i];
            }
            for (int i = 0; i < BINS; i++)
                magnitudes[channel][i] = (float) Math.hypot(real[i], imaginary[i]);
        }
        recordHistory();
        count++;
        if (count > pendingCount - 2) {
            emit(head, level);
            head = (head + 1) % pendingCount;
            count--;
        }
        if (samples - lastRequest >= rate / 2
                && !busy.get()
                && !historyStale
                && model.available()) {
            lastRequest = samples;
            request();
        }
        for (int c = 0; c < 2; c++) System.arraycopy(magnitudes[c], 0, previous[c], 0, BINS);
    }

    private void recordHistory() {
        if (!historyLock.tryLock()) return;
        try {
            // Not while an estimate is still on its way to reading the song that's ending.
            if (historyStale && !busy.get()) {
                historyStale = false;
                historyCount = historyPosition = 0;
                nextHistory = samples;
                for (float[] channel : previous) Arrays.fill(channel, 0);
            }
            if (nextHistory == 0) nextHistory = samples;
            while (nextHistory <= samples) {
                float time =
                        (float) Math.max(0, Math.min(1, (nextHistory - (samples - HOP)) / HOP));
                for (int c = 0; c < 2; c++)
                    for (int bin = 0; bin < BINS; bin++) {
                        double live = bin * (double) MODEL_RATE / rate;
                        float current = interpolate(magnitudes[c], live),
                                past = interpolate(previous[c], live);
                        history[(c * BINS + bin) * FRAMES + historyPosition] =
                                past + (current - past) * time;
                    }
                historyPosition = (historyPosition + 1) % FRAMES;
                historyCount = Math.min(FRAMES, historyCount + 1);
                historyEnd = nextHistory;
                nextHistory += HOP * (double) rate / MODEL_RATE;
            }
        } finally {
            historyLock.unlock();
        }
    }

    private void request() {
        if (closed || !busy.compareAndSet(false, true)) return;
        final int generation = epoch, trackAsked = track;
        try {
            executor.execute(
                    () -> {
                        try {
                            if (snapshotBuffer == null)
                                snapshotBuffer = model.scratch(history.length);
                            float[] snapshot = snapshotBuffer;
                            Arrays.fill(snapshot, 0);
                            double end;
                            historyLock.lock();
                            try {
                                if (generation != epoch || closed) return;
                                end = historyEnd;
                                int filled = historyCount,
                                        first = (historyPosition - filled + FRAMES) % FRAMES;
                                for (int c = 0; c < 2; c++)
                                    for (int bin = 0; bin < BINS; bin++)
                                        for (int frame = 0; frame < filled; frame++)
                                            snapshot[
                                                            (c * BINS + bin) * FRAMES
                                                                    + FRAMES
                                                                    - filled
                                                                    + frame] =
                                                    history[
                                                            (c * BINS + bin) * FRAMES
                                                                    + (first + frame) % FRAMES];
                            } finally {
                                historyLock.unlock();
                            }
                            float[] vocals = model.infer(snapshot);
                            if (vocals == null || vocals.length != snapshot.length) return;
                            float[] masks = new float[2 * BINS * MASK_FRAMES];
                            for (int c = 0; c < 2; c++)
                                for (int bin = 0; bin < BINS; bin++)
                                    for (int frame = 0; frame < MASK_FRAMES; frame++) {
                                        int index =
                                                (c * BINS + bin) * FRAMES
                                                        + FRAMES
                                                        - MASK_FRAMES
                                                        + frame;
                                        masks[(c * BINS + bin) * MASK_FRAMES + frame] =
                                                mask(snapshot[index], vocals[index]);
                                    }
                            if (closed || generation != epoch) return;
                            Mask made = new Mask(masks, end, generation, trackAsked);
                            if (trackAsked == track) latest = made;
                            else if (trackAsked == track - 1) outgoing = made;
                        } catch (OutOfMemoryError pressure) {
                            snapshotBuffer = null;
                            close();
                            model.failed();
                        } catch (Exception failure) {
                            /* The last valid mask remains available until it ages out. */
                        } finally {
                            busy.set(false);
                        }
                    });
        } catch (RuntimeException stopped) {
            busy.set(false);
        }
    }

    private void emit(int slot, float level) {
        long start = frameStarts[slot];
        double frameTime = start + FFT;
        Mask mask = trackStart >= 0 && frameTime <= trackStart ? outgoing : latest;
        double column =
                mask == null
                        ? -1
                        : MASK_FRAMES
                                - 1
                                - (mask.end - frameTime) * MODEL_RATE / (rate * (double) HOP);
        boolean valid =
                mask != null && mask.epoch == epoch && column >= 0 && column <= MASK_FRAMES - 1;
        usingMask = valid;
        float target = valid ? level : 0;
        wet += Math.max(-.15f, Math.min(.15f, target - wet));
        for (int channel = 0; channel < 2; channel++) {
            for (int bin = 0; bin < FFT; bin++) {
                int positive = bin <= FFT / 2 ? bin : FFT - bin;
                double modelBin = positive * (double) rate / MODEL_RATE;
                float vocal = valid ? maskAt(mask.values, channel, modelBin, column) : 0;
                float gain = 1 - wet * vocal;
                real[bin] = spectra[slot][channel * 2 * FFT + bin] * gain;
                imaginary[bin] = -spectra[slot][channel * 2 * FFT + FFT + bin] * gain;
            }
            Fingerprint.fft(real, imaginary);
            for (int i = 0; i < FFT; i++) {
                long position = start + delay + i;
                if (position < samples) continue;
                int index = (int) (position % outputSize);
                if (outputEpoch[index] != epoch) {
                    outputEpoch[index] = epoch;
                    outputLeft[index] = outputRight[index] = outputWeight[index] = 0;
                }
                float value = (float) (real[i] / FFT * window[i]);
                if (channel == 0) {
                    outputLeft[index] += value;
                    outputWeight[index] += (float) (window[i] * window[i]);
                } else outputRight[index] += value;
            }
        }
    }

    static float mask(float mixture, float vocal) {
        if (!Float.isFinite(mixture) || !Float.isFinite(vocal) || mixture <= 0) return 0;
        double v = Math.max(0, Math.min(mixture, vocal)), a = Math.max(mixture - v, 0);
        return (float) Math.max(0, Math.min(1, v * v / (v * v + a * a + 1e-12)));
    }

    static double modelBin(int liveBin, int sampleRate) {
        return liveBin * (double) sampleRate / MODEL_RATE;
    }

    private static float interpolate(float[] values, double index) {
        if (index < 0 || index > values.length - 1) return 0;
        int lo = (int) index, hi = Math.min(lo + 1, values.length - 1);
        return values[lo] + (values[hi] - values[lo]) * (float) (index - lo);
    }

    private static float maskAt(float[] values, int channel, double bin, double frame) {
        if (bin < 0 || bin > BINS - 1) return 0;
        int lo = (int) bin,
                hi = Math.min(lo + 1, BINS - 1),
                time = (int) frame,
                next = Math.min(time + 1, MASK_FRAMES - 1);
        int a = (channel * BINS + lo) * MASK_FRAMES, b = (channel * BINS + hi) * MASK_FRAMES;
        float f = (float) (frame - time),
                x = values[a + time] + (values[a + next] - values[a + time]) * f,
                y = values[b + time] + (values[b + next] - values[b + time]) * f;
        return x + (y - x) * (float) (bin - lo);
    }

    private static double[] window() {
        double[] result = new double[FFT];
        for (int i = 0; i < FFT; i++) result[i] = .5 - .5 * Math.cos(2 * Math.PI * i / FFT);
        return result;
    }
}
