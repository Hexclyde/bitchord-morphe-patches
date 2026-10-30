package app.bitchord.soulseek;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Bundled Tor daemon, cookie-authenticated loopback control and onion-only streams. Own in
 * :bitchord_tor; Tor's native global state is never restarted in the music process. Nostr carries
 * the resulting onion endpoint and the authenticated transfer protocol runs on the returned
 * sockets. No public SOCKS proxy or Tor companion app is used.
 */
public final class TorRuntime implements Closeable {
    interface Engine {
        void start(String[] arguments) throws IOException;

        boolean isAlive();

        Throwable error();

        void awaitStopped(long timeoutMs) throws InterruptedException;
    }

    private static final InetAddress LOOPBACK;

    static {
        try {
            LOOPBACK = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (IOException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private final Engine engine;
    private final int startupTimeoutMs;
    private final Set<Socket> streams = Collections.synchronizedSet(new HashSet<Socket>());
    private volatile boolean closed;
    private boolean started;
    private volatile String phase = "stopped";
    private volatile String onion;
    private volatile int socksPort;
    private volatile ServerSocket listener;
    private volatile Control control;
    private volatile Socket openingControl;

    public TorRuntime() {
        this(new TorNative(), 15000);
    }

    TorRuntime(Engine engine, int startupTimeoutMs) {
        this.engine = engine;
        this.startupTimeoutMs = startupTimeoutMs;
    }

    /** Returns when local ports and the onion identity exist; bootstrap can continue. */
    public void start(File home) throws IOException {
        synchronized (this) {
            if (started || closed) throw new IOException("Tor runtime already used");
            started = true;
            phase = "starting";
        }
        try {
            File root = home.getCanonicalFile();
            privateDirectory(root);
            File data = new File(root, "data");
            privateDirectory(data);
            File portFile = new File(root, "control-port");
            File cookieFile = new File(root, "control-cookie");
            deleteStale(portFile);
            deleteStale(cookieFile);
            ServerSocket target = new ServerSocket(0, 16, LOOPBACK);
            synchronized (this) {
                if (closed) {
                    target.close();
                    throw new IOException("Tor startup cancelled");
                }
                listener = target;
                engine.start(
                        new String[] {
                            "tor",
                            "--RunAsDaemon",
                            "0",
                            "--defaults-torrc",
                            "/dev/null",
                            "-f",
                            "/dev/null",
                            "--DataDirectory",
                            data.getAbsolutePath(),
                            "--ClientOnly",
                            "1",
                            "--ExitRelay",
                            "0",
                            "--AvoidDiskWrites",
                            "1",
                            "--SocksPort",
                            "127.0.0.1:auto IsolateSOCKSAuth",
                            "--SafeSocks",
                            "1",
                            "--HTTPTunnelPort",
                            "0",
                            "--DNSPort",
                            "0",
                            "--TransPort",
                            "0",
                            "--ControlPort",
                            "127.0.0.1:auto",
                            "--ControlPortWriteToFile",
                            portFile.getAbsolutePath(),
                            "--CookieAuthentication",
                            "1",
                            "--CookieAuthFile",
                            cookieFile.getAbsolutePath(),
                            "--CookieAuthFileGroupReadable",
                            "0",
                            "--Log",
                            "err stderr"
                        });
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(startupTimeoutMs);
            while (!closed && System.nanoTime() < deadline) {
                if (!engine.isAlive())
                    throw new IOException("Tor native runtime stopped", engine.error());
                if (portFile.isFile() && cookieFile.isFile()) {
                    // Tor writes the port and cookie while opening its listeners. Only retry
                    // incomplete startup files, never unauthenticated/malformed control replies.
                    byte[] cookie = readFile(cookieFile, 32);
                    String portText =
                            new String(readFile(portFile, 128), StandardCharsets.US_ASCII).trim();
                    if (cookie.length == 32
                            && portText.matches("PORT=127\\.0\\.0\\.1:[0-9]{1,5}")) {
                        int port = parsePort(portText.substring(portText.lastIndexOf(':') + 1));
                        Socket socket = new Socket();
                        synchronized (this) {
                            if (closed) {
                                socket.close();
                                throw new IOException("Tor startup cancelled");
                            }
                            openingControl = socket;
                        }
                        socket.connect(new InetSocketAddress(LOOPBACK, port), remaining(deadline));
                        socket.setSoTimeout(3000);
                        Control c = new Control(socket, deadline);
                        try {
                            authenticate(c, cookie);
                        } finally {
                            java.util.Arrays.fill(cookie, (byte) 0);
                        }
                        c.command("TAKEOWNERSHIP");
                        int discoveredSocksPort = parseSocksListener(c.info("net/listeners/socks"));
                        List<String> reply =
                                c.command(
                                        "ADD_ONION NEW:ED25519-V3 Flags=DiscardPK"
                                                + " Port=80,127.0.0.1:"
                                                + target.getLocalPort());
                        String serviceId = null;
                        for (String line : reply)
                            if (line.startsWith("ServiceID=")) {
                                if (serviceId != null)
                                    throw new IOException("Duplicate Tor onion identity");
                                serviceId = line.substring(10);
                            }
                        String discoveredOnion =
                                validateOnion(serviceId == null ? "" : serviceId + ".onion");
                        synchronized (this) {
                            if (closed) throw new IOException("Tor startup cancelled");
                            c.startupComplete();
                            socksPort = discoveredSocksPort;
                            onion = discoveredOnion;
                            control = c;
                            openingControl = null;
                            phase = "bootstrapping";
                        }
                        return;
                    }
                }
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Tor startup interrupted", e);
                }
            }
            throw new IOException(closed ? "Tor startup cancelled" : "Tor local startup timed out");
        } catch (IOException e) {
            close();
            throw e;
        } catch (RuntimeException e) {
            close();
            throw new IOException("Tor local startup failed", e);
        }
    }

    public String status() {
        if (closed) return "stopped";
        Control c = control;
        if (c != null) {
            try {
                phase = c.info("status/bootstrap-phase");
            } catch (IOException e) {
                // A partial/timeout response cannot be reused safely. Ownership closes
                // the daemon when this authenticated control connection disappears.
                control = null;
                socksPort = 0;
                onion = null;
                closeQuietly(c);
                phase = engine.isAlive() ? "control unavailable" : "stopped";
            }
        }
        return closed ? "stopped" : phase;
    }

    public String onionAddress() {
        return closed ? null : onion;
    }

    public Socket connect(String endpoint, int timeoutMs) throws IOException {
        if (endpoint == null || endpoint.length() > 80)
            throw new IOException("Invalid Tor endpoint");
        int colon = endpoint.indexOf(':');
        String host = colon < 0 ? endpoint : endpoint.substring(0, colon);
        int port = colon < 0 ? 80 : parsePort(endpoint.substring(colon + 1));
        return connect(host, port, timeoutMs);
    }

    public Socket connect(String hostname, int virtualPort, int timeoutMs) throws IOException {
        String host = validateOnion(hostname);
        checkTimeout(timeoutMs);
        if (virtualPort < 1 || virtualPort > 65535) throw new IOException("Invalid Tor port");
        int proxy = socksPort;
        if (closed || proxy == 0 || onion == null) throw new IOException("Tor runtime unavailable");
        Socket socket = new Socket();
        track(socket);
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            socket.connect(new InetSocketAddress(LOOPBACK, proxy), remaining(deadline));
            socksConnect(socket, host, virtualPort, deadline);
            socket.setSoTimeout(0);
            if (closed) throw new IOException("Tor connection cancelled");
            return socket;
        } catch (IOException e) {
            streams.remove(socket);
            closeQuietly(socket);
            throw e;
        }
    }

    public Socket accept(int timeoutMs) throws IOException {
        checkTimeout(timeoutMs);
        ServerSocket server = listener;
        if (closed || server == null || onion == null)
            throw new IOException("Tor runtime unavailable");
        // The service has one accept loop. Closing the runtime interrupts its accept.
        server.setSoTimeout(timeoutMs);
        Socket socket = server.accept();
        try {
            track(socket);
            return socket;
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        phase = "stopped";
        closeQuietly(listener);
        closeQuietly(openingControl);
        synchronized (streams) {
            for (Socket s : streams) closeQuietly(s);
            streams.clear();
        }
        Control c = control;
        if (c != null) {
            try {
                c.command("SIGNAL SHUTDOWN");
            } catch (IOException ignored) {
            }
            closeQuietly(c);
        }
        try {
            engine.awaitStopped(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // The owning isolated Android Service terminates only its own process if
        // Tor cannot stop; never System.exit()/killProcess() in the music process.
    }

    private void track(Socket socket) throws IOException {
        synchronized (streams) {
            for (Iterator<Socket> i = streams.iterator(); i.hasNext(); )
                if (i.next().isClosed()) i.remove();
            if (closed || streams.size() >= 64)
                throw new IOException("Tor stream capacity unavailable");
            streams.add(socket);
        }
    }

    private static void privateDirectory(File dir) throws IOException {
        if ((!dir.isDirectory() && !dir.mkdirs()) || !dir.isDirectory())
            throw new IOException("Cannot create Tor private directory");
        if (!dir.setReadable(false, false)
                || !dir.setWritable(false, false)
                || !dir.setExecutable(false, false)
                || !dir.setReadable(true, true)
                || !dir.setWritable(true, true)
                || !dir.setExecutable(true, true))
            throw new IOException("Cannot secure Tor private directory");
    }

    private static void deleteStale(File file) throws IOException {
        if (file.exists() && !file.delete())
            throw new IOException("Cannot remove stale Tor control file");
    }

    private static byte[] readFile(File file, int max) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] data = new byte[max];
            int count = 0, n;
            while (count < max && (n = in.read(data, count, max - count)) != -1) count += n;
            if (in.read() != -1) throw new IOException("Tor control file too large");
            return java.util.Arrays.copyOf(data, count);
        }
    }

    static int parseSocksListener(String value) throws IOException {
        if (value == null || !value.matches("\"127\\.0\\.0\\.1:[0-9]{1,5}\""))
            throw new IOException("Unexpected Tor SOCKS listener");
        return parsePort(value.substring(value.lastIndexOf(':') + 1, value.length() - 1));
    }

    static String validateOnion(String hostname) throws IOException {
        if (hostname == null || !hostname.matches("[a-z2-7]{56}\\.onion"))
            throw new IOException("A v3 onion hostname is required");
        // Enforce the encoded v3 version byte; Tor validates the address checksum.
        String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
        int last = alphabet.indexOf(hostname.charAt(55));
        int previous = alphabet.indexOf(hostname.charAt(54));
        if (((previous & 7) << 5 | last) != 3) throw new IOException("Invalid onion version");
        return hostname;
    }

    private static int parsePort(String value) throws IOException {
        if (!value.matches("[0-9]{1,5}")) throw new IOException("Invalid Tor port");
        try {
            int port = Integer.parseInt(value);
            if (port > 0 && port <= 65535) return port;
        } catch (NumberFormatException ignored) {
        }
        throw new IOException("Invalid Tor port");
    }

    private static void checkTimeout(int timeoutMs) throws IOException {
        if (timeoutMs < 1 || timeoutMs > 120000)
            throw new IOException("Tor timeout outside supported bounds");
    }

    private static int remaining(long deadline) throws SocketTimeoutException {
        long ms = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (ms <= 0) throw new SocketTimeoutException("Tor connection timed out");
        return (int) Math.min(ms, Integer.MAX_VALUE);
    }

    static void socksConnect(Socket socket, String host, int port, long deadline)
            throws IOException {
        host = validateOnion(host);
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        out.write(new byte[] {5, 1, 2});
        out.flush();
        byte[] response = readExactly(socket, in, 2, deadline);
        if (response[0] != 5 || response[1] != 2)
            throw new IOException("Tor SOCKS authentication unavailable");
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        byte[] username = hex(random).getBytes(StandardCharsets.US_ASCII);
        new SecureRandom().nextBytes(random);
        byte[] password = hex(random).getBytes(StandardCharsets.US_ASCII);
        out.write(1);
        out.write(username.length);
        out.write(username);
        out.write(password.length);
        out.write(password);
        out.flush();
        response = readExactly(socket, in, 2, deadline);
        if (response[0] != 1 || response[1] != 0)
            throw new IOException("Tor SOCKS authentication failed");
        byte[] domain = host.getBytes(StandardCharsets.US_ASCII);
        out.write(new byte[] {5, 1, 0, 3, (byte) domain.length});
        out.write(domain);
        out.write((port >>> 8) & 255);
        out.write(port & 255);
        out.flush();
        response = readExactly(socket, in, 4, deadline);
        if (response[0] != 5 || response[1] != 0 || response[2] != 0)
            throw new IOException("Tor onion connection failed");
        int type = response[3] & 255;
        int count;
        if (type == 1) count = 4;
        else if (type == 4) count = 16;
        else if (type == 3) {
            count = readExactly(socket, in, 1, deadline)[0] & 255;
            if (count == 0) throw new IOException("Malformed Tor SOCKS address");
        } else throw new IOException("Malformed Tor SOCKS address type");
        readExactly(socket, in, count + 2, deadline);
    }

    private static byte[] readExactly(Socket socket, InputStream in, int count, long deadline)
            throws IOException {
        byte[] data = new byte[count];
        int done = 0;
        while (done < count) {
            socket.setSoTimeout(remaining(deadline));
            int n = in.read(data, done, count - done);
            if (n < 0) throw new IOException("Truncated Tor SOCKS response");
            done += n;
        }
        return data;
    }

    static void authenticate(Control control, byte[] cookie) throws IOException {
        if (cookie.length != 32) throw new IOException("Invalid Tor control cookie");
        byte[] clientNonce = new byte[32];
        new SecureRandom().nextBytes(clientNonce);
        List<String> challenge = control.command("AUTHCHALLENGE SAFECOOKIE " + hex(clientNonce));
        if (challenge.size() != 1
                || !challenge
                        .get(0)
                        .matches(
                                "AUTHCHALLENGE SERVERHASH=[0-9A-Fa-f]{64}"
                                        + " SERVERNONCE=[0-9A-Fa-f]{64}"))
            throw new IOException("Malformed Tor safe-cookie challenge");
        String[] fields = challenge.get(0).split(" ");
        byte[] serverHash = unhex(fields[1].substring(11));
        byte[] serverNonce = unhex(fields[2].substring(12));
        byte[] message = new byte[96];
        System.arraycopy(cookie, 0, message, 0, 32);
        System.arraycopy(clientNonce, 0, message, 32, 32);
        System.arraycopy(serverNonce, 0, message, 64, 32);
        try {
            byte[] expected =
                    cookieHash("Tor safe cookie authentication server-to-controller hash", message);
            if (!MessageDigest.isEqual(serverHash, expected))
                throw new IOException("Tor control server authentication failed");
            control.command(
                    "AUTHENTICATE "
                            + hex(
                                    cookieHash(
                                            "Tor safe cookie authentication controller-to-server"
                                                    + " hash",
                                            message)));
        } finally {
            java.util.Arrays.fill(message, (byte) 0);
        }
    }

    static byte[] cookieHash(String label, byte[] message) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(label.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IOException("Tor control authentication unavailable", e);
        }
    }

    static byte[] unhex(String text) throws IOException {
        if (!text.matches("[0-9A-Fa-f]{64}"))
            throw new IOException("Invalid Tor authentication hex");
        byte[] data = new byte[text.length() / 2];
        for (int i = 0; i < data.length; i++)
            data[i] =
                    (byte)
                            ((Character.digit(text.charAt(i * 2), 16) << 4)
                                    | Character.digit(text.charAt(i * 2 + 1), 16));
        return data;
    }

    static String hex(byte[] data) {
        StringBuilder result = new StringBuilder(data.length * 2);
        for (byte b : data) {
            int v = b & 255;
            result.append("0123456789abcdef".charAt(v >>> 4));
            result.append("0123456789abcdef".charAt(v & 15));
        }
        return result.toString();
    }

    private static void closeQuietly(Closeable thing) {
        if (thing != null)
            try {
                thing.close();
            } catch (IOException ignored) {
            }
    }

    /** Synchronous, bounded Tor control parser; never subscribes to asynchronous events. */
    static final class Control implements Closeable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private volatile long startupDeadline = Long.MAX_VALUE;

        Control(Socket socket, long startupDeadline) throws IOException {
            this(socket);
            this.startupDeadline = startupDeadline;
        }

        void startupComplete() {
            startupDeadline = Long.MAX_VALUE;
        }

        Control(Socket socket) throws IOException {
            this.socket = socket;
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }

        synchronized String info(String key) throws IOException {
            if (!key.matches("[a-z/-]+")) throw new IOException("Invalid Tor info key");
            String result = null;
            for (String line : command("GETINFO " + key))
                if (line.startsWith(key + "=")) {
                    if (result != null) throw new IOException("Duplicate Tor info key");
                    result = line.substring(key.length() + 1);
                }
            if (result == null) throw new IOException("Missing Tor info key");
            return result;
        }

        synchronized List<String> command(String command) throws IOException {
            if (command.length() > 2048 || command.indexOf('\r') >= 0 || command.indexOf('\n') >= 0)
                throw new IOException("Invalid Tor control command");
            out.write((command + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            long deadline =
                    Math.min(startupDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(3));
            List<String> values = new ArrayList<String>();
            int total = 0;
            for (int lines = 0; lines < 256; lines++) {
                String line = line(deadline);
                total += line.length();
                if (total > 65536 || line.length() < 4)
                    throw new IOException("Malformed Tor control response");
                if (!line.startsWith("250")) throw new IOException("Tor control command rejected");
                char separator = line.charAt(3);
                if (separator != '-' && separator != ' ' && separator != '+')
                    throw new IOException("Malformed Tor control separator");
                values.add(line.substring(4));
                if (separator == ' ') return values;
                if (separator == '+') {
                    boolean terminated = false;
                    while (++lines < 256) {
                        String item = line(deadline);
                        total += item.length();
                        if (total > 65536) throw new IOException("Tor control response too large");
                        if (item.equals(".")) {
                            terminated = true;
                            break;
                        }
                        values.add(item.startsWith("..") ? item.substring(1) : item);
                    }
                    if (!terminated) throw new IOException("Tor control data not terminated");
                }
            }
            throw new IOException("Tor control response has too many lines");
        }

        private String line(long deadline) throws IOException {
            StringBuilder text = new StringBuilder();
            boolean cr = false;
            while (text.length() <= 4096) {
                socket.setSoTimeout(remaining(deadline));
                int b = in.read();
                if (b < 0) throw new IOException("Truncated Tor control response");
                if (cr) {
                    if (b != '\n') throw new IOException("Malformed Tor control newline");
                    return text.toString();
                }
                if (b == '\r') cr = true;
                else {
                    if (b < 32 || b > 126) throw new IOException("Non-ASCII Tor control response");
                    text.append((char) b);
                }
            }
            throw new IOException("Tor control line too long");
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
