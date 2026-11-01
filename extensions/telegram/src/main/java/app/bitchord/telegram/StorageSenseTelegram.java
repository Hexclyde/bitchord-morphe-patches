package app.bitchord.telegram;

import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Downloaded audio in Telegram's cache, listed from the music chats' index and removed by TDLib's
 * own DeleteFile. Lives with the Telegram runtime, so other patch selections never load it.
 */
final class StorageSenseTelegram implements StorageSense.Owner {
    private static final StorageSense.Category[] CATEGORY = {StorageSense.Category.TELEGRAM_CACHE};
    private static final int PAGE = 200, MAX_ROWS = 6000, MAX_FILES = 2000;
    private static final long CALL_MS = 8000;

    /** Found by name, since a selection without Telegram has no such class. */
    public StorageSenseTelegram() {}

    @Override
    public StorageSense.Category[] categories() {
        return CATEGORY;
    }

    private static TelegramBridge bridge() throws StorageSense.Unavailable {
        TelegramBridge bridge = TelegramBridge.instance;
        if (bridge == null || !bridge.ready())
            throw new StorageSense.Unavailable(Strings.t("Sign in to Telegram to see its cache."));
        return bridge;
    }

    /** Which chat and message a media id's track is in, whatever entry of a zip it is. */
    static String messageOf(String id) {
        if (id == null) return null;
        int first = id.indexOf('_');
        if (first <= 0) return null;
        int second = id.indexOf('_', first + 1);
        return second < 0 ? id : id.substring(0, second);
    }

    private static Set<String> playing(StorageSense.Active active) {
        Set<String> messages = new HashSet<>();
        for (String mediaId : active.mediaIds) {
            String track = PlaybackPolicy.track(mediaId);
            if (track != null) messages.add(messageOf(track));
        }
        return messages;
    }

    private static boolean inCache(TelegramBridge bridge, TdApi.File file) {
        if (file.local == null || file.local.path == null || file.local.path.isEmpty())
            return false;
        return StorageSenseScanner.prove(
                        new File(file.local.path),
                        Collections.singletonList(new File(bridge.home, "files")))
                != null;
    }

    private static long lastUsed(TdApi.File file) {
        return file.local == null || file.local.path == null || file.local.path.isEmpty()
                ? 0
                : new File(file.local.path).lastModified();
    }

    private static StorageSense.Block block(
            TelegramBridge bridge, TdApi.File file, String message, StorageSense.Active active) {
        if (!active.known) return StorageSense.Block.STATE_UNKNOWN;
        synchronized (bridge.audio.cacheGuard) {
            if (bridge.audio.leases.containsKey(file.id)) return StorageSense.Block.PLAYING;
        }
        if (playing(active).contains(message)) return StorageSense.Block.PLAYING;
        if (file.local.isDownloadingActive || file.remote.isUploadingActive)
            return StorageSense.Block.TRANSFER_ACTIVE;
        if (!file.local.canBeDeleted || !inCache(bridge, file))
            return StorageSense.Block.LOCATION_UNPROVEN;
        // TDLib's trim keeps files from the last hour so a song about to play survives; so does
        // this.
        long used = lastUsed(file);
        if (used <= 0
                || System.currentTimeMillis() - used < TelegramBridge.KEEP_RECENT_SECONDS * 1000L)
            return StorageSense.Block.RECENTLY_USED;
        return StorageSense.Block.NONE;
    }

    @Override
    public StorageSense.Listing list(StorageSense.Active active, StorageSense.Cancel cancel)
            throws Exception {
        TelegramBridge bridge = bridge();
        List<StorageSense.Item> items = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        int rows = 0;
        boolean truncated = false;
        chats:
        for (long chat : bridge.indexedChats()) {
            long after = 0;
            while (true) {
                if (cancel.cancelled()) break chats;
                List<JSONObject> page = bridge.index.afterAny(chat, after, PAGE);
                if (page == null || page.isEmpty()) break;
                for (JSONObject row : page) {
                    if (cancel.cancelled()) break chats;
                    after = Math.max(after, row.optLong("message"));
                    if (++rows > MAX_ROWS || items.size() >= MAX_FILES) {
                        truncated = true;
                        break chats;
                    }
                    int id = row.optInt("file");
                    if (id == 0 || !seen.add(id)) continue;
                    StorageSense.Item item = describe(bridge, row, id, chat, active);
                    if (item != null) items.add(item);
                }
                if (page.size() < PAGE) break;
            }
        }
        return new StorageSense.Listing(items, truncated);
    }

    private static StorageSense.Item describe(
            TelegramBridge bridge, JSONObject row, int id, long chat, StorageSense.Active active) {
        TdApi.File file;
        try {
            file = bridge.call(new TdApi.GetFile(id), CALL_MS);
        } catch (Exception gone) {
            return null;
        }
        if (file.local == null || file.local.downloadedSize <= 0) return null;
        String message = chat + "_" + row.optLong("message");
        StorageSense.Item item = new StorageSense.Item();
        item.id = "telegram:" + id;
        item.location = message + "/" + id;
        item.mediaId = message;
        item.category = StorageSense.Category.TELEGRAM_CACHE;
        item.effect = StorageSense.Effect.CACHE_RECLAIM;
        String title = row.optString("title");
        item.title = !title.isEmpty() ? title : row.optString("fileName", message);
        item.subtitle = row.optString("artist");
        item.origin = "TELEGRAM";
        item.bytes = file.local.downloadedSize;
        item.contentLength = file.size > 0 ? file.size : file.expectedSize;
        item.complete = file.local.isDownloadingCompleted;
        item.lastUsedMs = lastUsed(file);
        item.block = block(bridge, file, message, active);
        item.automatic = true;
        return item;
    }

    private static int fileId(StorageSense.Item item) throws IOException {
        try {
            return Integer.parseInt(item.id.substring("telegram:".length()));
        } catch (RuntimeException invalid) {
            throw new IOException(Strings.t("This Telegram file is no longer listed."));
        }
    }

    @Override
    public StorageSense.Block recheck(StorageSense.Item item, StorageSense.Active active)
            throws Exception {
        TelegramBridge bridge = bridge();
        TdApi.File file = bridge.call(new TdApi.GetFile(fileId(item)), CALL_MS);
        return block(bridge, file, item.mediaId, active);
    }

    @Override
    public void remove(StorageSense.Item item) throws Exception {
        TelegramBridge bridge = bridge();
        int id = fileId(item);
        synchronized (bridge.audio.cacheGuard) {
            // A read taking its lease waits here, so none can start between this and the delete.
            if (bridge.audio.leases.containsKey(id))
                throw new StorageSense.Refused(StorageSense.Block.PLAYING);
            bridge.call(new TdApi.DeleteFile(id), CALL_MS);
        }
    }
}
