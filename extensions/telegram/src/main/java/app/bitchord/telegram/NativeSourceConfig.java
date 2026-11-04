package app.bitchord.telegram;

import java.util.Collections;
import java.util.List;

/** Builds the Sources row of an internal addon for the BitChord build that is running. */
public final class NativeSourceConfig {
    private NativeSourceConfig() {}

    /**
     * Internal addons declare no quality settings, so the row gets no quality options and no pick.
     * The seven-value constructor is the one BitChord 1.9 has; the nine-value one adds the options
     * and the pick.
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
        try {
            return config.getConstructor(
                            String.class,
                            kindType,
                            String.class,
                            String.class,
                            boolean.class,
                            boolean.class,
                            boolean.class,
                            List.class,
                            String.class)
                    .newInstance(
                            id,
                            kind,
                            label,
                            url,
                            enabled,
                            allowDownloads,
                            checkValidLossless,
                            Collections.emptyList(),
                            null);
        } catch (NoSuchMethodException newer) {
            try {
                return config.getConstructor(
                                String.class,
                                kindType,
                                String.class,
                                String.class,
                                boolean.class,
                                boolean.class,
                                boolean.class)
                        .newInstance(
                                id, kind, label, url, enabled, allowDownloads, checkValidLossless);
            } catch (NoSuchMethodException older) {
                throw new IllegalStateException(
                        "Source configuration constructor is not a known BitChord shape", older);
            }
        }
    }
}
