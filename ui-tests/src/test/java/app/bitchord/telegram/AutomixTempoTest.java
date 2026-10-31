package app.bitchord.telegram;

import static org.junit.Assert.*;

import androidx.media3.common.*;

import com.music.bitchord.playback.PlaybackService;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Proxy;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class AutomixTempoTest {
    static class Plan {
        double incomingPlaybackRate = 1.08, outgoingPlaybackRate = 1.03;
        boolean phaseLock = true;
    }

    @Test
    public void tempoRampsCannotOverrideUserSpeedOrPitch() {
        for (float selected : new float[] {.8f, 1f, 1.25f}) {
            PlaybackService.selectedSpeed = selected;
            PlaybackParameters[] current = {new PlaybackParameters(selected, 1.07f)};
            Player deck =
                    (Player)
                            Proxy.newProxyInstance(
                                    Player.class.getClassLoader(),
                                    new Class<?>[] {Player.class},
                                    (p, m, a) -> {
                                        if (m.getName().equals("getPlaybackParameters"))
                                            return current[0];
                                        if (m.getName().equals("setPlaybackParameters")) {
                                            current[0] = (PlaybackParameters) a[0];
                                            return null;
                                        }
                                        return null;
                                    });
            for (float requested : new float[] {1.08f, .97f, 1.001f, 1f}) {
                SourceAutomix.speed(deck, requested);
                assertEquals(selected, current[0].speed, 0);
                assertEquals(1.07f, current[0].pitch, 0);
            }
        }
    }

    @Test
    public void phaseServoAndBothRateMultipliersAreRemovedTogether() {
        Plan plan = new Plan();
        SourceAutomix.plan(plan);
        assertEquals(1d, plan.incomingPlaybackRate, 0);
        assertEquals(1d, plan.outgoingPlaybackRate, 0);
        assertFalse(plan.phaseLock);
    }
}
