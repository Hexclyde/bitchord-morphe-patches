package app.bitchord.telegram;

import org.json.JSONObject;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class SearchDiscoveryRegressionTest {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Set<Long> chats = Collections.singleton(-12L);
        JSONObject song =
                new JSONObject().put("title", "Drive Home").put("artist", "Steven Wilson");
        List<JSONObject> songs = Collections.singletonList(song);
        AtomicInteger calls = new AtomicInteger();
        {
            LiveSearch.Result r =
                    SearchPlan.run(
                            "drive home",
                            false,
                            chats,
                            q -> {
                                calls.incrementAndGet();
                                return new LiveSearch.Result(songs, "");
                            },
                            (q, requireComplete) -> {
                                throw new AssertionError("Index must not delay live success");
                            },
                            null);
            check(r.tracks.size() == 1, "Live search succeeds independently of index coverage");
        }
        check(calls.get() == 1, "Each foreground discovery reaches live path");
        LiveSearch.Result fallback =
                SearchPlan.run(
                        "drive home",
                        false,
                        chats,
                        q -> {
                            throw new IOException("offline");
                        },
                        (q, complete) -> songs,
                        null);
        check(
                fallback.tracks.size() == 1
                        && fallback.report.origin == SearchReport.Origin.INDEX_FALLBACK,
                "Offline live search uses partial saved index");
        check(
                fallback.report.cause.equals("error"),
                "Failed search is distinguished from zero matches");
        List<String> queries = new ArrayList<>();
        LiveSearch.Result broadened =
                SearchPlan.run(
                        "drive home steven wilson",
                        false,
                        chats,
                        q -> {
                            queries.add(q);
                            return new LiveSearch.Result(
                                    q.equals("drive home") ? songs : Collections.emptyList(), "");
                        },
                        (q, complete) -> null,
                        null);
        check(queries.size() <= 3, "Broader live queries remain bounded");
        check(broadened.tracks.size() == 1, "A title search finds a tagged album audio message");
        AtomicLong time = new AtomicLong(1000);
        SearchPlan.Misses misses = new SearchPlan.Misses(time::get, 100);
        misses.scope(1, chats);
        misses.add("drive home");
        check(misses.known("Drive Home"), "Misses normalize words");
        misses.scope(2, chats);
        check(!misses.known("drive home"), "Catalogue additions invalidate misses");
        misses.add("drive home");
        misses.scope(2, Collections.singleton(-13L));
        check(!misses.known("drive home"), "Selected channel changes invalidate misses");
        misses.add("drive home");
        time.addAndGet(100);
        check(!misses.known("drive home"), "Miss expiry permits another lookup");
        String scope = SearchReport.scoped(chats).line();
        check(!scope.contains("-12"), "Scope diagnostics do not expose private channel IDs");
        System.out.println("PASS: " + checks + " live discovery regression checks");
    }
}
