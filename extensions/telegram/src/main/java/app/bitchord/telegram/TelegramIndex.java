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

        /** Telegram's count of one kind of message in a chat, as its Media tabs show it. */
        int count(long chat, int kind) throws Exception;

        /** How many messages of one kind are newer than this one, plus one. */
        int position(long chat, int kind, long messageId) throws Exception;

        /** Bytes of a file, downloading only that part: for reading inside zips. */
        byte[] range(int file, long offset, int length) throws Exception;
    }

    /** A line in BitChord's log every this many pages, with how long they took. */
    static final int LOG_EVERY = 25;

    /** How far back the time-left estimate looks. */
    static final long RATE_WINDOW_MILLIS = 10 * 60 * 1000L;

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
    // Messages read over time, for the time-left estimate: {millis, messages read so far}.
    private final ArrayDeque<long[]> pace = new ArrayDeque<>();
    private long read;
    private int pagesSinceLog;
    private final Set<String> asked = ConcurrentHashMap.newKeySet();
    private long loggedAt;
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
     * Fetches one page for a selected chat that needs it. False when every selected chat is up to
     * date.
     */
    synchronized boolean step(Collection<Long> selected) throws Exception {
        return step(selected, 0);
    }

    /**
     * As above, with {@code first} built before the others. Chats being built take turns a page at
     * a time, so a small chat isn't stuck behind one with 200,000 files, and chats already built
     * are topped up in between.
     */
    synchronized boolean step(Collection<Long> selected, long first) throws Exception {
        if (!enabled) return false;
        // Zips found so far are looked into on every other step, so their songs show up while a
        // large chat is still being gone through.
        zipTurn = !zipTurn;
        if (zipTurn && openZip(selected)) return true;
        List<IndexStore.State> building = new ArrayList<>();
        IndexStore.State due = null;
        long now = clock.millis();
        for (long id : new TreeSet<>(selected)) {
            if (id == 0) continue;
            IndexStore.State chat = state(id);
            if (chat.built() && now - chat.builtAt > REBUILD_MILLIS) {
                Arrays.fill(chat.cursor, 0);
                Arrays.fill(chat.complete, false);
                Arrays.fill(chat.total, -1);
                Arrays.fill(chat.scanned, 0);
                chat.generation++;
                chat.rebuilding = true;
                store.save(chat);
            }
            if (!chat.built()) building.add(chat);
            else if (due == null && now - chat.refreshed > REFRESH_MILLIS) due = chat;
        }
        if (due != null) {
            topUp(due);
            return true;
        }
        if (building.isEmpty()) return openZip(selected);
        IndexStore.State next = null;
        for (IndexStore.State chat : building) if (chat.chat == first) next = chat;
        if (next == null) {
            next = building.get(0);
            for (IndexStore.State chat : building)
                if (chat.chat > turn) {
                    next = chat;
                    break;
                }
            turn = next.chat;
        }
        for (int kind = 0; kind < KINDS; kind++)
            if (!next.complete[kind]) {
                build(next, kind);
                break;
            }
        return true;
    }

    private long turn = Long.MIN_VALUE;

    /** How far a chat's first build has got, as "34%", or "" before that's known. */
    String progress(long chat) throws Exception {
        IndexStore.State state = state(chat);
        long total = 0, scanned = 0;
        for (int k = 0; k < KINDS; k++) {
            if (state.total[k] < 0 && !state.complete[k]) return "";
            long all = Math.max(state.total[k], state.scanned[k]);
            total += all;
            scanned += state.complete[k] ? all : state.scanned[k];
        }
        return total == 0 ? "" : (int) (100 * scanned / total) + "%";
    }

    private void build(IndexStore.State chat, int kind) throws Exception {
        // Both totals are asked for once per run, before the first page, so progress has a
        // percentage from the start. A build started by 1.0.8 has neither number yet.
        if (asked.add(Long.toString(chat.chat)))
            for (int k = 0; k < KINDS; k++)
                try {
                    if (chat.total[k] < 0) chat.total[k] = Math.max(0, pages.count(chat.chat, k));
                    if (chat.scanned[k] == 0 && chat.cursor[k] != 0)
                        chat.scanned[k] = Math.max(0, pages.position(chat.chat, k, chat.cursor[k]));
                } catch (LiveSearch.RateLimited e) {
                    throw e;
                } catch (Exception e) {
                    // Progress then shows without a percentage.
                }
        TdApi.FoundChatMessages found = pages.page(chat.chat, kind, chat.cursor[kind]);
        if (chat.total[kind] < 0 && found != null && found.totalCount >= 0)
            chat.total[kind] = found.totalCount;
        List<JSONObject> rows = new ArrayList<>(), zips = new ArrayList<>();
        long oldest = read(chat.chat, found, rows, zips);
        store.put(chat.chat, rows, chat.generation);
        store.queueZips(chat.chat, zips, chat.generation);
        int messages = found == null || found.messages == null ? 0 : found.messages.length;
        chat.scanned[kind] += messages;
        progressed(messages);
        if (++pagesSinceLog >= LOG_EVERY) {
            long now = clock.millis();
            HostLog.d(
                    String.format(
                            Locale.US,
                            "Unofficial Telegram index: chat %d, %s, %,d of %,d messages read, %,d"
                                    + " files kept; the last %d pages took %d s",
                            chat.chat,
                            kind == 0 ? "music" : "other files",
                            chat.scanned[kind],
                            chat.total[kind],
                            store.count(chat.chat),
                            pagesSinceLog,
                            (now - loggedAt) / 1000));
            pagesSinceLog = 0;
            loggedAt = now;
        }
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

    private boolean zipTurn;

    /** Reads the list of songs in one waiting zip; false when none are waiting. */
    private boolean openZip(Collection<Long> selected) throws Exception {
        List<Long> chats = new ArrayList<>();
        for (long id : selected) if (id != 0) chats.add(id);
        JSONObject zip = store.nextZip(chats);
        if (zip == null) return false;
        long chat = zip.getLong("chat"), message = zip.getLong("message");
        int file = zip.getInt("file");
        try {
            List<JSONObject> songs =
                    ZipAlbums.songs(zip, (offset, length) -> pages.range(file, offset, length));
            store.put(chat, songs, state(chat).generation);
            HostLog.d(
                    "Unofficial Telegram found "
                            + songs.size()
                            + (songs.size() == 1 ? " song" : " songs")
                            + " in "
                            + zip.optString("fileName"));
        } catch (LiveSearch.RateLimited e) {
            throw e;
        } catch (Exception e) {
            HostLog.d(
                    "Unofficial Telegram couldn't look into "
                            + zip.optString("fileName")
                            + ": "
                            + e.getMessage());
        }
        store.zipDone(chat, message);
        return true;
    }

    /** One file of a chat, or null: a song inside a zip has an entry from 1. */
    JSONObject row(long chat, long message, int entry) throws Exception {
        return enabled ? store.row(chat, message, entry) : null;
    }

    private synchronized void progressed(int messages) {
        long now = clock.millis();
        if (pace.isEmpty()) {
            loggedAt = now;
            pace.add(new long[] {now, read});
        }
        read += messages;
        pace.add(new long[] {now, read});
        while (pace.size() > 2 && now - pace.peekFirst()[0] > RATE_WINDOW_MILLIS) pace.pollFirst();
    }

    /** Messages read per minute lately, or 0 before there's enough to tell. */
    synchronized double perMinute() {
        if (pace.size() < 2) return 0;
        long[] first = pace.peekFirst(), last = pace.peekLast();
        long millis = Math.max(clock.millis(), last[0]) - first[0];
        return millis < 30_000 ? 0 : (last[1] - first[1]) * 60_000.0 / millis;
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
            List<JSONObject> rows = new ArrayList<>(), zips = new ArrayList<>();
            long oldest = read(chat.chat, found, rows, zips);
            store.put(chat.chat, rows, chat.generation);
            store.queueZips(chat.chat, zips, chat.generation);
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
    private static long read(
            long chat,
            TdApi.FoundChatMessages found,
            List<JSONObject> rows,
            List<JSONObject> zips) {
        long oldest = 0;
        if (found == null || found.messages == null) return 0;
        for (TdApi.Message m : found.messages) {
            if (m == null || m.chatId != chat) continue;
            if (oldest == 0 || m.id < oldest) oldest = m.id;
            try {
                JSONObject row = TrackMetadata.from(m);
                if (row != null) rows.add(row);
                JSONObject zip = row == null ? ZipAlbums.descriptor(m) : null;
                if (zip != null) zips.add(zip);
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

    /** One chat's files posted after a message, oldest first; null until the chat is indexed. */
    List<JSONObject> after(long chat, long message, int limit) throws Exception {
        if (!enabled || state(chat).builtAt == 0) return null;
        return store.after(chat, message, limit);
    }

    int countAfter(long chat, long message) throws Exception {
        return store.countAfter(chat, message);
    }

    boolean built(long chat) throws Exception {
        return enabled && state(chat).builtAt > 0;
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
     * "1,234 files indexed from 2 chats", or while building, "Indexing: 12,920 files found · 6% ·
     * about 1 h 40 min left (0 of 2 chats done)".
     */
    String status(Collection<Long> selected) throws Exception {
        int files = 0, done = 0, chats = 0;
        long total = 0, scanned = 0;
        boolean known = true;
        for (long id : new TreeSet<>(selected)) {
            if (id == 0) continue;
            chats++;
            IndexStore.State chat = state(id);
            files += store.count(id);
            if (chat.builtAt > 0 && !chat.rebuilding) {
                done++;
                continue;
            }
            for (int k = 0; k < KINDS; k++) {
                if (chat.total[k] < 0 && !chat.complete[k]) known = false;
                long all = Math.max(chat.total[k], chat.scanned[k]);
                total += all;
                scanned += chat.complete[k] ? all : chat.scanned[k];
            }
        }
        if (chats == 0) return Strings.t("No music chats selected");
        int zips = store.zipsWaiting(new TreeSet<>(selected));
        String waiting =
                zips > 0
                        ? " · "
                                + Strings.f(
                                        zips == 1 ? "{0} zip to open" : "{0} zips to open",
                                        Strings.number(zips))
                        : "";
        if (done == chats)
            return Strings.f(
                            files == 1
                                    ? chats == 1
                                            ? "{0} file indexed from {1} chat"
                                            : "{0} file indexed from {1} chats"
                                    : chats == 1
                                            ? "{0} files indexed from {1} chat"
                                            : "{0} files indexed from {1} chats",
                            Strings.number(files),
                            chats)
                    + waiting;
        StringBuilder text =
                new StringBuilder(Strings.f("Indexing: {0} files found", Strings.number(files)));
        if (known && total > 0) {
            text.append(" · ").append((int) (100 * scanned / total)).append('%');
            double rate = perMinute();
            if (rate > 0)
                text.append(" · ")
                        .append(Strings.f("about {0} left", timeLeft((total - scanned) / rate)));
        }
        if (chats > 1) text.append(" ").append(Strings.f("({0} of {1} chats done)", done, chats));
        return text.append(waiting).toString();
    }

    /** "40 min", "1 h 40 min", "2 days". */
    static String timeLeft(double minutes) {
        long m = Math.max(1, Math.round(minutes));
        if (m < 60) return Strings.f("{0} min", m);
        if (m < 48 * 60)
            return m % 60 == 0
                    ? Strings.f("{0} h", m / 60)
                    : Strings.f("{0} h {1} min", m / 60, m % 60);
        return Strings.f("{0} days", Math.round(m / 1440.0));
    }

    /** Forgets everything, on disk too: when it's turned off or you sign out. */
    synchronized void clear() throws Exception {
        states.clear();
        asked.clear();
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
