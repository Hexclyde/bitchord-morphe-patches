package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Looper;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = {26, 35},
        instrumentedPackages = "app.bitchord.telegram.VideoSwitch",
        shadows = VideoFullscreenOrientationTest.Exit.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class VideoFullscreenOrientationTest {
    static final class NativeOrientation {
        final Activity activity;
        final int original;
        int portraitCalls, listenerChanges;
        String listener;

        NativeOrientation(Activity activity) {
            this.activity = activity;
            original = activity.getRequestedOrientation();
        }

        void landscape() throws Exception {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            listener = "landscape";
            listenerChanges++;
            capture(this, activity, true);
        }

        void portrait() throws Exception {
            portraitCalls++;
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            listener = "portrait";
            listenerChanges++;
            capture(this, activity, false);
        }

        void release() {
            activity.setRequestedOrientation(original);
        }
    }

    @Implements(value = VideoSwitch.Fullscreen.class, isInAndroidSdk = false)
    public static class Exit {
        @Implementation
        public static void portrait(Object controller) {
            try {
                ((NativeOrientation) controller).portrait();
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }
    }

    static void capture(Object controller, Activity activity, boolean landscape) {
        VideoSwitch.Fullscreen.requested(controller, activity, landscape);
    }

    Activity window;
    NativeOrientation orientation;

    @Before
    public void before() throws Exception {
        VideoSwitch.Fullscreen.requested(null, false);
        VideoSwitch.Fullscreen.reset();
        VideoSwitch.Fullscreen.show(false);
        window = Robolectric.buildActivity(Activity.class).setup().get();
        orientation = new NativeOrientation(window);
        orientation.landscape();
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(VideoSwitch.Fullscreen.SETTLE_MS + 50, TimeUnit.MILLISECONDS);
        assertTrue(VideoSwitch.Fullscreen.show(false));
        assertEquals(
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, window.getRequestedOrientation());
    }

    @After
    public void after() {
        orientation.release();
        VideoSwitch.Fullscreen.reset();
        window.finish();
    }

    @Test
    public void genuineFullscreenLeaveUsesNativePortraitAndKeepsItsReleaseBookkeeping() {
        VideoSwitch.Fullscreen.LEAVE.invoke();
        assertEquals(
                "Native exit callback must run, not only reset the mode",
                1,
                orientation.portraitCalls);
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, window.getRequestedOrientation());
        assertEquals("portrait", orientation.listener);
        assertEquals(2, orientation.listenerChanges);
        assertFalse(VideoSwitch.Fullscreen.show(false));
        orientation.release();
        assertEquals(orientation.original, window.getRequestedOrientation());
    }
}
