package app.bitchord.soulseek;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Boundary tests use dummy signatures; signature validation belongs to the native tests. */
public final class PeerCacheExchangeTest {
    private static int checks;
    private static final long NOW = 1_000;
    private static final String PEER = key(700), OTHER = key(701), SESSION = token(1);

    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static String key(int number) {
        return String.format("%064x", number);
    }

    static String token(int number) {
        return String.format("%032x", number);
    }

    static JSONObject body(long expires) throws Exception {
        return new JSONObject()
                .put("protocol", "app.bitchord.providers/1")
                .put("version", 1)
                .put("public", true)
                .put("pex", true)
                .put("expires_at", expires)
                .put("transports", new JSONArray().put("direct").put("tor").put("i2p").put("ipfs"));
    }

    static JSONObject record(int number, long created, long expires) throws Exception {
        return new JSONObject()
                .put("id", key(number + 1000))
                .put("pubkey", key(number))
                .put("created_at", created)
                .put("kind", 11120)
                .put(
                        "tags",
                        new JSONArray()
                                .put(new JSONArray().put("t").put("app.bitchord.providers/1"))
                                .put(new JSONArray().put("expiration").put(Long.toString(expires))))
                .put("content", body(expires).toString())
                .put("sig", key(1) + key(2));
    }

    static JSONArray records(int first, int count, long created, long expires) throws Exception {
        JSONArray result = new JSONArray();
        for (int i = 0; i < count; i++) result.put(record(first + i, created, expires));
        return result;
    }

    static void invalid(JSONObject event, String why) throws Exception {
        try {
            PeerDiscovery.Candidate.parse(event, NOW);
            throw new AssertionError(why);
        } catch (java.io.IOException expected) {
            checks++;
        }
    }

    static void profileBoundary() throws Exception {
        PeerDiscovery.Candidate parsed =
                PeerDiscovery.Candidate.parse(record(1, NOW, NOW + 300), NOW);
        check(
                parsed.key.equals(key(1)) && parsed.transports.size() == 4,
                "valid public capabilities accepted");
        invalid(record(1, NOW, NOW + 300).put("kind", 21120), "ephemeral legacy kind rejected");
        invalid(
                record(1, NOW, NOW + 300).put("endpoint", "192.0.2.1"),
                "private top-level fields rejected");
        invalid(
                record(1, NOW, NOW + 300)
                        .put(
                                "content",
                                body(NOW + 300).put("contacts", new JSONArray()).toString()),
                "private body fields rejected");
        invalid(
                record(1, NOW, NOW + 300)
                        .put("content", body(NOW + 300).put("public", false).toString()),
                "private profile rejected");
        invalid(record(1, NOW - 1, NOW), "expired lease rejected");
        invalid(record(1, NOW, NOW + 301), "lease over five minutes rejected");
        invalid(record(1, NOW + 31, NOW + 300), "future-created profile rejected");
        invalid(
                record(1, NOW, NOW + 300)
                        .put(
                                "tags",
                                new JSONArray()
                                        .put(new JSONArray().put("t").put("other"))
                                        .put(new JSONArray().put("expiration").put("1300"))),
                "unrelated discovery topic rejected");
        invalid(
                record(1, NOW, NOW + 300)
                        .put(
                                "content",
                                body(NOW + 300)
                                        .put("transports", new JSONArray().put("tor").put("tor"))
                                        .toString()),
                "duplicate capability rejected");
        invalid(
                record(1, NOW, NOW + 300)
                        .put(
                                "content",
                                body(NOW + 300)
                                        .put(
                                                "transports",
                                                new JSONArray().put("https://private.invalid"))
                                        .toString()),
                "unrecognized endpoint capability rejected");
    }

    static void cacheBounds() throws Exception {
        PeerCandidateCache cache = new PeerCandidateCache();
        check(
                cache.ingest(records(1, 128, NOW, NOW + 300), PEER, "", NOW) == 64,
                "one referrer bounded at 64 distinct keys");
        check(
                cache.ingest(records(129, 128, NOW, NOW + 300), PEER, "", NOW) == 0,
                "further same-referrer profiles refused");
        check(
                cache.ingest(records(129, 128, NOW, NOW + 300), OTHER, "", NOW) == 64,
                "another referrer has separate quota");
        cache.clear();
        for (int i = 0; i < 4; i++)
            check(
                    cache.ingest(records(1 + 128 * i, 128, NOW, NOW + 300), null, "", NOW) == 128,
                    "direct relay scan is not restricted to one referrer's quota");
        check(cache.snapshot(NOW).size() == 512, "global pool reaches 512");
        check(
                cache.ingest(records(600, 1, NOW, NOW + 300), null, "", NOW) == 0,
                "global limit blocks key 513");
        check(
                cache.ingest(records(1, 1, NOW + 1, NOW + 300), PEER, "", NOW + 1) == 1,
                "newer publisher replacement allowed at capacity");
        check(cache.snapshot(NOW + 1).get(0).created == NOW + 1, "newest candidates sort first");
        check(cache.snapshot(NOW + 300).isEmpty(), "expired profiles pruned");
        cache.clear();
        check(
                cache.ingest(records(1, 2, NOW, NOW + 300), null, key(1), NOW) == 1,
                "own identity excluded");
        check(
                cache.ingest(records(2, 1, NOW, NOW + 300), null, "", NOW) == 0,
                "duplicate version ignored");
        check(
                cache.ingest(records(2, 1, NOW - 1, NOW + 298), null, "", NOW) == 0,
                "older live version cannot roll back latest");
        PeerDiscovery.Candidate visible = cache.snapshot(NOW).get(0);
        visible.signed.put("pubkey", key(999));
        visible.signed.put("content", "mutated");
        JSONObject exported = cache.records(NOW, 8).getJSONObject(0);
        check(
                exported.getString("pubkey").equals(key(2)),
                "snapshot mutation cannot rewrite signed identity");
        check(
                new JSONObject(exported.getString("content")).getBoolean("public"),
                "exports retain original signed public body");
        cache.clear();
        cache.ingest(records(3, 1, NOW + 1, NOW + 2), PEER, "", NOW + 1);
        check(cache.snapshot(NOW + 2).isEmpty(), "short newest lease expires");
        check(
                cache.ingest(records(3, 1, NOW, NOW + 300), OTHER, "", NOW + 2) == 0,
                "short expired lease cannot roll back to older long lease");
    }

    static final class Clock implements PeerExchange.Clock {
        long now = 100;

        public long elapsed() {
            return now;
        }
    }

    static final class Message {
        final String peer, session, type;
        final JSONObject payload;

        Message(String peer, String session, String type, JSONObject payload) throws Exception {
            this.peer = peer;
            this.session = session;
            this.type = type;
            this.payload = new JSONObject(payload.toString());
        }
    }

    static final class Fixture implements PeerExchange.Sender, PeerExchange.Records {
        final Clock clock = new Clock();
        final List<Message> sent = new ArrayList<>();
        final PeerExchange exchange = new PeerExchange(clock, this, this);
        JSONArray offered = new JSONArray();
        int ingested;
        long lastContext;
        String lastReferrer;

        public void send(String peer, String session, String type, JSONObject payload)
                throws Exception {
            sent.add(new Message(peer, session, type, payload));
        }

        public JSONArray records(int limit) throws Exception {
            JSONArray result = new JSONArray();
            for (int i = 0; i < Math.min(limit, offered.length()); i++) result.put(offered.get(i));
            return result;
        }

        public void ingest(JSONArray records, String referrer, long generation) {
            ingested += records.length();
            lastContext = generation;
            lastReferrer = referrer;
        }

        void receive(String peer, String session, String type, JSONObject payload)
                throws Exception {
            exchange.receive(peer, session, type, payload, 77);
        }

        void hello(String peer) throws Exception {
            receive(peer, SESSION, "hello", envelope().put("support", 1).put("reply", true));
        }

        int count(String type) {
            int result = 0;
            for (Message m : sent) if (type.equals(m.type)) result++;
            return result;
        }

        Message last(String type) {
            for (int i = sent.size() - 1; i >= 0; i--)
                if (type.equals(sent.get(i).type)) return sent.get(i);
            throw new AssertionError("No " + type);
        }
    }

    static JSONObject envelope() throws Exception {
        return new JSONObject().put("protocol", PeerExchange.PROTOCOL);
    }

    static JSONObject response(Message request, JSONArray records) throws Exception {
        return envelope().put("nonce", request.payload.getString("nonce")).put("records", records);
    }

    static void pexBoundaries() throws Exception {
        Fixture f = new Fixture();
        f.receive(PEER, SESSION, "pex_request", envelope().put("nonce", token(2)));
        check(f.sent.isEmpty(), "no PEX to peer without capability negotiation");
        f.receive(PEER, SESSION, "hello", envelope().put("support", 2).put("reply", true));
        check(f.sent.isEmpty(), "unsupported HELLO version ignored");
        f.receive(PEER, SESSION, "hello", envelope().put("support", 1).put("reply", false));
        check(
                f.count("hello") == 1 && f.count("pex_request") == 1,
                "HELLO negotiates then sends one request");
        Message first = f.last("pex_request");
        JSONArray one = records(1, 1, NOW, NOW + 300);
        f.hello(OTHER);
        f.receive(OTHER, first.session, "pex_response", response(first, one));
        f.receive(PEER, token(42), "pex_response", response(first, one));
        f.receive(
                PEER, first.session, "pex_response", response(first, one).put("nonce", token(42)));
        check(f.ingested == 0, "response requires matching peer, session and nonce");
        f.receive(
                PEER,
                first.session,
                "pex_response",
                response(first, records(1, 9, NOW, NOW + 300)));
        check(f.ingested == 0, "nine-record response rejected without consuming valid request");
        f.receive(PEER, first.session, "pex_response", response(first, one));
        check(
                f.ingested == 1 && f.lastContext == 77 && PEER.equals(f.lastReferrer),
                "valid response keeps caller generation and authenticated referrer");
        f.receive(PEER, first.session, "pex_response", response(first, one));
        check(f.ingested == 1, "response replay consumed once");
        f.hello(PEER);
        check(f.count("pex_request") == 2, "repeat HELLO cannot request again within a minute");
        f.exchange.reset();
        f.hello(PEER);
        check(f.count("pex_request") == 2, "reset cannot bypass per-peer request limit");
        f.clock.now += 60_000;
        f.hello(PEER);
        check(f.count("pex_request") == 3, "new request permitted after one minute");
        Message expiring = f.last("pex_request");
        f.clock.now += 15_000;
        f.receive(PEER, expiring.session, "pex_response", response(expiring, one));
        check(f.ingested == 1, "response expires exactly at 15 seconds");
        Fixture out = new Fixture();
        out.hello(PEER);
        out.offered = records(1, 8, NOW, NOW + 300);
        out.receive(PEER, SESSION, "pex_request", envelope().put("nonce", token(5)));
        Message sent = out.last("pex_response");
        check(
                sent.payload.getJSONArray("records").length() <= 8,
                "response contains at most eight original ads");
        check(
                sent.payload.toString().getBytes(StandardCharsets.UTF_8).length <= 8192,
                "response obeys eight KiB payload budget");
        check(
                sent.session.equals(SESSION) && sent.payload.getString("nonce").equals(token(5)),
                "response is bound to requester session and nonce");
        out.receive(PEER, SESSION, "pex_request", envelope().put("nonce", token(6)));
        check(out.count("pex_response") == 1, "response limited to one per peer each minute");
        out.exchange.reset();
        out.hello(PEER);
        out.receive(PEER, SESSION, "pex_request", envelope().put("nonce", token(7)));
        check(out.count("pex_response") == 1, "reset cannot bypass response rate limit");
        out.clock.now += 60_000;
        out.hello(PEER);
        out.receive(PEER, SESSION, "pex_request", envelope().put("nonce", token(8)));
        check(out.count("pex_response") == 2, "response allowed in next minute");
        Fixture reset = new Fixture();
        reset.hello(PEER);
        Message pending = reset.last("pex_request");
        reset.exchange.reset();
        reset.hello(PEER);
        reset.receive(PEER, pending.session, "pex_response", response(pending, one));
        check(reset.ingested == 0, "reset invalidates pending referral responses");
        Fixture hello = new Fixture();
        hello.exchange.hello(PEER);
        hello.exchange.hello(PEER);
        hello.exchange.reset();
        hello.exchange.hello(PEER);
        check(hello.count("hello") == 1, "outbound HELLO rate survives reset");
        hello.clock.now += 60_000;
        hello.exchange.hello(PEER);
        check(hello.count("hello") == 2, "outbound HELLO allowed after minute");
    }

    public static void main(String[] args) throws Exception {
        profileBoundary();
        cacheBounds();
        pexBoundaries();
        System.out.println("Provider cache and PEX: " + checks + " assertions passed (no network)");
    }
}
