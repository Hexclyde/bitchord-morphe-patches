package app.bitchord.telegram;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Watches only files that source preparation currently holds. */
final class TelegramMediaChanges {
    static final int MAX_WATCHES = 256;

    static final class Watch {
        final long chat, message;
        volatile boolean valid;
        volatile String invalidationReason;

        Watch(long chat, long message, boolean valid) {
            this.chat = chat;
            this.message = message;
            invalidationReason = valid ? "" : "capacity";
            this.valid = valid;
        }
    }

    private final List<WeakReference<Watch>> watches = new ArrayList<>();
    volatile long contentUpdates, cacheDeletesIgnored, serverDeletes, protectionUpdates, resets;

    private static long next(long value) {
        return value == Long.MAX_VALUE ? value : value + 1;
    }

    private static void invalidate(Watch watch, String reason) {
        if (!watch.valid) return;
        watch.invalidationReason = reason;
        watch.valid = false;
    }

    synchronized void ignoreCacheDeletion() {
        cacheDeletesIgnored = next(cacheDeletesIgnored);
    }

    synchronized Watch watch(long chat, long message) {
        cleanup();
        Watch watch = new Watch(chat, message, watches.size() < MAX_WATCHES);
        if (watch.valid) watches.add(new WeakReference<>(watch));
        return watch;
    }

    synchronized void message(long chat, long message) {
        contentUpdates = next(contentUpdates);
        invalidateMessage(chat, message, "content");
        cleanup();
    }

    private void invalidateMessage(long chat, long message, String reason) {
        for (WeakReference<Watch> ref : watches) {
            Watch watch = ref.get();
            if (watch != null && watch.chat == chat && watch.message == message)
                invalidate(watch, reason);
        }
    }

    synchronized void messages(long chat, long[] messages) {
        serverDeletes = next(serverDeletes);
        if (messages == null) return;
        for (long message : messages) invalidateMessage(chat, message, "delete");
        cleanup();
    }

    synchronized void chat(long chat) {
        protectionUpdates = next(protectionUpdates);
        for (WeakReference<Watch> ref : watches) {
            Watch watch = ref.get();
            if (watch != null && watch.chat == chat) invalidate(watch, "protection");
        }
        cleanup();
    }

    synchronized void reset() {
        resets = next(resets);
        for (WeakReference<Watch> ref : watches) {
            Watch watch = ref.get();
            if (watch != null) invalidate(watch, "session");
        }
        watches.clear();
    }

    private void cleanup() {
        for (Iterator<WeakReference<Watch>> it = watches.iterator(); it.hasNext(); ) {
            Watch watch = it.next().get();
            if (watch == null || !watch.valid) it.remove();
        }
    }
}
