package app.bitchord.soulseek;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** No public DNS, API traffic or account keys are used by these transport fixtures. */
public final class DebridTlsSocketsTest {
    private static int checks;
    private static final InetAddress PUBLIC = address(8, 8, 8, 8);
    private static final InetAddress PRIVATE = address(10, 0, 0, 1);

    private static InetAddress address(int... values) {
        try {
            byte[] bytes = new byte[values.length];
            for (int i = 0; i < values.length; i++) bytes[i] = (byte) values[i];
            return InetAddress.getByAddress(bytes);
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void check(boolean value, String description) {
        checks++;
        if (!value) throw new AssertionError(description);
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void rejected(Action action, String description) throws Exception {
        try {
            action.run();
            throw new AssertionError(description);
        } catch (IOException expected) {
            checks++;
        }
    }

    private static final class Cancel implements DebridModels.Cancellation {
        volatile boolean stopped;
        final Set<Closeable> resources =
                Collections.newSetFromMap(new ConcurrentHashMap<Closeable, Boolean>());

        public void check() throws IOException {
            if (stopped || Thread.currentThread().isInterrupted())
                throw new IOException("cancelled");
        }

        public void bind(Closeable resource) throws IOException {
            check();
            resources.add(resource);
            if (stopped) resource.close();
            check();
        }

        public void clear(Closeable resource) {
            resources.remove(resource);
        }

        void stop() throws IOException {
            stopped = true;
            for (Closeable resource : resources) resource.close();
        }
    }

    private static final class Plain extends Socket {
        final InetAddress remote;
        volatile boolean closed;

        Plain(InetAddress remote) {
            this.remote = remote;
        }

        public InetAddress getInetAddress() {
            return remote;
        }

        public int getPort() {
            return 443;
        }

        public boolean isConnected() {
            return true;
        }

        public synchronized void close() {
            closed = true;
        }

        public boolean isClosed() {
            return closed;
        }
    }

    private static final class Secured extends SSLSocket {
        final Plain plain;
        final SSLSocket settings;

        Secured(Plain plain) throws IOException {
            this.plain = plain;
            settings = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
        }

        public InetAddress getInetAddress() {
            return plain.remote;
        }

        public int getPort() {
            return 443;
        }

        public boolean isConnected() {
            return !plain.closed;
        }

        public boolean isBound() {
            return true;
        }

        public synchronized void close() throws IOException {
            plain.close();
            settings.close();
        }

        public boolean isClosed() {
            return plain.closed;
        }

        public InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(new byte[0]);
        }

        public OutputStream getOutputStream() {
            return new java.io.ByteArrayOutputStream();
        }

        public String[] getSupportedCipherSuites() {
            return settings.getSupportedCipherSuites();
        }

        public String[] getEnabledCipherSuites() {
            return settings.getEnabledCipherSuites();
        }

        public void setEnabledCipherSuites(String[] value) {
            settings.setEnabledCipherSuites(value);
        }

        public String[] getSupportedProtocols() {
            return settings.getSupportedProtocols();
        }

        public String[] getEnabledProtocols() {
            return settings.getEnabledProtocols();
        }

        public void setEnabledProtocols(String[] value) {
            settings.setEnabledProtocols(value);
        }

        public SSLSession getSession() {
            return settings.getSession();
        }

        public void addHandshakeCompletedListener(HandshakeCompletedListener value) {
            settings.addHandshakeCompletedListener(value);
        }

        public void removeHandshakeCompletedListener(HandshakeCompletedListener value) {
            settings.removeHandshakeCompletedListener(value);
        }

        public void startHandshake() {}

        public void setUseClientMode(boolean value) {
            settings.setUseClientMode(value);
        }

        public boolean getUseClientMode() {
            return settings.getUseClientMode();
        }

        public void setNeedClientAuth(boolean value) {
            settings.setNeedClientAuth(value);
        }

        public boolean getNeedClientAuth() {
            return settings.getNeedClientAuth();
        }

        public void setWantClientAuth(boolean value) {
            settings.setWantClientAuth(value);
        }

        public boolean getWantClientAuth() {
            return settings.getWantClientAuth();
        }

        public void setEnableSessionCreation(boolean value) {
            settings.setEnableSessionCreation(value);
        }

        public boolean getEnableSessionCreation() {
            return settings.getEnableSessionCreation();
        }

        public SSLParameters getSSLParameters() {
            return settings.getSSLParameters();
        }

        public void setSSLParameters(SSLParameters value) {
            settings.setSSLParameters(value);
        }
    }

    private static final class TLS extends SSLSocketFactory {
        final AtomicInteger wraps = new AtomicInteger();
        volatile String hostname;
        final SSLSocketFactory defaults = (SSLSocketFactory) SSLSocketFactory.getDefault();

        public String[] getDefaultCipherSuites() {
            return defaults.getDefaultCipherSuites();
        }

        public String[] getSupportedCipherSuites() {
            return defaults.getSupportedCipherSuites();
        }

        public Socket createSocket() throws IOException {
            return defaults.createSocket();
        }

        public Socket createSocket(Socket socket, String host, int port, boolean autoClose)
                throws IOException {
            wraps.incrementAndGet();
            hostname = host;
            return new Secured((Plain) socket);
        }

        public Socket createSocket(String host, int port) {
            throw new AssertionError("unvalidated DNS path");
        }

        public Socket createSocket(String host, int port, InetAddress local, int localPort) {
            throw new AssertionError("unvalidated DNS path");
        }

        public Socket createSocket(InetAddress host, int port) {
            throw new AssertionError("unvalidated DNS path");
        }

        public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) {
            throw new AssertionError("unvalidated DNS path");
        }
    }

    public static void main(String[] arguments) throws Exception {
        Cancel cancellation = new Cancel();
        rejected(() -> DebridTlsSockets.url("http://example.test/file"), "HTTP must fail");
        rejected(() -> DebridTlsSockets.url("https://key@example.test/file"), "userinfo must fail");
        rejected(
                () -> DebridTlsSockets.url("https://example.test:444/file"),
                "alternate port must fail");
        rejected(
                () -> DebridTlsSockets.url("https://example.test/file#secret"),
                "fragment must fail");
        InetAddress[] approved =
                DebridTlsSockets.resolve(
                        "example.test", cancellation, 1000, host -> new InetAddress[] {PUBLIC});
        check(approved.length == 1 && approved[0].equals(PUBLIC), "public DNS result retained");
        rejected(
                () ->
                        DebridTlsSockets.resolve(
                                "example.test",
                                cancellation,
                                1000,
                                host -> new InetAddress[] {PUBLIC, PRIVATE}),
                "mixed DNS answer must fail entirely");
        rejected(
                () ->
                        DebridTlsSockets.resolve(
                                "example.test", cancellation, 1000, host -> new InetAddress[0]),
                "empty DNS result must fail");
        check(!DebridHttp.isPublic(address(127, 0, 0, 1)), "IPv4 loopback rejected");
        check(!DebridHttp.isPublic(address(100, 64, 0, 1)), "carrier-grade private range rejected");
        check(
                !DebridHttp.isPublic(
                        InetAddress.getByAddress(
                                new byte[] {
                                    0,
                                    0,
                                    0,
                                    0,
                                    0,
                                    0,
                                    0,
                                    0,
                                    0,
                                    0,
                                    (byte) 255,
                                    (byte) 255,
                                    127,
                                    0,
                                    0,
                                    1
                                })),
                "IPv4 mapped private IPv6 rejected");
        check(
                DebridHttp.isPublic(
                        InetAddress.getByAddress(
                                new byte[] {
                                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 255, (byte) 255, 8, 8, 8, 8
                                })),
                "IPv4 mapped public address retained");
        check(
                !DebridHttp.isPublic(
                        address(0x20, 1, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)),
                "IPv6 documentation prefix rejected");

        TLS tls = new TLS();
        AtomicReference<InetAddress> actualTarget = new AtomicReference<>();
        DebridTlsSockets.Connector connector =
                (address, port, local, timeout, cancel) -> {
                    actualTarget.set(address);
                    return new Plain(address);
                };
        DebridTlsSockets.Factory factory =
                new DebridTlsSockets.Factory(
                        "example.test", 443, approved, 1000, cancellation, tls, connector);
        try (Socket socket = factory.createSocket("example.test", 443)) {
            check(
                    socket.isConnected() && actualTarget.get().equals(PUBLIC),
                    "named overload uses approved numeric address");
            check(tls.hostname.equals("example.test"), "original TLS hostname retained");
        }
        try (Socket socket = factory.createSocket(PUBLIC, 443)) {
            check(socket.isConnected(), "numeric overload is validated");
        }
        try (Socket socket = factory.createSocket("example.test", 443, PRIVATE, 0)) {
            check(socket.isConnected(), "local bind overload retains validated remote");
        }
        try (Socket socket = factory.createSocket(PUBLIC, 443, PRIVATE, 0)) {
            check(socket.isConnected(), "numeric local bind overload retained");
        }
        rejected(() -> factory.createSocket("different.test", 443), "different TLS host rejected");
        rejected(() -> factory.createSocket(PUBLIC, 444), "different TLS port rejected");
        Plain privatePeer = new Plain(PRIVATE);
        int before = tls.wraps.get();
        rejected(
                () -> factory.createSocket(privatePeer, "example.test", 443, true),
                "actual private peer rejected");
        check(
                privatePeer.closed && tls.wraps.get() == before,
                "private peer closes before TLS sees it");
        Plain changedPublic = new Plain(address(9, 9, 9, 9));
        rejected(
                () -> factory.createSocket(changedPublic, "example.test", 443, true),
                "unapproved public peer rejected");
        check(changedPublic.closed, "unapproved public socket closed");

        try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
            socket.setSoTimeout(222);
            socket.setEnabledProtocols(new String[] {"TLSv1.2"});
            InetAddress rebound = InetAddress.getByAddress("example.test", PRIVATE.getAddress());
            socket.connect(new InetSocketAddress(rebound, 443), 1000);
            check(
                    actualTarget.get().equals(PUBLIC),
                    "noarg createSocket cannot rebind DNS to private IP");
            check(
                    socket.getEnabledProtocols().length == 1
                            && socket.getEnabledProtocols()[0].equals("TLSv1.2"),
                    "preconnect TLS settings preserved");
            check(socket.getSoTimeout() == 222, "preconnect timeout preserved");
            check(
                    socket.getSSLParameters().getServerNames() != null
                            && socket.getSSLParameters().getServerNames().size() == 1,
                    "noarg route retains SNI");
            check(
                    tls.hostname.equals("example.test"),
                    "noarg wrapper retains hostname verification target");
        }
        try (Socket socket = factory.createSocket()) {
            rejected(
                    () -> socket.connect(new InetSocketAddress(PRIVATE, 443)),
                    "noarg socket rejects unrelated private target");
        }
        HttpsURLConnection connection =
                DebridTlsSockets.open(
                        "https://example.test/audio",
                        cancellation,
                        1000,
                        host -> approved,
                        tls,
                        connector);
        check(
                connection.getHostnameVerifier() == HttpsURLConnection.getDefaultHostnameVerifier(),
                "platform hostname verifier remains unchanged");
        check(
                connection.getSSLSocketFactory() instanceof DebridTlsSockets.Factory,
                "only socket routing is wrapped");
        connection.disconnect();
        check(
                cancellation.resources.isEmpty(),
                "successful fixture operations release cancellation leases");

        CountDownLatch release = new CountDownLatch(1), started = new CountDownLatch(1);
        DebridTlsSockets.Resolver blocked =
                host -> {
                    started.countDown();
                    while (release.getCount() != 0)
                        try {
                            release.await();
                        } catch (InterruptedException ignored) {
                        }
                    return approved;
                };
        long begin = System.nanoTime();
        rejected(
                () -> DebridTlsSockets.resolve("example.test", new Cancel(), 120, blocked),
                "DNS deadline stops a blocked resolver");
        check(
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 1500,
                "DNS deadline is bounded independently of resolver completion");
        release.countDown();
        CountDownLatch release2 = new CountDownLatch(1), started2 = new CountDownLatch(1);
        Cancel stopped = new Cancel();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter =
                new Thread(
                        () -> {
                            try {
                                DebridTlsSockets.resolve(
                                        "example.test",
                                        stopped,
                                        20000,
                                        host -> {
                                            started2.countDown();
                                            while (release2.getCount() != 0)
                                                try {
                                                    release2.await();
                                                } catch (InterruptedException ignored) {
                                                }
                                            return approved;
                                        });
                            } catch (Throwable error) {
                                failure.set(error);
                            }
                        });
        waiter.start();
        check(started2.await(2, TimeUnit.SECONDS), "cancellation resolver fixture started");
        stopped.stop();
        waiter.join(1000);
        release2.countDown();
        check(
                !waiter.isAlive() && failure.get() instanceof IOException,
                "cancelling DNS stops its caller promptly");
        check(stopped.resources.isEmpty(), "cancelled DNS releases its lease");
        System.out.println("Debrid TLS routing: " + checks + " assertions passed");
    }
}
