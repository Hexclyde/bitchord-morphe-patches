package app.bitchord.telegram;

import android.app.Activity;
import android.content.Context;
import android.view.Window;

/** Compile-only API; the shared patch runtime supplies the build-specific implementation. */
public final class CapturePolicy {
    private CapturePolicy() {}

    public static boolean testingBuild() {
        return false;
    }

    public static void apply(Activity activity) {}

    public static void apply(Window window) {}

    public static void start(Context context) {}
}
