package app.bitchord.telegram;

import org.drinkless.tdlib.TdApi;

/** Maps TDLib content updates to the files whose preparation is no longer valid. */
final class TelegramMediaUpdates {
    private TelegramMediaUpdates() {}

    static boolean changed(TelegramMediaChanges files, TdApi.Object update) {
        if (update instanceof TdApi.UpdateMessageContent) {
            TdApi.UpdateMessageContent content = (TdApi.UpdateMessageContent) update;
            files.message(content.chatId, content.messageId);
        } else if (update instanceof TdApi.UpdateDeleteMessages) {
            TdApi.UpdateDeleteMessages deleted = (TdApi.UpdateDeleteMessages) update;
            // Local message-cache eviction does not change access to the underlying audio.
            // A contradictory permanent flag must retain the invalidating path.
            if (deleted.fromCache && !deleted.isPermanent) {
                files.ignoreCacheDeletion();
                return false;
            }
            files.messages(deleted.chatId, deleted.messageIds);
        } else if (update instanceof TdApi.UpdateChatHasProtectedContent) {
            files.chat(((TdApi.UpdateChatHasProtectedContent) update).chatId);
        }
        return true;
    }
}
