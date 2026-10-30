package app.bitchord.soulseek;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

public final class RelayPolicyTest {
    private static int checks;
    private static final String NETWORK = "test-network";
    private static final long NOW = 2_000_000_000L;
    private static final String A = "wss://a.example.com", B = "wss://b.example.com";

    private interface Checked {
        void run() throws Exception;
    }

    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static void reject(Checked action, String label) throws Exception {
        checks++;
        try {
            action.run();
            throw new AssertionError(label);
        } catch (IOException expected) {
        }
    }

    private static JSONObject result(String relay, int rtt, int connect, boolean usable)
            throws Exception {
        return new JSONObject()
                .put("relay", relay)
                .put("status", usable ? "ok" : "write_rejected")
                .put("online", true)
                .put("usable", usable)
                .put("connect_ms", connect)
                .put("roundtrip_ms", usable ? rtt : -1)
                .put("completed_ms", 1000)
                .put("samples", usable ? 3 : 0);
    }

    private static RelayRanking.Observation measured(String relay, int rtt, long at)
            throws Exception {
        return RelayRanking.Observation.fromProbe(result(relay, rtt, 40, true), NETWORK, at);
    }

    private static void candidates() throws Exception {
        List<String> candidates =
                RelayCandidates.parse(
                        "# source\nWSS://relay.example.com:443/\r\n"
                                + "wss://relay.example.com\nwss://other.example.com/path\n"
                                + "stun:stun.example.com:3478\nws://plain.example.com\n"
                                + "wss://private.onion\nwss://router.i2p\nwss://127.0.0.1\n"
                                + "wss://relay.example.com:8443\nwss://u:p@relay.example.com\n");
        check(
                candidates.equals(
                        Arrays.asList("wss://relay.example.com", "wss://other.example.com/path")),
                "pool canonicalizes and excludes incompatible protocols");
        check(
                RelayCandidates.merge(candidates, candidates).size() == 2,
                "pool deduplicates imports");
        List<String> loaded =
                RelayCandidates.load(
                        new ByteArrayInputStream(
                                "wss://custom.example.com\n".getBytes(StandardCharsets.UTF_8)));
        check(
                loaded.containsAll(RelayCandidates.DEFAULTS) && loaded.size() == 3,
                "bundled pool retains original candidates");
        checks++;
        try {
            loaded.add(A);
            throw new AssertionError("mutable pool");
        } catch (UnsupportedOperationException expected) {
        }
        for (String bad :
                Arrays.asList(
                        "stun:host:3478",
                        "https://a.example.com",
                        "wss://a.example.com?q=x",
                        "wss://x.onion",
                        "wss://x.i2p",
                        "wss://relay.local",
                        "wss://a.example.com\nwss://b.example.com"))
            reject(() -> RelayCandidates.canonical(bad), "unsafe address admitted");
        reject(
                () -> RelayCandidates.parse(String.join("", Collections.nCopies(262145, "x"))),
                "oversized text admitted");
        reject(
                () -> RelayCandidates.parse(String.join("", Collections.nCopies(131073, "λ"))),
                "oversized UTF8 admitted");
        reject(
                () -> RelayCandidates.parse(String.join("", Collections.nCopies(4097, "\n"))),
                "excessive lines admitted");
        reject(
                () ->
                        RelayCandidates.load(
                                new ByteArrayInputStream(new byte[] {(byte) 0xc0, (byte) 0xaf})),
                "malformed UTF8 admitted");
        reject(() -> RelayCandidates.load(null), "missing asset admitted");
    }

    private static void rankings() throws Exception {
        List<RelayRanking.Observation> history =
                Arrays.asList(
                        measured(A, 300, NOW),
                        measured(B, 500, NOW),
                        measured("wss://fast.example.com", 10, NOW),
                        measured("wss://second.example.com", 20, NOW));
        List<String> configured = Arrays.asList(A, B);
        RelayRanking.Recommendation first =
                RelayRanking.recommend(history, configured, NETWORK, NOW);
        check(first.ranked.get(0).equals("wss://fast.example.com"), "measured RTT drives ranking");
        check(
                first.canApply
                        && first.selected.size() == 3
                        && first.selected.containsAll(configured),
                "selection retains all healthy shared relays");
        List<RelayRanking.Observation> differentLocation =
                Arrays.asList(
                        measured(A, 500, NOW),
                        measured(B, 50, NOW),
                        measured("wss://elsewhere.example.com", 5, NOW));
        check(
                RelayRanking.recommend(differentLocation, configured, NETWORK, NOW)
                        .selected
                        .containsAll(configured),
                "different user RTTs preserve common anchors");
        List<String> three = Arrays.asList(A, B, "wss://third.example.com");
        List<RelayRanking.Observation> full = new ArrayList<>(history);
        full.add(measured(three.get(2), 800, NOW));
        check(
                RelayRanking.recommend(full, three, NETWORK, NOW).selected.equals(three),
                "three healthy shared relays are never silently displaced");
        RelayRanking.Recommendation unpaired =
                RelayRanking.recommend(
                        history,
                        Collections.singletonList("wss://unmeasured.example.com"),
                        NETWORK,
                        NOW);
        check(
                !unpaired.canApply && unpaired.selected.isEmpty() && !unpaired.ranked.isEmpty(),
                "fast unshared candidates cannot silently replace bootstrap relays");
        List<RelayRanking.Observation> stale =
                Arrays.asList(
                        measured(A, 1, NOW - RelayRanking.TTL_MS),
                        measured(B, 2, NOW + 1),
                        RelayRanking.Observation.fromProbe(
                                result("wss://foreign.example.com", 1, 1, true), "other", NOW));
        check(
                RelayRanking.recommend(stale, configured, NETWORK, NOW).ranked.isEmpty(),
                "stale future and foreign network results excluded");
        List<RelayRanking.Observation> failed = new ArrayList<>(history);
        failed.add(RelayRanking.Observation.fromProbe(result(A, 0, 100, false), NETWORK, NOW + 1));
        RelayRanking.Recommendation afterFailure =
                RelayRanking.recommend(failed, configured, NETWORK, NOW + 1);
        check(
                !afterFailure.ranked.contains(A) && afterFailure.selected.contains(B),
                "new failure supersedes cached success");
        JSONObject roundtrip = history.get(0).toJson();
        RelayRanking.Observation restored = RelayRanking.Observation.fromJson(roundtrip);
        check(
                restored.relay.equals(A)
                        && restored.checkedAtMs == NOW
                        && restored.roundtripMs == 300,
                "cache preserves measurement identity and timestamp");
        for (JSONObject bad :
                Arrays.asList(
                        result(A, 1, 1, true).put("samples", 1),
                        result(A, 1, 1, true).put("samples", 3.5),
                        result(A, 1, 1, true).put("roundtrip_ms", -1),
                        result(A, 1, 1, true).put("connect_ms", 1.5),
                        result(A, 1, 1, true).put("online", false),
                        result(A, 1, 1, true).put("online", "true"),
                        result(A, 1, 1, true).put("status", "remote error with metadata")))
            reject(
                    () -> RelayRanking.Observation.fromProbe(bad, NETWORK, NOW),
                    "invalid measurement admitted");
        reject(
                () -> RelayRanking.Observation.fromProbe(result(A, 1, 1, true), "", NOW),
                "missing network identity admitted");
        reject(
                () ->
                        RelayRanking.recommend(
                                Collections.nCopies(4097, history.get(0)),
                                configured,
                                NETWORK,
                                NOW),
                "oversized history admitted");
        checks++;
        try {
            first.selected.clear();
            throw new AssertionError("mutable recommendation");
        } catch (UnsupportedOperationException expected) {
        }
    }

    private static void exploration() throws Exception {
        List<String> pool = new ArrayList<>();
        List<RelayRanking.Observation> prior = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String relay = "wss://relay" + i + ".example.com";
            pool.add(relay);
            if (i < 24) prior.add(measured(relay, i + 1, NOW));
        }
        List<String> configured = Arrays.asList(A, B);
        List<String> sample =
                RelayCandidates.sample(pool, configured, prior, NETWORK, NOW, new Random(1), 24);
        check(
                sample.size() == 24 && sample.subList(0, 2).equals(configured),
                "bounded scan includes current shared relays first");
        int unexplored = 0;
        HashSet<String> old = new HashSet<>();
        for (RelayRanking.Observation observation : prior) old.add(observation.relay);
        for (String relay : sample)
            if (!old.contains(relay) && !configured.contains(relay)) unexplored++;
        check(unexplored > 0, "previous successes cannot prevent candidate exploration");
        check(
                !sample.equals(
                        RelayCandidates.sample(
                                pool, configured, prior, NETWORK, NOW, new Random(2), 24)),
                "successive scan seeds rotate unexplored candidates");
        prior.add(
                RelayRanking.Observation.fromProbe(
                        result(pool.get(99), 0, 1, false), NETWORK, NOW));
        check(
                !RelayCandidates.sample(
                                Collections.singletonList(pool.get(99)),
                                Collections.emptyList(),
                                prior,
                                NETWORK,
                                NOW,
                                new Random(1),
                                24)
                        .contains(pool.get(99)),
                "recent failures have a scan cooldown");
        check(
                RelayCandidates.sample(
                                Collections.singletonList(pool.get(99)),
                                Collections.singletonList(pool.get(99)),
                                prior,
                                NETWORK,
                                NOW,
                                new Random(1),
                                24)
                        .contains(pool.get(99)),
                "configured failed relay is rechecked for common connectivity");
        reject(
                () ->
                        RelayCandidates.sample(
                                pool, configured, prior, NETWORK, NOW, new Random(), 1),
                "scan omits current relays");
        reject(
                () ->
                        RelayCandidates.sample(
                                pool, configured, prior, NETWORK, NOW, new Random(), 25),
                "oversized scan admitted");
        checks++;
        try {
            sample.add(A);
            throw new AssertionError("mutable sample");
        } catch (UnsupportedOperationException expected) {
        }
    }

    public static void main(String[] args) throws Exception {
        candidates();
        rankings();
        exploration();
        List<String> bundled = RelayCandidates.load(Files.newInputStream(Paths.get(args[0])));
        check(
                bundled.size() > 1000 && bundled.size() <= 4096,
                "full validated source pool is bundled");
        check(
                bundled.containsAll(RelayCandidates.DEFAULTS),
                "bundled list includes original relay defaults");
        for (String relay : bundled)
            check(RelayCandidates.canonical(relay).equals(relay), "canonical bundled endpoint");
        System.out.println("PASS: " + checks + " relay candidate and ranking checks.");
    }
}
