package app.bitchord.telegram;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Play alongside other apps. Normally BitChord takes Android's audio focus, so it pauses when a
 * reel, a video or another player starts. With this on its players don't take focus at all: they
 * have none to lose, so other apps' sound plays over the music instead of stopping it. Calls
 * still pause the music, since Android no longer does that for BitChord, and it plays again when
 * the call ends.
 */
public final class AudioFocus {
    private AudioFocus() {}

    static final String PREFERENCES = "bitchord_audio_focus";

    /** On: BitChord's players don't take audio focus. */
    static volatile boolean alongside = false;

    private static volatile SharedPreferences preferences;
    private static volatile AudioManager audio;

    /** What BitChord asked of each player, and with which attributes. */
    private static final Map<Player, Boolean> wanted = new WeakHashMap<>();

    private static final Map<Player, AudioAttributes> attributes = new WeakHashMap<>();

    /** The focus BitChord's next player is being built with, until the player hook takes it. */
    private static volatile boolean building = true;

    private static Handler main;
    private static final List<Player> pausedForCall = new ArrayList<>();
    private static Object modeListener;
    private static final Runnable poll = AudioFocus::checkCall;

    static void init(Context context) {
        if (preferences != null || context == null) return;
        Context app = context.getApplicationContext();
        preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        alongside = preferences.getBoolean("alongside", false);
        audio = app.getSystemService(AudioManager.class);
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
        building = handleAudioFocus;
        return handleAudioFocus && !alongside;
    }

    /** A player BitChord has just built, after {@link #built}. */
    static void player(Player player) {
        if (player == null) return;
        synchronized (wanted) {
            wanted.put(player, building);
        }
        building = true;
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
    }

    static boolean inCall(int mode) {
        return mode == AudioManager.MODE_RINGTONE
                || mode == AudioManager.MODE_IN_CALL
                || mode == AudioManager.MODE_IN_COMMUNICATION
                || mode == 4; // MODE_CALL_SCREENING
    }

    private static void checkCall() {
        AudioManager manager = audio;
        if (manager == null) return;
        boolean call = alongside && inCall(manager.getMode());
        List<Player> known;
        synchronized (wanted) {
            known = new ArrayList<>(wanted.keySet());
        }
        if (call) {
            for (Player player : known)
                runOn(
                        player,
                        () -> {
                            if (player.isPlaying()) {
                                player.pause();
                                synchronized (pausedForCall) {
                                    pausedForCall.add(player);
                                }
                                HostLog.d("Paused for a call");
                            }
                        });
        } else {
            List<Player> resume;
            synchronized (pausedForCall) {
                resume = new ArrayList<>(pausedForCall);
                pausedForCall.clear();
            }
            for (Player player : resume) runOn(player, player::play);
        }
        if (alongside && Build.VERSION.SDK_INT < 31 && main != null) {
            main.removeCallbacks(poll);
            main.postDelayed(poll, 1_000);
        }
    }

    private static void runOn(Player player, Runnable action) {
        try {
            Looper looper = player.getApplicationLooper();
            if (Looper.myLooper() == looper) action.run();
            else new Handler(looper).post(action);
        } catch (RuntimeException e) {
            HostLog.d("Couldn't reach a player: " + e);
        }
    }
}
