package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class VideoPreferenceTest {
    @Test
    public void preferenceSurvivesRuntimeReinitializationAndDisabling() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(VideoSwitch.PREFERENCES, 0).edit().clear().commit();
        java.lang.reflect.Field preferences = VideoSwitch.class.getDeclaredField("preferences");
        preferences.setAccessible(true);
        preferences.set(null, null);
        VideoSwitch.init(app);
        assertFalse(VideoSwitch.enabled);
        assertFalse(VideoSwitch.preferredVideo);
        VideoSwitch.setEnabled(true);
        VideoSwitch.setPreferredVideo(true);
        VideoSwitch.setEnabled(false);
        preferences.set(null, null);
        VideoSwitch.preferredVideo = false;
        VideoSwitch.init(app);
        assertTrue(VideoSwitch.preferredVideo);
        assertFalse(VideoSwitch.enabled);
        VideoSwitch.setPreferredVideo(false);
        preferences.set(null, null);
        VideoSwitch.preferredVideo = true;
        VideoSwitch.init(app);
        assertFalse(VideoSwitch.preferredVideo);
    }
}
