package app.bitchord.telegram;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/** Where the saved index keeps its files: SQLite on the phone, memory in tests. */
interface IndexStore {
    /** How far one chat's index has got. */
    final class State {
        final long chat;
        // Building: the oldest message reached so far for each kind (0 = from the newest).
        final long[] cursor = new long[TelegramIndex.KINDS];
        final boolean[] complete = new boolean[TelegramIndex.KINDS];
        // Telegram's own count of each kind (-1 until known), and how many of those this build has
        // gone through.
        final int[] total = new int[TelegramIndex.KINDS];
        final int[] scanned = new int[TelegramIndex.KINDS];
        long builtAt, refreshed;
        // Files written by the current build. A rebuild bumps it and then drops older files,
        // which are the ones deleted from the chat.
        long generation = 1;
        boolean rebuilding;

        State(long chat) {
            this.chat = chat;
            Arrays.fill(total, -1);
        }

        boolean built() {
            for (boolean done : complete) if (!done) return false;
            return true;
        }

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("cursor", new JSONArray(cursor))
                    .put("complete", new JSONArray(complete))
                    .put("total", new JSONArray(total))
                    .put("scanned", new JSONArray(scanned))
                    .put("builtAt", builtAt)
                    .put("refreshed", refreshed)
                    .put("generation", generation)
                    .put("rebuilding", rebuilding);
        }

        static State fromJson(long chat, JSONObject saved) throws Exception {
            State s = new State(chat);
            JSONArray cursor = saved.getJSONArray("cursor"),
                    complete = saved.getJSONArray("complete"),
                    total = saved.optJSONArray("total"),
                    scanned = saved.optJSONArray("scanned");
            for (int k = 0; k < TelegramIndex.KINDS; k++) {
                s.cursor[k] = cursor.getLong(k);
                s.complete[k] = complete.getBoolean(k);
                if (total != null) s.total[k] = total.optInt(k, -1);
                if (s.total[k] == 0 && !s.complete[k] && saved.optJSONArray("scanned") == null)
                    s.total[k] = -1; // 1.0.8 wrote 0 for "not known".
                if (scanned != null) s.scanned[k] = scanned.optInt(k);
            }
            s.builtAt = saved.optLong("builtAt");
            s.refreshed = saved.optLong("refreshed");
            s.generation = saved.optLong("generation", 1);
            s.rebuilding = saved.optBoolean("rebuilding");
            return s;
        }
    }

    State state(long chat) throws Exception;

    void save(State state) throws Exception;

    /** Adds or updates files, marking them as part of this generation. */
    void put(long chat, List<JSONObject> rows, long generation) throws Exception;

    boolean has(long chat, long message) throws Exception;

    int count(long chat) throws Exception;

    /** Files in these chats with every word as the start of one of theirs, newest first. */
    List<JSONObject> search(Collection<Long> chats, List<String> words, int limit) throws Exception;

    /** One chat's newest files, newest first. */
    List<JSONObject> newest(long chat, int limit) throws Exception;

    /** One chat's files posted after {@code message}, oldest first. */
    List<JSONObject> after(long chat, long message, int limit) throws Exception;

    /** How many of one chat's files were posted after {@code message}. */
    int countAfter(long chat, long message) throws Exception;

    /** Drops a chat's files from before this generation. */
    void prune(long chat, long generation) throws Exception;

    void clear() throws Exception;

    /**
     * The words of a text both split at apostrophes and joined across them ("you re" and "youre"),
     * since BitChord asks for "youre" when the file says "You're".
     */
    static List<String> looseWords(String text) {
        Set<String> words = new LinkedHashSet<>(TrackNames.words(text));
        words.addAll(TrackNames.words(text.replaceAll("['’ʼ`´]", "")));
        return new ArrayList<>(words);
    }

    /** What a file is searched by: its title, artist and file name. */
    static String text(JSONObject row) {
        return String.join(
                " ",
                looseWords(
                        row.optString("title")
                                + " "
                                + row.optString("artist")
                                + " "
                                + row.optString("fileName")));
    }

    /** True when every wanted word starts one of the words in {@code text}. */
    static boolean matches(String text, List<String> wanted) {
        if (wanted.isEmpty()) return false;
        List<String> have = Arrays.asList(text.split(" "));
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

    /** Kept in memory: for tests, and small enough only for them. */
    final class Memory implements IndexStore {
        private static final class Entry {
            final String row, text;
            final long generation;

            Entry(String row, String text, long generation) {
                this.row = row;
                this.text = text;
                this.generation = generation;
            }
        }

        private final Map<Long, String> states = new HashMap<>();
        private final Map<Long, TreeMap<Long, Entry>> files = new HashMap<>();

        @Override
        public synchronized State state(long chat) throws Exception {
            String saved = states.get(chat);
            return saved == null ? new State(chat) : State.fromJson(chat, new JSONObject(saved));
        }

        @Override
        public synchronized void save(State state) throws Exception {
            states.put(state.chat, state.toJson().toString());
        }

        @Override
        public synchronized void put(long chat, List<JSONObject> rows, long generation) {
            TreeMap<Long, Entry> chatFiles = files.computeIfAbsent(chat, c -> new TreeMap<>());
            for (JSONObject row : rows)
                chatFiles.put(
                        row.optLong("message"), new Entry(row.toString(), text(row), generation));
        }

        @Override
        public synchronized boolean has(long chat, long message) {
            return files.getOrDefault(chat, new TreeMap<>()).containsKey(message);
        }

        @Override
        public synchronized int count(long chat) {
            return files.getOrDefault(chat, new TreeMap<>()).size();
        }

        @Override
        public synchronized List<JSONObject> search(
                Collection<Long> chats, List<String> words, int limit) throws Exception {
            List<long[]> hits = new ArrayList<>();
            List<String> rows = new ArrayList<>();
            for (long chat : chats)
                for (Map.Entry<Long, Entry> e :
                        files.getOrDefault(chat, new TreeMap<>()).entrySet())
                    if (matches(e.getValue().text, words)) {
                        hits.add(new long[] {e.getKey(), rows.size()});
                        rows.add(e.getValue().row);
                    }
            hits.sort((a, b) -> Long.compare(b[0], a[0]));
            List<JSONObject> found = new ArrayList<>();
            for (long[] hit : hits.subList(0, Math.min(limit, hits.size())))
                found.add(new JSONObject(rows.get((int) hit[1])));
            return found;
        }

        @Override
        public synchronized List<JSONObject> newest(long chat, int limit) throws Exception {
            List<JSONObject> found = new ArrayList<>();
            for (Entry e : files.getOrDefault(chat, new TreeMap<>()).descendingMap().values()) {
                if (found.size() >= limit) break;
                found.add(new JSONObject(e.row));
            }
            return found;
        }

        @Override
        public synchronized List<JSONObject> after(long chat, long message, int limit)
                throws Exception {
            List<JSONObject> found = new ArrayList<>();
            for (Entry e :
                    files.getOrDefault(chat, new TreeMap<>()).tailMap(message, false).values()) {
                if (found.size() >= limit) break;
                found.add(new JSONObject(e.row));
            }
            return found;
        }

        @Override
        public synchronized int countAfter(long chat, long message) {
            return files.getOrDefault(chat, new TreeMap<>()).tailMap(message, false).size();
        }

        @Override
        public synchronized void prune(long chat, long generation) {
            TreeMap<Long, Entry> chatFiles = files.get(chat);
            if (chatFiles != null) chatFiles.values().removeIf(e -> e.generation < generation);
        }

        @Override
        public synchronized void clear() {
            states.clear();
            files.clear();
        }
    }
}
