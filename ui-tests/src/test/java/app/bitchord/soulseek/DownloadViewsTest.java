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
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w393dp-h852dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class DownloadViewsTest {
    Activity activity;
    DownloadsHubPage page;
    List<DownloadsHubPage.Row> data;
    @Before public void setup() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.getSharedPreferences("bitchord_download_ui", 0).edit().clear().commit();
        page = new DownloadsHubPage(activity, new Intent()); page.onCreate(new Bundle());
        data = new ArrayList<>();
        for (int i = 0; i < 4; i++) data.add(new DownloadsHubPage.Row("Track " + i,
                "Soulseek · Downloading · 25%", "Active", "Cancel", () -> {}));
    }
    @After public void stop() { page.onDestroy(); activity.finish(); }
    @Test public void allViewsRetainEveryJobAndGridUsesTwoColumns() {
        for (String mode : DownloadsHubPage.VIEW_IDS) {
            page.setViewMode(mode); assertEquals(4, page.renderRows(data));
            LinearLayout rows = page.view.findViewWithTag("download-items");
            assertEquals(mode.equals("grid") ? 2 : 4, rows.getChildCount());
            for (int i = 0; i < 4; i++) assertNotNull(rows.findViewWithTag("download-row:" + i));
        }
    }
    @Test public void selectedViewSurvivesPageRecreationAndRejectsUnknownValues() {
        page.setViewMode("compact"); page.onDestroy();
        page = new DownloadsHubPage(activity, new Intent()); page.onCreate(new Bundle());
        assertTrue(((android.widget.Button) page.view.findViewWithTag("download-view")).getText().toString().contains("Compact"));
        page.setViewMode("unknown");
        assertEquals("compact", activity.getSharedPreferences("bitchord_download_ui", 0).getString("view", ""));
    }
}
