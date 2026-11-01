package app.bitchord.soulseek;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.LinearLayout;

import com.music.bitchord.data.settings.AppSettings;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

import java.util.*;

/** Native presentation tests use controlled source claims, without contacting any service. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w393dp-h852dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DownloadExplorerViewTest {
    ActivityController<Activity> controller;
    Activity activity;
    DownloadExplorerView view;
    LinearLayout group;
    List<String> admissions;
    DownloadExplorerView.Option mp3, flac;

    static class Page extends DownloadPage {
        Page(Activity activity) {
            super(
                    activity,
                    new Intent()
                            .putExtra("download.track.title", "Midnight Drive")
                            .putExtra("download.track.artist", "Example Artist")
                            .putExtra("download.track.duration", "2:42"));
        }
    }

    @Before
    public void open() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        admissions = new ArrayList<>();
        create("DARK", false);
    }

    void create(String theme, boolean batch) {
        AppSettings.themeMode.setValue(theme);
        Page page = new Page(activity);
        view =
                new DownloadExplorerView(
                        page,
                        batch,
                        () -> {},
                        () -> {},
                        (option, now) -> admissions.add(option.source + ":" + now));
        activity.setContentView(view.root);
        group = new LinearLayout(page);
        group.setOrientation(LinearLayout.VERTICAL);
        view.results.addView(group);
        mp3 =
                new DownloadExplorerView.Option(
                        "Audio addon",
                        "Midnight Drive",
                        "Example Artist",
                        "MP3 · 320 kbps (reported)",
                        "Availability not reported",
                        false,
                        null,
                        now -> {},
                        null);
        flac =
                new DownloadExplorerView.Option(
                        "Soulseek",
                        "Midnight Drive",
                        "Example Artist",
                        "FLAC (reported)",
                        "28 MB · example-peer",
                        true,
                        true,
                        now -> {},
                        null);
        view.add(group, mp3);
        view.add(group, flac);
    }

    @After
    public void close() {
        AppSettings.themeMode.setValue("SYSTEM");
        controller.pause().stop().destroy();
    }

    @Test
    public void tappingTheTrackTextSelectsItsCardWithoutSubmittingADownload() {
        View root = view.root;
        root.measure(
                View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 393, 852);
        View title = root.findViewWithTag("explorer-track-title");
        android.graphics.Rect bounds = new android.graphics.Rect();
        assertTrue(title.getGlobalVisibleRect(bounds));
        int[] offset = new int[2];
        root.getLocationOnScreen(offset);
        float x = bounds.exactCenterX() - offset[0], y = bounds.exactCenterY() - offset[1];
        long when = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down =
                android.view.MotionEvent.obtain(
                        when, when, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        android.view.MotionEvent up =
                android.view.MotionEvent.obtain(
                        when, when + 20, android.view.MotionEvent.ACTION_UP, x, y, 0);
        try {
            root.dispatchTouchEvent(down);
            root.dispatchTouchEvent(up);
        } finally {
            down.recycle();
            up.recycle();
        }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue(mp3.radio.isChecked());
        assertTrue(view.now.isEnabled());
        assertTrue(admissions.isEmpty());
    }

    @Test
    public void individualSourceCardsShowARealFormatBadgeAndEvidence() {
        view.reset();
        LinearLayout source = new LinearLayout(activity);
        view.results.addView(source);
        DownloadExplorerView.Option option =
                new DownloadExplorerView.Option(
                                "One provider",
                                "Song",
                                "Artist",
                                "FLAC 24-bit / 192 kHz · requested, availability unverified",
                                "3:25",
                                true,
                                null,
                                now -> {},
                                null)
                        .fromFamily("addons", -1, true);
        view.add(source, option);
        android.widget.TextView track = option.card.findViewWithTag("explorer-track-title");
        android.widget.TextView badge = option.card.findViewWithTag("explorer-format-badge");
        android.widget.TextView format = option.card.findViewWithTag("explorer-track-format");
        assertEquals("Song · Artist", track.getText().toString());
        assertEquals("FLAC", badge.getText().toString());
        assertEquals("24-bit / 192 kHz", format.getText().toString());
        assertEquals(View.GONE, source.getVisibility());
        assertEquals(View.GONE, option.infoAction.getVisibility());
        option.card.performClick();
        assertEquals(View.VISIBLE, option.infoAction.getVisibility());
        assertTrue(admissions.isEmpty());
    }

    @Test
    public void familyFilteringAndSortingPreserveTheQueryAndDoNotAdmitFiles() {
        mp3.fromFamily("addons", 100, false);
        flac.fromFamily("soulseek", 1000, false);
        // A new result refreshes the actual family catalogue without backend calls.
        DownloadExplorerView.Option high =
                new DownloadExplorerView.Option(
                                "Hi-res addon",
                                "Song",
                                "Artist",
                                "FLAC 24-bit / 192 kHz",
                                "",
                                true,
                                null,
                                now -> {},
                                null)
                        .fromFamily("addons", 2000, false);
        view.add(group, high);
        view.showSearch();
        view.query.setText("My artist and title");
        view.query.requestFocus();
        android.widget.Spinner family = view.root.findViewWithTag("explorer-source-family");
        family.setSelection(2);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(View.VISIBLE, flac.card.getVisibility());
        assertEquals(View.GONE, mp3.card.getVisibility());
        assertEquals(
                "1 result",
                ((android.widget.TextView) view.root.findViewWithTag("explorer-result-count"))
                        .getText()
                        .toString());
        family.setSelection(0);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals("My artist and title", view.query.getText().toString());
        assertTrue(view.query.hasFocus());
        findDescription(view.root, "Sort source results").performClick();
        Shadows.shadowOf(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
                .clickOnItem(1);
        assertTrue(view.results.indexOfChild(high.card) < view.results.indexOfChild(flac.card));
        assertTrue(view.results.indexOfChild(flac.card) < view.results.indexOfChild(mp3.card));
        assertTrue(admissions.isEmpty());
    }

    static View findDescription(View view, String description) {
        if (description.equals(view.getContentDescription())) return view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findDescription(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    public void selectionDoesNotDownloadUntilAnAdmissionAction() {
        assertFalse(view.now.isEnabled());
        assertFalse(view.queue.isEnabled());
        assertNull(view.footer.getParent());
        flac.card.performClick();
        assertSame(flac.card, view.footer.getParent());
        assertTrue(flac.radio.isChecked());
        assertFalse(mp3.radio.isChecked());
        assertTrue(view.now.isEnabled());
        assertTrue(admissions.isEmpty());
        view.queue.performClick();
        view.now.performClick();
        assertEquals(Arrays.asList("Soulseek:false", "Soulseek:true"), admissions);
    }

    @Test
    public void losslessFilterClearsAHiddenSelection() {
        mp3.card.performClick();
        view.root.findViewWithTag("explorer-filter:lossless").performClick();
        assertEquals(View.GONE, mp3.card.getVisibility());
        assertEquals(View.VISIBLE, flac.card.getVisibility());
        assertFalse(view.now.isEnabled());
        assertFalse(mp3.radio.isChecked());
        assertTrue(admissions.isEmpty());
    }

    @Test
    public void readinessFilterHidesEmptySourceGroupsAndAllRestoresTheirDiagnostics() {
        LinearLayout unavailable = new LinearLayout(activity);
        android.widget.TextView message = new android.widget.TextView(activity);
        message.setText("No matches from this source");
        unavailable.addView(message);
        view.results.addView(unavailable);
        view.root.findViewWithTag("explorer-filter:available").performClick();
        assertEquals(View.GONE, unavailable.getVisibility());
        view.root.findViewWithTag("explorer-filter:all").performClick();
        assertEquals(View.GONE, unavailable.getVisibility());
        view.reset();
        view.results.addView(unavailable);
        view.searching(true);
        view.searching(false);
        assertEquals(View.VISIBLE, unavailable.getVisibility());
        assertTrue(admissions.isEmpty());
    }

    @Test
    public void availableFilterExcludesUnknownAvailability() {
        view.root.findViewWithTag("explorer-filter:available").performClick();
        assertEquals(View.GONE, mp3.card.getVisibility());
        assertEquals(View.VISIBLE, flac.card.getVisibility());
        assertTrue(flac.card.createAccessibilityNodeInfo().isCheckable());
        flac.card.performClick();
        assertTrue(flac.card.createAccessibilityNodeInfo().isChecked());
    }

    @Test
    public void searchingAgainClearsSelectionAndDisablesAdmission() {
        flac.card.performClick();
        view.reset();
        assertEquals(0, view.results.getChildCount());
        assertFalse(view.now.isEnabled());
        assertFalse(view.queue.isEnabled());
    }

    @Test
    public void pendingSubmissionCannotBeSubmittedTwice() {
        flac.card.performClick();
        view.busy(true);
        view.now.performClick();
        assertTrue(admissions.isEmpty());
        assertFalse(view.now.isEnabled());
        view.busy(false);
        assertTrue(view.now.isEnabled());
    }

    @Test
    public void batchUsesTheTrackChoiceActionWithoutTheQueueButton() {
        create("LIGHT", true);
        assertEquals("Use for this track", view.now.getText().toString());
        assertNull(view.queue.getParent());
        mp3.card.performClick();
        view.now.performClick();
        assertEquals(Collections.singletonList("Audio addon:true"), admissions);
    }

    @Test
    public void selectingAnotherCardMovesActionsAndLeavesNoGlobalFooter() {
        flac.card.performClick();
        assertSame(flac.card, view.footer.getParent());
        mp3.card.performClick();
        assertSame(mp3.card, view.footer.getParent());
        assertEquals("Now", view.now.getText().toString());
        assertEquals("Later", view.queue.getText().toString());
        assertTrue(admissions.isEmpty());
    }

    @Test
    public void unknownOnlyResultsDoNotShowTheAvailabilityFilter() {
        view.reset();
        LinearLayout group = new LinearLayout(activity);
        view.results.addView(group);
        view.add(
                group,
                new DownloadExplorerView.Option(
                        "Addon",
                        "Song",
                        "Artist",
                        "FLAC",
                        "Unverified",
                        true,
                        null,
                        now -> {},
                        null));
        assertEquals(
                View.GONE, view.root.findViewWithTag("explorer-filter:available").getVisibility());
        assertEquals(
                View.VISIBLE,
                view.root.findViewWithTag("explorer-filter:lossless").getVisibility());
    }

    @Test
    public void radioStaysInTheTopEndCornerWhenSourceTitleWraps() {
        view.reset();
        LinearLayout group = new LinearLayout(activity);
        group.setOrientation(LinearLayout.VERTICAL);
        view.results.addView(group);
        DownloadExplorerView.Option option =
                new DownloadExplorerView.Option(
                        "A source with a very long provider name that wraps onto several lines",
                        "Song",
                        "Artist",
                        "FLAC",
                        "",
                        true,
                        null,
                        now -> {},
                        null);
        view.add(group, option);
        view.root.measure(
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        view.root.layout(0, 0, 320, 640);
        android.widget.FrameLayout.LayoutParams placement =
                (android.widget.FrameLayout.LayoutParams) option.radio.getLayoutParams();
        assertEquals(android.view.Gravity.TOP | android.view.Gravity.END, placement.gravity);
        int[] card = new int[2], radio = new int[2];
        option.card.getLocationOnScreen(card);
        option.radio.getLocationOnScreen(radio);
        assertTrue(radio[0] + option.radio.getWidth() / 2 > card[0] + option.card.getWidth() - 32);
        assertTrue(radio[1] + option.radio.getHeight() / 2 < card[1] + 32);
    }

    void render(String name, int width, int height) throws Exception {
        view.root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.root.layout(0, 0, width, height);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        view.root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.root.layout(0, 0, width, height);
        assertSame(flac.card, view.footer.getParent());
        assertSame(view.queue.getParent(), view.now.getParent());
        assertTrue(view.now.getHeight() >= 48);
        assertEquals(view.now.getTop(), view.queue.getTop());
        assertNotNull(view.now.getCompoundDrawablesRelative()[0]);
        assertNotNull(view.queue.getCompoundDrawablesRelative()[0]);
        for (DownloadExplorerView.Option option : new DownloadExplorerView.Option[] {mp3, flac})
            option.radio.jumpDrawablesToCurrentState();
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        view.root.draw(new Canvas(image));
        java.io.File output = new java.io.File("target/previews/explorer-" + name + ".png");
        output.getParentFile().mkdirs();
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(output)) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
    }

    @Test
    public void selectedCardActionsRenderInLightAndDarkThemes() throws Exception {
        for (String theme : new String[] {"LIGHT", "DARK"}) {
            create(theme, false);
            flac.card.performClick();
            render(theme.toLowerCase(Locale.ROOT), 393, 696);
        }
    }

    @Test
    public void largeTextKeepsInlineActionsInTheScrollableCard() throws Exception {
        android.content.res.Configuration config =
                new android.content.res.Configuration(activity.getResources().getConfiguration());
        config.fontScale = 1.5f;
        activity.getResources()
                .updateConfiguration(config, activity.getResources().getDisplayMetrics());
        create("DARK", false);
        flac.card.performClick();
        render("large-text", 320, 640);
    }
}
