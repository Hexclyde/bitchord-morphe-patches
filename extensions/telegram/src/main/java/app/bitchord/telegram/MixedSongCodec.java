package app.bitchord.telegram;

import org.json.*;
import java.lang.reflect.*;
import java.util.*;

/** BitChord 1.9 Song metadata, preserving file and addon identities without storing queue state. */
final class MixedSongCodec {
    static final String[] FIELDS = {"videoId", "title", "artist", "thumbnailUrl", "durationText", "artistId", "artists", "albumId", "albumName", "isVideo", "isVideoOrigin", "setVideoId", "queueTier", "queueEntryId", "radioName", "localUri", "downloadFormat", "localPath", "localDateAddedSeconds", "localDateModifiedSeconds", "trackNumber", "sourceQuality", "sourceHiRes", "isExplicit", "playbackSource", "playbackSourceType", "playbackSourceId", "feedbackToken"};
    private static final Set<String> TRANSIENT = new HashSet<>(Arrays.asList("setVideoId", "queueEntryId", "radioName", "feedbackToken"));
    private MixedSongCodec() {}
    static Object field(Object value, String name) throws Exception {
        Field f = value.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(value);
    }
    static String text(Object value, String name) {
        try { Object v = field(value, name); return v == null ? "" : String.valueOf(v); }
        catch (Exception e) { return ""; }
    }
    static String key(Object song) {
        String uri = text(song, "localUri"), id = text(song, "videoId");
        if (!uri.isEmpty()) return "file:" + uri;
        if (!id.isEmpty()) return "stream:" + id;
        throw new IllegalArgumentException("Track has no playable identity");
    }
    static JSONObject encode(Object song) throws Exception {
        key(song);
        JSONObject json = new JSONObject();
        for (String name : FIELDS) {
            if (TRANSIENT.contains(name) || name.equals("queueTier")) continue;
            Object value = field(song, name);
            if (value == null) continue;
            if (name.equals("artists")) {
                JSONArray artists = new JSONArray();
                for (Object artist : (List<?>) value) artists.put(new JSONObject().put("name", text(artist,"name")).put("browseId", text(artist,"browseId")));
                json.put(name, artists);
            } else json.put(name, value instanceof Enum ? ((Enum<?>) value).name() : value);
        }
        return json;
    }
    static Object decode(JSONObject json) throws Exception { return decode(json, Class.forName("com.music.bitchord.data.model.Song")); }
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object decode(JSONObject json, Class<?> songClass) throws Exception {
        Constructor<?> constructor = null;
        for (Constructor<?> c : songClass.getDeclaredConstructors()) {
            Class<?>[] types = c.getParameterTypes();
            if (types.length == FIELDS.length && types[0] == String.class && types[6] == List.class && types[12].isEnum()) { constructor = c; break; }
        }
        if (constructor == null) throw new ReflectiveOperationException("Unsupported BitChord Song constructor");
        Class<?>[] types = constructor.getParameterTypes(); Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            String name = FIELDS[i]; Class<?> type = types[i];
            if (name.equals("artists")) {
                List<Object> artists = new ArrayList<>(); JSONArray list = json.optJSONArray(name);
                if (list != null && list.length() > 0) {
                    Class<?> artistType = Class.forName("com.music.bitchord.data.model.ArtistRef");
                    Constructor<?> make = artistType.getDeclaredConstructor(String.class, String.class); make.setAccessible(true);
                    for (int n = 0; n < list.length(); n++) { JSONObject a = list.getJSONObject(n); String browse = a.optString("browseId"); artists.add(make.newInstance(a.optString("name"), browse.isEmpty() ? null : browse)); }
                }
                args[i] = artists;
            } else if (name.equals("queueTier")) args[i] = Enum.valueOf((Class<? extends Enum>) type, "CONTEXT");
            else if (TRANSIENT.contains(name)) args[i] = null;
            else if (type == boolean.class) args[i] = json.optBoolean(name);
            else if (type == Boolean.class) args[i] = json.has(name) && !json.isNull(name) ? json.getBoolean(name) : null;
            else if (type == Long.class) args[i] = json.has(name) && !json.isNull(name) ? json.getLong(name) : null;
            else if (type == Integer.class) args[i] = json.has(name) && !json.isNull(name) ? json.getInt(name) : null;
            else if (type.isEnum()) {
                String value = json.optString(name); args[i] = value.isEmpty() ? null : Enum.valueOf((Class<? extends Enum>) type, value);
            } else if (type == String.class) args[i] = i < 3 ? json.optString(name, "") : json.has(name) && !json.isNull(name) ? json.getString(name) : null;
            else throw new ReflectiveOperationException("Unsupported Song field: " + name);
        }
        constructor.setAccessible(true); Object song = constructor.newInstance(args); key(song); return song;
    }
    private static String digest(String value) throws Exception {
        byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return out.toString();
    }
    static Object file(String uri, String title) throws Exception {
        if (uri == null || uri.isEmpty()) throw new IllegalArgumentException("Missing file location");
        return decode(new JSONObject().put("videoId", "local:" + digest(uri)).put("title", title).put("artist", "").put("localUri", uri));
    }
}
