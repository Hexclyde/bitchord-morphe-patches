package app.bitchord.telegram;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Equaliser per output: BitChord's equaliser settings are remembered for each output, and put back
 * when that output is in use again. Set the equaliser for the car with the car connected, for
 * earbuds with the earbuds connected, and each keeps its own from then on. A setting can also be
 * shared as a short code and pasted on another phone. The idea is from Prism by Yigder.
 *
 * <p>BitChord keeps its equaliser in its {@code bitchord_settings} preferences and in flows its
 * playback service watches; writing both is what BitChord's own setters do.
 */
final class OutputEq {
    private OutputEq() {}

    static final String HOST_PREFERENCES = "bitchord_settings";
    static final String PREFERENCES = "bitchord_output_eq";
    static final String CODE_PREFIX = "BCEQ1.";
    static final int BANDS = 7;

    /** BitChord's equaliser settings at one moment. */
    static final class Snapshot {
        boolean enabled, focused;
        String mode = "DYNAMIC";
        int toneX, toneY;
        float balance;
        float[] bands = new float[BANDS];

        String describe() {
            if (!enabled) return Strings.t("Equaliser off");
            if (mode.equals("MANUAL")) {
                StringBuilder b = new StringBuilder();
                for (float band : bands) {
                    if (b.length() > 0) b.append(' ');
                    b.append(String.format(Locale.ROOT, "%+.0f", band));
                }
                return Strings.f("Sliders {0}", b.toString());
            }
            return Strings.f("Tone pad {0}, {1}", String.valueOf(toneX), String.valueOf(toneY));
        }

        /** The code: version, mode, pad, focus, balance and the seven gains in tenths of a dB. */
        String code() {
            byte[] bytes = new byte[5 + BANDS];
            bytes[0] = (byte) (mode.equals("MANUAL") ? 1 : 0);
            bytes[1] = (byte) toneX;
            bytes[2] = (byte) toneY;
            bytes[3] = (byte) (focused ? 1 : 0);
            bytes[4] = (byte) Math.round(Math.max(-1f, Math.min(1f, balance)) * 100);
            for (int i = 0; i < BANDS; i++)
                bytes[5 + i] = (byte) Math.round(Math.max(-12f, Math.min(12f, bands[i])) * 10);
            return CODE_PREFIX
                    + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }

        /** The settings a code stands for, or null when it isn't one. Always enabled. */
        static Snapshot fromCode(String code) {
            if (code == null) return null;
            String text = code.trim();
            if (!text.startsWith(CODE_PREFIX)) return null;
            byte[] bytes;
            try {
                bytes =
                        java.util.Base64.getUrlDecoder()
                                .decode(text.substring(CODE_PREFIX.length()));
            } catch (IllegalArgumentException e) {
                return null;
            }
            if (bytes.length != 5 + BANDS) return null;
            Snapshot s = new Snapshot();
            s.enabled = true;
            s.mode = bytes[0] == 1 ? "MANUAL" : "DYNAMIC";
            s.toneX = bytes[1];
            s.toneY = bytes[2];
            s.focused = bytes[3] == 1;
            s.balance = bytes[4] / 100f;
            for (int i = 0; i < BANDS; i++) s.bands[i] = bytes[5 + i] / 10f;
            return s;
        }

        String toJson() {
            StringBuilder b = new StringBuilder();
            for (float band : bands) {
                if (b.length() > 0) b.append(',');
                b.append(String.format(Locale.ROOT, "%.2f", band));
            }
            return enabled
                    + "|"
                    + mode
                    + "|"
                    + toneX
                    + "|"
                    + toneY
                    + "|"
                    + focused
                    + "|"
                    + String.format(Locale.ROOT, "%.3f", balance)
                    + "|"
                    + b;
        }

        static Snapshot fromJson(String text) {
            if (text == null) return null;
            String[] parts = text.split("\\|", -1);
            if (parts.length != 7) return null;
            try {
                Snapshot s = new Snapshot();
                s.enabled = Boolean.parseBoolean(parts[0]);
                s.mode = parts[1].equals("MANUAL") ? "MANUAL" : "DYNAMIC";
                s.toneX = Integer.parseInt(parts[2]);
                s.toneY = Integer.parseInt(parts[3]);
                s.focused = Boolean.parseBoolean(parts[4]);
                s.balance = Float.parseFloat(parts[5]);
                s.bands = parseBands(parts[6]);
                return s;
            } catch (RuntimeException e) {
                return null;
            }
        }

        boolean sameAs(Snapshot other) {
            return other != null
                    && enabled == other.enabled
                    && focused == other.focused
                    && mode.equals(other.mode)
                    && toneX == other.toneX
                    && toneY == other.toneY
                    && Math.abs(balance - other.balance) < 1e-3
                    && Arrays.equals(bands, other.bands);
        }
    }

    static float[] parseBands(String text) {
        float[] out = new float[BANDS];
        if (text == null || text.isEmpty()) return out;
        String[] parts = text.split(",");
        for (int i = 0; i < Math.min(BANDS, parts.length); i++)
            try {
                out[i] = Math.max(-12f, Math.min(12f, Float.parseFloat(parts[i].trim())));
            } catch (NumberFormatException ignored) {
                // Stays flat.
            }
        return out;
    }

    // ── Reading and writing BitChord's settings ──────────────────────────────

    private static volatile SharedPreferences host, mine;
    private static volatile String currentOutput = "";
    private static volatile boolean applying;
    private static volatile boolean enabled = false;
    private static SharedPreferences.OnSharedPreferenceChangeListener watcher;

    static void init(Context context) {
        if (mine != null || context == null) return;
        Context app = context.getApplicationContext();
        host = app.getSharedPreferences(HOST_PREFERENCES, Context.MODE_PRIVATE);
        mine = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        enabled = mine.getBoolean("enabled", false);
        currentOutput = Outputs.current().key;
        // Held in a field: the preferences keep listeners weakly.
        watcher =
                (preferences, key) -> {
                    if (key != null && key.startsWith("equalizer_") && !applying) remember();
                };
        host.registerOnSharedPreferenceChangeListener(watcher);
        Outputs.listen(OutputEq::outputChanged);
    }

    static boolean enabled() {
        return enabled;
    }

    static void setEnabled(boolean on) {
        enabled = on;
        SharedPreferences saved = mine;
        if (saved != null) saved.edit().putBoolean("enabled", on).apply();
        if (on) remember();
    }

    /** BitChord's equaliser as it is now. */
    static Snapshot current() {
        SharedPreferences h = host;
        Snapshot s = new Snapshot();
        if (h == null) return s;
        s.enabled = h.getBoolean("equalizer_enabled", false);
        s.mode = "MANUAL".equals(h.getString("equalizer_mode", null)) ? "MANUAL" : "DYNAMIC";
        s.toneX = h.getInt("equalizer_tone_x", 0);
        s.toneY = h.getInt("equalizer_tone_y", 0);
        s.focused = h.getBoolean("equalizer_focused", false);
        s.balance = h.getFloat("equalizer_balance", 0f);
        s.bands = parseBands(h.getString("equalizer_bands", null));
        return s;
    }

    /** The settings saved for an output, or null. */
    static Snapshot saved(String outputKey) {
        SharedPreferences saved = mine;
        return saved == null ? null : Snapshot.fromJson(saved.getString("eq." + outputKey, null));
    }

    static List<String> savedOutputs() {
        SharedPreferences saved = mine;
        List<String> keys = new ArrayList<>();
        if (saved == null) return keys;
        for (String key : saved.getAll().keySet())
            if (key.startsWith("eq.")) keys.add(key.substring(3));
        java.util.Collections.sort(keys);
        return keys;
    }

    static String savedName(String outputKey) {
        SharedPreferences saved = mine;
        String name = saved == null ? null : saved.getString("name." + outputKey, null);
        return name != null ? name : outputKey;
    }

    static void forget(String outputKey) {
        SharedPreferences saved = mine;
        if (saved != null)
            saved.edit().remove("eq." + outputKey).remove("name." + outputKey).apply();
    }

    /** The equaliser was changed: keep it for the output in use. */
    static void remember() {
        if (!enabled) return;
        SharedPreferences saved = mine;
        if (saved == null) return;
        Outputs.Output output = Outputs.current();
        Snapshot now = current();
        saved.edit()
                .putString("eq." + output.key, now.toJson())
                .putString("name." + output.key, output.name)
                .apply();
    }

    private static void outputChanged() {
        Outputs.Output output = Outputs.current();
        if (output.key.equals(currentOutput)) return;
        currentOutput = output.key;
        if (!enabled) return;
        Snapshot wanted = saved(output.key);
        if (wanted == null) {
            // A new output starts from what's set now, and keeps its own from here.
            remember();
            return;
        }
        if (wanted.sameAs(current())) return;
        HostLog.d("Equaliser for " + output.name + ": " + wanted.describe());
        apply(wanted);
    }

    /** Puts a snapshot into BitChord: its flows and its preferences, as its setters do. */
    static void apply(Snapshot s) {
        Runnable run =
                () -> {
                    applying = true;
                    try {
                        SharedPreferences h = host;
                        if (h != null)
                            h.edit()
                                    .putBoolean("equalizer_enabled", s.enabled)
                                    .putString("equalizer_mode", s.mode)
                                    .putInt("equalizer_tone_x", s.toneX)
                                    .putInt("equalizer_tone_y", s.toneY)
                                    .putBoolean("equalizer_focused", s.focused)
                                    .putFloat("equalizer_balance", s.balance)
                                    .apply();
                        Object settings = settings();
                        if (settings != null) {
                            List<Float> bands = new ArrayList<>();
                            for (float band : s.bands) bands.add(band);
                            call(settings, "setEqualizerBands", List.class, bands);
                            call(settings, "setEqualizerFocused", boolean.class, s.focused);
                            setFlow(settings, "equalizerToneX", s.toneX);
                            setFlow(settings, "equalizerToneY", s.toneY);
                            setFlow(settings, "equalizerBalance", s.balance);
                            setFlow(settings, "equalizerMode", mode(s.mode));
                            call(settings, "setEqualizerEnabled", boolean.class, s.enabled);
                        }
                    } catch (Exception | LinkageError e) {
                        HostLog.d("Equaliser per output couldn't apply: " + e);
                    } finally {
                        applying = false;
                    }
                };
        if (Looper.myLooper() == Looper.getMainLooper()) run.run();
        else new Handler(Looper.getMainLooper()).post(run);
    }

    private static Object settings() throws Exception {
        Class<?> type = Class.forName("com.music.bitchord.data.settings.AppSettings");
        Field instance = type.getDeclaredField("INSTANCE");
        return instance.get(null);
    }

    private static Object mode(String name) throws Exception {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<? extends Enum> type =
                (Class<? extends Enum>)
                        Class.forName("com.music.bitchord.data.settings.EqualizerMode");
        @SuppressWarnings("unchecked")
        Object value = Enum.valueOf(type, name);
        return value;
    }

    private static void call(Object target, String name, Class<?> type, Object value)
            throws Exception {
        Method method = target.getClass().getMethod(name, type);
        method.invoke(target, value);
    }

    private static void setFlow(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        Object flow = field.get(target);
        if (flow == null) flow = field.get(null);
        if (flow == null) return;
        flow.getClass().getMethod("setValue", Object.class).invoke(flow, value);
    }
}
