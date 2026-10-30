package app.bitchord.soulseek;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;

/** Reads only the downloaded contiguous prefix; sparse file length is never availability. */
public final class ProgressiveReader implements Closeable {
    public interface Source {
        Snapshot snapshot() throws IOException;

        void release();
    }

    public static final class Snapshot {
        public final File file;
        public final long downloaded, total;
        public final boolean complete;
        public final String status;

        public Snapshot(File file, long downloaded, long total, boolean complete, String status) {
            this.file = file;
            this.downloaded = downloaded;
            this.total = total;
            this.complete = complete;
            this.status = status;
        }
    }

    private final Source source;
    private final long end, total, waitMillis;
    private long position;
    private volatile boolean closed;
    public Runnable cancellation = () -> {};

    public ProgressiveReader(Source source, long total, long position, long length, long waitMillis)
            throws IOException {
        if (source == null
                || total <= 0
                || position < 0
                || position > total
                || length < -1
                || waitMillis < 1) throw new IOException("Invalid Soulseek audio range");
        this.source = source;
        this.total = total;
        this.position = position;
        this.end = position + (length < 0 ? total - position : Math.min(length, total - position));
        this.waitMillis = waitMillis;
    }

    public long remaining() {
        return end - position;
    }

    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (buffer == null) throw new NullPointerException("buffer");
        if (offset < 0 || length < 0 || offset > buffer.length - length)
            throw new IndexOutOfBoundsException();
        if (length == 0) return 0;
        long deadline = System.nanoTime() + waitMillis * 1_000_000L;
        while (true) {
            check();
            if (position == end && end < total) return -1;
            Snapshot current = source.snapshot();
            if (current.total != total
                    || current.downloaded < 0
                    || current.downloaded > total
                    || (current.complete && current.downloaded != total))
                throw new IOException("Soulseek transfer size changed");
            if (position == end && current.complete) return -1;
            if ("failed".equals(current.status) || "cancelled".equals(current.status))
                throw new IOException(
                        "Soulseek transfer " + current.status + ". Search again or retry.");
            long available = Math.min(end, current.downloaded) - position;
            if (available > 0 && current.file != null) {
                try (RandomAccessFile input = new RandomAccessFile(current.file, "r")) {
                    int count = (int) Math.min(length, available);
                    if (input.length() >= position + count) {
                        input.seek(position);
                        int read = input.read(buffer, offset, count);
                        if (read > 0) {
                            check();
                            position += read;
                            return read;
                        }
                    }
                } catch (FileNotFoundException moved) {
                    // A completed transfer may rename .part to its final path between polls.
                }
            }
            if (current.complete)
                throw new IOException("Completed Soulseek audio is missing or truncated");
            if (System.nanoTime() >= deadline)
                throw new IOException(
                        "Soulseek peer is queued or too slow. Retry or choose another source.");
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Soulseek read interrupted");
            }
        }
    }

    private void check() throws IOException {
        if (closed) throw new IOException("Soulseek reader closed");
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Soulseek read interrupted");
        cancellation.run();
    }

    @Override
    public void close() {
        boolean release;
        synchronized (this) {
            release = !closed;
            closed = true;
        }
        if (release) source.release();
    }
}
