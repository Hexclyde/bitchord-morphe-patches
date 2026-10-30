package app.bitchord.soulseek;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The immutable byte identity shared by every download route. */
public final class BulkManifest {
    public static final int BLOCK_SIZE = 256 * 1024;
    public static final long MAX_SIZE = 256L * 1024 * 1024;
    public static final int MAX_MANIFEST = BLOCK_SIZE;
    public final byte[] bytes;
    public final String sha256, fileSha256, cid;
    public final long size;
    public final List<Block> blocks;

    public static final class Block {
        public final String cid, sha256;
        public final int size;

        Block(String cid, String sha256, int size) {
            this.cid = cid;
            this.sha256 = sha256;
            this.size = size;
        }

        public void verify(byte[] bytes) throws IOException {
            if (bytes.length != size || !sha256.equals(hash(bytes)))
                throw new IOException("Downloaded block failed verification.");
        }
    }

    private BulkManifest(byte[] source, String expected) throws Exception {
        if (source.length == 0 || source.length > MAX_MANIFEST)
            throw new IOException("Invalid file manifest size.");
        bytes = source.clone();
        sha256 = hash(bytes);
        if (!hex64(expected) || !sha256.equals(expected))
            throw new IOException("File manifest does not match the signed offer.");
        cid = cidForHash(sha256);
        JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (json.getInt("version") != 1 || json.getInt("blockSize") != BLOCK_SIZE)
            throw new IOException("Unsupported file manifest.");
        size = json.getLong("size");
        fileSha256 = json.getString("sha256");
        if (size <= 0 || size > MAX_SIZE || !hex64(fileSha256))
            throw new IOException("Invalid file identity.");
        JSONArray entries = json.getJSONArray("blocks");
        int count = (int) ((size + BLOCK_SIZE - 1) / BLOCK_SIZE);
        if (entries.length() != count) throw new IOException("Invalid block count.");
        List<Block> parsed = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            JSONObject item = entries.getJSONObject(i);
            String hash = item.getString("sha256"), blockCid = item.getString("cid");
            int length = item.getInt("size");
            int wanted = (int) Math.min(BLOCK_SIZE, size - (long) i * BLOCK_SIZE);
            if (!hex64(hash) || !cidForHash(hash).equals(blockCid) || length != wanted)
                throw new IOException("Invalid block identity.");
            parsed.add(new Block(blockCid, hash, length));
        }
        blocks = Collections.unmodifiableList(parsed);
    }

    public static BulkManifest parse(byte[] bytes, String expectedSha256) throws IOException {
        try {
            return new BulkManifest(bytes, expectedSha256);
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Invalid file manifest.", error);
        }
    }

    public static String hash(byte[] bytes) {
        return hex(digest().digest(bytes));
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            result[i * 2] = alphabet[(bytes[i] & 255) >>> 4];
            result[i * 2 + 1] = alphabet[bytes[i] & 15];
        }
        return new String(result);
    }

    public static boolean hex64(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    public static String cidForHash(String hash) {
        if (!hex64(hash)) throw new IllegalArgumentException("Invalid SHA-256");
        byte[] raw = new byte[36];
        raw[0] = 1;
        raw[1] = 0x55;
        raw[2] = 0x12;
        raw[3] = 0x20;
        for (int i = 0; i < 32; i++)
            raw[i + 4] = (byte) Integer.parseInt(hash.substring(i * 2, i * 2 + 2), 16);
        StringBuilder encoded = new StringBuilder("b");
        String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
        int accumulator = 0, bits = 0;
        for (byte b : raw) {
            accumulator = (accumulator << 8) | (b & 255);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                encoded.append(alphabet.charAt((accumulator >>> bits) & 31));
            }
        }
        if (bits != 0) encoded.append(alphabet.charAt((accumulator << (5 - bits)) & 31));
        return encoded.toString();
    }
}
