package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;

import com.music.bitchord.playback.PlaybackService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class MixLabelTest {
    Object previous;
    boolean realMix;
    int lookups;

    @Before
    public void setup() {
        previous = SourceAutomix.owner();
        realMix = PlaybackService.realMix;
        PlaybackService.realMix = false;
    }

    @After
    public void restore() {
        PlaybackService.realMix = realMix;
        SourceAutomix.service(previous);
    }

    Context localized(boolean renamed) {
        Context app = RuntimeEnvironment.getApplication();
        Resources original = app.getResources();
        Resources resources =
                new Resources(
                        original.getAssets(),
                        original.getDisplayMetrics(),
                        original.getConfiguration()) {
                    @Override
                    public int getIdentifier(String name, String type, String packageName) {
                        lookups++;
                        assertEquals("crossfade", name);
                        assertEquals("string", type);
                        return !renamed || packageName.equals("com.music.bitchord") ? 123 : 0;
                    }

                    @Override
                    public String getString(int id) {
                        assertEquals(123, id);
                        return "Überblendung";
                    }
                };
        return new ContextWrapper(app) {
            @Override
            public Resources getResources() {
                return resources;
            }

            @Override
            public String getPackageName() {
                return "com.music.bitchord.telegramtest";
            }
        };
    }

    @Test
    public void aBasicBlendUsesTheExistingLocalizedCrossfadeLabel() {
        SourceAutomix.service(localized(false));
        assertEquals("Überblendung", AutomixAccess.mixLabel("Mezclando"));
        assertEquals(1, lookups);
        assertFalse(PlaybackService.realMix);
    }

    @Test
    public void aRealAnalyzedMixRetainsTheOriginalLocalizedLabelWithoutResourceWork() {
        SourceAutomix.service(localized(false));
        PlaybackService.realMix = true;
        assertEquals("Mezclando", AutomixAccess.mixLabel("Mezclando"));
        assertEquals(0, lookups);
        assertTrue(PlaybackService.realMix);
    }

    @Test
    public void aRenamedInstallationResolvesTheOriginalResourceNamespace() {
        SourceAutomix.service(localized(true));
        assertEquals("Überblendung", AutomixAccess.mixLabel("Mezclando"));
        assertEquals(2, lookups);
    }
}
