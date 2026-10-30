package app.bitchord.soulseek;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URI;
import java.net.URL;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Bounded DNS and public-address enforcement without replacing platform TLS trust. */
final class DebridTlsSockets {
    interface Resolver {
        InetAddress[] resolve(String hostname) throws IOException;
    }

    interface Connector {
        Socket connect(
                InetAddress address,
                int port,
                SocketAddress local,
                int timeout,
                DebridModels.Cancellation cancellation)
                throws IOException;
    }

    private static final ThreadPoolExecutor DNS =
            new ThreadPoolExecutor(
                    2,
                    2,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<Runnable>(8),
                    work -> {
                        Thread thread = new Thread(work, "debrid-dns");
                        thread.setDaemon(true);
                        return thread;
                    });

    private DebridTlsSockets() {}

    static HttpsURLConnection open(
            String value, DebridModels.Cancellation cancellation, int timeoutMs)
            throws IOException {
        return open(
                value,
                cancellation,
                timeoutMs,
                InetAddress::getAllByName,
                HttpsURLConnection.getDefaultSSLSocketFactory(),
                DebridTlsSockets::connect);
    }

    static HttpsURLConnection open(
            String value,
            DebridModels.Cancellation cancellation,
            int timeoutMs,
            Resolver resolver,
            SSLSocketFactory tls,
            Connector connector)
            throws IOException {
        URL url = url(value);
        InetAddress[] addresses = resolve(url.getHost(), cancellation, timeoutMs, resolver);
        // A user/system HTTP proxy would otherwise change the connected address behind this check.
        // VPN routing remains under Android's network stack.
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection(Proxy.NO_PROXY);
        connection.setSSLSocketFactory(
                new Factory(
                        url.getHost(),
                        url.getPort() < 0 ? 443 : url.getPort(),
                        addresses,
                        timeoutMs,
                        cancellation,
                        tls,
                        connector));
        return connection;
    }

    static URL url(String value) throws IOException {
        try {
            if (value == null || value.length() > 16384 || DebridModels.hasControl(value))
                throw new IllegalArgumentException();
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getRawUserInfo() != null
                    || uri.getFragment() != null
                    || uri.getHost() == null
                    || uri.getHost().contains("%")
                    || (uri.getPort() != -1 && uri.getPort() != 443))
                throw new IllegalArgumentException();
            return uri.toURL();
        } catch (Exception invalid) {
            throw new IOException("Use a public HTTPS download URL.");
        }
    }

    static InetAddress[] resolve(
            String host, DebridModels.Cancellation cancellation, int timeoutMs, Resolver resolver)
            throws IOException {
        if (timeoutMs < 1 || timeoutMs > 120000) throw new IOException("Invalid DNS deadline.");
        cancellation.check();
        final Future<InetAddress[]> future;
        try {
            future = DNS.submit(() -> resolver.resolve(host));
        } catch (RejectedExecutionException busy) {
            throw new IOException("Download hostname lookups are busy. Retry shortly.");
        }
        Closeable cancel = () -> future.cancel(true);
        try {
            cancellation.bind(cancel);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (true) {
                cancellation.check();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0)
                    throw new IOException("The download hostname lookup timed out.");
                try {
                    InetAddress[] result =
                            future.get(
                                    Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)),
                                    TimeUnit.NANOSECONDS);
                    if (result == null || result.length == 0 || result.length > 32)
                        throw new IOException(
                                "The download hostname returned an invalid address list.");
                    for (InetAddress address : result)
                        if (address == null || !DebridHttp.isPublic(address))
                            throw new IOException(
                                    "Private or reserved network addresses are not allowed.");
                    cancellation.check();
                    return result.clone();
                } catch (TimeoutException poll) {
                    // Recheck cancellation while a platform resolver is still blocked.
                } catch (ExecutionException | java.util.concurrent.CancellationException failed) {
                    cancellation.check();
                    throw new IOException("The download hostname could not be resolved.");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Download hostname lookup cancelled.");
                }
            }
        } finally {
            future.cancel(true);
            DNS.remove((Runnable) future);
            DNS.purge();
            cancellation.clear(cancel);
        }
    }

    private static Socket connect(
            InetAddress address,
            int port,
            SocketAddress local,
            int timeout,
            DebridModels.Cancellation cancellation)
            throws IOException {
        Socket socket = new Socket();
        Closeable cancel = socket::close;
        try {
            cancellation.bind(cancel);
            if (local != null) socket.bind(local);
            socket.connect(new InetSocketAddress(address, port), timeout);
            cancellation.check();
            return socket;
        } catch (IOException failure) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw failure;
        } finally {
            cancellation.clear(cancel);
        }
    }

    static final class Factory extends SSLSocketFactory {
        private final String host;
        private final int port, timeout;
        private final InetAddress[] addresses;
        private final DebridModels.Cancellation cancellation;
        private final SSLSocketFactory tls;
        private final Connector connector;

        Factory(
                String host,
                int port,
                InetAddress[] addresses,
                int timeout,
                DebridModels.Cancellation cancellation,
                SSLSocketFactory tls,
                Connector connector)
                throws IOException {
            if (host == null
                    || port != 443
                    || addresses == null
                    || addresses.length == 0
                    || timeout < 1
                    || timeout > 120000
                    || tls == null
                    || connector == null)
                throw new IOException("Invalid download TLS configuration.");
            for (InetAddress address : addresses)
                if (address == null || !DebridHttp.isPublic(address))
                    throw new IOException("Private or reserved network addresses are not allowed.");
            this.host = host;
            this.port = port;
            this.addresses = addresses.clone();
            this.timeout = timeout;
            this.cancellation = cancellation;
            this.tls = tls;
            this.connector = connector;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return tls.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return tls.getSupportedCipherSuites();
        }

        private void destination(String candidate, int requestedPort) throws IOException {
            if (requestedPort != port || candidate == null || !host.equalsIgnoreCase(candidate))
                throw new IOException("The TLS destination changed unexpectedly.");
        }

        private void allowed(Socket socket) throws IOException {
            InetAddress remote = socket.getInetAddress();
            if (!socket.isConnected()
                    || socket.getPort() != port
                    || remote == null
                    || !DebridHttp.isPublic(remote)
                    || !Arrays.asList(addresses).contains(remote))
                throw new IOException("The connected download address was not approved.");
        }

        private Socket wrap(Socket socket) throws IOException {
            if (socket == null) throw new IOException("The download socket is unavailable.");
            Closeable cancel = socket::close;
            try {
                cancellation.bind(cancel);
                allowed(socket);
                cancellation.check();
                // The original hostname is retained for SNI and ordinary hostname verification.
                Socket secured = tls.createSocket(socket, host, port, true);
                try {
                    allowed(secured);
                    cancellation.check();
                    return secured;
                } catch (IOException failure) {
                    try {
                        secured.close();
                    } catch (IOException ignored) {
                    }
                    throw failure;
                }
            } catch (IOException failure) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                throw failure;
            } finally {
                cancellation.clear(cancel);
            }
        }

        private Socket connected(SocketAddress local) throws IOException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
            for (InetAddress address : addresses) {
                cancellation.check();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                Socket socket = null;
                try {
                    int wait =
                            (int)
                                    Math.max(
                                            1,
                                            Math.min(
                                                    timeout,
                                                    TimeUnit.NANOSECONDS.toMillis(remaining)));
                    socket = connector.connect(address, port, local, wait, cancellation);
                    return wrap(socket);
                } catch (IOException failure) {
                    if (socket != null)
                        try {
                            socket.close();
                        } catch (IOException ignored) {
                        }
                    cancellation.check();
                }
            }
            throw new IOException("The download server could not be reached securely.");
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose)
                throws IOException {
            try {
                destination(host, port);
                return wrap(socket);
            } catch (IOException failure) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                throw failure;
            }
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            destination(host, port);
            return connected(null);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress local, int localPort)
                throws IOException {
            destination(host, port);
            return connected(new InetSocketAddress(local, localPort));
        }

        @Override
        public Socket createSocket(InetAddress address, int port) throws IOException {
            if (port != this.port || !Arrays.asList(addresses).contains(address))
                throw new IOException("The TLS destination changed unexpectedly.");
            return connected(null);
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress local, int localPort)
                throws IOException {
            if (port != this.port || !Arrays.asList(addresses).contains(address))
                throw new IOException("The TLS destination changed unexpectedly.");
            return connected(new InetSocketAddress(local, localPort));
        }

        @Override
        public Socket createSocket() throws IOException {
            return new RoutedSocket(this, (SSLSocket) tls.createSocket());
        }
    }

    /**
     * Covers clients that call createSocket() first and connect to a newly resolved address later.
     */
    private static final class RoutedSocket extends SSLSocket {
        private final Factory factory;
        private volatile SSLSocket delegate;
        private final List<HandshakeCompletedListener> listeners = new ArrayList<>();
        private volatile boolean closed, connecting, connected;
        private SocketAddress local;

        RoutedSocket(Factory factory, SSLSocket initial) {
            this.factory = factory;
            delegate = initial;
        }

        @Override
        public void connect(SocketAddress endpoint) throws IOException {
            connect(endpoint, factory.timeout);
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            synchronized (this) {
                if (closed || connecting || connected)
                    throw new SocketException("Download socket is not available.");
                if (!(endpoint instanceof InetSocketAddress))
                    throw new SocketException("Invalid TLS destination.");
                InetSocketAddress target = (InetSocketAddress) endpoint;
                String name = target.getHostString();
                if (target.getPort() != factory.port
                        || !(factory.host.equalsIgnoreCase(name)
                                || (target.getAddress() != null
                                        && Arrays.asList(factory.addresses)
                                                .contains(target.getAddress()))))
                    throw new SocketException("The TLS destination changed unexpectedly.");
                connecting = true;
            }
            SSLSocket previous = delegate;
            SSLSocket secured = null;
            try {
                secured = (SSLSocket) factory.connected(local);
                secured.setUseClientMode(previous.getUseClientMode());
                secured.setEnableSessionCreation(previous.getEnableSessionCreation());
                SSLParameters parameters = previous.getSSLParameters();
                if (!factory.host.contains(":") && !factory.host.matches("[0-9.]+"))
                    parameters.setServerNames(
                            java.util.Collections.singletonList(new SNIHostName(factory.host)));
                secured.setSSLParameters(parameters);
                secured.setEnabledCipherSuites(previous.getEnabledCipherSuites());
                secured.setEnabledProtocols(previous.getEnabledProtocols());
                secured.setSoTimeout(previous.getSoTimeout());
                secured.setTcpNoDelay(previous.getTcpNoDelay());
                secured.setKeepAlive(previous.getKeepAlive());
                secured.setReuseAddress(previous.getReuseAddress());
                int linger = previous.getSoLinger();
                secured.setSoLinger(linger >= 0, Math.max(0, linger));
                secured.setReceiveBufferSize(previous.getReceiveBufferSize());
                secured.setSendBufferSize(previous.getSendBufferSize());
                secured.setTrafficClass(previous.getTrafficClass());
                synchronized (this) {
                    if (closed) throw new SocketException("Download socket closed.");
                    for (HandshakeCompletedListener listener : listeners)
                        secured.addHandshakeCompletedListener(listener);
                    delegate = secured;
                    connected = true;
                    secured = null;
                }
            } finally {
                connecting = false;
                previous.close();
                if (secured != null) secured.close();
            }
        }

        @Override
        public synchronized void bind(SocketAddress endpoint) throws IOException {
            if (connected
                    || connecting
                    || local != null
                    || !(endpoint instanceof InetSocketAddress))
                throw new SocketException("Download socket cannot bind here.");
            local = endpoint;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            delegate.close();
        }

        @Override
        public boolean isClosed() {
            return closed || delegate.isClosed();
        }

        @Override
        public boolean isConnected() {
            return connected && delegate.isConnected();
        }

        @Override
        public boolean isBound() {
            return connected ? delegate.isBound() : local != null;
        }

        @Override
        public InetAddress getInetAddress() {
            return delegate.getInetAddress();
        }

        @Override
        public InetAddress getLocalAddress() {
            return delegate.getLocalAddress();
        }

        @Override
        public int getPort() {
            return delegate.getPort();
        }

        @Override
        public int getLocalPort() {
            return delegate.getLocalPort();
        }

        @Override
        public SocketAddress getRemoteSocketAddress() {
            return delegate.getRemoteSocketAddress();
        }

        @Override
        public SocketAddress getLocalSocketAddress() {
            return delegate.getLocalSocketAddress();
        }

        @Override
        public SocketChannel getChannel() {
            return delegate.getChannel();
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return delegate.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return delegate.getOutputStream();
        }

        @Override
        public void shutdownInput() throws IOException {
            delegate.shutdownInput();
        }

        @Override
        public void shutdownOutput() throws IOException {
            delegate.shutdownOutput();
        }

        @Override
        public boolean isInputShutdown() {
            return delegate.isInputShutdown();
        }

        @Override
        public boolean isOutputShutdown() {
            return delegate.isOutputShutdown();
        }

        @Override
        public void setSoTimeout(int timeout) throws SocketException {
            delegate.setSoTimeout(timeout);
        }

        @Override
        public int getSoTimeout() throws SocketException {
            return delegate.getSoTimeout();
        }

        @Override
        public void setTcpNoDelay(boolean value) throws SocketException {
            delegate.setTcpNoDelay(value);
        }

        @Override
        public boolean getTcpNoDelay() throws SocketException {
            return delegate.getTcpNoDelay();
        }

        @Override
        public void setKeepAlive(boolean value) throws SocketException {
            delegate.setKeepAlive(value);
        }

        @Override
        public boolean getKeepAlive() throws SocketException {
            return delegate.getKeepAlive();
        }

        @Override
        public void setReuseAddress(boolean value) throws SocketException {
            delegate.setReuseAddress(value);
        }

        @Override
        public boolean getReuseAddress() throws SocketException {
            return delegate.getReuseAddress();
        }

        @Override
        public void setSoLinger(boolean value, int linger) throws SocketException {
            delegate.setSoLinger(value, linger);
        }

        @Override
        public int getSoLinger() throws SocketException {
            return delegate.getSoLinger();
        }

        @Override
        public void setReceiveBufferSize(int value) throws SocketException {
            delegate.setReceiveBufferSize(value);
        }

        @Override
        public int getReceiveBufferSize() throws SocketException {
            return delegate.getReceiveBufferSize();
        }

        @Override
        public void setSendBufferSize(int value) throws SocketException {
            delegate.setSendBufferSize(value);
        }

        @Override
        public int getSendBufferSize() throws SocketException {
            return delegate.getSendBufferSize();
        }

        @Override
        public void setTrafficClass(int value) throws SocketException {
            delegate.setTrafficClass(value);
        }

        @Override
        public int getTrafficClass() throws SocketException {
            return delegate.getTrafficClass();
        }

        @Override
        public void setOOBInline(boolean value) throws SocketException {
            delegate.setOOBInline(value);
        }

        @Override
        public boolean getOOBInline() throws SocketException {
            return delegate.getOOBInline();
        }

        @Override
        public void sendUrgentData(int value) throws IOException {
            delegate.sendUrgentData(value);
        }

        @Override
        public void setPerformancePreferences(int connectionTime, int latency, int bandwidth) {
            delegate.setPerformancePreferences(connectionTime, latency, bandwidth);
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public String[] getEnabledCipherSuites() {
            return delegate.getEnabledCipherSuites();
        }

        @Override
        public void setEnabledCipherSuites(String[] suites) {
            delegate.setEnabledCipherSuites(suites);
        }

        @Override
        public String[] getSupportedProtocols() {
            return delegate.getSupportedProtocols();
        }

        @Override
        public String[] getEnabledProtocols() {
            return delegate.getEnabledProtocols();
        }

        @Override
        public void setEnabledProtocols(String[] protocols) {
            delegate.setEnabledProtocols(protocols);
        }

        @Override
        public SSLSession getSession() {
            return delegate.getSession();
        }

        @Override
        public SSLSession getHandshakeSession() {
            return delegate.getHandshakeSession();
        }

        @Override
        public synchronized void addHandshakeCompletedListener(
                HandshakeCompletedListener listener) {
            if (listener == null) throw new IllegalArgumentException("listener");
            listeners.add(listener);
            if (connected) delegate.addHandshakeCompletedListener(listener);
        }

        @Override
        public synchronized void removeHandshakeCompletedListener(
                HandshakeCompletedListener listener) {
            if (!listeners.remove(listener)) throw new IllegalArgumentException("listener");
            if (connected) delegate.removeHandshakeCompletedListener(listener);
        }

        @Override
        public void startHandshake() throws IOException {
            delegate.startHandshake();
        }

        @Override
        public void setUseClientMode(boolean value) {
            delegate.setUseClientMode(value);
        }

        @Override
        public boolean getUseClientMode() {
            return delegate.getUseClientMode();
        }

        @Override
        public void setNeedClientAuth(boolean value) {
            delegate.setNeedClientAuth(value);
        }

        @Override
        public boolean getNeedClientAuth() {
            return delegate.getNeedClientAuth();
        }

        @Override
        public void setWantClientAuth(boolean value) {
            delegate.setWantClientAuth(value);
        }

        @Override
        public boolean getWantClientAuth() {
            return delegate.getWantClientAuth();
        }

        @Override
        public void setEnableSessionCreation(boolean value) {
            delegate.setEnableSessionCreation(value);
        }

        @Override
        public boolean getEnableSessionCreation() {
            return delegate.getEnableSessionCreation();
        }

        @Override
        public SSLParameters getSSLParameters() {
            return delegate.getSSLParameters();
        }

        @Override
        public void setSSLParameters(SSLParameters value) {
            delegate.setSSLParameters(value);
        }
    }
}
