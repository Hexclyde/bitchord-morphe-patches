package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.app.Activity;
import android.os.Looper;

import androidx.compose.runtime.ComposerImpl;
import androidx.compose.runtime.ParcelableSnapshotMutableState;

import kotlin.Unit;
import kotlin.jvm.functions.Function0;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

@RunWith(RobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = {26, 35},
        instrumentedPackages = "app.bitchord.telegram.VideoSwitch",
        shadows = VideoFullscreenOverlayTest.Back.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class VideoFullscreenOverlayTest {
    static boolean registered;
    static Function0<Unit> callback;

    @Implements(value = VideoSwitch.Fullscreen.class, isInAndroidSdk = false)
    public static class Back {
        @Implementation
        public static void hostBack(
                boolean enabled,
                Function0<Unit> action,
                ComposerImpl composer,
                int changed,
                int flags) {
            registered = enabled;
            callback = action;
        }
    }

    Activity window;
    ComposerImpl composer;

    @Before
    public void before() {
        registered = false;
        callback = null;
        VideoSwitch.Fullscreen.reset();
        VideoSwitch.Fullscreen.show(false);
        window = Robolectric.buildActivity(Activity.class).setup().get();
        composer = new ComposerImpl(window, null);
        VideoSwitch.Fullscreen.requested(window, true);
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(VideoSwitch.Fullscreen.SETTLE_MS + 50, TimeUnit.MILLISECONDS);
        assertTrue(VideoSwitch.Fullscreen.show(false));
    }

    @After
    public void after() {
        VideoSwitch.Fullscreen.reset();
        window.finish();
    }

    void bind(Function0<Boolean> overlay) {
        VideoSwitch.Fullscreen.bind(overlay, composer);
    }

    @Test
    public void sameWindowNativeDrawerReceivesBackBeforeHeldFullscreen() throws Exception {
        AtomicBoolean drawer = new AtomicBoolean(true);
        bind(drawer::get);
        assertFalse("The fullscreen callback must yield while a native drawer is open", registered);
        if (registered) callback.invoke();
        else drawer.set(false);
        assertFalse(drawer.get());
        assertTrue("Closing a drawer must retain fullscreen", VideoSwitch.Fullscreen.show(false));
        bind(drawer::get);
        assertTrue(registered);
        callback.invoke();
        assertFalse(VideoSwitch.Fullscreen.show(false));
    }

    @Test
    public void bindingReadsNativeOverlayStateEveryComposition() throws Exception {
        AtomicBoolean drawer = new AtomicBoolean(false);
        AtomicInteger reads = new AtomicInteger();
        Function0<Boolean> overlay =
                () -> {
                    reads.incrementAndGet();
                    return drawer.get();
                };
        bind(overlay);
        assertTrue(registered);
        drawer.set(true);
        bind(overlay);
        assertFalse(registered);
        assertEquals(2, reads.get());
    }

    @Test
    public void bindingTracksRevisionForItsOwnFrameScope() throws Exception {
        int reads = ParcelableSnapshotMutableState.reads;
        bind(() -> false);
        assertTrue(
                "The frame observes fullscreen state",
                ParcelableSnapshotMutableState.reads > reads);
        VideoSwitch.Fullscreen.reset();
        bind(() -> false);
        assertFalse(registered);
    }

    @Test
    public void aRealWindowRotationNotifiesTheFrameToReleaseItsCallback() {
        bind(() -> false);
        assertTrue(registered);
        int writes = ParcelableSnapshotMutableState.writes;
        assertTrue(VideoSwitch.Fullscreen.show(true));
        assertTrue(
                "The frame must observe the return to native orientation",
                ParcelableSnapshotMutableState.writes > writes);
        bind(() -> false);
        assertFalse(registered);
    }
}
