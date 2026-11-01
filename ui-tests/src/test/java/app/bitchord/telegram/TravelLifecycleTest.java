package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.AudioDeviceInfoBuilder;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class TravelLifecycleTest {
    Context app;
    AudioManager audio;

    /**
     * Stands in for BitChord's player. As in the real one, every play and pause reports to the hook
     * first, and only a real change of state fires an event.
     */
    static final class StandIn implements InvocationHandler {
        boolean wants = true;
        int state = Player.STATE_READY;
        int resumes, pauses;
        MediaItem item = new MediaItem.Builder().setMediaId("track").build();
        List<Player.Listener> listeners = new ArrayList<>();
        Player player =
                (Player)
                        Proxy.newProxyInstance(
                                Player.class.getClassLoader(), new Class<?>[] {Player.class}, this);

        void request(boolean value) {
            AudioFocus.playRequested(player);
            if (wants == value) return;
            wants = value;
            for (Player.Listener listener : listeners) listener.onPlayWhenReadyChanged(value, 1);
        }

        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "Call test player";
                case "getApplicationLooper":
                    return Looper.getMainLooper();
                case "addListener":
                    listeners.add((Player.Listener) args[0]);
                    return null;
                case "isPlaying":
                    return wants && state == Player.STATE_READY;
                case "getPlayWhenReady":
                    return wants;
                case "getCurrentMediaItem":
                    return item;
                case "getPlaybackState":
                    return state;
                case "pause":
                    pauses++;
                    request(false);
                    return null;
                case "play":
                    resumes++;
                    request(true);
                    return null;
                default:
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == float.class) return 1f;
                    return null;
            }
        }
    }

    static void reset(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    @Before
    public void setup() throws Exception {
        app = RuntimeEnvironment.getApplication();
        audio = app.getSystemService(AudioManager.class);
        audio.setMode(AudioManager.MODE_NORMAL);
        reset(AudioFocus.class, "preferences", null);
        reset(AudioFocus.class, "noisyReceiver", null);
        reset(AudioFocus.class, "deviceCallback", null);
        reset(AudioFocus.class, "modeListener", null);
        reset(SteadyPlayback.class, "preferences", null);
        reset(SteadyPlayback.class, "registered", false);
        reset(SteadyPlayback.class, "blocked", false);
        app.getSharedPreferences(AudioFocus.PREFERENCES, 0).edit().clear().commit();
        app.getSharedPreferences(SteadyPlayback.PREFERENCES, 0).edit().clear().commit();
        AudioFocus.init(app);
        SteadyPlayback.init(app);
    }

    @After
    public void stop() {
        AudioFocus.setAlongside(false);
        SteadyPlayback.setEnabled(false);
    }

    void mode(int mode) throws Exception {
        audio.setMode(mode);
        Method check = AudioFocus.class.getDeclaredMethod("checkCall");
        check.setAccessible(true);
        check.invoke(null);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** The call has been over for as long as it takes to believe it. */
    void callEndHolds() {
        Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(Duration.ofMillis(AudioFocus.CALL_END_WAIT_MS + 1));
    }

    StandIn alongside() {
        StandIn player = new StandIn();
        AudioFocus.player(player.player);
        AudioFocus.setAlongside(true);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return player;
    }

    @Test
    public void callRecoveryResumesOnceAndNeverRestartsAnOldTrack() throws Exception {
        StandIn first = alongside();
        mode(AudioManager.MODE_IN_COMMUNICATION);
        assertFalse(first.wants);
        assertEquals(1, first.pauses);
        mode(AudioManager.MODE_NORMAL);
        callEndHolds();
        assertTrue(first.wants);
        assertEquals(1, first.resumes);
        mode(AudioManager.MODE_NORMAL);
        callEndHolds();
        assertEquals(1, first.resumes);
        mode(AudioManager.MODE_RINGTONE);
        first.item = new MediaItem.Builder().setMediaId("different").build();
        mode(AudioManager.MODE_NORMAL);
        callEndHolds();
        assertFalse(first.wants);
        assertEquals(1, first.resumes);
    }

    @Test
    public void disablingAlongsideDuringACallDiscardsTheRecoveryTicket() throws Exception {
        StandIn player = alongside();
        mode(AudioManager.MODE_IN_CALL);
        assertFalse(player.wants);
        AudioFocus.setAlongside(false);
        mode(AudioManager.MODE_NORMAL);
        AudioFocus.setAlongside(true);
        mode(AudioManager.MODE_NORMAL);
        callEndHolds();
        assertFalse(player.wants);
        assertEquals(0, player.resumes);
    }

    @Test
    public void aBufferingPlayerIsPausedAndResumed() throws Exception {
        StandIn player = alongside();
        player.state = Player.STATE_BUFFERING;
        mode(AudioManager.MODE_RINGTONE);
        assertFalse("it would start by itself during the call", player.wants);
        mode(AudioManager.MODE_NORMAL);
        callEndHolds();
        assertTrue(player.wants);
        assertEquals(1, player.resumes);
    }

    @Test
    public void aFinishedPlayerIsLeftAlone() throws Exception {
        StandIn player = alongside();
        player.state = Player.STATE_ENDED;
        mode(AudioManager.MODE_IN_CALL);
        assertEquals(0, player.pauses);
        mode(AudioManager.MODE_NORMAL);
        callEndHolds();
        assertEquals(0, player.resumes);
    }

    @Test
    public void aStoppedPlayerIsNotStartedAgain() throws Exception {
        StandIn player = alongside();
        mode(AudioManager.MODE_IN_CALL);
        player.state = Player.STATE_IDLE;
        mode(AudioManager.MODE_NORMAL);
        callEndHolds();
        assertEquals(0, player.resumes);
    }

    @Test
    public void aMomentOfNormalBetweenRingingAndTheCallDoesNotPlay() throws Exception {
        StandIn player = alongside();
        mode(AudioManager.MODE_RINGTONE);
        mode(AudioManager.MODE_NORMAL);
        Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(Duration.ofMillis(AudioFocus.CALL_END_WAIT_MS / 2));
        assertEquals("not yet", 0, player.resumes);
        mode(AudioManager.MODE_IN_CALL);
        callEndHolds();
        assertEquals("the call took over", 0, player.resumes);
        assertFalse(player.wants);
        mode(AudioManager.MODE_NORMAL);
        assertEquals("the end of the call is waited out too", 0, player.resumes);
        callEndHolds();
        assertEquals(1, player.resumes);
        assertEquals(1, player.pauses);
    }

    @Test
    public void everyEndOfACallGetsTheWholeWait() throws Exception {
        StandIn player = alongside();
        mode(AudioManager.MODE_RINGTONE);
        mode(AudioManager.MODE_NORMAL);
        Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(Duration.ofMillis(AudioFocus.CALL_END_WAIT_MS - 100));
        mode(AudioManager.MODE_IN_CALL);
        mode(AudioManager.MODE_NORMAL);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        assertEquals("the first wait doesn't carry over", 0, player.resumes);
        callEndHolds();
        assertEquals(1, player.resumes);
    }

    @Test
    public void repeatedChecksAfterACallDoNotPutOffTheResume() throws Exception {
        StandIn player = alongside();
        mode(AudioManager.MODE_IN_CALL);
        mode(AudioManager.MODE_NORMAL);
        Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(Duration.ofMillis(AudioFocus.CALL_END_WAIT_MS / 2));
        mode(AudioManager.MODE_NORMAL);
        Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(Duration.ofMillis(AudioFocus.CALL_END_WAIT_MS / 2 + 1));
        assertEquals(1, player.resumes);
    }

    @Test
    public void aPauseRequestDuringACallFiresNoEventAndStillCancelsTheResume() throws Exception {
        StandIn player = alongside();
        mode(AudioManager.MODE_IN_CALL);
        assertTrue("its own pause doesn't count as someone else's", paused(player));
        // A sleep timer running out or a manual pause: the player is already paused.
        player.player.pause();
        assertEquals(2, player.pauses);
        mode(AudioManager.MODE_NORMAL);
        callEndHolds();
        assertFalse(player.wants);
        assertEquals(0, player.resumes);
    }

    @Test
    public void aPauseAfterTheCallWhileWaitingCancelsTheResume() throws Exception {
        StandIn player = alongside();
        mode(AudioManager.MODE_IN_CALL);
        mode(AudioManager.MODE_NORMAL);
        player.player.pause();
        callEndHolds();
        assertEquals(0, player.resumes);
    }

    @Test
    public void onlyTheRequestedPlayerLosesItsResume() throws Exception {
        StandIn asked = alongside(), other = new StandIn();
        AudioFocus.player(other.player);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        mode(AudioManager.MODE_IN_CALL);
        asked.player.pause();
        mode(AudioManager.MODE_NORMAL);
        callEndHolds();
        assertEquals(0, asked.resumes);
        assertEquals(1, other.resumes);
    }

    @Test
    public void headphonesComingOutDuringACallCancelTheResume() throws Exception {
        StandIn player = alongside();
        mode(AudioManager.MODE_IN_CALL);
        app.sendBroadcast(
                new Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                        .setPackage(app.getPackageName()));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        mode(AudioManager.MODE_NORMAL);
        callEndHolds();
        assertEquals(0, player.resumes);
    }

    void removed(int type) throws Exception {
        Field field = AudioFocus.class.getDeclaredField("deviceCallback");
        field.setAccessible(true);
        AudioDeviceInfo device = AudioDeviceInfoBuilder.newBuilder().setType(type).build();
        // An output: the builder doesn't say which way a device faces.
        Object port = ReflectionHelpers.getField(device, "mPort");
        ReflectionHelpers.setField(Class.forName("android.media.AudioPort"), port, "mRole", 2);
        assertTrue(device.isSink());
        assertEquals(type, device.getType());
        ((AudioDeviceCallback) field.get(null))
                .onAudioDevicesRemoved(new AudioDeviceInfo[] {device});
    }

    @Test
    public void headphonesGoingAwayWithoutABroadcastCancelTheResume() throws Exception {
        StandIn player = alongside();
        mode(AudioManager.MODE_IN_CALL);
        removed(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP);
        mode(AudioManager.MODE_NORMAL);
        callEndHolds();
        assertEquals(0, player.resumes);
    }

    @Test
    public void theCallLinkClosingAtTheEndOfACallStillResumes() throws Exception {
        StandIn player = alongside();
        mode(AudioManager.MODE_IN_CALL);
        removed(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        mode(AudioManager.MODE_NORMAL);
        callEndHolds();
        assertEquals(1, player.resumes);
    }

    @Test
    public void theSameSongInANewObjectStillResumes() throws Exception {
        StandIn player = alongside();
        mode(AudioManager.MODE_IN_CALL);
        player.item = new MediaItem.Builder().setMediaId("track").build();
        mode(AudioManager.MODE_NORMAL);
        callEndHolds();
        assertEquals(1, player.resumes);
        assertEquals("track", AudioFocus.identity(player.item));
    }

    @Test
    public void hooksWithNothingPausedDoNothingAndBuildersStayIndependent() {
        AudioFocus.playRequested(new Object());
        assertTrue("focus requested, off", AudioFocus.built(true));
        assertFalse("not requested", AudioFocus.built(false));
        AudioFocus.setAlongside(true);
        assertFalse("alongside never takes it", AudioFocus.built(true));
    }

    boolean paused(StandIn player) throws Exception {
        Field field = AudioFocus.class.getDeclaredField("pausedForCall");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(null)).containsKey(player.player);
    }

    static final class LoadingState {
        int targetBufferBytes;
    }

    static final class Control {
        int targetBufferBytesOverwrite = 8 << 20, updates;
        long thread;
        Map<String, LoadingState> loadingStates = new HashMap<>();

        Control() {
            loadingStates.put("player", new LoadingState());
        }

        void updateAllocator() {
            updates++;
            thread = Thread.currentThread().getId();
        }
    }

    @Test
    public void bufferChangesWaitForOwnerTickAndRestoreHostLimitWhenDisabled() throws Exception {
        Control control = new Control();
        SteadyPlayback.loadControl(control);
        assertEquals(8 << 20, control.targetBufferBytesOverwrite);
        SteadyPlayback.setEnabled(true);
        SteadyPlayback.setMobileMb(64);
        assertEquals(8 << 20, control.targetBufferBytesOverwrite);
        Thread owner = new Thread(() -> SteadyPlayback.tick(control));
        owner.start();
        owner.join();
        assertEquals(owner.getId(), control.thread);
        assertEquals(64 << 20, control.loadingStates.get("player").targetBufferBytes);
        int updates = control.updates;
        SteadyPlayback.tick(control);
        assertEquals(updates, control.updates);
        SteadyPlayback.setEnabled(false);
        SteadyPlayback.tick(control);
        assertEquals(8 << 20, control.targetBufferBytesOverwrite);
        assertEquals(64, SteadyPlayback.mobileMb);
    }

    @Test
    public void loadControlsItWasNotGivenKeepTheirOwnLimit() {
        for (boolean on : new boolean[] {false, true}) {
            SteadyPlayback.setEnabled(on);
            Control audition = new Control();
            audition.targetBufferBytesOverwrite = 24 << 20;
            audition.loadingStates.get("player").targetBufferBytes = 24 << 20;
            SteadyPlayback.tick(audition);
            assertEquals(24 << 20, audition.targetBufferBytesOverwrite);
            assertEquals(24 << 20, audition.loadingStates.get("player").targetBufferBytes);
            assertEquals(0, audition.updates);
        }
    }

    static NetworkCapabilities caps(int... capabilities) {
        NetworkCapabilities caps = ShadowNetworkCapabilities.newInstance();
        for (int capability : capabilities)
            Shadow.<ShadowNetworkCapabilities>extract(caps).addCapability(capability);
        return caps;
    }

    @Test
    public void theConnectionFollowsAndroidsCallbackAndIsNeverAskedForLater() throws Exception {
        ConnectivityManager manager = app.getSystemService(ConnectivityManager.class);
        ShadowConnectivityManager shadow = Shadow.extract(manager);
        ConnectivityManager.NetworkCallback callback =
                shadow.getNetworkCallbacks().iterator().next();
        callback.onCapabilitiesChanged(
                null,
                caps(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET,
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                        NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
        assertTrue(SteadyPlayback.online());
        assertEquals(SteadyPlayback.Link.UNMETERED, SteadyPlayback.link());
        // Android's own answer changes; playback threads keep what the callback said.
        shadow.setActiveNetworkInfo(null);
        shadow.setDefaultNetworkActive(false);
        assertTrue(SteadyPlayback.online());
        assertEquals(SteadyPlayback.Link.UNMETERED, SteadyPlayback.link());
        assertEquals(3, SteadyPlayback.minRetries(3));

        callback.onCapabilitiesChanged(
                null,
                caps(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET,
                        NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
        assertFalse("a network with nothing behind it", SteadyPlayback.online());
        assertEquals(SteadyPlayback.Link.UNMETERED, SteadyPlayback.link());

        callback.onCapabilitiesChanged(
                null,
                caps(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET,
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED));
        assertTrue(SteadyPlayback.online());
        assertEquals(SteadyPlayback.Link.METERED, SteadyPlayback.link());

        callback.onBlockedStatusChanged(null, true);
        assertFalse("Android has blocked this app's traffic", SteadyPlayback.online());
        callback.onBlockedStatusChanged(null, false);
        assertTrue(SteadyPlayback.online());

        callback.onLost(null);
        assertFalse(SteadyPlayback.online());
        assertEquals(SteadyPlayback.Link.NONE, SteadyPlayback.link());
        SteadyPlayback.setEnabled(true);
        assertEquals(SteadyPlayback.OFFLINE_RETRIES, SteadyPlayback.minRetries(3));
    }

    @Test
    public void onlyARealChangeOfConnectionTouchesAPlayersBuffer() {
        SteadyPlayback.observed(true, true, true);
        Control control = new Control();
        SteadyPlayback.loadControl(control);
        int updates = control.updates;
        SteadyPlayback.observed(true, true, true);
        SteadyPlayback.observed(true, false, true);
        SteadyPlayback.tick(control);
        assertEquals("signal changes and validation are not a new limit", updates, control.updates);
        SteadyPlayback.observed(true, true, false);
        SteadyPlayback.tick(control);
        assertEquals(updates + 1, control.updates);
    }

    @Test
    public void prefsStayAndEverythingStartsOff() {
        assertFalse(AudioFocus.alongside);
        assertFalse(SteadyPlayback.enabled);
        assertFalse(app.getSharedPreferences(AudioFocus.PREFERENCES, 0).contains("alongside"));
        assertFalse(app.getSharedPreferences(SteadyPlayback.PREFERENCES, 0).contains("enabled"));
        AudioFocus.setAlongside(true);
        SteadyPlayback.setEnabled(true);
        assertTrue(
                app.getSharedPreferences(AudioFocus.PREFERENCES, 0).getBoolean("alongside", false));
        assertTrue(
                app.getSharedPreferences(SteadyPlayback.PREFERENCES, 0)
                        .getBoolean("enabled", false));
    }
}
