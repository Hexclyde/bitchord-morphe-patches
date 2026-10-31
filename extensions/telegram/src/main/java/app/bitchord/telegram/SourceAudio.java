package app.bitchord.telegram;

import android.media.MediaDataSource;
import android.net.Uri;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import java.io.IOException;
import java.util.Objects;

/** Random-access analysis through the player's source router, including native Telegram readers. */
public final class SourceAudio extends MediaDataSource {
    private final DataSpec spec;
    private final long deadline;
    private final java.util.function.BooleanSupplier cancelled;
    private DataSource source;
    private long size = -1, position = -1, spent;
    private boolean closed;
    private String served;
    private static final long MAX_BYTES = 256L * 1024 * 1024;

    SourceAudio(DataSpec spec, long deadline) throws IOException {
        this(spec, deadline, () -> false);
    }

    SourceAudio(DataSpec spec, long deadline, java.util.function.BooleanSupplier cancelled)
            throws IOException {
        if (spec == null) throw new IOException("Audio source is not resolved");
        this.spec = spec;
        this.deadline = deadline;
        this.cancelled = cancelled;
        reopen(0);
    }

    private void check() throws IOException {
        if (closed
                || Thread.currentThread().isInterrupted()
                || AutomixAccess.stopped()
                || cancelled.getAsBoolean()
                || System.nanoTime() > deadline
                || spent > MAX_BYTES) throw new IOException("Background audio analysis stopped");
    }

    private void reopen(long at) throws IOException {
        check();
        if (source != null) source.close();
        source = AutomixAccess.source();
        if (source == null) throw new IOException("Playback source router is not ready");
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
            try {
                source.close();
            } catch (Exception ignored) {
            }
            source = null;
            throw error;
        }
    }

    @Override
    public synchronized long getSize() {
        return size;
    }

    @Override
    public synchronized int readAt(long at, byte[] bytes, int offset, int length)
            throws IOException {
        check();
        if (at < 0 || (size >= 0 && at >= size)) return -1;
        if (length == 0) return 0;
        if (source == null || position != at) reopen(at);
        int wanted = size < 0 ? length : (int) Math.min(length, size - at);
        int read = source.read(bytes, offset, wanted);
        if (read > 0) {
            position += read;
            spent += read;
        }
        return read;
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        if (source != null) {
            try {
                source.close();
            } finally {
                source = null;
            }
        }
    }
}
