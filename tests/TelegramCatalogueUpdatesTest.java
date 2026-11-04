package app.bitchord.telegram;

import org.drinkless.tdlib.TdApi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TelegramCatalogueUpdatesTest {
    private static int checks;

    private static void require(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static final class Queue {
        final Set<Long> selected = new HashSet<>(Arrays.asList(-10L, -20L));
        final List<Long> held = new ArrayList<>();
        final TelegramCatalogueUpdates changes =
                new TelegramCatalogueUpdates(selected::contains, held::add);
    }

    private static TdApi.Message audio(long chat, long id) {
        TdApi.Message message = new TdApi.Message();
        message.chatId = chat;
        message.id = id;
        message.content = new TdApi.MessageAudio();
        return message;
    }

    private static boolean add(Queue queue, long chat, long id, long generation) {
        return queue.changes.accept(new TdApi.UpdateNewMessage(audio(chat, id)), generation);
    }

    private static boolean edit(Queue queue, long chat, long id, long generation) {
        return queue.changes.accept(new TdApi.UpdateMessageContent(chat, id, null), generation);
    }

    private static boolean delete(
            Queue queue, long chat, long id, boolean permanent, boolean cached, long generation) {
        return queue.changes.accept(
                new TdApi.UpdateDeleteMessages(chat, new long[] {id}, permanent, cached),
                generation);
    }

    private static void finish(Queue queue, List<TelegramCatalogueUpdates.Change> batch) {
        for (TelegramCatalogueUpdates.Change change : batch) queue.changes.complete(change);
    }

    private static void rejectedMessages() {
        Queue queue = new Queue();
        for (TdApi.Message message :
                new TdApi.Message[] {audio(-30, 1), audio(0, 1), audio(-10, 0), audio(-10, -1)})
            require(
                    !queue.changes.accept(new TdApi.UpdateNewMessage(message), 1),
                    "Unselected chats and invalid identifiers do not enter the catalogue queue");
        require(
                !queue.changes.accept(new TdApi.UpdateNewMessage(null), 1),
                "A missing message does not enter the queue");
        TdApi.Message pending = audio(-10, 1);
        pending.sendingState = new TdApi.MessageSendingStatePending();
        TdApi.Message scheduled = audio(-10, 2);
        scheduled.schedulingState = new TdApi.MessageSchedulingStateSendAtDate();
        TdApi.Message text = audio(-10, 3);
        text.content = new TdApi.MessageText();
        for (TdApi.Message message : new TdApi.Message[] {pending, scheduled, text}) {
            require(
                    !queue.changes.accept(new TdApi.UpdateNewMessage(message), 1),
                    "Pending, scheduled and non-file messages do not enter the queue");
            require(
                    !queue.changes.accept(new TdApi.UpdateMessageSendSucceeded(message, 90), 1),
                    "Send success does not bypass message eligibility");
        }
        require(!edit(queue, -30, 1, 1), "Unselected content updates are ignored");
        require(!delete(queue, -30, 1, true, false, 1), "Unselected deletions are ignored");
        require(
                !queue.changes.accept(new TdApi.UpdateChatHasProtectedContent(-30, true), 1),
                "Unselected protection changes do not hold a chat");
        require(
                queue.changes.pending() == 0 && queue.changes.resets() == 0,
                "Rejected updates leave no pending catalogue work");
        require(queue.held.isEmpty(), "Rejected updates do not hold selected catalogues");
    }

    private static void acceptedMessages() {
        Queue queue = new Queue();
        TdApi.Message first = audio(-10, 101);
        TdApi.Message document = audio(-20, 201);
        document.content = new TdApi.MessageDocument();
        require(
                queue.changes.accept(new TdApi.UpdateNewMessage(first), 7),
                "A selected audio post enters the queue");
        require(
                queue.changes.accept(new TdApi.UpdateMessageSendSucceeded(document, -3), 7),
                "A delivered document uses its final identifier");
        require(
                queue.changes.accept(new TdApi.UpdateNewMessage(first), 7),
                "Duplicate delivery is accepted without another row");
        List<TelegramCatalogueUpdates.Change> batch = queue.changes.take(32);
        require(batch.size() == 2, "Duplicate posts coalesce by chat and final message identifier");
        require(
                batch.get(0).value == document && batch.get(1).value == first,
                "Latest receive order is preserved after duplicate delivery");
        require(
                batch.get(1).generation == 7
                        && batch.get(1).kind == TelegramCatalogueUpdates.Change.MESSAGE,
                "The queued post carries its generation and message kind");
        finish(queue, batch);
        require(queue.changes.take(1).isEmpty(), "Completing the batch leaves no repeated post");
    }

    private static void deletionFlags() {
        for (boolean permanent : new boolean[] {false, true}) {
            for (boolean cached : new boolean[] {false, true}) {
                Queue queue = new Queue();
                require(add(queue, -10, 100, 1), "The audio post is queued before deletion");
                boolean ignored = cached && !permanent;
                require(
                        delete(queue, -10, 100, permanent, cached, 1) != ignored,
                        "Only unequivocal local cache eviction omits catalogue deletion");
                TelegramCatalogueUpdates.Change change = queue.changes.take(1).get(0);
                require(
                        change.kind
                                == (ignored
                                        ? TelegramCatalogueUpdates.Change.MESSAGE
                                        : TelegramCatalogueUpdates.Change.DELETE),
                        "Permanent and inaccessible server deletions replace pending media");
                require(
                        change.permanent == permanent,
                        "The worker receives the permanent deletion flag unchanged");
                queue.changes.complete(change);
            }
        }
        Queue queue = new Queue();
        require(
                !queue.changes.accept(new TdApi.UpdateDeleteMessages(-10, null, true, false), 1),
                "A missing deletion identifier list is harmless");
        require(
                !queue.changes.accept(
                        new TdApi.UpdateDeleteMessages(-10, new long[] {0, -2}, true, false), 1),
                "Invalid deletion identifiers do not create work");
    }

    private static void permanentDeletion() {
        Queue queue = new Queue();
        delete(queue, -10, 100, true, false, 1);
        edit(queue, -10, 100, 1);
        add(queue, -10, 100, 1);
        delete(queue, -10, 100, false, false, 1);
        List<TelegramCatalogueUpdates.Change> batch = queue.changes.take(1);
        TelegramCatalogueUpdates.Change deleted = batch.get(0);
        require(
                deleted.kind == TelegramCatalogueUpdates.Change.DELETE && deleted.permanent,
                "Coalescing cannot weaken a permanent deletion");
        require(
                add(queue, -10, 100, 1) && edit(queue, -10, 100, 1),
                "Updates received during a permanent deletion are acknowledged");
        require(
                queue.changes.pending() == 0,
                "An in-flight permanent deletion prevents queued resurrection");
        finish(queue, batch);
        require(
                queue.changes.take(32).isEmpty(),
                "Completing a permanent deletion does not release stale replacement work");
    }

    private static void ordering() {
        Queue queue = new Queue();
        add(queue, -10, 100, 3);
        add(queue, -20, 100, 3);
        edit(queue, -10, 100, 3);
        delete(queue, -10, 200, false, false, 3);
        List<TelegramCatalogueUpdates.Change> batch = queue.changes.take(32);
        require(batch.size() == 3, "Equal message identifiers in different chats stay independent");
        require(
                batch.get(0).chat == -20
                        && batch.get(1).chat == -10
                        && batch.get(1).kind == TelegramCatalogueUpdates.Change.CONTENT
                        && batch.get(2).message == 200,
                "The worker sees coalesced content changes in receive order across chats");
        finish(queue, batch);
        delete(queue, -10, 300, false, false, 3);
        add(queue, -10, 300, 3);
        batch = queue.changes.take(32);
        require(
                batch.size() == 1 && batch.get(0).kind == TelegramCatalogueUpdates.Change.MESSAGE,
                "An accessible replacement may supersede a non-permanent inaccessible post");
        edit(queue, -10, 300, 3);
        finish(queue, batch);
        batch = queue.changes.take(32);
        require(
                batch.size() == 1 && batch.get(0).kind == TelegramCatalogueUpdates.Change.CONTENT,
                "A content update arriving during work remains pending for the next batch");
        finish(queue, batch);
    }

    private static void boundedOverflow() {
        Queue queue = new Queue();
        add(queue, -20, 1, 5);
        for (int id = 1; id < TelegramCatalogueUpdates.MAX_PENDING; id++) add(queue, -10, id, 5);
        require(
                queue.changes.pending() == TelegramCatalogueUpdates.MAX_PENDING,
                "The receive queue reaches its stated bound");
        add(queue, -10, 2000, 5);
        require(
                queue.changes.pending() == 1 && queue.changes.resets() == 1,
                "Overflow replaces only the affected chat's pending posts with a reset");
        require(
                queue.held.equals(Arrays.asList(-10L)),
                "Overflow holds the affected catalogue before worker repair");
        for (int id = 2001; id < 5000; id++) add(queue, -10, id, 5);
        require(
                queue.changes.pending() == 1 && queue.changes.resets() == 1,
                "Further posts in a held chat cannot grow the receive queue");
        List<TelegramCatalogueUpdates.Change> batch = queue.changes.take(32);
        require(
                batch.size() == 2
                        && batch.get(0).kind == TelegramCatalogueUpdates.Change.RESET
                        && batch.get(0).chat == -10
                        && batch.get(1).chat == -20,
                "Worker repair precedes independent unaffected-chat work");
        finish(queue, batch);
        for (int id = 1; id <= TelegramCatalogueUpdates.MAX_PENDING + 1; id++)
            add(queue, -10, id, 5);
        queue.changes.reset(-20, 5);
        for (int id = 1; id < 5000; id++) add(queue, -20, id, 5);
        require(
                queue.changes.pending() == 0 && queue.changes.resets() == 2,
                "Reset flags are bounded by selected chats despite repeated updates");
        batch = queue.changes.take(1);
        require(batch.size() == 1, "Reset batches respect the requested work limit");
        finish(queue, batch);
        batch = queue.changes.take(1);
        require(batch.size() == 1, "The next selected chat's reset remains queued");
        finish(queue, batch);
    }

    private static void sessionChange() {
        Queue queue = new Queue();
        add(queue, -10, 100, 8);
        add(queue, -20, 200, 8);
        List<TelegramCatalogueUpdates.Change> oldBatch = queue.changes.take(1);
        queue.changes.sessionChanged(9);
        require(
                queue.changes.pending() == 0 && queue.changes.resets() == 2,
                "A new session repairs both queued and in-flight chats");
        require(
                queue.held.containsAll(Arrays.asList(-10L, -20L)),
                "Both old-session catalogues are held before their resets run");
        boolean blocked = false;
        try {
            queue.changes.take(1);
        } catch (IllegalStateException expected) {
            blocked = true;
        }
        require(blocked, "The next session cannot overlap an unfinished old batch");
        finish(queue, oldBatch);
        List<TelegramCatalogueUpdates.Change> resetBatch = queue.changes.take(32);
        require(
                resetBatch.size() == 2
                        && resetBatch.stream()
                                .allMatch(
                                        change ->
                                                change.kind == TelegramCatalogueUpdates.Change.RESET
                                                        && change.generation == 9),
                "Old posts are replaced by reset work belonging to the current generation");
        queue.changes.sessionChanged(10);
        finish(queue, resetBatch);
        resetBatch = queue.changes.take(32);
        require(
                resetBatch.size() == 2
                        && resetBatch.stream().allMatch(change -> change.generation == 10),
                "A session change while repair is in flight preserves the newest generation");
        finish(queue, resetBatch);
        require(
                queue.changes.take(32).isEmpty(),
                "Finished current-session repair drains the queue");
    }

    private static void batchCompletion() {
        Queue queue = new Queue();
        for (int limit : new int[] {-1, 0, 33}) {
            boolean rejected = false;
            try {
                queue.changes.take(limit);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            require(rejected, "A batch limit must be between one and thirty-two");
        }
        add(queue, -10, 100, 1);
        add(queue, -10, 200, 1);
        List<TelegramCatalogueUpdates.Change> batch = queue.changes.take(2);
        TelegramCatalogueUpdates.Change first = batch.get(0);
        queue.changes.complete(
                new TelegramCatalogueUpdates.Change(
                        first.chat,
                        first.message,
                        first.generation,
                        first.kind,
                        first.value,
                        first.permanent));
        queue.changes.complete(batch.get(1));
        boolean blocked = false;
        try {
            queue.changes.take(1);
        } catch (IllegalStateException expected) {
            blocked = true;
        }
        require(blocked, "Completion requires the exact in-flight work item");
        queue.changes.complete(first);
        queue.changes.complete(first);
        require(
                queue.changes.take(1).isEmpty(),
                "Repeated completion of the same item is harmless after the batch finishes");
    }

    private static void publishedNotifications() {
        Queue queue = new Queue();
        require(
                !queue.changes.published(-30, 100, 1)
                        && !queue.changes.published(0, 100, 1)
                        && !queue.changes.published(-10, 0, 1)
                        && !queue.changes.published(-10, -1, 1),
                "Archive publication accepts only selected chats and valid message identifiers");
        require(
                queue.changes.published(-10, 100, 4)
                        && queue.changes.published(-10, 100, 4)
                        && queue.changes.pending() == 1,
                "Repeated publication coalesces to one notification");
        List<TelegramCatalogueUpdates.Change> batch = queue.changes.take(1);
        TelegramCatalogueUpdates.Change notice = batch.get(0);
        require(
                notice.kind == TelegramCatalogueUpdates.Change.NOTIFY
                        && notice.generation == 4
                        && notice.value == null,
                "Published work carries notification kind and current generation without a post");
        require(
                queue.changes.published(-10, 100, 4) && queue.changes.pending() == 0,
                "Publishing an in-flight notification creates no duplicate work");
        finish(queue, batch);
        require(
                queue.changes.take(1).isEmpty(),
                "A completed notification releases batch ownership");

        for (int kind :
                new int[] {
                    TelegramCatalogueUpdates.Change.MESSAGE,
                    TelegramCatalogueUpdates.Change.CONTENT,
                    TelegramCatalogueUpdates.Change.DELETE
                }) {
            Queue same = new Queue();
            if (kind == TelegramCatalogueUpdates.Change.MESSAGE) add(same, -10, 100, 1);
            else if (kind == TelegramCatalogueUpdates.Change.CONTENT) edit(same, -10, 100, 1);
            else delete(same, -10, 100, true, false, 1);
            require(
                    same.changes.published(-10, 100, 1),
                    "Publication acknowledges existing message work");
            List<TelegramCatalogueUpdates.Change> work = same.changes.take(1);
            TelegramCatalogueUpdates.Change original = work.get(0);
            require(
                    original.kind == kind,
                    "Publication never replaces pending message, content or deletion work");
            same.changes.published(-10, 100, 1);
            require(
                    same.changes.pending() == 0,
                    "Publication never replaces or duplicates in-flight message work");
            finish(same, work);
            require(
                    same.changes.take(1).isEmpty(),
                    "Publication does not leave replacement work after exact completion");
        }
        Queue unavailable = new Queue();
        delete(unavailable, -10, 100, false, false, 1);
        unavailable.changes.published(-10, 100, 1);
        batch = unavailable.changes.take(1);
        require(
                batch.get(0).kind == TelegramCatalogueUpdates.Change.DELETE
                        && !batch.get(0).permanent,
                "Publication also preserves a non-permanent server deletion");
        finish(unavailable, batch);

        Queue edited = new Queue();
        edited.changes.published(-10, 100, 1);
        edit(edited, -10, 100, 1);
        batch = edited.changes.take(1);
        require(
                batch.get(0).kind == TelegramCatalogueUpdates.Change.CONTENT,
                "A real content change supersedes a pending publication notice");
        finish(edited, batch);

        Queue overflow = new Queue();
        add(overflow, -20, 1, 1);
        for (int id = 1; id < TelegramCatalogueUpdates.MAX_PENDING; id++)
            overflow.changes.published(-10, id, 1);
        require(
                overflow.changes.pending() == TelegramCatalogueUpdates.MAX_PENDING,
                "Publication shares the bounded receive queue");
        overflow.changes.published(-10, 2000, 1);
        require(
                overflow.changes.pending() == 1
                        && overflow.changes.resets() == 1
                        && overflow.held.equals(Arrays.asList(-10L)),
                "Publication overflow holds only the affected chat for repair");
        overflow.changes.published(-10, 2001, 1);
        require(
                overflow.changes.pending() == 1 && overflow.changes.resets() == 1,
                "Publishing in a held chat does not grow pending work");
        batch = overflow.changes.take(32);
        require(
                batch.size() == 2
                        && batch.get(0).kind == TelegramCatalogueUpdates.Change.RESET
                        && batch.get(1).chat == -20,
                "Publication overflow preserves unrelated work behind the chat reset");
        finish(overflow, batch);
    }

    private static void deferredContent() {
        Queue queue = new Queue();
        edit(queue, -10, 100, 1);
        List<TelegramCatalogueUpdates.Change> batch = queue.changes.take(1);
        TelegramCatalogueUpdates.Change original = batch.get(0);
        require(
                !queue.changes.retry(
                        new TelegramCatalogueUpdates.Change(
                                original.chat,
                                original.message,
                                original.generation,
                                original.kind,
                                original.value,
                                original.permanent),
                        true),
                "Retry requires the exact in-flight change");
        require(
                queue.changes.retry(original, true),
                "Deferred content returns to bounded pending work");
        queue.changes.complete(original);
        require(
                queue.changes.take(1, false).isEmpty() && queue.changes.pending() == 1,
                "Paused metadata does not consume quarantined content");
        add(queue, -20, 200, 1);
        batch = queue.changes.take(1, false);
        require(
                batch.size() == 1 && batch.get(0).chat == -20,
                "A new post can pass paused metadata without a lookup");
        finish(queue, batch);
        batch = queue.changes.take(1, true);
        require(batch.get(0).quarantined, "Retry preserves the once-only quarantine marker");
        TelegramCatalogueUpdates.Change retried = batch.get(0);
        queue.changes.complete(original);
        edit(queue, -10, 100, 1);
        require(
                !queue.changes.retry(retried, true),
                "Retry does not overwrite a newer pending edit");
        batch = queue.changes.take(1);
        require(
                !batch.get(0).quarantined
                        && batch.get(0).kind == TelegramCatalogueUpdates.Change.CONTENT,
                "The newer edit keeps its own quarantine state");
        delete(queue, -10, 100, true, false, 1);
        require(
                !queue.changes.retry(batch.get(0), true),
                "Retry cannot replace a newer permanent deletion");
        batch = queue.changes.take(1);
        require(
                batch.get(0).kind == TelegramCatalogueUpdates.Change.DELETE
                        && batch.get(0).permanent,
                "Permanent deletion remains the next worker action");
        finish(queue, batch);

        edit(queue, -10, 300, 1);
        batch = queue.changes.take(1);
        queue.changes.sessionChanged(2);
        require(
                !queue.changes.retry(batch.get(0), true),
                "An old in-flight retry cannot override current-session repair");
        batch = queue.changes.take(1);
        require(
                batch.get(0).kind == TelegramCatalogueUpdates.Change.RESET
                        && batch.get(0).generation == 2,
                "The new session's reset supersedes deferred old metadata");
        finish(queue, batch);

        Queue overflow = new Queue();
        edit(overflow, -10, 100, 1);
        batch = overflow.changes.take(1);
        for (int id = 1; id <= TelegramCatalogueUpdates.MAX_PENDING; id++)
            add(overflow, -20, id, 1);
        require(
                overflow.changes.retry(batch.get(0), true)
                        && overflow.changes.pending() == TelegramCatalogueUpdates.MAX_PENDING
                        && overflow.changes.resets() == 1
                        && overflow.held.equals(Arrays.asList(-10L)),
                "A retry at capacity holds its chat without growing pending work");
        batch = overflow.changes.take(1);
        require(
                batch.get(0).kind == TelegramCatalogueUpdates.Change.RESET,
                "A bounded retry overflow leaves its batch complete and repairable");
        finish(overflow, batch);
    }

    public static void main(String[] args) {
        rejectedMessages();
        acceptedMessages();
        deletionFlags();
        permanentDeletion();
        ordering();
        boundedOverflow();
        sessionChange();
        batchCompletion();
        publishedNotifications();
        deferredContent();
        System.out.println("Telegram catalogue updates: " + checks + " checks passed");
    }
}
