package app.bitchord.telegram;

import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;

import java.io.IOException;
import java.util.*;

/**
 * Why a post is left out of a search, what the diagnostics say, and that a failure is not a zero.
 */
public final class SearchReportTest {
    static int passed;

    static void check(boolean okay, String message) {
        passed++;
        if (!okay) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        // Reasons a post is not a song.
        check(
                TrackMetadata.rejection(TestMessages.audio(-5, 1, "Song")).isEmpty(),
                "audio message accepted");
        check(
                TrackMetadata.rejection(TestMessages.document(-5, 2, "Drive Home.flac")).isEmpty(),
                "flac document accepted");
        check(
                TrackMetadata.rejection(TestMessages.document(-5, 3, "Album.zip"))
                        .equals("archive-or-cue"),
                "zip named apart");
        check(
                TrackMetadata.rejection(TestMessages.document(-5, 4, "Album.cue"))
                        .equals("archive-or-cue"),
                "cue named apart");
        check(
                TrackMetadata.rejection(TestMessages.document(-5, 5, "notes.pdf"))
                        .equals("other-file-type"),
                "pdf is another type");
        check(TrackMetadata.rejection(null).equals("invalid"), "missing post");
        TdApi.Message noName = TestMessages.document(-5, 6, "Track");
        ((TdApi.MessageDocument) noName.content).document.mimeType = "audio/flac";
        check(
                TrackMetadata.rejection(noName).isEmpty() && TrackMetadata.from(noName) != null,
                "audio type stands in for a missing extension");
        TdApi.Message named = TestMessages.document(-5, 7, "Track.bin");
        ((TdApi.MessageDocument) named.content).document.mimeType = "audio/flac";
        check(
                !TrackMetadata.rejection(named).isEmpty(),
                "a different extension is not rescued by the type");
        TdApi.Message protectedPost = TestMessages.audio(-5, 8, "Song");
        protectedPost.canBeSaved = false;
        check(
                TrackMetadata.rejection(protectedPost).equals("protected"),
                "protected content named");

        // The scope names the chats by a hash, never by id, and tells different sets apart.
        Set<Long> one = new TreeSet<>(Arrays.asList(-1001234567L));
        Set<Long> two = new TreeSet<>(Arrays.asList(-1001234567L, -1007654321L));
        String a = SearchReport.scopeOf(one), b = SearchReport.scopeOf(two);
        check(
                !a.equals(b) && !a.contains("1234567") && !b.contains("7654321"),
                "scope: " + a + " / " + b);
        check(a.equals(SearchReport.scopeOf(one)), "scope is stable");

        // A failed alternate search is not a successful zero.
        Set<Long> chats = Collections.singleton(-12L);
        List<String> asked = new ArrayList<>();
        LiveSearch.Result partial =
                SearchPlan.run(
                        "drive home steven wilson",
                        false,
                        chats,
                        q -> {
                            asked.add(q);
                            if (asked.size() > 1) throw new IOException("offline");
                            return new LiveSearch.Result(Collections.<JSONObject>emptyList(), "");
                        },
                        (q, complete) -> null,
                        null);
        check(
                partial.tracks.isEmpty() && LiveSearch.PARTIAL.equals(partial.warning),
                "partial answer is flagged, not a clean zero");
        check(
                partial.report.line().contains("search-"),
                "and the cause is in the diagnostics: " + partial.report.line());

        // The saved index after a failure keeps the cause and the scope.
        LiveSearch.Result fallback =
                SearchPlan.run(
                        "x y",
                        false,
                        chats,
                        q -> {
                            throw new LiveSearch.RateLimited(30);
                        },
                        (q, complete) ->
                                Collections.singletonList(new JSONObject().put("title", "x y")),
                        null);
        String line = fallback.report.line();
        check(
                line.contains("index-fallback")
                        && line.contains("rate-limit")
                        && !line.contains("scope none"),
                "fallback: " + line);
        check(!line.contains("x y"), "the query is not logged");

        // A cache answer says so.
        check(fallback.report.fromCache().line().contains("cached"), "cache origin shown");

        System.out.println("PASS: " + passed + " search diagnostics and partial-answer checks.");
    }
}
