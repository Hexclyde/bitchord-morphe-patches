package app.bitchord.telegram;

import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;

import java.util.*;
import java.util.zip.Inflater;

/**
 * Albums shared as zip files: each song inside becomes a file of its own in the saved index, named
 * from its file name and with its length and format from its header, so it can be found, matched
 * and played like any other.
 */
final class ZipAlbums {
    private ZipAlbums() {}

    /** Bytes read from the start of each song: its local header and enough for any tag. */
    static final int HEAD = 64 * 1024;

    /** No album has more songs than this; anything beyond is left out. */
    static final int MAX_SONGS = 500;

    /** A zip post, as the index queues it, or null for anything else. */
    static JSONObject descriptor(TdApi.Message m) throws Exception {
        if (m == null || m.chatId == 0 || m.id <= 0 || !MediaPolicy.savable(m)) return null;
        if (!(m.content instanceof TdApi.MessageDocument)) return null;
        TdApi.Document d = ((TdApi.MessageDocument) m.content).document;
        if (d == null || d.document == null || d.fileName == null) return null;
        if (!d.fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) return null;
        JSONObject zip =
                new JSONObject()
                        .put("chat", m.chatId)
                        .put("message", m.id)
                        .put("file", d.document.id)
                        .put("size", d.document.size)
                        .put("fileName", d.fileName);
        String unique = d.document.remote == null ? null : d.document.remote.uniqueId;
        if (unique != null && !unique.isEmpty()) zip.put("unique", unique);
        return zip;
    }

    /** The songs in a zip, read through {@code range}. */
    static List<JSONObject> songs(JSONObject zip, ZipDirectory.Range range) throws Exception {
        long chat = zip.getLong("chat"), message = zip.getLong("message");
        List<ZipDirectory.Entry> entries = ZipDirectory.read(range, zip.getLong("size"));
        String zipArtist = TrackDetails.fromFileName(zip.optString("fileName"), "")[1];
        List<JSONObject> rows = new ArrayList<>();
        for (ZipDirectory.Entry e : entries) {
            if (!e.playable()) continue;
            if (rows.size() >= MAX_SONGS) break;
            long length = Math.min(30 + 2048 + HEAD, zip.getLong("size") - e.localOffset);
            byte[] local = range.read(e.localOffset, (int) length);
            long data = ZipDirectory.dataOffset(local, e);
            int skip = (int) (data - e.localOffset);
            byte[] head = start(local, skip, e);
            JSONObject header = AudioHeader.parse(head, head.length);

            String[] name = TrackDetails.fromFileName(e.baseName(), "");
            String artist = name[1];
            if (artist.isEmpty()) {
                String folderArtist = TrackDetails.fromFileName(e.folder(), "")[1];
                artist = !folderArtist.isEmpty() ? folderArtist : zipArtist;
            }
            String ext = e.extension();
            JSONObject row =
                    new JSONObject()
                            .put("id", chat + "_" + message + "_" + e.index)
                            .put("chat", chat)
                            .put("message", message)
                            .put("entry", e.index)
                            .put("file", zip.getInt("file"))
                            .put("size", e.size)
                            .put("title", name[0])
                            .put("artist", artist)
                            .put("duration", header == null ? 0 : header.optInt("seconds"))
                            .put("format", ext)
                            .put("mime", mime(ext))
                            .put("fileName", e.baseName())
                            .put("tagged", false)
                            .put(
                                    "album",
                                    e.folder().isEmpty() ? zip.optString("fileName") : e.folder())
                            .put(
                                    "zip",
                                    new JSONObject()
                                            .put("data", data)
                                            .put("method", e.method)
                                            .put("compressed", e.compressedSize));
            if (header != null) row.put("header", header);
            if (zip.has("unique")) row.put("unique", zip.getString("unique") + "/" + e.index);
            rows.add(row);
        }
        return rows;
    }

    /** The first bytes of a song, decompressed if it was deflated. */
    static byte[] start(byte[] local, int skip, ZipDirectory.Entry e) {
        if (skip >= local.length) return new byte[0];
        if (e.method == ZipDirectory.STORED)
            return Arrays.copyOfRange(
                    local, skip, (int) Math.min(local.length, skip + Math.min(HEAD, e.size)));
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(local, skip, local.length - skip);
            byte[] out = new byte[HEAD];
            int n = 0;
            while (n < out.length && !inflater.finished() && !inflater.needsInput()) {
                int got = inflater.inflate(out, n, out.length - n);
                if (got == 0) break;
                n += got;
            }
            return Arrays.copyOf(out, n);
        } catch (Exception ex) {
            return new byte[0];
        } finally {
            inflater.end();
        }
    }

    static String mime(String ext) {
        switch (ext) {
            case "flac":
                return "audio/flac";
            case "wav":
                return "audio/wav";
            case "aif":
            case "aiff":
                return "audio/aiff";
            case "m4a":
            case "alac":
                return "audio/mp4";
            case "mp3":
                return "audio/mpeg";
            case "ogg":
                return "audio/ogg";
            case "opus":
                return "audio/opus";
            default:
                return "audio/aac";
        }
    }

    /** "-100_42_3" for the third file in the zip posted as message 42. */
    static boolean validId(String id) {
        return id != null
                && id.length() <= 48
                && id.matches("-?[1-9][0-9]*_[1-9][0-9]*_[1-9][0-9]{0,4}");
    }

    /** {chat, message, entry} of a zip song's id. */
    static long[] parse(String id) {
        String[] parts = id.split("_");
        return new long[] {
            Long.parseLong(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2])
        };
    }
}
