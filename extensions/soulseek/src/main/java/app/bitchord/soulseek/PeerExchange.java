package app.bitchord.soulseek;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Capability-negotiated PEX over the existing authenticated, paired-only transport. */
final class PeerExchange {
    static final String PROTOCOL = "app.bitchord.pex/1";

    interface Clock {
        long elapsed();
    }

    interface Sender {
        void send(String peer, String session, String type, JSONObject payload) throws Exception;
    }

    interface Records {
        JSONArray records(int limit) throws Exception;

        void ingest(JSONArray records, String referrer, long contextGeneration) throws Exception;
    }

    private final Clock clock;
    private final Sender sender;
    private final Records records;
    private final Map<String, Long> capabilities = new HashMap<>(),
            requested = new HashMap<>(),
            responded = new HashMap<>();
    private final Map<String, Pending> pending = new HashMap<>();
    private final Map<String, Long> announced = new HashMap<>(), helloReplied = new HashMap<>();
    private long generation;

    private static final class Pending {
        final String peer, nonce;
        final long until;

        Pending(String peer, String nonce, long until) {
            this.peer = peer;
            this.nonce = nonce;
            this.until = until;
        }
    }

    PeerExchange(Clock clock, Sender sender, Records records) {
        this.clock = clock;
        this.sender = sender;
        this.records = records;
    }

    synchronized void reset() {
        generation++;
        capabilities.clear();
        pending.clear();
        expire(clock.elapsed());
    }

    void hello(String peer) throws Exception {
        final long intent;
        synchronized (this) {
            long now = clock.elapsed();
            expire(now);
            Long last = announced.get(peer);
            if (last != null && now - last < 60_000) return;
            if (announced.size() >= 16 && !announced.containsKey(peer)) return;
            announced.put(peer, now);
            intent = generation;
        }
        send(intent, peer, token(), "hello", envelope().put("support", 1).put("reply", false));
    }

    void receive(
            String peer, String session, String type, JSONObject payload, long contextGeneration)
            throws Exception {
        if (!PROTOCOL.equals(payload.optString("protocol"))) return;
        if (!peer.matches("[0-9a-f]{64}") || !session.matches("[0-9a-f]{32}")) return;
        if (payload.toString().getBytes(StandardCharsets.UTF_8).length > 8192) return;
        long now = clock.elapsed();
        final long intent;
        synchronized (this) {
            intent = generation;
            expire(now);
        }
        if ("hello".equals(type)) {
            if (payload.optInt("support") != 1
                    || payload.length() != 3
                    || !(payload.opt("reply") instanceof Boolean)) return;
            synchronized (this) {
                if (intent != generation
                        || (capabilities.size() >= 16 && !capabilities.containsKey(peer))) return;
                capabilities.put(peer, now + 300_000);
            }
            boolean reply;
            synchronized (this) {
                reply = !payload.getBoolean("reply") && !helloReplied.containsKey(peer);
                if (reply) helloReplied.put(peer, now);
            }
            if (reply)
                send(
                        intent,
                        peer,
                        session,
                        "hello",
                        envelope().put("support", 1).put("reply", true));
            request(peer, intent, now);
            return;
        }
        synchronized (this) {
            Long supported = capabilities.get(peer);
            if (intent != generation || supported == null || supported <= now) return;
        }
        String nonce = payload.optString("nonce");
        if (!nonce.matches("[0-9a-f]{32}")) return;
        if ("pex_request".equals(type)) {
            if (payload.length() != 2) return;
            synchronized (this) {
                Long last = responded.get(peer);
                if (intent != generation || last != null && now - last < 60_000) return;
                responded.put(peer, now);
            }
            JSONArray offered = records.records(8);
            while (offered.length() > 8) offered.remove(offered.length() - 1);
            JSONObject response = envelope().put("nonce", nonce).put("records", offered);
            while (response.toString().getBytes(StandardCharsets.UTF_8).length > 8192
                    && offered.length() > 0) offered.remove(offered.length() - 1);
            synchronized (this) {
                if (intent != generation) return;
            }
            send(intent, peer, session, "pex_response", response);
        } else if ("pex_response".equals(type)) {
            if (payload.length() != 3) return;
            JSONArray offered = payload.optJSONArray("records");
            if (offered == null || offered.length() > 8) return;
            synchronized (this) {
                Pending wanted = pending.get(session);
                if (intent != generation
                        || wanted == null
                        || !wanted.peer.equals(peer)
                        || !wanted.nonce.equals(nonce)
                        || wanted.until <= now) return;
                pending.remove(session);
            }
            records.ingest(offered, peer, contextGeneration);
        }
    }

    private void request(String peer, long intent, long now) throws Exception {
        String session = token(), nonce = token();
        synchronized (this) {
            Long last = requested.get(peer);
            if (intent != generation || last != null && now - last < 60_000 || pending.size() >= 16)
                return;
            requested.put(peer, now);
            pending.put(session, new Pending(peer, nonce, now + 15_000));
        }
        try {
            send(intent, peer, session, "pex_request", envelope().put("nonce", nonce));
        } catch (Exception failed) {
            synchronized (this) {
                pending.remove(session);
            }
            throw failed;
        }
    }

    private void expire(long now) {
        capabilities.values().removeIf(expiry -> expiry <= now);
        pending.values().removeIf(value -> value.until <= now);
        requested.values().removeIf(last -> now - last >= 60_000);
        responded.values().removeIf(last -> now - last >= 60_000);
        announced.values().removeIf(last -> now - last >= 60_000);
        helloReplied.values().removeIf(last -> now - last >= 60_000);
    }

    private synchronized void send(
            long intent, String peer, String session, String type, JSONObject payload)
            throws Exception {
        // reset waits for an already-started local send, and no stale queued send can
        // start after reset returns. This never opens another transport or pairs a key.
        if (intent == generation) sender.send(peer, session, type, payload);
    }

    private static JSONObject envelope() throws Exception {
        return new JSONObject().put("protocol", PROTOCOL);
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
