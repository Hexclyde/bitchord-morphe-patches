package app.bitchord.soulseek;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Local fixtures only. Does not load libtor, bootstrap Tor, or contact public peers. */
public final class TorRuntimeTest {
    static final String ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaad";
    static final String HOST = ID + ".onion";
    static int assertions;

    interface Action {
        void run() throws Exception;
    }

    static void check(boolean test, String message) {
        assertions++;
        if (!test) throw new AssertionError(message);
    }

    static void rejects(Action action) throws Exception {
        assertions++;
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Expected IOException");
    }

    static ServerSocket server() throws IOException {
        return new ServerSocket(0, 8, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
    }

    static Socket local(ServerSocket listener) throws IOException {
        return new Socket(
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), listener.getLocalPort());
    }

    static byte[] read(InputStream in, int length) throws IOException {
        byte[] result = new byte[length];
        int at = 0;
        while (at < length) {
            int n = in.read(result, at, length - at);
            if (n < 0) throw new EOFException();
            at += n;
        }
        return result;
    }

    static void send(OutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    static Thread daemon(final Action action) {
        Thread thread =
                new Thread(
                        new Runnable() {
                            public void run() {
                                try {
                                    action.run();
                                } catch (Exception ignored) {
                                }
                            }
                        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    static final class FakeEngine implements TorRuntime.Engine, Closeable {
        final ServerSocket controlListener = server();
        final ServerSocket socksListener = server();
        volatile boolean alive;
        volatile Throwable failure;
        volatile int targetPort;
        volatile Socket control;
        volatile Socket socks;
        volatile boolean stallSocks;
        volatile CountDownLatch challengeSeen;
        volatile CountDownLatch releaseChallenge;

        FakeEngine() throws IOException {}

        public void start(String[] args) throws IOException {
            check(
                    Arrays.asList(args).contains("127.0.0.1:auto IsolateSOCKSAuth"),
                    "SOCKS is isolated and bound to loopback");
            check(
                    Arrays.asList(args).contains("--CookieAuthentication"),
                    "cookie authentication configured");
            List<String> values = Arrays.asList(args);
            byte[] cookie = new byte[32];
            Arrays.fill(cookie, (byte) 7);
            Files.write(
                    new File(values.get(values.indexOf("--CookieAuthFile") + 1)).toPath(), cookie);
            Files.write(
                    new File(values.get(values.indexOf("--ControlPortWriteToFile") + 1)).toPath(),
                    ("PORT=127.0.0.1:" + controlListener.getLocalPort() + "\n")
                            .getBytes(StandardCharsets.US_ASCII));
            alive = true;
            daemon(
                    new Action() {
                        public void run() throws Exception {
                            try {
                                control = controlListener.accept();
                                BufferedReader in =
                                        new BufferedReader(
                                                new InputStreamReader(
                                                        control.getInputStream(),
                                                        StandardCharsets.US_ASCII));
                                OutputStream out = control.getOutputStream();
                                String command;
                                String expectedAuthentication = null;
                                while ((command = in.readLine()) != null) {
                                    if (command.startsWith("AUTHCHALLENGE SAFECOOKIE ")) {
                                        if (challengeSeen != null) challengeSeen.countDown();
                                        if (releaseChallenge != null)
                                            releaseChallenge.await(2, TimeUnit.SECONDS);
                                        byte[] message = new byte[96];
                                        Arrays.fill(message, 0, 32, (byte) 7);
                                        System.arraycopy(
                                                TorRuntime.unhex(command.substring(25)),
                                                0,
                                                message,
                                                32,
                                                32);
                                        Arrays.fill(message, 64, 96, (byte) 8);
                                        expectedAuthentication =
                                                "AUTHENTICATE "
                                                        + TorRuntime.hex(
                                                                TorRuntime.cookieHash(
                                                                        "Tor safe cookie"
                                                                            + " authentication"
                                                                            + " controller-to-server"
                                                                            + " hash",
                                                                        message));
                                        send(
                                                out,
                                                "250 AUTHCHALLENGE SERVERHASH="
                                                        + TorRuntime.hex(
                                                                TorRuntime.cookieHash(
                                                                        "Tor safe cookie"
                                                                            + " authentication"
                                                                            + " server-to-controller"
                                                                            + " hash",
                                                                        message))
                                                        + " SERVERNONCE="
                                                        + repeat("08", 32)
                                                        + "\r\n");
                                    } else if (command.startsWith("AUTHENTICATE ")) {
                                        check(
                                                command.equals(expectedAuthentication),
                                                "safe-cookie challenge authenticated");
                                        send(out, "250 OK\r\n");
                                    } else if (command.equals("TAKEOWNERSHIP"))
                                        send(out, "250 OK\r\n");
                                    else if (command.equals("GETINFO net/listeners/socks"))
                                        send(
                                                out,
                                                "250-net/listeners/socks=\"127.0.0.1:"
                                                        + socksListener.getLocalPort()
                                                        + "\"\r\n250 OK\r\n");
                                    else if (command.startsWith(
                                            "ADD_ONION NEW:ED25519-V3 Flags=DiscardPK"
                                                    + " Port=80,127.0.0.1:")) {
                                        targetPort =
                                                Integer.parseInt(
                                                        command.substring(
                                                                command.lastIndexOf(':') + 1));
                                        send(out, "250-ServiceID=" + ID + "\r\n250 OK\r\n");
                                    } else if (command.equals("GETINFO status/bootstrap-phase"))
                                        send(
                                                out,
                                                "250-status/bootstrap-phase=NOTICE BOOTSTRAP"
                                                    + " PROGRESS=100 TAG=done SUMMARY=\"Done\"\r\n"
                                                    + "250 OK\r\n");
                                    else if (command.equals("SIGNAL SHUTDOWN")) {
                                        send(out, "250 OK\r\n");
                                        break;
                                    } else throw new IOException("Unexpected command");
                                }
                            } finally {
                                alive = false;
                            }
                        }
                    });
            daemon(
                    new Action() {
                        public void run() throws Exception {
                            socks = socksListener.accept();
                            if (stallSocks) {
                                Thread.sleep(5000);
                                return;
                            }
                            socksFixture(socks, 1, false);
                        }
                    });
        }

        public boolean isAlive() {
            return alive;
        }

        public Throwable error() {
            return failure;
        }

        public void awaitStopped(long timeout) {}

        public void close() throws IOException {
            alive = false;
            controlListener.close();
            socksListener.close();
            if (control != null) control.close();
            if (socks != null) socks.close();
        }
    }

    static String repeat(String text, int count) {
        StringBuilder out = new StringBuilder();
        while (count-- > 0) out.append(text);
        return out.toString();
    }

    static void socksFixture(Socket client, int replyType, boolean evil) throws Exception {
        try (Socket socket = client) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            check(
                    Arrays.equals(read(in, 3), new byte[] {5, 1, 2}),
                    "SOCKS requires authentication");
            out.write(new byte[] {5, 2});
            out.flush();
            check(in.read() == 1, "RFC1929 version");
            int n = in.read();
            byte[] user = read(in, n);
            n = in.read();
            byte[] password = read(in, n);
            check(
                    user.length == 32 && password.length == 32 && !Arrays.equals(user, password),
                    "per-stream random credentials");
            out.write(new byte[] {1, 0});
            out.flush();
            check(
                    Arrays.equals(read(in, 4), new byte[] {5, 1, 0, 3}),
                    "SOCKS uses domain addressing without local DNS");
            n = in.read();
            String host = new String(read(in, n), StandardCharsets.US_ASCII);
            check(host.equals(HOST), "only onion address sent");
            check(Arrays.equals(read(in, 2), new byte[] {0, 80}), "onion virtual port");
            if (evil) {
                out.write(new byte[] {5, 0, 0, 9});
                out.flush();
                return;
            }
            out.write(new byte[] {5, 0, 0, (byte) replyType});
            if (replyType == 1) out.write(new byte[] {127, 0, 0, 1, 0, 80});
            else if (replyType == 4) out.write(new byte[18]);
            else if (replyType == 3) out.write(new byte[] {1, 'x', 0, 80});
            out.flush();
            int value = in.read();
            if (value != -1) {
                out.write(value);
                out.flush();
            }
        }
    }

    static void controlResponse(final String response, ActionConsumer consumer) throws Exception {
        try (ServerSocket listener = server();
                Socket client = local(listener);
                Socket peer = listener.accept()) {
            client.setSoTimeout(1000);
            daemon(
                    new Action() {
                        public void run() throws Exception {
                            new BufferedReader(
                                            new InputStreamReader(
                                                    peer.getInputStream(),
                                                    StandardCharsets.US_ASCII))
                                    .readLine();
                            send(peer.getOutputStream(), response);
                        }
                    });
            consumer.accept(new TorRuntime.Control(client));
        }
    }

    interface ActionConsumer {
        void accept(TorRuntime.Control control) throws Exception;
    }

    static void parserTests() throws Exception {
        controlResponse(
                "250-key=value\r\n250 OK\r\n",
                c -> check(c.info("key").equals("value"), "multiline info"));
        controlResponse(
                "250+data=\r\n..value\r\n.\r\n250 OK\r\n",
                c -> check(c.command("GETINFO data").contains(".value"), "dot unstuffing"));
        for (final String response :
                new String[] {
                    "515 Authentication failed\r\n",
                    "650 EVENT\r\n",
                    "250!broken\r\n",
                    "250 OK\n",
                    "250 " + repeat("x", 4097) + "\r\n",
                    repeat("250-more\r\n", 257),
                    "250-key=a\r\n250-key=b\r\n250 OK\r\n"
                }) {
            controlResponse(response, c -> rejects(() -> c.info("key")));
        }
        controlResponse("250 OK\r\n", c -> rejects(() -> c.command("GETINFO key\r\nSIGNAL HALT")));
        controlResponse(
                "250 AUTHCHALLENGE SERVERHASH="
                        + repeat("00", 32)
                        + " SERVERNONCE="
                        + repeat("08", 32)
                        + "\r\n",
                c -> rejects(() -> TorRuntime.authenticate(c, new byte[32])));
        controlResponse(
                "250 AUTHCHALLENGE SERVERHASH=no SERVERNONCE=no\r\n",
                c -> rejects(() -> TorRuntime.authenticate(c, new byte[32])));
        try (ServerSocket listener = server();
                Socket client = local(listener);
                Socket peer = listener.accept()) {
            TorRuntime.Control c =
                    new TorRuntime.Control(
                            client, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(80));
            long start = System.nanoTime();
            rejects(() -> c.command("GETINFO key"));
            check(
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 600,
                    "absolute startup control deadline enforced");
        }
        check(
                TorRuntime.parseSocksListener("\"127.0.0.1:9050\"") == 9050,
                "local listener accepted");
        for (final String value :
                new String[] {
                    "\"0.0.0.0:9050\"",
                    "\"127.0.0.1:0\"",
                    "\"127.0.0.1:65536\"",
                    "\"127.0.0.1:9050\" \"10.0.0.1:1\""
                }) rejects(() -> TorRuntime.parseSocksListener(value));
    }

    static void socksTests() throws Exception {
        for (final int type : new int[] {1, 3, 4, 9}) {
            try (ServerSocket listener = server();
                    Socket client = local(listener)) {
                Socket peer = listener.accept();
                Thread fixture = daemon(() -> socksFixture(peer, type, type == 9));
                Action run =
                        () ->
                                TorRuntime.socksConnect(
                                        client,
                                        HOST,
                                        80,
                                        System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
                if (type == 9) rejects(run);
                else {
                    run.run();
                    client.getOutputStream().write(42);
                    check(client.getInputStream().read() == 42, "stream remains duplex");
                }
                client.close();
                fixture.join(1000);
            }
        }
        for (final byte[] malformed :
                new byte[][] {
                    new byte[] {4, 2}, new byte[] {5, 0}, new byte[] {5, (byte) 255}, new byte[] {5}
                }) {
            try (ServerSocket listener = server();
                    Socket client = local(listener);
                    Socket peer = listener.accept()) {
                daemon(
                        () -> {
                            read(peer.getInputStream(), 3);
                            peer.getOutputStream().write(malformed);
                            peer.getOutputStream().flush();
                            peer.shutdownOutput();
                        });
                rejects(
                        () ->
                                TorRuntime.socksConnect(
                                        client,
                                        HOST,
                                        80,
                                        System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
            }
        }
        try (ServerSocket listener = server();
                Socket client = local(listener);
                Socket peer = listener.accept()) {
            long start = System.nanoTime();
            rejects(
                    () ->
                            TorRuntime.socksConnect(
                                    client,
                                    HOST,
                                    80,
                                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(80)));
            check(
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 600,
                    "total handshake deadline enforced");
        }
    }

    static void runtimeTests() throws Exception {
        File home = Files.createTempDirectory("tor-fixture").toFile();
        try (FakeEngine engine = new FakeEngine()) {
            TorRuntime runtime = new TorRuntime(engine, 1000);
            runtime.start(home);
            check(HOST.equals(runtime.onionAddress()), "ephemeral onion service exposed");
            check(
                    runtime.status().contains("PROGRESS=100"),
                    "bootstrap status from authenticated control");
            try (Socket outgoing = runtime.connect(HOST, 1000)) {
                outgoing.getOutputStream().write(23);
                check(outgoing.getInputStream().read() == 23, "onion outgoing stream");
            }
            try (Socket source =
                            new Socket(
                                    InetAddress.getByAddress(new byte[] {127, 0, 0, 1}),
                                    engine.targetPort);
                    Socket accepted = runtime.accept(1000)) {
                source.getOutputStream().write(9);
                check(accepted.getInputStream().read() == 9, "onion inbound stream accepted");
                runtime.close();
                check(accepted.isClosed(), "stop cancels accepted streams");
            }
            check(
                    runtime.onionAddress() == null && runtime.status().equals("stopped"),
                    "closed state");
            rejects(() -> runtime.connect(HOST, 1000));
            rejects(() -> runtime.start(home));
        }
        try (FakeEngine engine = new FakeEngine()) {
            engine.stallSocks = true;
            TorRuntime runtime = new TorRuntime(engine, 1000);
            runtime.start(home);
            final FutureTask<Boolean> task =
                    new FutureTask<Boolean>(
                            () -> {
                                try {
                                    runtime.connect(HOST, 5000);
                                    return false;
                                } catch (IOException expected) {
                                    return true;
                                }
                            });
            new Thread(task).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (engine.socks == null && System.nanoTime() < deadline) Thread.yield();
            runtime.close();
            check(task.get(1, TimeUnit.SECONDS), "close cancels pending SOCKS handshake");
        }
        for (boolean cancel : new boolean[] {false, true}) {
            try (FakeEngine engine = new FakeEngine()) {
                engine.challengeSeen = new CountDownLatch(1);
                engine.releaseChallenge = new CountDownLatch(1);
                TorRuntime starting = new TorRuntime(engine, 1000);
                FutureTask<Boolean> startup =
                        new FutureTask<Boolean>(
                                () -> {
                                    try {
                                        starting.start(home);
                                        return true;
                                    } catch (IOException failure) {
                                        return false;
                                    }
                                });
                new Thread(startup).start();
                check(
                        engine.challengeSeen.await(1, TimeUnit.SECONDS),
                        "control authentication pending");
                check(
                        starting.status().equals("starting"),
                        "status does not issue unauthenticated startup commands");
                if (cancel) starting.close();
                engine.releaseChallenge.countDown();
                check(
                        startup.get(1, TimeUnit.SECONDS) != cancel,
                        "startup publish/cancel race completes safely");
                starting.close();
            }
        }
        TorRuntime.Engine dead =
                new TorRuntime.Engine() {
                    public void start(String[] a) {}

                    public boolean isAlive() {
                        return false;
                    }

                    public Throwable error() {
                        return new IOException("fixture");
                    }

                    public void awaitStopped(long n) {}
                };
        TorRuntime runtime = new TorRuntime(dead, 100);
        rejects(() -> runtime.start(home));
        check(runtime.status().equals("stopped"), "failed native startup closes runtime");
    }

    public static void main(String[] args) throws Exception {
        check(TorRuntime.validateOnion(HOST).equals(HOST), "v3 name accepted");
        for (final String host :
                new String[] {
                    "example.com",
                    "127.0.0.1",
                    "localhost",
                    HOST + "/x",
                    HOST + "\r\n",
                    HOST.toUpperCase(),
                    repeat("a", 56) + ".onion",
                    "abcdefghijklmnop.onion",
                    null
                }) rejects(() -> TorRuntime.validateOnion(host));
        parserTests();
        socksTests();
        runtimeTests();
        System.out.println(
                "Tor local protocol/lifecycle fixtures passed: " + assertions + " assertions");
    }
}
