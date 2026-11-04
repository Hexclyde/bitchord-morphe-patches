package app.bitchord.telegram;

import java.io.File;
import java.nio.file.Files;

public final class TelegramLegendInvalidationTest {
    static int checks;

    static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("bitchord-legends-").toFile(),
                saved = new File(dir, "legends.json");
        try {
            LegendMemory memory = new LegendMemory(saved);
            memory.put("src:telegram-native::-10_1", "FLAC 24bit");
            memory.put("src:telegram-native::-10_1_2", "FLAC 24bit");
            memory.put("src:telegram-native::-10_11", "FLAC 24bit");
            memory.put("src:telegram-native::-20_1", "FLAC 24bit");
            memory.put("abcdefghijk", "FLAC 24bit");
            memory.put("src:other::-10_1", "FLAC 24bit");
            check(
                    !memory.put("src:telegram-native::-10_1", "FLAC"),
                    "Less-specific unchanged-file legends remain protected");
            check(
                    memory.forgetMessages(-10, new long[] {1, 1}) == 2,
                    "Only exact Telegram message and child legends removed");
            check(
                    memory.put("src:telegram-native::-10_1", "FLAC"),
                    "An edited lower-quality file no longer inherits old24-bit legend");
            check(
                    memory.get("src:telegram-native::-10_11").equals("FLAC 24bit")
                            && memory.get("src:telegram-native::-20_1").equals("FLAC 24bit"),
                    "Neighbour messages/chats preserved");
            check(
                    memory.get("abcdefghijk").equals("FLAC 24bit")
                            && memory.get("src:other::-10_1").equals("FLAC 24bit"),
                    "YouTube and other-source legends never removed by Telegram events");
            memory.save();
            LegendMemory restored = new LegendMemory(saved);
            check(
                    restored.get("src:telegram-native::-10_1").equals("FLAC")
                            && restored.get("src:telegram-native::-10_1_2").isEmpty(),
                    "Exact family change persists");
            check(
                    restored.forgetChat(-10) == 2
                            && restored.get("src:telegram-native::-20_1").equals("FLAC 24bit"),
                    "Chat reset remains source/chat scoped");
            TelegramLegendRows rows = new TelegramLegendRows();
            Object first = rows.begin("song");
            java.util.List<org.json.JSONObject> matches =
                    java.util.Arrays.asList(
                            new org.json.JSONObject().put("chat", -10).put("message", 1),
                            new org.json.JSONObject().put("chat", -10).put("message", 11),
                            new org.json.JSONObject().put("chat", -20).put("message", 1));
            check(
                    rows.publish(first, "song", matches),
                    "Current legend search publishes actual rows");
            TelegramLegendRows.Snapshot previous = rows.snapshot();
            rows.forget(-10, java.util.Collections.singleton(1L));
            check(
                    rows.snapshot().rows.size() == 2
                            && rows.snapshot().rows.get(0) == matches.get(1),
                    "Recent legend filtering is message/chat exact");
            Object next = rows.begin("song");
            check(
                    next != null && !rows.publish(first, "song", matches),
                    "Same-query replay cannot resurrect an older pending search");
            java.util.concurrent.atomic.AtomicBoolean remembered =
                    new java.util.concurrent.atomic.AtomicBoolean();
            check(
                    !rows.commit(previous, () -> remembered.set(true)) && !remembered.get(),
                    "Invalidated matched rows cannot re-add remembered legends");
            check(
                    rows.publish(next, "song", java.util.Collections.singletonList(matches.get(2))),
                    "A fresh same-query search can publish current data");
            rows.forget(-10, null);
            check(
                    rows.snapshot().rows.size() == 1
                            && rows.snapshot().rows.get(0).optLong("chat") == -20,
                    "Chat reset preserves unrelated recent rows");
            System.out.println("PASS: " + checks + " Telegram legend-family checks");
        } finally {
            for (File file : dir.listFiles()) file.delete();
            dir.delete();
        }
    }
}
