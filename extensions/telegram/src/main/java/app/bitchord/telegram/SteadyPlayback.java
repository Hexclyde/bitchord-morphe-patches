package app.bitchord.telegram;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Steady playback, for travelling. BitChord's players stop reading ahead once 8 MB is buffered:
 * about four minutes of a normal YouTube stream, but only a minute of a lossless file. This lets
 * them read further ahead, through the rest of the song and into the next ones in the queue, with
 * separate limits for Wi-Fi and mobile data that follow the connection as it changes. And while
 * the phone has no connection at all, a song that runs out of buffer waits for the signal to come
 * back instead of stopping with an error.
 */
public final class SteadyPlayback {
    private SteadyPlayback() {}

    static final String PREFERENCES = "bitchord_steady_playback";
    static final int HOST_BUFFER_MB = 8;
    static final int[] WIFI_CHOICES = {16, 32, 64, 96};
    static final int[] MOBILE_CHOICES = {8, 16, 32, 64};

    /** Load errors BitChord allows before a song that ran dry stops, while offline. */
    static final int OFFLINE_RETRIES = 1_000;

    static volatile boolean enabled = false;
    static volatile int wifiMb = 64;
    static volatile int mobileMb = 32;

    private static volatile SharedPreferences preferences;
    private static volatile ConnectivityManager connectivity;
    private static volatile boolean registered;
    private static final List<WeakReference<Object>> controls = new ArrayList<>();

    static void init(Context context) {
        if (preferences != null || context == null) return;
        Context app = context.getApplicationContext();
        SharedPreferences saved = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        enabled = saved.getBoolean("enabled", false);
        wifiMb = nearest(saved.getInt("wifiMb", 64), WIFI_CHOICES);
        mobileMb = nearest(saved.getInt("mobileMb", 32), MOBILE_CHOICES);
        preferences = saved;
        connectivity = app.getSystemService(ConnectivityManager.class);
        listen();
    }

    static void setEnabled(boolean on) {
        enabled = on;
        save("enabled", on);
        applyAll();
    }

    static void setWifiMb(int mb) {
        wifiMb = nearest(mb, WIFI_CHOICES);
        save("wifiMb", wifiMb);
        applyAll();
    }

    static void setMobileMb(int mb) {
        mobileMb = nearest(mb, MOBILE_CHOICES);
        save("mobileMb", mobileMb);
        applyAll();
    }

    private static void save(String key, Object value) {
        SharedPreferences saved = preferences;
        if (saved == null) return;
        SharedPreferences.Editor edit = saved.edit();
        if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
        else edit.putInt(key, (Integer) value);
        edit.apply();
    }

    static int nearest(int value, int[] choices) {
        int best = choices[0];
        for (int choice : choices)
            if (Math.abs(choice - value) < Math.abs(best - value)) best = choice;
        return best;
    }

    // ── The connection ──────────────────────────────────────────────────────

    /** Wi-Fi, Ethernet or another unmetered network, mobile data, or nothing. */
    enum Link {
        UNMETERED,
        METERED,
        NONE
    }

    static Link link() {
        ConnectivityManager manager = connectivity;
        if (manager == null) return Link.UNMETERED;
        try {
            Network network = manager.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : manager.getNetworkCapabilities(network);
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                return Link.NONE;
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    ? Link.UNMETERED
                    : Link.METERED;
        } catch (RuntimeException e) {
            return Link.UNMETERED;
        }
    }

    /** The read-ahead limit in bytes for a connection, or BitChord's own when this is off. */
    static int targetBytes(boolean on, Link link, int wifi, int mobile) {
        if (!on) return HOST_BUFFER_MB << 20;
        // With no connection there's nothing to fetch; keep the larger limit for what's buffered.
        int mb = link == Link.METERED ? mobile : wifi;
        return Math.max(HOST_BUFFER_MB, mb) << 20;
    }

    private static void listen() {
        ConnectivityManager manager = connectivity;
        if (manager == null || registered) return;
        try {
            manager.registerDefaultNetworkCallback(
                    new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                            applyAll();
                        }

                        @Override
                        public void onLost(Network network) {
                            applyAll();
                        }
                    });
            registered = true;
        } catch (RuntimeException e) {
            HostLog.d("Steady playback can't follow the connection: " + e);
        }
    }

    // ── Hooks ───────────────────────────────────────────────────────────────

    /** Each load control BitChord builds for a player. */
    public static void loadControl(Object control) {
        if (control == null) return;
        synchronized (controls) {
            controls.removeIf(reference -> reference.get() == null);
            controls.add(new WeakReference<>(control));
        }
        apply(control);
    }

    /**
     * How many load errors a song may have before it stops, when it has run out of buffer.
     * BitChord allows 3; with no connection at all this waits for the signal instead.
     */
    public static int minRetries(int bitchords) {
        if (!enabled || link() != Link.NONE) return bitchords;
        return Math.max(bitchords, OFFLINE_RETRIES);
    }

    private static void applyAll() {
        List<Object> live = new ArrayList<>();
        synchronized (controls) {
            for (WeakReference<Object> reference : controls) {
                Object control = reference.get();
                if (control != null) live.add(control);
            }
        }
        for (Object control : live) apply(control);
    }

    private static void apply(Object control) {
        int bytes = targetBytes(enabled, link(), wifiMb, mobileMb);
        try {
            Class<?> type = control.getClass();
            // Players prepared from now on.
            Field overwrite = type.getDeclaredField("targetBufferBytesOverwrite");
            overwrite.setAccessible(true);
            overwrite.setInt(control, bytes);
            // Players already playing.
            Field states = type.getDeclaredField("loadingStates");
            states.setAccessible(true);
            Object map = states.get(control);
            if (map instanceof Map)
                for (Object state : ((Map<?, ?>) map).values()) {
                    Field target = state.getClass().getDeclaredField("targetBufferBytes");
                    target.setAccessible(true);
                    target.setInt(state, bytes);
                }
            Method update = type.getDeclaredMethod("updateAllocator");
            update.setAccessible(true);
            update.invoke(control);
        } catch (Exception | LinkageError e) {
            HostLog.d("Steady playback couldn't change the read-ahead: " + e);
        }
    }
}
