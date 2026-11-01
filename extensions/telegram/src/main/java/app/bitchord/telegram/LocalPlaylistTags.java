package app.bitchord.telegram;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Reads a tempo tag someone already wrote into a file: ID3v2 TBPM, FLAC and Ogg comments, MP4 tmpo.
 * Only headers are read, never audio, and a damaged or untagged file simply has no tempo.
 */
final class LocalPlaylistTags {
    private LocalPlaylistTags() {}

    static final double MIN_BPM = 40, MAX_BPM = 240;
    private static final int MAX_BLOCK = 512 * 1024;
    private static final int MAX_COMMENTS = 512;
    private static final int MAX_ATOMS = 256;
    private static final int OGG_HEAD = 64 * 1024;

    /** The tagged tempo, or null. The caller owns the stream. */
    static Double bpm(InputStream input) {
        try {
            DataInputStream data = new DataInputStream(input);
            byte[] head = new byte[12];
            data.readFully(head);
            if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') return id3(data, head);
            if (head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C')
                return flac(data, head);
            if (head[0] == 'O' && head[1] == 'g' && head[2] == 'g' && head[3] == 'S')
                return ogg(data, head);
            if (head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p')
                return mp4(data, head);
            return null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    static Double plausible(double value) {
        return value >= MIN_BPM && value <= MAX_BPM ? value : null;
    }

    private static Double parsed(String text) {
        if (text == null) return null;
        String clean = text.trim().replace(',', '.');
        int space = clean.indexOf(' ');
        if (space > 0) clean = clean.substring(0, space);
        if (!clean.matches("\\d{1,3}(?:\\.\\d{1,3})?")) return null;
        return plausible(Double.parseDouble(clean));
    }

    private static void skip(InputStream input, long count) throws IOException {
        long left = count;
        while (left > 0) {
            long moved = input.skip(left);
            if (moved <= 0) {
                if (input.read() < 0) throw new EOFException();
                moved = 1;
            }
            left -= moved;
        }
    }

    private static Double id3(DataInputStream data, byte[] head) throws IOException {
        int version = head[3];
        if (version < 2 || version > 4 || (head[5] & 0x40) != 0 && version == 2) return null;
        if ((head[5] & 0x80) != 0) return null; // Unsynchronised tags aren't worth undoing here.
        long remaining = syncsafe(head, 6);
        // The first two bytes of the frame area were already read into head.
        byte[] first = {head[10], head[11]};
        int headerSize = version == 2 ? 6 : 10;
        byte[] frame = new byte[headerSize];
        frame[0] = first[0];
        frame[1] = first[1];
        if ((head[5] & 0x40) != 0) {
            // An extended header comes first; its size field starts with those two bytes.
            byte[] rest = new byte[2];
            data.readFully(rest);
            long size =
                    version == 4
                            ? syncsafe(new byte[] {first[0], first[1], rest[0], rest[1]}, 0)
                            : big(new byte[] {first[0], first[1], rest[0], rest[1]}, 0) + 4;
            if (size < 4 || size > remaining) return null;
            skip(data, size - 4);
            remaining -= size;
            data.readFully(frame);
        } else data.readFully(frame, 2, headerSize - 2);
        while (remaining >= headerSize) {
            if (frame[0] == 0) return null;
            String name = new String(frame, 0, version == 2 ? 3 : 4, StandardCharsets.ISO_8859_1);
            long size =
                    version == 2
                            ? (frame[3] & 255L) << 16 | (frame[4] & 255L) << 8 | (frame[5] & 255L)
                            : version == 4 ? syncsafe(frame, 4) : big(frame, 4);
            remaining -= headerSize;
            if (size < 0 || size > remaining) return null;
            if (name.equals("TBPM") || name.equals("TBP")) {
                if (size > 64) return null;
                byte[] value = new byte[(int) size];
                data.readFully(value);
                return parsed(id3Text(value));
            }
            skip(data, size);
            remaining -= size;
            if (remaining < headerSize) return null;
            data.readFully(frame);
        }
        return null;
    }

    private static String id3Text(byte[] value) {
        if (value.length < 2) return null;
        String charset;
        switch (value[0]) {
            case 0:
                charset = "ISO-8859-1";
                break;
            case 1:
                charset = "UTF-16";
                break;
            case 2:
                charset = "UTF-16BE";
                break;
            case 3:
                charset = "UTF-8";
                break;
            default:
                return null;
        }
        try {
            return new String(value, 1, value.length - 1, charset).replace("\0", "");
        } catch (java.io.UnsupportedEncodingException unknown) {
            return null;
        }
    }

    private static long syncsafe(byte[] b, int at) {
        return (b[at] & 127L) << 21
                | (b[at + 1] & 127L) << 14
                | (b[at + 2] & 127L) << 7
                | (b[at + 3] & 127L);
    }

    private static long big(byte[] b, int at) {
        return (b[at] & 255L) << 24
                | (b[at + 1] & 255L) << 16
                | (b[at + 2] & 255L) << 8
                | (b[at + 3] & 255L);
    }

    private static Double flac(DataInputStream data, byte[] head) throws IOException {
        byte[] header = {head[4], head[5], head[6], head[7]};
        long skipped = 4; // Bytes of the first block's body already in head.
        for (int block = 0; block < 64; block++) {
            int type = header[0] & 127;
            boolean last = (header[0] & 128) != 0;
            long size = (header[1] & 255L) << 16 | (header[2] & 255L) << 8 | (header[3] & 255L);
            if (type == 4) {
                if (size > MAX_BLOCK) return null;
                byte[] body = new byte[(int) size];
                int have = (int) Math.min(skipped, size);
                if (have > 0) System.arraycopy(head, 8, body, 0, have);
                data.readFully(body, have, (int) size - have);
                return comments(body, 0);
            }
            if (last) return null;
            skip(data, Math.max(0, size - skipped));
            skipped = 0;
            data.readFully(header);
        }
        return null;
    }

    /** Vorbis comment list: BPM, TBPM or TEMPO. Returns null when absent or malformed. */
    private static Double comments(byte[] body, int from) {
        long vendor = little(body, from);
        if (vendor < 0) return null;
        long at = from + 4L + vendor;
        long count = little(body, at);
        if (count < 0) return null;
        at += 4;
        for (long i = 0; i < count && i < MAX_COMMENTS; i++) {
            long length = little(body, at);
            if (length < 0 || at + 4 + length > body.length) return null;
            String comment = new String(body, (int) at + 4, (int) length, StandardCharsets.UTF_8);
            int equals = comment.indexOf('=');
            if (equals > 0) {
                String key = comment.substring(0, equals).toUpperCase(Locale.ROOT);
                if (key.equals("BPM") || key.equals("TBPM") || key.equals("TEMPO"))
                    return parsed(comment.substring(equals + 1));
            }
            at += 4 + length;
        }
        return null;
    }

    private static long little(byte[] b, long at) {
        if (at < 0 || at + 4 > b.length) return -1;
        int i = (int) at;
        return (b[i] & 255L)
                | (b[i + 1] & 255L) << 8
                | (b[i + 2] & 255L) << 16
                | (b[i + 3] & 255L) << 24;
    }

    /** The comment packet sits in the first pages; one spanning past the head is left unread. */
    private static Double ogg(DataInputStream data, byte[] head) throws IOException {
        byte[] window = new byte[OGG_HEAD];
        System.arraycopy(head, 0, window, 0, head.length);
        int filled = head.length;
        int read;
        while (filled < window.length
                && (read = data.read(window, filled, window.length - filled)) > 0) filled += read;
        byte[] present = java.util.Arrays.copyOf(window, filled);
        int at = find(present, "OpusTags".getBytes(StandardCharsets.ISO_8859_1));
        int skip = 8;
        if (at < 0) {
            at = find(present, new byte[] {3, 'v', 'o', 'r', 'b', 'i', 's'});
            skip = 7;
        }
        return at < 0 ? null : comments(present, at + skip);
    }

    private static int find(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i + pattern.length <= data.length; i++) {
            for (int n = 0; n < pattern.length; n++) if (data[i + n] != pattern[n]) continue outer;
            return i;
        }
        return -1;
    }

    /** Walks moov/udta/meta/ilst/tmpo. Media data is skipped, so nothing past headers is read. */
    private static Double mp4(DataInputStream data, byte[] head) throws IOException {
        long firstSize = big(head, 0);
        if (firstSize < 12) return null;
        skip(data, firstSize - 12);
        byte[] header = new byte[8];
        for (int atom = 0; atom < MAX_ATOMS; atom++) {
            data.readFully(header);
            long size = big(header, 0);
            String name = new String(header, 4, 4, StandardCharsets.ISO_8859_1);
            if (size == 1) {
                byte[] wide = new byte[8];
                data.readFully(wide);
                size = big(wide, 0) << 32 | big(wide, 4);
                if (size < 16) return null;
                size -= 16;
            } else if (size == 0) return null; // Runs to the end of the file; it can't be moov.
            else if (size < 8) return null;
            else size -= 8;
            if (!name.equals("moov")) {
                skip(data, size);
                continue;
            }
            if (size > 16 * 1024 * 1024) return null;
            byte[] moov = new byte[(int) size];
            data.readFully(moov);
            return tmpo(moov);
        }
        return null;
    }

    private static Double tmpo(byte[] moov) {
        int[] udta = child(moov, 0, moov.length, "udta");
        if (udta == null) return null;
        int[] meta = child(moov, udta[0], udta[1], "meta");
        if (meta == null) return null;
        // "meta" is a full atom in iTunes files: four bytes of version and flags first.
        int[] ilst = child(moov, meta[0] + 4, meta[1], "ilst");
        if (ilst == null) ilst = child(moov, meta[0], meta[1], "ilst");
        if (ilst == null) return null;
        int[] tag = child(moov, ilst[0], ilst[1], "tmpo");
        if (tag == null) return null;
        int[] value = child(moov, tag[0], tag[1], "data");
        if (value == null || value[1] - value[0] < 10) return null;
        return plausible((moov[value[0] + 8] & 255) << 8 | (moov[value[0] + 9] & 255));
    }

    private static int[] child(byte[] data, int from, int to, String name) {
        int at = from;
        for (int i = 0; i < MAX_ATOMS && at + 8 <= to; i++) {
            long size = big(data, at);
            if (size < 8 || at + size > to) return null;
            if (new String(data, at + 4, 4, StandardCharsets.ISO_8859_1).equals(name))
                return new int[] {at + 8, (int) (at + size)};
            at += (int) size;
        }
        return null;
    }
}
