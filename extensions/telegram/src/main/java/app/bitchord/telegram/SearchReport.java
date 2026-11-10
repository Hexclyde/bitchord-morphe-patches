package app.bitchord.telegram;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * What one search did, for the log: where the answer came from, how many posts Telegram returned,
 * how many were songs and why the others weren't. It holds counts and fixed names only, never the
 * search text, a file name, an account detail or a credential.
 */
final class SearchReport {
    enum Origin {
        /** Telegram answered the query as typed. */
        LIVE,
        /** Telegram answered a shorter query, filtered here by every word of the typed one. */
        LIVE_BROADENED,
        /** Telegram couldn't answer (see {@code cause}); the saved index did. */
        INDEX_FALLBACK,
        /** Telegram found no song; the saved index had some. */
        INDEX_AFTER_EMPTY,
        /** A background lookup answered from a finished saved index. */
        INDEX_BACKGROUND,
        NONE
    }

    static final SearchReport NONE =
            new SearchReport(Origin.NONE, "", "", false, 0, 0, 0, new TreeMap<>());

    final Origin origin;

    /** "rate-limit", "busy", "timeout" or "error" for a fallback; otherwise "". */
    final String cause;

    /**
     * The selected chats as a count and short hashes, enough to compare scopes, not to read ids.
     */
    final String scope;

    /** True when the answer came from the short-lived result cache, not a new Telegram request. */
    final boolean cached;

    /** How many chats were selected for the search. */
    final int chats;

    /** Posts Telegram returned, and how many of them became songs. */
    final int raw, accepted;

    /** Why posts were left out, by fixed reason name. */
    final Map<String, Integer> rejected;

    private SearchReport(
            Origin origin,
            String cause,
            String scope,
            boolean cached,
            int chats,
            int raw,
            int accepted,
            Map<String, Integer> rejected) {
        this.origin = origin;
        this.cause = cause;
        this.scope = scope;
        this.cached = cached;
        this.chats = chats;
        this.raw = raw;
        this.accepted = accepted;
        this.rejected = rejected;
    }

    SearchReport answeredBy(Origin by, String why, int songs) {
        return new SearchReport(by, why, scope, cached, chats, raw, songs, rejected);
    }

    /** This report with more left-out reasons counted in; zero counts are not recorded. */
    SearchReport with(Map<String, Integer> more) {
        Map<String, Integer> all = new TreeMap<>(rejected);
        for (Map.Entry<String, Integer> e : more.entrySet())
            if (e.getValue() > 0) all.merge(e.getKey(), e.getValue(), Integer::sum);
        return new SearchReport(origin, cause, scope, cached, chats, raw, accepted, all);
    }

    /** The two reports' counts added, for a search that asked Telegram more than once. */
    SearchReport plus(SearchReport other) {
        Map<String, Integer> all = new TreeMap<>(rejected);
        for (Map.Entry<String, Integer> e : other.rejected.entrySet())
            all.merge(e.getKey(), e.getValue(), Integer::sum);
        return new SearchReport(
                origin,
                cause,
                scope,
                cached && other.cached,
                Math.max(chats, other.chats),
                raw + other.raw,
                accepted,
                all);
    }

    /** An empty report that names the chats searched, for answers that never reached Telegram. */
    static SearchReport scoped(java.util.Collection<Long> selected) {
        return new Builder(selected).build(Origin.NONE);
    }

    /** This report marked as served from the result cache. */
    SearchReport fromCache() {
        return new SearchReport(origin, cause, scope, true, chats, raw, accepted, rejected);
    }

    /** A hash short enough to log, long enough to tell two chat selections apart. */
    static String scopeOf(java.util.Collection<Long> selected) {
        java.util.TreeSet<Long> ids = new java.util.TreeSet<>(selected);
        ids.remove(0L);
        StringBuilder out = new StringBuilder().append(ids.size()).append(" [");
        try {
            for (long id : ids) {
                byte[] hash =
                        java.security.MessageDigest.getInstance("SHA-256")
                                .digest(
                                        ("chat:" + id)
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (out.charAt(out.length() - 1) != '[') out.append(',');
                for (int i = 0; i < 3; i++) out.append(String.format("%02x", hash[i]));
            }
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            out.append("?");
        }
        return out.append(']').toString();
    }

    /** Short category for a failed search, safe to log. */
    static String causeOf(Exception e) {
        if (e instanceof LiveSearch.RateLimited) return "rate-limit";
        if (e instanceof java.util.concurrent.TimeoutException) return "timeout";
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.equals(LiveSearch.BUSY)) return "busy";
        if (message.contains("timed out")) return "timeout";
        return "error";
    }

    String line() {
        StringBuilder out =
                new StringBuilder("Telegram search: ")
                        .append(origin.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        if (!cause.isEmpty()) out.append(" after ").append(cause);
        out.append(", scope ").append(scope.isEmpty() ? "none" : scope);
        if (cached) out.append(", cached");
        out.append(", returned ").append(raw);
        out.append(", songs ").append(accepted);
        if (!rejected.isEmpty()) out.append(", left out ").append(rejected);
        return out.toString();
    }

    /** Collects counts while a search reads Telegram's answers. */
    static final class Builder {
        private final int chats;
        private final String scope;
        private int raw, accepted;
        private final Map<String, Integer> rejected = new TreeMap<>();

        Builder(java.util.Collection<Long> selected) {
            this.chats = selected.size();
            this.scope = scopeOf(selected);
        }

        void accepted() {
            raw++;
            accepted++;
        }

        void rejected(String reason) {
            raw++;
            rejected.merge(reason, 1, Integer::sum);
        }

        /** A chat's search that failed or ran out of time, so its posts weren't seen. */
        void unfinished(String cause) {
            rejected.merge("search-" + cause, 1, Integer::sum);
        }

        SearchReport build(Origin origin) {
            return new SearchReport(
                    origin, "", scope, false, chats, raw, accepted, new TreeMap<>(rejected));
        }
    }
}
