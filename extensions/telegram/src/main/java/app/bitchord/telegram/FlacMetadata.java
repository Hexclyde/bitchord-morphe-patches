package app.bitchord.telegram;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Keeps existing FLAC tags that the native download tagger was not asked to replace. */
public final class FlacMetadata {
    private FlacMetadata() {}

    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_BLOCKS = 1024;
    private static final int MAX_FIELDS = 65536;

    /** Returns metadata only; the native writer still copies the original audio frames. */
    public static byte[] mergeMetadata(byte[] original, byte[] replacement, int audioStart) {
        List<Block> before;
        try {
            before = blocks(original, audioStart);
        } catch (RuntimeException | OutOfMemoryError invalid) {
            return null;
        }
        if (before == null) return null;
        try {
            List<Block> after = blocks(replacement, replacement == null ? 0 : replacement.length);
            if (after == null || !same(before.get(0), after.get(0)))
                return prefix(original, audioStart);
            Comments old = comments(before), next = comments(after);
            if (old == null || next == null) return prefix(original, audioStart);
            Set<String> replaced = new HashSet<>();
            for (Field field : next.fields) if (field.hasValue) replaced.add(field.key);
            if (replaced.contains("LYRICS")) replaced.add("BITCHORD_LYRICS");
            List<Field> fields = new ArrayList<>();
            for (Field field : old.fields) if (!replaced.contains(field.key)) fields.add(field);
            for (Field field : next.fields) if (field.hasValue) fields.add(field);
            if (fields.size() > MAX_FIELDS) return prefix(original, audioStart);
            byte[] comment = comment(old.vendor == null ? next.vendor : old.vendor, fields);
            if (comment == null) return prefix(original, audioStart);
            List<Block> pictures = new ArrayList<>();
            boolean newFront = false;
            for (Block block : after) {
                if (block.type != 6) continue;
                if (!picture(block)) return prefix(original, audioStart);
                pictures.add(block);
                newFront |= be32(block.data, block.start) == 3;
            }
            List<Block> merged = new ArrayList<>();
            boolean wroteComment = false;
            for (Block block : before) {
                if (block.type == 4) {
                    merged.add(new Block(comment, 4, 0, comment.length));
                    wroteComment = true;
                } else if (!(block.type == 6 && newFront && be32(block.data, block.start) == 3)) {
                    merged.add(block);
                }
            }
            if (!wroteComment && !fields.isEmpty())
                merged.add(new Block(comment, 4, 0, comment.length));
            merged.addAll(pictures);
            long size = 4;
            for (Block block : merged) size += 4L + block.length;
            if (size > MAX_BYTES || merged.size() > MAX_BLOCKS) return prefix(original, audioStart);
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) size);
            out.write('f');
            out.write('L');
            out.write('a');
            out.write('C');
            for (int i = 0; i < merged.size(); i++) {
                Block block = merged.get(i);
                out.write(block.type | (i == merged.size() - 1 ? 128 : 0));
                out.write(block.length >>> 16);
                out.write(block.length >>> 8);
                out.write(block.length);
                out.write(block.data, block.start, block.length);
            }
            return out.toByteArray();
        } catch (RuntimeException | OutOfMemoryError invalid) {
            return prefix(original, audioStart);
        }
    }

    private static byte[] prefix(byte[] original, int end) {
        try {
            return Arrays.copyOf(original, end);
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            return null;
        }
    }

    private static final class Block {
        final byte[] data;
        final int type, start, length;

        Block(byte[] data, int type, int start, int length) {
            this.data = data;
            this.type = type;
            this.start = start;
            this.length = length;
        }
    }

    private static List<Block> blocks(byte[] data, int end) {
        if (data == null
                || end < 42
                || end > data.length
                || end > MAX_BYTES
                || data[0] != 'f'
                || data[1] != 'L'
                || data[2] != 'a'
                || data[3] != 'C') return null;
        List<Block> blocks = new ArrayList<>();
        int offset = 4;
        boolean last = false;
        while (!last) {
            if (offset > end - 4 || blocks.size() >= MAX_BLOCKS) return null;
            int flags = data[offset] & 255;
            int type = flags & 127;
            int length =
                    ((data[offset + 1] & 255) << 16)
                            | ((data[offset + 2] & 255) << 8)
                            | (data[offset + 3] & 255);
            offset += 4;
            if (type == 127 || length > end - offset) return null;
            if (blocks.isEmpty() ? type != 0 || length != 34 : type == 0) return null;
            if (type == 6 && length < 4) return null;
            blocks.add(new Block(data, type, offset, length));
            offset += length;
            last = (flags & 128) != 0;
        }
        return offset == end ? blocks : null;
    }

    private static boolean same(Block a, Block b) {
        if (a.type != b.type || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++)
            if (a.data[a.start + i] != b.data[b.start + i]) return false;
        return true;
    }

    private static final class Field {
        final byte[] data;
        final int start, length;
        final String key;
        final boolean hasValue;

        Field(byte[] data, int start, int length, int equals) {
            this.data = data;
            this.start = start;
            this.length = length;
            this.key =
                    new String(data, start, equals - start, StandardCharsets.US_ASCII)
                            .toUpperCase(Locale.ROOT);
            this.hasValue = equals + 1 < start + length;
        }
    }

    private static final class Comments {
        byte[] vendor;
        final List<Field> fields = new ArrayList<>();
    }

    private static Comments comments(List<Block> blocks) {
        Comments result = new Comments();
        boolean found = false;
        for (Block block : blocks) {
            if (block.type != 4) continue;
            if (found) return null;
            found = true;
            int end = block.start + block.length, at = block.start;
            int vendor = le32(block.data, at, end);
            if (vendor < 0 || vendor > end - at - 4) return null;
            at += 4;
            result.vendor = Arrays.copyOfRange(block.data, at, at + vendor);
            at += vendor;
            int count = le32(block.data, at, end);
            if (count < 0 || count > MAX_FIELDS || count > (end - at - 4) / 4) return null;
            at += 4;
            for (int i = 0; i < count; i++) {
                int length = le32(block.data, at, end);
                if (length < 0 || length > end - at - 4) return null;
                at += 4;
                int equals = at;
                while (equals < at + length && block.data[equals] != '=') {
                    int c = block.data[equals] & 255;
                    if (c < 32 || c > 125) return null;
                    equals++;
                }
                if (equals == at || equals == at + length) return null;
                result.fields.add(new Field(block.data, at, length, equals));
                at += length;
            }
            if (at != end) return null;
        }
        return result;
    }

    private static byte[] comment(byte[] vendor, List<Field> fields) {
        if (vendor == null) vendor = new byte[0];
        long length = 8L + vendor.length;
        for (Field field : fields) length += 4L + field.length;
        if (length > 0xffffff) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) length);
        le32(out, vendor.length);
        out.write(vendor, 0, vendor.length);
        le32(out, fields.size());
        for (Field field : fields) {
            le32(out, field.length);
            out.write(field.data, field.start, field.length);
        }
        return out.toByteArray();
    }

    private static int le32(byte[] data, int at, int end) {
        if (at < 0 || at > end - 4 || (data[at + 3] & 128) != 0) return -1;
        return (data[at] & 255)
                | ((data[at + 1] & 255) << 8)
                | ((data[at + 2] & 255) << 16)
                | ((data[at + 3] & 255) << 24);
    }

    private static void le32(ByteArrayOutputStream out, int value) {
        out.write(value);
        out.write(value >>> 8);
        out.write(value >>> 16);
        out.write(value >>> 24);
    }

    private static int be32(byte[] data, int at) {
        return ((data[at] & 255) << 24)
                | ((data[at + 1] & 255) << 16)
                | ((data[at + 2] & 255) << 8)
                | (data[at + 3] & 255);
    }

    private static boolean picture(Block block) {
        int end = block.start + block.length, at = block.start;
        if (block.length < 32) return false;
        at += 4;
        int mime = be32(block.data, at);
        at += 4;
        if (mime < 0 || mime > end - at - 24) return false;
        at += mime;
        int description = be32(block.data, at);
        at += 4;
        if (description < 0 || description > end - at - 20) return false;
        at += description + 16;
        int bytes = be32(block.data, at);
        return bytes >= 0 && bytes == end - at - 4;
    }
}
