package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Looper;
import android.provider.OpenableColumns;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = DiagnosticShareTest.Installed.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class DiagnosticShareTest {
    @Implements(value = SoundExtras.class, isInAndroidSdk = false)
    public static class Installed {
        @Implementation
        public static boolean diagnosticsApplied() {
            return true;
        }
    }

    @Test
    public void nativeShareUsesCompressedAttachmentWithOnlyReadPermission() throws Exception {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        var page = Robolectric.buildActivity(DiagnosticsActivity.class).setup().visible();
        try {
            DiagnosticsActivity activity = page.get();
            activity.getWindow().getDecorView().findViewWithTag("diagnostics-start").performClick();
            Diagnostics.nativeLine(
                    'E', "ERR_UNKNOWN_URL_SCHEME cookie=PRIVATECOOKIE person@example.invalid");
            activity.getWindow().getDecorView().findViewWithTag("diagnostics-stop").performClick();
            activity.getWindow().getDecorView().findViewWithTag("diagnostics-share").performClick();
            var field = DiagnosticsActivity.class.getDeclaredField("worker");
            field.setAccessible(true);
            ((ExecutorService) field.get(activity)).submit(() -> {}).get(5, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Intent chooser = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull(chooser);
            assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
            Intent send = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
            assertEquals(Intent.ACTION_SEND, send.getAction());
            assertEquals("application/zip", send.getType());
            assertTrue((send.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
            assertEquals(0, send.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            Uri uri = send.getParcelableExtra(Intent.EXTRA_STREAM);
            assertEquals("content", uri.getScheme());
            assertEquals(uri, send.getClipData().getItemAt(0).getUri());
            assertFalse(Diagnostics.active());
            File zip =
                    new File(
                            new File(activity.getCacheDir(), "diagnostic-exports"),
                            uri.getLastPathSegment());
            try (ZipFile archive = new ZipFile(zip)) {
                assertEquals(1, archive.size());
                String content =
                        new String(
                                archive.getInputStream(archive.getEntry("bitchord-diagnostics.txt"))
                                        .readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(content.contains("ERR_UNKNOWN_URL_SCHEME"));
                assertFalse(content.contains("PRIVATECOOKIE"));
                assertFalse(content.contains("person@example.invalid"));
            }
            assertNull(
                    "No upload or second target chosen automatically",
                    Shadows.shadowOf(activity).getNextStartedActivity());
        } finally {
            Diagnostics.clear();
            Diagnostics.report();
            page.pause().stop().destroy();
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    public void providerAllowsOnlyExistingReadOnlyZipAndArchiveStorageIsBounded() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        DiagnosticArchive.clear(context);
        File latest = null;
        for (int i = 0; i < 7; i++)
            latest = DiagnosticArchive.create(context, "safe event\n".repeat(500));
        assertTrue(latest.length() < 5000);
        File[] files = latest.getParentFile().listFiles((directory, name) -> name.endsWith(".zip"));
        assertTrue(files.length <= DiagnosticArchive.MAX_FILES);
        DiagnosticFiles provider = new DiagnosticFiles();
        ProviderInfo info = new ProviderInfo();
        info.authority = context.getPackageName() + ".diagnostics";
        info.exported = false;
        info.grantUriPermissions = true;
        provider.attachInfo(context, info);
        Uri uri = DiagnosticFiles.uri(context, latest);
        assertEquals("application/zip", provider.getType(uri));
        try (Cursor cursor = provider.query(uri, null, null, null, null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(
                    latest.getName(),
                    cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)));
            assertEquals(
                    latest.length(),
                    cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)));
        }
        try (var opened = provider.openFile(uri, "r")) {
            assertTrue(opened.getStatSize() > 0);
        }
        for (String mode : new String[] {"w", "rw", "wa", "rwt"}) {
            try {
                provider.openFile(uri, mode);
                fail("Write permission is forbidden");
            } catch (FileNotFoundException expected) {
            }
        }
        for (Uri bad :
                new Uri[] {
                    uri.buildUpon().appendQueryParameter("file", "private").build(),
                    Uri.parse("content://" + info.authority + "/%2e%2e/private"),
                    Uri.parse("content://other/private")
                }) {
            try {
                provider.openFile(bad, "r");
                fail("Only the exact archive is readable");
            } catch (FileNotFoundException expected) {
            }
        }
        latest.setLastModified(System.currentTimeMillis() - DiagnosticArchive.MAX_AGE_MS - 1000);
        assertNull(provider.getType(uri));
        DiagnosticArchive.clear(context);
        assertEquals(
                0,
                latest.getParentFile()
                        .listFiles((directory, name) -> name.endsWith(".zip"))
                        .length);
    }
}
