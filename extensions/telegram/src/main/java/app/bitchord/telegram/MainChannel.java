package app.bitchord.telegram;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Copies music from community channels into a private channel you own, a few files at a time.
 *
 * <p>Files are copied, not forwarded, so they show as your own posts, and Telegram doesn't upload
 * them again: the copy points at the same file. Once copied, a file stays in your channel even if
 * the community channel deletes it. The files to copy come from the saved index, oldest post first;
 * the pace never goes above the hourly limit.
 */
final class MainChannel {
    interface Telegram {
        /** Copies messages without "Forwarded from". Throws RateLimited when told to wait. */
        void copy(long toChat, long fromChat, long[] messages) throws Exception;
    }

    interface Clock {
        long millis();
    }

    static final int MAX_PER_HOUR = 500, DEFAULT_PER_HOUR = 100, MAX_BATCH = 10;
    private static final int LOOK_AHEAD = 50;

    private final File file;
    private final Telegram telegram;
    private final Clock clock;
    private JSONObject saved;
    private long nextAt;
    private Set<String> owned; // duplicate keys of what the main channel has
    volatile String status = "";

    MainChannel(File file, Telegram telegram, Clock clock) {
        this.file = file;
        this.telegram = telegram;
        this.clock = clock;
        try {
            saved =
                    file != null && file.isFile()
                            ? new JSONObject(
                                    new String(
                                            Files.readAllBytes(file.toPath()),
                                            StandardCharsets.UTF_8))
                            : new JSONObject();
        } catch (Exception e) {
            saved = new JSONObject();
        }
    }

    // ── Settings ────────────────────────────────────────────────────────────

    synchronized long main() {
        return saved.optLong("main");
    }

    synchronized String mainTitle() {
        return saved.optString("mainTitle");
    }

    synchronized void setMain(long chat, String title) throws Exception {
        if (chat != main()) {
            saved.put("main", chat).put("mainTitle", title).remove("chats");
            saved.put("copied", 0).put("lossy", 0).put("duplicates", 0);
            owned = null;
        }
        save();
    }

    synchronized List<Long> community() {
        List<Long> chats = new ArrayList<>();
        JSONArray list = saved.optJSONArray("community");
        if (list != null) for (int i = 0; i < list.length(); i++) chats.add(list.optLong(i));
        return chats;
    }

    synchronized void setCommunity(Collection<Long> chats) throws Exception {
        List<Long> kept = new ArrayList<>();
        for (long chat : chats)
            if (chat != 0 && chat != main() && !kept.contains(chat)) kept.add(chat);
        saved.put("community", new JSONArray(kept));
        save();
    }

    synchronized boolean option(String name) {
        return saved.optBoolean(name);
    }

    /** "import", "sync", "losslessOnly" or "skipDuplicates". */
    synchronized void setOption(String name, boolean on) throws Exception {
        if (name.equals("import") && on && !option("import")) {
            // An import without sync copies what each channel has now; that's fixed when it starts.
            saved.remove("chats");
            nextAt = 0;
        }
        if (name.equals("skipDuplicates")) owned = null;
        saved.put(name, on);
        save();
    }

    synchronized int perHour() {
        return Math.max(1, Math.min(MAX_PER_HOUR, saved.optInt("perHour", DEFAULT_PER_HOUR)));
    }

    synchronized void setPerHour(int files) throws Exception {
        saved.put("perHour", Math.max(1, Math.min(MAX_PER_HOUR, files)));
        save();
    }

    /** The chats the saved index needs: the main channel and the community channels. */
    synchronized List<Long> indexed() {
        List<Long> chats = new ArrayList<>(community());
        if (main() != 0) chats.add(main());
        return chats;
    }

    // ── Copying ─────────────────────────────────────────────────────────────

    /** Files per copy request, and the gap between requests that keeps to the hourly limit. */
    int batch() {
        return Math.max(1, Math.min(MAX_BATCH, (int) Math.ceil(perHour() / 60.0)));
    }

    long gapMillis(int files) {
        return 3_600_000L * files / perHour();
    }

    /**
     * Copies the next batch when one is due. Returns how long to wait before calling again. Throws
     * RateLimited when Telegram asks for a pause; the next batch waits for it.
     */
    synchronized long step(TelegramIndex index) throws Exception {
        long now = clock.millis();
        long main = main();
        List<Long> sources = community();
        if (!option("import") || main == 0 || sources.isEmpty()) {
            status = "";
            return 60_000;
        }
        if (!index.enabled) {
            status = "Waiting for Saved index to be turned on";
            return 60_000;
        }
        if (now < nextAt) return nextAt - now;
        boolean dedupe = option("skipDuplicates");
        if (dedupe && owned == null) {
            if (!index.built(main)) {
                status = "Waiting for the main channel to be indexed";
                return 30_000;
            }
            owned = new HashSet<>();
            List<JSONObject> rows;
            long after = 0;
            while (!(rows = index.after(main, after, 1000)).isEmpty())
                for (JSONObject row : rows) {
                    owned.addAll(keys(row));
                    after = row.optLong("message");
                }
        }
        int batch = batch();
        boolean waiting = false, more = false;
        for (long chat : sources) {
            if (!index.built(chat)) {
                waiting = true;
                continue;
            }
            JSONObject state = chatState(chat);
            if (!state.has("cutoff")) state.put("cutoff", newest(index, chat));
            long cutoff = option("sync") ? Long.MAX_VALUE : state.optLong("cutoff");
            long after = state.optLong("after");
            List<Long> picked = new ArrayList<>();
            List<JSONObject> rows = index.after(chat, after, LOOK_AHEAD);
            for (JSONObject row : rows) {
                long message = row.optLong("message");
                if (message > cutoff) break;
                if (picked.size() >= batch) {
                    more = true;
                    break;
                }
                after = message;
                if (option("losslessOnly") && !lossless(row)) {
                    count("lossy");
                    continue;
                }
                if (dedupe && !Collections.disjoint(keys(row), owned)) {
                    count("duplicates");
                    continue;
                }
                if (!picked.contains(message)) picked.add(message); // songs of one zip share it
                if (dedupe) owned.addAll(keys(row));
            }
            if (!picked.isEmpty()) {
                long[] ids = new long[picked.size()];
                for (int i = 0; i < ids.length; i++) ids[i] = picked.get(i);
                try {
                    telegram.copy(main, chat, ids);
                } catch (LiveSearch.RateLimited e) {
                    nextAt = now + 1000L * e.retrySeconds + 5000;
                    status = "Telegram asked for a pause; carrying on in " + e.retrySeconds + " s";
                    save();
                    throw e;
                } catch (Exception e) {
                    HostLog.d(
                            "Unofficial Telegram couldn't copy "
                                    + ids.length
                                    + " files from chat "
                                    + chat
                                    + ": "
                                    + e.getMessage());
                    count("failed", ids.length);
                    ids = new long[0];
                }
                count("copied", ids.length);
                state.put("after", after);
                nextAt = now + gapMillis(picked.size());
                status = progress(index);
                save();
                return nextAt - now;
            }
            state.put("after", after);
            if (!rows.isEmpty() && after < cutoff && rows.size() == LOOK_AHEAD) more = true;
        }
        save();
        status =
                waiting
                        ? "Waiting for the community channels to be indexed. " + progress(index)
                        : more ? progress(index) : done();
        return more ? 1000 : option("sync") ? 600_000 : 60_000;
    }

    private String done() {
        return option("sync")
                ? String.format(
                        Locale.US,
                        "Up to date: %,d copied. New posts are copied as they're indexed.",
                        saved.optInt("copied"))
                : String.format(Locale.US, "Done: %,d copied.", saved.optInt("copied"));
    }

    /** "Copied 1,234 · 56 lossy and 12 duplicates skipped · about 4,500 to go, up to 9 h". */
    String progress(TelegramIndex index) {
        long left = 0;
        try {
            for (long chat : community()) {
                if (!index.built(chat)) continue;
                JSONObject state = chatState(chat);
                left += index.countAfter(chat, state.optLong("after"));
            }
        } catch (Exception ignored) {
            // Shown without what's left.
        }
        StringBuilder text =
                new StringBuilder(String.format(Locale.US, "Copied %,d", saved.optInt("copied")));
        int lossy = saved.optInt("lossy"), dupes = saved.optInt("duplicates");
        if (lossy > 0 || dupes > 0) {
            text.append(" · ");
            if (lossy > 0) text.append(String.format(Locale.US, "%,d lossy", lossy));
            if (lossy > 0 && dupes > 0) text.append(" and ");
            if (dupes > 0)
                text.append(
                        String.format(
                                Locale.US,
                                "%,d %s",
                                dupes,
                                dupes == 1 ? "duplicate" : "duplicates"));
            text.append(" skipped");
        }
        if (left > 0)
            text.append(
                    String.format(
                            Locale.US,
                            " · about %,d to go, up to %s",
                            left,
                            TelegramIndex.timeLeft(left * 60.0 / perHour())));
        return text.toString();
    }

    private long newest(TelegramIndex index, long chat) throws Exception {
        long newest = 0;
        List<JSONObject> rows = index.newest(chat, 1);
        if (rows != null && !rows.isEmpty()) newest = rows.get(0).optLong("message");
        return newest;
    }

    private JSONObject chatState(long chat) throws Exception {
        JSONObject chats = saved.optJSONObject("chats");
        if (chats == null) saved.put("chats", chats = new JSONObject());
        JSONObject state = chats.optJSONObject(Long.toString(chat));
        if (state == null) chats.put(Long.toString(chat), state = new JSONObject());
        return state;
    }

    private void count(String name) throws Exception {
        count(name, 1);
    }

    private void count(String name, int n) throws Exception {
        saved.put(name, saved.optInt(name) + n);
    }

    static boolean lossless(JSONObject row) {
        return InternalAddon.quality(row).contains("LOSSLESS");
    }

    /**
     * What makes two posts the same song: the same Telegram file, or the same title, artist, length
     * and format.
     */
    static Set<String> keys(JSONObject row) {
        Set<String> keys = new HashSet<>();
        String unique = row.optString("unique");
        if (!unique.isEmpty()) keys.add("u:" + unique);
        if (row.optInt("file") != 0)
            keys.add(
                    "f:"
                            + row.optInt("file")
                            + (row.optInt("entry") > 0 ? "/" + row.optInt("entry") : ""));
        List<String> title = TrackNames.words(row.optString("title"));
        if (!title.isEmpty() && row.optInt("duration") > 0)
            keys.add(
                    "m:"
                            + String.join(" ", title)
                            + "|"
                            + String.join(" ", TrackNames.words(row.optString("artist")))
                            + "|"
                            + row.optInt("duration")
                            + "|"
                            + row.optString("format").toLowerCase(Locale.ROOT));
        return keys;
    }

    /** Forgets everything: on sign-out. */
    synchronized void clear() {
        saved = new JSONObject();
        owned = null;
        status = "";
        if (file != null) file.delete();
    }

    private void save() throws Exception {
        if (file == null) return;
        File tmp = new File(file.getPath() + ".tmp");
        Files.write(tmp.toPath(), saved.toString().getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(file)) tmp.delete();
    }
}
