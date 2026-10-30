package app.bitchord.soulseek;

import android.content.Context;

final class DownloadDestinations {
    static final class Snapshot {
        final String phoneTreeUri;

        Snapshot(String uri) {
            phoneTreeUri = uri;
        }
    }

    static Snapshot current = new Snapshot("content://original-folder");

    static Snapshot snapshot(Context context) {
        return current;
    }
}
