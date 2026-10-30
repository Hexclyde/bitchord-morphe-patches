package com.music.bitchord.data.sources;

public final class SourceRegistry {
    public static volatile boolean enabled = true, missing;

    public static final class Config {
        public boolean getEnabled() {
            return enabled;
        }
    }

    public static Config config(String name) {
        return missing ? null : new Config();
    }
}
