package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 35,
        qualifiers = "en-rUS-w393dp-h852dp-mdpi",
        shadows = DiagnosticsScreenTest.Installed.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class DiagnosticsScreenTest {
    @Implements(value = SoundExtras.class, isInAndroidSdk = false)
    public static class Installed {
        @Implementation
        public static boolean diagnosticsApplied() {
            return true;
        }
    }

    private static View tagged(DiagnosticsActivity activity, String tag) {
        View view = activity.getWindow().getDecorView().findViewWithTag(tag);
        assertNotNull(tag, view);
        return view;
    }

    private static void set(String field, Object value) throws Exception {
        Field target = Diagnostics.class.getDeclaredField(field);
        target.setAccessible(true);
        target.set(null, value);
    }

    private static Object get(String field) throws Exception {
        Field target = Diagnostics.class.getDeclaredField(field);
        target.setAccessible(true);
        return target.get(null);
    }

    @Test
    public void explicitRecordingExportExpiryClearAndCrashDelegation() throws Exception {
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        AtomicInteger delegated = new AtomicInteger();
        Throwable[] received = new Throwable[1];
        Thread.setDefaultUncaughtExceptionHandler(
                (thread, error) -> {
                    delegated.incrementAndGet();
                    received[0] = error;
                });
        ActivityController<DiagnosticsActivity> controller = null;
        CountDownLatch release = new CountDownLatch(1);
        try {
            controller = Robolectric.buildActivity(DiagnosticsActivity.class).setup().visible();
            DiagnosticsActivity activity = controller.get();
            assertFalse("Opening settings must not opt in", Diagnostics.active());
            assertEquals(
                    "Off", ((TextView) tagged(activity, "diagnostics-state")).getText().toString());
            assertTrue(tagged(activity, "diagnostics-start").isEnabled());
            assertFalse(tagged(activity, "diagnostics-stop").isEnabled());
            Diagnostics.report();
            assertFalse(
                    new java.io.File(activity.getNoBackupFilesDir(), "support-recording.jsonl")
                            .exists());
            assertNull(Shadows.shadowOf(activity).getNextStartedActivity());

            android.content.pm.PackageInfo installed =
                    activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            installed.versionName = "1.9.1-beta1";
            Shadows.shadowOf(activity.getPackageManager()).installPackage(installed);
            tagged(activity, "diagnostics-start").performClick();
            assertTrue(Diagnostics.active());
            assertFalse(tagged(activity, "diagnostics-start").isEnabled());
            assertTrue(tagged(activity, "diagnostics-stop").isEnabled());
            for (String action : new String[] {"start", "stop", "save", "delete"})
                assertTrue(tagged(activity, "diagnostics-" + action).getMinimumHeight() >= 48);
            String started = Diagnostics.report();
            assertTrue(started.contains("recording.started"));
            assertTrue(started.contains("environment.sdk"));
            assertTrue(started, started.contains("\"app_version\":\"1.9.1-beta1\""));
            tagged(activity, "diagnostics-save").performClick();
            Intent document = Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, document.getAction());
            assertEquals("text/plain", document.getType());
            assertTrue(document.hasCategory(Intent.CATEGORY_OPENABLE));
            assertEquals("bitchord-diagnostics.txt", document.getStringExtra(Intent.EXTRA_TITLE));
            assertSame(
                    "Activity and result queues refer to the same document request",
                    document,
                    Shadows.shadowOf(activity).getNextStartedActivity());
            assertNull(
                    "No second intent or upload",
                    Shadows.shadowOf(activity).getNextStartedActivity());

            tagged(activity, "diagnostics-stop").performClick();
            assertFalse(Diagnostics.active());
            assertTrue(Diagnostics.report().contains("recording.stopped"));
            controller.pause().stop().destroy();
            controller = Robolectric.buildActivity(DiagnosticsActivity.class).setup().visible();
            activity = controller.get();
            assertFalse("Reopening must keep recording stopped", Diagnostics.active());
            assertTrue(Diagnostics.report().contains("recording.started"));

            tagged(activity, "diagnostics-start").performClick();
            set("deadline", System.currentTimeMillis() - 1);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertFalse(Diagnostics.active());
            assertEquals(
                    "Off", ((TextView) tagged(activity, "diagnostics-state")).getText().toString());
            Diagnostics.nativeLine('E', "ERR_UNKNOWN_URL_SCHEME PRIVATECOOKIE");
            assertFalse(Diagnostics.report().contains("ERR_UNKNOWN_URL_SCHEME"));

            tagged(activity, "diagnostics-start").performClick();
            Diagnostics.report();
            ThreadPoolExecutor writer = (ThreadPoolExecutor) get("writer");
            CountDownLatch entered = new CountDownLatch(1);
            writer.execute(
                    () -> {
                        entered.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Diagnostics.nativeLine(
                    'E', "ERR_UNKNOWN_URL_SCHEME PRIVATECOOKIE person@example.invalid");
            tagged(activity, "diagnostics-delete").performClick();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            release.countDown();
            String cleared = Diagnostics.report();
            assertFalse(Diagnostics.active());
            assertFalse(cleared.contains("host.line"));
            assertFalse(cleared.contains("PRIVATECOOKIE"));
            assertTrue(cleared.contains("\"event_count\":0"));

            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            tagged(activity, "diagnostics-start").performClick();
            Diagnostics.report();
            Throwable crash = new IllegalStateException("PRIVATEPASSWORD person@example.invalid");
            crash.setStackTrace(
                    new StackTraceElement[] {
                        new StackTraceElement(
                                "app.bitchord.telegram.MicroGSignIn",
                                "requestToken",
                                "private/path",
                                72)
                    });
            Thread.getDefaultUncaughtExceptionHandler()
                    .uncaughtException(Thread.currentThread(), crash);
            assertEquals(1, delegated.get());
            assertSame(crash, received[0]);
            String report = Diagnostics.report();
            assertTrue(report.contains("java.crash"));
            assertTrue(report.contains("MICROG_SIGN_IN"));
            assertFalse(report.contains("PRIVATEPASSWORD"));
            assertFalse(report.contains("person@example.invalid"));
            assertFalse(report.contains("private/path"));
        } finally {
            release.countDown();
            Diagnostics.clear();
            Diagnostics.report();
            if (controller != null) controller.pause().stop().destroy();
            Thread.setDefaultUncaughtExceptionHandler(original);
        }
    }
}
