package app.bitchord.telegram;

import android.content.Context;

import org.json.JSONObject;

import java.util.List;

public final class DownloadControls {
    public static boolean hidden(Context app, String id) {
        return false;
    }

    public static List<JSONObject> paused(Context app) {
        return java.util.Collections.emptyList();
    }

    public static void pause(Context app, String id) throws Exception {}

    public static void retry(Context app, String id) throws Exception {}

    public static void remove(Context app, String id) throws Exception {}

    public static boolean deleteLocalFile(Context app, String uri) throws Exception {
        return false;
    }

    public static boolean delete(Context app, String id) throws Exception {
        return false;
    }
}
