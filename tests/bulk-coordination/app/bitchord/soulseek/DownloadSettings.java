package app.bitchord.soulseek;

import android.content.Context;

import java.io.IOException;

final class DownloadSettings {
    static final String PEERS = "peer-downloads", DEBRID = "debrid";
    static volatile boolean on = true;

    static boolean enabled(Context context, String id) {
        return on;
    }

    static void requireEnabled(Context context, String id) throws IOException {
        if (!on) throw new IOException("Enable the feature in Patches & tweaks first.");
    }
}
