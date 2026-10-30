package app.bitchord.soulseek;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** A user-started private overlay service. Blocking methods belong on a worker. */
public final class OverlayClient implements Closeable {
    public enum Kind {
        I2P,
        TOR
    }

    private final Context context;
    private final Intent intent;
    private final String nonce = UUID.randomUUID().toString();
    private final Object lifecycle = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentHashMap<Long, Pending> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Connection> connections = new ConcurrentHashMap<>();
    private final CountDownLatch bound = new CountDownLatch(1);
    private volatile Messenger service;
    private volatile Messenger replies;
    private volatile HandlerThread replyThread;
    private volatile boolean binding;
    private volatile boolean started;
    private volatile String phase = "stopped";
    private volatile IOException bindingFailure;

    private final ServiceConnection bindingCallback =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    if (closed.get()) return;
                    service = new Messenger(binder);
                    bound.countDown();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    disconnected("Overlay service stopped");
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    disconnected("Overlay service binding died");
                }

                @Override
                public void onNullBinding(ComponentName name) {
                    disconnected("Overlay service refused binding");
                }
            };

    public OverlayClient(Context context, Kind kind) {
        if (context == null || kind == null) throw new NullPointerException();
        this.context = context.getApplicationContext();
        this.intent =
                new Intent(this.context, kind == Kind.I2P ? I2pService.class : TorService.class);
        intent.setAction(OverlayService.ACTION_START);
    }

    /** Invoke from a download action while the app is allowed to start a foreground service. */
    public void start(int timeoutMs) throws IOException {
        workerOnly();
        long deadline = deadline(timeoutMs);
        try {
            synchronized (lifecycle) {
                checkOpen();
                if (started) return;
                phase = "starting";
                if (!binding) {
                    HandlerThread thread = new HandlerThread("bitchord-overlay-replies");
                    thread.start();
                    replyThread = thread;
                    replies =
                            new Messenger(
                                    new Handler(thread.getLooper()) {
                                        @Override
                                        public void handleMessage(Message message) {
                                            receive(message);
                                        }
                                    });
                    try {
                        context.startForegroundService(intent);
                        binding =
                                context.bindService(
                                        intent, bindingCallback, Context.BIND_AUTO_CREATE);
                        if (!binding) throw new IOException("Could not bind overlay service");
                    } catch (RuntimeException failure) {
                        throw new IOException("Could not start overlay service", failure);
                    }
                }
                await(bound, deadline, "Overlay service binding timed out");
                if (bindingFailure != null) throw bindingFailure;
                checkOpen();
                request(OverlayService.START, null, deadline);
                started = true;
            }
        } catch (IOException failure) {
            close();
            throw failure;
        }
    }

    public String endpoint(int timeoutMs) throws IOException {
        workerOnly();
        requireStarted();
        Bundle result = request(OverlayService.ENDPOINT, null, deadline(timeoutMs));
        String endpoint = result.getString(OverlayService.ENDPOINT_VALUE);
        if (endpoint == null || endpoint.length() == 0)
            throw new IOException("Overlay endpoint unavailable");
        return endpoint;
    }

    /** Cached status; requesting a refresh never blocks the UI thread. */
    public String status() {
        if (started && !closed.get()) sendOneWay(OverlayService.STATUS, 0, 0);
        return phase;
    }

    public Connection connect(String endpoint, int timeoutMs) throws IOException {
        workerOnly();
        requireStarted();
        if (endpoint == null || endpoint.length() == 0 || endpoint.length() > 256)
            throw new IOException("Invalid overlay endpoint");
        Bundle arguments = new Bundle();
        arguments.putString(OverlayService.ENDPOINT_VALUE, endpoint);
        return connection(request(OverlayService.CONNECT, arguments, deadline(timeoutMs)));
    }

    /** Returns null when no incoming stream arrives before the deadline. */
    public Connection accept(int timeoutMs) throws IOException {
        workerOnly();
        requireStarted();
        Bundle result = request(OverlayService.ACCEPT, null, deadline(timeoutMs));
        return result.getBoolean(OverlayService.TIMED_OUT) ? null : connection(result);
    }

    private Connection connection(Bundle reply) throws IOException {
        ParcelFileDescriptor descriptor = reply.getParcelable(OverlayService.DESCRIPTOR);
        long id = reply.getLong(OverlayService.REQUEST_ID);
        if (descriptor == null || id <= 0) throw new IOException("Overlay stream unavailable");
        Connection connection;
        try {
            connection = new Connection(id, descriptor);
        } catch (IOException failure) {
            closeQuietly(descriptor);
            sendOneWay(OverlayService.CLOSE_STREAM, 0, id);
            throw failure;
        }
        connections.put(id, connection);
        if (closed.get()) {
            connection.close();
            throw new IOException("Overlay client closed");
        }
        return connection;
    }

    private Bundle request(int operation, Bundle arguments, long deadline) throws IOException {
        checkOpen();
        Messenger remote = service;
        if (remote == null) throw new IOException("Overlay service disconnected");
        if (pending.size() >= 8) throw new IOException("Too many overlay requests");
        int operationTimeout = Math.max(1, remaining(deadline) - 100);
        long id = sequence.incrementAndGet();
        Pending call = new Pending();
        pending.put(id, call);
        Bundle data = arguments == null ? new Bundle() : new Bundle(arguments);
        data.putLong(OverlayService.REQUEST_ID, id);
        data.putString(OverlayService.NONCE, nonce);
        data.putInt(OverlayService.TIMEOUT, operationTimeout);
        Message message = Message.obtain(null, operation);
        message.setData(data);
        message.replyTo = replies;
        boolean succeeded = false;
        try {
            remote.send(message);
            await(call.done, deadline, "Overlay request timed out");
            Bundle result;
            IOException failure;
            synchronized (call) {
                result = call.result;
                call.result = null;
                call.abandoned = true;
                failure = call.failure;
            }
            if (failure != null) {
                if (result != null) discard(result);
                throw failure;
            }
            if (result == null) throw new IOException("Overlay reply missing");
            String error = result.getString(OverlayService.ERROR);
            if (error != null) {
                discard(result);
                if (result.getBoolean(OverlayService.TIMED_OUT))
                    throw new SocketTimeoutException(error);
                throw new IOException(error);
            }
            succeeded = true;
            return result;
        } catch (RemoteException failure) {
            throw new IOException("Overlay service disconnected", failure);
        } finally {
            pending.remove(id, call);
            synchronized (call) {
                call.abandoned = true;
                if (call.result != null) {
                    discard(call.result);
                    call.result = null;
                }
            }
            if (!succeeded) sendOneWay(OverlayService.CANCEL, 0, id);
        }
    }

    private void receive(Message message) {
        if (message.what != OverlayService.REPLY || message.sendingUid != Process.myUid()) return;
        Bundle data = message.getData();
        if (!nonce.equals(data.getString(OverlayService.NONCE))) {
            discard(data);
            return;
        }
        String nextPhase = data.getString(OverlayService.PHASE);
        if (nextPhase != null && !closed.get()) phase = nextPhase;
        long id = data.getLong(OverlayService.REQUEST_ID);
        if (id == 0) {
            discard(data);
            return;
        }
        Pending call = pending.get(id);
        if (call == null) {
            discard(data);
            return;
        }
        synchronized (call) {
            if (call.abandoned || closed.get() || call.result != null) {
                discard(data);
                return;
            }
            call.result = data;
            call.done.countDown();
        }
    }

    private void discard(Bundle bundle) {
        ParcelFileDescriptor descriptor = bundle.getParcelable(OverlayService.DESCRIPTOR);
        if (descriptor != null) {
            closeQuietly(descriptor);
            sendOneWay(OverlayService.CLOSE_STREAM, 0, bundle.getLong(OverlayService.REQUEST_ID));
        }
    }

    private void sendOneWay(int operation, long id, long target) {
        Messenger remote = service;
        if (remote == null) return;
        Bundle data = new Bundle();
        data.putString(OverlayService.NONCE, nonce);
        data.putLong(OverlayService.REQUEST_ID, id);
        data.putLong(OverlayService.TARGET_ID, target);
        Message message = Message.obtain(null, operation);
        message.replyTo = replies;
        message.setData(data);
        try {
            remote.send(message);
        } catch (RemoteException ignored) {
        }
    }

    private void disconnected(String reason) {
        service = null;
        bindingFailure = new IOException(reason);
        phase = "stopped";
        bound.countDown();
        for (Pending call : pending.values())
            synchronized (call) {
                call.failure = new IOException(reason);
                call.done.countDown();
            }
        for (Connection connection : connections.values()) connection.close();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        phase = "stopped";
        bindingFailure = new IOException("Overlay client closed");
        bound.countDown();
        sendOneWay(OverlayService.STOP, 0, 0);
        for (Connection connection : connections.values()) connection.close();
        for (Pending call : pending.values())
            synchronized (call) {
                call.failure = new IOException("Overlay client closed");
                if (call.result != null) {
                    discard(call.result);
                    call.result = null;
                }
                call.done.countDown();
            }
        if (binding) {
            try {
                context.unbindService(bindingCallback);
            } catch (IllegalArgumentException ignored) {
            }
        }
        service = null;
        HandlerThread thread = replyThread;
        if (thread != null) thread.quitSafely();
    }

    private void requireStarted() throws IOException {
        checkOpen();
        if (!started) throw new IOException("Start the overlay from a download action first");
    }

    private void checkOpen() throws IOException {
        if (closed.get()) throw new IOException("Overlay client closed");
    }

    private static void workerOnly() throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IOException("Blocking overlay operation must run on a worker");
    }

    private static long deadline(int timeoutMs) {
        if (timeoutMs < 1 || timeoutMs > OverlayService.MAX_TIMEOUT_MS)
            throw new IllegalArgumentException("Timeout must be 1..120000 ms");
        return SystemClock.elapsedRealtime() + timeoutMs;
    }

    private static int remaining(long deadline) throws SocketTimeoutException {
        long left = deadline - SystemClock.elapsedRealtime();
        if (left <= 0) throw new SocketTimeoutException("Overlay request timed out");
        return (int) Math.min(left, OverlayService.MAX_TIMEOUT_MS);
    }

    private static void await(CountDownLatch latch, long deadline, String reason)
            throws IOException {
        try {
            if (!latch.await(remaining(deadline), TimeUnit.MILLISECONDS))
                throw new SocketTimeoutException(reason);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Overlay request interrupted", failure);
        }
    }

    private static void closeQuietly(Closeable value) {
        try {
            value.close();
        } catch (IOException ignored) {
        }
    }

    private static final class Pending {
        final CountDownLatch done = new CountDownLatch(1);
        boolean abandoned;
        Bundle result;
        IOException failure;
    }

    public final class Connection implements Closeable {
        public final InputStream input;
        public final OutputStream output;
        private final long id;
        private final ParcelFileDescriptor descriptor;
        private final AtomicBoolean connectionClosed = new AtomicBoolean();
        private boolean finished;

        private Connection(long id, ParcelFileDescriptor descriptor) throws IOException {
            this.id = id;
            this.descriptor = descriptor;
            ParcelFileDescriptor writer = ParcelFileDescriptor.dup(descriptor.getFileDescriptor());
            input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
            output = new ParcelFileDescriptor.AutoCloseOutputStream(writer);
        }

        /** Drain queued IPC writes before the normal protocol close. close() still aborts. */
        public synchronized void finish() throws IOException {
            workerOnly();
            if (connectionClosed.get()) throw new IOException("Overlay stream closed");
            if (finished) return;
            output.flush();
            IOException shutdownFailure = null;
            try {
                Os.shutdown(descriptor.getFileDescriptor(), OsConstants.SHUT_WR);
            } catch (ErrnoException failure) {
                shutdownFailure = new IOException("Cannot finish overlay stream", failure);
            }
            try {
                if (input.read() != -1) throw new IOException("Unexpected trailing overlay bytes");
            } catch (IOException failure) {
                if (shutdownFailure != null) failure.addSuppressed(shutdownFailure);
                throw failure;
            }
            finished = true;
        }

        @Override
        public void close() {
            if (!connectionClosed.compareAndSet(false, true)) return;
            connections.remove(id, this);
            try {
                Os.shutdown(descriptor.getFileDescriptor(), OsConstants.SHUT_RDWR);
            } catch (ErrnoException ignored) {
            }
            closeQuietly(input);
            closeQuietly(output);
            sendOneWay(OverlayService.CLOSE_STREAM, 0, id);
        }
    }
}
