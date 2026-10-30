package app.bitchord.telegram;

import android.content.Context;

import org.json.JSONObject;

import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/**
 * Archive UI/service tests substitute remote I/O; the separate upload runner tests the real bridge.
 */
public final class DownloadUploadBridge {
    public static boolean rejectVerification, wrongHash;
    public static int uploads, readbacks;
    static final Map<String, byte[]> remote = new HashMap<>();
    static final Map<String, JSONObject> receipts = new HashMap<>();

    public static void reset() {
        rejectVerification = wrongHash = false;
        uploads = readbacks = 0;
        remote.clear();
        receipts.clear();
    }

    public static String upload(
            Context context, File file, String name, long chat, long user, String token)
            throws Exception {
        byte[] bytes = Files.readAllBytes(file.toPath());
        remote.put(token, bytes);
        uploads++;
        StringBuilder hash = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
            hash.append(String.format(Locale.ROOT, "%02x", b & 255));
        JSONObject row =
                new JSONObject()
                        .put("confirmed", true)
                        .put("userId", user)
                        .put("chatId", chat)
                        .put("bytes", bytes.length)
                        .put("sha256", hash.toString())
                        .put("messageId", 901);
        receipts.put(token, row);
        return row.toString();
    }

    public static String verifyArchive(Context context, String token) throws Exception {
        readbacks++;
        if (rejectVerification) throw new IOException("Remote archive unavailable");
        JSONObject row =
                new JSONObject(receipts.get(token).toString())
                        .put("readbackVerifiedAt", System.currentTimeMillis());
        if (wrongHash) row.put("sha256", "0".repeat(64));
        return row.toString();
    }

    public static String restoreArchive(Context context, String token, File file) throws Exception {
        String result = verifyArchive(context, token);
        Files.write(file.toPath(), remote.get(token));
        return result;
    }
}
