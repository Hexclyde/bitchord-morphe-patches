package app.bitchord.soulseek;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;

import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BooleanSupplier;

/** The chosen supported Soulseek offer gets the Explorer's inline preview inside its card. */
@RunWith(RobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 35,
        qualifiers = "w393dp-h852dp-mdpi",
        shadows = StreamseekPreviewTest.Installed.class,
        instrumentedPackages = "app.bitchord.soulseek.SoulseekPreview")
@LooperMode(LooperMode.Mode.PAUSED)
public class BatchDownloadPreviewTest {
    final String token = "preview-batch";
    ActivityController<Activity> controller;
    Activity activity;
    SoulseekBridge bridge;
    BatchDownloadPage page;
    BatchDownloadPageTest.Fake fake;
    InlinePreviewTest.FakePlayback player;
    SoulseekTrack flac, opus;
    Locale before;

    @Before
    public void open() throws Exception {
        before = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        SoulseekPreview.stopAll();
        DownloadSettings.reset();
        bridge = SoulseekBridge.reset(activity);
        bridge.previewFile = new File(activity.getCacheDir(), "batch-fixture.part");
        Files.write(bridge.previewFile.toPath(), new byte[] {1, 2, 3, 4});
        bridge.previewDownloaded = 4;
        bridge.previewComplete = true;
        SoulseekPreview.setEnabled(activity, true);
        SoulseekSelectedDownloads.selectedReferences.clear();
        BatchDownloadPageTest.offer(token);
        flac = track("Second.flac");
        opus = track("Second b.opus");
        bridge.searchRows.add(flac);
        bridge.searchRows.add(opus);
        build();
    }

    SoulseekTrack track(String name) throws Exception {
        return new SoulseekTrack(
                new JSONObject()
                        .put("peer", "fixture-peer")
                        .put("path", "Second artist - " + name)
                        .put("size", 4)
                        .put("duration", 301));
    }

    void build() throws Exception {
        page =
                new BatchDownloadPage(
                        activity,
                        new Intent().putExtra(SoulseekDownloadChoice.EXTRA_CHOICE, token));
        page.onCreate(null);
        activity.setContentView(page.view);
        fake = new BatchDownloadPageTest.Fake();
        fake.soulseek = true;
        fake.result = DownloadBatch.SoulseekResult.of(bridge, Arrays.asList(flac, opus));
        page.searcher = fake;
        page.previewFactory = (owner, session) -> player = new InlinePreviewTest.FakePlayback();
        Field foreground = BatchDownloadPage.class.getDeclaredField("foreground");
        foreground.setAccessible(true);
        foreground.setBoolean(page, true);
        java.lang.reflect.Method method = BatchDownloadPage.class.getDeclaredMethod("render");
        method.setAccessible(true);
        method.invoke(page);
    }

    @After
    public void close() {
        if (page != null) page.onDestroy();
        SoulseekPreview.stopAll();
        SoulseekDownloadChoice.forget(token);
        DownloadBatch.forget(token);
        controller.pause().stop().destroy();
        Locale.setDefault(before);
    }

    View tag(String exact) {
        return page.view.findViewWithTag(exact);
    }

    void waitFor(BooleanSupplier condition) throws Exception {
        for (int n = 0; n < 300; n++) {
            shadowOf(Looper.getMainLooper()).idle();
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        fail("page did not settle");
    }

    void choose(SoulseekTrack file) throws Exception {
        if (tag("batch-toggle:1") != null && tag("batch-choose:1:soulseek\u0000" + file.id) == null)
            tag("batch-toggle:1").performClick();
        waitFor(() -> tag("batch-choose:1:soulseek\u0000" + file.id) != null);
        tag("batch-choose:1:soulseek\u0000" + file.id).performClick();
    }

    ViewGroup slot() {
        return (ViewGroup) tag("batch-preview:1");
    }

    @Test
    public void chosenSupportedOfferShowsOnePreviewThatNeverStartsItself() throws Exception {
        choose(flac);
        assertEquals(1, slot().getChildCount());
        assertNotNull(slot().findViewWithTag("inline-preview:" + flac.id));
        assertEquals(0, bridge.previewStarts);
        assertNull(player);
        assertNull(shadowOf(activity).getNextStartedActivity());
        assertTrue(SoulseekSelectedDownloads.selectedReferences.isEmpty());
        assertFalse(slot().findViewWithTag("preview-stop").isEnabled());
    }

    @Test
    public void unsupportedContainerAndUnchosenOffersGetNoPreview() throws Exception {
        choose(opus);
        assertEquals(0, slot().getChildCount());
        tag("batch-unselect:1").performClick();
        assertEquals(0, slot().getChildCount());
        choose(flac);
        assertEquals(1, slot().getChildCount());
    }

    @Test
    public void choosingAnotherOfferSwitchesTheViewAndAFilterKeepsIt() throws Exception {
        fake.soulseek = true;
        SoulseekTrack other = track("Second c.flac");
        bridge.searchRows.add(other);
        fake.result = DownloadBatch.SoulseekResult.of(bridge, Arrays.asList(flac, opus, other));
        choose(flac);
        View first = slot().getChildAt(0);
        waitFor(() -> tagged("batch-filter:1:all") != null);
        tag("batch-filter:1:all").performClick();
        assertSame("filters leave the preview alone", first, slot().getChildAt(0));
        tag("batch-choose:1:soulseek\u0000" + other.id).performClick();
        assertEquals(1, slot().getChildCount());
        assertNotSame(first, slot().getChildAt(0));
        assertNotNull(slot().findViewWithTag("inline-preview:" + other.id));
    }

    View tagged(String exact) {
        return tag(exact);
    }

    @Test
    public void collapsingStopsAndRemovesAStartedPreview() throws Exception {
        choose(flac);
        slot().findViewWithTag("preview-play").performClick();
        waitFor(() -> player != null);
        InlinePreviewTest.FakePlayback started = player;
        tag("batch-toggle:1").performClick();
        assertTrue(started.closed);
        assertEquals(0, SoulseekPreview.activeCount());
        assertEquals(0, slot().getChildCount());
        assertEquals(View.GONE, tag("batch-candidates:1").getVisibility());
    }

    @Test
    public void pageStopReleasesAnActivePreview() throws Exception {
        choose(flac);
        slot().findViewWithTag("preview-play").performClick();
        waitFor(() -> player != null);
        InlinePreviewTest.FakePlayback started = player;
        page.onStop();
        assertTrue(started.closed);
        assertEquals(0, SoulseekPreview.activeCount());
    }

    @Test
    public void endedSessionOrDisabledFeatureRemovesThePreview() throws Exception {
        choose(flac);
        assertEquals(1, slot().getChildCount());
        bridge.sessionTag = "another-session";
        tag("batch-toggle:1").performClick();
        tag("batch-toggle:1").performClick();
        assertEquals(0, slot().getChildCount());
        assertTrue(((CompoundButton) tag("batch-choose:1:soulseek\u0000" + flac.id)).isChecked());
        assertFalse(tag("batch-choose:1:soulseek\u0000" + flac.id).isEnabled());
    }

    @Test
    public void disabledFeatureShowsNoPreview() throws Exception {
        SoulseekPreview.setEnabled(activity, false);
        choose(flac);
        assertEquals(0, slot().getChildCount());
        assertNotNull(DownloadBatch.choice(token, 1));
    }
}
