package app.bitchord.telegram;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;

/** Window capture follows the exact build profile selected by the patch bundle. */
public final class CapturePolicy {
    private static Application installed;

    private CapturePolicy() {}

    /** Morphe changes this marker only for the explicitly designated UI testing build. */
    public static boolean testingBuild() {
        return false;
    }

    /** Sensitive patch screens retain their protected defaults outside the testing build. */
    public static void apply(Activity activity) {
        if (activity == null) return;
        apply(activity.getWindow());
        if (Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(testingBuild());
    }

    /** Also used by separate dialog windows, including credential entry. */
    public static void apply(Window window) {
        if (window == null) return;
        if (testingBuild()) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    /** Covers BitChord's own activities as well as the injected patch screens. */
    public static synchronized void start(Context context) {
        if (!testingBuild() || context == null) return;
        Context app = context.getApplicationContext();
        if (!(app instanceof Application) || installed == app) return;
        Application application = (Application) app;
        application.registerActivityLifecycleCallbacks(new TestingWindows());
        installed = application;
    }

    private static void allow(Activity activity) {
        if (!testingBuild()) return;
        apply(activity);
        // A host activity can set its flags after calling super.onCreate/onResume.
        // Repeat once after that lifecycle callback has finished, without a polling loop.
        Window window = activity.getWindow();
        if (window != null)
            window.getDecorView()
                    .post(
                            () -> {
                                if (testingBuild() && !activity.isDestroyed()) apply(activity);
                            });
    }

    private static final class TestingWindows implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, Bundle state) {
            allow(activity);
        }

        @Override
        public void onActivityStarted(Activity activity) {
            allow(activity);
        }

        @Override
        public void onActivityResumed(Activity activity) {
            allow(activity);
        }

        @Override
        public void onActivityPaused(Activity activity) {}

        @Override
        public void onActivityStopped(Activity activity) {}

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle state) {}

        @Override
        public void onActivityDestroyed(Activity activity) {}
    }
}
