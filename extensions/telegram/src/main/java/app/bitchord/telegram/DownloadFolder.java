package app.bitchord.telegram;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONObject;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/** A folder choice is captured at admission, before a native transfer starts. */
public final class DownloadFolder {
    static final String PREFS = "bitchord_download_folder";
    static final String SELECTIONS = "bitchord-download-destinations";
    private static final String WRITES = "write.";
    private static final String CREATING = "creating.";
    private static final ThreadLocal<Selection> naming = new ThreadLocal<>();
    private static Context app;

    private DownloadFolder() {}

    static synchronized void init(Context context) {
        app = context.getApplicationContext();
    }

    private static SharedPreferences prefs() {
        Context context = app;
        if (context == null) context = ExtrasUi.context;
        if (context == null)
            throw new IllegalStateException(Strings.t("Download folder unavailable"));
        return context.getSharedPreferences(PREFS, 0);
    }

    public static String selected(Context context) {
        return context.getSharedPreferences(SELECTIONS, 0).getString("defaultFolderTree", "");
    }

    static String label(Context context) {
        String name =
                context.getSharedPreferences(SELECTIONS, 0).getString("defaultFolderLabel", "");
        return name.isEmpty() ? Strings.t("BitChord default") : name;
    }

    static JSONObject snapshot(Context context) throws Exception {
        init(context);
        return new JSONObject().put("tree", selected(context)).put("export", nativeExport());
    }

    static boolean nativeExport() {
        try {
            Class<?> settings = Class.forName("com.music.bitchord.data.settings.AppSettings");
            Object flow = settings.getField("exportDownloads").get(null);
            java.lang.reflect.Method read = flow.getClass().getMethod("getValue");
            read.setAccessible(true);
            return Boolean.TRUE.equals(read.invoke(flow));
        } catch (ReflectiveOperationException | LinkageError absent) {
            throw unavailable(absent);
        }
    }

    public static String prepare(Context context, String value) throws IOException {
        init(context);
        String name = value.isEmpty() ? "" : verify(context.getContentResolver(), Uri.parse(value));
        DownloadControls.freezeFolders(context);
        return name;
    }

    public static void choose(Context context, String value) throws IOException {
        String name = prepare(context, value);
        SharedPreferences.Editor write =
                context.getSharedPreferences(SELECTIONS, 0)
                        .edit()
                        .putString("defaultFolderTree", value)
                        .putString("defaultFolderLabel", name);
        if (!value.isEmpty()) write.putString("phoneTreeUri", value);
        if (!write.commit()) throw new IOException(Strings.t("Download folder unavailable"));
    }

    static String verify(ContentResolver resolver, Uri tree) throws IOException {
        try {
            if (!"content".equals(tree.getScheme()) || !DocumentsContract.isTreeUri(tree))
                throw unavailable(null);
            boolean granted = false;
            for (UriPermission permission : resolver.getPersistedUriPermissions())
                if (tree.equals(permission.getUri())
                        && permission.isReadPermission()
                        && permission.isWritePermission()) granted = true;
            if (!granted) throw unavailable(null);
            Uri parent = parent(tree);
            try (Cursor cursor =
                    resolver.query(
                            parent,
                            new String[] {
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                DocumentsContract.Document.COLUMN_MIME_TYPE,
                                DocumentsContract.Document.COLUMN_FLAGS
                            },
                            null,
                            null,
                            null)) {
                if (cursor == null
                        || !cursor.moveToFirst()
                        || !DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(1))
                        || (cursor.getInt(2) & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE)
                                == 0) throw unavailable(null);
                return cursor.getString(0) == null ? tree.getAuthority() : cursor.getString(0);
            }
        } catch (RuntimeException failure) {
            throw new IOException(unavailable(failure).getMessage(), failure);
        }
    }

    private static Uri within(Uri tree, Uri document) throws IOException {
        if (!"content".equals(document.getScheme())
                || !tree.getAuthority().equals(document.getAuthority()))
            throw new IOException(Strings.t("Download folder unavailable"));
        return DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getDocumentId(document));
    }

    private static Uri parent(Uri tree) {
        return DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
    }

    static void naming(Object song) {
        naming.remove();
        Context context = app == null ? ExtrasUi.context : app;
        if (context == null) return;
        String id = MixedSongCodec.text(song, "videoId");
        JSONObject folder = DownloadControls.folder(context, id);
        if (folder == null && !selected(context).isEmpty()) throw unavailable(null);
        if (folder != null)
            naming.set(new Selection(id, folder.optString("tree"), folder.optBoolean("export")));
    }

    static void named(String name) {
        Selection current = naming.get();
        if (current != null) current.name = name;
    }

    private static Selection selection(String name) {
        Selection current = naming.get();
        return current != null && name.equals(current.name) ? current : null;
    }

    public static boolean custom(String name) {
        Selection current = selection(name);
        if (current == null) naming.remove();
        return current != null && !current.tree.isEmpty();
    }

    public static boolean export(boolean wanted) {
        Selection current = naming.get();
        if (current == null || current.name == null) return wanted;
        return !current.tree.isEmpty() || current.export;
    }

    public static Uri existing(Context context, String name) {
        Selection current = selection(name);
        if (current == null || current.tree.isEmpty())
            throw new IllegalStateException("Download folder context changed");
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri tree = Uri.parse(current.tree);
            verify(resolver, tree);
            Uri children =
                    DocumentsContract.buildChildDocumentsUriUsingTree(
                            tree, DocumentsContract.getTreeDocumentId(tree));
            try (Cursor cursor =
                    resolver.query(
                            children,
                            new String[] {
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                DocumentsContract.Document.COLUMN_MIME_TYPE
                            },
                            null,
                            null,
                            null)) {
                if (cursor == null) throw unavailable(null);
                while (cursor.moveToNext())
                    if (name.equals(cursor.getString(1))
                            && !DocumentsContract.Document.MIME_TYPE_DIR.equals(
                                    cursor.getString(2))) {
                        Uri document =
                                DocumentsContract.buildDocumentUriUsingTree(
                                        tree, cursor.getString(0));
                        if (!pending(tree, document, name)) return document;
                    }
            }
            return null;
        } catch (Exception failure) {
            throw unavailable(failure);
        }
    }

    public static Uri insert(ContentResolver resolver, Uri collection, ContentValues values) {
        String name = values.getAsString("_display_name");
        Selection current = name == null ? null : selection(name);
        if (current == null || current.tree.isEmpty()) return resolver.insert(collection, values);
        Uri created = null;
        JSONObject receipt = null;
        String creating = null;
        try {
            Uri tree = Uri.parse(current.tree);
            verify(resolver, tree);
            recover(resolver, current.id);
            String token = UUID.randomUUID().toString();
            receipt =
                    new JSONObject()
                            .put("id", current.id)
                            .put("tree", current.tree)
                            .put("token", token)
                            .put("name", name)
                            .put("phase", "writing");
            creating = CREATING + token;
            synchronized (DownloadFolder.class) {
                if (prefs().getAll().size() >= 514
                        || !prefs().edit().putString(creating, receipt.toString()).commit())
                    throw unavailable(null);
            }
            created =
                    DocumentsContract.createDocument(
                            resolver, parent(tree), values.getAsString("mime_type"), name);
            if (created == null) throw unavailable(null);
            created = within(tree, created);
            try (Cursor cursor =
                    resolver.query(
                            created,
                            new String[] {
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                DocumentsContract.Document.COLUMN_FLAGS
                            },
                            null,
                            null,
                            null)) {
                int required = DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
                if (cursor == null || !cursor.moveToFirst()) throw unavailable(null);
                String actualName = cursor.getString(0);
                if (actualName == null || actualName.isEmpty()) throw unavailable(null);
                receipt.put("documentName", actualName);
                if ((cursor.getInt(1) & required) != required) throw unavailable(null);
            }
            synchronized (DownloadFolder.class) {
                if (prefs().getAll().size() >= 514
                        || !prefs().edit()
                                .putString(WRITES + created, receipt.toString())
                                .remove(creating)
                                .commit()) throw unavailable(null);
            }
            return created;
        } catch (Exception failure) {
            if (created != null && receipt != null)
                try {
                    if (owned(resolver, created, receipt)
                            && DocumentsContract.deleteDocument(resolver, created))
                        prefs().edit().remove(creating).remove(WRITES + created).commit();
                } catch (Exception ignored) {
                }
            throw unavailable(failure);
        } finally {
            naming.remove();
        }
    }

    public static OutputStream open(ContentResolver resolver, Uri uri) throws IOException {
        JSONObject receipt = receipt(uri);
        if (receipt == null) return resolver.openOutputStream(uri);
        if (!"writing".equals(receipt.optString("phase"))) throw unavailable(null);
        try {
            OutputStream stream = resolver.openOutputStream(uri, "wt");
            if (stream == null) throw unavailable(null);
            return new FilterOutputStream(stream) {
                public void write(int value) throws IOException {
                    try {
                        out.write(value);
                    } catch (IOException failure) {
                        throw unavailable(failure);
                    }
                }

                public void write(byte[] bytes, int offset, int length) throws IOException {
                    try {
                        out.write(bytes, offset, length);
                    } catch (IOException failure) {
                        throw unavailable(failure);
                    }
                }

                public void flush() throws IOException {
                    try {
                        out.flush();
                    } catch (IOException failure) {
                        throw unavailable(failure);
                    }
                }

                public void close() throws IOException {
                    try {
                        out.close();
                    } catch (IOException failure) {
                        throw unavailable(failure);
                    }
                }
            };
        } catch (IOException | RuntimeException failure) {
            throw unavailable(failure);
        }
    }

    public static Uri commit(
            ContentResolver resolver, Uri uri, ContentValues values, String where, String[] args) {
        JSONObject receipt = receipt(uri);
        if (receipt == null) {
            resolver.update(uri, values, where, args);
            return uri;
        }
        try {
            verify(resolver, Uri.parse(receipt.getString("tree")));
            if (!"writing".equals(receipt.optString("phase")) || !owned(resolver, uri, receipt))
                throw new IOException(
                        Strings.t("Check the download folder before retrying this file"));
            phase(uri, receipt, "publishing");
            synchronized (DownloadFolder.class) {
                JSONObject latest = receipt(uri);
                if (latest == null
                        || !"publishing".equals(latest.optString("phase"))
                        || !receipt.optString("token").equals(latest.optString("token")))
                    throw unavailable(null);
                if (!prefs().edit().remove(WRITES + uri).commit()) {
                    prefs().edit().putString(WRITES + uri, latest.toString()).apply();
                    throw unavailable(null);
                }
            }
            return uri;
        } catch (Exception failure) {
            throw unavailable(failure);
        }
    }

    public static int delete(ContentResolver resolver, Uri uri, String where, String[] args) {
        JSONObject receipt = receipt(uri);
        if (receipt == null) {
            if (!DocumentsContract.isTreeUri(uri)) return resolver.delete(uri, where, args);
            try {
                return DocumentsContract.deleteDocument(resolver, uri) ? 1 : 0;
            } catch (Exception failure) {
                return 0;
            }
        }
        try {
            String state = receipt.optString("phase");
            if (!state.equals("writing")
                    && !state.equals("detached-writing")
                    && !state.equals("deleting")
                    && !state.equals("detached-deleting")) return 0;
            if (!owned(resolver, uri, receipt)) {
                verify(resolver, Uri.parse(receipt.getString("tree")));
                try (Cursor cursor =
                        resolver.query(
                                uri,
                                new String[] {DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                                null,
                                null,
                                null)) {
                    if (cursor != null && !cursor.moveToFirst()) {
                        prefs().edit().remove(WRITES + uri).commit();
                        return 1;
                    }
                }
                return 0;
            }
            phase(uri, receipt, state.startsWith("detached") ? "detached-deleting" : "deleting");
            boolean deleted = DocumentsContract.deleteDocument(resolver, uri);
            if (deleted) prefs().edit().remove(WRITES + uri).commit();
            return deleted ? 1 : 0;
        } catch (Exception failure) {
            return 0;
        }
    }

    private static void recover(ContentResolver resolver, String id) throws IOException {
        for (java.util.Map.Entry<String, ?> entry : prefs().getAll().entrySet()) {
            if ((!entry.getKey().startsWith(WRITES) && !entry.getKey().startsWith(CREATING))
                    || !(entry.getValue() instanceof String)) continue;
            try {
                JSONObject receipt = new JSONObject((String) entry.getValue());
                if (!id.equals(receipt.optString("id"))
                        || receipt.optString("phase").startsWith("detached")) continue;
                if (entry.getKey().startsWith(CREATING))
                    throw new IOException(
                            Strings.t("Check the download folder before retrying this file"));
                Uri uri = Uri.parse(entry.getKey().substring(WRITES.length()));
                if (delete(resolver, uri, null, null) == 0)
                    throw new IOException(
                            Strings.t("Check the download folder before retrying this file"));
            } catch (IOException failure) {
                throw failure;
            } catch (Exception failure) {
                throw unavailable(failure);
            }
        }
    }

    static synchronized void removed(Context context, String id) {
        init(context);
        SharedPreferences.Editor write = prefs().edit();
        for (java.util.Map.Entry<String, ?> entry : prefs().getAll().entrySet()) {
            if ((!entry.getKey().startsWith(WRITES) && !entry.getKey().startsWith(CREATING))
                    || !(entry.getValue() instanceof String)) continue;
            try {
                JSONObject receipt = new JSONObject((String) entry.getValue());
                if (!id.equals(receipt.optString("id"))) continue;
                if (entry.getKey().startsWith(CREATING))
                    write.putString(entry.getKey(), receipt.put("phase", "detached").toString());
                else {
                    String state = receipt.optString("phase");
                    if (state.equals("publishing")) receipt.put("phase", "detached");
                    else if (state.equals("writing") || state.equals("deleting"))
                        receipt.put("phase", "detached-" + state);
                    write.putString(entry.getKey(), receipt.toString());
                }
            } catch (Exception ignored) {
            }
        }
        write.commit();
    }

    private static synchronized void phase(Uri uri, JSONObject expected, String next)
            throws Exception {
        JSONObject latest = receipt(uri);
        if (latest == null
                || !expected.optString("phase").equals(latest.optString("phase"))
                || !expected.optString("token").equals(latest.optString("token")))
            throw unavailable(null);
        latest.put("phase", next);
        if (!prefs().edit().putString(WRITES + uri, latest.toString()).commit())
            throw unavailable(null);
    }

    private static boolean owned(ContentResolver resolver, Uri uri, JSONObject receipt)
            throws Exception {
        Uri tree = Uri.parse(receipt.getString("tree"));
        if (!tree.getAuthority().equals(uri.getAuthority())
                || !DocumentsContract.getTreeDocumentId(tree)
                        .equals(DocumentsContract.getTreeDocumentId(uri))) return false;
        String token = receipt.optString("token");
        if (!token.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            return false;
        String documentName = receipt.optString("documentName");
        if (documentName.isEmpty()) return false;
        try (Cursor cursor =
                resolver.query(
                        uri,
                        new String[] {DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                        null,
                        null,
                        null)) {
            return cursor != null
                    && cursor.moveToFirst()
                    && documentName.equals(cursor.getString(0));
        }
    }

    private static boolean pending(Uri tree, Uri document, String name) throws Exception {
        for (java.util.Map.Entry<String, ?> entry : prefs().getAll().entrySet()) {
            if ((!entry.getKey().startsWith(WRITES) && !entry.getKey().startsWith(CREATING))
                    || !(entry.getValue() instanceof String)) continue;
            JSONObject row = new JSONObject((String) entry.getValue());
            Uri owner = Uri.parse(row.getString("tree"));
            if (!tree.getAuthority().equals(owner.getAuthority())
                    || !DocumentsContract.getTreeDocumentId(tree)
                            .equals(DocumentsContract.getTreeDocumentId(owner))) continue;
            if (entry.getKey().startsWith(CREATING) && name.equals(row.optString("name")))
                return true;
            if (entry.getKey().startsWith(WRITES)) {
                Uri reserved = Uri.parse(entry.getKey().substring(WRITES.length()));
                if (document.getAuthority().equals(reserved.getAuthority())
                        && DocumentsContract.getDocumentId(document)
                                .equals(DocumentsContract.getDocumentId(reserved))) return true;
            }
        }
        return false;
    }

    private static JSONObject receipt(Uri uri) {
        String raw = prefs().getString(WRITES + uri, null);
        if (raw == null) return null;
        try {
            return new JSONObject(raw);
        } catch (Exception invalid) {
            throw unavailable(invalid);
        }
    }

    private static IllegalStateException unavailable(Throwable cause) {
        if (cause != null
                && Strings.t("Check the download folder before retrying this file")
                        .equals(cause.getMessage()))
            return new IllegalStateException(cause.getMessage(), cause);
        return new IllegalStateException(
                Strings.t(
                        "Download folder unavailable. Reconnect the drive or choose the folder"
                                + " again in Downloads settings."),
                cause);
    }

    private static final class Selection {
        final String id, tree;
        final boolean export;
        String name;

        Selection(String id, String tree, boolean export) {
            this.id = id;
            this.tree = tree;
            this.export = export;
        }
    }
}
