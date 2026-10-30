package app.bitchord.soulseek;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Network-free tests exercise real coordinator boundaries through its native adapter seam. */
public final class PeerDiscoveryTest {
    static int checks;

    interface Action {
        void run() throws Exception;
    }

    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static void denied(Action action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            checks++;
        }
    }

    static void until(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(2);
        check(condition.getAsBoolean(), message);
    }

    static final class Block {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);

        void await() throws Exception {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("fixture timed out");
        }

        void reached() throws Exception {
            check(entered.await(3, TimeUnit.SECONDS), "fixture reached blocked boundary");
        }
    }

    static final class Time implements PeerDiscovery.Clock {
        volatile long now = 1000, elapsed;

        public long wall() {
            return now;
        }

        public long elapsed() {
            return elapsed;
        }
    }

    static final class Gate implements PeerDiscovery.Gate {
        volatile boolean on = true;

        public void require() throws IOException {
            if (!on) throw new IOException("parent disabled");
        }

        public boolean enabled() {
            return on;
        }
    }

    static final class Identity implements PeerDiscovery.Identity {
        volatile Block block;
        final AtomicInteger finished = new AtomicInteger();

        public NostrIdentityStore.Settings settings() throws Exception {
            Block waiting = block;
            block = null;
            if (waiting != null) waiting.await();
            finished.incrementAndGet();
            return new NostrIdentityStore.Settings(
                    "test-secret", Arrays.asList("wss://relay.example"), Collections.emptyList());
        }

        public String publicKey(String ignored) {
            return PeerCacheExchangeTest.key(900);
        }
    }

    static final class Disk implements PeerDiscovery.CacheFile {
        JSONArray saved = new JSONArray();
        int writes, clears;

        public synchronized JSONArray read() throws Exception {
            return new JSONArray(saved.toString());
        }

        public synchronized void write(JSONArray records) throws Exception {
            saved = new JSONArray(records.toString());
            writes++;
        }

        public synchronized void clear() {
            saved = new JSONArray();
            clears++;
        }
    }

    static final class Native implements PeerDiscovery.Native {
        final AtomicInteger opens = new AtomicInteger(),
                closes = new AtomicInteger(),
                verifies = new AtomicInteger(),
                polls = new AtomicInteger();
        volatile boolean advertised, readComplete, closeFails;
        volatile Block verifyBlock, openBlock, closeBlock;
        volatile String rejectedKey = "";
        final List<Integer> batchSizes = Collections.synchronizedList(new ArrayList<>());

        public long open(String secret, String relays, boolean advertise) throws Exception {
            advertised = advertise;
            Block waiting = openBlock;
            openBlock = null;
            if (waiting != null) waiting.await();
            return opens.incrementAndGet();
        }

        public String poll(long handle, int timeout) throws Exception {
            polls.incrementAndGet();
            Thread.sleep(3);
            return "[]";
        }

        public String status(long handle) throws Exception {
            return new JSONObject()
                    .put("state", "reading")
                    .put("connected_relays", 2)
                    .put("read_complete", readComplete)
                    .toString();
        }

        public String verify(String events) throws Exception {
            verifies.incrementAndGet();
            Block waiting = verifyBlock;
            verifyBlock = null;
            if (waiting != null) waiting.await();
            JSONArray supplied = new JSONArray(events), valid = new JSONArray();
            batchSizes.add(supplied.length());
            for (int i = 0; i < supplied.length(); i++)
                if (!rejectedKey.equals(supplied.getJSONObject(i).optString("pubkey")))
                    valid.put(supplied.get(i));
            return valid.toString();
        }

        public void close(long handle) throws Exception {
            Block waiting = closeBlock;
            closeBlock = null;
            if (waiting != null) waiting.await();
            closes.incrementAndGet();
            if (closeFails) throw new IOException("fixture close failed");
        }
    }

    static final class Fixture {
        final Time time = new Time();
        final Gate gate = new Gate();
        final Identity identity = new Identity();
        final Disk disk = new Disk();
        final Native nativeApi = new Native();
        final Map<String, Boolean> prefs = new HashMap<>();
        final AtomicInteger sends = new AtomicInteger();
        final PeerDiscovery discovery =
                new PeerDiscovery(
                        nativeApi,
                        time,
                        identity,
                        gate,
                        disk,
                        preferences(prefs),
                        (p, s, t, b) -> sends.incrementAndGet());

        void start() throws Exception {
            discovery.setEnabled(true);
            discovery.startScan();
            until(
                    () -> nativeApi.opens.get() == 1,
                    "cold start opens discovery without any paired peer");
        }
    }

    static SharedPreferences preferences(Map<String, Boolean> values) {
        return (SharedPreferences)
                Proxy.newProxyInstance(
                        SharedPreferences.class.getClassLoader(),
                        new Class<?>[] {SharedPreferences.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getBoolean"))
                                return values.getOrDefault(args[0], (Boolean) args[1]);
                            if (method.getName().equals("edit")) {
                                Map<String, Boolean> pending = new HashMap<>();
                                return Proxy.newProxyInstance(
                                        SharedPreferences.Editor.class.getClassLoader(),
                                        new Class<?>[] {SharedPreferences.Editor.class},
                                        (editor, edit, params) -> {
                                            if (edit.getName().equals("putBoolean")) {
                                                pending.put(
                                                        (String) params[0], (Boolean) params[1]);
                                                return editor;
                                            }
                                            if (edit.getName().equals("commit")) {
                                                values.putAll(pending);
                                                return true;
                                            }
                                            if (edit.getName().equals("apply")) {
                                                values.putAll(pending);
                                                return null;
                                            }
                                            throw new AssertionError(edit.getName());
                                        });
                            }
                            throw new AssertionError(method.getName());
                        });
    }

    static void optInAndColdStart() throws Exception {
        Fixture f = new Fixture();
        check(
                !f.discovery.snapshot().enabled && !f.discovery.snapshot().advertise,
                "both discovery and publishing default off");
        denied(f.discovery::startScan, "disabled module cannot scan");
        f.discovery.setEnabled(true);
        check(f.nativeApi.opens.get() == 0, "enabling does not publish or connect");
        f.gate.on = false;
        denied(f.discovery::startScan, "parent gate blocks native scan");
        f.gate.on = true;
        f.discovery.startScan();
        until(() -> f.nativeApi.opens.get() == 1, "zero paired peers still boots public discovery");
        check(
                !f.nativeApi.advertised && f.sends.get() == 0,
                "read-only scan publishes nothing and does not invent trusted peers");
        f.discovery.stop();
        check(
                f.nativeApi.closes.get() == 1 && !f.discovery.snapshot().running,
                "page stop closes active reader");
        check(
                f.discovery.snapshot().enabled && !f.discovery.snapshot().advertise,
                "stop preserves opt-in choices without restarting");
    }

    static void verifyWarmCache() throws Exception {
        Fixture f = new Fixture();
        f.disk.saved = PeerCacheExchangeTest.records(1, 256, 1000, 1300);
        f.disk.saved.put(PeerCacheExchangeTest.record(900, 1000, 1300));
        f.nativeApi.rejectedKey = PeerCacheExchangeTest.key(2);
        f.start();
        check(
                f.nativeApi.batchSizes.equals(Arrays.asList(128, 128, 1)),
                "saved records are natively reverified in bounded batches before native open");
        check(
                f.discovery.snapshot().candidates.size() == 255,
                "native-rejected and own profiles never enter warm cache");
        check(
                f.disk.saved.length() == 255,
                "persisted pool contains only accepted public candidate events");
        f.discovery.clearCache();
        check(
                f.discovery.snapshot().candidates.isEmpty()
                        && f.disk.saved.length() == 0
                        && f.disk.clears == 1,
                "clear removes disk and memory cache after stopping");
    }

    static void cancellationBeforeOpenAndAfterVerify() throws Exception {
        Fixture identity = new Fixture();
        Block blockedIdentity = new Block();
        identity.identity.block = blockedIdentity;
        identity.discovery.setEnabled(true);
        identity.discovery.startScan();
        blockedIdentity.reached();
        identity.discovery.stop();
        blockedIdentity.release.countDown();
        until(() -> identity.identity.finished.get() == 1, "cancelled identity load exits");
        check(
                identity.nativeApi.opens.get() == 0,
                "cancelled identity load cannot open a delayed advertiser");

        Fixture crypto = new Fixture();
        crypto.disk.saved = PeerCacheExchangeTest.records(1, 1, 1000, 1300);
        Block blockedVerify = new Block();
        crypto.nativeApi.verifyBlock = blockedVerify;
        crypto.discovery.setEnabled(true);
        crypto.discovery.startScan();
        blockedVerify.reached();
        crypto.discovery.stop();
        blockedVerify.release.countDown();
        until(() -> crypto.nativeApi.batchSizes.size() == 1, "cancelled verification exits");
        check(
                crypto.discovery.snapshot().candidates.isEmpty()
                        && crypto.nativeApi.opens.get() == 0
                        && crypto.disk.writes == 0,
                "stale crypto result cannot update or persist candidate cache or open a session");
    }

    static void nativeLifetimeSerialization() throws Exception {
        Fixture f = new Fixture();
        Block opened = new Block();
        f.nativeApi.openBlock = opened;
        f.discovery.setEnabled(true);
        f.discovery.startScan();
        opened.reached();
        CountDownLatch stopped = new CountDownLatch(1);
        Thread stop =
                new Thread(
                        () -> {
                            f.discovery.stop();
                            stopped.countDown();
                        });
        stop.start();
        check(
                !stopped.await(30, TimeUnit.MILLISECONDS),
                "stop waits for local open ownership to resolve");
        opened.release.countDown();
        check(
                stopped.await(3, TimeUnit.SECONDS),
                "stop closes the handle created by in-flight open");
        check(
                f.nativeApi.opens.get() == 1
                        && f.nativeApi.closes.get() == 1
                        && !f.discovery.snapshot().running,
                "native open/stop has no leaked handle");

        Fixture close = new Fixture();
        close.start();
        Block closing = new Block();
        close.nativeApi.closeBlock = closing;
        Thread closer = new Thread(close.discovery::stop);
        closer.start();
        closing.reached();
        CountDownLatch restarted = new CountDownLatch(1);
        Thread restart =
                new Thread(
                        () -> {
                            try {
                                close.discovery.startScan();
                            } catch (Exception e) {
                                throw new AssertionError(e);
                            } finally {
                                restarted.countDown();
                            }
                        });
        restart.start();
        check(
                !restarted.await(30, TimeUnit.MILLISECONDS),
                "new scan cannot overlap a closing native reader");
        closing.release.countDown();
        check(
                restarted.await(3, TimeUnit.SECONDS),
                "new scan starts once old native lifetime closes");
        until(() -> close.nativeApi.opens.get() == 2, "fresh reader opens after close");
        close.discovery.stop();
    }

    static void advertisementAndCompletion() throws Exception {
        Fixture f = new Fixture();
        f.discovery.setEnabled(true);
        f.discovery.setAdvertise(true);
        check(f.nativeApi.opens.get() == 0, "public profile toggle requires an explicit scan");
        f.discovery.startScan();
        until(() -> f.nativeApi.opens.get() == 1, "opted-in advertiser opened");
        check(f.nativeApi.advertised, "explicit own advertisement opt-in is passed to native");
        f.nativeApi.readComplete = true;
        until(
                () -> f.discovery.snapshot().status.startsWith("Sharing"),
                "advertiser finishes scan window");
        check(
                f.discovery.snapshot().running,
                "opted-in publication remains active only while page session remains open");
        f.discovery.stop();
        check(
                f.nativeApi.closes.get() == 1 && f.discovery.snapshot().advertise,
                "page stop closes advertising but preserves choice");

        Fixture read = new Fixture();
        read.nativeApi.readComplete = true;
        read.start();
        until(
                () -> !read.discovery.snapshot().running,
                "read-only discovery ends on native scan completion");
        check(read.nativeApi.closes.get() == 1, "completed read session closes exactly once");

        Fixture bounded = new Fixture();
        bounded.start();
        bounded.time.elapsed = 10000;
        until(
                () -> !bounded.discovery.snapshot().running,
                "Java wall budget also terminates an unfinished native read");
        check(
                bounded.nativeApi.closes.get() == 1,
                "deadline terminates reader without waiting for native completion flag");
    }

    static void failedCloseBlocksRestart() throws Exception {
        Fixture f = new Fixture();
        f.start();
        f.nativeApi.closeFails = true;
        f.discovery.stop();
        check(
                f.discovery.shutdownFailed(),
                "native shutdown failure is exposed to parent disable/reset gates");
        denied(f.discovery::startScan, "unknown native shutdown state requires restart");
        check(f.nativeApi.opens.get() == 1, "failed shutdown cannot create overlapping reader");
        f.nativeApi.closeFails = false;
        f.discovery.stop();
    }

    public static void main(String[] args) throws Exception {
        optInAndColdStart();
        verifyWarmCache();
        cancellationBeforeOpenAndAfterVerify();
        nativeLifetimeSerialization();
        advertisementAndCompletion();
        failedCloseBlocksRestart();
        System.out.println("Peer discovery coordinator: " + checks + " assertions passed");
    }
}
