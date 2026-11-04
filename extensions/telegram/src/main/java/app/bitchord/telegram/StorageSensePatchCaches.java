package app.bitchord.telegram;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Audio that patches hold for themselves. Each folder belongs to an owner that may be using it, and
 * only that owner can say when it's safe, so these rows show real bytes and nothing is removed.
 */
final class StorageSensePatchCaches implements StorageSense.Owner {
    private static final StorageSense.Category[] CATEGORY = {StorageSense.Category.PATCH_CACHE};
    private final Context app;

    StorageSensePatchCaches(Context app) {
        this.app = app.getApplicationContext();
    }

    @Override
    public StorageSense.Category[] categories() {
        return CATEGORY;
    }

    @Override
    public StorageSense.Listing list(StorageSense.Active active, StorageSense.Cancel cancel)
            throws IOException {
        File transfers = new File(app.getNoBackupFilesDir(), "soulseek-native/transfers");
        StorageSenseScanner.Tree tree = StorageSenseScanner.walk(transfers, 3, 2000, true, cancel);
        List<StorageSense.Item> items = new ArrayList<>();
        for (StorageSenseScanner.Entry entry : tree.files) {
            if (cancel.cancelled()) break;
            if (!StorageSenseScanner.looksLikeAudio(entry.file)) continue;
            StorageSense.Item item = new StorageSense.Item();
            item.id = "patch:" + entry.file.getPath();
            item.title = entry.file.getName();
            item.subtitle = Strings.t("Soulseek temporary audio");
            item.category = StorageSense.Category.PATCH_CACHE;
            item.effect = StorageSense.Effect.NONE;
            item.block = StorageSense.Block.READ_ONLY;
            item.bytes = entry.bytes;
            item.lastUsedMs = entry.modifiedMs;
            items.add(item);
        }
        return new StorageSense.Listing(items, tree.truncated);
    }

    @Override
    public StorageSense.Block recheck(StorageSense.Item item, StorageSense.Active active) {
        return StorageSense.Block.READ_ONLY;
    }

    @Override
    public void remove(StorageSense.Item item) throws StorageSense.Refused {
        throw new StorageSense.Refused(StorageSense.Block.READ_ONLY);
    }

    /** A location shown with its reason, so nothing on this page looks like all there is. */
    static final class Unmanaged {
        final String label, reason;
        final long bytes;

        Unmanaged(String label, String reason, long bytes) {
            this.label = label;
            this.reason = reason;
            this.bytes = bytes;
        }
    }

    private static long measure(File root, StorageSense.Cancel cancel) {
        try {
            StorageSenseScanner.Tree tree =
                    StorageSenseScanner.walk(
                            root,
                            StorageSenseScanner.MAX_DEPTH,
                            StorageSenseScanner.MAX_ENTRIES,
                            false,
                            cancel);
            return tree.truncated || tree.cancelled ? -1 : tree.bytes;
        } catch (IOException unreadable) {
            return -1;
        }
    }

    static List<Unmanaged> unmanaged(Context app, StorageSense.Cancel cancel) {
        File files = app.getFilesDir(), noBackup = app.getNoBackupFilesDir();
        List<Unmanaged> rows = new ArrayList<>();
        long staging =
                Math.max(0, measure(new File(noBackup, "soulseek-selected"), cancel))
                        + Math.max(0, measure(new File(noBackup, "bitchord-bulk"), cancel))
                        + Math.max(0, measure(new File(noBackup, "debrid-capture"), cancel));
        rows.add(
                new Unmanaged(
                        Strings.t("Transfers and shared files"),
                        Strings.t(
                                "Staged downloads, uploads and shares stay with the download"
                                        + " manager that's using them."),
                        staging));
        rows.add(
                new Unmanaged(
                        Strings.t("Separation models"),
                        Strings.t("Removed from the Karaoke settings, never from here."),
                        measure(new File(files, "sing-models"), cancel)));
        rows.add(
                new Unmanaged(
                        Strings.t("Accounts and sign-in data"),
                        Strings.t(
                                "Sessions, credentials and databases are never read or changed"
                                        + " here."),
                        -1));
        rows.add(
                new Unmanaged(
                        Strings.t("Other Telegram files"),
                        Strings.t(
                                "Photos and files outside your music chats follow the Telegram"
                                        + " cache target."),
                        -1));
        rows.add(
                new Unmanaged(
                        Strings.t("BitChord's artwork and video covers"),
                        Strings.t("BitChord manages these caches itself."),
                        measure(app.getCacheDir(), cancel)));
        rows.add(
                new Unmanaged(
                        Strings.t("Other music on this device"),
                        Strings.t(
                                "Only files BitChord recorded as its own downloads are listed"
                                        + " under Saved downloads."),
                        -1));
        return rows;
    }
}
