package app.bitchord.soulseek;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import app.bitchord.telegram.PatchStrings;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Foreground lifetime for downloads the user explicitly starts. Never starts at boot. */
public final class BulkTransferService extends Service {
    private static final String CHANNEL = "bitchord_downloads";
    private static final String START = "app.bitchord.soulseek.DOWNLOADS_START";
    private static final String STOP = "app.bitchord.soulseek.DOWNLOADS_STOP";
    private static final String TOKEN = "request";
    private static final int NOTIFICATION = 7920;
    private static final long IDLE_MS = 60_000, REFRESH_MS = 5_000;
    private static final Object LOCK = new Object();
    private static final AtomicLong REQUESTS = new AtomicLong();
    private static BulkTransferService active;
    private static StartSignal pending;
    private static volatile String status = "Downloads are stopped.";

    private static final class StartSignal {
        final long token = REQUESTS.incrementAndGet();
        final CountDownLatch ready = new CountDownLatch(1);
        volatile String failure;
    }

    /** Pure lifetime state; all times are monotonic, including tests. */
    static final class Lifetime {
        private boolean started, stopping;
        private long lastBusy;

        synchronized boolean activate(long now) {
            if (stopping) return false;
            started = true;
            lastBusy = now;
            return true;
        }

        synchronized void touch(long now) {
            if (started && !stopping) lastBusy = now;
        }

        synchronized boolean idle(long now, boolean busy) {
            if (!started || stopping) return false;
            if (busy) lastBusy = now;
            return !busy && now >= lastBusy && now - lastBusy >= IDLE_MS;
        }

        synchronized boolean beginStop() {
            if (stopping) return false;
            stopping = true;
            return true;
        }

        synchronized boolean stopping() {
            return stopping;
        }
    }

    private final Lifetime lifetime = new Lifetime();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService control =
            Executors.newSingleThreadExecutor(
                    work -> new Thread(work, "bitchord-download-lifetime"));
    private volatile boolean foreground;
    private boolean engaged;
    private final Runnable refresh =
            new Runnable() {
                @Override
                public void run() {
                    if (!foreground || lifetime.stopping()) return;
                    control.execute(() -> inspect());
                }
            };

    /** User-action callers only. On workers, returns after startForeground succeeds. */
    public static void ensureRunning(Context context) throws IOException {
        final StartSignal request;
        synchronized (LOCK) {
            if (active != null) {
                if (active.lifetime.stopping())
                    throw new IOException("Downloads are stopping. Retry when they have stopped.");
                if (active.foreground) {
                    active.lifetime.touch(SystemClock.elapsedRealtime());
                    return;
                }
            }
            if (pending == null || pending.failure != null) pending = new StartSignal();
            request = pending;
        }
        try {
            Intent start =
                    new Intent(context.getApplicationContext(), BulkTransferService.class)
                            .setAction(START)
                            .putExtra(TOKEN, request.token);
            if (context.getApplicationContext().startForegroundService(start) == null)
                throw new IllegalStateException("Service could not be started");
        } catch (RuntimeException failure) {
            request.failure = "Open Peer downloads and start the download again.";
            request.ready.countDown();
            throw new IOException(request.failure, failure);
        }
        if (Looper.myLooper() != Looper.getMainLooper()) await(request);
    }

    /** First operation in a queued worker when ensureRunning was called on the UI thread. */
    public static void awaitRunning() throws IOException {
        final StartSignal request;
        synchronized (LOCK) {
            if (active != null && active.foreground && !active.lifetime.stopping()) return;
            request = pending;
        }
        if (request == null) throw new IOException("Start downloads from Peer downloads first.");
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IOException("Download startup must wait on a worker.");
        await(request);
    }

    private static void await(StartSignal request) throws IOException {
        try {
            if (!request.ready.await(5, TimeUnit.SECONDS))
                throw new IOException(
                        "Download notification could not start. Open Peer downloads and retry.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Download startup was cancelled.", interrupted);
        }
        synchronized (LOCK) {
            if (request.failure != null
                    || active == null
                    || !active.foreground
                    || active.lifetime.stopping())
                throw new IOException(
                        request.failure == null
                                ? "Downloads are stopped. Start them again explicitly."
                                : request.failure);
        }
    }

    public static String status() {
        return status;
    }

    /** Explicit UI stop. Notification stop uses the same private service action. */
    public static void requestStop(Context context) throws IOException {
        final boolean running;
        synchronized (LOCK) {
            running = active != null;
            if (!running && pending != null) {
                pending.failure = "Download startup was cancelled.";
                pending.ready.countDown();
                pending = null;
            }
        }
        try {
            if (!running) {
                context.getApplicationContext()
                        .stopService(new Intent(context, BulkTransferService.class));
                return;
            }
            context.getApplicationContext()
                    .startService(new Intent(context, BulkTransferService.class).setAction(STOP));
        } catch (RuntimeException failure) {
            throw new IOException(
                    "Could not stop downloads. Open Peer downloads and retry.", failure);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        PatchStrings.refresh(this);
        if (intent != null && STOP.equals(intent.getAction())) {
            shutdown("Downloads and sharing stopped.", false);
            return START_NOT_STICKY;
        }
        final StartSignal request;
        synchronized (LOCK) {
            request = pending;
        }
        if (intent == null
                || !START.equals(intent.getAction())
                || request == null
                || intent.getLongExtra(TOKEN, -1) != request.token
                || lifetime.stopping()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        try {
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager == null)
                throw new IllegalStateException("Notification manager unavailable");
            manager.createNotificationChannel(
                    new NotificationChannel(
                            CHANNEL,
                            PatchStrings.t("Downloads and sharing"),
                            NotificationManager.IMPORTANCE_LOW));
            Notification notification = notification("Preparing your downloads");
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(
                        NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIFICATION, notification);
            foreground = true;
            engaged = true;
            lifetime.activate(SystemClock.elapsedRealtime());
            synchronized (LOCK) {
                active = this;
            }
            status = "Downloads and sharing are ready.";
            request.ready.countDown();
            main.removeCallbacks(refresh);
            main.postDelayed(refresh, REFRESH_MS);
        } catch (RuntimeException failure) {
            request.failure =
                    "The download notification could not start. Check app notification settings and"
                            + " retry.";
            request.ready.countDown();
            shutdown(request.failure, true);
        }
        return START_NOT_STICKY;
    }

    private Notification notification(String message) {
        int immutable = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open =
                PendingIntent.getActivity(
                        this,
                        NOTIFICATION,
                        new Intent(this, DownloadsHubActivity.class)
                                .addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK
                                                | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        immutable);
        PendingIntent stop =
                PendingIntent.getService(
                        this,
                        NOTIFICATION + 1,
                        new Intent(this, BulkTransferService.class).setAction(STOP),
                        immutable);
        Notification.Builder builder =
                new Notification.Builder(this, CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setContentTitle(PatchStrings.t("Downloads and sharing"))
                        .setContentText(PatchStrings.t(message))
                        .setContentIntent(open)
                        .setCategory(Notification.CATEGORY_SERVICE)
                        .setOnlyAlertOnce(true)
                        .setOngoing(true)
                        .addAction(
                                android.R.drawable.ic_menu_close_clear_cancel,
                                PatchStrings.t("Stop all"),
                                stop);
        if (Build.VERSION.SDK_INT >= 31)
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }

    private void inspect() {
        if (lifetime.stopping()) return;
        BulkDownloads downloads = BulkDownloads.existing();
        boolean publicSharing = PublicSoulseekShares.enabled(this);
        boolean busy =
                SoulseekSelectedDownloads.activeCount() > 0
                        || publicSharing
                        || DownloadArchives.activeCount() > 0;
        DebridDownloads debrid = DebridDownloads.existing();
        if (debrid != null) busy |= debrid.hasActiveWork();
        BulkCoordinator coordinator = BulkCoordinator.existing();
        boolean connections = coordinator != null && coordinator.isRunning();
        int offers = coordinator == null ? 0 : coordinator.offers().size();
        busy |=
                connections
                        || offers > 0
                        || (coordinator != null && coordinator.hasPendingHandshake());
        int shares = publicSharing ? 1 : 0,
                jobs = SoulseekSelectedDownloads.activeCount() + DownloadArchives.activeCount();
        if (debrid != null) jobs += debrid.activeCount();
        if (downloads != null) {
            busy |= downloads.hasActiveWork();
            shares += downloads.shares().size();
            for (BulkJobStore.Job job : downloads.jobs())
                if (job.state == BulkJobStore.State.QUEUED
                        || job.state == BulkJobStore.State.RUNNING
                        || job.state == BulkJobStore.State.PUBLISHING) jobs++;
        }
        if (lifetime.idle(SystemClock.elapsedRealtime(), busy)) {
            shutdown(
                    "Downloads paused after one minute without active transfers or shares.", false);
            return;
        }
        final String message =
                shares > 0 || jobs > 0
                        ? jobs + " transfer(s), " + shares + " sharing source(s)"
                        : offers > 0
                                ? "Open Peer downloads to review a file offer"
                                : connections
                                        ? "Download connections are ready"
                                        : "Ready for downloads · stops after one idle minute";
        main.post(
                () -> {
                    if (!foreground || lifetime.stopping()) return;
                    NotificationManager manager =
                            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    try {
                        if (manager != null) manager.notify(NOTIFICATION, notification(message));
                    } catch (RuntimeException unavailable) {
                        shutdown(
                                "Download notification is unavailable. Resume downloads"
                                        + " explicitly.",
                                true);
                        return;
                    }
                    main.postDelayed(refresh, REFRESH_MS);
                });
    }

    /** Android 15 data-sync budget: acknowledge promptly, clean up on the worker. */
    @Override
    public void onTimeout(int startId, int foregroundServiceType) {
        shutdown(
                "Downloads paused by Android's background time limit. Resume them explicitly.",
                true);
    }

    @Override
    public void onTimeout(int startId) {
        shutdown(
                "Downloads paused by Android's background time limit. Resume them explicitly.",
                true);
    }

    private void shutdown(String message, boolean immediate) {
        if (!lifetime.beginStop()) return;
        status = message;
        main.removeCallbacks(refresh);
        if (immediate) finishForeground();
        control.execute(
                () -> {
                    boolean failed = false;
                    SoulseekSelectedDownloads.cancelAll();
                    DownloadArchives.pauseAll();
                    try {
                        PublicSoulseekShares.pause(this);
                    } catch (Exception | LinkageError failure) {
                        failed = true;
                    }
                    DebridDownloads debrid = DebridDownloads.existing();
                    if (debrid != null) {
                        try {
                            debrid.stop();
                        } catch (Exception | LinkageError failure) {
                            failed = true;
                        }
                    }
                    BulkCoordinator coordinator = BulkCoordinator.existing();
                    if (coordinator != null) {
                        try {
                            coordinator.stop();
                        } catch (Exception | LinkageError failure) {
                            failed = true;
                        }
                    }
                    BulkDownloads downloads = BulkDownloads.existing();
                    if (downloads != null) {
                        try {
                            downloads.stop();
                        } catch (Exception | LinkageError failure) {
                            failed = true;
                        }
                    }
                    if (failed)
                        status =
                                "Downloads paused. Restart the app before starting connections"
                                        + " again.";
                    main.post(() -> finishForeground());
                });
    }

    private void finishForeground() {
        foreground = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(refresh);
        // A graceful lifecycle removal must close active resources; BulkDownloads
        // pauses running leases only and never rewrites completed job states.
        if (engaged && !lifetime.stopping())
            shutdown("Downloads paused. Resume them explicitly.", true);
        synchronized (LOCK) {
            if (active == this) {
                active = null;
                pending = null;
            }
        }
        control.shutdown();
        super.onDestroy();
    }
}
