package app.bitchord.telegram;

import org.drinkless.tdlib.TdApi;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A list of the music files in each selected chat, kept on the phone.
 *
 * <p>It's built slowly in the background, one page of 100 files at a time, and topped up with new
 * posts every few hours. It answers when Telegram's own search can't (rate limited, busy or timed
 * out), and answers background searches such as the format legend's without asking Telegram.
 */
final class TelegramIndex {
    interface Pages {
        /** Up to 100 messages of one kind (0 audio, 1 documents) older than the given id. */
        TdApi.FoundChatMessages page(long chat, int kind, long fromMessageId) throws Exception;
    }

    interface Clock {
        long millis();
    }

    static final int KINDS = 2, MAX_RESULTS = 200;

    /** The pause between two pages, so building never looks like a burst of searches. */
    static final long PACE_MILLIS = 2000;

    static final long REFRESH_MILLIS = 6 * 3600 * 1000L;

    /** Rebuilt from scratch once a week, which drops files that were deleted from the chat. */
    static final long REBUILD_MILLIS = 7 * 24 * 3600 * 1000L;

    private static final int SAVE_EVERY = 10;

    static final class Chat {
        final long id;
        final LinkedHashMap<String, JSONObject> rows = new LinkedHashMap<>();
        // Building: the oldest message reached so far for each kind (0 = from the newest).
        final long[] cursor = new long[KINDS];
        final boolean[] complete = new boolean[KINDS];
        // Topping up: where each kind has got to, and whether it has reached known posts.
        final long[] top = new long[KINDS];
        final boolean[] fresh = new boolean[KINDS];
        long builtAt, refreshed;
        Set<String> seen; // during a rebuild: the files still in the chat
        int unsaved;

        Chat(long id) {
            this.id = id;
        }

        boolean built() {
            for (boolean done : complete) if (!done) return false;
            return true;
        }
    }

    volatile boolean enabled = true;
    private final File dir;
    private final Pages pages;
    private final Clock clock;
    private final Map<Long, Chat> chats = new ConcurrentHashMap<>();

    TelegramIndex(File dir, Pages pages, Clock clock) {
        this.dir = dir;
        this.pages = pages;
        this.clock = clock;
    }

    /**
     * Fetches one page for the first selected chat that needs it. False when every selected chat is
     * up to date.
     */
    boolean step(Collection<Long> selected) throws Exception {
        if (!enabled) return false;
        for (long id : new TreeSet<>(selected)) {
            if (id == 0) continue;
            Chat chat = chat(id);
            synchronized (chat) {
                long now = clock.millis();
                if (chat.built() && now - chat.builtAt > REBUILD_MILLIS) startRebuild(chat);
                for (int kind = 0; kind < KINDS; kind++)
                    if (!chat.complete[kind]) {
                        build(chat, kind);
                        return true;
                    }
                if (now - chat.refreshed > REFRESH_MILLIS) {
                    topUp(chat);
                    return true;
                }
            }
        }
        return false;
    }

    private void startRebuild(Chat chat) {
        Arrays.fill(chat.cursor, 0);
        Arrays.fill(chat.complete, false);
        chat.seen = new HashSet<>();
    }

    private void build(Chat chat, int kind) throws Exception {
        TdApi.FoundChatMessages found = pages.page(chat.id, kind, chat.cursor[kind]);
        long oldest = add(chat, found);
        if (oldest == 0 || oldest == chat.cursor[kind]) {
            chat.complete[kind] = true;
            if (chat.built()) {
                if (chat.seen != null) chat.rows.keySet().retainAll(chat.seen);
                chat.seen = null;
                chat.builtAt = chat.refreshed = clock.millis();
                HostLog.d(
                        "Unofficial Telegram index has "
                                + chat.rows.size()
                                + " files for chat "
                                + chat.id);
                save(chat);
                return;
            }
        } else {
            chat.cursor[kind] = oldest;
        }
        if (++chat.unsaved >= SAVE_EVERY) save(chat);
    }

    private void topUp(Chat chat) throws Exception {
        for (int kind = 0; kind < KINDS; kind++) {
            if (chat.fresh[kind]) continue;
            TdApi.FoundChatMessages found = pages.page(chat.id, kind, chat.top[kind]);
            boolean known = false;
            if (found != null && found.messages != null)
                for (TdApi.Message m : found.messages)
                    if (m != null && chat.rows.containsKey(chat.id + "_" + m.id)) known = true;
            long oldest = add(chat, found);
            if (known || oldest == 0 || oldest == chat.top[kind]) {
                chat.fresh[kind] = true;
                chat.top[kind] = 0;
            } else {
                chat.top[kind] = oldest;
            }
            break;
        }
        boolean all = true;
        for (boolean done : chat.fresh) all &= done;
        if (all) {
            Arrays.fill(chat.fresh, false);
            chat.refreshed = clock.millis();
            save(chat);
        }
    }

    /** Adds a page's music files; returns the oldest message id in it, or 0 when it was empty. */
    private long add(Chat chat, TdApi.FoundChatMessages found) {
        long oldest = 0;
        if (found == null || found.messages == null) return 0;
        for (TdApi.Message m : found.messages) {
            if (m == null || m.chatId != chat.id) continue;
            if (oldest == 0 || m.id < oldest) oldest = m.id;
            JSONObject row;
            try {
                row = TrackMetadata.from(m);
            } catch (Exception e) {
                continue; // A post this can't read is left out, as search leaves it out.
            }
            if (row == null) continue;
            String id = row.optString("id");
            chat.rows.put(id, row);
            if (chat.seen != null) chat.seen.add(id);
        }
        return oldest;
    }

    /**
     * Files in the selected chats with every word of the query at the start of a word of their
     * title, artist or file name, newest first. Null when the index is off or has nothing for these
     * chats, or, with {@code complete}, while any of them is still being built.
     */
    List<JSONObject> search(Collection<Long> selected, String query, boolean complete) {
        if (!enabled) return null;
        List<String> wanted = TrackNames.words(query);
        List<JSONObject> found = new ArrayList<>();
        boolean any = false;
        for (long id : new TreeSet<>(selected)) {
            if (id == 0) continue;
            Chat chat = chat(id);
            synchronized (chat) {
                if (complete && !chat.built() && chat.builtAt == 0) return null;
                if (chat.builtAt > 0 || !chat.rows.isEmpty()) any = true;
                for (JSONObject row : chat.rows.values()) if (matches(row, wanted)) found.add(row);
            }
        }
        if (!any) return null;
        found.sort((a, b) -> Long.compare(b.optLong("message"), a.optLong("message")));
        List<JSONObject> copies = new ArrayList<>();
        for (JSONObject row : found.subList(0, Math.min(MAX_RESULTS, found.size())))
            try {
                copies.add(new JSONObject(row.toString()));
            } catch (Exception e) {
                // Can't happen: the row was written by JSONObject.
            }
        return copies;
    }

    static boolean matches(JSONObject row, List<String> wanted) {
        if (wanted.isEmpty()) return false;
        List<String> have =
                looseWords(
                        row.optString("title")
                                + " "
                                + row.optString("artist")
                                + " "
                                + row.optString("fileName"));
        for (String word : wanted) {
            boolean hit = false;
            for (String w : have)
                if (w.startsWith(word)) {
                    hit = true;
                    break;
                }
            if (!hit) return false;
        }
        return true;
    }

    /**
     * The words of a text both split at apostrophes and joined across them ("you re" and "youre"),
     * since BitChord asks for "youre" when the file says "You're".
     */
    static List<String> looseWords(String text) {
        Set<String> words = new LinkedHashSet<>(TrackNames.words(text));
        words.addAll(TrackNames.words(text.replaceAll("['’ʼ`´]", "")));
        return new ArrayList<>(words);
    }

    private static final Set<String> CONTRACTIONS =
            new HashSet<>(
                    Arrays.asList(
                            "aint",
                            "arent",
                            "cant",
                            "couldnt",
                            "didnt",
                            "doesnt",
                            "dont",
                            "hadnt",
                            "hasnt",
                            "havent",
                            "hes",
                            "hows",
                            "id",
                            "im",
                            "isnt",
                            "ive",
                            "lets",
                            "mustnt",
                            "shes",
                            "shouldnt",
                            "thats",
                            "theres",
                            "theyd",
                            "theyll",
                            "theyre",
                            "theyve",
                            "wasnt",
                            "weve",
                            "werent",
                            "whats",
                            "wheres",
                            "whos",
                            "wont",
                            "wouldnt",
                            "yall",
                            "youd",
                            "youll",
                            "youre",
                            "youve"));

    /**
     * The query without words like "youre" that BitChord wrote without their apostrophe, which
     * Telegram's search can't match to "You're"; null when there are none, or nothing else.
     */
    static String withoutContractions(String query) {
        List<String> kept = new ArrayList<>();
        boolean dropped = false;
        for (String word : TrackNames.words(query))
            if (CONTRACTIONS.contains(word)) dropped = true;
            else kept.add(word);
        return dropped && !kept.isEmpty() ? String.join(" ", kept) : null;
    }

    /** "1,234 files from 3 chats", with how far building has got. */
    String status(Collection<Long> selected) {
        int files = 0, chatsDone = 0, total = 0;
        for (long id : new TreeSet<>(selected)) {
            if (id == 0) continue;
            total++;
            Chat chat = chat(id);
            synchronized (chat) {
                files += chat.rows.size();
                if (chat.builtAt > 0) chatsDone++;
            }
        }
        if (total == 0) return "No music chats selected";
        String count = String.format(Locale.US, "%,d %s", files, files == 1 ? "file" : "files");
        return chatsDone == total
                ? count + " indexed from " + total + (total == 1 ? " chat" : " chats")
                : count + " so far, " + chatsDone + " of " + total + " chats done";
    }

    /** Forgets everything, on disk too: when it's turned off or you sign out. */
    void clear() {
        chats.clear();
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
    }

    private Chat chat(long id) {
        return chats.computeIfAbsent(id, this::load);
    }

    private File file(long id) {
        return new File(dir, id + ".json");
    }

    private Chat load(long id) {
        Chat chat = new Chat(id);
        File f = file(id);
        if (!f.isFile()) return chat;
        try {
            JSONObject saved =
                    new JSONObject(
                            new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            JSONArray cursor = saved.getJSONArray("cursor"),
                    complete = saved.getJSONArray("complete");
            for (int k = 0; k < KINDS; k++) {
                chat.cursor[k] = cursor.getLong(k);
                chat.complete[k] = complete.getBoolean(k);
            }
            chat.builtAt = saved.optLong("builtAt");
            chat.refreshed = saved.optLong("refreshed");
            JSONArray rows = saved.getJSONArray("rows");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                chat.rows.put(row.getString("id"), row);
            }
        } catch (Exception e) {
            // A damaged file is rebuilt from scratch.
            return new Chat(id);
        }
        return chat;
    }

    private void save(Chat chat) {
        chat.unsaved = 0;
        try {
            JSONObject out =
                    new JSONObject()
                            .put("cursor", new JSONArray(chat.cursor))
                            .put("complete", new JSONArray(chat.complete))
                            .put("builtAt", chat.builtAt)
                            .put("refreshed", chat.refreshed)
                            .put("rows", new JSONArray(chat.rows.values()));
            dir.mkdirs();
            File tmp = new File(dir, chat.id + ".tmp");
            try (FileOutputStream stream = new FileOutputStream(tmp)) {
                stream.write(out.toString().getBytes(StandardCharsets.UTF_8));
                stream.getFD().sync();
            }
            if (!tmp.renameTo(file(chat.id))) tmp.delete();
        } catch (Exception e) {
            HostLog.d("Unofficial Telegram index couldn't be saved: " + e.getMessage());
        }
    }
}
