package coil.util;

import androidx.compose.runtime.ComposerImpl;

import kotlin.jvm.functions.Function0;

public final class DrawableUtils {
    public static Function0 back;

    public static void BackHandler(
            boolean enabled, Function0 action, ComposerImpl composer, int changed, int defaults) {
        back = enabled ? action : null;
    }
}
