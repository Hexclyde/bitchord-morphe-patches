package app.bitchord.telegram;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONObject;

import java.io.File;
import java.util.*;

/**
 * The saved index on the phone. Files are rows of a table, with their words in a full-text table
 * beside it, so a chat of a few hundred thousand files searches in milliseconds and never has to be
 * held in memory.
 */
final class SqliteIndexStore implements IndexStore {
    private final SQLiteDatabase db;

    SqliteIndexStore(File file) {
        file.getParentFile().mkdirs();
        db = SQLiteDatabase.openOrCreateDatabase(file, null);
        db.enableWriteAheadLogging();
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS files (id INTEGER PRIMARY KEY, chat INTEGER NOT NULL,"
                        + " message INTEGER NOT NULL, generation INTEGER NOT NULL, row TEXT NOT"
                        + " NULL, UNIQUE (chat, message))");
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS words USING fts4(text)");
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS chats (chat INTEGER PRIMARY KEY, state TEXT NOT"
                        + " NULL)");
    }

    @Override
    public State state(long chat) throws Exception {
        try (Cursor c =
                db.rawQuery(
                        "SELECT state FROM chats WHERE chat = ?",
                        new String[] {Long.toString(chat)})) {
            return c.moveToFirst()
                    ? State.fromJson(chat, new JSONObject(c.getString(0)))
                    : new State(chat);
        }
    }

    @Override
    public void save(State state) throws Exception {
        ContentValues values = new ContentValues();
        values.put("chat", state.chat);
        values.put("state", state.toJson().toString());
        db.insertWithOnConflict("chats", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    @Override
    public void put(long chat, List<JSONObject> rows, long generation) {
        db.beginTransaction();
        try {
            for (JSONObject row : rows) {
                long message = row.optLong("message");
                long id = -1;
                try (Cursor c =
                        db.rawQuery(
                                "SELECT id FROM files WHERE chat = ? AND message = ?",
                                new String[] {Long.toString(chat), Long.toString(message)})) {
                    if (c.moveToFirst()) id = c.getLong(0);
                }
                ContentValues file = new ContentValues();
                file.put("generation", generation);
                file.put("row", row.toString());
                ContentValues words = new ContentValues();
                words.put("text", IndexStore.text(row));
                if (id >= 0) {
                    db.update("files", file, "id = ?", new String[] {Long.toString(id)});
                    db.update("words", words, "docid = ?", new String[] {Long.toString(id)});
                } else {
                    file.put("chat", chat);
                    file.put("message", message);
                    id = db.insert("files", null, file);
                    words.put("docid", id);
                    db.insert("words", null, words);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public boolean has(long chat, long message) {
        try (Cursor c =
                db.rawQuery(
                        "SELECT 1 FROM files WHERE chat = ? AND message = ? LIMIT 1",
                        new String[] {Long.toString(chat), Long.toString(message)})) {
            return c.moveToFirst();
        }
    }

    @Override
    public int count(long chat) {
        try (Cursor c =
                db.rawQuery(
                        "SELECT count(*) FROM files WHERE chat = ?",
                        new String[] {Long.toString(chat)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    @Override
    public List<JSONObject> search(Collection<Long> chats, List<String> words, int limit)
            throws Exception {
        List<JSONObject> found = new ArrayList<>();
        if (words.isEmpty() || chats.isEmpty()) return found;
        // Words are letters and digits only (see TrackNames.words), so they're safe in a MATCH.
        StringBuilder match = new StringBuilder();
        for (String word : words)
            match.append(match.length() == 0 ? "" : " ").append(word).append('*');
        List<String> args = new ArrayList<>();
        args.add(match.toString());
        StringBuilder in = new StringBuilder();
        for (long chat : chats) {
            in.append(in.length() == 0 ? "?" : ", ?");
            args.add(Long.toString(chat));
        }
        try (Cursor c =
                db.rawQuery(
                        // The full-text table is asked for its matches first. Written as a join,
                        // SQLite walks every file and checks each one against the full-text
                        // table, which took 5 to 40 seconds over 223,776 files; this takes
                        // well under a tenth of a second.
                        "SELECT row FROM files WHERE id IN (SELECT docid FROM words WHERE text"
                                + " MATCH ?) AND chat IN ("
                                + in
                                + ") ORDER BY message DESC LIMIT "
                                + Math.max(0, limit),
                        args.toArray(new String[0]))) {
            while (c.moveToNext()) found.add(new JSONObject(c.getString(0)));
        }
        return found;
    }

    @Override
    public List<JSONObject> newest(long chat, int limit) throws Exception {
        List<JSONObject> found = new ArrayList<>();
        try (Cursor c =
                db.rawQuery(
                        "SELECT row FROM files WHERE chat = ? ORDER BY message DESC LIMIT "
                                + Math.max(0, limit),
                        new String[] {Long.toString(chat)})) {
            while (c.moveToNext()) found.add(new JSONObject(c.getString(0)));
        }
        return found;
    }

    @Override
    public void prune(long chat, long generation) {
        String[] args = {Long.toString(chat), Long.toString(generation)};
        db.beginTransaction();
        try {
            db.execSQL(
                    "DELETE FROM words WHERE docid IN (SELECT id FROM files WHERE chat = ? AND"
                            + " generation < ?)",
                    args);
            db.execSQL("DELETE FROM files WHERE chat = ? AND generation < ?", args);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public void clear() {
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM words");
            db.execSQL("DELETE FROM files");
            db.execSQL("DELETE FROM chats");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        // Gives the space back: a large channel's index is well over 100 MB.
        db.execSQL("VACUUM");
    }
}
