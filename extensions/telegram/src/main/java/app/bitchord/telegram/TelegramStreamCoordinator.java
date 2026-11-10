package app.bitchord.telegram;

import java.io.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.LongSupplier;

/**
 * One public TDLib download window per file. Only nonblocking control sends occur under this
 * monitor; reads happen outside it.
 */
final class TelegramStreamCoordinator {
    enum Consumer {
        SEEK(100, 32),
        PLAYBACK(90, 32),
        PREVIEW(80, 30),
        VERIFICATION(60, 16),
        ANALYSIS(40, 4),
        DOWNLOAD(30, 8),
        METADATA(20, 2),
        ARTWORK(10, 4),
        BACKUP(5, 1),
        SPONSOR(5, 16),
        BACKGROUND(0, 1);
        final int rank, priority;

        Consumer(int rank, int priority) {
            this.rank = rank;
            this.priority = priority;
        }

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        boolean interactive() {
            return rank >= PREVIEW.rank;
        }
    }

    interface Guard {
        void check() throws Exception;
    }

    interface Reply {
        void done(IOException failure, long retryAfterMs);
    }

    interface Backend {
        long prefix(int file, long offset) throws Exception;

        void download(int file, int priority, long offset, long length, Reply reply);

        void cancel(int file);
    }

    static final class State {
        final String path;
        final long size, offset, prefix, downloaded;
        final boolean complete, active;

        State(
                String path,
                long size,
                long offset,
                long prefix,
                long downloaded,
                boolean complete,
                boolean active) {
            this.path = path;
            this.size = size;
            this.offset = offset;
            this.prefix = prefix;
            this.downloaded = downloaded;
            this.complete = complete;
            this.active = active;
        }

        long prefixAt(long at) {
            if (complete) return Math.max(0, size - at);
            return at >= offset && at < offset + prefix ? offset + prefix - at : 0;
        }
    }

    private static final class FileWindow {
        final int file;
        final Set<Registration> readers = new LinkedHashSet<>();
        final Object signal = new Object();
        State state;
        volatile long update;
        long pushes, serial, offset = -1, length, retryAt;
        int priority;
        boolean owned;
        IOException failure;

        FileWindow(int file) {
            this.file = file;
        }
    }

    private final Backend backend;
    private final LongSupplier clock;
    private final Map<Integer, FileWindow> files = new HashMap<>();
    private long sequence, generation;
    private long retryAt;
    private int registrations;
    private boolean shutdown, playbackActive = true;
    // Time-based thresholds are converted using the selected encoded bitrate, with byte caps.
    volatile long lowWaterMs = 15000, targetAheadMs = 30000;
    volatile long maxAheadBytes = 4L * 1024 * 1024;
    volatile long fallbackMs = 500;

    TelegramStreamCoordinator(Backend backend, LongSupplier clock) {
        this.backend = backend;
        this.clock = clock;
    }

    synchronized long generation() {
        return generation;
    }

    synchronized void playbackActive(boolean active) {
        if (playbackActive == active) return;
        playbackActive = active;
        for (FileWindow window : files.values()) signal(window);
    }

    synchronized int active() {
        return registrations;
    }

    synchronized boolean active(int file) {
        FileWindow window = files.get(file);
        return window != null && !window.readers.isEmpty();
    }

    synchronized Registration register(int file, Consumer consumer, long bitrate)
            throws IOException {
        if (shutdown) throw new IOException("Telegram stream coordinator stopped");
        if (registrations >= 128) throw new IOException("Too many Telegram stream consumers");
        FileWindow window = files.get(file);
        if (window == null) {
            if (files.size() >= 256) {
                Iterator<FileWindow> it = files.values().iterator();
                while (it.hasNext()) if (it.next().readers.isEmpty()) it.remove();
            }
            files.put(file, window = new FileWindow(file));
        }
        if (window.readers.size() >= 32)
            throw new IOException("Too many readers of a Telegram file");
        Registration reader = new Registration(window, consumer, bitrate, ++sequence, generation);
        window.readers.add(reader);
        registrations++;
        return reader;
    }

    /** Callback path: copy immutable state, increment a sequence and wake; no TDLib calls. */
    synchronized void update(int file, State state) {
        update(file, state, true);
    }

    synchronized void seed(int file, State state) {
        update(file, state, false);
    }

    private void update(int file, State state, boolean pushed) {
        FileWindow window = files.get(file);
        if (window == null) return;
        window.state = state;
        if (pushed) window.pushes++;
        signal(window);
    }

    // Always called under the coordinator monitor. Waiters never acquire it while holding signal.
    private void signal(FileWindow window) {
        window.update++;
        synchronized (window.signal) {
            window.signal.notifyAll();
        }
    }

    synchronized State state(int file) {
        FileWindow window = files.get(file);
        return window == null ? null : window.state;
    }

    synchronized void invalidate(int file) {
        FileWindow window = files.get(file);
        if (window != null) {
            window.state = null;
            window.offset = -1;
            signal(window);
        }
    }

    /** A seek discards speculation; a buffered seek must not kill the player's live reader. */
    synchronized void seek() {
        generation++;
        for (FileWindow window : files.values())
            for (Registration reader : new ArrayList<>(window.readers))
                if (!reader.consumer.interactive() && reader.consumer != Consumer.DOWNLOAD)
                    reader.release("superseded");
        for (FileWindow window : files.values()) signal(window);
    }

    private int rank(Registration reader) {
        return !playbackActive
                        && (reader.consumer == Consumer.PLAYBACK
                                || reader.consumer == Consumer.SEEK)
                ? Consumer.BACKGROUND.rank - 1
                : reader.consumer.rank;
    }

    synchronized void reset(boolean stop) {
        generation++;
        shutdown = stop;
        for (FileWindow window : files.values()) {
            for (Registration reader : new ArrayList<>(window.readers))
                reader.release(stop ? "shutdown" : "session-change");
        }
        files.clear();
    }

    private Registration winner(FileWindow window) {
        Registration best = null;
        for (Registration reader : window.readers)
            if (!reader.closed
                    && reader.demand
                    && (best == null
                            || rank(reader) > rank(best)
                            || rank(reader) == rank(best) && reader.id > best.id)) best = reader;
        // A foreground reader retains ownership even while its current range is cached.
        for (Registration reader : window.readers)
            if (!reader.closed
                    && reader.consumer.interactive()
                    && reader.demand == false
                    && best != null
                    && rank(reader) > rank(best)) return null;
        return best;
    }

    private long ahead(Registration reader, long millis) {
        long bytes =
                reader.bitrate > 0
                        ? Math.min(100_000_000L, reader.bitrate) * millis / 8000
                        : 1024 * 1024;
        return Math.min(Math.max(65536, bytes), Math.max(65536, maxAheadBytes));
    }

    private long lowWater(Registration reader) {
        // A byte cap must not collapse low-water and target into the same threshold.
        return Math.min(
                ahead(reader, lowWaterMs), Math.max(16384, ahead(reader, targetAheadMs) / 2));
    }

    /** Plan under the monitor; dispatch the command after releasing it. */
    private Runnable plan(FileWindow window) {
        Registration reader = winner(window);
        if (reader == null || clock.getAsLong() < window.retryAt) return null;
        long at = reader.at;
        long length = reader.required;
        if (length >= 0 && reader.consumer.interactive() && reader.readAhead)
            length = Math.max(length, ahead(reader, targetAheadMs));
        if (window.state != null && window.state.size > 0)
            length = length < 0 ? window.state.size - at : Math.min(length, window.state.size - at);
        length = Math.max(0, length); // zero means the whole remaining file to TDLib.
        if (window.owned
                && reader.readAhead
                && at >= window.offset
                && length > 0
                && window.length > 0
                && at + reader.required <= window.offset + window.length
                && window.offset + window.length - at >= lowWater(reader)
                && window.priority == reader.consumer.priority) return null;
        long offset = at;
        if (window.owned
                && reader.readAhead
                && at >= window.offset
                && at < window.offset + window.length)
            offset = window.offset; // Extend an in-flight window without moving its beginning.
        long end = at + length;
        length = length == 0 ? 0 : end - offset;
        if (window.owned
                && window.offset == offset
                && window.length == length
                && window.priority == reader.consumer.priority) return null;
        window.offset = offset;
        window.length = length;
        window.priority = reader.consumer.priority;
        window.owned = true;
        window.failure = null;
        long serial = ++window.serial;
        long sendOffset = offset, sendLength = length;
        int priority = window.priority;
        return () -> {
            synchronized (TelegramStreamCoordinator.this) {
                if (serial != window.serial || !window.owned || files.get(window.file) != window)
                    return;
                boolean blocked = reader.closed;
                for (Registration other : window.readers)
                    if (!other.closed && rank(other) > rank(reader)) blocked = true;
                if (blocked) {
                    window.offset = -1;
                    window.serial++;
                    signal(window);
                    return;
                }
                if (reader.trace != null) reader.trace.window(sendOffset, sendLength, priority);
                backend.download(
                        window.file,
                        priority,
                        sendOffset,
                        sendLength,
                        (failure, retryAfter) -> {
                            synchronized (TelegramStreamCoordinator.this) {
                                if (serial != window.serial || files.get(window.file) != window)
                                    return;
                                if (failure != null) {
                                    if (reader.trace != null) reader.trace.retryAfter = retryAfter;
                                    window.failure = failure;
                                    window.retryAt =
                                            retryAfter > 0 ? clock.getAsLong() + retryAfter : 0;
                                    if (retryAfter > 0) retryAt = Math.max(retryAt, window.retryAt);
                                    window.offset = -1;
                                }
                                signal(window);
                            }
                        });
            }
        };
    }

    final class Registration implements AutoCloseable {
        final FileWindow window;
        final Consumer consumer;
        final long bitrate, id, generation;
        long at, required;
        boolean demand, closed;
        boolean readAhead = true;
        String end = "cancelled";
        TelegramStreamTrace.Request trace;

        Registration(FileWindow window, Consumer consumer, long bitrate, long id, long generation) {
            this.window = window;
            this.consumer = consumer;
            this.bitrate = bitrate;
            this.id = id;
            this.generation = generation;
        }

        private void check() throws IOException {
            if (closed && end.equals("superseded"))
                throw new CancellationException("Telegram stream superseded");
            if (closed) throw new IOException("Telegram stream consumer " + end);
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedIOException("Telegram stream interrupted");
        }

        long await(long offset, long count, long timeoutMs, Guard guard) throws Exception {
            long deadline = clock.getAsLong() + timeoutMs;
            boolean initial = true;
            String wake = null;
            try {
                for (; ; ) {
                    guard.check();
                    long update, pushes;
                    State state;
                    synchronized (TelegramStreamCoordinator.this) {
                        check();
                        update = window.update;
                        pushes = window.pushes;
                        state = window.state;
                    }
                    long prefix = state == null ? 0 : state.prefixAt(offset);
                    if (count == 0 || prefix < count) {
                        if (trace != null) trace.prefixCalls++;
                        prefix = Math.max(prefix, backend.prefix(window.file, offset));
                    }
                    boolean ready = count > 0 ? prefix >= count : state != null && state.complete;
                    if (trace != null) {
                        if (initial) trace.initial(prefix);
                        if (wake != null) trace.wake(wake, ready);
                        trace.readable(offset, prefix, count == 0 && !ready ? -1 : count);
                    }
                    initial = false;
                    if (ready) {
                        synchronized (TelegramStreamCoordinator.this) {
                            check();
                            demand = false;
                        }
                        return prefix;
                    }
                    Runnable send;
                    synchronized (TelegramStreamCoordinator.this) {
                        check();
                        at = offset;
                        required = count == 0 ? -1 : count;
                        demand = true;
                        if (clock.getAsLong() < retryAt) {
                            if (trace != null) trace.retryAfter = retryAt - clock.getAsLong();
                            throw new IOException("Telegram download backoff is active");
                        }
                        if (window.failure != null) {
                            if (window.retryAt > 0 && clock.getAsLong() >= window.retryAt)
                                window.failure = null;
                            else throw window.failure;
                        }
                        send = plan(window);
                    }
                    if (send != null) send.run();
                    long remaining = deadline - clock.getAsLong();
                    if (remaining <= 0)
                        throw new java.net.SocketTimeoutException("Telegram range timed out");
                    long waitMs = Math.min(Math.max(1, fallbackMs), remaining);
                    long fallbackAt = clock.getAsLong() + waitMs;
                    synchronized (window.signal) {
                        if (window.update == update) window.signal.wait(waitMs);
                    }
                    synchronized (TelegramStreamCoordinator.this) {
                        check();
                        wake =
                                window.pushes != pushes
                                        ? "update"
                                        : window.update != update
                                                ? "control"
                                                : clock.getAsLong() >= fallbackAt
                                                        ? "fallback"
                                                        : null;
                    }
                }
            } catch (Exception | Error failure) {
                synchronized (TelegramStreamCoordinator.this) {
                    end =
                            failure instanceof java.net.SocketTimeoutException
                                    ? "timeout"
                                    : failure instanceof CancellationException
                                                    || failure instanceof InterruptedException
                                            ? "cancelled"
                                            : "failed";
                }
                close();
                throw failure;
            }
        }

        void refetch(long offset, int count) {
            Runnable send;
            synchronized (TelegramStreamCoordinator.this) {
                if (closed) return;
                at = offset;
                required = count;
                demand = true;
                window.offset = -1;
                send = plan(window);
            }
            if (send != null) send.run();
        }

        void consumed(long position, long readableUntil) {
            Runnable send = null;
            synchronized (TelegramStreamCoordinator.this) {
                if (closed) return;
                at = position;
                required = 1;
                if (consumer.interactive()
                        && (window.state == null || !window.state.complete)
                        && readableUntil - position < lowWater(this)
                        && (!window.owned
                                || window.offset + window.length - position < lowWater(this))) {
                    demand = true;
                    send = plan(window);
                    demand = false;
                }
            }
            if (send != null) send.run();
        }

        private void release(String status) {
            if (closed) return;
            closed = true;
            end = status;
            window.readers.remove(this);
            registrations--;
            if (trace != null) trace.end(status);
            if (window.readers.isEmpty() && window.owned) {
                window.owned = false;
                window.serial++;
                if (window.retryAt == 0) window.failure = null;
                // Backend.cancel is asynchronous and never waits for a TDLib response.
                backend.cancel(window.file);
            }
            signal(window);
        }

        @Override
        public void close() {
            synchronized (TelegramStreamCoordinator.this) {
                release(end);
            }
        }
    }
}
