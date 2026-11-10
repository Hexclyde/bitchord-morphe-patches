package app.bitchord.telegram;

import java.util.*;

public final class PerformanceTraceTest {
    public static void main(String[] args) throws Exception {
        TelegramProgress progress = new TelegramProgress();
        progress.observe(7, 1, "file-a", 1000);
        if (progress.estimated(7) != 0)
            throw new AssertionError("Existing cache counted as download");
        progress.observe(7, 1, "file-a", 1500);
        progress.observe(7, 1, "file-a", 1500);
        if (progress.estimated(7) != 500)
            throw new AssertionError("Overlapping consumers double-counted");
        progress.observe(7, 1, "file-a", 0);
        progress.observe(7, 1, "file-a", 200);
        if (progress.estimated(7) != 200) throw new AssertionError("Eviction/reset not handled");
        progress.observe(7, 2, "file-b", 9000);
        if (progress.estimated(7) != 0)
            throw new AssertionError("Replacement/session counted as new transfer");
        List<Map<String, Object>> events = new ArrayList<>();
        PerformanceTrace.begin(PerformanceTrace.Stage.SEARCH).close();
        PerformanceTrace.start(events::add);
        PerformanceTrace.Span search = PerformanceTrace.begin(PerformanceTrace.Stage.SEARCH);
        Thread worker =
                new Thread(
                        () -> {
                            try (PerformanceTrace.Span child =
                                    PerformanceTrace.begin(
                                            PerformanceTrace.Stage.SEARCH_CHAT, search)) {
                                child.progress(0, 123);
                            }
                        });
        worker.start();
        worker.join();
        search.close();
        if (events.size() != 5) throw new AssertionError("Cross-thread stages missing");
        for (Map<String, Object> event : events) {
            String encoded = PerformanceTrace.encode(event);
            if (!encoded.equals(DiagnosticRecord.sanitize(encoded)))
                throw new AssertionError("Export dropped typed fields");
            if (!event.get("operation").equals(events.get(0).get("operation")))
                throw new AssertionError("Operation correlation lost");
        }
        Map<String, Object> hostile = new LinkedHashMap<>(events.get(0));
        hostile.put("secret", "PRIVATEPASSWORD");
        if (PerformanceTrace.encode(hostile).contains("PRIVATE"))
            throw new AssertionError("Unknown field leaked");
        hostile.put("stage", "PRIVATEPASSWORD");
        if (PerformanceTrace.encode(hostile) != null)
            throw new AssertionError("Unknown stage accepted");
        PerformanceTrace.Span abandoned = PerformanceTrace.begin(PerformanceTrace.Stage.SEEK);
        PerformanceTrace.stop();
        if (!events.get(events.size() - 1).get("outcome").equals("INCOMPLETE"))
            throw new AssertionError("Stall hidden");
        int count = events.size();
        abandoned.close();
        if (events.size() != count) throw new AssertionError("Late completion emitted");
        System.out.println("PASS: correlated spans, strict schema, unfinished and late operations");
    }
}
