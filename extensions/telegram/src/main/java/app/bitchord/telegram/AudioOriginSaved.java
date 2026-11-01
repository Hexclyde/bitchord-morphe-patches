package app.bitchord.telegram;

import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/** One small record per saved id. A file is known only by a digest of its URI. */
final class AudioOriginSaved {
    private AudioOriginSaved() {}

    static final String PREFS = "bitchord_audio_origin";
    private static final int MAX_RECORDS = 20000;

    static synchronized void put(
            SharedPreferences prefs, String id, String uri, AudioOrigin.Source source)
            throws JSONException {
        String key = key(id);
        if (!prefs.contains(key) && prefs.getAll().size() >= MAX_RECORDS) return;
        String next = encode(source, digest(uri));
        if (next.equals(prefs.getString(key, null))) return;
        prefs.edit().putString(key, next).apply();
    }

    static AudioOrigin.Source get(SharedPreferences prefs, String id, String uri) {
        return decode(prefs.getString(key(id), null), digest(uri));
    }

    /** The record any id already holds for this exact file. */
    static AudioOrigin.Source ofFile(SharedPreferences prefs, String uri) {
        String file = digest(uri);
        for (Map.Entry<String, ?> row : prefs.getAll().entrySet()) {
            if (!(row.getValue() instanceof String)) continue;
            AudioOrigin.Source source = decode((String) row.getValue(), file);
            if (source != null) return source;
        }
        return null;
    }

    static synchronized void remove(SharedPreferences prefs, String id) {
        String key = key(id);
        if (prefs.contains(key)) prefs.edit().remove(key).apply();
    }

    private static String encode(AudioOrigin.Source source, String file) throws JSONException {
        return new JSONObject()
                .put("file", file)
                .put("id", source.id)
                .putOpt("name", source.name)
                .putOpt("kind", source.kind)
                .putOpt("proof", source.proof)
                .putOpt("codec", source.codec)
                .putOpt("kbps", source.kbps)
                .putOpt("bits", source.bits)
                .putOpt("rate", source.rate)
                .toString();
    }

    private static AudioOrigin.Source decode(String text, String file) {
        if (text == null) return null;
        try {
            JSONObject row = new JSONObject(text);
            String id = row.optString("id", "");
            if (id.isEmpty() || !file.equals(row.optString("file", ""))) return null;
            return new AudioOrigin.Source(
                    id,
                    optional(row, "name"),
                    optional(row, "kind"),
                    optional(row, "proof"),
                    optional(row, "codec"),
                    AudioOrigin.number(optional(row, "kbps")),
                    AudioOrigin.number(optional(row, "bits")),
                    AudioOrigin.number(optional(row, "rate")));
        } catch (JSONException malformed) {
            return null;
        }
    }

    private static String optional(JSONObject row, String name) {
        return row.has(name) && !row.isNull(name) ? row.optString(name) : null;
    }

    private static String key(String id) {
        return "id." + digest(id);
    }

    private static String digest(String text) {
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(Character.forDigit((value & 255) >>> 4, 16));
                hex.append(Character.forDigit(value & 15, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
