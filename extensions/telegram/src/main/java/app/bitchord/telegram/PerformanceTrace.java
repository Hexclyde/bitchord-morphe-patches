package app.bitchord.telegram;

import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Explicit handles cross executor/callback boundaries; disabled calls allocate no spans. */
final class PerformanceTrace {
    enum Stage {
        PLAY_REQUESTED,
        PAUSE_REQUESTED,
        SKIP_REQUESTED,
        PREPARE_REQUESTED,
        QUEUE_REQUESTED,
        SEARCH,
        SEARCH_SLOT,
        SEARCH_CHAT,
        SEARCH_RESULTS,
        SEEK,
        SEEK_ACCEPTED,
        SEEK_READY,
        OUTPUT_PROGRESS_ESTIMATE,
        PREVIEW,
        HANDOVER,
        ANALYSIS,
        SOURCE_OPEN,
        RANGE_WAIT,
        LEASE_WAIT,
        TRANSFER,
        TDLIB_PROGRESS_ESTIMATE,
        MEMORY,
        ISSUE
    }

    enum Outcome {
        START,
        COMPLETE,
        CANCELLED,
        SUPERSEDED,
        TIMEOUT,
        FAILED,
        WAITING,
        INCOMPLETE,
        DEFERRED
    }

    private static volatile Consumer<Map<String, Object>> sink;
    private static String recording;
    private static final AtomicLong sequence =
            new AtomicLong(UUID.randomUUID().getMostSignificantBits() & 0x000fffffffffffffL);
    private static final Map<Long, Span> open = new LinkedHashMap<>();
    private static final Span OFF = new Span(null, 0, 0, 0, 0);

    static synchronized void start(Consumer<Map<String, Object>> target) {
        start(target, null);
    }

    static boolean enabled() {
        return sink != null;
    }

    static synchronized String recordingId() {
        return recording;
    }

    static synchronized void start(Consumer<Map<String, Object>> target, String saved) {
        stop();
        try {
            recording = UUID.fromString(saved).toString();
        } catch (RuntimeException absent) {
            recording = UUID.randomUUID().toString();
        }
        sink = target;
    }

    static synchronized void stop() {
        for (Span span : new ArrayList<>(open.values())) span.end(Outcome.INCOMPLETE);
        open.clear();
        sink = null;
    }

    static Span begin(Stage stage) {
        return begin(stage, null);
    }

    static synchronized Span begin(Stage stage, Span parent) {
        if (sink == null || parent != null && parent.ended) return OFF;
        if (open.size() >= 128) open.values().iterator().next().end(Outcome.INCOMPLETE);
        long id = sequence.incrementAndGet();
        Span span =
                new Span(
                        stage,
                        id,
                        parent == null ? id : parent.operation,
                        parent == null ? 0 : parent.id,
                        System.nanoTime());
        open.put(id, span);
        span.emit(Outcome.START, 0, 0);
        return span;
    }

    static final class Span implements AutoCloseable {
        final Stage stage;
        final long id, operation, parent, start;
        boolean ended;
        final Map<String, Long> measurements = new LinkedHashMap<>();

        void measure(String key, long value) {
            synchronized (PerformanceTrace.class) {
                if (!ended && id != 0 && MEASUREMENTS.contains(key) && value >= -1)
                    measurements.put(key, value);
            }
        }

        Span(Stage stage, long id, long operation, long parent, long start) {
            this.stage = stage;
            this.id = id;
            this.operation = operation;
            this.parent = parent;
            this.start = start;
        }

        void emit(Outcome outcome, long bytes, long queueNanos) {
            Consumer<Map<String, Object>> target = sink;
            if (id == 0 || target == null) return;
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("recording", recording);
            fields.put("operation", operation);
            fields.put("span", id);
            fields.put("parent", parent);
            fields.put("stage", stage.name());
            fields.put("outcome", outcome.name());
            fields.put("monotonic_ns", System.nanoTime());
            fields.put("elapsed_ns", System.nanoTime() - start);
            fields.put("queue_ns", Math.max(0, queueNanos));
            fields.put("bytes", Math.max(0, bytes));
            fields.put("thread_id", Thread.currentThread().getId());
            fields.putAll(measurements);
            target.accept(fields);
        }

        void progress(long bytes, long queueNanos) {
            synchronized (PerformanceTrace.class) {
                if (!ended) emit(Outcome.WAITING, bytes, queueNanos);
            }
        }

        void end(Outcome outcome) {
            synchronized (PerformanceTrace.class) {
                if (ended || id == 0) return;
                ended = true;
                open.remove(id);
                emit(outcome, 0, 0);
            }
        }

        public void close() {
            end(Outcome.COMPLETE);
        }
    }

    private static final Set<String> MEASUREMENTS =
            new HashSet<>(
                    Arrays.asList(
                            "original_ms",
                            "target_ms",
                            "player",
                            "heap_used",
                            "heap_max",
                            "working_ceiling",
                            "native_heap",
                            "process_rss",
                            "system_available",
                            "pending",
                            "playback_reserve",
                            "analysis_allowance",
                            "counter_resets",
                            "range_offset",
                            "range_limit",
                            "priority",
                            "owner_operation",
                            "physical_cache_bytes",
                            "tap_ms",
                            "session_ms",
                            "decks_ms",
                            "seek_ms",
                            "prepare_ms",
                            "out_ready_ms",
                            "in_ready_ms",
                            "out_progress_ms",
                            "in_progress_ms",
                            "stalls",
                            "stall_ms",
                            "required_bytes",
                            "generation"));

    /** Strict schema used both before persistence and again on export. No free text. */
    static String encode(Map<String, ?> fields) {
        try {
            JSONObject out =
                    new JSONObject().put("type", "performance.span").put("schema_version", 1);
            for (String key : new String[] {"recording", "stage", "outcome"}) {
                Object value = fields.get(key);
                if (!(value instanceof String)) return null;
                String text = (String) value;
                if (key.equals("recording")) UUID.fromString(text);
                else if (key.equals("stage")) Stage.valueOf(text);
                else Outcome.valueOf(text);
                out.put(key, text);
            }
            for (String key :
                    new String[] {
                        "operation",
                        "span",
                        "parent",
                        "monotonic_ns",
                        "elapsed_ns",
                        "queue_ns",
                        "bytes",
                        "thread_id"
                    }) {
                Object value = fields.get(key);
                if (!(value instanceof Number) || ((Number) value).longValue() < 0) return null;
                out.put(key, ((Number) value).longValue());
            }
            for (String key : MEASUREMENTS) {
                Object value = fields.get(key);
                if (value instanceof Number && ((Number) value).longValue() >= -1)
                    out.put(key, ((Number) value).longValue());
            }
            return out.toString();
        } catch (Exception invalid) {
            return null;
        }
    }

    private PerformanceTrace() {}
}
