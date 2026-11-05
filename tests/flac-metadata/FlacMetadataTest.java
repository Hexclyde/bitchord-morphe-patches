package app.bitchord.telegram;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class FlacMetadataTest {
    static int checks;

    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static final class Block {
        final int type;
        final byte[] bytes;

        Block(int type, byte[] bytes) {
            this.type = type;
            this.bytes = bytes;
        }
    }

    static void le(ByteArrayOutputStream out, int n) {
        for (int i = 0; i < 4; i++) out.write(n >>> (i * 8));
    }

    static void be(ByteArrayOutputStream out, int n) {
        for (int i = 3; i >= 0; i--) out.write(n >>> (i * 8));
    }

    static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static void write(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }

    static byte[] comments(String... fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] vendor = utf8("Original vendor");
        le(out, vendor.length);
        write(out, vendor);
        le(out, fields.length);
        for (String field : fields) {
            byte[] bytes = utf8(field);
            le(out, bytes.length);
            write(out, bytes);
        }
        return out.toByteArray();
    }

    static byte[] picture(int type, int marker) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        be(out, type);
        be(out, 10);
        write(out, utf8("image/jpeg"));
        be(out, 0);
        for (int i = 0; i < 4; i++) be(out, 0);
        be(out, 1);
        out.write(marker);
        return out.toByteArray();
    }

    static byte[] prefix(Block... extra) {
        List<Block> blocks = new ArrayList<>();
        blocks.add(new Block(0, new byte[34]));
        blocks.addAll(Arrays.asList(extra));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, utf8("fLaC"));
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            out.write(block.type | (i == blocks.size() - 1 ? 128 : 0));
            out.write(block.bytes.length >>> 16);
            out.write(block.bytes.length >>> 8);
            out.write(block.bytes.length);
            write(out, block.bytes);
        }
        return out.toByteArray();
    }

    static List<Block> read(byte[] data) {
        List<Block> blocks = new ArrayList<>();
        int at = 4;
        while (at < data.length) {
            int flags = data[at++] & 255;
            int length =
                    ((data[at++] & 255) << 16) | ((data[at++] & 255) << 8) | (data[at++] & 255);
            blocks.add(new Block(flags & 127, Arrays.copyOfRange(data, at, at + length)));
            at += length;
            if ((flags & 128) != 0) {
                check(at == data.length, "Exactly one complete metadata prefix");
                break;
            }
        }
        return blocks;
    }

    static int integer(byte[] bytes, int at) {
        return (bytes[at] & 255)
                | ((bytes[at + 1] & 255) << 8)
                | ((bytes[at + 2] & 255) << 16)
                | ((bytes[at + 3] & 255) << 24);
    }

    static List<String> fields(byte[] metadata) {
        List<String> result = new ArrayList<>();
        int found = 0;
        for (Block block : read(metadata))
            if (block.type == 4) {
                found++;
                byte[] b = block.bytes;
                int at = 4 + integer(b, 0);
                int count = integer(b, at);
                at += 4;
                for (int i = 0; i < count; i++) {
                    int n = integer(b, at);
                    at += 4;
                    result.add(new String(b, at, n, StandardCharsets.UTF_8));
                    at += n;
                }
                check(at == b.length, "No comment framing bit or trailing garbage");
            }
        check(found == 1, "Single Vorbis comment block");
        return result;
    }

    static byte[] merge(byte[] original, byte[] next) {
        return FlacMetadata.mergeMetadata(original, next, original.length);
    }

    public static void main(String[] args) {
        byte[] front = picture(3, 11), back = picture(4, 22), app = {5, 6, 7};
        byte[] original =
                prefix(
                        new Block(2, app),
                        new Block(
                                4,
                                comments(
                                        "TITLE=Old",
                                        "ARTIST=One",
                                        "ARTIST=Two",
                                        "ALBUM=Original album",
                                        "DATE=1999-10-31",
                                        "LYRICS=Original words",
                                        "BITCHORD_LYRICS=Original timing",
                                        "MUSICBRAINZ_TRACKID=abc",
                                        "CUSTOM=é=值")),
                        new Block(6, front),
                        new Block(6, back));
        byte[] originalCopy = original.clone();
        byte[] next = prefix(new Block(4, comments("title=New")));
        byte[] merged = merge(original, next);
        List<String> fields = fields(merged);
        check(
                fields.contains("title=New") && !fields.contains("TITLE=Old"),
                "Only supplied title replaced case-insensitively");
        for (String field :
                new String[] {
                    "ARTIST=One",
                    "ARTIST=Two",
                    "ALBUM=Original album",
                    "DATE=1999-10-31",
                    "LYRICS=Original words",
                    "BITCHORD_LYRICS=Original timing",
                    "MUSICBRAINZ_TRACKID=abc",
                    "CUSTOM=é=值"
                }) check(fields.contains(field), "Preserved " + field);
        List<Block> blocks = read(merged);
        check(
                blocks.stream().filter(b -> b.type == 6).count() == 2,
                "Both original pictures retained without new cover");
        check(
                blocks.stream().anyMatch(b -> b.type == 2 && Arrays.equals(b.bytes, app)),
                "Application block preserved");
        check(Arrays.equals(original, originalCopy), "Original prefix unchanged");
        byte[] withCover =
                merge(
                        original,
                        prefix(
                                new Block(4, comments("ARTIST=New artist")),
                                new Block(6, picture(3, 33))));
        List<Block> covered = read(withCover);
        check(
                covered.stream().anyMatch(b -> b.type == 6 && Arrays.equals(b.bytes, back)),
                "Back cover survives front-cover replacement");
        check(
                covered.stream().noneMatch(b -> b.type == 6 && Arrays.equals(b.bytes, front)),
                "Old front cover replaced");
        check(
                covered.stream().filter(b -> b.type == 6).count() == 2,
                "Exactly one front cover and one back cover");
        List<String> changed =
                fields(merge(original, prefix(new Block(4, comments("LYRICS=New words")))));
        check(changed.contains("LYRICS=New words"), "New portable lyrics installed");
        check(
                changed.stream().noneMatch(s -> s.startsWith("BITCHORD_LYRICS=")),
                "Old word timings cannot accompany different new lyrics");
        check(changed.contains("DATE=1999-10-31"), "Lyrics replacement preserves release date");
        check(
                fields(merge(original, prefix(new Block(4, comments("ALBUM=")))))
                        .contains("ALBUM=Original album"),
                "Blank replacement cannot erase album");
        byte[] audio = {(byte) 255, (byte) 248, 1, 2, 3, 4, 5};
        byte[] whole = Arrays.copyOf(original, original.length + audio.length);
        System.arraycopy(audio, 0, whole, original.length, audio.length);
        check(
                Arrays.equals(merged, FlacMetadata.mergeMetadata(whole, next, original.length)),
                "Same bounded prefix for streaming and full-file input");
        check(
                Arrays.equals(audio, Arrays.copyOfRange(whole, original.length, whole.length)),
                "Audio frames unchanged");
        check(
                Arrays.equals(original, merge(original, new byte[] {1, 2, 3})),
                "Unrecognised replacement keeps complete original prefix");
        check(
                Arrays.equals(original, merge(original, new byte[16 * 1024 * 1024 + 1])),
                "Oversized replacement keeps original metadata");
        byte[] brokenComments = prefix(new Block(4, new byte[] {-1, -1, -1, -1}));
        check(
                Arrays.equals(original, merge(original, brokenComments)),
                "Malformed unsigned comment length cannot drop tags");
        byte[] duplicateComments =
                prefix(new Block(4, comments("TITLE=A")), new Block(4, comments("TITLE=B")));
        check(
                Arrays.equals(original, merge(original, duplicateComments)),
                "Ambiguous replacement comments declined");
        byte[] malformedPicture = prefix(new Block(6, new byte[] {0, 0, 0, 3}));
        check(
                Arrays.equals(original, merge(original, malformedPicture)),
                "Truncated picture cannot replace valid cover");
        check(
                FlacMetadata.mergeMetadata(original, next, original.length - 1) == null,
                "Incorrect audio boundary declined");
        check(FlacMetadata.mergeMetadata(new byte[42], next, 42) == null, "Non-FLAC declined");
        check(
                FlacMetadata.mergeMetadata(null, next, 0) == null,
                "Null input declined without throwing");
        check(
                Arrays.equals(original, merge(original, null)),
                "Null replacement keeps complete original metadata");
        Random random = new Random(9639);
        for (int i = 0; i < 512; i++) {
            byte[] mutation = next.clone();
            mutation[random.nextInt(mutation.length)] = (byte) random.nextInt(256);
            byte[] result = merge(original, mutation);
            check(result != null, "Malformed replacement must keep the recognised original");
            check(
                    fields(result).contains("DATE=1999-10-31"),
                    "Replacement mutation must not erase unrelated date");
        }
        System.out.println("PASS: " + checks + " FLAC metadata preservation checks");
    }
}
