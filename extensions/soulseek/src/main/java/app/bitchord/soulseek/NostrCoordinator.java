package app.bitchord.soulseek;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Explicitly started, app-specific coordination. No Telegram account or automatic discovery. */
final class NostrCoordinator {
    interface MessageHandler {
        void onMessage(
                long generation, String peer, String session, String type, JSONObject payload);

        void onStopped();
    }

    private static NostrCoordinator instance;
    private final NostrIdentityStore store;
    private final Context context;
    private final Object lock = new Object();
    private final Map<String, Pending> checks = new LinkedHashMap<>();
    private final Set<Long> relayProbes = new HashSet<>();
    private long generation;
    private volatile long handle;
    private volatile boolean starting;
    private boolean resetting, stopping, shutdownFailed;
    private volatile String state = "Coordination is stopped.";
    private volatile String peerResult = "";
    private volatile int connectedRelays, configuredRelays, trustedPeers, responses;
    private volatile MessageHandler messageHandler;

    private static final class Pending {
        final String peer, nonce;
        final long deadline;

        Pending(String peer, String nonce) {
            this.peer = peer;
            this.nonce = nonce;
            deadline = SystemClock.elapsedRealtime() + 15_000;
        }
    }

    private NostrCoordinator(Context context) throws IOException {
        Context app = context.getApplicationContext();
        this.context = app;
        File home = new File(app.getNoBackupFilesDir(), "soulseek-coordination");
        if (!home.isDirectory() && !home.mkdirs())
            throw new IOException("Cannot create connection settings storage.");
        store = new NostrIdentityStore(app, home);
    }

    static synchronized NostrCoordinator get(Context context) throws IOException {
        if (instance == null) instance = new NostrCoordinator(context);
        return instance;
    }

    static synchronized NostrCoordinator existing() {
        return instance;
    }

    String publicKey() throws Exception {
        NostrNative.load();
        return NostrNative.publicKey(store.loadOrCreate().secret);
    }

    String relaysText() throws Exception {
        return NostrConfig.lines(store.loadOrCreate().relays);
    }

    String peersText() throws Exception {
        return NostrConfig.lines(store.loadOrCreate().peers);
    }

    NostrIdentityStore.Settings discoverySettings() throws Exception {
        synchronized (lock) {
            if (resetting || stopping) throw new IOException("Coordination is still stopping.");
            return store.loadOrCreate();
        }
    }

    void configure(String relayText, String peerText) throws Exception {
        List<String> relays = NostrConfig.relays(relayText);
        List<String> peers = NostrConfig.peers(peerText);
        synchronized (lock) {
            if (handle != 0 || starting || resetting || stopping || !relayProbes.isEmpty())
                throw new IOException("Stop coordination before changing settings.");
            String ownKey = publicKey();
            if (peers.contains(ownKey)) throw new IOException("Add another phone's public key.");
            store.configure(relays, peers);
            peerResult = "";
        }
    }

    void configureRelays(String relayText) throws Exception {
        List<String> relays = NostrConfig.relays(relayText);
        synchronized (lock) {
            if (handle != 0 || starting || resetting || stopping || !relayProbes.isEmpty())
                throw new IOException(
                        "Stop coordination and relay checks before changing settings.");
            store.configure(relays, store.loadOrCreate().peers);
        }
    }

    long createRelayProbe() throws Exception {
        DownloadSettings.requireEnabled(context, DownloadSettings.PEERS);
        final long intent;
        synchronized (lock) {
            if (handle != 0
                    || starting
                    || resetting
                    || stopping
                    || shutdownFailed
                    || !relayProbes.isEmpty())
                throw new IOException("Stop coordination before checking relays.");
            intent = generation;
        }
        NostrIdentityStore.Settings settings = store.loadOrCreate();
        RelayProbeNative.load();
        synchronized (lock) {
            if (generation != intent
                    || handle != 0
                    || starting
                    || resetting
                    || stopping
                    || shutdownFailed
                    || !relayProbes.isEmpty()) throw new IOException("Relay check cancelled.");
            long probe = RelayProbeNative.create(settings.secret);
            if (probe <= 0) throw new IOException("Relay check could not start.");
            relayProbes.add(probe);
            return probe;
        }
    }

    void closeRelayProbe(long probe) {
        synchronized (lock) {
            if (!relayProbes.contains(probe)) return;
            try {
                RelayProbeNative.close(probe);
                relayProbes.remove(probe);
            } catch (Exception | LinkageError error) {
                markShutdownFailure();
            }
        }
    }

    void start() throws Exception {
        DownloadSettings.requireEnabled(context, DownloadSettings.PEERS);
        final long intent;
        synchronized (lock) {
            if (resetting || stopping) throw new IOException("Coordination is still stopping.");
            if (!relayProbes.isEmpty())
                throw new IOException("Stop the relay check before starting coordination.");
            if (shutdownFailed)
                throw new IOException("Restart BitChord before starting coordination.");
            if (handle != 0 || starting) return;
            intent = ++generation;
            starting = true;
            state = "Starting coordination…";
            peerResult = "";
        }
        long opened = 0;
        try {
            NostrNative.load();
            NostrIdentityStore.Settings settings = store.loadOrCreate();
            List<String> relays = NostrConfig.relays(NostrConfig.lines(settings.relays));
            List<String> peers = NostrConfig.peers(NostrConfig.lines(settings.peers));
            if (peers.contains(NostrNative.publicKey(settings.secret)))
                throw new IOException("Add another phone's public key.");
            synchronized (lock) {
                if (generation != intent) throw new IOException("Coordination start cancelled.");
                DownloadSettings.requireEnabled(context, DownloadSettings.PEERS);
                opened =
                        NostrNative.open(
                                settings.secret,
                                new JSONArray(relays).toString(),
                                new JSONArray(peers).toString());
                if (opened <= 0) throw new IOException("Coordination could not start.");
                handle = opened;
                starting = false;
                configuredRelays = relays.size();
                trustedPeers = peers.size();
                connectedRelays = responses = 0;
                checks.clear();
                state = "Connecting to relays…";
                final long active = opened;
                Thread reader = new Thread(() -> receive(active, intent), "bitchord-coordination");
                reader.setDaemon(true);
                reader.start();
            }
            opened = 0;
            PeerDiscovery discovery = PeerDiscovery.existing();
            if (discovery != null) discovery.onPairedStarted();
        } catch (Exception | LinkageError error) {
            synchronized (lock) {
                if (generation == intent) {
                    handle = 0;
                    starting = false;
                    state = "Could not start coordination. Check settings and try again.";
                }
            }
            throw error;
        } finally {
            if (opened != 0 && !closeHandle(opened)) markShutdownFailure();
        }
    }

    void stop() {
        long closing;
        List<Long> probes;
        synchronized (lock) {
            if (stopping) return;
            stopping = true;
            ++generation;
            closing = handle;
            probes = new ArrayList<>(relayProbes);
            handle = 0;
            starting = false;
            connectedRelays = 0;
            checks.clear();
            peerResult = "";
            state = "Stopping coordination…";
        }
        MessageHandler listener = messageHandler;
        if (listener != null) listener.onStopped();
        PeerDiscovery discovery = PeerDiscovery.existing();
        if (discovery != null) discovery.stop();
        boolean closed = closeHandle(closing);
        for (long probe : probes) closeRelayProbe(probe);
        synchronized (lock) {
            stopping = false;
            if (!closed) markShutdownFailure();
            else if (!shutdownFailed) state = "Coordination is stopped.";
        }
    }

    void forget() throws Exception {
        synchronized (lock) {
            if (resetting) throw new IOException("Connection identity is being reset.");
            resetting = true;
        }
        try {
            stop();
            PeerDiscovery discovery = PeerDiscovery.existing();
            if (discovery != null) {
                discovery.clearCache();
                if (discovery.shutdownFailed())
                    throw new IOException(
                            "Restart BitChord before resetting the connection identity.");
            }
            synchronized (lock) {
                if (shutdownFailed || stopping)
                    throw new IOException(
                            "Restart BitChord before resetting the connection identity.");
                configuredRelays = trustedPeers = responses = 0;
            }
            store.clear();
        } finally {
            synchronized (lock) {
                resetting = false;
            }
        }
    }

    void ping(String peerText) throws Exception {
        DownloadSettings.requireEnabled(context, DownloadSettings.PEERS);
        List<String> parsed = NostrConfig.peers(peerText);
        if (parsed.size() != 1) throw new IOException("Choose one trusted peer to test.");
        String peer = parsed.get(0);
        if (!store.loadOrCreate().peers.contains(peer))
            throw new IOException("Save this peer's key before testing it.");
        String session = token();
        String nonce = token();
        final long active, intent;
        synchronized (lock) {
            if (handle == 0) throw new IOException("Start coordination first.");
            expireChecks();
            if (checks.size() >= 8) throw new IOException("Wait for pending peer checks.");
            active = handle;
            intent = generation;
            checks.put(session, new Pending(peer, nonce));
            peerResult = "Waiting for an authenticated peer response…";
        }
        try {
            JSONObject sent =
                    new JSONObject(
                            NostrNative.send(
                                    active,
                                    peer,
                                    session,
                                    "ping",
                                    new JSONObject().put("nonce", nonce).toString()));
            if (sent.getInt("accepted_relays") < 1)
                throw new IOException("No relay accepted the connection check.");
        } catch (Exception | LinkageError error) {
            synchronized (lock) {
                if (generation == intent && checks.remove(session) != null)
                    peerResult = "Connection check could not be delivered to a relay.";
            }
            throw error;
        }
    }

    void setMessageHandler(MessageHandler handler) {
        messageHandler = handler;
    }

    boolean isActive(long expectedGeneration) {
        synchronized (lock) {
            return handle != 0
                    && generation == expectedGeneration
                    && !stopping
                    && DownloadSettings.enabled(context, DownloadSettings.PEERS);
        }
    }

    void sendToPeer(String peer, String session, String type, JSONObject payload) throws Exception {
        DownloadSettings.requireEnabled(context, DownloadSettings.PEERS);
        if (!peer.matches("[0-9a-f]{64}")
                || !session.matches("[0-9a-f]{32}")
                || !("offer".equals(type)
                        || "answer".equals(type)
                        || "candidate".equals(type)
                        || "cancel".equals(type))
                || payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        > 8192) throw new IOException("Invalid download coordination message.");
        if (!store.loadOrCreate().peers.contains(peer))
            throw new IOException("Save this peer's key before sharing downloads.");
        final long active, intent;
        synchronized (lock) {
            if (handle == 0 || stopping) throw new IOException("Start coordination first.");
            active = handle;
            intent = generation;
        }
        JSONObject sent =
                new JSONObject(NostrNative.send(active, peer, session, type, payload.toString()));
        if (!current(active, intent)) throw new IOException("Download coordination stopped.");
        if (sent.getInt("accepted_relays") < 1)
            throw new IOException("No relay accepted the download message.");
    }

    void sendDiscoveryMessage(String peer, String session, String type, JSONObject payload)
            throws Exception {
        DownloadSettings.requireEnabled(context, DownloadSettings.PEERS);
        if (!PeerExchange.PROTOCOL.equals(payload.optString("protocol"))
                || !("hello".equals(type)
                        || "pex_request".equals(type)
                        || "pex_response".equals(type))
                || !peer.matches("[0-9a-f]{64}")
                || !session.matches("[0-9a-f]{32}")
                || payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        > 8192) throw new IOException("Invalid provider exchange message.");
        if (!store.loadOrCreate().peers.contains(peer))
            throw new IOException("Save this peer's key before exchanging provider profiles.");
        final long active, intent;
        synchronized (lock) {
            if (handle == 0 || stopping) throw new IOException("Start coordination first.");
            active = handle;
            intent = generation;
        }
        JSONObject sent =
                new JSONObject(NostrNative.send(active, peer, session, type, payload.toString()));
        if (!current(active, intent) || sent.getInt("accepted_relays") < 1)
            throw new IOException("Provider exchange could not be delivered.");
    }

    private void receive(long active, long intent) {
        boolean failed = false;
        try {
            while (current(active, intent)) {
                JSONArray messages = new JSONArray(NostrNative.poll(active, 250));
                for (int i = 0; i < messages.length() && current(active, intent); i++) {
                    JSONObject message = messages.getJSONObject(i);
                    String type = message.getString("type");
                    JSONObject payload = message.getJSONObject("payload");
                    String peer = message.getString("peer");
                    String session = message.getString("session");
                    if ("hello".equals(type)
                            || "pex_request".equals(type)
                            || "pex_response".equals(type)) {
                        PeerDiscovery discovery = PeerDiscovery.existing();
                        if (discovery != null && current(active, intent))
                            discovery.onPairedMessage(peer, session, type, payload);
                        continue;
                    }
                    if (!"ping".equals(type) && !"pong".equals(type)) {
                        MessageHandler listener = messageHandler;
                        if (listener != null && current(active, intent))
                            listener.onMessage(intent, peer, session, type, payload);
                        continue;
                    }
                    String nonce = payload.optString("nonce", "");
                    if (!nonce.matches("[0-9a-f]{32}")) continue;
                    if ("ping".equals(type)) {
                        try {
                            NostrNative.send(
                                    active,
                                    peer,
                                    session,
                                    "pong",
                                    new JSONObject().put("nonce", nonce).toString());
                        } catch (Exception ignored) {
                            // A failed reply leaves the initiating phone's check pending.
                        }
                    } else if ("pong".equals(type)) {
                        synchronized (lock) {
                            if (generation != intent) return;
                            expireChecks();
                            Pending check = checks.get(session);
                            if (check != null
                                    && check.peer.equals(peer)
                                    && check.nonce.equals(nonce)) {
                                checks.remove(session);
                                responses++;
                                peerResult = "Peer check succeeded. Encrypted coordination works.";
                            }
                        }
                    }
                }
                JSONObject health = new JSONObject(NostrNative.status(active));
                if ("failed".equals(health.optString("state"))
                        || "closed".equals(health.optString("state")))
                    throw new IOException("Coordination worker stopped.");
                synchronized (lock) {
                    if (generation != intent) return;
                    connectedRelays = health.optInt("connected_relays", 0);
                    state =
                            connectedRelays > 0
                                    ? "Relay connected. Peer delivery requires a successful check."
                                    : "No relay connected. Waiting to reconnect…";
                    expireChecks();
                }
            }
        } catch (Exception | LinkageError error) {
            synchronized (lock) {
                if (generation == intent) {
                    stopping = true;
                    failed = true;
                    starting = false;
                    connectedRelays = 0;
                    checks.clear();
                    state = "Coordination stopped unexpectedly. Start it again to retry.";
                }
            }
        } finally {
            if (failed) {
                MessageHandler listener = messageHandler;
                if (listener != null) listener.onStopped();
                PeerDiscovery discovery = PeerDiscovery.existing();
                if (discovery != null) discovery.stop();
            }
            boolean closed = closeHandle(active);
            synchronized (lock) {
                if (failed && generation == intent) {
                    handle = 0;
                    stopping = false;
                }
                if (!closed) markShutdownFailure();
            }
        }
    }

    private boolean current(long active, long intent) {
        synchronized (lock) {
            return handle == active
                    && generation == intent
                    && DownloadSettings.enabled(context, DownloadSettings.PEERS);
        }
    }

    private void expireChecks() {
        long now = SystemClock.elapsedRealtime();
        Iterator<Pending> iterator = checks.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().deadline <= now) {
                iterator.remove();
                peerResult =
                        "Peer check timed out. Both phones need matching trust and relay settings.";
            }
        }
    }

    boolean running() {
        return starting || handle != 0;
    }

    boolean shutdownFailed() {
        synchronized (lock) {
            return shutdownFailed;
        }
    }

    String status() {
        String result = peerResult;
        return state + (result.isEmpty() ? "" : "\n" + result);
    }

    String diagnostics() {
        return "Connected relays: "
                + connectedRelays
                + "/"
                + configuredRelays
                + " · Trusted peers: "
                + trustedPeers
                + " · Verified responses: "
                + responses;
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private boolean closeHandle(long active) {
        if (active == 0) return true;
        try {
            NostrNative.close(active);
            return true;
        } catch (Exception | LinkageError ignored) {
            return false;
        }
    }

    private void markShutdownFailure() {
        synchronized (lock) {
            shutdownFailed = true;
            state = "Coordination did not finish stopping. Restart BitChord before reconnecting.";
        }
    }
}
