package app.bitchord.telegram;

import android.content.Context;

import org.json.*;

import java.lang.reflect.*;
import java.util.*;

/** Uses the host's active Google profile and complete occurrence-preserving playlist reads. */
final class YouTubePlaylistApi implements PlaylistSyncEngine.YouTubePort {
    final Context app;

    YouTubePlaylistApi(Context app) {
        this.app = app.getApplicationContext();
    }

    public String account() throws Exception {
        Class<?> storeType = Class.forName("com.music.bitchord.auth.AuthStore");
        Object store = storeType.getConstructor(Context.class).newInstance(app);
        if (!Boolean.TRUE.equals(storeType.getMethod("isSignedIn").invoke(store)))
            throw new IllegalStateException("Sign in to YouTube Music in BitChord first");
        Object session = storeType.getMethod("getActiveSession").invoke(store);
        if (session == null)
            throw new IllegalStateException("YouTube Music account is unavailable");
        String account = MixedSongCodec.text(session, "accountId"),
                profile = MixedSongCodec.text(session, "activeProfileId");
        if (account.isEmpty())
            throw new IllegalStateException("YouTube Music account is unavailable");
        return TransitionRuntime.key(account, profile);
    }

    static String rawId(String id) {
        String value = id.startsWith("VL") ? id.substring(2) : id;
        if (!value.matches("[A-Za-z0-9_-]{2,160}") || value.startsWith("RD"))
            throw new IllegalArgumentException("Invalid editable playlist ID");
        return value;
    }

    static Object repo() throws Exception {
        return Class.forName("com.music.bitchord.data.YtMusicRepository")
                .getField("INSTANCE")
                .get(null);
    }

    private Object invoke(String method, Class<?>[] types, Object... arguments) throws Exception {
        Object repository = repo();
        Method call = repository.getClass().getMethod(method, types);
        return VideoSwitch.await(
                step -> {
                    Object[] args = Arrays.copyOf(arguments, arguments.length + 1);
                    args[arguments.length] = step;
                    return call.invoke(repository, args);
                });
    }

    static Class<?> continuation() {
        return kotlin.coroutines.jvm.internal.ContinuationImpl.class;
    }

    static final class Playlist {
        final String id, title, revision, account;
        final List<Track> tracks;

        Playlist(String id, String title, String revision, String account, List<Track> tracks) {
            this.id = id;
            this.title = title;
            this.revision = revision;
            this.account = account;
            this.tracks = tracks;
        }
    }

    static final class Track {
        final String id, occurrence;
        final Object song;
        final PlaylistImport.Track metadata;

        Track(String id, String occurrence, Object song) {
            this.id = id;
            this.occurrence = occurrence;
            this.song = song;
            long duration = 0;
            try {
                duration =
                        PlaylistImport.duration(MixedSongCodec.text(song, "durationText"), false);
            } catch (Exception ignored) {
            }
            metadata =
                    new PlaylistImport.Track(
                            MixedSongCodec.text(song, "title"),
                            MixedSongCodec.text(song, "artist"),
                            MixedSongCodec.text(song, "albumName"),
                            id,
                            "",
                            duration);
        }
    }

    private Object browse(String playlist, String token) throws Exception {
        Class<?> type = Class.forName("com.music.bitchord.data.innertube.Innertube");
        Object inner = type.getField("INSTANCE").get(null);
        if (token == null) {
            Method call =
                    type.getMethod(
                            "browse",
                            String.class,
                            String.class,
                            kotlin.coroutines.Continuation.class);
            return VideoSwitch.await(step -> call.invoke(inner, "VL" + playlist, null, step));
        }
        Method call = type.getMethod("browseContinuation", String.class, continuation());
        return VideoSwitch.await(step -> call.invoke(inner, token, step));
    }

    static JSONObject object(JSONObject value, String... keys) {
        for (String key : keys) {
            if (value == null) return null;
            value = value.optJSONObject(key);
        }
        return value;
    }

    static List<JSONObject> renderers(Object root, String name) throws Exception {
        List<JSONObject> result = new ArrayList<>();
        walk(root, name, result);
        return result;
    }

    private static void walk(Object value, String name, List<JSONObject> found) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONObject renderer = object.optJSONObject(name);
            if (renderer != null) found.add(renderer);
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                walk(object.get(key), name, found);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) walk(array.get(i), name, found);
        }
    }

    static JSONObject shelf(JSONObject root) throws Exception {
        JSONObject scope = root.optJSONObject("continuationContents");
        if (scope == null)
            scope = object(root, "contents", "twoColumnBrowseResultsRenderer", "secondaryContents");
        if (scope == null) throw new IllegalStateException("Unsupported playlist page");
        JSONObject continuation = scope.optJSONObject("musicPlaylistShelfContinuation");
        if (continuation != null) return continuation;
        List<JSONObject> shelves = renderers(scope, "musicPlaylistShelfRenderer");
        if (shelves.size() == 1) return shelves.get(0);
        if (shelves.isEmpty() && root.has("continuationContents")) {
            for (JSONObject suggestion : renderers(scope, "musicShelfRenderer"))
                if ("Suggestions".equals(runs(suggestion.optJSONObject("title")))) return null;
        }
        throw new IllegalStateException("Playlist page is incomplete or ambiguous");
    }

    static String runs(JSONObject text) throws Exception {
        if (text == null) return "";
        if (text.has("simpleText")) return text.getString("simpleText");
        JSONArray runs = text.optJSONArray("runs");
        StringBuilder out = new StringBuilder();
        if (runs != null)
            for (int i = 0; i < runs.length(); i++)
                out.append(runs.getJSONObject(i).optString("text", ""));
        return out.toString();
    }

    static boolean editable(JSONObject root) throws Exception {
        if (!renderers(root, "musicEditablePlaylistDetailHeaderRenderer").isEmpty()) return true;
        for (JSONObject header : renderers(root, "musicResponsiveHeaderRenderer"))
            for (JSONObject action : renderers(header, "menuNavigationItemRenderer")) {
                JSONObject icon = action.optJSONObject("icon");
                String type = icon == null ? "" : icon.optString("iconType", "");
                if (type.equals("EDIT") || type.equals("DELETE")) return true;
            }
        return false;
    }

    private static Object nativeJson(JSONObject object) throws Exception {
        Class<?> type = Class.forName("kotlinx.serialization.json.Json");
        return type.getMethod("parseToJsonElement", String.class)
                .invoke(type.getField("Default").get(null), object.toString());
    }

    private static Object parse(JSONObject row) throws Exception {
        Class<?> parser = Class.forName("com.music.bitchord.data.innertube.InnertubeParser"),
                credits =
                        Class.forName("com.music.bitchord.data.innertube.InnertubeParser$Credits"),
                json = Class.forName("kotlinx.serialization.json.JsonObject");
        Object empty =
                credits.getConstructor(
                                String.class, String.class, String.class, List.class, String.class)
                        .newInstance(null, null, null, Collections.emptyList(), null);
        return parser.getMethod("parseResponsiveListItem", json, credits)
                .invoke(null, nativeJson(row), empty);
    }

    private static String next(JSONObject shelf) throws Exception {
        if (shelf == null) return null;
        Class<?> parser = Class.forName("com.music.bitchord.data.innertube.InnertubeParser");
        Object token =
                parser.getMethod(
                                "continuationToken",
                                Class.forName("kotlinx.serialization.json.JsonElement"))
                        .invoke(null, nativeJson(shelf));
        return token == null ? null : token.toString();
    }

    public Playlist read(String value) throws Exception {
        String id = rawId(value), identity = account(), title = id;
        List<Track> tracks = new ArrayList<>();
        Set<String> tokens = new HashSet<>(), slots = new HashSet<>();
        String token = null;
        boolean first = true;
        do {
            if (!identity.equals(account()))
                throw new IllegalStateException("YouTube Music account changed");
            Object raw = browse(id, token);
            if (raw == null) throw new IllegalStateException("YouTube Music playlist read failed");
            JSONObject page = new JSONObject(raw.toString());
            if (page.has("error") || page.has("alerts"))
                throw new IllegalStateException("YouTube Music refused this playlist");
            if (first) {
                if (!editable(page))
                    throw new IllegalStateException(
                            "Choose a playlist you can edit in YouTube Music");
                for (String name :
                        new String[] {
                            "musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer"
                        }) {
                    List<JSONObject> headers = renderers(page, name);
                    if (!headers.isEmpty()) {
                        String nameValue = runs(headers.get(0).optJSONObject("title"));
                        if (!nameValue.isEmpty()) title = nameValue;
                    }
                }
                first = false;
            }
            JSONObject scope = shelf(page);
            if (scope == null) break;
            for (JSONObject row : renderers(scope, "musicResponsiveListItemRenderer")) {
                Object song = parse(row);
                if (song == null)
                    throw new IllegalStateException(
                            "Playlist contains an unavailable or unsupported entry");
                String video = MixedSongCodec.text(song, "videoId"),
                        slot = MixedSongCodec.text(song, "setVideoId");
                if (!video.matches("[A-Za-z0-9_-]{11}") || slot.isEmpty() || !slots.add(slot))
                    throw new IllegalStateException("Playlist entry identities are incomplete");
                tracks.add(new Track(video, slot, song));
                if (tracks.size() > PlaylistImport.LIMIT)
                    throw new IllegalStateException("Playlist exceeds 5,000 tracks");
            }
            token = next(scope);
            if (token != null && (!tokens.add(token) || tokens.size() > 500))
                throw new IllegalStateException("Playlist pagination did not finish");
        } while (token != null);
        if (!identity.equals(account()))
            throw new IllegalStateException("YouTube Music account changed");
        StringBuilder fingerprint = new StringBuilder(title);
        for (Track track : tracks)
            fingerprint.append('\n').append(track.id).append(':').append(track.occurrence);
        return new Playlist(
                id, title, TransitionRuntime.key(id, fingerprint.toString()), identity, tracks);
    }

    public void assertUnchanged(Playlist expected) throws Exception {
        Playlist actual = read(expected.id);
        if (!actual.account.equals(expected.account) || !actual.revision.equals(expected.revision))
            throw new IllegalStateException("YouTube Music playlist changed; preview again");
    }

    String create(String title) throws Exception {
        account();
        Class<?> privacy = Class.forName("com.music.bitchord.data.model.PlaylistPrivacy");
        Object id =
                invoke(
                        "createPlaylist-BWLJW6A",
                        new Class<?>[] {String.class, privacy, List.class, continuation()},
                        title,
                        privacy.getField("PRIVATE").get(null),
                        Collections.emptyList());
        if (!(id instanceof String))
            throw new IllegalStateException("Playlist creation needs verification");
        return rawId((String) id);
    }

    public void add(Playlist expected, List<String> ids) throws Exception {
        assertUnchanged(expected);
        for (int at = 0; at < ids.size(); at += 100) {
            if (!expected.account.equals(account()))
                throw new IllegalStateException("YouTube Music account changed");
            Object result =
                    invoke(
                            "addToPlaylist-0E7RQCE",
                            new Class<?>[] {String.class, List.class, continuation()},
                            expected.id,
                            new ArrayList<>(ids.subList(at, Math.min(ids.size(), at + 100))));
            if (result == null)
                throw new IllegalStateException("YouTube Music write needs verification");
        }
    }

    public void remove(Playlist expected, List<Track> tracks) throws Exception {
        assertUnchanged(expected);
        List<kotlin.Pair<String, String>> entries = new ArrayList<>();
        for (Track track : tracks) entries.add(new kotlin.Pair<>(track.occurrence, track.id));
        if (!expected.account.equals(account()))
            throw new IllegalStateException("YouTube Music account changed");
        Object result =
                invoke(
                        "removeFromPlaylist-0E7RQCE",
                        new Class<?>[] {String.class, List.class, continuation()},
                        expected.id,
                        entries);
        if (result == null)
            throw new IllegalStateException("YouTube Music write needs verification");
    }

    void rename(Playlist expected, String title) throws Exception {
        assertUnchanged(expected);
        Object result =
                invoke(
                        "renamePlaylist-0E7RQCE",
                        new Class<?>[] {String.class, String.class, continuation()},
                        expected.id,
                        title);
        if (result == null)
            throw new IllegalStateException("YouTube Music write needs verification");
    }
}
