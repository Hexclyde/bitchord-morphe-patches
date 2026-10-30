package com.music.bitchord.data.sources;

public final class SourceRegistry {
    public static boolean enabled = true;
    public static int lookups;

    public static final class SourceConfig {
        public boolean getEnabled() {
            return enabled;
        }
    }

    public static SourceConfig config(String id) {
        lookups++;
        if (!"soulseek-native".equals(id)) throw new AssertionError("Unexpected source lookup");
        return new SourceConfig();
    }
}
