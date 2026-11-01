package app.bitchord.telegram;

import android.media.MediaDataSource;
import android.net.Uri;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Random-access analysis through the player's source router, including native Telegram readers.
 *
 * <p>Android's extractors ask for 2 KB at a time and rarely where the last read ended: a Matroska
 * seek that can't use its cues reads the head of every block up to the target. So what's read is
 * kept in blocks, up to {@link #MAX_BLOCKS}, a short gap is read through on the open connection,
 * and only a longer jump asks for a new one.
 */
public final class SourceAudio extends MediaDataSource {
    /** Analysis ran out of time, was cancelled or closed: not a fault in the audio. */
    static final class Stopped extends IOException {
        Stopped() {
            super("Background audio analysis stopped");
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
        if (closed
                || Thread.currentThread().isInterrupted()
                || AutomixAccess.stopped()
                || cancelled.getAsBoolean()
                || System.nanoTime() > deadline
                || spent > MAX_BYTES) throw new Stopped();
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
     * This file for one extractor. Releasing an extractor closes its source, so each gets a view
     * that closes only itself, and what was read carries over to the next.
     */
    MediaDataSource view() {
        return new MediaDataSource() {
            private volatile boolean released;

            @Override
            public int readAt(long at, byte[] bytes, int offset, int length) throws IOException {
                if (released) throw new Stopped();
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
}
