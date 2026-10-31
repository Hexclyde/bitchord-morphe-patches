package app.bitchord.telegram;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.widget.*;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import org.json.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;

/** Device-owned playlists; the host's native Library, detail page and playback remain in use. */
public final class MixedPlaylists {
    static final String PREFERENCES = "bitchord_mixed_playlists", HOST_PREFERENCES = "bitchord_local_playlists";
    private static final String STORE = "com.music.bitchord.data.spotify.LocalPlaylistStore";
    private static Context context;
    private static SharedPreferences prefs, hostPrefs;
    private static SharedPreferences.OnSharedPreferenceChangeListener changed;
    private static final Set<String> managed = new HashSet<>();
    private static WeakReference<Activity> current = new WeakReference<>(null);
    static volatile boolean enabled;
    private MixedPlaylists() {}

    static synchronized void init(Context app) {
        if (context != null || app == null) return;
        context = app.getApplicationContext(); prefs = context.getSharedPreferences(PREFERENCES, 0);
        enabled = prefs.getBoolean("enabled", false); hostPrefs = context.getSharedPreferences(HOST_PREFERENCES, 0);
        try { restore(); } catch (Exception e) { HostLog.d("Mixed playlists restore failed: " + e); }
        changed = (p, key) -> { if (key.equals("playlists_json")) try { remember(nativePlaylists()); } catch (Exception e) { HostLog.d("Mixed playlist save failed: " + e); } };
        hostPrefs.registerOnSharedPreferenceChangeListener(changed);
        if (context instanceof Application) ((Application) context).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle b) { current = new WeakReference<>(a); }
            public void onActivityResumed(Activity a) { current = new WeakReference<>(a); }
            public void onActivityStarted(Activity a) {}
            public void onActivityPaused(Activity a) {}
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            public void onActivityDestroyed(Activity a) { if (current.get() == a) current.clear(); }
        });
    }
    static void setEnabled(boolean on) { enabled = on; if (prefs != null) prefs.edit().putBoolean("enabled", on).apply(); }
    public static boolean available() { return SoundExtras.mixedPlaylistsApplied() && enabled; }
    public static boolean allowPlaylist() { return available(); }
    public static Function0<Unit> playlistAction(Object song, Function0<Unit> original) {
        if (!available()) return original;
        return () -> { if (!available()) return original.invoke(); Activity a = current.get(); if (a == null || a.isFinishing()) return original.invoke(); offer(a, song, original); return Unit.INSTANCE; };
    }
    public static Function0<Unit> newPlaylistAction(Function0<Unit> original) {
        if (!available()) return original;
        return () -> { if (!available()) return original.invoke(); Activity a = current.get(); if (a == null || a.isFinishing()) return original.invoke();
            new AlertDialog.Builder(a).setTitle(Strings.t("New playlist")).setItems(new String[]{Strings.t("On this device · any audio source"), Strings.t("YouTube Music · YouTube tracks only")},
                (d, i) -> { if (i == 0) createDialog(a, null); else original.invoke(); }).show(); return Unit.INSTANCE; };
    }
    public static void offerFile(Activity activity, String uri, String title) {
        if (!available()) return;
        try { choose(activity, MixedSongCodec.file(uri, title)); } catch (Exception e) { failure(activity); }
    }
    private static void offer(Activity a, Object song, Function0<Unit> original) {
        try { MixedSongCodec.key(song); } catch (Exception e) { original.invoke(); return; }
        boolean file = !MixedSongCodec.text(song,"localUri").isEmpty();
        boolean youtube = MixedSongCodec.text(song,"videoId").matches("[A-Za-z0-9_-]{11}");
        if (!file && !youtube) { choose(a, song); return; }
        new AlertDialog.Builder(a).setTitle(Strings.t("Add to playlist")).setItems(new String[]{Strings.t("Device playlists · any audio source"), Strings.t(file ? "Offline playlists · files on this device" : "YouTube Music playlists")},
            (d, i) -> { if (i == 0) choose(a, song); else original.invoke(); }).show();
    }
    @SuppressWarnings("unchecked")
    static synchronized List<Object> nativePlaylists() throws Exception {
        Object flow = flow(); Object value = Class.forName("kotlinx.coroutines.flow.StateFlow").getMethod("getValue").invoke(flow);
        return new ArrayList<>((List<Object>) value);
    }
    private static Object flow() throws Exception { Field field = Class.forName(STORE).getDeclaredField("_playlists"); field.setAccessible(true); return field.get(null); }
    private static void publish(List<Object> playlists) throws Exception {
        Class.forName("kotlinx.coroutines.flow.MutableStateFlow").getMethod("setValue", Object.class).invoke(flow(), playlists);
    }
    static Object playlist(String id, String title, List<Object> songs) throws Exception {
        Constructor<?> c = Class.forName("com.music.bitchord.data.spotify.LocalPlaylist").getDeclaredConstructor(String.class, String.class, List.class); c.setAccessible(true); return c.newInstance(id, title, songs);
    }
    @SuppressWarnings("unchecked")
    static List<Object> songs(Object playlist) throws Exception { return new ArrayList<>((List<Object>) MixedSongCodec.field(playlist,"songs")); }
    private static JSONObject encodePlaylist(Object playlist) throws Exception {
        JSONArray songs = new JSONArray(); for (Object song : songs(playlist)) songs.put(MixedSongCodec.encode(song));
        return new JSONObject().put("id", MixedSongCodec.text(playlist,"id")).put("title", MixedSongCodec.text(playlist,"title")).put("songs", songs);
    }
    static synchronized void remember(List<Object> playlists) throws Exception {
        JSONArray saved = new JSONArray(); Set<String> live = new HashSet<>();
        for (Object playlist : playlists) { String id = MixedSongCodec.text(playlist,"id"); if (managed.contains(id) || id.startsWith("bc_mix_")) { saved.put(encodePlaylist(playlist)); live.add(id); } }
        managed.clear(); managed.addAll(live); prefs.edit().putString("playlists", saved.toString()).apply();
    }
    static synchronized void restore() throws Exception {
        JSONArray saved = new JSONArray(prefs.getString("playlists", "[]")); List<Object> incoming = new ArrayList<>();
        for (int i = 0; i < saved.length(); i++) {
            JSONObject p = saved.getJSONObject(i); String id = p.getString("id"); List<Object> songs = new ArrayList<>(); JSONArray array = p.getJSONArray("songs");
            for (int n = 0; n < array.length(); n++) songs.add(MixedSongCodec.decode(array.getJSONObject(n)));
            incoming.add(playlist(id,p.getString("title"),songs)); managed.add(id);
        }
        if (incoming.isEmpty()) return;
        for (Object p : nativePlaylists()) if (!managed.contains(MixedSongCodec.text(p,"id"))) incoming.add(p);
        publish(incoming);
    }
    private static synchronized void commit(List<Object> expected, List<Object> playlists) throws Exception {
        boolean changed = (Boolean) Class.forName("kotlinx.coroutines.flow.MutableStateFlow").getMethod("compareAndSet", Object.class, Object.class).invoke(flow(), expected, playlists);
        if (!changed) throw new ConcurrentModificationException("Playlist changed; retry the operation");
        playlists = nativePlaylists();
        remember(playlists);
        JSONArray legacy = new JSONArray(); for (Object p : playlists) legacy.put(encodePlaylist(p));
        hostPrefs.edit().putString("playlists_json", legacy.toString()).apply();
    }
    static synchronized String create(String title, Object song) throws Exception {
        if (title == null || title.trim().isEmpty()) throw new IllegalArgumentException("Empty playlist title");
        String id = "bc_mix_" + UUID.randomUUID(); List<Object> previous = nativePlaylists(); List<Object> list = new ArrayList<>(previous); List<Object> items = new ArrayList<>(); if (song != null) items.add(MixedSongCodec.decode(MixedSongCodec.encode(song)));
        list.add(0, playlist(id,title.trim(),items)); managed.add(id); commit(previous, list); return id;
    }
    static synchronized void membership(Object song, Set<String> selected) throws Exception { membership(song, selected, null); }
    static synchronized void membership(Object song, Set<String> selected, Set<String> visible) throws Exception {
        String key = MixedSongCodec.key(song); List<Object> result = new ArrayList<>(); List<Object> previous = nativePlaylists();
        for (Object p : previous) {
            String id = MixedSongCodec.text(p,"id"); if (visible != null && !visible.contains(id)) { result.add(p); continue; } List<Object> rows = songs(p); boolean exists = false;
            for (Object item : rows) if (MixedSongCodec.key(item).equals(key)) { exists = true; break; }
            if (selected.contains(id) || exists) {
                managed.add(id);
                if (selected.contains(id) && !exists) rows.add(MixedSongCodec.decode(MixedSongCodec.encode(song)));
                else if (!selected.contains(id)) rows.removeIf(item -> MixedSongCodec.key(item).equals(key));
                result.add(playlist(id,MixedSongCodec.text(p,"title"),rows));
            } else result.add(p);
        }
        commit(previous, result);
    }
    private static void choose(Activity a, Object song) {
        try {
            List<Object> list = nativePlaylists(); if (list.isEmpty()) { createDialog(a,song); return; } Set<String> visible = new HashSet<>(); for (Object p : list) visible.add(MixedSongCodec.text(p,"id")); String[] labels = new String[list.size()]; boolean[] checked = new boolean[list.size()]; Set<String> selected = new HashSet<>(); String key = MixedSongCodec.key(song);
            for (int i = 0; i < list.size(); i++) { Object p = list.get(i); labels[i] = MixedSongCodec.text(p,"title"); String id = MixedSongCodec.text(p,"id"); for (Object row : songs(p)) if (MixedSongCodec.key(row).equals(key)) { checked[i] = true; selected.add(id); break; } }
            new AlertDialog.Builder(a).setTitle(Strings.t("Save to device playlists")).setMultiChoiceItems(labels, checked, (d, which, on) -> { String id = MixedSongCodec.text(list.get(which),"id"); if (on) selected.add(id); else selected.remove(id); })
                .setPositiveButton(Strings.t("Save"), (d, which) -> { try { membership(song, selected, visible); } catch (Exception e) { failure(a); } })
                .setNeutralButton(Strings.t("New playlist"), (d, which) -> createDialog(a,song)).setNegativeButton(Strings.t("Cancel"),null).show();
        } catch (Exception e) { failure(a); }
    }
    private static void createDialog(Activity a, Object song) {
        EditText input = new EditText(a); input.setSingleLine(true); input.setHint(Strings.t("Playlist name"));
        AlertDialog dialog = new AlertDialog.Builder(a).setTitle(Strings.t("New device playlist")).setView(input).setPositiveButton(Strings.t("Create"),null).setNegativeButton(Strings.t("Cancel"),null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { if (input.getText().toString().trim().isEmpty()) { input.setError(Strings.t("Enter a playlist name")); return; } try { create(input.getText().toString(),song); dialog.dismiss(); } catch (Exception e) { failure(a); } })); dialog.show();
    }
    private static void failure(Activity a) { Toast.makeText(a,Strings.t("Could not update this device playlist."),Toast.LENGTH_LONG).show(); }
}
