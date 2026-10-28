package app.bitchord.telegram;

import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class TelegramIndexTest {
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    /** A chat's audio, newest first, paged like SearchChatMessages with an empty query. */
    static final class FakeChat implements TelegramIndex.Pages {
        final TreeMap<Long, TdApi.Message> audio = new TreeMap<>(Comparator.reverseOrder());
        final AtomicInteger calls = new AtomicInteger();
        final AtomicBoolean limited = new AtomicBoolean();

        void add(long id, String title) {
            audio.put(id, TestMessages.audio(-100, id, title));
        }

        @Override
        public TdApi.FoundChatMessages page(long chat, int kind, long from) throws Exception {
            calls.incrementAndGet();
            if (limited.get()) throw new LiveSearch.RateLimited(30);
            List<TdApi.Message> page = new ArrayList<>();
            if (kind == 0)
                for (TdApi.Message m : audio.values())
                    if ((from == 0 || m.id < from) && page.size() < 100) page.add(m);
            TdApi.FoundChatMessages found = new TdApi.FoundChatMessages();
            found.messages = page.toArray(new TdApi.Message[0]);
            return found;
        }
    }

    static int build(TelegramIndex index, Set<Long> chats) throws Exception {
        int steps = 0;
        while (index.step(chats)) if (++steps > 100) throw new AssertionError("Never finishes");
        return steps;
    }

    static List<String> titles(List<JSONObject> rows) {
        List<String> out = new ArrayList<>();
        for (JSONObject row : rows) out.add(row.optString("title"));
        return out;
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("index").toFile();
        AtomicLong clock = new AtomicLong(1_000_000);
        FakeChat chat = new FakeChat();
        for (long id = 1; id <= 250; id++) chat.add(id, "Song " + id);
        chat.add(300, "Nobody Knows You When You're Down and Out");
        Set<Long> chats = Collections.singleton(-100L);
        TelegramIndex index = new TelegramIndex(dir, chat, clock::get);

        check(index.search(chats, "song", false) == null, "Nothing to answer from yet");
        check(index.step(chats), "Starts building");
        check(index.search(chats, "song", true) == null, "Not complete yet");
        check(index.search(chats, "song", false).size() == 99, "A partial index still answers");
        check(index.status(chats).equals("100 files so far, 0 of 1 chats done"), "Progress");
        int steps = build(index, chats);
        // Pages of 100, 100 and 51, an empty one to finish the audio, one for documents.
        check(steps == 4 && chat.calls.get() == 5, "One page per step: " + chat.calls.get());
        check(index.status(chats).equals("251 files indexed from 1 chat"), index.status(chats));
        check(!index.step(chats), "Nothing to do once built");

        List<JSONObject> nobody = index.search(chats, "nobody know", true);
        check(
                titles(nobody).equals(List.of("Nobody Knows You When You're Down and Out")),
                "Words match at their start: " + titles(nobody));
        check(index.search(chats, "you re down", true).size() == 1, "Apostrophes are ignored");
        check(
                index.search(chats, "nobody knows you when youre down out", true).size() == 1,
                "BitChord's 'youre' matches 'You're'");
        check(
                TelegramIndex.withoutContractions(
                                "nobody knows you when youre down out nina simone")
                        .equals("nobody knows you when down out nina simone"),
                "Run-together words dropped for Telegram");
        check(TelegramIndex.withoutContractions("sway dean martin") == null, "Nothing to drop");
        check(TelegramIndex.withoutContractions("youre") == null, "Nothing left");
        check(
                TelegramIndex.withoutContractions("dont stop me now").equals("stop me now"),
                "Other run-together words too");
        check(
                TelegramIndex.withoutContractions("Don't Stop Me Now") == null,
                "A query with its apostrophe is left alone");
        check(
                TelegramIndex.looseWords("Ain’t No Sunshine")
                        .containsAll(List.of("aint", "ain", "t")),
                "Curly apostrophes too");
        check(index.search(chats, "artist nobody", true).size() == 1, "Artist words count");
        check(index.search(chats, "nobody cares", true).isEmpty(), "Every word must match");
        check(index.search(chats, "song", true).size() == TelegramIndex.MAX_RESULTS, "Capped");
        check(
                index.search(chats, "song", true).get(0).optString("title").equals("Song 250"),
                "Newest first");
        check(index.search(Collections.singleton(-200L), "song", false) == null, "Other chats");
        nobody.get(0).put("title", "Changed");
        check(index.search(chats, "nobody", true).size() == 1, "Results are copies");

        // Topped up after a few hours: new posts, and only until a known one.
        chat.add(301, "New Upload");
        clock.addAndGet(TelegramIndex.REFRESH_MILLIS + 1);
        int before = chat.calls.get();
        check(build(index, chats) == 2, "Tops up a page per step");
        check(chat.calls.get() - before == 2, "One page per kind, stopping at a known post");
        check(index.search(chats, "new upload", true).size() == 1, "New post found");

        // Saved: a new index answers straight away.
        TelegramIndex reopened = new TelegramIndex(dir, chat, clock::get);
        check(reopened.search(chats, "new upload", true).size() == 1, "Loaded from disk");
        check(!reopened.step(chats), "Nothing to do after loading");

        // A weekly rebuild drops files deleted from the chat, and answers meanwhile.
        chat.audio.remove(5L);
        clock.addAndGet(TelegramIndex.REBUILD_MILLIS + 1);
        check(reopened.step(chats), "Rebuild starts");
        check(reopened.search(chats, "song 5", true) != null, "Still answers while rebuilding");
        build(reopened, chats);
        boolean gone = true;
        for (JSONObject row : reopened.search(chats, "song", true))
            if (row.optString("title").equals("Song 5")) gone = false;
        check(gone, "Deleted file dropped");
        check(reopened.status(chats).startsWith("251 files"), reopened.status(chats));

        // A rate limit stops the step without losing what was there.
        clock.addAndGet(TelegramIndex.REFRESH_MILLIS + 1);
        chat.limited.set(true);
        try {
            reopened.step(chats);
            check(false, "Rate limit passed on");
        } catch (LiveSearch.RateLimited e) {
            check(e.retrySeconds == 30, "Wait passed on");
        }
        check(reopened.search(chats, "nobody", true).size() == 1, "Kept through a rate limit");
        chat.limited.set(false);

        // Off: no work, no answers, and the files are deleted.
        reopened.enabled = false;
        check(!reopened.step(chats), "No work while off");
        check(reopened.search(chats, "nobody", false) == null, "No answers while off");
        reopened.clear();
        check(Objects.requireNonNull(dir.listFiles()).length == 0, "Files deleted");
        reopened.enabled = true;
        check(reopened.search(chats, "nobody", false) == null, "Starts again empty");

        // A damaged file is rebuilt, not trusted.
        Files.write(new File(dir, "-100.json").toPath(), "{".getBytes());
        TelegramIndex damaged = new TelegramIndex(dir, chat, clock::get);
        check(damaged.search(chats, "nobody", false) == null, "Damaged file ignored");
        check(damaged.step(chats), "And rebuilt");

        System.out.println(
                "PASS: " + checks + " saved index build, top-up, rebuild and search checks.");
    }
}
