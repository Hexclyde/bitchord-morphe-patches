package app.bitchord.telegram;

import androidx.media3.common.Format;
import androidx.media3.common.PlaybackParameters;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Sing's delay on its way out of BitChord's sink. Each frame leaves with the time of the frame it
 * really is, so the first one heard is the stream's first and the position the player shows is what
 * can be heard. At the end of a stream, or before the format changes, what the delay still holds
 * goes out in small pieces through the sink's own output buffer, so a full audio track holds it up
 * the same way it holds up anything else.
 */
public final class SingOutput {
    private SingOutput() {}

    static final int CHUNK_FRAMES = 1024;
    private static final long TIME_UNSET = Long.MIN_VALUE + 1;

    private static final class Stream {
        final WeakReference<Object> sink;
        int rate, blockFrames;
        long shiftUs, inEndUs = TIME_UNSET;
        boolean blockNew, leadStale, ended, jumped;
        Object pendingConfig;

        Stream(Object sink) {
            this.sink = new WeakReference<>(sink);
        }
    }

    private static final Map<Object, Stream> bySink =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Stream> byDelegate =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Boolean> applying = new ThreadLocal<>();

    /**
     * What's read from the sink, once for its class. BitChord's names are checked at patch time.
     */
    private static final class Port {
        final Field delegate, block, output, pendingTime, processedEnd, sampleCount, samples;
        final Method emit, retry, parameters;

        Port(Class<?> sink, Class<?> blockType) throws ReflectiveOperationException {
            delegate = field(sink, "delegate");
            block = field(sink, "audioBlock");
            output = field(sink, "outputByteBuffer");
            pendingTime = field(sink, "pendingPresentationTimeUs");
            processedEnd = field(sink, "processedEndUs");
            sampleCount = field(blockType, "terminalBitCount");
            samples = field(blockType, "children");
            emit = sink.getMethod("bitchordSingEmit", long.class);
            retry = sink.getMethod("bitchordSingRetry");
            parameters = parameters(delegate.getType());
        }
    }

    /** Null if it's missing: that only takes this stream off the model, see playsNormally. */
    private static Method parameters(Class<?> delegate) {
        try {
            Method method = delegate.getMethod("getPlaybackParameters");
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | RuntimeException unknown) {
            return null;
        }
    }

    private static volatile Port port;

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Port port(Object sink) throws ReflectiveOperationException {
        Port known = port;
        if (known != null) return known;
        Field block = field(sink.getClass(), "audioBlock");
        known = new Port(sink.getClass(), block.get(sink).getClass());
        port = known;
        return known;
    }

    private static long durationUs(long frames, int rate) {
        return rate <= 0 ? 0 : frames * 1_000_000L / rate;
    }

    private static volatile boolean parametersUnknown;

    /**
     * Whether this sink is playing at its own normal speed and pitch, read from the sink itself on
     * the thread that sets them. Anything else, or not being able to tell, means the filter for
     * this stream alone.
     */
    static boolean playsNormally(Object sink) {
        try {
            Port known = port(sink);
            PlaybackParameters now =
                    (PlaybackParameters) known.parameters.invoke(known.delegate.get(sink));
            return Math.abs(now.speed - 1) < 1e-5 && Math.abs(now.pitch - 1) < 1e-5;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unknown) {
            if (!parametersUnknown) {
                parametersUnknown = true;
                HostLog.d("Sing can't read this sink's speed and pitch: " + unknown);
            }
            return false;
        }
    }

    // ── The block just processed ────────────────────────────────────────────

    /** A block has been through the engine; {@code consumedBefore} is its input before this one. */
    static void block(
            Object sink, SeparatedSing engine, int frames, long consumedBefore, int rate) {
        Stream stream = bySink.get(sink);
        if (stream == null) {
            try {
                Object delegate = port(sink).delegate.get(sink);
                stream = new Stream(sink);
                bySink.put(sink, stream);
                byDelegate.put(delegate, stream);
            } catch (ReflectiveOperationException | RuntimeException unknown) {
                HostLog.d("Sing can't follow this audio sink: " + unknown);
                return;
            }
        }
        stream.rate = rate;
        stream.blockFrames = frames;
        stream.blockNew = true;
        stream.leadStale = true;
        stream.ended = false;
        long shift = durationUs(Math.min(engine.latencySamples(), consumedBefore), rate);
        // Less held back than last time means audio was let go, not played: a jump, on purpose.
        if (shift < stream.shiftUs) stream.jumped = true;
        stream.shiftUs = shift;
    }

    /** A block went past the engine, so whatever the delay held is gone and its time with it. */
    static void bypassed(Object sink) {
        if (bySink.isEmpty()) return;
        Stream stream = bySink.get(sink);
        if (stream == null || stream.shiftUs == 0) return;
        stream.shiftUs = 0;
        stream.jumped = true;
    }

    // ── Every write to the delegate, from the sink's own code ───────────────

    /** The time a buffer really starts at, given the time of the input that produced it. */
    public static long outputTimeUs(Object delegate, long timeUs, ByteBuffer buffer) {
        if (byDelegate.isEmpty() || timeUs == TIME_UNSET) return timeUs;
        Stream stream = byDelegate.get(delegate);
        if (stream == null) return timeUs;
        if (stream.blockNew) {
            stream.blockNew = false;
            stream.inEndUs = timeUs + durationUs(stream.blockFrames, stream.rate);
        }
        if (stream.leadStale) {
            stream.leadStale = false;
            Object sink = stream.sink.get();
            try {
                // How far ahead of the speaker the chain is, which the delay isn't part of: what
                // reaches the chain's later stages is already the delayed audio.
                if (sink != null) {
                    Field end = port(sink).processedEnd;
                    end.setLong(sink, end.getLong(sink) - stream.shiftUs);
                }
            } catch (ReflectiveOperationException | RuntimeException unknown) {
                HostLog.d("Sing can't correct the chain's lead: " + unknown);
            }
        }
        if (stream.jumped) {
            stream.jumped = false;
            try {
                // Told in advance, the delegate takes the new time without reporting an error.
                delegate.getClass().getMethod("handleDiscontinuity").invoke(delegate);
            } catch (ReflectiveOperationException | RuntimeException unknown) {
                HostLog.d("Sing can't announce a jump: " + unknown);
            }
        }
        return timeUs - stream.shiftUs;
    }

    /** A block that held nothing to play: the delegate shouldn't start its clock from it. */
    public static boolean skipsWrite(Object delegate, ByteBuffer buffer) {
        return !buffer.hasRemaining() && !byDelegate.isEmpty() && byDelegate.containsKey(delegate);
    }

    // ── The end of a stream ─────────────────────────────────────────────────

    /** True while the sink shouldn't go on to tell its delegate that this is the end. */
    public static boolean playToEnd(Object sink) {
        Stream stream = bySink.isEmpty() ? null : bySink.get(sink);
        SeparatedSing engine = stream == null ? null : SeparationModel.engine(sink);
        if (engine == null) return false;
        if (!stream.ended) {
            stream.ended = true;
            engine.end();
        }
        return pump(sink, stream, engine);
    }

    /**
     * Whether the stream has ended with audio still to come out. {@link #pump} only ever stops with
     * a piece in the sink's output buffer, which already keeps the sink from finishing.
     */
    static boolean holds(Object sink) {
        Stream stream = bySink.isEmpty() ? null : bySink.get(sink);
        if (stream == null || !stream.ended) return false;
        SeparatedSing engine = SeparationModel.engine(sink);
        return engine != null && engine.hasTail();
    }

    /** True while what's left has to wait for the delegate. */
    private static boolean pump(Object sink, Stream stream, SeparatedSing engine) {
        Port known;
        Object block;
        ByteBuffer output;
        float[] samples;
        try {
            known = port(sink);
            block = known.block.get(sink);
            output = (ByteBuffer) known.output.get(sink);
            samples = (float[]) known.samples.get(block);
        } catch (ReflectiveOperationException | RuntimeException unknown) {
            HostLog.d("Sing can't reach the sink's buffers: " + unknown);
            engine.reset();
            return false;
        }
        float level = SoundExtras.singLevel / 100f;
        try {
            while (true) {
                if (output.hasRemaining()) {
                    known.retry.invoke(sink);
                    if (output.hasRemaining()) return true;
                }
                if (!engine.hasTail()) return false;
                if (level <= 0) {
                    engine.reset();
                    return false;
                }
                long consumedBefore = engine.inputFrames();
                int frames = engine.drain(samples, CHUNK_FRAMES, level);
                int input = engine.lastDrainInput();
                stream.blockFrames = input;
                stream.blockNew = true;
                stream.shiftUs =
                        durationUs(Math.min(engine.latencySamples(), consumedBefore), stream.rate);
                long timeUs = stream.inEndUs;
                if (frames == 0) {
                    stream.blockNew = false;
                    if (timeUs != TIME_UNSET)
                        stream.inEndUs = timeUs + durationUs(input, stream.rate);
                    continue;
                }
                known.sampleCount.setInt(block, frames);
                SoundExtras.afterSing(sink, samples, frames * 2, 2, stream.rate);
                known.emit.invoke(sink, timeUs);
            }
        } catch (ReflectiveOperationException | RuntimeException unknown) {
            HostLog.d("Sing couldn't play out the end of a stream: " + unknown);
            engine.reset();
            return false;
        }
    }

    // ── A change of format ──────────────────────────────────────────────────

    /**
     * The sink is being moved to another format while the delay holds audio in the old one. Android
     * can't take that audio afterwards, so the change waits, the way the delegate itself holds a
     * new format back until what it already has has been played. True when it's been held back.
     */
    public static boolean configure(Object sink, Object config) {
        if (bySink.isEmpty() || applying.get() != null) return false;
        Stream stream = bySink.get(sink);
        SeparatedSing engine = stream == null ? null : SeparationModel.engine(sink);
        if (engine == null || engine.inputFrames() == 0) return false;
        Format next;
        try {
            next = (Format) config.getClass().getField("format").get(config);
        } catch (ReflectiveOperationException | RuntimeException unknown) {
            HostLog.d("Sing can't read the sink's new format: " + unknown);
            return false;
        }
        if (next.sampleRate == engine.sampleRate() && next.channelCount == 2) return false;
        return holdFormat(stream, engine, config);
    }

    private static boolean holdFormat(Stream stream, SeparatedSing engine, Object config) {
        stream.pendingConfig = config;
        if (!stream.ended) {
            stream.ended = true;
            engine.end();
        }
        return true;
    }

    /** For tests, which can't make a sink's configuration. */
    static boolean holdFormatForTest(Object sink, Object config) {
        Stream stream = bySink.get(sink);
        SeparatedSing engine = SeparationModel.engine(sink);
        return stream != null && engine != null && holdFormat(stream, engine, config);
    }

    /** At the start of each input buffer. True while it has to wait for the old format's end. */
    public static boolean holdsInput(Object sink) {
        Stream stream = bySink.isEmpty() ? null : bySink.get(sink);
        if (stream == null || stream.pendingConfig == null) return false;
        SeparatedSing engine = SeparationModel.engine(sink);
        if (engine != null && pump(sink, stream, engine)) return true;
        applyFormat(sink, stream);
        return false;
    }

    private static void applyFormat(Object sink, Stream stream) {
        Object config = stream.pendingConfig;
        stream.pendingConfig = null;
        SeparationModel.flush(sink);
        forget(sink, stream);
        applying.set(true);
        try {
            for (Method method : sink.getClass().getMethods())
                if (method.getName().equals("configure")
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isInstance(config)) {
                    method.invoke(sink, config);
                    return;
                }
            HostLog.d("Sing couldn't find the sink's format change");
        } catch (ReflectiveOperationException | RuntimeException failed) {
            HostLog.d("Sing couldn't apply a format change: " + failed);
        } finally {
            applying.remove();
        }
    }

    private static void forget(Object sink, Stream stream) {
        bySink.remove(sink);
        synchronized (byDelegate) {
            byDelegate.values().remove(stream);
        }
    }

    /** The sink was flushed: its audio is gone, but a format it was told about still stands. */
    static void flushed(Object sink) {
        Stream stream = bySink.isEmpty() ? null : bySink.get(sink);
        if (stream == null) return;
        if (stream.pendingConfig != null) applyFormat(sink, stream);
        else forget(sink, stream);
    }

    /** The sink was reset: nothing about it stands. */
    static void wasReset(Object sink) {
        Stream stream = bySink.isEmpty() ? null : bySink.get(sink);
        if (stream != null) forget(sink, stream);
    }

    /** The output side moved to the next song in the same stream. */
    public static void streamBoundary(Object sink) {
        SeparatedSing engine = SeparationModel.engine(sink);
        if (engine != null) engine.newTrack();
    }

    // ── The 16-bit path, in BitChord's equaliser ────────────────────────────

    private static final class Pcm {
        final float[] samples = new float[CHUNK_FRAMES * 2];
        final ByteBuffer bytes =
                ByteBuffer.allocateDirect(CHUNK_FRAMES * 4).order(ByteOrder.nativeOrder());
    }

    private static final Map<Object, Pcm> pcm = Collections.synchronizedMap(new WeakHashMap<>());

    /** The equaliser's input has ended. */
    public static void equaliserEnd(Object equaliser) {
        SeparatedSing engine = SeparationModel.engine(equaliser);
        if (engine != null) engine.end();
    }

    /** True while the equaliser isn't finished, though its own buffer is empty. */
    public static boolean equaliserHolds(Object equaliser) {
        SeparatedSing engine = SeparationModel.engine(equaliser);
        return engine != null && engine.ended() && engine.hasTail();
    }

    /** What the equaliser hands on when it has nothing of its own: the next piece of the tail. */
    public static ByteBuffer equaliserOutput(Object equaliser, ByteBuffer empty) {
        SeparatedSing engine = SeparationModel.engine(equaliser);
        if (engine == null || !engine.ended() || !engine.hasTail()) return empty;
        Pcm scratch = pcm.get(equaliser);
        if (scratch == null) pcm.put(equaliser, scratch = new Pcm());
        float level = SoundExtras.singLevel / 100f;
        int frames = 0;
        while (frames == 0 && engine.hasTail())
            frames = engine.drain(scratch.samples, CHUNK_FRAMES, level);
        if (frames == 0) return empty;
        SoundExtras.afterSing(equaliser, scratch.samples, frames * 2, 2, engine.sampleRate());
        scratch.bytes.clear();
        for (int i = 0; i < frames * 2; i++)
            scratch.bytes.putShort(SoundExtras.toPcm16(scratch.samples[i]));
        scratch.bytes.flip();
        return scratch.bytes;
    }
}
