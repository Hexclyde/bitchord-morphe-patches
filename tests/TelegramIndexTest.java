package app.bitchord.telegram;

import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
            found.totalCount = kind == 0 ? audio.size() : 0;
            found.messages = page.toArray(new TdApi.Message[0]);
            return found;
        }

        @Override
        public int count(long chat, int kind) {
            return kind == 0 ? audio.size() : 0;
        }

        @Override
        public byte[] range(int file, long offset, int length) {
            return new byte[0];
        }

        @Override
        public int position(long chat, int kind, long messageId) {
            int n = 0;
            if (kind == 0) for (long id : audio.keySet()) if (id >= messageId) n++;
            return n;
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

    static void realtime() throws Exception {
        IndexStore.Memory store = new IndexStore.Memory();
        FakeChat source = new FakeChat();
        TelegramIndex index = new TelegramIndex(store, source, () -> 1_000_000);
        check(
                index.message(TestMessages.audio(-100, 10, "First")),
                "A new message is searchable immediately");
        JSONObject old = index.row(-100, 10, 0);
        index.putFound(old, "names", new JSONObject().put("title", "Recognised"));
        index.message(TestMessages.audio(-100, 10, "Replacement"));
        index.putFound(old, "names", new JSONObject().put("title", "Stale"));
        index.fileFound(old, 99);
        check(
                index.row(-100, 10, 0).getString("title").equals("Replacement"),
                "Old recognition cannot rename a replacement");
        check(
                index.row(-100, 10, 0).getInt("file") == 7,
                "Old file resolution cannot replace the new file");
        check(store.found("names", "-100_10") == null, "Message-owned recognition is removed");
        store.putFound(-200, 1, "check", "shared-file-hash", new JSONObject().put("value", 1));
        index.deleted(-100, new long[] {10}, true);
        check(
                !index.message(TestMessages.audio(-100, 10, "Late event")),
                "A late event cannot undo permanent deletion");
        check(
                store.found("check", "shared-file-hash") != null,
                "Shared file evidence survives a message deletion");
        long now = store.epoch();
        store.putPage(
                -100,
                List.of(TrackMetadata.from(TestMessages.audio(-100, 10, "Stale"))),
                List.of(),
                1,
                now);
        check(index.row(-100, 10, 0) == null, "Permanent deletion rejects later stale pages");

        index.message(TestMessages.audio(-100, 20, "Temporarily unavailable"));
        long before = store.epoch();
        index.deleted(-100, new long[] {20}, false);
        JSONObject refreshed = TrackMetadata.from(TestMessages.audio(-100, 20, "Accessible again"));
        store.putPage(-100, List.of(refreshed), List.of(), 1, before);
        check(index.row(-100, 20, 0) == null, "An old page cannot reverse inaccessible state");
        store.putPage(-100, List.of(refreshed), List.of(), 1, store.epoch());
        check(
                index.row(-100, 20, 0) != null,
                "A later authoritative page can restore inaccessible media");

        TdApi.Message zip = TestMessages.document(-100, 30, "Album.zip");
        index.message(zip);
        JSONObject job = store.nextZip(List.of(-100L));
        JSONObject entry = TrackMetadata.from(TestMessages.audio(-100, 30, "Archive song"));
        entry.put("entry", 1).put("id", "-100_30_1");
        check(store.finishZip(job, List.of(entry), 1, false), "The current archive can finish");
        check(!index.message(zip), "An unchanged archive does not replace its entries");
        check(
                store.row(-100, 30, 1) != null && store.zipsWaiting(List.of(-100L)) == 0,
                "An unchanged archive keeps its songs and completion");
        index.message(TestMessages.audio(-100, 30, "Now audio"));
        check(
                store.row(-100, 30, 1) == null && store.zip(-100, 30) == null,
                "An audio edit removes every archive entry and job");
        check(
                !store.finishZip(job, List.of(entry), 1, false),
                "The previous archive cannot publish after replacement");

        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        source.add(40, "Old scan");
        source.add(20, "Old scan edit");
        TelegramIndex.Pages blocked =
                new TelegramIndex.Pages() {
                    public TdApi.FoundChatMessages page(long chat, int kind, long from)
                            throws Exception {
                        TdApi.FoundChatMessages result = source.page(chat, kind, from);
                        if (kind == 0) {
                            entered.countDown();
                            if (!release.await(3, TimeUnit.SECONDS))
                                throw new AssertionError("Page not released");
                        }
                        return result;
                    }

                    public int count(long chat, int kind) {
                        return source.count(chat, kind);
                    }

                    public int position(long chat, int kind, long message) {
                        return source.position(chat, kind, message);
                    }

                    public byte[] range(int file, long offset, int length) {
                        return new byte[0];
                    }
                };
        TelegramIndex scanning = new TelegramIndex(store, blocked, () -> 1_000_000);
        Thread worker =
                new Thread(
                        () -> {
                            try {
                                scanning.step(List.of(-100L));
                            } catch (Throwable error) {
                                failure.set(error);
                            }
                        });
        worker.start();
        try {
            check(entered.await(2, TimeUnit.SECONDS), "The old page is in flight");
            scanning.deleted(-100, new long[] {40}, true);
            scanning.message(TestMessages.audio(-100, 20, "Concurrent edit"));
        } finally {
            release.countDown();
            worker.join(4000);
        }
        check(
                !worker.isAlive() && failure.get() == null,
                "The blocked scan finishes without a worker failure");
        check(
                store.row(-100, 40, 0) == null,
                "An in-flight page cannot resurrect a deleted message");
        check(
                store.row(-100, 20, 0).getString("title").equals("Concurrent edit"),
                "An old page leaves a concurrent edit alone");
        scanning.holdChat(-100);
        check(
                scanning.search(List.of(-100L), "concurrent", false) == null
                        && scanning.row(-100, 20, 0) == null,
                "A held chat cannot return stale media");
        scanning.invalidateChat(-100);
        check(
                store.count(-100) == 0 && !store.state(-100).built(),
                "Overflow reset is limited to that chat and restarts its cursor");
        check(
                !store.finishZip(job, List.of(entry), 1, false),
                "A reset fences an earlier archive lease");
        check(
                store.found("check", "shared-file-hash") != null,
                "Reset preserves unrelated shared evidence");

        TdApi.Message currentMessage = TestMessages.audio(-100, 700, "Current native file");
        ((TdApi.MessageAudio) currentMessage.content).audio.audio.id = 88;
        index.message(currentMessage);
        JSONObject nativeRow = TrackMetadata.from(currentMessage).put("_indexCatalogueEpoch", 12);
        check(index.current(nativeRow), "An unmarked native row can match the current local file");
        index.withFound(nativeRow);
        check(
                nativeRow.optLong(IndexStore.REVISION) == store.revision(-100, 700)
                        && nativeRow.optLong(IndexStore.REVISION) > 0,
                "Matching native metadata adopts only the current local revision");
        JSONObject staleNative = TrackMetadata.from(TestMessages.audio(-100, 700, "Old file"));
        check(
                !index.current(staleNative),
                "An old native file identity is rejected after a local change");
        check(
                !index.withFound(staleNative).has(IndexStore.REVISION),
                "Rejected native metadata is not stamped");
        index.fileFound(nativeRow.put("_indexUnknown", 99), 88);
        check(
                !index.row(-100, 700, 0).has("_indexCatalogueEpoch")
                        && !index.row(-100, 700, 0).has("_indexUnknown"),
                "Transient catalogue and foreign local fields are not persisted");
        index.enabled = false;
        check(
                index.current(staleNative) && !index.current(nativeRow),
                "Disabled indexing permits raw native rows but rejects old owned rows");
        index.enabled = true;
        index.holdChat(-100);
        check(
                !index.current(TrackMetadata.from(currentMessage)),
                "Held chats reject native enrichment");
        index.invalidateChat(-100);

        TdApi.Message stable = TestMessages.audio(-100, 710, "Stable remote identity");
        TdApi.File stableFile = ((TdApi.MessageAudio) stable.content).audio.audio;
        stableFile.remote = new TdApi.RemoteFile();
        stableFile.remote.uniqueId = "stable-remote-file";
        index.message(stable);
        JSONObject renumbered = TrackMetadata.from(stable).put("file", 123);
        check(
                index.current(renumbered),
                "Stable remote identity survives a local file-number change");
        index.withFound(renumbered);
        check(
                renumbered.has(IndexStore.REVISION),
                "The renumbered file adopts its current local revision");
        index.fileFound(renumbered, 123);
        check(
                index.row(-100, 710, 0).getInt("file") == 123,
                "The verified new file number can be persisted");
        check(
                !index.current(
                        TrackMetadata.from(stable)
                                .put("unique", "different-remote-file")
                                .put("file", 123)),
                "An equal file number cannot override a different remote identity");
        check(
                !index.current(TrackMetadata.from(stable).put("size", stableFile.size + 1)),
                "A changed size does not match the stable remote identity");
        check(
                !index.current(TrackMetadata.from(stable).put("entry", 1).put("file", 123)),
                "A different archive entry cannot borrow another entry's identity");

        IndexStore.Memory refreshedStore = new IndexStore.Memory();
        FakeChat refreshedChat = new FakeChat();
        refreshedChat.add(100, "Old frontier");
        AtomicLong time = new AtomicLong(1_000_000);
        TelegramIndex refreshedIndex = new TelegramIndex(refreshedStore, refreshedChat, time::get);
        build(refreshedIndex, Collections.singleton(-100L));
        for (long id = 101; id <= 250; id++) refreshedChat.add(id, "Missed " + id);
        refreshedChat.add(300, "Live newest");
        refreshedIndex.message(refreshedChat.audio.get(300L));
        time.addAndGet(TelegramIndex.REFRESH_MILLIS + 1);
        check(refreshedIndex.step(List.of(-100L)), "A paced catch-up starts");
        TelegramIndex restarted = new TelegramIndex(refreshedStore, refreshedChat, time::get);
        build(restarted, Collections.singleton(-100L));
        check(
                refreshedStore.count(-100) == 152 && refreshedStore.row(-100, 101, 0) != null,
                "A known live newest post and a restart do not hide older missed posts");

        restarted.deleted(-100, new long[] {300}, true);
        IndexTransfer.Section imported = new IndexTransfer.Section();
        imported.chat = -100;
        imported.rows.add(
                TrackMetadata.from(TestMessages.audio(-100, 300, "Forged deleted row"))
                        .put(IndexStore.REVISION, refreshedStore.epoch()));
        imported.rows.add(
                TrackMetadata.from(TestMessages.audio(-100, 500, "Shared new row"))
                        .put(IndexStore.REVISION, 999)
                        .put(IndexStore.SOURCE_MESSAGE, 300)
                        .put(IndexStore.SOURCE_REVISION, 999)
                        .put(IndexStore.SCANNED, false));
        check(
                restarted.importSection(imported) == 1,
                "Shared imports cannot reverse local deletion");
        JSONObject importedRow = restarted.row(-100, 500, 0);
        check(
                importedRow != null
                        && !importedRow.has(IndexStore.SOURCE_MESSAGE)
                        && importedRow.optLong(IndexStore.REVISION) != 999,
                "Foreign revision and dependency fields are ignored");
        for (JSONObject exported : restarted.exportRows(-100, 0, 1000))
            check(
                    !exported.has(IndexStore.REVISION)
                            && !exported.has(IndexStore.SOURCE_MESSAGE)
                            && !exported.has(IndexStore.SOURCE_REVISION)
                            && !exported.has(IndexStore.SCANNED),
                    "Exports omit local ownership fields");

        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream archive = new java.util.zip.ZipOutputStream(bytes)) {
            archive.putNextEntry(new java.util.zip.ZipEntry("Artist - Old song.mp3"));
            archive.write(new byte[1024]);
            archive.closeEntry();
        }
        byte[] archive = bytes.toByteArray();
        CountDownLatch reading = new CountDownLatch(1), finish = new CountDownLatch(1);
        IndexStore.Memory archiveStore = new IndexStore.Memory();
        TelegramIndex.Pages archivePages =
                new TelegramIndex.Pages() {
                    public TdApi.FoundChatMessages page(long chat, int kind, long from) {
                        return null;
                    }

                    public int count(long chat, int kind) {
                        return 0;
                    }

                    public int position(long chat, int kind, long message) {
                        return 0;
                    }

                    public byte[] range(int file, long offset, int length) throws Exception {
                        reading.countDown();
                        if (!finish.await(3, TimeUnit.SECONDS))
                            throw new AssertionError("Archive not released");
                        return Arrays.copyOfRange(
                                archive,
                                (int) offset,
                                (int) Math.min(archive.length, offset + length));
                    }
                };
        TelegramIndex archiveIndex = new TelegramIndex(archiveStore, archivePages, time::get);
        AtomicInteger staleNotices = new AtomicInteger();
        archiveIndex.onExpanded = (chatId, messageId) -> staleNotices.incrementAndGet();
        TdApi.Message archived = TestMessages.document(-100, 600, "Old.zip");
        ((TdApi.MessageDocument) archived.content).document.document.size = archive.length;
        archiveIndex.message(archived);
        AtomicReference<Throwable> archiveFailure = new AtomicReference<>();
        Thread zipWorker =
                new Thread(
                        () -> {
                            try {
                                archiveIndex.step(List.of(-100L));
                            } catch (Throwable error) {
                                archiveFailure.set(error);
                            }
                        });
        zipWorker.start();
        try {
            check(reading.await(2, TimeUnit.SECONDS), "The archive worker is reading old bytes");
            archiveIndex.message(TestMessages.audio(-100, 600, "New audio"));
        } finally {
            finish.countDown();
            zipWorker.join(4000);
        }
        check(
                !zipWorker.isAlive() && archiveFailure.get() == null,
                "The archive worker finishes cleanly");
        check(
                archiveStore.count(-100) == 1
                        && archiveStore.row(-100, 600, 1) == null
                        && archiveStore.row(-100, 600, 0).getString("title").equals("New audio"),
                "A blocked archive cannot resurrect entries after an audio edit");
        check(
                staleNotices.get() == 0,
                "A rejected archive lease cannot publish a catalogue update");

        IndexStore.Memory publishedStore = new IndexStore.Memory();
        TelegramIndex published = new TelegramIndex(publishedStore, archivePages, time::get);
        AtomicInteger notices = new AtomicInteger();
        AtomicBoolean cachedEmpty = new AtomicBoolean(true);
        published.onExpanded =
                (chatId, messageId) -> {
                    check(
                            chatId == -100 && messageId == 600,
                            "The expanded archive publishes its exact family");
                    check(
                            !Thread.holdsLock(publishedStore),
                            "Expansion notification runs outside the store lock");
                    try {
                        check(
                                published.search(List.of(-100L), "old song", false).size() == 1,
                                "The notification observes committed searchable archive children");
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                    cachedEmpty.set(false);
                    notices.incrementAndGet();
                };
        published.message(archived);
        check(
                published.search(List.of(-100L), "old song", false) == null,
                "A query can finish empty before a new archive is expanded");
        published.step(List.of(-100L));
        check(
                notices.get() == 1 && !cachedEmpty.get(),
                "Live expansion invalidates the earlier empty-query result");
        check(
                !published.message(archived),
                "Duplicate completed archive delivery keeps the existing children");
        published.step(List.of(-100L));
        check(notices.get() == 1, "A completed archive does not publish twice");

        IndexStore.Memory historicalStore = new IndexStore.Memory();
        historicalStore.clear();
        historicalStore.putPage(
                -100,
                List.of(),
                List.of(ZipAlbums.descriptor(archived)),
                1,
                historicalStore.epoch());
        TelegramIndex historical = new TelegramIndex(historicalStore, archivePages, time::get);
        AtomicInteger historicalNotices = new AtomicInteger();
        historical.onExpanded = (chatId, messageId) -> historicalNotices.incrementAndGet();
        historical.step(List.of(-100L));
        check(
                historicalStore.count(-100) == 1 && historicalNotices.get() == 0,
                "Ordinary historical expansion after a reset does not invalidate live searches");

        java.io.ByteArrayOutputStream emptyBytes = new java.io.ByteArrayOutputStream();
        new java.util.zip.ZipOutputStream(emptyBytes).close();
        for (byte[] content : new byte[][] {emptyBytes.toByteArray(), new byte[] {1, 2, 3}}) {
            CueTest.Chat emptyChat = new CueTest.Chat();
            emptyChat.add(-300, 42, "Empty.zip", 22, content);
            IndexStore.Memory emptyStore = new IndexStore.Memory();
            TelegramIndex emptyIndex = new TelegramIndex(emptyStore, emptyChat, time::get);
            AtomicInteger emptyNotices = new AtomicInteger();
            emptyIndex.onExpanded = (chatId, messageId) -> emptyNotices.incrementAndGet();
            emptyIndex.message(emptyChat.documents.get(0));
            emptyIndex.step(List.of(-300L));
            check(
                    emptyStore.count(-300) == 0 && emptyNotices.get() == 0,
                    "Empty and unreadable archives do not publish nonexistent children");
        }

        CueTest.Chat cueChat = new CueTest.Chat();
        cueChat.add(-200, 10, "Album.wav", 21, ZipDirectoryTest.wav(8000, 3));
        cueChat.add(
                -200,
                11,
                "Album.cue",
                22,
                ("FILE \"Album.wav\" WAVE\n TRACK 01 AUDIO\n TITLE \"First\"\n INDEX 01 00:00:00\n"
                                + " TRACK 02 AUDIO\n TITLE \"Second\"\n INDEX 01 00:01:00\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        IndexStore.Memory cueStore = new IndexStore.Memory();
        TelegramIndex cueIndex = new TelegramIndex(cueStore, cueChat, time::get);
        cueIndex.message(cueChat.documents.get(0));
        cueIndex.message(cueChat.documents.get(1));
        Set<Long> cueFamilies = new HashSet<>();
        cueIndex.onExpanded =
                (chatId, messageId) -> {
                    check(
                            chatId == -200 && !Thread.holdsLock(cueStore),
                            "Cue expansion publishes outside its store lock");
                    cueFamilies.add(messageId);
                };
        cueIndex.step(List.of(-200L));
        check(
                cueFamilies.equals(Set.of(10L, 11L)),
                "A cue expansion publishes both the sheet and album families");
        check(
                cueIndex.row(-200, 10, CueAlbums.BASE + 1) != null,
                "The published cue album contains its committed child tracks");
    }

    static void buildResetRace() throws Exception {
        IndexStore.Memory saved = new IndexStore.Memory();
        IndexStore.State rebuilding = saved.state(-100);
        rebuilding.generation = 5;
        rebuilding.rebuilding = true;
        rebuilding.complete[0] = true;
        Arrays.fill(rebuilding.total, 0);
        saved.save(rebuilding);
        saved.put(
                -100,
                List.of(TrackMetadata.from(TestMessages.audio(-100, 1, "Old generation"))),
                4);
        CountDownLatch pageWritten = new CountDownLatch(1),
                release = new CountDownLatch(1),
                resetDone = new CountDownLatch(1);
        AtomicReference<Thread> builder = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean paused = new AtomicBoolean();
        IndexStore store =
                (IndexStore)
                        java.lang.reflect.Proxy.newProxyInstance(
                                IndexStore.class.getClassLoader(),
                                new Class<?>[] {IndexStore.class},
                                (proxy, method, arguments) -> {
                                    Object result;
                                    try {
                                        result = method.invoke(saved, arguments);
                                    } catch (java.lang.reflect.InvocationTargetException error) {
                                        throw error.getCause();
                                    }
                                    if (method.getName().equals("putPage")
                                            && Thread.currentThread() == builder.get()
                                            && paused.compareAndSet(false, true)) {
                                        pageWritten.countDown();
                                        if (!release.await(4, TimeUnit.SECONDS))
                                            throw new AssertionError("Old build not released");
                                    }
                                    return result;
                                });
        FakeChat source = new FakeChat();
        TelegramIndex index = new TelegramIndex(store, source, () -> 1_000_000);
        Thread oldBuild =
                new Thread(
                        () -> {
                            try {
                                index.step(List.of(-100L));
                            } catch (Throwable error) {
                                failure.compareAndSet(null, error);
                            }
                        });
        builder.set(oldBuild);
        Thread reset =
                new Thread(
                        () -> {
                            try {
                                index.invalidateChat(-100);
                                index.message(TestMessages.audio(-100, 70, "New live message"));
                                saved.putPage(
                                        -100,
                                        List.of(
                                                TrackMetadata.from(
                                                        TestMessages.audio(
                                                                -100, 80, "Fresh generation"))),
                                        List.of(),
                                        1,
                                        saved.epoch());
                            } catch (Throwable error) {
                                failure.compareAndSet(null, error);
                            } finally {
                                resetDone.countDown();
                            }
                        });
        oldBuild.start();
        try {
            check(
                    pageWritten.await(2, TimeUnit.SECONDS),
                    "The old rebuild has written its final page");
            reset.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (resetDone.getCount() != 0
                    && reset.getState() != Thread.State.BLOCKED
                    && System.nanoTime() < deadline)
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            check(
                    resetDone.getCount() == 0 || reset.getState() == Thread.State.BLOCKED,
                    "Reset reaches the paused commit boundary");
        } finally {
            release.countDown();
            oldBuild.join(4000);
            if (reset.getState() != Thread.State.NEW) reset.join(4000);
        }
        check(
                !oldBuild.isAlive() && !reset.isAlive() && failure.get() == null,
                "The old rebuild and reset finish without worker errors: " + failure.get());
        check(
                saved.row(-100, 70, 0) != null,
                "Old rebuild cleanup leaves the new live message alone");
        check(
                saved.row(-100, 80, 0) != null,
                "Old rebuild cleanup cannot prune a fresh post-reset generation");
        check(
                !saved.state(-100).built() && saved.state(-100).generation == 1,
                "Old completion and cursor state cannot replace the reset state");
    }

    public static void main(String[] args) throws Exception {
        IndexStore.Memory store = new IndexStore.Memory();
        AtomicLong clock = new AtomicLong(1_000_000);
        FakeChat chat = new FakeChat();
        for (long id = 1; id <= 250; id++) chat.add(id, "Song " + id);
        chat.add(300, "Nobody Knows You When You're Down and Out");
        Set<Long> chats = Collections.singleton(-100L);
        TelegramIndex index = new TelegramIndex(store, chat, clock::get);

        check(index.search(chats, "song", false) == null, "Nothing to answer from yet");
        check(index.step(chats), "Starts building");
        check(index.search(chats, "song", true) == null, "Not complete yet");
        check(index.search(chats, "song", false).size() == 99, "A partial index still answers");
        check(
                index.status(chats).equals("Indexing: 100 files found · 39%"),
                "Progress against Telegram's count: " + index.status(chats));
        clock.addAndGet(60_000);
        check(index.step(chats), "Second page");
        check(
                index.status(chats).equals("Indexing: 200 files found · 79% · about 1 min left"),
                "Time left from the pace so far: " + index.status(chats));
        int steps = build(index, chats);
        // Pages of 100, 100 and 51, an empty one to finish the audio, one for documents.
        check(steps == 3 && chat.calls.get() == 5, "One page per step: " + chat.calls.get());
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
                IndexStore.looseWords("Ain’t No Sunshine").containsAll(List.of("aint", "ain", "t")),
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

        // Saved: a new index on the same store answers straight away.
        TelegramIndex reopened = new TelegramIndex(store, chat, clock::get);
        check(reopened.search(chats, "new upload", true).size() == 1, "Loaded from disk");
        check(!reopened.step(chats), "Nothing to do after loading");

        // A monthly rebuild drops files deleted from the chat, and answers meanwhile.
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
        check(store.count(-100) == 0, "Files deleted");
        reopened.enabled = true;
        check(reopened.search(chats, "nobody", false) == null, "Starts again empty");
        check(reopened.step(chats), "And builds again");

        // Playlists and downloads take a chat's newest files, oldest first.
        build(reopened, chats);
        List<JSONObject> newest = reopened.newest(-100, 3);
        check(
                titles(newest)
                        .equals(
                                List.of(
                                        "Song 250",
                                        "Nobody Knows You When You're Down and Out",
                                        "New Upload")),
                "Newest three, in posting order: " + titles(newest));
        check(reopened.newest(-200, 3) == null, "Not for a chat that isn't indexed");
        check(reopened.count(-100) == 251, "Count, without the deleted file");

        // A build that 1.0.8 started picks up its progress from where it had got to.
        IndexStore.Memory old = new IndexStore.Memory();
        old.save(
                IndexStore.State.fromJson(
                        -100,
                        new JSONObject(
                                "{\"cursor\":[151,0],\"complete\":[false,false],\"total\":[0,0],"
                                        + "\"builtAt\":0,\"refreshed\":0}")));
        TelegramIndex resumed = new TelegramIndex(old, chat, clock::get);
        check(resumed.step(chats), "Carries on");
        check(
                resumed.status(chats).equals("Indexing: 100 files found · 80%"),
                "Read count from the message position: " + resumed.status(chats));
        check(TelegramIndex.timeLeft(100).equals("1 h 40 min"), "Hours");
        check(TelegramIndex.timeLeft(3 * 1440).equals("3 days"), "Days");
        check(TelegramIndex.timeLeft(0.2).equals("1 min"), "At least a minute");

        realtime();
        buildResetRace();
        System.out.println(
                "PASS: " + checks + " saved index build, top-up, rebuild and search checks.");
    }
}
