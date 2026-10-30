package app.bitchord.soulseek;

import android.app.Activity;
import android.content.Context;

/** No provider endpoint is contacted by settings-screen tests. */
final class DebridDownloads {
    static final DebridDownloads INSTANCE = new DebridDownloads();
    static volatile DebridProvider.Kind validated;

    static DebridDownloads get(Context context) {
        return INSTANCE;
    }

    void validate(DebridProvider.Kind kind, String candidate) {
        if (candidate == null || candidate.isEmpty()) throw new AssertionError("No API key");
        validated = kind;
    }
}

final class DebridDownloadsActivity extends Activity {}
