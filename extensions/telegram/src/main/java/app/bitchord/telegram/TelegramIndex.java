package app.bitchord.telegram;

import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A list of the music files in each selected chat, kept on the phone.
 *
 * <p>It's built slowly in the background, one page of 100 files at a time, and topped up with new
 * posts every few hours. Once built it answers lookups without asking Telegram, and it answers when
 * Telegram's own search can't (rate limited, busy or timed out).
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

    /**
     * Rebuilt from scratch once a month, which drops files deleted from the chat. A large channel
     * takes an hour or more to go through, so this isn't done more often.
     */
    static final long REBUILD_MILLIS = 30 * 24 * 3600 * 1000L;

    volatile boolean enabled = true;
    private final IndexStore store;
    private final Pages pages;
    private final Clock clock;
    private final Map<Long, IndexStore.State> states = new ConcurrentHashMap<>();
    // Topping up: where each kind has got to, and whether it has reached known posts.
    private final Map<Long, long[]> top = new ConcurrentHashMap<>();
    private final Map<Long, boolean[]> fresh = new ConcurrentHashMap<>();

    TelegramIndex(IndexStore store, Pages pages, Clock clock) {
        this.store = store;
        this.pages = pages;
        this.clock = clock;
    }

    /**
     * Fetches one page for the first selected chat that needs it. False when every selected chat is
     * up to date.
     */
    synchronized boolean step(Collection<Long> selected) throws Exception {
        if (!enabled) return false;
        for (long id : new TreeSet<>(selected)) {
            if (id == 0) continue;
            IndexStore.State chat = state(id);
            long now = clock.millis();
            if (chat.built() && now - chat.builtAt > REBUILD_MILLIS) {
                Arrays.fill(chat.cursor, 0);
                Arrays.fill(chat.complete, false);
                chat.generation++;
                chat.rebuilding = true;
                store.save(chat);
            }
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
        return false;
    }

    private void build(IndexStore.State chat, int kind) throws Exception {
        TdApi.FoundChatMessages found = pages.page(chat.chat, kind, chat.cursor[kind]);
        if (chat.cursor[kind] == 0 && found != null && found.totalCount > 0)
            chat.total[kind] = found.totalCount;
        List<JSONObject> rows = new ArrayList<>();
        long oldest = read(chat.chat, found, rows);
        store.put(chat.chat, rows, chat.generation);
        if (oldest == 0 || oldest == chat.cursor[kind]) {
            chat.complete[kind] = true;
            if (chat.built()) {
                if (chat.rebuilding) store.prune(chat.chat, chat.generation);
                chat.rebuilding = false;
                chat.builtAt = chat.refreshed = clock.millis();
                HostLog.d(
                        "Unofficial Telegram index has "
                                + store.count(chat.chat)
                                + " files for chat "
                                + chat.chat);
            }
        } else {
            chat.cursor[kind] = oldest;
        }
        store.save(chat);
    }

    private void topUp(IndexStore.State chat) throws Exception {
        long[] where = top.computeIfAbsent(chat.chat, c -> new long[KINDS]);
        boolean[] done = fresh.computeIfAbsent(chat.chat, c -> new boolean[KINDS]);
        for (int kind = 0; kind < KINDS; kind++) {
            if (done[kind]) continue;
            TdApi.FoundChatMessages found = pages.page(chat.chat, kind, where[kind]);
            boolean known = false;
            if (found != null && found.messages != null)
                for (TdApi.Message m : found.messages)
                    if (m != null && store.has(chat.chat, m.id)) known = true;
            List<JSONObject> rows = new ArrayList<>();
            long oldest = read(chat.chat, found, rows);
            store.put(chat.chat, rows, chat.generation);
            if (known || oldest == 0 || oldest == where[kind]) {
                done[kind] = true;
                where[kind] = 0;
            } else {
                where[kind] = oldest;
            }
            break;
        }
        boolean all = true;
        for (boolean d : done) all &= d;
        if (all) {
            Arrays.fill(done, false);
            chat.refreshed = clock.millis();
            store.save(chat);
        }
    }

    /** A page's music files; returns the oldest message id in it, or 0 when it was empty. */
    private static long read(long chat, TdApi.FoundChatMessages found, List<JSONObject> rows) {
        long oldest = 0;
        if (found == null || found.messages == null) return 0;
        for (TdApi.Message m : found.messages) {
            if (m == null || m.chatId != chat) continue;
            if (oldest == 0 || m.id < oldest) oldest = m.id;
            try {
                JSONObject row = TrackMetadata.from(m);
                if (row != null) rows.add(row);
            } catch (Exception e) {
                // A post this can't read is left out, as search leaves it out.
            }
        }
        return oldest;
    }

    /**
     * Files in the selected chats with every word of the query at the start of a word of their
     * title, artist or file name, newest first. Null when the index is off or has nothing for these
     * chats, or, with {@code complete}, while any of them is still being built.
     */
    List<JSONObject> search(Collection<Long> selected, String query, boolean complete)
            throws Exception {
        if (!enabled) return null;
        List<Long> chats = new ArrayList<>();
        boolean any = false;
        for (long id : new TreeSet<>(selected)) {
            if (id == 0) continue;
            IndexStore.State chat = state(id);
            if (complete && chat.builtAt == 0) return null;
            if (!any && (chat.builtAt > 0 || store.count(id) > 0)) any = true;
            chats.add(id);
        }
        if (!any) return null;
        List<String> wanted = TrackNames.words(query);
        if (wanted.isEmpty()) return new ArrayList<>();
        return store.search(chats, wanted, MAX_RESULTS);
    }

    /**
     * One chat's newest files, up to {@code limit}, oldest post first. Null when the index is off
     * or the chat hasn't been indexed yet.
     */
    List<JSONObject> newest(long chat, int limit) throws Exception {
        if (!enabled || state(chat).builtAt == 0) return null;
        List<JSONObject> rows = store.newest(chat, limit);
        Collections.reverse(rows);
        return rows;
    }

    int count(long chat) throws Exception {
        return store.count(chat);
    }

    static boolean matches(JSONObject row, List<String> wanted) {
        return IndexStore.matches(IndexStore.text(row), wanted);
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

    /**
     * "1,234 files indexed from 2 chats", or while building, "Indexing: 12,300 files so far, of
     * about 223,776".
     */
    String status(Collection<Long> selected) throws Exception {
        int files = 0, done = 0, chats = 0;
        long expected = 0;
        for (long id : new TreeSet<>(selected)) {
            if (id == 0) continue;
            chats++;
            IndexStore.State chat = state(id);
            int count = store.count(id);
            files += count;
            if (chat.builtAt > 0) {
                done++;
                expected += count;
            } else expected += Math.max(count, chat.total[0]);
        }
        if (chats == 0) return "No music chats selected";
        if (done == chats)
            return String.format(
                    Locale.US,
                    "%,d %s indexed from %d %s",
                    files,
                    files == 1 ? "file" : "files",
                    chats,
                    chats == 1 ? "chat" : "chats");
        String text = String.format(Locale.US, "Indexing: %,d files so far", files);
        if (expected > files) text += String.format(Locale.US, ", of about %,d", expected);
        if (chats > 1) text += " (" + done + " of " + chats + " chats done)";
        return text;
    }

    /** Forgets everything, on disk too: when it's turned off or you sign out. */
    synchronized void clear() throws Exception {
        states.clear();
        top.clear();
        fresh.clear();
        store.clear();
    }

    private IndexStore.State state(long chat) throws Exception {
        IndexStore.State known = states.get(chat);
        if (known != null) return known;
        IndexStore.State loaded = store.state(chat);
        IndexStore.State raced = states.putIfAbsent(chat, loaded);
        return raced == null ? loaded : raced;
    }
}
