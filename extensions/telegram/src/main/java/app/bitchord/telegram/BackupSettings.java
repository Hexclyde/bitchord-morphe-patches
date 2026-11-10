package app.bitchord.telegram;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

/**
 * What goes into a backup's settings and how it comes back. Only settings named here are written or
 * restored; the saved config also holds the key that unlocks this phone's Telegram database, and a
 * backup must neither carry that out nor overwrite it.
 */
final class BackupSettings {
    private BackupSettings() {}

    /** On/off settings. */
    static final Set<String> SWITCHES =
            new HashSet<>(
                    Arrays.asList(
                            "useTrackNames",
                            "suggestTrackNames",
                            "reportQuality",
                            "showArtwork",
                            "namesFromFiles",
                            "surroundAsStereo",
                            "smoothUpgrades",
                            "sourceFirst",
                            "formatLegend",
                            "searchIndex",
                            "hideDuplicates",
                            "hideUnplayableDolby",
                            "indexWifiOnly",
                            "indexChargingOnly",
                            "soundCheck",
                            "useSharedIndexes",
                            "checkLossless",
                            "checkInBackground",
                            "recognizeUntagged"));

    /** Size settings, in bytes. */
    static final Set<String> SIZES = new HashSet<>(Arrays.asList("audioCacheBytes"));

    /** The settings of {@code config} that a backup may carry, with the right kind of value. */
    static JSONObject export(JSONObject config) throws Exception {
        JSONObject out = new JSONObject();
        for (String key : new TreeSet<>(SWITCHES))
            if (config.opt(key) instanceof Boolean) out.put(key, config.getBoolean(key));
        for (String key : new TreeSet<>(SIZES)) {
            Object value = config.opt(key);
            if (value instanceof Number && ((Number) value).longValue() >= 0)
                out.put(key, ((Number) value).longValue());
        }
        return out;
    }

    /**
     * Puts the restored settings into {@code config}: only allowed keys, only the right kind of
     * value, and nothing else in {@code config} changes. Returns how many were applied.
     */
    static int merge(JSONObject config, JSONObject restored) throws Exception {
        if (restored == null) return 0;
        int applied = 0;
        Iterator<String> keys = restored.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = restored.opt(key);
            if (SWITCHES.contains(key) && value instanceof Boolean) {
                config.put(key, value);
                applied++;
            } else if (SIZES.contains(key)
                    && value instanceof Number
                    && ((Number) value).longValue() >= 0) {
                config.put(key, ((Number) value).longValue());
                applied++;
            }
        }
        return applied;
    }

    /** A short stand-in for an account, so a backup can tell whose chats it lists. */
    static String account(long userId) {
        return digest("account:" + userId);
    }

    static String digest(String text) {
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 8; i++) out.append(String.format("%02x", hash[i]));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    /** The chat ids of a backup's source chats, or none when the list isn't one. */
    static Set<Long> chats(JSONArray ids) {
        Set<Long> out = new TreeSet<>();
        if (ids == null) return out;
        for (int i = 0; i < ids.length(); i++) {
            long id = ids.optLong(i);
            if (id != 0) out.add(id);
        }
        return out;
    }
}
