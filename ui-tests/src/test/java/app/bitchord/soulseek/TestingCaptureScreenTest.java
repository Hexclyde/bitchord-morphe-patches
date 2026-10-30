package app.bitchord.soulseek;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CompoundButton;

import app.bitchord.telegram.CapturePolicy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;

import java.lang.reflect.Field;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.crypto.spec.SecretKeySpec;

@RunWith(RobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 35,
        instrumentedPackages = "app.bitchord.telegram.CapturePolicy")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
public class TestingCaptureScreenTest {
    @Implements(value = CapturePolicy.class, isInAndroidSdk = false)
    public static class TestingBuild {
        @Implementation
        protected static boolean testingBuild() {
            return true;
        }
    }

    public static class SecureScreen extends Activity {
        boolean recentsAllowed;

        @Override
        public void onCreate(Bundle state) {
            super.onCreate(state);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            setRecentsScreenshotEnabled(false);
        }

        @Override
        public void setRecentsScreenshotEnabled(boolean enabled) {
            recentsAllowed = enabled;
            super.setRecentsScreenshotEnabled(enabled);
        }
    }

    private ActivityController<? extends Activity> controller;

    @Before
    public void prepare() throws Exception {
        shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.HIDE_OVERLAY_WINDOWS);
        DebridCredentials.Storage storage =
                new DebridCredentials.Storage() {
                    byte[] sealed;

                    public byte[] read() {
                        return sealed;
                    }

                    public void write(byte[] value) {
                        sealed = value.clone();
                    }

                    public void clear() {
                        sealed = null;
                    }
                };
        credentials(
                new DebridCredentials(
                        storage,
                        new DebridCredentials.AesCrypto(
                                create -> new SecretKeySpec(new byte[32], "AES"),
                                "capture-ui-fixture")));
    }

    @After
    public void close() throws Exception {
        if (controller != null) controller.pause().stop().destroy();
        credentials(null);
    }

    private static void credentials(DebridCredentials value) throws Exception {
        Field field = DebridCredentials.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, value);
    }

    private static boolean secure(Activity activity) {
        return (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE)
                != 0;
    }

    @Test
    public void sourceDefaultRetainsProtectedWindowsAndRecents() {
        assertFalse(CapturePolicy.testingBuild());
        controller = Robolectric.buildActivity(SecureScreen.class).setup();
        SecureScreen screen = (SecureScreen) controller.get();
        CapturePolicy.apply(screen);
        assertTrue(secure(screen));
        assertFalse(screen.recentsAllowed);
    }

    @Test
    @Config(shadows = TestingBuild.class)
    public void testingProfileAllowsCaptureAndRecentsAndHandlesWindowOverload() {
        assertTrue(CapturePolicy.testingBuild());
        controller = Robolectric.buildActivity(SecureScreen.class).setup();
        SecureScreen screen = (SecureScreen) controller.get();
        CapturePolicy.apply(screen);
        assertFalse(secure(screen));
        assertTrue(screen.recentsAllowed);
        screen.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        CapturePolicy.apply(screen.getWindow());
        assertFalse(secure(screen));
    }

    @Test
    @Config(shadows = TestingBuild.class)
    public void testingProfileAllowsDebridPageAndItsSeparateApiKeyDialog() throws Exception {
        CapturePolicy.start(RuntimeEnvironment.getApplication());
        controller = Robolectric.buildActivity(DebridSetupActivity.class).setup();
        DebridSetupActivity activity = (DebridSetupActivity) controller.get();
        settle(activity);
        assertFalse(secure(activity));
        CompoundButton enabled = first(activity.findViewById(android.R.id.content));
        assertNotNull(enabled);
        enabled.performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialog.isShowing());
        assertEquals(
                0,
                dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
        dialog.cancel();
        shadowOf(Looper.getMainLooper()).idle();
    }

    private CompoundButton first(View view) {
        if (view instanceof CompoundButton) return (CompoundButton) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                CompoundButton found = first(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void settle(DebridSetupActivity activity) throws Exception {
        Field field = DebridSetupActivity.class.getDeclaredField("worker");
        field.setAccessible(true);
        ThreadPoolExecutor worker = (ThreadPoolExecutor) field.get(activity);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        do {
            shadowOf(Looper.getMainLooper()).idle();
            if (worker.getActiveCount() == 0 && worker.getQueue().isEmpty()) {
                shadowOf(Looper.getMainLooper()).idle();
                return;
            }
            Thread.sleep(5);
        } while (System.nanoTime() < end);
        fail("Settings worker did not complete");
    }
}
