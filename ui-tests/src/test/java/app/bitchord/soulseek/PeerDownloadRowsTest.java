package app.bitchord.soulseek;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.res.Configuration;
import android.view.View;
import android.widget.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class PeerDownloadRowsTest {
    @Test
    public void taskNavigationShowsOneWorkflowAndPreservesItsRows() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout receive = new LinearLayout(activity),
                share = new LinearLayout(activity),
                transfers = new LinearLayout(activity);
        PeerDownloadRows rows = new PeerDownloadRows(new DownloadStyle(activity));
        rows.begin(transfers);
        LinearLayout transfer = rows.row(transfers, "track");
        TextView progress = rows.text(transfer, "Downloading 20%", 14);
        rows.end(transfers);
        PeerDownloadRows.Tasks tasks =
                new PeerDownloadRows.Tasks(
                        new DownloadStyle(activity),
                        new String[] {"Receive", "Share", "Transfers"},
                        new View[] {receive, share, transfers},
                        0);
        body.addView(tasks.view);
        body.addView(receive);
        body.addView(share);
        body.addView(transfers);
        activity.setContentView(body);
        assertEquals(View.VISIBLE, receive.getVisibility());
        assertEquals(View.GONE, share.getVisibility());
        assertEquals(View.GONE, transfers.getVisibility());
        assertTrue(tasks.buttons[0].isSelected());
        tasks.buttons[2].performClick();
        assertEquals(2, tasks.selected());
        assertFalse(tasks.buttons[0].isSelected());
        assertTrue(tasks.buttons[2].isSelected());
        assertEquals(View.GONE, receive.getVisibility());
        assertEquals(View.VISIBLE, transfers.getVisibility());
        rows.begin(transfers);
        assertSame(transfer, rows.row(transfers, "track"));
        assertSame(progress, rows.text(transfer, "Downloading 30%", 14));
        rows.end(transfers);
        tasks.buttons[1].performClick();
        tasks.buttons[2].performClick();
        assertSame(transfer, transfers.getChildAt(0));
        assertSame(progress, transfer.getChildAt(0));
        activity.finish();
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-night-mdpi")
    public void largeTextUsesFullWidthTaskControlsWithoutClipping() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Configuration original = new Configuration(activity.getResources().getConfiguration());
        Configuration scaled = new Configuration(original);
        scaled.fontScale = 2;
        activity.getResources()
                .updateConfiguration(scaled, activity.getResources().getDisplayMetrics());
        try {
            View[] pages = {
                new LinearLayout(activity), new LinearLayout(activity), new LinearLayout(activity)
            };
            PeerDownloadRows.Tasks tasks =
                    new PeerDownloadRows.Tasks(
                            new DownloadStyle(activity),
                            new String[] {
                                "Receive audio files", "Share selected audio", "Current transfers"
                            },
                            pages,
                            2);
            activity.setContentView(tasks.view);
            tasks.view.measure(
                    View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            tasks.view.layout(0, 0, 280, tasks.view.getMeasuredHeight());
            assertEquals(LinearLayout.VERTICAL, tasks.view.getOrientation());
            for (Button button : tasks.buttons) {
                assertTrue(button.getHeight() >= 48);
                assertTrue(button.getWidth() >= 250);
                assertEquals(button.getText().toString(), button.getContentDescription());
                assertNotNull(button.getLayout());
                for (int line = 0; line < button.getLineCount(); line++)
                    assertEquals(0, button.getLayout().getEllipsisCount(line));
                assertTrue(
                        button.getHeight()
                                >= button.getLayout().getHeight()
                                        + button.getCompoundPaddingTop()
                                        + button.getCompoundPaddingBottom());
            }
            assertTrue(tasks.buttons[2].isSelected());
            assertEquals(View.VISIBLE, pages[2].getVisibility());
        } finally {
            activity.getResources()
                    .updateConfiguration(original, activity.getResources().getDisplayMetrics());
            activity.finish();
        }
    }

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
