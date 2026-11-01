package app.bitchord.soulseek;

import static org.junit.Assert.*;

import android.app.Activity;
import android.widget.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class PeerDownloadRowsTest {
    @Test
    public void repeatedProgressKeepsRowsControlsAndFocus() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout section = new LinearLayout(activity);
        activity.setContentView(section);
        PeerDownloadRows rows = new PeerDownloadRows(new DownloadStyle(activity));
        rows.begin(section);
        LinearLayout first = rows.row(section, "transfer-1");
        TextView label = rows.text(first, "Downloading 1%", 14);
        rows.button(first, "Pause", () -> {});
        rows.end(section);
        Object button = first.getChildAt(1);
        first.getChildAt(1).setFocusableInTouchMode(true);
        first.getChildAt(1).requestFocus();
        for (int i = 2; i <= 100; i++) {
            rows.begin(section);
            assertSame(first, rows.row(section, "transfer-1"));
            assertSame(label, rows.text(first, "Downloading " + i + "%", 14));
            rows.button(first, "Pause", () -> {});
            rows.end(section);
            assertSame(button, first.getChildAt(1));
            assertTrue(first.getChildAt(1).hasFocus());
        }
        assertEquals(1, section.getChildCount());
        activity.finish();
    }

    @Test
    public void removingOneTransferDoesNotRetargetOtherControls() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout section = new LinearLayout(activity);
        PeerDownloadRows rows = new PeerDownloadRows(new DownloadStyle(activity));
        rows.begin(section);
        rows.text(rows.row(section, "one"), "One", 14);
        LinearLayout second = rows.row(section, "two");
        rows.text(second, "Two", 14);
        rows.end(section);
        rows.begin(section);
        assertSame(second, rows.row(section, "two"));
        rows.text(second, "Two updated", 14);
        rows.end(section);
        assertEquals(1, section.getChildCount());
        assertSame(second, section.getChildAt(0));
        activity.finish();
    }
}
