package app.bitchord.soulseek;

import static org.junit.Assert.*;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.util.function.BooleanSupplier;

@RunWith(RobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 35,
        qualifiers = "w393dp-h852dp-mdpi",
        shadows = StreamseekPreviewTest.Installed.class,
        instrumentedPackages = "app.bitchord.soulseek.SoulseekPreview")
@LooperMode(LooperMode.Mode.PAUSED)
public class InlinePreviewTest {
    Activity activity;
    SoulseekBridge bridge;
    LinearLayout host;
    InlinePreview view;
    FakePlayback player;

    static final class FakePlayback implements InlinePreview.Playback {
        boolean playing = true, closed;
        long position;

        public boolean playing() {
            return playing;
        }

        public long position() {
            return position;
        }

        public void pause() {
            playing = false;
        }

        public void resume() {
            playing = true;
        }

        public String format() {
            return "Decoded: FLAC · 96000 Hz · 2 channels";
        }

        public String error() {
            return "";
        }

        public void close() {
            closed = true;
            playing = false;
        }
    }

    @Before
    public void setup() throws Exception {
        activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        SoulseekPreview.stopAll();
        DownloadSettings.reset();
        bridge = SoulseekBridge.reset(activity);
        bridge.previewFile = new File(activity.getCacheDir(), "inline-fixture.part");
        Files.write(bridge.previewFile.toPath(), new byte[] {1, 2, 3, 4});
        bridge.previewDownloaded = 4;
        bridge.previewComplete = true;
        SoulseekPreview.setEnabled(activity, true);
        SoulseekSelectedDownloads.selectedReferences.clear();
        SoulseekSelectedDownloads.previewAdmissions = 0;
        host = new LinearLayout(activity);
        host.setOrientation(LinearLayout.VERTICAL);
        activity.setContentView(host);
        view = add("one.flac");
    }

    InlinePreview add(String path) throws Exception {
        SoulseekTrack track =
                new SoulseekTrack(
                        new JSONObject()
                                .put("peer", "fixture-peer")
                                .put("path", path)
                                .put("size", 4));
        bridge.searchRows.add(track);
        InlinePreview result =
                new InlinePreview(
                        activity,
                        track,
                        bridge.sessionTag,
                        (owner, session) -> player = new FakePlayback());
        host.addView(result);
        return result;
    }

    void waitFor(BooleanSupplier condition) throws Exception {
        for (int n = 0; n < 300; n++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        fail("Inline preview did not settle");
    }

    void start() throws Exception {
        view.findViewWithTag("preview-play").performClick();
        waitFor(() -> player != null && view.findViewWithTag("preview-play").isEnabled());
    }

    @After
    public void close() {
        host.removeAllViews();
        SoulseekPreview.stopAll();
        activity.finish();
    }

    @Test
    public void renderingDoesNotStartAudioOrLeaveTheResults() {
        assertEquals(0, bridge.previewStarts);
        assertNull(player);
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        assertFalse(view.findViewWithTag("preview-stop").isEnabled());
    }

    @Test
    public void explicitPlayPauseResumeAndLimitStayInsideTheCard() throws Exception {
        start();
        assertEquals(1, bridge.previewStarts);
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        player.position = 12500;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        assertEquals(
                "0:12 / 0:30",
                ((TextView) view.findViewWithTag("preview-time")).getText().toString());
        view.findViewWithTag("preview-play").performClick();
        assertFalse(player.playing);
        view.findViewWithTag("preview-play").performClick();
        assertTrue(player.playing);
        player.position = 30000;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        assertFalse(player.playing);
        assertEquals(
                "30-second preview complete",
                ((TextView) view.findViewWithTag("preview-state")).getText().toString());
    }

    @Test
    public void hidingTheCardAndDisablingTheFeatureReleaseItsSession() throws Exception {
        start();
        FakePlayback original = player;
        view.setVisibility(View.GONE);
        assertTrue(original.closed);
        assertEquals(0, SoulseekPreview.activeCount());
        assertEquals(0, bridge.previewReaders);
        view.setVisibility(View.VISIBLE);
        player = null;
        start();
        SoulseekPreview.setEnabled(activity, false);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        assertTrue(player.closed);
        assertEquals(0, bridge.previewReaders);
    }

    @Test
    public void switchingResultsKeepsOnlyOnePreviewOwner() throws Exception {
        start();
        FakePlayback first = player;
        InlinePreview second = add("two.flac");
        second.findViewWithTag("preview-play").performClick();
        waitFor(
                () ->
                        first.closed
                                && player != first
                                && second.findViewWithTag("preview-play").isEnabled());
        assertEquals(1, SoulseekPreview.activeCount());
        assertEquals(1, bridge.previewReaders);
        host.removeAllViews();
        assertEquals(0, SoulseekPreview.activeCount());
        assertEquals(0, bridge.previewReaders);
    }

    @Test
    public void downloadingTheSameResultUsesPreviewRetention() throws Exception {
        start();
        SoulseekTrack track = bridge.searchRows.get(0);
        assertTrue(SoulseekPreview.enqueueSelected(activity, track, bridge.sessionTag, true));
        assertEquals(1, SoulseekSelectedDownloads.previewAdmissions);
        assertEquals(1, SoulseekSelectedDownloads.selectedReferences.size());
        assertEquals(1, bridge.previewStarts);
    }

    @Test
    public void rapidSelectionChangesCancelPendingCreationWithoutStartingAnOldPlayer()
            throws Exception {
        view.findViewWithTag("preview-play").performClick();
        InlinePreview second = add("rapid-second.flac");
        second.findViewWithTag("preview-play").performClick();
        waitFor(() -> player != null && second.findViewWithTag("preview-play").isEnabled());
        assertEquals(1, SoulseekPreview.activeCount());
        assertEquals(1, bridge.previewReaders);
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        host.removeAllViews();
        assertEquals(0, bridge.previewReaders);
    }
}
