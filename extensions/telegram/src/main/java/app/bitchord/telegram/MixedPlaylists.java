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
    static final String PREFERENCES = "bitchord_mixed_playlists",
            HOST_PREFERENCES = "bitchord_local_playlists";
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
        context = app.getApplicationContext();
        prefs = context.getSharedPreferences(PREFERENCES, 0);
        enabled = prefs.getBoolean("enabled", false);
        hostPrefs = context.getSharedPreferences(HOST_PREFERENCES, 0);
        try {
            restore();
        } catch (Exception e) {
            HostLog.d("Mixed playlists restore failed: " + e);
        }
        changed =
                (p, key) -> {
                    if (key.equals("playlists_json"))
                        try {
                            remember(nativePlaylists());
                        } catch (Exception e) {
                            HostLog.d("Mixed playlist save failed: " + e);
                        }
                };
        hostPrefs.registerOnSharedPreferenceChangeListener(changed);
        if (context instanceof Application)
            ((Application) context)
                    .registerActivityLifecycleCallbacks(
                            new Application.ActivityLifecycleCallbacks() {
                                public void onActivityCreated(Activity a, Bundle b) {
                                    current = new WeakReference<>(a);
                                }

                                public void onActivityResumed(Activity a) {
                                    current = new WeakReference<>(a);
                                }

                                public void onActivityStarted(Activity a) {}

                                public void onActivityPaused(Activity a) {}

                                public void onActivityStopped(Activity a) {}

                                public void onActivitySaveInstanceState(Activity a, Bundle b) {}

                                public void onActivityDestroyed(Activity a) {
                                    if (current.get() == a) current.clear();
                                }
                            });
    }

    static void setEnabled(boolean on) {
        enabled = on;
        if (prefs != null) prefs.edit().putBoolean("enabled", on).apply();
    }

    public static boolean available() {
        return SoundExtras.mixedPlaylistsApplied() && enabled;
    }

    public static boolean allowPlaylist() {
        return available();
    }

    public static Function0<Unit> playlistAction(Object song, Function0<Unit> original) {
        if (!available()) return original;
        return () -> {
            if (!available()) return original.invoke();
            Activity a = current.get();
            if (a == null || a.isFinishing()) return original.invoke();
            offer(a, song, original);
            return Unit.INSTANCE;
        };
    }

    public static Function0<Unit> newPlaylistAction(Function0<Unit> original) {
        if (!available()) return original;
        return () -> {
            if (!available()) return original.invoke();
            Activity a = current.get();
            if (a == null || a.isFinishing()) return original.invoke();
            PlaylistModal modal = new PlaylistModal(a, Strings.t("New playlist"), null);
            modal.action(
                    Strings.t("On this device · any audio source"),
                    true,
                    () -> {
                        modal.dismiss();
                        createDialog(a, null);
                    });
            modal.action(
                    Strings.t("YouTube Music · YouTube tracks only"),
                    false,
                    () -> {
                        modal.dismiss();
                        original.invoke();
                    });
            modal.action(Strings.t("Cancel"), false, modal::dismiss);
            modal.show();
            return Unit.INSTANCE;
        };
    }

    public static void offerFile(Activity activity, String uri, String title) {
        if (!available()) return;
        try {
            choose(activity, MixedSongCodec.file(uri, title));
        } catch (Exception e) {
            failure(activity);
        }
    }

    private static void offer(Activity a, Object song, Function0<Unit> original) {
        try {
            MixedSongCodec.key(song);
        } catch (Exception e) {
            original.invoke();
            return;
        }
        boolean file = !MixedSongCodec.text(song, "localUri").isEmpty();
        boolean youtube = MixedSongCodec.text(song, "videoId").matches("[A-Za-z0-9_-]{11}");
        if (!file && !youtube) {
            choose(a, song);
            return;
        }
        PlaylistModal modal =
                new PlaylistModal(
                        a, Strings.t("Add to playlist"), MixedSongCodec.text(song, "title"));
        modal.action(
                Strings.t("Device playlists · any audio source"),
                true,
                () -> {
                    modal.dismiss();
                    choose(a, song);
                });
        modal.action(
                Strings.t(
                        file
                                ? "Offline playlists · files on this device"
                                : "YouTube Music playlists"),
                false,
                () -> {
                    modal.dismiss();
                    original.invoke();
                });
        modal.action(Strings.t("Cancel"), false, modal::dismiss);
        modal.show();
    }

    @SuppressWarnings("unchecked")
    static synchronized List<Object> nativePlaylists() throws Exception {
        Object state = flow();
        Object value = state.getClass().getMethod("getValue").invoke(state);
        return new ArrayList<>((List<Object>) value);
    }

    private static Object flow() throws Exception {
        Field field = Class.forName(STORE).getDeclaredField("_playlists");
        field.setAccessible(true);
        return field.get(null);
    }

    private static void publish(List<Object> playlists) throws Exception {
        Object state = flow();
        state.getClass().getMethod("setValue", Object.class).invoke(state, playlists);
    }

    static Object playlist(String id, String title, List<Object> songs) throws Exception {
        Constructor<?> c =
                Class.forName("com.music.bitchord.data.spotify.LocalPlaylist")
                        .getDeclaredConstructor(String.class, String.class, List.class);
        c.setAccessible(true);
        return c.newInstance(id, title, songs);
    }

    @SuppressWarnings("unchecked")
    static List<Object> songs(Object playlist) throws Exception {
        return new ArrayList<>((List<Object>) MixedSongCodec.field(playlist, "songs"));
    }

    private static JSONObject encodePlaylist(Object playlist) throws Exception {
        JSONArray songs = new JSONArray();
        for (Object song : songs(playlist)) songs.put(MixedSongCodec.encode(song));
        return new JSONObject()
                .put("id", MixedSongCodec.text(playlist, "id"))
                .put("title", MixedSongCodec.text(playlist, "title"))
                .put("songs", songs);
    }

    static synchronized void remember(List<Object> playlists) throws Exception {
        JSONArray saved = new JSONArray();
        Set<String> live = new HashSet<>();
        for (Object playlist : playlists) {
            String id = MixedSongCodec.text(playlist, "id");
            if (managed.contains(id) || id.startsWith("bc_mix_")) {
                saved.put(encodePlaylist(playlist));
                live.add(id);
            }
        }
        managed.clear();
        managed.addAll(live);
        prefs.edit().putString("playlists", saved.toString()).apply();
    }

    static synchronized void restore() throws Exception {
        JSONArray saved = new JSONArray(prefs.getString("playlists", "[]"));
        List<Object> incoming = new ArrayList<>();
        for (int i = 0; i < saved.length(); i++) {
            JSONObject p = saved.getJSONObject(i);
            String id = p.getString("id");
            List<Object> songs = new ArrayList<>();
            JSONArray array = p.getJSONArray("songs");
            for (int n = 0; n < array.length(); n++)
                songs.add(MixedSongCodec.decode(array.getJSONObject(n)));
            incoming.add(playlist(id, p.getString("title"), songs));
            managed.add(id);
        }
        if (incoming.isEmpty()) return;
        for (Object p : nativePlaylists())
            if (!managed.contains(MixedSongCodec.text(p, "id"))) incoming.add(p);
        publish(incoming);
    }

    private static synchronized void commit(List<Object> expected, List<Object> playlists)
            throws Exception {
        Object state = flow();
        // R8 removes these methods from MutableStateFlow in BitChord 1.9's release APK.
        // The public concrete StateFlowImpl methods are retained by native host call sites.
        boolean changed =
                (Boolean)
                        state.getClass()
                                .getMethod("compareAndSet", Object.class, Object.class)
                                .invoke(state, expected, playlists);
        if (!changed)
            throw new ConcurrentModificationException("Playlist changed; retry the operation");
        playlists = nativePlaylists();
        remember(playlists);
        JSONArray legacy = new JSONArray();
        for (Object p : playlists) legacy.put(encodePlaylist(p));
        hostPrefs.edit().putString("playlists_json", legacy.toString()).apply();
    }

    static synchronized String create(String title, Object song) throws Exception {
        return createAll(
                title, song == null ? Collections.emptyList() : Collections.singletonList(song));
    }

    static synchronized String createAll(String title, List<Object> songs) throws Exception {
        if (title == null || title.trim().isEmpty() || title.length() > 300)
            throw new IllegalArgumentException("Invalid playlist title");
        String id = "bc_mix_" + UUID.randomUUID();
        List<Object> previous = nativePlaylists(), list = new ArrayList<>(previous);
        LinkedHashMap<String, Object> unique = new LinkedHashMap<>();
        for (Object song : songs)
            unique.putIfAbsent(
                    MixedSongCodec.key(song), MixedSongCodec.decode(MixedSongCodec.encode(song)));
        list.add(0, playlist(id, title.trim(), new ArrayList<>(unique.values())));
        managed.add(id);
        try {
            commit(previous, list);
        } catch (Exception failure) {
            managed.remove(id);
            throw failure;
        }
        return id;
    }

    static synchronized void importAll(List<ImportedPlaylist> lists) throws Exception {
        List<Object> previous = nativePlaylists(), result = new ArrayList<>(previous);
        Set<String> added = new HashSet<>();
        for (ImportedPlaylist list : lists) {
            String id = "bc_mix_" + UUID.randomUUID();
            List<Object> songs = new ArrayList<>();
            for (Object song : list.songs)
                songs.add(MixedSongCodec.decode(MixedSongCodec.encode(song)));
            result.add(0, playlist(id, list.title, songs));
            added.add(id);
        }
        managed.addAll(added);
        try {
            commit(previous, result);
        } catch (Exception failure) {
            managed.removeAll(added);
            throw failure;
        }
    }

    static synchronized void edit(String id, String title, List<Object> songs, boolean delete)
            throws Exception {
        if (!id.startsWith("bc_mix_"))
            throw new IllegalArgumentException("This playlist is not owned by this device");
        if (!delete && (title == null || title.trim().isEmpty() || title.length() > 300))
            throw new IllegalArgumentException("Invalid playlist title");
        List<Object> previous = nativePlaylists(), result = new ArrayList<>();
        boolean found = false;
        for (Object playlist : previous) {
            if (!id.equals(MixedSongCodec.text(playlist, "id"))) {
                result.add(playlist);
                continue;
            }
            found = true;
            if (!delete) result.add(playlist(id, title.trim(), songs));
        }
        if (!found) throw new IllegalArgumentException("Playlist no longer exists");
        commit(previous, result);
    }

    static synchronized void reorder(Object expected, List<Object> reordered) throws Exception {
        String id = MixedSongCodec.text(expected, "id");
        List<Object> previous = nativePlaylists(), result = new ArrayList<>();
        boolean found = false;
        for (Object playlist : previous) {
            if (!id.equals(MixedSongCodec.text(playlist, "id"))) {
                result.add(playlist);
                continue;
            }
            if (!encodePlaylist(playlist).toString().equals(encodePlaylist(expected).toString()))
                throw new ConcurrentModificationException("Playlist changed; retry the operation");
            Map<String, Integer> counts = new HashMap<>();
            for (Object song : songs(playlist)) {
                String key = MixedSongCodec.encode(song).toString();
                counts.put(key, counts.getOrDefault(key, 0) + 1);
            }
            for (Object song : reordered) {
                String key = MixedSongCodec.encode(song).toString();
                int count = counts.getOrDefault(key, 0);
                if (count == 0)
                    throw new IllegalArgumentException("The DJ order must preserve every track");
                if (count == 1) counts.remove(key);
                else counts.put(key, count - 1);
            }
            if (!counts.isEmpty()) throw new IllegalArgumentException("The DJ order is incomplete");
            result.add(playlist(id, MixedSongCodec.text(playlist, "title"), reordered));
            found = true;
        }
        if (!found) throw new IllegalArgumentException("Playlist no longer exists");
        if (prefs == null) {
            Context app = SoundExtras.app();
            if (app == null) throw new IllegalStateException("Playlist storage is unavailable");
            prefs = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
            hostPrefs = app.getSharedPreferences(HOST_PREFERENCES, Context.MODE_PRIVATE);
            JSONArray saved = new JSONArray(prefs.getString("playlists", "[]"));
            for (int i = 0; i < saved.length(); i++)
                managed.add(saved.getJSONObject(i).getString("id"));
        }
        commit(previous, result);
    }

    static synchronized void membership(Object song, Set<String> selected) throws Exception {
        membership(song, selected, null);
    }

    static synchronized void membership(Object song, Set<String> selected, Set<String> visible)
            throws Exception {
        String key = MixedSongCodec.key(song);
        List<Object> result = new ArrayList<>();
        List<Object> previous = nativePlaylists();
        for (Object p : previous) {
            String id = MixedSongCodec.text(p, "id");
            if (visible != null && !visible.contains(id)) {
                result.add(p);
                continue;
            }
            List<Object> rows = songs(p);
            boolean exists = false;
            for (Object item : rows)
                if (MixedSongCodec.key(item).equals(key)) {
                    exists = true;
                    break;
                }
            if (selected.contains(id) || exists) {
                managed.add(id);
                if (selected.contains(id) && !exists)
                    rows.add(MixedSongCodec.decode(MixedSongCodec.encode(song)));
                else if (!selected.contains(id))
                    rows.removeIf(item -> MixedSongCodec.key(item).equals(key));
                result.add(playlist(id, MixedSongCodec.text(p, "title"), rows));
            } else result.add(p);
        }
        commit(previous, result);
    }

    private static void choose(Activity a, Object song) {
        try {
            List<Object> list = nativePlaylists();
            if (list.isEmpty()) {
                createDialog(a, song);
                return;
            }
            Set<String> visible = new HashSet<>();
            for (Object p : list) visible.add(MixedSongCodec.text(p, "id"));
            String[] labels = new String[list.size()];
            boolean[] checked = new boolean[list.size()];
            Set<String> selected = new HashSet<>();
            String key = MixedSongCodec.key(song);
            for (int i = 0; i < list.size(); i++) {
                Object p = list.get(i);
                labels[i] = MixedSongCodec.text(p, "title");
                String id = MixedSongCodec.text(p, "id");
                for (Object row : songs(p))
                    if (MixedSongCodec.key(row).equals(key)) {
                        checked[i] = true;
                        selected.add(id);
                        break;
                    }
            }
            PlaylistModal modal =
                    new PlaylistModal(
                            a,
                            Strings.t("Save to device playlists"),
                            MixedSongCodec.text(song, "title"));
            modal.choices(
                    labels,
                    checked,
                    (which, on) -> {
                        String id = MixedSongCodec.text(list.get(which), "id");
                        if (on) selected.add(id);
                        else selected.remove(id);
                    });
            modal.action(
                    Strings.t("Save"),
                    true,
                    () -> {
                        try {
                            membership(song, selected, visible);
                            modal.dismiss();
                        } catch (Exception failure) {
                            failure(a);
                        }
                    });
            modal.action(
                    Strings.t("New playlist"),
                    false,
                    () -> {
                        modal.dismiss();
                        createDialog(a, song);
                    });
            modal.action(Strings.t("Cancel"), false, modal::dismiss);
            modal.show();
        } catch (Exception failure) {
            failure(a);
        }
    }

    private static void createDialog(Activity a, Object song) {
        PlaylistModal modal = new PlaylistModal(a, Strings.t("New device playlist"), null);
        EditText input = modal.input(Strings.t("Playlist name"));
        android.widget.TextView create =
                modal.action(
                        Strings.t("Create"),
                        true,
                        () -> {
                            if (input.getText().toString().trim().isEmpty()) {
                                input.setError(Strings.t("Enter a playlist name"));
                                return;
                            }
                            try {
                                create(input.getText().toString(), song);
                                modal.dismiss();
                            } catch (Exception failure) {
                                failure(a);
                            }
                        });
        create.setTag("playlist-create");
        modal.action(Strings.t("Cancel"), false, modal::dismiss);
        modal.show();
    }

    private static void failure(Activity a) {
        Toast.makeText(a, Strings.t("Could not update this device playlist."), Toast.LENGTH_LONG)
                .show();
    }
}
