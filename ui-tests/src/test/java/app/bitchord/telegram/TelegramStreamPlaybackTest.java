package app.bitchord.telegram;

import static org.junit.Assert.*;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Proxy;
import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = DiagnosticsScreenTest.Installed.class)
public class TelegramStreamPlaybackTest {
    @Test
    public void readyRemainsUnknownUntilTheSinkAdvancesAndFieldsSurviveExport() throws Exception {
        TelegramBridge.init(RuntimeEnvironment.getApplication());
        TelegramBridge bridge = TelegramBridge.instance;
        String uri = bridge.url() + "/audio/10_20";
        MediaItem item = new MediaItem.Builder().setMediaId("unit-test").setUri(uri).build();
        AnalyticsListener[] analytics = new AnalyticsListener[1];
        Player player =
                (Player)
                        Proxy.newProxyInstance(
                                ExoPlayer.class.getClassLoader(),
                                new Class<?>[] {ExoPlayer.class},
                                (proxy, method, args) -> {
                                    switch (method.getName()) {
                                        case "getCurrentMediaItem":
                                            return item;
                                        case "getPlayWhenReady":
                                        case "isPlaying":
                                            return true;
                                        case "getPlaybackState":
                                            return Player.STATE_READY;
                                        case "getTotalBufferedDuration":
                                            return 1700L;
                                        case "addAnalyticsListener":
                                            analytics[0] = (AnalyticsListener) args[0];
                                            return null;
                                        case "hashCode":
                                            return System.identityHashCode(proxy);
                                        case "equals":
                                            return proxy == args[0];
                                        case "toString":
                                            return "scripted-player";
                                        default:
                                            return null;
                                    }
                                });
        Diagnostics.start(RuntimeEnvironment.getApplication());
        try {
            bridge.audio.trace.bind(7, uri);
            TelegramStreamPlayback.watch(player, () -> true);
            assertNotNull("Real Media3 analytics interface is supported", analytics[0]);
            TelegramStreamPlayback.seek(player);
            TelegramStreamPlayback.activity(player);
            List<JSONObject> before = output();
            assertFalse(before.isEmpty());
            assertTrue(before.stream().allMatch(r -> r.optLong("output_ms", -1) == -1));
            analytics[0].onAudioPositionAdvancing(null, android.os.SystemClock.elapsedRealtime());
            analytics[0].onAudioUnderrun(null, 1024, 30, 100);
            List<JSONObject> after = output();
            JSONObject audible =
                    after.stream()
                            .filter(r -> r.optString("output_signal").equals("sink-advancing"))
                            .findFirst()
                            .get();
            assertTrue(audible.getLong("output_ms") >= 0);
            assertTrue(audible.getLong("file_ref") > 0);
            assertEquals(1700, audible.getLong("buffer_ms"));
            assertTrue(after.stream().anyMatch(r -> r.optLong("underruns") == 1));
            assertFalse(after.toString().contains(uri));
        } finally {
            Diagnostics.stop();
        }
    }

    private static List<JSONObject> output() throws Exception {
        List<JSONObject> rows = new ArrayList<>();
        for (String line : Diagnostics.report().split("\n")) {
            if (!line.startsWith("{")) continue;
            JSONObject row = new JSONObject(line);
            if (row.optString("type").equals("telegram.stream.output")) rows.add(row);
        }
        return rows;
    }
}
