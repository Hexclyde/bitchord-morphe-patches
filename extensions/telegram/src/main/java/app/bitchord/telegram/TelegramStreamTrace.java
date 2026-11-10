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
    private final Map<String, Long> bindings = new LinkedHashMap<>();

    synchronized void bind(int id, String uri) {
        if (!enabled.getAsBoolean()) return;
        Progress progress = file(id);
        if (progress == null) return;
        if (bindings.size() >= 128) bindings.remove(bindings.keySet().iterator().next());
        bindings.put(uri, progress.ref);
    }

    synchronized long fileRef(String uri) {
        return bindings.getOrDefault(uri, 0L);
    }

    private long seekRequest, seekGeneration = -1;

    synchronized long seek(long generation) {
        seekGeneration = generation;
        return seekRequest = sequence.incrementAndGet();
    }

    private static final class Progress {
        final long ref;
        long bytes = -1, emitted, pending, intervalStart = -1;
        boolean reset;
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
        seekRequest = 0;
        seekGeneration = -1;
        bindings.clear();
        for (Progress progress : new ArrayList<>(files.values()))
            for (Request request : new ArrayList<>(progress.readers)) request.end("session-change");
        for (Progress progress : files.values()) flush(progress);
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
        if (progress.intervalStart < 0) progress.intervalStart = now;
        boolean reset = progress.bytes >= 0 && bytes < progress.bytes;
        long delta = progress.bytes < 0 || reset ? 0 : Math.max(0, bytes - progress.bytes);
        progress.bytes = bytes;
        if (delta > 0)
            for (Request request : progress.readers)
                if (request.firstProgress < 0) request.firstProgress = now;
        // Aggregate file progress; a shared delta is never attributed to every consumer.
        progress.pending += delta;
        progress.reset |= reset;
        if (progress.reset || progress.pending > 0 && now - progress.emitted >= 250)
            flush(progress);
    }

    private void flush(Progress progress) {
        if (progress.pending > 0 || progress.reset) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("file_ref", progress.ref);
            fields.put("new_bytes_estimate", progress.pending);
            fields.put("progress_reset", progress.reset);
            fields.put("mono_ms", clock.getAsLong());
            fields.put("interval_ms", Math.max(0, clock.getAsLong() - progress.intervalStart));
            sink.record("telegram.stream.progress", fields);
            progress.pending = 0;
            progress.reset = false;
            progress.emitted = clock.getAsLong();
            progress.intervalStart = clock.getAsLong();
        }
    }

    final class Request {
        final Progress file;
        final long id = sequence.incrementAndGet(), offset, length, generation, started;
        final long parent;
        String format = "unknown", rangeKind = "read";
        final String consumer;
        final int priority;
        final boolean v2;
        long first = -1, required = -1, firstProgress = -1;
        long cacheBytes, uncachedBytes, polls, updates, unreadable, controls;
        long prefixCalls, handleOpens, partFallbacks;
        long retryAfter;
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
            parent = generation == seekGeneration ? seekRequest : 0;
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

        void readable(long at, long prefix, long needed) {
            synchronized (TelegramStreamTrace.this) {
                if (ended) return;
                if (requiredUntil < 0 && needed >= 0) requiredUntil = offset + needed;
                long now = clock.getAsLong();
                boolean changed = false;
                if (prefix > 0 && first < 0) {
                    first = now;
                    changed = true;
                }
                if (requiredUntil >= 0
                        && prefix > 0
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
            wake(update ? "update" : "fallback", readable);
        }

        void wake(String wake, boolean readable) {
            synchronized (TelegramStreamTrace.this) {
                if (wake.equals("update")) updates++;
                else if (wake.equals("control")) controls++;
                else polls++;
                if (!readable) unreadable++;
                if (v2 && wake.equals("fallback")) {
                    Map<String, Object> values = fields();
                    values.put("wake", "fallback");
                    values.put("range_readable", readable);
                    sink.record("telegram.stream.wake", values);
                }
            }
        }

        void window(long at, long length, int priority) {
            synchronized (TelegramStreamTrace.this) {
                Map<String, Object> values = fields();
                values.put("offset_bytes", at);
                values.put("length_bytes", length);
                values.put("priority", priority);
                sink.record("telegram.stream.window", values);
            }
        }

        void end(String status) {
            synchronized (TelegramStreamTrace.this) {
                if (ended) return;
                flush(file);
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
            if (parent > 0) values.put("parent_request_id", parent);
            values.put("file_ref", file.ref);
            values.put("stream_generation", generation);
            values.put("offset_bytes", offset);
            values.put("length_bytes", length);
            values.put("consumer", consumer);
            values.put("priority", priority);
            values.put("stream_v2", v2);
            values.put("container_format", format);
            values.put("range_kind", rangeKind);
            values.put("mono_ms", clock.getAsLong());
            values.put("first_byte_ms", first < 0 ? -1 : first - started);
            values.put("required_range_ms", required < 0 ? -1 : required - started);
            values.put("before_progress_ms", firstProgress < 0 ? -1 : firstProgress - started);
            values.put(
                    "after_progress_ms",
                    first < 0 || firstProgress < 0 || first < firstProgress
                            ? -1
                            : first - firstProgress);
            values.put(
                    "range_after_progress_ms",
                    required < 0 || firstProgress < 0 || required < firstProgress
                            ? -1
                            : required - firstProgress);
            values.put("cache_bytes", cacheBytes);
            values.put("uncached_bytes", uncachedBytes);
            values.put("poll_wakes", polls);
            values.put("update_wakes", updates);
            values.put("control_wakes", controls);
            values.put("unreadable_wakes", unreadable);
            values.put("prefix_calls", prefixCalls);
            values.put("handle_opens", handleOpens);
            values.put("part_fallbacks", partFallbacks);
            if (retryAfter > 0) values.put("retry_after_ms", retryAfter);
            return values;
        }
    }
}
