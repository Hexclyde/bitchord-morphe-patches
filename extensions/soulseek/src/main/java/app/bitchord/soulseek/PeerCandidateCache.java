package app.bitchord.soulseek;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Only cryptographically verified public advertisements may enter this bounded pool. */
final class PeerCandidateCache {
    static final int MAX_CANDIDATES = 512;
    static final int MAX_PER_REFERRER = 64;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Long> newest = new HashMap<>();

    private static final class Entry {
        final PeerDiscovery.Candidate candidate;
        final String referrer;

        Entry(PeerDiscovery.Candidate candidate, String referrer) {
            this.candidate = candidate;
            this.referrer = referrer;
        }
    }

    synchronized int ingest(JSONArray verified, String referrer, String ownKey, long now)
            throws Exception {
        if (verified.length() > 128) throw new IOException("Too many provider records.");
        expire(now);
        int added = 0;
        for (int i = 0; i < verified.length(); i++) {
            PeerDiscovery.Candidate candidate;
            try {
                candidate = PeerDiscovery.Candidate.parse(verified.getJSONObject(i), now);
            } catch (Exception invalid) {
                continue;
            }
            if (candidate.key.equals(ownKey)) continue;
            Long latest = newest.get(candidate.key);
            if (latest != null && candidate.created <= latest) continue;
            Entry old = entries.get(candidate.key);
            // A publisher's own relay announcement is not a third-party referral. Paired
            // PEX senders, on the other hand, are limited to 64 distinct candidate keys.
            String source = old != null ? old.referrer : referrer;
            if (old == null && referrer != null && referredBy(referrer) >= MAX_PER_REFERRER)
                continue;
            if (old == null && entries.size() >= MAX_CANDIDATES) continue;
            entries.put(candidate.key, new Entry(candidate, source));
            newest.put(candidate.key, candidate.created);
            added++;
        }
        return added;
    }

    private int referredBy(String source) {
        int count = 0;
        for (Entry entry : entries.values()) if (source.equals(entry.referrer)) count++;
        return count;
    }

    synchronized List<PeerDiscovery.Candidate> snapshot(long now) {
        expire(now);
        List<PeerDiscovery.Candidate> result = new ArrayList<>();
        for (Entry entry : entries.values()) result.add(entry.candidate.copy());
        result.sort(Comparator.comparingLong((PeerDiscovery.Candidate c) -> c.created).reversed());
        return Collections.unmodifiableList(result);
    }

    synchronized JSONArray records(long now, int limit) throws Exception {
        JSONArray result = new JSONArray();
        for (PeerDiscovery.Candidate candidate : snapshot(now)) {
            if (result.length() >= limit) break;
            result.put(new JSONObject(candidate.original));
        }
        return result;
    }

    synchronized void clear() {
        entries.clear();
        newest.clear();
    }

    private void expire(long now) {
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) if (iterator.next().candidate.expires <= now) iterator.remove();
        Iterator<Map.Entry<String, Long>> seen = newest.entrySet().iterator();
        while (seen.hasNext()) if (seen.next().getValue() + 300 <= now) seen.remove();
        // Expired short leases cannot roll back to an older, still-live signed record.
        // Bound this history even if one referrer supplies many rapidly expired keys.
        if (newest.size() > 1024) {
            Iterator<String> keys = newest.keySet().iterator();
            while (newest.size() > 1024 && keys.hasNext()) {
                keys.next();
                keys.remove();
            }
        }
    }
}
