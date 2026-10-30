package app.bitchord.soulseek;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Android lifecycle contracts with in-memory network/asset fixtures, no public traffic. */
public final class DirectClientTest {
    static int assertions;

    interface Action {
        void run() throws Exception;
    }

    static void check(boolean okay, String message) {
        assertions++;
        if (!okay) throw new AssertionError(message);
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

    static final class FakeContext extends Context {
        final ConnectivityManager manager = new ConnectivityManager();
        final byte[] asset;

        FakeContext(String text) {
            asset = text == null ? null : text.getBytes(StandardCharsets.UTF_8);
        }

        public Object getSystemService(String name) {
            return manager;
        }

        public AssetManager getAssets() {
            return new AssetManager() {
                public InputStream open(String name) throws IOException {
                    check(
                            name.equals("soulseek-native/stun-servers.txt"),
                            "pinned bundled asset path");
                    if (asset == null) throw new FileNotFoundException();
                    return new ByteArrayInputStream(asset);
                }
            };
        }
    }

    public static void main(String[] args) throws Exception {
        FakeContext context =
                new FakeContext(
                        "stun:stun.example-one.net:3478\n"
                                + "stun:stun.example-two.net:3478\n"
                                + "stun:stun.example-three.net:3478\n");
        DirectClient client = new DirectClient(context);
        client.start(1000);
        check(DirectNative.sources.length == 3, "at most three STUN sources selected");
        boolean bundled = false;
        for (String source : DirectNative.sources) if (source.contains("example-")) bundled = true;
        check(bundled, "bundled public pool participates in startup");
        check(
                client.endpoint(1000).equals("fixture-descriptor"),
                "current-network descriptor available");
        DirectClient.Connection connection = client.connect("fixture-descriptor", 1000);
        FutureTask<Boolean> blocked =
                new FutureTask<Boolean>(
                        () -> {
                            try {
                                connection.input.read();
                                return false;
                            } catch (IOException expected) {
                                return true;
                            }
                        });
        new Thread(blocked).start();
        check(DirectNative.reading.await(1, TimeUnit.SECONDS), "read pending");
        int before = DirectNative.stopped;
        context.manager.switchTo(new Network(2));
        check(blocked.get(1, TimeUnit.SECONDS), "network handover cancels pending stream");
        check(
                DirectNative.stopped == before + 1 && context.manager.unregisters == 1,
                "handover closes node and unregisters callback");
        rejects(() -> client.endpoint(1000));
        rejects(() -> client.start(1000));
        client.close();
        check(DirectNative.stopped == before + 1, "close idempotent");
        for (String asset : new String[] {null, new String(new char[65537]).replace('\0', 'x')}) {
            FakeContext fallback = new FakeContext(asset);
            DirectClient next = new DirectClient(fallback);
            next.start(1000);
            check(
                    new HashSet<String>(Arrays.asList(DirectNative.sources))
                            .equals(new HashSet<String>(StunServers.defaults())),
                    "missing/oversized asset uses explicit defaults");
            next.close();
        }
        FakeContext during = new FakeContext(null);
        DirectNative.starting = () -> during.manager.active = new Network(9);
        DirectClient changed = new DirectClient(during);
        before = DirectNative.stopped;
        rejects(() -> changed.start(1000));
        check(
                DirectNative.stopped == before + 1,
                "network change during discovery reclaims unpublished native handle");
        changed.close();
        System.out.println(
                "Direct Android asset/network lifecycle fixtures passed: "
                        + assertions
                        + " assertions");
    }
}
