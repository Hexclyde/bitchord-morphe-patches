package app.bitchord.telegram;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Measurements only. File numbers and paths never leave this class. */
final class TelegramStreamTrace {
    interface Sink {
        void record(String type, Map<String, ?> fields);
    }

    private final Sink sink;
    private final BooleanSupplier enabled;
    private final LongSupplier clock;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<Integer, Progress> files = new LinkedHashMap<>();

    private static final class Progress {
        final long ref;
        long bytes = -1, emitted;
        final Set<Request> readers = new HashSet<>();

        Progress(long ref) {
            this.ref = ref;
        }
    }

    TelegramStreamTrace(Sink sink, BooleanSupplier enabled, LongSupplier clock) {
        this.sink = sink;
        this.enabled = enabled;
        this.clock = clock;
    }

    synchronized void reset() {
        for (Progress progress : new ArrayList<>(files.values()))
            for (Request request : new ArrayList<>(progress.readers)) request.end("session-change");
        files.clear();
    }

    private Progress file(int file) {
        Progress progress = files.get(file);
        if (progress == null) {
            if (files.size() >= 256) {
                Iterator<Progress> iterator = files.values().iterator();
                while (iterator.hasNext())
                    if (iterator.next().readers.isEmpty()) {
                        iterator.remove();
                        break;
                    }
                if (files.size() >= 256) return null;
            }
            files.put(file, progress = new Progress(sequence.incrementAndGet()));
        }
        return progress;
    }

    synchronized Request begin(
            int file,
            long offset,
            long length,
            String consumer,
            int priority,
            long generation,
            boolean v2,
            long startedMs) {
        if (!enabled.getAsBoolean()) return null;
        Progress progress = file(file);
        if (progress == null || progress.readers.size() >= 64) return null;
        Request request =
                new Request(
                        progress, offset, length, consumer, priority, generation, v2, startedMs);
        progress.readers.add(request);
        request.emit("request");
        return request;
    }

    synchronized void update(int file, long bytes) {
        if (!enabled.getAsBoolean()) return;
        Progress progress = file(file);
        if (progress == null) return;
        long now = clock.getAsLong();
        boolean reset = progress.bytes >= 0 && bytes < progress.bytes;
        long delta = progress.bytes < 0 || reset ? 0 : Math.max(0, bytes - progress.bytes);
        progress.bytes = bytes;
        if (delta > 0)
            for (Request request : progress.readers)
                if (request.firstProgress < 0) request.firstProgress = now;
        // Aggregate file progress; a shared delta is never attributed to every consumer.
        if (delta > 0 || reset) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("file_ref", progress.ref);
            fields.put("new_bytes_estimate", delta);
            fields.put("progress_reset", reset);
            fields.put("mono_ms", now);
            sink.record("telegram.stream.progress", fields);
        }
    }

    final class Request {
        final Progress file;
        final long id = sequence.incrementAndGet(), offset, length, generation, started;
        final String consumer;
        final int priority;
        final boolean v2;
        long first = -1, required = -1, firstProgress = -1;
        long cacheBytes, uncachedBytes, polls, updates, unreadable;
        long cachedUntil = -1, requiredUntil = -1;
        boolean initialChecked, ended;

        Request(
                Progress file,
                long offset,
                long length,
                String consumer,
                int priority,
                long generation,
                boolean v2,
                long started) {
            this.file = file;
            this.offset = offset;
            this.length = Math.max(0, length);
            this.consumer = consumer;
            this.priority = priority;
            this.generation = generation;
            this.v2 = v2;
            this.started = started;
        }

        void initial(long prefix) {
            synchronized (TelegramStreamTrace.this) {
                if (!initialChecked) {
                    initialChecked = true;
                    cachedUntil = offset + Math.max(0, prefix);
                }
            }
        }

        void readable(long at, long prefix, int needed) {
            synchronized (TelegramStreamTrace.this) {
                if (ended) return;
                if (requiredUntil < 0) requiredUntil = offset + needed;
                long now = clock.getAsLong();
                boolean changed = false;
                if (prefix > 0 && first < 0) {
                    first = now;
                    changed = true;
                }
                if (prefix > 0
                        && at <= requiredUntil
                        && at + prefix >= requiredUntil
                        && required < 0) {
                    required = now;
                    changed = true;
                }
                if (changed) emit("readable");
            }
        }

        void served(long at, int count) {
            synchronized (TelegramStreamTrace.this) {
                long cached = Math.min(count, Math.max(0, cachedUntil - at));
                cacheBytes += cached;
                uncachedBytes += count - cached;
            }
        }

        void wake(boolean update, boolean readable) {
            synchronized (TelegramStreamTrace.this) {
                if (update) updates++;
                else polls++;
                if (!readable) unreadable++;
                // Counters are emitted with first/full readiness and the final summary.
            }
        }

        void end(String status) {
            synchronized (TelegramStreamTrace.this) {
                if (ended) return;
                ended = true;
                Map<String, Object> values = fields();
                values.put("stream_status", status);
                sink.record("telegram.stream.end", values);
                file.readers.remove(this);
            }
        }

        private void emit(String event) {
            sink.record("telegram.stream." + event, fields());
        }

        private Map<String, Object> fields() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("request_id", id);
            values.put("file_ref", file.ref);
            values.put("stream_generation", generation);
            values.put("offset_bytes", offset);
            values.put("length_bytes", length);
            values.put("consumer", consumer);
            values.put("priority", priority);
            values.put("stream_v2", v2);
            values.put("mono_ms", clock.getAsLong());
            values.put("first_byte_ms", first < 0 ? -1 : first - started);
            values.put("required_range_ms", required < 0 ? -1 : required - started);
            values.put("before_progress_ms", firstProgress < 0 ? -1 : firstProgress - started);
            values.put(
                    "after_progress_ms",
                    first < 0 || firstProgress < 0 ? -1 : Math.max(0, first - firstProgress));
            values.put("cache_bytes", cacheBytes);
            values.put("uncached_bytes", uncachedBytes);
            values.put("poll_wakes", polls);
            values.put("update_wakes", updates);
            values.put("unreadable_wakes", unreadable);
            return values;
        }
    }
}
