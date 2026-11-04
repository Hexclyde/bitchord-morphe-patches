package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.app.Activity;

import androidx.compose.runtime.ParcelableSnapshotMutableState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.time.Duration;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class VideoFullscreenTest {
    @Before
    public void fresh() {
        VideoSwitch.Fullscreen.requested(null, false);
        VideoSwitch.Fullscreen.reset();
        VideoSwitch.Fullscreen.show(false);
        ShadowLooper.idleMainLooper(
                Duration.ofSeconds(2).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static void settled() {
        ShadowLooper.idleMainLooper(
                VideoSwitch.Fullscreen.SETTLE_MS + 50, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Test
    public void ignoredLandscapeRequestIsHeldAndComposeIsTold() {
        Activity window = Robolectric.buildActivity(Activity.class).setup().get();
        int reads = ParcelableSnapshotMutableState.reads;
        assertFalse(VideoSwitch.Fullscreen.show(false));
        assertTrue("the gate reads Compose state", ParcelableSnapshotMutableState.reads > reads);
        int writes = ParcelableSnapshotMutableState.writes;
        VideoSwitch.Fullscreen.requested(window, true);
        assertFalse(
                "nothing is decided before the window has had its say",
                VideoSwitch.Fullscreen.show(false));
        settled();
        assertTrue(
                "the window did not turn, so fullscreen is held",
                VideoSwitch.Fullscreen.show(false));
        assertTrue(
                "and Compose is told to read again",
                ParcelableSnapshotMutableState.writes > writes);
    }

    @Test
    public void theHandlersActionLeavesAHeldFullscreenAndComposeIsTold() {
        Activity window = Robolectric.buildActivity(Activity.class).setup().get();
        VideoSwitch.Fullscreen.show(false);
        VideoSwitch.Fullscreen.requested(window, true);
        settled();
        assertTrue(VideoSwitch.Fullscreen.show(false));
        int writes = ParcelableSnapshotMutableState.writes;
        VideoSwitch.Fullscreen.LEAVE.invoke();
        assertTrue(ParcelableSnapshotMutableState.writes > writes);
        assertFalse(VideoSwitch.Fullscreen.show(false));
    }

    @Test
    public void exitIgnoredInALandscapeWindowShowsTheLandscapeLayout() {
        Activity window = Robolectric.buildActivity(Activity.class).setup().get();
        assertTrue(VideoSwitch.Fullscreen.show(true));
        VideoSwitch.Fullscreen.requested(window, false);
        settled();
        assertFalse(VideoSwitch.Fullscreen.show(true));
        assertFalse("a rotation hands the choice back", VideoSwitch.Fullscreen.show(false));
        assertTrue(VideoSwitch.Fullscreen.show(true));
    }

    @Test
    public void anotherWindowAndAFinishedWindowDropTheHeldState() {
        Activity first = Robolectric.buildActivity(Activity.class).setup().get();
        VideoSwitch.Fullscreen.show(false);
        VideoSwitch.Fullscreen.requested(first, true);
        settled();
        assertTrue(VideoSwitch.Fullscreen.show(false));
        Activity second = Robolectric.buildActivity(Activity.class).setup().get();
        VideoSwitch.Fullscreen.requested(second, false);
        assertFalse("a new window starts from the host", VideoSwitch.Fullscreen.show(false));
        VideoSwitch.Fullscreen.requested(second, true);
        settled();
        VideoSwitch.Fullscreen.reset();
        assertFalse("a new track clears it", VideoSwitch.Fullscreen.show(false));
    }

    @Test
    public void aRequestWithoutAWindowChangesNothing() {
        VideoSwitch.Fullscreen.requested(null, true);
        settled();
        assertTrue("held against no window", VideoSwitch.Fullscreen.show(false));
        VideoSwitch.Fullscreen.reset();
        assertFalse(VideoSwitch.Fullscreen.show(false));
    }
}
