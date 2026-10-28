package app.bitchord.telegram;

import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class MainChannelTest {
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    static int nextFile = 100;

    /** A music post: FLAC files are 30 MB for 3 minutes, MP3s 7 MB. */
    static TdApi.Message post(long chat, long id, String title, boolean flac, String unique) {
        TdApi.File file = new TdApi.File();
        file.id = nextFile++;
        file.size = flac ? 30_000_000 : 7_000_000;
        if (unique != null) {
            file.remote = new TdApi.RemoteFile();
            file.remote.uniqueId = unique;
        }
        TdApi.Audio audio = new TdApi.Audio();
        audio.audio = file;
        audio.title = title;
        audio.performer = "Artist";
        audio.fileName = title + (flac ? ".flac" : ".mp3");
        audio.mimeType = flac ? "audio/flac" : "audio/mpeg";
        audio.duration = 180;
        TdApi.MessageAudio content = new TdApi.MessageAudio();
        content.audio = audio;
        TdApi.Message m = new TdApi.Message();
        m.chatId = chat;
        m.id = id;
        m.content = content;
        m.canBeSaved = true;
        return m;
    }

    /** Several chats' audio, newest first, as the index pages through them. */
    static final class Chats implements TelegramIndex.Pages {
        final Map<Long, TreeMap<Long, TdApi.Message>> chats = new HashMap<>();

        void add(TdApi.Message m) {
            chats.computeIfAbsent(m.chatId, c -> new TreeMap<>(Comparator.reverseOrder()))
                    .put(m.id, m);
        }

        @Override
        public TdApi.FoundChatMessages page(long chat, int kind, long from) {
            List<TdApi.Message> page = new ArrayList<>();
            if (kind == 0)
                for (TdApi.Message m : chats.getOrDefault(chat, new TreeMap<>()).values())
                    if ((from == 0 || m.id < from) && page.size() < 100) page.add(m);
            TdApi.FoundChatMessages found = new TdApi.FoundChatMessages();
            found.messages = page.toArray(new TdApi.Message[0]);
            return found;
        }

        @Override
        public int count(long chat, int kind) {
            return kind == 0 ? chats.getOrDefault(chat, new TreeMap<>()).size() : 0;
        }

        @Override
        public byte[] range(int file, long offset, int length) {
            return new byte[0];
        }

        @Override
        public int position(long chat, int kind, long messageId) {
            return 0;
        }
    }

    static void index(TelegramIndex index, Collection<Long> chats) throws Exception {
        for (int i = 0; i < 1000 && index.step(chats); i++) {}
    }

    public static void main(String[] args) throws Exception {
        long main = -1, a = -10, b = -20;
        Chats chats = new Chats();
        chats.add(post(main, 1, "Already Here", true, "U-shared"));
        chats.add(post(main, 2, "Same Song", true, null));
        for (long id = 1; id <= 40; id++)
            chats.add(post(a, id, "A Song " + id, id % 4 != 0, null)); // every fourth is an MP3
        chats.add(post(a, 41, "Copy Of Already Here", true, "U-shared"));
        chats.add(post(b, 5, "Same Song", true, null)); // same title, artist, length, format
        chats.add(post(b, 6, "B Song", true, null));
        AtomicLong clock = new AtomicLong(1_000_000);
        TelegramIndex index = new TelegramIndex(new IndexStore.Memory(), chats, clock::get);
        index(index, List.of(main, a, b));

        List<String> copies = new ArrayList<>();
        AtomicBoolean limited = new AtomicBoolean();
        MainChannel.Telegram telegram =
                (to, from, messages) -> {
                    if (limited.get()) throw new LiveSearch.RateLimited(120);
                    for (long m : messages) copies.add(to + "<" + from + ":" + m);
                };
        File file = Files.createTempFile("main", ".json").toFile();
        file.delete();
        MainChannel channel = new MainChannel(file, telegram, clock::get);

        // Nothing happens until it's set up and turned on.
        check(channel.step(index) == 60_000 && copies.isEmpty(), "Off by default");
        channel.setMain(main, "My Library");
        channel.setCommunity(List.of(a, b, main));
        check(channel.community().equals(List.of(a, b)), "The main channel isn't its own source");
        check(channel.indexed().equals(List.of(a, b, main)), "Index covers all three");
        check(channel.perHour() == 100, "100 an hour unless set");
        channel.setPerHour(9999);
        check(channel.perHour() == 500, "Never above 500");
        channel.setPerHour(0);
        check(channel.perHour() == 1, "At least 1");
        channel.setPerHour(120);
        check(channel.batch() == 2 && channel.gapMillis(2) == 60_000, "2 files a minute");

        channel.setOption("losslessOnly", true);
        channel.setOption("skipDuplicates", true);
        channel.setOption("import", true);
        long wait = channel.step(index);
        check(copies.equals(List.of("-1<-10:1", "-1<-10:2")), "Oldest first: " + copies);
        check(wait == 60_000, "Then waits for the pace: " + wait);
        check(channel.step(index) > 0 && copies.size() == 2, "Not early");
        clock.addAndGet(wait);
        channel.step(index);
        check(
                copies.get(2).equals("-1<-10:3") && copies.get(3).equals("-1<-10:5"),
                "MP3 4 skipped");

        // A simulated hour never goes over the limit.
        int before = copies.size();
        long start = clock.get();
        while (clock.get() - start < 3_600_000L)
            clock.addAndGet(Math.max(1000, channel.step(index)));
        check(copies.size() - before <= 120, "At most 120 in an hour: " + (copies.size() - before));
        check(!copies.contains("-1<-10:41"), "Same Telegram file as the main channel's: skipped");
        check(!copies.contains("-1<-20:5"), "Same title, artist, length and format: skipped");
        check(copies.contains("-1<-20:6"), "Second community channel too");
        check(copies.size() == 31, "30 FLACs from A and one from B: " + copies.size());
        check(channel.status.startsWith("Done: 31 copied"), channel.status);
        check(
                channel.progress(index).equals("Copied 31 · 10 lossy and 2 duplicates skipped"),
                channel.progress(index));

        // Without sync, later posts wait; with it, they're copied.
        chats.add(post(a, 42, "Posted Later", true, null));
        index.clear();
        index(index, List.of(main, a, b));
        clock.addAndGet(120_000);
        channel.step(index);
        check(!copies.contains("-1<-10:42"), "Sync off: only what was there at the start");
        channel.setOption("sync", true);
        clock.addAndGet(120_000);
        channel.step(index);
        check(copies.contains("-1<-10:42"), "Sync on: new posts copied");

        // A flood wait pauses copying until it's over, and progress survives a restart.
        chats.add(post(b, 7, "Another", true, null));
        index.clear();
        index(index, List.of(main, a, b));
        limited.set(true);
        clock.addAndGet(120_000);
        try {
            channel.step(index);
            check(false, "Rate limit passed on");
        } catch (LiveSearch.RateLimited e) {
            check(e.retrySeconds == 120, "Wait passed on");
        }
        limited.set(false);
        check(channel.step(index) > 120_000, "Waits out the pause");
        MainChannel restarted = new MainChannel(file, telegram, clock::get);
        clock.addAndGet(200_000);
        int had = copies.size();
        restarted.step(index);
        check(
                copies.size() == had + 1 && copies.get(had).equals("-1<-20:7"),
                "Carries on: " + copies.subList(had, copies.size()));
        check(restarted.option("sync") && restarted.perHour() == 120, "Settings kept");

        check(MainChannel.lossless(new JSONObject().put("format", "flac")), "FLAC");
        check(
                !MainChannel.lossless(
                        new JSONObject()
                                .put("format", "mp3")
                                .put("size", 7_000_000)
                                .put("duration", 180)),
                "MP3");
        restarted.clear();
        check(!file.exists() && restarted.main() == 0, "Sign-out forgets it");
        System.out.println(
                "PASS: " + checks + " main channel copy, pace, filter, dedupe and sync checks.");
    }
}
