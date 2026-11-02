package app.bitchord.telegram;

import org.drinkless.tdlib.TdApi;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class TelegramMediaChangesTest {
    private static int checks;

    private static void require(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void cacheDeletionFlags() {
        for (boolean permanent : new boolean[] {false, true})
            for (boolean cache : new boolean[] {false, true}) {
                TelegramMediaChanges files = new TelegramMediaChanges();
                TelegramMediaChanges.Watch first = files.watch(-10, 100);
                TelegramMediaChanges.Watch same = files.watch(-10, 100);
                TelegramMediaChanges.Watch matching = files.watch(-10, 200);
                TelegramMediaChanges.Watch neighbour = files.watch(-10, 300);
                TelegramMediaChanges.Watch other = files.watch(-20, 100);
                boolean changed =
                        TelegramMediaUpdates.changed(
                                files,
                                new TdApi.UpdateDeleteMessages(
                                        -10, new long[] {100, 200, 900}, permanent, cache));
                boolean kept = cache && !permanent;
                require(changed != kept, "Only local eviction may omit the capture revision bump");
                require(
                        first.valid == kept && same.valid == kept && matching.valid == kept,
                        "Only unequivocal local cache eviction must preserve matching prepared"
                                + " files");
                require(
                        neighbour.valid && other.valid,
                        "Every deletion flag combination must preserve other files and chats");
                require(
                        files.cacheDeletesIgnored == (kept ? 1 : 0)
                                && files.serverDeletes == (kept ? 0 : 1),
                        "Aggregate counters must distinguish local eviction from invalidating"
                                + " deletion");
                require(
                        first.invalidationReason.equals(kept ? "" : "delete")
                                && neighbour.invalidationReason.isEmpty()
                                && other.invalidationReason.isEmpty(),
                        "Fixed reasons describe only an affected watch");
            }
    }

    private static boolean meaningful(TelegramMediaChanges files, TdApi.Object update)
            throws Exception {
        Method method =
                TelegramMediaUpdates.class.getDeclaredMethod(
                        "changed", TelegramMediaChanges.class, TdApi.Object.class);
        Object result = method.invoke(null, files, update);
        // A void mapping reflects the original Bridge handler's unconditional revision bump.
        return result instanceof Boolean ? (Boolean) result : true;
    }

    private static void blockedCaptureGuard() throws Exception {
        for (boolean permanent : new boolean[] {false, true})
            for (boolean cache : new boolean[] {false, true}) {
                TelegramMediaChanges files = new TelegramMediaChanges();
                AtomicLong revision = new AtomicLong(2);
                CountDownLatch resolving = new CountDownLatch(1), completed = new CountDownLatch(1);
                AtomicBoolean captured = new AtomicBoolean();
                Thread capture =
                        new Thread(
                                () -> {
                                    long before = revision.get();
                                    resolving.countDown();
                                    try {
                                        if (!completed.await(5, TimeUnit.SECONDS)) return;
                                        synchronized (files) {
                                            captured.set(before == revision.get());
                                        }
                                    } catch (InterruptedException stopped) {
                                        Thread.currentThread().interrupt();
                                    }
                                });
                capture.start();
                require(
                        resolving.await(5, TimeUnit.SECONDS),
                        "Description must reach its blocked resolve");
                try {
                    synchronized (files) {
                        if (meaningful(
                                files,
                                new TdApi.UpdateDeleteMessages(
                                        -10, new long[] {100}, permanent, cache)))
                            revision.incrementAndGet();
                    }
                } finally {
                    completed.countDown();
                }
                capture.join(5000);
                require(!capture.isAlive(), "The blocked description must finish");
                require(
                        captured.get() == (cache && !permanent),
                        "Cache-only eviction must not abort capture; actual changes must abort it");
            }
    }

    private static void safeDiagnostics() {
        TelegramMediaChanges files = new TelegramMediaChanges();
        TelegramMediaChanges.Watch edited = files.watch(-10, 100);
        TelegramMediaChanges.Watch deleted = files.watch(-10, 200);
        TelegramMediaChanges.Watch protectedFile = files.watch(-20, 100);
        TelegramMediaChanges.Watch session = files.watch(-30, 100);
        TelegramMediaUpdates.changed(files, new TdApi.UpdateDeleteMessages(-10, null, false, true));
        require(
                edited.valid && deleted.valid && files.cacheDeletesIgnored == 1,
                "An empty local eviction remains non-invalidating and is counted once");
        TelegramMediaUpdates.changed(files, new TdApi.UpdateMessageContent(-10, 100, null));
        require(
                !edited.valid
                        && edited.invalidationReason.equals("content")
                        && files.contentUpdates == 1,
                "Content edits retain their invalidation reason and aggregate count");
        TelegramMediaUpdates.changed(
                files, new TdApi.UpdateDeleteMessages(-10, new long[] {100, 200}, false, false));
        require(
                !deleted.valid
                        && deleted.invalidationReason.equals("delete")
                        && edited.invalidationReason.equals("content")
                        && files.serverDeletes == 1,
                "Inaccessible files invalidate without replacing an earlier first reason");
        TelegramMediaUpdates.changed(files, new TdApi.UpdateChatHasProtectedContent(-20, false));
        require(
                !protectedFile.valid
                        && protectedFile.invalidationReason.equals("protection")
                        && files.protectionUpdates == 1,
                "Protection removal still requires fresh eligibility proof");
        files.reset();
        require(
                !session.valid && session.invalidationReason.equals("session") && files.resets == 1,
                "Session changes invalidate held descriptions and retain their fixed reason");
        files.contentUpdates =
                files.cacheDeletesIgnored =
                        files.serverDeletes =
                                files.protectionUpdates = files.resets = Long.MAX_VALUE;
        TelegramMediaUpdates.changed(files, new TdApi.UpdateMessageContent(-10, 100, null));
        TelegramMediaUpdates.changed(files, new TdApi.UpdateDeleteMessages(-10, null, false, true));
        TelegramMediaUpdates.changed(files, new TdApi.UpdateDeleteMessages(-10, null, true, true));
        TelegramMediaUpdates.changed(files, new TdApi.UpdateChatHasProtectedContent(-20, true));
        files.reset();
        require(
                files.contentUpdates == Long.MAX_VALUE
                        && files.cacheDeletesIgnored == Long.MAX_VALUE
                        && files.serverDeletes == Long.MAX_VALUE
                        && files.protectionUpdates == Long.MAX_VALUE
                        && files.resets == Long.MAX_VALUE,
                "Diagnostic counters must saturate instead of wrapping");
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            if (args[0].equals("cache-delete")) cacheDeletionFlags();
            else if (args[0].equals("capture-guard")) blockedCaptureGuard();
            else throw new IllegalArgumentException("Unknown regression selection");
            return;
        }
        cacheDeletionFlags();
        blockedCaptureGuard();
        safeDiagnostics();
        TelegramMediaChanges files = new TelegramMediaChanges();
        TelegramMediaChanges.Watch first = files.watch(-10, 100);
        TelegramMediaChanges.Watch same = files.watch(-10, 100);
        TelegramMediaChanges.Watch neighbour = files.watch(-10, 200);
        TelegramMediaChanges.Watch otherChat = files.watch(-20, 100);
        TelegramMediaUpdates.changed(files, new TdApi.UpdateMessageContent(-30, 100, null));
        require(
                first.valid && neighbour.valid && otherChat.valid,
                "An unrelated chat must not invalidate prepared audio");
        TelegramMediaUpdates.changed(files, new TdApi.UpdateMessageContent(-10, 300, null));
        require(
                first.valid && neighbour.valid && otherChat.valid,
                "An unrelated message must not invalidate prepared audio");
        TelegramMediaUpdates.changed(files, new TdApi.UpdateMessageContent(-10, 100, null));
        require(
                !first.valid && !same.valid,
                "An edit must invalidate every description of its file");
        require(neighbour.valid && otherChat.valid, "An edit must preserve other files");
        TelegramMediaUpdates.changed(
                files, new TdApi.UpdateDeleteMessages(-30, new long[] {200}, true, false));
        require(
                neighbour.valid && otherChat.valid,
                "A deletion in another chat must preserve prepared audio");
        TelegramMediaUpdates.changed(
                files, new TdApi.UpdateDeleteMessages(-10, new long[] {400, 200}, true, false));
        require(
                !neighbour.valid && otherChat.valid,
                "A deletion must invalidate only matching files");
        TelegramMediaChanges.Watch protectedChat = files.watch(-20, 200);
        TelegramMediaUpdates.changed(files, new TdApi.UpdateChatHasProtectedContent(-40, true));
        require(
                protectedChat.valid && otherChat.valid,
                "An unrelated protection update must preserve prepared audio");
        TelegramMediaUpdates.changed(files, new TdApi.UpdateChatHasProtectedContent(-20, true));
        require(
                !protectedChat.valid && !otherChat.valid,
                "Protection changes must invalidate every file in that chat");
        TelegramMediaChanges.Watch replacement = files.watch(-10, 100);
        require(
                replacement.valid && !first.valid,
                "A new description must not revive an older invalidated file");
        TelegramMediaUpdates.changed(files, new TdApi.UpdateDeleteMessages(-10, null, true, false));
        require(replacement.valid, "An empty deletion cannot target a file");
        files.reset();
        require(!replacement.valid, "A session reset must invalidate every prior description");
        List<TelegramMediaChanges.Watch> live = new ArrayList<>();
        for (int i = 0; i < TelegramMediaChanges.MAX_WATCHES; i++) {
            TelegramMediaChanges.Watch watch = files.watch(-50, i + 1);
            live.add(watch);
            require(watch.valid, "Each available slot must accept its file");
        }
        require(!files.watch(-50, 1000).valid, "Exhaustion must reject a new description safely");
        require(live.get(0).valid, "Exhaustion must preserve currently held descriptions");
        TelegramMediaUpdates.changed(files, new TdApi.UpdateMessageContent(-50, 1, null));
        require(files.watch(-50, 1000).valid, "Invalidated descriptions must free their slot");
        Field tracked = TelegramMediaChanges.class.getDeclaredField("watches");
        tracked.setAccessible(true);
        List<?> weak = (List<?>) tracked.get(files);
        ((WeakReference<?>) weak.get(0)).clear();
        require(files.watch(-50, 1001).valid, "Released streams must free their weak slot");
        require(weak.size() <= TelegramMediaChanges.MAX_WATCHES, "Tracking must remain bounded");
        System.out.println("Telegram file invalidation: " + checks + " assertions passed");
    }
}
