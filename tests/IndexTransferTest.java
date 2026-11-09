package app.bitchord.telegram;

import org.json.JSONObject;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

public final class IndexTransferTest {
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    static JSONObject row(long chat, long message, int entry, String title) throws Exception {
        return new JSONObject()
                .put("id", chat + "_" + message + (entry > 0 ? "_" + entry : ""))
                .put("chat", chat)
                .put("message", message)
                .put("entry", entry)
                .put("file", 1234)
                .put("cover", 99)
                .put("title", title)
                .put("artist", "Artist")
                .put("format", "flac")
                .put("size", 1000)
                .put("duration", 200)
                .put("unique", "U" + message)
                .put(
                        "header",
                        new JSONObject().put("codec", "flac").put("bits", 24).put("rate", 96000));
    }

    public static void main(String[] args) throws Exception {
        long chat = -100;
        IndexStore.Memory store = new IndexStore.Memory();
        List<JSONObject> rows = new ArrayList<>();
        for (int m = 1; m <= 1500; m++) rows.add(row(chat, m, 0, "Song " + m));
        // A zip post with 700 songs straddles a page boundary.
        for (int e = 1; e <= 700; e++)
            rows.add(
                    row(chat, 1600, e, "Zip song " + e)
                            .put(
                                    "zip",
                                    new JSONObject()
                                            .put("data", 10)
                                            .put("method", 0)
                                            .put("compressed", 5)));
        store.put(chat, rows, 1);
        AtomicLong clock = new AtomicLong(1_000_000);
        TelegramIndex source = new TelegramIndex(store, new MainChannelTest.Chats(), clock::get);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IndexTransfer.Section section = new IndexTransfer.Section();
        section.chat = chat;
        section.title = "Lossless";
        section.complete = true;
        section.newest = 1600;
        IndexTransfer.write(
                out,
                new JSONObject().put("format", IndexTransfer.INDEX).put("version", 1),
                List.of(section),
                source::exportRows);
        IndexTransfer.Contents read =
                IndexTransfer.read(new ByteArrayInputStream(out.toByteArray()));
        check(read.sections.size() == 1, "One chat");
        List<JSONObject> back = read.sections.get(0).rows;
        check(
                back.size() == 2200,
                "Every file, across pages and inside the large post: " + back.size());
        Set<String> ids = new HashSet<>();
        for (JSONObject r : back) ids.add(r.getString("id"));
        check(ids.size() == 2200, "Each once");
        JSONObject first = back.get(0);
        check(
                first.getInt("file") == 0 && !first.has("cover"),
                "No file numbers from the other phone");
        check(first.getJSONObject("header").getInt("bits") == 24, "Format details kept");

        // Into a phone that hasn't read the chat: built from the file, then topped up.
        IndexStore.Memory other = new IndexStore.Memory();
        TelegramIndex mine = new TelegramIndex(other, new MainChannelTest.Chats(), clock::get);
        other.put(chat, List.of(row(chat, 5, 0, "Own copy").put("file", 77)), 1);
        int added = mine.importSection(read.sections.get(0));
        check(added == 2199, "Added all but the one it had: " + added);
        check(other.row(chat, 5, 0).getInt("file") == 77, "Its own file kept");
        check(mine.built(chat), "Counts as built");
        check(mine.search(Set.of(chat), "zip song 650", true).size() == 1, "Searchable");

        // Someone else's file is read strictly.
        ByteArrayOutputStream hostile = new ByteArrayOutputStream();
        try (Writer w = new OutputStreamWriter(new GZIPOutputStream(hostile), "UTF-8")) {
            w.write("{\"format\":\"bitchord-index\",\"version\":1}\n");
            w.write("{\"chat\":-100,\"complete\":true}\n");
            w.write(row(-999, 1, 0, "Other chat").toString() + "\n");
            w.write(row(-100, -5, 0, "Bad id").toString() + "\n");
            w.write(row(-100, 9, 0, "").toString() + "\n");
            w.write(
                    row(-100, 10, 0, "Fine")
                                    .put("evil", "x")
                                    .put("cue", new JSONObject().put("source", "exe"))
                                    .toString()
                            + "\n");
            w.write(row(-100, 11, 0, "Good\u0007 one").put("extra", 1).toString() + "\n");
            w.write("{\"end\":true}\n");
        }
        List<JSONObject> kept =
                IndexTransfer.read(new ByteArrayInputStream(hostile.toByteArray()))
                        .sections
                        .get(0)
                        .rows;
        check(kept.size() == 1, "Only the sensible row: " + kept);
        check(
                !kept.get(0).has("extra") && kept.get(0).getString("title").equals("Good  one"),
                "Unknown fields and control characters dropped");
        try {
            IndexTransfer.read(new ByteArrayInputStream(new byte[] {1, 2, 3}));
            check(false, "Not gzip");
        } catch (IOException e) {
            check(true, "Refused");
        }
        check(
                IndexTransfer.forChat(IndexTransfer.fileName(-1001234, "20261029"), -1001234),
                "Named for its chat");
        check(
                !IndexTransfer.forChat("bitchord-index-1001234-x.jsonl.gz", -100123),
                "Not another chat's");

        // A paused index keeps its files, backs up as a whole non-empty file and can be reloaded.
        mine.enabled = false;
        check(other.count(chat) == 2200, "Pausing deletes nothing");
        IndexTransfer.Section paused = mine.section(chat, "Paused");
        check(paused.newest == 1600, "A paused index still knows its newest post");
        File backup = File.createTempFile("backup", ".gz");
        try {
            int[] closes = {0};
            try (OutputStream file =
                    new FilterOutputStream(new FileOutputStream(backup)) {
                        @Override
                        public void write(byte[] b, int off, int len) throws IOException {
                            out.write(b, off, len);
                        }

                        @Override
                        public void close() throws IOException {
                            closes[0]++;
                            super.close();
                        }
                    }) {
                IndexTransfer.write(
                        file,
                        new JSONObject().put("format", IndexTransfer.BACKUP).put("version", 1),
                        List.of(paused),
                        mine::exportRows);
                check(closes[0] == 0, "Writing leaves the stream for its owner to close once");
            }
            check(closes[0] == 1, "Closed once");
            check(backup.length() > 100, "Not 0 KB: " + backup.length());
            try (InputStream in = new FileInputStream(backup)) {
                IndexTransfer.check(in);
            }
            try (InputStream in = new FileInputStream(backup)) {
                check(
                        IndexTransfer.read(in).sections.get(0).rows.size() == 2200,
                        "Every file written while paused");
            }

            // Delivering to the chosen place: whole, or an error.
            ByteArrayOutputStream chosen = new ByteArrayOutputStream();
            IndexTransfer.deliver(backup, chosen);
            check(chosen.size() == backup.length(), "Delivered every byte");
            IndexTransfer.check(new ByteArrayInputStream(chosen.toByteArray()));
            IndexTransfer.verifyDelivered(backup, new ByteArrayInputStream(chosen.toByteArray()));
            byte[] altered = chosen.toByteArray();
            altered[altered.length / 2] ^= 1;
            try {
                IndexTransfer.verifyDelivered(backup, new ByteArrayInputStream(altered));
                check(false, "A provider must commit exactly the backup written");
            } catch (IOException expected) {
                check(true, "Changed saved bytes rejected");
            }
            try {
                IndexTransfer.deliver(
                        backup,
                        new OutputStream() {
                            @Override
                            public void write(int b) throws IOException {
                                throw new IOException("Disk full");
                            }
                        });
                check(false, "A failed write is never a success");
            } catch (IOException e) {
                check(e.getMessage().equals(IndexTransfer.CUT_SHORT), "Says it stopped");
            }
            File empty = File.createTempFile("empty", ".gz");
            try {
                IndexTransfer.deliver(empty, new ByteArrayOutputStream());
                check(false, "An empty file is never delivered");
            } catch (IOException e) {
                check(true, "Refused");
            } finally {
                empty.delete();
            }
            // Reloaded into a paused index too: the files come back.
            IndexStore.Memory fresh = new IndexStore.Memory();
            TelegramIndex reloaded =
                    new TelegramIndex(fresh, new MainChannelTest.Chats(), clock::get);
            reloaded.enabled = false;
            try (InputStream in = new FileInputStream(backup)) {
                check(
                        reloaded.importSection(IndexTransfer.read(in).sections.get(0)) == 2200,
                        "Reloads while paused");
            }
            check(fresh.count(chat) == 2200, "Stored");

            // What is not a whole file is refused before anyone is told it worked.
            byte[] whole = chosen.toByteArray();
            for (byte[] bad :
                    new byte[][] {
                        new byte[0],
                        Arrays.copyOf(whole, whole.length / 2),
                        Arrays.copyOf(whole, whole.length - 4)
                    }) {
                try {
                    IndexTransfer.check(new ByteArrayInputStream(bad));
                    check(false, "Not a whole file: " + bad.length);
                } catch (IOException e) {
                    check(true, "Refused");
                }
            }
        } finally {
            backup.delete();
        }

        // A write cut short (cancelled, or a read failing) deletes nothing and finishes nothing.
        ByteArrayOutputStream cut = new ByteArrayOutputStream();
        try {
            IndexTransfer.write(
                    cut,
                    new JSONObject().put("format", IndexTransfer.BACKUP).put("version", 1),
                    List.of(section),
                    (c, m, n) -> {
                        throw new InterruptedException("cancelled");
                    });
            check(false, "Cancelled");
        } catch (InterruptedException e) {
            check(other.count(chat) == 2200 && store.count(chat) == 2200, "Nothing deleted");
        }
        for (String body :
                new String[] {
                    "{\"format\":\"bitchord-index\",\"version\":1}\n",
                    "{\"format\":\"bitchord-backup\",\"version\":1}\n{\"chat\":-100}\n",
                    "{\"format\":\"bitchord-backup\",\"version\":1}\nnot-json\n"
                }) {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
                gzip.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            try {
                IndexTransfer.check(new ByteArrayInputStream(compressed.toByteArray()));
                check(false, "Valid gzip must also contain a complete index");
            } catch (IOException expected) {
                check(true, "Incomplete or invalid content rejected");
            }
        }
        System.out.println(
                "PASS: " + checks + " index file export, import and strict reading checks.");
    }
}
