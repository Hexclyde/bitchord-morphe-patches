package app.bitchord.telegram;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The Playback extras patches, each added on its own: Sing, Pitch control, Loudness level, Sleep
 * timer fade-out, Battery saver and Lossless addons over Bluetooth. Each is called from one place
 * in BitChord's playback code and leaves it exactly as it was while its setting is at the default.
 *
 * <p>Sing is ported from Auris by OreoMuncher45 (see {@link VocalCut}). The pitch, loudness level
 * and battery saver settings follow ideas from Auris, Prism by Yigder and Stash by Rawnald Clark;
 * the code here is written for BitChord.
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
    static volatile boolean sleepFade = true;
    static volatile boolean batterySaver = false;
    static volatile boolean losslessOverBluetooth = false;

    private static volatile SharedPreferences preferences;
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

    // ── Settings ─────────────────────────────────────────────────────────────

    public static void init(Context context) {
        if (preferences != null || context == null) return;
        SharedPreferences saved =
                context.getApplicationContext()
                        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        singLevel = clamp(saved.getInt("singLevel", 0), 0, 100);
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
        sleepFade = saved.getBoolean("sleepFade", true);
        batterySaver = saved.getBoolean("batterySaver", false);
        losslessOverBluetooth = saved.getBoolean("losslessOverBluetooth", false);
        preferences = saved;
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
        singLevel = clamp(level, 0, 100);
        save("singLevel", singLevel);
    }

    static void setSingMethod(int method) {
        singMethod = method == VocalCut.CENTRE ? VocalCut.CENTRE : VocalCut.VOICE_GATE;
        save("singMethod", singMethod);
    }

    static void setPitch(int semitones) {
        pitchSemitones = clamp(semitones, MIN_PITCH, MAX_PITCH);
        save("pitch", pitchSemitones);
        onPlayers(SoundExtras::applyPitch);
    }

    static void setTargetLufs(int lufs) {
        targetLufs = clamp(lufs, MIN_TARGET_LUFS, MAX_TARGET_LUFS);
        save("targetLufs", targetLufs);
    }

    static void setSleepFade(boolean on) {
        sleepFade = on;
        save("sleepFade", on);
    }

    static void setBatterySaver(boolean on) {
        batterySaver = on;
        save("batterySaver", on);
        onPlayers(SoundExtras::applyOffload);
    }

    static void setLosslessOverBluetooth(boolean on) {
        losslessOverBluetooth = on;
        save("losslessOverBluetooth", on);
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

    // ── Sing ─────────────────────────────────────────────────────────────────

    private static volatile Field samplesField, framesField, channelsField;

    /**
     * Called by BitChord's own audio sink with each decoded block, before its equaliser and
     * effects, when it plays in its precision mode. The block holds interleaved 32-bit float
     * samples.
     */
    public static void process(Object sink, Object block, int sampleRate) {
        int level = singLevel;
        if (level <= 0 || block == null) {
            if (!singers.isEmpty()) singers.remove(sink);
            return;
        }
        try {
            if (samplesField == null) fields(block.getClass());
            if ((int) channelsField.getInt(block) != 2) return;
            float[] samples = (float[]) samplesField.get(block);
            int frames = framesField.getInt(block);
            if (samples == null || frames <= 0) return;
            singer(sink, sampleRate)
                    .process(samples, Math.min(samples.length, frames * 2), level / 100f);
        } catch (Exception e) {
            HostLog.d("Sing stopped: " + e);
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
        int level = singLevel;
        if (level <= 0 || block == null || channels != 2) {
            if (level <= 0 && !singers.isEmpty()) singers.remove(equaliser);
            return;
        }
        try {
            ByteBuffer pcm = block.duplicate().order(ByteOrder.nativeOrder());
            int start = pcm.position(), count = pcm.remaining() / 4 * 2;
            if (count == 0) return;
            float[] samples = scratch.get();
            if (samples == null || samples.length < count) {
                samples = new float[Math.max(count, 8_192)];
                scratch.set(samples);
            }
            for (int i = 0; i < count; i++) samples[i] = pcm.getShort(start + 2 * i) / 32768f;
            singer(equaliser, rate).process(samples, count, level / 100f);
            for (int i = 0; i < count; i++)
                pcm.putShort(
                        start + 2 * i,
                        (short) Math.max(-32768, Math.min(32767, Math.round(samples[i] * 32768f))));
        } catch (RuntimeException e) {
            HostLog.d("Sing stopped: " + e);
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
    }

    // ── Loudness level ───────────────────────────────────────────────────────

    /**
     * BitChord's loudness gain for a song in millibels, which brings it to -14 LUFS, moved to the
     * chosen level. BitChord allows from -15 to +3 dB; the limit on the side the level moves to
     * moves with it.
     */
    public static Integer loudness(Integer millibels) {
        if (millibels == null) return null;
        int shift = (targetLufs - HOST_TARGET_LUFS) * 100;
        if (shift == 0) return millibels;
        return clamp(millibels + shift, -1500 + Math.min(0, shift), 300 + Math.max(0, shift));
    }

    // ── Players: pitch and battery saver ─────────────────────────────────────

    /** Each player BitChord builds; it keeps two, for crossfades and version swaps. */
    public static void player(Player player) {
        init(ExtrasUi.context);
        if (player == null) return;
        synchronized (players) {
            players.removeIf(reference -> reference.get() == null);
            players.add(new WeakReference<>(player));
        }
        if (pitchApplied()) applyPitch(player);
        if (batterySaverApplied()) applyOffload(player);
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

    static float pitchFactor(int semitones) {
        return (float) Math.pow(2, semitones / 12.0);
    }

    /**
     * Keeps the speed and sets the pitch. BitChord's speed control keeps the pitch that's set, so
     * the two stay separate.
     */
    private static void applyPitch(Player player) {
        PlaybackParameters now = player.getPlaybackParameters();
        float pitch = pitchFactor(pitchSemitones);
        if (now != null && now.pitch == pitch) return;
        player.setPlaybackParameters(new PlaybackParameters(now == null ? 1f : now.speed, pitch));
    }

    /**
     * Battery saver: lets the phone's audio hardware decode the song, so the processor sleeps. The
     * equaliser, Sing and BitChord's other effects don't run then. Android decides which songs can
     * play this way; the rest play as before.
     */
    private static void applyOffload(Player player) {
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
            set(preferencesBuilder, "audioOffloadMode", batterySaver ? 1 : 0);
            set(preferencesBuilder, "isGaplessSupportRequired", batterySaver);
            Constructor<?> make = preferencesType.getDeclaredConstructor(preferencesBuilderType);
            make.setAccessible(true);
            Object offload = make.newInstance(preferencesBuilder);
            Class<?> builderType = builder.getClass();
            Field field = null;
            for (Class<?> c = builderType; c != null && field == null; c = c.getSuperclass())
                try {
                    field = c.getDeclaredField("audioOffloadPreferences");
                } catch (NoSuchFieldException ignored) {
                    // Declared on a superclass.
                }
            if (field == null) throw new NoSuchFieldException("audioOffloadPreferences");
            field.setAccessible(true);
            field.set(builder, offload);
            Object built = builderType.getMethod("build").invoke(builder);
            for (Method set : player.getClass().getMethods())
                if (set.getName().equals("setTrackSelectionParameters")
                        && set.getParameterTypes().length == 1) {
                    set.invoke(player, built);
                    HostLog.d("Battery saver " + (batterySaver ? "on" : "off"));
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

    // ── Sleep timer ──────────────────────────────────────────────────────────

    /**
     * Replaces the sleep timer's pause when its time is up: the song fades out over ten seconds
     * first, then pauses, and the volume goes back to where it was for the next time you play.
     */
    public static void sleepPause(Player player) {
        if (!sleepFade || player == null) {
            if (player != null) player.pause();
            return;
        }
        Looper looper = player.getApplicationLooper();
        Handler handler = new Handler(looper == null ? Looper.getMainLooper() : looper);
        float start = player.getVolume();
        int steps = FADE_SECONDS * 10;
        HostLog.d("Sleep timer: fading out");
        handler.post(
                new Runnable() {
                    int step = 0;

                    public void run() {
                        try {
                            if (!player.isPlaying() || ++step >= steps) {
                                player.pause();
                                player.setVolume(start);
                                return;
                            }
                            player.setVolume(start * fade(step, steps));
                            handler.postDelayed(this, 100);
                        } catch (RuntimeException e) {
                            player.pause();
                            player.setVolume(start);
                        }
                    }
                });
    }

    /** Volume at {@code step} of {@code steps}: an even fade to the ear, not to the meter. */
    static float fade(int step, int steps) {
        float left = 1f - (float) step / steps;
        return Math.max(0f, left * left);
    }

    // ── Lossless addons over Bluetooth ───────────────────────────────────────

    /**
     * BitChord leaves out addons marked lossless while the phone can't play lossless audio, such as
     * over Bluetooth. With this on they stay in; Bluetooth then gets the phone's best codec.
     */
    public static boolean losslessAnywhere() {
        return losslessOverBluetooth;
    }
}
