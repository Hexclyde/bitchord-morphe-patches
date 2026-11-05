package com.music.bitchord.playback.smart;

import android.media.MediaCodec;
import android.media.MediaDataSource;

import kotlin.Pair;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function4;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntConsumer;

/**
 * Test stand-in for the host decoder, with the native behaviour the real one inherits: a Matroska
 * seek without usable cues walks every block through a 2 KB cache, a read error is swallowed and
 * reads as the end of the file from then on, and releasing an extractor closes its source.
 */
public final class AudioDecoder {
    public static final int RATE = 44100, BYTES_PER_SECOND = 20_000, HEADER = 64;
    public static final List<String> windows = new ArrayList<>();
    public static volatile boolean corrupt;
    public static volatile IntConsumer afterWindow;

    /**
     * While held, a seek past the start waits without reading or looking at abort or interrupts.
     */
    public static volatile java.util.concurrent.CountDownLatch seekStall;

    /**
     * Whether decoding goes through the platform extractor the old way, where a seek cannot be
     * stopped. By default it goes through the shared decoder bridge, as the host's does.
     */
    public static volatile boolean platform;

    public static final java.util.concurrent.atomic.AtomicInteger
            seeksEntered = new java.util.concurrent.atomic.AtomicInteger(),
            buffersDelivered = new java.util.concurrent.atomic.AtomicInteger();

    private AudioDecoder() {}

    /** Builds the synthetic PCM before a worker's measured behavior starts. */
    public static void prepareRecording(long seed, int seconds) {
        tune(seed, seconds);
    }

    /** A file's bytes: a header naming the recording, then a position-dependent pattern. */
    public static byte[] file(long seed, long middleSeed, int seconds, int leadMs, boolean walks) {
        byte[] out = new byte[HEADER + seconds * BYTES_PER_SECOND];
        ByteBuffer header = ByteBuffer.wrap(out);
        header.putLong(seed).putLong(middleSeed).putInt(seconds).putInt(leadMs);
        header.put((byte) (walks ? 1 : 0));
        for (int i = HEADER; i < out.length; i++) out[i] = pattern(i);
        return out;
    }

    public static byte pattern(long position) {
        return (byte) (position * 31 ^ position >>> 8);
    }

    /** One extractor's view of a source; after a read error it only sees the end of the file. */
    private static final class Native implements AutoCloseable {
        final MediaDataSource source;
        boolean failed;

        Native(MediaDataSource source) {
            this.source = source;
        }

        int read(long at, byte[] into, int length) {
            if (failed) return -1;
            try {
                return source.readAt(at, into, 0, length);
            } catch (IOException | RuntimeException swallowedByJni) {
                failed = true;
                return -1;
            }
        }

        ByteBuffer header() {
            byte[] bytes = new byte[2048];
            int got = read(0, bytes, 2048);
            return got < HEADER ? null : ByteBuffer.wrap(bytes);
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }

    public static Double containerDurationSeconds(MediaDataSource source) {
        if (!platform) {
            app.bitchord.telegram.SyntheticDecoder.install();
            return app.bitchord.telegram.SourceAudio.containerDurationSeconds(source);
        }
        try (Native extractor = new Native(source)) {
            ByteBuffer header = extractor.header();
            if (header == null) return null;
            return (double) header.getInt(16);
        } catch (IOException e) {
            return null;
        }
    }

    public static float[] toMono(ByteBuffer buffer, MediaCodec.BufferInfo info, int channels) {
        ByteBuffer pcm = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        pcm.position(info.offset);
        pcm.limit(info.offset + info.size);
        float[] mono = new float[pcm.remaining() / 2];
        for (int i = 0; i < mono.length; i++) mono[i] = pcm.getShort() / 32768f;
        return mono;
    }

    @SuppressWarnings("unchecked")
    public static Pair<Double, Double> decodeRaw(
            MediaDataSource source,
            double start,
            double end,
            Function0<Boolean> abort,
            Function4<ByteBuffer, MediaCodec.BufferInfo, Integer, Integer, ?> onBuffer)
            throws IOException {
        if (platform) return platformDecodeRaw(source, start, end, abort, onBuffer);
        app.bitchord.telegram.SyntheticDecoder.install();
        Function4<ByteBuffer, MediaCodec.BufferInfo, Integer, Integer, kotlin.Unit> counted =
                (bytes, info, channels, rate) -> {
                    buffersDelivered.incrementAndGet();
                    return ((Function4<
                                            ByteBuffer,
                                            MediaCodec.BufferInfo,
                                            Integer,
                                            Integer,
                                            kotlin.Unit>)
                                    onBuffer)
                            .invoke(bytes, info, channels, rate);
                };
        Pair<Double, Double> timing =
                app.bitchord.telegram.SourceAudio.decodeRaw(source, start, end, abort, counted);
        if (timing != null) {
            synchronized (windows) {
                windows.add(app.bitchord.telegram.SyntheticDecoder.seed() + "@" + (int) start);
            }
            IntConsumer hook = afterWindow;
            if (hook != null) hook.accept(windows.size());
        }
        return timing;
    }

    private static Pair<Double, Double> platformDecodeRaw(
            MediaDataSource source,
            double start,
            double end,
            Function0<Boolean> abort,
            Function4<ByteBuffer, MediaCodec.BufferInfo, Integer, Integer, ?> onBuffer)
            throws IOException {
        try (Native extractor = new Native(source)) {
            ByteBuffer header = extractor.header();
            if (header == null) return null;
            long seed = header.getLong(0), middle = header.getLong(8);
            int seconds = header.getInt(16), lead = header.getInt(20);
            boolean walks = header.get(24) == 1;
            byte[] bytes = new byte[2048];
            long target = HEADER + (long) (start * BYTES_PER_SECOND);
            if (walks && start > 0)
                for (long at = HEADER; at < target; at += 2100)
                    if (extractor.read(at, bytes, 2048) <= 0) return null;
            java.util.concurrent.CountDownLatch stall = seekStall;
            if (stall != null && start > 0) {
                seeksEntered.incrementAndGet();
                boolean held = true;
                while (held) {
                    try {
                        held = !stall.await(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                        // The platform call does not return early for an interrupt.
                    }
                }
            }
            float[] whole = tune(seed, seconds), other = middle == 0 ? null : tune(middle, seconds);
            int done = 0;
            for (double t = start; t < end; t += 1) {
                if (abort.invoke()) return null;
                long at = HEADER + (long) (t * BYTES_PER_SECOND);
                for (int part = 0; part < BYTES_PER_SECOND; ) {
                    int got = extractor.read(at + part, bytes, 2048);
                    if (got <= 0) return done == 0 ? null : timing(start);
                    for (int i = 0; i < got; i++)
                        if (bytes[i] != pattern(at + part + i)) corrupt = true;
                    part += got;
                }
                int from = (int) ((t - lead / 1000.0) * RATE);
                float[] song =
                        other != null && t > seconds / 3.0 && t < seconds * 2 / 3.0 ? other : whole;
                ByteBuffer pcm = ByteBuffer.allocate(RATE * 2).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < RATE; i++) {
                    int index = from + i;
                    float v = index >= 0 && index < song.length ? song[index] : 0;
                    pcm.putShort((short) Math.max(-32768, Math.min(32767, v * 32767)));
                }
                pcm.flip();
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                info.set(0, RATE * 2, (long) (t * 1e6), 0);
                buffersDelivered.incrementAndGet();
                onBuffer.invoke(pcm, info, 1, RATE);
                done++;
            }
            synchronized (windows) {
                windows.add(seed + "@" + (int) start);
            }
            IntConsumer hook = afterWindow;
            if (hook != null) hook.accept(windows.size());
            return timing(start);
        }
    }

    private static Pair<Double, Double> timing(double start) {
        return new Pair<>((double) RATE, start);
    }

    private static final java.util.Map<Long, float[]> tunes =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Separate notes on a beat, so its loudness and pitch move the way music's do. */
    public static float[] tune(long seed, int seconds) {
        return tunes.computeIfAbsent(
                seed * 1000 + seconds,
                k -> {
                    float[] out = new float[seconds * RATE];
                    Random random = new Random(seed);
                    double beat = 0.25;
                    for (double start = 0; start < seconds; start += beat) {
                        double freq = 110 * Math.pow(2, random.nextInt(36) / 12.0);
                        double length = beat * (1 + random.nextInt(3));
                        int from = (int) (start * RATE),
                                to = Math.min(out.length, (int) ((start + length) * RATE));
                        for (int i = from; i < to; i++) {
                            double t = (i - from) / (double) RATE;
                            double envelope = Math.exp(-3 * t), v = 0;
                            for (int h = 1; h <= 4; h++)
                                v += Math.sin(2 * Math.PI * freq * h * t) / h;
                            out[i] += (float) (0.2 * envelope * v);
                        }
                    }
                    return out;
                });
    }
}
