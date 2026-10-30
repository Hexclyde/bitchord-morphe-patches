package app.bitchord.telegram;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The playback patches, each added on its own: Sing, Pitch control, Loudness level, Sleep timer
 * fade-out, Battery saver, Lossless addons over Bluetooth, Per-download quality, Headphone
 * correction, Equaliser per output, DJ mode and JamesDSP. Each is called from one place in
 * BitChord's playback code and leaves it exactly as it was while its setting is at the default.
 *
 * <p>Sing is ported from Auris by OreoMuncher45 (see {@link VocalCut}). Pitch control, the loudness
 * level, the sleep fade, headphone correction, the equaliser per output and DJ mode follow ideas
 * from Prism by Yigder; per-download quality from Stash by Rawnald Clark; the code here is written
 * for BitChord.
 */
public final class SoundExtras {
    private SoundExtras() {}

    static final String PREFERENCES = "bitchord_playback_extras";

    /** BitChord's own loudness level; a target at this changes nothing. */
    static final int HOST_TARGET_LUFS = -14;

    static final int MIN_TARGET_LUFS = -23, MAX_TARGET_LUFS = -8;
    static final int MIN_PITCH = -12, MAX_PITCH = 12;
    static final int FADE_SECONDS = 10;

    static volatile int singLevel = 0; // 0 to 100
    static volatile int singMethod = VocalCut.VOICE_GATE;
    static volatile int pitchSemitones = 0;
    static volatile int targetLufs = HOST_TARGET_LUFS;
    static volatile boolean singEnabled = false;
    static volatile boolean pitchEnabled = false;
    static volatile boolean loudnessEnabled = false;
    static volatile boolean sleepFade = false;
    static volatile int sleepFadeSeconds = FADE_SECONDS;
    static volatile boolean sleepFadeEnding = true;
    static volatile boolean batterySaver = false;
    static volatile boolean losslessOverBluetooth = false;

    /** The page's switch for Lossless addons over Bluetooth; off keeps BitChord's own rule. */
    static volatile boolean losslessBluetoothEnabled = false;

    /** Bluetooth outputs allowed lossless addons one by one, by their {@link Outputs} keys. */
    static volatile Set<String> losslessDevices = Collections.emptySet();

    private static volatile SharedPreferences preferences;
    private static volatile Context app;
    private static final List<WeakReference<Player>> players = new ArrayList<>();
    private static final Map<Object, VocalCut> singers =
            Collections.synchronizedMap(new WeakHashMap<>());

    // ── Which patches were added ─────────────────────────────────────────────

    // Each answers true once its patch is added; the patch replaces the body.

    public static boolean singApplied() {
        return false;
    }

    public static boolean pitchApplied() {
        return false;
    }

    public static boolean loudnessApplied() {
        return false;
    }

    public static boolean sleepFadeApplied() {
        return false;
    }

    public static boolean batterySaverApplied() {
        return false;
    }

    public static boolean losslessBluetoothApplied() {
        return false;
    }

    public static boolean downloadQualityApplied() {
        return false;
    }

    public static boolean headphoneApplied() {
        return false;
    }

    public static boolean outputEqApplied() {
        return false;
    }

    public static boolean djApplied() {
        return false;
    }

    public static boolean jamesDspApplied() {
        return false;
    }

    public static boolean videoSwitchApplied() {
        return false;
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    public static void init(Context context) {
        if (preferences != null || context == null) return;
        Context application = context.getApplicationContext();
        app = application;
        SharedPreferences saved =
                application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        // Preserve a user's earlier adjustments when introducing the enable switches.
        // A fresh install starts off, with a useful voice level ready when enabled.
        int previousSingLevel = clamp(saved.getInt("singLevel", 0), 0, 100);
        singLevel = clamp(saved.getInt("singLevel", 50), 0, 100);
        singMethod =
                saved.getInt("singMethod", VocalCut.VOICE_GATE) == VocalCut.CENTRE
                        ? VocalCut.CENTRE
                        : VocalCut.VOICE_GATE;
        pitchSemitones = clamp(saved.getInt("pitch", 0), MIN_PITCH, MAX_PITCH);
        targetLufs =
                clamp(
                        saved.getInt("targetLufs", HOST_TARGET_LUFS),
                        MIN_TARGET_LUFS,
                        MAX_TARGET_LUFS);
        singEnabled = saved.getBoolean("singEnabled", previousSingLevel > 0);
        pitchEnabled = saved.getBoolean("pitchEnabled", pitchSemitones != 0);
        loudnessEnabled = saved.getBoolean("loudnessEnabled", targetLufs != HOST_TARGET_LUFS);
        sleepFade = saved.getBoolean("sleepFade", false);
        sleepFadeSeconds =
                clamp(
                        saved.getInt("sleepFadeSeconds", FADE_SECONDS),
                        SleepFade.MIN_SECONDS,
                        SleepFade.MAX_SECONDS);
        sleepFadeEnding = saved.getBoolean("sleepFadeEnding", true);
        batterySaver = saved.getBoolean("batterySaver", false);
        losslessOverBluetooth = saved.getBoolean("losslessOverBluetooth", false);
        Set<String> devices = saved.getStringSet("losslessDevices", null);
        losslessDevices = devices == null ? Collections.emptySet() : new HashSet<>(devices);
        // Earlier versions had no switch: a choice made there turns it on.
        losslessBluetoothEnabled =
                saved.getBoolean(
                        "losslessBluetoothEnabled",
                        losslessOverBluetooth || !losslessDevices.isEmpty());
        preferences = saved;
        Outputs.init(application);
        if (headphoneApplied()) HeadphoneEq.init(application);
        if (outputEqApplied()) OutputEq.init(application);
        if (djApplied()) DjMode.init(application);
        if (downloadQualityApplied()) DownloadChoice.init(application);
        if (jamesDspApplied()) EffectsApps.init(application);
        if (videoSwitchApplied()) VideoSwitch.init(application);
        Outputs.listen(
                () -> {
                    rememberBluetooth(Outputs.current());
                    if (batterySaverApplied()) onPlayers(SoundExtras::applyOffload);
                });
    }

    static Context app() {
        return app;
    }

    private static void save(String key, Object value) {
        SharedPreferences saved = preferences;
        if (saved == null) return;
        SharedPreferences.Editor editor = saved.edit();
        if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else editor.putInt(key, (Integer) value);
        editor.apply();
    }

    static void setSingLevel(int level) {
        boolean was = singLevel > 0;
        singLevel = clamp(level, 0, 100);
        save("singLevel", singLevel);
        if (was != singLevel > 0 && batterySaverApplied()) onPlayers(SoundExtras::applyOffload);
    }

    static void setSingEnabled(boolean on) {
        singEnabled = on;
        save("singEnabled", on);
        if (!on) singers.clear();
        effectsChanged();
    }

    static void setPitchEnabled(boolean on) {
        pitchEnabled = on;
        save("pitchEnabled", on);
        onPlayers(SoundExtras::applyPitch);
        effectsChanged();
    }

    static void setLoudnessEnabled(boolean on) {
        loudnessEnabled = on;
        save("loudnessEnabled", on);
    }

    static void setSingMethod(int method) {
        singMethod = method == VocalCut.CENTRE ? VocalCut.CENTRE : VocalCut.VOICE_GATE;
        save("singMethod", singMethod);
    }

    static void setPitch(int semitones) {
        pitchSemitones = clamp(semitones, MIN_PITCH, MAX_PITCH);
        save("pitch", pitchSemitones);
        onPlayers(SoundExtras::applyPitch);
        if (batterySaverApplied()) onPlayers(SoundExtras::applyOffload);
    }

    static void setTargetLufs(int lufs) {
        targetLufs = clamp(lufs, MIN_TARGET_LUFS, MAX_TARGET_LUFS);
        save("targetLufs", targetLufs);
    }

    static void setSleepFade(boolean on) {
        sleepFade = on;
        save("sleepFade", on);
        if (!on) SleepFade.cancel();
    }

    static void setSleepFadeSeconds(int seconds) {
        sleepFadeSeconds = clamp(seconds, SleepFade.MIN_SECONDS, SleepFade.MAX_SECONDS);
        save("sleepFadeSeconds", sleepFadeSeconds);
    }

    static void setSleepFadeEnding(boolean on) {
        sleepFadeEnding = on;
        save("sleepFadeEnding", on);
    }

    static void setBatterySaver(boolean on) {
        batterySaver = on;
        save("batterySaver", on);
        onPlayers(SoundExtras::applyOffload);
    }

    /** Called when another patch's setting changes what Battery saver may do. */
    static void effectsChanged() {
        if (batterySaverApplied()) onPlayers(SoundExtras::applyOffload);
    }

    static void setLosslessBluetoothEnabled(boolean on) {
        losslessBluetoothEnabled = on;
        save("losslessBluetoothEnabled", on);
    }

    static void setLosslessOverBluetooth(boolean on) {
        losslessOverBluetooth = on;
        save("losslessOverBluetooth", on);
    }

    /** Allows or stops lossless addons on one Bluetooth output. */
    static void setLosslessDevice(String outputKey, boolean on) {
        Set<String> next = new HashSet<>(losslessDevices);
        if (on) next.add(outputKey);
        else next.remove(outputKey);
        losslessDevices = next;
        SharedPreferences saved = preferences;
        if (saved != null)
            saved.edit().putStringSet("losslessDevices", new HashSet<>(next)).apply();
    }

    /** Bluetooth outputs seen so far, for the page's list. */
    static List<String> bluetoothSeen() {
        SharedPreferences saved = preferences;
        List<String> out = new ArrayList<>();
        if (saved == null) return out;
        for (String key : saved.getAll().keySet())
            if (key.startsWith("btname.")) out.add(key.substring(7));
        Collections.sort(out);
        return out;
    }

    static String bluetoothName(String outputKey) {
        SharedPreferences saved = preferences;
        String name = saved == null ? null : saved.getString("btname." + outputKey, null);
        return name == null ? outputKey : name;
    }

    static void rememberBluetooth(Outputs.Output output) {
        SharedPreferences saved = preferences;
        if (saved == null || output == null || !output.bluetooth) return;
        if (!output.name.equals(saved.getString("btname." + output.key, null)))
            saved.edit().putString("btname." + output.key, output.name).apply();
    }

    static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    static String semitones(int value) {
        if (value == 0) return Strings.t("Original key");
        String amount = (value > 0 ? "+" : "−") + Math.abs(value);
        return Strings.f(Math.abs(value) == 1 ? "{0} semitone" : "{0} semitones", amount);
    }

    static String lufs(int value) {
        String level = String.format(Locale.ROOT, "%d LUFS", value).replace('-', '−');
        return value == HOST_TARGET_LUFS ? Strings.f("{0} (BitChord’s level)", level) : level;
    }

    static String seconds(int value) {
        return Strings.f("{0} s", String.valueOf(value));
    }

    // ── The audio tap: Sing, headphone correction and DJ mode ────────────────

    private static volatile Field samplesField, framesField, channelsField, sampleRateField;

    /**
     * Called by BitChord's own audio sink as it hands a block to its effects chain, for the song
     * that plays and for Automix's loop mix. The sample rate is read from the sink.
     */
    public static void process(Object sink, Object block) {
        if (block == null || sink == null) return;
        if (!(singEnabled && singLevel > 0)
                && !(headphoneApplied() && HeadphoneEq.active())
                && !djEnabled()) {
            if (!singers.isEmpty()) singers.remove(sink);
            return;
        }
        int rate;
        try {
            Field field = sampleRateField;
            if (field == null) {
                field = sink.getClass().getDeclaredField("configuredSampleRate");
                field.setAccessible(true);
                sampleRateField = field;
            }
            rate = field.getInt(sink);
        } catch (Exception e) {
            HostLog.d("Playback extras can't read the sample rate: " + e);
            singLevel = 0;
            return;
        }
        if (rate > 0) process(sink, block, rate);
    }

    private static boolean djEnabled() {
        return djApplied() && DjMode.enabled;
    }

    /**
     * Called by BitChord's own audio sink with each decoded block, before its equaliser and
     * effects, when it plays in its precision mode. The block holds interleaved 32-bit float
     * samples.
     */
    public static void process(Object sink, Object block, int sampleRate) {
        if (block == null) return;
        boolean sing = singEnabled && singLevel > 0;
        boolean headphones = headphoneApplied() && HeadphoneEq.active();
        boolean dj = djApplied() && DjMode.enabled;
        if (!sing && !headphones && !dj) {
            if (!singers.isEmpty()) singers.remove(sink);
            return;
        }
        try {
            if (samplesField == null) fields(block.getClass());
            int channels = channelsField.getInt(block);
            float[] samples = (float[]) samplesField.get(block);
            int frames = framesField.getInt(block);
            if (samples == null || frames <= 0 || channels <= 0) return;
            int count = Math.min(samples.length, frames * channels);
            if (dj) DjMode.tap(sink, samples, count, channels, sampleRate);
            if (sing && channels == 2)
                singer(sink, sampleRate).process(samples, count, singLevel / 100f);
            if (headphones) HeadphoneEq.process(sink, samples, count, channels, sampleRate);
        } catch (Exception e) {
            HostLog.d("Playback extras stopped on this block: " + e);
            singLevel = 0;
        }
    }

    private static final ThreadLocal<float[]> scratch = new ThreadLocal<>();

    /**
     * Called by BitChord's equaliser with the 16-bit block it has just written, in BitChord's usual
     * mode, where its effects run inside Android's media player. The block is changed in place,
     * from its position to its limit.
     */
    public static void processPcm16(Object equaliser, ByteBuffer block, int channels, int rate) {
        if (block == null || channels < 1 || channels > 2) return;
        boolean sing = singEnabled && singLevel > 0 && channels == 2;
        boolean headphones = headphoneApplied() && HeadphoneEq.active();
        boolean dj = djApplied() && DjMode.enabled;
        if (!sing && !headphones && !dj) {
            if (!sing && !singers.isEmpty()) singers.remove(equaliser);
            return;
        }
        try {
            ByteBuffer pcm = block.duplicate().order(ByteOrder.nativeOrder());
            int start = pcm.position(), count = pcm.remaining() / (2 * channels) * channels;
            if (count == 0) return;
            float[] samples = scratch.get();
            if (samples == null || samples.length < count) {
                samples = new float[Math.max(count, 8_192)];
                scratch.set(samples);
            }
            for (int i = 0; i < count; i++) samples[i] = pcm.getShort(start + 2 * i) / 32768f;
            if (dj) DjMode.tap(equaliser, samples, count, channels, rate);
            if (!sing && !headphones) return;
            if (sing) singer(equaliser, rate).process(samples, count, singLevel / 100f);
            if (headphones) HeadphoneEq.process(equaliser, samples, count, channels, rate);
            for (int i = 0; i < count; i++)
                pcm.putShort(
                        start + 2 * i,
                        (short) Math.max(-32768, Math.min(32767, Math.round(samples[i] * 32768f))));
        } catch (RuntimeException e) {
            HostLog.d("Playback extras stopped on this block: " + e);
            singLevel = 0;
        }
    }

    private static VocalCut singer(Object key, int sampleRate) {
        VocalCut singer = singers.get(key);
        if (singer == null) {
            singer = new VocalCut(sampleRate);
            singers.put(key, singer);
            HostLog.d("Sing on, " + sampleRate + " Hz");
        } else if (singer.sampleRate != Math.max(8_000, sampleRate)) {
            singer.configure(sampleRate);
        }
        if (singer.method != singMethod) {
            singer.method = singMethod;
            singer.reset();
        }
        return singer;
    }

    /**
     * BitChord's audio block, after R8 renamed it: {@code children} holds the samples, {@code
     * terminalBitCount} the number of frames and {@code symbol} the number of channels.
     */
    private static synchronized void fields(Class<?> type) throws NoSuchFieldException {
        Field samples = type.getDeclaredField("children");
        Field frames = type.getDeclaredField("terminalBitCount");
        Field channels = type.getDeclaredField("symbol");
        samples.setAccessible(true);
        frames.setAccessible(true);
        channels.setAccessible(true);
        framesField = frames;
        channelsField = channels;
        samplesField = samples;
    }

    /** The sink or equaliser was flushed or reset: a seek or a new song. */
    public static void flush(Object sink) {
        VocalCut singer = singers.get(sink);
        if (singer != null) singer.reset();
        if (headphoneApplied()) HeadphoneEq.flush(sink);
    }

    // ── Loudness level ───────────────────────────────────────────────────────

    /**
     * BitChord's loudness gain for a song in millibels, which brings it to -14 LUFS, moved to the
     * chosen level. BitChord allows from -15 to +3 dB; the limit on the side the level moves to
     * moves with it.
     */
    public static Integer loudness(Integer millibels) {
        if (millibels == null) return null;
        int shift = ((loudnessEnabled ? targetLufs : HOST_TARGET_LUFS) - HOST_TARGET_LUFS) * 100;
        if (shift == 0) return millibels;
        return clamp(millibels + shift, -1500 + Math.min(0, shift), 300 + Math.max(0, shift));
    }

    // ── Players ──────────────────────────────────────────────────────────────

    /** Each player BitChord builds; it keeps two, for crossfades and version swaps. */
    public static void player(Player player) {
        if (player == null) return;
        synchronized (players) {
            players.removeIf(reference -> reference.get() == null);
            players.add(new WeakReference<>(player));
        }
        if (sleepFadeApplied() || djApplied() || batterySaverApplied())
            try {
                player.addListener(new Watcher(player));
            } catch (RuntimeException | LinkageError e) {
                HostLog.d("Playback extras couldn't watch the player: " + e);
            }
        if (pitchApplied()) applyPitch(player);
        if (batterySaverApplied()) applyOffload(player);
    }

    /** BitChord's playback service, from its start. */
    public static void service(Object playbackService) {
        if (playbackService instanceof Context) init((Context) playbackService);
        if (djApplied()) DjMode.service(playbackService);
    }

    /** BitChord's output picker has applied its route, including already-connected devices. */
    public static void outputChanged() {
        Outputs.refresh();
    }

    /** What the patches do as a player moves from song to song. */
    private static final class Watcher implements Player.Listener {
        private final Player player;
        private final Handler handler;
        private final Runnable endingCheck = this::checkEnding;

        Watcher(Player player) {
            this.player = player;
            Looper looper = player.getApplicationLooper();
            this.handler = new Handler(looper == null ? Looper.getMainLooper() : looper);
        }

        // R8 reordered the pinned APK's callback. Keep the source-order overload too so
        // framework tests against the published Media3 API exercise the same handler.
        public void onMediaItemTransition(MediaItem mediaItem, int reason) {
            onMediaItemTransition(reason, mediaItem);
        }

        public void onMediaItemTransition(int reason, MediaItem mediaItem) {
            Outputs.refresh();
            if (djApplied()) {
                DjMode.pollSoon();
                DjMode.importHost();
                if (DjMode.isSessionPlayer(player)) DjMode.arrange(player);
            }
            if (sleepFadeApplied()) {
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
                    SleepFade.cancel(player);
                scheduleEnding();
            }
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            if (sleepFadeApplied()) {
                if (!isPlaying && !SleepFade.active()) handler.removeCallbacks(endingCheck);
                else scheduleEnding();
            }
        }

        public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
            onPlayWhenReadyChanged(reason, playWhenReady);
        }

        // As above, these callbacks use the pinned APK's R8 argument order.
        public void onPlayWhenReadyChanged(int reason, boolean playWhenReady) {
            cancelEnding();
        }

        @Override
        public void onPositionDiscontinuity(int reason) {
            cancelEnding();
        }

        public void onPositionDiscontinuity(
                Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
            onPositionDiscontinuity(reason, oldPosition, newPosition);
        }

        public void onPositionDiscontinuity(
                int reason, Player.PositionInfo oldPosition, Player.PositionInfo newPosition) {
            cancelEnding();
        }

        private void cancelEnding() {
            if (!sleepFadeApplied()) return;
            handler.removeCallbacks(endingCheck);
            SleepFade.cancel(player);
        }

        @Override
        public void onPlaybackParametersChanged(PlaybackParameters parameters) {
            if (batterySaverApplied()) applyOffload(player);
        }

        private void scheduleEnding() {
            handler.removeCallbacks(endingCheck);
            if (sleepFade && sleepFadeEnding && player.isPlaying())
                handler.postDelayed(endingCheck, 1_000);
        }

        /** "Sleep after this song": start the fade so it ends with the song. */
        private void checkEnding() {
            try {
                if (!sleepFade || !sleepFadeEnding || !player.isPlaying()) return;
                if (!SleepFade.afterTrack()) {
                    handler.postDelayed(endingCheck, 5_000);
                    return;
                }
                long left = player.getDuration() - player.getCurrentPosition();
                if (left > 0 && left <= sleepFadeSeconds * 1000L + 500) {
                    SleepFade.fadeEnding(
                            player, (int) Math.max(1, Math.min(sleepFadeSeconds, left / 1000)));
                    return;
                }
                long wait =
                        left > 0
                                ? Math.max(200, Math.min(1_000, left - sleepFadeSeconds * 1000L))
                                : 1_000;
                handler.postDelayed(endingCheck, wait);
            } catch (RuntimeException e) {
                // The player is gone.
            }
        }
    }

    private interface Change {
        void apply(Player player);
    }

    private static void onPlayers(Change change) {
        List<Player> live = new ArrayList<>();
        synchronized (players) {
            for (WeakReference<Player> reference : players) {
                Player player = reference.get();
                if (player != null) live.add(player);
            }
        }
        for (Player player : live) {
            Runnable run =
                    () -> {
                        try {
                            change.apply(player);
                        } catch (RuntimeException e) {
                            HostLog.d("Playback extras couldn't change the player: " + e);
                        }
                    };
            Looper looper = player.getApplicationLooper();
            if (looper == Looper.myLooper()) run.run();
            else new Handler(looper).post(run);
        }
    }

    // ── Pitch ────────────────────────────────────────────────────────────────

    static float pitchFactor(int semitones) {
        return (float) Math.pow(2, semitones / 12.0);
    }

    /**
     * Keeps the speed and sets the pitch. BitChord's speed control keeps the pitch that's set, so
     * the two stay separate.
     */
    private static void applyPitch(Player player) {
        PlaybackParameters now = player.getPlaybackParameters();
        float pitch = pitchFactor(pitchEnabled ? pitchSemitones : 0);
        if (now != null && now.pitch == pitch) return;
        player.setPlaybackParameters(new PlaybackParameters(now == null ? 1f : now.speed, pitch));
    }

    // ── Battery saver ────────────────────────────────────────────────────────

    /** Why the audio chip isn't asked for right now, or null when it is. */
    static volatile String offloadHeldBack;

    /**
     * Whether Battery saver should ask for the audio chip now: not while an effect of the patches
     * needs the decoded audio, since the chip plays the file as it is.
     */
    static boolean offloadWanted() {
        String reason = null;
        if (batterySaver) {
            if (singEnabled && singLevel > 0) reason = Strings.t("Sing is on");
            else if (headphoneApplied() && HeadphoneEq.active())
                reason = Strings.t("Headphone correction is on");
            else if (jamesDspApplied() && EffectsApps.useJamesDsp)
                reason = Strings.t("JamesDSP has the audio");
            else if (djApplied() && DjMode.enabled) reason = Strings.t("DJ mode is listening");
            // The chip plays the file through Android's usual output, which a bit-perfect song
            // would leave.
            else if (BitPerfect.wanted()) reason = Strings.t("Bit-perfect output is on");
        }
        offloadHeldBack = reason;
        return batterySaver && reason == null;
    }

    /**
     * Battery saver: lets the phone's audio hardware decode the song, so the processor sleeps. The
     * equaliser, Sing and BitChord's other effects don't run then. Android decides which songs can
     * play this way; the rest play as before. A changed speed or pitch needs the chip to support
     * it, so it's asked for only then.
     */
    private static void applyOffload(Player player) {
        boolean wanted = offloadWanted();
        boolean changedSpeed = false;
        try {
            PlaybackParameters now = player.getPlaybackParameters();
            changedSpeed = now != null && (now.speed != 1f || now.pitch != 1f);
        } catch (RuntimeException ignored) {
            // Not a parameter the player has.
        }
        try {
            Method get = player.getClass().getMethod("getTrackSelectionParameters");
            Object parameters = get.invoke(player);
            Object builder = parameters.getClass().getMethod("buildUpon").invoke(parameters);
            Class<?> preferencesType =
                    Class.forName(
                            "androidx.media3.common.TrackSelectionParameters$AudioOffloadPreferences");
            Class<?> preferencesBuilderType =
                    Class.forName(
                            "androidx.media3.common.TrackSelectionParameters$AudioOffloadPreferences$Builder");
            Object preferencesBuilder =
                    preferencesBuilderType.getDeclaredConstructor().newInstance();
            // AUDIO_OFFLOAD_MODE_ENABLED is 1 and DISABLED 0. R8 removed the setters.
            set(preferencesBuilder, "audioOffloadMode", wanted ? 1 : 0);
            set(preferencesBuilder, "isGaplessSupportRequired", wanted);
            set(preferencesBuilder, "isSpeedChangeSupportRequired", wanted && changedSpeed);
            Constructor<?> make = preferencesType.getDeclaredConstructor(preferencesBuilderType);
            make.setAccessible(true);
            Object offload = make.newInstance(preferencesBuilder);
            Field field = null;
            for (Class<?> c = builder.getClass(); c != null && field == null; c = c.getSuperclass())
                try {
                    field = c.getDeclaredField("audioOffloadPreferences");
                } catch (NoSuchFieldException ignored) {
                    // Declared on a superclass.
                }
            if (field == null) throw new NoSuchFieldException("audioOffloadPreferences");
            field.setAccessible(true);
            Object current = field.get(builder);
            if (current != null && current.equals(offload)) return;
            field.set(builder, offload);
            Object built = builder.getClass().getMethod("build").invoke(builder);
            for (Method set : player.getClass().getMethods())
                if (set.getName().equals("setTrackSelectionParameters")
                        && set.getParameterTypes().length == 1) {
                    set.invoke(player, built);
                    HostLog.d(
                            "Battery saver "
                                    + (wanted ? "on" : "off")
                                    + (offloadHeldBack != null ? " (" + offloadHeldBack + ")" : "")
                                    + (changedSpeed ? ", speed changed" : ""));
                    return;
                }
            throw new NoSuchMethodException("setTrackSelectionParameters");
        } catch (Exception e) {
            if (batterySaver) HostLog.d("Battery saver unavailable: " + e);
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** Which formats this phone's audio chip can play by itself, for the page; empty if none. */
    static List<String> offloadFormats(Context context) {
        List<String> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 29 || context == null) return out;
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return out;
        AudioAttributes music =
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build();
        // Android's names for the encodings the chip can take: AAC-LC 10, MP3 9, Opus 20.
        String[][] formats = {{"AAC", "10"}, {"MP3", "9"}, {"Opus", "20"}};
        for (String[] format : formats) {
            try {
                AudioFormat audio =
                        new AudioFormat.Builder()
                                .setEncoding(Integer.parseInt(format[1]))
                                .setSampleRate(44_100)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .build();
                boolean yes;
                if (Build.VERSION.SDK_INT >= 31)
                    yes =
                            manager.getPlaybackOffloadSupport(audio, music)
                                    != AudioManager.PLAYBACK_OFFLOAD_NOT_SUPPORTED;
                else yes = AudioManager.isOffloadedPlaybackSupported(audio, music);
                if (yes) out.add(format[0]);
            } catch (RuntimeException | LinkageError ignored) {
                // An encoding this Android doesn't know, or an Android without the call.
            }
        }
        return out;
    }

    /** The decoder BitChord says it's using, or null: none means the chip plays the song. */
    static String hostDecoder() {
        try {
            Class<?> type = Class.forName("com.music.bitchord.playback.AudioOutputStatus");
            Field field = type.getDeclaredField("current");
            field.setAccessible(true);
            Object flow = field.get(null);
            Object snapshot =
                    flow == null ? null : flow.getClass().getMethod("getValue").invoke(flow);
            Object decoder = snapshot == null ? null : Outputs.read(snapshot, "decoderName");
            return decoder instanceof String && !((String) decoder).isEmpty()
                    ? (String) decoder
                    : null;
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    // ── Sleep timer ──────────────────────────────────────────────────────────

    /**
     * Replaces the sleep timer's pause when its time is up: the song fades out first, then pauses,
     * and the volume goes back to where it was for the next time you play.
     */
    public static void sleepPause(Player player) {
        if (!sleepFade || player == null) {
            if (player != null) player.pause();
            return;
        }
        SleepFade.pauseSoon(player, sleepFadeSeconds);
    }

    /** Volume at {@code step} of {@code steps}: an even fade to the ear, not to the meter. */
    static float fade(int step, int steps) {
        return SleepFade.fade(step, steps);
    }

    // ── Lossless addons over Bluetooth ───────────────────────────────────────

    /**
     * BitChord leaves out addons marked lossless while the phone can't play lossless audio, such as
     * over Bluetooth. With this on for every Bluetooth output, or for the one in use, they stay in;
     * Bluetooth then gets the phone's best codec. Other outputs are left to BitChord.
     */
    public static boolean losslessAnywhere() {
        if (!losslessBluetoothEnabled) return false;
        if (losslessOverBluetooth) return true;
        Set<String> devices = losslessDevices;
        if (devices.isEmpty()) return false;
        Outputs.Output output = Outputs.current();
        return output.bluetooth && devices.contains(output.key);
    }
}
