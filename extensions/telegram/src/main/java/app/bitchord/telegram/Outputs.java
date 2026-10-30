package app.bitchord.telegram;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Which output the phone plays through right now: the speaker, wired or USB headphones, or a
 * Bluetooth device, named so settings can be kept per output. Follows BitChord's own choice when
 * one was made in its output picker, and otherwise the same order Android and BitChord use:
 * Bluetooth, then USB, then wired, then the speaker.
 */
final class Outputs {
    private Outputs() {}

    /** One output, with a key that stays the same each time it's connected. */
    static final class Output {
        final String key, name;
        final int type;
        final boolean bluetooth;

        Output(String key, String name, int type, boolean bluetooth) {
            this.key = key;
            this.name = name;
            this.type = type;
            this.bluetooth = bluetooth;
        }

        /** What kind of output it is, for the page. */
        String kind() {
            switch (type) {
                case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                    return Strings.t("Phone speaker");
                case AudioDeviceInfo.TYPE_USB_DEVICE:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                    return Strings.t("USB");
                case AudioDeviceInfo.TYPE_HDMI:
                case AudioDeviceInfo.TYPE_HDMI_ARC:
                case 29: // TYPE_HDMI_EARC
                    return "HDMI";
                default:
                    return bluetooth ? "Bluetooth" : Strings.t("Wired");
            }
        }

        @Override
        public String toString() {
            return name + " (" + key + ")";
        }
    }

    static final Output PHONE =
            new Output("phone", "Phone", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, false);

    private static volatile AudioManager manager;
    private static volatile Output current = PHONE;
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static volatile Field selectedIdField;

    static void init(Context context) {
        if (manager != null || context == null) return;
        AudioManager found =
                (AudioManager)
                        context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        if (found == null) return;
        manager = found;
        try {
            found.registerAudioDeviceCallback(
                    new AudioDeviceCallback() {
                        @Override
                        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                            refresh();
                        }

                        @Override
                        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                            refresh();
                        }
                    },
                    new Handler(Looper.getMainLooper()));
        } catch (RuntimeException e) {
            HostLog.d("Outputs: no device callback: " + e);
        }
        refresh();
    }

    /** The output in use now; the phone's speaker until something is known. */
    static Output current() {
        return current;
    }

    /** Called on the main thread whenever the output changes. */
    static void listen(Runnable listener) {
        listeners.add(listener);
    }

    /** Looks again: at a song change, or when BitChord's output picker may have been used. */
    static void refresh() {
        AudioManager found = manager;
        if (found == null) return;
        Output next;
        try {
            next = choose(found.getDevices(AudioManager.GET_DEVICES_OUTPUTS), selectedId());
        } catch (RuntimeException e) {
            return;
        }
        Output before = current;
        if (next.key.equals(before.key)) return;
        current = next;
        HostLog.d("Output: " + next);
        Runnable tell =
                () -> {
                    for (Runnable listener : listeners)
                        try {
                            listener.run();
                        } catch (RuntimeException e) {
                            HostLog.d("Output listener failed: " + e);
                        }
                };
        if (Looper.myLooper() == Looper.getMainLooper()) tell.run();
        else new Handler(Looper.getMainLooper()).post(tell);
    }

    /** The device BitChord's output picker chose, or null for the phone's own routing. */
    private static Integer selectedId() {
        try {
            Field field = selectedIdField;
            if (field == null) {
                Class<?> routing = Class.forName("com.music.bitchord.playback.AudioRouting");
                field = routing.getDeclaredField("selectedId");
                field.setAccessible(true);
                selectedIdField = field;
            }
            Object flow = field.get(null);
            Object value = flow == null ? null : flow.getClass().getMethod("getValue").invoke(flow);
            return value instanceof Integer ? (Integer) value : null;
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /** The output Android plays through, from the connected sinks. Pure, for tests. */
    static Output choose(AudioDeviceInfo[] devices, Integer selected) {
        List<AudioDeviceInfo> sinks = new ArrayList<>();
        for (AudioDeviceInfo device : devices) if (device.isSink()) sinks.add(device);
        if (selected != null)
            for (AudioDeviceInfo device : sinks)
                if (device.getId() == selected) return describe(device);
        int[][] order = {
            {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26, 27, 23
            }, // A2DP, BLE headset, BLE speaker, hearing aid
            {
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_ACCESSORY
            },
            {
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_LINE_ANALOG,
                AudioDeviceInfo.TYPE_LINE_DIGITAL,
                AudioDeviceInfo.TYPE_AUX_LINE
            },
            {AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, 29},
            {AudioDeviceInfo.TYPE_DOCK, 31},
        };
        for (int[] group : order)
            for (int type : group)
                for (AudioDeviceInfo device : sinks)
                    if (device.getType() == type) return describe(device);
        return PHONE;
    }

    static Output describe(AudioDeviceInfo device) {
        int type = device.getType();
        String name = String.valueOf(device.getProductName()).trim();
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                return PHONE;
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case 26:
            case 27:
            case 23:
                {
                    String address = Build.VERSION.SDK_INT >= 28 ? device.getAddress() : "";
                    String id = address != null && address.length() >= 11 ? address : name;
                    return new Output("bt:" + id, name.isEmpty() ? "Bluetooth" : name, type, true);
                }
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                return new Output("usb:" + name, name.isEmpty() ? "USB" : name, type, false);
            case AudioDeviceInfo.TYPE_HDMI:
            case AudioDeviceInfo.TYPE_HDMI_ARC:
            case 29:
                return new Output("hdmi", "HDMI", type, false);
            default:
                // Wired outputs are all named after the phone, so they share one key.
                return new Output("wired", Strings.t("Wired headphones"), type, false);
        }
    }

    // ── What BitChord knows about Bluetooth ──────────────────────────────────

    /** The Bluetooth codec Android named to BitChord, or null when it named none. */
    static String bluetoothCodec() {
        Object state = losslessState();
        return state == null ? null : (String) read(state, "bluetoothCodec");
    }

    /** True when a Bluetooth output is connected but BitChord couldn't read its codec. */
    static boolean bluetoothCodecUnknown() {
        Object state = losslessState();
        return state != null && Boolean.TRUE.equals(read(state, "bluetoothCodecUnknown"));
    }

    /** True when BitChord already counts the output as lossless-capable. */
    static boolean losslessCapable() {
        Object state = losslessState();
        return state != null && Boolean.TRUE.equals(read(state, "capable"));
    }

    private static Object losslessState() {
        try {
            Class<?> type = Class.forName("com.music.bitchord.playback.audio.LosslessOutput");
            Field field = type.getDeclaredField("state");
            field.setAccessible(true);
            Object flow = field.get(null);
            return flow == null ? null : flow.getClass().getMethod("getValue").invoke(flow);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    static Object read(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception e) {
            return null;
        }
    }
}
