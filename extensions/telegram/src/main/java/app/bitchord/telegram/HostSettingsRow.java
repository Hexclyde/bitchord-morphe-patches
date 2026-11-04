package app.bitchord.telegram;

import androidx.compose.runtime.ComposerImpl;
import androidx.compose.ui.graphics.painter.Painter;

import com.music.bitchord.ui.screens.SettingsSheetKt;

import kotlin.jvm.functions.Function0;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calls the host's themed settings row. BitChord 1.9.1 inserted an icon-content slot before the
 * click handler and a third Compose int, so the row is looked up by shape instead of linked.
 */
final class HostSettingsRow {
    private static final int OLD_SHAPE = 13;
    private static final int ICON_CONTENT_SHAPE = 15;
    private static final Map<Class<?>, Method> ROWS = new ConcurrentHashMap<>();

    private HostSettingsRow() {}

    static void call(
            String title,
            String subtitle,
            boolean enabled,
            Painter icon,
            Function0<?> click,
            ComposerImpl composer) {
        invoke(SettingsSheetKt.class, title, subtitle, enabled, icon, click, composer);
    }

    static void invoke(
            Class<?> host,
            String title,
            String subtitle,
            boolean enabled,
            Painter icon,
            Function0<?> click,
            ComposerImpl composer) {
        Method method = ROWS.get(host);
        if (method == null) {
            for (Method candidate : host.getDeclaredMethods()) {
                int count = candidate.getParameterTypes().length;
                if (candidate.getName().equals("SettingsRow")
                        && (count == OLD_SHAPE || count == ICON_CONTENT_SHAPE)) {
                    method = candidate;
                    break;
                }
            }
            if (method == null) throw new IllegalStateException("Host settings row unavailable");
            ROWS.put(host, method);
        }
        Object[] args =
                method.getParameterTypes().length == OLD_SHAPE
                        ? new Object[] {
                            null, title, subtitle, null, null, null, enabled, icon, click, null,
                            composer, 0, 0
                        }
                        : new Object[] {
                            null, title, subtitle, null, null, null, enabled, icon, null, click,
                            null, composer, 0, 0, 0
                        };
        try {
            method.invoke(null, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Host settings row failed", e);
        }
    }
}
