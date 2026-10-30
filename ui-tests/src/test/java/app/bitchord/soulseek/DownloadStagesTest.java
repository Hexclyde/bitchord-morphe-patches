package app.bitchord.soulseek;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.*;

import app.bitchord.telegram.DownloadUploadBridge;
import app.bitchord.telegram.PatchStrings;

import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class DownloadStagesTest {
    Context context;
    File source;
    byte[] original;

    @Before
    public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        SoulseekBridge.reset(context);
        DownloadSettings.reset();
        PatchStrings.refresh(context);
        DownloadUploadBridge.reset();
        File root = new File(context.getNoBackupFilesDir(), "soulseek-native");
        if (root.exists()) delete(root);
        root.mkdirs();
        context.getSharedPreferences("bitchord_public_soulseek", 0).edit().clear().commit();
        source = new File(context.getFilesDir(), "Original.flac");
        original = new byte[1000];
        System.arraycopy(new byte[] {'f', 'L', 'a', 'C'}, 0, original, 0, 4);
        original[4] = (byte) 128;
        original[7] = 34;
        original[8] = 16;
        original[10] = 16;
        original[18] = 10;
        original[19] = (byte) 196;
        original[20] = 66;
        original[21] = (byte) 240;
        Files.write(source.toPath(), original);
        SoulseekNative.announcements = 0;
        SoulseekNative.publicEnabled = false;
    }

    @After
    public void cleanup() {
        SoulseekBridge.instance.work.shutdownNow();
    }

    void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    JSONObject await(String phase) throws Exception {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(6);
        JSONObject row = null;
        while (System.nanoTime() < until) {
            JSONArray rows = DownloadArchives.snapshot(context);
            if (rows.length() > 0) {
                row = rows.getJSONObject(0);
                if (phase.equals(row.optString("phase"))) return row;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Expected " + phase + " but saw " + row);
    }

    @Test
    public void publicSharesRequireOptInAndUseSeparateOriginalBytes() throws Exception {
        assertFalse(PublicSoulseekShares.snapshot(context).getBoolean("enabled"));
        PublicSoulseekShares.add(context, Uri.fromFile(source), "Artist - Original");
        JSONObject before = PublicSoulseekShares.snapshot(context);
        assertFalse(before.getBoolean("enabled"));
        assertFalse(SoulseekNative.publicEnabled);
        JSONObject row = before.getJSONArray("inventory").getJSONObject(0);
        File copy = new File(SoulseekBridge.get().home, "public-shares/" + row.getString("name"));
        assertArrayEquals(original, Files.readAllBytes(copy.toPath()));
        assertTrue(source.exists());
        PublicSoulseekShares.enable(context, true, 2);
        assertTrue(SoulseekNative.publicEnabled);
        assertEquals(2, SoulseekNative.slots);
        PublicSoulseekShares.remove(context, row.getString("id"));
        assertFalse(copy.exists());
        assertTrue(source.exists());
        assertEquals(0, PublicSoulseekShares.snapshot(context).getJSONArray("inventory").length());
    }

    @Test
    public void unjournaledFilesAreRemovedBeforeAdvertisingAndTamperingBlocksSharing()
            throws Exception {
        PublicSoulseekShares.add(context, Uri.fromFile(source), "Original");
        File orphan = new File(SoulseekBridge.get().home, "public-shares/orphan.flac");
        Files.write(orphan.toPath(), original);
        PublicSoulseekShares.enable(context, true, 1);
        assertFalse(orphan.exists());
        JSONObject row =
                PublicSoulseekShares.snapshot(context).getJSONArray("inventory").getJSONObject(0);
        File copy = new File(SoulseekBridge.get().home, "public-shares/" + row.getString("name"));
        Files.write(copy.toPath(), new byte[] {1, 2, 3});
        PublicSoulseekShares.enable(context, false, 1);
        try {
            PublicSoulseekShares.enable(context, true, 1);
            fail("changed copy must not be advertised");
        } catch (IOException expected) {
        }
        assertFalse(SoulseekNative.publicEnabled);
        assertTrue(source.exists());
    }

    @Test
    public void disconnectedOptOutPersistsAndReconnectDoesNotReEnableShares() throws Exception {
        PublicSoulseekShares.add(context, Uri.fromFile(source), "Original");
        PublicSoulseekShares.enable(context, true, 1);
        SoulseekBridge.get().connected = false;
        PublicSoulseekShares.enable(context, false, 1);
        SoulseekBridge.get().connected = true;
        PublicSoulseekShares.reconcile(context);
        assertFalse(SoulseekNative.publicEnabled);
    }

    @Test
    public void archiveThenExplicitRemovalAndRestorePreserveExactBytes() throws Exception {
        DownloadArchives.archive(context, "", Uri.fromFile(source), "Original");
        JSONObject row = await("archived");
        assertTrue(source.exists());
        assertEquals(1, DownloadUploadBridge.uploads);
        assertEquals(1, DownloadUploadBridge.readbacks);
        DownloadArchives.removeLocal(context, row.getString("id"));
        await("offloaded");
        assertFalse(source.exists());
        assertEquals(2, DownloadUploadBridge.readbacks);
        DownloadArchives.restore(context, row.getString("id"));
        await("restore-submitted");
        assertEquals(row.getString("sha256"), BulkDownloads.restoredHash);
    }

    @Test
    public void failedRemoteVerificationKeepsTheLocalFileAndCanBeRetried() throws Exception {
        DownloadUploadBridge.rejectVerification = true;
        DownloadArchives.archive(context, "", Uri.fromFile(source), "Original");
        JSONObject row = await("failed");
        assertTrue(source.exists());
        DownloadUploadBridge.rejectVerification = false;
        DownloadArchives.retry(context, row.getString("id"));
        await("archived");
        assertTrue(source.exists());
    }

    @Test
    public void archiveReceiptMismatchNeverDeletesTheLocalFile() throws Exception {
        DownloadArchives.archive(context, "", Uri.fromFile(source), "Original");
        JSONObject row = await("archived");
        DownloadUploadBridge.wrongHash = true;
        DownloadArchives.removeLocal(context, row.getString("id"));
        await("failed");
        assertArrayEquals(original, Files.readAllBytes(source.toPath()));
    }

    @Test
    public void changedLocalFileIsKeptEvenWhenTheRemoteArchiveIsValid() throws Exception {
        DownloadArchives.archive(context, "", Uri.fromFile(source), "Original");
        JSONObject row = await("archived");
        byte[] changed = original.clone();
        changed[700] = 1;
        Files.write(source.toPath(), changed);
        DownloadArchives.removeLocal(context, row.getString("id"));
        await("failed");
        assertArrayEquals(changed, Files.readAllBytes(source.toPath()));
    }

    @Test
    public void batchSubmissionKeepsSuccessfulRowsOutOfRetries() throws Exception {
        List<SoulseekDownloadChoiceTest.Song> songs =
                Arrays.asList(
                        new SoulseekDownloadChoiceTest.Song("Abc_12-Xy9Z"),
                        new SoulseekDownloadChoiceTest.Song("Abc_12-Xy9Y"));
        SoulseekDownloadChoice.offer(
                (kotlin.jvm.functions.Function2<Object, Object, Object>)
                        (a, b) -> kotlin.Unit.INSTANCE,
                songs,
                null);
        Intent launch =
                Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
        String token = launch.getStringExtra(SoulseekDownloadChoice.EXTRA_CHOICE);
        AtomicInteger first = new AtomicInteger(), second = new AtomicInteger();
        DownloadBatch.select(token, 0, "Addon A · FLAC", now -> first.incrementAndGet());
        DownloadBatch.select(
                token,
                1,
                "Soulseek · MP3",
                now -> {
                    if (second.incrementAndGet() == 1) throw new IOException("retry");
                });
        assertEquals(1, DownloadBatch.submit(token, false));
        assertEquals(1, first.get());
        assertFalse(DownloadBatch.choice(token, 1).submitted);
        assertEquals(1, DownloadBatch.submit(token, true));
        assertEquals(1, first.get());
        assertEquals(2, second.get());
        try {
            DownloadBatch.select(token, 0, "replace", now -> {});
            fail("submitted row cannot be replaced");
        } catch (IllegalStateException expected) {
        }
        SoulseekDownloadChoice.forget(token);
    }

    @Test
    public void batchPageRendersReviewWithoutStartingDownloads() throws Exception {
        List<SoulseekDownloadChoiceTest.Song> songs =
                Arrays.asList(
                        new SoulseekDownloadChoiceTest.Song("Abc_12-Xy9Z"),
                        new SoulseekDownloadChoiceTest.Song("Abc_12-Xy9Y"));
        SoulseekDownloadChoice.offer(
                (kotlin.jvm.functions.Function2<Object, Object, Object>)
                        (a, b) -> kotlin.Unit.INSTANCE,
                songs,
                null);
        Intent launch =
                Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
        ActivityController<BatchDownloadActivity> controller =
                Robolectric.buildActivity(BatchDownloadActivity.class, launch).setup();
        try {
            String text = allText(controller.get().findViewById(android.R.id.content));
            assertTrue(text.contains("Batch source selection"));
            assertTrue(text.contains("Download selected now"));
            assertTrue(text.contains("Choose source and quality"));
            assertTrue(SoulseekSelectedDownloads.selectedReferences.isEmpty());
        } finally {
            controller.pause().stop().destroy();
            SoulseekDownloadChoice.forget(
                    launch.getStringExtra(SoulseekDownloadChoice.EXTRA_CHOICE));
        }
    }

    String allText(View view) {
        String text = view instanceof TextView ? ((TextView) view).getText().toString() : "";
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                text += "\n" + allText(group.getChildAt(i));
        }
        return text;
    }
}
