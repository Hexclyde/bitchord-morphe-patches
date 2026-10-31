package app.bitchord.telegram;

import android.app.Activity;
import android.content.*;
import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;

import org.json.*;

import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;

/** Public playlist bookmarks and independent device copies. Stores metadata only. */
public final class GuestPlaylists {
    private static WeakReference<kotlin.jvm.functions.Function0<?>> signIn =
            new WeakReference<>(null);

    public static void signInAction(kotlin.jvm.functions.Function0<?> action) {
        signIn = new WeakReference<>(action);
    }

    static void signIn(Activity activity) {
        kotlin.jvm.functions.Function0<?> action = signIn.get();
        if (action != null) action.invoke();
        activity.startActivity(
                new Intent()
                        .setClassName(activity, "com.music.bitchord.MainActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        activity.finish();
    }

    static final String PREFS = "bitchord_playlist_tools", CARD = "bitchord:playlist-tools";
    private static WeakReference<Object> service = new WeakReference<>(null);

    public static void service(Object owner) {
        service = new WeakReference<>(owner);
    }

    public static void release(Object owner) {
        if (service.get() == owner) service.clear();
    }

    static SharedPreferences preferences() {
        return SoundExtras.app().getSharedPreferences(PREFS, 0);
    }

    static String playlistId(String input) {
        Uri uri = Uri.parse(input.trim());
        String scheme = uri.getScheme(), host = uri.getHost();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                || host == null
                || uri.getUserInfo() != null)
            throw new IllegalArgumentException("Enter a YouTube playlist link");
        host = host.toLowerCase(Locale.ROOT);
        if (!Arrays.asList(
                        "youtube.com",
                        "www.youtube.com",
                        "m.youtube.com",
                        "music.youtube.com",
                        "youtu.be",
                        "www.youtu.be")
                .contains(host))
            throw new IllegalArgumentException("Enter a YouTube playlist link");
        if (uri.getQueryParameters("list").size() != 1)
            throw new IllegalArgumentException("Enter one playlist link");
        String id = uri.getQueryParameter("list");
        if (id == null || !id.matches("[A-Za-z0-9_-]{2,160}") || id.startsWith("VL"))
            throw new IllegalArgumentException("Invalid playlist ID");
        if (id.startsWith("RD") && !id.startsWith("RDCLAK"))
            throw new IllegalArgumentException("Radio mixes cannot be saved as playlists");
        return id;
    }

    static String link(String id) {
        return "https://music.youtube.com/playlist?list=" + Uri.encode(id);
    }

    static JSONArray bookmarks() {
        try {
            return new JSONArray(preferences().getString("bookmarks", "[]"));
        } catch (JSONException invalid) {
            return new JSONArray();
        }
    }

    static void bookmark(String id, String title) throws Exception {
        playlistId(link(id));
        JSONArray old = bookmarks(), next = new JSONArray();
        next.put(
                new JSONObject()
                        .put("id", id)
                        .put("title", title.trim().isEmpty() ? id : title.trim()));
        for (int i = 0; i < old.length() && next.length() < 200; i++)
            if (!old.getJSONObject(i).getString("id").equals(id)) next.put(old.getJSONObject(i));
        preferences().edit().putString("bookmarks", next.toString()).apply();
    }

    static void forget(String id) throws Exception {
        JSONArray old = bookmarks(), next = new JSONArray();
        for (int i = 0; i < old.length(); i++)
            if (!old.getJSONObject(i).getString("id").equals(id)) next.put(old.getJSONObject(i));
        preferences().edit().putString("bookmarks", next.toString()).apply();
    }

    static void open(Activity activity, String id) throws Exception {
        Intent request = new Intent(Intent.ACTION_VIEW, Uri.parse(link(id)));
        Class.forName("com.music.bitchord.playback.MusicLink")
                .getMethod("consume", Intent.class)
                .invoke(null, request);
        activity.startActivity(
                new Intent()
                        .setClassName(activity, "com.music.bitchord.MainActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    static void openLocal(Activity activity, String id) throws Exception {
        if (!id.startsWith("bc_mix_"))
            throw new IllegalArgumentException("Invalid device playlist");
        Object page =
                Class.forName("com.music.bitchord.playback.LinkRequest$Page")
                        .getConstructor(String.class)
                        .newInstance("local:playlist:" + id);
        Field pending =
                Class.forName("com.music.bitchord.playback.MusicLink").getDeclaredField("_pending");
        pending.setAccessible(true);
        Object state = pending.get(null);
        state.getClass().getMethod("setValue", Object.class).invoke(state, page);
        activity.startActivity(
                new Intent()
                        .setClassName(activity, "com.music.bitchord.MainActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    public static ArrayList<Object> library(ArrayList<Object> items) {
        if (!MixedPlaylists.available()) return items;
        try {
            ArrayList<Object> next = new ArrayList<>(items);
            next.add(
                    0,
                    Class.forName("com.music.bitchord.data.model.ShelfItem")
                            .getConstructor(
                                    String.class,
                                    String.class,
                                    String.class,
                                    String.class,
                                    String.class)
                            .newInstance(
                                    Strings.t("Playlist tools"),
                                    Strings.t("Saved playlists · no account needed"),
                                    null,
                                    null,
                                    CARD));
            return next;
        } catch (Exception unavailable) {
            return items;
        }
    }

    public static boolean detail(String browseId) {
        if (!CARD.equals(browseId) || !MixedPlaylists.available()) return false;
        Context app = SoundExtras.app();
        app.startActivity(
                new Intent(app, PlaylistToolsActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
    }

    static List<Object> queue() throws Exception {
        Object owner = service.get();
        if (owner == null) throw new IllegalStateException("Queue is unavailable");
        Field field = owner.getClass().getDeclaredField("player");
        field.setAccessible(true);
        Player player = (Player) field.get(owner);
        List<Object> result = new ArrayList<>();
        int current = player.getCurrentMediaItemIndex();
        if (current < 0) return result;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Method song =
                Class.forName("com.music.bitchord.playback.PlayerConnectionKt")
                        .getMethod("toSong", MediaItem.class);
        int at = current;
        // Follow the actual timeline's shuffle order without wrapping repeat-all.
        Object timeline = Player.class.getMethod("getCurrentTimeline").invoke(player);
        Method nextWindow =
                Class.forName("androidx.media3.common.Timeline")
                        .getMethod("getNextWindowIndex", int.class, int.class, boolean.class);
        boolean shuffle = (Boolean) Player.class.getMethod("getShuffleModeEnabled").invoke(player);
        while (at >= 0 && at < player.getMediaItemCount() && seen.add("index:" + at)) {
            Object value = song.invoke(null, player.getMediaItemAt(at));
            String key = MixedSongCodec.key(value);
            if (seen.add(key)) result.add(value);
            at = (Integer) nextWindow.invoke(timeline, at, 0, shuffle);
        }
        return result;
    }

    interface Pages {
        Object load(String continuation) throws Exception;
    }

    static List<Object> collect(Object first, Pages pages) throws Exception {
        if (first == null) throw new IllegalStateException("Could not read the complete playlist");
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        Object page = first;
        while (true) {
            List<?> songs = (List<?>) MixedSongCodec.field(page, "songs");
            for (Object song : songs) {
                result.putIfAbsent(MixedSongCodec.key(song), song);
                if (result.size() > 5000)
                    throw new IllegalStateException("This playlist exceeds the 5,000-track limit");
            }
            Object token = MixedSongCodec.field(page, "continuation");
            if (token == null || token.toString().isEmpty())
                return new ArrayList<>(result.values());
            if (!seen.add(token.toString()) || seen.size() > 500)
                throw new IllegalStateException("Playlist pagination did not finish");
            page = pages.load(token.toString());
            if (page == null)
                throw new IllegalStateException("Could not read the complete playlist");
        }
    }

    static List<Object> copy(String id) throws Exception {
        Object repo =
                Class.forName("com.music.bitchord.data.YtMusicRepository")
                        .getField("INSTANCE")
                        .get(null);
        Method first =
                repo.getClass()
                        .getMethod(
                                "browseSongs-gIAlu-s",
                                String.class,
                                kotlin.coroutines.Continuation.class);
        Method more =
                repo.getClass()
                        .getMethod(
                                "moreSongs-gIAlu-s",
                                String.class,
                                kotlin.coroutines.jvm.internal.ContinuationImpl.class);
        Object page = VideoSwitch.await(step -> first.invoke(repo, "VL" + id, step));
        return collect(page, token -> VideoSwitch.await(step -> more.invoke(repo, token, step)));
    }
}
