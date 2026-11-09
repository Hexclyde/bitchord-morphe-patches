package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.os.Looper;

import androidx.media3.common.Player;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class PlaybackUrgencyTest {
    static final class Host {
        public Player player;
    }

    @Test
    public void bufferingRenewsPriorityAndReleasedPlayersCannotRenewIt() throws Exception {
        PlaybackPriority.reset();
        AtomicLong clock = new AtomicLong(1);
        PlaybackPriority.clock = clock::get;
        int[] state = {Player.STATE_BUFFERING}, subscriptions = {0};
        Player.Listener[] listener = {null};
        Player player =
                (Player)
                        Proxy.newProxyInstance(
                                Player.class.getClassLoader(),
                                new Class<?>[] {Player.class},
                                (proxy, method, args) -> {
                                    switch (method.getName()) {
                                        case "getApplicationLooper":
                                            return Looper.getMainLooper();
                                        case "getPlayWhenReady":
                                            return true;
                                        case "getPlaybackState":
                                            return state[0];
                                        case "addListener":
                                            listener[0] = (Player.Listener) args[0];
                                            subscriptions[0]++;
                                            return null;
                                        case "hashCode":
                                            return System.identityHashCode(proxy);
                                        case "equals":
                                            return proxy == args[0];
                                        default:
                                            return null;
                                    }
                                });
        Host host = new Host();
        host.player = player;
        java.lang.reflect.Field owner = SourceFirst.class.getDeclaredField("service");
        owner.setAccessible(true);
        Object previous = owner.get(null);
        owner.set(null, host);
        try {
            SourceFirst.watchPriority(player);
            SourceFirst.watchPriority(player);
            assertEquals(1, subscriptions[0]);
            assertFalse(PlaybackPriority.analysisAllowed());
            clock.addAndGet(10_000_000_000L);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertFalse("A long rebuffer keeps its priority", PlaybackPriority.analysisAllowed());
            state[0] = Player.STATE_READY;
            listener[0].onPlaybackStateChanged(Player.STATE_READY);
            clock.addAndGet(3_000_000_000L);
            assertTrue(PlaybackPriority.analysisAllowed());
            listener[0].onPositionDiscontinuity(1);
            assertFalse("A seek defers background work", PlaybackPriority.analysisAllowed());
            host.player = null;
            clock.addAndGet(3_000_000_000L);
            listener[0].onPositionDiscontinuity(1);
            assertTrue("Old players cannot reserve resources", PlaybackPriority.analysisAllowed());
        } finally {
            owner.set(null, previous);
            PlaybackPriority.reset();
        }
    }
}
