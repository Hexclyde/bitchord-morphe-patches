package app.bitchord.telegram;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Saved indexes as a file: for a backup, or for someone else in the same chat, whose phone can then
 * skip reading the chat from Telegram.
 *
 * <p>A gzip file of JSON lines: a header, then for each chat a line about it and one line per file.
 * Only what the index knows about each post is kept. Telegram's file numbers are left out: they
 * belong to one phone, and the post's own file is taken when a song plays.
 *
 * <p>A file someone else made is read strictly: only known fields, sensible sizes, and every file
 * in the chat its section names.
 */
final class IndexTransfer {
    private IndexTransfer() {}

    static final String INDEX = "bitchord-index", BACKUP = "bitchord-backup";
    static final int VERSION = 1;

    /** No index file holds more than this many files, or lines longer than this. */
    static final int MAX_FILES = 2_000_000, MAX_LINE = 64 * 1024;

    /** One chat's part of a file. */
    static final class Section {
        long chat, newest;
        // Export-only mutation fence; never serialized into a portable backup.
        long exportEpoch = -1;
        String title = "";
        boolean complete;

        /**
         * Where the build stood when the section was written; null in files from older versions.
         */
        JSONObject progress;

        final List<JSONObject> rows = new ArrayList<>();
    }

    /**
     * A section's build progress with only sensible values, or null: a cursor and a count for each
     * kind of post, and whether that kind is done. A file that says otherwise is read as if it
     * carried no progress, so the chat is built from where this phone already was.
     */
    static JSONObject progress(JSONObject saved) {
        try {
            if (saved == null) return null;
            JSONArray cursor = saved.getJSONArray("cursor"),
                    complete = saved.getJSONArray("complete"),
                    total = saved.getJSONArray("total"),
                    scanned = saved.getJSONArray("scanned");
            JSONArray c = new JSONArray(),
                    d = new JSONArray(),
                    t = new JSONArray(),
                    n = new JSONArray();
            if (cursor.length() != TelegramIndex.KINDS
                    || complete.length() != TelegramIndex.KINDS
                    || total.length() != TelegramIndex.KINDS
                    || scanned.length() != TelegramIndex.KINDS) return null;
            for (int k = 0; k < TelegramIndex.KINDS; k++) {
                long at = cursor.getLong(k);
                int all = total.getInt(k), done = scanned.getInt(k);
                boolean finished = complete.getBoolean(k);
                if (at < 0 || all < -1 || done < 0 || (all >= 0 && done > all)) return null;
                c.put(finished ? 0 : at);
                d.put(finished);
                t.put(all);
                n.put(done);
            }
            return new JSONObject()
                    .put("cursor", c)
                    .put("complete", d)
                    .put("total", t)
                    .put("scanned", n);
        } catch (org.json.JSONException invalid) {
            return null;
        }
    }

    /** Where files come from when writing: a chat's rows, a page at a time. */
    interface Rows {
        List<JSONObject> after(long chat, long message, int entry, int limit) throws Exception;
    }

    /**
     * Writes a header line, then each chat's section. The gzip is finished and {@code out} flushed,
     * but {@code out} is left open: its owner closes it, once.
     */
    static void write(OutputStream out, JSONObject header, List<Section> chats, Rows rows)
            throws Exception {
        OutputStream open =
                new FilterOutputStream(out) {
                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        this.out.write(b, off, len);
                    }

                    @Override
                    public void close() throws IOException {
                        flush();
                    }
                };
        try (Writer w =
                new OutputStreamWriter(new GZIPOutputStream(open), StandardCharsets.UTF_8)) {
            w.write(header.toString());
            w.write('\n');
            for (Section s : chats) {
                w.write(
                        new JSONObject()
                                .put("chat", s.chat)
                                .put("title", s.title)
                                .put("newest", s.newest)
                                .put("complete", s.complete)
                                .put("progress", s.progress == null ? JSONObject.NULL : s.progress)
                                .toString());
                w.write('\n');
                // Include the archive entry in the cursor: one post may span several pages.
                long lastMessage = 0, lastEntry = -1;
                while (true) {
                    List<JSONObject> page = rows.after(s.chat, lastMessage, (int) lastEntry, 1000);
                    boolean wrote = false;
                    for (JSONObject row : page) {
                        long message = row.optLong("message"), entry = row.optLong("entry");
                        if (message < lastMessage || (message == lastMessage && entry <= lastEntry))
                            continue;
                        lastMessage = message;
                        lastEntry = entry;
                        wrote = true;
                        JSONObject clean = clean(row, s.chat);
                        if (clean != null) {
                            w.write(clean.toString());
                            w.write('\n');
                        }
                    }
                    if (!wrote || page.size() < 1000) break;
                }
                w.write("{\"end\":true}\n");
            }
        }
    }

    static final String EMPTY =
            "The backup came out empty or damaged, so nothing was saved. Your saved index is"
                    + " untouched.";

    /**
     * Throws unless the stream is a whole index or backup file: gzip that reads to its end (its
     * checksum is verified there) after a header of ours. Nothing is kept in memory.
     */
    static void check(InputStream in) throws IOException {
        try {
            BufferedReader r =
                    new BufferedReader(
                            new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
            JSONObject header = new JSONObject(String.valueOf(line(r)));
            String format = header.optString("format");
            if (!format.equals(INDEX) && !format.equals(BACKUP)) throw new IOException(EMPTY);
            if (header.optInt("version", -1) != VERSION) throw new IOException(EMPTY);
            boolean section = false;
            long chat = 0;
            int count = 0;
            String text;
            while ((text = line(r)) != null) {
                if (text.isEmpty()) continue;
                JSONObject row = new JSONObject(text);
                if (!section) {
                    if (!row.has("chat")) throw new IOException(EMPTY);
                    chat = row.getLong("chat");
                    section = true;
                } else if (row.optBoolean("end")) section = false;
                else {
                    if (++count > MAX_FILES || clean(row, chat) == null)
                        throw new IOException(EMPTY);
                }
            }
            if (section || (format.equals(INDEX) && count == 0)) throw new IOException(EMPTY);
        } catch (IOException e) {
            if (EMPTY.equals(e.getMessage())) throw e;
            throw new IOException(EMPTY, e);
        } catch (org.json.JSONException e) {
            throw new IOException(EMPTY, e);
        }
    }

    static final String CUT_SHORT =
            "Saving the file stopped part-way. Try another place; your saved index is untouched.";

    /**
     * Copies a checked file to where the user chose, then flushes. The caller closes {@code out}
     * once; a write that fails or comes up short is an error, never a success.
     */
    static void deliver(File file, OutputStream out) throws IOException {
        long written = 0;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                written += n;
            }
            out.flush();
        } catch (IOException e) {
            throw new IOException(CUT_SHORT, e);
        }
        if (written == 0 || written != file.length()) throw new IOException(CUT_SHORT);
    }

    static void verifyDelivered(File file, InputStream saved) throws IOException {
        try (InputStream expected = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024], actual = new byte[64 * 1024];
            int count;
            while ((count = expected.read(buffer)) != -1) {
                int read = 0;
                while (read < count) {
                    int n = saved.read(actual, read, count - read);
                    if (n <= 0) throw new IOException(CUT_SHORT);
                    read += n;
                }
                for (int i = 0; i < count; i++)
                    if (buffer[i] != actual[i]) throw new IOException(CUT_SHORT);
            }
            if (saved.read() != -1) throw new IOException(CUT_SHORT);
        }
    }

    /** The header of a file and its sections. */
    static final class Contents {
        JSONObject header;
        final List<Section> sections = new ArrayList<>();
    }

    /** Reads a file, refusing anything that isn't one of ours. */
    static Contents read(InputStream in) throws Exception {
        BufferedReader r =
                new BufferedReader(
                        new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
        Contents contents = new Contents();
        String line = line(r);
        if (line == null) throw new IOException("This isn't a BitChord index or backup file.");
        contents.header = new JSONObject(line);
        String format = contents.header.optString("format");
        if (!format.equals(INDEX) && !format.equals(BACKUP))
            throw new IOException("This isn't a BitChord index or backup file.");
        if (contents.header.optInt("version") > VERSION)
            throw new IOException("This file is from a newer version of the patch. Update first.");
        Section section = null;
        int files = 0;
        while ((line = line(r)) != null) {
            if (line.isEmpty()) continue;
            JSONObject o = new JSONObject(line);
            if (section == null) {
                if (!o.has("chat")) throw new IOException("Damaged index file");
                section = new Section();
                section.chat = o.getLong("chat");
                section.title = text(o.optString("title"), 256);
                section.newest = Math.max(0, o.optLong("newest"));
                section.complete = o.optBoolean("complete");
                section.progress = progress(o.optJSONObject("progress"));
                contents.sections.add(section);
            } else if (o.optBoolean("end")) section = null;
            else {
                if (++files > MAX_FILES) throw new IOException("Index file too large");
                JSONObject clean = clean(o, section.chat);
                if (clean != null) section.rows.add(clean);
            }
        }
        return contents;
    }

    private static String line(BufferedReader r) throws IOException {
        StringBuilder b = new StringBuilder();
        int c;
        while ((c = r.read()) != -1) {
            if (c == '\n') return b.toString();
            if (b.length() >= MAX_LINE) throw new IOException("Damaged index file");
            b.append((char) c);
        }
        return b.length() == 0 ? null : b.toString();
    }

    private static final String[] TEXT = {
        "title", "artist", "format", "mime", "fileName", "unique", "coverKey", "album"
    };
    private static final String[] NUMBERS = {"size", "duration", "track", "mediaAlbum"};
    private static final Set<String> CHECK_VERDICTS =
            new HashSet<>(Arrays.asList("ok", "lossy", "suspect", "upsampled", "unknown"));

    /**
     * A file's row with only known fields, in the given chat, without Telegram's file numbers; null
     * when it isn't a sensible one.
     */
    static JSONObject clean(JSONObject row, long chat) {
        try {
            long message = row.optLong("message");
            long entry = row.optLong("entry");
            if (row.optLong("chat") != chat || message <= 0 || entry < 0 || entry > 999_999_999)
                return null;
            JSONObject out =
                    new JSONObject()
                            .put("chat", chat)
                            .put("message", message)
                            .put("entry", entry)
                            .put("id", chat + "_" + message + (entry > 0 ? "_" + entry : ""))
                            .put("file", 0)
                            .put("tagged", row.optBoolean("tagged"));
            for (String key : TEXT) if (row.has(key)) out.put(key, text(row.optString(key), 512));
            for (String key : NUMBERS)
                if (row.has(key)) out.put(key, Math.max(0, row.optLong(key)));
            if (out.optString("title").isEmpty()) return null;
            JSONObject header =
                    numbers(
                            row.optJSONObject("header"),
                            "bits",
                            "rate",
                            "channels",
                            "seconds",
                            "lfe");
            if (header != null) {
                JSONObject from = row.getJSONObject("header");
                header.put("codec", text(from.optString("codec"), 16));
                if (from.optBoolean("atmos")) header.put("atmos", true);
                out.put("header", header);
            }
            JSONObject zip = numbers(row.optJSONObject("zip"), "data", "method", "compressed");
            if (zip != null) out.put("zip", zip);
            JSONObject cue =
                    numbers(
                            row.optJSONObject("cue"),
                            "start",
                            "end",
                            "rate",
                            "channels",
                            "bits",
                            "first",
                            "total",
                            "samples",
                            "minBlock",
                            "maxBlock",
                            "maxFrame");
            if (cue != null) {
                String source = row.getJSONObject("cue").optString("source");
                if (!source.equals("flac") && !source.equals("wav")) return null;
                cue.put("source", source);
                out.put("cue", cue);
            }
            // What a check of the file found, and names it was recognised by.
            JSONObject check =
                    numbers(
                            row.optJSONObject("check"),
                            "v",
                            "cutoff",
                            "kbps",
                            "from",
                            "bits",
                            "declared",
                            "score");
            if (check != null) {
                String verdict = row.getJSONObject("check").optString("verdict");
                if (CHECK_VERDICTS.contains(verdict)) {
                    check.put("verdict", verdict);
                    JSONObject from = row.getJSONObject("check");
                    if (from.has("encoder"))
                        check.put("encoder", text(from.optString("encoder"), 64));
                    for (String key : new String[] {"strength", "lock"}) {
                        double v = from.optDouble(key, 0);
                        if (from.has(key) && Double.isFinite(v))
                            check.put(key, Math.max(0, Math.min(200, v)));
                    }
                    out.put("check", check);
                }
            }
            JSONObject names = row.optJSONObject("recognized");
            if (names != null && !names.optString("title").isEmpty()) {
                JSONObject kept = new JSONObject();
                for (String key : new String[] {"title", "artist", "id", "by"})
                    kept.put(key, text(names.optString(key), 512));
                double score = names.optDouble("score", 0);
                kept.put("score", Double.isFinite(score) ? Math.max(0, Math.min(1, score)) : 0);
                out.put("recognized", kept);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject numbers(JSONObject from, String... keys) throws Exception {
        if (from == null) return null;
        JSONObject out = new JSONObject();
        for (String key : keys) if (from.has(key)) out.put(key, Math.max(0, from.optLong(key)));
        return out;
    }

    private static String text(String s, int max) {
        s = s == null ? "" : s.replaceAll("\\p{Cntrl}", " ");
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** "bitchord-index-1001234567890-20261029.jsonl.gz": how a chat's index file is named. */
    static String fileName(long chat, String date) {
        return "bitchord-index-" + Math.abs(chat) + "-" + date + ".jsonl.gz";
    }

    /** Whether a file name is a chat's index file. */
    static boolean forChat(String name, long chat) {
        return name != null
                && name.startsWith("bitchord-index-" + Math.abs(chat) + "-")
                && name.endsWith(".jsonl.gz");
    }
}
