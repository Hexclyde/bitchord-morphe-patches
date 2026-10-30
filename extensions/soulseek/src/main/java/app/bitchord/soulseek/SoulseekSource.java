package app.bitchord.soulseek;

import java.util.ArrayList;
import java.util.List;

/** Registry glue for the pinned BitChord build; the user's source toggle is preserved. */
final class SoulseekSource {
    static final String ID = "soulseek-native";

    private SoulseekSource() {}

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
