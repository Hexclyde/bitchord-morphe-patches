package app.bitchord.soulseek;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;

import app.bitchord.telegram.PatchStrings;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Same-UID IPC and bounded stream pumps shared by the two private router processes. */
public abstract class OverlayService extends Service {
    static final String ACTION_START = "app.bitchord.soulseek.START_OVERLAY";
    static final int START = 1, STATUS = 2, ENDPOINT = 3, CONNECT = 4, ACCEPT = 5;
    static final int CANCEL = 6, CLOSE_STREAM = 7, STOP = 8, REPLY = 9;
    static final String REQUEST_ID = "request", TARGET_ID = "target", NONCE = "nonce";
    static final String ENDPOINT_VALUE = "endpoint", TIMEOUT = "timeout", DESCRIPTOR = "descriptor";
    static final String ERROR = "error", PHASE = "phase", TIMED_OUT = "timedOut";
    static final int MAX_TIMEOUT_MS = 120000;
    private static final int IO_TIMEOUT_MS = 30000;
    private static final String CHANNEL = "bitchord-overlay-downloads";

    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean shutdownFinished = new AtomicBoolean();
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final Semaphore slots = new Semaphore(2);
    private final Semaphore accepting = new Semaphore(1);
    private final ConcurrentHashMap<Long, Request> requests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Bridge> streams = new ConcurrentHashMap<>();
    private final ExecutorService lifecycle =
            Executors.newSingleThreadExecutor(threads("overlay-lifecycle"));
    private final ThreadPoolExecutor operations =
            new ThreadPoolExecutor(
                    2,
                    2,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<Runnable>(2),
                    threads("overlay-request"),
                    new ThreadPoolExecutor.AbortPolicy());
    private final ThreadPoolExecutor pumps =
            new ThreadPoolExecutor(
                    4,
                    4,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<Runnable>(4),
                    threads("overlay-stream"),
                    new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledThreadPoolExecutor deadlines =
            new ScheduledThreadPoolExecutor(1, threads("overlay-deadline"));
    private volatile boolean initialized;
    private volatile boolean starting;
    private volatile boolean foreground;
    private volatile String phase = "stopped";
    private volatile IBinder owner;
    private volatile String session;
    private HandlerThread ipcThread;
    private Handler ipc;
    private Messenger messenger;
    private final IBinder.DeathRecipient ownerDeath =
            new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    shutdown();
                }
            };

    protected abstract String label();

    protected abstract String processSuffix();

    protected abstract void startRuntime(File home) throws IOException;

    protected abstract String runtimeStatus() throws IOException;

    protected abstract String runtimeEndpoint() throws IOException;

    protected abstract Transport connectRuntime(String endpoint, int timeoutMs) throws IOException;

    protected abstract Transport acceptRuntime(int timeoutMs) throws IOException;

    protected abstract void stopRuntime();

    protected interface Transport extends Closeable {
        int read(byte[] bytes, int timeoutMs) throws IOException;

        void write(byte[] bytes, int length, int timeoutMs) throws IOException;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        PatchStrings.refresh(this);
        ipcThread = new HandlerThread("bitchord-overlay-ipc");
        ipcThread.start();
        ipc =
                new Handler(ipcThread.getLooper()) {
                    @Override
                    public void handleMessage(Message message) {
                        dispatch(message);
                    }
                };
        messenger = new Messenger(ipc);
        deadlines.setRemoveOnCancelPolicy(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (stopping.get()
                || intent == null
                || !ACTION_START.equals(intent.getAction())
                || !privateProcess()) {
            stopSelf();
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
                            PatchStrings.t("Peer downloads"),
                            NotificationManager.IMPORTANCE_LOW));
            Notification notification =
                    new Notification.Builder(this, CHANNEL)
                            .setSmallIcon(android.R.drawable.stat_sys_download)
                            .setContentTitle(PatchStrings.t("Peer downloads"))
                            .setContentText(PatchStrings.f("{0} download connections", label()))
                            .setCategory(Notification.CATEGORY_SERVICE)
                            .setOngoing(true)
                            .build();
            startForeground(7910 + ("I2P".equals(label()) ? 1 : 2), notification);
            foreground = true;
            ipc.postDelayed(
                    new Runnable() {
                        @Override
                        public void run() {
                            if (owner == null) shutdown();
                        }
                    },
                    10000);
        } catch (RuntimeException failure) {
            phase = "unavailable";
            shutdown();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        shutdown();
        return false;
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    @Override
    public void onTimeout(int startId, int foregroundServiceType) {
        foreground = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        shutdown();
    }

    private void dispatch(Message message) {
        if (message.sendingUid != Process.myUid() || message.replyTo == null) return;
        Bundle arguments = message.getData();
        String nonce = arguments.getString(NONCE);
        long id = arguments.getLong(REQUEST_ID);
        if (nonce == null || nonce.length() < 16 || nonce.length() > 64 || id < 0) return;
        Messenger caller = message.replyTo;
        if (message.what == START && owner == null && !stopping.get()) {
            try {
                IBinder binder = caller.getBinder();
                binder.linkToDeath(ownerDeath, 0);
                owner = binder;
                session = nonce;
            } catch (RemoteException failure) {
                shutdown();
                return;
            }
        }
        if (!nonce.equals(session) || owner == null || !owner.equals(caller.getBinder())) {
            error(caller, nonce, id, "Overlay service already has a client", false);
            return;
        }
        if (message.what == STOP) {
            shutdown();
            return;
        }
        if (message.what == CANCEL || message.what == CLOSE_STREAM) {
            long target = arguments.getLong(TARGET_ID);
            Request request = requests.get(target);
            if (request != null) request.cancel();
            Bridge stream = streams.get(target);
            if (stream != null) stream.close();
            return;
        }
        if (stopping.get()) {
            error(caller, nonce, id, "Overlay service stopping", false);
            return;
        }
        if (message.what == STATUS) {
            refreshStatus(caller, nonce, id);
            return;
        }
        if (id == 0 || requests.containsKey(id) || streams.containsKey(id)) {
            error(caller, nonce, id, "Invalid overlay request", false);
            return;
        }
        if (message.what == START) {
            start(caller, nonce, id);
            return;
        }
        if (!initialized) {
            error(caller, nonce, id, "Overlay runtime is not started", false);
            return;
        }
        if (message.what == ENDPOINT) {
            try {
                Bundle result = new Bundle();
                result.putString(ENDPOINT_VALUE, runtimeEndpoint());
                reply(caller, nonce, id, result);
            } catch (IOException failure) {
                error(caller, nonce, id, "Overlay endpoint unavailable", false);
            }
            return;
        }
        if (message.what != CONNECT && message.what != ACCEPT) {
            error(caller, nonce, id, "Unknown overlay operation", false);
            return;
        }
        int timeout = arguments.getInt(TIMEOUT);
        String endpoint = arguments.getString(ENDPOINT_VALUE);
        if (timeout < 1
                || timeout > MAX_TIMEOUT_MS
                || (message.what == CONNECT
                        && (endpoint == null
                                || endpoint.length() == 0
                                || endpoint.length() > 256))) {
            error(caller, nonce, id, "Invalid overlay request", false);
            return;
        }
        final Request request = new Request(id, caller, nonce, message.what, endpoint, timeout);
        requests.put(id, request);
        try {
            request.future = operations.submit(request);
        } catch (RejectedExecutionException failure) {
            requests.remove(id, request);
            error(caller, nonce, id, "Overlay transfer limit reached", false);
        }
    }

    private void start(final Messenger caller, final String nonce, final long id) {
        if (!privateProcess() || !foreground) {
            error(caller, nonce, id, "Overlay needs its private foreground service", false);
            shutdown();
            return;
        }
        if (initialized) {
            reply(caller, nonce, id, new Bundle());
            return;
        }
        if (starting) {
            error(caller, nonce, id, "Overlay startup already in progress", false);
            return;
        }
        starting = true;
        phase = "starting";
        lifecycle.execute(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            File home =
                                    new File(
                                            getNoBackupFilesDir(),
                                            "bitchord-"
                                                    + label().toLowerCase(java.util.Locale.ROOT));
                            privateDirectory(home);
                            startRuntime(home);
                            initialized = true;
                            if (!stopping.get()) reply(caller, nonce, id, new Bundle());
                        } catch (IOException | RuntimeException | LinkageError failure) {
                            phase = "unavailable";
                            error(caller, nonce, id, label() + " runtime could not start", false);
                            shutdown();
                        } finally {
                            starting = false;
                        }
                    }
                });
    }

    private void refreshStatus(final Messenger caller, final String nonce, final long id) {
        if (!initialized || !refreshing.compareAndSet(false, true)) {
            reply(caller, nonce, id, new Bundle());
            return;
        }
        try {
            lifecycle.execute(
                    new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (!stopping.get()) phase = runtimeStatus();
                            } catch (IOException | RuntimeException | LinkageError failure) {
                                phase = "unavailable";
                            } finally {
                                refreshing.set(false);
                            }
                            reply(caller, nonce, id, new Bundle());
                        }
                    });
        } catch (RejectedExecutionException ignored) {
            refreshing.set(false);
            reply(caller, nonce, id, new Bundle());
        }
    }

    private boolean reply(Messenger caller, String nonce, long id, Bundle result) {
        result.putString(NONCE, nonce);
        result.putLong(REQUEST_ID, id);
        result.putString(PHASE, stopping.get() ? "stopped" : phase);
        Message message = Message.obtain(null, REPLY);
        message.setData(result);
        try {
            caller.send(message);
            return true;
        } catch (RemoteException failure) {
            if (nonce.equals(session) && owner != null && owner.equals(caller.getBinder()))
                shutdown();
            return false;
        }
    }

    private void error(Messenger caller, String nonce, long id, String reason, boolean timeout) {
        Bundle result = new Bundle();
        result.putString(ERROR, reason);
        result.putBoolean(TIMED_OUT, timeout);
        reply(caller, nonce, id, result);
    }

    private void shutdown() {
        if (!stopping.compareAndSet(false, true)) return;
        phase = "stopped";
        Thread cleanup =
                threads("overlay-stop")
                        .newThread(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        operations.shutdownNow();
                                        for (Request request : requests.values()) request.cancel();
                                        for (Bridge bridge : streams.values()) bridge.close();
                                        pumps.shutdownNow();
                                        deadlines.shutdownNow();
                                        lifecycle.execute(
                                                new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        final Handler main =
                                                                new Handler(getMainLooper());
                                                        final Runnable deadline =
                                                                new Runnable() {
                                                                    @Override
                                                                    public void run() {
                                                                        if (!shutdownFinished.get()
                                                                                && privateProcess())
                                                                            Process.killProcess(
                                                                                    Process
                                                                                            .myPid());
                                                                    }
                                                                };
                                                        main.postDelayed(deadline, 20000);
                                                        try {
                                                            stopRuntime();
                                                        } finally {
                                                            main.removeCallbacks(deadline);
                                                            finishShutdown();
                                                        }
                                                    }
                                                });
                                        lifecycle.shutdown();
                                    }
                                });
        cleanup.start();
    }

    private void finishShutdown() {
        if (!shutdownFinished.compareAndSet(false, true)) return;
        initialized = false;
        IBinder binder = owner;
        if (binder != null) {
            try {
                binder.unlinkToDeath(ownerDeath, 0);
            } catch (RuntimeException ignored) {
            }
        }
        new Handler(getMainLooper())
                .post(
                        new Runnable() {
                            @Override
                            public void run() {
                                stopForeground(STOP_FOREGROUND_REMOVE);
                                stopSelf();
                                if (ipcThread != null) ipcThread.quitSafely();
                                // Native router globals must never be initialized twice in one
                                // process.
                                if (privateProcess()) Process.killProcess(Process.myPid());
                            }
                        });
    }

    private boolean privateProcess() {
        String name;
        if (android.os.Build.VERSION.SDK_INT >= 28) name = Application.getProcessName();
        else {
            try (InputStream input = new FileInputStream("/proc/self/cmdline")) {
                byte[] bytes = new byte[256];
                int length = input.read(bytes);
                int end = 0;
                while (end < length && bytes[end] != 0) end++;
                name = new String(bytes, 0, end, StandardCharsets.UTF_8);
            } catch (IOException failure) {
                return false;
            }
        }
        return name != null
                && name.endsWith(processSuffix())
                && !name.equals(getApplicationInfo().processName);
    }

    protected static void privateDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Private overlay directory unavailable");
        try {
            Os.chmod(directory.getAbsolutePath(), 0700);
        } catch (ErrnoException failure) {
            throw new IOException("Cannot protect overlay directory", failure);
        }
    }

    private static ThreadFactory threads(final String name) {
        final AtomicInteger sequence = new AtomicInteger();
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread =
                        new Thread(runnable, "bitchord-" + name + "-" + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    private static void closeQuietly(Closeable value) {
        try {
            value.close();
        } catch (IOException ignored) {
        }
    }

    private final class Request implements Runnable {
        final long id;
        final Messenger caller;
        final String nonce;
        final int operation;
        final String endpoint;
        final int timeout;
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile Future<?> future;
        volatile Transport transport;

        Request(
                long id,
                Messenger caller,
                String nonce,
                int operation,
                String endpoint,
                int timeout) {
            this.id = id;
            this.caller = caller;
            this.nonce = nonce;
            this.operation = operation;
            this.endpoint = endpoint;
            this.timeout = timeout;
        }

        void cancel() {
            cancelled.set(true);
            requests.remove(id, this);
            Future<?> task = future;
            if (task != null) task.cancel(true);
            operations.purge();
            Transport current = transport;
            if (current != null) closeQuietly(current);
        }

        @Override
        public void run() {
            boolean acquired = false;
            boolean acceptAcquired = false;
            boolean transferred = false;
            ParcelFileDescriptor remote = null;
            try {
                if (cancelled.get() || stopping.get()) return;
                long began = SystemClock.elapsedRealtime();
                if (operation == ACCEPT) {
                    if (!accepting.tryAcquire())
                        throw new IOException("An overlay accept is already pending");
                    acceptAcquired = true;
                    try {
                        acquired = slots.tryAcquire(timeout, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!acquired) {
                        Bundle result = new Bundle();
                        result.putBoolean(TIMED_OUT, true);
                        reply(caller, nonce, id, result);
                        return;
                    }
                } else {
                    if (!slots.tryAcquire())
                        throw new IOException("Overlay transfer limit reached");
                    acquired = true;
                }
                int remaining =
                        Math.max(1, timeout - (int) (SystemClock.elapsedRealtime() - began));
                Transport channel =
                        operation == CONNECT
                                ? connectRuntime(endpoint, remaining)
                                : acceptRuntime(remaining);
                transport = channel;
                if (cancelled.get() || stopping.get()) return;
                if (channel == null) {
                    Bundle result = new Bundle();
                    result.putBoolean(TIMED_OUT, true);
                    reply(caller, nonce, id, result);
                    return;
                }
                ParcelFileDescriptor[] pair = ParcelFileDescriptor.createSocketPair();
                remote = pair[1];
                try {
                    for (ParcelFileDescriptor descriptor : pair) {
                        Os.setsockoptTimeval(
                                descriptor.getFileDescriptor(),
                                OsConstants.SOL_SOCKET,
                                OsConstants.SO_RCVTIMEO,
                                StructTimeval.fromMillis(IO_TIMEOUT_MS));
                        Os.setsockoptTimeval(
                                descriptor.getFileDescriptor(),
                                OsConstants.SOL_SOCKET,
                                OsConstants.SO_SNDTIMEO,
                                StructTimeval.fromMillis(IO_TIMEOUT_MS));
                    }
                } catch (ErrnoException failure) {
                    closeQuietly(pair[0]);
                    throw new IOException("Cannot set overlay stream deadlines", failure);
                }
                Bridge bridge;
                try {
                    bridge = new Bridge(id, channel, pair[0]);
                } catch (IOException failure) {
                    closeQuietly(pair[0]);
                    throw failure;
                }
                streams.put(id, bridge);
                transferred = true;
                transport = null;
                if (cancelled.get() || stopping.get()) {
                    bridge.close();
                    return;
                }
                bridge.start();
                Bundle result = new Bundle();
                result.putParcelable(DESCRIPTOR, remote);
                if (!reply(caller, nonce, id, result)) bridge.close();
            } catch (SocketTimeoutException failure) {
                if (!cancelled.get() && !stopping.get()) {
                    if (operation == ACCEPT) {
                        Bundle result = new Bundle();
                        result.putBoolean(TIMED_OUT, true);
                        reply(caller, nonce, id, result);
                    } else error(caller, nonce, id, "Overlay connection timed out", true);
                }
            } catch (IOException | RuntimeException | LinkageError failure) {
                if (!cancelled.get() && !stopping.get())
                    error(caller, nonce, id, "Overlay connection failed", false);
                Bridge bridge = streams.get(id);
                if (bridge != null) bridge.close();
            } finally {
                requests.remove(id, this);
                if (remote != null) closeQuietly(remote);
                Transport current = transport;
                if (current != null) closeQuietly(current);
                transport = null;
                if (acquired && !transferred) slots.release();
                if (acceptAcquired) accepting.release();
            }
        }
    }

    private final class Bridge implements Closeable {
        final long id;
        final Transport transport;
        final InputStream input;
        final OutputStream output;
        final ParcelFileDescriptor descriptor;
        final AtomicBoolean closed = new AtomicBoolean();

        Bridge(long id, Transport transport, ParcelFileDescriptor descriptor) throws IOException {
            this.id = id;
            this.transport = transport;
            this.descriptor = descriptor;
            ParcelFileDescriptor writer = ParcelFileDescriptor.dup(descriptor.getFileDescriptor());
            input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
            output = new ParcelFileDescriptor.AutoCloseOutputStream(writer);
        }

        void start() {
            pumps.execute(
                    new Runnable() {
                        @Override
                        public void run() {
                            byte[] buffer = new byte[32768];
                            try {
                                while (!closed.get()) {
                                    int count = input.read(buffer);
                                    if (count < 0) break;
                                    if (count > 0) write(buffer, count);
                                }
                            } catch (IOException | RuntimeException ignored) {
                            } finally {
                                close();
                            }
                        }
                    });
            pumps.execute(
                    new Runnable() {
                        @Override
                        public void run() {
                            byte[] buffer = new byte[32768];
                            try {
                                while (!closed.get()) {
                                    int count = transport.read(buffer, IO_TIMEOUT_MS);
                                    if (count < 0) break;
                                    if (count > 0) output.write(buffer, 0, count);
                                }
                            } catch (IOException | RuntimeException ignored) {
                            } finally {
                                close();
                            }
                        }
                    });
        }

        private void write(byte[] buffer, int count) throws IOException {
            final AtomicBoolean completed = new AtomicBoolean();
            ScheduledFuture<?> timeout =
                    deadlines.schedule(
                            new Runnable() {
                                @Override
                                public void run() {
                                    if (completed.compareAndSet(false, true)) close();
                                }
                            },
                            IO_TIMEOUT_MS,
                            TimeUnit.MILLISECONDS);
            try {
                transport.write(buffer, count, IO_TIMEOUT_MS);
            } finally {
                completed.set(true);
                timeout.cancel(false);
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            streams.remove(id, this);
            try {
                Os.shutdown(descriptor.getFileDescriptor(), OsConstants.SHUT_RDWR);
            } catch (ErrnoException ignored) {
            }
            closeQuietly(transport);
            closeQuietly(input);
            closeQuietly(output);
            slots.release();
        }
    }
}
