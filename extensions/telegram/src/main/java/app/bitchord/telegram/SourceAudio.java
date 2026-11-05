package app.bitchord.telegram;

import android.media.MediaCodec;
import android.media.MediaDataSource;
import android.media.MediaFormat;
import android.net.Uri;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import kotlin.Pair;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function4;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Random-access analysis through the player's source router, including native Telegram readers.
 *
 * <p>The decoder reads small pieces and rarely where the last read ended: a Matroska seek that
 * can't use its cues reads the head of every block up to the target. So what's read is kept in
 * blocks, up to {@link #MAX_BLOCKS}, a short gap is read through on the open connection, and only a
 * longer jump asks for a new one. Every read checks for cancellation, the deadline and the byte
 * limit first.
 */
public final class SourceAudio extends MediaDataSource {
    interface AnalysisFormat extends AutoCloseable {
        MediaFormat read(MediaDataSource source) throws IOException;

        void close();
    }

    static Supplier<AnalysisFormat> analysisFormats =
            () ->
                    new AnalysisFormat() {
                        public MediaFormat read(MediaDataSource source) throws IOException {
                            Info info = probe(source);
                            if (info == null) return null;
                            MediaFormat format =
                                    MediaFormat.createAudioFormat(
                                            "audio/" + info.codec, info.rate, info.channels);
                            // A length guessed from the bitrate is not a length.
                            if (info.exactDuration())
                                format.setLong(MediaFormat.KEY_DURATION, info.us);
                            return format;
                        }

                        public void close() {}
                    };

    /** Playback buffers may grow after analysis starts, so their share stays reserved. */
    static long analysisAvailable(long maximum, long used) {
        if (maximum <= 0 || used < 0 || used >= maximum) return 0;
        long available = maximum - used - pendingJava(null);
        return Math.max(0, available - maximum / 2);
    }

    static final class JavaMemory implements AutoCloseable {
        private static long pending;
        final long bytes;
        private boolean closed;

        private JavaMemory(long bytes) {
            this.bytes = bytes;
            pending += bytes;
        }

        @Override
        public void close() {
            synchronized (JavaMemory.class) {
                if (closed) return;
                closed = true;
                pending -= bytes;
            }
        }
    }

    private static long pendingJava(JavaMemory own) {
        synchronized (JavaMemory.class) {
            return JavaMemory.pending - (own == null || own.closed ? 0 : own.bytes);
        }
    }

    static JavaMemory reserveJava(long bytes) {
        Runtime runtime = Runtime.getRuntime();
        return reserveJava(
                runtime.maxMemory(), runtime.totalMemory() - runtime.freeMemory(), bytes);
    }

    static AutoCloseable holdJavaResident() {
        return SteadyPlayback.holdResident();
    }

    private static void checkMemory() throws Stopped {
        if (!SteadyPlayback.residentActive()) return;
        Runtime runtime = Runtime.getRuntime();
        if (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()) >= (16L << 20))
            return;
        for (Decoding current = leased.get(); current != null; current = current.outer)
            current.memoryStopped = true;
        throw new Stopped(StopReason.MEMORY);
    }

    static JavaMemory reserveJava(long maximum, long used, long bytes) {
        synchronized (JavaMemory.class) {
            if (maximum <= 0 || used < 0 || used >= maximum || bytes <= 0) return null;
            // Playback loading and the visible screen can still grow during an allocation.
            long margin = Math.max(8L << 20, Math.min(24L << 20, maximum / 8));
            long free = maximum - used - margin;
            if (bytes > free || JavaMemory.pending > free - bytes) return null;
            return new JavaMemory(bytes);
        }
    }

    static final class AnalysisInfo {
        final int sampleRate;
        final double seconds;

        AnalysisInfo(int sampleRate, double seconds) {
            this.sampleRate = sampleRate;
            this.seconds = seconds;
        }
    }

    /** Inspect the pinned source without taking ownership of its reader. */
    static AnalysisInfo analysisInfo(MediaDataSource source, double requestedSeconds)
            throws IOException {
        if (source == null || !Double.isFinite(requestedSeconds))
            throw new IOException("Selected audio duration or source is unknown");
        try (AnalysisFormat probe = analysisFormats.get()) {
            MediaFormat format = probe.read(source);
            if (format == null || !format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                throw new IOException("Selected audio sample rate is unknown");
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null || !mime.startsWith("audio/"))
                throw new IOException("Selected audio format is unknown");
            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            double seconds = Math.max(0, requestedSeconds);
            if (format.containsKey(MediaFormat.KEY_DURATION))
                seconds = Math.max(seconds, format.getLong(MediaFormat.KEY_DURATION) / 1_000_000d);
            if (rate <= 0 || !Double.isFinite(seconds) || seconds <= 0)
                throw new IOException("Selected audio duration or sample rate is unusable");
            return new AnalysisInfo(rate, seconds);
        } catch (RuntimeException | LinkageError unavailable) {
            throw new IOException("Selected audio format could not be inspected", unavailable);
        }
    }

    private static final long DSP_MARGIN = 8L << 20, STOCK_PLAYER_RESERVE = 141L << 20;

    private static long dspAvailable(long maximum, long used, long growth) {
        return dspAvailable(maximum, used, growth, null);
    }

    private static long dspAvailable(long maximum, long used, long growth, JavaMemory own) {
        if (maximum <= 0 || used < 0 || used >= maximum) return 0;
        if (growth < 0) growth = Math.max(maximum / 2, STOCK_PLAYER_RESERVE);
        long free = maximum - used - pendingJava(own);
        if (growth >= free) return 0;
        return Math.max(0, free - growth - DSP_MARGIN);
    }

    static long dspAvailable(long maximum, long used) {
        long growth = -1;
        try {
            growth = SteadyPlayback.analysisGrowth(maximum);
        } catch (RuntimeException | LinkageError unavailable) {
            // Independent patch selections retain the ordinary player's full possible growth.
        }
        return dspAvailable(maximum, used, growth);
    }

    /** A relaxed estimate is valid only while the native loading ceiling stays pinned. */
    static final class DspMemory implements AutoCloseable {
        final long available;
        private final long maximum;
        private final AutoCloseable lease;
        private final LongSupplier growth;
        private final BooleanSupplier stopped;
        private JavaMemory allocation;
        private volatile boolean closed;

        private DspMemory(
                long maximum,
                long used,
                AutoCloseable lease,
                LongSupplier growth,
                BooleanSupplier stopped) {
            this.maximum = maximum;
            this.lease = lease;
            this.growth = growth;
            this.stopped = stopped;
            available = available(maximum, used);
        }

        long available() {
            return available;
        }

        long available(long currentMaximum, long used) {
            if (closed || currentMaximum != maximum || cancelled()) return 0;
            try {
                long future = growth.getAsLong();
                if (cancelled() || lease != null && future < 0) return 0;
                return dspAvailable(maximum, used, future, allocation);
            } catch (RuntimeException | LinkageError unavailable) {
                return 0;
            }
        }

        boolean cancelled() {
            if (closed) return true;
            try {
                return stopped.getAsBoolean();
            } catch (RuntimeException | LinkageError unavailable) {
                return true;
            }
        }

        synchronized boolean claim(long bytes, long currentMaximum, long used) {
            if (closed || currentMaximum != maximum || cancelled()) return false;
            if (allocation != null) return bytes > 0 && bytes <= allocation.bytes;
            if (bytes <= 0 || bytes > available(currentMaximum, used)) return false;
            allocation = reserveJava(currentMaximum, used, bytes);
            return allocation != null;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            if (allocation != null) allocation.close();
            if (lease != null)
                try {
                    lease.close();
                } catch (Exception | LinkageError unavailable) {
                    HostLog.d("AutoMix memory allowance could not be released");
                }
        }
    }

    static DspMemory dspMemory(long maximum, long used) {
        try {
            SteadyPlayback.AnalysisLease lease = SteadyPlayback.analysisLease(maximum);
            if (lease != null) {
                try {
                    return new DspMemory(maximum, used, lease, lease, lease);
                } catch (RuntimeException | Error failure) {
                    lease.close();
                    throw failure;
                }
            }
        } catch (RuntimeException | LinkageError unavailable) {
            // The conservative calculation needs no optional runtime or loading hooks.
        }
        return new DspMemory(maximum, used, null, () -> -1, () -> false);
    }

    /** Peak live Java PCM, with room for DSP reduction, model tensors and read overhead. */
    static long analysisBytes(int sampleRate, double seconds) {
        if (sampleRate <= 0 || !Double.isFinite(seconds) || seconds <= 0) return -1;
        // decodeRegion keeps chunks while flattening; structure then resamples to 11.025 kHz.
        double structure = sampleRate * seconds * 8 + 11_025d * seconds * 4;
        // Stereo chunks + flattened channels + mono, then the bounded model tensor copies.
        double region = sampleRate * Math.min(seconds, 30) * 20 + (64L << 20);
        double required = Math.max(structure, region) + (16L << 20);
        return !Double.isFinite(required) || required >= Long.MAX_VALUE
                ? -1
                : (long) Math.ceil(required);
    }

    static String analysisLimit(MediaDataSource source, double requestedSeconds, long available) {
        if (available <= (16L << 20)) return "not enough Java heap for analysis";
        if (source == null) return "selected audio cannot be inspected";
        if (!Double.isFinite(requestedSeconds)) return "selected audio duration is unknown";
        try (AnalysisFormat probe = analysisFormats.get()) {
            MediaFormat format = probe.read(source);
            if (format == null || !format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                return "selected audio sample rate is unknown";
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null || !mime.startsWith("audio/"))
                return "selected audio format is unknown";
            double seconds = Math.max(0, requestedSeconds);
            if (format.containsKey(MediaFormat.KEY_DURATION))
                seconds = Math.max(seconds, format.getLong(MediaFormat.KEY_DURATION) / 1_000_000d);
            long required = analysisBytes(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), seconds);
            if (required < 0) return "selected audio duration or sample rate is unusable";
            return required <= available
                    ? null
                    : "not enough Java heap for selected audio (estimated "
                            + required / (1 << 20)
                            + " MiB, available "
                            + available / (1 << 20)
                            + " MiB)";
        } catch (IOException | RuntimeException | LinkageError unavailable) {
            return "selected audio format could not be inspected";
        }
    }

    private static final ReentrantLock decoding = new ReentrantLock(true);

    /** The innermost lease held by this thread, honoured by any decode it starts. */
    private static final ThreadLocal<Decoding> leased = new ThreadLocal<>();

    /** An analysis that has no job of its own may wait this long for the shared permit. */
    private static final long ADMISSION = TimeUnit.MINUTES.toNanos(10);

    /** Longest a decode may go without a read, seek or frame completing. */
    static long idleNanos = TimeUnit.SECONDS.toNanos(60);

    /** Longest a format probe may take, permit wait included. */
    static long probeNanos = TimeUnit.SECONDS.toNanos(15);

    /** One background PCM working set at a time, including nested verification on this thread. */
    static final class Decoding implements AutoCloseable {
        private long deadline;
        private final BooleanSupplier cancelled;
        private final boolean host;
        private Decoding outer;
        private boolean released;
        volatile boolean memoryStopped;

        private Decoding(long deadline, BooleanSupplier cancelled, boolean host) {
            this.deadline = deadline;
            this.cancelled = cancelled;
            this.host = host;
        }

        void check() throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new Stopped(StopReason.INTERRUPTED);
            if (host && AutomixAccess.stopped()) throw new Stopped(StopReason.PLAYBACK_STOPPED);
            if (cancelled.getAsBoolean()) throw new Stopped(StopReason.CANCELLED);
            if (System.nanoTime() >= deadline) throw new Stopped(StopReason.DEADLINE);
            checkMemory();
        }

        @Override
        public void close() {
            if (!released) {
                released = true;
                if (leased.get() == this) {
                    if (outer == null) leased.remove();
                    else leased.set(outer);
                }
                decoding.unlock();
            }
        }
    }

    /** Throws why this thread's lease has ended, if it has. A stopped decode only returns null. */
    static void checkLeased() throws IOException {
        Decoding lease = leased.get();
        if (lease != null) lease.check();
    }

    static Decoding decode(long deadline, BooleanSupplier cancelled) throws IOException {
        return admit(new Decoding(deadline, cancelled, true));
    }

    private static Decoding admit(Decoding permit) throws IOException {
        while (true) {
            permit.check();
            try {
                if (decoding.tryLock(
                        Math.min(
                                permit.deadline - System.nanoTime(),
                                TimeUnit.MILLISECONDS.toNanos(50)),
                        TimeUnit.NANOSECONDS)) {
                    boolean admitted = false;
                    try {
                        permit.check();
                        permit.outer = leased.get();
                        leased.set(permit);
                        admitted = true;
                        return permit;
                    } finally {
                        if (!admitted) decoding.unlock();
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new Stopped(StopReason.INTERRUPTED);
            }
        }
    }

    /**
     * Cached analysis must still stop when its job or deadline ends, without another byte cache.
     */
    static MediaDataSource bounded(
            MediaDataSource source, long deadline, BooleanSupplier cancelled) {
        return new MediaDataSource() {
            private boolean closed;

            private void check() throws IOException {
                if (closed) throw new Stopped(StopReason.CLOSED);
                if (Thread.currentThread().isInterrupted())
                    throw new Stopped(StopReason.INTERRUPTED);
                if (AutomixAccess.stopped()) throw new Stopped(StopReason.PLAYBACK_STOPPED);
                if (cancelled.getAsBoolean()) throw new Stopped(StopReason.CANCELLED);
                if (System.nanoTime() >= deadline) throw new Stopped(StopReason.DEADLINE);
                checkMemory();
            }

            @Override
            public synchronized int readAt(long at, byte[] bytes, int offset, int length)
                    throws IOException {
                check();
                return source.readAt(at, bytes, offset, length);
            }

            @Override
            public synchronized long getSize() throws IOException {
                check();
                return source.getSize();
            }

            @Override
            public synchronized void close() throws IOException {
                if (closed) return;
                closed = true;
                source.close();
            }
        };
    }

    /** Analysis ran out of time, was cancelled or closed: not a fault in the audio. */
    enum StopReason {
        UNSPECIFIED,
        CLOSED,
        INTERRUPTED,
        PLAYBACK_STOPPED,
        CANCELLED,
        DEADLINE,
        BYTE_LIMIT,
        READER_RELEASED,
        READ_PERMISSION,
        MEMORY
    }

    static final class Stopped extends IOException {
        final StopReason reason;

        Stopped() {
            this(StopReason.UNSPECIFIED);
        }

        Stopped(StopReason reason) {
            super("Background audio analysis stopped");
            this.reason = reason;
        }
    }

    static final int BLOCK = 64 * 1024, MAX_BLOCKS = 128;

    /** The furthest gap read through rather than asking for a new connection. */
    static final long MAX_SKIP = 512 * 1024;

    private static final long MAX_BYTES = 256L * 1024 * 1024;

    private final DataSpec spec;
    private final long deadline;
    private final BooleanSupplier cancelled;
    private final Supplier<DataSource> sources;
    private final Map<Long, byte[]> blocks = new LinkedHashMap<>(16, 0.75f, true);
    private DataSource source;
    private long size = -1, position = -1, spent;
    private int opens;
    private long sourceNanos;
    private boolean closed;
    private String served;
    private IOException failure;

    SourceAudio(DataSpec spec, long deadline) throws IOException {
        this(spec, deadline, () -> false);
    }

    SourceAudio(DataSpec spec, long deadline, BooleanSupplier cancelled) throws IOException {
        this(spec, deadline, cancelled, AutomixAccess::source);
    }

    private SourceAudio(
            DataSpec spec, long deadline, BooleanSupplier cancelled, Supplier<DataSource> sources)
            throws IOException {
        if (spec == null) throw new IOException("Audio source is not resolved");
        this.spec = spec;
        this.deadline = deadline;
        this.cancelled = cancelled;
        this.sources = sources;
        reopen(0);
    }

    /**
     * A copy already whole in BitChord's cache. Its reader reopens the cache at every jump and has
     * no deadline, so it gets the same blocks and the same checks as anything else.
     */
    static SourceAudio whole(Uri uri, long deadline, BooleanSupplier cancelled) throws IOException {
        return new SourceAudio(new DataSpec(uri), deadline, cancelled, () -> new Cached(uri));
    }

    /** BitChord's cached reader as a source. Only ever asked for the next bytes in order. */
    private static final class Cached implements DataSource {
        private final Uri uri;
        private MediaDataSource media;
        private long position;

        Cached(Uri uri) {
            this.uri = uri;
        }

        @Override
        public void addTransferListener(TransferListener listener) {}

        @Override
        public long open(DataSpec requested) throws IOException {
            media = AutomixAccess.cached(uri);
            if (media == null) throw new IOException("The cached copy has gone");
            position = requested.position;
            long size = media.getSize();
            return size < 0 ? -1 : size - position;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = media.readAt(position, buffer, offset, length);
            if (read <= 0) return -1;
            position += read;
            return read;
        }

        @Override
        public Uri getUri() {
            return uri;
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return Collections.emptyMap();
        }

        @Override
        public void close() throws IOException {
            MediaDataSource open = media;
            media = null;
            if (open != null) open.close();
        }
    }

    private void check() throws IOException {
        if (closed) throw new Stopped(StopReason.CLOSED);
        if (Thread.currentThread().isInterrupted()) throw new Stopped(StopReason.INTERRUPTED);
        if (AutomixAccess.stopped()) throw new Stopped(StopReason.PLAYBACK_STOPPED);
        if (cancelled.getAsBoolean()) throw new Stopped(StopReason.CANCELLED);
        if (System.nanoTime() > deadline) throw new Stopped(StopReason.DEADLINE);
        if (spent > MAX_BYTES) throw new Stopped(StopReason.BYTE_LIMIT);
        checkMemory();
    }

    private void reopen(long at) throws IOException {
        check();
        disconnect();
        source = sources.get();
        if (source == null) throw new IOException("Playback source router is not ready");
        opens++;
        try {
            long remaining = source.open(at == 0 ? spec : spec.subrange(at));
            Uri actual = source.getUri();
            String next = actual == null ? spec.uri.toString() : actual.toString();
            if (served != null && !Objects.equals(served, next))
                throw new IOException("Audio rendition changed during analysis");
            served = next;
            if (remaining >= 0 && size < 0) size = at + remaining;
            position = at;
        } catch (IOException | RuntimeException error) {
            disconnect();
            throw error;
        }
    }

    private void disconnect() {
        DataSource open = source;
        source = null;
        if (open == null) return;
        try {
            open.close();
        } catch (Exception ignored) {
            // What was read is kept; a connection that won't close is of no more use.
        }
    }

    /**
     * Reads one block from the connection, which is at a block's start. After a failure the
     * connection is dropped, so no partly read block is kept and nothing is read out of place.
     */
    private byte[] fill() throws IOException {
        long start = position;
        int wanted = size < 0 ? BLOCK : (int) Math.min(BLOCK, size - start);
        byte[] block = new byte[wanted];
        int got = 0;
        try {
            while (got < wanted) {
                check();
                long began = System.nanoTime();
                int read = source.read(block, got, wanted - got);
                sourceNanos += System.nanoTime() - began;
                if (read < 0) break;
                if (read == 0) throw new IOException("Audio source returned no data");
                got += read;
                position += read;
                spent += read;
            }
        } catch (IOException | RuntimeException error) {
            disconnect();
            throw error;
        }
        if (got < wanted) {
            // The file ends here, whatever was expected.
            size = start + got;
            disconnect();
            block = Arrays.copyOf(block, got);
        } else if (size >= 0 && position >= size) disconnect();
        blocks.put(start / BLOCK, block);
        Iterator<Long> oldest = blocks.keySet().iterator();
        while (blocks.size() > MAX_BLOCKS) {
            oldest.next();
            oldest.remove();
        }
        return block;
    }

    /** The block at {@code index}, reading it unless it's held; null past the end of the file. */
    private byte[] block(long index) throws IOException {
        byte[] held = blocks.get(index);
        if (held != null) return held;
        long start = index * BLOCK;
        if (size >= 0 && start >= size) return null;
        if (source == null || position > start || start - position > MAX_SKIP) reopen(start);
        while (true) {
            long at = position / BLOCK;
            byte[] block = fill();
            if (at == index) return block;
            if (source == null) return null;
        }
    }

    @Override
    public synchronized long getSize() {
        return size;
    }

    @Override
    public synchronized int readAt(long at, byte[] bytes, int offset, int length)
            throws IOException {
        try {
            check();
            if (at < 0 || (size >= 0 && at >= size)) return -1;
            if (length == 0) return 0;
            int total = 0;
            while (total < length) {
                long from = at + total, index = from / BLOCK;
                // What's already here is returned rather than kept waiting for the network.
                if (total > 0 && !blocks.containsKey(index)) break;
                byte[] block = block(index);
                int within = (int) (from - index * BLOCK);
                if (block == null || within >= block.length) break;
                int count = Math.min(length - total, block.length - within);
                System.arraycopy(block, within, bytes, offset + total, count);
                total += count;
            }
            return total > 0 ? total : -1;
        } catch (IOException error) {
            // The native caller swallows this and reads it as the end of the file.
            if (failure == null) failure = error;
            throw error;
        }
    }

    /** Throws the first failed read since the last call: the decoder only saw the file end. */
    synchronized void rethrowFailure() throws IOException {
        IOException failed = failure;
        failure = null;
        if (failed != null) throw failed;
    }

    /** Lets go of the connection while it isn't needed. What was read stays. */
    synchronized void idle() {
        disconnect();
    }

    synchronized long sourceNanos() {
        return sourceNanos;
    }

    synchronized int opens() {
        return opens;
    }

    synchronized long spent() {
        return spent;
    }

    synchronized int heldBlocks() {
        return blocks.size();
    }

    /**
     * This file for one decoder. Decoding closes its source, so each gets a view that closes only
     * itself, and what was read carries over to the next.
     */
    MediaDataSource view() {
        return new MediaDataSource() {
            private volatile boolean released;

            @Override
            public int readAt(long at, byte[] bytes, int offset, int length) throws IOException {
                if (released) throw new Stopped(StopReason.READER_RELEASED);
                return SourceAudio.this.readAt(at, bytes, offset, length);
            }

            @Override
            public long getSize() {
                return SourceAudio.this.getSize();
            }

            @Override
            public void close() {
                released = true;
            }
        };
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        blocks.clear();
        DataSource open = source;
        source = null;
        if (open != null) open.close();
    }

    // ── Decoding through the bundled FFmpeg library ─────────────────────────

    /** The native calls for one open file. */
    interface Backend {
        long open(Object input) throws IOException;

        long[] info(long handle) throws IOException;

        String codec(long handle) throws IOException;

        void seek(long handle, long timeUs) throws IOException;

        /** The next frame as 16-bit interleaved PCM in the file's own channels; null at the end. */
        byte[] next(long handle) throws IOException;

        long time(long handle) throws IOException;

        void close(long handle);
    }

    static Backend decoder = new NativeBackend();

    private static final class NativeBackend implements Backend {
        private static boolean loaded;

        private static synchronized void load() throws IOException {
            if (loaded) return;
            try {
                System.loadLibrary("bitchord_preview");
                loaded = true;
            } catch (LinkageError missing) {
                throw new IOException("The audio decoder is not available", missing);
            }
        }

        @Override
        public long open(Object input) throws IOException {
            load();
            return nativeOpen(input);
        }

        @Override
        public long[] info(long handle) {
            return nativeInfo(handle);
        }

        @Override
        public String codec(long handle) {
            return nativeCodec(handle);
        }

        @Override
        public void seek(long handle, long timeUs) throws IOException {
            nativeSeek(handle, timeUs);
        }

        @Override
        public byte[] next(long handle) throws IOException {
            return nativeNext(handle);
        }

        @Override
        public long time(long handle) {
            return nativeTime(handle);
        }

        @Override
        public void close(long handle) {
            nativeClose(handle);
        }
    }

    private static native long nativeOpen(Object input) throws IOException;

    private static native long[] nativeInfo(long handle);

    private static native String nativeCodec(long handle);

    private static native void nativeSeek(long handle, long timeUs) throws IOException;

    private static native byte[] nativeNext(long handle) throws IOException;

    private static native long nativeTime(long handle);

    private static native void nativeClose(long handle);

    /**
     * A file as the decoder library reads it. Every read and seek first checks for a stop, an
     * interrupt and the idle limit, which each completed read, seek and frame renews.
     */
    static final class Input {
        private final MediaDataSource source;
        private final BooleanSupplier stop;
        private final long idle;
        private volatile long last = System.nanoTime();
        private long position;

        Input(MediaDataSource source, BooleanSupplier stop) {
            this(source, stop, 0);
        }

        Input(MediaDataSource source, BooleanSupplier stop, long idle) {
            this.source = source;
            this.stop = stop;
            this.idle = idle;
        }

        private void check() throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new Stopped(StopReason.INTERRUPTED);
            if (idle > 0 && System.nanoTime() - last > idle) throw new Stopped(StopReason.DEADLINE);
            if (stop.getAsBoolean()) throw new Stopped(StopReason.CANCELLED);
            checkMemory();
        }

        void progress() {
            last = System.nanoTime();
        }

        public int read(byte[] bytes, int offset, int length) throws IOException {
            check();
            int count = source.readAt(position, bytes, offset, length);
            if (count > length) throw new IOException("Audio source returned too much data");
            if (count > 0) position += count;
            progress();
            return count;
        }

        public long seek(long offset, int whence) throws IOException {
            check();
            if ((whence & 0x10000) != 0) return source.getSize();
            int mode = whence & ~0x20000;
            long base;
            if (mode == 0) base = 0;
            else if (mode == 1) base = position;
            else if (mode == 2) {
                base = source.getSize();
                if (base < 0) throw new IOException("Audio length is unknown");
            } else throw new IOException("Invalid audio seek");
            if (offset > Long.MAX_VALUE - base || offset < -base)
                throw new IOException("Invalid audio seek");
            position = base + offset;
            progress();
            return position;
        }

        /** Asked by the library before every read, seek, packet and frame. */
        public boolean stopped() {
            try {
                check();
                return false;
            } catch (IOException | RuntimeException | LinkageError stopped) {
                return true;
            }
        }
    }

    /** What the library reports about a file, before any audio is decoded. */
    static final class Info {
        /** Container durations read from timestamps (0) or from the stream itself (1). */
        static final long FROM_PTS = 0, FROM_STREAM = 1;

        final String codec;
        final int rate, channels;
        final long us, method;

        /** Whether frame times are on the playback clock, so a window can be compared. */
        final boolean qualified;

        Info(String codec, int rate, int channels, long us, long method, boolean qualified) {
            this.qualified = qualified;
            this.codec = codec;
            this.rate = rate;
            this.channels = channels;
            this.us = us;
            this.method = method;
        }

        boolean exactDuration() {
            return qualified && us > 0 && (method == FROM_PTS || method == FROM_STREAM);
        }
    }

    /** The innermost lease's stop, or the caller's own, as one check. */
    private static BooleanSupplier stopOf(Decoding lease, BooleanSupplier own) {
        return () -> {
            if (own != null && own.getAsBoolean()) return true;
            if (lease == null) return false;
            try {
                lease.check();
                return false;
            } catch (IOException stopped) {
                return true;
            }
        };
    }

    private static Info describe(long handle) throws IOException {
        long[] values = decoder.info(handle);
        String codec = decoder.codec(handle);
        if (values == null
                || values.length < 6
                || codec == null
                || values[0] < 1
                || values[0] > 1_536_000
                || values[1] < 1
                || values[1] > 8) throw new IOException("Selected audio format is unknown");
        return new Info(
                codec, (int) values[0], (int) values[1], values[3], values[4], values[5] == 1);
    }

    /**
     * The file's format, or null for no source. Waiting for the permit and reading are limited by
     * {@link #probeNanos}; the source is closed.
     */
    static Info probe(MediaDataSource source) throws IOException {
        if (source == null) return null;
        Decoding own = null;
        try {
            Decoding lease = leased.get();
            if (lease == null)
                lease =
                        own =
                                admit(
                                        new Decoding(
                                                System.nanoTime() + probeNanos,
                                                () -> false,
                                                false));
            long handle = decoder.open(new Input(source, stopOf(lease, null), probeNanos));
            try {
                return describe(handle);
            } finally {
                decoder.close(handle);
            }
        } finally {
            closeQuietly(source);
            if (own != null) own.close();
        }
    }

    /**
     * The container's length in seconds, or null when it is unknown or only guessed from the
     * bitrate. Reads through the decoder library on the calling thread.
     */
    public static Double containerDurationSeconds(MediaDataSource source) {
        try {
            Info info = probe(source);
            return info != null && info.exactDuration() ? info.us / 1_000_000d : null;
        } catch (IOException | RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    /**
     * Decodes from {@code startSeconds} to {@code endSeconds} into 16-bit interleaved PCM, one
     * frame at a time and only while {@code abort} stays false. Returns the sample rate and the
     * real time of the first frame, or null when nothing usable was decoded. It runs on the calling
     * thread under the shared permit and closes the source.
     */
    public static Pair<Double, Double> decodeRaw(
            MediaDataSource source,
            double startSeconds,
            double endSeconds,
            Function0<Boolean> abort,
            Function4<ByteBuffer, MediaCodec.BufferInfo, Integer, Integer, Unit> onBuffer) {
        Decoding own = null;
        try {
            if (source == null
                    || abort == null
                    || onBuffer == null
                    || !(startSeconds >= 0)
                    || !(endSeconds > startSeconds)
                    || endSeconds > 1e9) return null;
            long startUs = (long) (startSeconds * 1_000_000),
                    endUs = (long) (endSeconds * 1_000_000);
            BooleanSupplier requested = () -> Boolean.TRUE.equals(abort.invoke());
            Decoding lease = leased.get();
            if (lease == null) {
                lease = own = admit(new Decoding(System.nanoTime() + ADMISSION, requested, false));
                // Waiting for the permit is limited; the work ends at the idle limit or on abort.
                own.deadline = Long.MAX_VALUE;
            }
            Input input = new Input(source, stopOf(lease, requested), idleNanos);
            long handle = decoder.open(input);
            try {
                Info info = describe(handle);
                if (!info.qualified) return null;
                decoder.seek(handle, startUs);
                MediaCodec.BufferInfo buffer = new MediaCodec.BufferInfo();
                long first = 0, previous = Long.MIN_VALUE;
                boolean decoded = false;
                while (!input.stopped()) {
                    byte[] pcm = decoder.next(handle);
                    if (pcm == null) break;
                    // Nothing is delivered once the work has been stopped.
                    if (input.stopped()) return null;
                    long time = decoder.time(handle);
                    // A frame needs a real time later than the last one, and a bounded size.
                    if (pcm.length == 0
                            || pcm.length % (info.channels * 2) != 0
                            || pcm.length > info.channels * 2L * MAX_FRAME_SAMPLES
                            || time == Long.MIN_VALUE
                            || time <= previous) return null;
                    if (time >= endUs) break;
                    long span = pcm.length / (info.channels * 2L) * 1_000_000L / info.rate;
                    if (time > Long.MAX_VALUE - span) return null;
                    previous = time;
                    // A seek lands on a sync point; frames wholly before the start are skipped.
                    if (time + span <= startUs) {
                        input.progress();
                        continue;
                    }
                    if (!decoded) first = time;
                    decoded = true;
                    previous = time;
                    buffer.set(0, pcm.length, time, 0);
                    onBuffer.invoke(
                            ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN),
                            buffer,
                            info.channels,
                            info.rate);
                    input.progress();
                }
                if (input.stopped() || !decoded) return null;
                return new Pair<>((double) info.rate, first / 1_000_000d);
            } finally {
                decoder.close(handle);
            }
        } catch (IOException | RuntimeException | LinkageError failed) {
            return null;
        } finally {
            closeQuietly(source);
            if (own != null) own.close();
        }
    }

    private static final int MAX_FRAME_SAMPLES = 1 << 20;

    private static void closeQuietly(MediaDataSource source) {
        try {
            if (source != null) source.close();
        } catch (IOException | RuntimeException ignored) {
            // Whatever was read is already used.
        }
    }
}
