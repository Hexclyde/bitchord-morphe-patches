package app.bitchord.telegram;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Settings-only adapters. SourceRegistry remains the authoritative playback/search registry. */
public final class PatchSources {
    private static final String SOULSEEK = "soulseek-native";
    private static final Map<Object, WeakReference<Object>> states = new WeakHashMap<>();

    private PatchSources() {}

    /**
     * Filters only the Sources screen's presentation list. Hidden patch entries stay in the
     * registry so Addons search, exact selected-track IDs and Telegram playback keep working.
     */
    public static List<?> visible(List<?> configs) {
        if (configs == null || configs.isEmpty()) return configs;
        ArrayList<Object> visible = new ArrayList<>(configs.size());
        boolean removed = false;
        for (Object config : configs) {
            String id = "";
            if (config != null)
                try {
                    id = (String) config.getClass().getMethod("getId").invoke(config);
                } catch (ReflectiveOperationException | LinkageError ignored) {
                    // Unknown host/user source objects must never be silently removed.
                }
            if (SOULSEEK.equals(id)) removed = true;
            else visible.add(config);
        }
        return removed ? visible : configs;
    }

    /** Read-only view of the host's collected state, confined to the Sources screen. */
    public static synchronized Object visibleState(Object state) {
        if (state == null) return null;
        WeakReference<Object> reference = states.get(state);
        Object cached = reference == null ? null : reference.get();
        if (cached != null) return cached;
        try {
            Class<?> mutableState = Class.forName("androidx.compose.runtime.MutableState");
            if (!mutableState.isInstance(state)) return state;
            Object filtered =
                    Proxy.newProxyInstance(
                            mutableState.getClassLoader(),
                            new Class<?>[] {mutableState},
                            (proxy, method, arguments) -> {
                                String name = method.getName();
                                if (method.getDeclaringClass() == Object.class) {
                                    if ("equals".equals(name)) return proxy == arguments[0];
                                    if ("hashCode".equals(name))
                                        return System.identityHashCode(proxy);
                                    if ("toString".equals(name)) return "PatchSources.visibleState";
                                }
                                if ("setValue".equals(name) || "component2".equals(name))
                                    throw new UnsupportedOperationException(
                                            "Source settings view is read-only.");
                                try {
                                    Object value = method.invoke(state, arguments);
                                    if (("getValue".equals(name) || "component1".equals(name))
                                            && value instanceof List<?>)
                                        return visible((List<?>) value);
                                    return value;
                                } catch (InvocationTargetException delegated) {
                                    throw delegated.getCause();
                                }
                            });
            states.put(state, new WeakReference<>(filtered));
            return filtered;
        } catch (ClassNotFoundException | LinkageError | IllegalArgumentException unavailable) {
            return state;
        }
    }
}
