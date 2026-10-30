package app.bitchord.telegram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * BitChord's bit-perfect output (from 1.9), which sends a song to a USB or built-in DAC unchanged.
 * Songs it plays skip the effects chain, so Sing, Headphone correction and the rest don't reach
 * them, and the player's volume isn't used.
 */
final class BitPerfect {
    private BitPerfect() {}

    private static volatile Field modeField, statusField, activeField;
    private static volatile Method valueMethod;

    /** True when the Bit-perfect output setting is anything but off. */
    static boolean wanted() {
        try {
            Field field = modeField;
            if (field == null) {
                field =
                        Class.forName("com.music.bitchord.data.settings.AppSettings")
                                .getDeclaredField("bitPerfectMode");
                field.setAccessible(true);
                modeField = field;
            }
            Object mode = value(field.get(null));
            return mode instanceof Enum && !((Enum<?>) mode).name().equals("OFF");
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    /** True while a song is playing through the bit-perfect output now. */
    static boolean playing() {
        try {
            Field field = statusField;
            if (field == null) {
                field =
                        Class.forName(
                                        "com.music.bitchord.playback.audio.bitperfect"
                                                + ".BitPerfectOutput")
                                .getDeclaredField("_status");
                field.setAccessible(true);
                statusField = field;
            }
            Object status = value(field.get(null));
            if (status == null) return false;
            Field active = activeField;
            if (active == null) {
                active = status.getClass().getDeclaredField("active");
                active.setAccessible(true);
                activeField = active;
            }
            return active.getBoolean(status);
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    private static Object value(Object flow) throws Exception {
        if (flow == null) return null;
        Method method = valueMethod;
        if (method == null) {
            method = flow.getClass().getMethod("getValue");
            method.setAccessible(true);
            valueMethod = method;
        }
        return method.invoke(flow);
    }
}
