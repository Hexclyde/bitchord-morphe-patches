package app.bitchord.soulseek;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.lang.reflect.Method;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class DownloadsSavedPlaybackTest {
    Activity activity;
    DownloadsHubPage page;
    File file;
    java.util.List<com.music.bitchord.data.sources.SourceConfig> originalSources;
    java.util.List<com.music.bitchord.data.sources.SourceConfig> disabledSources;

    @Before
    public void open() throws Exception {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        originalSources = com.music.bitchord.data.sources.SourceRegistry.configs.getValue();
        disabledSources =
                java.util.Arrays.asList(
                        new com.music.bitchord.data.sources.SourceConfig(
                                "yt",
                                com.music.bitchord.data.sources.SourceKind.YOUTUBE,
                                "YouTube",
                                "",
                                false,
                                true,
                                false),
                        new com.music.bitchord.data.sources.SourceConfig(
                                "addon",
                                com.music.bitchord.data.sources.SourceKind.ADDON,
                                "Addon",
                                "",
                                false,
                                true,
                                false));
        com.music.bitchord.data.sources.SourceRegistry.configs.setValue(disabledSources);
        ActivityInfo target = new ActivityInfo();
        target.name = "com.music.bitchord.LauncherAlias";
        target.targetActivity = "com.music.bitchord.MainActivity";
        target.packageName = activity.getPackageName();
        target.applicationInfo = activity.getApplicationInfo();
        target.enabled = target.exported = true;
        ResolveInfo resolved = new ResolveInfo();
        resolved.activityInfo = target;
        Shadows.shadowOf(activity.getPackageManager())
                .addResolveInfoForIntent(
                        new Intent(Intent.ACTION_MAIN)
                                .addCategory(Intent.CATEGORY_LAUNCHER)
                                .setPackage(activity.getPackageName()),
                        resolved);
        page = new DownloadsHubPage(activity, new Intent());
        page.onCreate(new Bundle());
        file = File.createTempFile("saved-playback", ".wav", activity.getFilesDir());
    }

    @After
    public void close() {
        page.onDestroy();
        assertEquals(
                disabledSources, com.music.bitchord.data.sources.SourceRegistry.configs.getValue());
        com.music.bitchord.data.sources.SourceRegistry.configs.setValue(originalSources);
        file.delete();
        activity.finish();
    }

    private LinearLayout row() throws Exception {
        return row(Uri.fromFile(file).toString());
    }

    private LinearLayout row(String location) throws Exception {
        Method factory =
                DownloadsHubPage.class.getDeclaredMethod(
                        "fileRow", String.class, String.class, String.class, String.class);
        factory.setAccessible(true);
        DownloadsHubPage.Row data =
                (DownloadsHubPage.Row)
                        factory.invoke(
                                page, "Saved recording", "Artist · Album", "saved-id", location);
        Method render =
                DownloadsHubPage.class.getDeclaredMethod("rowView", DownloadsHubPage.Row.class);
        render.setAccessible(true);
        LinearLayout card = (LinearLayout) render.invoke(page, data);
        activity.setContentView(card);
        return card;
    }

    @Test
    public void tappingSavedCardStartsTheNativeFileHandler() throws Exception {
        LinearLayout card = row();
        assertTrue("Saved card must have a primary playback action", card.performClick());
        Intent launched = waitForPlayback();
        assertNotNull("Saved playback must reach BitChord", launched);
        assertEquals(Intent.ACTION_VIEW, launched.getAction());
        assertEquals(Uri.fromFile(file), launched.getData());
        assertEquals(activity.getPackageName(), launched.getComponent().getPackageName());
        assertEquals("com.music.bitchord.LauncherAlias", launched.getComponent().getClassName());
        assertEquals(activity.getPackageName(), launched.getPackage());
        assertTrue((launched.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }

    private Intent waitForPlayback() throws Exception {
        Intent launched = null;
        for (int i = 0; i < 200 && launched == null; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            launched = Shadows.shadowOf(activity).getNextStartedActivity();
            if (launched == null) Thread.sleep(10);
        }
        return launched;
    }

    private static View text(View view, String name) {
        if (view instanceof android.widget.TextView
                && name.contentEquals(((android.widget.TextView) view).getText())) return view;
        if (view instanceof android.view.ViewGroup)
            for (int i = 0; i < ((android.view.ViewGroup) view).getChildCount(); i++) {
                View found = text(((android.view.ViewGroup) view).getChildAt(i), name);
                if (found != null) return found;
            }
        return null;
    }

    private static View description(View view, String name) {
        if (name.contentEquals(
                view.getContentDescription() == null ? "" : view.getContentDescription()))
            return view;
        if (view instanceof android.view.ViewGroup)
            for (int i = 0; i < ((android.view.ViewGroup) view).getChildCount(); i++) {
                View found = description(((android.view.ViewGroup) view).getChildAt(i), name);
                if (found != null) return found;
            }
        return null;
    }

    @Test
    public void titleTouchPlaysInEachViewAndKeepsTheMenuSeparate() throws Exception {
        java.lang.reflect.Field mode = DownloadsHubPage.class.getDeclaredField("viewMode");
        mode.setAccessible(true);
        for (String name : DownloadsHubPage.VIEW_IDS) {
            mode.set(page, name);
            LinearLayout card = row();
            assertNull(
                    "Rendering never starts playback",
                    Shadows.shadowOf(activity).getNextStartedActivity());
            assertTrue(card.isFocusable());
            assertTrue(
                    card.getMinimumHeight()
                            >= 48 * activity.getResources().getDisplayMetrics().density);
            assertEquals(
                    android.widget.Button.class.getName(),
                    card.createAccessibilityNodeInfo().getClassName());
            card.measure(
                    View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            card.layout(0, 0, 480, card.getMeasuredHeight());
            View title = text(card, "Saved recording");
            int[] at = new int[2], origin = new int[2];
            title.getLocationOnScreen(at);
            card.getLocationOnScreen(origin);
            float x = at[0] - origin[0] + title.getWidth() / 2f;
            float y = at[1] - origin[1] + title.getHeight() / 2f;
            long now = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent down =
                    android.view.MotionEvent.obtain(
                            now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
            android.view.MotionEvent up =
                    android.view.MotionEvent.obtain(
                            now, now + 30, android.view.MotionEvent.ACTION_UP, x, y, 0);
            try {
                card.dispatchTouchEvent(down);
                card.dispatchTouchEvent(up);
            } finally {
                down.recycle();
                up.recycle();
            }
            assertNotNull(name + " title touch must play", waitForPlayback());
            View menu = description(card, "Actions for Saved recording");
            assertTrue(menu.performClick());
            assertNull(
                    "Overflow must not trigger the card",
                    Shadows.shadowOf(activity).getNextStartedActivity());
            android.app.Dialog dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertTrue(dialog.isShowing());
            View play = dialog.getWindow().getDecorView().findViewWithTag("download-setting:1");
            assertEquals("Play", play.getContentDescription());
            assertTrue(play.performClick());
            Intent intent = waitForPlayback();
            assertNotNull(intent);
            assertEquals(activity.getPackageName(), intent.getPackage());
            assertFalse(dialog.isShowing());
        }
    }

    @Test
    public void duplicateTapAdmitsOneNativeIntent() throws Exception {
        LinearLayout card = row();
        card.performClick();
        card.performClick();
        assertNotNull(waitForPlayback());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test
    public void missingFileDoesNotLaunchOrEnqueue() throws Exception {
        int before = com.music.bitchord.download.Downloads.queued.size();
        LinearLayout card =
                row(Uri.fromFile(new File(file.getParentFile(), "missing-saved-audio")).toString());
        card.performClick();
        assertNull(waitForPlayback());
        assertEquals(before, com.music.bitchord.download.Downloads.queued.size());
        assertNotNull(
                text(page.view, "Saved file could not be opened. Check its storage location."));
    }

    @Test
    public void remoteLocationNeverFallsBackToAStreamOrExternalApp() throws Exception {
        LinearLayout card = row("https://example.invalid/audio.wav");
        card.performClick();
        assertNull(waitForPlayback());
        assertNotNull(
                text(page.view, "Saved file could not be opened. Check its storage location."));
    }

    @Test
    public void aClosedPageCannotStartPlaybackAfterItsWorker() throws Exception {
        LinearLayout card = row();
        page.onDestroy();
        card.performClick();
        assertNull(waitForPlayback());
    }

    public static final class SavedProvider extends android.content.ContentProvider {
        static File source;
        static final java.util.List<Thread> opens =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        public boolean onCreate() {
            return true;
        }

        public String getType(Uri uri) {
            return "audio/wav";
        }

        public android.database.Cursor query(Uri u, String[] p, String s, String[] a, String o) {
            return null;
        }

        public Uri insert(Uri u, android.content.ContentValues v) {
            return null;
        }

        public int delete(Uri u, String s, String[] a) {
            return 0;
        }

        public int update(Uri u, android.content.ContentValues v, String s, String[] a) {
            return 0;
        }

        public android.os.ParcelFileDescriptor openFile(Uri uri, String mode)
                throws java.io.FileNotFoundException {
            opens.add(Thread.currentThread());
            return android.os.ParcelFileDescriptor.open(
                    source, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
        }
    }

    @Test
    public void grantedDocumentPlaysThroughTheSameNativeHandlerOffTheUiThread() throws Exception {
        SavedProvider.source = file;
        SavedProvider provider = new SavedProvider();
        android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo();
        info.authority = "saved.audio.test";
        provider.attachInfo(activity, info);
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(
                info.authority, provider);
        Uri uri = Uri.parse("content://saved.audio.test/owned.wav");
        LinearLayout card = row(uri.toString());
        SavedProvider.opens.clear();
        card.performClick();
        Intent intent = waitForPlayback();
        assertNotNull(intent);
        assertEquals(uri, intent.getData());
        assertEquals(activity.getPackageName(), intent.getComponent().getPackageName());
        assertFalse(SavedProvider.opens.isEmpty());
        for (Thread thread : new java.util.ArrayList<>(SavedProvider.opens))
            assertNotEquals(Looper.getMainLooper().getThread(), thread);
    }
}
