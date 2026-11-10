package app.bitchord.telegram;

import org.json.JSONObject;

import java.util.*;

/** Export-only projection of validated structured events; parallel spans are never summed. */
final class DiagnosticTimeline {
    static String render(String report) {
        StringBuilder out =
                new StringBuilder(
                        "recording,operation,span,parent,stage,outcome,monotonic_ns,elapsed_ns,queue_ns,bytes\n");
        for (String line : report.split("\n")) {
            String safe = DiagnosticRecord.sanitize(line);
            if (safe == null) continue;
            try {
                JSONObject row = new JSONObject(safe);
                if (!row.optString("type").equals("performance.span")) continue;
                for (String key :
                        new String[] {
                            "recording",
                            "operation",
                            "span",
                            "parent",
                            "stage",
                            "outcome",
                            "monotonic_ns",
                            "elapsed_ns",
                            "queue_ns",
                            "bytes"
                        }) {
                    if (!key.equals("recording")) out.append(',');
                    out.append(row.get(key));
                }
                out.append('\n');
            } catch (Exception invalid) {
                /* Already validated; omit a damaged row. */
            }
        }
        return out.toString();
    }

    static String summary(String report) {
        Map<String, JSONObject> starts = new LinkedHashMap<>();
        List<JSONObject> ended = new ArrayList<>();
        for (String line : report.split("\n")) {
            String safe = DiagnosticRecord.sanitize(line);
            if (safe == null) continue;
            try {
                JSONObject row = new JSONObject(safe);
                if (!row.optString("type").equals("performance.span")) continue;
                String id = row.getString("recording") + "/" + row.getLong("span");
                String outcome = row.getString("outcome");
                if (outcome.equals("START")) starts.put(id, row);
                else if (!outcome.equals("WAITING")) {
                    starts.remove(id);
                    ended.add(row);
                }
            } catch (Exception invalid) {
            }
        }
        ended.sort(
                Comparator.comparingLong((JSONObject row) -> row.optLong("elapsed_ns")).reversed());
        StringBuilder out =
                new StringBuilder(
                        "Observed slow spans (inclusive elapsed time; parallel durations are not"
                                + " additive):\n");
        for (int i = 0; i < Math.min(20, ended.size()); i++) {
            JSONObject row = ended.get(i);
            out.append(row.optString("stage"))
                    .append(" span=")
                    .append(row.optLong("span"))
                    .append(" operation=")
                    .append(row.optLong("operation"))
                    .append(" outcome=")
                    .append(row.optString("outcome"))
                    .append(" elapsedMs=")
                    .append(row.optLong("elapsed_ns") / 1_000_000.0)
                    .append('\n');
        }
        out.append("Starts without a retained terminal event: ")
                .append(starts.size())
                .append(
                        " (may be stalled, interrupted by process death, or lost through bounded"
                                + " eviction).\n")
                .append(
                        "SOURCE_OPEN bytes are delivery including cache, not internet throughput."
                            + " OUTPUT_PROGRESS_ESTIMATE is player-position progress, not confirmed"
                            + " sound.\n")
                .append(
                        "No network link-speed or TDLib DNS/TLS measurement is available in these"
                                + " spans.\n");
        return out.toString();
    }

    private DiagnosticTimeline() {}
}
