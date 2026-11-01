package app.bitchord.soulseek;

import android.content.Context;

import java.io.IOException;

public final class DownloadSettings {
    public static final String SOULSEEK = "soulseek", PEERS = "peer-downloads", DEBRID = "debrid";
    public static Thread lastWriter;
    public static boolean soulseek = true, peers = true, debrid = true;

    public static void reset() {
        soulseek = peers = debrid = true;
    }

    public static boolean enabled(Context context, String feature) {
        return SOULSEEK.equals(feature)
                ? soulseek
                : PEERS.equals(feature) ? peers : DEBRID.equals(feature) && debrid;
    }

    public static boolean peekEnabled(Context context, String feature) {
        return context.getSharedPreferences("bitchord_download_features", 0)
                .getBoolean(feature, false);
    }

    public static void setEnabled(Context context, String feature, boolean enabled) {
        lastWriter = Thread.currentThread();
        context.getSharedPreferences("bitchord_download_features", 0)
                .edit()
                .putBoolean(feature, enabled)
                .apply();
        if (SOULSEEK.equals(feature)) soulseek = enabled;
        else if (PEERS.equals(feature)) peers = enabled;
        else if (DEBRID.equals(feature)) debrid = enabled;
    }

    public static void requireEnabled(Context context, String feature) throws IOException {
        if (!enabled(context, feature)) throw new IOException("Enable the feature first.");
    }
}
