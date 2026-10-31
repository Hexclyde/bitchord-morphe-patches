package app.bitchord.soulseek;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w393dp-h852dp-night-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
public class DownloadViewsTest {
    Activity activity;
    DownloadsHubPage page;
    List<DownloadsHubPage.Row> data;

    @Before
    public void setup() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.getSharedPreferences("bitchord_download_ui", 0).edit().clear().commit();
        page = new DownloadsHubPage(activity, new Intent());
        page.onCreate(new Bundle());
        data = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            data.add(
                    new DownloadsHubPage.Row(
                            "Track " + i,
                            "Soulseek · Downloading · 25%",
                            "Active",
                            "Cancel",
                            () -> {}));
    }

    @After
    public void stop() {
        page.onDestroy();
        activity.finish();
    }

    @Test
    public void allViewsRetainEveryJobAndGridUsesTwoColumns() {
        for (String mode : DownloadsHubPage.VIEW_IDS) {
            page.setViewMode(mode);
            assertEquals(4, page.renderRows(data));
            LinearLayout rows = page.view.findViewWithTag("download-items");
            assertEquals(mode.equals("grid") ? 2 : 4, rows.getChildCount());
            for (int i = 0; i < 4; i++) assertNotNull(rows.findViewWithTag("download-row:" + i));
        }
    }

    @Test
    public void iconCyclesAllFiveModesAndLongPressOpensDirectSelection() {
        View toggle = page.view.findViewWithTag("download-view");
        for (String label : new String[] {"Detailed", "Grid", "List", "Compact", "Comfy"}) {
            toggle.performClick();
            assertTrue(toggle.getContentDescription().toString().contains(label));
        }
        assertTrue(toggle.performLongClick());
        assertNotNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test
    public void selectedViewSurvivesPageRecreationAndRejectsUnknownValues() {
        page.setViewMode("compact");
        page.onDestroy();
        page = new DownloadsHubPage(activity, new Intent());
        page.onCreate(new Bundle());
        assertTrue(
                page.view
                        .findViewWithTag("download-view")
                        .getContentDescription()
                        .toString()
                        .contains("Compact"));
        page.setViewMode("unknown");
        assertEquals(
                "compact",
                activity.getSharedPreferences("bitchord_download_ui", 0).getString("view", ""));
    }

    @Test
    public void rootUsesFileCardsAndKeepsManagementActionsInSettings() {
        assertNull(findDescription(page.view, "Back"));
        assertNotNull(findDescription(page.view, "Download settings"));
        assertNull(findText(page.view, "Copy destinations and Telegram setup"));
        DownloadsHubPage.Row active = data.get(0);
        active.source = "Soulseek";
        active.format = "FLAC";
        active.fraction = .64f;
        page.setViewMode("comfy");
        page.renderRows(Collections.singletonList(active));
        View card = page.view.findViewWithTag("download-row:0");
        assertNotNull(card.findViewWithTag("download-cover"));
        android.widget.ProgressBar bar = card.findViewWithTag("download-progress");
        assertEquals(640, bar.getProgress());
        assertFalse(bar.isIndeterminate());
        assertEquals(3, bar.getLayoutParams().height);
        assertTrue(
                findDescription(card, "Actions for Track 0") instanceof android.widget.ImageButton);
        assertEquals(
                28f,
                ((android.graphics.drawable.GradientDrawable) card.getBackground())
                        .getCornerRadius(),
                0.01f);
        active.total = 28_000_000L;
        page.setViewMode("detailed");
        page.renderRows(Collections.singletonList(active));
        assertNotNull(
                findText(
                        page.view,
                        "Source · Soulseek\nFormat · FLAC\nSize · 28 MB\nCategory · Active"));
    }

    @Test
    public void sharingIsNotPresentedAsAnArtificialDownloadedFile() {
        List<DownloadsHubPage.Row> files = new ArrayList<>(data);
        files.add(
                new DownloadsHubPage.Row(
                        "Public Soulseek sharing", "Paused", "Sharing", null, null));
        assertEquals(4, page.renderRows(files));
        assertNull(
                findText(page.view.findViewWithTag("download-items"), "Public Soulseek sharing"));
    }

    @Test
    public void saveAllFiveProductionViewRenders() throws Exception {
        activity.setContentView(page.view);
        List<DownloadsHubPage.Row> demo = new ArrayList<>();
        String[] titles = {"Midnight Drive", "Open Skies", "Quiet Hours", "Coastline"};
        String[] groups = {"Active", "Queue", "On device", "Telegram"};
        int[] colors = {0xff9da98c, 0xff657c85, 0xffb59b91, 0xffa19e82};
        for (int i = 0; i < 4; i++) {
            DownloadsHubPage.Row row =
                    new DownloadsHubPage.Row(
                            titles[i],
                            i == 0
                                    ? "Downloading · 17.9 / 28 MB"
                                    : i == 1
                                            ? "In download queue · #1"
                                            : i == 2
                                                    ? "On device"
                                                    : "Archived · restore for offline",
                            groups[i],
                            "Details",
                            () -> {});
            row.source = i == 1 ? "Hi-Fi addon" : i == 3 ? "Telegram" : "Soulseek";
            row.format = "FLAC";
            row.total = i == 0 ? 28_000_000L : 31_000_000L;
            row.fraction = i == 0 ? .64f : Float.NaN;
            android.graphics.Bitmap art =
                    android.graphics.Bitmap.createBitmap(
                            160, 160, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas paint = new android.graphics.Canvas(art);
            paint.drawColor(colors[i]);
            android.graphics.Paint ink =
                    new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            ink.setColor(0x55303030);
            ink.setStrokeWidth(2);
            paint.drawLine(36, 0, 144, 160, ink);
            ink.setColor(0xff202820);
            ink.setTextSize(10);
            paint.drawText(titles[i].toUpperCase(java.util.Locale.ROOT), 10, 24, ink);
            java.io.File cover = new java.io.File(activity.getCacheDir(), "cover-" + i + ".png");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(cover)) {
                art.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            }
            row.artwork = android.net.Uri.fromFile(cover).toString();
            demo.add(row);
        }
        java.io.File folder =
                new java.io.File(
                        System.getProperty("basedir", "ui-tests"), "../build/ui-preview-beta16");
        folder.mkdirs();
        for (String mode : DownloadsHubPage.VIEW_IDS) {
            page.setViewMode(mode);
            page.renderRows(demo);
            for (int wait = 0; wait < 10; wait++) {
                Thread.sleep(15);
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            }
            View view = page.view;
            view.measure(
                    View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, 393, 852);
            android.graphics.Bitmap picture =
                    android.graphics.Bitmap.createBitmap(
                            393, 852, android.graphics.Bitmap.Config.ARGB_8888);
            view.draw(new android.graphics.Canvas(picture));
            try (java.io.FileOutputStream out =
                    new java.io.FileOutputStream(
                            new java.io.File(folder, "downloads-" + mode + ".png"))) {
                picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            }
        }
    }

    private static View findDescription(View view, String text) {
        if (text.equals(
                view.getContentDescription() == null
                        ? null
                        : view.getContentDescription().toString())) return view;
        if (view instanceof android.view.ViewGroup)
            for (int i = 0; i < ((android.view.ViewGroup) view).getChildCount(); i++) {
                View found = findDescription(((android.view.ViewGroup) view).getChildAt(i), text);
                if (found != null) return found;
            }
        return null;
    }

    private static View findText(View view, String text) {
        if (view instanceof android.widget.TextView
                && text.contentEquals(((android.widget.TextView) view).getText())) return view;
        if (view instanceof android.view.ViewGroup)
            for (int i = 0; i < ((android.view.ViewGroup) view).getChildCount(); i++) {
                View found = findText(((android.view.ViewGroup) view).getChildAt(i), text);
                if (found != null) return found;
            }
        return null;
    }
}
