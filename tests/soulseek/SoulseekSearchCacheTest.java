package app.bitchord.soulseek;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SoulseekSearchCacheTest {
    static int checks;

    static final class Clock implements SoulseekSearchCache.Clock {
        long elapsed;

        public long now() {
            return elapsed;
        }
    }

    static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }

    static SoulseekTrack track(String path) throws Exception {
        return new SoulseekTrack(
                new JSONObject().put("peer", "peer").put("path", path).put("size", 1000));
    }

    public static void main(String[] args) throws Exception {
        Clock clock = new Clock();
        SoulseekSearchCache cache = new SoulseekSearchCache(clock);
        String scope = "session-1:handle-1:FLAC,MP3:configured";
        SoulseekTrack track = track("Artist - Song.flac");
        List<SoulseekTrack> one = Collections.singletonList(track);
        cache.put(scope, " Artist Song ", one);
        check(cache.get(scope, "artist song").get(0) == track, "case and trim normalized in key");
        cache.get(scope, "artist song").clear();
        check(cache.get(scope, "artist song").size() == 1, "returned list cannot mutate cache");
        clock.elapsed = SoulseekSearchCache.TTL_NANOS - 1;
        check(cache.get(scope, "artist song") != null, "batch remains inside 30-second TTL");
        clock.elapsed++;
        check(cache.get(scope, "artist song") == null, "TTL uses age, not last access time");
        cache.put(scope, "query", one);
        check(
                cache.get("session-2:handle-1:FLAC,MP3:configured", "query") == null,
                "session change invalidates batch");
        cache.put(scope, "query", one);
        check(
                cache.get("session-1:handle-2:FLAC,MP3:configured", "query") == null,
                "native handle change invalidates batch");
        cache.put(scope, "query", one);
        check(
                cache.get("session-1:handle-1:MP3,FLAC:configured", "query") == null,
                "format enablement and order belong to policy scope");
        check(cache.get(scope, "query") == null, "switching back cannot revive discarded scope");
        cache.put(scope, "query", one);
        cache.clear();
        check(cache.get(scope, "query") == null, "sign-out or reconnect explicitly clears cache");
        for (int i = 0; i < 16; i++) cache.put(scope, "q" + i, one);
        cache.get(scope, "q0");
        cache.put(scope, "q16", one);
        check(
                cache.get(scope, "q1") == null && cache.get(scope, "q0") != null,
                "query limit evicts least recently used whole batch");
        cache.clear();
        List<SoulseekTrack> many = new ArrayList<>(Collections.nCopies(250, track));
        cache.put(scope, "many", many);
        check(cache.get(scope, "many").size() == 200, "row cap is 200 per query");
        for (int i = 0; i < 6; i++) cache.put(scope, "batch" + i, many);
        check(
                cache.get(scope, "many") == null && cache.get(scope, "batch5") != null,
                "aggregate row cap evicts oldest batch above 1200 rows");
        cache.clear();
        String longName = new String(new char[3980]).replace('\0', 'x') + ".flac";
        List<SoulseekTrack> heavy = Collections.nCopies(200, track(longName));
        cache.put(scope, "heavy0", heavy);
        cache.put(scope, "heavy1", heavy);
        cache.put(scope, "heavy2", heavy);
        check(
                cache.get(scope, "heavy0") == null && cache.get(scope, "heavy2") != null,
                "estimated metadata budget evicts before aggregate row cap");
        cache.put(scope, "empty", Collections.emptyList());
        check(cache.get(scope, "empty") == null, "no-hit searches can be retried immediately");
        cache.put(scope, "query", one);
        clock.elapsed--;
        check(cache.get(scope, "query") == null, "invalid backward clock invalidates batch");
        System.out.println("PASS: " + checks + " bounded session/policy search cache checks.");
    }
}
