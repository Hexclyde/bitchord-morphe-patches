package app.bitchord.telegram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Who may use the device while a song is playing. From most to least urgent: the current song
 * (short, renewable urgent windows), the current song's quality upgrade, an interactive transition
 * preview, preparing the next song, AutoMix analysis, then indexing and other background work.
 *
 * <p>Nothing here blocks or touches Android; callers poll, and a worker that must wait uses {@link
 * #awaitBackground}. Every lease is bounded: a holder that never closes only delays lower tiers
 * until its time limit, and renewing one identity's lease replaces it and cannot extend it past
 * {@link #MAX_HOLD_NANOS}. The analysis of the current or next transition is let through after
 * {@link #STARVATION_NANOS} behind preparation, so renewed next-song leases cannot starve it.
 */
public final class PlaybackPriority {
    private PlaybackPriority() {}

    /** Longest an upgrade lease holds off lower tiers without being closed. */
    static final long UPGRADE_TTL_NANOS = 120_000_000_000L;

    /** Longest one urgent playback window lasts. */
    static final long URGENT_MAX_NANOS = 30_000_000_000L;

    /** Quiet period after higher-priority work ends, so work does not restart between steps. */
    static final long SETTLE_NANOS = 750_000_000L;

    private static final long WAIT_SLICE_NANOS = 100_000_000L;

    static LongSupplier clock = System::nanoTime;

    private static final Object lock = new Object();
    private static final List<Lease> upgrades = new ArrayList<>();
    private static final List<Lease> preparations = new ArrayList<>();
    private static final List<Lease> previews = new ArrayList<>();
    private static final List<Lease> analyses = new ArrayList<>();
    // First and latest grant of a renewed lease, per tier and identity, so renewal has a limit.
    private static final Map<String, long[]> chains = bounded();
    // When each essential analysis was first held back, and the one let through right now.
    private static final Map<String, long[]> waiting = bounded();
    private static String letThrough;
    private static long letThroughUntil;
    private static long quietAt;
    private static boolean quiet;
    private static boolean urgent;
    private static long urgentUntil;

    /** Most leases of one kind kept at once; a new one forgets the oldest beyond this. */
    static final int MAX_LEASES = 16;

    private static final int MAX_LISTENERS = 4;
    private static final List<Runnable> listeners = new ArrayList<>();

    /**
     * Registers a hook run on the caller's thread when higher-priority work starts (an upgrade,
     * next preparation or urgent window). It must be cheap and never block, because the caller may
     * be the player or UI thread. Registering the same hook twice is ignored; at most four are
     * kept, and they survive {@link #reset}.
     */
    public static void onPreempt(Runnable hook) {
        synchronized (lock) {
            if (!listeners.contains(hook) && listeners.size() < MAX_LISTENERS) listeners.add(hook);
        }
    }

    private static void preempted() {
        Runnable[] hooks;
        synchronized (lock) {
            hooks = listeners.toArray(new Runnable[0]);
        }
        for (Runnable hook : hooks)
            try {
                hook.run();
            } catch (RuntimeException ignored) {
                // A faulty hook must not break playback.
            }
    }

    private static Map<String, long[]> bounded() {
        return new LinkedHashMap<String, long[]>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, long[]> eldest) {
                return size() > 2 * MAX_LEASES;
            }
        };
    }

    private static Lease grant(String tier, String identity, long now, List<Lease> owner) {
        long expires = now + UPGRADE_TTL_NANOS;
        if (owner != analyses) {
            // A renewal replaces the holder's earlier lease instead of piling up beside it.
            for (int i = owner.size() - 1; i >= 0; i--)
                if (owner.get(i).identity.equals(identity)) owner.remove(i).closed = true;
            String key = tier + identity;
            long[] chain = chains.get(key);
            if (chain == null || now - chain[1] - UPGRADE_TTL_NANOS > 0)
                chain = new long[] {now, now};
            chain[1] = now;
            chains.put(key, chain);
            long limit = chain[0] + MAX_HOLD_NANOS;
            if (limit - expires < 0) expires = limit;
        }
        Lease lease = new Lease(identity, expires, owner);
        // A renewal past its limit holds nothing.
        if (expires - now <= 0) {
            lease.closed = true;
            return lease;
        }
        // Past the cap the oldest lease is forgotten (its later close is a no-op), never the new
        // one.
        if (owner.size() >= MAX_LEASES) owner.remove(0).closed = true;
        owner.add(lease);
        return lease;
    }

    private static final class Lease implements AutoCloseable {
        final String identity;
        final long expires;
        private final List<Lease> owner;
        private boolean closed;

        Lease(String identity, long expires, List<Lease> owner) {
            this.identity = identity;
            this.expires = expires;
            this.owner = owner;
        }

        @Override
        public void close() {
            synchronized (lock) {
                if (closed) return;
                closed = true;
                // An expired lease already settled when it was dropped.
                if (owner.remove(this) && owner != analyses) settle(clock.getAsLong());
                lock.notifyAll();
            }
        }
    }

    /** Holds lower tiers off for the current song's quality upgrade until closed or expired. */
    public static AutoCloseable upgrade(String identity) {
        Lease lease;
        synchronized (lock) {
            long now = clock.getAsLong();
            prune(now);
            lease = grant("u", identity, now, upgrades);
        }
        preempted();
        return lease;
    }

    /** Holds AutoMix and indexing off while the next song is prepared. */
    public static AutoCloseable nextPreparation(String identity) {
        Lease lease;
        synchronized (lock) {
            long now = clock.getAsLong();
            prune(now);
            lease = grant("p", identity, now, preparations);
        }
        preempted();
        return lease;
    }

    static AutoCloseable analysis() {
        synchronized (lock) {
            long now = clock.getAsLong();
            prune(now);
            return grant("a", "analysis", now, analyses);
        }
    }

    public static boolean upgradeActive() {
        synchronized (lock) {
            prune(clock.getAsLong());
            return !upgrades.isEmpty();
        }
    }

    /** The current song needs the device for this long; windows renew, and never stack up. */
    public static void playbackUrgent(long millis) {
        if (millis <= 0) return;
        synchronized (lock) {
            long now = clock.getAsLong();
            long limit = URGENT_MAX_NANOS / 1_000_000L;
            long span = millis >= limit ? URGENT_MAX_NANOS : millis * 1_000_000L;
            long end = now + span + SETTLE_NANOS;
            if (!urgent || end - urgentUntil > 0) {
                urgent = true;
                urgentUntil = end;
            }
            if (!quiet || end - quietAt > 0) {
                quiet = true;
                quietAt = end;
            }
        }
        preempted();
    }

    public static boolean verificationAllowed() {
        synchronized (lock) {
            return !urgent || urgentUntil - clock.getAsLong() <= 0;
        }
    }

    public static boolean preparationAllowed() {
        synchronized (lock) {
            long now = clock.getAsLong();
            prune(now);
            return upgrades.isEmpty()
                    && previews.isEmpty()
                    && !letThroughNow(now)
                    && !settling(now);
        }
    }

    /** Indexing and other background work: below next-song preparation, AutoMix and the upgrade. */
    public static boolean backgroundAllowed() {
        synchronized (lock) {
            long now = clock.getAsLong();
            prune(now);
            return upgrades.isEmpty()
                    && previews.isEmpty()
                    && preparations.isEmpty()
                    && analyses.isEmpty()
                    && !settling(now);
        }
    }

    /** AutoMix yields to preparation and playback, but not to another analysis. */
    public static boolean analysisAllowed() {
        synchronized (lock) {
            long now = clock.getAsLong();
            prune(now);
            return analysisFree(now);
        }
    }

    private static boolean analysisFree(long now) {
        if (!upgrades.isEmpty() || !previews.isEmpty()) return false;
        // A let-through analysis ignores preparation, never the current song.
        if (letThroughNow(now)) return !urgentNow(now);
        return preparations.isEmpty() && !settling(now);
    }

    private static boolean letThroughNow(long now) {
        return letThrough != null && letThroughUntil - now > 0;
    }

    private static boolean urgentNow(long now) {
        return urgent && urgentUntil - now > 0;
    }

    /**
     * Waits on a worker thread until {@link #backgroundAllowed}. Returns false if cancelled or
     * interrupted first. Never call this from the UI or a TDLib thread.
     */
    public static boolean awaitBackground(BooleanSupplier cancelled) {
        synchronized (lock) {
            while (true) {
                if (cancelled.getAsBoolean()) return false;
                long now = clock.getAsLong();
                prune(now);
                if (upgrades.isEmpty()
                        && previews.isEmpty()
                        && preparations.isEmpty()
                        && analyses.isEmpty()
                        && !settling(now)) return true;
                try {
                    lock.wait(WAIT_SLICE_NANOS / 1_000_000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    private static boolean settling(long now) {
        return quiet && quietAt - now > 0;
    }

    private static void settle(long at) {
        long end = at + SETTLE_NANOS;
        if (!quiet || end - quietAt > 0) {
            quiet = true;
            quietAt = end;
        }
    }

    /** Drops leases past their limit; their holders may still close them later. */
    private static void prune(long now) {
        expire(upgrades, now);
        expire(preparations, now);
        expire(previews, now);
        expire(analyses, now);
        if (letThrough != null && letThroughUntil - now <= 0) letThrough = null;
        // A held-back analysis nobody has asked about for two minutes is forgotten.
        waiting.values().removeIf(held -> now - held[1] - UPGRADE_TTL_NANOS > 0);
    }

    private static void expire(List<Lease> leases, long now) {
        for (int i = leases.size() - 1; i >= 0; i--) {
            Lease lease = leases.get(i);
            if (lease.expires - now <= 0) {
                leases.remove(i);
                if (leases != analyses) settle(lease.expires);
            }
        }
    }

    /** Longest an essential analysis waits behind renewed leases before it is let through. */
    static final long STARVATION_NANOS = 20_000_000_000L;

    /** Longest one let-through analysis holds off next-song preparation. */
    static final long ESSENTIAL_RUN_NANOS = 90_000_000_000L;

    /** Longest one identity can keep a tier held by renewing its lease. */
    static final long MAX_HOLD_NANOS = 600_000_000_000L;

    /**
     * Holds AutoMix analysis and indexing off while a transition preview is prepared and heard.
     * Source preparation and verification keep running, as the preview may need them. It ranks
     * below the current song and its upgrade, which it never blocks.
     */
    public static AutoCloseable preview(String identity) {
        Lease lease;
        synchronized (lock) {
            long now = clock.getAsLong();
            prune(now);
            lease = grant("v", identity, now, previews);
        }
        preempted();
        return lease;
    }

    static boolean previewActive() {
        synchronized (lock) {
            prune(clock.getAsLong());
            return !previews.isEmpty();
        }
    }

    /**
     * Analysis of one recording. An essential one (the current or next transition) that has been
     * held back for {@link #STARVATION_NANOS} is let through for {@link #ESSENTIAL_RUN_NANOS},
     * during which next-song preparation waits; playback, upgrades and previews still win.
     */
    static boolean analysisAllowed(String identity, boolean essential) {
        synchronized (lock) {
            long now = clock.getAsLong();
            prune(now);
            if (analysisFree(now)) return true;
            if (!essential) return false;
            long[] held = waiting.get(identity);
            if (held == null) waiting.put(identity, held = new long[] {now, now});
            held[1] = now;
            if (now - held[0] - STARVATION_NANOS < 0
                    || !upgrades.isEmpty()
                    || !previews.isEmpty()
                    || urgentNow(now)) return false;
            letThrough = identity;
            letThroughUntil = now + ESSENTIAL_RUN_NANOS;
            // Held back again only after the run, and for the full bound before the next one.
            held[0] = letThroughUntil;
            return true;
        }
    }

    /** The analysis ended for good (measured, failed or cancelled), not because it yielded. */
    static void analysisFinished(String identity) {
        synchronized (lock) {
            waiting.remove(identity);
            if (identity.equals(letThrough)) letThrough = null;
            lock.notifyAll();
        }
    }

    /** Leases currently held across all tiers; for tests. */
    static int leaseCount() {
        synchronized (lock) {
            return upgrades.size() + preparations.size() + previews.size() + analyses.size();
        }
    }

    /** Forgets everything; for tests. */
    static void reset() {
        synchronized (lock) {
            upgrades.clear();
            preparations.clear();
            previews.clear();
            analyses.clear();
            chains.clear();
            waiting.clear();
            letThrough = null;
            quiet = false;
            urgent = false;
            clock = System::nanoTime;
            lock.notifyAll();
        }
    }
}
