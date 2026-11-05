package app.bitchord.telegram;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Steady playback, for travelling. BitChord's players stop reading ahead once 8 MB is buffered:
 * about four minutes of a normal YouTube stream, but only a minute of a lossless file. This lets
 * them read further ahead, through the rest of the song and into the next ones in the queue, with
 * separate limits for Wi-Fi and mobile data that follow the connection as it changes. And while the
 * phone has no working connection, a song that runs out of buffer waits for the signal to come back
 * instead of stopping with an error.
 *
 * <p>The players share a heap allowance, including animated artwork. Playback threads only read
 * what Android's network callback last said; asking Android itself would block them while they're
 * already short of audio.
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

    /** Until Android says otherwise: the smaller limit, and BitChord's own retries. */
    private static volatile Link currentLink = Link.METERED;

    private static volatile boolean online = true;
    private static boolean blocked;

    /** The load controls of BitChord's own players. Any other keeps the limit it was built with. */
    private static final Map<Object, Integer> controls = new java.util.WeakHashMap<>();

    private static final Map<Object, Boolean> observedControls = new java.util.WeakHashMap<>();
    private static final List<AnalysisLease> analysisLeases = new ArrayList<>();
    private static int residentHolds;
    private static boolean gateObserved;
    private static long configurationEpoch;
    static LongSupplier heapBytes = () -> Runtime.getRuntime().maxMemory();
    private static final long READY_US = 2_000_000;
    private static final int AUXILIARY_BYTES = 16 << 20;

    static void init(Context context) {
        if (preferences != null || context == null) return;
        Context app = context.getApplicationContext();
        SharedPreferences saved = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        enabled = saved.getBoolean("enabled", false);
        wifiMb = nearest(saved.getInt("wifiMb", 64), WIFI_CHOICES);
        mobileMb = nearest(saved.getInt("mobileMb", 32), MOBILE_CHOICES);
        preferences = saved;
        connectivity = app.getSystemService(ConnectivityManager.class);
        readConnection();
        listen();
        applyAll();
    }

    static void setEnabled(boolean on) {
        synchronized (controls) {
            if (enabled != on) configurationEpoch++;
            enabled = on;
            if (!on) cancelAnalysis();
        }
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

    /** What Android last said; never asks it. */
    static Link link() {
        return currentLink;
    }

    /** Whether the connection has been shown to reach the internet and this app may use it. */
    static boolean online() {
        return online;
    }

    /**
     * A network counts as working once Android has validated it. A captive portal or a Wi-Fi
     * network with nothing behind it is still metered or not, for the read-ahead, but it isn't
     * online.
     */
    static synchronized void observed(boolean internet, boolean validated, boolean unmetered) {
        online = internet && validated && !blocked;
        Link next = !internet ? Link.NONE : unmetered ? Link.UNMETERED : Link.METERED;
        if (next == currentLink) return;
        currentLink = next;
        applyAll();
    }

    private static void observed(NetworkCapabilities caps) {
        observed(
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
    }

    private static synchronized void setBlocked(boolean value, NetworkCapabilities caps) {
        blocked = value;
        if (caps != null) observed(caps);
        else online = online && !value;
    }

    /** Once, as this starts and off every playback thread. Unreadable stays at the safe default. */
    private static void readConnection() {
        ConnectivityManager manager = connectivity;
        if (manager == null) return;
        try {
            Network network = manager.getActiveNetwork();
            NetworkCapabilities caps =
                    network == null ? null : manager.getNetworkCapabilities(network);
            if (caps == null) observed(false, false, false);
            else observed(caps);
        } catch (RuntimeException e) {
            HostLog.d("Steady playback couldn't read the connection: " + e);
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
                        private volatile NetworkCapabilities last;

                        @Override
                        public void onCapabilitiesChanged(
                                Network network, NetworkCapabilities caps) {
                            last = caps;
                            observed(caps);
                        }

                        @Override
                        public void onBlockedStatusChanged(Network network, boolean isBlocked) {
                            setBlocked(isBlocked, last);
                        }

                        @Override
                        public void onLost(Network network) {
                            last = null;
                            setBlocked(false, null);
                            observed(false, false, false);
                        }
                    });
            registered = true;
        } catch (RuntimeException e) {
            // Nothing will report a change, so a value read once can't be trusted for long.
            synchronized (SteadyPlayback.class) {
                online = true;
                blocked = false;
                currentLink = Link.METERED;
                applyAll();
            }
            HostLog.d("Steady playback can't follow the connection: " + e);
        }
    }

    // ── Hooks ───────────────────────────────────────────────────────────────

    /** Each load control BitChord builds for a player. */
    public static void loadControl(Object control) {
        if (control == null) return;
        synchronized (controls) {
            if (!observedControls.containsKey(control)) cancelAnalysis();
            controls.put(control, -1);
            observedControls.put(control, true);
            configurationEpoch++;
            controls.replaceAll((owner, previous) -> -1);
        }
        tick(control);
    }

    /**
     * The load errors of one load on one playback thread. BitChord has one retry policy for all its
     * players, and Media3 asks that policy for the allowance without saying which load it's for,
     * but a player's loads and their error checks all run on its own playback thread.
     */
    private static final class ErrorRun {
        int lastCount, offlineErrors;
    }

    private static final ThreadLocal<ErrorRun> runs = new ThreadLocal<>();

    /**
     * Each load error, with Media3's count for it. That count rises through one load, and starts
     * again for a new load or once a load has made progress, so a count that doesn't rise means the
     * errors from the last outage are over.
     */
    public static void loadError(int count) {
        ErrorRun run = runs.get();
        if (run == null) {
            if (!enabled) return;
            run = new ErrorRun();
            runs.set(run);
        }
        if (count <= run.lastCount) run.offlineErrors = 0;
        run.lastCount = count;
        if (enabled && !online)
            run.offlineErrors = Math.min(run.offlineErrors + 1, OFFLINE_RETRIES);
    }

    /**
     * How many load errors a song may have before it stops, when it has run out of buffer. BitChord
     * allows 3; with no working connection this waits for the signal instead. Media3 compares this
     * with the load's whole count, including what piled up offline, so when the signal returns
     * those errors stay excused: the load gets BitChord's usual allowance from there, and a server
     * that keeps refusing still stops it after the same number of errors as ever.
     */
    public static int minRetries(int bitchords) {
        if (enabled && !online) return Math.max(bitchords, OFFLINE_RETRIES);
        ErrorRun run = runs.get();
        return run == null ? bitchords : bitchords + run.offlineErrors;
    }

    private static void applyAll() {
        synchronized (controls) {
            controls.replaceAll((owner, previous) -> -1);
        }
    }

    /**
     * Runs at Media3's loading decision, on the load control's owning playback thread. That
     * decision is made for every load control in the app, and only the ones {@link #loadControl}
     * was given are this feature's to change: BitChord builds others with limits of their own.
     */
    public static void tick(Object control) {
        synchronized (controls) {
            if (!controls.containsKey(control)) return;
        }
        try {
            int bytes = targetBytes(enabled, currentLink, wifiMb, mobileMb);
            if (enabled || residentActive()) {
                bytes = Math.min(bytes, allowance(control));
                Object states = read(control, "loadingStates");
                int players = states instanceof Map ? Math.max(1, ((Map<?, ?>) states).size()) : 1;
                bytes = Math.max(1, bytes / players);
            }
            synchronized (controls) {
                Integer previous = controls.get(control);
                if (previous == null || previous == bytes) return;
                controls.put(control, bytes);
            }
            apply(control, bytes);
        } catch (Exception | LinkageError unavailable) {
            HostLog.d("Steady playback couldn't qualify the buffer allowance");
        }
    }

    private static Object read(Object value, String name) throws ReflectiveOperationException {
        Field field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(value);
    }

    private static List<Object> observed() {
        synchronized (controls) {
            return new ArrayList<>(observedControls.keySet());
        }
    }

    private static int allowance(Object control) {
        synchronized (controls) {
            long budget = loadingBudget();
            int primary = controls.size();
            int auxiliary = Math.max(0, observedControls.size() - primary);
            long auxiliaryShare =
                    Math.min(
                            AUXILIARY_BYTES,
                            Math.max(
                                    HOST_BUFFER_MB << 20,
                                    budget / Math.max(4, primary + auxiliary)));
            if (!controls.containsKey(control)) return (int) auxiliaryShare;
            long share = (budget - auxiliaryShare * auxiliary) / Math.max(1, primary);
            return (int) Math.min(Integer.MAX_VALUE, Math.max(HOST_BUFFER_MB << 20, share));
        }
    }

    private static long[] allocation(Object allocator) throws ReflectiveOperationException {
        synchronized (allocator) {
            long used = ((Number) read(allocator, "allocatedCount")).longValue();
            long pooled = ((Number) read(allocator, "availableCount")).longValue();
            long size = ((Number) read(allocator, "individualAllocationSize")).longValue();
            if (used < 0 || pooled < 0 || size <= 0)
                throw new IllegalStateException("Invalid native buffer allocation");
            return new long[] {Math.multiplyExact(used, size), Math.multiplyExact(pooled, size)};
        }
    }

    private static long retained(List<Object> known) throws ReflectiveOperationException {
        long total = 0;
        Map<Object, Boolean> counted = new IdentityHashMap<>();
        for (Object control : known) {
            Object allocator = read(control, "allocator");
            if (counted.put(allocator, true) == null) {
                long[] usage = allocation(allocator);
                total = Math.addExact(total, Math.addExact(usage[0], usage[1]));
            }
        }
        return total;
    }

    /** During DSP, only growth toward a temporary quarter-heap ceiling needs reserving. */
    static long analysisGrowth(long maximum) {
        long epoch;
        List<Object> known;
        synchronized (controls) {
            if (maximum <= 0 || !gateObserved || controls.isEmpty() || observedControls.isEmpty())
                return -1;
            epoch = configurationEpoch;
            known = new ArrayList<>(observedControls.keySet());
        }
        try {
            long bytes = retained(known);
            synchronized (controls) {
                if (configurationEpoch != epoch) return -1;
            }
            return Math.max(0, Math.min(maximum / 4, loadingBudget()) - bytes);
        } catch (Exception | LinkageError unavailable) {
            return -1;
        }
    }

    /** Pins the already-installed loading ceiling until the actual analysis work returns. */
    static final class AnalysisLease implements AutoCloseable, LongSupplier, BooleanSupplier {
        private final long budget;
        private volatile boolean cancelled, closed;

        private AnalysisLease(long maximum) {
            budget = maximum / 4;
        }

        @Override
        public long getAsLong() {
            if (closed || cancelled) return -1;
            try {
                return Math.max(0, Math.min(budget, loadingBudget()) - retained(observed()));
            } catch (Exception | LinkageError unavailable) {
                cancelled = true;
                return -1;
            }
        }

        @Override
        public boolean getAsBoolean() {
            return closed || cancelled;
        }

        @Override
        public void close() {
            synchronized (controls) {
                if (closed) return;
                closed = true;
                analysisLeases.remove(this);
                controls.replaceAll((owner, previous) -> -1);
            }
        }
    }

    static AnalysisLease analysisLease(long maximum) {
        long epoch;
        synchronized (controls) {
            epoch = configurationEpoch;
        }
        if (analysisGrowth(maximum) < 0) return null;
        synchronized (controls) {
            if (configurationEpoch != epoch) return null;
            AnalysisLease lease = new AnalysisLease(maximum);
            analysisLeases.add(lease);
            controls.replaceAll((owner, previous) -> -1);
            return lease;
        }
    }

    private static boolean analysisActive() {
        synchronized (controls) {
            return !analysisLeases.isEmpty();
        }
    }

    static boolean residentActive() {
        synchronized (controls) {
            return residentHolds > 0;
        }
    }

    static AutoCloseable holdResident() {
        synchronized (controls) {
            residentHolds++;
            configurationEpoch++;
            controls.replaceAll((owner, previous) -> -1);
        }
        return new AutoCloseable() {
            private boolean closed;

            public void close() {
                synchronized (controls) {
                    if (closed) return;
                    closed = true;
                    residentHolds--;
                    configurationEpoch++;
                    controls.replaceAll((owner, previous) -> -1);
                }
            }
        };
    }

    private static boolean loadingHeadroom() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()) >= (24L << 20);
    }

    /** Only unused native allocations are discarded; queued audio remains owned by its player. */
    private static void reclaimFreePools() {
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        for (Object control : observed()) {
            try {
                Object allocator = read(control, "allocator");
                if (seen.put(allocator, true) != null) continue;
                synchronized (allocator) {
                    if (((Number) read(allocator, "availableCount")).intValue() == 0) continue;
                    int target = ((Number) read(allocator, "targetBufferSize")).intValue();
                    Method resize =
                            allocator
                                    .getClass()
                                    .getDeclaredMethod("setTargetBufferSize", int.class);
                    resize.setAccessible(true);
                    try {
                        resize.invoke(allocator, 0);
                    } finally {
                        resize.invoke(allocator, target);
                    }
                }
            } catch (Exception | LinkageError unavailable) {
                // A loading decision can still refuse growth without changing an unknown pool.
            }
        }
    }

    private static void cancelAnalysis() {
        synchronized (controls) {
            for (AnalysisLease lease : analysisLeases) lease.cancelled = true;
        }
    }

    private static long loadingBudget() {
        synchronized (controls) {
            long budget = Math.max(0, heapBytes.getAsLong() / 2);
            if (residentHolds > 0)
                budget = Math.min(budget, Math.max(16L << 20, heapBytes.getAsLong() / 8));
            for (AnalysisLease lease : analysisLeases) budget = Math.min(budget, lease.budget);
            return budget;
        }
    }

    /** The native loading decision keeps its time policy after a bounded startup allowance. */
    public static boolean allowLoading(Object control, Object parameters) {
        if (control == null) {
            if (!analysisActive() && !residentActive()) return true;
            cancelAnalysis();
            return false;
        }
        synchronized (controls) {
            gateObserved = true;
            if (!observedControls.containsKey(control)) {
                observedControls.put(control, true);
                configurationEpoch++;
                cancelAnalysis();
                controls.replaceAll((owner, previous) -> -1);
            }
        }
        tick(control);
        boolean leased = analysisActive();
        boolean resident = residentActive();
        if (!enabled && !leased && !resident) return true;
        try {
            boolean starting =
                    ((Number) read(parameters, "bufferedDurationUs")).longValue() < READY_US;
            if (starting && !leased && !resident) return true;
            Object own = read(control, "allocator");
            if (resident && !loadingHeadroom()) {
                reclaimFreePools();
                if (!loadingHeadroom()) return false;
            }
            long[] ownUsage = allocation(own);
            if (leased && ownUsage[1] > 0) return true;
            if (ownUsage[0] >= allowance(control)) {
                if (starting && leased) cancelAnalysis();
                return false;
            }
            // Reusing an owned block sustains read-ahead without increasing the live heap.
            if (ownUsage[1] > 0) return true;
            boolean allowed = retained(observed()) < loadingBudget();
            if (!allowed && resident) {
                reclaimFreePools();
                allowed = loadingHeadroom() && retained(observed()) < loadingBudget();
            }
            if (!allowed && starting && leased) cancelAnalysis();
            return allowed;
        } catch (Exception | LinkageError unavailable) {
            HostLog.d("Steady playback couldn't inspect the loading allowance");
            if (analysisActive() || residentActive()) {
                cancelAnalysis();
                return false;
            }
            return true;
        }
    }

    private static void apply(Object control, int bytes) {
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
