package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.os.Looper;

import androidx.media3.common.Player;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = DiagnosticsScreenTest.Installed.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class SeekTraceTest {
    @Test
    public void rapidPausedSeeksSupersedeWithoutStartingPlayback() throws Exception {
        List<Player.Listener> listeners = new ArrayList<>();
        long[] position = {100};
        int[] mutations = {0};
        Player player =
                (Player)
                        Proxy.newProxyInstance(
                                Player.class.getClassLoader(),
                                new Class<?>[] {Player.class},
                                (proxy, method, args) -> {
                                    switch (method.getName()) {
                                        case "hashCode":
                                            return System.identityHashCode(proxy);
                                        case "equals":
                                            return proxy == args[0];
                                        case "getApplicationLooper":
                                            return Looper.getMainLooper();
                                        case "getCurrentPosition":
                                            return position[0];
                                        case "getPlaybackState":
                                            return Player.STATE_READY;
                                        case "getPlayWhenReady":
                                        case "isPlaying":
                                            return false;
                                        case "addListener":
                                            listeners.add((Player.Listener) args[0]);
                                            return null;
                                        case "removeListener":
                                            listeners.remove(args[0]);
                                            return null;
                                        case "play":
                                        case "seekTo":
                                        case "setPlayWhenReady":
                                            mutations[0]++;
                                            return null;
                                        default:
                                            if (method.getReturnType() == boolean.class)
                                                return false;
                                            if (method.getReturnType() == int.class) return 0;
                                            if (method.getReturnType() == long.class) return 0L;
                                            return null;
                                    }
                                });
        Diagnostics.start(RuntimeEnvironment.getApplication(), 2);
        try {
            Diagnostics.seek(player, 1000);
            Player.Listener stale = listeners.get(0);
            Diagnostics.seek(player, 2000);
            assertEquals(1, listeners.size());
            position[0] = 2000;
            listeners.get(0).onPositionDiscontinuity(Player.DISCONTINUITY_REASON_SEEK);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
            assertTrue(listeners.isEmpty());
            stale.onPositionDiscontinuity(Player.DISCONTINUITY_REASON_SEEK);
            String report = Diagnostics.report();
            assertTrue(report.contains("SUPERSEDED"));
            assertTrue(report.contains("SEEK_READY"));
            assertTrue(report.contains("\"target_ms\":2000"));
            assertFalse(report.contains("OUTPUT_PROGRESS_ESTIMATE"));
            assertEquals(0, mutations[0]);
        } finally {
            Diagnostics.stop();
            Diagnostics.clear();
        }
    }
}
