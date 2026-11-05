package app.bitchord.telegram;

import com.music.bitchord.playback.smart.AudioDecoder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stands in for the native library over the synthetic test files. Every read, seek and wait goes
 * through the real {@link SourceAudio.Input}, and asks its {@code stopped()} first, as the
 * library's input callbacks do. The library's own timing needs real files and a device.
 */
public class SyntheticDecoder implements SourceAudio.Backend {
    public static final AtomicInteger opened = new AtomicInteger(),
            closed = new AtomicInteger(),
            misuse = new AtomicInteger();

    /** What the next files report as the way their length was found. */
    public static volatile long durationMethod;

    /** When set, the frame with this index reports no usable time. */
    public static volatile int silentFrame = -1;

    /** When set, the frame with this index goes back in time. */
    public static volatile int backwardFrame = -1;

    /** When set, the frame with this index changes the stream's layout. */
    public static volatile int malformedFrame = -1;

    /** When not Long.MIN_VALUE, the length in microseconds the next files report. */
    public static volatile long durationOverride = Long.MIN_VALUE;

    /** When set, the frame with this index reports the missing-time marker as its time. */
    public static volatile int sentinelFrame = -1;

    /** When set, the frame with this index repeats the previous frame's time. */
    public static volatile int repeatFrame = -1;

    /** When set, the next files report frame times that are not on the playback clock. */
    public static volatile boolean unqualified;

    /** When set, the format report lacks the clock flag, as an older library's would. */
    public static volatile boolean shortInfo;

    /** When set, a seek lands at the start of the file whatever time it asks for. */
    public static volatile boolean seekFromStart;

    /** When set, the frame with this index is timed at the end of the representable range. */
    public static volatile int lateFrame = -1;

    /** When set, the frame with this index is larger than any frame may be. */
    public static volatile int hugeFrame = -1;

    /** While held, reading the format waits, noticing a stop at its next input callback. */
    public static volatile CountDownLatch infoStall;

    private static final ThreadLocal<Long> seeds = new ThreadLocal<>();
    private static final AtomicLong handles = new AtomicLong(1);

    private final Map<Long, State> states = new ConcurrentHashMap<>();

    private static final class State {
        final SourceAudio.Input input;
        final Thread owner = Thread.currentThread();
        long seed, middle;
        int seconds, lead, frames;
        boolean walks;
        double at;
        long time;
        boolean closed;

        State(SourceAudio.Input input) {
            this.input = input;
        }
    }

    public static void install() {
        if (!(SourceAudio.decoder instanceof SyntheticDecoder))
            SourceAudio.decoder = new SyntheticDecoder();
    }

    public static void reset() {
        opened.set(0);
        closed.set(0);
        misuse.set(0);
        durationMethod = 0;
        seekFromStart = false;
        unqualified = shortInfo = false;
        silentFrame =
                lateFrame =
                        backwardFrame =
                                malformedFrame = sentinelFrame = repeatFrame = hugeFrame = -1;
        infoStall = null;
        durationOverride = Long.MIN_VALUE;
    }

    /** The recording named by the last file this thread opened. */
    public static long seed() {
        Long seed = seeds.get();
        return seed == null ? 0 : seed;
    }

    private State state(long handle) {
        State state = states.get(handle);
        if (state == null || state.closed || state.owner != Thread.currentThread()) {
            misuse.incrementAndGet();
            throw new IllegalStateException("Handle used out of turn");
        }
        return state;
    }

    private static int read(State state, long at, byte[] into) throws IOException {
        if (state.input.stopped()) throw new IOException("Audio analysis stopped");
        state.input.seek(at, 0);
        return state.input.read(into, 0, into.length);
    }

    @Override
    public long open(Object input) throws IOException {
        State state = new State((SourceAudio.Input) input);
        byte[] bytes = new byte[2048];
        int got = read(state, 0, bytes);
        if (got < AudioDecoder.HEADER) throw new IOException("Not audio");
        ByteBuffer header = ByteBuffer.wrap(bytes);
        state.seed = header.getLong(0);
        state.middle = header.getLong(8);
        state.seconds = header.getInt(16);
        state.lead = header.getInt(20);
        state.walks = header.get(24) == 1;
        seeds.set(state.seed);
        long handle = handles.getAndIncrement();
        states.put(handle, state);
        opened.incrementAndGet();
        return handle;
    }

    @Override
    public long[] info(long handle) throws IOException {
        State state = state(handle);
        CountDownLatch hold = infoStall;
        if (hold != null) holdFor(state, hold);
        long[] values = {
            AudioDecoder.RATE,
            1,
            16,
            durationOverride != Long.MIN_VALUE ? durationOverride : state.seconds * 1_000_000L,
            durationMethod,
            unqualified ? 0 : 1
        };
        return shortInfo ? java.util.Arrays.copyOf(values, 5) : values;
    }

    @Override
    public String codec(long handle) {
        state(handle);
        return "pcm_s16le";
    }

    @Override
    public void seek(long handle, long timeUs) throws IOException {
        State state = state(handle);
        double start = timeUs / 1e6;
        CountDownLatch stall = AudioDecoder.seekStall;
        if (stall != null && timeUs > 0) {
            AudioDecoder.seeksEntered.incrementAndGet();
            holdFor(state, stall);
        }
        long target = AudioDecoder.HEADER + (long) (start * AudioDecoder.BYTES_PER_SECOND);
        byte[] bytes = new byte[2048];
        if (state.walks && timeUs > 0)
            for (long at = AudioDecoder.HEADER; at < target; at += 2100)
                if (read(state, at, bytes) <= 0) throw new IOException("Seek past the end");
        state.at = seekFromStart ? 0 : start;
        state.frames = 0;
    }

    /** The library notices a stop at its next input callback. */
    private static void holdFor(State state, CountDownLatch latch) throws IOException {
        while (true) {
            try {
                if (latch.await(20, TimeUnit.MILLISECONDS)) return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Audio analysis stopped");
            }
            if (state.input.stopped()) throw new IOException("Audio analysis stopped");
        }
    }

    @Override
    public byte[] next(long handle) throws IOException {
        State state = state(handle);
        if (state.input.stopped()) throw new IOException("Audio analysis stopped");
        if (state.at >= state.seconds) return null;
        long at = AudioDecoder.HEADER + (long) (state.at * AudioDecoder.BYTES_PER_SECOND);
        byte[] bytes = new byte[2048];
        for (int part = 0; part < AudioDecoder.BYTES_PER_SECOND; ) {
            int got = read(state, at + part, bytes);
            if (got <= 0) {
                if (part == 0) return null;
                throw new IOException("Audio ended inside a frame");
            }
            for (int i = 0; i < got; i++)
                if (bytes[i] != AudioDecoder.pattern(at + part + i)) AudioDecoder.corrupt = true;
            part += got;
        }
        float[] whole = AudioDecoder.tune(state.seed, state.seconds),
                other = state.middle == 0 ? null : AudioDecoder.tune(state.middle, state.seconds);
        double t = state.at;
        int from = (int) ((t - state.lead / 1000.0) * AudioDecoder.RATE);
        float[] song =
                other != null && t > state.seconds / 3.0 && t < state.seconds * 2 / 3.0
                        ? other
                        : whole;
        ByteBuffer pcm = ByteBuffer.allocate(AudioDecoder.RATE * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < AudioDecoder.RATE; i++) {
            int index = from + i;
            float v = index >= 0 && index < song.length ? song[index] : 0;
            pcm.putShort((short) Math.max(-32768, Math.min(32767, v * 32767)));
        }
        long time = (long) (t * 1e6);
        int frame = state.frames++;
        if (frame == backwardFrame) time -= 5_000_000L;
        if (frame == repeatFrame) time -= 1_000_000L;
        if (frame == sentinelFrame) time = Long.MIN_VALUE;
        if (frame == lateFrame) time = Long.MAX_VALUE - 1;
        // The library refuses a frame without a timestamp instead of inventing one.
        if (frame == silentFrame) throw new IOException("Audio frame timestamp");
        state.time = time;
        state.at += 1;
        byte[] out = pcm.array();
        if (frame == hugeFrame) return new byte[(1 << 21) + 2];
        return frame == malformedFrame ? java.util.Arrays.copyOf(out, out.length - 1) : out;
    }

    @Override
    public long time(long handle) {
        return state(handle).time;
    }

    @Override
    public void close(long handle) {
        State state = states.remove(handle);
        if (state == null || state.closed) {
            misuse.incrementAndGet();
            return;
        }
        state.closed = true;
        closed.incrementAndGet();
    }
}
