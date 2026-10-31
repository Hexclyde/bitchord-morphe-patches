package app.bitchord.soulseek;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import app.bitchord.telegram.PatchStrings;

import kotlin.coroutines.EmptyCoroutineContext;

import java.io.*;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Routes explicit Soulseek selections through verified staging and the saved copy destinations. */
public final class SoulseekSelectedDownloads {
    private static final String PREFIX = "src:soulseek-native::";
    private static final ConcurrentHashMap<String, Task> TASKS = new ConcurrentHashMap<>();
    private static final LinkedHashMap<String, Entry> HISTORY = new LinkedHashMap<>();
    private static final ThreadPoolExecutor WORK =
            new ThreadPoolExecutor(
                    1,
                    1,
                    30,
                    TimeUnit.SECONDS,
                    new LinkedBlockingDeque<Runnable>(256),
                    runnable -> new Thread(runnable, "selected-soulseek-download"));
    private static volatile String lastError = "";
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ScheduledThreadPoolExecutor MONITOR =
            new ScheduledThreadPoolExecutor(
                    1,
                    runnable -> {
                        Thread thread = new Thread(runnable, "soulseek-progress-monitor");
                        thread.setDaemon(true);
                        return thread;
                    });
    private static ScheduledFuture<?> monitor;

    private SoulseekSelectedDownloads() {}

    public enum Phase {
        QUEUED,
        STARTING,
        WAITING_PEER,
        RECEIVING,
        VERIFYING,
        DESTINATIONS_PENDING,
        SAVING,
        PAUSED,
        FAILED,
        CANCELLED,
        COMPLETE
    }

    /** Immutable, bounded display state. Destination receipt contents never leave the publisher. */
    public static final class Progress {
        public final String reference, name, phoneState, telegramState, error;
        public final Phase phase;
        public final long downloaded, total, queuePosition;
        public final boolean needsPhone, needsTelegram, handedOff;
        private final long order;

        private Progress(Entry entry) {
            this(entry, null);
        }

        private Progress(Entry entry, SoulseekBridge.TransferStatus transfer) {
            reference = entry.reference;
            order = entry.order;
            name = entry.name;
            Phase shownPhase = entry.phase;
            long received = entry.downloaded;
            if (transfer != null
                    && (shownPhase == Phase.WAITING_PEER || shownPhase == Phase.RECEIVING)) {
                if (transfer.state.equals("queued") && received == 0)
                    shownPhase = Phase.WAITING_PEER;
                if (transfer.state.equals("downloading") && transfer.downloaded > 0) {
                    shownPhase = Phase.RECEIVING;
                    received = Math.max(received, transfer.downloaded);
                }
            }
            phase = shownPhase;
            downloaded = Math.max(0, Math.min(entry.total, received));
            total = entry.total;
            queuePosition = transfer == null ? -1 : transfer.queuePosition;
            needsPhone = entry.needsPhone;
            needsTelegram = entry.needsTelegram;
            handedOff = entry.jobId != null;
            phoneState = entry.phoneState;
            telegramState = entry.telegramState;
            error = entry.error;
        }

        public boolean canCancel() {
            return phase == Phase.QUEUED
                    || phase == Phase.STARTING
                    || phase == Phase.WAITING_PEER
                    || phase == Phase.RECEIVING
                    || phase == Phase.VERIFYING;
        }

        public String summary() {
            String stage;
            switch (phase) {
                case QUEUED:
                    stage = PatchStrings.t("Queued for download");
                    break;
                case STARTING:
                    stage = PatchStrings.t("Preparing download");
                    break;
                case WAITING_PEER:
                    stage =
                            queuePosition >= 0
                                    ? PatchStrings.f(
                                            "Waiting for peer · queue position {0}", queuePosition)
                                    : PatchStrings.t("Waiting for peer");
                    break;
                case RECEIVING:
                    stage =
                            PatchStrings.f(
                                    "Downloading · {0} of {1}", bytes(downloaded), bytes(total));
                    break;
                case VERIFYING:
                    stage = PatchStrings.t("Checking audio format");
                    break;
                case DESTINATIONS_PENDING:
                    stage = PatchStrings.t("Waiting to save selected copies");
                    break;
                case SAVING:
                    stage = PatchStrings.t("Saving selected copies");
                    break;
                case PAUSED:
                    stage = PatchStrings.t("Saving paused");
                    break;
                case FAILED:
                    stage = PatchStrings.t("Download needs attention");
                    break;
                case CANCELLED:
                    stage = PatchStrings.t("Download cancelled");
                    break;
                case COMPLETE:
                    stage = PatchStrings.t("Saved to all selected destinations");
                    break;
                default:
                    stage = "";
            }
            String text = PatchStrings.f("{0} · {1}", name, stage);
            if (phase == Phase.DESTINATIONS_PENDING
                    || phase == Phase.SAVING
                    || phase == Phase.PAUSED
                    || phase == Phase.COMPLETE
                    || (!phoneState.equals("pending") || !telegramState.equals("pending"))) {
                if (needsPhone)
                    text +=
                            "\n"
                                    + PatchStrings.f(
                                            "Phone folder: {0}", destinationState(phoneState));
                if (needsTelegram)
                    text +=
                            "\n"
                                    + PatchStrings.f(
                                            "Telegram main channel: {0}",
                                            destinationState(telegramState));
            }
            if (!error.isEmpty()) text += "\n" + PatchStrings.t(error);
            return text;
        }
    }

    private static final class Entry {
        final String reference, name;
        final long total;
        final boolean needsPhone, needsTelegram;
        final long order = SEQUENCE.incrementAndGet();
        volatile Phase phase = Phase.QUEUED;
        volatile long downloaded;
        volatile String phoneState = "pending", telegramState = "pending", error = "";
        volatile BulkDownloads publisher;
        volatile String jobId;
        volatile SoulseekBridge.TransferStatus observedTransfer;

        Entry(String reference, SoulseekTrack track, DownloadDestinations.Snapshot destinations) {
            this.reference = reference;
            String basename =
                    track.path.substring(
                            Math.max(track.path.lastIndexOf('/'), track.path.lastIndexOf('\\'))
                                    + 1);
            name = boundedName(basename);
            total = track.size;
            needsPhone = destinations.mode.equals("phone") || destinations.mode.equals("both");
            needsTelegram =
                    destinations.mode.equals("telegram") || destinations.mode.equals("both");
        }
    }

    /** Called before the host can adopt an existing filename or create a phone-only output. */
    public static boolean enqueueOrFalse(Context context, Object song) {
        if (song == null) return false;
        final String reference;
        try {
            Object value = song.getClass().getMethod("getVideoId").invoke(song);
            if (!(value instanceof String)) return false;
            reference = (String) value;
        } catch (ReflectiveOperationException failure) {
            return false;
        }
        if (!SoulseekDownloadRoute.ownsTrack(reference)) return false;
        enqueueSelected(context, reference);
        return true; // Owned selections never fall through to another source, even on failure.
    }

    /** Explicit result-file choice. False means a visible error, never permission to fall back. */
    public static boolean enqueueSelected(Context context, String reference) {
        return enqueueSelected(context, reference, false);
    }

    /** A now request moves ahead of waiting work, without interrupting the active transfer. */
    public static boolean enqueueSelected(Context context, String reference, boolean now) {
        return enqueueSelected(context, reference, now, null, null, null);
    }

    static boolean enqueueSelectedFile(
            Context context, SoulseekTrack track, String session, boolean now) {
        return enqueueSelected(context, PREFIX + track.id, now, track, session, null);
    }

    static boolean enqueuePreview(
            Context context, SoulseekTrack track, String session, boolean now, Closeable retained) {
        return enqueueSelected(context, PREFIX + track.id, now, track, session, retained);
    }

    private static boolean enqueueSelected(
            Context context,
            String reference,
            boolean now,
            SoulseekTrack selected,
            String expectedSession,
            Closeable retained) {
        if (context == null) {
            closeRetained(retained);
            return false;
        }
        boolean accepted = false;
        final Context app = context.getApplicationContext();
        try {
            if (reference == null || !reference.matches("src:soulseek-native::[a-f0-9]{64}"))
                throw new UserFailure("Invalid selected Soulseek reference.");
            String id = reference.substring(PREFIX.length());
            requireDownloadsAllowed();
            Object config =
                    Class.forName("com.music.bitchord.data.sources.SourceRegistry")
                            .getMethod("config", String.class)
                            .invoke(null, "soulseek-native");
            if (config == null
                    || !Boolean.TRUE.equals(
                            config.getClass().getMethod("getEnabled").invoke(config)))
                throw new UserFailure("Enable Unofficial Soulseek before downloading this result.");
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
                throw new UserFailure("Choose where copies go before downloading.");
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
                throw new UserFailure("Choose your audio formats and their order first.");
            }
            String session = bridge.sessionTag;
            SoulseekTrack track;
            try {
                track = selected == null ? bridge.track(id) : selected;
                if (!track.id.equals(id)
                        || expectedSession != null && !expectedSession.equals(session))
                    throw new IOException("Selected file session changed");
            } catch (IOException expired) {
                throw new UserFailure("This Soulseek result expired. Search again.");
            }
            if (!session.equals(bridge.sessionTag))
                throw new UserFailure("Soulseek session changed. Select the result again.");
            if (track.size > SoulseekBridge.MAX_CACHE_BYTES)
                throw new UserFailure("Selected files are limited to 256 MiB.");
            Task task = new Task(app, bridge, track, session, destinations, formats, reference);
            task.retained = retained;
            Task existing = TASKS.putIfAbsent(reference, task);
            if (existing != null) {
                synchronized (existing) {
                    if (!existing.cancelled.get()
                            && !existing.handoffStarted
                            && !existing.retentionClosed
                            && existing.retained == null
                            && retained != null) {
                        existing.retained = retained;
                        accepted = true;
                    }
                }
                notice(app, PatchStrings.t("This selected download is already queued."));
                return true;
            }
            synchronized (HISTORY) {
                HISTORY.remove(reference);
                HISTORY.put(reference, task.entry);
                while (HISTORY.size() > 512) HISTORY.remove(HISTORY.keySet().iterator().next());
            }
            try {
                BulkTransferService.ensureRunning(app);
                Runnable selectedWork =
                        () -> {
                            try {
                                task.run();
                            } finally {
                                TASKS.remove(reference, task);
                                stopMonitorIfIdle();
                            }
                        };
                synchronized (WORK) {
                    WORK.execute(selectedWork);
                    if (now && WORK.getQueue().remove(selectedWork))
                        ((LinkedBlockingDeque<Runnable>) WORK.getQueue()).addFirst(selectedWork);
                }
            } catch (RejectedExecutionException full) {
                TASKS.remove(reference, task);
                synchronized (HISTORY) {
                    HISTORY.remove(reference);
                }
                throw new UserFailure("The selected download queue is full.");
            } catch (IOException unavailable) {
                TASKS.remove(reference, task);
                synchronized (HISTORY) {
                    HISTORY.remove(reference);
                }
                throw unavailable;
            }
            accepted = true;
            lastError = "";
            notice(
                    app,
                    PatchStrings.f(
                            "Queued {0}. Open downloads to follow progress.", task.entry.name));
            return true;
        } catch (Exception failure) {
            lastError = safe(failure);
            notice(app, PatchStrings.t(lastError));
            return false;
        } finally {
            if (!accepted) closeRetained(retained);
        }
    }

    private static void closeRetained(Closeable retained) {
        if (retained != null)
            try {
                retained.close();
            } catch (IOException ignored) {
            }
    }

    private static final class Task {
        final Context context;
        final SoulseekBridge bridge;
        final SoulseekTrack track;
        final String session;
        final DownloadDestinations.Snapshot destinations;
        final AudioFormatPreferences.Settings formats;
        final Entry entry;
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile Thread thread;
        Closeable retained;
        boolean retentionClosed;

        synchronized void releaseRetention() {
            retentionClosed = true;
            Closeable current = retained;
            retained = null;
            closeRetained(current);
        }

        // Guarded by this task's monitor. Destination journals own cancellation after handoff.
        boolean handoffStarted;

        Task(
                Context context,
                SoulseekBridge bridge,
                SoulseekTrack track,
                String session,
                DownloadDestinations.Snapshot destinations,
                AudioFormatPreferences.Settings formats,
                String reference) {
            this.context = context;
            this.bridge = bridge;
            this.track = track;
            this.session = session;
            this.destinations = destinations;
            this.formats = formats;
            entry = new Entry(reference, track, destinations);
        }

        void check() throws IOException {
            if (cancelled.get() || Thread.currentThread().isInterrupted())
                throw new InterruptedIOException("Selected download cancelled.");
            if (!session.equals(bridge.sessionTag))
                throw new UserFailure("Soulseek session changed. Select the result again.");
        }

        void run() {
            File source = null;
            try {
                thread = Thread.currentThread();
                check();
                entry.phase = Phase.STARTING;
                BulkTransferService.awaitRunning();
                check();
                requireDownloadsAllowed();
                File directory = new File(context.getNoBackupFilesDir(), "soulseek-selected");
                if (!directory.isDirectory() && !directory.mkdirs())
                    throw new UserFailure("Cannot create selected download storage.");
                long occupied = 0;
                File[] previous = directory.listFiles();
                if (previous == null)
                    throw new UserFailure("Cannot inspect selected download storage.");
                for (File file : previous) {
                    if (file.isFile() && file.getName().matches("[a-f0-9]{32}\\.part")) {
                        if (!file.delete())
                            throw new UserFailure(
                                    "Cannot remove interrupted selected download staging.");
                    } else occupied += file.length();
                }
                if (occupied + track.size > SoulseekBridge.MAX_CACHE_BYTES
                        || directory.getUsableSpace() < track.size + 32L * 1024 * 1024)
                    throw new UserFailure(
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
                    check();
                    entry.phase = Phase.WAITING_PEER;
                    startMonitor();
                    SoulseekDownloads.fetchSelected(
                            track,
                            session,
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
                                if (!cancelled.get()) {
                                    entry.downloaded = Math.max(0, Math.min(track.size, done));
                                    entry.phase = Phase.RECEIVING;
                                }
                                return kotlin.Unit.INSTANCE;
                            },
                            EmptyCoroutineContext.INSTANCE);
                    check();
                    output.getFD().sync();
                }
                entry.phase = Phase.VERIFYING;
                try {
                    AudioFormatPreferences.validateSelectedFile(formats, source, name);
                } catch (IOException invalid) {
                    throw new UserFailure(
                            "This file did not pass the audio format check. Choose another"
                                    + " result.");
                }
                check();
                requireDownloadsAllowed();
                synchronized (this) {
                    check();
                    handoffStarted = true;
                    entry.phase = Phase.DESTINATIONS_PENDING;
                }
                BulkDownloads publisher = BulkDownloads.get(context);
                String job =
                        publisher.importVerified(
                                source,
                                name,
                                destinations,
                                token,
                                AudioFormatPreferences.encode(formats));
                source = null;
                entry.publisher = publisher;
                entry.jobId = job;
            } catch (Exception failure) {
                if (cancelled.get()
                        || failure instanceof InterruptedIOException
                        || Thread.currentThread().isInterrupted()) {
                    entry.phase = Phase.CANCELLED;
                    entry.error = "";
                } else {
                    entry.phase = Phase.FAILED;
                    entry.error = safe(failure);
                    notice(context, new Progress(entry).summary());
                }
            } finally {
                if (source != null) source.delete();
                releaseRetention();
                thread = null;
                Thread.interrupted();
            }
        }
    }

    public static List<Progress> snapshots() {
        List<Entry> entries;
        synchronized (HISTORY) {
            entries = new ArrayList<>(HISTORY.values());
        }
        List<Progress> result = new ArrayList<>();
        for (Entry entry : entries) {
            BulkDownloads publisher = entry.publisher;
            String jobId = entry.jobId;
            if (publisher != null && jobId != null) {
                boolean found = false;
                for (BulkJobStore.Job job : publisher.jobs()) {
                    if (job.id.equals(jobId)) {
                        projectDestination(entry, job);
                        found = true;
                        break;
                    }
                }
                if (!found) continue; // Cleared journal entries have no current display facts.
            }
            result.add(new Progress(entry, entry.observedTransfer));
        }
        Collections.sort(
                result,
                Comparator.comparingInt(SoulseekSelectedDownloads::priority)
                        .thenComparingLong(item -> priority(item) == 3 ? -item.order : item.order));
        return Collections.unmodifiableList(result);
    }

    private static int priority(Progress item) {
        if (item.phase == Phase.QUEUED) return 2;
        if (item.phase == Phase.COMPLETE
                || item.phase == Phase.FAILED
                || item.phase == Phase.CANCELLED) return 3;
        return item.handedOff ? 1 : 0;
    }

    private static void startMonitor() {
        synchronized (MONITOR) {
            if (monitor != null && !monitor.isDone()) return;
            monitor =
                    MONITOR.scheduleWithFixedDelay(
                            () -> {
                                for (Task task : TASKS.values()) {
                                    if (task.cancelled.get()
                                            || (task.entry.phase != Phase.WAITING_PEER
                                                    && task.entry.phase != Phase.RECEIVING))
                                        continue;
                                    try {
                                        SoulseekBridge.TransferStatus state =
                                                task.bridge.transferStatus(
                                                        task.track.id, task.session);
                                        if (!task.cancelled.get()
                                                && task.session.equals(task.bridge.sessionTag))
                                            task.entry.observedTransfer = state;
                                    } catch (IOException
                                            | RuntimeException
                                            | LinkageError unavailable) {
                                        task.entry.observedTransfer = null;
                                    }
                                }
                            },
                            0,
                            500,
                            TimeUnit.MILLISECONDS);
        }
    }

    private static void stopMonitorIfIdle() {
        synchronized (MONITOR) {
            if (!TASKS.isEmpty() || monitor == null) return;
            monitor.cancel(false);
            MONITOR.purge();
            monitor = null;
        }
    }

    private static void projectDestination(Entry entry, BulkJobStore.Job job) {
        boolean phoneConfirmed =
                !entry.needsPhone
                        || ("complete".equals(job.phoneStatus) && !job.publishedUri.isEmpty());
        boolean telegramConfirmed =
                !entry.needsTelegram
                        || ("complete".equals(job.telegramStatus)
                                && !job.telegramReceipt.isEmpty());
        entry.phoneState =
                phoneConfirmed && entry.needsPhone
                        ? "complete"
                        : "complete".equals(job.phoneStatus) ? "pending" : job.phoneStatus;
        entry.telegramState =
                telegramConfirmed && entry.needsTelegram
                        ? "complete"
                        : "complete".equals(job.telegramStatus)
                                ? "receipt-check-required"
                                : job.telegramStatus;
        if ((job.state == BulkJobStore.State.FAILED || job.state == BulkJobStore.State.PAUSED)
                && (entry.phoneState.equals("creating") || entry.phoneState.equals("writing")))
            entry.phoneState = "check-required";
        if ((job.state == BulkJobStore.State.FAILED || job.state == BulkJobStore.State.PAUSED)
                && entry.telegramState.equals("uploading"))
            entry.telegramState = "receipt-check-required";
        switch (job.state) {
            case COMPLETE:
                entry.phase =
                        phoneConfirmed && telegramConfirmed
                                ? Phase.COMPLETE
                                : Phase.DESTINATIONS_PENDING;
                break;
            case CANCELLED:
                entry.phase = Phase.CANCELLED;
                break;
            case FAILED:
                entry.phase = Phase.FAILED;
                break;
            case PAUSED:
                entry.phase = Phase.PAUSED;
                break;
            case PUBLISHING:
                entry.phase = Phase.SAVING;
                break;
            default:
                entry.phase =
                        entry.phoneState.equals("creating")
                                        || entry.phoneState.equals("writing")
                                        || entry.telegramState.equals("uploading")
                                ? Phase.SAVING
                                : Phase.DESTINATIONS_PENDING;
        }
    }

    /**
     * Already translated summary for overview/status widgets; file names are data, not lookup keys.
     */
    public static String status() {
        StringBuilder value = new StringBuilder();
        if (!lastError.isEmpty()) value.append(PatchStrings.t(lastError));
        int count = 0;
        for (Progress progress : snapshots()) {
            if (count++ >= 6) break;
            if (value.length() > 0) value.append('\n');
            value.append(progress.summary());
        }
        return value.toString();
    }

    public static int activeCount() {
        return TASKS.size();
    }

    public static boolean cancel(String reference) {
        if (reference == null) return false;
        Task task = TASKS.get(reference);
        if (task == null) return false;
        final Thread thread;
        synchronized (task) {
            if (task.handoffStarted) return false;
            task.cancelled.set(true);
            task.entry.phase = Phase.CANCELLED;
            thread = task.thread;
        }
        task.releaseRetention();
        if (thread != null) thread.interrupt();
        return true;
    }

    public static void cancelAll() {
        Task[] pending = TASKS.values().toArray(new Task[0]);
        // Mark the whole queue before waking the active worker.
        for (Task task : pending)
            synchronized (task) {
                if (task.handoffStarted) continue;
                task.cancelled.set(true);
                task.entry.phase = Phase.CANCELLED;
            }
        for (Task task : pending) {
            task.releaseRetention();
            Thread thread = task.thread;
            if (task.cancelled.get() && thread != null) thread.interrupt();
        }
        lastError = "";
    }

    private static String destinationState(String state) {
        switch (state) {
            case "complete":
                return PatchStrings.t("Saved and verified");
            case "creating":
            case "writing":
                return PatchStrings.t("Saving file");
            case "uploading":
                return PatchStrings.t("Uploading file");
            case "receipt-check-required":
                return PatchStrings.t("Confirmation required before retry");
            case "check-required":
                return PatchStrings.t("Check the saved file before retry");
            case "failed":
                return PatchStrings.t("Copy failed");
            default:
                return PatchStrings.t("Waiting to save");
        }
    }

    private static String boundedName(String raw) {
        String name = raw.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").trim();
        if (name.isEmpty()) return PatchStrings.t("Selected audio file");
        return name.length() <= 120 ? name : name.substring(0, 117) + "…";
    }

    private static String bytes(long value) {
        NumberFormat number = NumberFormat.getNumberInstance(PatchStrings.locale());
        number.setMaximumFractionDigits(1);
        if (value >= 1024 * 1024) return number.format(value / (1024.0 * 1024)) + " MiB";
        if (value >= 1024) return number.format(value / 1024.0) + " KiB";
        return number.format(value) + " B";
    }

    private static final class UserFailure extends IOException {
        UserFailure(String message) {
            super(message);
        }
    }

    private static String safe(Exception failure) {
        // Only messages created by these explicit UI guards are shown. IO/provider messages may
        // contain private paths or connection details and never become display text.
        return failure instanceof UserFailure
                ? failure.getMessage()
                : "The selected download could not complete. Try again or choose another result.";
    }

    private static void requireDownloadsAllowed() throws IOException {
        try {
            if (!Boolean.TRUE.equals(
                    Class.forName("com.music.bitchord.data.settings.AppSettings")
                            .getMethod("getDownloadsAllowedNow")
                            .invoke(null)))
                throw new UserFailure(
                        "Downloads are paused by the current download settings. Retry when"
                                + " downloads are allowed.");
        } catch (ReflectiveOperationException failure) {
            throw new UserFailure(
                    "Download settings could not be checked. Restart BitChord and retry.");
        }
    }

    private static void notice(Context context, String message) {
        new Handler(Looper.getMainLooper())
                .post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }
}
