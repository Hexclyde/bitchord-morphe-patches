package app.bitchord.telegram;

import org.json.JSONArray;
import org.json.JSONObject;

/** What a backup carries of the settings and what a restore may change. */
public final class BackupSettingsTest {
    static int passed;

    static void check(boolean okay, String message) {
        passed++;
        if (!okay) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        JSONObject config =
                new JSONObject()
                        .put("wrappedKey", "SECRETWRAPPEDKEY")
                        .put("keyIv", "SECRETIV")
                        .put("apiId", 12345)
                        .put("apiHash", "abcdef")
                        .put("token", "t0ken")
                        .put("registered", true)
                        .put("playlists", new JSONObject().put("k", "host-id"))
                        .put("chats", new JSONArray(new long[] {-1001, -1002}))
                        .put("searchIndex", false)
                        .put("hideDuplicates", "yes")
                        .put("audioCacheBytes", 123456789L)
                        .put("indexWifiOnly", true);
        JSONObject out = BackupSettings.export(config);
        String text = out.toString();
        for (String secret :
                new String[] {
                    "SECRET", "apiHash", "apiId", "token", "t0ken", "abcdef", "wrapped", "keyIv"
                }) check(!text.contains(secret), "no " + secret + " in " + text);
        check(
                !out.has("registered") && !out.has("playlists") && !out.has("chats"),
                "only named settings");
        check(out.getBoolean("indexWifiOnly") && !out.getBoolean("searchIndex"), "settings kept");
        check(!out.has("hideDuplicates"), "a value of the wrong kind is left out");
        check(out.getLong("audioCacheBytes") == 123456789L, "sizes kept");

        // A restore changes only what is named, and never the database key.
        JSONObject current =
                new JSONObject()
                        .put("wrappedKey", "MINE")
                        .put("keyIv", "MYIV")
                        .put("chats", new JSONArray(new long[] {-5}));
        JSONObject restored =
                new JSONObject(out.toString())
                        .put("wrappedKey", "THEIRS")
                        .put("keyIv", "THEIRIV")
                        .put("apiHash", "x")
                        .put("chats", new JSONArray(new long[] {-9}))
                        .put("soundCheck", "true")
                        .put("audioCacheBytes", -1);
        int applied = BackupSettings.merge(current, restored);
        check(
                current.getString("wrappedKey").equals("MINE")
                        && current.getString("keyIv").equals("MYIV"),
                "key untouched");
        check(
                !current.has("apiHash") && current.getJSONArray("chats").getLong(0) == -5,
                "unlisted keys untouched");
        check(
                !current.has("soundCheck") && !current.has("audioCacheBytes"),
                "wrong kinds and negative sizes refused");
        check(applied == 2 && current.getBoolean("indexWifiOnly"), "applied: " + applied);
        check(BackupSettings.merge(current, null) == 0, "no settings is fine");

        check(BackupSettings.account(42).equals(BackupSettings.account(42)), "stable");
        check(
                !BackupSettings.account(42).equals(BackupSettings.account(43)),
                "tells accounts apart");
        check(
                !BackupSettings.account(42).contains("42")
                        || BackupSettings.account(42).length() == 16,
                "not the id");
        check(
                BackupSettings.chats(new JSONArray(new long[] {0, -3, -3, 7})).size() == 2,
                "chat list read");
        System.out.println("PASS: " + passed + " backup settings allowlist checks.");
    }
}
