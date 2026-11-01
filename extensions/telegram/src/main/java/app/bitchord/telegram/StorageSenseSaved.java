package app.bitchord.telegram;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Files BitChord's download record names. Deleting one is permanent, so a row can only be removed
 * by hand, and only when the record and its location both prove it's BitChord's own download.
 */
final class StorageSenseSaved implements StorageSense.Owner {
    static final int MAX_RECORDS = 5000;
    private static final StorageSense.Category[] CATEGORY = {StorageSense.Category.SAVED};

    interface Records {
        Map<String, String> saved() throws Exception;

        Map<String, Object> metadata();

        void forget(String id) throws Exception;
    }

    private final Context app;
    private final Records records;

    StorageSenseSaved(Context app, Records records) {
        this.app = app.getApplicationContext();
        this.records = records;
    }

    private List<File> roots() {
        List<File> roots = new ArrayList<>();
        roots.add(new File(app.getFilesDir(), "downloads"));
        roots.add(new File(app.getFilesDir(), "offline-hls"));
        roots.add(
                new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                        "BitChord"));
        return roots;
    }

    private static boolean offlinePackage(File file) {
        File parent = file.getParentFile();
        File grand = parent == null ? null : parent.getParentFile();
        return file.getName().equals("playlist.m3u8")
                && grand != null
                && grand.getName().equals("offline-hls");
    }

    /** Only the media store's own audio rows, which it refuses to delete for anyone else. */
    static boolean mediaAudio(Uri uri) {
        return "content".equals(uri.getScheme())
                && "media".equals(uri.getAuthority())
                && uri.getPath() != null
                && uri.getPath().contains("/audio/");
    }

    private StorageSense.Block location(Uri uri) {
        if (mediaAudio(uri)) return StorageSense.Block.NONE;
        if (!"file".equals(uri.getScheme()) || uri.getPath() == null)
            return StorageSense.Block.LOCATION_UNPROVEN;
        return StorageSenseScanner.prove(new File(uri.getPath()), roots()) != null
                ? StorageSense.Block.NONE
                : StorageSense.Block.LOCATION_UNPROVEN;
    }

    @Override
    public StorageSense.Category[] categories() {
        return CATEGORY;
    }

    @Override
    public StorageSense.Listing list(StorageSense.Active active, StorageSense.Cancel cancel)
            throws Exception {
        Map<String, List<String>> byUri = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : records.saved().entrySet()) {
            List<String> ids = byUri.get(entry.getValue());
            if (ids == null) byUri.put(entry.getValue(), ids = new ArrayList<>());
            ids.add(entry.getKey());
        }
        Map<String, Object> metadata = records.metadata();
        List<StorageSense.Item> items = new ArrayList<>();
        boolean truncated = false;
        for (Map.Entry<String, List<String>> entry : byUri.entrySet()) {
            if (cancel.cancelled()) break;
            if (items.size() >= MAX_RECORDS) {
                truncated = true;
                break;
            }
            StorageSense.Item item = describe(entry.getKey(), entry.getValue(), metadata, active);
            if (item != null) items.add(item);
        }
        return new StorageSense.Listing(items, truncated);
    }

    private StorageSense.Item describe(
            String location,
            List<String> ids,
            Map<String, Object> metadata,
            StorageSense.Active active) {
        Uri uri = Uri.parse(location);
        StorageSense.Item item = new StorageSense.Item();
        item.id = "saved:" + location;
        item.category = StorageSense.Category.SAVED;
        item.effect = StorageSense.Effect.DELETE_SAVED_FILE;
        item.location = location;
        item.aliases = ids;
        item.mediaId = ids.get(0);
        AudioOrigin.Source source = AudioOrigin.savedSource(item.mediaId, location);
        if (source != null && source.name != null) item.sourceName = source.name;
        item.bytes = -1;
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            File file = new File(uri.getPath());
            if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null;
            item.lastUsedMs = file.lastModified();
            item.title = file.getName();
            if (offlinePackage(file)) {
                try {
                    item.bytes =
                            StorageSenseScanner.walk(
                                            file.getParentFile(),
                                            2,
                                            4096,
                                            false,
                                            StorageSense.NEVER)
                                    .bytes;
                } catch (IOException unreadable) {
                    item.bytes = -1;
                }
            } else item.bytes = file.length();
        } else if ("content".equals(uri.getScheme())) {
            try (Cursor row = app.getContentResolver().query(uri, null, null, null, null)) {
                if (row == null || !row.moveToFirst()) return null;
                int size = row.getColumnIndex(OpenableColumns.SIZE);
                int name = row.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (size >= 0 && !row.isNull(size)) item.bytes = row.getLong(size);
                if (name >= 0 && !row.isNull(name)) item.title = row.getString(name);
            } catch (RuntimeException unreadable) {
                item.block = StorageSense.Block.LOCATION_UNPROVEN;
                item.title = uri.getLastPathSegment();
                return item;
            }
        } else {
            item.block = StorageSense.Block.LOCATION_UNPROVEN;
            item.title = location;
            return item;
        }
        for (String id : ids) {
            Object about = metadata.get(id);
            if (about == null) continue;
            String title = MixedSongCodec.text(about, "title");
            if (!title.isEmpty()) item.title = title;
            item.subtitle = MixedSongCodec.text(about, "artist");
            break;
        }
        if (item.title == null || item.title.isEmpty()) item.title = location;
        item.block = block(item, active);
        return item;
    }

    private StorageSense.Block block(StorageSense.Item item, StorageSense.Active active) {
        StorageSense.Block where = location(Uri.parse(item.location));
        if (where != StorageSense.Block.NONE) return where;
        return active.savedBlock(item.location, item.aliases);
    }

    @Override
    public StorageSense.Block recheck(StorageSense.Item item, StorageSense.Active active) {
        return block(item, active);
    }

    @Override
    public void remove(StorageSense.Item item) throws Exception {
        Uri uri = Uri.parse(item.location);
        boolean gone;
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            File file = new File(uri.getPath());
            File proven = StorageSenseScanner.prove(file, roots());
            if (proven == null)
                throw new StorageSense.Refused(StorageSense.Block.LOCATION_UNPROVEN);
            if (offlinePackage(proven)) {
                StorageSenseScanner.deleteTree(proven.getParentFile(), roots());
                gone = true;
            } else gone = proven.delete();
        } else if (mediaAudio(uri)) {
            try {
                gone = app.getContentResolver().delete(uri, null, null) > 0;
            } catch (SecurityException notOurs) {
                throw new IOException(Strings.t("Android didn't allow this file to be deleted."));
            }
        } else throw new StorageSense.Refused(StorageSense.Block.LOCATION_UNPROVEN);
        if (!gone) throw new IOException(Strings.t("This file couldn't be deleted."));
        // One file can answer to two ids: a music video and the catalogue track it became.
        for (String id : item.aliases) {
            try {
                records.forget(id);
                DownloadControls.remove(app, id);
            } catch (Exception | LinkageError stale) {
                // The host drops a record the first time it finds the file missing.
            }
        }
    }

    /** Reads the file's contents for the duplicate review. Offline packages aren't single files. */
    String sha256(StorageSense.Item item, StorageSense.Cancel cancel) throws IOException {
        Uri uri = Uri.parse(item.location);
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            File proven = StorageSenseScanner.prove(new File(uri.getPath()), roots());
            if (proven == null || offlinePackage(proven)) return null;
            try (InputStream in = Files.newInputStream(proven.toPath())) {
                return StorageSenseScanner.sha256(in, cancel);
            }
        }
        if (!mediaAudio(uri)) return null;
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            return in == null ? null : StorageSenseScanner.sha256(in, cancel);
        } catch (SecurityException notReadable) {
            return null;
        }
    }

    /** The host's record, by the fields it has kept through its builds. */
    static final class HostRecords implements Records {
        private static Class<?> type() throws ClassNotFoundException {
            return Class.forName("com.music.bitchord.download.Downloads");
        }

        private static Map<?, ?> flow(String name) throws Exception {
            Field field = type().getDeclaredField(name);
            field.setAccessible(true);
            Object flow = field.get(null);
            Method read = flow.getClass().getMethod("getValue");
            read.setAccessible(true);
            return (Map<?, ?>) read.invoke(flow);
        }

        @Override
        public Map<String, String> saved() throws Exception {
            try {
                Map<String, String> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : flow("_saved").entrySet())
                    if (entry.getKey() instanceof String && entry.getValue() instanceof String)
                        out.put((String) entry.getKey(), (String) entry.getValue());
                return out;
            } catch (ReflectiveOperationException | LinkageError | ClassCastException unknown) {
                throw new StorageSense.Unavailable(
                        Strings.t("This version of BitChord doesn't expose its downloads."));
            }
        }

        @Override
        public Map<String, Object> metadata() {
            Map<String, Object> out = new LinkedHashMap<>();
            try {
                for (Map.Entry<?, ?> entry : flow("_savedMetadata").entrySet())
                    if (entry.getKey() instanceof String)
                        out.put((String) entry.getKey(), entry.getValue());
            } catch (Exception | LinkageError absent) {
                return Collections.emptyMap();
            }
            return out;
        }

        @Override
        public void forget(String id) throws Exception {
            Method forget = type().getDeclaredMethod("forget", String.class);
            forget.setAccessible(true);
            forget.invoke(null, id);
        }
    }
}
