package app.bitchord.telegram;

import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TelegramCatalogueWorkerTest {
    private static int checks;

    private static void require(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private interface DuringLookup {
        void run() throws Exception;
    }

    private static final class Fixture implements TelegramCatalogueWorker.Host {
        final Object lock = new Object();
        final Set<Long> selected = new HashSet<>(Arrays.asList(-10L, -20L));
        final IndexStore.Memory store = new IndexStore.Memory();
        final TelegramIndex index = new TelegramIndex(store, null, () -> 1_000_000);
        final TelegramCatalogueUpdates updates =
                new TelegramCatalogueUpdates(selected::contains, index::holdChat);
        final java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong(1000);
        final TelegramCatalogueWorker worker =
                new TelegramCatalogueWorker(index, updates, this, lock, clock::get);
        final Map<String, TdApi.Message> messages = new HashMap<>();
        final List<Map<Long, Set<Long>>> notices = new ArrayList<>();
        final List<Exception> failures = new ArrayList<>();
        boolean ready = true;
        long generation = 1;
        int lookups, noticeAttempts, noticeFailures;
        DuringLookup duringLookup;

        @Override
        public boolean ready() {
            return ready;
        }

        @Override
        public long generation() {
            return generation;
        }

        @Override
        public boolean selected(long chat) {
            return selected.contains(chat);
        }

        @Override
        public TdApi.Message lookup(long chat, long message) throws Exception {
            require(
                    !Thread.holdsLock(lock),
                    "Message retrieval happens outside the catalogue lock");
            lookups++;
            if (duringLookup != null) duringLookup.run();
            return messages.get(chat + "_" + message);
        }

        @Override
        public void changed(Map<Long, Set<Long>> changed) throws Exception {
            require(!Thread.holdsLock(lock), "Batch observers run outside the catalogue lock");
            noticeAttempts++;
            if (noticeFailures > 0) {
                noticeFailures--;
                throw new IOException("Cache notice unavailable");
            }
            Map<Long, Set<Long>> copy = new LinkedHashMap<>();
            for (Map.Entry<Long, Set<Long>> entry : changed.entrySet())
                copy.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
            notices.add(copy);
        }

        @Override
        public void failed(Exception failure) {
            failures.add(failure);
        }

        void post(TdApi.Message message) {
            updates.accept(new TdApi.UpdateNewMessage(message), generation);
        }

        void edit(long chat, long id, TdApi.Message result) {
            messages.put(chat + "_" + id, result);
            updates.accept(new TdApi.UpdateMessageContent(chat, id, null), generation);
        }

        void delete(long chat, long id, boolean permanent, boolean cached) {
            updates.accept(
                    new TdApi.UpdateDeleteMessages(chat, new long[] {id}, permanent, cached),
                    generation);
        }

        String title(long chat, long id) throws Exception {
            JSONObject row = index.row(chat, id, 0);
            return row == null ? null : row.getString("title");
        }
    }

    private static void additionsAndDeletions() throws Exception {
        Fixture f = new Fixture();
        f.post(TestMessages.audio(-10, 100, "Incoming"));
        f.post(TestMessages.audio(-20, 100, "Other chat"));
        f.post(TestMessages.document(-10, 200, "Album.zip"));
        require(
                f.worker.drain() == TelegramCatalogueWorker.Outcome.PROCESSED,
                "A received batch reaches the real saved index");
        require(
                "Incoming".equals(f.title(-10, 100)) && "Other chat".equals(f.title(-20, 100)),
                "Audio additions become visible in their respective chats");
        require(
                f.index.search(f.selected, "Incoming", false).size() == 1,
                "A received addition is immediately searchable without pagination");
        require(
                f.store.zip(-10, 200) != null,
                "A ZIP addition queues the actual archive descriptor");
        require(
                f.notices.size() == 1
                        && f.notices
                                .get(0)
                                .get(-10L)
                                .equals(new LinkedHashSet<>(Arrays.asList(100L, 200L)))
                        && f.notices.get(0).get(-20L).equals(Set.of(100L)),
                "One batch notice identifies all affected messages across chats");
        f.delete(-10, 100, false, true);
        require(
                f.worker.drain() == TelegramCatalogueWorker.Outcome.IDLE
                        && "Incoming".equals(f.title(-10, 100)),
                "Local TDLib cache eviction leaves searchable audio unchanged");
        f.delete(-10, 100, true, false);
        f.delete(-10, 200, true, false);
        f.worker.drain();
        require(
                f.title(-10, 100) == null && f.store.zip(-10, 200) == null,
                "Permanent deletion removes audio and pending archive work");
        require(
                f.store.deleted(-10, 100) && f.store.deleted(-10, 200),
                "Removed posts carry permanent tombstones");
        require(
                "Other chat".equals(f.title(-20, 100)),
                "Deleting one chat does not remove the same identifier elsewhere");
        int notices = f.notices.size();
        f.post(TestMessages.audio(-10, 100, "Late replay"));
        f.worker.drain();
        require(
                f.title(-10, 100) == null && f.notices.size() == notices,
                "A late post cannot resurrect a deleted message or announce an addition");
    }

    private static void contentReplacement() throws Exception {
        Fixture f = new Fixture();
        f.index.message(TestMessages.audio(-10, 100, "Old title"));
        long revision = f.store.revision(-10, 100);
        f.edit(-10, 100, TestMessages.audio(-10, 100, "New title"));
        require(
                f.worker.drain() == TelegramCatalogueWorker.Outcome.PROCESSED
                        && "New title".equals(f.title(-10, 100))
                        && f.store.revision(-10, 100) > revision,
                "A content event fetches and replaces the saved audio metadata");
        f.edit(-10, 100, TestMessages.document(-10, 100, "Replacement.zip"));
        f.worker.drain();
        require(
                f.title(-10, 100) == null && f.store.zip(-10, 100) != null,
                "An audio-to-ZIP edit replaces the row with archive work");
        TdApi.Message text = TestMessages.audio(-10, 100, "Unused");
        text.content = new TdApi.MessageText();
        f.edit(-10, 100, text);
        f.worker.drain();
        require(
                f.title(-10, 100) == null
                        && f.store.zip(-10, 100) == null
                        && !f.store.deleted(-10, 100)
                        && f.store.unavailable(-10, 100),
                "A non-media replacement removes rows and ZIP work without permanent deletion");
        f.post(TestMessages.audio(-10, 100, "Accessible again"));
        f.worker.drain();
        require(
                "Accessible again".equals(f.title(-10, 100)),
                "A later authoritative media post can replace an inaccessible state");
        f.edit(-10, 100, null);
        f.worker.drain();
        require(
                f.title(-10, 100) == null && !f.store.deleted(-10, 100),
                "An authoritative missing-message result uses a non-permanent removal");
        require(f.lookups == 4, "Only content events retrieve the current post");
    }

    private static void changedSessionDuringLookup() throws Exception {
        Fixture f = new Fixture();
        f.index.message(TestMessages.audio(-10, 100, "Original"));
        long epoch = f.store.epoch();
        f.edit(-10, 100, TestMessages.audio(-10, 100, "Obsolete"));
        f.duringLookup =
                () -> {
                    synchronized (f.lock) {
                        f.generation = 2;
                        f.updates.sessionChanged(2);
                    }
                };
        require(
                f.worker.drain() == TelegramCatalogueWorker.Outcome.PROCESSED,
                "Old-session lookup completion releases its batch");
        require(
                "Original".equals(f.store.row(-10, 100, 0).getString("title"))
                        && f.notices.isEmpty(),
                "An old-session reply neither replaces current data nor announces it");
        f.duringLookup = null;
        f.worker.drain();
        require(
                f.store.epoch() > epoch && f.title(-10, 100) == null,
                "Current-session reset repairs the held catalogue and advances its epoch");
        f.post(TestMessages.audio(-10, 100, "Current"));
        f.worker.drain();
        require(
                "Current".equals(f.title(-10, 100)),
                "A new-session event remains usable after the old batch finishes");
    }

    private static void selectionAndReadiness() throws Exception {
        Fixture f = new Fixture();
        f.post(TestMessages.audio(-10, 100, "Waiting"));
        f.ready = false;
        require(
                f.worker.drain() == TelegramCatalogueWorker.Outcome.IDLE
                        && f.updates.pending() == 1
                        && f.store.count(-10) == 0,
                "An unready account leaves received work queued without touching the index");
        f.ready = true;
        f.worker.drain();
        f.edit(-10, 100, TestMessages.audio(-10, 100, "Unselected reply"));
        f.duringLookup = () -> f.selected.remove(-10L);
        f.worker.drain();
        require(
                "Waiting".equals(f.store.row(-10, 100, 0).getString("title"))
                        && f.notices.size() == 1,
                "Deselecting a chat while lookup runs prevents applying and announcing its reply");
        f.selected.add(-10L);
        f.duringLookup = () -> f.ready = false;
        f.edit(-10, 100, TestMessages.audio(-10, 100, "Unready reply"));
        f.worker.drain();
        require(
                "Waiting".equals(f.store.row(-10, 100, 0).getString("title")),
                "Authorization readiness is checked again after retrieval");
    }

    private static void deferredLookup() throws Exception {
        for (boolean wrongIdentity : new boolean[] {false, true}) {
            Fixture f = new Fixture();
            f.index.message(TestMessages.audio(-10, 100, "Original"));
            f.edit(-10, 100, TestMessages.audio(-20, 999, "Unrelated"));
            if (!wrongIdentity)
                f.duringLookup =
                        () -> {
                            throw new IOException("Try later");
                        };
            f.post(TestMessages.audio(-20, 300, "Independent"));
            require(
                    f.worker.drain() == TelegramCatalogueWorker.Outcome.DEFERRED,
                    "Transient failure and mismatched lookup identity defer the affected chat");
            require(
                    f.failures.size() == 1 && f.updates.resets() == 1,
                    "A failed current-generation update schedules one repair");
            require(
                    "Original".equals(f.store.row(-10, 100, 0).getString("title"))
                            && "Independent".equals(f.title(-20, 300)),
                    "Lookup failure does not overwrite the row or prevent independent batch work");
            require(
                    f.notices.size() == 1
                            && f.notices.get(0).get(-10L).contains(0L)
                            && f.notices.get(0).get(-20L).contains(300L),
                    "One cache notice includes chat repair and independent successful changes");
            f.duringLookup = null;
            require(
                    f.worker.drain() == TelegramCatalogueWorker.Outcome.PROCESSED
                            && f.title(-10, 100) == null,
                    "A deferred batch releases ownership so the queued repair can run");
        }
    }

    private static void notificationRetry() throws Exception {
        Fixture f = new Fixture();
        f.noticeFailures = 2;
        f.post(TestMessages.audio(-10, 100, "Saved once"));
        require(
                f.worker.drain() == TelegramCatalogueWorker.Outcome.DEFERRED,
                "A cache observer failure reports deferred notification");
        long revision = f.store.revision(-10, 100);
        require(
                "Saved once".equals(f.title(-10, 100)) && f.updates.resets() == 0,
                "Observer failure neither removes valid audio nor rebuilds its chat");
        f.post(TestMessages.audio(-20, 200, "Next batch"));
        require(
                f.worker.drain() == TelegramCatalogueWorker.Outcome.DEFERRED
                        && f.updates.pending() == 1
                        && f.store.count(-20) == 0,
                "A pending failed notice is retried before consuming another batch");
        require(
                f.store.revision(-10, 100) == revision && f.failures.size() == 2,
                "Retrying notification never repeats index replacement or advances its revision");
        require(
                f.worker.drain() == TelegramCatalogueWorker.Outcome.PROCESSED
                        && f.notices.size() == 2
                        && f.notices.get(0).get(-10L).equals(Set.of(100L))
                        && f.notices.get(1).get(-20L).equals(Set.of(200L)),
                "Successful retry announces the original batch once before the next batch");
        require(
                f.store.revision(-10, 100) == revision
                        && f.worker.drain() == TelegramCatalogueWorker.Outcome.IDLE,
                "A delivered notice leaves neither duplicate work nor another notification");
    }

    private static void notificationGeneration() throws Exception {
        Fixture f = new Fixture();
        f.noticeFailures = 1;
        f.post(TestMessages.audio(-10, 100, "Earlier account"));
        f.worker.drain();
        f.generation = 2;
        f.post(TestMessages.audio(-20, 200, "Current account"));
        require(
                f.worker.drain() == TelegramCatalogueWorker.Outcome.PROCESSED
                        && f.notices.size() == 1
                        && !f.notices.get(0).containsKey(-10L)
                        && f.notices.get(0).containsKey(-20L),
                "A stale generation's undelivered notification is discarded before new work");
    }

    private static void batchingAndDisabledIndex() throws Exception {
        Fixture f = new Fixture();
        for (int id = 1; id <= 35; id++) f.post(TestMessages.audio(-10, id, "Song " + id));
        f.worker.drain();
        require(
                f.store.count(-10) == 32
                        && f.updates.pending() == 3
                        && f.notices.size() == 1
                        && f.notices.get(0).get(-10L).size() == 32,
                "A worker pass applies at most thirty-two posts and announces one bounded batch");
        f.worker.drain();
        require(
                f.store.count(-10) == 35 && f.notices.size() == 2,
                "The next bounded pass completes the remaining posts");
        f.index.enabled = false;
        f.edit(-10, 1, TestMessages.audio(-10, 1, "Disabled edit"));
        f.delete(-10, 2, true, false);
        f.worker.drain();
        require(
                f.lookups == 0
                        && f.store.count(-10) == 35
                        && f.notices.get(2).get(-10L).equals(Set.of(1L, 2L)),
                "A disabled saved index clears source caches without retrieval or row mutation");
        f.updates.reset(-10, 1);
        f.worker.drain();
        require(
                f.store.count(-10) == 0,
                "A reset invalidates obsolete rows even while the saved index is disabled");
    }

    private static void publishedArchive() throws Exception {
        Fixture f = new Fixture();
        f.index.message(TestMessages.document(-10, 200, "Published.zip"));
        JSONObject descriptor = f.store.zip(-10, 200);
        long epoch = f.store.epoch(), revision = f.store.revision(-10, 200);
        f.updates.published(-10, 200, 1);
        require(
                f.worker.drain() == TelegramCatalogueWorker.Outcome.PROCESSED
                        && f.notices.size() == 1
                        && f.notices.get(0).get(-10L).equals(Set.of(200L)),
                "Archive publication sends one real batch cache notification");
        require(
                f.lookups == 0
                        && f.store.epoch() == epoch
                        && f.store.revision(-10, 200) == revision
                        && IndexStore.content(descriptor)
                                .equals(IndexStore.content(f.store.zip(-10, 200))),
                "Publication neither fetches the post nor mutates or requeues archive contents");
        require(
                f.worker.drain() == TelegramCatalogueWorker.Outcome.IDLE,
                "A delivered archive publication has no repeated notification");
        f.edit(-10, 200, TestMessages.audio(-10, 200, "Edited after expansion"));
        f.updates.published(-10, 200, 1);
        f.worker.drain();
        require(
                f.lookups == 1
                        && "Edited after expansion".equals(f.title(-10, 200))
                        && f.store.zip(-10, 200) == null
                        && f.notices.size() == 2,
                "A coalesced publication cannot hide a genuine content replacement");
        f.updates.published(-10, 200, 1);
        f.delete(-10, 200, true, false);
        f.worker.drain();
        require(
                f.title(-10, 200) == null && f.store.deleted(-10, 200),
                "A deletion supersedes publication without preserving stale audio");
    }

    private static void metadataPause() throws Exception {
        Fixture f = new Fixture();
        f.index.message(TestMessages.audio(-10, 100, "Old first"));
        f.index.message(TestMessages.audio(-10, 200, "Old second"));
        f.index.message(TestMessages.audio(-20, 400, "Removed during pause"));
        f.edit(-10, 100, TestMessages.audio(-10, 100, "New first"));
        f.edit(-10, 200, TestMessages.audio(-10, 200, "New second"));
        f.duringLookup =
                () -> {
                    f.duringLookup = null;
                    throw new LiveSearch.RateLimited(10);
                };
        f.post(TestMessages.audio(-20, 300, "Added during pause"));
        f.delete(-20, 400, true, false);
        require(
                f.worker.drain() == TelegramCatalogueWorker.Outcome.DEFERRED && f.lookups == 1,
                "The first flood wait prevents every later metadata lookup in that batch");
        require(
                f.title(-10, 100) == null
                        && f.title(-10, 200) == null
                        && f.store.unavailable(-10, 100)
                        && f.store.unavailable(-10, 200)
                        && !f.store.deleted(-10, 100)
                        && f.updates.resets() == 0,
                "Deferred edits hide old rows without permanent deletion or chat rebuild");
        require(
                "Added during pause".equals(f.title(-20, 300)) && f.store.deleted(-20, 400),
                "New posts and permanent deletions still apply within the paused batch");
        long epoch = f.store.epoch(), firstRevision = f.store.revision(-10, 100);
        int notices = f.notices.size();
        for (int attempt = 0; attempt < 3; attempt++)
            require(
                    f.worker.drain() == TelegramCatalogueWorker.Outcome.DEFERRED,
                    "Only deferred content reports backoff rather than an idle busy loop");
        require(
                f.lookups == 1
                        && f.store.epoch() == epoch
                        && f.store.revision(-10, 100) == firstRevision
                        && f.notices.size() == notices,
                "Repeated paused drains perform no lookup, quarantine write or cache notification");
        f.edit(-10, 200, TestMessages.audio(-10, 200, "Latest second"));
        f.delete(-10, 100, true, false);
        f.post(TestMessages.audio(-20, 500, "Another available post"));
        f.worker.drain();
        require(
                f.lookups == 1
                        && f.store.deleted(-10, 100)
                        && "Another available post".equals(f.title(-20, 500)),
                "Latest deletion and authoritative new audio pass metadata waiting for retry");
        epoch = f.store.epoch();
        f.clock.set(10999);
        require(
                f.worker.drain() == TelegramCatalogueWorker.Outcome.DEFERRED
                        && f.lookups == 1
                        && f.store.epoch() == epoch,
                "The complete monotonic pause is respected without repeating unavailable writes");
        f.clock.set(11000);
        require(
                f.worker.drain() == TelegramCatalogueWorker.Outcome.PROCESSED
                        && f.lookups == 2
                        && "Latest second".equals(f.title(-10, 200))
                        && f.store.deleted(-10, 100)
                        && !f.store.unavailable(-10, 200),
                "At the deadline only the latest surviving edit restores its authoritative audio");
    }

    private static void rateLimitedGeneration() throws Exception {
        Fixture f = new Fixture();
        f.index.message(TestMessages.audio(-10, 100, "Earlier audio"));
        f.edit(-10, 100, TestMessages.audio(-10, 100, "Earlier reply"));
        f.duringLookup =
                () -> {
                    f.duringLookup = null;
                    throw new LiveSearch.RateLimited(5);
                };
        f.worker.drain();
        f.generation = 2;
        f.updates.sessionChanged(2);
        f.worker.drain();
        f.post(TestMessages.audio(-10, 100, "Current session"));
        f.worker.drain();
        require(
                "Current session".equals(f.title(-10, 100)) && f.lookups == 1,
                "Current-session reset and new posts replace old quarantined work during a pause");
        f.clock.set(6000);
        require(
                f.worker.drain() == TelegramCatalogueWorker.Outcome.IDLE
                        && f.lookups == 1
                        && "Current session".equals(f.title(-10, 100)),
                "The old generation's edit is not resurrected when its pause expires");
    }

    public static void main(String[] args) throws Exception {
        additionsAndDeletions();
        contentReplacement();
        changedSessionDuringLookup();
        selectionAndReadiness();
        deferredLookup();
        notificationRetry();
        notificationGeneration();
        batchingAndDisabledIndex();
        publishedArchive();
        metadataPause();
        rateLimitedGeneration();
        System.out.println("Telegram catalogue worker: " + checks + " checks passed");
    }
}
