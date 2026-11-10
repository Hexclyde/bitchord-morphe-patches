package app.bitchord.telegram;

import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class DiagnosticStoreTest {
    static int checks;

    static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }

    static String event(int count) {
        return DiagnosticRecord.structured(
                "host.line",
                new LinkedHashMap<String, Object>() {
                    {
                        put("count", count);
                        put("severity", "INFO");
                        put("category", "APP");
                    }
                });
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("bitchord-diagnostics-");
        File file = directory.resolve("support-recording.jsonl").toFile();
        try {
            DiagnosticStore store = new DiagnosticStore(file);
            check(
                    store.size() == 0 && !file.exists(),
                    "Opening an unused store started persistent recording");
            store.append(event(1));
            store.append("raw PRIVATEPASSWORD");
            check(
                    store.size() == 1 && store.dropped() == 1,
                    "Raw payload accepted or invalid input uncounted");
            DiagnosticStore reopened = new DiagnosticStore(file);
            check(
                    reopened.size() == 1 && reopened.snapshot().contains("\"count\":1"),
                    "Process restart lost the last complete event");
            Files.write(
                    file.toPath(),
                    "{\"type\":\"host.line\",\"message\":\"PRIVATECOOKIE\",\"severity\":\"ERROR\"}\npartial PRIVATEPASSWORD"
                            .getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);
            DiagnosticStore recovered = new DiagnosticStore(file);
            check(
                    recovered.size() == 2 && recovered.corrupt() > 0,
                    "Partial crash tail was accepted or complete earlier event lost");
            check(
                    !recovered.snapshot().contains("PRIVATE")
                            && !new String(
                                            Files.readAllBytes(file.toPath()),
                                            StandardCharsets.UTF_8)
                                    .contains("PRIVATE"),
                    "Recovery/export retained private unknown fields");
            for (int index = 0; index < DiagnosticStore.MAX_RECORDS + 200; index++)
                recovered.append(event(index));
            check(
                    recovered.size() <= DiagnosticStore.MAX_RECORDS
                            && file.length() <= DiagnosticStore.MAX_BYTES,
                    "Journal exceeded memory/disk bounds");
            check(
                    recovered.writtenBytes() < 200_000,
                    "Saturation rewrites history for every event");
            for (int index = 0; index < 4000; index++) recovered.append(event(index));
            check(recovered.rotations() > 0, "Journal must rotate at the byte bound");
            check(
                    recovered.writtenBytes() < 2_000_000,
                    "Rotation has excessive write amplification");
            check(recovered.dropped() > 0, "Bounded eviction was not reported");
            String snapshot = recovered.snapshot();
            JSONObject header = new JSONObject(snapshot.split("\n")[0]);
            check(
                    header.getInt("event_count") == recovered.size()
                            && header.getJSONObject("severity_counts").getInt("INFO")
                                    == recovered.size(),
                    "Retained event/category counts inaccurate");
            check(
                    new DiagnosticStore(file).size() == recovered.size(),
                    "Rotation/restart lost bounded retained entries");
            recovered.clear();
            check(recovered.size() == 0 && !file.exists(), "Explicit clear left persisted records");
            Files.write(
                    directory.resolve("support-recording.jsonl.tmp"),
                    (event(9) + "\npartial PRIVATECOOKIE").getBytes(StandardCharsets.UTF_8));
            DiagnosticStore interrupted = new DiagnosticStore(file);
            check(
                    interrupted.size() == 1
                            && interrupted.snapshot().contains("\"count\":9")
                            && !interrupted.snapshot().contains("PRIVATECOOKIE"),
                    "Interrupted first rotation did not recover its complete safe entry");
            interrupted.clear();
            File unrelated = directory.resolve("unrelated.txt").toFile();
            Files.write(unrelated.toPath(), "PRIVATEOWNER".getBytes(StandardCharsets.UTF_8));
            Path linked = directory.resolve("linked.jsonl");
            Files.createSymbolicLink(linked, unrelated.toPath());
            try {
                new DiagnosticStore(linked.toFile());
                throw new AssertionError("Linked destination accepted");
            } catch (IOException expected) {
                checks++;
            }
            check(
                    new String(Files.readAllBytes(unrelated.toPath()), StandardCharsets.UTF_8)
                            .equals("PRIVATEOWNER"),
                    "Guard changed an unrelated file");
            Files.delete(linked);
            Path giant = directory.resolve("oversized.jsonl");
            try (OutputStream output = Files.newOutputStream(giant)) {
                for (int i = 0; i < 20000; i++)
                    output.write((event(i) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            DiagnosticStore trimmed = new DiagnosticStore(giant.toFile());
            check(
                    giant.toFile().length() <= DiagnosticStore.MAX_BYTES
                            && trimmed.size() <= DiagnosticStore.MAX_RECORDS,
                    "Oversized recovery was not bounded");
            Set<java.nio.file.attribute.PosixFilePermission> permissions =
                    Files.getPosixFilePermissions(giant);
            check(
                    !permissions.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ)
                            && !permissions.contains(
                                    java.nio.file.attribute.PosixFilePermission.OTHERS_READ),
                    "Diagnostic file is not owner-readable only");
            System.out.println(
                    "PASS: " + checks + " bounded diagnostic persistence/recovery checks");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(
                                path -> {
                                    try {
                                        Files.delete(path);
                                    } catch (IOException ignored) {
                                    }
                                });
            }
        }
    }
}
