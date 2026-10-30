package app.bitchord.soulseek;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Public profile discovery is an opt-in candidate pool, never a trust or file grant. */
final class PeerDiscovery {
    interface Native {
        long open(String secret, String relays, boolean advertise) throws Exception;

        String poll(long handle, int timeout) throws Exception;

        String status(long handle) throws Exception;

        String verify(String events) throws Exception;

        void close(long handle) throws Exception;
    }

    interface Clock extends PeerExchange.Clock {
        long wall();
    }

    interface Identity {
        NostrIdentityStore.Settings settings() throws Exception;

        String publicKey(String secret) throws Exception;
    }

    interface Gate {
        void require() throws IOException;

        boolean enabled();
    }

    interface CacheFile {
        JSONArray read() throws Exception;

        void write(JSONArray records) throws Exception;

        void clear() throws Exception;
    }

    public static final class Candidate {
        public final String key;
        public final long created, expires;
        public final List<String> transports;
        public final JSONObject signed;
        final String original;

        private Candidate(JSONObject record, long now) throws Exception {
            original = record.toString();
            if (original.getBytes(StandardCharsets.UTF_8).length > 2048
                    || record.length() != 7
                    || record.getInt("kind") != 11120)
                throw new IOException("Invalid provider profile.");
            key = record.getString("pubkey");
            if (!key.matches("[0-9a-f]{64}")
                    || !record.getString("id").matches("[0-9a-f]{64}")
                    || !record.getString("sig").matches("[0-9a-f]{128}"))
                throw new IOException("Invalid provider identity.");
            created = record.getLong("created_at");
            JSONObject body = new JSONObject(record.getString("content"));
            if (body.length() != 6
                    || !"app.bitchord.providers/1".equals(body.getString("protocol"))
                    || body.getInt("version") != 1
                    || !Boolean.TRUE.equals(body.get("public"))
                    || !Boolean.TRUE.equals(body.get("pex")))
                throw new IOException("Unsupported provider profile.");
            expires = body.getLong("expires_at");
            if (created <= 0
                    || created > now + 30
                    || expires <= now
                    || expires <= created
                    || expires - created > 300) throw new IOException("Expired provider profile.");
            JSONArray tags = record.getJSONArray("tags");
            boolean topic = false, expiration = false;
            if (tags.length() != 2) throw new IOException("Invalid provider tags.");
            for (int i = 0; i < tags.length(); i++) {
                JSONArray tag = tags.getJSONArray(i);
                if (tag.length() != 2) throw new IOException("Invalid provider tag.");
                if ("t".equals(tag.getString(0))
                        && "app.bitchord.providers/1".equals(tag.getString(1))
                        && !topic) topic = true;
                else if ("expiration".equals(tag.getString(0))
                        && Long.toString(expires).equals(tag.getString(1))
                        && !expiration) expiration = true;
                else throw new IOException("Unsupported provider tag.");
            }
            if (!topic || !expiration) throw new IOException("Missing provider tags.");
            JSONArray supplied = body.getJSONArray("transports");
            Set<String> allowed = new HashSet<>(Arrays.asList("direct", "tor", "i2p", "ipfs"));
            List<String> parsed = new ArrayList<>();
            if (supplied.length() < 1 || supplied.length() > 4)
                throw new IOException("Invalid provider capabilities.");
            for (int i = 0; i < supplied.length(); i++) {
                String value = supplied.getString(i);
                if (!allowed.contains(value) || parsed.contains(value))
                    throw new IOException("Invalid provider capability.");
                parsed.add(value);
            }
            transports = Collections.unmodifiableList(parsed);
            signed = new JSONObject(original);
        }

        static Candidate parse(JSONObject record, long now) throws Exception {
            return new Candidate(record, now);
        }

        Candidate copy() {
            try {
                return new Candidate(new JSONObject(original), created);
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    public static final class Snapshot {
        public final boolean enabled, advertise, running;
        public final String status;
        public final int connectedRelays, received;
        public final List<Candidate> candidates;

        Snapshot(
                boolean enabled,
                boolean advertise,
                boolean running,
                String status,
                int connectedRelays,
                int received,
                List<Candidate> candidates) {
            this.enabled = enabled;
            this.advertise = advertise;
            this.running = running;
            this.status = status;
            this.connectedRelays = connectedRelays;
            this.received = received;
            this.candidates = candidates;
        }
    }

    private static final int MAX_CACHE = 1024 * 1024 + 4096;
    private static PeerDiscovery instance;
    private final Object lock = new Object();
    private final Native nativeApi;
    private final Clock clock;
    private final Identity identity;
    private final Gate gate;
    private final CacheFile file;
    private final SharedPreferences preferences;
    private final PeerCandidateCache cache = new PeerCandidateCache();
    private final PeerExchange exchange;
    private long generation, handle, failedHandle;
    private boolean enabled, advertise, running, shutdownFailed;
    private String state = "Peer discovery is off.";
    private String ownKey = "";
    private int connectedRelays, received;

    static synchronized PeerDiscovery get(Context context) throws IOException {
        if (instance == null) {
            Context app = context.getApplicationContext();
            File home = new File(app.getNoBackupFilesDir(), "peer-discovery");
            if (!home.isDirectory() && !home.mkdirs())
                throw new IOException("Cannot create provider cache.");
            instance =
                    new PeerDiscovery(
                            new Native() {
                                public long open(String secret, String relays, boolean advertise) {
                                    PeerDiscoveryNative.load();
                                    return PeerDiscoveryNative.open(secret, relays, advertise);
                                }

                                public String poll(long handle, int timeout) {
                                    return PeerDiscoveryNative.poll(handle, timeout);
                                }

                                public String status(long handle) {
                                    return PeerDiscoveryNative.status(handle);
                                }

                                public String verify(String events) {
                                    PeerDiscoveryNative.load();
                                    return PeerDiscoveryNative.verify(events);
                                }

                                public void close(long handle) {
                                    PeerDiscoveryNative.close(handle);
                                }
                            },
                            new Clock() {
                                public long wall() {
                                    return System.currentTimeMillis() / 1000;
                                }

                                public long elapsed() {
                                    return SystemClock.elapsedRealtime();
                                }
                            },
                            new Identity() {
                                public NostrIdentityStore.Settings settings() throws Exception {
                                    return NostrCoordinator.get(app).discoverySettings();
                                }

                                public String publicKey(String secret) {
                                    NostrNative.load();
                                    return NostrNative.publicKey(secret);
                                }
                            },
                            new Gate() {
                                public void require() throws IOException {
                                    DownloadSettings.requireEnabled(app, DownloadSettings.PEERS);
                                }

                                public boolean enabled() {
                                    return DownloadSettings.enabled(app, DownloadSettings.PEERS);
                                }
                            },
                            new DiskCache(new File(home, "providers.json")),
                            app.getSharedPreferences(
                                    "bitchord_peer_discovery", Context.MODE_PRIVATE),
                            (peer, session, type, payload) -> {
                                NostrCoordinator coordinator = NostrCoordinator.existing();
                                if (coordinator == null)
                                    throw new IOException("Start paired coordination first.");
                                coordinator.sendDiscoveryMessage(peer, session, type, payload);
                            });
        }
        return instance;
    }

    static synchronized PeerDiscovery existing() {
        return instance;
    }

    PeerDiscovery(
            Native nativeApi,
            Clock clock,
            Identity identity,
            Gate gate,
            CacheFile file,
            SharedPreferences preferences,
            PeerExchange.Sender sender) {
        this.nativeApi = nativeApi;
        this.clock = clock;
        this.identity = identity;
        this.gate = gate;
        this.file = file;
        this.preferences = preferences;
        enabled = preferences.getBoolean("enabled", false);
        advertise = preferences.getBoolean("advertise", false);
        if (enabled) state = "Start a scan to find compatible provider profiles.";
        exchange =
                new PeerExchange(
                        clock,
                        sender,
                        new PeerExchange.Records() {
                            public JSONArray records(int limit) throws Exception {
                                synchronized (lock) {
                                    if (!allowed()) return new JSONArray();
                                    return cache.records(clock.wall(), limit);
                                }
                            }

                            public void ingest(JSONArray records, String referrer, long intent)
                                    throws Exception {
                                ingestRecords(records, referrer, intent);
                            }
                        });
    }

    Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(
                    enabled,
                    advertise,
                    running,
                    state,
                    connectedRelays,
                    received,
                    cache.snapshot(clock.wall()));
        }
    }

    boolean shutdownFailed() {
        synchronized (lock) {
            return shutdownFailed;
        }
    }

    void setEnabled(boolean on) throws IOException {
        if (on) gate.require();
        synchronized (lock) {
            if (!preferences.edit().putBoolean("enabled", on).commit())
                throw new IOException("Discovery settings could not be saved.");
            enabled = on;
        }
        if (!on) stop();
    }

    void setAdvertise(boolean on) throws IOException {
        if (on) {
            gate.require();
            synchronized (lock) {
                if (!enabled) throw new IOException("Enable peer discovery first.");
            }
        }
        stop();
        synchronized (lock) {
            if (!preferences.edit().putBoolean("advertise", on).commit())
                throw new IOException("Discovery settings could not be saved.");
            advertise = on;
            state = "Start a scan to apply the public profile setting.";
        }
    }

    void startScan() throws IOException {
        gate.require();
        final long intent, deadline;
        final boolean publish;
        synchronized (lock) {
            if (!enabled) throw new IOException("Enable peer discovery first.");
            if (shutdownFailed)
                throw new IOException("Restart BitChord before starting discovery.");
            if (running) return;
            intent = ++generation;
            deadline = clock.elapsed() + 10_000;
            publish = advertise;
            running = true;
            connectedRelays = received = 0;
            state = "Finding compatible provider profiles…";
        }
        Thread worker =
                new Thread(() -> scan(intent, deadline, publish), "bitchord-peer-discovery");
        worker.setDaemon(true);
        worker.start();
        announcePaired();
    }

    private void scan(long intent, long deadline, boolean publish) {
        long active = 0;
        try {
            NostrIdentityStore.Settings settings = identity.settings();
            String key = identity.publicKey(settings.secret);
            synchronized (lock) {
                if (!current(intent)) return;
                ownKey = key;
            }
            JSONArray saved;
            try {
                saved = file.read();
            } catch (Exception invalidCache) {
                saved = new JSONArray();
            }
            for (int from = 0; from < saved.length() && from < 512; from += 128) {
                JSONArray part = new JSONArray();
                for (int i = from; i < Math.min(from + 128, saved.length()); i++)
                    part.put(saved.get(i));
                ingestRecords(part, null, intent);
            }
            synchronized (lock) {
                if (!current(intent) || clock.elapsed() >= deadline) return;
                gate.require();
                // Native open creates its worker without waiting for network IO. Serialize it
                // with stop so no cancelled start can open an advertiser after stop returns.
                active =
                        nativeApi.open(
                                settings.secret,
                                new JSONArray(settings.relays).toString(),
                                publish);
                if (active <= 0) throw new IOException("Discovery could not start.");
                handle = active;
            }
            while (isCurrent(intent, active)) {
                JSONArray events = new JSONArray(nativeApi.poll(active, 250));
                if (clock.elapsed() <= deadline && events.length() > 0)
                    ingestRecords(events, null, intent);
                JSONObject status = new JSONObject(nativeApi.status(active));
                synchronized (lock) {
                    if (!current(intent) || handle != active) break;
                    connectedRelays = Math.max(0, Math.min(3, status.optInt("connected_relays")));
                    boolean complete =
                            clock.elapsed() >= deadline || status.optBoolean("read_complete");
                    state =
                            complete
                                    ? (publish
                                            ? "Sharing a public provider profile. No files are"
                                                    + " shared."
                                            : "Provider profile scan finished.")
                                    : "Finding compatible provider profiles…";
                    if (!publish && complete) break;
                    if ("failed".equals(status.optString("state")))
                        throw new IOException("Discovery worker stopped.");
                    if ("closed".equals(status.optString("state"))) break;
                }
            }
        } catch (Exception | LinkageError failed) {
            synchronized (lock) {
                if (current(intent))
                    state = "Could not complete peer discovery. Check relays and try again.";
            }
        } finally {
            synchronized (lock) {
                if (active > 0 && handle == active) {
                    handle = 0;
                    // Keep start/stop serialized until this native worker is closed.
                    closeHandle(active);
                }
                if (generation == intent) {
                    running = false;
                    connectedRelays = 0;
                    if (state.equals("Finding compatible provider profiles…"))
                        state = "Provider profile scan finished.";
                }
            }
        }
    }

    void stop() {
        synchronized (lock) {
            generation++;
            long closing = handle != 0 ? handle : failedHandle;
            handle = failedHandle = 0;
            running = false;
            connectedRelays = 0;
            state = enabled ? "Peer discovery stopped." : "Peer discovery is off.";
            if (closing != 0) closeHandle(closing);
        }
        exchange.reset();
    }

    void clearCache() throws Exception {
        stop();
        synchronized (lock) {
            cache.clear();
            file.clear();
            received = 0;
            state = "Provider cache cleared.";
        }
    }

    void onPairedStarted() {
        exchange.reset();
        announcePaired();
    }

    void onPairedMessage(String peer, String session, String type, JSONObject payload) {
        synchronized (lock) {
            if (!allowed() || !running) return;
            // Serialize a paired message's local verification/send with page stop. The
            // bounded callback cannot become a fresh HELLO after stop resets PEX.
            try {
                exchange.receive(peer, session, type, payload, generation);
            } catch (Exception | LinkageError invalid) {
                /* A failed optional referral never affects transfers. */
            }
        }
    }

    private void announcePaired() {
        final long intent;
        synchronized (lock) {
            if (!allowed() || !running) return;
            intent = generation;
        }
        NostrCoordinator coordinator = NostrCoordinator.existing();
        if (coordinator == null || !coordinator.running()) return;
        try {
            String key = coordinator.publicKey();
            List<String> peers = NostrConfig.peers(coordinator.peersText());
            synchronized (lock) {
                if (!current(intent)) return;
                ownKey = key;
                for (String peer : peers) exchange.hello(peer);
            }
        } catch (Exception | LinkageError unavailable) {
            /* Paired coordination can start later. */
        }
    }

    private void ingestRecords(JSONArray events, String referrer, long intent) throws Exception {
        if (events.length() > 128
                || events.toString().getBytes(StandardCharsets.UTF_8).length > 256 * 1024)
            throw new IOException("Provider records exceed the discovery limit.");
        boolean needsKey;
        synchronized (lock) {
            if (!current(intent)) return;
            needsKey = ownKey.isEmpty();
        }
        if (needsKey) {
            NostrIdentityStore.Settings settings = identity.settings();
            String key = identity.publicKey(settings.secret);
            synchronized (lock) {
                if (!current(intent)) return;
                ownKey = key;
            }
        }
        JSONArray verified = new JSONArray(nativeApi.verify(events.toString()));
        synchronized (lock) {
            if (!current(intent)) return;
            int added = cache.ingest(verified, referrer, ownKey, clock.wall());
            if (added > 0) {
                received += added;
                file.write(cache.records(clock.wall(), 512));
            }
        }
    }

    private boolean allowed() {
        return enabled && gate.enabled() && !shutdownFailed;
    }

    private boolean current(long intent) {
        return generation == intent && running && allowed();
    }

    private boolean isCurrent(long intent, long active) {
        synchronized (lock) {
            return current(intent) && handle == active;
        }
    }

    private void closeHandle(long closing) {
        try {
            nativeApi.close(closing);
        } catch (Exception | LinkageError failed) {
            synchronized (lock) {
                shutdownFailed = true;
                failedHandle = closing;
                state = "Restart BitChord before starting discovery.";
            }
        }
    }

    private static final class DiskCache implements CacheFile {
        final AtomicFile file;

        DiskCache(File path) {
            file = new AtomicFile(path);
        }

        public JSONArray read() throws Exception {
            if (!file.getBaseFile().exists()) return new JSONArray();
            try (FileInputStream in = file.openRead();
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] block = new byte[4096];
                int count;
                while ((count = in.read(block)) >= 0) {
                    if (bytes.size() + count > MAX_CACHE)
                        throw new IOException("Provider cache is too large.");
                    bytes.write(block, 0, count);
                }
                JSONArray records =
                        new JSONArray(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                if (records.length() > 512) throw new IOException("Too many cached providers.");
                return records;
            }
        }

        public void write(JSONArray records) throws Exception {
            byte[] bytes = records.toString().getBytes(StandardCharsets.UTF_8);
            if (records.length() > 512 || bytes.length > MAX_CACHE)
                throw new IOException("Provider cache is too large.");
            FileOutputStream output = file.startWrite();
            try {
                output.write(bytes);
                file.finishWrite(output);
            } catch (Exception error) {
                file.failWrite(output);
                throw error;
            }
        }

        public void clear() {
            file.delete();
        }
    }
}
