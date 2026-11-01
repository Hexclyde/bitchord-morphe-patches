package app.bitchord.telegram;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.*;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;

/** Explicit transfer controls; removing history never deletes a saved file. */
public final class DownloadControls {
    private DownloadControls() {}

    static final String PREFS = "bitchord_download_controls";

    public interface Host {
        void cancel(String id) throws Exception;

        void enqueue(Context app, Object song, String from) throws Exception;

        boolean delete(Context app, String id) throws Exception;
    }

    static Host host =
            new Host() {
                private Class<?> type() throws Exception {
                    return Class.forName("com.music.bitchord.download.Downloads");
                }

                public void cancel(String id) throws Exception {
                    type().getMethod("cancel", String.class).invoke(null, id);
                }

                public void enqueue(Context app, Object song, String from) throws Exception {
                    type().getMethod("enqueue", Context.class, song.getClass(), String.class)
                            .invoke(null, app, song, from);
                }

                public boolean delete(Context app, String id) throws Exception {
                    Field field = type().getDeclaredField("_saved");
                    field.setAccessible(true);
                    Object flow = field.get(null);
                    Method read = flow.getClass().getMethod("getValue");
                    read.setAccessible(true);
                    Object raw = ((Map<?, ?>) read.invoke(flow)).get(id);
                    if (!(raw instanceof String))
                        throw new IOException("Saved file is unavailable");
                    android.net.Uri uri = android.net.Uri.parse((String) raw);
                    boolean removed;
                    if ("content".equals(uri.getScheme())) {
                        removed = app.getContentResolver().delete(uri, null, null) > 0;
                    } else if ("file".equals(uri.getScheme())) {
                        removed = new java.io.File(uri.getPath()).delete();
                    } else throw new IOException("This file location cannot be deleted");
                    if (removed) {
                        Method forget = type().getDeclaredMethod("forget", String.class);
                        forget.setAccessible(true);
                        forget.invoke(null, id);
                    }
                    return removed;
                }
            };

    private static SharedPreferences prefs(Context app) {
        return app.getSharedPreferences(PREFS, 0);
    }

    private static String key(String id) {
        return "request." + TransitionRuntime.key(id, "download");
    }

    public static void remember(Context app, Object song, String from) {
        try {
            String id = MixedSongCodec.text(song, "videoId");
            if (id.isEmpty()) return;
            JSONObject record =
                    new JSONObject()
                            .put("id", id)
                            .put("song", MixedSongCodec.encode(song))
                            .put("from", from == null ? JSONObject.NULL : from)
                            .put("paused", false);
            synchronized (DownloadControls.class) {
                SharedPreferences store = prefs(app);
                Set<String> hidden =
                        new HashSet<>(store.getStringSet("hidden", Collections.emptySet()));
                hidden.remove(id);
                SharedPreferences.Editor write = store.edit();
                if (!store.contains(key(id)) && store.getAll().size() >= 513) {
                    for (String old : store.getAll().keySet())
                        if (old.startsWith("request.")) {
                            write.remove(old);
                            break;
                        }
                }
                write.putString(key(id), record.toString()).putStringSet("hidden", hidden).apply();
            }
        } catch (Exception invalid) {
            HostLog.d("Download control metadata could not be retained");
        }
    }

    private static JSONObject request(Context app, String id) throws Exception {
        String raw = prefs(app).getString(key(id), null);
        if (raw != null) return new JSONObject(raw);
        // Existing requests in this process still have their full host session metadata.
        Class<?> session = Class.forName("com.music.bitchord.download.DownloadSession");
        Field flow = session.getDeclaredField("_state");
        flow.setAccessible(true);
        Object stateFlow = flow.get(null);
        Method read = stateFlow.getClass().getMethod("getValue");
        read.setAccessible(true);
        Object state = read.invoke(stateFlow);
        for (Object item : (List<?>) MixedSongCodec.field(state, "items")) {
            if (id.equals(MixedSongCodec.text(item, "videoId"))) {
                remember(
                        app, MixedSongCodec.field(item, "song"), MixedSongCodec.text(item, "from"));
                return new JSONObject(prefs(app).getString(key(id), "{}"));
            }
        }
        throw new IOException("Select this track again to retry its exact source");
    }

    public static boolean hidden(Context app, String id) {
        return prefs(app).getStringSet("hidden", Collections.emptySet()).contains(id);
    }

    public static List<JSONObject> paused(Context app) {
        List<JSONObject> rows = new ArrayList<>();
        for (Map.Entry<String, ?> entry : prefs(app).getAll().entrySet()) {
            if (!entry.getKey().startsWith("request.") || !(entry.getValue() instanceof String))
                continue;
            try {
                JSONObject row = new JSONObject((String) entry.getValue());
                if (row.optBoolean("paused") && !hidden(app, row.optString("id"))) rows.add(row);
            } catch (JSONException ignored) {
            }
        }
        return rows;
    }

    public static void pause(Context app, String id) throws Exception {
        JSONObject row = request(app, id);
        host.cancel(id);
        row.put("paused", true);
        prefs(app).edit().putString(key(id), row.toString()).commit();
    }

    public static void retry(Context app, String id) throws Exception {
        JSONObject row = request(app, id);
        Object song = MixedSongCodec.decode(row.getJSONObject("song"));
        if (!id.equals(MixedSongCodec.text(song, "videoId")))
            throw new IOException("Download source identity changed");
        host.enqueue(app, song, row.isNull("from") ? null : row.optString("from"));
        row.put("paused", false);
        prefs(app).edit().putString(key(id), row.toString()).commit();
    }

    public static void remove(Context app, String id) throws Exception {
        host.cancel(id);
        synchronized (DownloadControls.class) {
            SharedPreferences store = prefs(app);
            Set<String> hidden =
                    new HashSet<>(store.getStringSet("hidden", Collections.emptySet()));
            hidden.add(id);
            store.edit().putStringSet("hidden", hidden).remove(key(id)).commit();
        }
    }

    public static boolean deleteLocalFile(Context app, String location) throws Exception {
        android.net.Uri uri = android.net.Uri.parse(location);
        if ("content".equals(uri.getScheme()))
            return app.getContentResolver().delete(uri, null, null) > 0;
        if ("file".equals(uri.getScheme())) return new java.io.File(uri.getPath()).delete();
        throw new IOException("This file location cannot be deleted");
    }

    public static boolean delete(Context app, String id) throws Exception {
        host.cancel(id);
        if (!host.delete(app, id)) return false;
        remove(app, id);
        return true;
    }
}
