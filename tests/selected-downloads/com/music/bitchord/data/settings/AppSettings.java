package com.music.bitchord.data.settings;

public final class AppSettings {
    public static volatile boolean allowed = true;

    public static boolean getDownloadsAllowedNow() {
        return allowed;
    }
}
