package app.bitchord.telegram;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Durable ordered-pair/recording transitions; disk work never runs on the playback/UI thread. */
final class TransitionStore {
    static final String DATABASE = "bitchord_automix.db";
    private static TransitionStore instance;
    private final Helper helper;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io =
            Executors.newSingleThreadExecutor(
                    task -> {
                        Thread thread = new Thread(task, "automix-database");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final Map<String, Entry> cache =
            new LinkedHashMap<String, Entry>(128, .75f, true) {
                protected boolean removeEldestEntry(
                        Map.Entry<String, TransitionStore.Entry> eldest) {
                    return size() > 128 && eldest.getValue().known;
                }
            };

    static final class Entry {
        volatile boolean known;
        volatile String json;
        volatile Exception error;
        final List<Consumer<Entry>> listeners = new ArrayList<>();

        TransitionEdit edit() {
            return json == null ? null : TransitionEdit.decode(json);
        }
    }

    private TransitionStore(Context context) {
        app = context.getApplicationContext();
        helper = new Helper(app);
    }

    static synchronized TransitionStore get() {
        Context app = SoundExtras.app();
        if (app == null) throw new IllegalStateException("Playback service is not ready");
        if (instance == null) instance = new TransitionStore(app);
        return instance;
    }

    private static String id(TransitionRuntime.Pair pair) {
        return pair.key + ":" + pair.recording();
    }

    Entry lookup(TransitionRuntime.Pair pair) {
        String key = id(pair);
        synchronized (cache) {
            Entry found = cache.get(key);
            if (found != null) return found;
            Entry entry = new Entry();
            cache.put(key, entry);
            String pairKey = pair.key, recording = pair.recording();
            io.execute(
                    () -> {
                        String json = null;
                        Exception failure = null;
                        try (Cursor cursor =
                                database()
                                        .query(
                                                "transitions",
                                                new String[] {"edit_json"},
                                                "pair_key=? AND recording_key=?",
                                                new String[] {pairKey, recording},
                                                null,
                                                null,
                                                null)) {
                            if (cursor.moveToFirst()) {
                                TransitionEdit edit = TransitionEdit.decode(cursor.getString(0));
                                if (recording.equals(edit.recording)) json = edit.encode();
                            }
                        } catch (Exception unavailable) {
                            failure = unavailable;
                        }
                        List<Consumer<Entry>> listeners;
                        synchronized (cache) {
                            entry.json = json;
                            entry.error = failure;
                            entry.known = true;
                            listeners = new ArrayList<>(entry.listeners);
                            entry.listeners.clear();
                        }
                        for (Consumer<Entry> listener : listeners)
                            main.post(() -> listener.accept(entry));
                    });
            return entry;
        }
    }

    void read(TransitionRuntime.Pair pair, Consumer<Entry> listener) {
        Entry entry = lookup(pair);
        synchronized (cache) {
            if (entry.known) main.post(() -> listener.accept(entry));
            else entry.listeners.add(listener);
        }
    }

    static final class Draft {
        final TransitionEdit edit;
        final boolean automatic;
        final Exception error;

        Draft(TransitionEdit edit, boolean automatic, Exception error) {
            this.edit = edit;
            this.automatic = automatic;
            this.error = error;
        }
    }

    void readDraft(TransitionRuntime.Pair pair, Consumer<Draft> done) {
        String key = pair.key, recording = pair.recording();
        io.execute(
                () -> {
                    TransitionEdit edit = null;
                    boolean automatic = false;
                    Exception error = null;
                    try (Cursor cursor =
                            database()
                                    .query(
                                            "drafts",
                                            new String[] {"edit_json", "automatic"},
                                            "pair_key=? AND recording_key=?",
                                            new String[] {key, recording},
                                            null,
                                            null,
                                            null)) {
                        if (cursor.moveToFirst()) {
                            TransitionEdit found = TransitionEdit.decode(cursor.getString(0));
                            if (recording.equals(found.recording)) {
                                edit = found;
                                automatic = cursor.getInt(1) != 0;
                            }
                        }
                    } catch (Exception unavailable) {
                        error = unavailable;
                    }
                    Draft result = new Draft(edit, automatic, error);
                    main.post(() -> done.accept(result));
                });
    }

    void writeDraft(
            TransitionRuntime.Pair pair,
            TransitionEdit draft,
            boolean automatic,
            Consumer<Exception> done) {
        persist(pair, null, draft, automatic, false, done);
    }

    void commit(
            TransitionRuntime.Pair pair,
            TransitionEdit draft,
            boolean automatic,
            Consumer<Exception> done) {
        // Automatic timing stores an override only when the listener chose the tempo.
        TransitionEdit active = automatic && !draft.tempoExplicit ? null : draft.copy();
        if (active != null) active.automaticTiming = automatic;
        persist(pair, active, draft, automatic, true, done);
    }

    void write(TransitionRuntime.Pair pair, TransitionEdit draft, Consumer<Exception> done) {
        persist(pair, draft, null, false, true, done);
    }

    private void persist(
            TransitionRuntime.Pair pair,
            TransitionEdit active,
            TransitionEdit draft,
            boolean automatic,
            boolean apply,
            Consumer<Exception> done) {
        String pairKey = pair.key, recording = pair.recording(), key = id(pair);
        String outgoing = pair.outgoingId, incoming = pair.incomingId;
        TransitionEdit stored = active == null ? null : active.copy();
        if (stored != null) stored.recording = recording;
        String json = stored == null ? null : stored.encode();
        TransitionEdit savedDraft = draft == null ? null : draft.copy();
        if (savedDraft != null) savedDraft.recording = recording;
        String draftJson = savedDraft == null ? null : savedDraft.encode();
        io.execute(
                () -> {
                    Exception failure = null;
                    try {
                        SQLiteDatabase db = database();
                        db.beginTransaction();
                        try {
                            if (apply) {
                                if (json == null)
                                    db.delete(
                                            "transitions",
                                            "pair_key=? AND recording_key=?",
                                            new String[] {pairKey, recording});
                                else {
                                    ContentValues values = row(pairKey, recording, json);
                                    values.put("outgoing_id", outgoing);
                                    values.put("incoming_id", incoming);
                                    insert(db, "transitions", values);
                                }
                            }
                            if (draftJson != null) {
                                ContentValues values = row(pairKey, recording, draftJson);
                                values.put("automatic", automatic ? 1 : 0);
                                insert(db, "drafts", values);
                            }
                            db.setTransactionSuccessful();
                        } finally {
                            db.endTransaction();
                        }
                        if (apply)
                            synchronized (cache) {
                                Entry entry = new Entry();
                                entry.json = json;
                                entry.known = true;
                                cache.put(key, entry);
                            }
                    } catch (Exception unavailable) {
                        failure = unavailable;
                    }
                    Exception result = failure;
                    main.post(() -> done.accept(result));
                });
    }

    private static void insert(SQLiteDatabase db, String table, ContentValues values) {
        if (db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0)
            throw new IllegalStateException("The transition could not be stored");
    }

    private SQLiteDatabase database() {
        SQLiteDatabase db = helper.getWritableDatabase();
        try (Cursor cursor =
                db.rawQuery("SELECT value FROM metadata WHERE name='preferences_migrated'", null)) {
            if (cursor.moveToFirst()) return db;
        }
        db.beginTransaction();
        try {
            for (Map.Entry<String, ?> old :
                    app.getSharedPreferences(TransitionRuntime.PREFERENCES, Context.MODE_PRIVATE)
                            .getAll()
                            .entrySet()) {
                if (!(old.getValue() instanceof String)) continue;
                try {
                    TransitionEdit edit = TransitionEdit.decode((String) old.getValue());
                    if (edit.recording != null)
                        db.insertWithOnConflict(
                                "transitions",
                                null,
                                row(old.getKey(), edit.recording, edit.encode()),
                                SQLiteDatabase.CONFLICT_IGNORE);
                } catch (IllegalArgumentException invalid) {
                    /* Leave invalid/unbound legacy entries untouched. */
                }
            }
            db.execSQL("INSERT INTO metadata(name,value) VALUES('preferences_migrated','1')");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return db;
    }

    private static ContentValues row(String pair, String recording, String json) {
        ContentValues values = new ContentValues();
        values.put("pair_key", pair);
        values.put("recording_key", recording);
        values.put("edit_json", json);
        values.put("updated_at", System.currentTimeMillis());
        return values;
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) {
            super(context, DATABASE, null, 2);
            setWriteAheadLoggingEnabled(true);
        }

        public void onCreate(SQLiteDatabase db) {
            db.execSQL(
                    "CREATE TABLE transitions(pair_key TEXT NOT NULL,recording_key TEXT NOT"
                        + " NULL,outgoing_id TEXT,incoming_id TEXT,edit_json TEXT NOT"
                        + " NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(pair_key,recording_key))");
            db.execSQL("CREATE TABLE metadata(name TEXT PRIMARY KEY,value TEXT NOT NULL)");
            createDrafts(db);
        }

        private static void createDrafts(SQLiteDatabase db) {
            db.execSQL(
                    "CREATE TABLE drafts(pair_key TEXT NOT NULL,recording_key TEXT NOT"
                            + " NULL,edit_json TEXT NOT NULL,automatic INTEGER NOT NULL,updated_at"
                            + " INTEGER NOT NULL,PRIMARY KEY(pair_key,recording_key))");
        }

        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion == 1 && newVersion == 2) createDrafts(db);
            else throw new IllegalStateException("Unsupported AutoMix database version");
        }
    }

    /** Test barrier and process-restart simulation; no app callback waits on this. */
    void awaitIdle() throws Exception {
        io.submit(() -> {}).get(10, TimeUnit.SECONDS);
    }

    static synchronized void closeForTest() throws Exception {
        if (instance == null) return;
        instance.awaitIdle();
        instance.io.shutdown();
        instance.helper.close();
        instance = null;
    }
}
