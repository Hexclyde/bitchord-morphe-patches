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
