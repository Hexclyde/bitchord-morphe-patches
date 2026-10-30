package app.bitchord.soulseek;

import android.content.Context;

import java.io.IOException;

final class AudioFormatPreferences {
    static final class Settings {
        final String encoded;
        final boolean configured;

        Settings(String value, boolean configured) {
            encoded = value;
            this.configured = configured;
        }
    }

    static Settings current = new Settings("original formats", true);

    static Settings snapshot(Context context) {
        return current;
    }

    static String encode(Settings settings) {
        return settings.encoded;
    }

    static String audioName(String name) throws IOException {
        if (name == null || !name.endsWith(".mp3")) throw new IOException("Invalid name");
        return name;
    }
}
