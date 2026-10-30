package app.bitchord.soulseek;

import android.content.Context;

import java.io.File;
import java.io.IOException;

final class AudioFormatPreferences {
    static final class Settings {
        final String name;
        final boolean configured;

        Settings(String name, boolean configured) {
            this.name = name;
            this.configured = configured;
        }
    }

    static volatile Settings current = new Settings("original", true), verified;
    static volatile boolean failVerification, flipAfterSnapshot;
    static int snapshots;

    static Settings snapshot(Context context) {
        snapshots++;
        Settings captured = current;
        if (flipAfterSnapshot) {
            current = new Settings("changed", true);
            flipAfterSnapshot = false;
        }
        return captured;
    }

    static String audioName(String name) throws IOException {
        if (!name.endsWith(".flac")) throw new IOException("Not audio.");
        return name;
    }

    static void validateSelectedFile(Settings formats, File source, String name)
            throws IOException {
        verified = formats;
        if (failVerification)
            throw new IOException("Audio header does not match an enabled format.");
    }

    static String encode(Settings formats) {
        return formats.name;
    }
}
