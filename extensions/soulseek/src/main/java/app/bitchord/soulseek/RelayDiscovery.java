package app.bitchord.soulseek;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** User-requested measurements from this phone's current network. */
final class RelayDiscovery {
    private static final int MAX_CACHE_BYTES = 128 * 1024;
    private static final int MAX_HISTORY = 256;
    private static final long SCAN_MS = 120_000;
    private static final String PROCESS_SESSION = UUID.randomUUID().toString();
    private final Context context;
    private final NostrCoordinator coordinator;
    private final AtomicFile cache;
    private final String bootSession;
    private final Object lock = new Object();
    private long generation, probe;
    private volatile boolean scanning;
    private volatile String progress = "No relay check has run.";
    private volatile Report report;

    static final class Report {
        final String networkTag;
        final List<String> configured;
        final List<RelayRanking.Observation> observations;
        final List<RelayRanking.Observation> checked;
        final RelayRanking.Recommendation recommendation;
        final int attempted, planned, poolSize;
        final boolean complete;

        Report(
                String networkTag,
                List<String> configured,
                List<RelayRanking.Observation> observations,
                List<RelayRanking.Observation> checked,
                RelayRanking.Recommendation recommendation,
                int attempted,
                int planned,
                int poolSize,
                boolean complete) {
            this.networkTag = networkTag;
            this.configured = Collections.unmodifiableList(new ArrayList<>(configured));
            this.observations = Collections.unmodifiableList(new ArrayList<>(observations));
            this.checked = Collections.unmodifiableList(new ArrayList<>(checked));
            this.recommendation = recommendation;
            this.attempted = attempted;
            this.planned = planned;
            this.poolSize = poolSize;
            this.complete = complete;
        }

        String summary() {
            int online = 0, usable = 0;
            for (RelayRanking.Observation observation : checked) {
                if (observation.online) online++;
                if (observation.usable) usable++;
            }
            StringBuilder text =
                    new StringBuilder()
                            .append("Checked ")
                            .append(attempted)
                            .append('/')
                            .append(planned)
                            .append(" sampled candidates from ")
                            .append(poolSize)
                            .append(" known relays. This check: ")
                            .append(online)
                            .append(" online, ")
                            .append(usable)
                            .append(" verified usable.\n")
                            .append("Fastest measured coordination relays:");
            Map<String, RelayRanking.Observation> byRelay = new LinkedHashMap<>();
            for (RelayRanking.Observation observation : observations)
                byRelay.put(observation.relay, observation);
            int count = 0;
            for (String relay : recommendation.ranked) {
                if (++count > 3) break;
                RelayRanking.Observation observation = byRelay.get(relay);
                if (observation != null)
                    text.append("\n")
                            .append(relay)
                            .append(" · ")
                            .append(observation.roundtripMs)
                            .append(" ms median · ")
                            .append(observation.samples)
                            .append(" replies");
            }
            if (count == 0) text.append("\nNone verified on this network.");
            text.append("\n")
                    .append(
                            recommendation.canApply
                                    ? "Compatible selection retains working configured relays."
                                    : "No working configured anchor was verified; coordinate relay"
                                            + " changes with your peer.");
            if (!complete)
                text.append("\nThis check did not finish; run it again before applying.");
            return text.toString();
        }

        String details() {
            StringBuilder text = new StringBuilder();
            for (RelayRanking.Observation observation : checked) {
                if (text.length() > 0) text.append("\n\n");
                text.append(observation.relay)
                        .append("\n")
                        .append(observation.status.replace('_', ' '));
                if (observation.online) text.append(" · online");
                if (observation.usable)
                    text.append(" · ")
                            .append(observation.roundtripMs)
                            .append(" ms median, ")
                            .append(observation.samples)
                            .append(" replies");
            }
            return text.length() == 0 ? "No relay measurements completed." : text.toString();
        }
    }

    RelayDiscovery(Context context, NostrCoordinator coordinator) throws IOException {
        this.context = context.getApplicationContext();
        this.coordinator = coordinator;
        String session;
        try {
            int bootCount =
                    Settings.Global.getInt(
                            this.context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            session = bootCount < 0 ? "process:" + PROCESS_SESSION : "boot:" + bootCount;
        } catch (RuntimeException error) {
            session = "process:" + PROCESS_SESSION;
        }
        bootSession = session;
        File home = new File(this.context.getNoBackupFilesDir(), "soulseek-coordination");
        if (!home.isDirectory() && !home.mkdirs())
            throw new IOException("Cannot create relay measurement storage.");
        cache = new AtomicFile(new File(home, "relay-health.json"));
    }

    boolean scanning() {
        return scanning;
    }

    String status() {
        return progress;
    }

    Report report() {
        return report;
    }

    void scan() throws Exception {
        final long intent;
        synchronized (lock) {
            if (scanning) throw new IOException("A relay check is already running.");
            if (coordinator.running())
                throw new IOException("Stop coordination before checking relays.");
            intent = ++generation;
            scanning = true;
            report = null;
            progress = "Preparing relay candidates…";
        }
        ExecutorService workers = null;
        long opened = 0;
        long deadline = SystemClock.elapsedRealtime() + SCAN_MS;
        try {
            String identity = coordinator.publicKey();
            String network = networkTag(identity);
            List<String> configured = NostrConfig.relays(coordinator.relaysText());
            List<RelayRanking.Observation> history = load(network);
            List<String> pool;
            try (InputStream input = context.getAssets().open(RelayCandidates.ASSET_PATH)) {
                pool = RelayCandidates.load(input);
            }
            List<String> candidates =
                    RelayCandidates.sample(
                            pool,
                            configured,
                            history,
                            network,
                            System.currentTimeMillis(),
                            new Random(),
                            RelayCandidates.MAX_PROBES);
            Map<String, RelayRanking.Observation> results = new LinkedHashMap<>();
            List<RelayRanking.Observation> checked = new ArrayList<>();
            for (RelayRanking.Observation observation : history)
                results.put(observation.relay, observation);
            synchronized (lock) {
                if (generation != intent) return;
                opened = coordinator.createRelayProbe();
                probe = opened;
            }
            final long active = opened;
            workers = Executors.newFixedThreadPool(2);
            CompletionService<RelayRanking.Observation> completions =
                    new ExecutorCompletionService<>(workers);
            int submitted = 0, finished = 0, pending = 0;
            boolean interrupted = false;
            while (finished < candidates.size()) {
                if (!current(intent) || Thread.currentThread().isInterrupted()) {
                    interrupted = true;
                    break;
                }
                String currentNetwork;
                try {
                    currentNetwork = networkTag(identity);
                } catch (Exception error) {
                    currentNetwork = "";
                }
                if (!network.equals(currentNetwork)) {
                    progress = "Network changed. Run the relay check again on the new connection.";
                    interrupted = true;
                    break;
                }
                long remaining = deadline - SystemClock.elapsedRealtime() - 5000;
                if (remaining <= 0) break;
                while (pending < 2 && submitted < candidates.size() && remaining >= 2000) {
                    String relay = candidates.get(submitted++);
                    int timeout = (int) Math.min(8000, remaining);
                    completions.submit(() -> measure(active, relay, network, timeout));
                    pending++;
                }
                if (pending == 0) break;
                Future<RelayRanking.Observation> completed =
                        completions.poll(Math.min(250, remaining), TimeUnit.MILLISECONDS);
                if (completed == null) continue;
                RelayRanking.Observation observation = completed.get();
                pending--;
                finished++;
                results.put(observation.relay, observation);
                checked.add(observation);
                progress =
                        "Checked "
                                + finished
                                + "/"
                                + candidates.size()
                                + " relay candidates on this network…";
            }
            if (!current(intent)) interrupted = true;
            if (!interrupted && !network.equals(networkTag(coordinator.publicKey()))) {
                progress = "Network or identity changed. Run the relay check again.";
                interrupted = true;
            }
            List<RelayRanking.Observation> recent = recent(results, network);
            RelayRanking.Recommendation preferred =
                    RelayRanking.recommend(recent, configured, network, System.currentTimeMillis());
            Report measured =
                    new Report(
                            network,
                            configured,
                            recent,
                            checked,
                            preferred,
                            finished,
                            candidates.size(),
                            RelayCandidates.merge(pool, configured).size(),
                            !interrupted && finished == candidates.size());
            synchronized (lock) {
                if (generation == intent && !interrupted) {
                    report = measured;
                    progress = measured.summary();
                    try {
                        save(network, recent);
                    } catch (Exception error) {
                        progress += "\nResults are available, but could not be cached.";
                    }
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            progress = "Relay check cancelled.";
        } catch (Exception | LinkageError error) {
            if (!current(intent)) {
                progress = "Relay check cancelled.";
                return;
            }
            progress = "Relay check could not complete. Check the connection and try again.";
            throw error;
        } finally {
            try {
                if (opened != 0) coordinator.closeRelayProbe(opened);
            } finally {
                if (workers != null) {
                    workers.shutdownNow();
                    try {
                        workers.awaitTermination(2, TimeUnit.SECONDS);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                }
                synchronized (lock) {
                    if (probe == opened) probe = 0;
                    scanning = false;
                    if (generation != intent) progress = "Relay check cancelled.";
                }
            }
        }
    }

    private RelayRanking.Observation measure(long active, String relay, String network, int timeout)
            throws Exception {
        String value = RelayProbeNative.probe(active, relay, timeout);
        if (value == null || value.length() > 4096)
            throw new IOException("Invalid relay measurement response.");
        RelayRanking.Observation result =
                RelayRanking.Observation.fromProbe(
                        new JSONObject(value), network, System.currentTimeMillis());
        if (!relay.equals(result.relay))
            throw new IOException("Relay measurement address mismatch.");
        return result;
    }

    void cancel() {
        long closing;
        synchronized (lock) {
            ++generation;
            closing = probe;
            probe = 0;
            report = null;
            if (scanning) progress = "Cancelling relay check…";
        }
        if (closing != 0) coordinator.closeRelayProbe(closing);
    }

    List<String> preferred() throws Exception {
        Report current = report;
        if (scanning || current == null || !current.complete || !current.recommendation.canApply)
            throw new IOException("Run a complete relay check before applying a selection.");
        if (!current.networkTag.equals(networkTag(coordinator.publicKey()))
                || !current.configured.equals(NostrConfig.relays(coordinator.relaysText())))
            throw new IOException("Network or relay settings changed. Check relays again.");
        RelayRanking.Recommendation fresh =
                RelayRanking.recommend(
                        current.observations,
                        current.configured,
                        current.networkTag,
                        System.currentTimeMillis());
        if (!fresh.canApply) throw new IOException("Relay measurements expired. Check again.");
        return fresh.selected;
    }

    private boolean current(long intent) {
        synchronized (lock) {
            return scanning && generation == intent;
        }
    }

    private String networkTag(String identity) throws Exception {
        ConnectivityManager manager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) throw new IOException("Network information is unavailable.");
        Network network = manager.getActiveNetwork();
        NetworkCapabilities capabilities =
                network == null ? null : manager.getNetworkCapabilities(network);
        if (network == null
                || capabilities == null
                || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            throw new IOException("Connect to the internet before checking relays.");
        StringBuilder value =
                new StringBuilder(bootSession)
                        .append(':')
                        .append(identity)
                        .append(':')
                        .append(Long.toHexString(network.getNetworkHandle()));
        for (int transport = 0; transport < 8; transport++)
            if (capabilities.hasTransport(transport)) value.append(':').append(transport);
        byte[] digest =
                MessageDigest.getInstance("SHA-256")
                        .digest(value.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder tag = new StringBuilder();
        for (byte item : digest)
            tag.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return tag.toString();
    }

    private List<RelayRanking.Observation> recent(
            Map<String, RelayRanking.Observation> values, String network) throws IOException {
        List<RelayRanking.Observation> recent =
                new ArrayList<>(
                        RelayRanking.latest(
                                        new ArrayList<>(values.values()),
                                        network,
                                        System.currentTimeMillis())
                                .values());
        recent.sort(
                Comparator.comparingLong((RelayRanking.Observation item) -> item.checkedAtMs)
                        .reversed());
        if (recent.size() > MAX_HISTORY) recent = new ArrayList<>(recent.subList(0, MAX_HISTORY));
        return recent;
    }

    private List<RelayRanking.Observation> load(String network) {
        try (FileInputStream input = cache.openRead();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_CACHE_BYTES) return Collections.emptyList();
                output.write(buffer, 0, count);
            }
            JSONObject saved =
                    new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            if (saved.getInt("version") != 1 || !network.equals(saved.getString("network")))
                return Collections.emptyList();
            JSONArray entries = saved.getJSONArray("observations");
            if (entries.length() > MAX_HISTORY) return Collections.emptyList();
            List<RelayRanking.Observation> observations = new ArrayList<>();
            for (int i = 0; i < entries.length(); i++)
                observations.add(RelayRanking.Observation.fromJson(entries.getJSONObject(i)));
            return new ArrayList<>(
                    RelayRanking.latest(observations, network, System.currentTimeMillis())
                            .values());
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private void save(String network, List<RelayRanking.Observation> observations)
            throws Exception {
        JSONArray entries = new JSONArray();
        for (RelayRanking.Observation observation : observations) entries.put(observation.toJson());
        byte[] bytes =
                new JSONObject()
                        .put("version", 1)
                        .put("network", network)
                        .put("observations", entries)
                        .toString()
                        .getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CACHE_BYTES)
            throw new IOException("Relay measurements exceed storage limit.");
        FileOutputStream output = cache.startWrite();
        try {
            output.write(bytes);
            cache.finishWrite(output);
        } catch (Exception error) {
            cache.failWrite(output);
            throw error;
        }
    }
}
