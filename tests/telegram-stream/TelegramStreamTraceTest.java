package app.bitchord.telegram;

import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class TelegramStreamTraceTest {
    public static void main(String[] args) throws Exception {
        AtomicLong now = new AtomicLong(1000);
        List<JSONObject> events = new ArrayList<>();
        TelegramStreamTrace trace =
                new TelegramStreamTrace(
                        (type, fields) -> {
                            String encoded = DiagnosticRecord.structured(type, fields);
                            if (!encoded.equals(DiagnosticRecord.sanitize(encoded)))
                                throw new AssertionError("Export lost fields");
                            events.add(new JSONObject(encoded));
                        },
                        () -> true,
                        now::get);
        trace.update(123456, 4096); // Existing cache establishes the baseline, not a download.
        TelegramStreamTrace.Request a = trace.begin(123456, 4096, 8192, "seek", 32, 7, false, 1000);
        TelegramStreamTrace.Request b =
                trace.begin(123456, 4096, 8192, "analysis", 1, 7, false, 1000);
        a.initial(0);
        now.set(1250);
        trace.update(123456, 8192);
        a.readable(4096, 4096, 8192);
        a.served(4096, 4096);
        a.wake(true, false);
        now.set(1400);
        a.readable(8192, 4096, 4096);
        a.served(8192, 4096);
        a.end("complete");
        a.end("cancelled");
        b.end("superseded");
        JSONObject end =
                events.stream()
                        .filter(e -> e.optString("type").equals("telegram.stream.end"))
                        .findFirst()
                        .get();
        check(end.getLong("first_byte_ms") == 250, "First usable data timing");
        check(end.getLong("required_range_ms") == 400, "Full original required range timing");
        check(
                end.getLong("before_progress_ms") == 250 && end.getLong("after_progress_ms") == 0,
                "Observed progress split");
        check(
                end.getLong("stream_generation") == 7 && end.getLong("uncached_bytes") == 8192,
                "Generation and attribution");
        long payload = events.stream().mapToLong(e -> e.optLong("new_bytes_estimate")).sum();
        check(payload == 4096, "Shared file progress counted only once");
        trace.update(123456, 0);
        check(events.get(events.size() - 1).getBoolean("progress_reset"), "Counter reset recorded");
        TelegramStreamTrace.Request c = trace.begin(55, 0, 4096, "playback", 32, 8, true, 1400);
        c.initial(4096);
        c.readable(0, 4096, 4096);
        c.served(0, 4096);
        c.end("complete");
        JSONObject cached = events.get(events.size() - 1);
        check(
                cached.getLong("cache_bytes") == 4096 && cached.getLong("before_progress_ms") == -1,
                "Cache is not transfer");
        check(
                events.stream().noneMatch(e -> e.toString().contains("123456")),
                "File identifier excluded");
        Map<String, Object> build = new LinkedHashMap<>();
        build.put("wrapper_version", "java-stream-1");
        build.put("wrapper_commit", "ab6d586b3ede");
        build.put("wrapper_fingerprint", "0123456789abcdef");
        build.put("tdlib_version", "1.8.69");
        build.put("token", "DO-NOT-EXPORT");
        String encoded = DiagnosticRecord.structured("telegram.stream.session", build);
        JSONObject session = new JSONObject(DiagnosticRecord.sanitize(encoded));
        check(
                session.getString("wrapper_commit").equals("ab6d586b3ede")
                        && session.getString("tdlib_version").equals("1.8.69"),
                "Build identifiers survive export");
        check(!encoded.contains("DO-NOT-EXPORT"), "Secrets excluded");
        System.out.println(
                "PASS Telegram stream trace: timing, shared progress, cache, generation, export and"
                        + " privacy");
    }

    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
}
