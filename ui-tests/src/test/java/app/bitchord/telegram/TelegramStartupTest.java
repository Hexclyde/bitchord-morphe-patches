package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.content.*;
import android.view.*;
import android.widget.TextView;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class TelegramStartupTest {
    ActivityController<TelegramActivity> controller;

    @Implements(value = TelegramBridge.class, isInAndroidSdk = false)
    public static class FailedBridge {
        @Implementation
        public static void init(Context context) {
            throw new UnsatisfiedLinkError("secret-API-hash-and-phone-must-not-be-copied");
        }
    }

    @Implements(value = TelegramBridge.class, isInAndroidSdk = false)
    public static class UnavailableBridge {
        @Implementation
        public static void init(Context context) {
            TelegramBridge.startupError = "Native extension could not start";
        }
    }

    @Before
    public void reset() {
        TelegramBridge.instance = null;
        TelegramBridge.startupError = "";
    }

    @After
    public void close() {
        if (controller != null) {
            controller.get().handler.removeCallbacksAndMessages(null);
            controller.pause().stop().destroy();
        }
        if (TelegramBridge.instance != null) TelegramBridge.instance.work.shutdownNow();
        TelegramBridge.instance = null;
        TelegramBridge.startupError = "";
    }

    static TextView text(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText()))
            return (TextView) view;
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                TextView found = text(((ViewGroup) view).getChildAt(i), label);
                if (found != null) return found;
            }
        return null;
    }

    void prepared() {
        try {
            if (controller.get().preparation != null)
                controller.get().preparation.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception failed) {
            throw new AssertionError(failed);
        }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    void assertFailurePage() {
        assertNotNull(
                text(controller.get().getWindow().getDecorView(), "Telegram page could not open"));
        assertNotNull(text(controller.get().getWindow().getDecorView(), "Copy crash details"));
        assertTrue(controller.get().secrets.isEmpty());
        assertFalse(controller.get().isFinishing());
    }

    @Test
    public void deniedOverlayProtectionDoesNotCrashOrExposeCredentialFields() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .denyPermissions("android.permission.HIDE_OVERLAY_WINDOWS");
        controller = Robolectric.buildActivity(TelegramActivity.class).setup();
        prepared();
        assertFailurePage();
    }

    @Test
    @Config(shadows = FailedBridge.class)
    public void unexpectedStartupLinkageFailureProvidesRedactedDiagnostics() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions("android.permission.HIDE_OVERLAY_WINDOWS");
        controller = Robolectric.buildActivity(TelegramActivity.class).setup();
        prepared();
        assertFailurePage();
        text(controller.get().getWindow().getDecorView(), "Copy crash details").performClick();
        ClipboardManager clipboard =
                (ClipboardManager) controller.get().getSystemService(Context.CLIPBOARD_SERVICE);
        String details = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
        assertTrue(details.contains("UnsatisfiedLinkError"));
        assertFalse(details.contains("secret-API-hash-and-phone"));
    }

    @Test
    @Config(shadows = UnavailableBridge.class)
    public void unavailableNativeBridgeLeavesAReadableConnectionPage() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions("android.permission.HIDE_OVERLAY_WINDOWS");
        controller = Robolectric.buildActivity(TelegramActivity.class).setup();
        prepared();
        assertNotNull(
                text(
                        controller.get().getWindow().getDecorView(),
                        "Native extension could not start"));
        assertTrue(controller.get().secrets.isEmpty());
        assertFalse(controller.get().isFinishing());
    }

    @Test
    public void nullStatusDetailDoesNotCrashRefresh() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions("android.permission.HIDE_OVERLAY_WINDOWS");
        controller = Robolectric.buildActivity(TelegramActivity.class).setup();
        prepared();
        TelegramActivity page = controller.get();
        page.bridge.error = null;
        page.render();
        assertNull(text(page.getWindow().getDecorView(), "Telegram page could not open"));
        assertFalse(page.isFinishing());
        page.refresh.run();
        assertFalse(page.isFinishing());
    }
}
