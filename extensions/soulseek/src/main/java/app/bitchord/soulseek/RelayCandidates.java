package app.bitchord.soulseek;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Candidate discovery is separate from the three live coordination relays. */
final class RelayCandidates {
    static final String ASSET_PATH = "soulseek-native/nostr-relays.txt";
    static final String SOURCE_URL =
            "https://github.com/sesseor/nostr-relays-list/blob/main/relays.txt";
    static final int MAX_BYTES = 256 * 1024;
    static final int MAX_CANDIDATES = 4096;
    static final int MAX_PROBES = 24;
    static final long FAILURE_COOLDOWN_MS = 5 * 60 * 1000L;
    static final List<String> DEFAULTS =
            Collections.unmodifiableList(Arrays.asList("wss://relay.damus.io", "wss://nos.lol"));

    private RelayCandidates() {}

    static List<String> load(InputStream input) throws IOException {
        if (input == null) throw new IOException("Relay candidate list is unavailable.");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > MAX_BYTES)
                throw new IOException("Relay candidate list exceeds its import limit.");
            output.write(buffer, 0, count);
        }
        String text =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(output.toByteArray()))
                        .toString();
        return merge(DEFAULTS, parse(text));
    }

    static List<String> parse(String text) throws IOException {
        if (text == null
                || text.length() > MAX_BYTES
                || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IOException("Relay candidate list exceeds its import limit.");
        Set<String> result = new LinkedHashSet<>();
        int start = 0, lines = 0;
        while (start < text.length()) {
            if (++lines > MAX_CANDIDATES)
                throw new IOException("Relay candidate list has too many lines.");
            int end = text.indexOf('\n', start);
            if (end < 0) end = text.length();
            if (end - start <= 514) {
                String line = text.substring(start, end).trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    try {
                        result.add(canonical(line));
                    } catch (IOException ignored) {
                        // Unsupported addresses stay out of the public WSS pool.
                    }
                }
            }
            start = end + 1;
        }
        return immutable(result);
    }

    @SafeVarargs
    static List<String> merge(List<String>... lists) throws IOException {
        Set<String> result = new LinkedHashSet<>();
        for (List<String> list : lists) {
            if (list == null || list.size() > MAX_CANDIDATES)
                throw new IOException("Invalid relay candidate collection.");
            for (String value : list) {
                result.add(canonical(value));
                if (result.size() > MAX_CANDIDATES)
                    throw new IOException("Too many relay candidates.");
            }
        }
        return immutable(result);
    }

    static String canonical(String value) throws IOException {
        if (value == null || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw new IOException("Invalid public relay address.");
        List<String> validated = NostrConfig.relays(value);
        if (validated.size() != 1) throw new IOException("Choose one public relay address.");
        String result = validated.get(0);
        if (result.length() > 512) throw new IOException("A relay address is too long.");
        String host = URI.create(result).getHost();
        if (host.endsWith(".onion") || host.endsWith(".i2p"))
            throw new IOException("Overlay relay addresses require their own transport.");
        return result;
    }

    static List<String> sample(
            List<String> pool,
            List<String> configured,
            List<RelayRanking.Observation> history,
            String networkTag,
            long nowMs,
            Random random,
            int limit)
            throws IOException {
        if (limit < 1 || limit > MAX_PROBES || random == null)
            throw new IOException("Invalid relay scan limit.");
        List<String> all = merge(pool, configured);
        List<String> current = merge(configured);
        if (current.size() > 3) throw new IOException("Too many configured relays.");
        if (current.size() > limit) throw new IOException("Scan must include configured relays.");
        Map<String, RelayRanking.Observation> latest =
                RelayRanking.latest(history, networkTag, nowMs);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Set<String> hosts = new HashSet<>();
        for (String value : current) add(result, hosts, value, limit, true);
        int historicalSlots = Math.min(12, (limit - current.size()) / 2);
        int historicalEnd = current.size() + historicalSlots;
        for (String value : RelayRanking.recommend(history, configured, networkTag, nowMs).ranked) {
            if (result.size() >= historicalEnd) break;
            add(result, hosts, value, limit, false);
        }
        List<String> shuffled = new ArrayList<>(all);
        Collections.shuffle(shuffled, random);
        for (String value : shuffled) {
            RelayRanking.Observation previous = latest.get(value);
            if (previous != null
                    && !previous.usable
                    && nowMs - previous.checkedAtMs < FAILURE_COOLDOWN_MS) continue;
            add(result, hosts, value, limit, false);
        }
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    private static void add(
            Set<String> values, Set<String> hosts, String value, int limit, boolean configured) {
        if (values.size() >= limit || values.contains(value)) return;
        String host = URI.create(value).getHost();
        if (configured || !hosts.contains(host)) {
            values.add(value);
            hosts.add(host);
        }
    }

    private static List<String> immutable(Set<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
