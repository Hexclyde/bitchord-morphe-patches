package app.bitchord.telegram;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded peak-only cache. Keys include the exact rendition, duration and waveform format. */
final class TransitionWaveCache {
    interface Reader {
        float[][] read() throws Exception;
    }

    static final class Window {
        final double from, to;
        final float[][] bands;

        Window(double from, double to, float[][] bands) {
            this.from = from;
            this.to = to;
            this.bands = bands;
        }

        boolean covers(double start, double end) {
            return from <= start + .000001 && to >= end - .000001;
        }
    }

    private static final Map<String, Window> memory =
            new LinkedHashMap<String, Window>(32, .75f, true) {
                protected boolean removeEldestEntry(Map.Entry<String, Window> entry) {
                    return size() > 32;
                }
            };

    private static final class Work extends FutureTask<Window> {
        final String identity;
        final double from, to;

        /** Speculative preload work; a visible editor may cancel it. */
        volatile boolean background;

        Work(String identity, double from, double to, Callable<Window> task) {
            super(task);
            this.identity = identity;
            this.from = from;
            this.to = to;
        }

        boolean covers(String identity, double start, double end) {
            return this.identity.equals(identity) && from <= start + .000001 && to >= end - .000001;
        }
    }

    private static final ConcurrentHashMap<String, Work> running = new ConcurrentHashMap<>();
    private static final int ATTEMPTS = 3, PENDING = 4;
    private static final Set<String> pending = ConcurrentHashMap.newKeySet();
    private static final ExecutorService preloads =
            Executors.newSingleThreadExecutor(
                    task -> {
                        Thread t =
                                new Thread(
                                        () -> {
                                            android.os.Process.setThreadPriority(
                                                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
                                            task.run();
                                        },
                                        "automix-peak-prefetch");
                        t.setDaemon(true);
                        return t;
                    });
    private static volatile String requested;
    private static String waiting, failed;
    private static int failures;
    private static long checked;

    static String identity(TransitionRuntime.Pair pair, int side) {
        return TransitionRuntime.key(
                "waveform-v1",
                TransitionRuntime.rendition(
                        side == 0 ? pair.outgoing : pair.incoming,
                        side == 0 ? pair.outgoingId : pair.incomingId,
                        side == 0 ? pair.outgoingDuration : pair.incomingDuration));
    }

    /** The decoded interval drawn around a transition: its lead-in, overlap and tail. */
    static double[] window(TransitionRuntime.Pair pair, TransitionEdit edit, int side) {
        double start = side == 0 ? edit.start(pair.outgoingDuration) : edit.incomingCue,
                duration = side == 0 ? pair.outgoingDuration : pair.incomingDuration;
        return new double[] {
            Math.max(0, Math.floor((start - edit.overlap * .22) / 4) * 4),
            Math.min(duration, Math.ceil((start + edit.overlap * 1.22) / 4) * 4)
        };
    }

    static Window peek(String identity, double from, double to) {
        synchronized (memory) {
            for (Map.Entry<String, Window> e : memory.entrySet())
                if (e.getKey().startsWith(identity + "-") && e.getValue().covers(from, to))
                    return e.getValue();
        }
        return null;
    }

    static Window load(String identity, double from, double to, Reader reader) throws Exception {
        return load(identity, from, to, reader, false);
    }

    private static Window load(
            String identity, double from, double to, Reader reader, boolean background)
            throws Exception {
        Window hit = peek(identity, from, to);
        if (hit != null) return hit;
        // A wider decode already in flight for the same rendition is shared, not repeated.
        for (Work other : running.values())
            if (other.covers(identity, from, to)) {
                Window shared = shared(other);
                if (shared != null && shared.covers(from, to)) return shared;
                break;
            }
        String key =
                identity + "-" + TransitionRuntime.key(Double.toString(from), Double.toString(to));
        Work work =
                new Work(
                        identity,
                        from,
                        to,
                        () -> {
                            Window disk = read(identity, from, to);
                            if (disk != null) {
                                synchronized (memory) {
                                    memory.put(key, disk);
                                }
                                return disk;
                            }
                            float[][] bands = reader.read();
                            if (Thread.currentThread().isInterrupted() || !valid(bands))
                                return null;
                            Window result = new Window(from, to, bands);
                            synchronized (memory) {
                                memory.put(key, result);
                            }
                            write(key, result);
                            return result;
                        });
        work.background = background;
        Work other = running.putIfAbsent(key, work);
        if (other != null) {
            Window shared = shared(other);
            if (shared != null && shared.covers(from, to)) return shared;
            if (Thread.currentThread().isInterrupted()) return null;
            // The other decode ended without peaks; this caller makes one attempt of its own.
            running.remove(key, other);
            Window late = peek(identity, from, to);
            if (late != null) return late;
            Work again = running.putIfAbsent(key, work);
            if (again != null) return shared(again);
        }
        try {
            work.run();
            return work.get();
        } finally {
            running.remove(key, work);
        }
    }

    /**
     * A visible window takes priority: queued preloads are dropped and running speculative decodes
     * that would not serve it are cancelled. A decode that covers it stays shared.
     */
    static void yieldTo(String identity, double from, double to) {
        synchronized (pending) {
            requested = null;
        }
        for (Work work : running.values())
            if (work.background && !work.covers(identity, from, to)) work.cancel(true);
    }

    /** Another caller's cancelled or failed decode leaves this caller free to decode itself. */
    private static Window shared(Work other) throws InterruptedException {
        try {
            return other.get();
        } catch (ExecutionException | CancellationException unavailable) {
            return null;
        }
    }

    private static File directory() {
        android.content.Context app = SoundExtras.app();
        if (app == null) return null;
        File dir = new File(app.getCacheDir(), "automix_waveforms");
        return dir.isDirectory() || dir.mkdirs() ? dir : null;
    }

    private static boolean valid(float[][] bands) {
        if (bands == null
                || bands.length != 3
                || bands[0] == null
                || bands[0].length < 2
                || bands[0].length > 4096) return false;
        for (float[] band : bands) {
            if (band == null || band.length != bands[0].length) return false;
            for (float v : band) if (!Float.isFinite(v) || v < 0 || v > 1.001) return false;
        }
        return true;
    }

    private static Window read(String identity, double from, double to) {
        File dir = directory();
        File[] files =
                dir == null
                        ? null
                        : dir.listFiles(
                                (d, n) -> n.startsWith(identity + "-") && n.endsWith(".peaks"));
        if (files == null) return null;
        for (File f : files)
            try (DataInputStream in =
                    new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
                if (f.length() > 50000 || in.readInt() != 0x41574d31) continue;
                double a = in.readDouble(), b = in.readDouble();
                int n = in.readInt();
                if (!Double.isFinite(a)
                        || !Double.isFinite(b)
                        || a < 0
                        || b <= a
                        || n < 2
                        || n > 4096
                        || f.length() != 24L + 12L * n) continue;
                if (a > from + .000001 || b < to - .000001) continue;
                float[][] bands = new float[3][n];
                for (float[] band : bands) for (int i = 0; i < n; i++) band[i] = in.readFloat();
                if (valid(bands)) return new Window(a, b, bands);
            } catch (IOException invalid) {
                /* A corrupt evictable cache never blocks decoding. */
            }
        return null;
    }

    private static void write(String key, Window window) {
        File dir = directory();
        if (dir == null) return;
        File temp = new File(dir, key + ".tmp"), target = new File(dir, key + ".peaks");
        try {
            try (DataOutputStream out =
                    new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temp)))) {
                out.writeInt(0x41574d31);
                out.writeDouble(window.from);
                out.writeDouble(window.to);
                out.writeInt(window.bands[0].length);
                for (float[] band : window.bands) for (float value : band) out.writeFloat(value);
            }
            if (!temp.renameTo(target)) temp.delete();
            File[] files = dir.listFiles((d, n) -> n.endsWith(".peaks"));
            if (files != null && files.length > 64) {
                Arrays.sort(files, Comparator.comparingLong(File::lastModified));
                for (int i = 0; i < files.length - 64; i++) files[i].delete();
            }
        } catch (IOException unavailable) {
            temp.delete();
        }
    }

    /** Called on the player thread while its main surface is visible; only peaks are retained. */
    static void prefetch() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - checked < 3000) return;
        checked = now;
        if (!AutomixAccess.enabled()) return;
        try {
            TransitionRuntime.Pair pair = TransitionRuntime.pair();
            if (pair == null) return;
            TransitionStore store = TransitionStore.get();
            TransitionStore.Entry stored = store.lookup(pair);
            if (stored.known) {
                warm(pair, stored);
                return;
            }
            String selection = selection(pair);
            if (selection.equals(waiting)) return;
            waiting = selection;
            // The first header render usually precedes the saved-transition read.
            store.read(
                    pair,
                    entry -> {
                        if (!selection.equals(waiting)) return;
                        waiting = null;
                        try {
                            if (!AutomixAccess.enabled() || AutomixAccess.stopped()) return;
                            TransitionRuntime.Pair current = TransitionRuntime.pair();
                            if (current != null && selection.equals(selection(current)))
                                warm(current, entry);
                        } catch (Exception unavailable) {
                        }
                    });
        } catch (Exception unavailable) {
            /* Playback and editor opening never wait for prefetch. */
        }
    }

    private static String selection(TransitionRuntime.Pair pair) {
        return pair.key + "\n" + pair.recording();
    }

    private static void warm(TransitionRuntime.Pair pair, TransitionStore.Entry stored) {
        TransitionEdit edit = stored.error == null ? stored.edit() : null;
        if (edit == null) edit = TransitionRuntime.automaticDraft(pair);
        String stamp =
                pair.recording()
                        + edit.start(pair.outgoingDuration)
                        + ":"
                        + edit.incomingCue
                        + ":"
                        + edit.overlap;
        synchronized (pending) {
            if (stamp.equals(requested) || (stamp.equals(failed) && failures >= ATTEMPTS)) return;
            requested = stamp;
        }
        for (int side = 0; side < 2; side++) {
            double[] range = window(pair, edit, side);
            double from = range[0], to = range[1];
            String id = identity(pair, side), job = id + "-" + from + "-" + to;
            if (peek(id, from, to) != null) continue;
            if (pending.contains(job)) continue;
            if (pending.size() >= PENDING || !pending.add(job)) {
                synchronized (pending) {
                    if (stamp.equals(requested)) requested = null;
                }
                continue;
            }
            TransitionRuntime.WaveRequest request =
                    TransitionRuntime.waveform(pair, side, from, to);
            if (request == null) {
                pending.remove(job);
                retry(stamp);
                continue;
            }
            preloads.execute(
                    () -> {
                        try {
                            // A newer selection supersedes queued work before it decodes.
                            if (!stamp.equals(requested)) return;
                            if (load(id, from, to, request::read, true) == null) retry(stamp);
                        } catch (Exception unavailable) {
                            retry(stamp);
                        } finally {
                            pending.remove(job);
                        }
                    });
        }
    }

    /** A failed window is tried again on a later header refresh, a bounded number of times. */
    private static void retry(String stamp) {
        synchronized (pending) {
            if (!stamp.equals(requested)) return;
            requested = null;
            failures = stamp.equals(failed) ? failures + 1 : 1;
            failed = stamp;
        }
    }

    static void clearMemory() {
        synchronized (memory) {
            memory.clear();
        }
    }
}
