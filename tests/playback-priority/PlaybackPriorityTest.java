package app.bitchord.telegram;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic checks of the priority tiers, on a fake clock. */
public final class PlaybackPriorityTest {
    static final long MS = 1_000_000L, S = 1_000 * MS;
    static final AtomicLong now = new AtomicLong(1_000 * S);

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    static void reset() {
        PlaybackPriority.reset();
        PlaybackPriority.clock = now::get;
    }

    static void upgradeBlocksUntilClosedAndSettled() throws Exception {
        reset();
        check(PlaybackPriority.backgroundAllowed() && PlaybackPriority.analysisAllowed(), "idle");
        AutoCloseable lease = PlaybackPriority.upgrade("song");
        check(PlaybackPriority.upgradeActive(), "active");
        check(!PlaybackPriority.backgroundAllowed(), "background held");
        check(!PlaybackPriority.analysisAllowed(), "analysis held");
        lease.close();
        check(!PlaybackPriority.upgradeActive(), "released");
        check(!PlaybackPriority.backgroundAllowed(), "settle grace");
        now.addAndGet(PlaybackPriority.SETTLE_NANOS + 1);
        check(PlaybackPriority.backgroundAllowed(), "after grace");
        // Closing again must not start another grace period.
        lease.close();
        check(PlaybackPriority.backgroundAllowed(), "idempotent close");
    }

    static void overlappingUpgradesAndExpiry() throws Exception {
        reset();
        AutoCloseable a = PlaybackPriority.upgrade("a"), b = PlaybackPriority.upgrade("b");
        a.close();
        check(PlaybackPriority.upgradeActive(), "second still holds");
        now.addAndGet(PlaybackPriority.UPGRADE_TTL_NANOS - 1);
        check(PlaybackPriority.upgradeActive(), "inside ttl");
        now.addAndGet(1);
        check(!PlaybackPriority.upgradeActive(), "ttl frees a lease nobody closed");
        check(!PlaybackPriority.backgroundAllowed(), "grace after expiry");
        now.addAndGet(PlaybackPriority.SETTLE_NANOS);
        check(PlaybackPriority.backgroundAllowed(), "allowed after expiry grace");
        b.close();
        check(PlaybackPriority.backgroundAllowed(), "late close of an expired lease is harmless");
    }

    static void urgentIsBoundedAndMonotonic() {
        reset();
        PlaybackPriority.playbackUrgent(0);
        PlaybackPriority.playbackUrgent(-5);
        check(PlaybackPriority.backgroundAllowed(), "no window for non-positive spans");
        PlaybackPriority.playbackUrgent(Long.MAX_VALUE);
        now.addAndGet(PlaybackPriority.URGENT_MAX_NANOS - 1);
        check(!PlaybackPriority.backgroundAllowed(), "capped window still open");
        now.addAndGet(PlaybackPriority.SETTLE_NANOS + 1);
        check(PlaybackPriority.backgroundAllowed(), "capped window ended");
        PlaybackPriority.playbackUrgent(5_000);
        PlaybackPriority.playbackUrgent(100);
        now.addAndGet(5_000 * MS);
        check(
                !PlaybackPriority.backgroundAllowed(),
                "a shorter window never shortens a longer one");
        now.addAndGet(PlaybackPriority.SETTLE_NANOS);
        check(PlaybackPriority.backgroundAllowed(), "longer window ended");
    }

    static void nextPreparationOutranksAnalysisAndIndexing() throws Exception {
        reset();
        AutoCloseable next = PlaybackPriority.nextPreparation("next");
        check(!PlaybackPriority.backgroundAllowed(), "indexing yields to next prep");
        check(!PlaybackPriority.analysisAllowed(), "analysis yields to next prep");
        next.close();
        check(!PlaybackPriority.backgroundAllowed(), "and settles");
        now.addAndGet(PlaybackPriority.SETTLE_NANOS);
        check(PlaybackPriority.analysisAllowed(), "analysis resumes");
        check(PlaybackPriority.backgroundAllowed(), "indexing resumes");
        // A waiting indexer is released by the same event.
        AutoCloseable again = PlaybackPriority.nextPreparation("again");
        Thread worker = new Thread(() -> result = PlaybackPriority.awaitBackground(() -> false));
        result = null;
        worker.start();
        Thread.sleep(150);
        check(result == null, "indexer waits during next prep");
        again.close();
        now.addAndGet(PlaybackPriority.SETTLE_NANOS);
        worker.join(2_000);
        check(Boolean.TRUE.equals(result), "indexer released");
    }

    static void leasesAreCappedAndOldestIsForgotten() throws Exception {
        reset();
        AutoCloseable[] leases = new AutoCloseable[PlaybackPriority.MAX_LEASES + 4];
        for (int i = 0; i < leases.length; i++) leases[i] = PlaybackPriority.upgrade("song" + i);
        check(PlaybackPriority.upgradeActive(), "held");
        // The first four were forgotten; closing them changes nothing and is safe.
        for (int i = 0; i < 4; i++) leases[i].close();
        check(PlaybackPriority.upgradeActive(), "newest still held");
        for (int i = 4; i < leases.length; i++) leases[i].close();
        check(!PlaybackPriority.upgradeActive(), "all closed, none leaked");
        for (AutoCloseable lease : leases) lease.close();
    }

    static void preemptionHooksRunOnEveryHigherPriorityStart() throws Exception {
        reset();
        int[] calls = {0};
        Runnable hook = () -> calls[0]++;
        PlaybackPriority.onPreempt(hook);
        PlaybackPriority.onPreempt(hook);
        PlaybackPriority.onPreempt(
                () -> {
                    throw new IllegalStateException("faulty hook");
                });
        PlaybackPriority.upgrade("a").close();
        PlaybackPriority.nextPreparation("b").close();
        PlaybackPriority.playbackUrgent(100);
        PlaybackPriority.playbackUrgent(0);
        check(calls[0] == 3, "once each, deduplicated, faulty hook harmless: " + calls[0]);
    }

    static void awaitBackgroundIsCancellableAndWakes() throws Exception {
        reset();
        AutoCloseable lease = PlaybackPriority.upgrade("song");
        AtomicBoolean cancel = new AtomicBoolean();
        check(!awaitAfter(cancel, 50), "cancelled wait returns false");
        Thread worker = new Thread(() -> result = PlaybackPriority.awaitBackground(() -> false));
        result = null;
        worker.start();
        Thread.sleep(150);
        check(result == null && worker.isAlive(), "still waiting during upgrade");
        lease.close();
        now.addAndGet(PlaybackPriority.SETTLE_NANOS);
        worker.join(2_000);
        check(Boolean.TRUE.equals(result), "released waiter returns true");
        PlaybackPriority.upgrade("again");
        Thread interrupted =
                new Thread(() -> result = PlaybackPriority.awaitBackground(() -> false));
        result = null;
        interrupted.start();
        Thread.sleep(50);
        interrupted.interrupt();
        interrupted.join(2_000);
        check(Boolean.FALSE.equals(result), "interrupt ends the wait");
    }

    static void playbackOutranksVerificationAndPreparation() throws Exception {
        reset();
        AutoCloseable next = PlaybackPriority.nextPreparation("next");
        check(PlaybackPriority.preparationAllowed(), "preparation does not block itself");
        check(PlaybackPriority.verificationAllowed(), "preparation does not block verification");
        AutoCloseable upgrade = PlaybackPriority.upgrade("current");
        check(!PlaybackPriority.preparationAllowed(), "current upgrade preempts next");
        check(
                PlaybackPriority.verificationAllowed(),
                "upgrade does not block its own verification");
        PlaybackPriority.playbackUrgent(2000);
        check(!PlaybackPriority.verificationAllowed(), "rebuffer preempts verification");
        now.addAndGet(2000 * MS + PlaybackPriority.SETTLE_NANOS);
        check(
                PlaybackPriority.verificationAllowed(),
                "verification resumes after playback settles");
        check(!PlaybackPriority.preparationAllowed(), "upgrade still outranks next");
        upgrade.close();
        now.addAndGet(PlaybackPriority.SETTLE_NANOS);
        check(PlaybackPriority.preparationAllowed(), "next resumes");
        next.close();
        now.addAndGet(PlaybackPriority.SETTLE_NANOS);
        check(PlaybackPriority.backgroundAllowed(), "background eventually resumes");
    }

    static void analysisPausesIndexingButNeverBlocksPreparation() throws Exception {
        reset();
        try (AutoCloseable analysis = PlaybackPriority.analysis()) {
            check(!PlaybackPriority.backgroundAllowed(), "indexing waits for analysis");
            check(PlaybackPriority.analysisAllowed(), "analysis does not cancel itself");
            check(PlaybackPriority.preparationAllowed(), "preparation preempts analysis");
            check(PlaybackPriority.verificationAllowed(), "verification preempts analysis");
        }
        check(PlaybackPriority.backgroundAllowed(), "indexing resumes when decoder releases");
    }

    static volatile Boolean result;

    static boolean awaitAfter(AtomicBoolean cancel, long millis) throws Exception {
        Thread timer =
                new Thread(
                        () -> {
                            try {
                                Thread.sleep(millis);
                            } catch (InterruptedException ignored) {
                            }
                            cancel.set(true);
                        });
        timer.start();
        return PlaybackPriority.awaitBackground(cancel::get);
    }

    public static void main(String[] args) throws Exception {
        upgradeBlocksUntilClosedAndSettled();
        overlappingUpgradesAndExpiry();
        urgentIsBoundedAndMonotonic();
        nextPreparationOutranksAnalysisAndIndexing();
        leasesAreCappedAndOldestIsForgotten();
        preemptionHooksRunOnEveryHigherPriorityStart();
        awaitBackgroundIsCancellableAndWakes();
        playbackOutranksVerificationAndPreparation();
        analysisPausesIndexingButNeverBlocksPreparation();
        PlaybackPriority.reset();
        System.out.println("PlaybackPriorityTest: 9 passed");
    }
}
