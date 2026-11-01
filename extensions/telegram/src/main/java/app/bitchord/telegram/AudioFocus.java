package app.bitchord.telegram;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Play alongside other apps. Normally BitChord takes Android's audio focus, so it pauses when a
 * reel, a video or another player starts. With this on its players don't take focus at all: they
 * have none to lose, so other apps' sound plays over the music instead of stopping it. Calls still
 * pause the music, since Android no longer does that for BitChord, and it plays again when the call
 * ends.
 */
public final class AudioFocus {
    private AudioFocus() {}

    static final String PREFERENCES = "bitchord_audio_focus";

    /** How long the end of a call must last: phones can pass through normal between its stages. */
    static final long CALL_END_WAIT_MS = 1_000;

    /** On: BitChord's players don't take audio focus. */
    static volatile boolean alongside = false;

    private static volatile SharedPreferences preferences;
    private static volatile AudioManager audio;

    /** What BitChord asked of each player, and with which attributes. */
    private static final Map<Player, Boolean> wanted = new WeakHashMap<>();

    private static final Map<Player, AudioAttributes> attributes = new WeakHashMap<>();

    /** The focus BitChord's next player is being built with, until the player hook takes it. */
    private static final ThreadLocal<Boolean> building = new ThreadLocal<>();

    private static volatile Handler main;

    /** Players this paused for a call, with the song each was on. Lock for the fields below. */
    private static final Map<Object, Object> pausedForCall = new WeakHashMap<>();

    private static boolean resumeWaiting;
    private static final Runnable resumeAfterCall = AudioFocus::resume;

    private static volatile Context appContext;
    private static BroadcastReceiver noisyReceiver;
    private static AudioDeviceCallback deviceCallback;
    private static final Map<Player, Player.Listener> observers = new WeakHashMap<>();
    private static Object modeListener;
    private static final Runnable poll = AudioFocus::checkCall;

    static void init(Context context) {
        if (preferences != null || context == null) return;
        Context app = context.getApplicationContext();
        preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        alongside = preferences.getBoolean("alongside", false);
        audio = app.getSystemService(AudioManager.class);
        appContext = app;
        watchCalls(alongside);
    }

    static void setAlongside(boolean on) {
        alongside = on;
        SharedPreferences saved = preferences;
        if (saved != null) saved.edit().putBoolean("alongside", on).apply();
        List<Player> known;
        synchronized (wanted) {
            known = new ArrayList<>(wanted.keySet());
        }
        for (Player player : known) apply(player);
        watchCalls(on);
        HostLog.d(on ? "Playing alongside other apps" : "Taking audio focus again");
    }

    // ── Hooks ───────────────────────────────────────────────────────────────

    /** The focus a player is being built with; what it really gets is returned. */
    public static boolean built(boolean handleAudioFocus) {
        building.set(handleAudioFocus);
        return handleAudioFocus && !alongside;
    }

    /** A player BitChord has just built, after {@link #built}. */
    static void player(Player player) {
        if (player == null) return;
        synchronized (wanted) {
            wanted.put(player, !Boolean.FALSE.equals(building.get()));
        }
        building.remove();
        runOn(
                player,
                () -> {
                    synchronized (observers) {
                        if (!observers.containsKey(player)) {
                            java.lang.ref.WeakReference<Player> reference =
                                    new java.lang.ref.WeakReference<>(player);
                            Player.Listener listener =
                                    new Player.Listener() {
                                        public void onPlayWhenReadyChanged(
                                                boolean ready, int reason) {
                                            changed(ready, reason);
                                        }

                                        public void onPlayWhenReadyChanged(
                                                int reason, boolean ready) {
                                            changed(ready, reason);
                                        }

                                        private void changed(boolean ready, int reason) {
                                            // A manual play or a focus change supersedes automatic
                                            // call recovery.
                                            if (ready || reason != 1) clearPaused(reference.get());
                                        }

                                        public void onMediaItemTransition(
                                                int reason, androidx.media3.common.MediaItem item) {
                                            clearPaused(reference.get());
                                        }

                                        public void onMediaItemTransition(
                                                androidx.media3.common.MediaItem item, int reason) {
                                            clearPaused(reference.get());
                                        }

                                        public void onPositionDiscontinuity(int reason) {
                                            clearPaused(reference.get());
                                        }

                                        public void onPositionDiscontinuity(
                                                int reason,
                                                Player.PositionInfo oldPosition,
                                                Player.PositionInfo newPosition) {
                                            clearPaused(reference.get());
                                        }

                                        public void onPositionDiscontinuity(
                                                Player.PositionInfo oldPosition,
                                                Player.PositionInfo newPosition,
                                                int reason) {
                                            clearPaused(reference.get());
                                        }
                                    };
                            observers.put(player, listener);
                            player.addListener(listener);
                        }
                    }
                });
        if (alongside) checkCall();
    }

    /** BitChord moves focus between its players for crossfades, casting and version swaps. */
    public static void setAudioAttributes(
            Player player, AudioAttributes attrs, boolean handleAudioFocus) {
        synchronized (wanted) {
            wanted.put(player, handleAudioFocus);
            if (attrs != null) attributes.put(player, attrs);
        }
        player.setAudioAttributes(attrs, handleAudioFocus && !alongside);
    }

    /**
     * Every request to play or pause a player, from BitChord's buttons, its sleep timer or anything
     * else. Pausing a paused player fires no event, so this is the only sign of one made during a
     * call, and it means the player shouldn't start again by itself when the call ends.
     */
    public static void playRequested(Object player) {
        synchronized (pausedForCall) {
            pausedForCall.remove(player);
        }
    }

    static boolean takesFocus(boolean wantedByBitChord, boolean alongsideOn) {
        return wantedByBitChord && !alongsideOn;
    }

    private static void apply(Player player) {
        Boolean want;
        AudioAttributes attrs;
        synchronized (wanted) {
            want = wanted.get(player);
            attrs = attributes.get(player);
        }
        if (want == null) return;
        Runnable change =
                () -> {
                    try {
                        AudioAttributes current =
                                attrs != null ? attrs : player.getAudioAttributes();
                        player.setAudioAttributes(current, takesFocus(want, alongside));
                    } catch (RuntimeException e) {
                        HostLog.d("Couldn't change a player's audio focus: " + e);
                    }
                };
        Looper looper = player.getApplicationLooper();
        if (Looper.myLooper() == looper) change.run();
        else new Handler(looper).post(change);
    }

    // ── Calls ───────────────────────────────────────────────────────────────

    /** Android only pauses a player for a call through audio focus, so this does it instead. */
    private static synchronized void watchCalls(boolean on) {
        AudioManager manager = audio;
        if (manager == null) return;
        if (main == null) main = new Handler(Looper.getMainLooper());
        main.removeCallbacks(poll);
        watchUnplugging(on);
        if (Build.VERSION.SDK_INT >= 31) {
            if (on && modeListener == null) {
                AudioManager.OnModeChangedListener listener = mode -> checkCall();
                manager.addOnModeChangedListener(main::post, listener);
                modeListener = listener;
            } else if (!on && modeListener != null) {
                manager.removeOnModeChangedListener(
                        (AudioManager.OnModeChangedListener) modeListener);
                modeListener = null;
            }
        } else if (on) main.postDelayed(poll, 1_000);
        if (on) checkCall();
        else forgetCalls();
    }

    /** Headphones coming out during a call mustn't send the song to the speaker when it ends. */
    private static void watchUnplugging(boolean on) {
        Context context = appContext;
        AudioManager manager = audio;
        if (context == null || manager == null) return;
        try {
            if (on && noisyReceiver == null) {
                BroadcastReceiver receiver =
                        new BroadcastReceiver() {
                            @Override
                            public void onReceive(Context c, Intent intent) {
                                forgetCalls();
                            }
                        };
                IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
                if (Build.VERSION.SDK_INT >= 33)
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
                else context.registerReceiver(receiver, filter);
                noisyReceiver = receiver;
                // Android may not send that one while a call holds the audio.
                AudioDeviceCallback devices =
                        new AudioDeviceCallback() {
                            @Override
                            public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                                for (AudioDeviceInfo device : removed)
                                    if (device.isSink() && headphones(device.getType()))
                                        forgetCalls();
                            }
                        };
                manager.registerAudioDeviceCallback(devices, main);
                deviceCallback = devices;
            } else if (!on && noisyReceiver != null) {
                context.unregisterReceiver(noisyReceiver);
                noisyReceiver = null;
                if (deviceCallback != null) manager.unregisterAudioDeviceCallback(deviceCallback);
                deviceCallback = null;
            }
        } catch (RuntimeException e) {
            HostLog.d("Couldn't follow headphones during calls: " + e);
        }
    }

    /**
     * Listening devices that going away means the music shouldn't carry on. Not the Bluetooth call
     * link, which Android closes at the end of every call on a headset.
     */
    static boolean headphones(int type) {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_USB_HEADSET
                || type == AudioDeviceInfo.TYPE_HEARING_AID
                || type == 26; // TYPE_BLE_HEADSET
    }

    /** Nothing is to start again by itself. */
    private static void forgetCalls() {
        cancelResume();
        synchronized (pausedForCall) {
            pausedForCall.clear();
        }
    }

    static boolean inCall(int mode) {
        return mode == AudioManager.MODE_RINGTONE
                || mode == AudioManager.MODE_IN_CALL
                || mode == AudioManager.MODE_IN_COMMUNICATION
                || mode == 4; // MODE_CALL_SCREENING
    }

    /** Buffering counts: it plays by itself as soon as there's enough, call or not. */
    static boolean pausesForCall(boolean playWhenReady, int playbackState) {
        return playWhenReady && playbackState != Player.STATE_ENDED;
    }

    /** A song can only be picked up again from a player that still has one prepared. */
    static boolean canResume(boolean playWhenReady, int playbackState) {
        return !playWhenReady
                && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING);
    }

    /** The song itself, whichever object BitChord happens to be holding it in now. */
    static Object identity(MediaItem item) {
        if (item == null) return null;
        return item.mediaId == null || item.mediaId.isEmpty() ? item : item.mediaId;
    }

    private static void checkCall() {
        AudioManager manager = audio;
        if (manager == null) return;
        boolean call = alongside && inCall(manager.getMode());
        if (call) {
            cancelResume();
            List<Player> known;
            synchronized (wanted) {
                known = new ArrayList<>(wanted.keySet());
            }
            for (Player player : known)
                runOn(
                        player,
                        () -> {
                            if (alongside
                                    && pausesForCall(
                                            player.getPlayWhenReady(), player.getPlaybackState())) {
                                Object item = identity(player.getCurrentMediaItem());
                                // Before the note below, so its own request can't cancel it.
                                player.pause();
                                synchronized (pausedForCall) {
                                    pausedForCall.put(player, item);
                                }
                                HostLog.d("Paused for a call");
                            }
                        });
        } else waitToResume();
        Handler handler = main;
        if (alongside && Build.VERSION.SDK_INT < 31 && handler != null) {
            handler.removeCallbacks(poll);
            handler.postDelayed(poll, 1_000);
        }
    }

    private static void waitToResume() {
        Handler handler = main;
        if (handler == null) return;
        synchronized (pausedForCall) {
            if (pausedForCall.isEmpty() || resumeWaiting) return;
            resumeWaiting = true;
        }
        handler.postDelayed(resumeAfterCall, CALL_END_WAIT_MS);
    }

    private static void cancelResume() {
        Handler handler = main;
        if (handler != null) handler.removeCallbacks(resumeAfterCall);
        synchronized (pausedForCall) {
            resumeWaiting = false;
        }
    }

    private static void resume() {
        AudioManager manager = audio;
        boolean clear = manager != null && alongside && !inCall(manager.getMode());
        Map<Object, Object> resume;
        synchronized (pausedForCall) {
            resumeWaiting = false;
            if (!clear) return;
            resume = new WeakHashMap<>(pausedForCall);
            pausedForCall.clear();
        }
        for (Map.Entry<Object, Object> entry : resume.entrySet()) {
            Player player = (Player) entry.getKey();
            Object item = entry.getValue();
            runOn(
                    player,
                    () -> {
                        if (alongside
                                && !inCall(manager.getMode())
                                && Objects.equals(identity(player.getCurrentMediaItem()), item)
                                && canResume(player.getPlayWhenReady(), player.getPlaybackState()))
                            player.play();
                    });
        }
    }

    private static void clearPaused(Player player) {
        synchronized (pausedForCall) {
            pausedForCall.remove(player);
        }
    }

    private static void runOn(Player player, Runnable action) {
        try {
            Looper looper = player.getApplicationLooper();
            Runnable safe =
                    () -> {
                        try {
                            action.run();
                        } catch (RuntimeException failure) {
                            HostLog.d("Player no longer available");
                        }
                    };
            if (Looper.myLooper() == looper) safe.run();
            else if (looper != null) new Handler(looper).post(safe);
        } catch (RuntimeException e) {
            HostLog.d("Couldn't reach a player: " + e);
        }
    }
}
