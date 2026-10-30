package app.bitchord.soulseek;

import android.content.Context;
import android.net.Network;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class RelayDiscoveryTest {
    private static int checks;

    interface Task {
        void run() throws Exception;
    }

    static final class Job {
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
                            });
            thread.setDaemon(true);
            thread.start();
        }

        void finish(boolean allowCancellation) throws Exception {
            thread.join(5000);
            check(!thread.isAlive(), "Scan worker must finish within its close bound");
            if (failure.get() instanceof Error)
                throw new AssertionError("Probe fixture failed", failure.get());
            if (!allowCancellation && failure.get() != null)
                throw new AssertionError("Unexpected scan failure", failure.get());
        }
    }

    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static void denied(Task task) throws Exception {
        try {
            task.run();
            throw new AssertionError("Expected blocked application");
        } catch (IOException expected) {
            checks++;
        }
    }

    static Context context(int candidates) throws Exception {
        Context context = new Context(Files.createTempDirectory("relay-discovery-").toFile());
        StringBuilder pool = new StringBuilder("wss://fast.example\nwss://auth.example\n");
        for (int i = 0; i < candidates; i++)
            pool.append("wss://candidate").append(i).append(".example\n");
        context.assets.candidates = pool.toString();
        RelayProbeNative.block = false;
        RelayProbeNative.failHost = "";
        return context;
    }

    static RelayProbeNative.State waitProbe() throws Exception {
        if (!RelayProbeNative.created.await(5, TimeUnit.SECONDS))
            throw new AssertionError("No probe created");
        RelayProbeNative.State state = RelayProbeNative.latest;
        state.entered();
        return state;
    }

    static File cache(Context context) {
        return new File(context.getNoBackupFilesDir(), "soulseek-coordination/relay-health.json");
    }

    static void boundedAndNoAutomaticConfiguration() throws Exception {
        Context context = context(80);
        NostrCoordinator coordinator = new NostrCoordinator();
        int nativeHandles = (int) RelayProbeNative.handles.get();
        RelayDiscovery discovery = new RelayDiscovery(context, coordinator);
        check(
                !discovery.scanning() && discovery.report() == null,
                "Construction only initializes local state");
        check(
                coordinator.creates == 0 && RelayProbeNative.handles.get() == nativeHandles,
                "No startup relay network activity");
        denied(discovery::preferred);
        RelayProbeNative.block = true;
        RelayProbeNative.latest = null;
        RelayProbeNative.created = new CountDownLatch(1);
        Job scan = new Job(discovery::scan);
        RelayProbeNative.State nativeState = waitProbe();
        check(discovery.scanning(), "Explicit scan reports its active state");
        check(
                nativeState.active.get() == 2 && nativeState.maximum.get() == 2,
                "Scan uses at most two native probes concurrently");
        denied(discovery::scan);
        nativeState.release();
        scan.finish(false);
        RelayDiscovery.Report report = discovery.report();
        check(
                report != null && report.complete,
                "All sampled candidates produce a completed report");
        check(
                report.planned == 24 && report.attempted == 24 && nativeState.calls.size() == 24,
                "Large pools are sampled to 24 unique candidates");
        check(nativeState.maximum.get() <= 2, "Later candidates preserve the two-worker limit");
        check(
                nativeState.closed && nativeState.active.get() == 0,
                "Completed scan joins native work");
        check(report.poolSize > report.planned, "Report distinguishes sample from known pool");
        List<String> preferred = discovery.preferred();
        check(
                preferred.contains("wss://relay.damus.io") && preferred.contains("wss://nos.lol"),
                "Working configured anchors are retained");
        check(preferred.size() <= 3, "Live relay selection remains bounded to three");
        check(
                coordinator.configurations == 0,
                "Neither scan nor recommendation automatically changes relay settings");
        check(cache(context).isFile(), "Successful scan caches local measurements");
        context.connectivity.network = new Network(72);
        denied(discovery::preferred);
        context.connectivity.network = new Network(71);
        coordinator.identity = NostrCoordinator.repeat('b');
        denied(discovery::preferred);
        coordinator.identity = NostrCoordinator.repeat('a');
        coordinator.relays = "wss://changed.example";
        denied(discovery::preferred);
    }

    static void cancelAndContextChangesDoNotPublish() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            Context context = context(10);
            NostrCoordinator coordinator = new NostrCoordinator();
            RelayDiscovery discovery = new RelayDiscovery(context, coordinator);
            RelayProbeNative.block = true;
            RelayProbeNative.latest = null;
            RelayProbeNative.created = new CountDownLatch(1);
            Job scan = new Job(discovery::scan);
            RelayProbeNative.State nativeState = waitProbe();
            if (scenario == 0) {
                discovery.cancel();
                check(
                        nativeState.closed && nativeState.active.get() == 0,
                        "Cancel joins native probe work before returning");
            } else {
                if (scenario == 1) context.connectivity.network = new Network(99);
                else coordinator.identity = NostrCoordinator.repeat('c');
                nativeState.release();
            }
            scan.finish(scenario == 0);
            check(
                    !discovery.scanning() && discovery.report() == null,
                    "Cancelled or stale scans publish no report");
            check(
                    nativeState.closed && nativeState.active.get() == 0,
                    "Stale scan closes all native work");
            check(!cache(context).exists(), "Cancelled or stale measurements are not cached");
            denied(discovery::preferred);
        }
        Context context = context(40);
        NostrCoordinator coordinator = new NostrCoordinator();
        RelayDiscovery discovery = new RelayDiscovery(context, coordinator);
        RelayProbeNative.block = true;
        RelayProbeNative.latest = null;
        RelayProbeNative.created = new CountDownLatch(1);
        Job scan = new Job(discovery::scan);
        RelayProbeNative.State state = waitProbe();
        SystemClock.advance(120001);
        state.release();
        scan.finish(false);
        check(
                discovery.report() != null && !discovery.report().complete,
                "Deadline stops the scan without a complete recommendation");
        denied(discovery::preferred);
    }

    static void historyIsBoundToNetworkIdentityAndBoot() throws Exception {
        Context context = context(0);
        NostrCoordinator coordinator = new NostrCoordinator();
        RelayDiscovery initial = new RelayDiscovery(context, coordinator);
        initial.scan();
        JSONObject saved =
                new JSONObject(
                        new String(
                                Files.readAllBytes(cache(context).toPath()),
                                StandardCharsets.UTF_8));
        JSONObject historical =
                new JSONObject()
                        .put("relay", "wss://historical.example")
                        .put("network", initial.report().networkTag)
                        .put("checked_at", System.currentTimeMillis())
                        .put("status", "ok")
                        .put("online", true)
                        .put("usable", true)
                        .put("connect_ms", 1)
                        .put("roundtrip_ms", 1)
                        .put("completed_ms", 4)
                        .put("samples", 3);
        saved.getJSONArray("observations").put(historical);
        byte[] seeded = saved.toString().getBytes(StandardCharsets.UTF_8);
        for (int scenario = 0; scenario < 4; scenario++) {
            Files.write(cache(context).toPath(), seeded);
            context.connectivity.network = new Network(scenario == 1 ? 90 : 71);
            coordinator.identity = NostrCoordinator.repeat(scenario == 2 ? 'e' : 'a');
            Settings.Global.boot = scenario == 3 ? 2 : 1;
            RelayDiscovery discovery = new RelayDiscovery(context, coordinator);
            discovery.scan();
            boolean retried = RelayProbeNative.latest.calls.containsKey("wss://historical.example");
            check(
                    retried == (scenario == 0),
                    "Cached history is reused only for the same network, identity and boot");
            check(
                    coordinator.configurations == 0,
                    "Cache reuse does not silently apply relay changes");
        }
        Settings.Global.boot = 1;
        check(
                !new String(seeded, StandardCharsets.UTF_8).contains(NostrCoordinator.repeat('a')),
                "Cache context does not persist a plaintext app identity");
    }

    static void classificationAndMissingAnchor() throws Exception {
        Context context = context(0);
        NostrCoordinator coordinator = new NostrCoordinator();
        RelayProbeNative.failHost = "auth.example";
        RelayDiscovery discovery = new RelayDiscovery(context, coordinator);
        discovery.scan();
        boolean found = false;
        for (RelayRanking.Observation value : discovery.report().observations)
            if (value.relay.equals("wss://auth.example")) {
                found = true;
                check(
                        value.online && !value.usable && value.status.equals("auth_required"),
                        "Online but unauthorized relays remain distinctly classified");
            }
        check(
                found && discovery.report().complete,
                "One unusable relay does not abort the candidate scan");
        check(
                !discovery.preferred().contains("wss://auth.example"),
                "Unusable relays never enter the live recommendation");
        coordinator.relays = "wss://auth.example";
        discovery = new RelayDiscovery(context, coordinator);
        discovery.scan();
        check(
                !discovery.report().recommendation.canApply
                        && !discovery.report().recommendation.ranked.isEmpty(),
                "A fast new relay cannot replace all unverified configured anchors");
        denied(discovery::preferred);
    }

    public static void main(String[] args) throws Exception {
        boundedAndNoAutomaticConfiguration();
        cancelAndContextChangesDoNotPublish();
        historyIsBoundToNetworkIdentityAndBoot();
        classificationAndMissingAnchor();
        System.out.println("PASS: " + checks + " relay discovery checks.");
    }
}
