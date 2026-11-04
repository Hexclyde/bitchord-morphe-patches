package app.bitchord.telegram;

import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public final class FormatProbeInvalidationTest {
    static int checks;

    static void check(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }

    static JSONObject header() throws Exception {
        return new JSONObject()
                .put("codec", "flac")
                .put("bits", 24)
                .put("rate", 96000)
                .put("channels", 2);
    }

    public static void main(String[] args) throws Exception {
        File directory = Files.createTempDirectory("bitchord-formats-").toFile();
        File saved = new File(directory, "formats.json"),
                audio = new File(directory, "fixture.flac");
        Files.write(audio.toPath(), AudioHeaderTest.flac(96000, 2, 24, new byte[0]));
        try {
            FormatProbe formats = new FormatProbe(saved);
            for (String id : Arrays.asList("-10_1", "-10_1_2", "-10_1_3", "-10_11", "-20_1"))
                formats.record(id, header());
            check(
                    formats.forgetMessages(-10, new long[] {1, 1}) == 3,
                    "Exact edited message and ZIP/cue children removed once");
            check(
                    formats.get("-10_11") != null && formats.get("-20_1") != null,
                    "Prefix neighbours and other chats preserved");
            FormatProbe reopened = new FormatProbe(saved);
            check(
                    reopened.get("-10_1") == null && reopened.get("-10_11") != null,
                    "Message-family removal persists across restart");
            List<Artwork.Done> pending = new ArrayList<>();
            JSONObject row =
                    new JSONObject().put("id", "-10_2").put("file", 1).put("format", "flac");
            CountDownLatch old =
                    formats.probe(
                            Collections.singletonList(row), (file, done) -> pending.add(done), 1);
            formats.forgetMessage(-10, 2);
            pending.remove(0).finished(audio.getAbsolutePath());
            check(
                    old.getCount() == 0 && formats.get("-10_2") == null,
                    "Late header completion cannot resurrect an edited/deleted message");
            formats.probe(Collections.singletonList(row), (file, done) -> pending.add(done), 1);
            pending.remove(0).finished(audio.getAbsolutePath());
            check(
                    formats.get("-10_2") != null,
                    "A fresh probe of the same message may populate current metadata");
            formats.probe(
                    Collections.singletonList(new JSONObject(row.toString()).put("id", "-10_3")),
                    (file, done) -> pending.add(done),
                    1);
            check(
                    formats.forgetChat(-10) == 2,
                    "Chat reset clears only that chat's cached headers");
            pending.remove(0).finished(audio.getAbsolutePath());
            check(
                    formats.get("-10_3") == null && formats.get("-20_1") != null,
                    "Chat reset also fences pending probes while preserving other chats");
            check(
                    formats.forgetMessages(-20, new long[] {0, -1}) == 0
                            && formats.get("-20_1") != null,
                    "Malformed message IDs do not broaden removal");
            JSONObject staleZip =
                    new JSONObject(row.toString())
                            .put("id", "-10_4_1")
                            .put("zip", new JSONObject())
                            .put("header", header());
            CountDownLatch rejected =
                    formats.probe(
                            Arrays.asList(row, staleZip),
                            (file, done) -> {
                                throw new AssertionError("Stale row scheduled a read");
                            },
                            8,
                            candidate -> false);
            check(
                    rejected.getCount() == 0 && formats.get("-10_4_1") == null,
                    "Late-delivered stale rows cannot recertify header tickets or ZIP metadata");
            java.util.concurrent.atomic.AtomicBoolean current =
                    new java.util.concurrent.atomic.AtomicBoolean(true);
            CountDownLatch raced =
                    formats.probe(
                            Collections.singletonList(row),
                            (file, done) -> pending.add(done),
                            1,
                            candidate -> current.get());
            current.set(false);
            pending.remove(0).finished(audio.getAbsolutePath());
            check(
                    raced.getCount() == 0 && formats.get("-10_2") == null,
                    "A row that became stale during its read cannot publish a format");
            System.out.println("PASS: " + checks + " exact format-cache invalidation checks");
        } finally {
            for (File file : Objects.requireNonNull(directory.listFiles())) file.delete();
            directory.delete();
        }
    }
}
