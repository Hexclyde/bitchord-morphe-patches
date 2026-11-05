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

    public static final class Controller {
        public Player outgoing, incoming, easing;
    }

    @Before
    @After
    public void reset() {
        TransitionRuntime.release();
        PlaybackService.selectedSpeed = 1f;
    }

    private static Player deck(PlaybackParameters[] current) {
        return (Player)
                Proxy.newProxyInstance(
                        Player.class.getClassLoader(),
                        new Class<?>[] {Player.class},
                        (p, m, a) -> {
                            if (m.getName().equals("getPlaybackParameters")) return current[0];
                            if (m.getName().equals("setPlaybackParameters")) {
                                current[0] = (PlaybackParameters) a[0];
                                return null;
                            }
                            if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                            if (m.getName().equals("equals")) return p == a[0];
                            return null;
                        });
    }

    @Test
    public void unownedDecksKeepUserSpeedAndPitchWhateverIsRequested() {
        for (float selected : new float[] {.8f, 1f, 1.25f}) {
            PlaybackService.selectedSpeed = selected;
            PlaybackParameters[] current = {new PlaybackParameters(selected, 1.07f)};
            Player deck = deck(current);
            for (float requested : new float[] {1.08f, .97f, 1.001f, 1f}) {
                SourceAutomix.speed(deck, requested);
                assertEquals(selected, current[0].speed, 0);
                assertEquals(1.07f, current[0].pitch, 0);
            }
        }
    }

    @Test
    public void aBoundControllerOnlyPassesItsOwnDecksNativeSpeedThrough() {
        PlaybackService.selectedSpeed = 1.25f;
        PlaybackParameters[] nativeDeck = {new PlaybackParameters(1.25f, .95f)};
        PlaybackParameters[] spare = {new PlaybackParameters(1.25f, .95f)};
        Controller controller = new Controller();
        controller.incoming = deck(nativeDeck);
        TransitionRuntime.bind(controller);
        SourceAutomix.speed(controller.incoming, 1.3f);
        assertEquals(1.3f, nativeDeck[0].speed, 1e-5f);
        assertEquals(.95f, nativeDeck[0].pitch, 0);
        SourceAutomix.speed(deck(spare), 1.3f);
        assertEquals(1.25f, spare[0].speed, 0);
        assertEquals(.95f, spare[0].pitch, 0);
    }

    @Test
    public void thePlanHookLeavesNativeRatesAndPhaseServoToTheHost() {
        Plan plan = new Plan();
        SourceAutomix.plan(plan);
        assertEquals(1.08, plan.incomingPlaybackRate, 0);
        assertEquals(1.03, plan.outgoingPlaybackRate, 0);
        assertTrue(plan.phaseLock);
    }
}
