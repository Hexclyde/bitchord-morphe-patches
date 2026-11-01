package app.bitchord.telegram;

import android.content.Context;

/** Shared download signatures for compiling the optional transfer extension in isolation. */
public final class DownloadSelection {
    public static void init(Context context) {}

    public static void keep(Context context, Object song) throws java.io.IOException {}

    public static boolean forbidFallback(Object song) {
        return false;
    }

    public static void fileNameStart(Object song) {}

    public static String fileNameFinish(String name) {
        return name;
    }

    public static String selectedFileName(Object song, String name) {
        return name;
    }
}
