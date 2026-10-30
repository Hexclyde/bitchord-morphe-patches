package app.bitchord.soulseek;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.AtomicFile;

import org.json.*;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Durable archive receipts, with upload, independent read-back, explicit removal and restore. */
final class DownloadArchives {
    private static final ThreadPoolExecutor work =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<Runnable>(32),
                    r -> {
                        Thread thread = new Thread(r, "download-archives");
                        thread.setDaemon(true);
                        return thread;
                    });
    private static final java.util.Map<String, Thread> running =
            new java.util.concurrent.ConcurrentHashMap<>();

    static int activeCount() {
        return active.size();
    }

    static void pauseAll() {
        work.getQueue().clear();
        for (Thread thread : running.values()) thread.interrupt();
        active.retainAll(running.keySet());
    }

    private static final Set<String> active = ConcurrentHashMap.newKeySet();

    private DownloadArchives() {}

    private static File home(Context context) throws IOException {
        File root = new File(context.getNoBackupFilesDir(), "soulseek-native/archives");
        if (!root.isDirectory() && !root.mkdirs())
            throw new IOException("Cannot create archive journal");
        return root.getCanonicalFile();
    }

    private static AtomicFile ledger(Context context, String id) throws IOException {
        if (id == null || !id.matches("[a-f0-9-]{36}"))
            throw new IOException("Invalid archive identifier");
        return new AtomicFile(new File(home(context), id + ".json"));
    }

    private static JSONObject read(Context context, String id) throws Exception {
        byte[] bytes = ledger(context, id).readFully();
        if (bytes.length > 32768) throw new IOException("Invalid archive journal");
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private static synchronized void save(Context context, JSONObject row) throws Exception {
        AtomicFile file = ledger(context, row.getString("id"));
        FileOutputStream out = file.startWrite();
        try {
            out.write(row.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (Exception error) {
            file.failWrite(out);
            throw error;
        }
    }

    static synchronized JSONArray snapshot(Context context) throws Exception {
        JSONArray rows = new JSONArray();
        File[] files = home(context).listFiles((parent, name) -> name.endsWith(".json"));
        if (files != null)
            for (File file : files) {
                if (rows.length() >= 500) break;
                JSONObject row = read(context, file.getName().substring(0, 36));
                if (!active.contains(row.getString("id"))
                        && Arrays.asList("uploading", "verifying", "removing", "restoring")
                                .contains(row.optString("phase")))
                    row.put("phase", "paused")
                            .put("error", "Interrupted. Review the archive and retry.");
                rows.put(row);
            }
        return rows;
    }

    static void archive(Context context, String hostId, Uri source, String title) throws Exception {
        Context app = context.getApplicationContext();
        JSONObject row = null;
        JSONArray previous = snapshot(app);
        for (int i = 0; i < previous.length(); i++)
            if (source.toString().equals(previous.getJSONObject(i).optString("source")))
                row = previous.getJSONObject(i);
        if (row == null) {
            if (previous.length() >= 500) throw new IOException("Archive journal is full");
            JSONObject destination = DownloadDestinations.configuredTelegram(app);
            row =
                    new JSONObject()
                            .put("id", UUID.randomUUID().toString())
                            .put("source", source.toString())
                            .put("hostId", hostId)
                            .put("title", title)
                            .put("account", destination.getLong("userId"))
                            .put("chat", destination.getLong("chatId"))
                            .put("phase", "queued")
                            .put("localRemoved", false);
            save(app, row);
        }
        String id = row.getString("id");
        schedule(app, id, () -> upload(app, id));
    }

    static void retry(Context context, String id) throws Exception {
        Context app = context.getApplicationContext();
        schedule(app, id, () -> upload(app, id));
    }

    static void removeLocal(Context context, String id) throws Exception {
        Context app = context.getApplicationContext();
        schedule(app, id, () -> remove(app, id));
    }

    static void restore(Context context, String id) throws Exception {
        Context app = context.getApplicationContext();
        schedule(app, id, () -> restoreFile(app, id));
    }

    interface Action {
        void run() throws Exception;
    }

    private static void schedule(Context context, String id, Action action) throws Exception {
        BulkTransferService.ensureRunning(context);
        if (!active.add(id)) throw new IOException("Archive operation already in progress");
        try {
            work.execute(
                    () -> {
                        running.put(id, Thread.currentThread());
                        try {
                            action.run();
                        } catch (Exception failed) {
                            try {
                                JSONObject row = read(context, id);
                                row.put("phase", "failed").put("error", safe(failed));
                                save(context, row);
                            } catch (Exception ignored) {
                            }
                        } finally {
                            running.remove(id);
                            active.remove(id);
                            Thread.interrupted();
                        }
                    });
        } catch (RejectedExecutionException full) {
            active.remove(id);
            throw new IOException("Archive queue is full");
        }
    }

    private static String safe(Exception error) {
        String text = error.getMessage();
        return text == null
                ? "Archive action failed"
                : text.substring(0, Math.min(text.length(), 240));
    }

    private static Object telegram(Context context, String method, Class<?>[] types, Object... args)
            throws Exception {
        try {
            return Class.forName(
                            "app.bitchord.telegram.DownloadUploadBridge",
                            true,
                            context.getClassLoader())
                    .getMethod(method, types)
                    .invoke(null, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IOException("Telegram archive unavailable", cause);
        } catch (ReflectiveOperationException | LinkageError missing) {
            throw new IOException(
                    "Enable the Telegram integration and connect your private main channel",
                    missing);
        }
    }

    private static void upload(Context context, String id) throws Exception {
        JSONObject row = read(context, id);
        if (row.optBoolean("localRemoved"))
            throw new IOException("Restore this archive before uploading again");
        File stage = new File(home(context), id + ".stage");
        try {
            row.put("phase", "uploading").put("error", "");
            save(context, row);
            PublicSoulseekShares.copy(
                    context,
                    Uri.parse(row.getString("source")),
                    stage,
                    PublicSoulseekShares.MAX_FILE);
            String hash = PublicSoulseekShares.digest(stage);
            if (row.has("sha256") && !hash.equals(row.getString("sha256")))
                throw new IOException(
                        "The local file changed; the old receipt cannot archive different bytes");
            AudioFormatPreferences.Format format = AudioFormatPreferences.probe(stage);
            if (format == AudioFormatPreferences.Format.UNKNOWN)
                throw new IOException("Saved file did not pass the audio format check");
            String extension = extension(stage, format);
            String name = PublicSoulseekShares.safeName(row.getString("title")) + "." + extension;
            row.put("name", name).put("bytes", stage.length()).put("sha256", hash);
            save(context, row);
            String receipt =
                    (String)
                            telegram(
                                    context,
                                    "upload",
                                    new Class<?>[] {
                                        Context.class,
                                        File.class,
                                        String.class,
                                        long.class,
                                        long.class,
                                        String.class
                                    },
                                    context,
                                    stage,
                                    name,
                                    row.getLong("chat"),
                                    row.getLong("account"),
                                    id);
            row.put("receipt", new JSONObject(receipt)).put("phase", "verifying");
            save(context, row);
            verify(context, row);
            row.put("phase", "archived");
            save(context, row);
        } finally {
            stage.delete();
        }
    }

    static String extension(File file, AudioFormatPreferences.Format format) throws IOException {
        switch (format) {
            case FLAC:
                return "flac";
            case ALAC:
                return "m4a";
            case WAV:
                return "wav";
            case AIFF:
                return "aiff";
            case DSD:
                try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
                    return in.readInt() == 0x46524d38 ? "dff" : "dsf";
                }
            case OPUS:
                return "opus";
            case VORBIS:
                return "ogg";
            case MP3:
                return "mp3";
            case AAC:
                try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
                    in.seek(4);
                    return in.readInt() == 0x66747970 ? "m4a" : "aac";
                }
            default:
                throw new IOException("Unknown audio format");
        }
    }

    private static void verify(Context context, JSONObject row) throws Exception {
        String receipt =
                (String)
                        telegram(
                                context,
                                "verifyArchive",
                                new Class<?>[] {Context.class, String.class},
                                context,
                                row.getString("id"));
        JSONObject checked = new JSONObject(receipt);
        if (!checked.optBoolean("confirmed")
                || checked.optLong("readbackVerifiedAt") <= 0
                || !checked.getString("sha256").equals(row.getString("sha256"))
                || checked.getLong("bytes") != row.getLong("bytes")
                || checked.getLong("userId") != row.getLong("account")
                || checked.getLong("chatId") != row.getLong("chat"))
            throw new IOException("Archive receipt does not match this file");
        row.put("receipt", checked);
    }

    private static void remove(Context context, String id) throws Exception {
        JSONObject row = read(context, id);
        if (row.optBoolean("localRemoved")) return;
        if (!row.has("receipt")) throw new IOException("Archive this file first");
        row.put("phase", "verifying");
        save(context, row);
        verify(context, row);
        Uri source = Uri.parse(row.getString("source"));
        File check = new File(home(context), id + ".remove-check");
        try {
            PublicSoulseekShares.copy(context, source, check, PublicSoulseekShares.MAX_FILE);
            if (check.length() != row.getLong("bytes")
                    || !PublicSoulseekShares.digest(check).equals(row.getString("sha256")))
                throw new IOException("The local file changed; it was kept");
        } finally {
            check.delete();
        }
        row.put("phase", "removing");
        save(context, row);
        boolean removed = false;
        if ("content".equals(source.getScheme())) {
            if (DocumentsContract.isDocumentUri(context, source))
                removed = DocumentsContract.deleteDocument(context.getContentResolver(), source);
            else removed = context.getContentResolver().delete(source, null, null) > 0;
        } else if ("file".equals(source.getScheme())) removed = new File(source.getPath()).delete();
        if (!removed)
            throw new IOException(
                    "Storage did not allow removal. The Telegram copy remains archived");
        row.put("localRemoved", true).put("phase", "offloaded").put("error", "");
        save(context, row);
        String hostId = row.optString("hostId");
        if (!hostId.isEmpty()
                && source.toString()
                        .equals(String.valueOf(HostDownloadAccess.state("saved").get(hostId))))
            HostDownloadAccess.forget(hostId);
    }

    private static String preservePolicy() {
        java.util.List<AudioFormatPreferences.Format> formats = new java.util.ArrayList<>();
        for (AudioFormatPreferences.Format format : AudioFormatPreferences.Format.values())
            if (format != AudioFormatPreferences.Format.UNKNOWN) formats.add(format);
        return AudioFormatPreferences.encode(
                new AudioFormatPreferences.Settings(
                        formats, java.util.EnumSet.copyOf(formats), true));
    }

    private static void restoreFile(Context context, String id) throws Exception {
        JSONObject row = read(context, id);
        DownloadDestinations.Snapshot destination = DownloadDestinations.snapshot(context);
        if (!destination.needsPhone())
            throw new IOException("Choose a phone folder in copy destinations first");
        File stage = new File(home(context), id + ".restore");
        try {
            row.put("phase", "restoring").put("error", "");
            save(context, row);
            telegram(
                    context,
                    "restoreArchive",
                    new Class<?>[] {Context.class, String.class, File.class},
                    context,
                    id,
                    stage);
            if (stage.length() != row.getLong("bytes")
                    || !PublicSoulseekShares.digest(stage).equals(row.getString("sha256")))
                throw new IOException("Restored bytes did not match the archive");
            // The existing publisher journals the SAF write and verifies the completed document.
            String job =
                    BulkDownloads.get(context)
                            .importVerified(
                                    stage,
                                    row.getString("name"),
                                    new DownloadDestinations.Snapshot(
                                            DownloadDestinations.PHONE,
                                            destination.phoneTreeUri,
                                            0,
                                            0,
                                            ""),
                                    "restore-" + id,
                                    preservePolicy());
            row.put("restoreJob", job).put("phase", "restore-submitted");
            save(context, row);
        } finally {
            stage.delete();
        }
    }
}
