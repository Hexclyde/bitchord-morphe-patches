package app.bitchord.telegram;

import java.util.Collections;
import java.util.List;

/** Builds the Sources row of an internal addon for the BitChord build that is running. */
public final class NativeSourceConfig {
    private NativeSourceConfig() {}

    /** Ids of the addons built into the app, which answer from the phone and not from a server. */
    public static boolean isNative(String id) {
        return "telegram-native".equals(id)
                || "flacdownloader-native".equals(id)
                || "soulseek-native".equals(id);
    }

    /**
     * Internal addons declare no quality settings and no BitChord verification, so the row gets no
     * quality options, no pick and no declared flag. BitChord 1.9 takes seven values, the next
     * build adds the options and the pick, and 1.9.1 adds the declared flag.
     */
    public static Object addon(
            String id,
            String label,
            String url,
            boolean enabled,
            boolean allowDownloads,
            boolean checkValidLossless)
            throws ReflectiveOperationException {
        Class<?> config = Class.forName("com.music.bitchord.data.sources.SourceConfig");
        Class<?> kindType = Class.forName("com.music.bitchord.data.sources.SourceKind");
        Object kind = kindType.getField("ADDON").get(null);
        Class<?>[] seven = {
            String.class,
            kindType,
            String.class,
            String.class,
            boolean.class,
            boolean.class,
            boolean.class
        };
        Object[] base = {id, kind, label, url, enabled, allowDownloads, checkValidLossless};
        Class<?>[] nine = types(seven, List.class, String.class);
        Class<?>[] ten = types(nine, Boolean.class);
        try {
            return config.getConstructor(ten)
                    .newInstance(values(base, Collections.emptyList(), null, null));
        } catch (NoSuchMethodException auth) {
            try {
                return config.getConstructor(nine)
                        .newInstance(values(base, Collections.emptyList(), null));
            } catch (NoSuchMethodException quality) {
                try {
                    return config.getConstructor(seven).newInstance(base);
                } catch (NoSuchMethodException legacy) {
                    throw new IllegalStateException(
                            "Source configuration constructor is not a known BitChord shape",
                            legacy);
                }
            }
        }
    }

    private static Class<?>[] types(Class<?>[] base, Class<?>... more) {
        Class<?>[] out = java.util.Arrays.copyOf(base, base.length + more.length);
        System.arraycopy(more, 0, out, base.length, more.length);
        return out;
    }

    private static Object[] values(Object[] base, Object... more) {
        Object[] out = java.util.Arrays.copyOf(base, base.length + more.length);
        System.arraycopy(more, 0, out, base.length, more.length);
        return out;
    }
}
