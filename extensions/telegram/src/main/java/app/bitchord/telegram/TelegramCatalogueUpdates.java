package app.bitchord.telegram;

import org.drinkless.tdlib.TdApi;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.LongPredicate;

/** Coalesces catalogue changes without doing file or database work on TDLib's receive thread. */
final class TelegramCatalogueUpdates {
    static final int MAX_PENDING = 1024;

    interface Hold {
        void chat(long chat);
    }

    static final class Change {
        static final int MESSAGE = 0, CONTENT = 1, DELETE = 2, RESET = 3, NOTIFY = 4;
        final long chat, message, generation;
        final int kind;
        final TdApi.Message value;
        final boolean permanent, quarantined;

        Change(
                long chat,
                long message,
                long generation,
                int kind,
                TdApi.Message value,
                boolean permanent) {
            this(chat, message, generation, kind, value, permanent, false);
        }

        Change(
                long chat,
                long message,
                long generation,
                int kind,
                TdApi.Message value,
                boolean permanent,
                boolean quarantined) {
            this.chat = chat;
            this.message = message;
            this.generation = generation;
            this.kind = kind;
            this.value = value;
            this.permanent = permanent;
            this.quarantined = quarantined;
        }
    }

    private final LinkedHashMap<String, Change> pending = new LinkedHashMap<>();
    private final LinkedHashMap<String, Change> inFlight = new LinkedHashMap<>();
    // Reset flags are bounded by the explicitly selected chats, rather than the number of posts.
    private final LinkedHashMap<Long, Change> resets = new LinkedHashMap<>();
    private final LongPredicate selected;
    private final Hold hold;

    TelegramCatalogueUpdates(LongPredicate selected, Hold hold) {
        this.selected = selected;
        this.hold = hold;
    }

    synchronized boolean accept(TdApi.Object update, long generation) {
        if (update instanceof TdApi.UpdateNewMessage) {
            return message(((TdApi.UpdateNewMessage) update).message, generation);
        } else if (update instanceof TdApi.UpdateMessageSendSucceeded) {
            return message(((TdApi.UpdateMessageSendSucceeded) update).message, generation);
        } else if (update instanceof TdApi.UpdateMessageContent) {
            TdApi.UpdateMessageContent changed = (TdApi.UpdateMessageContent) update;
            return put(
                    new Change(
                            changed.chatId,
                            changed.messageId,
                            generation,
                            Change.CONTENT,
                            null,
                            false));
        } else if (update instanceof TdApi.UpdateDeleteMessages) {
            TdApi.UpdateDeleteMessages deleted = (TdApi.UpdateDeleteMessages) update;
            if (deleted.fromCache && !deleted.isPermanent
                    || deleted.messageIds == null
                    || !selected.test(deleted.chatId)) return false;
            boolean changed = false;
            for (long id : deleted.messageIds) {
                if (id <= 0) continue;
                changed |=
                        put(
                                new Change(
                                        deleted.chatId,
                                        id,
                                        generation,
                                        Change.DELETE,
                                        null,
                                        deleted.isPermanent));
                if (resets.containsKey(deleted.chatId)) break;
            }
            return changed;
        } else if (update instanceof TdApi.UpdateChatHasProtectedContent) {
            return reset(((TdApi.UpdateChatHasProtectedContent) update).chatId, generation);
        }
        return false;
    }

    private boolean message(TdApi.Message message, long generation) {
        if (message == null
                || message.sendingState != null
                || message.schedulingState != null
                || !(message.content instanceof TdApi.MessageAudio
                        || message.content instanceof TdApi.MessageDocument)) return false;
        return put(
                new Change(message.chatId, message.id, generation, Change.MESSAGE, message, false));
    }

    private boolean put(Change change) {
        if (change.chat == 0 || change.message <= 0 || !selected.test(change.chat)) return false;
        if (resets.containsKey(change.chat)) return true;
        String key = change.chat + "_" + change.message;
        Change previous = pending.get(key);
        if (previous == null) previous = inFlight.get(key);
        if (previous != null && previous.kind == Change.DELETE && previous.permanent) return true;
        if (!pending.containsKey(key) && pending.size() >= MAX_PENDING)
            return reset(change.chat, change.generation);
        // Removing first keeps the latest event at its actual place in receive order.
        pending.remove(key);
        pending.put(key, change);
        notifyAll();
        return true;
    }

    synchronized boolean published(long chat, long message, long generation) {
        String key = chat + "_" + message;
        if (pending.containsKey(key) || inFlight.containsKey(key)) return true;
        return put(new Change(chat, message, generation, Change.NOTIFY, null, false));
    }

    synchronized boolean reset(long chat, long generation) {
        if (chat == 0 || !selected.test(chat)) return false;
        hold.chat(chat);
        pending.values().removeIf(change -> change.chat == chat);
        resets.put(chat, new Change(chat, 0, generation, Change.RESET, null, false));
        notifyAll();
        return true;
    }

    synchronized void sessionChanged(long generation) {
        LinkedHashSet<Long> chats = new LinkedHashSet<>(resets.keySet());
        for (Change change : pending.values()) chats.add(change.chat);
        for (Change change : inFlight.values()) chats.add(change.chat);
        pending.clear();
        resets.clear();
        for (long chat : chats) reset(chat, generation);
    }

    synchronized List<Change> take(int limit) {
        return take(limit, true);
    }

    synchronized List<Change> take(int limit, boolean contentReady) {
        if (limit <= 0 || limit > 32) throw new IllegalArgumentException("Invalid catalogue batch");
        if (!inFlight.isEmpty())
            throw new IllegalStateException("Finish the catalogue batch first");
        List<Change> out = new ArrayList<>();
        while (!resets.isEmpty() && out.size() < limit) {
            Iterator<Change> iterator = resets.values().iterator();
            out.add(iterator.next());
            iterator.remove();
        }
        Iterator<Change> iterator = pending.values().iterator();
        while (iterator.hasNext() && out.size() < limit) {
            Change change = iterator.next();
            if (!contentReady && change.kind == Change.CONTENT && change.quarantined) continue;
            out.add(change);
            iterator.remove();
        }
        for (Change change : out) inFlight.put(change.chat + "_" + change.message, change);
        return out;
    }

    synchronized boolean retry(Change change, boolean quarantined) {
        String key = change.chat + "_" + change.message;
        if (inFlight.get(key) != change) return false;
        inFlight.remove(key);
        if (!selected.test(change.chat)
                || resets.containsKey(change.chat)
                || pending.containsKey(key)) return false;
        if (pending.size() >= MAX_PENDING) return reset(change.chat, change.generation);
        pending.put(
                key,
                new Change(
                        change.chat,
                        change.message,
                        change.generation,
                        change.kind,
                        change.value,
                        change.permanent,
                        quarantined));
        notifyAll();
        return true;
    }

    synchronized void complete(Change change) {
        inFlight.remove(change.chat + "_" + change.message, change);
    }

    synchronized void await(long millis) throws InterruptedException {
        if (pending.isEmpty() && resets.isEmpty()) wait(millis);
    }

    synchronized int pending() {
        return pending.size();
    }

    synchronized int resets() {
        return resets.size();
    }
}
