package app.bitchord.soulseek;

import java.util.ArrayList;
import java.util.List;

/** Registry glue for the pinned BitChord build; the user's source toggle is preserved. */
final class SoulseekSource {
    static final String ID = "soulseek-native";

    private SoulseekSource() {}

    static boolean enabled() {
        try {
            Class<?> registry = Class.forName("com.music.bitchord.data.sources.SourceRegistry");
            Object value = registry.getMethod("config", String.class).invoke(null, ID);
            return value != null
                    && (Boolean) value.getClass().getMethod("getEnabled").invoke(value);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
    }

    static void setEnabled(boolean enabled) throws java.io.IOException {
        try {
            Class<?> registry = Class.forName("com.music.bitchord.data.sources.SourceRegistry");
            Object value = registry.getMethod("config", String.class).invoke(null, ID);
            if (value == null)
                throw new java.io.IOException(
                        "Soulseek is still starting. Reopen Patches & tweaks.");
            registry.getMethod("setEnabled", String.class, boolean.class).invoke(null, ID, enabled);
            if (enabled() != enabled)
                throw new java.io.IOException("Soulseek settings could not be changed.");
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            throw new java.io.IOException(
                    "Soulseek settings are unavailable in this BitChord version.", unavailable);
        }
    }

    static void register(String url) throws Exception {
        Class<?> config = Class.forName("com.music.bitchord.data.sources.SourceConfig");
        Class<?> kind = Class.forName("com.music.bitchord.data.sources.SourceKind");
        Class<?> registry = Class.forName("com.music.bitchord.data.sources.SourceRegistry");
        Class<?> state = Class.forName("kotlinx.coroutines.flow.StateFlow");
        Object flow = registry.getField("configs").get(null);
        List<?> existing = (List<?>) state.getMethod("getValue").invoke(flow);
        boolean enabled = false;
        for (Object value : existing)
            if (ID.equals(config.getMethod("getId").invoke(value))) {
                enabled = (Boolean) config.getMethod("getEnabled").invoke(value);
                if (url.equals(config.getMethod("getBaseUrl").invoke(value))) return;
            }
        Object source =
                config.getConstructor(
                                String.class,
                                kind,
                                String.class,
                                String.class,
                                boolean.class,
                                boolean.class,
                                boolean.class)
                        .newInstance(
                                ID,
                                kind.getField("ADDON").get(null),
                                "Unofficial Soulseek",
                                url,
                                enabled,
                                true,
                                false);
        ArrayList<Object> next = new ArrayList<>();
        for (Object value : existing)
            if (!ID.equals(config.getMethod("getId").invoke(value))) next.add(value);
        next.add(source);
        registry.getMethod("publish", ArrayList.class, boolean.class).invoke(null, next, true);
    }
}
