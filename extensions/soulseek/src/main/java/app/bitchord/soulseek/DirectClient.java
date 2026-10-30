package app.bitchord.soulseek;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * User-started direct download transport. Nostr encrypts every endpoint descriptor. The descriptor
 * pins the provider's exact QUIC certificate. BulkFileProtocol authenticates the transfer before
 * any file bytes are served.
 */
public final class DirectClient implements Closeable {
    private final Context context;
    private final ConnectivityManager connectivity;
    private volatile Network network;
    private final AtomicLong node = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConcurrentHashMap<Long, Connection> connections =
            new ConcurrentHashMap<Long, Connection>();
    private boolean starting;
    private boolean callbackRegistered;
    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network available) {
                    Network selected = network;
                    if (selected != null && !selected.equals(available)) close();
                }

                @Override
                public void onLost(Network lost) {
                    Network selected = network;
                    if (selected != null && selected.equals(lost)) close();
                }
            };

    public DirectClient(Context context) {
        if (context == null) throw new NullPointerException("context");
        this.context = context.getApplicationContext();
        this.connectivity =
                (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public void start(int timeoutMs) throws IOException {
        workerOnly();
        checkTimeout(timeoutMs);
        synchronized (this) {
            checkOpen();
            if (node.get() != 0) return;
            if (starting) throw new IOException("Direct runtime startup already in progress");
            starting = true;
        }
        long handle = 0;
        try {
            Network selectedNetwork = currentNetwork();
            if (selectedNetwork == null)
                throw new IOException("No active network for direct transport");
            List<String> sources = stunSources();
            handle = DirectNative.start(sources.toArray(new String[sources.size()]), timeoutMs);
            if (handle <= 0) throw new IOException("Direct runtime unavailable");
            synchronized (this) {
                checkOpen();
                if (!selectedNetwork.equals(currentNetwork()))
                    throw new IOException(
                            "Network changed during direct discovery; start a fresh connection");
                network = selectedNetwork;
                node.set(handle);
                handle = 0;
                callbackRegistered = true;
                try {
                    connectivity.registerDefaultNetworkCallback(networkCallback);
                } catch (RuntimeException failure) {
                    close();
                    throw new IOException("Cannot monitor direct network changes", failure);
                }
            }
        } finally {
            if (handle != 0) DirectNative.closeNode(handle);
            synchronized (this) {
                starting = false;
            }
        }
    }

    public String endpoint(int timeoutMs) throws IOException {
        workerOnly();
        checkTimeout(timeoutMs);
        String descriptor = DirectNative.endpoint(requireNode());
        checkDescriptor(descriptor);
        checkNetwork();
        return descriptor;
    }

    public Connection connect(String descriptor, int timeoutMs) throws IOException {
        workerOnly();
        checkTimeout(timeoutMs);
        checkDescriptor(descriptor);
        long handle = DirectNative.connect(requireNode(), descriptor, timeoutMs);
        if (handle <= 0) throw new IOException("Direct connection unavailable");
        return connection(handle);
    }

    /** Null means no incoming connection before the deadline. */
    public Connection accept(int timeoutMs) throws IOException {
        workerOnly();
        checkTimeout(timeoutMs);
        long handle = DirectNative.accept(requireNode(), timeoutMs);
        return handle == 0 ? null : connection(handle);
    }

    /** Send bounded mapping-opening indications while the peer starts its QUIC handshake. */
    public void punch(String descriptor, int timeoutMs) throws IOException {
        workerOnly();
        checkTimeout(timeoutMs);
        checkDescriptor(descriptor);
        DirectNative.punch(requireNode(), descriptor, Math.min(timeoutMs, 5000));
    }

    private Connection connection(long handle) throws IOException {
        Connection connection = new Connection(handle);
        connections.put(handle, connection);
        try {
            checkNetwork();
        } catch (IOException failure) {
            connection.close();
            throw failure;
        }
        return connection;
    }

    private long requireNode() throws IOException {
        checkOpen();
        long value = node.get();
        if (value == 0) throw new IOException("Direct runtime not started");
        checkNetwork();
        return value;
    }

    private Network currentNetwork() throws IOException {
        if (connectivity == null) throw new IOException("Network state unavailable");
        try {
            return connectivity.getActiveNetwork();
        } catch (SecurityException failure) {
            throw new IOException("Network state permission unavailable", failure);
        }
    }

    private void checkNetwork() throws IOException {
        checkOpen();
        Network selected = network;
        if (selected == null || !selected.equals(currentNetwork())) {
            close();
            throw new IOException(
                    "Network changed; direct candidates expired and require a fresh connection");
        }
    }

    private List<String> stunSources() throws IOException {
        LinkedHashSet<String> candidates = new LinkedHashSet<String>(StunServers.defaults());
        try (InputStream input = context.getAssets().open("soulseek-native/stun-servers.txt")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] block = new byte[4096];
            int n;
            while ((n = input.read(block)) != -1) {
                if (bytes.size() + n > StunServers.MAX_TEXT_CHARS)
                    throw new IOException("Bundled STUN list exceeds bounds");
                bytes.write(block, 0, n);
            }
            for (String source :
                    StunServers.parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8))) {
                if (candidates.size() == StunServers.MAX_CANDIDATES) break;
                candidates.add(source);
            }
        } catch (IOException failure) {
            // The pinned bundled pool is optional in an older patched host. The two
            // established defaults are explicit fallback, never a runtime HTTP fetch.
        }
        candidates.addAll(StunServers.defaults());
        return StunServers.select(new ArrayList<String>(candidates), new SecureRandom());
    }

    private void checkOpen() throws IOException {
        if (closed.get()) throw new IOException("Direct client closed");
    }

    private static void checkTimeout(int timeoutMs) throws IOException {
        if (timeoutMs < 1 || timeoutMs > 120000)
            throw new IOException("Direct timeout outside supported bounds");
    }

    private static void checkDescriptor(String text) throws IOException {
        if (text == null
                || text.length() == 0
                || text.length() > 2048
                || text.getBytes(StandardCharsets.UTF_8).length > 2048)
            throw new IOException("Direct descriptor outside supported bounds");
    }

    private static void workerOnly() throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IOException("Direct network operation requires a worker thread");
    }

    @Override
    public void close() {
        boolean unregister;
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) return;
            unregister = callbackRegistered;
            callbackRegistered = false;
        }
        if (unregister)
            try {
                connectivity.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
            }
        for (Connection connection : connections.values()) connection.close();
        long value = node.getAndSet(0);
        if (value != 0) DirectNative.closeNode(value);
    }

    public final class Connection implements Closeable {
        private final long handle;
        private final AtomicBoolean streamClosed = new AtomicBoolean();
        public final InputStream input;
        public final OutputStream output;

        private Connection(long handle) {
            this.handle = handle;
            input =
                    new InputStream() {
                        @Override
                        public int read() throws IOException {
                            byte[] one = new byte[1];
                            int n = read(one, 0, 1);
                            return n < 0 ? -1 : one[0] & 255;
                        }

                        @Override
                        public int read(byte[] data, int offset, int length) throws IOException {
                            bounds(data, offset, length);
                            if (length == 0) return 0;
                            checkStream();
                            return DirectNative.read(
                                    Connection.this.handle,
                                    data,
                                    offset,
                                    Math.min(length, 65536),
                                    120000);
                        }

                        @Override
                        public void close() {
                            Connection.this.close();
                        }
                    };
            output =
                    new OutputStream() {
                        @Override
                        public void write(int value) throws IOException {
                            byte[] one = {(byte) value};
                            write(one, 0, 1);
                        }

                        @Override
                        public void write(byte[] data, int offset, int length) throws IOException {
                            bounds(data, offset, length);
                            while (length > 0) {
                                checkStream();
                                int n = Math.min(length, 65536);
                                DirectNative.write(Connection.this.handle, data, offset, n, 120000);
                                offset += n;
                                length -= n;
                            }
                        }

                        @Override
                        public void close() {
                            Connection.this.close();
                        }
                    };
        }

        /**
         * Half-close output and await transport acknowledgement of every queued byte. Call after a
         * successful application receipt, then close; close alone cancels.
         */
        public void finish() throws IOException {
            finish(30000);
        }

        public void finish(int timeoutMs) throws IOException {
            workerOnly();
            checkTimeout(timeoutMs);
            checkStream();
            DirectNative.finish(handle, timeoutMs);
        }

        private void checkStream() throws IOException {
            if (streamClosed.get() || closed.get()) throw new IOException("Direct stream closed");
            checkNetwork();
        }

        @Override
        public void close() {
            if (!streamClosed.compareAndSet(false, true)) return;
            connections.remove(handle, this);
            DirectNative.closeConnection(handle);
        }
    }

    private static void bounds(byte[] data, int offset, int length) {
        if (data == null) throw new NullPointerException("data");
        if (offset < 0 || length < 0 || offset > data.length - length)
            throw new IndexOutOfBoundsException();
    }
}
