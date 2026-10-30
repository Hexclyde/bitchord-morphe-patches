package app.bitchord.soulseek;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Scores the phone's own measurements; geographic lookup is unnecessary. */
final class RelayRanking {
    static final long TTL_MS = 24 * 60 * 60 * 1000L;
    static final int MAX_HISTORY = 4096;

    private RelayRanking() {}

    static final class Observation {
        final String relay, networkTag, status;
        final boolean online, usable;
        final long connectMs, roundtripMs, completedMs, checkedAtMs;
        final int samples;

        private Observation(
                String relay,
                String networkTag,
                String status,
                boolean online,
                boolean usable,
                long connectMs,
                long roundtripMs,
                long completedMs,
                int samples,
                long checkedAtMs) {
            this.relay = relay;
            this.networkTag = networkTag;
            this.status = status;
            this.online = online;
            this.usable = usable;
            this.connectMs = connectMs;
            this.roundtripMs = roundtripMs;
            this.completedMs = completedMs;
            this.samples = samples;
            this.checkedAtMs = checkedAtMs;
        }

        static Observation fromProbe(JSONObject data, String networkTag, long nowMs)
                throws IOException {
            try {
                if (networkTag == null
                        || networkTag.isEmpty()
                        || networkTag.length() > 128
                        || networkTag.chars().anyMatch(Character::isISOControl)
                        || nowMs < 0) throw new IOException("Invalid relay measurement context.");
                String relay = RelayCandidates.canonical(data.getString("relay"));
                String status = data.getString("status");
                if (!status.matches("[a-z_]{1,40}"))
                    throw new IOException("Invalid relay measurement status.");
                Object onlineValue = data.get("online"), usableValue = data.get("usable");
                if (!(onlineValue instanceof Boolean) || !(usableValue instanceof Boolean))
                    throw new IOException("Invalid relay measurement flags.");
                boolean online = (Boolean) onlineValue, usable = (Boolean) usableValue;
                long connect = duration(data, "connect_ms", true);
                long roundtrip = duration(data, "roundtrip_ms", true);
                long completed = duration(data, "completed_ms", false);
                int samples = (int) duration(data, "samples", false);
                if (samples < 0
                        || samples > 3
                        || usable
                                && (!online
                                        || !status.equals("ok")
                                        || connect < 0
                                        || roundtrip < 0
                                        || samples != 3))
                    throw new IOException("Incomplete relay measurement.");
                return new Observation(
                        relay,
                        networkTag,
                        status,
                        online,
                        usable,
                        connect,
                        roundtrip,
                        completed,
                        samples,
                        nowMs);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Invalid relay measurement.");
            }
        }

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("relay", relay)
                    .put("network", networkTag)
                    .put("checked_at", checkedAtMs)
                    .put("status", status)
                    .put("online", online)
                    .put("usable", usable)
                    .put("connect_ms", connectMs)
                    .put("roundtrip_ms", roundtripMs)
                    .put("completed_ms", completedMs)
                    .put("samples", samples);
        }

        static Observation fromJson(JSONObject data) throws IOException {
            try {
                return fromProbe(data, data.getString("network"), data.getLong("checked_at"));
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Invalid saved relay measurement.");
            }
        }

        private static long duration(JSONObject data, String key, boolean unavailable)
                throws Exception {
            Object value = data.get(key);
            if (!(value instanceof Number)) throw new IOException("Invalid relay duration.");
            double number = ((Number) value).doubleValue();
            long result = ((Number) value).longValue();
            if (number != result || result < (unavailable ? -1 : 0) || result > 60_000)
                throw new IOException("Invalid relay duration.");
            return result;
        }
    }

    static final class Recommendation {
        final List<String> ranked, selected;
        final boolean canApply;

        Recommendation(List<String> ranked, List<String> selected) {
            this.ranked = Collections.unmodifiableList(new ArrayList<>(ranked));
            this.selected = Collections.unmodifiableList(new ArrayList<>(selected));
            canApply = !selected.isEmpty();
        }
    }

    static Recommendation recommend(
            List<Observation> history, List<String> configured, String networkTag, long nowMs)
            throws IOException {
        List<String> current = RelayCandidates.merge(configured);
        if (current.size() > 3) throw new IOException("Too many configured relays.");
        List<Observation> useful = new ArrayList<>();
        for (Observation observation : latest(history, networkTag, nowMs).values())
            if (observation.usable) useful.add(observation);
        useful.sort(
                Comparator.comparingLong(RelayRanking::score).thenComparing(value -> value.relay));
        List<String> ranked = new ArrayList<>();
        for (Observation observation : useful) {
            ranked.add(observation.relay);
        }
        List<String> selected = new ArrayList<>();
        for (String relay : current) if (ranked.contains(relay)) selected.add(relay);
        if (!selected.isEmpty()) {
            Set<String> hosts = new HashSet<>();
            for (String relay : selected) hosts.add(URI.create(relay).getHost());
            for (String relay : ranked) {
                if (selected.size() == 3) break;
                if (hosts.add(URI.create(relay).getHost())) selected.add(relay);
            }
        }
        return new Recommendation(ranked, selected);
    }

    static Map<String, Observation> latest(List<Observation> history, String networkTag, long nowMs)
            throws IOException {
        if (history == null
                || history.size() > MAX_HISTORY
                || networkTag == null
                || networkTag.isEmpty()
                || nowMs < 0) throw new IOException("Invalid relay measurement history.");
        Map<String, Observation> latest = new LinkedHashMap<>();
        for (Observation observation : history) {
            if (observation == null) throw new IOException("Invalid relay measurement history.");
            if (!networkTag.equals(observation.networkTag)
                    || observation.checkedAtMs > nowMs
                    || nowMs - observation.checkedAtMs >= TTL_MS) continue;
            Observation old = latest.get(observation.relay);
            if (old == null || observation.checkedAtMs >= old.checkedAtMs)
                latest.put(observation.relay, observation);
        }
        return latest;
    }

    private static long score(Observation value) {
        return value.roundtripMs + (value.connectMs + 3) / 4;
    }
}
