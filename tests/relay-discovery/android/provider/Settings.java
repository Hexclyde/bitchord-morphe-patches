package android.provider;

import android.content.ContentResolver;

public final class Settings {
    public static final class Global {
        public static final String BOOT_COUNT = "boot_count";
        public static volatile int boot = 1;

        public static int getInt(ContentResolver resolver, String name, int fallback) {
            return boot;
        }
    }
}
