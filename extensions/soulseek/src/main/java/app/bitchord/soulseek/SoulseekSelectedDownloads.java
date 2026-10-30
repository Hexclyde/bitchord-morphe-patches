package app.bitchord.soulseek;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import kotlin.coroutines.EmptyCoroutineContext;

import java.io.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Routes explicit Soulseek selections through verified staging and the saved copy destinations. */
public final class SoulseekSelectedDownloads {
    private static final ConcurrentHashMap<String, Task> TASKS = new ConcurrentHashMap<>();
    private static final ThreadPoolExecutor WORK =
            new ThreadPoolExecutor(
                    1,
                    1,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<Runnable>(32),
                    runnable -> new Thread(runnable, "selected-soulseek-download"));
    private static volatile String status = "";

    private SoulseekSelectedDownloads() {}

    /** Called before the host can adopt an existing filename or create a phone-only output. */
    public static boolean enqueueOrFalse(Context context, Object song) {
        if (song == null) return false;
        final String videoId;
        try {
            Object value = song.getClass().getMethod("getVideoId").invoke(song);
            if (!(value instanceof String)) return false;
            videoId = (String) value;
        } catch (ReflectiveOperationException failure) {
            return false;
        }
        if (!SoulseekDownloadRoute.ownsTrack(videoId)) return false;
        final Context app = context.getApplicationContext();
        try {
            String id = videoId.substring("src:soulseek-native::".length());
            if (!id.matches("[a-f0-9]{64}"))
                throw new IOException("Invalid selected Soulseek reference.");
            requireDownloadsAllowed();
            Object config =
                    Class.forName("com.music.bitchord.data.sources.SourceRegistry")
                            .getMethod("config", String.class)
                            .invoke(null, "soulseek-native");
            if (config == null
                    || !Boolean.TRUE.equals(
                            config.getClass().getMethod("getEnabled").invoke(config)))
                throw new IOException("Enable Unofficial Soulseek before downloading this result.");
            DownloadDestinations.Snapshot destinations;
            try {
                destinations = DownloadDestinations.snapshot(app);
            } catch (IOException missing) {
                new Handler(Looper.getMainLooper())
                        .post(
                                () ->
                                        app.startActivity(
                                                new Intent(app, DownloadDestinationActivity.class)
                                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
                throw missing;
            }
            SoulseekBridge bridge = SoulseekBridge.get();
            AudioFormatPreferences.Settings formats = AudioFormatPreferences.snapshot(app);
            if (!formats.configured) {
                new Handler(Looper.getMainLooper())
                        .post(
                                () ->
                                        app.startActivity(
                                                new Intent(app, AudioFormatSetupActivity.class)
                                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
                throw new IOException("Choose your audio formats and their order first.");
            }
            String session = bridge.sessionTag;
            SoulseekTrack track = bridge.track(id);
            if (!session.equals(bridge.sessionTag))
                throw new IOException("Soulseek session changed. Select the result again.");
            if (track.size > SoulseekBridge.MAX_CACHE_BYTES)
                throw new IOException("Selected files are limited to 256 MiB.");
            Task task = new Task(app, bridge, track, session, destinations, formats);
            if (TASKS.putIfAbsent(videoId, task) != null) {
                notice(app, "This selected download is already queued.");
                return true;
            }
            try {
                BulkTransferService.ensureRunning(app);
                WORK.execute(
                        () -> {
                            try {
                                task.run();
                            } finally {
                                TASKS.remove(videoId, task);
                            }
                        });
            } catch (RejectedExecutionException full) {
                TASKS.remove(videoId, task);
                throw new IOException("The selected download queue is full.");
            } catch (IOException unavailable) {
                TASKS.remove(videoId, task);
                throw unavailable;
            }
            status = "Soulseek: " + TASKS.size() + " selected download(s) queued";
            notice(app, "Queued in Peer downloads using your saved copy destinations.");
        } catch (Exception failure) {
            status = "Soulseek: " + safe(failure);
            notice(app, status);
        }
        return true;
    }

    private static final class Task {
        final Context context;
        final SoulseekBridge bridge;
        final SoulseekTrack track;
        final String session;
        final DownloadDestinations.Snapshot destinations;
        final AudioFormatPreferences.Settings formats;
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile Thread thread;

        Task(
                Context context,
                SoulseekBridge bridge,
                SoulseekTrack track,
                String session,
                DownloadDestinations.Snapshot destinations,
                AudioFormatPreferences.Settings formats) {
            this.context = context;
            this.bridge = bridge;
            this.track = track;
            this.session = session;
            this.destinations = destinations;
            this.formats = formats;
        }

        void check() throws IOException {
            if (cancelled.get() || Thread.currentThread().isInterrupted())
                throw new InterruptedIOException("Selected download cancelled.");
            if (!session.equals(bridge.sessionTag))
                throw new IOException("Soulseek session changed. Select the result again.");
        }

        void run() {
            File source = null;
            try {
                BulkTransferService.awaitRunning();
                thread = Thread.currentThread();
                check();
                requireDownloadsAllowed();
                File directory = new File(context.getNoBackupFilesDir(), "soulseek-selected");
                if (!directory.isDirectory() && !directory.mkdirs())
                    throw new IOException("Cannot create selected download storage.");
                long occupied = 0;
                File[] previous = directory.listFiles();
                if (previous == null)
                    throw new IOException("Cannot inspect selected download storage.");
                for (File file : previous) {
                    if (file.isFile() && file.getName().matches("[a-f0-9]{32}\\.part")) {
                        if (!file.delete())
                            throw new IOException(
                                    "Cannot remove interrupted selected download staging.");
                    } else occupied += file.length();
                }
                if (occupied + track.size > SoulseekBridge.MAX_CACHE_BYTES
                        || directory.getUsableSpace() < track.size + 32L * 1024 * 1024)
                    throw new IOException(
                            "Selected download storage is full. Finish existing copies first.");
                String token = UUID.randomUUID().toString().replace("-", "");
                source = new File(directory, token + ".part");
                String name =
                        AudioFormatPreferences.audioName(
                                track.path.substring(
                                        Math.max(
                                                        track.path.lastIndexOf('/'),
                                                        track.path.lastIndexOf('\\'))
                                                + 1));
                try (FileOutputStream output = new FileOutputStream(source)) {
                    final FileOutputStream sink = output;
                    String url = SoulseekAddon.ORIGIN + session + "/audio/" + track.id;
                    SoulseekDownloads.fetch(
                            url,
                            new FilterOutputStream(sink) {
                                public void write(byte[] bytes, int offset, int length)
                                        throws IOException {
                                    check();
                                    out.write(bytes, offset, length);
                                }

                                public void write(int value) throws IOException {
                                    check();
                                    out.write(value);
                                }
                            },
                            (done, total) -> {
                                status = "Soulseek: " + done + "/" + total + " bytes";
                                return kotlin.Unit.INSTANCE;
                            },
                            EmptyCoroutineContext.INSTANCE);
                    check();
                    output.getFD().sync();
                }
                AudioFormatPreferences.validateSelectedFile(formats, source, name);
                check();
                requireDownloadsAllowed();
                BulkDownloads.get(context)
                        .importVerified(
                                source,
                                name,
                                destinations,
                                token,
                                AudioFormatPreferences.encode(formats));
                source = null;
                status = "Soulseek: verified file queued for your selected destinations";
            } catch (Exception failure) {
                status = "Soulseek: " + safe(failure);
                notice(context, status);
            } finally {
                if (source != null) source.delete();
                thread = null;
                Thread.interrupted();
            }
        }
    }

    public static String status() {
        return status;
    }

    public static int activeCount() {
        return TASKS.size();
    }

    public static void cancelAll() {
        Task[] pending = TASKS.values().toArray(new Task[0]);
        // Mark the whole queue before waking the active worker. Otherwise it can finish
        // its interrupted task and open a pending transfer before that task is marked.
        for (Task task : pending) task.cancelled.set(true);
        for (Task task : pending) {
            Thread thread = task.thread;
            if (thread != null) thread.interrupt();
        }
        status = "Soulseek selected downloads stopped";
    }

    private static String safe(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.length() > 256
                ? "The selected download could not complete."
                : message;
    }

    private static void requireDownloadsAllowed() throws IOException {
        try {
            if (!Boolean.TRUE.equals(
                    Class.forName("com.music.bitchord.data.settings.AppSettings")
                            .getMethod("getDownloadsAllowedNow")
                            .invoke(null)))
                throw new IOException(
                        "Downloads are paused by the current download settings. Retry when"
                                + " downloads are allowed.");
        } catch (ReflectiveOperationException failure) {
            throw new IOException(
                    "Download settings could not be checked. Restart BitChord and retry.", failure);
        }
    }

    private static void notice(Context context, String message) {
        new Handler(Looper.getMainLooper())
                .post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }
}
