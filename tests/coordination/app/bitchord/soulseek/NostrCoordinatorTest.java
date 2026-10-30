package app.bitchord.soulseek;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class NostrCoordinatorTest {
    private static final String PEER_A = hex('b', 64);
    private static final String PEER_B = hex('c', 64);
    private static final String PEER_C = hex('d', 64);
    private static final String RELAYS = "wss://relay.damus.io\nwss://nos.lol";
    private static int checks;
    private static NostrCoordinator previous;

    interface Task {
        void run() throws Exception;
    }

    private static final class Job {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread thread;

        Job(Task task) {
            thread =
                    new Thread(
                            () -> {
                                try {
                                    task.run();
                                } catch (Throwable error) {
                                    failure.set(error);
                                }
                            },
                            "coordination-test-worker");
            thread.setDaemon(true);
            thread.start();
        }

        void finished(boolean expectedFailure) throws Exception {
            thread.join(5_000);
            check(!thread.isAlive(), "Concurrent operation must finish");
            if (expectedFailure)
                check(failure.get() instanceof IOException, "Cancelled operation reports failure");
            else if (failure.get() != null)
                throw new AssertionError("Unexpected concurrent failure", failure.get());
        }
    }

    private static String hex(char value, int count) {
        StringBuilder text = new StringBuilder();
        while (text.length() < count) text.append(value);
        return text.toString();
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void denied(Task task, String message) throws Exception {
        try {
            task.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            checks++;
        }
    }

    private static NostrCoordinator fresh() throws Exception {
        if (previous != null) previous.stop();
        Field singleton = NostrCoordinator.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        singleton.set(null, null);
        previous =
                NostrCoordinator.get(
                        new Context(Files.createTempDirectory("nostr-controller-").toFile()));
        return previous;
    }

    private static NostrCoordinator configured() throws Exception {
        NostrCoordinator coordinator = fresh();
        coordinator.configure(RELAYS, PEER_A + "\n" + PEER_B);
        return coordinator;
    }

    private static void responses(NostrCoordinator coordinator, int count) {
        check(
                coordinator.diagnostics().endsWith("Verified responses: " + count),
                "Only matched, live challenges count as verified responses");
    }

    private static void constructionAndConfiguration() throws Exception {
        int operations = NostrNative.operations.get();
        int probes = RelayProbeNative.creates.get();
        NostrCoordinator coordinator = fresh();
        check(!coordinator.running(), "Construction leaves coordination stopped");
        check(coordinator.status().contains("stopped"), "Initial status is truthful");
        check(coordinator.publicKey().matches("[0-9a-f]{64}"), "Expose a local app identity");
        check(!coordinator.relaysText().isEmpty(), "Default relays can be inspected locally");
        check(coordinator.peersText().isEmpty(), "No implicit trusted peers");
        check(
                NostrNative.operations.get() == operations,
                "Construction and settings getters never start networking");
        check(RelayProbeNative.creates.get() == probes, "Construction never creates a relay probe");
        denied(coordinator::start, "Do not open a relay without configured peers");
        check(
                NostrNative.operations.get() == operations,
                "Invalid start never reaches native open");
        denied(() -> coordinator.configure(RELAYS, coordinator.publicKey()), "Reject self pairing");
        denied(
                () -> coordinator.configure("ws://relay.damus.io", PEER_A),
                "Reject insecure relays");
        denied(() -> coordinator.configure(RELAYS, "invalid"), "Reject invalid peer keys");
        coordinator.configure("WSS://RELAY.DAMUS.IO:443/\nwss://nos.lol", PEER_A.toUpperCase());
        check(coordinator.relaysText().equals(RELAYS), "Save canonical relay addresses");
        check(coordinator.peersText().equals(PEER_A), "Save canonical trusted peer keys");
        check(
                NostrNative.operations.get() == operations,
                "Saving settings never opens a connection");
        denied(() -> coordinator.ping(PEER_A), "A peer test requires an active connection");
    }

    private static void stopCancelsPendingStart() throws Exception {
        NostrCoordinator coordinator = configured();
        NostrNative.Gate gate = new NostrNative.Gate();
        NostrIdentityStore.last.nextLoad = gate;
        int opens = NostrNative.opens.get();
        Job start = new Job(coordinator::start);
        gate.entered();
        check(coordinator.running(), "A pending start is reported as running");
        denied(() -> coordinator.configure(RELAYS, PEER_A), "Do not change settings during start");
        coordinator.stop();
        check(!coordinator.running(), "Stop invalidates the pending start immediately");
        gate.release();
        start.finished(true);
        check(
                NostrNative.opens.get() == opens,
                "A cancelled start cannot open a stale native session");
        check(coordinator.status().contains("stopped"), "Old start cannot overwrite stop status");
    }

    private static void resetCancelsPendingStart() throws Exception {
        NostrCoordinator coordinator = configured();
        String oldKey = coordinator.publicKey();
        NostrIdentityStore store = NostrIdentityStore.last;
        NostrNative.Gate gate = new NostrNative.Gate();
        store.nextLoad = gate;
        int opens = NostrNative.opens.get();
        Job start = new Job(coordinator::start);
        gate.entered();
        coordinator.forget();
        gate.release();
        start.finished(true);
        check(store.clears == 1, "Reset removes the old identity settings");
        check(!coordinator.publicKey().equals(oldKey), "Reset creates a different app identity");
        check(coordinator.peersText().isEmpty(), "Reset removes old trust relationships");
        check(!coordinator.running(), "Reset leaves coordination stopped");
        check(NostrNative.opens.get() == opens, "Reset prevents old identity from opening a relay");

        NostrNative.Gate clear = new NostrNative.Gate();
        store.nextClear = clear;
        Job reset = new Job(coordinator::forget);
        clear.entered();
        denied(coordinator::start, "A new start cannot race identity reset");
        denied(() -> coordinator.configure(RELAYS, PEER_A), "Do not save settings during reset");
        clear.release();
        reset.finished(false);
    }

    private static void pingMatching() throws Exception {
        NostrCoordinator coordinator = configured();
        coordinator.start();
        NostrNative.Session session = NostrNative.opened();
        check(coordinator.running(), "Explicit start creates an active coordination session");
        denied(() -> coordinator.configure(RELAYS, PEER_A), "Stop before changing active settings");
        denied(() -> coordinator.ping(PEER_C), "Never test an untrusted peer");
        denied(
                () -> coordinator.ping(PEER_A + "\n" + PEER_B),
                "A check addresses exactly one peer");
        coordinator.ping(PEER_A);
        NostrNative.Request ping = session.request();
        check(
                ping.type.equals("ping") && ping.peer.equals(PEER_A),
                "Send the requested peer a ping");
        check(
                ping.session.matches("[0-9a-f]{32}") && ping.nonce.matches("[0-9a-f]{32}"),
                "Use independent session and challenge tokens");
        check(!ping.session.equals(ping.nonce), "Session and challenge are distinct");
        session.deliver(ping.response(PEER_A, ping.session, hex('0', 32)));
        responses(coordinator, 0);
        session.deliver(ping.response(PEER_B, ping.session, ping.nonce));
        responses(coordinator, 0);
        session.deliver(ping.response(PEER_A, hex('f', 32), ping.nonce));
        responses(coordinator, 0);
        session.deliver(ping.response());
        responses(coordinator, 1);
        check(
                coordinator.status().contains("Peer check succeeded"),
                "Report authenticated peer success");
        session.deliver(ping.response());
        responses(coordinator, 1);

        session.acceptedRelays = 0;
        denied(() -> coordinator.ping(PEER_A), "Zero accepted relays is delivery failure");
        NostrNative.Request rejected = session.request();
        check(
                coordinator.status().contains("could not be delivered"),
                "Report failed relay delivery");
        session.acceptedRelays = 1;
        session.deliver(rejected.response());
        responses(coordinator, 1);

        coordinator.ping(PEER_A);
        NostrNative.Request expired = session.request();
        SystemClock.advance(15_001);
        session.deliver(expired.response());
        responses(coordinator, 1);
        check(
                coordinator.status().contains("timed out"),
                "Expired challenges cannot report success");

        JSONObject inboundPing =
                new JSONObject()
                        .put("type", "ping")
                        .put("peer", PEER_B)
                        .put("session", hex('e', 32))
                        .put("payload", new JSONObject().put("nonce", hex('a', 32)));
        session.deliver(inboundPing);
        NostrNative.Request pong = session.request();
        check(
                pong.type.equals("pong") && pong.peer.equals(PEER_B),
                "Reply to an authenticated peer ping");
        check(
                pong.session.equals(hex('e', 32)) && pong.nonce.equals(hex('a', 32)),
                "Reply echoes only the originating session challenge");
        responses(coordinator, 1);

        for (int i = 0; i < 8; i++) {
            coordinator.ping(PEER_A);
            session.request();
        }
        denied(() -> coordinator.ping(PEER_A), "Bound outstanding peer checks");
        SystemClock.advance(15_001);
        coordinator.ping(PEER_A);
        session.request();
        coordinator.stop();
        session.awaitClosed();
        check(!coordinator.running(), "Stop closes the active coordination session");
    }

    private static void generationAndShutdown() throws Exception {
        NostrCoordinator coordinator = configured();
        coordinator.start();
        NostrNative.Session old = NostrNative.opened();
        coordinator.ping(PEER_A);
        NostrNative.Request stale = old.request();
        NostrNative.Gate close = new NostrNative.Gate();
        old.closeGate = close;
        Job stop = new Job(coordinator::stop);
        close.entered();
        denied(coordinator::start, "A new session cannot race native shutdown");
        denied(
                () -> coordinator.configure(RELAYS, PEER_A),
                "Settings cannot change during shutdown");
        close.release();
        stop.finished(false);
        coordinator.start();
        NostrNative.Session current = NostrNative.opened();
        current.deliver(stale.response());
        responses(coordinator, 0);
        check(!current.isClosed, "Closing the old session cannot close a new native handle");

        current.health = "failed";
        current.batches.add("[]");
        current.awaitStopped();
        check(!coordinator.running(), "A failed native worker clears the running state");
        check(
                coordinator.status().contains("stopped unexpectedly"),
                "Report a terminal worker failure");
        coordinator.start();
        NostrNative.Session failingClose = NostrNative.opened();
        failingClose.closeFails = true;
        coordinator.stop();
        check(
                coordinator.status().contains("Restart BitChord"),
                "Expose incomplete native shutdown");
        denied(coordinator::start, "Do not reconnect after incomplete native shutdown");
        denied(coordinator::forget, "Do not replace identity while shutdown is uncertain");
    }

    private static void configBoundaries() throws Exception {
        List<String> normalized =
                NostrConfig.relays("WSS://RELAY.DAMUS.IO:443/\r\nwss://relay.damus.io");
        check(
                normalized.size() == 1 && normalized.get(0).equals("wss://relay.damus.io"),
                "Normalize and deduplicate configured relays");
        String[] invalid = {
            "ws://relay.damus.io",
            "wss://user@relay.damus.io",
            "wss://relay.damus.io:444",
            "wss://relay.damus.io?token=secret",
            "wss://relay.damus.io#fragment",
            "wss://127.0.0.1",
            "wss://localhost",
            "wss://relay.local",
            "wss://relay.internal",
            "wss://relay.damus.io/%00",
            "wss://relay.damus.io/%0A",
            "wss://relay.damus.io\u200b",
            "wss://relay.damus.io\t",
            "wss://relay.damus.io\r"
        };
        for (String relay : invalid)
            denied(() -> NostrConfig.relays(relay), "Reject invalid relay configuration");
        denied(() -> NostrConfig.relays(null), "Reject null configuration");
        denied(() -> NostrConfig.relays(""), "Require a relay before start");
        denied(() -> NostrConfig.relays(hex('a', 2049)), "Bound configuration size");
        denied(
                () ->
                        NostrConfig.relays(
                                "wss://a.example\n"
                                        + "wss://b.example\n"
                                        + "wss://c.example\n"
                                        + "wss://d.example"),
                "Bound configured relay count");
        denied(() -> NostrConfig.peers(""), "Require a peer before start");
        denied(() -> NostrConfig.peers(PEER_A + "\0"), "Reject hidden peer-key controls");
        denied(() -> NostrConfig.peers(hex('g', 64)), "Reject non-hexadecimal peer keys");
        check(
                NostrConfig.peers(PEER_A + "\n" + PEER_A.toUpperCase()).size() == 1,
                "Peer key deduplication is case insensitive");
        try {
            normalized.add("wss://changed.example");
            throw new AssertionError("Validated configuration must be immutable");
        } catch (UnsupportedOperationException expected) {
            checks++;
        }
    }

    private static void relayConfigurationPreservesPairing() throws Exception {
        NostrCoordinator coordinator = fresh();
        String key = coordinator.publicKey();
        int opens = NostrNative.opens.get();
        int probes = RelayProbeNative.creates.get();
        coordinator.configureRelays("WSS://RELAY.EXAMPLE:443/\nwss://backup.example");
        check(
                coordinator.relaysText().equals("wss://relay.example\nwss://backup.example"),
                "Relay-only settings are validated and canonicalized");
        check(coordinator.peersText().isEmpty(), "Relay setup does not require or invent peers");
        check(coordinator.publicKey().equals(key), "Relay-only setup preserves the app identity");
        denied(
                () -> coordinator.configureRelays("ws://insecure.example"),
                "Relay-only setup rejects unsafe addresses");
        check(
                coordinator.relaysText().equals("wss://relay.example\nwss://backup.example"),
                "Rejected relay configuration preserves saved settings");
        coordinator.configure(RELAYS, PEER_A + "\n" + PEER_B);
        coordinator.configureRelays("wss://replacement.example");
        check(
                coordinator.peersText().equals(PEER_A + "\n" + PEER_B),
                "Replacing relays preserves the existing trusted peer list");
        check(coordinator.publicKey().equals(key), "Relay replacement does not rotate identity");
        check(
                NostrNative.opens.get() == opens && RelayProbeNative.creates.get() == probes,
                "Saving relays never starts native sessions or scans");
    }

    private static void registeredRelayProbeLifecycle() throws Exception {
        NostrCoordinator coordinator = configured();
        NostrIdentityStore store = NostrIdentityStore.last;
        int opens = NostrNative.opens.get();
        long handle = coordinator.createRelayProbe();
        RelayProbeNative.Probe probe = RelayProbeNative.get(handle);
        check(handle > 0 && !probe.closed, "An explicit relay check registers a live probe");
        check(
                probe.secret.equals(store.loadOrCreate().secret),
                "A relay check uses the current app identity");
        check(!coordinator.running(), "A probe does not claim a peer coordination session");
        check(
                !coordinator.status().contains(probe.secret)
                        && !coordinator.diagnostics().contains(probe.secret),
                "Status and counters never expose the probe's signing secret");
        check(
                NostrNative.opens.get() == opens,
                "Creating a probe does not start peer coordination");
        int creates = RelayProbeNative.creates.get();
        denied(coordinator::start, "Coordination cannot race an active relay scan");
        denied(coordinator::createRelayProbe, "Only one relay scan may hold the identity");
        denied(
                () -> coordinator.configure(RELAYS, PEER_C),
                "Trusted peers cannot change during a scan");
        denied(
                () -> coordinator.configureRelays("wss://replacement.example"),
                "Saved relays cannot change during a scan");
        check(
                NostrNative.opens.get() == opens && RelayProbeNative.creates.get() == creates,
                "Rejected actions cannot open hidden native sessions or probes");
        int closes = RelayProbeNative.closes.get();
        coordinator.closeRelayProbe(handle + 100_000);
        check(
                !probe.closed && RelayProbeNative.closes.get() == closes,
                "An unknown probe handle cannot close a registered scan");
        coordinator.closeRelayProbe(handle);
        check(probe.closed, "Closing a registered scan releases its native probe");
        coordinator.closeRelayProbe(handle);
        check(RelayProbeNative.closes.get() == closes + 1, "Probe close is idempotent");
        coordinator.start();
        NostrNative.Session session = NostrNative.opened();
        check(coordinator.running(), "Successful probe shutdown permits coordination to start");
        denied(coordinator::createRelayProbe, "An active coordination session excludes new scans");
        denied(
                () -> coordinator.configureRelays("wss://replacement.example"),
                "Relay-only configuration cannot change an active session");
        coordinator.stop();
        session.awaitStopped();

        long stoppedHandle = coordinator.createRelayProbe();
        RelayProbeNative.Probe stopped = RelayProbeNative.get(stoppedHandle);
        coordinator.stop();
        check(stopped.closed, "Stop closes a scan even when no coordination session exists");
        check(store.clears == 0, "Stopping a relay scan preserves its stored identity");
        check(coordinator.status().contains("stopped"), "Successful scan stop reports stopped");
    }

    private static void resetClosesRegisteredProbeBeforeIdentityDeletion() throws Exception {
        NostrCoordinator coordinator = configured();
        NostrIdentityStore store = NostrIdentityStore.last;
        String key = coordinator.publicKey();
        RelayProbeNative.Probe probe = RelayProbeNative.get(coordinator.createRelayProbe());
        NostrNative.Gate close = new NostrNative.Gate();
        NostrNative.Gate clear = new NostrNative.Gate();
        probe.closeGate = close;
        store.nextClear = clear;
        Job reset = new Job(coordinator::forget);
        close.entered();
        check(
                !probe.closed && store.clears == 0,
                "Identity deletion waits for the registered probe's close barrier");
        close.release();
        clear.entered();
        check(probe.closed, "The old signing probe is closed before clearing stored identity");
        check(store.clears == 0, "Reset has not completed its storage clear prematurely");
        denied(coordinator::createRelayProbe, "A new relay scan cannot race identity deletion");
        denied(coordinator::start, "Coordination cannot race identity deletion");
        denied(
                () -> coordinator.configureRelays("wss://replacement.example"),
                "Relay settings cannot race identity deletion");
        clear.release();
        reset.finished(false);
        check(store.clears == 1, "Reset clears stored state once the probe is stopped");
        check(!coordinator.publicKey().equals(key), "Reset replaces the signing identity");
        check(coordinator.peersText().isEmpty(), "Reset removes old trusted peers");
        RelayProbeNative.Probe replacement = RelayProbeNative.get(coordinator.createRelayProbe());
        check(
                !replacement.secret.equals(probe.secret),
                "A later scan cannot reuse the old identity");
        coordinator.closeRelayProbe(replacement.handle);
    }

    private static void pendingRelayProbeIsCancelledByStopAndReset() throws Exception {
        NostrCoordinator coordinator = configured();
        NostrIdentityStore store = NostrIdentityStore.last;
        NostrNative.Gate stopGate = new NostrNative.Gate();
        store.nextLoad = stopGate;
        int probes = RelayProbeNative.creates.get();
        Job stopped = new Job(coordinator::createRelayProbe);
        stopGate.entered();
        coordinator.stop();
        stopGate.release();
        stopped.finished(true);
        check(
                RelayProbeNative.creates.get() == probes,
                "Stop prevents a pending settings read from creating an old probe");

        String oldKey = coordinator.publicKey();
        NostrNative.Gate resetGate = new NostrNative.Gate();
        store.nextLoad = resetGate;
        Job reset = new Job(coordinator::createRelayProbe);
        resetGate.entered();
        coordinator.forget();
        check(!coordinator.publicKey().equals(oldKey), "The reset creates a fresh identity");
        resetGate.release();
        reset.finished(true);
        check(
                RelayProbeNative.creates.get() == probes,
                "An obsolete settings snapshot cannot start a scan after reset");
        check(
                coordinator.status().contains("stopped"),
                "Cancelled scan cannot overwrite reset state");

        coordinator.configure(RELAYS, PEER_A);
        NostrNative.Gate startGate = new NostrNative.Gate();
        store.nextLoad = startGate;
        Job start = new Job(coordinator::start);
        startGate.entered();
        denied(coordinator::createRelayProbe, "A scan cannot race a pending coordination start");
        denied(
                () -> coordinator.configureRelays("wss://replacement.example"),
                "Relay changes cannot race a pending coordination start");
        coordinator.stop();
        startGate.release();
        start.finished(true);
    }

    private static void failedRelayProbeShutdownPreventsIdentityReuse() throws Exception {
        for (boolean stop : new boolean[] {false, true}) {
            NostrCoordinator coordinator = configured();
            NostrIdentityStore store = NostrIdentityStore.last;
            String key = coordinator.publicKey();
            RelayProbeNative.Probe probe = RelayProbeNative.get(coordinator.createRelayProbe());
            probe.closeFails = true;
            if (stop) coordinator.stop();
            else coordinator.closeRelayProbe(probe.handle);
            check(!probe.closed, "An uncertain native close keeps the probe retained");
            check(
                    coordinator.status().contains("Restart BitChord"),
                    "An incomplete relay check shutdown is actionable");
            denied(coordinator::start, "Do not reconnect with an incompletely stopped probe");
            denied(
                    coordinator::createRelayProbe,
                    "Do not create another signing probe after close failure");
            denied(
                    coordinator::forget,
                    "Do not delete a signing identity whose probe is still active");
            denied(
                    () -> coordinator.configureRelays("wss://replacement.example"),
                    "Do not change relay settings while an old signing probe remains active");
            check(
                    store.clears == 0 && coordinator.publicKey().equals(key),
                    "Failed probe shutdown preserves the original identity for safe recovery");
            probe.closeFails = false;
            coordinator.closeRelayProbe(probe.handle);
            check(probe.closed, "A retained probe can still finish native cleanup");
            denied(
                    coordinator::start,
                    "Uncertain shutdown requires process restart even after cleanup");
        }

        NostrCoordinator coordinator = configured();
        RelayProbeNative.failCreate = true;
        try {
            denied(coordinator::createRelayProbe, "A failed native create is not registered");
        } finally {
            RelayProbeNative.failCreate = false;
        }
        RelayProbeNative.Probe replacement = RelayProbeNative.get(coordinator.createRelayProbe());
        check(!replacement.closed, "A failed probe create does not leak an active registration");
        coordinator.closeRelayProbe(replacement.handle);
    }

    private static void downloadMessagesAreDispatchedWithoutPingNonce() throws Exception {
        NostrCoordinator coordinator = configured();
        AtomicReference<JSONObject> received = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger stops =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicLong receivedGeneration =
                new java.util.concurrent.atomic.AtomicLong();
        coordinator.setMessageHandler(
                new NostrCoordinator.MessageHandler() {
                    @Override
                    public void onMessage(
                            long generation,
                            String peer,
                            String session,
                            String type,
                            JSONObject payload) {
                        receivedGeneration.set(generation);
                        received.set(payload);
                    }

                    @Override
                    public void onStopped() {
                        stops.incrementAndGet();
                    }
                });
        denied(
                () -> coordinator.sendToPeer(PEER_A, hex('1', 32), "offer", new JSONObject()),
                "Download messages require an active coordination handle");
        coordinator.start();
        NostrNative.Session active = NostrNative.opened();
        JSONObject payload = new JSONObject().put("protocol", "app.bitchord.bulk-signal/1");
        coordinator.sendToPeer(PEER_A, hex('1', 32), "offer", payload);
        check(
                active.request().type.equals("offer"),
                "File offer uses the authenticated native sender");
        denied(
                () -> coordinator.sendToPeer(PEER_C, hex('1', 32), "offer", payload),
                "A new recipient must be saved before sending an offer");
        denied(
                () -> coordinator.sendToPeer(PEER_A, "bad", "offer", payload),
                "Download sessions must use bounded opaque identifiers");
        denied(
                () -> coordinator.sendToPeer(PEER_A, hex('1', 32), "hello", payload),
                "The download sender cannot bypass its allowed message types");
        denied(
                () ->
                        coordinator.sendToPeer(
                                PEER_A,
                                hex('1', 32),
                                "offer",
                                new JSONObject().put("x", hex('x', 8192))),
                "Download payload bytes are bounded before JNI");
        active.deliver(
                new JSONObject()
                        .put("peer", PEER_A)
                        .put("session", hex('2', 32))
                        .put("type", "offer")
                        .put("payload", payload));
        check(received.get() != null, "A file offer without ping nonce reaches the handler");
        long generation = receivedGeneration.get();
        check(
                coordinator.isActive(generation),
                "Handler generation is valid while the session is active");
        active.acceptedRelays = 0;
        denied(
                () -> coordinator.sendToPeer(PEER_A, hex('1', 32), "offer", payload),
                "A file offer needs an accepted relay write");
        active.request();
        coordinator.stop();
        active.awaitStopped();
        check(
                !coordinator.isActive(generation),
                "A stopped session invalidates queued handler work");
        check(stops.get() > 0, "Stopping coordination clears pending download handshakes");
    }

    static void featureGate() throws Exception {
        NostrCoordinator coordinator = fresh();
        DownloadSettings.on = false;
        denied(() -> coordinator.start(), "disabled peer patch cannot open Nostr");
        denied(() -> coordinator.createRelayProbe(), "disabled peer patch cannot probe relays");
        denied(() -> coordinator.ping(PEER_A), "disabled peer patch cannot ping a peer");
        denied(
                () -> coordinator.sendToPeer(PEER_A, hex('1', 32), "offer", new JSONObject()),
                "disabled peer patch cannot send coordination");
        DownloadSettings.on = true;
    }

    public static void main(String[] args) throws Exception {
        featureGate();
        configBoundaries();
        constructionAndConfiguration();
        stopCancelsPendingStart();
        resetCancelsPendingStart();
        pingMatching();
        generationAndShutdown();
        relayConfigurationPreservesPairing();
        registeredRelayProbeLifecycle();
        resetClosesRegisteredProbeBeforeIdentityDeletion();
        pendingRelayProbeIsCancelledByStopAndReset();
        failedRelayProbeShutdownPreventsIdentityReuse();
        downloadMessagesAreDispatchedWithoutPingNonce();
        if (previous != null) previous.stop();
        System.out.println("PASS: " + checks + " Nostr coordinator checks.");
    }
}
