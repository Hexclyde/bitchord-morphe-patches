package app.bitchord.telegram;

import org.drinkless.tdlib.TdApi;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Applies received catalogue changes away from TDLib's receive thread. */
final class TelegramCatalogueWorker {
    interface Host {
        boolean ready();

        long generation();

        boolean selected(long chat);

        TdApi.Message lookup(long chat, long message) throws Exception;

        void changed(Map<Long, Set<Long>> changes) throws Exception;

        void failed(Exception failure);
    }

    enum Outcome {
        IDLE,
        PROCESSED,
        DEFERRED
    }

    private final TelegramIndex index;
    private final TelegramCatalogueUpdates updates;
    private final Host host;
    private final Object lock;
    private Map<Long, Set<Long>> notification;
    private long notificationGeneration;
    private final java.util.function.LongSupplier clock;
    private long metadataUntil = Long.MIN_VALUE;

    TelegramCatalogueWorker(
            TelegramIndex index, TelegramCatalogueUpdates updates, Host host, Object lock) {
        this(index, updates, host, lock, () -> System.nanoTime() / 1_000_000L);
    }

    TelegramCatalogueWorker(
            TelegramIndex index,
            TelegramCatalogueUpdates updates,
            Host host,
            Object lock,
            java.util.function.LongSupplier clock) {
        this.index = index;
        this.updates = updates;
        this.host = host;
        this.lock = lock;
        this.clock = clock;
    }

    private boolean current(TelegramCatalogueUpdates.Change change) {
        return host.ready() && host.generation() == change.generation && host.selected(change.chat);
    }

    private boolean notifyChanges() {
        try {
            host.changed(notification);
            notification = null;
            return true;
        } catch (Exception failure) {
            host.failed(failure);
            return false;
        }
    }

    Outcome drain() {
        if (notification != null && notificationGeneration != host.generation())
            notification = null;
        if (!host.ready()) return Outcome.IDLE;
        boolean notified = notification != null;
        if (notified && !notifyChanges()) return Outcome.DEFERRED;
        boolean paused = clock.getAsLong() < metadataUntil;
        List<TelegramCatalogueUpdates.Change> batch = updates.take(32, !paused);
        if (batch.isEmpty()) {
            if (paused && updates.pending() > 0) return Outcome.DEFERRED;
            return notified ? Outcome.PROCESSED : Outcome.IDLE;
        }
        Map<Long, Set<Long>> changed = new LinkedHashMap<>();
        long changedGeneration = host.generation();
        boolean deferred = false;
        for (TelegramCatalogueUpdates.Change change : batch) {
            try {
                if (!current(change)) continue;
                TdApi.Message message = change.value;
                if (change.kind == TelegramCatalogueUpdates.Change.CONTENT && index.enabled) {
                    boolean retry = clock.getAsLong() < metadataUntil;
                    if (!retry) {
                        try {
                            message = host.lookup(change.chat, change.message);
                        } catch (LiveSearch.RateLimited limited) {
                            long now = clock.getAsLong(), delay = 1000L * limited.retrySeconds;
                            metadataUntil =
                                    Math.max(
                                            metadataUntil,
                                            now > Long.MAX_VALUE - delay
                                                    ? Long.MAX_VALUE
                                                    : now + delay);
                            retry = true;
                            host.failed(limited);
                        }
                    }
                    if (retry) {
                        synchronized (lock) {
                            if (!current(change)) continue;
                            if (!change.quarantined) {
                                index.deleted(change.chat, new long[] {change.message}, false);
                                if (changedGeneration != change.generation) changed.clear();
                                changedGeneration = change.generation;
                                changed.computeIfAbsent(change.chat, chat -> new LinkedHashSet<>())
                                        .add(change.message);
                            }
                            updates.retry(change, true);
                        }
                        deferred = true;
                        continue;
                    }
                    if (message != null
                            && (message.chatId != change.chat || message.id != change.message))
                        throw new IOException("Telegram returned another message");
                }
                synchronized (lock) {
                    if (!current(change)) continue;
                    boolean applied = true;
                    if (change.kind == TelegramCatalogueUpdates.Change.RESET) {
                        index.invalidateChat(change.chat);
                    } else if (index.enabled
                            && change.kind != TelegramCatalogueUpdates.Change.NOTIFY) {
                        if (change.kind == TelegramCatalogueUpdates.Change.DELETE) {
                            index.deleted(
                                    change.chat, new long[] {change.message}, change.permanent);
                        } else if (change.kind == TelegramCatalogueUpdates.Change.CONTENT
                                && (message == null
                                        || !(message.content instanceof TdApi.MessageAudio
                                                || message.content
                                                        instanceof TdApi.MessageDocument))) {
                            index.deleted(change.chat, new long[] {change.message}, false);
                        } else if (message != null) {
                            applied = index.message(message);
                        }
                    }
                    if (applied) {
                        if (changedGeneration != change.generation) changed.clear();
                        changedGeneration = change.generation;
                        changed.computeIfAbsent(change.chat, chat -> new LinkedHashSet<>())
                                .add(change.message);
                    }
                }
            } catch (Exception failure) {
                deferred = true;
                synchronized (lock) {
                    if (current(change)) {
                        updates.reset(change.chat, host.generation());
                        if (changedGeneration != change.generation) changed.clear();
                        changedGeneration = change.generation;
                        changed.computeIfAbsent(change.chat, chat -> new LinkedHashSet<>()).add(0L);
                    }
                }
                host.failed(failure);
            } finally {
                updates.complete(change);
            }
        }
        if (!changed.isEmpty() && changedGeneration == host.generation()) {
            notification = changed;
            notificationGeneration = changedGeneration;
            if (!notifyChanges()) deferred = true;
        }
        return deferred ? Outcome.DEFERRED : Outcome.PROCESSED;
    }
}
