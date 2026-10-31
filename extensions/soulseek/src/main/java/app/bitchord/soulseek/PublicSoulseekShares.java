package app.bitchord.soulseek;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.AtomicFile;

import org.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Explicit public shares. Only copies in this inventory are advertised, never a phone folder. */
final class PublicSoulseekShares {
    static final long MAX_FILE = 256L * 1024 * 1024, MAX_TOTAL = 2L * 1024 * 1024 * 1024;

    private PublicSoulseekShares() {}

    static boolean enabled(Context context) {
        return prefs(context).getBoolean("enabled", false);
    }

    static void initialize(Context context) throws IOException {
        if (!prefs(context).edit().putBoolean("enabled", false).commit())
            throw new IOException("Cannot pause public sharing after restart");
    }

    static void pause(Context context) throws Exception {
        enable(context, false, prefs(context).getInt("slots", 2));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("bitchord_public_soulseek", 0);
    }

    private static File home() throws IOException {
        return SoulseekBridge.get().home.getCanonicalFile();
    }

    private static File inventory() throws IOException {
        File root = new File(home(), "public-shares");
        if (!root.isDirectory() && !root.mkdirs())
            throw new IOException("Cannot create sharing inventory");
        if (!root.getCanonicalFile().equals(root)
                || java.nio.file.Files.isSymbolicLink(root.toPath()))
            throw new IOException("Invalid sharing inventory");
        return root;
    }

    private static AtomicFile ledger() throws IOException {
        return new AtomicFile(new File(home(), "public-shares.json"));
    }

    private static JSONArray read() throws Exception {
        AtomicFile file = ledger();
        if (!file.getBaseFile().exists()) return new JSONArray();
        byte[] bytes = file.readFully();
        if (bytes.length > 512 * 1024) throw new IOException("Sharing inventory is too large");
        JSONArray rows = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
        if (rows.length() > 200) throw new IOException("Too many public shares");
        return rows;
    }

    private static void write(JSONArray rows) throws Exception {
        AtomicFile file = ledger();
        FileOutputStream out = file.startWrite();
        try {
            out.write(rows.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (Exception error) {
            file.failWrite(out);
            throw error;
        }
    }

    static synchronized void add(Context context, Uri source, String title) throws Exception {
        JSONArray rows = read();
        for (int i = 0; i < rows.length(); i++)
            if (source.toString().equals(rows.getJSONObject(i).optString("source"))) return;
        if (rows.length() >= 200) throw new IOException("Public sharing is limited to 200 files");
        long total = 0;
        for (int i = 0; i < rows.length(); i++) total += rows.getJSONObject(i).getLong("bytes");
        File stage = File.createTempFile("public-", ".part", home());
        File published = null;
        try {
            long size = copy(context, source, stage, Math.min(MAX_FILE, MAX_TOTAL - total));
            AudioFormatPreferences.Format format = AudioFormatPreferences.probe(stage);
            if (format == AudioFormatPreferences.Format.UNKNOWN)
                throw new IOException("This file did not pass the audio format check");
            String extension = DownloadArchives.extension(stage, format);
            String id = UUID.randomUUID().toString();
            String name = safeName(title) + " (" + id.substring(0, 8) + ")." + extension;
            published = new File(inventory(), name);
            String hash = digest(stage);
            if (!stage.renameTo(published)) throw new IOException("Could not stage public share");
            rows.put(
                    new JSONObject()
                            .put("id", id)
                            .put("name", name)
                            .put("source", source.toString())
                            .put("bytes", size)
                            .put("sha256", hash));
            write(rows);
            published = null; // Journal now owns the file even if the network announcement fails.
            reconcile(context);
        } finally {
            stage.delete();
            if (published != null) published.delete();
        }
    }

    static String safeName(String title) {
        String name =
                title == null ? "Audio" : title.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", " ").trim();
        name = name.replaceAll("\\.[A-Za-z0-9]{2,5}$", "");
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "Audio";
        return name.substring(0, Math.min(name.length(), 130));
    }

    static long copy(Context context, Uri source, File target, long maximum) throws Exception {
        if (maximum <= 0) throw new IOException("Not enough sharing space");
        long size = 0;
        try (InputStream in = context.getContentResolver().openInputStream(source);
                FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IOException("Saved file is unavailable");
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedIOException("Copy cancelled");
                size += count;
                if (size > maximum || target.getUsableSpace() < 16L * 1024 * 1024)
                    throw new IOException("File is too large or storage is full");
                out.write(buffer, 0, count);
            }
            if (size == 0) throw new IOException("Saved file is empty");
            out.getFD().sync();
        }
        return size;
    }

    static String digest(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedIOException("Verification cancelled");
                hash.update(buffer, 0, count);
            }
        }
        StringBuilder text = new StringBuilder();
        for (byte value : hash.digest())
            text.append(String.format(Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }

    private static void validateInventory() throws Exception {
        File root = inventory();
        JSONArray rows = read();
        java.util.Set<String> expected = new java.util.HashSet<>();
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.getJSONObject(index);
            String name = row.getString("name");
            File file = new File(root, name);
            if (name.indexOf('/') >= 0
                    || name.indexOf('\\') >= 0
                    || !expected.add(name)
                    || !file.getCanonicalFile().getParentFile().equals(root)
                    || java.nio.file.Files.isSymbolicLink(file.toPath())
                    || !file.isFile()
                    || file.length() != row.getLong("bytes")
                    || !digest(file).equals(row.getString("sha256")))
                throw new IOException(
                        "A sharing copy changed or is missing. Remove it from the inventory and add"
                                + " it again");
        }
        File[] files = root.listFiles();
        if (files == null) throw new IOException("Cannot inspect public inventory");
        for (File file : files)
            if (!expected.contains(file.getName()) && !file.delete())
                throw new IOException("Could not remove incomplete sharing copy");
    }

    static synchronized void enable(Context context, boolean enabled, int slots) throws Exception {
        if (enabled) {
            BulkTransferService.ensureRunning(context);
            validateInventory();
        }
        if (slots < 1 || slots > 4) throw new IOException("Choose one to four upload slots");
        // Persist opt-out first: a disconnected session must never re-advertise on its next login.
        if (!enabled && !prefs(context).edit().putBoolean("enabled", false).commit())
            throw new IOException("Cannot save sharing setting");
        if (SoulseekBridge.get().connected())
            SoulseekNative.configureShares(SoulseekBridge.get().sharingHandle(), enabled, slots);
        if (!prefs(context).edit().putBoolean("enabled", enabled).putInt("slots", slots).commit()) {
            if (enabled && SoulseekBridge.get().connected())
                SoulseekNative.configureShares(SoulseekBridge.get().sharingHandle(), false, slots);
            throw new IOException("Cannot save sharing setting");
        }
    }

    static synchronized void reconcile(Context context) throws Exception {
        inventory();
        if (prefs(context).getBoolean("enabled", false)) validateInventory();
        if (SoulseekBridge.get().connected())
            SoulseekNative.configureShares(
                    SoulseekBridge.get().ready(),
                    prefs(context).getBoolean("enabled", false),
                    prefs(context).getInt("slots", 2));
    }

    static synchronized void remove(Context context, String id) throws Exception {
        JSONArray rows = read(), keep = new JSONArray();
        JSONObject found = null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (id.equals(row.getString("id"))) found = row;
            else keep.put(row);
        }
        if (found == null) return;
        boolean enabled = prefs(context).getBoolean("enabled", false);
        enable(context, false, prefs(context).getInt("slots", 2));
        File file = new File(inventory(), found.getString("name"));
        if (!file.getCanonicalFile().getParentFile().equals(inventory()))
            throw new IOException("Invalid shared file");
        write(keep);
        if (file.exists() && !file.delete()) throw new IOException("Could not remove sharing copy");
        if (enabled) enable(context, true, prefs(context).getInt("slots", 2));
    }

    static synchronized JSONObject snapshot(Context context) throws Exception {
        JSONObject result =
                new JSONObject()
                        .put("enabled", prefs(context).getBoolean("enabled", false))
                        .put("slots", prefs(context).getInt("slots", 2))
                        .put("inventory", read())
                        .put("online", false);
        if (SoulseekBridge.get().connected()) {
            result.put("live", new JSONObject(SoulseekNative.sharing(SoulseekBridge.get().ready())))
                    .put("online", true);
        }
        return result;
    }
}
