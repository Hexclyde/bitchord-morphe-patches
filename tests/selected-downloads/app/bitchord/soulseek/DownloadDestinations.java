package app.bitchord.soulseek;

import android.content.Context;

import java.io.IOException;

final class DownloadDestinations {
    static final class Snapshot {
        final String mode;

        Snapshot(String mode) {
            this.mode = mode;
        }
    }

    static volatile boolean missing;
    static volatile Snapshot current = new Snapshot("phone");

    static Snapshot snapshot(Context context) throws IOException {
        if (missing) throw new IOException("Choose copy destinations first.");
        return current;
    }
}
